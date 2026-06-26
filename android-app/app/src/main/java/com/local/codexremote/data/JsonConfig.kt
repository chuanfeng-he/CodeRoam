package com.local.codexremote.data

import kotlinx.serialization.json.Json

val CodexJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
