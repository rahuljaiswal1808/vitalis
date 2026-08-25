// Zero-dependency static server for the Vitalis demo.
// getUserMedia works over http on localhost, so no TLS needed for local testing.
import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { extname, join, normalize } from "node:path";
import { fileURLToPath } from "node:url";

const root = normalize(join(fileURLToPath(import.meta.url), "..", "..")); // web/
const port = Number(process.env.PORT) || 5173;

const types = {
  ".html": "text/html", ".js": "text/javascript", ".mjs": "text/javascript",
  ".css": "text/css", ".map": "application/json", ".json": "application/json",
  ".wasm": "application/wasm", ".task": "application/octet-stream",
  ".png": "image/png", ".svg": "image/svg+xml",
};

createServer(async (req, res) => {
  try {
    let path = decodeURIComponent((req.url || "/").split("?")[0]);
    if (path === "/") path = "/examples/index.html";
    const filePath = normalize(join(root, path));
    if (!filePath.startsWith(root)) { res.writeHead(403).end("Forbidden"); return; }
    const body = await readFile(filePath);
    res.writeHead(200, { "content-type": types[extname(filePath)] || "application/octet-stream" });
    res.end(body);
  } catch {
    res.writeHead(404, { "content-type": "text/plain" }).end("Not found");
  }
}).listen(port, () => {
  console.log(`\n  Vitalis demo → http://localhost:${port}/\n`);
  console.log("  (Run `npm run build` first so dist/ exists.)\n");
});
