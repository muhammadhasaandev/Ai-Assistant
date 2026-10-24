package com.hassan.assistant

import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Custom speech-to-text using Groq's Whisper endpoint (OpenAI-compatible,
 * multipart/form-data). No Google recognizer involved. Call from a background
 * thread. Takes raw 16-bit PCM mono audio and returns the transcribed text.
 */
class WhisperClient(private val prefs: Prefs) {

    class ApiException(message: String) : Exception(message)

    /**
     * @param pcm16 little-endian 16-bit PCM mono samples.
     * @param sampleRate e.g. 16000.
     * @param bias optional hint words (names, app names) to improve accuracy.
     */
    fun transcribe(pcm16: ByteArray, sampleRate: Int, bias: String? = null): String {
        val key = prefs.apiKey
        if (key.isBlank()) throw ApiException("Groq API key is not set.")
        if (pcm16.isEmpty()) return ""

        val wav = wrapWav(pcm16, sampleRate)
        val boundary = "----HassanAssistant" + System.currentTimeMillis()
        val url = URL(prefs.apiBaseUrl + "/audio/transcriptions")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        try {
            DataOutputStream(conn.outputStream).use { out ->
                writeField(out, boundary, "model", WHISPER_MODEL)
                writeField(out, boundary, "response_format", "json")
                writeField(out, boundary, "temperature", "0")
                // language hint helps accuracy; omit "auto"
                val lang = if (prefs.isUrdu) "ur" else "en"
                writeField(out, boundary, "language", lang)
                if (!bias.isNullOrBlank()) writeField(out, boundary, "prompt", bias)
                // the audio file part
                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n")
                out.writeBytes("Content-Type: audio/wav\r\n\r\n")
                out.write(wav)
                out.writeBytes("\r\n")
                out.writeBytes("--$boundary--\r\n")
                out.flush()
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() } } ?: ""
            if (code !in 200..299) {
                val msg = try { JSONObject(text).optJSONObject("error")?.optString("message") ?: text }
                catch (e: Exception) { text }
                throw ApiException("Transcription error $code: ${msg.take(200)}")
            }
            return JSONObject(text).optString("text", "").trim()
        } finally {
            conn.disconnect()
        }
    }

    private fun writeField(out: DataOutputStream, boundary: String, name: String, value: String) {
        out.writeBytes("--$boundary\r\n")
        out.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
        out.write(value.toByteArray(Charsets.UTF_8))
        out.writeBytes("\r\n")
    }

    /** Prepend a 44-byte WAV header to raw PCM16 mono. */
    private fun wrapWav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val channels = 1
        val bits = 16
        val byteRate = sampleRate * channels * bits / 8
        val blockAlign = channels * bits / 8
        val dataLen = pcm.size
        val out = ByteArrayOutputStream(44 + dataLen)
        fun wStr(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun w32(v: Int) { out.write(v and 0xff); out.write((v shr 8) and 0xff); out.write((v shr 16) and 0xff); out.write((v shr 24) and 0xff) }
        fun w16(v: Int) { out.write(v and 0xff); out.write((v shr 8) and 0xff) }
        wStr("RIFF"); w32(36 + dataLen); wStr("WAVE")
        wStr("fmt "); w32(16); w16(1); w16(channels)
        w32(sampleRate); w32(byteRate); w16(blockAlign); w16(bits)
        wStr("data"); w32(dataLen)
        out.write(pcm)
        return out.toByteArray()
    }

    companion object {
        const val WHISPER_MODEL = "whisper-large-v3-turbo"
    }
}
