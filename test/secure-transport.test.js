const assert = require("node:assert/strict");
const test = require("node:test");

const {
  createAndroidSecureClient,
  createHostSecureTransport,
  createIdentity,
} = require("../shared/src/secure-transport");

test("host and android complete an authenticated encrypted handshake", () => {
  const hostIdentity = createIdentity();
  const androidIdentity = createIdentity();
  const sessionId = "session-secure-1";
  const host = createHostSecureTransport({
    sessionId,
    hostDeviceId: "host-1",
    hostIdentity,
    trustedAndroidKeys: new Map(),
  });
  const client = createAndroidSecureClient({
    sessionId,
    androidDeviceId: "android-1",
    androidIdentity,
    expectedHostDeviceId: "host-1",
    expectedHostIdentityPublicKey: hostIdentity.publicKey,
  });

  const clientHello = client.createClientHello();
  const serverHello = host.handleClientHello(clientHello);
  const clientAuth = client.handleServerHello(serverHello);
  const secureReady = host.handleClientAuth(clientAuth);
  client.handleSecureReady(secureReady);

  assert.equal(host.isReady(), true);
  assert.equal(client.isReady(), true);

  const encrypted = client.encryptApplicationMessage(JSON.stringify({
    jsonrpc: "2.0",
    id: 1,
    method: "thread/list",
    params: {},
  }));
  const plaintext = host.decryptApplicationMessage(encrypted);
  assert.match(plaintext, /thread\/list/);

  const responseEnvelope = host.encryptApplicationMessage(JSON.stringify({
    jsonrpc: "2.0",
    id: 1,
    result: { data: [] },
  }));
  const responsePlaintext = client.decryptApplicationMessage(responseEnvelope);
  assert.match(responsePlaintext, /"data":\[\]/);
});

test("host sends canonical public key without trailing PEM whitespace", () => {
  const identity = createIdentity();
  const host = createHostSecureTransport({
    sessionId: "session-trimmed-host-key",
    hostDeviceId: "host-1",
    hostIdentity: {
      ...identity,
      publicKey: `${identity.publicKey}\n`,
    },
    trustedAndroidKeys: new Map(),
  });
  const androidIdentity = createIdentity();

  const serverHello = host.handleClientHello({
    kind: "clientHello",
    protocolVersion: 1,
    sessionId: "session-trimmed-host-key",
    androidDeviceId: "android-1",
    androidIdentityPublicKey: androidIdentity.publicKey,
    androidEphemeralPublicKey: androidIdentity.publicKey,
    clientNonce: "client-nonce",
  });

  assert.equal(serverHello.hostIdentityPublicKey, identity.publicKey.trim());
});

test("android rejects a host identity mismatch", () => {
  const hostIdentity = createIdentity();
  const otherIdentity = createIdentity();
  const androidIdentity = createIdentity();
  const sessionId = "session-secure-2";
  const host = createHostSecureTransport({
    sessionId,
    hostDeviceId: "host-1",
    hostIdentity,
    trustedAndroidKeys: new Map(),
  });
  const client = createAndroidSecureClient({
    sessionId,
    androidDeviceId: "android-1",
    androidIdentity,
    expectedHostDeviceId: "host-1",
    expectedHostIdentityPublicKey: otherIdentity.publicKey,
  });

  const hello = client.createClientHello();
  const serverHello = host.handleClientHello(hello);
  assert.throws(() => client.handleServerHello(serverHello), /host identity/i);
});
