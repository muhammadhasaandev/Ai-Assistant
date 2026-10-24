package com.hassan.assistant

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Text-to-speech wrapper. speakBlocking() waits until the sentence has been spoken. */
class Speaker(context: Context, private val prefs: Prefs) {

    @Volatile var ready = false
        private set
    @Volatile var urduSupported = false
        private set

    private val latches = ConcurrentHashMap<String, CountDownLatch>()
    private val initLatch = CountDownLatch(1)
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                val r = tts?.isLanguageAvailable(Locale("ur", "PK")) ?: TextToSpeech.LANG_NOT_SUPPORTED
                urduSupported = r >= TextToSpeech.LANG_AVAILABLE
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { release(utteranceId) }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { release(utteranceId) }
                    override fun onError(utteranceId: String?, errorCode: Int) { release(utteranceId) }
                    override fun onStop(utteranceId: String?, interrupted: Boolean) { release(utteranceId) }
                })
            }
            initLatch.countDown()
        }
    }

    private fun release(id: String?) {
        if (id != null) latches.remove(id)?.countDown()
    }

    private fun applyLanguage() {
        val t = tts ?: return
        if (prefs.isUrdu && urduSupported) {
            t.language = Locale("ur", "PK")
        } else {
            t.language = Locale.US
        }
    }

    /** Speaks and returns when done (or after a safety timeout). Safe to call from any thread. */
    fun speakBlocking(text: String) {
        initLatch.await(5, TimeUnit.SECONDS)
        val clean = clean(text)
        if (!ready || clean.isBlank()) return
        applyLanguage()
        val id = UUID.randomUUID().toString()
        val latch = CountDownLatch(1)
        latches[id] = latch
        val params = Bundle()
        val result = tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, params, id) ?: TextToSpeech.ERROR
        if (result != TextToSpeech.SUCCESS) {
            latches.remove(id)
            return
        }
        // roughly 80 ms per character, at least 4 s, at most 60 s
        val timeoutMs = (clean.length * 80L).coerceIn(4_000L, 60_000L)
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    fun stop() {
        tts?.stop()
        latches.values.forEach { it.countDown() }
        latches.clear()
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
    }

    private fun clean(text: String): String =
        text.replace(Regex("[*_#`>|~]"), " ")
            .replace(Regex("https?://\\S+"), " link ")
            .replace(Regex("[\\p{So}\\p{Cn}]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
