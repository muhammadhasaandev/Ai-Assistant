package com.hassan.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Always-on foreground service. It listens for the wake word, and when it hears
 * it, pops up the overlay, greets Hassan, takes a spoken command and runs it
 * through Grok with the phone-control tools.
 */
class AssistantService : android.app.Service() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var voice: VoiceListener
    private lateinit var speaker: Speaker
    private lateinit var overlay: OverlayController
    private lateinit var device: DeviceTools
    private lateinit var grok: GrokClient

    private enum class Mode { WAKE, SESSION, STOPPED }
    @Volatile private var mode = Mode.STOPPED
    private val sessionRunning = AtomicBoolean(false)
    private var worker: Thread? = null

    // one-shot blocking listen plumbing
    @Volatile private var pendingResult: String? = null
    private var listenLatch: CountDownLatch? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        voice = VoiceListener(this, prefs)
        speaker = Speaker(this, prefs)
        overlay = OverlayController(this).apply {
            onClose = { main.post { endSessionUi() } }
            onMic = { main.post { if (mode == Mode.WAKE) triggerManual() } }
        }
        device = DeviceTools(this)
        grok = GrokClient(prefs)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEverything()
            return START_NOT_STICKY
        }
        startForegroundSafe()
        prefs.serviceEnabled = true
        if (mode == Mode.STOPPED) {
            mode = Mode.WAKE
            scheduleWake(300)
        }
        return START_STICKY
    }

    // ---- foreground notification ---------------------------------------

    private fun startForegroundSafe() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, "Assistant", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                description = "Keeps Hassan Assistant listening for the wake word."
            }
            mgr.createNotificationChannel(ch)
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AssistantService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle("Hassan Assistant is listening")
            .setContentText("Say \"Hassan Assistant\" any time.")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    // ---- wake-word loop -------------------------------------------------

    private fun scheduleWake(delay: Long) {
        main.postDelayed({ listenForWake() }, delay)
    }

    private fun listenForWake() {
        if (mode != Mode.WAKE) return
        if (!voice.isAvailable) {
            // No speech engine; retry slowly so we don't spin.
            scheduleWake(5_000)
            return
        }
        voice.listen(prefs.speechLocaleTag, quiet = true, completeSilenceMs = 1300,
            object : VoiceListener.Callback {
                override fun onFinal(text: String) {
                    if (mode != Mode.WAKE) return
                    val hit = matchWake(text)
                    if (hit != null) {
                        onWakeDetected(remainderAfterWake(text, hit))
                    } else {
                        scheduleWake(150)
                    }
                }
                override fun onFailed(error: Int) {
                    if (mode != Mode.WAKE) return
                    val delay = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1200L else 350L
                    scheduleWake(delay)
                }
            })
    }

    private fun matchWake(text: String): String? {
        val t = text.lowercase()
        return prefs.wakePhraseList().firstOrNull { phrase ->
            phrase.isNotEmpty() && (t.contains(phrase) || looseMatch(t, phrase))
        }
    }

    /** Tolerates the speech engine mishearing e.g. "hassan" as "hasan"/"hussain". */
    private fun looseMatch(text: String, phrase: String): Boolean {
        if (!phrase.contains("assistant") && !phrase.contains("اسسٹنٹ")) return false
        val hasAssistant = text.contains("assistant") || text.contains("اسسٹنٹ") || text.contains("اسسٹینٹ")
        val hasName = text.contains("hassan") || text.contains("hasan") || text.contains("hussain") ||
            text.contains("حسن") || text.contains("my ") || text.contains("مائی")
        return hasAssistant && hasName
    }

    private fun remainderAfterWake(text: String, phrase: String): String {
        val idx = text.lowercase().indexOf(phrase)
        if (idx < 0) return ""
        val after = text.substring((idx + phrase.length).coerceAtMost(text.length))
        return after.trim().trimStart(',', '،', '.', '!', ' ')
    }

    private fun triggerManual() = onWakeDetected("")

    // ---- a session: greet, take a command, run it ----------------------

    private fun onWakeDetected(inlineCommand: String) {
        if (!sessionRunning.compareAndSet(false, true)) return
        mode = Mode.SESSION
        voice.cancel()
        main.post {
            if (overlay.canShow && !overlay.isShowing) overlay.show()
            overlay.setReply("")
            overlay.setStatus(if (prefs.isUrdu) "سن رہا ہوں…" else "Listening…")
        }
        worker = Thread { runSession(inlineCommand) }.apply { isDaemon = true; start() }
    }

    private fun runSession(inlineCommand: String) {
        try {
            val greeting = if (prefs.isUrdu) "جی ${prefs.userName}، میں آپ کی کیا مدد کر سکتا ہوں؟"
            else "Yes ${prefs.userName}, how can I help you?"

            val command: String = if (inlineCommand.length >= 3) {
                overlay.setReply("“$inlineCommand”")
                inlineCommand
            } else {
                speaker.speakBlocking(greeting)
                overlay.setStatus(if (prefs.isUrdu) "بولیں…" else "Speak now…")
                val heard = listenBlocking(quiet = false, silenceMs = 1600)
                if (heard.isNullOrBlank()) {
                    speaker.speakBlocking(if (prefs.isUrdu) "معاف کیجیے، میں سن نہیں سکا۔" else "Sorry, I didn't catch that.")
                    return
                }
                overlay.setReply("“$heard”")
                heard
            }

            overlay.setStatus(if (prefs.isUrdu) "سوچ رہا ہوں…" else "Thinking…")
            val answer = runAgent(command)
            overlay.setStatus("")
            overlay.setReply(answer)
            speaker.speakBlocking(answer)
        } catch (e: GrokClient.ApiException) {
            val msg = e.message ?: "API error."
            overlay.setReply(msg)
            speaker.speakBlocking(if (prefs.isUrdu) "اے پی آئی میں مسئلہ ہے۔" else "There is a problem with the API.")
        } catch (e: Exception) {
            overlay.setReply(e.message ?: "Error")
        } finally {
            main.postDelayed({ endSessionUi() }, 2500)
        }
    }

    private fun endSessionUi() {
        voice.cancel()
        overlay.hide()
        speaker.stop()
        sessionRunning.set(false)
        worker = null
        if (mode != Mode.STOPPED) {
            mode = Mode.WAKE
            scheduleWake(600)
        }
    }

    // ---- the Grok agent loop -------------------------------------------

    private fun runAgent(command: String): String {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", systemPrompt()))
        messages.put(JSONObject().put("role", "user").put("content", command))

        val tools = Tools.definitions()
        val env = Tools.Env(
            device = device,
            prefs = prefs,
            confirm = { prompt -> askYesNo(prompt) },
            say = { s -> speaker.speakBlocking(s) },
            waitMs = { ms -> sleep(ms) }
        )

        var lastText = ""
        for (step in 0 until MAX_STEPS) {
            val msg = grok.chat(messages, tools)
            messages.put(msg)
            val toolCalls = msg.optJSONArray("tool_calls")
            val content = msg.optString("content", "").trim()
            if (content.isNotEmpty()) {
                lastText = content
                overlay.setReply(content)
            }

            if (toolCalls == null || toolCalls.length() == 0) {
                return content.ifEmpty { lastText.ifEmpty {
                    if (prefs.isUrdu) "ٹھیک ہے۔" else "Done." } }
            }

            for (i in 0 until toolCalls.length()) {
                val call = toolCalls.getJSONObject(i)
                val id = call.optString("id", "call_$i")
                val fn = call.optJSONObject("function") ?: JSONObject()
                val name = fn.optString("name")
                val args = parseArgs(fn.optString("arguments", "{}"))

                if (name == "finish") {
                    val reply = args.optString("reply", "").trim()
                    return reply.ifEmpty { lastText.ifEmpty { if (prefs.isUrdu) "ٹھیک ہے۔" else "Done." } }
                }

                overlay.setStatus(statusFor(name, args))
                val result = Tools.dispatch(name, args, env)
                messages.put(JSONObject()
                    .put("role", "tool")
                    .put("tool_call_id", id)
                    .put("content", result))
            }
        }
        return lastText.ifEmpty {
            if (prefs.isUrdu) "کام مکمل نہیں ہو سکا، دوبارہ کوشش کریں۔"
            else "I couldn't finish that — please try again."
        }
    }

    private fun statusFor(name: String, args: JSONObject): String {
        val ur = prefs.isUrdu
        return when (name) {
            "open_app" -> if (ur) "کھول رہا ہوں…" else "Opening…"
            "read_screen" -> if (ur) "اسکرین دیکھ رہا ہوں…" else "Reading the screen…"
            "tap" -> if (ur) "ٹیپ کر رہا ہوں…" else "Tapping…"
            "type_text" -> if (ur) "ٹائپ کر رہا ہوں…" else "Typing…"
            "whatsapp_open_chat", "whatsapp_search" -> if (ur) "واٹس ایپ…" else "WhatsApp…"
            "set_alarm", "set_timer" -> if (ur) "سیٹ کر رہا ہوں…" else "Setting…"
            else -> if (ur) "کام کر رہا ہوں…" else "Working…"
        }
    }

    private fun parseArgs(raw: String): JSONObject = try {
        if (raw.isBlank()) JSONObject() else JSONObject(raw)
    } catch (e: Exception) { JSONObject() }

    private fun systemPrompt(): String {
        val lang = if (prefs.isUrdu) "Urdu (Roman or اردو script is fine but prefer اردو script)" else "English"
        return """
            You are "${prefs.userName} Assistant", a voice assistant that controls an Android phone for ${prefs.userName}.
            Always talk back in $lang, in one or two short spoken sentences (this text is read aloud).
            You can control the phone ONLY by calling the provided tools. Decide the steps yourself.
            Rules:
            - To do something inside another app (WhatsApp, YouTube, etc.): open the app, then call read_screen to see what is there, then tap/type. Re-read the screen after each important step; never assume a button is there.
            - For WhatsApp messages use whatsapp_open_chat then whatsapp_send_message. NEVER send a message or make a call without the user confirming — the send/call tools already ask, so just call them.
            - For alarms, timers, calendar events, calls, web search, maps and settings, use the dedicated tools directly.
            - If the phone-control (accessibility) service is reported OFF, tell ${prefs.userName} to turn on "${prefs.userName} Assistant" under Settings > Accessibility, and still do whatever you can with the other tools.
            - When the task is finished, or if it is just a question to answer, call the "finish" tool with your spoken reply. Keep it brief and natural.
            - Today's steps run on a real phone, so be careful and literal. Do not invent contact names or numbers.
        """.trimIndent()
    }

    // ---- confirmation (blocking yes/no) --------------------------------

    private fun askYesNo(prompt: String): Boolean {
        overlay.setStatus(if (prefs.isUrdu) "تصدیق…" else "Confirm…")
        speaker.speakBlocking(prompt)
        val heard = listenBlocking(quiet = false, silenceMs = 1200)?.lowercase()?.trim() ?: return false
        overlay.setReply("“$heard”")
        val yes = listOf("yes","yeah","yep","ok","okay","sure","send","go","do it","han","haan","ہاں","جی","کرو","بھیجو","بھیج دو","ٹھیک")
        val no = listOf("no","nope","don't","dont","cancel","stop","nahi","nahin","نہیں","مت","رہنے","نہ")
        if (no.any { heard.contains(it) }) return false
        return yes.any { heard.contains(it) }
    }

    // ---- blocking one-shot listen --------------------------------------

    private fun listenBlocking(quiet: Boolean, silenceMs: Long): String? {
        val latch = CountDownLatch(1)
        listenLatch = latch
        pendingResult = null
        main.post {
            voice.listen(prefs.speechLocaleTag, quiet = quiet, completeSilenceMs = silenceMs,
                object : VoiceListener.Callback {
                    override fun onPartial(text: String) {
                        if (text.isNotBlank()) overlay.setReply("“$text”")
                    }
                    override fun onFinal(text: String) { pendingResult = text; latch.countDown() }
                    override fun onFailed(error: Int) { pendingResult = null; latch.countDown() }
                })
        }
        latch.await(20, TimeUnit.SECONDS)
        listenLatch = null
        return pendingResult
    }

    private fun sleep(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) {} }

    // ---- shutdown -------------------------------------------------------

    private fun stopEverything() {
        mode = Mode.STOPPED
        prefs.serviceEnabled = false
        main.removeCallbacksAndMessages(null)
        worker?.interrupt()
        voice.destroy()
        speaker.shutdown()
        overlay.hide()
        sessionRunning.set(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (mode != Mode.STOPPED) {
            // the system killed us; ask to be restarted
            try {
                val restart = Intent(applicationContext, AssistantService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    applicationContext.startForegroundService(restart)
            } catch (_: Exception) {}
        }
        try { voice.destroy() } catch (_: Exception) {}
        try { speaker.shutdown() } catch (_: Exception) {}
    }

    companion object {
        const val CHANNEL = "assistant_listening"
        const val NOTIF_ID = 42
        const val ACTION_STOP = "com.hassan.assistant.STOP"
        const val MAX_STEPS = 12
    }
}
