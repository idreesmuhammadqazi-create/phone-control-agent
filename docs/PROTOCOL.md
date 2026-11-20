# Protocol

PocketPilot exposes one engine through three faces:

- **MCP** over Streamable HTTP at `POST /mcp` (JSON responses, no session, no SSE stream).
- **REST** for curl/scripts and agents that are not MCP-aware.
- **stdio bridge** (`tools/mcp-stdio-bridge.mjs`) for clients that only speak stdio.

Everything is plain JSON. There is no binary protocol and no vision payload unless you request one.

## Transport

| Property | Value |
|---|---|
| Bind | `127.0.0.1:8787` by default; `0.0.0.0` when LAN mode is enabled |
| Auth | Optional `Authorization: Bearer <token>`; `/health` is always open |
| Body limit | 1 MiB |
| Connections | One request per connection, `Connection: close`; fixed pool of 4 |
| CORS | `*` with `content-type`, `authorization`, `mcp-protocol-version` allowed |
| TLS | None — use `adb forward` or a trusted LAN |

`Accept: text/event-stream` without `application/json` gets a one-shot SSE frame
(`event: message` + `data:`), so simple SSE clients work too.

## MCP

Supported methods: `initialize`, `ping`, `tools/list`, `tools/call`,
`resources/list` (empty), `prompts/list` (empty). Notifications get `202` with an empty body.
Batches (JSON arrays) are supported.

### initialize

```json
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}
```

Server echoes the requested version if it is one of `2024-11-05`, `2025-03-26`, `2025-06-18`,
otherwise returns `2025-06-18`.

```json
{
  "jsonrpc":"2.0","id":1,
  "result":{
    "protocolVersion":"2025-06-18",
    "capabilities":{"tools":{"listChanged":false}},
    "serverInfo":{"name":"pocketpilot","version":"0.1.0"},
    "instructions":"Control the phone by element refs…"
  }
}
```

### tools/call

```json
{"jsonrpc":"2.0","id":2,"method":"tools/call",
 "params":{"name":"observe","arguments":{"fmt":"text"}}}
```

Result:

```json
{
  "jsonrpc":"2.0","id":2,
  "result":{
    "content":[{"type":"text","text":"S1 com.android.settings/.Settings …"}],
    "isError":false,
    "structuredContent":{ "ok":true, "snapshot":1, "…":"…" }
  }
}
```

- `isError` is `true` when the engine result has `ok:false`. Tool-level errors (unknown tool,
  bad arguments, action failure) are tool results, not JSON-RPC errors, so the model sees them.
- `structuredContent` is omitted for screenshots and for results larger than ~6 KB.
- `screenshot` returns `{"type":"image","data":"<base64 jpeg>","mimeType":"image/jpeg"}`.

### JSON-RPC errors

| Code | Meaning |
|---|---|
| `-32700` | Parse error |
| `-32600` | Invalid request |
| `-32601` | Method not found |
| `-32603` | Internal error |

## REST

All REST paths require the bearer token when one is configured (except `/health`).

| Method | Path | Body / query | Result |
|---|---|---|---|
| GET | `/health` | – | `{"ok":true,"name","version","tools"}` |
| POST | `/mcp` | JSON-RPC | JSON-RPC response |
| GET | `/` | – | small HTML status page |
| GET | `/v1/observe` | `since`, `fmt`, `max`, `wait` | observation object |
| POST | `/v1/observe` | observation args as JSON | observation object |
| GET | `/v1/status` | – | status object |
| GET | `/v1/apps` | `q` | app list |
| POST | `/v1/task` | `{"steps":[…],"stopOnError":true}` | task result |
| POST | `/v1/tool/<name>` | tool arguments as JSON | tool result |

## Screen format

```
S<id> <package>/<activity> <w>x<h> r<rotation> kb<0|1> <kept>/<total> [trunc]
@<ref>|<parent>|<role>|<flags>|<l>,<t>,<w>,<h>|<viewId>|<text>|<desc>|<hint>
```

- Header fields are space-separated; element fields are `|`-separated and always nine fields.
- Empty fields are allowed (`…|@5|txt|c|0,0,10,10|||`).
- `|` in text/desc/hint is escaped as `\|`; newline as `\n`; backslash as `\\`.
- `parent` is the nearest kept ancestor, `-` for roots.
- `bounds` is `left,top,width,height` in screen pixels.
- `viewId` is the resource id without its package prefix; generic ids (`content`, `action_bar`, …)
  are dropped.
- Text is trimmed, whitespace-collapsed and capped at 100 characters (with `…`).
- Password content is replaced by `•••`; card/token patterns are `[redacted]` before rendering.

Roles:

| Role | Meaning |
|---|---|
| `btn` | Button / clickable text or image |
| `txt` | Non-interactive text |
| `inp` | Editable text field |
| `img` | Image (only kept if labelled or clickable) |
| `chk` `rad` `swt` `sld` | Checkbox, radio, switch, slider |
| `spn` `tab` `item` `lnk` | Spinner, tab, list row, link |
| `scr` `lst` `dlg` `prg` `web` | Scroll container, list, dialog, progress, WebView |
| `box` | Neutral container (usually pruned) |

Flags (concatenated, deterministic order): `c` clickable, `l` long-clickable, `e` editable,
`s` scrollable, `f` focused, `k` checked, `v` selected, `x` disabled, `p` password.

