package com.local.codexremote.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class JsonConfigTest {
    @Test
    fun codexJsonIncludesDefaultMessageKinds() {
        val clientHello = CodexJson.encodeToString(
            ClientHello(
                protocolVersion = 1,
                sessionId = "session_1",
                androidDeviceId = "android_1",
                androidIdentityPublicKey = "android-key",
                androidEphemeralPublicKey = "ephemeral-key",
                clientNonce = "nonce"
            )
        )
        val clientAuth = CodexJson.encodeToString(
            ClientAuth(
                sessionId = "session_1",
                androidDeviceId = "android_1",
                keyEpoch = 1,
                androidSignature = "signature"
            )
        )
        val envelope = CodexJson.encodeToString(
            SecureEnvelope(
                v = 1,
                sessionId = "session_1",
                keyEpoch = 1,
                sender = "android",
                counter = 0,
                ciphertext = "ciphertext",
                tag = "tag"
            )
        )

        assertEquals("clientHello", kindOf(clientHello))
        assertEquals("clientAuth", kindOf(clientAuth))
        assertEquals("encryptedEnvelope", kindOf(envelope))
    }

    private fun kindOf(raw: String): String {
        val value = CodexJson.parseToJsonElement(raw).jsonObject["kind"]
        assertNotNull(value)
        return value!!.toString().trim('"')
    }
}
