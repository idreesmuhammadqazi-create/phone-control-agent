# Architecture

## Constraints

The target is a phone with 2 GB of RAM and an entry-level SoC, possibly running Android 8–14.
That rules out a lot of otherwise reasonable designs:

| Constraint | Consequence |
|---|---|
| No reliable background execution | One foreground service hosts everything |
| Small heap | No Compose/AndroidX, no image pipeline, bounded caches |
| Slow CPU | Capture on demand, cap the tree, avoid XML/JSON round-trips |
| Limited battery | Event-driven idle detection, no polling loops while idle |
| Untrusted screens | Redaction and confirmations before any action leaves the engine |
| Agent context is expensive | Compact text tree, diffs, refs instead of coordinates |

## Module split

```
:core  (plain Kotlin/JVM, org.json)          :app (Android)
┌───────────────────────────────┐            ┌──────────────────────────────┐
│ PilotEngine                   │  Device    │ AndroidDevice                │
│  observe / act / task         │◀───────────│  ServiceTree + Capture       │
│  confirmations, diffing       │            │  Actuator (actions/gestures) │
│ Compactor + RefRegistry       │            │  PilotAccessibilityService   │
│ Guard (policy/redaction)      │            │                              │
│ Router (MCP + REST)           │            │ PilotService (foreground)    │
│ HttpServer (ServerSocket)     │            │ Agent + LlmClient            │
│ Tools (schemas)               │            │ MainActivity (plain Views)   │
└───────────────────────────────┘            └──────────────────────────────┘
```

`core` has no Android imports, so the entire decision layer — compaction, refs, diffs, policy,
task semantics, MCP framing, and the HTTP transport — is unit-tested on a JVM with a scripted
`FakeDevice` and real sockets. The Android module is deliberately thin: platform traversal,
platform actions, service lifecycle, UI.

## Observation pipeline

1. **Choose a root.** `rootInActiveWindow` normally points at the app window. If the active window
   is the IME, step to the active application window — typing into the keyboard is never the goal.
   System dialogs (permissions, power menu) keep their own root, which is what the agent must see.
2. **DFS with caps.** Iterative pre-order traversal (no recursion), 3,000 nodes max, depth 60.
   Each node becomes a flat `RawNode` with parent index, role, flags, bounds, view id and text.
3. **Key.** `package|path|class|viewId`, where `path` is the child-index chain (`r/0/2`). The key
   ignores mutable text so typing does not destroy a ref, but includes the class and view id so a
   structural change makes the key miss instead of silently pointing at another widget.
4. **Redact.** Password nodes lose their text; card-like and long-token-like strings are replaced
   before anything is rendered or cached.
5. **Compact** (see below) and **assign refs**.
6. **Store.** Bounded LRU of 8 renders; `since` diffs are computed against them.

The header's `kb` flag is set when any IME window exists, not only when the IME is the active window
(it usually is not), so it reflects a visible soft keyboard.

Platform node objects are recycled after conversion (a no-op on modern Android). The engine never
holds an `AccessibilityNodeInfo`; actions carry the string key and re-find the node, which makes
stale refs detectable rather than dangerous.

## Compaction

The goal is the smallest representation that still answers "what can I do here?".

1. Drop invisible subtrees, zero-size leaves and nodes deeper than 64.
2. Splice out unlabelled, non-interactive structural nodes (`box`, `img`, `scr`, `lst`, `txt`)
   while keeping their children. This removes the 5–10 wrapper layers that every Android layout
   has without losing tree context.
3. Merge a clickable container with exactly one non-interactive labelled child into one line
   (the classic `LinearLayout > TextView` list row).
4. Cap by score: interactive (+4), editable (+2), focused (+3), labelled (+2), has id (+1),
   neutral container/image (−2). Pull in all ancestors of every chosen node so the tree stays valid.
5. Render one line per element with a stable ref, short role, flag string and compact bounds.
6. Estimate tokens as `chars / 4 + 8` and report it in the observation metadata.

Everything is deterministic, which matters for diffs and tests.

## Refs

Refs are session-scoped integers. `RefRegistry` maps key → ref for the current UI session and
retains a ref for three snapshots after its key disappears. Rendering a different app does not
reset the registry: keys include the package, so old refs simply become absent from the current
render and fail as `stale_ref` instead of aliasing another app's element.

This is a deliberate safety choice. Silently mapping `@12` to a different control after
`open_app` would be worse than an error.

## Action pipeline

```
resolve target → guard → platform action → settle → snapshot → diff → result
```

- **Resolve**: use the last render if it is younger than 3 s, otherwise capture. A ref missing from
  the current render triggers one settle-and-recapture before `stale_ref`, which covers taps that
  start a screen transition. An editable target without a ref uses the focused `inp`, or the sole
  visible input (a search screen often opens with its field visible but not focused).
- **Guard**: read-only tools bypass policy. Everything else checks the package allowlist/blocklist,
  then destructive label patterns (`pay`, `buy`, `delete`, `uninstall`, `factory reset`, `log out`,
  money + confirm, …), long-press, and password input. A blocked action is parked with a
  `confirmId` and a notification; nothing executes until `confirm`.
