package com.local.codexremote.net

import org.junit.Assert.assertEquals
import org.junit.Test

class RelayClientTest {
    @Test
    fun buildRelayWebSocketUrlStripsPasteArtifacts() {
        val relay = " wss://businesses-seeds-\u200bqualified-\u00adresolutions.trycloudflare.com/relay/ "

        val url = buildRelayWebSocketUrl(relay, "session_150ea442")

        assertEquals("https", url.scheme)
        assertEquals("businesses-seeds-qualified-resolutions.trycloudflare.com", url.host)
        assertEquals("/relay/session_150ea442", url.encodedPath)
        assertEquals("android", url.queryParameter("role"))
    }

    @Test
    fun closeEventsPreserveCodeAndReason() {
        val event = RelayCloseEvent.closed(code = 4003, reason = "android replaced")

        assertEquals(4003, event.code)
        assertEquals("android replaced", event.reason)
        assertEquals("android replaced", event.displayMessage)
    }

    @Test
    fun relayClientDoesNotUseAggressiveAndroidHeartbeat() {
        val client = buildRelayOkHttpClient()

        assertEquals(0, client.pingIntervalMillis)
    }

    @Test
    fun relayClientCanOverrideRelayDns() {
        val client = buildRelayOkHttpClient(
            mapOf("relay.example" to "203.0.113.10")
        )

        val addresses = client.dns.lookup("relay.example")

        assertEquals("203.0.113.10", addresses.single().hostAddress)
    }
}
