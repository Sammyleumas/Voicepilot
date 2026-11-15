# Roadmap and status

Status legend: **Done** = implemented and covered by automated tests that ran · **Built** = implemented, compiles in CI, not yet verified on a physical device · **Foundation** = interface/architecture in place, needs an external dependency · **Not started**.

## Phase status

| Phase | Status | Notes |
|---|---|---|
| 1 Repository inspection & architecture | Done | Own repository: Sammyleumas/Voicepilot |
| 2 Project init & build config | Done | Gradle KTS, version catalog, composite core build, debug/release variants, CI |
| 3 Design system & UI | Built | Theme, components, all screens; needs device screenshots review |
| 4 Onboarding & permissions | Built | Contextual permissions, 8-step onboarding |
| 5 Speech recognition & TTS | Built | Engine abstractions Done (tests); Android adapters need device testing |
| 6 AI provider integration | Done (core) / Built (app) | Gemini and cloud proxy tested with fake transport; no live Gemini call made from this environment |
| 7 Structured interpretation | Done | Local rules + LLM JSON, 23+ tests |
| 8 Action registry & execution | Done (core) / Built (handlers) | |
| 9 App launching & Android integrations | Built | 15 handlers |
| 10 WhatsApp workflows | Built | Click to Chat; Business Platform not implemented (see INTEGRATIONS) |
| 11 Notification assistant | Built | |
| 12 Multi-step planning | Done | Dependencies, partial failures, cancellation |
| 13 Personal memory | Done (core) / Built (Room) | |
| 14 Wake word & default assistant | Built / Foundation | Assistant role + hands-free session; always-on screen-off hotword not possible for third-party apps |
| 15 Subscription foundation | Foundation | Entitlements, usage limits Done; Play Billing needs Play Console |
| 16 Security hardening | Done | See SECURITY.md |
| 17 Testing & debugging | In progress | Core 62 + backend 5 passing; app CI results below |
| 18 Performance | Built | Lazy lists, no idle services, recognizer released on stop; not profiled on device |
| 19 Release preparation | Foundation | Signing config, R8, docs; needs owner accounts |

## CI results (last checked)

See PROJECT_STATE.md for the latest recorded run.

## External dependencies still required

- Physical Android devices for the checklist in TESTING.md.
- Gemini API key (for live AI testing) or a deployed backend + Firebase project.
- Google Play Console account (billing products, signing, listing).
- Optional: Picovoice (or similar) access key for a dedicated low-power wake-word engine.

## Next improvements

1. Firebase Authentication sign-in in the app for VoicePilot Cloud (replace pasted token).
2. Play Billing implementation of `BillingProvider` + server verification.
3. Dedicated keyword-spotting `WakeWordEngine` to replace the recognizer loop.
4. More languages and Nigerian Pidgin phrasing in `LocalRuleInterpreter`; localised strings.
5. Screenshot/UI tests for all screens; baseline profile for startup.
6. Shared rate-limit store for the backend; usage sync per account.
7. Contact nickname suggestions after repeated choices (with user approval).
