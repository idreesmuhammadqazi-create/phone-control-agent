# PocketPilot

**Accessibility-native phone automation for AI agents.**

PocketPilot turns an Android phone into a controllable device for agents — without screenshots, OCR or a vision model. A native `AccessibilityService` exposes the UI as a compact, element-addressed tree; agents act on element references (`@12`) instead of pixel coordinates. Everything runs on the phone, in one small app, and is designed to stay comfortable on a 2 GB device.

```
agent (MCP / REST)  ──HTTP──▶  PocketPilot app  ──▶  AccessibilityService
      ▲                        │  engine (refs, guard, diff)
      └──── compact tree ◀─────┘  on-device agent loop (optional)
```

## Why

- **No vision.** Native accessibility data is structured, deterministic and cheap. A typical screen costs a few hundred tokens, not a 10k-token image.
- **Element refs, not pixels.** `tap @12` survives layout changes better than `tap 540,1830`, and fails loudly (`stale_ref`) instead of tapping the wrong thing.
- **On-device.** One APK hosts the engine, an MCP server on localhost, and an optional agent loop. No companion daemon, no root, no ADB in the loop at runtime.
- **Small.** No AndroidX, no Compose, no image pipeline unless you explicitly ask for a screenshot. The debug APK is about 1 MB; the minified release build is ~85 KB.
- **Safety first.** App allowlists, confirmation for destructive actions, and redaction of cards/secrets/password fields before anything reaches an agent.

## What it does

| Capability | Detail |
|---|---|
| Observe | Compact flat tree with stable refs, screen state, app/activity, keyboard state |
| Diff | `observe(since=<id>)` returns only what changed |
| Act | Tap, long-press, type (set-text + paste fallback), scroll, swipe, back/home/recents/notifications |
| Navigate | Launch apps by package or visible name, wait for text/package, system keys |
| Multi-step | `run_task` executes deterministic scripts with per-step expectations |
| Delegate | `run_agent` hands a natural-language goal to an on-device agent loop |
| Work with any agent | MCP over Streamable HTTP, a small REST surface, and a stdio bridge for stdio-only clients |

## Requirements

