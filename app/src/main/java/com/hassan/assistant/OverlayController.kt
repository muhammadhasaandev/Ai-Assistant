package com.hassan.assistant

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
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
 * The popup card that slides up from the bottom. Shows a live conversation
 * transcript (user + assistant bubbles), a status line, an animated listening
 * dot and a mic button. Needs the "display over other apps" permission.
 * All public methods are safe to call from any thread.
 */
class OverlayController(private val context: Context) {

    var onClose: (() -> Unit)? = null
    var onMic: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: FrameLayout? = null
    private var statusView: TextView? = null
    private var dot: View? = null
    private var transcript: LinearLayout? = null
    private var scroll: ScrollView? = null
    private var micView: TextView? = null

    val canShow: Boolean get() = Settings.canDrawOverlays(context)
    val isShowing: Boolean get() = root != null

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()

    fun show() = main.post { showInternal() }

    private fun showInternal() {
        if (root != null || !canShow) return

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F2141A20"))
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), Color.parseColor("#3348D9A8"))
            }
            setPadding(dp(18), dp(14), dp(18), dp(16))
        }

        // header: pulsing dot + title + close
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        dot = View(context).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#48D9A8")) }
            layoutParams = LinearLayout.LayoutParams(dp(9), dp(9)).apply { rightMargin = dp(8) }
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
        header.addView(dot)
        header.addView(title)
        header.addView(close)

        statusView = TextView(context).apply {
            setTextColor(Color.parseColor("#7FB7C4C0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(6), 0, dp(4))
        }

        transcript = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(230))
            isVerticalScrollBarEnabled = true
            addView(transcript)
        }

        micView = TextView(context).apply {
            text = "🎤"
            setTextColor(Color.parseColor("#06241C"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#48D9A8")); cornerRadius = dp(16).toFloat()
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42))
            lp.topMargin = dp(10)
            layoutParams = lp
            setOnClickListener { onMic?.invoke() }
        }

        card.addView(header)
        card.addView(statusView)
        card.addView(scroll)
        card.addView(micView)

        val container = FrameLayout(context).apply {
            setPadding(dp(12), dp(12), dp(12), dp(14))
            addView(card, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM }

        root = container
        container.alpha = 0f
        try {
            wm.addView(container, params)
            container.translationY = dp(40).toFloat()
            container.animate().alpha(1f).translationY(0f).setDuration(200).start()
            pulse()
        } catch (e: Exception) { root = null }
    }

    private fun pulse() {
        val d = dot ?: return
        d.animate().alpha(0.25f).setDuration(650).withEndAction {
            d.animate().alpha(1f).setDuration(650).withEndAction { if (root != null) pulse() }.start()
        }.start()
    }

    fun startConversation() = main.post { transcript?.removeAllViews() }

    fun addUserTurn(text: String) = addBubble(text, user = true)
    fun addAssistantTurn(text: String) = addBubble(text, user = false)

    private fun addBubble(text: String, user: Boolean) = main.post {
        val t = transcript ?: return@post
        val bubble = TextView(context).apply {
            this.text = text
            setTextColor(if (user) Color.parseColor("#06241C") else Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(13), dp(9), dp(13), dp(9))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(if (user) "#48D9A8" else "#22303A"))
                cornerRadius = dp(16).toFloat()
            }
        }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(6)
        lp.gravity = if (user) Gravity.END else Gravity.START
        lp.leftMargin = if (user) dp(40) else 0
        lp.rightMargin = if (user) 0 else dp(40)
        bubble.layoutParams = lp
        t.addView(bubble)
        scroll?.post { scroll?.fullScroll(View.FOCUS_DOWN) }
    }

    fun setStatus(text: String) { statusView?.post { statusView?.text = text } }

    fun setMicLabel(text: String) { micView?.post { micView?.text = text } }

    fun hide() = main.post {
        val r = root ?: return@post
        root = null
        try { wm.removeView(r) } catch (_: Exception) {}
        statusView = null; transcript = null; scroll = null; micView = null; dot = null
    }
}
