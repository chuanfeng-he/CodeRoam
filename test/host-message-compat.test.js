const assert = require("node:assert/strict");
const test = require("node:test");
const { EventEmitter } = require("node:events");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

const {
  createAndroidSecureClient,
  createIdentity,
} = require("../shared/src/secure-transport");

const {
  buildDesktopRemoteCommand,
  buildHostAppServerExitedNotification,
  buildHostCodexTransportOptions,
  buildRelayWebSocketOptions,
  createReadyMessageForwarder,
  inferRelayMessageKind,
  normalizeRelayMessage,
  startHost,
} = require("../linux-bridge/src/host");

class FakeWebSocket extends EventEmitter {
  constructor(url, options) {
    super();
    this.url = url;
    this.options = options;
    this.readyState = FakeWebSocket.OPEN;
    this.sent = [];
    this.closeCalls = [];
    FakeWebSocket.instances.push(this);
  }

  send(message) {
    this.sent.push(String(message));
  }

  close(code = 1000, reason = "") {
    this.readyState = FakeWebSocket.CLOSED;
    this.closeCalls.push({ code, reason });
    this.emit("close", code, reason);
  }
}

FakeWebSocket.OPEN = 1;
FakeWebSocket.CLOSED = 3;
FakeWebSocket.instances = [];

function makeFakeSpawn() {
  const children = [];
  const calls = [];
  function spawnImpl(command, args) {
    calls.push({ command, args });
    const child = new EventEmitter();
    child.stdin = {
      writable: true,
      writes: [],
      write(chunk) {
        this.writes.push(String(chunk));
      },
    };
    child.stdout = new EventEmitter();
    child.stderr = new EventEmitter();
    child.killSignal = null;
    child.kill = (signal) => {
      child.killSignal = signal;
    };
    children.push(child);
    return child;
  }
  return { children, calls, spawnImpl };
}

function makeStateDir() {
  return fs.mkdtempSync(path.join(os.tmpdir(), "codex-remote-host-test-"));
}

function waitForTimers() {
  return new Promise((resolve) => setTimeout(resolve, 5));
}

function completeInitialize(child) {
  const initializeRequest = child.stdin.writes
    .map((line) => JSON.parse(line))
    .find((message) => message.method === "initialize");
  assert.ok(initializeRequest, "expected app-server initialize request");
  child.stdout.emit("data", Buffer.from(JSON.stringify({
    jsonrpc: "2.0",
    id: initializeRequest.id,
    result: {
      userAgent: "codex-test",
      codexHome: "/tmp/codex",
      platformFamily: "unix",
      platformOs: "linux",
    },
  })));
}

async function startSharedProxy(fake, listenerIndex = 0) {
  fake.children[listenerIndex].stdout.emit("data", Buffer.from(
    "app-server control socket listening socket_path=/tmp/codex-remote/app-server.sock\n"
  ));
  await waitForTimers();
  return fake.children.at(-1);
}

function codexApplicationWrites(child) {
  return child.stdin.writes
    .map((line) => JSON.parse(line))
    .filter((message) => message.method !== "initialize");
}

function connectAndroidSecureClient(host, socket, androidDeviceId = "android-1") {
  const androidIdentity = createIdentity();
  const client = createAndroidSecureClient({
    sessionId: host.sessionId,
    androidDeviceId,
    androidIdentity,
    expectedHostDeviceId: host.pairingPayload.hostDeviceId,
    expectedHostIdentityPublicKey: host.pairingPayload.hostIdentityPublicKey,
  });

  socket.emit("message", JSON.stringify(client.createClientHello()));
  const serverHello = JSON.parse(socket.sent.at(-1));
  socket.emit("message", JSON.stringify(client.handleServerHello(serverHello)));
  const secureReady = JSON.parse(socket.sent.at(-1));
  client.handleSecureReady(secureReady);

  return client;
}

test("host infers missing client hello kind from Android defaults", () => {
  const message = {
    protocolVersion: 1,
    sessionId: "session-1",
    androidDeviceId: "android-1",
    androidIdentityPublicKey: "android-public",
    androidEphemeralPublicKey: "android-ephemeral",
    clientNonce: "nonce",
  };

  assert.equal(inferRelayMessageKind(message), "clientHello");
  assert.deepEqual(normalizeRelayMessage(message), {
    ...message,
    kind: "clientHello",
  });
});

