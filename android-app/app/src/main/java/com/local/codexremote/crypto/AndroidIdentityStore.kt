package com.local.codexremote.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID

class AndroidIdentityStore(
    context: Context,
    private val alias: String = "codex_remote_android_identity_v1"
) {
    private val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val preferences = context.getSharedPreferences("codex_remote_identity", Context.MODE_PRIVATE)

    val deviceId: String = preferences.getString("device_id", null) ?: "android_${UUID.randomUUID()}".also {
        preferences.edit().putString("device_id", it).apply()
    }

    fun publicKeyPem(): String {
        ensureKey()
        val publicKey = keyStore.getCertificate(alias).publicKey.encoded
        return pem("PUBLIC KEY", publicKey)
    }

    fun sign(payload: ByteArray): String {
        ensureKey()
        val privateKey = keyStore.getKey(alias, null) as PrivateKey
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey)
        signature.update(payload)
        return Base64.getEncoder().encodeToString(signature.sign())
    }

    private fun ensureKey() {
        if (keyStore.containsAlias(alias)) return
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()
        generator.initialize(spec)
        generator.generateKeyPair()
    }
}

fun pem(type: String, der: ByteArray): String {
    val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der)
    return "-----BEGIN $type-----\n$body\n-----END $type-----\n"
}
