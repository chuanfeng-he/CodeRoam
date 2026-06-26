"use strict";

const { createHash, randomUUID } = require("node:crypto");
const dns = require("node:dns");
const net = require("node:net");
const path = require("node:path");
const { spawn } = require("node:child_process");
const { WebSocket } = require("ws");
const { createPairingPayload, normalizeRelayBaseUrl } = require("../../shared/src/pairing");
const { createHostSecureTransport } = require("../../shared/src/secure-transport");
const {
  createSharedCodexTransport,
  createSpawnedCodexTransport,
} = require("./codex-transport");
const {
  buildLegacyContinuationPrompt,
  loadDesktopSessionThread,
} = require("./session-import");
const {
  loadOrCreateDeviceState,
  defaultStateDir,
  mergeTrustedAndroidKeyMap,
  saveDeviceState,
  trustedAndroidKeyMapFromState,
  writeBridgeConfig,
} = require("./device-state");

function startHost({
  relayUrl,
  stateDir,
  codexCommand = process.env.CODEX_REMOTE_CODEX_PATH || "codex",
  codexArgs = ["app-server"],
  appServerMode = process.env.CODEX_REMOTE_APP_SERVER_MODE || "shared",
  appServerSocket = process.env.CODEX_REMOTE_APP_SERVER_SOCKET,
  printQr = true,
  logger = console,
  WebSocketImpl = WebSocket,
  spawnImpl = spawn,
  relayHostOverrides = process.env.CODEX_REMOTE_RELAY_HOSTS,
  relayReconnectDelayMs = 1_000,
  codexRestartDelayMs = 1_000,
  desktopSessionImporter = loadDesktopSessionThread,
} = {}) {
  const relayBaseUrl = normalizeRelayBaseUrl(relayUrl || process.env.CODEX_REMOTE_RELAY || "");
  const deviceState = loadOrCreateDeviceState({ stateDir });
  const trustedAndroidKeys = trustedAndroidKeyMapFromState(deviceState);
  const sessionId = deviceState.relaySessionId || `session_${randomUUID()}`;
  if (!deviceState.relaySessionId) {
    deviceState.relaySessionId = sessionId;
    saveDeviceState({ stateDir, state: deviceState });
  }
  const pairingPayload = createPairingPayload({
    relayUrl: relayBaseUrl,
    sessionId,
    hostDeviceId: deviceState.hostDeviceId,
    hostIdentityPublicKey: deviceState.hostIdentity.publicKey,
    relayHostOverrides: parseRelayHostOverrides(relayHostOverrides),
    displayName: process.env.HOSTNAME || "linux-codex",
  });
  if (printQr) {
    printPairingQr(pairingPayload, logger);
  }

  const codexOptions = buildHostCodexTransportOptions({
    appServerMode,
    stateDir,
    codexCommand,
    codexArgs,
    appServerSocket,
  });
  writeBridgeConfig({
    stateDir,
    config: {
      relayUrl: relayBaseUrl,
      relayHostOverrides: Object.fromEntries(parseRelayHostOverrides(relayHostOverrides)),
      appServerMode: codexOptions.mode,
      appServerSocket: codexOptions.mode === "shared" ? codexOptions.socketPath : null,
      desktopRemoteCommand: codexOptions.mode === "shared" ? buildDesktopRemoteCommand(codexOptions.socketPath) : null,
    },
  });
  if (codexOptions.mode === "shared") {
    logger.log?.(`[host] desktop remote command: ${buildDesktopRemoteCommand(codexOptions.socketPath)}`);
  }
  let secureTransport = null;
  let stopped = false;
  let relayReconnectTimer = null;
  let socket = null;
  let codexWorkers = null;
  const socketUrl = `${relayBaseUrl}/${encodeURIComponent(sessionId)}?role=host`;

  codexWorkers = createCodexWorkerPool({
    options: codexOptions,
    spawnImpl,
    logger,
    codexRestartDelayMs,
    desktopSessionImporter,
    onMessage(message) {
      const currentSocket = socket;
      if (!secureTransport?.isReady() || currentSocket?.readyState !== WebSocketImpl.OPEN) {
        return;
      }
      currentSocket.send(JSON.stringify(secureTransport.encryptApplicationMessage(JSON.stringify(message))));
    },
    onExit({ code, signal, workerKey, threadId }) {
      notifyAndroidAppServerExited({ code, signal, workerKey, threadId });
    },
  });
  codexWorkers.ensureControlWorker();
  connectRelay();

  function connectRelay() {
    if (stopped) {
      return;
    }
    const nextSocket = new WebSocketImpl(socketUrl, buildRelayWebSocketOptions({
      relayBaseUrl,
      headers: { "x-role": "host" },
      relayHostOverrides,
    }));
    socket = nextSocket;
    const stopHeartbeat = attachHostWebSocketHeartbeat(nextSocket);
    nextSocket.on("open", () => logger.log?.(`[host] connected to relay ${socketUrl}`));
    nextSocket.on("close", (code, reason) => {
      stopHeartbeat();
      if (nextSocket !== socket) {
        return;
      }
      logger.error?.(`[host] relay closed (${code}) ${reason || ""}`);
      secureTransport = null;
      if (!stopped) {
        scheduleRelayReconnect();
      }
    });
    nextSocket.on("error", (error) => logger.error?.(`[host] relay error: ${error.message}`));
    nextSocket.on("message", (data) => {
      try {
        handleRelayMessage(String(data), nextSocket);
      } catch (error) {
        logger.error?.(`[host] failed to handle relay message: ${error.message}`);
      }
    });
  }

  function scheduleRelayReconnect() {
    if (relayReconnectTimer != null || stopped) {
      return;
    }
    relayReconnectTimer = setTimeout(() => {
      relayReconnectTimer = null;
      connectRelay();
    }, relayReconnectDelayMs);
  }

  function notifyAndroidAppServerExited({ code, signal, workerKey, threadId }) {
    const currentSocket = socket;
    if (currentSocket?.readyState !== WebSocketImpl.OPEN || !secureTransport?.isReady()) {
      return;
    }
    try {
      const notification = buildHostAppServerExitedNotification({ code, signal, workerKey, threadId });
      currentSocket.send(JSON.stringify(secureTransport.encryptApplicationMessage(JSON.stringify(notification))));
    } catch (error) {
      logger.error?.(`[host] failed to notify Android about app-server exit: ${error.message}`);
    }
  }

  function handleRelayMessage(rawText, relaySocket) {
    const message = normalizeRelayMessage(JSON.parse(rawText));
    logger.log?.(`[host] relay message kind=${message?.kind || "missing"} length=${rawText.length}`);
    switch (message.kind) {
    case "clientHello":
      secureTransport = createHostSecureTransport({
        sessionId,
        hostDeviceId: deviceState.hostDeviceId,
        hostIdentity: deviceState.hostIdentity,
        trustedAndroidKeys,
      });
      relaySocket.send(JSON.stringify(secureTransport.handleClientHello(message)));
      logger.log?.(`[host] sent serverHello session=${sessionId}`);
      break;
    case "clientAuth": {
      const ready = secureTransport.handleClientAuth(message);
      const nextState = mergeTrustedAndroidKeyMap(deviceState, trustedAndroidKeys);
      saveDeviceState({ stateDir, state: nextState });
      relaySocket.send(JSON.stringify(ready));
      logger.log?.(`[host] secure channel ready session=${sessionId}`);
      break;
    }
    case "encryptedEnvelope": {
      if (!secureTransport?.isReady()) {
        throw new Error("secure transport is not ready");
      }
      codexWorkers?.send(secureTransport.decryptApplicationMessage(message));
      break;
    }
    default:
      throw new Error(`unknown relay message kind: ${message.kind || "missing"}`);
    }
  }

  return {
    desktopRemoteCommand: codexOptions.mode === "shared" ? buildDesktopRemoteCommand(codexOptions.socketPath) : null,
    pairingPayload,
    sessionId,
    get socket() {
      return socket;
    },
    stop() {
      stopped = true;
      if (relayReconnectTimer != null) {
        clearTimeout(relayReconnectTimer);
        relayReconnectTimer = null;
      }
      socket?.close();
      codexWorkers?.stopAll();
    },
  };
}

