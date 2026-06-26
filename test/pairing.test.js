const assert = require("node:assert/strict");
const test = require("node:test");

const {
  createPairingPayload,
  normalizeRelayBaseUrl,
  parsePairingPayload,
  validatePairingPayload,
} = require("../shared/src/pairing");

test("pairing payload defaults to a permanent qr and normalizes relay url", () => {
  const payload = createPairingPayload({
    relayUrl: "wss://relay.example/relay///",
    sessionId: "session-1",
    hostDeviceId: "host-1",
    hostIdentityPublicKey: "public-key",
    relayHostOverrides: "relay.example=203.0.113.10",
    displayName: "linux-box",
    now: 1_000,
  });

  assert.equal(payload.v, 1);
  assert.equal(payload.relay, "wss://relay.example/relay");
  assert.equal(payload.sessionId, "session-1");
  assert.equal(payload.expiresAt, Number.MAX_SAFE_INTEGER);
  assert.deepEqual(payload.relayHostOverrides, { "relay.example": "203.0.113.10" });
  assert.equal(normalizeRelayBaseUrl("wss://relay.example/relay/"), "wss://relay.example/relay");

  const encoded = JSON.stringify(payload);
  const parsed = parsePairingPayload(encoded);
  assert.deepEqual(parsed, payload);
  assert.equal(validatePairingPayload(parsed, { now: 4_102_444_800_000 }).ok, true);
});

test("invalid relay host override is rejected", () => {
  assert.throws(
    () => createPairingPayload({
      relayUrl: "wss://relay.example/relay",
      sessionId: "session-1",
      hostDeviceId: "host-1",
      hostIdentityPublicKey: "public-key",
      relayHostOverrides: { "relay.example": "not-an-ip" },
    }),
    /must be an IP address/
  );

  const invalid = validatePairingPayload({
    v: 1,
    relay: "wss://relay.example/relay",
    sessionId: "session-1",
    hostDeviceId: "host-1",
    hostIdentityPublicKey: "public-key",
    relayHostOverrides: { "relay.example": "not-an-ip" },
    expiresAt: 2_000,
  }, { now: 1_000 });

  assert.equal(invalid.ok, false);
  assert.match(invalid.error, /must be an IP address/);
});

test("explicit pairing ttl can still expire for constrained deployments", () => {
  const payload = createPairingPayload({
    relayUrl: "wss://relay.example/relay",
    sessionId: "session-1",
    hostDeviceId: "host-1",
    hostIdentityPublicKey: "public-key",
    now: 1_000,
    ttlMs: 10,
  });

  assert.equal(payload.expiresAt, 1_010);
  assert.equal(validatePairingPayload(payload, { now: 2_000 }).ok, false);
});

test("incomplete pairing payloads are rejected", () => {
  const missing = validatePairingPayload({ v: 1, relay: "wss://relay.example/relay" }, { now: 1 });
  assert.equal(missing.ok, false);
  assert.match(missing.error, /sessionId/);
});
