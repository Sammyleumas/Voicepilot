// VoicePilot backend proxy. No third-party dependencies.
//
// POST /v1/generate  (Authorization: Bearer <token>)
//   { model, system, json, temperature, maxOutputTokens, messages: [{ role: "user"|"assistant", text }] }
//   -> { text, model, usage: { promptTokens, outputTokens, totalTokens } }
// GET /healthz -> { ok: true }
import { createHash, createPublicKey, createVerify, timingSafeEqual } from "node:crypto";

export const ALLOWED_MODELS = new Set(["gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.5-pro"]);
const MAX_BODY = 64 * 1024;
const MAX_MESSAGES = 12;
const MAX_TEXT = 4000;

export class HttpError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}

// ------------------------------------------------------------------ auth

const sha256 = (s) => createHash("sha256").update(s).digest();

/** Static tokens for private/dev deployments: ACCESS_TOKENS="userA:token1,userB:token2". */
export function staticAuth(spec) {
  const entries = (spec || "").split(",").map((p) => p.trim()).filter(Boolean).map((p) => {
    const i = p.indexOf(":");
    return { user: p.slice(0, i), hash: sha256(p.slice(i + 1)) };
  });
  return async (token) => {
    const h = sha256(token);
    const hit = entries.find((e) => timingSafeEqual(e.hash, h));
    if (!hit) throw new HttpError(401, "invalid token");
    return hit.user;
  };
}

const b64url = (s) => Buffer.from(s, "base64url");

/**
 * Verifies Firebase Authentication ID tokens (RS256) against Google's public keys.
 * fetchJwks is injectable for tests.
 */
export function firebaseAuth(projectId, { fetchJwks, now = () => Date.now() } = {}) {
  let cache = { keys: null, until: 0 };
  const jwksUrl = "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com";
  const load = async () => {
    if (cache.keys && now() < cache.until) return cache.keys;
    const keys = fetchJwks ? await fetchJwks() : (await (await fetch(jwksUrl)).json()).keys;
    cache = { keys, until: now() + 60 * 60 * 1000 };
    return keys;
  };
  return async (token) => {
    const parts = token.split(".");
    if (parts.length !== 3) throw new HttpError(401, "malformed token");
    let header, payload;
    try {
      header = JSON.parse(b64url(parts[0]).toString());
      payload = JSON.parse(b64url(parts[1]).toString());
    } catch { throw new HttpError(401, "malformed token"); }
    if (header.alg !== "RS256") throw new HttpError(401, "bad alg");
    const jwk = (await load()).find((k) => k.kid === header.kid);
    if (!jwk) throw new HttpError(401, "unknown key");
    const ok = createVerify("RSA-SHA256").update(`${parts[0]}.${parts[1]}`)
      .verify(createPublicKey({ key: jwk, format: "jwk" }), b64url(parts[2]));
    if (!ok) throw new HttpError(401, "bad signature");
    const t = Math.floor(now() / 1000);
    if (payload.aud !== projectId || payload.iss !== `https://securetoken.google.com/${projectId}`) throw new HttpError(401, "wrong audience");
    if (!payload.sub || payload.exp <= t || payload.iat > t + 60) throw new HttpError(401, "expired token");
    return payload.sub;
  };
}

// ------------------------------------------------------------------ limits

/** Per-user per-minute rate limit and daily quota (in memory; use Redis/Firestore when scaling out). */
export function limiter({ perMinute = 20, perDay = 25, now = () => Date.now() } = {}) {
  const minute = new Map();
  const day = new Map();
  return (user) => {
    const t = now();
    const m = minute.get(user) || { start: t, n: 0 };
    if (t - m.start >= 60_000) { m.start = t; m.n = 0; }
    if (m.n >= perMinute) throw new HttpError(429, "rate limited");
    const key = `${user}:${new Date(t).toISOString().slice(0, 10)}`;
    const d = day.get(key) || 0;
    if (d >= perDay) throw new HttpError(402, "daily quota exceeded");
    m.n++; minute.set(user, m); day.set(key, d + 1);
  };
}

/**
 * Same limits backed by Upstash Redis (REST), for serverless hosts such as Vercel
 * where memory isn't shared between requests.
 */
