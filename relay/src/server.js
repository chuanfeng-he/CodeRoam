"use strict";

const fs = require("node:fs");
const http = require("node:http");
const path = require("node:path");
const { URL } = require("node:url");
const { WebSocketServer, WebSocket } = require("ws");
const { attachWebSocketHeartbeat, createRelayController } = require("./relay");

function createRelayServer({
  host = process.env.HOST || "0.0.0.0",
  port = Number(process.env.PORT || 9000),
  apkPath = process.env.CODEX_REMOTE_APK_PATH || path.resolve(__dirname, "../../android-app/app/build/outputs/apk/debug/app-debug.apk"),
  logger = console,
} = {}) {
  const relay = createRelayController({ WebSocketOpenState: WebSocket.OPEN });
  const server = http.createServer((req, res) => {
    if (req.url === "/health") {
      const body = JSON.stringify({ ok: true, relay: relay.stats() });
      res.writeHead(200, { "content-type": "application/json" });
      res.end(body);
      return;
    }
    if (req.url === "/app-debug.apk") {
      streamApk({ apkPath, res });
      return;
    }
    res.writeHead(404);
    res.end("not found");
  });
  const wss = new WebSocketServer({ noServer: true });

  server.on("upgrade", (req, socket, head) => {
    const parsed = new URL(req.url || "/", "http://relay.local");
    const match = parsed.pathname.match(/^\/relay\/([^/]+)$/);
    if (!match) {
      socket.destroy();
      return;
    }
    wss.handleUpgrade(req, socket, head, (ws) => {
      const sessionId = decodeURIComponent(match[1]);
      const role = req.headers["x-role"] || parsed.searchParams.get("role") || "";
      relay.attach(ws, { sessionId, role });
      attachWebSocketHeartbeat(ws);
    });
  });

  return {
    listen() {
      server.listen(port, host, () => {
        logger.log(`[relay] listening on ${host}:${port}`);
      });
      return server;
    },
    close(callback) {
      wss.close();
      server.close(callback);
    },
    relay,
    server,
  };
}

function streamApk({ apkPath, res }) {
  fs.stat(apkPath, (statError, stat) => {
    if (statError || !stat.isFile()) {
      res.writeHead(404);
      res.end("apk not found");
      return;
    }
    res.writeHead(200, {
      "content-type": "application/vnd.android.package-archive",
      "content-length": stat.size,
      "cache-control": "no-store",
    });
    fs.createReadStream(apkPath).pipe(res);
  });
}

if (require.main === module) {
  createRelayServer().listen();
}

module.exports = {
  createRelayServer,
  streamApk,
};
