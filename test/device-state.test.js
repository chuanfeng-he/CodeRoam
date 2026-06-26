const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");

const {
  loadOrCreateDeviceState,
  readBridgeConfig,
  writeBridgeConfig,
} = require("../linux-bridge/src/device-state");

test("device state persists a stable host identity", () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "codex-remote-state-"));
  const first = loadOrCreateDeviceState({ stateDir: dir });
  const second = loadOrCreateDeviceState({ stateDir: dir });

  assert.equal(first.hostDeviceId, second.hostDeviceId);
  assert.equal(first.hostIdentity.publicKey, second.hostIdentity.publicKey);
  assert.match(fs.statSync(path.join(dir, "device-state.json")).mode.toString(8), /600$/);
});

test("bridge config round trips relay url", () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "codex-remote-config-"));
  writeBridgeConfig({ stateDir: dir, config: { relayUrl: "wss://relay.example/relay" } });

  assert.deepEqual(readBridgeConfig({ stateDir: dir }), {
    relayUrl: "wss://relay.example/relay",
  });
});
