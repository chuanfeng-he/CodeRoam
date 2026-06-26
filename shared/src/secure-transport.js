"use strict";

const {
  createCipheriv,
  createDecipheriv,
  createHash,
  createPrivateKey,
  createPublicKey,
  diffieHellman,
  generateKeyPairSync,
  hkdfSync,
  randomBytes,
  sign,
  verify,
} = require("node:crypto");

const PROTOCOL_VERSION = 1;
const HANDSHAKE_TAG = "codex-android-remote-e2ee-v1";

function createIdentity() {
  const { publicKey, privateKey } = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
  return {
    publicKey: publicKey.export({ type: "spki", format: "pem" }),
    privateKey: privateKey.export({ type: "pkcs8", format: "pem" }),
  };
}

function createHostSecureTransport({
  sessionId,
  hostDeviceId,
  hostIdentity,
  trustedAndroidKeys = new Map(),
}) {
  const normalizedSessionId = required(sessionId, "sessionId");
  const normalizedHostDeviceId = required(hostDeviceId, "hostDeviceId");
  const hostPublicKey = required(hostIdentity?.publicKey, "hostIdentity.publicKey").trim();
  const hostPrivateKey = required(hostIdentity?.privateKey, "hostIdentity.privateKey");
  let pending = null;
  let session = null;
  let nextOutboundCounter = 0;
  let lastInboundCounter = -1;

  return {
    handleClientHello(message) {
      assertKind(message, "clientHello");
      assertEqual(message.protocolVersion, PROTOCOL_VERSION, "protocolVersion");
      assertEqual(message.sessionId, normalizedSessionId, "sessionId");
      const androidDeviceId = required(message.androidDeviceId, "androidDeviceId");
      const androidIdentityPublicKey = required(message.androidIdentityPublicKey, "androidIdentityPublicKey");
      const androidEphemeralPublicKey = required(message.androidEphemeralPublicKey, "androidEphemeralPublicKey");
      const clientNonce = required(message.clientNonce, "clientNonce");

      const trustedKey = trustedAndroidKeys.get(androidDeviceId);
      if (trustedKey && trustedKey !== androidIdentityPublicKey) {
        throw new Error("trusted Android identity changed");
      }

      const ephemeral = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
      const hostEphemeralPublicKey = ephemeral.publicKey.export({ type: "spki", format: "pem" });
      const serverNonce = randomBytes(32).toString("base64");
      const keyEpoch = 1;
      const transcript = buildTranscript({
        sessionId: normalizedSessionId,
        protocolVersion: PROTOCOL_VERSION,
        keyEpoch,
        hostDeviceId: normalizedHostDeviceId,
        androidDeviceId,
        hostIdentityPublicKey: hostPublicKey,
        androidIdentityPublicKey,
        hostEphemeralPublicKey,
        androidEphemeralPublicKey,
        clientNonce,
        serverNonce,
      });
      const hostSignature = signTranscript(hostPrivateKey, transcript);
      pending = {
        androidDeviceId,
        androidIdentityPublicKey,
        androidEphemeralPublicKey,
        hostEphemeralPrivateKey: ephemeral.privateKey.export({ type: "pkcs8", format: "pem" }),
        hostEphemeralPublicKey,
        keyEpoch,
        transcript,
      };
      session = null;
      return {
        kind: "serverHello",
        protocolVersion: PROTOCOL_VERSION,
        sessionId: normalizedSessionId,
        keyEpoch,
        hostDeviceId: normalizedHostDeviceId,
        hostIdentityPublicKey: hostPublicKey,
        hostEphemeralPublicKey,
        serverNonce,
        hostSignature,
      };
    },

    handleClientAuth(message) {
      assertKind(message, "clientAuth");
      if (!pending) {
        throw new Error("clientAuth received before clientHello");
      }
      assertEqual(message.sessionId, normalizedSessionId, "sessionId");
      assertEqual(message.androidDeviceId, pending.androidDeviceId, "androidDeviceId");
      assertEqual(message.keyEpoch, pending.keyEpoch, "keyEpoch");

      const clientAuthTranscript = Buffer.concat([
        pending.transcript,
        Buffer.from("|clientAuth", "utf8"),
      ]);
      const androidVerified = verifyTranscript(
        pending.androidIdentityPublicKey,
        clientAuthTranscript,
        required(message.androidSignature, "androidSignature")
      );
      if (!androidVerified) {
        throw new Error("invalid Android signature");
      }

      trustedAndroidKeys.set(pending.androidDeviceId, pending.androidIdentityPublicKey);
      session = deriveSession({
        sessionId: normalizedSessionId,
        keyEpoch: pending.keyEpoch,
        privateKeyPem: pending.hostEphemeralPrivateKey,
        publicKeyPem: pending.androidEphemeralPublicKey,
        transcript: pending.transcript,
        outboundInfo: "hostToAndroid",
        inboundInfo: "androidToHost",
      });
      pending = null;
      return {
        kind: "secureReady",
        protocolVersion: PROTOCOL_VERSION,
        sessionId: normalizedSessionId,
        keyEpoch: session.keyEpoch,
        hostDeviceId: normalizedHostDeviceId,
      };
    },

    isReady() {
      return Boolean(session);
    },

    encryptApplicationMessage(payloadText) {
      if (!session) {
        throw new Error("secure session is not ready");
      }
      const result = encryptEnvelope({
        session,
        sender: "host",
        counter: nextOutboundCounter,
        key: session.outboundKey,
        payloadText,
      });
      nextOutboundCounter += 1;
      return result;
    },

    decryptApplicationMessage(envelope) {
      if (!session) {
        throw new Error("secure session is not ready");
      }
      const result = decryptEnvelope({
        envelope,
        expectedSender: "android",
        lastInboundCounter,
        key: session.inboundKey,
      });
      lastInboundCounter = result.counter;
      return result.payloadText;
    },
  };
}