function buildRelayWebSocketOptions({ headers = {}, relayHostOverrides } = {}) {
  const overrides = parseRelayHostOverrides(relayHostOverrides);
  if (overrides.size === 0) {
    return { headers };
  }
  return {
    headers,
    lookup(hostname, options, callback) {
      if (typeof options === "function") {
        callback = options;
        options = {};
      }
      const address = overrides.get(String(hostname || "").toLowerCase());
      if (address) {
        const family = net.isIP(address);
        if (options?.all) {
          callback(null, [{ address, family }]);
        } else {
          callback(null, address, family);
        }
        return;
      }
      dns.lookup(hostname, options, callback);
    },
  };
}

function parseRelayHostOverrides(value) {
  if (!value) {
    return new Map();
  }
  if (value instanceof Map) {
    return normalizeRelayHostOverrideEntries(value.entries());
  }
  if (typeof value === "object") {
    return normalizeRelayHostOverrideEntries(Object.entries(value));
  }
  const entries = String(value)
    .split(/[,\s]+/)
    .map((entry) => entry.trim())
    .filter(Boolean)
    .map((entry) => {
      const separator = entry.indexOf("=");
      return separator === -1 ? ["", ""] : [entry.slice(0, separator), entry.slice(separator + 1)];
    });
  return normalizeRelayHostOverrideEntries(entries);
}

