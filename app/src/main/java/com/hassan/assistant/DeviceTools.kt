package com.hassan.assistant

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Device-level actions that do not need the accessibility service:
 * launching apps, setting alarms/timers, adding calendar events, dialling,
 * looking up a contact's number, opening URLs and settings.
 * Every method returns a short human-readable result string for Grok / the user.
 */
class DeviceTools(private val context: Context) {

    // ---- apps -----------------------------------------------------------

    /** Find an installed app by (partial) name and return its package + label. */
    fun findApp(name: String): Pair<String, String>? {
        val pm = context.packageManager
        val q = name.trim().lowercase()
        if (q.isEmpty()) return null
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(main, 0)
        var best: Pair<String, String>? = null
        var bestScore = Int.MAX_VALUE
        for (ri in apps) {
            val label = ri.loadLabel(pm).toString()
            val pkg = ri.activityInfo.packageName
            val l = label.lowercase()
            val score = when {
                l == q -> 0
                l.startsWith(q) -> 1
                l.contains(q) -> 2
                pkg.lowercase().contains(q) -> 3
                else -> continue
            }
            if (score < bestScore) { bestScore = score; best = pkg to label }
        }
        // common aliases
        if (best == null) {
            val alias = ALIASES[q]
            if (alias != null) {
                val ri = apps.firstOrNull { it.activityInfo.packageName == alias }
                if (ri != null) best = alias to ri.loadLabel(pm).toString()
            }
        }
        return best
    }

    fun openApp(name: String): String {
        val found = findApp(name) ?: return "I could not find an app called \"$name\" on the phone."
        val launch = context.packageManager.getLaunchIntentForPackage(found.first)
            ?: return "I found ${found.second} but could not open it."
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
        return "Opened ${found.second}."
    }

    // ---- alarms & timers ------------------------------------------------

    fun setAlarm(hour: Int, minute: Int, label: String?): String {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            if (!label.isNullOrBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            val t = String.format(Locale.US, "%02d:%02d", hour, minute)
            "Alarm set for $t${if (!label.isNullOrBlank()) " ($label)" else ""}."
        } catch (e: Exception) {
            "No alarm app could handle that."
        }
    }

    fun setTimer(seconds: Int, label: String?): String {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            if (!label.isNullOrBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            "Timer set for ${seconds / 60} min ${seconds % 60} sec."
        } catch (e: Exception) {
            "No timer app could handle that."
        }
    }

    // ---- calendar -------------------------------------------------------

    /**
     * @param startMillis epoch millis of the start
     * @param durationMin length in minutes
     */
    fun addCalendarEvent(title: String, startMillis: Long, durationMin: Int, location: String?): String {
        return try {
            val values = ContentValues().apply {
                put(CalendarContract.Events.DTSTART, startMillis)
                put(CalendarContract.Events.DTEND, startMillis + durationMin * 60_000L)
                put(CalendarContract.Events.TITLE, title)
                if (!location.isNullOrBlank()) put(CalendarContract.Events.EVENT_LOCATION, location)
                put(CalendarContract.Events.CALENDAR_ID, defaultCalendarId())
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            if (uri != null) {
                val fmt = SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault())
                "Added \"$title\" to your calendar on ${fmt.format(startMillis)}."
            } else {
                openCalendarInsert(title, startMillis, durationMin, location)
            }
        } catch (e: SecurityException) {
            openCalendarInsert(title, startMillis, durationMin, location)
        } catch (e: Exception) {
            "Could not add the event: ${e.message}"
        }
    }

    private fun openCalendarInsert(title: String, startMillis: Long, durationMin: Int, location: String?): String {
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, startMillis + durationMin * 60_000L)
            if (!location.isNullOrBlank()) putExtra(CalendarContract.Events.EVENT_LOCATION, location)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            "Opened the calendar with \"$title\" filled in — tap save to confirm."
        } catch (e: Exception) {
            "No calendar app is available."
        }
    }

    private fun defaultCalendarId(): Long {
        val proj = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY)
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI, proj, null, null,
            "${CalendarContract.Calendars.IS_PRIMARY} DESC"
        )?.use { c ->
            if (c.moveToFirst()) return c.getLong(0)
        }
        return 1L
    }

    // ---- contacts & calls ----------------------------------------------

    fun lookupContactNumber(name: String): String? {
        val uri = Uri.withAppendedPath(
            ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(name)
        )
        val proj = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
        return try {
            context.contentResolver.query(uri, proj, null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (e: SecurityException) {
            null
        }
    }

    fun dial(number: String, direct: Boolean): String {
        val action = if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL
        val intent = Intent(action, Uri.parse("tel:" + Uri.encode(number)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            if (direct) "Calling $number." else "Opened the dialler with $number."
        } catch (e: SecurityException) {
            context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "Opened the dialler with $number (call permission not granted)."
        } catch (e: Exception) {
            "Could not start a call."
        }
    }

    // ---- web, maps, media ----------------------------------------------

    fun openUrl(url: String): String {
        val fixed = if (url.startsWith("http")) url else "https://$url"
        return try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(fixed)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            "Opened $fixed."
        } catch (e: Exception) { "Could not open that link." }
    }

    fun webSearch(query: String): String {
        return try {
            val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra(MediaStore.EXTRA_MEDIA_ARTIST, query)
                putExtra("query", query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Searching the web for \"$query\"."
        } catch (e: Exception) {
            openUrl("https://www.google.com/search?q=" + Uri.encode(query))
        }
    }

    fun openMaps(place: String): String {
        return try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(place)))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            "Opening the map for \"$place\"."
        } catch (e: Exception) {
            openUrl("https://www.google.com/maps/search/" + Uri.encode(place))
        }
    }

    fun openSetting(which: String): String {
        val action = when (which.lowercase()) {
            "wifi", "wi-fi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "location", "gps" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "data", "mobile data" -> Settings.ACTION_DATA_ROAMING_SETTINGS
            "display", "brightness" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound", "volume" -> Settings.ACTION_SOUND_SETTINGS
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "airplane", "flight" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        return try {
            context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "Opened $which settings."
        } catch (e: Exception) { "Could not open that settings screen." }
    }

    /** Normalise a local number (leading 0) to international form for WhatsApp etc. */
    fun normaliseNumber(raw: String, countryCode: String): String {
        var n = raw.filter { it.isDigit() || it == '+' }
        if (n.startsWith("+")) return n.drop(1)
        if (n.startsWith("00")) return n.drop(2)
        if (n.startsWith("0")) return countryCode + n.drop(1)
        return n
    }

    companion object {
        private val ALIASES = mapOf(
            "whatsapp" to "com.whatsapp",
            "youtube" to "com.google.android.youtube",
            "chrome" to "com.android.chrome",
            "gmail" to "com.google.android.gm",
            "maps" to "com.google.android.apps.maps",
            "camera" to "com.android.camera2",
            "settings" to "com.android.settings"
        )
    }
}
