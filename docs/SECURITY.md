# Security and privacy

## Principles

1. **Allowlist only.** Nothing outside `ActionId` can run. AI output is parsed as data and re-validated.
2. **The model proposes, VoicePilot decides.** Risk, confirmation, permissions and recipient phone numbers are set locally.
3. **Truthful results.** `Prepared` vs `Completed` distinguishes “I opened WhatsApp with your message” from “done”.
4. **Data minimisation.** Local by default, no backups, retention limits, delete-all.

## Risk classification and confirmation

| Risk | Examples | Default (Messages & sensitive actions) | “Every action” | “High only” |
|---|---|---|---|---|
| Low | open app/settings, alarms, timers, notes, flashlight, volume | runs | asks | runs |
| Medium | WhatsApp message, call, calendar event, DND, remembering a nickname | asks | asks | runs |
| High | sending external communications, deleting data, payments, sharing personal data | **always asks** | asks | asks |

No high-risk action is currently implemented as automatic; notification replies are sent only after the user types the reply and taps **Send**. Payments, file deletion and lock-screen bypass are explicitly out of scope and the AI prompt marks them unsupported.

## Secrets

- User Gemini key and cloud token: AES-256-GCM, key in Android Keystore (`SecretStore`), never logged, excluded from backup.
- No private keys in the APK. `CLOUD_BASE_URL` is a public URL only.
- Backend keeps `GEMINI_API_KEY` in its environment and never returns or logs it.

## Network

- HTTPS only (`network_security_config.xml`, transport rejects `http://`).
- Only the command text (plus up to 4 recent turns) is sent to the AI provider. Contacts, history and notifications are never sent.
- Response size capped at 512 KB on device.

## Local data

| Data | Store | Control |
|---|---|---|
| Settings | DataStore | Settings |
| History (utterance, result, retry request without phone numbers) | Room | Toggle, retention (1–365 days), clear |
| Memory (nicknames), routines | Room | Toggle, edit, clear |
| Notes, tasks, reminders | Room | Delete individually or all |
| Notifications | Memory only | Allow list, clear |
| Secrets | Keystore-encrypted prefs | Remove in Settings, delete all |

`allowBackup=false` and `data_extraction_rules.xml` exclude everything from cloud backup and device transfer.

## Logging

No prompts, messages, contacts or keys are logged. Release builds strip `Log.d/v`. Backend logs contain only event type, model, status and token counts.

## Backend

Bearer auth (Firebase ID tokens verified with Google JWKs, RS256, audience/issuer/expiry checks; or SHA-256 compared static tokens), model allowlist, body and message limits, per-user rate limit and daily quota, `Cache-Control: no-store`.

## Known gaps

- Backend rate limits are in memory (per instance). Use Redis/Firestore when scaling horizontally.
- Firebase Authentication sign-in is not yet implemented in the app; cloud mode uses a pasted token.
- No certificate pinning (would complicate key rotation; consider for the backend domain).
