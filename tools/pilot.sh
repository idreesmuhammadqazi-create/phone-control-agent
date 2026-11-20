#!/usr/bin/env bash
# Small curl helper for PocketPilot. Everything here is plain HTTP; the phone does the work.
#
#   ./tools/pilot.sh forward                 # adb forward tcp:8787
#   ./tools/pilot.sh observe                 # compact screen
#   ./tools/pilot.sh tap 12                  # tap element @12
#   ./tools/pilot.sh type 7 "hello"          # type into @7
#   ./tools/pilot.sh tool run_task '{"steps":[{"tool":"tap","args":{"ref":12}}]}'
#   ./tools/pilot.sh agent "turn on wifi"
#
# Env: POCKETPILOT_PORT (default 8787), POCKETPILOT_TOKEN (optional bearer token),
#      ADB (default adb).
set -euo pipefail

PORT="${POCKETPILOT_PORT:-8787}"
BASE="http://127.0.0.1:${PORT}"
TOKEN="${POCKETPILOT_TOKEN:-}"
ADB="${ADB:-adb}"

auth=()
if [[ -n "$TOKEN" ]]; then
  auth=(-H "Authorization: Bearer ${TOKEN}")
fi

json_escape() {
  printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' | tr '\n' ' '
}

cmd="${1:-help}"
shift || true

case "$cmd" in
  forward)
    "$ADB" forward "tcp:${PORT}" "tcp:${PORT}"
    echo "forwarded tcp:${PORT} -> device tcp:${PORT}"
    ;;
  mcp-url)
    echo "${BASE}/mcp"
    ;;
  health)
    curl -sS "${BASE}/health"; echo
    ;;
  status)
    curl -sS "${auth[@]}" "${BASE}/v1/status"; echo
    ;;
  observe)
    if [[ $# -ge 1 && -n "${1:-}" ]]; then
      curl -sS "${auth[@]}" "${BASE}/v1/observe?fmt=text&since=${1}"; echo
    else
      curl -sS "${auth[@]}" "${BASE}/v1/observe?fmt=text"; echo
    fi
    ;;
  tap)
    [[ $# -ge 1 ]] || { echo "usage: $0 tap <ref>" >&2; exit 2; }
    curl -sS "${auth[@]}" -X POST "${BASE}/v1/tool/tap" \
      -H 'content-type: application/json' -d "{\"ref\":$1}"; echo
    ;;
  type)
    [[ $# -ge 2 ]] || { echo "usage: $0 type <ref> <text> [--submit]" >&2; exit 2; }
    ref="$1"; shift
    submit=false
    if [[ "${*: -1}" == "--submit" ]]; then submit=true; set -- "${@:1:$(($#-1))}"; fi
    text="$(json_escape "$*")"
    curl -sS "${auth[@]}" -X POST "${BASE}/v1/tool/type" \
      -H 'content-type: application/json' \
      -d "{\"ref\":${ref},\"text\":\"${text}\",\"submit\":${submit}}"; echo
    ;;
  press)
    [[ $# -ge 1 ]] || { echo "usage: $0 press <back|home|recents|notifications|quick_settings|enter>" >&2; exit 2; }
    curl -sS "${auth[@]}" -X POST "${BASE}/v1/tool/press" \
      -H 'content-type: application/json' -d "{\"key\":\"$1\"}"; echo
    ;;
  open)
    [[ $# -ge 1 ]] || { echo "usage: $0 open <package|app name>" >&2; exit 2; }
    app="$(json_escape "$*")"
    curl -sS "${auth[@]}" -X POST "${BASE}/v1/tool/open_app" \
      -H 'content-type: application/json' -d "{\"app\":\"${app}\"}"; echo
    ;;
  tool)
    [[ $# -ge 1 ]] || { echo "usage: $0 tool <name> '<json args>'" >&2; exit 2; }
    name="$1"; shift
    payload="${1:-{}}"
    curl -sS "${auth[@]}" -X POST "${BASE}/v1/tool/${name}" \
      -H 'content-type: application/json' -d "${payload}"; echo
    ;;
  task)
    [[ $# -ge 1 ]] || { echo "usage: $0 task <steps.json>   # file containing the steps array" >&2; exit 2; }
    steps="$(cat "$1")"
    curl -sS "${auth[@]}" -X POST "${BASE}/v1/task" \
      -H 'content-type: application/json' -d "{\"steps\":${steps}}"; echo
    ;;
  agent)
    [[ $# -ge 1 ]] || { echo "usage: $0 agent <goal>" >&2; exit 2; }
    goal="$(json_escape "$*")"
    curl -sS "${auth[@]}" -X POST "${BASE}/v1/tool/run_agent" \
      -H 'content-type: application/json' \
      -d "{\"goal\":\"${goal}\",\"maxSteps\":20}"; echo
    ;;
  help|*)
    sed -n '2,14p' "$0" | sed 's/^# \{0,1\}//'
    ;;
esac
