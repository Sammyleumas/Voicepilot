# VoicePilot AI

**Your phone should understand you, not the other way round.**

VoicePilot AI is an Android assistant that turns natural speech into safe, allowlisted actions on the phone: opening apps and settings, alarms, timers, reminders, notes, tasks, calendar events, WhatsApp messages (prepared for you to send), calls (dialer), flashlight, volume, Do Not Disturb, routines and a notification assistant.

It works offline for everyday commands. An AI provider (Google Gemini, directly or through the VoicePilot backend) is optional and only used for requests the on-device rules don't understand.

## Download

Latest Android test build: **[Releases › latest](https://github.com/Sammyleumas/Voicepilot/releases/latest)**. To build on demand, open **Actions › Build VoicePilot app › Run workflow**.

## Features

| Area | What works | Notes |
|---|---|---|
| Voice | Android `SpeechRecognizer` (live partial results, waveform, timeouts, cancellation), `TextToSpeech` replies, typed fallback everywhere | Offline recognition only reported when the device supports it |
| Understanding | On-device rule interpreter (English, Nigerian phrasing like “abeg”), optional Gemini via strict JSON | AI output is data, never code; unknown actions are dropped |
| Conversation | Follow-up questions for missing details, contact disambiguation, yes/no confirmation, cancel at any time | Never guesses a message recipient |
| Actions | 16 allowlisted actions with validation, risk levels and permissions | See [docs/INTEGRATIONS.md](docs/INTEGRATIONS.md) |
| Messaging | WhatsApp (Click to Chat to the resolved contact), SMS (to the resolved number), or any other named app such as Telegram, Messenger or Signal (text handed over via Android share) | You always tap **Send**; VoicePilot never claims it sent anything |
| Planning | Multi-step requests and routines with progress, partial-failure reports | Free plan: 3 steps per request |
| Memory | Contact nicknames (“my brother”), routines | User controlled; can be disabled and cleared |
| Notifications | Opt-in summaries, sender announcements, replies through the app's own reply action | In memory only |
| Activation | App, assistant overlay, Quick Settings tile, app shortcut, default-assistant role, optional hands-free “Hey VoicePilot” session | See wake-word limits in [docs/ANDROID_PERMISSIONS.md](docs/ANDROID_PERMISSIONS.md) |
| Privacy | Local Room/DataStore, Keystore-encrypted secrets, no backups, history retention, delete-all | See [docs/SECURITY.md](docs/SECURITY.md) |

## Requirements

- Android 10 (API 29) or newer device or emulator
- JDK 17+, Android SDK with platform 35 (Android Studio Ladybug or newer works)
- Optional: a Gemini API key, or a deployed VoicePilot backend (`backend/`)

## Project layout

```
.
├── core/      Pure Kotlin engine (no Android): interpreter, actions, planner, conversation, AI providers. Builds & tests on any JVM.
├── app/       Android app: Compose UI, Hilt, Room, DataStore, platform adapters and action handlers.
├── backend/   Optional dependency-free Node.js proxy that keeps the AI key on the server.
└── docs/      Architecture, permissions, AI engine, integrations, security, testing, roadmap, release.
```

## Build and run

```bash
./gradlew coreTest                 # core engine unit tests (no Android SDK needed: cd core && ./gradlew test)
./gradlew :app:assembleDebug       # debug APK → app/build/outputs/apk/debug/
./gradlew :app:installDebug        # install on a connected device
./gradlew :app:testDebugUnitTest :app:lintDebug
./gradlew :app:connectedDebugAndroidTest   # needs a device/emulator
```

The debug build installs as `com.voicepilot.ai.debug`, so it can sit next to a release build.

Optional configuration (never commit secrets):

- `local.properties` → `voicepilot.cloudBaseUrl=https://your-backend.example.com` (public URL baked into the build)
- `keystore.properties` → release signing (see [docs/RELEASE.md](docs/RELEASE.md))

### Setting up AI

1. **On-device only** (default): nothing to do.
2. **Gemini (your key)**: Settings › AI › AI provider › *Gemini (your API key)*, then paste a key from Google AI Studio. It is encrypted with the Android Keystore.
3. **VoicePilot Cloud**: deploy `backend/` (see its README), then set the Cloud URL and an access token in Settings › AI.

## Documentation

- [Architecture](docs/ARCHITECTURE.md) · [AI engine](docs/AI_ENGINE.md) · [Integrations](docs/INTEGRATIONS.md)
- [Android permissions](docs/ANDROID_PERMISSIONS.md) · [Security & privacy](docs/SECURITY.md)
- [Testing](docs/TESTING.md) · [Roadmap & status](docs/ROADMAP.md) · [Release](docs/RELEASE.md) · [Project state](PROJECT_STATE.md)
