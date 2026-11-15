# PROJECT_STATE

_Last updated: 2026-11-15_

## Architecture (summary)

- `core/` pure Kotlin engine (interpreters, allowlisted actions, executor gate, planner, conversation state machine, AI providers, contacts, memory, subscription, voice abstractions). JUnit 5 tests.
- `app/` Android (Kotlin, Compose/Material 3, Hilt, Room, DataStore, Keystore). Package `com.voicepilot.ai`; minSdk 29, target/compile 35.
- `backend/` optional Node proxy for Gemini (Firebase or static-token auth, quotas).
- Details: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Current milestone

Phases 1–16 implemented; Phase 17 (testing/debugging) in progress against CI; Phase 19 needs owner accounts.

## Verification status

| Check | Where | Result |
|---|---|---|
| Core unit tests (62) | Local JVM + CI | Passing |
| Backend tests (5) | Local Node 22 | Passing |
| Android debug build, app unit tests, lint | GitHub Actions | See the latest run below |
| Instrumented tests (Room, Compose) | GitHub Actions emulator | See the latest run below |
| Physical devices | — | **Not done** (checklist in docs/TESTING.md) |
| Live Gemini call | — | **Not done** (no key in this environment) |

Latest CI run: _updated after each push; see GitHub Actions “VoicePilot Android”._

## Outstanding tasks

1. Run the device checklist (docs/TESTING.md) on Android 10/12/14+ incl. Samsung and Transsion devices.
2. Live-test Gemini with a real key; tune the system prompt on real utterances.
3. Firebase Auth sign-in for cloud mode; deploy backend.
4. Play Billing provider + server verification.
5. Store assets (screenshots, feature graphic) and hosted privacy policy.

## Next implementation instruction

If CI is green: install the debug APK artifact on a device and work through the checklist, logging failures as issues. If CI is red: open the failing job log, fix the compile/lint error in the named file, push, and re-check.
