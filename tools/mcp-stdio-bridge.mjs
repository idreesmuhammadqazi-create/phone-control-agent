#!/usr/bin/env node
/**
 * PocketPilot stdio <-> HTTP bridge.
 *
 * For MCP clients that only speak stdio (Claude Desktop, many IDE plugins): run this on the
 * computer, point it at the phone's PocketPilot server over `adb forward`, and use it as a normal
 * stdio MCP server. It is stateless; the phone stays the single source of truth.
 *
 *   adb forward tcp:8787 tcp:8787
 *   node tools/mcp-stdio-bridge.mjs --url http://127.0.0.1:8787/mcp
 *
 * Requires Node 18+ (global fetch).
 */
import readline from "node:readline";

const argv = process.argv.slice(2);
const valueOf = (flag, fallback) => {
  const i = argv.indexOf(flag);
  return i >= 0 && argv[i + 1] ? argv[i + 1] : fallback;
};

const url = valueOf("--url", process.env.POCKETPILOT_URL || "http://127.0.0.1:8787/mcp");
const token = valueOf("--token", process.env.POCKETPILOT_TOKEN || "");

const log = (message) => process.stderr.write(`[pocketpilot] ${message}\n`);

async function post(body) {
  const headers = {
    "content-type": "application/json",
    accept: "application/json, text/event-stream",
    "user-agent": "pocketpilot-bridge/0.1",
  };
  if (token) headers.authorization = `Bearer ${token}`;

  const response = await fetch(url, { method: "POST", headers, body });
  const text = await response.text();
  if (response.status === 202) return null;
  if (!response.ok) {
    throw new Error(`HTTP ${response.status}: ${text.slice(0, 300)}`);
  }
  if (!text.trim()) return null;

  const contentType = response.headers.get("content-type") || "";
  if (contentType.includes("text/event-stream")) {
    const data = text
      .split(/\r?\n/)
      .filter((line) => line.startsWith("data:"))
      .map((line) => line.slice(5).trimStart())
      .join("\n");
    return data || null;
  }
  return text;
}

function requestId(payload) {
  try {
    const parsed = JSON.parse(payload);
    if (Array.isArray(parsed)) return null;
    return parsed && Object.prototype.hasOwnProperty.call(parsed, "id") ? parsed.id : null;
  } catch {
    return null;
  }
}

// Serialise requests: MCP handshakes are order-sensitive and responses must not interleave.
let queue = Promise.resolve();
const input = readline.createInterface({ input: process.stdin, crlfDelay: Infinity });

input.on("line", (line) => {
  const payload = line.trim();
  if (!payload) return;
  queue = queue.then(async () => {
    try {
      const output = await post(payload);
      if (output) process.stdout.write(output.endsWith("\n") ? output : `${output}\n`);
    } catch (error) {
      const id = requestId(payload);
      log(error?.message || String(error));
      if (id !== null) {
        process.stdout.write(
          JSON.stringify({
            jsonrpc: "2.0",
            id,
            error: { code: -32000, message: String(error?.message || error) },
          }) + "\n"
        );
      }
    }
  });
});

input.on("close", () => {
  queue.finally(() => process.exit(0));
});

log(`bridging stdio to ${url}${token ? " (token auth)" : ""}`);
