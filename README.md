# Hassan Assistant

A voice-controlled Android assistant. It runs in the background, wakes up when you
say **"Hassan Assistant"**, greets you, and then carries out spoken commands on your
phone — opening apps, sending WhatsApp messages (after you confirm), setting alarms,
adding calendar events, making calls, searching the web, and more. The thinking is
done by **Groq** (free LLM API) through your own API key.

## How to get the APK (no PC setup needed)

The app builds automatically on GitHub.

1. Push this project to a GitHub repository.
2. Go to the **Actions** tab → the **Build APK** workflow runs on every push.
   (You can also run it manually with **Run workflow**.)
3. When it turns green, open the **Releases** page of the repo → the **latest**
   release has **HassanAssistant.apk**. Download it on your phone and install it.
   (Open the APK → allow "install from unknown sources" when asked.)

The APK is signed with a fixed key kept in the repo, so each new build installs over
the old one without uninstalling.

## First-time setup inside the app

Open the app and work down the screen:

1. **Groq API key** – get a free key at console.groq.com (starts with `gsk_`),
   paste it, keep the model (default `openai/gpt-oss-120b`; alternatives
   `openai/gpt-oss-20b`, `qwen/qwen3.8-27b`), tap **Save & test API**. You
   should see "API OK ✓". (Model names depend on your Groq account's catalog.)
2. **Language & name** – choose اردو or English, set the name it calls you, and the
   wake words.
3. **Permissions** – tap each button and allow it:
   - Microphone & notifications
   - Display over other apps (the popup)
   - **Phone control (Accessibility)** – find "Hassan Assistant" in the list and turn
     it ON. This is what lets it tap and type inside WhatsApp, YouTube, etc.
   - Contacts, calls & calendar
   - Ignore battery optimisation (so it keeps listening)
4. **Start** – tap **▶ Start assistant**. A quiet notification stays in the tray.

## Using it

- Just say **"Hassan"** or **"assistant"** anywhere in a sentence — e.g. *"Hassan,
  open WhatsApp"* — and it wakes and runs it. Or tap **🎤 Talk** on the notification.
- **Continuous conversation:** once it wakes, it keeps listening for your next
  command without needing the wake word again. Stay quiet or say *"bas" / "thank
  you" / "bye"* to end the conversation.
- **Memory:** it remembers the conversation — across wake-ups and restarts — so you
  can refer back to earlier things. Say *"new topic"* / *"forget that"* to reset.

Listening uses a custom pipeline (microphone + voice-activity detection + Groq
Whisper speech-to-text) — no Google recognizer and no beeps.

### What it can do

- Open any app; run searches that actually execute (YouTube, Play Store, browser).
- WhatsApp: open a chat, search, type and send a message (always confirms first).
- Alarms, timers, calendar events, calls (confirms first), web search, maps.
- Flashlight on/off, media volume, and quick status (battery, time, date).
- Answer general questions in Urdu or English.
- Control other apps by reading the screen and tapping/typing (Accessibility).
- Examples: *"open YouTube"*, *"message Ali on WhatsApp that I'm on my way"* (it reads
  the text back and asks before sending), *"set an alarm for 6:30"*, *"add a meeting
  tomorrow at 4pm"*, *"call Ammi"* (asks before dialling), *"search for petrol price"*.

## Notes

- It never sends a WhatsApp message or places a call without asking you first.
- Urdu voice recognition and text-to-speech depend on your phone's Google app /
  speech engine having the Urdu pack installed. If Urdu TTS is missing it falls back
  to English speech.
- Built with Kotlin, min Android 8 (API 26), target Android 15 (API 35).

## Build locally (optional)

With Android Studio: open the project and press Run, or from a terminal:

```
./gradlew :app:assembleRelease
```

The APK lands in `app/build/outputs/apk/release/`.
