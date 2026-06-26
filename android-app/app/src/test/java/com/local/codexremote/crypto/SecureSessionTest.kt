package com.local.codexremote.crypto

import com.local.codexremote.data.CodexJson
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class SecureSessionTest {
    @Test
    fun transcriptJsonDoesNotRequireAnySerializer() {
        val transcript = buildTranscriptJson(
            sessionId = "session_1",
            keyEpoch = 1,
            hostDeviceId = "host_1",
            androidDeviceId = "android_1",
            hostIdentityPublicKey = "host-key",
            androidIdentityPublicKey = "android-key",
            hostEphemeralPublicKey = "host-ephemeral",
            androidEphemeralPublicKey = "android-ephemeral",
            clientNonce = "client-nonce",
            serverNonce = "server-nonce"
        )
        val parsed = CodexJson.parseToJsonElement(transcript).jsonObject

        assertEquals("codex-android-remote-e2ee-v1", parsed["tag"]!!.jsonPrimitive.content)
        assertEquals(1, parsed["protocolVersion"]!!.jsonPrimitive.int)
        assertEquals(1, parsed["keyEpoch"]!!.jsonPrimitive.int)
        assertEquals("host-key", parsed["hostIdentityPublicKey"]!!.jsonPrimitive.content)
    }
}
