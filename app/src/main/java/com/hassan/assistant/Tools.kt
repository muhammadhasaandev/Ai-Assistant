package com.hassan.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * The set of actions Grok is allowed to call, described in OpenAI/xAI tool format,
 * plus the dispatcher that runs a chosen tool and returns a result string.
 */
object Tools {

    /** Context passed to the dispatcher so it can reach the device and talk back. */
    class Env(
        val device: DeviceTools,
        val prefs: Prefs,
        /** Ask the user yes/no out loud; returns true only on a clear "yes". */
        val confirm: (String) -> Boolean,
        /** Say something to the user mid-task (e.g. "opening WhatsApp..."). */
        val say: (String) -> Unit,
        /** Pause so an app has time to open / a screen to settle. */
        val waitMs: (Long) -> Unit,
        /** Wipe the remembered conversation. */
        val clearMemory: () -> Unit
    ) {
        val control: ControlService? get() = ControlService.get()
    }

    fun definitions(): JSONArray {
        val arr = JSONArray()
        arr.put(fn("open_app", "Open an installed app by name (e.g. WhatsApp, YouTube, Chrome, Camera).",
            obj("name" to strProp("The app's visible name"))
        , listOf("name")))

        arr.put(fn("read_screen",
            "Read what is currently on the screen. Use this before tapping or typing inside another app so you know what is there.",
            JSONObject(), emptyList()))

        arr.put(fn("tap",
            "Tap a button, menu item, chat or link on screen by its visible text or description. Read the screen first.",
            obj("text" to strProp("Visible text of the thing to tap")), listOf("text")))

        arr.put(fn("type_text",
            "Type text into the currently open text field (for example a WhatsApp message box or a search box). Set submit=true to also press the keyboard Search/Enter afterwards so a search actually runs.",
            obj("text" to strProp("The exact text to type"),
                "submit" to boolProp("Press Search/Enter after typing (use true for search boxes)")),
            listOf("text")))

        arr.put(fn("run_search",
            "Press the keyboard's Search/Enter/Go on the current text field to actually execute a search and show results. Call this after typing a query in a search box (YouTube, Play Store, browser, etc.).",
            JSONObject(), emptyList()))

        arr.put(fn("press_key",
            "Press a hardware/navigation key: back, home, recents, enter, scroll_down, scroll_up.",
            obj("key" to enumProp("Which key", listOf("back","home","recents","enter","scroll_down","scroll_up"))),
            listOf("key")))

        arr.put(fn("whatsapp_open_chat",
            "Open a specific person's chat in WhatsApp so you can read it or type a message. Use the contact name.",
            obj("contact" to strProp("The contact's name as saved on the phone")), listOf("contact")))

        arr.put(fn("whatsapp_send_message",
            "Type a message in the currently open WhatsApp chat and send it. ALWAYS confirms with the user before sending.",
            obj("message" to strProp("The message text to send")), listOf("message")))

        arr.put(fn("whatsapp_search",
            "Search inside WhatsApp (for a contact or a chat). Opens WhatsApp if needed.",
            obj("query" to strProp("What to search for")), listOf("query")))

        arr.put(fn("youtube_search",
            "Open YouTube, run a search for the query, and show the results. After this, read_screen and tap a video to play it if asked.",
            obj("query" to strProp("What to search on YouTube")), listOf("query")))

        arr.put(fn("set_alarm", "Set an alarm at a given time (24-hour clock).",
            obj("hour" to intProp("Hour 0-23"), "minute" to intProp("Minute 0-59"),
                "label" to strProp("Optional label")), listOf("hour","minute")))

        arr.put(fn("set_timer", "Start a countdown timer.",
            obj("seconds" to intProp("Total seconds"), "label" to strProp("Optional label")),
            listOf("seconds")))

        arr.put(fn("add_calendar_event",
            "Add an event/meeting to the calendar. Provide the start as a 24-hour time today or on a given date.",
            obj(
                "title" to strProp("Event title"),
                "year" to intProp("Year, e.g. 2026"),
                "month" to intProp("Month 1-12"),
                "day" to intProp("Day of month 1-31"),
                "hour" to intProp("Start hour 0-23"),
                "minute" to intProp("Start minute 0-59"),
                "duration_minutes" to intProp("Length in minutes (default 60)"),
                "location" to strProp("Optional location")
            ), listOf("title","hour","minute")))

        arr.put(fn("make_call",
            "Call a phone number or a saved contact. ALWAYS confirms with the user before dialling.",
            obj("contact" to strProp("Contact name to look up"),
                "number" to strProp("Or a raw phone number")), emptyList()))

        arr.put(fn("web_search", "Search the web with the default engine.",
            obj("query" to strProp("Search text")), listOf("query")))

        arr.put(fn("open_url", "Open a website URL in the browser.",
            obj("url" to strProp("The URL")), listOf("url")))

        arr.put(fn("open_maps", "Open a place or address in Maps.",
            obj("place" to strProp("Place or address")), listOf("place")))

        arr.put(fn("open_setting",
            "Open a phone settings screen: wifi, bluetooth, location, data, display, sound, battery, airplane.",
            obj("which" to strProp("Which settings screen")), listOf("which")))

        arr.put(fn("flashlight", "Turn the phone's flashlight/torch on or off.",
            obj("on" to boolProp("true = on, false = off")), listOf("on")))

        arr.put(fn("set_volume", "Change media volume: up, down, mute, unmute, max.",
            obj("action" to enumProp("Volume action", listOf("up","down","mute","unmute","max"))),
            listOf("action")))

        arr.put(fn("device_status",
            "Get the current battery percentage, time and date. Use for questions like 'how much battery' or 'what time is it'.",
            JSONObject(), emptyList()))

        arr.put(fn("clear_memory",
            "Forget the earlier conversation and start fresh. Use when the user says things like 'new topic', 'forget that', 'naya chat'.",
            JSONObject(), emptyList()))

        arr.put(fn("finish",
            "Call this when the task is done or when you just want to answer the user in words. Put your spoken reply in 'reply'.",
            obj("reply" to strProp("What to say to the user, in their language")), listOf("reply")))

        return arr
    }