export function redisLimiter({ url, token, perMinute = 20, perDay = 25, now = () => Date.now(), fetchImpl = fetch }) {
  const base = url.replace(/\/$/, "");
  return async (user) => {
    const t = now();
    const minuteKey = `vp:m:${user}:${Math.floor(t / 60_000)}`;
    const dayKey = `vp:d:${user}:${new Date(t).toISOString().slice(0, 10)}`;
    const res = await fetchImpl(`${base}/pipeline`, {
      method: "POST",
      headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
      body: JSON.stringify([["INCR", minuteKey], ["EXPIRE", minuteKey, "120"], ["INCR", dayKey], ["EXPIRE", dayKey, "172800"]]),
    });
    if (!res.ok) throw new HttpError(503, "limit store unavailable");
    const [m, , d] = await res.json();
    if (m.result > perMinute) throw new HttpError(429, "rate limited");
    if (d.result > perDay) throw new HttpError(402, "daily quota exceeded");
  };
}

// ------------------------------------------------------------------ validation & Gemini

export function validate(body) {
  if (!body || typeof body !== "object") throw new HttpError(400, "invalid body");
  const model = body.model || "gemini-2.5-flash";
  if (!ALLOWED_MODELS.has(model)) throw new HttpError(400, "model not allowed");
  if (typeof body.system !== "string" || body.system.length > 20_000) throw new HttpError(400, "invalid system prompt");
  if (!Array.isArray(body.messages) || body.messages.length === 0 || body.messages.length > MAX_MESSAGES) throw new HttpError(400, "invalid messages");
  const messages = body.messages.map((m) => {
    if (!m || (m.role !== "user" && m.role !== "assistant") || typeof m.text !== "string" || m.text.length > MAX_TEXT) throw new HttpError(400, "invalid message");
    return { role: m.role, text: m.text };
  });
  const temperature = Math.min(Math.max(Number(body.temperature ?? 0.1), 0), 1);
  const maxOutputTokens = Math.min(Math.max(Number(body.maxOutputTokens ?? 1024) | 0, 16), 2048);
  return { model, system: body.system, json: body.json !== false, temperature, maxOutputTokens, messages };
}

export async function callGemini(req, apiKey, fetchImpl = fetch) {
  const res = await fetchImpl(`https://generativelanguage.googleapis.com/v1beta/models/${req.model}:generateContent`, {
    method: "POST",
    headers: { "Content-Type": "application/json", "x-goog-api-key": apiKey },
    body: JSON.stringify({
      systemInstruction: { parts: [{ text: req.system }] },
      contents: req.messages.map((m) => ({ role: m.role === "user" ? "user" : "model", parts: [{ text: m.text }] })),
      generationConfig: { temperature: req.temperature, maxOutputTokens: req.maxOutputTokens, ...(req.json ? { responseMimeType: "application/json" } : {}) },
    }),
    signal: AbortSignal.timeout(20_000),
  });
  if (res.status === 429) throw new HttpError(429, "provider rate limited");
  if (!res.ok) throw new HttpError(502, `provider error ${res.status}`);
  const data = await res.json();
  const text = (data.candidates?.[0]?.content?.parts || []).map((p) => p.text || "").join("");
  if (!text) throw new HttpError(502, "empty provider response");
  const u = data.usageMetadata || {};
  return { text, model: req.model, usage: { promptTokens: u.promptTokenCount || 0, outputTokens: u.candidatesTokenCount || 0, totalTokens: u.totalTokenCount || 0 } };
}

// ------------------------------------------------------------------ handler

/** Returns a node:http request handler. Logs never include prompts, tokens or keys. */
export function createHandler({ authenticate, limit, apiKey, fetchImpl = fetch, log = () => {} }) {
  return async (req, res) => {
    const send = (status, obj) => {
      res.writeHead(status, { "Content-Type": "application/json", "Cache-Control": "no-store" });
      res.end(JSON.stringify(obj));
    };
    try {
      if (req.method === "GET" && req.url === "/healthz") return send(200, { ok: true });
      if (req.method !== "POST" || req.url !== "/v1/generate") throw new HttpError(404, "not found");
      const auth = req.headers.authorization || "";
      if (!auth.startsWith("Bearer ")) throw new HttpError(401, "missing token");
      const user = await authenticate(auth.slice(7).trim());
      const chunks = [];
      let size = 0;
      for await (const c of req) {
        size += c.length;
        if (size > MAX_BODY) throw new HttpError(413, "body too large");
        chunks.push(c);
      }
      let body;
      try { body = JSON.parse(Buffer.concat(chunks).toString("utf8")); } catch { throw new HttpError(400, "invalid json"); }
      const parsed = validate(body);
      await limit(user);
      const out = await callGemini(parsed, apiKey, fetchImpl);
      log({ event: "generate", model: out.model, tokens: out.usage.totalTokens });
      return send(200, out);
    } catch (e) {
      const status = e instanceof HttpError ? e.status : 500;
      log({ event: "error", status });
      return send(status, { error: status === 500 ? "internal error" : e.message });
    }
  };
}
