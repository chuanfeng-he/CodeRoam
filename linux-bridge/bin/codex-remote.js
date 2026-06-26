#!/usr/bin/env node
"use strict";

const path = require("node:path");
const { startHost } = require("../src/host");
const { readBridgeConfig, writeBridgeConfig } = require("../src/device-state");
const { installSystemdUserService, SERVICE_NAME } = require("../src/service");

async function main(argv = process.argv.slice(2)) {
  const [scope, command, ...rest] = argv;
  if (scope === "host" && command === "start") {
    const options = parseOptions(rest);
    const relayUrl = options["relay-url"] || readBridgeConfig().relayUrl || process.env.CODEX_REMOTE_RELAY;
    if (!relayUrl) {
      throw new Error("Missing relay URL. Pass --relay-url wss://host/relay or set CODEX_REMOTE_RELAY.");
    }
    startHost({
      relayUrl,
      printQr: options.qr !== false,
      codexCommand: options.codex || process.env.CODEX_REMOTE_CODEX_PATH || "codex",
      appServerMode: options["app-server-mode"] || process.env.CODEX_REMOTE_APP_SERVER_MODE || "shared",
      appServerSocket: options["app-server-socket"] || process.env.CODEX_REMOTE_APP_SERVER_SOCKET,
    });
    return;
  }

  if (scope === "host" && command === "status") {
    console.log(JSON.stringify(readBridgeConfig(), null, 2));
    return;
  }

  if (scope === "host" && command === "configure") {
    const options = parseOptions(rest);
    if (!options["relay-url"]) {
      throw new Error("--relay-url is required");
    }
    writeBridgeConfig({ config: { relayUrl: options["relay-url"] } });
    console.log("Saved bridge config.");
    return;
  }

  if (scope === "service" && command === "install") {
    const options = parseOptions(rest);
    const relayUrl = options["relay-url"] || readBridgeConfig().relayUrl || process.env.CODEX_REMOTE_RELAY;
    if (!relayUrl) {
      throw new Error("Missing relay URL. Pass --relay-url before installing the service.");
    }
    const unitPath = installSystemdUserService({
      relayUrl,
      cliPath: path.resolve(__filename),
    });
    console.log(`Installed ${SERVICE_NAME} at ${unitPath}`);
    console.log("Run: systemctl --user daemon-reload && systemctl --user enable --now codex-android-remote.service");
    return;
  }

  printUsage();
}

function parseOptions(args) {
  const options = { qr: true };
  for (let index = 0; index < args.length; index += 1) {
    const arg = args[index];
    if (arg === "--no-qr") {
      options.qr = false;
      continue;
    }
    if (arg.startsWith("--")) {
      const key = arg.slice(2);
      const next = args[index + 1];
      if (!next || next.startsWith("--")) {
        options[key] = true;
      } else {
        options[key] = next;
        index += 1;
      }
    }
  }
  return options;
}

function printUsage() {
  console.log(`Usage:
  codex-remote host configure --relay-url wss://example.com/relay
  codex-remote host start [--relay-url wss://example.com/relay] [--codex /path/to/codex] [--app-server-mode shared|private] [--app-server-socket /path/to/app-server.sock] [--no-qr]
  codex-remote host status
  codex-remote service install [--relay-url wss://example.com/relay]
`);
}

main().catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});