function normalizeRelayHostOverrideEntries(entries) {
  const overrides = new Map();
  for (const [rawHost, rawAddress] of entries) {
    const host = String(rawHost || "").trim().toLowerCase();
    const address = String(rawAddress || "").trim();
    if (!host || !address) {
      continue;
    }
    if (net.isIP(address) === 0) {
      throw new Error(`relay host override for ${host} must be an IP address`);
    }
    overrides.set(host, address);
  }
  return overrides;
}

function buildHostCodexTransportOptions({
  appServerMode = "shared",
  stateDir = defaultStateDir(),
  codexCommand = "codex",
  codexArgs = ["app-server"],
  appServerSocket,
} = {}) {
  if (appServerMode === "private") {
    return {
      mode: "private",
      command: codexCommand,
      args: codexArgs,
    };
  }
  if (appServerMode !== "shared") {
    throw new Error(`unsupported app-server mode: ${appServerMode}`);
  }
  const socketPath = appServerSocket || `${stateDir}/app-server.sock`;
  return {
    mode: "shared",
    command: codexCommand,
    socketPath,
  };
}

function buildDesktopRemoteCommand(socketPath) {
  return `codex --remote unix://${socketPath}`;
}

function buildHostAppServerExitedNotification({ code, signal, workerKey, threadId } = {}) {
  const cleanSignal = signal || "";
  const params = {
    code,
    signal: cleanSignal,
    message: `codex app-server exited code=${code} signal=${cleanSignal}`,
  };
  if (workerKey) {
    params.workerKey = workerKey;
  }
  if (threadId) {
    params.threadId = threadId;
  }
  return {
    jsonrpc: "2.0",
    method: "host/appServerExited",
    params,
  };
}

const CONTROL_WORKER_KEY = "control";

