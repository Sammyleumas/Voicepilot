# Android permissions and platform limits

VoicePilot asks for each permission only when a feature needs it, after explaining why. Everything except voice has a touch alternative.

| Permission / access | Why | When it's requested | If denied |
|---|---|---|---|
| `RECORD_AUDIO` | Hear voice commands | First mic tap or onboarding voice step | Typing still works |
| `READ_CONTACTS` | Find message/call recipients | First message or call to a name | Say a phone number instead |
| `POST_NOTIFICATIONS` (13+) | Show reminders and the hands-free indicator | First reminder | Reminders can't be shown |
| Do Not Disturb access (`ACCESS_NOTIFICATION_POLICY`) | Turn DND on/off | First DND command (system settings page) | DND commands fail with an explanation |
| Notification access (listener) | Notification assistant | Turning the assistant on (system settings page) | Feature stays off |
| `SCHEDULE_EXACT_ALARM` | Exact reminder times | Settings › Automation | Reminders are inexact (can be minutes late) |
| `SET_ALARM` | Alarms/timers via the clock app | Normal permission, no prompt | — |
| `FOREGROUND_SERVICE_MICROPHONE` | Optional hands-free session | Starting hands-free mode | — |
| `RECEIVE_BOOT_COMPLETED` | Re-arm reminders after restart | Normal permission | — |
| `INTERNET` | Optional AI provider | Normal permission | On-device commands still work |

No `QUERY_ALL_PACKAGES`: installed apps are discovered through a `<queries>` launcher intent, plus explicit WhatsApp packages.

## Speech recognition on different phones

VoicePilot uses the phone's default speech recognizer. If it reports “busy” (common when another app holds the microphone, or on some Tecno/Infinix/itel builds), VoicePilot retries twice and then opens the phone's own speech popup (`RecognizerIntent`) instead. Settings › Voice › *Use the phone's speech popup* makes the popup the default.

## Things Android does not allow (and how VoicePilot handles them)

- **Toggling Wi‑Fi, Bluetooth, mobile data, airplane mode** directly: since Android 10, ordinary apps can't. VoicePilot opens the matching settings panel and says so.
- **Sending WhatsApp messages** from a personal account: there is no public API. VoicePilot opens the chat with the message prefilled and asks you to tap Send.
- **Placing calls** without the user: VoicePilot opens the dialer (`ACTION_DIAL`), you tap Call. (`CALL_PHONE` is intentionally not requested.)
- **Starting screens from the background** (Android 10+): actions run while a VoicePilot screen is visible. A hands-free detection in the background shows a tap-to-talk notification instead.
- **Lock screen**: VoicePilot never bypasses it. The assistant overlay does not declare `supportsLaunchVoiceAssistFromKeyguard`.

## Wake word: what is and isn't possible

| Mode | How | Limits |
|---|---|---|
| Default digital assistant | `VoiceInteractionService` + `RecognitionService` (delegates to the device recognizer) + `ACTION_ASSIST` activity. User selects VoicePilot in *Settings › Apps › Default apps › Digital assistant app*. | The assist gesture / long-press Home opens VoicePilot; it listens immediately. Behaviour of the picker varies by manufacturer. |
| Hands-free session | `HandsFreeService` (foreground, type `microphone`) loops the speech recognizer and matches “Hey VoicePilot”. | Must be started while VoicePilot is open (Android 14 rule for microphone services). Stops after 30 minutes or when the screen turns off. Some recognizers play a beep each cycle. Costs battery. |
| Always-on, screen-off hotword | `AlwaysOnHotwordDetector` / `HotwordDetectionService` | **Not available**: requires system-privileged sound models available only to preinstalled assistants. A third-party keyword spotter (e.g. Picovoice Porcupine) could replace the recognizer loop through the `WakeWordEngine` interface but still needs a microphone foreground service. |
| Quick Settings tile, app shortcut | `TalkTileService`, `shortcuts.xml` | Always available |
| Home screen Talk button | 1x1 widget `TalkWidgetProvider`, pinned shortcut via `ShortcutManagerCompat.requestPinShortcut` (Settings › Voice, onboarding) | The launcher asks the user to confirm; some launchers only allow adding widgets by hand |

Locked-screen and screen-off behaviour **has not been verified on hardware**; see the device checklist in [TESTING.md](TESTING.md).

## Battery and background behaviour

No service runs unless the user starts hands-free mode or grants notification access. Reminders use `AlarmManager` (no polling). Speech recognizers are destroyed as soon as listening ends, and screens stop listening in `onStop`.
