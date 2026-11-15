// Vercel Function: POST /v1/generate (rewritten in vercel.json). Body parsing is left to the shared handler.
import { createHandler } from "../src/app.js";
import { fromEnv } from "../src/config.js";

export const config = { api: { bodyParser: false } };

let handler;
export default async function generate(req, res) {
  try {
    handler ??= createHandler({ ...fromEnv(), log: (e) => console.log(JSON.stringify(e)) });
  } catch (e) {
    res.statusCode = 500;
    res.setHeader("Content-Type", "application/json");
    return res.end(JSON.stringify({ error: "server not configured", detail: e.message }));
  }
  req.url = "/v1/generate";
  return handler(req, res);
}
