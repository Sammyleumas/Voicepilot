# Testing

## Commands

```bash
./gradlew coreTest                          # core engine (JUnit 5) — also: cd core && ./gradlew test
./gradlew :app:testDebugUnitTest            # app JVM tests
./gradlew :app:lintDebug                    # Android lint
./gradlew :app:connectedDebugAndroidTest    # instrumented: Room + Compose (device/emulator)
cd backend && npm test                      # backend proxy
```

CI (`.github/workflows/android.yml`) runs all of the above on every push to `main`, on pull requests and from the **Run workflow** button; the instrumented job uses an API 30 emulator. Results of each run are in the GitHub Actions tab; the status recorded in [ROADMAP.md](ROADMAP.md) is the last one checked.

## Automated coverage

| Suite | Location | What it covers |
|---|---|---|
| `LocalRuleInterpreterTest` (13) | core | Apps, the WhatsApp examples from the brief, reported-speech rewriting, alarms/timers, reminders (time splitting), notes/tasks/calendar, device controls, settings pages, sleep routine, compound commands, calls/memory/search, local answers |
| `AssistantSessionTest` (16) | core | Full conversation from the brief (ask message → find contact → confirm → prepare), duplicate contacts, unknown contact, aliases, decline, policies, sleep flow, permission pause/retry, disabled integrations, partial failures, plan limits, cancel, wake phrase, history toggle, retry |
| `EngineTest` (7) | core | Registry completeness, wire names, strict validation (unknown params, ranges, choices), risk/confirmation matrix, conditional capabilities, executor error mapping, plan dependency skipping |
| `AiTest` (10) | core | LLM JSON parsing and sanitising (drops unknown actions, model-supplied phone numbers), prompt contents, hybrid fallback, offline degradation, quota, rate limiter, Gemini request/response/error mapping, cloud proxy |
| `UtilitiesTest` (16) | core | Time and duration parsing, number words, name matching, contact resolution (ambiguous, weak, multiple numbers), phone normalisation, Click to Chat links, yes/no, wake phrase, routines, notification summaries, app resolution, memory handler |
| `DatabaseTest` (3) | app/androidTest | History round trip with retry request, alias normalisation/replace, routine JSON |
| `AssistantTurnTest` (2) | app/androidTest | Confirmation card preview + button, contact choice touch alternative |
| `app.test.js` (5) | backend | Proxy happy path (no key leak, no prompt logging), auth failures, validation, quotas, Firebase JWT verification incl. tampering |

## Physical device checklist

Run on Android 10, 12 and 14/15, including at least one Samsung and one Tecno/Infinix/itel device. Record device, Android version, result and notes.

| # | Scenario | Expected |
|---|---|---|
| 1 | Fresh install → onboarding → skip mic | Home works; typing works; mic tap explains and requests permission |
| 2 | Deny microphone twice (“don't ask again”) | Message + “Allow microphone” opens app settings; typing still works |
| 3 | “Open WhatsApp” with WhatsApp not installed | “I couldn't find an app called…”, history shows Failed |
| 4 | “Send Samuel a WhatsApp message saying I'm on my way” with two Samuels | Choice list with masked numbers; nothing opens before a choice and approval |
| 5 | Same with one Samuel | Confirmation card → WhatsApp opens with text prefilled; app says “Tap Send” |
| 5b | “Message Ada by SMS saying hi”, “Message Tunde on Telegram saying hi” | SMS app opens with number and text; Telegram opens its chat picker with the text |
| 6 | Contacts permission denied | Permission card; Retry after granting continues the same request |
| 7 | Airplane mode, AI provider = Gemini | Local commands work; unknown request explains no connection |
| 8 | Wrong Gemini key | “rejected the credentials” message |
| 9 | “I'm going to sleep” → “6:30” → yes | DND access requested if missing; alarm appears in clock app |
| 10 | “Remind me to drink water in 2 minutes”, lock the phone | Notification arrives (exact vs inexact noted) |
| 11 | Reboot with a pending reminder | Reminder still fires |
| 12 | Say “cancel” during a question; tap stop during execution | Nothing runs; state returns to Ready |
| 13 | Unsupported command (“transfer 5,000 naira to Ada”) | Clear refusal, nothing opened |
| 14 | Set as default assistant; long-press Home / assist gesture | Overlay opens and listens; screen-on only |
| 15 | Quick Settings tile | Overlay opens (Android 14 PendingIntent path) |
| 16 | Hands-free mode on, app open, say “Hey VoicePilot, open YouTube” | YouTube opens |
| 17 | Hands-free mode, app in background | Tap-to-talk notification appears, no screen launched |
| 18 | Hands-free mode, screen off / 30 min | Session stops, notification removed |
| 19 | Battery optimisation “restricted” (Samsung/Transsion) | Hands-free may be killed; document behaviour |
| 20 | Notification assistant with WhatsApp allowed | Summary lists senders; reply sends via WhatsApp's reply action |
| 21 | Large font (200%), TalkBack | All controls reachable and labelled; confirmation readable |
| 22 | Small phone (5") and tablet | No clipped content; overlay limited width |
| 23 | Delete all data | History, memory, notes, keys gone |
| 24 | Release (minified) build smoke test | Routines save/load (serialization), AI call works |

Locked-screen behaviour (14, 16) and manufacturer background restrictions (19) **have not been tested on hardware yet**.
