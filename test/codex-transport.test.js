const assert = require("node:assert/strict");
const { EventEmitter } = require("node:events");
const test = require("node:test");

const {
  consumeJsonMessages,
  createLineJsonRpcTransport,
  createSharedCodexTransport,
  createSpawnedCodexTransport,
} = require("../linux-bridge/src/codex-transport");

test("line json-rpc transport frames requests and resolves responses", async () => {
  const writes = [];
  const fakeProcess = {
    stdin: {
      writable: true,
      write(chunk) {
        writes.push(chunk);
      },
    },
    stdout: {
      on() {},
    },
    stderr: {
      on() {},
    },
    on() {},
  };
  const transport = createLineJsonRpcTransport({ process: fakeProcess });

  const pending = transport.request("thread/list", { limit: 5 });
  const outbound = JSON.parse(writes[0]);
  assert.equal(outbound.jsonrpc, "2.0");
  assert.equal(outbound.method, "thread/list");
  assert.deepEqual(outbound.params, { limit: 5 });

  transport.handleLine(JSON.stringify({
    jsonrpc: "2.0",
    id: outbound.id,
    result: { data: [] },
  }));

  assert.deepEqual(await pending, { data: [] });
});

test("line json-rpc transport emits notifications", async () => {
  const fakeProcess = {
    stdin: { writable: true, write() {} },
    stdout: { on() {} },
    stderr: { on() {} },
    on() {},
  };
  const transport = createLineJsonRpcTransport({ process: fakeProcess });
  const events = [];
  transport.onNotification((message) => events.push(message));

  transport.handleLine(JSON.stringify({
    jsonrpc: "2.0",
    method: "turn/started",
    params: { threadId: "t1" },
  }));

  assert.equal(events.length, 1);
  assert.equal(events[0].method, "turn/started");
});

test("codex stdout parser consumes newline-free concatenated JSON messages", () => {
  const messages = [];
  let buffer = "";

  buffer = consumeJsonMessages(
    "{\"id\":1,\"result\":{\"ok\":true}}{\"method\":\"remoteControl/status/changed\",\"params\":{\"status\":\"disabled\"}}",
    (messageText) => messages.push(JSON.parse(messageText))
  );

  assert.equal(buffer, "");
  assert.deepEqual(messages, [
    { id: 1, result: { ok: true } },
    { method: "remoteControl/status/changed", params: { status: "disabled" } },
  ]);
});

test("codex stdout parser preserves partial JSON until the next chunk", () => {
  const messages = [];
  let buffer = consumeJsonMessages("{\"id\":1,\"result\"", (messageText) => messages.push(JSON.parse(messageText)));

  assert.equal(messages.length, 0);
  buffer = consumeJsonMessages(`${buffer}:{\"ok\":true}}\n`, (messageText) => messages.push(JSON.parse(messageText)));

  assert.equal(buffer, "");
  assert.deepEqual(messages, [{ id: 1, result: { ok: true } }]);
});

test("spawned codex transport initializes app-server before application requests", async () => {
  const writes = [];
  const child = new EventEmitter();
  child.stdin = {
    writable: true,
    write(chunk) {
      writes.push(chunk);
    },
  };
  child.stdout = new EventEmitter();
  child.stderr = new EventEmitter();
  const spawnCalls = [];

  const codex = createSpawnedCodexTransport({
    command: "codex",
    args: ["app-server"],
    spawnImpl(command, args) {
      spawnCalls.push({ command, args });
      return child;
    },
  });

  assert.deepEqual(spawnCalls, [{ command: "codex", args: ["app-server"] }]);
  const initializeRequest = JSON.parse(writes[0]);
  assert.equal(initializeRequest.method, "initialize");
  assert.deepEqual(initializeRequest.params.clientInfo, {
    name: "codex-android-remote",
    version: "0.1.0",
  });

  child.stdout.emit("data", Buffer.from(JSON.stringify({
    id: initializeRequest.id,
    result: {
      userAgent: "codex-test",
      codexHome: "/tmp/codex",
      platformFamily: "unix",
      platformOs: "linux",
    },
  })));

  await codex.ready;

  const pending = codex.transport.request("thread/list", { limit: 5 });
  const listRequest = JSON.parse(writes[1]);
  assert.equal(listRequest.id, initializeRequest.id + 1);
  assert.equal(listRequest.method, "thread/list");

  child.stdout.emit("data", Buffer.from(`${JSON.stringify({
    id: listRequest.id,
    result: { data: [] },
  })}\n`));

  assert.deepEqual(await pending, { data: [] });
});

test("spawned codex transport rejects ready on child spawn error", async () => {
  const child = new EventEmitter();
  child.stdin = {
    writable: true,
    write() {},
  };
  child.stdout = new EventEmitter();
  child.stderr = new EventEmitter();

  const codex = createSpawnedCodexTransport({
    command: "codex",
    args: ["app-server"],
    spawnImpl() {
      process.nextTick(() => child.emit("error", new Error("spawn codex ENOENT")));
      return child;
    },
  });

  await assert.rejects(codex.ready, /spawn codex ENOENT/);
});

test("shared codex transport starts a unix listener and proxies stdio to it", async () => {
  const children = [];
  const spawnCalls = [];

  function makeChild() {
    const child = new EventEmitter();
    child.stdin = {
      writable: true,
      writes: [],
      write(chunk) {
        this.writes.push(chunk);
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

  const codex = createSharedCodexTransport({
    command: "codex",
    socketPath: "/tmp/codex-remote/app-server.sock",
    spawnImpl(command, args) {
      spawnCalls.push({ command, args });
      return makeChild();
    },
  });

  assert.deepEqual(spawnCalls, [
    { command: "codex", args: ["app-server", "--listen", "unix:///tmp/codex-remote/app-server.sock"] },
  ]);
  assert.equal(children.length, 1, "proxy must wait until the unix listener is ready");

  children[0].stdout.emit("data", Buffer.from(
    "app-server control socket listening socket_path=/tmp/codex-remote/app-server.sock\n"
  ));
  await new Promise((resolve) => setImmediate(resolve));

  assert.deepEqual(spawnCalls, [
    { command: "codex", args: ["app-server", "--listen", "unix:///tmp/codex-remote/app-server.sock"] },
    { command: "codex", args: ["app-server", "proxy", "--sock", "/tmp/codex-remote/app-server.sock"] },
  ]);

  const initializeRequest = JSON.parse(children[1].stdin.writes[0]);
  assert.equal(initializeRequest.method, "initialize");

  children[1].stdout.emit("data", Buffer.from(JSON.stringify({
    id: initializeRequest.id,
    result: {
      userAgent: "codex-test",
      codexHome: "/tmp/codex",
      platformFamily: "unix",
      platformOs: "linux",
    },
  })));

  await codex.ready;
  codex.stop();
  assert.equal(children[0].killSignal, "SIGTERM");
  assert.equal(children[1].killSignal, "SIGTERM");
});

test("shared codex transport rejects ready on listener spawn error", async () => {
  const listener = new EventEmitter();
  listener.stdout = new EventEmitter();
  listener.stderr = new EventEmitter();
  listener.kill = () => {};

  const codex = createSharedCodexTransport({
    command: "codex",
    socketPath: "/tmp/codex-test-missing.sock",
    spawnImpl() {
      process.nextTick(() => listener.emit("error", new Error("spawn codex ENOENT")));
      return listener;
    },
    socketPollIntervalMs: 1,
  });

  await assert.rejects(codex.ready, /spawn codex ENOENT/);
});
