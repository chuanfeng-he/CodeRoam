"use strict";

const { EventEmitter } = require("node:events");
const { spawn } = require("node:child_process");
const fs = require("node:fs");
const path = require("node:path");

function createLineJsonRpcTransport({ process, requestTimeoutMs = 60_000 }) {
  let nextId = 1;
  const pending = new Map();
  const messages = new EventEmitter();

  function request(method, params = {}) {
    const id = nextId;
    nextId += 1;
    const message = { jsonrpc: "2.0", id, method, params };
    const payload = `${JSON.stringify(message)}\n`;
    if (!process.stdin?.writable) {
      return Promise.reject(new Error("Codex app-server stdin is not writable"));
    }
    process.stdin.write(payload);
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        pending.delete(id);
        reject(new Error(`Codex request timed out: ${method}`));
      }, requestTimeoutMs);
      timer.unref?.();
      pending.set(id, { resolve, reject, timer });
    });
  }

  function sendRaw(jsonText) {
    if (!process.stdin?.writable) {
      throw new Error("Codex app-server stdin is not writable");
    }
    process.stdin.write(jsonText.endsWith("\n") ? jsonText : `${jsonText}\n`);
  }

  function handleLine(line) {
    const trimmed = String(line).trim();
    if (!trimmed) {
      return;
    }
    const message = JSON.parse(trimmed);
    if (message.id != null && pending.has(message.id)) {
      const waiter = pending.get(message.id);
      pending.delete(message.id);
      clearTimeout(waiter.timer);
      if (message.error) {
        const error = new Error(message.error.message || "Codex app-server error");
        error.code = message.error.code;
        error.data = message.error.data;
        waiter.reject(error);
      } else {
        waiter.resolve(message.result);
      }
      return;
    }
    messages.emit("message", message);
    if (message.method) {
      messages.emit("notification", message);
    }
  }

  function onMessage(handler) {
    messages.on("message", handler);
    return () => messages.off("message", handler);
  }

  function onNotification(handler) {
    messages.on("notification", handler);
    return () => messages.off("notification", handler);
  }

  return {
    handleLine,
    onMessage,
    onNotification,
    request,
    sendRaw,
  };
}

function consumeJsonMessages(buffer, handleMessageText) {
  let startIndex = -1;
  let depth = 0;
  let inString = false;
  let escaped = false;

  for (let index = 0; index < buffer.length; index += 1) {
    const char = buffer[index];
    if (startIndex === -1) {
      if (/\s/.test(char)) {
        continue;
      }
      if (char !== "{") {
        throw new Error(`unexpected app-server output before JSON object: ${char}`);
      }
      startIndex = index;
      depth = 1;
      continue;
    }

    if (inString) {
      if (escaped) {
        escaped = false;
      } else if (char === "\\") {
        escaped = true;
      } else if (char === "\"") {
        inString = false;
      }
      continue;
    }

    if (char === "\"") {
      inString = true;
    } else if (char === "{") {
      depth += 1;
    } else if (char === "}") {
      depth -= 1;
      if (depth === 0) {
        handleMessageText(buffer.slice(startIndex, index + 1));
        startIndex = -1;
      }
    }
  }

  return startIndex === -1 ? "" : buffer.slice(startIndex);
}

function createSpawnedCodexTransport({
  command = "codex",
  args = ["app-server"],
  env = process.env,
  cwd = process.cwd(),
  spawnImpl = spawn,
  onStderr = () => {},
  clientInfo = { name: "codex-android-remote", version: "0.1.0" },
} = {}) {
  const child = spawnImpl(command, args, {
    cwd,
    env: { ...env },
    stdio: ["pipe", "pipe", "pipe"],
  });
  const transport = createLineJsonRpcTransport({ process: child });
  let stdoutBuffer = "";
  let rejectSpawnError = null;
  const spawnError = new Promise((_, reject) => {
    rejectSpawnError = reject;
  });

  child.stdout.on("data", (chunk) => {
    stdoutBuffer += chunk.toString("utf8");
    stdoutBuffer = consumeJsonMessages(stdoutBuffer, (messageText) => transport.handleLine(messageText));
  });
  child.stderr.on("data", (chunk) => onStderr(chunk.toString("utf8")));
  child.on?.("error", (error) => {
    onStderr(`process error: ${error.message}\n`);
    rejectSpawnError?.(error);
  });
  const ready = Promise.race([transport.request("initialize", {
    clientInfo,
    capabilities: null,
  }), spawnError]);

  return { child, ready, transport };
}