test("host infers missing client auth and envelope kinds", () => {
  assert.equal(inferRelayMessageKind({
    sessionId: "session-1",
    androidDeviceId: "android-1",
    keyEpoch: 1,
    androidSignature: "sig",
  }), "clientAuth");

  assert.equal(inferRelayMessageKind({
    v: 1,
    sessionId: "session-1",
    keyEpoch: 1,
    sender: "android",
    counter: 0,
    ciphertext: "ciphertext",
    tag: "tag",
  }), "encryptedEnvelope");
});

test("host preserves explicit message kind", () => {
  const message = { kind: "clientHello", androidDeviceId: "android-1" };

  assert.equal(normalizeRelayMessage(message), message);
});

test("host waits for app-server initialization before forwarding Android RPC", async () => {
  const sent = [];
  let resolveReady;
  const ready = new Promise((resolve) => {
    resolveReady = resolve;
  });
  const forwarder = createReadyMessageForwarder({
    ready,
    sendRaw(message) {
      sent.push(message);
    },
  });

  forwarder.send("{\"id\":2,\"method\":\"thread/list\",\"params\":{\"limit\":30}}");
  assert.deepEqual(sent, []);

  resolveReady();
  await forwarder.drained();

  assert.deepEqual(sent, ["{\"id\":2,\"method\":\"thread/list\",\"params\":{\"limit\":30}}"]);
});

test("host routes each mobile thread to an isolated codex worker", async () => {
  FakeWebSocket.instances = [];
  const stateDir = makeStateDir();
  const fake = makeFakeSpawn();

  const host = startHost({
    relayUrl: "ws://relay.example/relay",
    stateDir,
    printQr: false,
    WebSocketImpl: FakeWebSocket,
    spawnImpl: fake.spawnImpl,
    appServerMode: "private",
    logger: { log() {}, error() {} },
  });
  const socket = FakeWebSocket.instances[0];
  const android = connectAndroidSecureClient(host, socket);

  assert.equal(fake.children.length, 1, "control worker should be started eagerly");
  completeInitialize(fake.children[0]);

  socket.emit("message", JSON.stringify(android.encryptApplicationMessage(JSON.stringify({
    jsonrpc: "2.0",
    id: 101,
    method: "turn/start",
    params: { threadId: "thread-a", prompt: "hi from a" },
  }))));
  assert.equal(fake.children.length, 2, "first thread should create its own worker");
  completeInitialize(fake.children[1]);
  await waitForTimers();

  socket.emit("message", JSON.stringify(android.encryptApplicationMessage(JSON.stringify({
    jsonrpc: "2.0",
    id: 102,
    method: "turn/start",
    params: { threadId: "thread-b", prompt: "hi from b" },
  }))));
  assert.equal(fake.children.length, 3, "second thread should create a separate worker");
  completeInitialize(fake.children[2]);
  await waitForTimers();

  assert.deepEqual(codexApplicationWrites(fake.children[0]), []);
  assert.deepEqual(codexApplicationWrites(fake.children[1]).map((message) => message.params.threadId), ["thread-a"]);
  assert.deepEqual(codexApplicationWrites(fake.children[2]).map((message) => message.params.threadId), ["thread-b"]);

  host.stop();
});

test("host shared mode routes thread traffic through the shared control worker", async () => {
  FakeWebSocket.instances = [];
  const stateDir = makeStateDir();
  const fake = makeFakeSpawn();

  const host = startHost({
    relayUrl: "ws://relay.example/relay",
    stateDir,
    printQr: false,
    WebSocketImpl: FakeWebSocket,
    spawnImpl: fake.spawnImpl,
    appServerMode: "shared",
    logger: { log() {}, error() {} },
  });
  const socket = FakeWebSocket.instances[0];
  const android = connectAndroidSecureClient(host, socket);

  assert.equal(fake.calls.length, 1, "shared mode should start the listener first");
  const controlProxy = await startSharedProxy(fake);
  assert.equal(fake.calls.length, 2, "shared mode should start one listener and one proxy");
  completeInitialize(controlProxy);

  socket.emit("message", JSON.stringify(android.encryptApplicationMessage(JSON.stringify({
    jsonrpc: "2.0",
    id: 151,
    method: "turn/start",
    params: { threadId: "desktop-thread-a", prompt: "hi from a" },
  }))));
  await waitForTimers();

  socket.emit("message", JSON.stringify(android.encryptApplicationMessage(JSON.stringify({
    jsonrpc: "2.0",
    id: 152,
    method: "turn/start",
    params: { threadId: "desktop-thread-b", prompt: "hi from b" },
  }))));
  await waitForTimers();

  assert.equal(fake.calls.length, 2, "shared mode must not spawn per-thread app-server sockets");
  assert.deepEqual(codexApplicationWrites(controlProxy).map((message) => message.params.threadId), [
    "desktop-thread-a",
    "desktop-thread-b",
  ]);

  host.stop();
});

