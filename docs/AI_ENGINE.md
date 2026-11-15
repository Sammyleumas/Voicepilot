# AI engine

## Pipeline

```
utterance ─▶ routines? ─▶ LocalRuleInterpreter ─▶ (no match) ─▶ rate limit ─▶ daily quota ─▶ LlmCommandInterpreter ─▶ Interpretation
```

`Interpretation` is one of `Actions(intent, requests, preface)`, `Clarify(question)`, `Answer(text)` or `Unsupported(reason)`.

### On-device rules (`LocalRuleInterpreter`)

Deterministic regex rules with filler stripping (“hey VoicePilot”, “please”, “can you”, “abeg”, “for me”), compound splitting (“… and then …”), time parsing (`TimeExpressionParser`: “6:30”, “half past seven”, “tomorrow at 9”, “in 20 minutes”, “on Friday at 3pm”) and reported-speech rewriting (`MessageRewriter`: “telling him I'll call him” → “I'll call you”). Unknown input returns `Unsupported` so AI can take over.

### Providers (`llm/`)

| Provider | Key location | Use |
|---|---|---|
| `GeminiProvider` | User's key, AES-GCM encrypted with Android Keystore | Personal use, development |
| `CloudProxyProvider` | Server only (`backend/`) | Production distribution |
| `ScriptedLlmProvider` | — | Tests |

Gemini is called at `POST /v1beta/models/{model}:generateContent` with `systemInstruction`, low temperature and `responseMimeType: application/json`. The key travels in the `x-goog-api-key` header, never in the URL. Token usage (`usageMetadata`) is surfaced in Settings › AI.

Add a provider by implementing `LlmProvider` (`isConfigured()`, `generate()`) and returning it from `AiProviderFactory`.

### Structured output

The system prompt lists every allowlisted action with its parameter types and asks for exactly one of:

```json
{"type":"actions","intent":"send_message","actions":[{"action":"prepare_whatsapp_message","params":{"recipient":"my brother","message":"I will call you after work"}}]}
{"type":"clarify","question":"Which Samuel?"}
{"type":"answer","answer":"..."}
{"type":"unsupported","reason":"..."}
```

`LlmCommandInterpreter.parse()`:
- extracts the JSON object (tolerates code fences), never evaluates anything;
- drops unknown actions and unknown parameters;
- drops resolver-only fields (`phone`, `recipient_name`) so the model can't redirect a message to a different number;
- caps actions (8), answer length and question length.

**Risk and confirmation never come from the model.** They are computed from `ActionDefinition.risk` and the user's confirmation setting. The product brief's example field `"risk_level"` is therefore ignored if a model returns it.

## Error handling

`LlmException.kind` ∈ `NOT_CONFIGURED, AUTH, RATE_LIMITED, QUOTA_EXCEEDED, TIMEOUT, NETWORK, SERVER, INVALID_RESPONSE, BLOCKED`, each with a user-facing message. `HybridInterpreter` turns any provider failure into `Unsupported` with that message plus a reminder that offline commands still work. Transport exceptions are mapped to `NETWORK`/`TIMEOUT`.

## Limits

- Client: token bucket (10 requests, one refilled every 6 s).
- Daily AI allowance per plan (`Entitlements`: Free 25, Premium 500), persisted in Room by day.
- Backend: per-user per-minute limit and daily quota, model allowlist, 64 KB body, 12 messages.

## Mock providers for testing

`ScriptedLlmProvider.of(json…)` and `ScriptedLlmProvider.failing(kind)` drive `AiTest` and can be injected in the app for demos without network.
