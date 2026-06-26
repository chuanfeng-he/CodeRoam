const assert = require("node:assert/strict");
const test = require("node:test");

const { buildSystemdUserService } = require("../linux-bridge/src/service");

test("systemd user service starts host bridge with configured relay", () => {
  const unit = buildSystemdUserService({
    nodePath: "/usr/bin/node",
    cliPath: "/opt/codex-android-remote/linux-bridge/bin/codex-remote.js",
    relayUrl: "wss://relay.example/relay",
  });

  assert.match(unit, /Description=CodeRoam bridge/);
  assert.match(unit, /ExecStart=\/usr\/bin\/node \/opt\/codex-android-remote\/linux-bridge\/bin\/codex-remote\.js host start --no-qr --relay-url wss:\/\/relay\.example\/relay/);
  assert.match(unit, /Restart=always/);
  assert.match(unit, /RestartSec=5/);
  assert.match(unit, /StartLimitIntervalSec=300/);
  assert.match(unit, /StartLimitBurst=20/);
  assert.match(unit, /Environment=CODEX_REMOTE_BRIDGE_RECONNECT=1/);
});

test("systemd user service puts node binary directory first in PATH", () => {
  const unit = buildSystemdUserService({
    nodePath: "/opt/node/bin/node",
    cliPath: "/opt/codex-android-remote/linux-bridge/bin/codex-remote.js",
    relayUrl: "wss://relay.example/relay",
    pathEnv: "/usr/local/bin:/usr/bin",
  });

  assert.match(unit, /Environment=PATH=\/opt\/node\/bin:\/usr\/local\/bin:\/usr\/bin/);
});
