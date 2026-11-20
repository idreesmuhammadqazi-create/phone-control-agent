#!/usr/bin/env bash
# End-to-end smoke test against a connected Android device or emulator.
#
#   ./tools/device-test.sh [path/to/app-debug.apk]
#
# It installs the APK, enables the accessibility service through secure settings (this is a test
# harness; normal users enable it in Settings), forwards the port, and exercises observe/act/task/
# MCP over the real HTTP server.
#
# Requires: adb, curl, node. Env: ANDROID_SERIAL, POCKETPILOT_PORT (8787), POCKETPILOT_TOKEN.
set -uo pipefail

APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"
PORT="${POCKETPILOT_PORT:-8787}"
BASE="http://127.0.0.1:${PORT}"
ADB="${ADB:-adb}"
SERIAL=()
[[ -n "${ANDROID_SERIAL:-}" ]] && SERIAL=(-s "${ANDROID_SERIAL}")
auth=()
[[ -n "${POCKETPILOT_TOKEN:-}" ]] && auth=(-H "Authorization: Bearer ${POCKETPILOT_TOKEN}")
PKG="dev.pocketpilot"
SERVICE="${PKG}/${PKG}.app.PilotAccessibilityService"

PASS=0
FAIL=0
ok()   { PASS=$((PASS+1)); printf '  \033[32mPASS\033[0m %s\n' "$1"; }
bad()  { FAIL=$((FAIL+1)); printf '  \033[31mFAIL\033[0m %s\n' "$1"; }
note() { printf '\n== %s ==\n' "$1"; }

json_get() {
  node -e '
    let s = "";
    process.stdin.on("data", d => (s += d)).on("end", () => {
      let o; try { o = JSON.parse(s); } catch (e) { console.log(""); return; }
      for (const k of process.argv[1].split(".")) o = o == null ? undefined : o[k];
      console.log(typeof o === "string" ? o : JSON.stringify(o ?? null));
    });
  ' "$1"
}

observe() { curl -sS "${auth[@]}" "${BASE}/v1/observe?fmt=text" | json_get text; }
tool()    { curl -sS "${auth[@]}" -X POST "${BASE}/v1/tool/$1" -H 'content-type: application/json' -d "${2:-{}}"; }
ref_for() {
  printf '%s\n' "$1" | awk -F'|' -v label="$2" '
    /^@/ {
      if ($7 == label || $8 == label || $9 == label) { sub(/^@/, "", $1); print $1; exit }
      if ($0 ~ label) { sub(/^@/, "", $1); print $1; exit }
    }'
}

note "device"
"$ADB" "${SERIAL[@]}" wait-for-device
STATE=$("$ADB" "${SERIAL[@]}" get-state 2>/dev/null)
[[ "$STATE" == "device" ]] && ok "adb device connected" || { bad "adb device ($STATE)"; exit 1; }
BOOT=$("$ADB" "${SERIAL[@]}" shell getprop sys.boot_completed | tr -d '\r')
[[ "$BOOT" == "1" ]] && ok "boot completed" || bad "boot_completed=$BOOT"

note "install and enable"
"$ADB" "${SERIAL[@]}" install -r "$APK" >/dev/null 2>&1 && ok "apk installed" || bad "apk install"
# Prevent the Android 13+ notification prompt from covering the first screens; test automation only.
"$ADB" "${SERIAL[@]}" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
"$ADB" "${SERIAL[@]}" shell am start -n "${PKG}/${PKG}.app.MainActivity" >/dev/null 2>&1
sleep 3
"$ADB" "${SERIAL[@]}" shell settings put secure enabled_accessibility_services "$SERVICE" >/dev/null 2>&1
"$ADB" "${SERIAL[@]}" shell settings put secure accessibility_enabled 1 >/dev/null 2>&1
sleep 2
if "$ADB" "${SERIAL[@]}" shell dumpsys accessibility 2>/dev/null | grep -qi pocketpilot; then
  ok "accessibility service bound"
else
  bad "accessibility service not bound (enable it manually in Settings)"
fi
"$ADB" "${SERIAL[@]}" shell am start -n "${PKG}/${PKG}.app.MainActivity" >/dev/null 2>&1
sleep 2
"$ADB" "${SERIAL[@]}" forward "tcp:${PORT}" "tcp:${PORT}" >/dev/null 2>&1 && ok "port forwarded" || bad "adb forward"

note "health"
HEALTH=$(curl -sS --max-time 5 "${BASE}/health" 2>/dev/null)
[[ "$(printf '%s' "$HEALTH" | json_get ok)" == "true" ]] && ok "health ok" || bad "health: $HEALTH"

note "observe"
OBS=$(observe)
printf '%s\n' "$OBS" | head -3
[[ "$OBS" == *"S"* && "$OBS" == *"@"* ]] && ok "compact tree returned" || bad "no tree"

