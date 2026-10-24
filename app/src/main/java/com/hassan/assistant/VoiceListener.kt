package com.hassan.assistant

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Wraps Android's SpeechRecognizer. All calls must happen on the main thread.
 * One listen() call = one utterance; the caller decides whether to listen again.
 */
class VoiceListener(private val context: Context, private val prefs: Prefs) {

    interface Callback {
        fun onPartial(text: String) {}
        fun onReady() {}
        fun onFinal(text: String)
        fun onFailed(error: Int)
    }

    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var recognizer: SpeechRecognizer? = null
    private var active = false
    private var muted = false

    val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    private fun ensureRecognizer(): SpeechRecognizer {
        recognizer?.let { return it }
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        return r
    }

    /**
     * @param quiet true while waiting for the wake word: hides the system beeps if enabled.
     */
    fun listen(languageTag: String, quiet: Boolean, completeSilenceMs: Long, cb: Callback) {
        cancel()
        val r = ensureRecognizer()
        val muteThis = quiet && prefs.muteBeeps
        r.setRecognitionListener(object : RecognitionListener {
            private var delivered = false

            override fun onReadyForSpeech(params: Bundle?) {
                if (muteThis) main.postDelayed({ unmute() }, 500)
                cb.onReady()
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                if (muteThis) mute()
            }

            override fun onError(error: Int) {
                active = false
                if (muteThis) main.postDelayed({ unmute() }, 350)
                if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) {
                    // recreate a recognizer that got stuck
                    recognizer?.destroy()
                    recognizer = null
                }
                if (!delivered) {
                    delivered = true
                    cb.onFailed(error)
                }
            }

            override fun onResults(results: Bundle?) {
                active = false
                if (muteThis) main.postDelayed({ unmute() }, 350)
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = list?.firstOrNull()?.trim().orEmpty()
                if (!delivered) {
                    delivered = true
                    if (text.isEmpty()) cb.onFailed(SpeechRecognizer.ERROR_NO_MATCH) else cb.onFinal(text)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val list = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = list?.firstOrNull()?.trim().orEmpty()
                if (text.isNotEmpty()) cb.onPartial(text)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, completeSilenceMs)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, completeSilenceMs)
            if (quiet) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        if (muteThis) mute()
        active = true
        try {
            r.startListening(intent)
        } catch (e: Exception) {
            active = false
            unmute()
            recognizer?.destroy()
            recognizer = null
            main.postDelayed({ cb.onFailed(SpeechRecognizer.ERROR_CLIENT) }, 500)
        }
    }

    fun cancel() {
        if (active) {
            try { recognizer?.cancel() } catch (_: Exception) {}
            active = false
        }
        unmute()
    }

    fun destroy() {
        cancel()
        recognizer?.destroy()
        recognizer = null
    }

    private fun mute() {
        if (muted) return
        // never silence the user's own music or video
        if (audio.isMusicActive) return
        try {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            muted = true
        } catch (_: Exception) {}
    }

    private fun unmute() {
        if (!muted) return
        try {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        } catch (_: Exception) {}
        muted = false
    }
}