function createCodexWorkerPool({
  options,
  spawnImpl,
  logger = console,
  codexRestartDelayMs = 1_000,
  desktopSessionImporter = loadDesktopSessionThread,
  onMessage = () => {},
  onExit = () => {},
}) {
  const workers = new Map();
  const threadWorkerKeys = new Map();
  const pendingThreadStarts = new Map();
  const pendingAndroidRequests = new Map();
  const pendingServerRequests = new Map();
  const pendingLegacyContinuations = new Map();
  const pendingInternalRequests = new Map();
  const restartTimers = new Set();
  let nextThreadStartWorkerId = 1;
  let nextHostRequestId = 1_000_000;
  let nextInternalRequestId = 2_000_000;
  let stopped = false;

  function ensureControlWorker() {
    return ensureWorker(CONTROL_WORKER_KEY);
  }

  function send(jsonText) {
    const route = routeAndroidMessage(jsonText);
    const worker = ensureWorker(route.workerKey, { threadId: route.threadId });
    if (route.androidRequest) {
      pendingAndroidRequests.set(String(route.androidRequest.id), {
        ...route.androidRequest,
        workerKey: worker.key,
      });
    }
    if (route.threadId) {
      threadWorkerKeys.set(route.threadId, worker.key);
    }
    if (route.pendingThreadStartRequestId != null) {
      pendingThreadStarts.set(String(route.pendingThreadStartRequestId), worker.key);
    }
    worker.forwarder.send(route.jsonText);
  }

  function routeAndroidMessage(jsonText) {
    const message = parseJsonObject(jsonText);
    if (!message) {
      return { workerKey: CONTROL_WORKER_KEY, jsonText };
    }
    const responseRoute = routeAndroidResponseToServerRequest(message);
    if (responseRoute) {
      return responseRoute;
    }

    const threadId = extractThreadIdFromRpcMessage(message);
    if (threadId) {
      if (options.mode === "shared") {
        return {
          workerKey: CONTROL_WORKER_KEY,
          threadId,
          jsonText,
          androidRequest: androidRequestFromMessage(message, threadId),
        };
      }
      const workerKey = threadWorkerKeys.get(threadId) || `thread:${threadId}`;
      return {
        workerKey,
        threadId,
        jsonText,
        androidRequest: androidRequestFromMessage(message, threadId),
      };
    }

    if (message.method === "thread/start") {
      if (options.mode === "shared") {
        return {
          workerKey: CONTROL_WORKER_KEY,
          jsonText,
          pendingThreadStartRequestId: message.id,
          androidRequest: androidRequestFromMessage(message, null),
        };
      }
      const workerKey = `thread-start:${nextThreadStartWorkerId}`;
      nextThreadStartWorkerId += 1;
      return {
        workerKey,
        jsonText,
        pendingThreadStartRequestId: message.id,
        androidRequest: androidRequestFromMessage(message, null),
      };
    }

    return {
      workerKey: CONTROL_WORKER_KEY,
      jsonText,
      androidRequest: androidRequestFromMessage(message, null),
    };
  }

  function routeAndroidResponseToServerRequest(message) {
    if (message.method || message.id == null || (message.result === undefined && message.error === undefined)) {
      return null;
    }
    const route = pendingServerRequests.get(String(message.id));
    if (!route) {
      return null;
    }
    pendingServerRequests.delete(String(message.id));
    return {
      workerKey: route.workerKey,
      jsonText: JSON.stringify({
        ...message,
        id: route.originalId,
      }),
    };
  }

  function ensureWorker(workerKey, { threadId = null } = {}) {
    const existing = workers.get(workerKey);
    if (existing) {
      if (threadId && !existing.threadId) {
        existing.threadId = threadId;
      }
      return existing;
    }

    const workerOptions = codexOptionsForWorker(options, workerKey);
    const codex = createCodexTransportForHost({
      options: workerOptions,
      spawnImpl,
      logger,
    });
    const worker = {
      key: workerKey,
      threadId,
      codex,
      forwarder: null,
    };
    worker.forwarder = createReadyMessageForwarder({
      ready: codex.ready,
      sendRaw: (message) => codex.transport.sendRaw(message),
      onError: (error) => logger.error?.(`[host] failed to forward message to codex worker ${workerKey}: ${error.message}`),
    });
    codex.ready.catch((error) => {
      logger.error?.(`[host] failed to initialize codex worker ${workerKey}: ${error.message}`);
      if (workerOptions.mode !== "shared" && workers.get(workerKey) === worker) {
        stopCodexTransport(codex);
      }
    });
    codex.transport.onMessage((message) => handleCodexMessage(worker, message));
    codex.child.on("exit", (code, signal) => handleWorkerExit(worker, code, signal));
    workers.set(workerKey, worker);
    return worker;
  }

  function handleCodexMessage(worker, message) {
    if (handlePendingLegacyContinuation(worker, message)) {
      return;
    }
    if (handleInternalRequestResponse(worker, message)) {
      return;
    }
    const pendingAndroidRequest = message.id == null ? null : pendingAndroidRequests.get(String(message.id));
    if (pendingAndroidRequest && isThreadNotFoundRpcError(message)) {
      pendingAndroidRequests.delete(String(message.id));
      if (handleThreadNotFoundFallback(worker, message, pendingAndroidRequest)) {
        return;
      }
    } else if (pendingAndroidRequest && isJsonRpcResponse(message)) {
      pendingAndroidRequests.delete(String(message.id));
    }
    learnThreadRouteFromCodexMessage(worker, message);
    onMessage(rewriteServerRequestForAndroid(worker, message));
  }

  function handleThreadNotFoundFallback(worker, message, request) {
    if (!request.threadId || !["thread/read", "thread/resume", "turn/start"].includes(request.method)) {
      return false;
    }
    const imported = importDesktopSession(request.threadId);
    if (!imported?.thread) {
      return false;
    }
    if (request.method === "turn/start") {
      startLegacyContinuation(worker, message, request, imported.thread);
      return true;
    }
    threadWorkerKeys.set(request.threadId, worker.key);
    onMessage({
      jsonrpc: "2.0",
      id: message.id,
      result: {
        thread: imported.thread,
        importedFrom: "desktop-session",
      },
    });
    return true;
  }

  function importDesktopSession(threadId) {
    try {
      return desktopSessionImporter(threadId);
    } catch (error) {
      logger.error?.(`[host] failed to import desktop session ${threadId}: ${error.message}`);
      return null;
    }
  }

  function startLegacyContinuation(failedWorker, failedMessage, request, importedThread) {
    const workerKey = options.mode === "shared"
      ? CONTROL_WORKER_KEY
      : `thread-start:${nextThreadStartWorkerId++}`;
    const worker = ensureWorker(workerKey);
    const startId = nextInternalRequestId++;
    pendingLegacyContinuations.set(String(startId), {
      originalRequestId: failedMessage.id,
      originalParams: isPlainObject(request.message.params) ? request.message.params : {},
      importedThread,
      workerKey: worker.key,
      failedWorkerKey: failedWorker.key,
    });
    worker.forwarder.send(JSON.stringify({
      jsonrpc: "2.0",
      id: startId,
      method: "thread/start",
      params: buildThreadStartParamsForContinuation(request.message.params, importedThread),
    }));
  }

  function handlePendingLegacyContinuation(worker, message) {
    if (message.id == null) {
      return false;
    }
    const continuation = pendingLegacyContinuations.get(String(message.id));
    if (!continuation) {
      return false;
    }
    pendingLegacyContinuations.delete(String(message.id));
    if (message.error) {
      onMessage({
        jsonrpc: "2.0",
        id: continuation.originalRequestId,
        error: {
          ...message.error,
          message: `failed to create mobile continuation for desktop session: ${message.error.message || "unknown error"}`,
        },
      });
      return true;
    }
    const newThreadId = extractThreadIdFromRpcResult(message.result);
    if (!newThreadId) {
      onMessage({
        jsonrpc: "2.0",
        id: continuation.originalRequestId,
        error: {
          code: -32603,
          message: "failed to create mobile continuation for desktop session: missing thread id",
        },
      });
      return true;
    }

    threadWorkerKeys.set(newThreadId, continuation.workerKey);
    const continuationWorker = workers.get(continuation.workerKey);
    if (continuationWorker && continuation.workerKey !== CONTROL_WORKER_KEY) {
      continuationWorker.threadId = newThreadId;
    }
    onMessage({
      jsonrpc: "2.0",
      id: continuation.originalRequestId,
      result: message.result,
    });

    const turnStartId = nextInternalRequestId++;
    pendingInternalRequests.set(String(turnStartId), {
      workerKey: worker.key,
      method: "turn/start",
      threadId: newThreadId,
    });
    worker.forwarder.send(JSON.stringify({
      jsonrpc: "2.0",
      id: turnStartId,
      method: "turn/start",
      params: buildContinuationTurnStartParams(
        continuation.originalParams,
        newThreadId,
        continuation.importedThread
      ),
    }));
    return true;
  }

  function handleInternalRequestResponse(worker, message) {
    if (message.id == null) {
      return false;
    }
    const internalRequest = pendingInternalRequests.get(String(message.id));
    if (!internalRequest) {
      return false;
    }
    pendingInternalRequests.delete(String(message.id));
    if (message.error) {
      logger.error?.(`[host] internal ${internalRequest.method} failed for ${internalRequest.threadId || worker.key}: ${message.error.message || "unknown error"}`);
    }
    return true;
  }

  function learnThreadRouteFromCodexMessage(worker, message) {
    const pendingKey = message.id == null ? null : pendingThreadStarts.get(String(message.id));
    if (pendingKey) {
      pendingThreadStarts.delete(String(message.id));
      const threadId = extractThreadIdFromRpcResult(message.result);
      if (threadId) {
        threadWorkerKeys.set(threadId, pendingKey);
        const pendingWorker = workers.get(pendingKey);
        if (pendingWorker) {
          pendingWorker.threadId = threadId;
        }
      }
    }

    const threadId = extractThreadIdFromRpcMessage(message) || extractThreadIdFromRpcResult(message.result);
    if (threadId && !threadWorkerKeys.has(threadId)) {
      threadWorkerKeys.set(threadId, worker.key);
    }
    if (threadId && !worker.threadId && worker.key !== CONTROL_WORKER_KEY) {
      worker.threadId = threadId;
    }
  }

  function rewriteServerRequestForAndroid(worker, message) {
    if (!isJsonRpcServerRequest(message)) {
      return message;
    }
    const hostRequestId = nextHostRequestId;
    nextHostRequestId += 1;
    pendingServerRequests.set(String(hostRequestId), {
      workerKey: worker.key,
      originalId: message.id,
    });
    return {
      ...message,
      id: hostRequestId,
    };
  }

  function handleWorkerExit(worker, code, signal) {
    if (stopped || workers.get(worker.key) !== worker) {
      return;
    }
    workers.delete(worker.key);
    removeWorkerRoutes(worker.key);
    logger.error?.(`[host] codex worker ${worker.key} exited code=${code} signal=${signal || ""}`);
    onExit({ code, signal, workerKey: worker.key, threadId: worker.threadId });
    const timer = setTimeout(() => {
      restartTimers.delete(timer);
      if (stopped || workers.has(worker.key)) {
        return;
      }
      stopCodexTransport(worker.codex);
      ensureWorker(worker.key, { threadId: worker.threadId });
    }, codexRestartDelayMs);
    restartTimers.add(timer);
  }

  function removeWorkerRoutes(workerKey) {
    for (const [threadId, mappedWorkerKey] of threadWorkerKeys.entries()) {
      if (mappedWorkerKey === workerKey) {
        threadWorkerKeys.delete(threadId);
      }
    }
    for (const [requestId, mappedWorkerKey] of pendingThreadStarts.entries()) {
      if (mappedWorkerKey === workerKey) {
        pendingThreadStarts.delete(requestId);
      }
    }
    for (const [requestId, route] of pendingServerRequests.entries()) {
      if (route.workerKey === workerKey) {
        pendingServerRequests.delete(requestId);
      }
    }
    for (const [requestId, route] of pendingAndroidRequests.entries()) {
      if (route.workerKey === workerKey) {
        pendingAndroidRequests.delete(requestId);
      }
    }
    for (const [requestId, route] of pendingLegacyContinuations.entries()) {
      if (route.workerKey === workerKey || route.failedWorkerKey === workerKey) {
        pendingLegacyContinuations.delete(requestId);
      }
    }
    for (const [requestId, route] of pendingInternalRequests.entries()) {
      if (route.workerKey === workerKey) {
        pendingInternalRequests.delete(requestId);
      }
    }
  }

  function stopAll() {
    stopped = true;
    for (const timer of restartTimers) {
      clearTimeout(timer);
    }
    restartTimers.clear();
    for (const worker of workers.values()) {
      stopCodexTransport(worker.codex);
    }
    workers.clear();
    threadWorkerKeys.clear();
    pendingThreadStarts.clear();
    pendingAndroidRequests.clear();
    pendingServerRequests.clear();
    pendingLegacyContinuations.clear();
    pendingInternalRequests.clear();
  }

  return {
    ensureControlWorker,
    send,
    stopAll,
  };
}

