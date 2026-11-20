# On-device verification

PocketPilot was verified against an **Android 14 (API 34), x86_64, 720×1280, 2 GB** emulator:
21/21 automated checks plus the screenshot, rotation, IME, approval and agent paths listed below.
A physical phone and OEM ROM pass is still recommended — this document is both the automated
harness guide and the manual checklist.

## Automated run

```bash
./tools/device-test.sh                    # APK defaults to app/build/outputs/apk/debug/
./tools/device-test.sh path/to/app.apk
```

The harness installs the APK, enables the accessibility service through secure settings (test
automation only — normal users enable it in the UI), forwards the port, and checks: health,
observation, Settings navigation, tap, typing, results, scrolling, Home, `run_task`, MCP
initialize/tools, and the stdio bridge. It exits non-zero if any check fails. Requires `adb`,
`curl` and `node` on the host.

Agent test without an API key:

```bash
node tools/mock-llm.mjs &                 # deterministic OpenAI-compatible endpoint
# AI settings: base URL http://10.0.2.2:9099/v1, model "mock"
curl -s -X POST http://127.0.0.1:8787/v1/tool/run_agent \
  -H 'content-type: application/json' -d '{"goal":"go home","maxSteps":5}'
# {"ok":true,"status":"done","summary":"mock goal complete","steps":2,...}
```

