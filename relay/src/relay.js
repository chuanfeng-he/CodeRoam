"use strict";

const CLOSE_CODE_INVALID_REQUEST = 4000;
const CLOSE_CODE_SESSION_UNAVAILABLE = 4002;
const CLOSE_CODE_MOBILE_REPLACED = 4003;

function createRelayController({ WebSocketOpenState = 1, logger = console } = {}) {
  const sessions = new Map();

  function attach(socket, { sessionId, role }) {
    const normalizedSessionId = normalizeString(sessionId);
    const normalizedRole = normalizeRole(role);
    if (!normalizedSessionId || !["host", "android"].includes(normalizedRole)) {
      logger.warn?.(`[relay] rejected connection role=${normalizedRole || "missing"} session=${normalizedSessionId || "missing"} reason=invalid_request`);
      socket.close?.(CLOSE_CODE_INVALID_REQUEST, "Missing sessionId or invalid role");
      return { ok: false, error: "invalid_request" };
    }

    if (normalizedRole === "android" && !sessions.has(normalizedSessionId)) {
      logger.warn?.(`[relay] rejected android session=${normalizedSessionId} reason=session_unavailable`);
      socket.close?.(CLOSE_CODE_SESSION_UNAVAILABLE, "Host session not available");
      return { ok: false, error: "session_unavailable" };
    }

    const session = sessions.get(normalizedSessionId) || {
      host: null,
      clients: new Set(),
    };
    sessions.set(normalizedSessionId, session);

    if (normalizedRole === "host") {
      if (session.host && session.host !== socket) {
        logger.warn?.(`[relay] replacing host session=${normalizedSessionId}`);
        session.host.close?.(4001, "Replaced by newer host connection");
      }
      session.host = socket;
    } else {
      for (const client of session.clients) {
        if (client !== socket) {
          logger.warn?.(`[relay] replacing android session=${normalizedSessionId}`);
          client.close?.(CLOSE_CODE_MOBILE_REPLACED, "Replaced by newer Android connection");
          session.clients.delete(client);
        }
      }
      session.clients.add(socket);
    }

    logger.log?.(`[relay] attached role=${normalizedRole} session=${normalizedSessionId} clients=${session.clients.size} hasHost=${Boolean(session.host)}`);
    socket.on?.("message", (data) => {
      routeMessage(session, normalizedRole, String(data));
    });
    socket.on?.("close", (code, reason) => {
      logger.log?.(`[relay] closed role=${normalizedRole} session=${normalizedSessionId} code=${code || ""} reason=${reason || ""}`);
      detach(normalizedSessionId, socket);
    });
    return { ok: true };
  }

  function routeMessage(session, role, message) {
    if (role === "host") {
      for (const client of session.clients) {
        if (client.readyState === WebSocketOpenState) {
          client.send(message);
        }
      }
      return;
    }
    if (session.host?.readyState === WebSocketOpenState) {
      session.host.send(message);
    }
  }

  function detach(sessionId, socket) {
    const session = sessions.get(sessionId);
    if (!session) {
      return;
    }
    if (session.host === socket) {
      session.host = null;
    }
    session.clients.delete(socket);
    if (!session.host && session.clients.size === 0) {
      sessions.delete(sessionId);
    }
  }

  function stats() {
    return {
      sessions: sessions.size,
      connectedAndroidClients: [...sessions.values()].reduce((count, session) => count + session.clients.size, 0),
    };
  }

  return { attach, stats };
}

function attachWebSocketHeartbeat(
  socket,
  {
    intervalMs = 30_000,
    setIntervalImpl = setInterval,
    clearIntervalImpl = clearInterval,
  } = {},
) {
  socket.isAlive = true;
  socket.on?.("pong", () => {
    socket.isAlive = true;
  });
  const timer = setIntervalImpl(() => {
    if (socket.isAlive === false) {
      clearIntervalImpl(timer);
      if (typeof socket.terminate === "function") {
        socket.terminate();
      } else {
        socket.close?.();
      }
      return;
    }
    socket.isAlive = false;
    socket.ping?.();
  }, intervalMs);
  timer?.unref?.();
  socket.on?.("close", () => clearIntervalImpl(timer));
  return timer;
}

function normalizeRole(role) {
  const normalized = normalizeString(role).toLowerCase();
  if (normalized === "mac") {
    return "host";
  }
  return normalized;
}

function normalizeString(value) {
  return typeof value === "string" ? value.trim() : "";
}

module.exports = {
  attachWebSocketHeartbeat,
  CLOSE_CODE_INVALID_REQUEST,
  CLOSE_CODE_MOBILE_REPLACED,
  CLOSE_CODE_SESSION_UNAVAILABLE,
  createRelayController,
};
