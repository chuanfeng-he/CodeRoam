package com.local.codexremote.crypto

class IdentityStoreProvider<T>(
    private val create: () -> T
) {
    private var cached: T? = null

    fun get(): T {
        return cached ?: create().also { cached = it }
    }
}
