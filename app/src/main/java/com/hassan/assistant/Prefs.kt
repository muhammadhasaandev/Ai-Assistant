package com.hassan.assistant

import android.content.Context
import android.content.SharedPreferences

/** All user settings in one place. */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("assistant_prefs", Context.MODE_PRIVATE)

    var userName: String
        get() = sp.getString("user_name", "Hassan") ?: "Hassan"
        set(v) = sp.edit().putString("user_name", v).apply()

    /** "ur" or "en" */
    var language: String
        get() = sp.getString("language", "ur") ?: "ur"
        set(v) = sp.edit().putString("language", v).apply()

    var apiKey: String
        get() = sp.getString("api_key", "") ?: ""
        set(v) = sp.edit().putString("api_key", v.trim()).apply()

    var model: String
        get() = sp.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) = sp.edit().putString("model", v.trim().ifEmpty { DEFAULT_MODEL }).apply()

    var apiBaseUrl: String
        get() = sp.getString("api_base", DEFAULT_BASE) ?: DEFAULT_BASE
        set(v) = sp.edit().putString("api_base", v.trim().trimEnd('/').ifEmpty { DEFAULT_BASE }).apply()

    /** Comma separated wake phrases. */
    var wakeWords: String
        get() = sp.getString("wake_words", DEFAULT_WAKE) ?: DEFAULT_WAKE
        set(v) = sp.edit().putString("wake_words", v.ifBlank { DEFAULT_WAKE }).apply()

    /** Country calling code used when a contact number starts with 0. */
    var countryCode: String
        get() = sp.getString("country_code", "92") ?: "92"
        set(v) = sp.edit().putString("country_code", v.filter { it.isDigit() }.ifEmpty { "92" }).apply()

    var muteBeeps: Boolean
        get() = sp.getBoolean("mute_beeps", true)
        set(v) = sp.edit().putBoolean("mute_beeps", v).apply()

    var serviceEnabled: Boolean
        get() = sp.getBoolean("service_enabled", false)
        set(v) = sp.edit().putBoolean("service_enabled", v).apply()

    val isUrdu: Boolean get() = language == "ur"

    val speechLocaleTag: String get() = if (isUrdu) "ur-PK" else "en-US"

    fun wakePhraseList(): List<String> =
        wakeWords.split(',', '،').map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    companion object {
        const val DEFAULT_MODEL = "grok-4.5"
        const val DEFAULT_BASE = "https://api.x.ai/v1"
        const val DEFAULT_WAKE =
            "hassan assistant, my assistant, hasan assistant, حسن اسسٹنٹ, حسن اسسٹینٹ, مائی اسسٹنٹ, مائی اسسٹینٹ"
    }
}
