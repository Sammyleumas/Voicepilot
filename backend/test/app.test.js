import { test } from "node:test";
import assert from "node:assert/strict";
import { createServer } from "node:http";
import { generateKeyPairSync, createSign } from "node:crypto";
import { createHandler, firebaseAuth, limiter, staticAuth, validate, HttpError } from "../src/app.js";

const geminiOk = async (url, init) => {
  assert.match(url, /models\/gemini-2\.5-flash:generateContent$/);
  assert.equal(init.headers["x-goog-api-key"], "server-key");
  const body = JSON.parse(init.body);
  assert.equal(body.generationConfig.responseMimeType, "application/json");
  return new Response(JSON.stringify({
    candidates: [{ content: { parts: [{ text: '{"type":"answer","answer":"hi"}' }] } }],
    usageMetadata: { promptTokenCount: 10, candidatesTokenCount: 5, totalTokenCount: 15 },
  }), { status: 200 });
};

async function withServer(handler, fn) {
  const server = createServer(handler);
  await new Promise((r) => server.listen(0, r));
  const base = `http://127.0.0.1:${server.address().port}`;
  try { await fn(base); } finally { server.close(); }
}

const request = { model: "gemini-2.5-flash", system: "s", messages: [{ role: "user", text: "hello" }] };

test("generate proxies to Gemini and never exposes the key", async () => {
  const logs = [];
  const handler = createHandler({ authenticate: staticAuth("u1:secret"), limit: limiter(), apiKey: "server-key", fetchImpl: geminiOk, log: (e) => logs.push(e) });
  await withServer(handler, async (base) => {
    const res = await fetch(`${base}/v1/generate`, { method: "POST", headers: { Authorization: "Bearer secret" }, body: JSON.stringify(request) });
    assert.equal(res.status, 200);
    const out = await res.json();
    assert.equal(out.text, '{"type":"answer","answer":"hi"}');
    assert.equal(out.usage.totalTokens, 15);
    assert.ok(!JSON.stringify(out).includes("server-key"));
    assert.ok(!JSON.stringify(logs).includes("hello"), "prompts are not logged");
  });
});

test("rejects missing or wrong tokens", async () => {
  const handler = createHandler({ authenticate: staticAuth("u1:secret"), limit: limiter(), apiKey: "k", fetchImpl: geminiOk });
  await withServer(handler, async (base) => {
    assert.equal((await fetch(`${base}/v1/generate`, { method: "POST", body: "{}" })).status, 401);
    assert.equal((await fetch(`${base}/v1/generate`, { method: "POST", headers: { Authorization: "Bearer nope" }, body: "{}" })).status, 401);
    assert.equal((await fetch(`${base}/healthz`)).status, 200);
  });
});

test("validation enforces model allowlist and sizes", () => {
  assert.throws(() => validate({ ...request, model: "some-expensive-model" }), HttpError);
  assert.throws(() => validate({ ...request, messages: [] }), HttpError);
  assert.throws(() => validate({ ...request, messages: [{ role: "system", text: "x" }] }), HttpError);
  assert.equal(validate({ ...request, maxOutputTokens: 999999 }).maxOutputTokens, 2048);
});

test("daily quota and per-minute rate limit", () => {
  let t = Date.parse("2026-11-15T10:00:00Z");
  const limit = limiter({ perMinute: 2, perDay: 3, now: () => t });
  limit("a"); limit("a");
  assert.throws(() => limit("a"), (e) => e.status === 429);
  t += 61_000;
  limit("a");
  assert.throws(() => limit("a"), (e) => e.status === 402);
  limit("b");
});

test("firebase ID token verification", async () => {
  const { privateKey, publicKey } = generateKeyPairSync("rsa", { modulusLength: 2048 });
  const jwk = { ...publicKey.export({ format: "jwk" }), kid: "k1", alg: "RS256" };
  const now = Date.parse("2026-11-15T10:00:00Z");
  const sign = (payload, kid = "k1") => {
    const h = Buffer.from(JSON.stringify({ alg: "RS256", kid })).toString("base64url");
    const p = Buffer.from(JSON.stringify(payload)).toString("base64url");
    const s = createSign("RSA-SHA256").update(`${h}.${p}`).sign(privateKey).toString("base64url");
    return `${h}.${p}.${s}`;
  };
  const iat = Math.floor(now / 1000) - 10;
  const good = { aud: "proj", iss: "https://securetoken.google.com/proj", sub: "user-1", iat, exp: iat + 3600 };
  const auth = firebaseAuth("proj", { fetchJwks: async () => [jwk], now: () => now });
  assert.equal(await auth(sign(good)), "user-1");
  await assert.rejects(auth(sign({ ...good, aud: "other" })), (e) => e.status === 401);
  await assert.rejects(auth(sign({ ...good, exp: iat })), (e) => e.status === 401);
  await assert.rejects(auth(sign(good, "unknown")), (e) => e.status === 401);
  const tampered = sign(good).split("."); tampered[1] = Buffer.from(JSON.stringify({ ...good, sub: "admin" })).toString("base64url");
  await assert.rejects(auth(tampered.join(".")), (e) => e.status === 401);
});

test("redis limiter counts per minute and per day", async () => {
  const { redisLimiter } = await import("../src/app.js");
  const store = new Map();
  const fakeRedis = async (url, init) => {
    assert.equal(url, "https://redis.example/pipeline");
    assert.equal(init.headers.Authorization, "Bearer rt");
    const out = JSON.parse(init.body).map(([cmd, key]) => {
      if (cmd === "INCR") { store.set(key, (store.get(key) || 0) + 1); return { result: store.get(key) }; }
      return { result: 1 };
    });
    return new Response(JSON.stringify(out), { status: 200 });
  };
  let t = Date.parse("2026-11-15T10:00:00Z");
  const limit = redisLimiter({ url: "https://redis.example/", token: "rt", perMinute: 2, perDay: 3, now: () => t, fetchImpl: fakeRedis });
  await limit("a"); await limit("a");
  await assert.rejects(limit("a"), (e) => e.status === 429);
  t += 60_000;
  await assert.rejects(limit("a"), (e) => e.status === 402, "third successful + one rejected already counted toward the day");
});

test("vercel function reports missing configuration clearly", async () => {
  const saved = { ...process.env };
  delete process.env.GEMINI_API_KEY;
  const { default: generate } = await import("../api/generate.js");
  await withServer(generate, async (base) => {
    const res = await fetch(`${base}/v1/generate`, { method: "POST", body: "{}" });
    assert.equal(res.status, 500);
    assert.equal((await res.json()).error, "server not configured");
  });
  Object.assign(process.env, saved);
});
