const assert = require("node:assert/strict");
const test = require("node:test");
const { EventEmitter } = require("node:events");

const { attachWebSocketHeartbeat, createRelayController } = require("../relay/src/relay");

class FakeSocket extends EventEmitter {
  constructor() {
    super();
    this.readyState = FakeSocket.OPEN;
    this.sent = [];
    this.closeCode = null;
    this.closeReason = "";
  }

  send(message) {
    this.sent.push(String(message));
  }

  close(code, reason) {
    this.readyState = FakeSocket.CLOSED;
    this.closeCode = code;
    this.closeReason = reason;
    this.emit("close");
  }
}

FakeSocket.OPEN = 1;
FakeSocket.CLOSED = 3;

test("relay routes encrypted messages between host and android only inside a live session", () => {
  const relay = createRelayController({ WebSocketOpenState: FakeSocket.OPEN });
  const host = new FakeSocket();
  const android = new FakeSocket();

  assert.equal(relay.attach(host, { sessionId: "s1", role: "host" }).ok, true);
  assert.equal(relay.attach(android, { sessionId: "s1", role: "android" }).ok, true);

  host.emit("message", "host->phone");
  android.emit("message", "phone->host");

  assert.deepEqual(android.sent, ["host->phone"]);
  assert.deepEqual(host.sent, ["phone->host"]);
});

test("relay rejects android connections before a host creates the session", () => {
  const relay = createRelayController({ WebSocketOpenState: FakeSocket.OPEN });
  const android = new FakeSocket();

  const result = relay.attach(android, { sessionId: "missing", role: "android" });

  assert.equal(result.ok, false);
  assert.equal(android.closeCode, 4002);
});

test("relay heartbeat pings live sockets and terminates stale sockets", () => {
  let tick;
  let cleared = false;
  const socket = new FakeSocket();
  socket.pingCount = 0;
  socket.terminated = false;
  socket.ping = () => {
    socket.pingCount += 1;
  };
  socket.terminate = () => {
    socket.terminated = true;
    socket.readyState = FakeSocket.CLOSED;
    socket.emit("close");
  };

  attachWebSocketHeartbeat(socket, {
    intervalMs: 30_000,
    setIntervalImpl(callback) {
      tick = callback;
      return "timer-1";
    },
    clearIntervalImpl(timer) {
      assert.equal(timer, "timer-1");
      cleared = true;
    },
  });

  tick();
  assert.equal(socket.pingCount, 1);
  assert.equal(socket.terminated, false);

  socket.emit("pong");
  tick();
  assert.equal(socket.pingCount, 2);
  assert.equal(socket.terminated, false);

  tick();
  assert.equal(socket.terminated, true);
  assert.equal(cleared, true);
});
