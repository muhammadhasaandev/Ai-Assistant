package com.hassan.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal client for the xAI (Grok) chat completions API, which follows the
 * OpenAI format: messages + tools in, an assistant message (text or tool calls) out.
 * Must be called from a background thread.
 */
class GrokClient(private val prefs: Prefs) {

    class ApiException(message: String) : Exception(message)

    fun chat(messages: JSONArray, tools: JSONArray?): JSONObject {
        val key = prefs.apiKey
        if (key.isBlank()) throw ApiException("Grok API key is not set. Open the app and paste your key.")

        val body = JSONObject().apply {
            put("model", prefs.model)
            put("messages", messages)
            put("temperature", 0.3)
            if (tools != null && tools.length() > 0) {
                put("tools", tools)
                put("tool_choice", "auto")
            }
        }

        val conn = (URL(prefs.apiBaseUrl + "/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 90_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer $key")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let { s ->
                BufferedReader(InputStreamReader(s, Charsets.UTF_8)).use { it.readText() }
            } ?: ""
            if (code !in 200..299) {
                val msg = try {
                    JSONObject(text).optJSONObject("error")?.optString("message")
                        ?: JSONObject(text).optString("error", text)
                } catch (e: Exception) {
                    text
                }
                throw ApiException("Grok API error $code: ${msg.take(300)}")
            }
            val json = JSONObject(text)
            val choices = json.optJSONArray("choices")
            if (choices == null || choices.length() == 0) throw ApiException("Grok returned no answer.")
            return choices.getJSONObject(0).getJSONObject("message")
        } finally {
            conn.disconnect()
        }
    }

    /** Quick check used by the "Test API" button. Returns the reply text. */
    fun test(): String {
        val msgs = JSONArray().put(
            JSONObject().put("role", "user").put("content", "Reply with just the word OK.")
        )
        return chat(msgs, null).optString("content", "").trim()
    }
}
