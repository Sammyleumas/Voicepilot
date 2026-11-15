// Vercel Function: GET /healthz — reports configuration state without revealing secrets.
export default function healthz(req, res) {
  const env = process.env;
  res.setHeader("Content-Type", "application/json");
  res.setHeader("Cache-Control", "no-store");
  res.end(JSON.stringify({
    ok: true,
    geminiKey: Boolean(env.GEMINI_API_KEY),
    auth: env.FIREBASE_PROJECT_ID ? "firebase" : env.ACCESS_TOKENS ? "tokens" : "missing",
    sharedLimits: Boolean((env.UPSTASH_REDIS_REST_URL || env.KV_REST_API_URL) && (env.UPSTASH_REDIS_REST_TOKEN || env.KV_REST_API_TOKEN)),
  }));
}
