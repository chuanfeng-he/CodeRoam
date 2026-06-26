package com.local.codexremote.ui

import android.content.Context
import com.local.codexremote.data.CodexJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val PairingPrefs = "codex_remote_pairing"
private const val RuntimePrefs = "codex_remote_runtime"
private const val PairingKey = "saved_pairing"
private const val RuntimeKey = "runtime_settings"

class SavedPairingStore(
    context: Context,
    private val json: Json = CodexJson
) {
    private val prefs = context.getSharedPreferences(PairingPrefs, Context.MODE_PRIVATE)

    fun load(): SavedPairingProfile? {
        val raw = prefs.getString(PairingKey, null) ?: return null
        return runCatching { json.decodeFromString<SavedPairingProfile>(raw) }.getOrNull()
            ?.takeIf { it.isUsableForReconnect() }
    }

    fun save(profile: SavedPairingProfile) {
        prefs.edit().putString(PairingKey, json.encodeToString(profile)).apply()
    }

    fun clear() {
        prefs.edit().remove(PairingKey).apply()
    }
}

class RuntimeSettingsStore(
    context: Context,
    private val json: Json = CodexJson
) {
    private val prefs = context.getSharedPreferences(RuntimePrefs, Context.MODE_PRIVATE)

    fun load(): RuntimeSettingsSnapshot {
        val raw = prefs.getString(RuntimeKey, null) ?: return RuntimeSettingsSnapshot()
        return runCatching { json.decodeFromString<RuntimeSettingsSnapshot>(raw) }.getOrDefault(RuntimeSettingsSnapshot())
    }

    fun save(snapshot: RuntimeSettingsSnapshot) {
        prefs.edit().putString(RuntimeKey, json.encodeToString(snapshot)).apply()
    }
}

@Serializable
data class RuntimeSettingsSnapshot(
    val selectedModelId: String = "gpt-5.5",
    val selectedAccessMode: AccessMode = AccessMode.OnRequest,
    val selectedReasoningEffort: ReasoningEffort = ReasoningEffort.Medium,
    val fastModeEnabled: Boolean = false,
    val selectedTheme: AppTheme = AppTheme.Default,
    val selectedProjectCwd: String? = null,
    val planModeEnabled: Boolean = false,
    val goalModeEnabled: Boolean = false,
    val showArchivedThreads: Boolean = false,
    val archivedThreadIds: Set<String> = emptySet()
)
