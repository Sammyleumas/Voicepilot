# VoicePilot backend proxy

A small, dependency-free Node.js service that lets the app use Gemini without shipping a provider key.

```
POST /v1/generate   Authorization: Bearer <token>
GET  /healthz
```

## Run locally

```bash
cd backend
npm test                                   # 7 tests, no network
GEMINI_API_KEY=... ACCESS_TOKENS="me:$(openssl rand -hex 24)" npm start
```

## Configuration

| Variable | Required | Meaning |
|---|---|---|
| `GEMINI_API_KEY` | yes | Server-side Gemini key |
| `FIREBASE_PROJECT_ID` | one of | Verify Firebase Auth ID tokens (production) |
| `ACCESS_TOKENS` | one of | `user:token,user2:token2` for private deployments |
| `DAILY_QUOTA` | no (25) | Requests per user per UTC day |
| `PER_MINUTE` | no (20) | Requests per user per minute |
| `PORT` | no (8080) | Listen port (Node server only) |
| `UPSTASH_REDIS_REST_URL` / `_TOKEN` (or `KV_REST_API_URL` / `_TOKEN`) | no | Shared rate-limit store |

## Deploy on Vercel (recommended)

The folder is a ready Vercel project: `public/` is the download page and demo, `api/` holds the functions, and `vercel.json` maps `/v1/generate` and `/healthz`.

1. In Vercel, **Add New › Project**, import the GitHub repository and set **Root Directory** to `backend`. Framework preset: **Other**. Leave build settings empty.
2. Add environment variables (Project › Settings › Environment Variables):
   - `GEMINI_API_KEY` — from Google AI Studio
   - `ACCESS_TOKENS` — e.g. `me:<long random string>` (or `FIREBASE_PROJECT_ID` once app sign-in exists)
   - Optional but recommended: connect **Upstash Redis** from the Vercel Marketplace; it sets `KV_REST_API_URL` / `KV_REST_API_TOKEN` (or `UPSTASH_REDIS_REST_*`) so limits are shared across function instances.
3. Deploy. Check `https://<project>.vercel.app/healthz` shows `"geminiKey": true`.
4. In the app: Settings › AI › AI provider › **VoicePilot Cloud**, Cloud URL `https://<project>.vercel.app`, Access token = the token part of `ACCESS_TOKENS`.

Every push to the connected branch redeploys automatically.

## Deploy elsewhere (example: Cloud Run)

```bash
gcloud run deploy voicepilot-api --source . --region europe-west1 \
  --set-secrets GEMINI_API_KEY=gemini-key:latest --set-env-vars FIREBASE_PROJECT_ID=your-project
```

Then build the app with `voicepilot.cloudBaseUrl=https://<service-url>` in `local.properties`, or enter the URL in Settings › AI.

Without Redis, limits are kept in memory per instance. On Vercel, connect Upstash Redis so they hold across instances.
