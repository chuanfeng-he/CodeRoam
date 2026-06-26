package com.local.codexremote.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class PairingPayload(
    val v: Int,
    val relay: String,
    val sessionId: String,
    val hostDeviceId: String,
    val hostIdentityPublicKey: String,
    val expiresAt: Long,
    val displayName: String = "",
    val relayHostOverrides: Map<String, String> = emptyMap()
)

@Serializable
data class RpcMessage(
    val jsonrpc: String? = "2.0",
    val id: JsonElement? = null,
    val method: String? = null,
    val params: JsonElement? = null,
    val result: JsonElement? = null,
    val error: RpcError? = null
)

@Serializable
data class RpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null
)

@Serializable
data class ClientHello(
    val kind: String = "clientHello",
    val protocolVersion: Int,
    val sessionId: String,
    val androidDeviceId: String,
    val androidIdentityPublicKey: String,
    val androidEphemeralPublicKey: String,
    val clientNonce: String
)

@Serializable
data class ServerHello(
    val kind: String,
    val protocolVersion: Int,
    val sessionId: String,
    val keyEpoch: Int,
    val hostDeviceId: String,
    val hostIdentityPublicKey: String,
    val hostEphemeralPublicKey: String,
    val serverNonce: String,
    val hostSignature: String
)

@Serializable
data class ClientAuth(
    val kind: String = "clientAuth",
    val sessionId: String,
    val androidDeviceId: String,
    val keyEpoch: Int,
    val androidSignature: String
)

@Serializable
data class SecureReady(
    val kind: String,
    val protocolVersion: Int,
    val sessionId: String,
    val keyEpoch: Int,
    val hostDeviceId: String
)

@Serializable
data class SecureEnvelope(
    val kind: String = "encryptedEnvelope",
    val v: Int,
    val sessionId: String,
    val keyEpoch: Int,
    val sender: String,
    val counter: Long,
    val ciphertext: String,
    val tag: String
)

data class ChatMessage(
    val role: Role,
    val text: String,
    val threadId: String? = null,
    val turnId: String? = null,
    val itemId: String? = null,
    val createdAtMs: Long? = null
) {
    enum class Role { User, Assistant, System }
}

@Serializable
data class ThreadSummary(
    val id: String,
    val title: String? = null,
    val preview: String? = null,
    val cwd: String? = null,
    @SerialName("updated_at")
    val updatedAt: String? = null
)