- Android 8.0+ (API 26). Tested by build only; see [Status](#status).
- A JDK 17 and the Android SDK (platform 34, build-tools 34.0.0) to build.
- No root. No ADB at runtime (ADB is only used to forward a port or install the APK).

## Build

```bash
export ANDROID_HOME="$HOME/Android/Sdk"        # or set sdk.dir in local.properties
./gradlew :core:test          # 57 unit tests, no device needed
./gradlew :app:assembleDebug  # app/build/outputs/apk/debug/app-debug.apk
```

Release (unsigned; sign it yourself before distributing):

```bash
./gradlew :app:assembleRelease
# app/build/outputs/apk/release/app-release-unsigned.apk
```

> `settings.gradle.kts` resolves Maven Central through Google's mirror
> (`maven-central.storage-download.googleapis.com`) because the build sandbox blocks `repo1`.
> Swap it for `mavenCentral()` if you prefer.

Install:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## First run

1. Open **PocketPilot**.
2. Tap **Enable accessibility** → **PocketPilot automation** → enable. Android will warn that the service can view and control the screen; that is exactly what the engine uses it for.
3. Allow notifications (used for action approvals).
4. The local server starts automatically with the app (`http://127.0.0.1:8787`). You can stop/start it from the main screen.
5. From a computer, forward the port:

```bash
adb forward tcp:8787 tcp:8787
curl -s http://127.0.0.1:8787/health
# {"ok":true,"name":"pocketpilot","version":"0.1.0","tools":15}
```

6. Open `http://127.0.0.1:8787/v1/observe?fmt=text` to see the current screen.

> LAN mode is off by default. If you enable it, set a bearer token in **Server & safety**.

## Connect an agent

### MCP over HTTP (recommended)

Claude Code:

```bash
claude mcp add --transport http pocketpilot http://127.0.0.1:8787/mcp
```

Any MCP client that supports Streamable HTTP:

```json
{
  "mcpServers": {
    "pocketpilot": {
      "type": "http",
      "url": "http://127.0.0.1:8787/mcp"
    }
  }
}
```

### stdio-only clients

Use the included bridge (Node 18+). It speaks newline-delimited JSON-RPC on stdio and forwards to the phone:

```json
{
  "mcpServers": {
    "pocketpilot": {
      "command": "node",
      "args": [
        "/absolute/path/to/tools/mcp-stdio-bridge.mjs",
        "--url",
        "http://127.0.0.1:8787/mcp"
      ]
    }
  }
}
```

With a token: add `"--token", "…"` or set `POCKETPILOT_TOKEN`.

### REST

Handy for scripts and non-MCP agents:

```bash
curl -s http://127.0.0.1:8787/v1/observe?fmt=text
curl -s -X POST http://127.0.0.1:8787/v1/tool/tap -H 'content-type: application/json' -d '{"ref":12}'
curl -s -X POST http://127.0.0.1:8787/v1/task  -H 'content-type: application/json' \
  -d '{"steps":[{"tool":"open_app","args":{"app":"com.android.settings"}},{"tool":"wait_for","args":{"text":"Network"}}]}'
```

`tools/pilot.sh` wraps the common calls.

## The screen format

One header line plus one line per element, flat and parent-linked:

```
S7 com.android.settings/.Settings 1080x2400 r0 kb0 21/186
@1|-|scr|s|0,0,1080,2400||Settings|
@2|@1|txt||24,150,1032,96|title|Network & internet||
@3|@2|item|c|24,260,1032,140|dashboard_tile|Wi-Fi|Connected to HomeNet|
@8|@3|swt|ck|850,290,160,80|switch_widget|On||
```

Header: `S<snapshot> <package>/<activity> <w>x<h> r<rotation> kb<0|1> <kept>/<total> [trunc]`.

Each element: `@ref|parent|role|flags|bounds(l,t,w,h)|viewId|text|desc|hint`.

- Roles: `btn txt inp img chk rad swt sld spn tab item lnk scr lst dlg prg web box`.
- Flags: `c` clickable, `l` long-clickable, `e` editable, `s` scrollable, `f` focused, `k` checked, `v` selected, `x` disabled, `p` password.
- `|` inside text is escaped as `\|`, newlines as `\n`.
- Decorative containers and unlabelled images are dropped; a clickable row with one text child is merged into one line.
- The default cap is 140 elements (configurable to 400). Elements are ranked by interactivity/label/focus, and ancestors are always kept so refs form a valid tree.

Actions return a **diff**, not the whole screen:

```
ok tap @8
S8 com.android.settings/.Settings 1080x2400 r0 kb0 22/187
D8<-7 +0 -0 ~1
~ @8|@3|swt|ck|850,290,160,80|switch_widget|Off||
```

If more than ~55% of the screen changed (navigation, app launch), you get the full new tree instead.

Full protocol details: [docs/PROTOCOL.md](docs/PROTOCOL.md).

## Token budget

Token efficiency is a design constraint, not an afterthought:

- **No pixels.** Accessibility data is text. A screenshot costs thousands of tokens before any reasoning happens.
- **Caps.** 140 elements by default (400 max), text truncated to 100 chars, decorative nodes dropped, wrapper layers spliced out.
- **Refs.** Actions are `{"ref":12}`, not coordinates plus element descriptions.
- **Diffs.** `observe(since=<id>)` sends only changed lines. Every action also returns a diff, so an agent usually does not need a separate observation at all.
- **Truncated diffs.** If more than ~55% changed, the engine sends the new full tree instead of a pointless diff.
- **Bounded prompts.** The on-device agent sends the goal, six recent actions and at most 7,000 characters of screen.

A typical screen lands around 1.5–4 KB of text (roughly 400–1,000 tokens); a tap that changes one
toggle typically returns a few hundred bytes.

## Tools

| Tool | Arguments | Notes |
|---|---|---|
| `observe` | `since`, `fmt`, `max`, `wait` | `since` gives a diff; `fmt=json` for machine parsing |
| `tap` | `ref` or `x`/`y`, `long` | Click via accessibility action, gesture fallback |
| `long_press` | `ref` or `x`/`y` | Long-press; always requires approval |
| `type` | `ref`, `text`, `append`, `submit` | Focused field if `ref` omitted; set-text then paste fallback |
| `scroll` | `ref`, `direction`, `amount` | Scrollable ancestor, gesture fallback |
| `swipe` | `x1`, `y1`, `x2`, `y2`, `ms` | Raw gesture |
| `press` | `key` | `back`, `home`, `recents`, `notifications`, `quick_settings`, `enter` |
| `open_app` | `app` | Package name or exact/unique visible name |
| `wait_for` | `text`, `package`, `timeout` | Polls until a condition is true, then returns the screen |
| `run_task` | `steps`, `stopOnError` | Deterministic multi-step script, optional `expect` per step |
| `run_agent` | `goal`, `maxSteps` | Requires an LLM configured in the app |
| `confirm` | `id`, `approve` | Resolve a pending destructive action |
| `apps` | `query` | Installed launchable apps |
| `status` | – | Service, foreground app, policy, agent state |
| `screenshot` | `maxWidth` | JPEG for human debugging only; Android 11+ |

## On-device agent

PocketPilot can run the agent loop itself. The loop lives on the phone (so it survives the laptop closing), while the LLM stays remote — a 2 GB phone should not run a model that is good enough to plan.

**AI settings** → base URL, model, API key. Any OpenAI-compatible endpoint works:

```
OpenAI        https://api.openai.com/v1        gpt-4o-mini
OpenRouter    https://openrouter.ai/api/v1     <any model>
Ollama (LAN)  http://192.168.1.20:11434/v1     qwen2.5:7b
LM Studio     http://192.168.1.20:1234/v1      <model>
```

Then type a goal and tap **Run agent**, or call `run_agent` over MCP. Each turn sends the goal, the last few actions and the current compact screen; the model replies with exactly one tool call. There is no transcript accumulation and no screenshot, so cost stays flat.

For no-LLM workflows use `run_task`:

```json
{
  "steps": [
    {"tool": "open_app", "args": {"app": "com.android.settings"}},
    {"tool": "wait_for", "args": {"text": "Network & internet", "timeout": 5000}},
    {"tool": "tap", "args": {"ref": 12}},
    {"tool": "observe", "expect": "Wi-Fi"}
  ]
}
```

## Safety model

- **Allowlist.** In **Server & safety**, disable *Allow actions in all apps* and list packages. Read-only observation stays available; mutating actions in other apps are refused.
- **Confirmations.** Labels matching payment/delete/logout patterns, long-presses, and typing into password fields return `confirmation_required` with a `confirmId`. Approve from the notification, the in-app dialog, or the `confirm` tool. Pending approvals expire after 2 minutes.
- **Coordinate taps are not label-checked.** `tap {x,y}` and raw swipes cannot be classified as destructive; prefer element refs so the guard can see what is being activated.
- **Redaction.** Password field content becomes `•••`; card-number-like and long token-like strings are replaced with `[redacted]` before rendering.
- **Network.** Binds `127.0.0.1` by default. LAN mode is opt-in and supports a bearer token. There is no TLS: use `adb forward` or a trusted network.
- **No shell, no root.** Notifications, system UI and other apps are only reachable through the accessibility APIs.

## Low-end / 2 GB notes

- Single process, no AndroidX, no Compose, no image decoding unless `screenshot` is called.
- Capture is capped at 3,000 platform nodes and rendered trees at 140 (max 400) elements.
- Snapshot cache is bounded to 8 renders; logs are a 160-line ring buffer.
- Four HTTP worker threads, one agent thread; no coroutines or background services beyond the one foreground service.
- Text-first everywhere; the agent prompt is capped at ~7,000 characters of screen data.

## Project layout

```
core/                     Pure Kotlin, no Android imports. 57 unit tests.
  Model, Compact, Diff    Raw tree, element refs, token-efficient rendering
  Guard                   Allowlist, destructive-action policy, redaction
  PilotEngine             Observe/act/task semantics, confirmations
  Router                  MCP JSON-RPC + REST, transport-neutral
  HttpServer              Tiny HTTP/1.1 server (loopback, 4 threads)
app/                      Android shell
  ServiceTree/Capture     Accessibility traversal and node keys
  Actuator                Actions and gesture fallbacks
  AndroidDevice           Device implementation for the core engine
  PilotService            Foreground service hosting server + agent
  Agent/Llm               On-device agent loop and OpenAI-compatible client
  MainActivity            Status, settings, approvals, logs
tools/                    stdio MCP bridge, curl helper
docs/                     Protocol, architecture, device test plan
```

More: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) · [docs/PROTOCOL.md](docs/PROTOCOL.md) · [docs/DEVICE-TESTING.md](docs/DEVICE-TESTING.md)

