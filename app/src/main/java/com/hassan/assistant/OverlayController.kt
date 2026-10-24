package com.hassan.assistant

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The small popup card that slides up from the bottom of the screen.
 * Must be used from the main thread. Needs the "display over other apps" permission.
 */
class OverlayController(private val context: Context) {

    var onClose: (() -> Unit)? = null
    var onMic: (() -> Unit)? = null

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: FrameLayout? = null
    private var statusView: TextView? = null
    private var replyView: TextView? = null
    private var micView: TextView? = null

    val canShow: Boolean get() = Settings.canDrawOverlays(context)
    val isShowing: Boolean get() = root != null

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics
        ).toInt()

    fun show() {
        if (root != null || !canShow) return

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F2141A20"))
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), Color.parseColor("#3348D9A8"))
            }
            setPadding(dp(20), dp(16), dp(20), dp(18))
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(context).apply {
            text = "Hassan Assistant"
            setTextColor(Color.parseColor("#48D9A8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val close = TextView(context).apply {
            text = "✕"
            setTextColor(Color.parseColor("#AACCCCCC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(10), dp(2), dp(4), dp(2))
            setOnClickListener { onClose?.invoke() }
        }
        header.addView(title)
        header.addView(close)

        statusView = TextView(context).apply {
            setTextColor(Color.parseColor("#7FB7C4C0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(8), 0, 0)
        }

        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(170)
            )
            isVerticalScrollBarEnabled = true
        }
        replyView = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(0, dp(6), 0, 0)
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        scroll.addView(replyView)

        micView = TextView(context).apply {
            text = "🎤  Tap to speak"
            setTextColor(Color.parseColor("#48D9A8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(2))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1F48D9A8"))
                cornerRadius = dp(14).toFloat()
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44)
            )
            lp.topMargin = dp(12)
            layoutParams = lp
            gravity = Gravity.CENTER
            setOnClickListener { onMic?.invoke() }
        }

        card.addView(header)
        card.addView(statusView)
        card.addView(scroll)
        card.addView(micView)

        val container = FrameLayout(context).apply {
            setPadding(dp(12), dp(12), dp(12), dp(14))
            addView(card, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
        }

        root = container
        container.alpha = 0f
        try {
            wm.addView(container, params)
            container.translationY = dp(40).toFloat()
            container.animate().alpha(1f).translationY(0f).setDuration(180).start()
        } catch (e: Exception) {
            root = null
        }
    }

    fun setStatus(text: String) {
        statusView?.post { statusView?.text = text }
    }

    fun setReply(text: String) {
        replyView?.post { replyView?.text = text }
    }

    fun setMicLabel(text: String) {
        micView?.post { micView?.text = text }
    }

    fun hide() {
        val r = root ?: return
        root = null
        try { wm.removeView(r) } catch (_: Exception) {}
        statusView = null
        replyView = null
        micView = null
    }

}