Modes: `press_home` (default), `observe`, `task` (nested `run_task`), `destructive` (taps an
"Erase all data" label so the engine's approval gate is exercised).

## Verified on API 34

| Area | Result |
|---|---|
| Health / 15 tools | pass |
| Compact observe, stable refs, header (`pkg`, `r0`, `kb0`, counts) | pass |
| `open_app` by package, Settings foreground | pass |
| Tap by ref, navigation | pass |
| Type into Settings search, results filter | pass |
| Scroll, diff on change | pass |
| Home, global actions | pass |
| `run_task` (open → wait → observe expect) | pass |
| MCP initialize, tools/list, stdio bridge | pass |
| Screenshot | 38 KB JPEG at 720 px |
| Rotation | `r1`, 1280×720, refs still valid |
| IME visible | `kb1` after focus |
| Destructive action | `confirmation_required`; deny leaves screen untouched |
| Approval | executes the tap; Android's own "Erase all data" confirmation still follows |
| Long-press | `confirmation_required` (reason `long_press`) |
| In-app dialog | deny resolves the pending action and dismisses |
| Approval expiry | pending clears and the stale dialog is dismissed |
| Agent + mock LLM | direct `press home` in 2 steps; nested `run_task` opens Settings |
| Agent + destructive goal | stops with `confirmation_required`, no action taken |

## Emulator setup

```bash
sdkmanager "emulator" "platform-tools" "system-images;android-34;default;x86_64"
avdmanager create avd -n pilot34 -k "system-images;android-34;default;x86_64"
# set hw.lcd.width=720, hw.lcd.height=1280, hw.lcd.density=320 in the AVD config
emulator -avd pilot34 -no-window -no-audio -no-boot-anim -no-snapshot \
  -gpu swiftshader_indirect -memory 2048 -cores 2 &
```

KVM is required for a usable boot. The service is enabled for the tests with:

```bash
adb shell settings put secure enabled_accessibility_services \
  dev.pocketpilot/dev.pocketpilot.app.PilotAccessibilityService
adb shell settings put secure accessibility_enabled 1
```

## Manual setup

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb forward tcp:8787 tcp:8787
```

On the phone:

1. Open PocketPilot.
2. **Enable accessibility** → PocketPilot automation → ON.
3. Allow notifications.
4. Confirm the status line shows `Accessibility: ON  Server: ON :8787`.

Useful log filter:

```bash
adb logcat -s PocketPilot:V AndroidRuntime:E
```

The harness covers sections 1–9 and 12; the safety, rotation/keyboard, agent and soak sections
below are the parts worth doing by hand.

## 1. Server and health

```bash
curl -s http://127.0.0.1:8787/health
# {"ok":true,"name":"pocketpilot","version":"0.1.0","tools":15}
```

## 2. Observe the launcher

Unlock the phone, go to the home screen.

```bash
curl -s 'http://127.0.0.1:8787/v1/observe?fmt=text' | head -20
```

Expect a header like `S1 <launcher pkg>/<activity> 1080x2400 r0 kb0 …` and icon labels with
`item`/`btn` roles. Empty or `no_window` means the service is not seeing the window.

## 3. Observe Settings and check refs

```bash
./tools/pilot.sh open com.android.settings
sleep 1
./tools/pilot.sh observe
```

Expect `com.android.settings` in the header, a search field (`inp`), and rows such as
`Network & internet`, `Connected devices`, `Apps`, `Battery`. Check that:

- refs are small integers and parent refs point upward;
- decorative wrappers are absent;
- bounds roughly match the visible position.

## 4. Diff

```bash
SNAP=$(curl -s 'http://127.0.0.1:8787/v1/observe?fmt=text' | head -1 | sed 's/^S\([0-9]*\).*/\1/')
# toggle Wi-Fi or open a submenu, then:
curl -s "http://127.0.0.1:8787/v1/observe?fmt=text&since=$SNAP" | head -20
```

Expect `D<new><-<old> +a -r ~c` and only the changed lines. If the whole screen changed, the engine
returns the full tree instead — that is expected.

## 5. Tap by ref

From the Settings screen, find the ref of a row, then:

```bash
./tools/pilot.sh tap <ref>
```

Expect `ok tap @<ref>` plus a diff or the new screen. Verify the app navigated to the right page.

## 6. Type

Open Settings search (tap the search field), then:

```bash
./tools/pilot.sh type <ref> "wifi" --submit
```

Expect the field to contain `wifi` and results to filter. Try a field in another app (Messages,
Contacts) to exercise the paste fallback.

## 7. Scroll

```bash
./tools/pilot.sh tool scroll '{"direction":"down","amount":0.8}'
```

Expect the list to move and a diff showing the newly visible rows. Repeat with `up`; try a
horizontal carousel with `left`/`right`.

## 8. Navigation keys

```bash
./tools/pilot.sh press home
./tools/pilot.sh press recents
./tools/pilot.sh press back
./tools/pilot.sh tool press '{"key":"notifications"}'
```

Each should return a screen diff. `enter` should submit a focused text field on Android 11+.

## 9. Multi-step task

```bash
./tools/pilot.sh task /dev/stdin <<'JSON'
[
  {"tool":"open_app","args":{"app":"com.android.settings"}},
  {"tool":"wait_for","args":{"text":"Network & internet","timeout":8000}},
  {"tool":"observe","expect":"Network"}
]
JSON
```

Expect `{"ok":true,...}` with three step records. Then remove the middle step and confirm it fails
cleanly when the app is not in the expected state.

## 10. Safety gate

Open a screen with a destructive button (a test app's "Delete" is ideal; otherwise use a shopping
app's "Buy now" and stop before paying).

```bash
./tools/pilot.sh tap <ref-of-destructive-button>
```

Expect `confirmation_required` with a `confirmId`, no visible change, a notification with
Approve/Deny, and an in-app dialog. Approve and verify the action executes exactly once. Repeat and
deny; verify nothing happens. Wait 2 minutes and confirm the approval expires.

Also verify a password field's content appears as `•••` and a card number as `[redacted]`.

## 11. Screen state details

Rotate the phone and observe: `r1`/`r3` and swapped width/height. Open the keyboard and observe
`kb1`. Open a permission dialog (e.g. first launch of a fresh app) and confirm the dialog's nodes
are what `observe` returns.

## 12. MCP

```bash
claude mcp add --transport http pocketpilot http://127.0.0.1:8787/mcp
```

Ask the agent to run `status` and `observe`; it should see 15 tools. Then run one action through the
agent and confirm the returned text is a diff.

For a stdio-only client, point the bridge at the same URL and check that `initialize`/`tools/list`
work.

## 13. Agent (optional)

In **AI settings**, set an OpenAI-compatible base URL, model and key. Run:

> Open Settings and tell me whether Wi-Fi is on.

Watch the Activity log. Expect one observe, a few taps, and a `done` summary. Check that a
destructive action pauses the agent with `awaiting_confirmation` rather than auto-approving.

## 14. Long-run behaviour

Leave the server running for an hour, lock/unlock several times, switch apps, then observe again.
The server should stay reachable; the notification should persist. Check `adb shell dumpsys meminfo
dev.pocketpilot` — the app should stay well under 100 MB PSS on a 2 GB device.

## Report back

If something fails, capture:

```bash
adb logcat -d -s PocketPilot:V AndroidRuntime:E > pocketpilot-log.txt
curl -s http://127.0.0.1:8787/v1/status
```

and the failing command plus the returned JSON. Most issues will be one of: service disabled,
IME window selection, a node that lies about clickability, or a field that needs the paste path.