function codexOptionsForWorker(options, workerKey) {
  if (options.mode !== "shared" || workerKey === CONTROL_WORKER_KEY) {
    return options;
  }
  return {
    ...options,
    socketPath: socketPathForWorker(options.socketPath, workerKey),
  };
}

function socketPathForWorker(socketPath, workerKey) {
  const extension = path.extname(socketPath) || ".sock";
  const baseName = path.basename(socketPath, extension);
  return path.join(path.dirname(socketPath), `${baseName}.${workerSocketSuffix(workerKey)}${extension}`);
}

function workerSocketSuffix(workerKey) {
  return createHash("sha256")
    .update(String(workerKey))
    .digest("hex")
    .slice(0, 16);
}

function androidRequestFromMessage(message, threadId) {
  if (!isPlainObject(message) || message.id == null || typeof message.method !== "string") {
    return null;
  }
  return {
    id: message.id,
    method: message.method,
    threadId,
    message,
  };
}

function isJsonRpcResponse(message) {
  return isPlainObject(message)
    && message.id != null
    && message.method === undefined
    && (message.result !== undefined || message.error !== undefined);
}

function isThreadNotFoundRpcError(message) {
  if (!isJsonRpcResponse(message) || !isPlainObject(message.error)) {
    return false;
  }
  const errorText = String(message.error.message || message.error.error || "");
  return /thread\s+not\s+found/i.test(errorText);
}