function createAndroidSecureClient({
  sessionId,
  androidDeviceId,
  androidIdentity,
  expectedHostDeviceId,
  expectedHostIdentityPublicKey,
}) {
  const normalizedSessionId = required(sessionId, "sessionId");
  const normalizedAndroidDeviceId = required(androidDeviceId, "androidDeviceId");
  const androidPublicKey = required(androidIdentity?.publicKey, "androidIdentity.publicKey");
  const androidPrivateKey = required(androidIdentity?.privateKey, "androidIdentity.privateKey");
  const expectedHostId = required(expectedHostDeviceId, "expectedHostDeviceId");
  const expectedHostKey = required(expectedHostIdentityPublicKey, "expectedHostIdentityPublicKey").trim();
  const ephemeral = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
  const androidEphemeralPrivateKey = ephemeral.privateKey.export({ type: "pkcs8", format: "pem" });
  const androidEphemeralPublicKey = ephemeral.publicKey.export({ type: "spki", format: "pem" });
  const clientNonce = randomBytes(32).toString("base64");
  let pending = null;
  let session = null;
  let nextOutboundCounter = 0;
  let lastInboundCounter = -1;

  return {
    createClientHello() {
      pending = { androidEphemeralPrivateKey, androidEphemeralPublicKey, clientNonce };
      return {
        kind: "clientHello",
        protocolVersion: PROTOCOL_VERSION,
        sessionId: normalizedSessionId,
        androidDeviceId: normalizedAndroidDeviceId,
        androidIdentityPublicKey: androidPublicKey,
        androidEphemeralPublicKey,
        clientNonce,
      };
    },

    handleServerHello(message) {
      assertKind(message, "serverHello");
      if (!pending) {
        throw new Error("serverHello received before clientHello");
      }
      assertEqual(message.protocolVersion, PROTOCOL_VERSION, "protocolVersion");
      assertEqual(message.sessionId, normalizedSessionId, "sessionId");
      assertEqual(message.hostDeviceId, expectedHostId, "hostDeviceId");
      if (message.hostIdentityPublicKey !== expectedHostKey) {
        throw new Error("host identity public key mismatch");
      }

      const transcript = buildTranscript({
        sessionId: normalizedSessionId,
        protocolVersion: PROTOCOL_VERSION,
        keyEpoch: message.keyEpoch,
        hostDeviceId: message.hostDeviceId,
        androidDeviceId: normalizedAndroidDeviceId,
        hostIdentityPublicKey: message.hostIdentityPublicKey,
        androidIdentityPublicKey: androidPublicKey,
        hostEphemeralPublicKey: required(message.hostEphemeralPublicKey, "hostEphemeralPublicKey"),
        androidEphemeralPublicKey: pending.androidEphemeralPublicKey,
        clientNonce: pending.clientNonce,
        serverNonce: required(message.serverNonce, "serverNonce"),
      });
      const validHostSignature = verifyTranscript(
        message.hostIdentityPublicKey,
        transcript,
        required(message.hostSignature, "hostSignature")
      );
      if (!validHostSignature) {
        throw new Error("invalid host signature");
      }

      const androidSignature = signTranscript(
        androidPrivateKey,
        Buffer.concat([transcript, Buffer.from("|clientAuth", "utf8")])
      );
      session = deriveSession({
        sessionId: normalizedSessionId,
        keyEpoch: message.keyEpoch,
        privateKeyPem: pending.androidEphemeralPrivateKey,
        publicKeyPem: message.hostEphemeralPublicKey,
        transcript,
        outboundInfo: "androidToHost",
        inboundInfo: "hostToAndroid",
      });
      return {
        kind: "clientAuth",
        sessionId: normalizedSessionId,
        androidDeviceId: normalizedAndroidDeviceId,
        keyEpoch: message.keyEpoch,
        androidSignature,
      };
    },

    handleSecureReady(message) {
      assertKind(message, "secureReady");
      if (!session) {
        throw new Error("secureReady received before serverHello");
      }
      assertEqual(message.sessionId, normalizedSessionId, "sessionId");
      assertEqual(message.keyEpoch, session.keyEpoch, "keyEpoch");
      assertEqual(message.hostDeviceId, expectedHostId, "hostDeviceId");
    },

    isReady() {
      return Boolean(session);
    },

    encryptApplicationMessage(payloadText) {
      if (!session) {
        throw new Error("secure session is not ready");
      }
      const result = encryptEnvelope({
        session,
        sender: "android",
        counter: nextOutboundCounter,
        key: session.outboundKey,
        payloadText,
      });
      nextOutboundCounter += 1;
      return result;
    },

    decryptApplicationMessage(envelope) {
      if (!session) {
        throw new Error("secure session is not ready");
      }
      const result = decryptEnvelope({
        envelope,
        expectedSender: "host",
        lastInboundCounter,
        key: session.inboundKey,
      });
      lastInboundCounter = result.counter;
      return result.payloadText;
    },
  };
}

