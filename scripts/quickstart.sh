#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
relay_url="${CODEX_REMOTE_RELAY:-}"
skip_npm_install=0
start_local_relay=0
local_relay_host="${CODEX_REMOTE_LOCAL_RELAY_HOST:-0.0.0.0}"
local_relay_port="${CODEX_REMOTE_LOCAL_RELAY_PORT:-9000}"

usage() {
  cat <<'EOF'
Usage:
  CODEX_REMOTE_RELAY=wss://YOUR_DOMAIN/relay npm run quickstart
  npm run quickstart -- --relay-url wss://YOUR_DOMAIN/relay

Options:
  --relay-url URL       Relay URL used by Android and Linux bridge.
  --start-local-relay   Start this repo's relay in the background before the bridge.
  --local-relay-host H  Host for --start-local-relay. Default: 0.0.0.0.
  --local-relay-port P  Port for --start-local-relay. Default: 9000.
  --skip-npm-install   Do not run npm install before configuring the bridge.
  -h, --help           Show this help.

What this does:
  1. Installs Node dependencies unless skipped.
  2. Saves the relay URL in the bridge config.
  3. Optionally starts a local relay.
  4. Starts the bridge with systemd user service when available.
  5. Generates codex-remote-pairing.html/json/png/svg for scanning.

Generated pairing files are local credentials and are ignored by git.
EOF
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --relay-url)
      relay_url="${2:-}"
      shift 2
      ;;
    --start-local-relay)
      start_local_relay=1
      shift
      ;;
    --local-relay-host)
      local_relay_host="${2:-}"
      shift 2
      ;;
    --local-relay-port)
      local_relay_port="${2:-}"
      shift 2
      ;;
    --skip-npm-install)
      skip_npm_install=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [ -z "$relay_url" ]; then
  echo "Missing relay URL. Pass --relay-url or set CODEX_REMOTE_RELAY." >&2
  usage >&2
  exit 2
fi

cd "$repo_root"

state_dir="${XDG_CONFIG_HOME:-$HOME/.config}/codex-android-remote"
state_file="$state_dir/device-state.json"
bridge_log="$state_dir/bridge.log"
bridge_pid="$state_dir/bridge.pid"
relay_log="$state_dir/relay.log"
relay_pid="$state_dir/relay.pid"

mkdir -p "$state_dir"

if [ "$skip_npm_install" -eq 0 ]; then
  if [ -f package-lock.json ]; then
    npm install
  else
    npm install
  fi
fi

wait_for_relay() {
  for _ in $(seq 1 30); do
    if node - "$relay_url" <<'NODE' >/dev/null 2>&1
const net = require("node:net");
const url = new URL(process.argv[2]);
const port = Number(url.port || (url.protocol === "wss:" ? 443 : 80));
let host = url.hostname;
if (host === "0.0.0.0" || host === "::") {
  host = "127.0.0.1";
}
const socket = net.createConnection({ host, port, timeout: 1_000 }, () => {
  socket.end();
  process.exit(0);
});
socket.on("error", () => process.exit(1));
socket.on("timeout", () => {
  socket.destroy();
  process.exit(1);
});
NODE
    then
      return 0
    fi
    sleep 1
  done
  return 1
}

if [ "$start_local_relay" -eq 1 ]; then
  if [ -f "$relay_pid" ] && kill -0 "$(cat "$relay_pid")" >/dev/null 2>&1; then
    kill "$(cat "$relay_pid")" >/dev/null 2>&1 || true
  fi
  HOST="$local_relay_host" PORT="$local_relay_port" nohup node relay/src/server.js >"$relay_log" 2>&1 &
  echo "$!" >"$relay_pid"
  sleep 1
  if ! kill -0 "$(cat "$relay_pid")" >/dev/null 2>&1; then
    echo "Local relay failed to start. Relay log: $relay_log" >&2
    exit 1
  fi
  if ! wait_for_relay; then
    echo "Timed out waiting for local relay. Relay log: $relay_log" >&2
    exit 1
  fi
fi

node linux-bridge/bin/codex-remote.js host configure --relay-url "$relay_url"
node linux-bridge/bin/codex-remote.js service install --relay-url "$relay_url"

start_mode="systemd user service"

if systemctl --user show-environment >/dev/null 2>&1; then
  systemctl --user daemon-reload
  systemctl --user enable --now codex-android-remote.service
else
  start_mode="background process"
  if [ -f "$bridge_pid" ] && kill -0 "$(cat "$bridge_pid")" >/dev/null 2>&1; then
    kill "$(cat "$bridge_pid")" >/dev/null 2>&1 || true
  fi
  nohup node linux-bridge/bin/codex-remote.js host start --no-qr --relay-url "$relay_url" >"$bridge_log" 2>&1 &
  echo "$!" >"$bridge_pid"
fi

for _ in $(seq 1 30); do
  if node -e '
const fs = require("node:fs");
const file = process.argv[1];
if (!fs.existsSync(file)) process.exit(1);
const state = JSON.parse(fs.readFileSync(file, "utf8"));
if (!state.relaySessionId || !state.hostDeviceId || !state.hostIdentity?.publicKey) process.exit(1);
' "$state_file" >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

if ! node -e '
const fs = require("node:fs");
const file = process.argv[1];
if (!fs.existsSync(file)) process.exit(1);
const state = JSON.parse(fs.readFileSync(file, "utf8"));
if (!state.relaySessionId || !state.hostDeviceId || !state.hostIdentity?.publicKey) process.exit(1);
' "$state_file" >/dev/null 2>&1; then
  echo "Timed out waiting for bridge pairing state: $state_file" >&2
  if [ "$start_mode" = "background process" ]; then
    echo "Bridge log: $bridge_log" >&2
  else
    echo "Check logs with: journalctl --user -u codex-android-remote.service -n 80" >&2
  fi
  exit 1
fi

CODEX_REMOTE_RELAY="$relay_url" node scripts/generate-pairing-artifacts.js

cat <<EOF

CodeRoam is running.

Start mode:
  $start_mode

Pairing page:
  $repo_root/codex-remote-pairing.html

Pairing QR image:
  $repo_root/codex-remote-pairing.png

Service status:
  systemctl --user status codex-android-remote.service

Live logs:
  journalctl --user -u codex-android-remote.service -f

Fallback process files:
  $bridge_pid
  $bridge_log

Local relay files:
  $relay_pid
  $relay_log

EOF
