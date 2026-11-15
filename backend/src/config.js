// Builds the handler pieces from environment variables (shared by the Node server and Vercel).
import { firebaseAuth, limiter, redisLimiter, staticAuth } from "./app.js";

export function fromEnv(env = process.env) {
  const { GEMINI_API_KEY, FIREBASE_PROJECT_ID, ACCESS_TOKENS, DAILY_QUOTA = "25", PER_MINUTE = "20" } = env;
  if (!GEMINI_API_KEY) throw new Error("GEMINI_API_KEY is required");
  if (!FIREBASE_PROJECT_ID && !ACCESS_TOKENS) throw new Error("Set FIREBASE_PROJECT_ID or ACCESS_TOKENS");
  const perDay = Number(DAILY_QUOTA), perMinute = Number(PER_MINUTE);
  const redisUrl = env.UPSTASH_REDIS_REST_URL || env.KV_REST_API_URL;
  const redisToken = env.UPSTASH_REDIS_REST_TOKEN || env.KV_REST_API_TOKEN;
  return {
    apiKey: GEMINI_API_KEY,
    authenticate: FIREBASE_PROJECT_ID ? firebaseAuth(FIREBASE_PROJECT_ID) : staticAuth(ACCESS_TOKENS),
    limit: redisUrl && redisToken ? redisLimiter({ url: redisUrl, token: redisToken, perDay, perMinute }) : limiter({ perDay, perMinute }),
    sharedLimits: Boolean(redisUrl && redisToken),
  };
}