- **Act**: accessibility actions first, gestures as fallback. This is what keeps the engine usable
  on Compose/Flutter/WebView screens where nodes are present but lie about clickability.
  `open_app` adds `FLAG_ACTIVITY_CLEAR_TOP` so an existing task returns to its main screen, then
  waits for the target package to actually reach the foreground.
- **Settle**: a 200 ms grace (activity starts and IME animations often begin later than a quiet
  window suggests), then no accessibility event for 120 ms, bounded by 900 ms.
- **Diff**: compare by ref and a signature that excludes bounds. Return additions, removals and
  changes; fall back to the full tree when too much changed or the app changed.

Every action therefore returns the next screen in miniature, so an agent can act continuously
without a separate `observe` round-trip.

## Transport

`HttpServer` is a deliberately small HTTP/1.1 implementation: `ServerSocket`, four worker threads,
`Content-Length` bodies up to 1 MiB, `Connection: close`. No TLS, no keep-alive, no chunked uploads.
It binds loopback unless LAN mode is explicitly enabled.

`Router` is transport-neutral and holds the MCP logic: JSON-RPC framing, method dispatch, tool
validation, tool-result shaping, and REST routes. That is why protocol behaviour is unit-tested
without a socket.

MCP is Streamable HTTP with JSON responses and no session. `initialize` negotiates one of the three
known protocol revisions; notifications return `202`; batches are supported. SSE is offered as a
one-shot fallback for clients that refuse JSON.

## On-device agent

The loop is on the phone, the model is not. A 2 GB device cannot run a model good enough to plan.

Per turn:

- system prompt (~450 tokens): screen grammar, tool signatures, four rules;
- goal, step number;
- last six actions with results truncated to 260 chars;
- current screen capped at 7,000 chars.

The model must answer with one JSON tool call or `{"done":true,"summary":"…"}`. Failures are fed
back as text; unparsable output costs one retry. Identical actions three times in a row abort as
`agent_stuck`. A confirmation stops the agent with the pending `confirmId`, so the human decides
without the agent holding a thread.

## Threading and memory

| Work | Thread |
|---|---|
| Node access, actions, gestures | main thread, via `MainThread.call` with a timeout |
| HTTP requests | 4-thread pool |
| Agent loop + LLM HTTP | one daemon thread |
| Wait-for-idle / polling | caller thread |

Bounded state: 8 rendered snapshots, 3,000 captured nodes, 160 log lines, 8 pending confirmations,
a 5-minute app-index cache, a 160-line log ring. No global bitmap cache; the only bitmap is created
inside `screenshot` and recycled immediately.

`MainThread.call` prevents a blocked accessibility service from wedging the server forever: a
capture that exceeds 4 s returns `null`, which surfaces as `no_window`/`action_failed`, not a hang.

## Failure modes

| Failure | Result |
|---|---|
| Service disabled | `service_off` on every tool |
| Lock screen / no window | `no_window` |
| UI changed under the ref | `stale_ref`, agent must observe |
| Node lies about clickability | gesture fallback |
| Typing ignored | paste fallback, then `action_failed` |
| Background launch blocked | `launch_blocked` after the app fails to reach the foreground |
| Port busy | server start error in the app + notification |
| LLM unreachable | `llm_error`, agent stops cleanly |
| Confirmation ignored | expires after 120 s; the in-app dialog is dismissed on the next refresh |

## Extending

**Add a tool:** define the schema in `core/Tools.kt`, handle the name in `PilotEngine.dispatch`,
document it in `docs/PROTOCOL.md`, add an engine test and a router test. The MCP/REST surfaces pick
it up automatically.

**Support another platform:** implement `core.Device` (snapshot + primitive actions). The engine,
compactor, policy, tasks, agent and MCP server work unchanged.

**Tune for a device:** `EngineConfig` exposes node caps, text length, settle/observe windows and
task/wait limits; `CompactConfig` controls the renderer.

## Verification

- **JVM:** 62 tests in `:core` — compaction/refs/diffs, policy and redaction, engine actions and
  tasks, MCP and REST routing, and the HTTP server over real loopback sockets (including auth,
  `202` notifications and the SSE fallback).
- **Emulator:** Android 14 (API 34, x86_64, 720x1280) with `tools/device-test.sh`: 21/21 checks
  covering observe, refs, open/tap/type/scroll/press, tasks, MCP and the stdio bridge.
- **Extra device passes:** screenshot (JPEG), rotation (`r1`, swapped dimensions), IME flag (`kb1`),
  destructive approval (deny, approve, system confirmation), long-press approval, in-app deny
  dialog, approval expiry, and the on-device agent against a deterministic mock LLM (a direct tool
  call and a nested `run_task`).
- **Still unverified:** physical phones and OEM ROMs (battery managers, custom accessibility
  implementations), and real third-party model providers.

## Deferred

- Resume an agent after a human approval (currently the agent stops; approval executes the action).
- Multi-window snapshots (dialogs are handled, split-screen is not).
- Semantic screen labels / learned app recipes for repeated tasks.
- Encrypted token storage (currently SharedPreferences).
- TLS or a pairing flow for LAN use.
- A benchmark corpus of real app trees to tune the scoring and settlement heuristics.
