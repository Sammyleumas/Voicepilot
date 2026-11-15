# Integrations

All actions are defined in `core/.../action/BuiltInActions.kt` and implemented in `app/.../automation/Handlers.kt`. Each can be disabled in Settings › Automation › Integrations.

| Action (`wireName`) | Risk | Outcome | Android mechanism | Limitations |
|---|---|---|---|---|
| `open_app` | Low | Completes | Launcher intent via `PackageManager` | Fuzzy name match + common aliases |
| `open_settings` | Low | Completes | `Settings.ACTION_*` / `Settings.Panel` | Falls back to main Settings if a page isn't exposed |
| `launch_camera` | Low | Hand-off | `INTENT_ACTION_STILL_IMAGE_CAMERA` / `VIDEO_CAMERA` | User takes the photo |
| `set_flashlight` | Low | Completes | `CameraManager.setTorchMode` | Fails if another app holds the camera |
| `set_volume` | Low | Completes | `AudioManager` media stream | Fixed-volume devices refuse |
| `set_alarm` | Low | Completes | `AlarmClock.ACTION_SET_ALARM` (skip UI) | Depends on the clock app honouring the intent |
| `set_timer` | Low | Completes | `AlarmClock.ACTION_SET_TIMER` | Same |
| `create_reminder` | Low | Completes | Room + `AlarmManager` + notification | Inexact without exact-alarm permission |
| `create_calendar_event` | Medium | Hand-off | `ACTION_INSERT` on `CalendarContract.Events` | User taps Save (no calendar write permission requested) |
| `save_note`, `create_task` | Low | Completes | Room | Viewable in Notes & tasks |
| `prepare_whatsapp_message` | Medium | Hand-off | `https://wa.me/<number>?text=…` with `setPackage(com.whatsapp / w4b)` | **User taps Send.** No reading of chats. |
| `prepare_sms` | Medium | Hand-off | `ACTION_SENDTO smsto:` with `sms_body` | User taps Send in their SMS app |
| `share_message_to_app` | Medium | Hand-off | `ACTION_SEND text/plain` to the named app (Telegram, Messenger, Signal, Instagram, Gmail, Slack, Teams, imo…) | These apps don't let other apps choose the chat: the user picks it and taps Send |
| `dial_contact` | Medium | Hand-off | `ACTION_DIAL` | User taps Call |
| `set_do_not_disturb` | Medium | Completes | `NotificationManager.setInterruptionFilter` | Needs DND access |
| `web_search` | Low | Hand-off | `ACTION_WEB_SEARCH`, browser fallback | — |
| `remember_alias` | Medium | Completes | Room memory | Contact must exist; memory toggle respected |

## Choosing the messaging app

“Message Ada saying …” uses the default messaging app (Settings › Automation, WhatsApp or SMS). Naming an app picks it for that request: “… on Telegram”, “by SMS”, “send Ada a Messenger message …”. SMS and WhatsApp resolve the contact's number on-device; other apps receive only the text.

## WhatsApp

- **Personal WhatsApp**: Click to Chat is the only supported route. It opens the chat with text prefilled; it does not send. VoicePilot's messages always say “Tap Send to deliver it”.
- **WhatsApp Business Platform (Cloud API)** is a different product: it sends from a business number to customers who opted in, with approved templates outside the 24-hour window, and requires a Meta business account, phone number registration and a server. It cannot control a personal account. A future `MessagingProvider` adapter could add it on the backend; it is **not implemented**.
- VoicePilot never reads WhatsApp databases, never uses accessibility automation to press Send, and never touches encryption.

## Contacts

`AndroidContactDirectory` queries only display name and phone columns with a SQL pre-filter; `ContactResolver` scores matches and returns `Resolved` only for a single strong match. Ambiguous names, several numbers or weak matches are always shown to the user. Local numbers are converted to international form using the configured calling code (default 234).

## Notification assistant

`NotificationListenerService`, only for apps on the user's allow list. Items are kept in memory (max 50), summarised (`NotificationSummarizer`), optionally announced (sender only), and can be replied to through the notification's own `RemoteInput` action after the user types and taps Send.

## Google Play Billing (not configured)

`BillingProvider` is the interface; `UnconfiguredBillingProvider` reports billing as unavailable and never grants Premium. To enable:
1. Create the app and a subscription product (`voicepilot_premium_monthly`) in Play Console.
2. Add `com.android.billingclient:billing-ktx`, implement `BillingProvider` with `BillingClient` (query `ProductDetails`, `launchBillingFlow`, acknowledge purchases).
3. Verify purchase tokens on the backend (Play Developer API) before trusting `ACTIVE`.
4. Bind the new provider in `BindingsModule`.

## Backend proxy

See `backend/README.md`. Auth via Firebase ID tokens (production) or static tokens (private deployments).
