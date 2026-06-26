const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const repoRoot = path.resolve(__dirname, "..");
const quickstartPath = path.join(repoRoot, "scripts", "quickstart.sh");
const bootstrapPath = path.join(repoRoot, "scripts", "bootstrap-ubuntu.sh");

test("quickstart script is exposed through npm and has a strict shell prologue", () => {
  const pkg = JSON.parse(fs.readFileSync(path.join(repoRoot, "package.json"), "utf8"));
  const script = fs.readFileSync(quickstartPath, "utf8");

  assert.equal(pkg.scripts.quickstart, "bash scripts/quickstart.sh");
  assert.match(script, /^#!\/usr\/bin\/env bash\nset -euo pipefail\n/);
});

test("ubuntu bootstrap installs node before delegating to quickstart", () => {
  const script = fs.readFileSync(bootstrapPath, "utf8");

  assert.match(script, /^#!\/usr\/bin\/env bash\nset -euo pipefail\n/);
  assert.match(script, /NODE_MAJOR="\$\{CODEX_REMOTE_NODE_MAJOR:-22\}"/);
  assert.match(script, /deb\.nodesource\.com/);
  assert.match(script, /has_node_18/);
  assert.match(script, /--start-local-relay/);
  assert.match(script, /quickstart_args=\(--relay-url "\$relay_url"\)/);
  assert.match(script, /npm run quickstart -- "\$\{quickstart_args\[@\]\}"/);
  assert.match(script, /--skip-node-install/);
});

test("quickstart script configures relay and starts the bridge without embedding secrets", () => {
  const script = fs.readFileSync(quickstartPath, "utf8");

  assert.match(script, /CODEX_REMOTE_RELAY/);
  assert.match(script, /host configure --relay-url/);
  assert.match(script, /service install --relay-url/);
  assert.match(script, /systemctl --user show-environment/);
  assert.match(script, /systemctl --user daemon-reload/);
  assert.match(script, /systemctl --user enable --now codex-android-remote\.service/);
  assert.match(script, /--start-local-relay/);
  assert.match(script, /node relay\/src\/server\.js/);
  assert.match(script, /relay_pid="\$state_dir\/relay\.pid"/);
  assert.match(script, /relay_log="\$state_dir\/relay\.log"/);
  assert.match(script, /nohup/);
  assert.match(script, /state_dir=.*codex-android-remote/);
  assert.match(script, /bridge_pid="\$state_dir\/bridge\.pid"/);
  assert.match(script, /bridge_log="\$state_dir\/bridge\.log"/);
  const forbidden = [
    ["OPENAI", "API", "KEY"].join("_"),
    ["ANTHROPIC", "API", "KEY"].join("_"),
    ["PASS", "WORD"].join(""),
    ["SEC", "RET"].join(""),
    ["TOK", "EN"].join(""),
  ];
  for (const value of forbidden) {
    assert.equal(script.includes(value), false);
  }
});
