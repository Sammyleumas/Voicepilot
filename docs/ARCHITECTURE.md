# Architecture

VoicePilot follows Clean Architecture with MVVM in the UI. The key decision is a **pure Kotlin `core` build** that holds all assistant logic and has no Android dependency, so it is fast to test and impossible to couple to UI or platform details.

```
┌──────────────────────────── app (Android) ────────────────────────────┐
│  UI (Compose, Material 3)          ViewModels (Hilt)                   │
│  Home · Conversation · Assist ──▶  AssistantViewModel ─┐              │
│  Onboarding · Settings · Library   SettingsViewModel   │              │
│                                    LibraryViewModel    │              │
│  Platform adapters                                     ▼              │
│  SpeechRecognizer · TextToSpeech · Contacts · PackageManager          │
│  HttpURLConnection · Keystore · DataStore · Room · AlarmManager       │
│  Action handlers (intents, CameraManager, AudioManager, DND…)         │
│  Services: VoiceInteractionService · RecognitionService · QS tile     │
│            HandsFreeService · NotificationListenerService             │
└──────────────────────────────┬────────────────────────────────────────┘
                               │ implements interfaces from
┌──────────────────────────────▼───────── core (pure Kotlin) ───────────┐
│ conversation/AssistantSession   multi-turn state machine              │
│ interpret/  LocalRuleInterpreter · LlmCommandInterpreter · Hybrid     │
│ llm/        LlmProvider · GeminiProvider · CloudProxyProvider · limits │
│ action/     ActionRegistry (allowlist) · ActionDefinition · Executor  │
│ planner/    TaskPlan · PlanRunner · ActionDescriber                    │
│ contacts/   ContactResolver · PhoneNumberNormalizer · CandidateChooser │
│ memory/ history/ subscription/ notifications/ voice/ settings/ time/  │
└───────────────────────────────────────────────────────────────────────┘
```

## Modules

| Build | Purpose | Tests |
|---|---|---|
| `core` | Domain models, interpreters, planner, executor gate, AI providers, voice abstractions | JUnit 5 + coroutines-test, runs on any JVM |
| `app` | Android implementation and UI, Hilt DI, Room DB, DataStore | Instrumented (Room, Compose) |
| `backend` | Optional AI proxy (Node 20+, no dependencies) | `node --test` |

`core` is included through a Gradle composite build (`includeBuild("core")`), and the app depends on `com.voicepilot:core`.

## Data flow of a command

1. **Input**: `AndroidSpeechRecognitionEngine` emits `SpeechEvent`s (level, partial, final) or the user types.
2. **Session**: `AssistantSession.submit()` strips the wake phrase, checks pending state (a question, a contact choice, a confirmation) and otherwise interprets the text.
3. **Interpretation**: `HybridInterpreter` tries `LocalRuleInterpreter` first. Only if nothing matches, and AI is configured and within quota and rate limits, it calls `LlmCommandInterpreter`. User routines are matched before both.
4. **Planning**: for each `ActionRequest`, the session resolves recipients (`ContactResolver`, aliases from memory), then runs `ActionExecutor.preflight()`: allowlist → user-disabled actions → handler availability → strict parameter validation → capabilities → confirmation policy. Missing parameters become one short question each.
5. **Confirmation**: if any step needs approval, the session shows a `TaskPlan` preview. Nothing runs until the user says yes or taps *Go ahead*.
6. **Execution**: `PlanRunner` runs steps in order with dependency skipping and cancellation; each handler returns `Completed`, `Prepared` (another app finishes it), `Failed` or `Cancelled`.
7. **Report**: one truthful summary is spoken and shown; each step is written to history (if enabled).

## Assistant states

`Idle → Processing → Planning → (AwaitingInput | AwaitingChoice | AwaitingConfirmation | NeedsPermission) → Executing → Success | Error`. “Listening” is a UI state owned by `AssistantViewModel`.

## Execution engine

Every action has an `ActionDefinition` (id, typed parameters, risk, required capabilities, outcome kind) in `BuiltInActions`, and an `ActionHandler` in the app. `ActionExecutor` is the only path to a handler and re-validates even if a caller skipped preflight. Handler exceptions become `Failed` results; `CancellationException` propagates.

## Dependency injection

`di/AppModule.kt` wires the core objects with Hilt: the handler map, executor, hybrid interpreter, session (singleton shared by the main app and the assistant overlay), usage tracker and repositories.
