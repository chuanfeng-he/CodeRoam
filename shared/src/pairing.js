"use strict";

const net = require("node:net");

const DEFAULT_PAIRING_TTL_MS = null;
const PERMANENT_PAIRING_EXPIRES_AT = Number.MAX_SAFE_INTEGER;
const PAIRING_VERSION = 1;

function normalizeRelayBaseUrl(relayUrl) {
  const normalized = normalizeString(relayUrl);
  if (!normalized) {
    throw new Error("relayUrl is required");
  }
  return normalized.replace(/\/+$/, "");
}

function createPairingPayload({
  relayUrl,
  sessionId,
  hostDeviceId,
  hostIdentityPublicKey,
  displayName = "",
  relayHostOverrides = {},
  now = Date.now(),
  ttlMs = DEFAULT_PAIRING_TTL_MS,
}) {
  const normalizedRelayHostOverrides = normalizeRelayHostOverrides(relayHostOverrides);
  const payload = {
    v: PAIRING_VERSION,
    relay: normalizeRelayBaseUrl(relayUrl),
    sessionId: requiredString(sessionId, "sessionId"),
    hostDeviceId: requiredString(hostDeviceId, "hostDeviceId"),
    hostIdentityPublicKey: requiredString(hostIdentityPublicKey, "hostIdentityPublicKey"),
    expiresAt: ttlMs == null ? PERMANENT_PAIRING_EXPIRES_AT : now + ttlMs,
    displayName: normalizeString(displayName),
  };
  if (Object.keys(normalizedRelayHostOverrides).length > 0) {
    payload.relayHostOverrides = normalizedRelayHostOverrides;
  }
  return payload;
}

function parsePairingPayload(raw) {
  if (typeof raw !== "string") {
    throw new Error("Pairing payload must be a JSON string");
  }
  const parsed = JSON.parse(raw);
  const result = validatePairingPayload(parsed, { now: 0 });
  if (!result.ok) {
    throw new Error(result.error);
  }
  return parsed;
}

function validatePairingPayload(payload, { now = Date.now() } = {}) {
  if (!payload || typeof payload !== "object" || Array.isArray(payload)) {
    return invalid("pairing payload must be an object");
  }
  if (payload.v !== PAIRING_VERSION) {
    return invalid("unsupported pairing payload version");
  }

  for (const field of ["relay", "sessionId", "hostDeviceId", "hostIdentityPublicKey"]) {
    if (!normalizeString(payload[field])) {
      return invalid(`${field} is required`);
    }
  }

  if (!Number.isFinite(payload.expiresAt)) {
    return invalid("expiresAt is required");
  }
  if (payload.expiresAt <= now) {
    return invalid("pairing payload is expired");
  }
  if (payload.relayHostOverrides !== undefined) {
    let overrides;
    try {
      overrides = normalizeRelayHostOverrides(payload.relayHostOverrides);
    } catch (error) {
      return invalid(error.message);
    }
    if (Object.keys(overrides).length === 0) {
      return invalid("relayHostOverrides must contain host=ip entries");
    }
  }
  return { ok: true, error: "" };
}

function invalid(error) {
  return { ok: false, error };
}

function requiredString(value, field) {
  const normalized = normalizeString(value);
  if (!normalized) {
    throw new Error(`${field} is required`);
  }
  return normalized;
}

function normalizeString(value) {
  return typeof value === "string" ? value.trim() : "";
}

function normalizeRelayHostOverrides(value) {
  if (!value) {
    return {};
  }
  const entries = value instanceof Map
    ? value.entries()
    : typeof value === "object"
      ? Object.entries(value)
      : String(value)
        .split(/[,\s]+/)
        .map((entry) => entry.trim())
        .filter(Boolean)
        .map((entry) => {
          const separator = entry.indexOf("=");
          return separator === -1 ? ["", ""] : [entry.slice(0, separator), entry.slice(separator + 1)];
        });
  const overrides = {};
  for (const [rawHost, rawAddress] of entries) {
    const host = normalizeString(rawHost).toLowerCase();
    const address = normalizeString(rawAddress);
    if (!host || !address) {
      continue;
    }
    if (net.isIP(address) === 0) {
      throw new Error(`relay host override for ${host} must be an IP address`);
    }
    overrides[host] = address;
  }
  return overrides;
}

module.exports = {
  DEFAULT_PAIRING_TTL_MS,
  PERMANENT_PAIRING_EXPIRES_AT,
  PAIRING_VERSION,
  createPairingPayload,
  normalizeRelayBaseUrl,
  normalizeRelayHostOverrides,
  parsePairingPayload,
  validatePairingPayload,
};