    /** Runs one tool call and returns a short result string for the model. */
    fun dispatch(name: String, args: JSONObject, env: Tools.Env): String {
        return try {
            when (name) {
                "open_app" -> env.device.openApp(args.getString("name"))
                "read_screen" -> env.control?.describeScreen()
                    ?: "The phone-control (accessibility) service is OFF. Ask Hassan to turn on Hassan Assistant in Accessibility settings."
                "tap" -> {
                    val c = env.control ?: return accessibilityOff()
                    if (c.tapByText(args.getString("text"))) "Tapped \"${args.getString("text")}\"."
                    else "Could not find \"${args.getString("text")}\" on screen. Read the screen again."
                }
                "type_text" -> {
                    val c = env.control ?: return accessibilityOff()
                    val typed = c.typeText(args.getString("text"))
                    if (!typed) "No text field was focused. Tap the input box first."
                    else if (args.optBoolean("submit", false)) {
                        env.waitMs(400)
                        if (c.submitSearch()) "Typed the text and ran the search." else "Typed the text (couldn't find a search button)."
                    } else "Typed the text."
                }
                "run_search" -> {
                    val c = env.control ?: return accessibilityOff()
                    if (c.submitSearch()) "Search submitted." else "Couldn't find a search/enter button; try tapping the result directly."
                }
                "press_key" -> {
                    val c = env.control ?: return accessibilityOff()
                    when (args.getString("key")) {
                        "back" -> c.pressBack()
                        "home" -> c.pressHome()
                        "recents" -> c.openRecents()
                        "scroll_down" -> c.scrollForward()
                        "scroll_up" -> c.scrollBackward()
                        "enter" -> c.tapByText("Send").let { if (it) true else c.scrollForward() }
                        else -> false
                    }
                    "Done."
                }
                "whatsapp_open_chat" -> whatsappOpenChat(args.getString("contact"), env)
                "whatsapp_send_message" -> whatsappSend(args.getString("message"), env)
                "whatsapp_search" -> whatsappSearch(args.getString("query"), env)
                "youtube_search" -> youtubeSearch(args.getString("query"), env)
                "flashlight" -> env.device.flashlight(args.optBoolean("on", true))
                "set_volume" -> env.device.setVolume(args.getString("action"))
                "device_status" -> env.device.deviceStatus()
                "clear_memory" -> { env.clearMemory(); "Earlier conversation cleared." }
                "set_alarm" -> env.device.setAlarm(
                    args.getInt("hour"), args.getInt("minute"), args.optString("label", null))
                "set_timer" -> env.device.setTimer(args.getInt("seconds"), args.optString("label", null))
                "add_calendar_event" -> addEvent(args, env)
                "make_call" -> makeCall(args, env)
                "web_search" -> env.device.webSearch(args.getString("query"))
                "open_url" -> env.device.openUrl(args.getString("url"))
                "open_maps" -> env.device.openMaps(args.getString("place"))
                "open_setting" -> env.device.openSetting(args.getString("which"))
                "finish" -> "FINISH:" + args.optString("reply", "")
                else -> "Unknown tool: $name"
            }
        } catch (e: Exception) {
            "Error running $name: ${e.message}"
        }
    }

    private fun accessibilityOff() =
        "The phone-control (accessibility) service is OFF, so I cannot tap or type in other apps yet. Ask Hassan to enable Hassan Assistant under Settings > Accessibility."

    // ---- WhatsApp flows -------------------------------------------------