function buildThreadStartParamsForContinuation(originalParams, importedThread) {
  const source = isPlainObject(originalParams) ? originalParams : {};
  const params = {};
  for (const key of ["cwd", "model", "reasoningEffort", "profile", "approvalPolicy", "sandbox"]) {
    if (source[key] !== undefined) {
      params[key] = source[key];
    }
  }
  if (params.cwd === undefined && isPlainObject(importedThread) && importedThread.cwd) {
    params.cwd = importedThread.cwd;
  }
  return params;
}

function buildContinuationTurnStartParams(originalParams, newThreadId, importedThread) {
  const params = isPlainObject(originalParams) ? { ...originalParams } : {};
  const originalPrompt = extractPromptTextFromTurnParams(params);
  const continuationPrompt = buildLegacyContinuationPrompt(importedThread, originalPrompt);
  params.threadId = newThreadId;
  if (Array.isArray(params.input)) {
    let replaced = false;
    params.input = params.input.map((item) => {
      if (!replaced && isPlainObject(item) && item.type === "text") {
        replaced = true;
        return {
          ...item,
          text: continuationPrompt,
          text_elements: Array.isArray(item.text_elements) ? item.text_elements : [],
        };
      }
      return item;
    });
    if (!replaced) {
      params.input = [
        { type: "text", text: continuationPrompt, text_elements: [] },
        ...params.input,
      ];
    }
  } else if (typeof params.input === "string") {
    params.input = continuationPrompt;
  } else if (typeof params.prompt === "string") {
    params.prompt = continuationPrompt;
  } else {
    params.input = [{ type: "text", text: continuationPrompt, text_elements: [] }];
  }
  return params;
}