test("host binds thread/start result to the worker that created the mobile thread", async () => {
  FakeWebSocket.instances = [];
  const stateDir = makeStateDir();
  const fake = makeFakeSpawn();

  const host = startHost({
    relayUrl: "ws://relay.example/relay",
    stateDir,
    printQr: false,
    WebSocketImpl: FakeWebSocket,
    spawnImpl: fake.spawnImpl,
    appServerMode: "private",
    logger: { log() {}, error() {} },
  });
  const socket = FakeWebSocket.instances[0];
  const android = connectAndroidSecureClient(host, socket);
  completeInitialize(fake.children[0]);

  socket.emit("message", JSON.stringify(android.encryptApplicationMessage(JSON.stringify({
    jsonrpc: "2.0",
    id: 201,
    method: "thread/start",
    params: { cwd: "/tmp/project" },
  }))));
  assert.equal(fake.children.length, 2);
  completeInitialize(fake.children[1]);
  await waitForTimers();

  fake.children[1].stdout.emit("data", Buffer.from(JSON.stringify({
    jsonrpc: "2.0",
    id: 201,
    result: {
      thread: {
        id: "thread-new",
        title: "New thread",
      },
    },
  })));
  await waitForTimers();

  socket.emit("message", JSON.stringify(android.encryptApplicationMessage(JSON.stringify({
    jsonrpc: "2.0",
    id: 202,
    method: "turn/start",
    params: { threadId: "thread-new", prompt: "continue" },
  }))));
  await waitForTimers();

  assert.equal(fake.children.length, 2, "known new thread should reuse its creator worker");
  assert.deepEqual(codexApplicationWrites(fake.children[1]).map((message) => message.method), [
    "thread/start",
    "turn/start",
  ]);

  host.stop();
});

test("host imports desktop session history when a mobile worker cannot find the thread", async () => {
  FakeWebSocket.instances = [];
  const stateDir = makeStateDir();
  const fake = makeFakeSpawn();
  const importedThread = {
    id: "legacy-thread",
    title: "Legacy desktop thread",
    cwd: "/tmp/project",
    turns: [
      {
        id: "turn-1",
        status: "completed",
        items: [
          { id: "item-1", type: "userMessage", text: "旧问题" },
          { id: "item-2", type: "agentMessage", text: "旧回答" },
        ],
      },
    ],
  };
  const importerCalls = [];

  const host = startHost({
    relayUrl: "ws://relay.example/relay",
    stateDir,
    printQr: false,
    WebSocketImpl: FakeWebSocket,
    spawnImpl: fake.spawnImpl,
    appServerMode: "private",
    desktopSessionImporter(threadId) {
      importerCalls.push(threadId);
      return { thread: importedThread };
    },
    logger: { log() {}, error() {} },
  });
  const socket = FakeWebSocket.instances[0];
  const android = connectAndroidSecureClient(host, socket);
  completeInitialize(fake.children[0]);

  socket.emit("message", JSON.stringify(android.encryptApplicationMessage(JSON.stringify({
    jsonrpc: "2.0",
    id: 401,
    method: "thread/read",
    params: { threadId: "legacy-thread" },
  }))));
  assert.equal(fake.children.length, 2);
  completeInitialize(fake.children[1]);
  await waitForTimers();

  fake.children[1].stdout.emit("data", Buffer.from(JSON.stringify({
    jsonrpc: "2.0",
    id: 401,
    error: { code: -32602, message: "thread not found: legacy-thread" },
  })));
  await waitForTimers();

  const response = JSON.parse(android.decryptApplicationMessage(JSON.parse(socket.sent.at(-1))));
  assert.deepEqual(importerCalls, ["legacy-thread"]);
  assert.equal(response.id, 401);
  assert.equal(response.result.thread.id, "legacy-thread");
  assert.equal(response.result.thread.turns[0].items[1].text, "旧回答");

  host.stop();
});