function createSharedCodexTransport({
  command = "codex",
  socketPath,
  env = process.env,
  cwd = process.cwd(),
  spawnImpl = spawn,
  onStderr = () => {},
  clientInfo = { name: "codex-android-remote", version: "0.1.0" },
  socketReadyTimeoutMs = 10_000,
  socketPollIntervalMs = 50,
} = {}) {
  if (!socketPath) {
    throw new Error("socketPath is required for shared app-server mode");
  }
  fs.mkdirSync(path.dirname(socketPath), { recursive: true });
  try {
    fs.unlinkSync(socketPath);
  } catch (error) {
    if (error.code !== "ENOENT") {
      throw error;
    }
  }
  const listener = spawnImpl(command, ["app-server", "--listen", `unix://${socketPath}`], {
    cwd,
    env: { ...env },
    stdio: ["ignore", "pipe", "pipe"],
  });
  const lifecycleChild = new EventEmitter();
  let proxy = null;
  let stopped = false;
  let lifecycleExited = false;
  const messageHandlers = new Set();
  const notificationHandlers = new Set();

  function emitLifecycleExit(code, signal) {
    if (stopped || lifecycleExited) {
      return;
    }
    lifecycleExited = true;
    lifecycleChild.emit("exit", code, signal);
  }

  listener.on?.("exit", (code, signal) => emitLifecycleExit(code, signal));
  listener.on?.("error", (error) => {
    onStderr(`process error: ${error.message}\n`);
    emitLifecycleExit(1, null);
  });

  const ready = waitForSharedSocket({
    listener,
    socketPath,
    timeoutMs: socketReadyTimeoutMs,
    pollIntervalMs: socketPollIntervalMs,
    onOutput: onStderr,
  })
    .then(() => {
      if (stopped) {
        throw new Error("shared codex transport stopped before proxy startup");
      }
      proxy = createSpawnedCodexTransport({
        command,
        args: ["app-server", "proxy", "--sock", socketPath],
        env,
        cwd,
        spawnImpl,
        onStderr,
        clientInfo,
      });
      for (const handler of messageHandlers) {
        proxy.transport.onMessage(handler);
      }
      for (const handler of notificationHandlers) {
        proxy.transport.onNotification(handler);
      }
      proxy.child.on?.("exit", (code, signal) => emitLifecycleExit(code, signal));
      return proxy.ready;
    })
    .catch((error) => {
      process.nextTick(() => emitLifecycleExit(1, null));
      throw error;
    });

  const transport = {
    request(method, params) {
      if (proxy) {
        return proxy.transport.request(method, params);
      }
      return ready.then(() => proxy.transport.request(method, params));
    },
    sendRaw(jsonText) {
      if (!proxy) {
        throw new Error("Codex app-server proxy is not ready");
      }
      proxy.transport.sendRaw(jsonText);
    },
    onMessage(handler) {
      messageHandlers.add(handler);
      proxy?.transport.onMessage(handler);
      return () => {
        messageHandlers.delete(handler);
      };
    },
    onNotification(handler) {
      notificationHandlers.add(handler);
      proxy?.transport.onNotification(handler);
      return () => {
        notificationHandlers.delete(handler);
      };
    },
  };

  function stop() {
    stopped = true;
    shutdownChild(proxy?.child);
    shutdownChild(listener);
  }

  lifecycleChild.kill = (signal) => stop(signal);

  return {
    child: lifecycleChild,
    get proxyChild() {
      return proxy?.child || null;
    },
    listenerChild: listener,
    ready,
    transport,
    stop,
  };
}

function waitForSharedSocket({
  listener,
  socketPath,
  timeoutMs,
  pollIntervalMs,
  onOutput = () => {},
}) {
  return new Promise((resolve, reject) => {
    let settled = false;
    const cleanup = () => {
      clearTimeout(timeout);
      clearInterval(interval);
      listener.stdout?.off?.("data", handleOutput);
      listener.stderr?.off?.("data", handleOutput);
      listener.off?.("exit", handleExit);
      listener.off?.("error", handleError);
    };
    const settle = (fn, value) => {
      if (settled) {
        return;
      }
      settled = true;
      cleanup();
      fn(value);
    };
    const checkSocket = () => {
      if (fs.existsSync(socketPath)) {
        settle(resolve);
      }
    };
    const handleOutput = (chunk) => {
      const text = chunk.toString("utf8");
      onOutput(text);
      if (text.includes("control socket listening") || text.includes(socketPath)) {
        settle(resolve);
        return;
      }
      checkSocket();
    };
    const handleExit = (code, signal) => {
      settle(reject, new Error(`codex app-server listener exited before socket was ready code=${code} signal=${signal || ""}`));
    };
    const handleError = (error) => {
      settle(reject, error);
    };
    const timeout = setTimeout(() => {
      settle(reject, new Error(`timed out waiting for codex app-server socket: ${socketPath}`));
    }, timeoutMs);
    timeout.unref?.();
    const interval = setInterval(checkSocket, pollIntervalMs);
    interval.unref?.();
    listener.stdout?.on?.("data", handleOutput);
    listener.stderr?.on?.("data", handleOutput);
    listener.on?.("exit", handleExit);
    listener.on?.("error", handleError);
    checkSocket();
  });
}

function shutdownChild(child) {
  if (!child || child.killed || (child.exitCode !== null && child.exitCode !== undefined)) {
    return;
  }
  child.kill("SIGTERM");
}

module.exports = {
  consumeJsonMessages,
  createLineJsonRpcTransport,
  createSharedCodexTransport,
  createSpawnedCodexTransport,
};
