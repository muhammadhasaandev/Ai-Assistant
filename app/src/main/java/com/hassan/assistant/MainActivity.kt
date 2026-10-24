package com.hassan.assistant

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())

    private lateinit var keyField: EditText
    private lateinit var modelField: EditText
    private lateinit var nameField: EditText
    private lateinit var wakeField: EditText
    private lateinit var statusText: TextView
    private lateinit var langGroup: RadioGroup
    private lateinit var rbUrdu: RadioButton
    private lateinit var rbEnglish: RadioButton
    private lateinit var muteBox: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(buildUi())
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    // ---- UI -------------------------------------------------------------

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun buildUi(): View {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#0E1419")) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(26), dp(22), dp(34))
        }

        root.addView(header("Hassan Assistant", 24, Color.parseColor("#48D9A8"), true))
        root.addView(sub("Your voice-controlled phone assistant, powered by Groq (free)."))

        // --- API key
        root.addView(sectionTitle("1 · Groq API key"))
        root.addView(label("Get a free key at console.groq.com (it starts with gsk_)."))
        keyField = input("gsk_...", prefs.apiKey, password = true)
        root.addView(keyField)
        modelField = input("Model", prefs.model)
        root.addView(label("Works: openai/gpt-oss-120b (best), openai/gpt-oss-20b, qwen/qwen3.8-27b"))
        root.addView(modelField)
        root.addView(button("Save & test API") { saveAndTest() })

        // --- language
        root.addView(sectionTitle("2 · Language & name"))
        nameField = input("What should it call you?", prefs.userName)
        root.addView(nameField)

        langGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
        }
        rbUrdu = radio("اردو", "ur").apply { id = View.generateViewId() }
        rbEnglish = radio("English", "en").apply { id = View.generateViewId() }
        langGroup.addView(rbUrdu)
        langGroup.addView(rbEnglish)
        if (prefs.isUrdu) rbUrdu.isChecked = true else rbEnglish.isChecked = true
        root.addView(langGroup)

        wakeField = input("Wake words (comma separated)", prefs.wakeWords)
        root.addView(label("The assistant activates when it hears any of these."))
        root.addView(wakeField)

        muteBox = CheckBox(this).apply {
            text = "Silence the listening beep while waiting for the wake word"
            setTextColor(Color.parseColor("#CFE6E1"))
            isChecked = prefs.muteBeeps
        }
        root.addView(muteBox)
        root.addView(button("Save settings") { saveSettings(toast = true) })

        // --- permissions
        root.addView(sectionTitle("3 · Permissions"))
        root.addView(label("Tap each one and allow it. All are needed for the assistant to work."))
        root.addView(button("① Microphone & notifications") { requestRuntimePerms() })
        root.addView(button("② Display over other apps (popup)") { openOverlaySettings() })
        root.addView(button("③ Phone control (Accessibility)") { openAccessibility() })
        root.addView(button("④ Contacts, calls & calendar") { requestExtraPerms() })
        root.addView(button("⑤ Ignore battery optimisation (stay alive)") { askBattery() })

        // --- start / stop
        root.addView(sectionTitle("4 · Start"))
        statusText = TextView(this).apply {
            setTextColor(Color.parseColor("#9FB4AE"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(4), 0, dp(10))
        }
        root.addView(statusText)
        root.addView(button("▶  Start assistant") { startAssistant() })
        root.addView(button("■  Stop assistant") { stopAssistant() })

        root.addView(sub("Tip: say the wake word, wait for \"Yes ${prefs.userName}, how can I help?\", then speak. " +
                "You can also say it all at once, e.g. \"Hassan Assistant, open WhatsApp\"."))

        scroll.addView(root)
        return scroll
    }

    private fun header(text: String, sizeSp: Int, color: Int, bold: Boolean) = TextView(this).apply {
        this.text = text
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp.toFloat())
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun sub(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Color.parseColor("#8097A0"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(0, dp(4), 0, dp(14))
    }

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(20), 0, dp(8))
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Color.parseColor("#8097A0"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(0, dp(6), 0, dp(2))
    }

    private fun input(hint: String, value: String, password: Boolean = false) = EditText(this).apply {
        this.hint = hint
        setText(value)
        setTextColor(Color.WHITE)
        setHintTextColor(Color.parseColor("#5E7079"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        setBackgroundColor(Color.parseColor("#1A2730"))
        inputType = if (password)
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        else InputType.TYPE_CLASS_TEXT
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(6)
        layoutParams = lp
    }

    private fun radio(text: String, tag: String) = RadioButton(this).apply {
        this.text = text
        this.tag = tag
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setPadding(dp(6), 0, dp(24), 0)
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(Color.parseColor("#06241C"))
        setBackgroundColor(Color.parseColor("#48D9A8"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(10)
        layoutParams = lp
        setOnClickListener { onClick() }
    }

    // ---- actions --------------------------------------------------------

    private fun saveSettings(toast: Boolean) {
        prefs.apiKey = keyField.text.toString()
        prefs.model = modelField.text.toString()
        prefs.userName = nameField.text.toString().trim().ifEmpty { "Hassan" }
        prefs.wakeWords = wakeField.text.toString()
        prefs.muteBeeps = muteBox.isChecked
        prefs.language = if (rbEnglish.isChecked) "en" else "ur"
        if (toast) Toast.makeText(this, "Saved.", Toast.LENGTH_SHORT).show()
    }

    private fun saveAndTest() {
        saveSettings(toast = false)
        if (prefs.apiKey.isBlank()) {
            Toast.makeText(this, "Paste your Groq API key first.", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "Testing…", Toast.LENGTH_SHORT).show()
        Thread {
            val result = try {
                val r = GrokClient(prefs).test()
                "API OK ✓  (reply: ${r.take(20)})"
            } catch (e: Exception) {
                "API failed: ${e.message?.take(140)}"
            }
            main.post { Toast.makeText(this, result, Toast.LENGTH_LONG).show() }
        }.apply { isDaemon = true }.start()
    }

    private fun startAssistant() {
        saveSettings(toast = false)
        if (prefs.apiKey.isBlank()) {
            Toast.makeText(this, "Add your Groq API key first.", Toast.LENGTH_LONG).show()
            return
        }
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            Toast.makeText(this, "Allow the microphone first (button ①).", Toast.LENGTH_LONG).show()
            requestRuntimePerms()
            return
        }
        val intent = Intent(this, AssistantService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
        Toast.makeText(this, "Assistant started — say \"${prefs.wakePhraseList().firstOrNull() ?: "Hassan Assistant"}\".", Toast.LENGTH_LONG).show()
        main.postDelayed({ refreshStatus() }, 800)
    }

    private fun stopAssistant() {
        startService(Intent(this, AssistantService::class.java).setAction(AssistantService.ACTION_STOP))
        Toast.makeText(this, "Assistant stopped.", Toast.LENGTH_SHORT).show()
        main.postDelayed({ refreshStatus() }, 600)
    }

    // ---- permissions ----------------------------------------------------

    private fun hasPermission(p: String) =
        checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun requestRuntimePerms() {
        val list = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(list.toTypedArray(), 101)
    }

    private fun requestExtraPerms() {
        requestPermissions(arrayOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR
        ), 102)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
    }

    private fun openOverlaySettings() {
        if (Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Already allowed ✓", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")))
    }

    private fun openAccessibility() {
        Toast.makeText(this, "Find \"${prefs.userName} Assistant\" and turn it ON.", Toast.LENGTH_LONG).show()
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun askBattery() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            catch (_: Exception) {}
        }
    }

    // ---- status ---------------------------------------------------------

    private fun refreshStatus() {
        if (!::statusText.isInitialized) return
        val mic = hasPermission(Manifest.permission.RECORD_AUDIO)
        val overlay = Settings.canDrawOverlays(this)
        val acc = ControlService.isRunning
        val running = prefs.serviceEnabled
        val sb = StringBuilder()
        sb.append(if (mic) "✓" else "✗").append(" microphone   ")
        sb.append(if (overlay) "✓" else "✗").append(" popup   ")
        sb.append(if (acc) "✓" else "✗").append(" phone-control\n")
        sb.append(if (running) "● assistant is ON" else "○ assistant is off")
        statusText.text = sb.toString()
        statusText.setTextColor(if (running) Color.parseColor("#48D9A8") else Color.parseColor("#9FB4AE"))
    }
}
