"use strict";

const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { randomUUID } = require("node:crypto");
const { createIdentity } = require("../../shared/src/secure-transport");

const APP_DIR = "codex-android-remote";
const DEVICE_STATE_FILE = "device-state.json";
const CONFIG_FILE = "config.json";

function defaultStateDir({ env = process.env } = {}) {
  const configHome = env.XDG_CONFIG_HOME || path.join(os.homedir(), ".config");
  return path.join(configHome, APP_DIR);
}

function loadOrCreateDeviceState({ stateDir = defaultStateDir() } = {}) {
  ensureDir(stateDir);
  const statePath = path.join(stateDir, DEVICE_STATE_FILE);
  const existing = readJsonFile(statePath);
  if (existing?.hostDeviceId && existing?.hostIdentity?.publicKey && existing?.hostIdentity?.privateKey) {
    return existing;
  }

  const state = {
    hostDeviceId: `host_${randomUUID()}`,
    hostIdentity: createIdentity(),
    trustedAndroidKeys: {},
    createdAt: new Date().toISOString(),
  };
  writePrivateJson(statePath, state);
  return state;
}

function saveDeviceState({ stateDir = defaultStateDir(), state }) {
  ensureDir(stateDir);
  writePrivateJson(path.join(stateDir, DEVICE_STATE_FILE), state);
}

function readBridgeConfig({ stateDir = defaultStateDir() } = {}) {
  return readJsonFile(path.join(stateDir, CONFIG_FILE)) || {};
}

function writeBridgeConfig({ stateDir = defaultStateDir(), config }) {
  ensureDir(stateDir);
  writePrivateJson(path.join(stateDir, CONFIG_FILE), config || {});
}

function trustedAndroidKeyMapFromState(state) {
  return new Map(Object.entries(state?.trustedAndroidKeys || {}));
}

function mergeTrustedAndroidKeyMap(state, trustedAndroidKeys) {
  return {
    ...state,
    trustedAndroidKeys: Object.fromEntries(trustedAndroidKeys.entries()),
  };
}

function ensureDir(dir) {
  fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
}

function readJsonFile(filePath) {
  try {
    return JSON.parse(fs.readFileSync(filePath, "utf8"));
  } catch (error) {
    if (error.code === "ENOENT") {
      return null;
    }
    throw error;
  }
}

function writePrivateJson(filePath, value) {
  fs.writeFileSync(filePath, `${JSON.stringify(value, null, 2)}\n`, { mode: 0o600 });
  fs.chmodSync(filePath, 0o600);
}

module.exports = {
  defaultStateDir,
  loadOrCreateDeviceState,
  mergeTrustedAndroidKeyMap,
  readBridgeConfig,
  saveDeviceState,
  trustedAndroidKeyMapFromState,
  writeBridgeConfig,
};
