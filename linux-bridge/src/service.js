"use strict";

const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

const SERVICE_NAME = "codex-android-remote.service";

function buildSystemdUserService({ nodePath, cliPath, relayUrl, pathEnv = process.env.PATH || "" }) {
  const command = [
    escapeSystemdArg(nodePath),
    escapeSystemdArg(cliPath),
    "host",
    "start",
    "--no-qr",
    "--relay-url",
    escapeSystemdArg(relayUrl),
  ].join(" ");
  return `[Unit]
Description=CodeRoam bridge
After=network-online.target

[Service]
Type=simple
Environment=CODEX_REMOTE_BRIDGE_RECONNECT=1
Environment=${escapeSystemdArg(`PATH=${buildServicePath({ nodePath, pathEnv })}`)}
ExecStart=${command}
Restart=always
RestartSec=5
StartLimitIntervalSec=300
StartLimitBurst=20

[Install]
WantedBy=default.target
`;
}

function installSystemdUserService({
  relayUrl,
  nodePath = process.execPath,
  cliPath = path.resolve(__dirname, "../bin/codex-remote.js"),
  systemdDir = path.join(os.homedir(), ".config", "systemd", "user"),
} = {}) {
  if (!relayUrl) {
    throw new Error("relayUrl is required");
  }
  fs.mkdirSync(systemdDir, { recursive: true });
  const unitPath = path.join(systemdDir, SERVICE_NAME);
  fs.writeFileSync(unitPath, buildSystemdUserService({ nodePath, cliPath, relayUrl }), "utf8");
  return unitPath;
}

function buildServicePath({ nodePath, pathEnv }) {
  const nodeDir = path.dirname(nodePath);
  const entries = String(pathEnv || "")
    .split(path.delimiter)
    .filter(Boolean)
    .filter((entry) => entry !== nodeDir);
  return [nodeDir, ...entries].join(path.delimiter);
}

function escapeSystemdArg(value) {
  const raw = String(value || "");
  if (!raw) {
    return "";
  }
  if (/^[A-Za-z0-9_./:@%+=,-]+$/.test(raw)) {
    return raw;
  }
  return `"${raw.replace(/(["\\$`])/g, "\\$1")}"`;
}

module.exports = {
  SERVICE_NAME,
  buildServicePath,
  buildSystemdUserService,
  installSystemdUserService,
};