    private fun whatsappOpenChat(contact: String, env: Tools.Env): String {
        val c = env.control ?: return accessibilityOff()
        env.device.openApp("whatsapp")
        env.waitMs(2200)
        // tap search, type the name, open the first result
        if (!c.tapByText("Search")) c.tapByText("search")
        env.waitMs(800)
        c.typeText(contact)
        env.waitMs(1400)
        val opened = c.tapByText(contact)
        env.waitMs(1200)
        return if (opened) "Opened the chat with $contact."
        else "I searched WhatsApp for \"$contact\". Read the screen to see the matches and tap the right one."
    }

    private fun whatsappSend(message: String, env: Tools.Env): String {
        val c = env.control ?: return accessibilityOff()
        // confirm first — never send without asking
        val prompt = if (env.prefs.isUrdu)
            "میں یہ میسج بھیجوں: $message ۔ ہاں یا نہیں؟"
        else
            "Should I send this message: $message ? Say yes or no."
        if (!env.confirm(prompt)) return "Hassan said not to send it. Message was NOT sent."
        // find the message box, type, then tap send
        val typed = c.typeText(message)
        if (!typed) return "I could not find the message box. Make sure a chat is open."
        env.waitMs(500)
        val sent = c.tapByText("Send") || c.tapByText("send")
        return if (sent) "Message sent to the open chat." else "Typed the message but could not find the send button."
    }

    private fun whatsappSearch(query: String, env: Tools.Env): String {
        val c = env.control ?: return accessibilityOff()
        env.device.openApp("whatsapp")
        env.waitMs(2200)
        if (!c.tapByText("Search")) c.tapByText("search")
        env.waitMs(700)
        c.typeText(query)
        env.waitMs(1000)
        return "Searched WhatsApp for \"$query\". Results appear live — read the screen and tap the right one."
    }

    private fun youtubeSearch(query: String, env: Tools.Env): String {
        val c = env.control ?: return accessibilityOff()
        env.device.openApp("youtube")
        env.waitMs(2500)
        // tap the search icon/box
        if (!c.tapByText("Search")) c.tapByText("search")
        env.waitMs(900)
        c.typeText(query)
        env.waitMs(700)
        val ran = c.submitSearch()
        env.waitMs(1500)
        return if (ran) "Searched YouTube for \"$query\" and opened the results. Read the screen and tap a video to play it."
        else "Typed \"$query\" in YouTube search. Read the screen; you may need to tap the search key or a result."
    }

    // ---- calendar / call -----------------------------------------------

    private fun addEvent(args: JSONObject, env: Tools.Env): String {
        val cal = Calendar.getInstance()
        val year = args.optInt("year", cal.get(Calendar.YEAR))
        val month = args.optInt("month", cal.get(Calendar.MONTH) + 1)
        val day = args.optInt("day", cal.get(Calendar.DAY_OF_MONTH))
        cal.set(year, month - 1, day, args.getInt("hour"), args.getInt("minute"), 0)
        cal.set(Calendar.MILLISECOND, 0)
        val dur = args.optInt("duration_minutes", 60).coerceIn(5, 24 * 60)
        return env.device.addCalendarEvent(
            args.getString("title"), cal.timeInMillis, dur, args.optString("location", null))
    }

    private fun makeCall(args: JSONObject, env: Tools.Env): String {
        val contact = args.optString("contact", "")
        var number = args.optString("number", "")
        var who = number
        if (number.isBlank() && contact.isNotBlank()) {
            val found = env.device.lookupContactNumber(contact)
                ?: return "I could not find a number saved for \"$contact\"."
            number = found
            who = contact
        }
        if (number.isBlank()) return "No contact or number was given."
        val prompt = if (env.prefs.isUrdu) "میں $who کو کال کروں؟ ہاں یا نہیں؟"
        else "Should I call $who? Say yes or no."
        if (!env.confirm(prompt)) return "Hassan said not to call. No call was made."
        return env.device.dial(number, direct = true)
    }

    // ---- JSON schema helpers -------------------------------------------

    private fun fn(name: String, desc: String, props: JSONObject, required: List<String>): JSONObject {
        val params = JSONObject().apply {
            put("type", "object")
            put("properties", props)
            if (required.isNotEmpty()) put("required", JSONArray(required))
        }
        val function = JSONObject().apply {
            put("name", name)
            put("description", desc)
            put("parameters", params)
        }
        return JSONObject().put("type", "function").put("function", function)
    }

    private fun obj(vararg pairs: Pair<String, JSONObject>): JSONObject {
        val o = JSONObject()
        for ((k, v) in pairs) o.put(k, v)
        return o
    }

    private fun strProp(desc: String) = JSONObject().put("type", "string").put("description", desc)
    private fun intProp(desc: String) = JSONObject().put("type", "integer").put("description", desc)
    private fun boolProp(desc: String) = JSONObject().put("type", "boolean").put("description", desc)
    private fun enumProp(desc: String, values: List<String>) =
        JSONObject().put("type", "string").put("description", desc).put("enum", JSONArray(values))
}
