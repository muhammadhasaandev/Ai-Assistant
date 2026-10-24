package com.hassan.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Accessibility service: this is what actually reads the screen and taps / types
 * inside other apps (WhatsApp, YouTube, anything). It exposes simple helpers that
 * AssistantService calls to carry out the steps Grok decides on.
 */
class ControlService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* polled on demand */ }
    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
    }

    // ---- screen reading -------------------------------------------------

    /** A short text description of what is currently on screen, for Grok. */
    fun describeScreen(maxItems: Int = 60): String {
        val root = rootInActiveWindow ?: return "(screen is empty or not readable)"
        val sb = StringBuilder()
        sb.append("Current app package: ").append(root.packageName).append('\n')
        val seen = HashSet<String>()
        val lines = ArrayList<String>()
        collect(root, lines, seen)
        root.recycle()
        lines.take(maxItems).forEach { sb.append(it).append('\n') }
        if (lines.size > maxItems) sb.append("... (").append(lines.size - maxItems).append(" more items)\n")
        return sb.toString()
    }

    private fun collect(node: AccessibilityNodeInfo?, out: ArrayList<String>, seen: HashSet<String>) {
        if (node == null) return
        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        val label = when {
            !text.isNullOrEmpty() -> text
            !desc.isNullOrEmpty() -> desc
            else -> null
        }
        if (label != null && label.length in 1..120) {
            val tag = buildString {
                append('"').append(label).append('"')
                if (node.isClickable) append(" [clickable]")
                if (node.isEditable) append(" [input]")
            }
            if (seen.add(tag)) out.add(tag)
        }
        for (i in 0 until node.childCount) {
            collect(node.getChild(i), out, seen)
        }
    }

    // ---- actions --------------------------------------------------------

    /** Tap the first clickable element whose text/description contains [query] (case-insensitive). */
    fun tapByText(query: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findNode(root) { n ->
            val t = (n.text?.toString() ?: "") + " " + (n.contentDescription?.toString() ?: "")
            t.contains(query, ignoreCase = true)
        }
        val result = node?.let { clickNode(it) } ?: false
        root.recycle()
        return result
    }

    /** Type [value] into the focused or first editable field. */
    fun typeText(value: String): Boolean {
        val root = rootInActiveWindow ?: return false
        var field = findNode(root) { it.isEditable && it.isFocused }
        if (field == null) field = findNode(root) { it.isEditable }
        val ok = field?.let {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            }
            it.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            it.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } ?: false
        root.recycle()
        return ok
    }

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun openRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    fun scrollForward(): Boolean = scroll(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    fun scrollBackward(): Boolean = scroll(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)

    private fun scroll(action: Int): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findNode(root) { it.isScrollable }
        val ok = node?.performAction(action) ?: false
        root.recycle()
        return ok
    }

    /** Tap at absolute screen coordinates (used as a fallback). */
    fun tapAt(x: Int, y: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        val latch = CountDownLatch(1)
        var ok = false
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { ok = true; latch.countDown() }
            override fun onCancelled(d: GestureDescription?) { latch.countDown() }
        }, Handler(Looper.getMainLooper()))
        if (!dispatched) return false
        latch.await(2, TimeUnit.SECONDS)
        return ok
    }

    private fun clickNode(start: AccessibilityNodeInfo): Boolean {
        var node: AccessibilityNodeInfo? = start
        // walk up until we find something clickable
        while (node != null) {
            if (node.isClickable) {
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            node = node.parent
        }
        // fall back to tapping the element's centre
        val rect = Rect()
        start.getBoundsInScreen(rect)
        if (rect.width() > 0 && rect.height() > 0) {
            return tapAt(rect.centerX(), rect.centerY())
        }
        return false
    }

    private fun findNode(
        root: AccessibilityNodeInfo,
        match: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (match(root)) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findNode(child, match)
            if (found != null) return found
        }
        return null
    }

    companion object {
        @Volatile private var instance: ControlService? = null
        fun get(): ControlService? = instance
        val isRunning: Boolean get() = instance != null
    }
}