## Status

- `core` unit tests: **57 passing** (`./gradlew :core:test`), including real-socket HTTP/MCP tests.
- Debug APK builds and is installable (signed with the debug key).
- Release APK builds with R8 (~85 KB, unsigned).
- **Not yet validated on a physical device or emulator in this environment.** The on-device layer (accessibility traversal, gestures, IME, screenshot) is written defensively but needs a real phone pass. Use [docs/DEVICE-TESTING.md](docs/DEVICE-TESTING.md) as the checklist.

## Known limitations

- The activity name comes from window-state accessibility events; it is approximate (e.g. a dialog class) and can be empty.
- Apps that draw everything into one canvas (games, some Flutter/Compose screens) expose few or no actionable nodes. The engine falls back to coordinate taps and gestures, but there is nothing to reason about.
- `ACTION_SET_TEXT` is the primary typing path; a paste fallback handles most custom fields, but focus behaviour varies by keyboard/app.
- Screenshots require Android 11+ and the accessibility screenshot capability.
- Refs and snapshot ids are process-local. After a force-stop they start over; agents should observe again.
- Accessibility-service distribution has Play Store policy implications. This is intended for personal/sideloaded use or device fleets you control.

## Troubleshooting

| Symptom | Check |
|---|---|
| `service_off` | Accessibility service enabled? Re-toggle it after reinstalling. |
| `no_window` | Phone locked or on a secure screen. Unlock it. |
| `stale_ref` | Call `observe` again; refs expire after a few snapshots or a screen change. |
| `connection refused` | Start the server in the app; run `adb forward tcp:8787 tcp:8787`. |
| MCP client shows 0 tools | Point it at `.../mcp`, not `/`; for stdio-only clients use the bridge. |
| `launch_failed` | Android may block background activity starts; open the app once, then retry. |
| Text not typed | Tap the field first (focus), then `type`; some fields need `submit` to commit. |
| Agent stops at `confirmation_required` | Approve via notification/dialog, then re-run the goal. |
