import { createServer } from "node:http";
import { createHandler } from "./app.js";
import { fromEnv } from "./config.js";

const cfg = fromEnv();
const handler = createHandler({ ...cfg, log: (e) => console.log(JSON.stringify(e)) });
const port = Number(process.env.PORT || 8080);
createServer(handler).listen(port, () => console.log(JSON.stringify({ event: "listening", port })));
