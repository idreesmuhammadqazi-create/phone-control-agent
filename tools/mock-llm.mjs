#!/usr/bin/env node
/**
 * Deterministic mock of an OpenAI-compatible chat endpoint, for testing the on-device agent without
 * a real model or API key.
 *
 *   node tools/mock-llm.mjs            # port 9099, /v1/chat/completions
 *   MOCK_MODE=observe node tools/mock-llm.mjs
 *   MOCK_MODE=task node tools/mock-llm.mjs
 *   MOCK_MODE=destructive node tools/mock-llm.mjs
 *
 * From the emulator, the host loopback is http://10.0.2.2:9099/v1. From a physical phone on the
 * same network, use the computer's LAN address.
 */
import http from "node:http";

const port = Number(process.env.MOCK_LLM_PORT || 9099);
const mode = process.env.MOCK_MODE || "press_home";
let calls = 0;

function plan(step, prompt) {
  if (mode === "observe") {
    return step === 1
      ? { tool: "observe", args: { fmt: "text" } }
      : { done: true, summary: "mock observe goal complete" };
  }
  if (mode === "task") {
    return step === 1
      ? {
          tool: "run_task",
          args: {
            steps: [
              { tool: "press", args: { key: "home" } },
              { tool: "open_app", args: { app: "com.android.settings" } },
              { tool: "observe", expect: "Settings" },
            ],
          },
        }
      : { done: true, summary: "mock task goal complete" };
  }
  if (mode === "destructive") {
    // Tap whatever the screen labels as the destructive action, so the engine's approval gate
    // (not the mock) is what decides whether it runs.
    const match = prompt.match(/@(\d+)\|[^\n]*Erase all data/i);
    return match
      ? { tool: "tap", args: { ref: Number(match[1]) } }
      : { tool: "observe", args: {} };
  }
  return step === 1
    ? { tool: "press", args: { key: "home" } }
    : { done: true, summary: "mock goal complete" };
}

const server = http.createServer((request, response) => {
  if (request.method === "GET" && request.url === "/health") {
    response.writeHead(200, { "content-type": "text/plain" });
    response.end("ok");
    return;
  }
  let body = "";
  request.on("data", (chunk) => (body += chunk));
  request.on("end", () => {
    calls += 1;
    let prompt = "";
    try {
      const payload = JSON.parse(body);
      prompt = payload?.messages?.[0]?.content || "";
    } catch {
      // keep prompt empty
    }
    const match = prompt.match(/STEP:\s*(\d+)\s*\//);
    const step = match ? Number(match[1]) : calls;
    const action = plan(step, prompt);
    const content = JSON.stringify(action);
    const payload = {
      id: `mock-${calls}`,
      object: "chat.completion",
      model: "mock",
      choices: [
        { index: 0, message: { role: "assistant", content }, finish_reason: "stop" },
      ],
    };
    response.writeHead(200, { "content-type": "application/json" });
    response.end(JSON.stringify(payload));
    console.error(`[mock-llm] call=${calls} step=${step} mode=${mode} -> ${content}`);
  });
});

server.listen(port, "127.0.0.1", () => {
  console.error(`[mock-llm] listening on http://127.0.0.1:${port}/v1 (mode=${mode})`);
});
