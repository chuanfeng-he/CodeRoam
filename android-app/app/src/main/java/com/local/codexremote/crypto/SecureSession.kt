package com.local.codexremote.crypto

import com.local.codexremote.data.ClientAuth
import com.local.codexremote.data.ClientHello
import com.local.codexremote.data.CodexJson
import com.local.codexremote.data.PairingPayload
import com.local.codexremote.data.SecureEnvelope
import com.local.codexremote.data.SecureReady
import com.local.codexremote.data.ServerHello
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest

private const val ProtocolVersion = 1
private const val HandshakeTag = "codex-android-remote-e2ee-v1"

class SecureSession(
    private val pairing: PairingPayload,
    private val identityStore: AndroidIdentityStore,
    private val json: Json = CodexJson
) {
    private val ephemeral = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()
    private val clientNonce = randomBytes(32)
    private var sessionKeys: SessionKeys? = null
    private var nextCounter = 0L
    private var lastInboundCounter = -1L

    fun createClientHello(): ClientHello = ClientHello(
        protocolVersion = ProtocolVersion,
        sessionId = pairing.sessionId,
        androidDeviceId = identityStore.deviceId,
        androidIdentityPublicKey = identityStore.publicKeyPem(),
        androidEphemeralPublicKey = pem("PUBLIC KEY", ephemeral.public.encoded),
        clientNonce = Base64.getEncoder().encodeToString(clientNonce)
    )

    fun handleServerHello(serverHello: ServerHello): ClientAuth {
        require(serverHello.sessionId == pairing.sessionId) { "sessionId mismatch" }
        require(serverHello.hostDeviceId == pairing.hostDeviceId) { "hostDeviceId mismatch" }
        require(serverHello.hostIdentityPublicKey == pairing.hostIdentityPublicKey) { "host identity mismatch" }

        val transcript = transcript(serverHello)
        val hostKey = publicKeyFromPem(serverHello.hostIdentityPublicKey)
        require(verify(hostKey, transcript, serverHello.hostSignature)) { "invalid host signature" }

        val sharedSecret = KeyAgreement.getInstance("ECDH").run {
            init(ephemeral.private)
            doPhase(publicKeyFromPem(serverHello.hostEphemeralPublicKey), true)
            generateSecret()
        }
        val salt = sha256(transcript)
        val infoPrefix = "$HandshakeTag|${pairing.sessionId}|${serverHello.keyEpoch}"
        sessionKeys = SessionKeys(
            keyEpoch = serverHello.keyEpoch,
            outboundKey = hkdf(sharedSecret, salt, "$infoPrefix|androidToHost".toByteArray(), 32),
            inboundKey = hkdf(sharedSecret, salt, "$infoPrefix|hostToAndroid".toByteArray(), 32)
        )

        return ClientAuth(
            sessionId = pairing.sessionId,
            androidDeviceId = identityStore.deviceId,
            keyEpoch = serverHello.keyEpoch,
            androidSignature = identityStore.sign(transcript + "|clientAuth".toByteArray())
        )
    }

    fun handleSecureReady(ready: SecureReady) {
        require(ready.sessionId == pairing.sessionId) { "sessionId mismatch" }
        require(ready.hostDeviceId == pairing.hostDeviceId) { "hostDeviceId mismatch" }
        require(sessionKeys?.keyEpoch == ready.keyEpoch) { "key epoch mismatch" }
    }

    fun encrypt(plaintext: String): SecureEnvelope {
        val keys = requireNotNull(sessionKeys) { "secure session is not ready" }
        val nonce = nonce("android", nextCounter, keys.keyEpoch)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.outboundKey, "AES"), GCMParameterSpec(128, nonce))
        val sealed = cipher.doFinal(plaintext.toByteArray())
        val tagOffset = sealed.size - 16
        val ciphertext = sealed.copyOfRange(0, tagOffset)
        val tag = sealed.copyOfRange(tagOffset, sealed.size)
        return SecureEnvelope(
            v = ProtocolVersion,
            sessionId = pairing.sessionId,
            keyEpoch = keys.keyEpoch,
            sender = "android",
            counter = nextCounter++,
            ciphertext = Base64.getEncoder().encodeToString(ciphertext),
            tag = Base64.getEncoder().encodeToString(tag)
        )
    }

    fun decrypt(envelope: SecureEnvelope): String {
        val keys = requireNotNull(sessionKeys) { "secure session is not ready" }
        require(envelope.sender == "host") { "unexpected sender" }
        require(envelope.counter > lastInboundCounter) { "replayed envelope" }
        val nonce = nonce("host", envelope.counter, envelope.keyEpoch)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keys.inboundKey, "AES"), GCMParameterSpec(128, nonce))
        val sealed = Base64.getDecoder().decode(envelope.ciphertext) + Base64.getDecoder().decode(envelope.tag)
        lastInboundCounter = envelope.counter
        return String(cipher.doFinal(sealed))
    }

    private fun transcript(serverHello: ServerHello): ByteArray {
        return buildTranscriptJson(
            sessionId = pairing.sessionId,
            keyEpoch = serverHello.keyEpoch,
            hostDeviceId = pairing.hostDeviceId,
            androidDeviceId = identityStore.deviceId,
            hostIdentityPublicKey = serverHello.hostIdentityPublicKey,
            androidIdentityPublicKey = identityStore.publicKeyPem(),
            hostEphemeralPublicKey = serverHello.hostEphemeralPublicKey,
            androidEphemeralPublicKey = pem("PUBLIC KEY", ephemeral.public.encoded),
            clientNonce = Base64.getEncoder().encodeToString(clientNonce),
            serverNonce = serverHello.serverNonce
        ).toByteArray()
    }

    private fun nonce(sender: String, counter: Long, keyEpoch: Int): ByteArray =
        sha256("$HandshakeTag|${pairing.sessionId}|$keyEpoch|$sender|$counter".toByteArray()).copyOfRange(0, 12)
}

