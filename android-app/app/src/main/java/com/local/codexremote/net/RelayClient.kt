package com.local.codexremote.net

import android.util.Log
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.InetAddress

class RelayClient(
    private val relayUrl: String,
    private val sessionId: String,
    private val relayHostOverrides: Map<String, String> = emptyMap(),
    private val onOpen: () -> Unit,
    private val onText: (String) -> Unit,
    private val onClosed: (RelayCloseEvent) -> Unit,
    private val onError: (RelayCloseEvent) -> Unit,
    private val client: OkHttpClient = buildRelayOkHttpClient(relayHostOverrides)
) {
    private var socket: WebSocket? = null

    fun connect() {
        val url = buildRelayWebSocketUrl(relayUrl, sessionId)
        Log.i(TAG, "connecting url=$url")
        Log.i(TAG, "dns override hosts=${normalizeRelayHostOverrides(relayHostOverrides).keys.joinToString().ifBlank { "(none)" }}")
        val request = Request.Builder()
            .url(url)
            .header("x-role", "android")
            .build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "open code=${response.code} message=${response.message}")
                onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.i(TAG, "message kind=${relayMessageKind(text)} length=${text.length}")
                onText(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "closed code=$code reason=${reason.ifBlank { "(blank)" }}")
                onClosed(RelayCloseEvent.closed(code, reason))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(
                    TAG,
                    "failure type=${t.javaClass.name} message=${t.message ?: "(blank)"} response=${response?.code ?: "(none)"} ${response?.message ?: ""}",
                    t
                )
                onError(RelayCloseEvent.failure(t, response))
            }
        })
    }

    fun send(text: String) {
        socket?.send(text)
    }

    fun close() {
        socket?.close(1000, "closed by android")
        socket = null
    }

    private companion object {
        const val TAG = "CodexRelayClient"
    }
}

internal fun buildRelayOkHttpClient(relayHostOverrides: Map<String, String> = emptyMap()): OkHttpClient {
    val normalizedOverrides = normalizeRelayHostOverrides(relayHostOverrides)
    return OkHttpClient.Builder()
        .apply {
            if (normalizedOverrides.isNotEmpty()) {
                dns(RelayOverrideDns(normalizedOverrides))
            }
        }
        .build()
}

private class RelayOverrideDns(
    private val overrides: Map<String, String>,
    private val fallback: Dns = Dns.SYSTEM
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val override = overrides[hostname.trim().lowercase()]
        if (override != null) {
            return listOf(InetAddress.getByName(override))
        }
        return fallback.lookup(hostname)
    }
}

data class RelayCloseEvent(
    val code: Int?,
    val reason: String,
    val errorMessage: String? = null
) {
    val displayMessage: String
        get() = errorMessage ?: reason.ifBlank {
            if (code != null) "连接已关闭 ($code)" else "连接失败"
        }

    companion object {
        fun closed(code: Int, reason: String): RelayCloseEvent = RelayCloseEvent(
            code = code,
            reason = reason.ifBlank { "连接已关闭 ($code)" }
        )

        fun failure(error: Throwable, response: Response?): RelayCloseEvent = RelayCloseEvent(
            code = response?.code,
            reason = response?.message.orEmpty(),
            errorMessage = buildString {
                append(error.javaClass.simpleName.ifBlank { error.javaClass.name })
                error.message?.takeIf { it.isNotBlank() }?.let { message ->
                    append(": ")
                    append(message)
                }
                response?.let {
                    append(" (HTTP ")
                    append(it.code)
                    if (it.message.isNotBlank()) {
                        append(" ")
                        append(it.message)
                    }
                    append(")")
                }
            }.ifBlank { "连接失败" }
        )
    }
}

private fun relayMessageKind(text: String): String = runCatching {
    JSONObject(text).optString("kind").ifBlank { "missing" }
}.getOrElse {
    "invalid-json"
}

internal fun buildRelayWebSocketUrl(relayUrl: String, sessionId: String): HttpUrl {
    return normalizeRelayBaseUrl(relayUrl)
        .newBuilder()
        .addPathSegment(sessionId)
        .addQueryParameter("role", "android")
        .build()
}

internal fun normalizeRelayBaseUrl(relayUrl: String): HttpUrl {
    val cleaned = relayUrl
        .trim()
        .trim('"', '\'', '“', '”', '‘', '’')
        .replace(Regex("[\\u00ad\\u200b\\u200c\\u200d\\u2060\\ufeff]"), "")
        .replace(Regex("\\s+"), "")
        .trimEnd('/')

    require(cleaned.startsWith("ws://", ignoreCase = true) || cleaned.startsWith("wss://", ignoreCase = true)) {
        "relay URL must start with ws:// or wss://"
    }

    val httpUrl = when {
        cleaned.startsWith("wss://", ignoreCase = true) -> "https://${cleaned.substringAfter("://")}"
        else -> "http://${cleaned.substringAfter("://")}"
    }.toHttpUrl()

    require(httpUrl.host.isNotBlank()) { "relay URL host is required" }
    return httpUrl
}

internal fun normalizeRelayHostOverrides(overrides: Map<String, String>): Map<String, String> {
    return overrides.mapNotNull { (rawHost, rawAddress) ->
        val host = rawHost.trim().lowercase()
        val address = rawAddress.trim()
        if (host.isBlank() || address.isBlank()) {
            null
        } else {
            host to address
        }
    }.toMap()
}
