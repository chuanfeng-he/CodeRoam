# Protocol

## Pairing payload

The Linux bridge prints a JSON payload:

```json
{
  "v": 1,
  "relay": "wss://example.com/relay",
  "sessionId": "session_uuid",
  "hostDeviceId": "host_uuid",
  "hostIdentityPublicKey": "-----BEGIN PUBLIC KEY-----...",
  "expiresAt": 9007199254740991,
  "displayName": "linux-codex"
}
```

Android uses this payload to connect to:

```text
{relay}/{sessionId}?role=android
```

Linux connects with `role=host`.

By default `expiresAt` is `Number.MAX_SAFE_INTEGER`, so QR payloads are
intended to be durable. A constrained deployment can still generate a shorter
TTL by passing `ttlMs` to `createPairingPayload()`. Durable pairings are revoked
by clearing Android pairing state or resetting Linux bridge/device state.

## Secure channel

The relay only forwards JSON control messages and encrypted envelopes.

Handshake:

1. `clientHello`
2. `serverHello`
3. `clientAuth`
4. `secureReady`
5. `encryptedEnvelope`

Crypto:

- Identity: ECDSA P-256, PEM public key in pairing payload.
- Ephemeral agreement: ECDH P-256.
- KDF: HKDF-SHA256.
- Payload encryption: AES-256-GCM.
- Replay guard: per-direction monotonic counters.

## App-server traffic

After `secureReady`, Android encrypts JSON-RPC messages for `codex app-server`.
The Linux bridge decrypts and writes them to app-server stdin as newline-delimited JSON.
Responses and notifications are encrypted back to Android.

If the shared app-server exits while the secure channel is ready, the bridge
sends one encrypted notification before closing the relay socket:

```json
{
  "jsonrpc": "2.0",
  "method": "host/appServerExited",
  "params": {
    "code": 1,
    "signal": "",
    "message": "codex app-server exited code=1 signal="
  }
}
```

Android treats this as a recoverable transport failure and keeps the workspace
visible so the user can refresh, archive the affected thread, or continue in a
new thread.
