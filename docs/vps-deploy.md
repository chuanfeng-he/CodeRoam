# VPS relay deployment

## Docker

```bash
git clone <your repo> codex-android-remote
cd codex-android-remote
docker compose -f deploy/docker-compose.yml up -d --build
```

Health check:

```bash
curl http://127.0.0.1:9000/health
```

## Reverse proxy

Terminate TLS with Caddy, Nginx, Traefik, or another reverse proxy and forward WebSocket upgrades to `127.0.0.1:9000`.

Example Caddyfile:

```text
codex.example.com {
  reverse_proxy 127.0.0.1:9000
}
```

Use this relay URL on Linux:

```bash
node linux-bridge/bin/codex-remote.js host configure --relay-url wss://codex.example.com/relay
```
