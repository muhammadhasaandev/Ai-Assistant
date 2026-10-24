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
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Always-on foreground service. Listens for the wake word using our OWN audio
 * pipeline (AudioRecord + voice-activity detection + Groq Whisper) — no Google
 * recognizer, no beeps. On the wake word it pops up the overlay, greets Hassan,
 * takes a spoken command and runs it through the LLM with the phone-control tools.
 */
class AssistantService : android.app.Service() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var audio: AudioCapture
    private lateinit var whisper: WhisperClient
    private lateinit var speaker: Speaker
    private lateinit var overlay: OverlayController
    private lateinit var device: DeviceTools
    private lateinit var grok: GrokClient
    private lateinit var history: ConversationStore

    @Volatile private var running = false
    private val inSession = AtomicBoolean(false)
    private val pendingManual = AtomicBoolean(false)
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        audio = AudioCapture()
        whisper = WhisperClient(prefs)
        speaker = Speaker(this, prefs)
        overlay = OverlayController(this).apply {
            onClose = { main.post { overlay.hide() } }
            onMic = { triggerManual() }
        }
        device = DeviceTools(this)
        grok = GrokClient(prefs)
        history = ConversationStore(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopEverything(); return START_NOT_STICKY }
            ACTION_TALK -> { startForegroundSafe(); ensureRunning(); triggerManual(); return START_STICKY }
        }
        startForegroundSafe()
        prefs.serviceEnabled = true
        ensureRunning()
        return START_STICKY
    }

    private fun ensureRunning() {
        if (running) return
        running = true
        worker = Thread { mainLoop() }.apply { isDaemon = true; start() }
    }

    private fun triggerManual() {
        if (inSession.get()) return // don't interrupt an active conversation
        pendingManual.set(true)
        audio.cancel() // break out of a blocking wake capture
    }

    // ---- the single background loop ------------------------------------

    private fun mainLoop() {
        if (!audio.start()) {
            speaker.speakBlocking(if (prefs.isUrdu) "مائیکروفون نہیں کھل سکا۔" else "I could not open the microphone.")
            running = false
            return
        }
        while (running) {
            try {
                if (pendingManual.getAndSet(false)) {
                    runSession("")
                    continue
                }
                // wait (indefinitely) for someone to speak, then grab that utterance
                val pcm = audio.captureUtterance(silenceMs = 700, maxMs = 6000, noSpeechTimeoutMs = 0)
                if (!running) break
                if (pendingManual.get()) continue
                if (pcm == null) continue

                val text = try { whisper.transcribe(pcm, AudioCapture.SAMPLE_RATE, biasPrompt()) }
                catch (e: Exception) { Thread.sleep(400); "" }
                val norm = normalize(text)
                if (norm.isEmpty()) continue

                val hit = matchWake(norm)
                if (hit != null) {
                    runSession(remainderAfterWake(norm, hit))
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                Thread.sleep(300)
            }
        }
    }

    // ---- a session: greet, take a command, run it ----------------------

    private fun runSession(inlineCommand: String) {
        if (!inSession.compareAndSet(false, true)) return
        try {
            main.post {
                if (overlay.canShow && !overlay.isShowing) overlay.show()
                overlay.startConversation()
                overlay.setStatus(if (prefs.isUrdu) "سن رہا ہوں…" else "Listening…")
            }

            // one conversation context for the whole session (system + remembered history)
            val messages = JSONArray()
            messages.put(JSONObject().put("role", "system").put("content", systemPrompt()))
            for (m in history.recentMessages()) messages.put(m)

            val env = Tools.Env(
                device = device, prefs = prefs,
                confirm = { prompt -> askYesNo(prompt) },
                say = { s -> speaker.speakBlocking(s) },
                waitMs = { ms -> sleep(ms) },
                clearMemory = { history.clear() }
            )
            val tools = Tools.definitions()

            // first command: either spoken inline with the wake word, or ask after greeting
            var command: String? = if (inlineCommand.length >= 3) {
                overlay.addUserTurn(inlineCommand); inlineCommand
            } else {
                val greeting = if (prefs.isUrdu) "جی ${prefs.userName}، میں آپ کی کیا مدد کر سکتا ہوں؟"
                else "Yes ${prefs.userName}, how can I help you?"
                speaker.speakBlocking(greeting)
                overlay.setStatus(if (prefs.isUrdu) "بولیں…" else "Speak now…")
                listenCommand()
            }

            if (command.isNullOrBlank()) {
                speaker.speakBlocking(if (prefs.isUrdu) "معاف کیجیے، میں سن نہیں سکا۔" else "Sorry, I didn't catch that.")
                return
            }

            // keep talking until the user goes quiet or says they're done
            var turn = 0
            while (running && turn < MAX_TURNS) {
                val cmd = command
                if (cmd.isNullOrBlank()) break
                turn++
                overlay.addUserTurn(cmd)
                messages.put(JSONObject().put("role", "user").put("content", cmd))
                overlay.setStatus(if (prefs.isUrdu) "سوچ رہا ہوں…" else "Thinking…")
                val answer = runAgent(messages, tools, env)
                overlay.setStatus("")
                overlay.addAssistantTurn(answer)
                history.addUser(cmd)
                history.addAssistant(answer)
                speaker.speakBlocking(answer)

                // listen for a follow-up without needing the wake word again
                overlay.setStatus(if (prefs.isUrdu) "سن رہا ہوں… (بولیں یا خاموش رہیں)" else "Listening… (speak, or stay quiet)")
                val next = listenFollowUp()
                if (next.isNullOrBlank()) break
                if (isEndPhrase(normalize(next))) {
                    speaker.speakBlocking(if (prefs.isUrdu) "ٹھیک ہے، میں حاضر ہوں۔" else "Okay, I'm here when you need me.")
                    break
                }
                command = next
            }
        } catch (e: GrokClient.ApiException) {
            overlay.addAssistantTurn(e.message ?: "API error.")
            speaker.speakBlocking(if (prefs.isUrdu) "اے پی آئی میں مسئلہ ہے۔" else "There is a problem with the API.")
        } catch (e: Exception) {
            overlay.addAssistantTurn(e.message ?: "Error")
        } finally {
            main.postDelayed({ overlay.hide() }, 2200)
            inSession.set(false)
        }
    }

    /** Record the first command (longer patience). */
    private fun listenCommand(): String? = listenAndTranscribe(900, 14000, 7000)

    /** Record a follow-up in continuous mode (give the user a moment to start). */
    private fun listenFollowUp(): String? = listenAndTranscribe(900, 14000, 9000)

    /** Record one utterance and transcribe it with Whisper (with accuracy bias). */
    private fun listenAndTranscribe(silenceMs: Long, maxMs: Long, noSpeechTimeoutMs: Long): String? {
        val pcm = audio.captureUtterance(silenceMs, maxMs, noSpeechTimeoutMs) ?: return null
        return try { whisper.transcribe(pcm, AudioCapture.SAMPLE_RATE, biasPrompt()) }
        catch (e: WhisperClient.ApiException) { throw GrokClient.ApiException(e.message ?: "Transcription failed") }
    }

    private fun isEndPhrase(t: String): Boolean {
        val ends = listOf("bas", "bus", "stop", "that's all", "thats all", "thank you", "thanks",
            "shukriya", "shukria", "khatam", "khatm", "bye", "goodbye", "that is all",
            "بس", "شکریہ", "ختم", "اللہ حافظ", "خدا حافظ", "رہنے دو", "نہیں شکریہ")
        return ends.any { t == it || t.startsWith("$it ") || t.endsWith(" $it") || t.contains(" $it ") }
    }

    private fun biasPrompt(): String = if (prefs.isUrdu)
        "${prefs.userName} اسسٹنٹ۔ واٹس ایپ، یوٹیوب، کروم، انسٹاگرام، فیس بک، گوگل، الارم، ٹائمر، کیلنڈر، میسج، کال، سرچ، کھولو، فلیش لائٹ، والیوم۔"
    else
        "${prefs.userName} Assistant. WhatsApp, YouTube, Chrome, Instagram, Facebook, Google, alarm, timer, calendar, message, call, search, open, flashlight, volume, battery."

    // ---- the agent loop -------------------------------------------------

    private fun runAgent(messages: JSONArray, tools: JSONArray, env: Tools.Env): String {
        var lastText = ""
        for (step in 0 until MAX_STEPS) {
            val msg = grok.chat(messages, tools)
            messages.put(msg)
            val toolCalls = msg.optJSONArray("tool_calls")
            val content = msg.optString("content", "").trim()
            if (content.isNotEmpty()) { lastText = content }

            if (toolCalls == null || toolCalls.length() == 0) {
                return content.ifEmpty { lastText.ifEmpty { if (prefs.isUrdu) "ٹھیک ہے۔" else "Done." } }
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

                overlay.setStatus(statusFor(name))
                val result = Tools.dispatch(name, args, env)
                messages.put(JSONObject().put("role", "tool").put("tool_call_id", id).put("content", result))
            }
        }
        return lastText.ifEmpty {
            if (prefs.isUrdu) "کام مکمل نہیں ہو سکا، دوبارہ کوشش کریں۔" else "I couldn't finish that — please try again."
        }
    }

    private fun statusFor(name: String): String {
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
        val lang = if (prefs.isUrdu) "Urdu (اردو script)" else "English"
        return """
            You are "${prefs.userName} Assistant", a professional voice assistant that controls an Android phone for ${prefs.userName}.
            Always talk back in $lang, in one or two short, natural spoken sentences (this text is read aloud). Be warm and concise.
            This is an ongoing conversation: remember what was said earlier in the messages and refer back to it. Do NOT greet again on follow-up turns.
            You can control the phone ONLY by calling the provided tools. Plan the steps yourself and keep going until the task is truly done.
            Rules:
            - Inside another app: open the app, call read_screen to see what's there, then tap/type. Re-read the screen after each important step; never assume a control is present.
            - SEARCH must actually run: after typing a query into a search box, call run_search (or use type_text with submit=true) so results appear. For YouTube prefer the youtube_search tool. To play a video, after searching call read_screen and tap the video's title.
            - WhatsApp messages: whatsapp_open_chat then whatsapp_send_message. NEVER send a message or place a call without confirming — the send/call tools ask for you, so just call them.
            - Use the dedicated tools for alarms, timers, calendar, calls, web search, maps, settings, flashlight, volume and device_status.
            - If the user wants to drop the earlier context ("new topic", "forget that"), call clear_memory.
            - If the accessibility service is reported OFF, tell ${prefs.userName} to enable "${prefs.userName} Assistant" under Settings > Accessibility, and still do whatever the other tools allow.
            - When a request is finished, or it's just a question, call "finish" with your spoken reply. Do not invent contact names or numbers.
        """.trimIndent()
    }

    // ---- confirmation ---------------------------------------------------

    private fun askYesNo(prompt: String): Boolean {
        overlay.setStatus(if (prefs.isUrdu) "تصدیق…" else "Confirm…")
        speaker.speakBlocking(prompt)
        val heard = try { listenAndTranscribe(800, 6000, 6000) } catch (e: Exception) { null }
        val h = normalize(heard ?: "")
        if (h.isEmpty()) return false
        overlay.addUserTurn(heard ?: "")
        val no = listOf("no","nope","dont","cancel","stop","nahi","nahin","نہیں","مت","نہ","رہنے")
        if (no.any { h.contains(it) }) return false
        val yes = listOf("yes","yeah","yep","ok","okay","sure","send","go","do it","han","haan","ہاں","جی","کرو","بھیجو","بھیج","ٹھیک","ضرور")
        return yes.any { h.contains(it) }
    }

    // ---- wake-word matching --------------------------------------------

    private fun normalize(text: String): String =
        text.lowercase().replace(Regex("[\\p{Punct}]"), " ").replace(Regex("\\s+"), " ").trim()

    // A single trigger word anywhere in the sentence wakes the assistant.
    private val triggerTokens = listOf(
        "assistant", "asistant", "asistent", "assistent",
        "اسسٹنٹ", "اسسٹینٹ", "اسِسٹنٹ", "اسسٹنٹ",
        "hassan", "hasan", "hussain", "حسن"
    )

    /** Returns the last trigger token found in the text, or null. */
    private fun matchWake(text: String): String? {
        var bestIdx = -1
        var best: String? = null
        for (tok in triggerTokens) {
            val i = text.lastIndexOf(tok)
            if (i > bestIdx) { bestIdx = i; best = tok }
        }
        return best
    }

    /** Everything the user said after the trigger word becomes the command. */
    private fun remainderAfterWake(text: String, phrase: String): String {
        val idx = text.lastIndexOf(phrase)
        if (idx < 0) return ""
        return text.substring((idx + phrase.length).coerceAtMost(text.length))
            .trim().trimStart(',', '،', '.', '!', '-', ' ')
    }

    // ---- foreground notification ---------------------------------------

    private fun startForegroundSafe() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, "Assistant", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                description = "Keeps ${prefs.userName} Assistant listening for the wake word."
            }
            mgr.createNotificationChannel(ch)
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val talk = PendingIntent.getService(
            this, 2, Intent(this, AssistantService::class.java).setAction(ACTION_TALK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AssistantService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle("${prefs.userName} Assistant is listening")
            .setContentText("Say the wake word, or tap Talk.")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "🎤 Talk", talk)
            .addAction(0, "Stop", stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    // ---- shutdown -------------------------------------------------------

    private fun sleep(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) {} }

    private fun stopEverything() {
        running = false
        prefs.serviceEnabled = false
        main.removeCallbacksAndMessages(null)
        worker?.interrupt()
        audio.release()
        speaker.shutdown()
        main.post { overlay.hide() }
        inSession.set(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (running) {
            try {
                val restart = Intent(applicationContext, AssistantService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    applicationContext.startForegroundService(restart)
            } catch (_: Exception) {}
        }
        try { audio.release() } catch (_: Exception) {}
        try { speaker.shutdown() } catch (_: Exception) {}
    }

    companion object {
        const val CHANNEL = "assistant_listening"
        const val NOTIF_ID = 42
        const val ACTION_STOP = "com.hassan.assistant.STOP"
        const val ACTION_TALK = "com.hassan.assistant.TALK"
        const val MAX_STEPS = 12
        const val MAX_TURNS = 30
    }
}