test("host forks an imported desktop session before continuing a turn on mobile", async () => {
  FakeWebSocket.instances = [];
  const stateDir = makeStateDir();
  const fake = makeFakeSpawn();
  const importedThread = {
    id: "legacy-thread",
    title: "Legacy desktop thread",
    cwd: "/tmp/project",
    turns: [
      {
        id: "turn-1",
        status: "completed",
        items: [
          { id: "item-1", type: "userMessage", text: "旧问题" },
          { id: "item-2", type: "agentMessage", text: "旧回答" },
        ],
      },
    ],
  };

  const host = startHost({
    relayUrl: "ws://relay.example/relay",
    stateDir,
    printQr: false,
    WebSocketImpl: FakeWebSocket,
    spawnImpl: fake.spawnImpl,
    appServerMode: "private",
    desktopSessionImporter() {
      return { thread: importedThread };
    },
    logger: { log() {}, error() {} },
  });
  const socket = FakeWebSocket.instances[0];
  const android = connectAndroidSecureClient(host, socket);
  completeInitialize(fake.children[0]);

  socket.emit("message", JSON.stringify(android.encryptApplicationMessage(JSON.stringify({
    jsonrpc: "2.0",
    id: 501,
    method: "turn/start",
    params: {
      threadId: "legacy-thread",
      input: [{ type: "text", text: "继续做" }],
      model: "gpt-test",
    },
  }))));
  assert.equal(fake.children.length, 2);
  completeInitialize(fake.children[1]);
  await waitForTimers();

  fake.children[1].stdout.emit("data", Buffer.from(JSON.stringify({
    jsonrpc: "2.0",
    id: 501,
    error: { code: -32602, message: "thread not found: legacy-thread" },
  })));
  await waitForTimers();

  assert.equal(fake.children.length, 3, "legacy continuation should allocate a mobile worker");
  completeInitialize(fake.children[2]);
  await waitForTimers();

  const startRequest = codexApplicationWrites(fake.children[2]).find((message) => message.method === "thread/start");
  assert.ok(startRequest, "expected a replacement thread/start");
  assert.equal(startRequest.params.cwd, "/tmp/project");
  fake.children[2].stdout.emit("data", Buffer.from(JSON.stringify({
    jsonrpc: "2.0",
    id: startRequest.id,
    result: {
      thread: {
        id: "mobile-thread",
        title: "Legacy desktop thread",
      },
    },
  })));
  await waitForTimers();

  const response = JSON.parse(android.decryptApplicationMessage(JSON.parse(socket.sent.at(-1))));
  assert.equal(response.id, 501);
  assert.equal(response.result.thread.id, "mobile-thread");

  const turnRequest = codexApplicationWrites(fake.children[2]).find((message) => message.method === "turn/start");
  assert.ok(turnRequest, "expected the user turn to continue on the replacement thread");
  assert.equal(turnRequest.params.threadId, "mobile-thread");
  const textInput = turnRequest.params.input.find((item) => item.type === "text").text;
  assert.match(textInput, /旧问题/);
  assert.match(textInput, /旧回答/);
  assert.match(textInput, /继续做/);

  host.stop();
});

test("host rewrites server request ids and routes Android responses back to the source worker", async () => {
  FakeWebSocket.instances = [];
  const stateDir = makeStateDir();
  const fake = makeFakeSpawn();

  const host = startHost({
    relayUrl: "ws://relay.example/relay",
    stateDir,
    printQr: false,
    WebSocketImpl: FakeWebSocket,
    spawnImpl: fake.spawnImpl,
    appServerMode: "private",
    logger: { log() {}, error() {} },
  });
  const socket = FakeWebSocket.instances[0];
  const android = connectAndroidSecureClient(host, socket);
  completeInitialize(fake.children[0]);

  socket.emit("message", JSON.stringify(android.encryptApplicationMessage(JSON.stringify({
    jsonrpc: "2.0",
    id: 301,
    method: "turn/start",
    params: { threadId: "thread-a", prompt: "needs approval" },
  }))));
  assert.equal(fake.children.length, 2);
  completeInitialize(fake.children[1]);
  await waitForTimers();

  fake.children[1].stdout.emit("data", Buffer.from(JSON.stringify({
    jsonrpc: "2.0",
    id: 1,
    method: "approval/request",
    params: { threadId: "thread-a", command: "ls" },
  })));
  await waitForTimers();

  const rewrittenServerRequest = JSON.parse(android.decryptApplicationMessage(JSON.parse(socket.sent.at(-1))));
  assert.equal(rewrittenServerRequest.method, "approval/request");
  assert.equal(rewrittenServerRequest.id, 1_000_000);

  socket.emit("message", JSON.stringify(android.encryptApplicationMessage(JSON.stringify({
    jsonrpc: "2.0",
    id: rewrittenServerRequest.id,
    result: { decision: "approved" },
  }))));
  await waitForTimers();

  const workerWrites = codexApplicationWrites(fake.children[1]);
  assert.deepEqual(workerWrites.at(-1), {
    jsonrpc: "2.0",
    id: 1,
    result: { decision: "approved" },
  });
  assert.equal(fake.children.length, 2);

  host.stop();
});

