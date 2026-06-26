package com.local.codexremote.crypto

import org.junit.Assert.assertEquals
import org.junit.Test

class IdentityStoreProviderTest {
    @Test
    fun doesNotCreateStoreUntilRequested() {
        var created = 0
        val provider = IdentityStoreProvider {
            created += 1
            "identity"
        }

        assertEquals(0, created)
        assertEquals("identity", provider.get())
        assertEquals("identity", provider.get())
        assertEquals(1, created)
    }
}
