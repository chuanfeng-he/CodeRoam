#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
relay_url="${CODEX_REMOTE_RELAY:-}"
skip_node_install=0
skip_npm_install=0
start_local_relay=0
local_relay_host=""
local_relay_port=""
NODE_MAJOR="${CODEX_REMOTE_NODE_MAJOR:-22}"

usage() {
  cat <<'EOF'
Usage:
  sudo bash scripts/bootstrap-ubuntu.sh --relay-url wss://YOUR_DOMAIN/relay
  CODEX_REMOTE_RELAY=wss://YOUR_DOMAIN/relay sudo -E bash scripts/bootstrap-ubuntu.sh

Options:
  --relay-url URL        Relay URL used by Android and Linux bridge.
  --start-local-relay    Forward to quickstart and start this repo's relay.
  --local-relay-host H   Forward to quickstart for local relay host.
  --local-relay-port P   Forward to quickstart for local relay port.
  --skip-node-install    Require an existing Node.js 18+ installation.
  --skip-npm-install     Forward to quickstart and skip npm install.
  -h, --help            Show this help.

This bootstrap is for Debian/Ubuntu hosts. It installs Node.js from NodeSource
when Node.js 18+ is not already available, then delegates to quickstart.
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
    --skip-node-install)
      skip_node_install=1
      shift
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

as_root() {
  if [ "$(id -u)" -eq 0 ]; then
    "$@"
  elif command -v sudo >/dev/null 2>&1; then
    sudo "$@"
  else
    echo "Root privileges are required to install Node.js." >&2
    exit 1
  fi
}

has_node_18() {
  command -v node >/dev/null 2>&1 &&
    node -e 'process.exit(Number(process.versions.node.split(".")[0]) >= 18 ? 0 : 1)' >/dev/null 2>&1 &&
    command -v npm >/dev/null 2>&1
}

install_node_ubuntu() {
  if ! command -v apt-get >/dev/null 2>&1; then
    echo "This bootstrap needs apt-get. Install Node.js 18+ and npm manually, then run scripts/quickstart.sh." >&2
    exit 1
  fi

  if [ -r /etc/os-release ]; then
    . /etc/os-release
    case "${ID:-}" in
      ubuntu|debian)
        ;;
      *)
        case "${ID_LIKE:-}" in
          *debian*)
            ;;
          *)
            echo "Unsupported OS '${ID:-unknown}'. Install Node.js 18+ and npm manually, then run scripts/quickstart.sh." >&2
            exit 1
            ;;
        esac
        ;;
    esac
  fi

  as_root apt-get update
  as_root apt-get install -y ca-certificates curl gnupg
  as_root install -d -m 0755 /etc/apt/keyrings
  curl -fsSL https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key |
    as_root gpg --dearmor -o /etc/apt/keyrings/nodesource.gpg
  echo "deb [signed-by=/etc/apt/keyrings/nodesource.gpg] https://deb.nodesource.com/node_${NODE_MAJOR}.x nodistro main" |
    as_root tee /etc/apt/sources.list.d/nodesource.list >/dev/null
  as_root apt-get update
  as_root apt-get install -y nodejs
}

if ! has_node_18; then
  if [ "$skip_node_install" -eq 1 ]; then
    echo "Node.js 18+ and npm are required." >&2
    exit 1
  fi
  install_node_ubuntu
fi

if ! has_node_18; then
  echo "Node.js 18+ and npm are still unavailable after installation." >&2
  exit 1
fi

cd "$repo_root"

quickstart_args=(--relay-url "$relay_url")
if [ "$start_local_relay" -eq 1 ]; then
  quickstart_args+=(--start-local-relay)
fi
if [ -n "$local_relay_host" ]; then
  quickstart_args+=(--local-relay-host "$local_relay_host")
fi
if [ -n "$local_relay_port" ]; then
  quickstart_args+=(--local-relay-port "$local_relay_port")
fi
if [ "$skip_npm_install" -eq 1 ]; then
  quickstart_args+=(--skip-npm-install)
fi

npm run quickstart -- "${quickstart_args[@]}"