test("host handles app-server initialize failure without an unhandled rejection", async () => {
  FakeWebSocket.instances = [];
  const stateDir = makeStateDir();
  const fake = makeFakeSpawn();
  const errors = [];
  const unhandledRejections = [];
  const unhandledHandler = (reason) => {
    unhandledRejections.push(reason);
  };
  process.on("unhandledRejection", unhandledHandler);

  let host;
  try {
    host = startHost({
      relayUrl: "ws://relay.example/relay",
      stateDir,
      printQr: false,
      WebSocketImpl: FakeWebSocket,
      spawnImpl: fake.spawnImpl,
      appServerMode: "private",
      logger: {
        log() {},
        error(message) {
          errors.push(String(message));
        },
      },
    });

    const initializeRequest = fake.children[0].stdin.writes
      .map((line) => JSON.parse(line))
      .find((message) => message.method === "initialize");
    fake.children[0].stdout.emit("data", Buffer.from(JSON.stringify({
      jsonrpc: "2.0",
      id: initializeRequest.id,
      error: { code: -32000, message: "initialize failed" },
    })));
    await waitForTimers();

    assert.deepEqual(unhandledRejections, []);
    assert.match(errors.join("\n"), /failed to initialize codex worker control/);
  } finally {
    process.off("unhandledRejection", unhandledHandler);
    host?.stop();
  }
});

test("host defaults to shared app-server mode with deterministic unix socket", () => {
  const options = buildHostCodexTransportOptions({
    appServerMode: "shared",
    stateDir: "/home/user/.config/codex-android-remote",
    codexCommand: "codex",
  });

  assert.equal(options.mode, "shared");
  assert.equal(options.socketPath, "/home/user/.config/codex-android-remote/app-server.sock");
  assert.equal(buildDesktopRemoteCommand(options.socketPath), "codex --remote unix:///home/user/.config/codex-android-remote/app-server.sock");
});

test("host shared app-server mode uses default state dir when omitted", () => {
  const options = buildHostCodexTransportOptions({
    appServerMode: "shared",
    codexCommand: "codex",
  });

  assert.equal(options.mode, "shared");
  assert.equal(options.socketPath.includes("undefined"), false);
  assert.match(options.socketPath, /codex-android-remote\/app-server\.sock$/);
});

test("host keeps private app-server mode as explicit fallback", () => {
  const options = buildHostCodexTransportOptions({
    appServerMode: "private",
    stateDir: "/tmp/state",
    codexCommand: "codex",
  });

  assert.deepEqual(options, {
    mode: "private",
    command: "codex",
    args: ["app-server"],
  });
});

test("host builds app-server exit notification for Android recovery", () => {
  assert.deepEqual(buildHostAppServerExitedNotification({ code: 1, signal: "" }), {
    jsonrpc: "2.0",
    method: "host/appServerExited",
    params: {
      code: 1,
      signal: "",
      message: "codex app-server exited code=1 signal=",
    },
  });
});

test("host can override relay DNS lookup without changing TLS hostname", () => {
  const options = buildRelayWebSocketOptions({
    relayBaseUrl: "wss://relay.example/relay",
    headers: { "x-role": "host" },
    relayHostOverrides: "relay.example=203.0.113.10",
  });

  assert.equal(options.headers["x-role"], "host");
  assert.equal(typeof options.lookup, "function");
  options.lookup("relay.example", {}, (error, address, family) => {
    assert.ifError(error);
    assert.equal(address, "203.0.113.10");
    assert.equal(family, 4);
  });
  options.lookup("relay.example", { all: true }, (error, addresses) => {
    assert.ifError(error);
    assert.deepEqual(addresses, [{ address: "203.0.113.10", family: 4 }]);
  });
});