note "open_app + observe Settings"
tool press '{"key":"home"}' >/dev/null
sleep 1
OPEN=$(tool open_app '{"app":"com.android.settings"}')
[[ "$(printf '%s' "$OPEN" | json_get ok)" == "true" ]] || {
  sleep 1
  OPEN=$(tool open_app '{"app":"com.android.settings"}')
}
[[ "$(printf '%s' "$OPEN" | json_get ok)" == "true" ]] && ok "open_app accepted" || bad "open_app: $OPEN"
sleep 2
OBS=$(observe)
PKG_NOW=$(printf '%s\n' "$OBS" | head -1 | awk '{print $2}' | cut -d/ -f1)
[[ "$PKG_NOW" == "com.android.settings" ]] && ok "Settings is foreground" || bad "foreground is $PKG_NOW"

REF=$(ref_for "$OBS" "Network & internet")
if [[ -n "${REF:-}" ]]; then
  ok "found Network & internet at @$REF"
  RES=$(tool tap "{\"ref\":$REF}")
  [[ "$(printf '%s' "$RES" | json_get ok)" == "true" ]] && ok "tap accepted" || bad "tap failed: $RES"
  sleep 1
  AFTER=$(observe)
  [[ "$AFTER" == *"Internet"* || "$AFTER" == *"Wi"* ]] && ok "navigated into network screen" || bad "unexpected screen"
  tool press '{"key":"back"}' >/dev/null
  sleep 1
else
  bad "could not find Network & internet"
fi

note "type into Settings search"
OBS=$(observe)
SEARCH=$(ref_for "$OBS" "Search settings")
if [[ -z "${SEARCH:-}" ]]; then
  SEARCH=$(printf '%s\n' "$OBS" | awk -F'|' '/^@/ && $3=="inp" { sub(/^@/,"",$1); print $1; exit }')
fi
if [[ -n "${SEARCH:-}" ]]; then
  tool tap "{\"ref\":$SEARCH}" >/dev/null
  sleep 2
  RES=$(tool type '{"text":"battery","submit":false}')
  [[ "$(printf '%s' "$RES" | json_get ok)" == "true" ]] && ok "typed into search" || bad "type failed: $RES"
  sleep 1
  AFTER=$(observe)
  [[ "${AFTER,,}" == *"battery"* ]] && ok "results reflect query" || bad "query not visible"
  tool press '{"key":"back"}' >/dev/null
  sleep 1
else
  bad "no search input found"
fi

note "scroll"
OBS=$(observe)
RES=$(tool scroll '{"direction":"down","amount":0.8}')
[[ "$(printf '%s' "$RES" | json_get ok)" == "true" ]] && ok "scroll accepted" || bad "scroll failed"
sleep 1
AFTER=$(observe)
[[ "$AFTER" != "$OBS" ]] && ok "screen changed after scroll" || bad "no change after scroll"

note "press home"
RES=$(tool press '{"key":"home"}')
[[ "$(printf '%s' "$RES" | json_get ok)" == "true" ]] && ok "home accepted" || bad "home failed"

note "run_task"
TASK=$(curl -sS "${auth[@]}" -X POST "${BASE}/v1/task" -H 'content-type: application/json' -d '{
  "steps":[
    {"tool":"open_app","args":{"app":"com.android.settings"}},
    {"tool":"wait_for","args":{"text":"Network & internet","timeout":8000}},
    {"tool":"observe","expect":"Network"}
  ]}')
[[ "$(printf '%s' "$TASK" | json_get ok)" == "true" ]] && ok "multi-step task succeeded" || bad "task failed: $TASK"

note "mcp"
INIT='{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}'
INIT_RES=$(curl -sS "${auth[@]}" -X POST "${BASE}/mcp" -H 'content-type: application/json' -d "$INIT")
[[ "$(printf '%s' "$INIT_RES" | json_get result.protocolVersion)" == "2025-06-18" ]] && ok "initialize" || bad "initialize: $INIT_RES"
TOOLS=$(curl -sS "${auth[@]}" -X POST "${BASE}/mcp" -H 'content-type: application/json' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}')
COUNT=$(printf '%s' "$TOOLS" | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{try{console.log(JSON.parse(s).result.tools.length)}catch(e){console.log(0)}})')
[[ "$COUNT" -ge 15 ]] && ok "$COUNT tools listed" || bad "tool count $COUNT"

note "stdio bridge"
if command -v node >/dev/null 2>&1 && [[ -f tools/mcp-stdio-bridge.mjs ]]; then
  BRIDGE=$(printf '%s\n%s\n' "$INIT" '{"jsonrpc":"2.0","id":9,"method":"tools/list"}' \
    | node tools/mcp-stdio-bridge.mjs --url "${BASE}/mcp" 2>/dev/null | tail -1)
  [[ "$BRIDGE" == *'"tools"'* ]] && ok "stdio bridge forwarded" || bad "stdio bridge: $BRIDGE"
else
  printf '  SKIP stdio bridge (node or tools/mcp-stdio-bridge.mjs missing)\n'
fi

printf '\n%d passed, %d failed\n' "$PASS" "$FAIL"
[[ "$FAIL" -eq 0 ]]