private data class SessionKeys(val keyEpoch: Int, val outboundKey: ByteArray, val inboundKey: ByteArray)

internal fun buildTranscriptJson(
    sessionId: String,
    keyEpoch: Int,
    hostDeviceId: String,
    androidDeviceId: String,
    hostIdentityPublicKey: String,
    androidIdentityPublicKey: String,
    hostEphemeralPublicKey: String,
    androidEphemeralPublicKey: String,
    clientNonce: String,
    serverNonce: String
): String = buildJsonObject {
    put("tag", HandshakeTag)
    put("sessionId", sessionId)
    put("protocolVersion", ProtocolVersion)
    put("keyEpoch", keyEpoch)
    put("hostDeviceId", hostDeviceId)
    put("androidDeviceId", androidDeviceId)
    put("hostIdentityPublicKey", hostIdentityPublicKey)
    put("androidIdentityPublicKey", androidIdentityPublicKey)
    put("hostEphemeralPublicKey", hostEphemeralPublicKey)
    put("androidEphemeralPublicKey", androidEphemeralPublicKey)
    put("clientNonce", clientNonce)
    put("serverNonce", serverNonce)
}.toString()

private fun publicKeyFromPem(pem: String): PublicKey {
    val body = pem.lineSequence().filterNot { it.startsWith("-----") }.joinToString("")
    return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(body)))
}

private fun verify(publicKey: PublicKey, payload: ByteArray, signatureBase64: String): Boolean {
    val verifier = Signature.getInstance("SHA256withECDSA")
    verifier.initVerify(publicKey)
    verifier.update(payload)
    return verifier.verify(Base64.getDecoder().decode(signatureBase64))
}

private fun randomBytes(size: Int): ByteArray = ByteArray(size).also { java.security.SecureRandom().nextBytes(it) }
private fun sha256(payload: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(payload)

private fun hkdf(secret: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
    val prk = hmac(salt, secret)
    var previous = ByteArray(0)
    val output = mutableListOf<Byte>()
    var counter = 1
    while (output.size < length) {
        previous = hmac(prk, previous + info + byteArrayOf(counter.toByte()))
        output.addAll(previous.toList())
        counter += 1
    }
    return output.take(length).toByteArray()
}

private fun hmac(key: ByteArray, payload: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(payload)
}