### Diffs

```
D<current><-<previous> +<added> -<removed> ~<changed>
+ @12|…            (new element)
- @7 "Wi-Fi"       (removed element and its old label)
~ @8|…             (element whose role/flags/id/text/desc/hint changed)
```

Bounds are not part of the change signature, so pure movement or scroll offset does not create
diff noise. If more than ~55% of the previous elements changed, or the package changed, the engine
returns the full tree instead and sets `changes.full=true`.

## Element refs

- Refs are small integers rendered as `@12`.
- They are stable while the underlying node key (package + traversal path + class + view id)
  remains present.
- A ref that has not been seen for three snapshots is recycled.
- Refs are resolved against the **current** render only. Acting on a ref that is not on screen
  returns `stale_ref` with a hint to call `observe`:
  the engine never guesses a different element for an old number.
- Refs are process-local. After a force-stop, ids and refs restart.

## Actions

All mutating actions go through the same pipeline:

1. Resolve the target (ref or focused field), refreshing the snapshot if the last one is older than 3 s.
2. Check policy: allowlist/blocklist, destructive patterns, password entry.
3. Perform the platform action; if the node lies about being clickable, use a gesture at its centre.
4. Wait for the UI to settle (event-quiet for ~90 ms, bounded by 900 ms).
5. Render the new screen and return a diff (or the full tree when the change is large).

| Tool | Behaviour |
|---|---|
| `tap` | `ACTION_CLICK` on the nearest clickable ancestor; gesture fallback. `long=true` uses `ACTION_LONG_CLICK` or a 700 ms press. |
| `long_press` | Long-press by ref or coordinates; confirmation is always required. |
| `type` | Focus, `ACTION_SET_TEXT` (replace or append), verify; clipboard + `ACTION_PASTE` fallback. `submit` presses the IME action when available (Android 11+). |
| `scroll` | Scrollable ancestor + `ACTION_SCROLL_FORWARD/BACKWARD`; otherwise a swipe inside the element or screen. `amount` 0.1–1.0. |
| `swipe` | Linear gesture, 50–3000 ms. |
| `press` | Global accessibility actions; `enter` uses the focused node's `ACTION_IME_ENTER` (Android 11+). |
| `open_app` | Launch intent by package, or exact/unique label match. Ambiguous names return candidates. |
| `wait_for` | Poll every 200 ms until `text` appears and/or `package` is foreground; returns the screen. |
| `run_task` | Up to 40 steps, optional `wait` before a step and `expect` substring after it. Stops on error by default. |
| `run_agent` | Delegates to the on-device agent loop if an LLM is configured. |
| `observe` | Read-only; `since` returns a diff; `fmt=json` adds an `elementsJson` array. |
| `status` | Service, foreground package, last snapshot, policy, pending approvals, agent state. |
| `screenshot` | JPEG (max width 320–1440), Android 11+ only. |

## Confirmation flow

When policy requires approval, the action does not run:

```json
{
  "ok": false,
  "status": "confirmation_required",
  "error": "confirmation_required",
  "confirmId": "c3",
  "reason": "destructive_action",
  "detail": "Pay now",
  "hint": "Call confirm with {id:\"c3\",approve:true} to proceed, or ask the user.",
  "text": "confirm c3: destructive_action (Pay now)"
}
```

Approval paths:

- `confirm` tool / `POST /v1/tool/confirm` with `{"id":"c3","approve":true}`;
- the notification action (Approve / Deny);
- the in-app dialog.

Approvals are single-use and expire after 120 seconds. Denying returns
`{"ok":true,"status":"denied"}` and performs nothing.

Reasons: `destructive_action`, `long_press`, `password_input`.

Coordinate actions (`tap` with `x`/`y`, `swipe`) cannot be label-classified. Prefer element refs
when an action could be destructive; the guard can only reason about what it can see.

## run_task step schema

```json
{
  "steps": [
    {"tool": "open_app", "args": {"app": "com.android.settings"}},
    {"tool": "wait_for", "args": {"text": "Network & internet"}, "wait": 300},
    {"tool": "observe", "expect": "Wi-Fi"}
  ],
  "stopOnError": true
}
```

`expect` is a case-insensitive substring check on the step's `text` result. Results include one
record per executed step with a truncated text and an error code when applicable.

## Status object

```json
{
  "ok": true,
  "service": true,
  "pkg": "com.android.settings",
  "snapshot": 12,
  "elements": 34,
  "screen": [1080, 2400],
  "allowAll": true,
  "allowlist": 0,
  "pendingConfirms": 0,
  "refs": 34,
  "port": 8787,
  "lan": false,
  "server": true,
  "agent": "idle",
  "agentStep": 0,
  "ai": false,
  "text": "ok service=1 pkg=com.android.settings snap=12 els=34 allowAll=1"
}
```

## Limits

| Limit | Value |
|---|---|
| Platform nodes captured per snapshot | 3,000 |
| Rendered elements | 140 default, 400 hard |
| Text field length | 100 chars |
| Cached snapshots (for `since`) | 8 |
| Task steps | 40 |
| Wait timeout | 30 s |
| Confirmation TTL | 120 s |
| Agent steps | 1–50 |
| Agent LLM timeout | 60 s |
| HTTP body | 1 MiB |
