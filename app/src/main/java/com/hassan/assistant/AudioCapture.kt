package com.hassan.assistant

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

/**
 * Records microphone audio with a simple energy-based voice-activity detector.
 * No system beep, no Google recognizer — just raw PCM that we send to Whisper.
 * Use from a background thread. 16 kHz mono PCM16 (what Whisper likes).
 */
class AudioCapture {

    companion object {
        const val SAMPLE_RATE = 16000
        private const val FRAME = 480            // 30 ms @ 16 kHz
        private const val START_FLOOR = 650.0    // min RMS to count as speech
        private const val SILENCE_FLOOR = 450.0
        private const val PREROLL_FRAMES = 6     // ~180 ms kept before speech starts
    }

    @Volatile private var record: AudioRecord? = null
    @Volatile private var cancelled = false
    @Volatile private var noiseFloor = 300.0

    val isReady: Boolean get() = record?.state == AudioRecord.STATE_INITIALIZED

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (record != null) return isReady
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, FRAME * 2 * 10)
        val r = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
        } catch (e: Exception) { null }
        if (r == null || r.state != AudioRecord.STATE_INITIALIZED) {
            r?.release()
            return false
        }
        record = r
        return try { r.startRecording(); true } catch (e: Exception) { false }
    }

    fun cancel() { cancelled = true }

    /**
     * Listen until the user speaks and then stops.
     * @param silenceMs trailing silence that ends an utterance.
     * @param maxMs hard cap on one utterance.
     * @param noSpeechTimeoutMs give up and return null if no speech starts in this long (0 = wait forever).
     * @return PCM16 bytes of the utterance, or null if cancelled / nothing heard.
     */
    fun captureUtterance(silenceMs: Long, maxMs: Long, noSpeechTimeoutMs: Long): ByteArray? {
        val r = record ?: return null
        cancelled = false
        drain(r) // throw away stale/buffered audio (e.g. our own TTS) before listening
        val buf = ShortArray(FRAME)
        val out = ByteArrayOutputStream()
        val preroll = ArrayDeque<ByteArray>(PREROLL_FRAMES + 1)

        var speaking = false
        var speechFrames = 0
        var silenceAccum = 0L
        var totalSpeech = 0L
        var waited = 0L
        val frameMs = (FRAME * 1000L) / SAMPLE_RATE

        while (!cancelled) {
            val n = r.read(buf, 0, FRAME)
            if (n <= 0) { continue }
            val rms = rms(buf, n)
            val bytes = toBytes(buf, n)

            if (!speaking) {
                // adapt the noise floor while it's quiet
                noiseFloor = 0.96 * noiseFloor + 0.04 * rms
                // keep a short rolling preroll so the first syllable isn't clipped
                preroll.addLast(bytes)
                if (preroll.size > PREROLL_FRAMES) preroll.removeFirst()

                val startThreshold = maxOf(START_FLOOR, noiseFloor * 3.0)
                if (rms > startThreshold) {
                    speechFrames++
                    if (speechFrames >= 2) {
                        // begin: flush preroll then this frame
                        speaking = true
                        preroll.forEach { out.write(it) }
                        out.write(bytes)
                        totalSpeech = frameMs * (preroll.size + 1)
                        preroll.clear()
                    }
                } else {
                    speechFrames = 0
                    waited += frameMs
                    if (noSpeechTimeoutMs > 0 && waited >= noSpeechTimeoutMs) return null
                }
            } else {
                out.write(bytes)
                totalSpeech += frameMs
                val silenceThreshold = maxOf(SILENCE_FLOOR, noiseFloor * 2.0)
                if (rms < silenceThreshold) {
                    silenceAccum += frameMs
                    if (silenceAccum >= silenceMs) break
                } else {
                    silenceAccum = 0
                }
                if (totalSpeech >= maxMs) break
            }
        }
        if (cancelled) return null
        val data = out.toByteArray()
        // ignore clips too short to be a real command (< ~0.3 s of audio)
        if (data.size < SAMPLE_RATE / 3 * 2) return null
        return data
    }

    /** Discard any audio already buffered so a capture starts fresh. */
    private fun drain(r: AudioRecord) {
        val tmp = ShortArray(FRAME)
        var guard = 0
        while (guard++ < 300) {
            val n = r.read(tmp, 0, FRAME, AudioRecord.READ_NON_BLOCKING)
            if (n <= 0) break
        }
    }

    fun release() {
        cancelled = true
        val r = record
        record = null
        try { r?.stop() } catch (_: Exception) {}
        try { r?.release() } catch (_: Exception) {}
    }

    private fun rms(buf: ShortArray, n: Int): Double {
        var sum = 0.0
        for (i in 0 until n) { val v = buf[i].toDouble(); sum += v * v }
        return sqrt(sum / n)
    }

    private fun toBytes(buf: ShortArray, n: Int): ByteArray {
        val b = ByteArray(n * 2)
        for (i in 0 until n) {
            val s = buf[i].toInt()
            b[i * 2] = (s and 0xff).toByte()
            b[i * 2 + 1] = ((s shr 8) and 0xff).toByte()
        }
        return b
    }
}