function buildTranscript(fields) {
  const ordered = {
    tag: HANDSHAKE_TAG,
    sessionId: fields.sessionId,
    protocolVersion: fields.protocolVersion,
    keyEpoch: fields.keyEpoch,
    hostDeviceId: fields.hostDeviceId,
    androidDeviceId: fields.androidDeviceId,
    hostIdentityPublicKey: fields.hostIdentityPublicKey,
    androidIdentityPublicKey: fields.androidIdentityPublicKey,
    hostEphemeralPublicKey: fields.hostEphemeralPublicKey,
    androidEphemeralPublicKey: fields.androidEphemeralPublicKey,
    clientNonce: fields.clientNonce,
    serverNonce: fields.serverNonce,
  };
  return Buffer.from(JSON.stringify(ordered), "utf8");
}

function deriveSession({
  sessionId,
  keyEpoch,
  privateKeyPem,
  publicKeyPem,
  transcript,
  outboundInfo,
  inboundInfo,
}) {
  const sharedSecret = diffieHellman({
    privateKey: createPrivateKey(privateKeyPem),
    publicKey: createPublicKey(publicKeyPem),
  });
  const salt = createHash("sha256").update(transcript).digest();
  const infoPrefix = `${HANDSHAKE_TAG}|${sessionId}|${keyEpoch}`;
  return {
    sessionId,
    keyEpoch,
    outboundKey: Buffer.from(hkdfSync("sha256", sharedSecret, salt, Buffer.from(`${infoPrefix}|${outboundInfo}`), 32)),
    inboundKey: Buffer.from(hkdfSync("sha256", sharedSecret, salt, Buffer.from(`${infoPrefix}|${inboundInfo}`), 32)),
  };
}

function encryptEnvelope({ session, sender, counter, key, payloadText }) {
  const nonce = nonceFor({ session, sender, counter });
  const cipher = createCipheriv("aes-256-gcm", key, nonce);
  const ciphertext = Buffer.concat([
    cipher.update(String(payloadText), "utf8"),
    cipher.final(),
  ]);
  const tag = cipher.getAuthTag();
  return {
    kind: "encryptedEnvelope",
    v: PROTOCOL_VERSION,
    sessionId: session.sessionId,
    keyEpoch: session.keyEpoch,
    sender,
    counter,
    ciphertext: ciphertext.toString("base64"),
    tag: tag.toString("base64"),
  };
}

function decryptEnvelope({ envelope, expectedSender, lastInboundCounter, key }) {
  assertKind(envelope, "encryptedEnvelope");
  assertEqual(envelope.sender, expectedSender, "sender");
  const counter = Number(envelope.counter);
  if (!Number.isInteger(counter) || counter <= lastInboundCounter) {
    throw new Error("replayed or invalid encrypted envelope counter");
  }
  const nonce = nonceFor({ session: envelope, sender: expectedSender, counter });
  const decipher = createDecipheriv("aes-256-gcm", key, nonce);
  decipher.setAuthTag(Buffer.from(required(envelope.tag, "tag"), "base64"));
  const plaintext = Buffer.concat([
    decipher.update(Buffer.from(required(envelope.ciphertext, "ciphertext"), "base64")),
    decipher.final(),
  ]);
  return { counter, payloadText: plaintext.toString("utf8") };
}

function nonceFor({ session, sender, counter }) {
  return createHash("sha256")
    .update(`${HANDSHAKE_TAG}|${session.sessionId}|${session.keyEpoch}|${sender}|${counter}`)
    .digest()
    .subarray(0, 12);
}

function signTranscript(privateKeyPem, transcript) {
  return sign("sha256", transcript, createPrivateKey(privateKeyPem)).toString("base64");
}

function verifyTranscript(publicKeyPem, transcript, signature) {
  return verify("sha256", transcript, createPublicKey(publicKeyPem), Buffer.from(signature, "base64"));
}

function assertKind(message, kind) {
  if (!message || message.kind !== kind) {
    throw new Error(`expected ${kind}`);
  }
}

function assertEqual(actual, expected, field) {
  if (actual !== expected) {
    throw new Error(`${field} mismatch`);
  }
}

function required(value, field) {
  if (typeof value !== "string" || !value.trim()) {
    throw new Error(`${field} is required`);
  }
  return value;
}

module.exports = {
  HANDSHAKE_TAG,
  PROTOCOL_VERSION,
  createAndroidSecureClient,
  createHostSecureTransport,
  createIdentity,
};