function extractPromptTextFromTurnParams(params) {
  if (!isPlainObject(params)) {
    return "";
  }
  if (typeof params.prompt === "string" && params.prompt.trim()) {
    return params.prompt.trim();
  }
  if (typeof params.input === "string" && params.input.trim()) {
    return params.input.trim();
  }
  if (Array.isArray(params.input)) {
    return params.input
      .map((item) => {
        if (typeof item === "string") {
          return item.trim();
        }
        if (isPlainObject(item) && typeof item.text === "string") {
          return item.text.trim();
        }
        return "";
      })
      .filter(Boolean)
      .join("\n\n");
  }
  return "";
}

function parseJsonObject(jsonText) {
  try {
    const value = JSON.parse(jsonText);
    return isPlainObject(value) ? value : null;
  } catch {
    return null;
  }
}

function extractThreadIdFromRpcMessage(message) {
  if (!isPlainObject(message)) {
    return null;
  }
  return extractThreadIdFromObject(message.params);
}

function extractThreadIdFromRpcResult(result) {
  if (!isPlainObject(result)) {
    return null;
  }
  return stringField(result, "threadId", "thread_id")
    || stringField(result.thread, "id", "threadId", "thread_id");
}

function extractThreadIdFromObject(value) {
  if (!isPlainObject(value)) {
    return null;
  }
  return stringField(value, "threadId", "thread_id")
    || stringField(value.thread, "id", "threadId", "thread_id");
}