test("host reconnects relay without changing session id or stopping codex", async () => {
  FakeWebSocket.instances = [];
  const stateDir = makeStateDir();
  const fake = makeFakeSpawn();

  const host = startHost({
    relayUrl: "ws://relay.example/relay",
    stateDir,
    printQr: false,
    WebSocketImpl: FakeWebSocket,
    spawnImpl: fake.spawnImpl,
    relayReconnectDelayMs: 0,
    logger: { log() {}, error() {} },
  });

  assert.equal(FakeWebSocket.instances.length, 1);
  const controlProxy = await startSharedProxy(fake);
  completeInitialize(controlProxy);
  const firstSocket = FakeWebSocket.instances[0];
  const originalSetTimeout = global.setTimeout;
  const unrefCalls = [];
  global.setTimeout = (callback, delay, ...args) => {
    const timer = originalSetTimeout(callback, delay, ...args);
    const originalUnref = timer.unref?.bind(timer);
    timer.unref = () => {
      unrefCalls.push(delay);
      return originalUnref?.();
    };
    return timer;
  };

  try {
    firstSocket.close(1006, "network lost");
    await waitForTimers();
  } finally {
    global.setTimeout = originalSetTimeout;
  }

  assert.equal(FakeWebSocket.instances.length, 2);
  assert.equal(host.sessionId, decodeURIComponent(new URL(FakeWebSocket.instances[1].url).pathname.split("/").pop()));
  assert.equal(fake.children[0].killSignal, null);
  assert.equal(controlProxy.killSignal, null);
  assert.deepEqual(unrefCalls.filter((delay) => delay === 0), []);

  host.stop();
});

test("host heartbeat does not ping relay sockets before they are open", () => {
  class ConnectingWebSocket extends FakeWebSocket {
    constructor(url, options) {
      super(url, options);
      this.readyState = ConnectingWebSocket.CONNECTING;
    }

    ping() {
      throw new Error("WebSocket is not open: readyState 0 (CONNECTING)");
    }
  }
  ConnectingWebSocket.CONNECTING = 0;
  ConnectingWebSocket.OPEN = 1;
  ConnectingWebSocket.CLOSED = 3;
  ConnectingWebSocket.instances = FakeWebSocket.instances;

  FakeWebSocket.instances = [];
  ConnectingWebSocket.instances = FakeWebSocket.instances;
  const stateDir = makeStateDir();
  const fake = makeFakeSpawn();
  const originalSetInterval = global.setInterval;
  const originalClearInterval = global.clearInterval;
  let heartbeatCallback = null;
  let cleared = false;

  global.setInterval = (callback) => {
    heartbeatCallback = callback;
    return {
      unref() {},
    };
  };
  global.clearInterval = () => {
    cleared = true;
  };

  let host;
  try {
    host = startHost({
      relayUrl: "ws://relay.example/relay",
      stateDir,
      printQr: false,
      WebSocketImpl: ConnectingWebSocket,
      spawnImpl: fake.spawnImpl,
      logger: { log() {}, error() {} },
    });

    assert.doesNotThrow(() => heartbeatCallback());
  } finally {
    global.setInterval = originalSetInterval;
    global.clearInterval = originalClearInterval;
    host?.stop();
  }

  assert.equal(cleared, false);
});

test("host restarts codex app-server transport after unexpected exit", async () => {
  FakeWebSocket.instances = [];
  const stateDir = makeStateDir();
  const fake = makeFakeSpawn();

  const host = startHost({
    relayUrl: "ws://relay.example/relay",
    stateDir,
    printQr: false,
    WebSocketImpl: FakeWebSocket,
    spawnImpl: fake.spawnImpl,
    codexRestartDelayMs: 0,
    logger: { log() {}, error() {} },
  });

  assert.equal(fake.calls.length, 1);
  const firstProxy = await startSharedProxy(fake);
  completeInitialize(firstProxy);
  assert.equal(fake.calls.length, 2);
  const originalSetTimeout = global.setTimeout;
  const unrefCalls = [];
  global.setTimeout = (callback, delay, ...args) => {
    const timer = originalSetTimeout(callback, delay, ...args);
    const originalUnref = timer.unref?.bind(timer);
    timer.unref = () => {
      unrefCalls.push(delay);
      return originalUnref?.();
    };
    return timer;
  };

  try {
    firstProxy.emit("exit", 1, null);
    await waitForTimers();
    await startSharedProxy(fake, 2);
    await waitForTimers();
  } finally {
    global.setTimeout = originalSetTimeout;
  }

  assert.equal(fake.calls.length, 4);
  assert.equal(fake.children[0].killSignal, "SIGTERM");
  assert.deepEqual(unrefCalls.filter((delay) => delay === 0), []);

  host.stop();
});
