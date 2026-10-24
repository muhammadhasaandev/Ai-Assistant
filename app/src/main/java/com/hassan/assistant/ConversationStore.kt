package com.hassan.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Remembers the conversation across wake-ups and across app restarts.
 * Only plain user/assistant text turns are stored (no tool-call chains), so the
 * history can be safely replayed into any future request as prior context.
 */
class ConversationStore(context: Context) {

    private val file = File(context.filesDir, "history.json")
    private val turns = ArrayList<JSONObject>()

    init { load() }

    private fun load() {
        turns.clear()
        if (!file.exists()) return
        try {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val role = o.optString("role")
                val content = o.optString("content")
                if ((role == "user" || role == "assistant") && content.isNotBlank()) {
                    turns.add(JSONObject().put("role", role).put("content", content))
                }
            }
        } catch (e: Exception) { /* start fresh on any corruption */ }
    }

    private fun save() {
        try {
            val arr = JSONArray()
            // keep only the most recent turns
            val start = (turns.size - MAX_TURNS).coerceAtLeast(0)
            for (i in start until turns.size) arr.put(turns[i])
            file.writeText(arr.toString())
        } catch (e: Exception) { /* ignore disk errors */ }
    }

    /** The remembered turns, ready to splice into a messages array after the system prompt. */
    fun recentMessages(): List<JSONObject> {
        val start = (turns.size - MAX_TURNS).coerceAtLeast(0)
        return turns.subList(start, turns.size).map {
            JSONObject().put("role", it.getString("role")).put("content", it.getString("content"))
        }
    }

    fun addUser(text: String) = add("user", text)
    fun addAssistant(text: String) = add("assistant", text)

    private fun add(role: String, text: String) {
        if (text.isBlank()) return
        turns.add(JSONObject().put("role", role).put("content", text))
        save()
    }

    fun clear() {
        turns.clear()
        try { file.delete() } catch (_: Exception) {}
    }

    val isEmpty: Boolean get() = turns.isEmpty()

    companion object {
        const val MAX_TURNS = 20
    }
}