function stringField(value, ...fieldNames) {
  if (!isPlainObject(value)) {
    return null;
  }
  for (const fieldName of fieldNames) {
    const fieldValue = value[fieldName];
    if (typeof fieldValue === "string" && fieldValue.trim()) {
      return fieldValue;
    }
  }
  return null;
}

function isJsonRpcServerRequest(message) {
  return isPlainObject(message)
    && message.id != null
    && typeof message.method === "string"
    && message.result === undefined
    && message.error === undefined;
}

function isPlainObject(value) {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function createCodexTransportForHost({ options, spawnImpl, logger = console }) {
  const onStderr = (chunk) => {
    logger.error?.(`[codex] ${chunk.trimEnd()}`);
  };
  if (options.mode === "shared") {
    return createSharedCodexTransport({
      command: options.command,
      socketPath: options.socketPath,
      spawnImpl,
      onStderr,
    });
  }
  return createSpawnedCodexTransport({
    command: options.command,
    args: options.args,
    spawnImpl,
    onStderr,
  });
}

function createReadyMessageForwarder({ ready, sendRaw, onError = () => {} }) {
  let tail = Promise.resolve();

  function send(message) {
    tail = tail
      .then(() => ready)
      .then(() => sendRaw(message))
      .catch((error) => {
        onError(error);
      });
    return tail;
  }

  return {
    send,
    drained() {
      return tail;
    },
  };
}

function normalizeRelayMessage(message) {
  if (!message || typeof message !== "object" || message.kind) {
    return message;
  }
  const inferredKind = inferRelayMessageKind(message);
  return inferredKind ? { ...message, kind: inferredKind } : message;
}

function inferRelayMessageKind(message) {
  if (
    message.protocolVersion !== undefined &&
    message.sessionId &&
    message.androidDeviceId &&
    message.androidIdentityPublicKey &&
    message.androidEphemeralPublicKey &&
    message.clientNonce
  ) {
    return "clientHello";
  }
  if (
    message.sessionId &&
    message.androidDeviceId &&
    message.keyEpoch !== undefined &&
    message.androidSignature
  ) {
    return "clientAuth";
  }
  if (
    message.v !== undefined &&
    message.sessionId &&
    message.keyEpoch !== undefined &&
    message.sender &&
    message.counter !== undefined &&
    message.ciphertext &&
    message.tag
  ) {
    return "encryptedEnvelope";
  }
  return null;
}

function printPairingQr(pairingPayload, logger = console) {
  const text = JSON.stringify(pairingPayload);
  logger.log?.("[host] Scan this QR from the Android app:");
  try {
    require("qrcode-terminal").generate(text, { small: true });
  } catch {
    logger.log?.("[host] QR rendering failed; use the pairing JSON below.");
  }
  logger.log?.("[host] Pairing JSON:");
  logger.log?.(text);
}

function shutdownCodex(child) {
  if (!child || child.killed || (child.exitCode !== null && child.exitCode !== undefined)) {
    return;
  }
  child.kill("SIGTERM");
}

function stopCodexTransport(codex) {
  if (!codex) {
    return;
  }
  if (typeof codex.stop === "function") {
    codex.stop();
  } else {
    shutdownCodex(codex.child);
  }
}

function attachHostWebSocketHeartbeat(socket, intervalMs = 30_000) {
  if (typeof socket.ping !== "function") {
    return () => {};
  }
  socket.isAlive = true;
  socket.on?.("pong", () => {
    socket.isAlive = true;
  });
  const timer = setInterval(() => {
    if (socket.readyState !== undefined && socket.readyState !== 1) {
      return;
    }
    if (socket.isAlive === false) {
      clearInterval(timer);
      socket.terminate?.();
      return;
    }
    socket.isAlive = false;
    try {
      socket.ping();
    } catch {
      clearInterval(timer);
      socket.terminate?.();
    }
  }, intervalMs);
  timer.unref?.();
  return () => clearInterval(timer);
}

module.exports = {
  buildDesktopRemoteCommand,
  buildHostAppServerExitedNotification,
  buildHostCodexTransportOptions,
  buildRelayWebSocketOptions,
  createReadyMessageForwarder,
  inferRelayMessageKind,
  normalizeRelayMessage,
  startHost,
};
