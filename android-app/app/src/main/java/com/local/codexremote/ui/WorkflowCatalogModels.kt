package com.local.codexremote.ui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val WorkflowPageLimit = 50

enum class WorkflowSection {
    Chat,
    Skills,
    Plugins,
    Automation
}

data class SkillItem(
    val name: String,
    val displayName: String = name,
    val description: String = "",
    val path: String? = null,
    val scope: String? = null,
    val enabled: Boolean = true,
    val defaultPrompt: String? = null
)

data class SkillGroup(
    val cwd: String,
    val skills: List<SkillItem> = emptyList(),
    val errors: List<String> = emptyList()
)

data class SkillsCatalogState(
    val groups: List<SkillGroup> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val lastSyncedAtMs: Long? = null,
    val isStale: Boolean = false
)

data class PluginItem(
    val id: String,
    val name: String,
    val displayName: String = name,
    val description: String = "",
    val installed: Boolean = false,
    val enabled: Boolean = false,
    val availability: String? = null,
    val source: String? = null,
    val capabilities: List<String> = emptyList(),
    val keywords: List<String> = emptyList(),
    val defaultPrompts: List<String> = emptyList(),
    val brandColor: String? = null
)

data class PluginMarketplace(
    val name: String,
    val path: String? = null,
    val plugins: List<PluginItem> = emptyList()
)

data class PluginCatalogState(
    val marketplaces: List<PluginMarketplace> = emptyList(),
    val errors: List<String> = emptyList(),
    val featuredPluginIds: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val lastSyncedAtMs: Long? = null,
    val isStale: Boolean = false
)

data class AutomationAppItem(
    val id: String,
    val name: String,
    val description: String? = null,
    val logoUrl: String? = null,
    val installUrl: String? = null,
    val isAccessible: Boolean = false,
    val isEnabled: Boolean = false,
    val pluginDisplayNames: List<String> = emptyList()
)

data class McpServerItem(
    val name: String,
    val authStatus: String? = null,
    val startupStatus: String? = null,
    val error: String? = null,
    val toolCount: Int = 0,
    val resourceCount: Int = 0,
    val resourceTemplateCount: Int = 0
)

data class HookItem(
    val key: String,
    val eventName: String? = null,
    val handlerType: String? = null,
    val matcher: String? = null,
    val command: String? = null,
    val timeoutSec: String? = null,
    val statusMessage: String? = null,
    val sourcePath: String? = null,
    val pluginId: String? = null,
    val enabled: Boolean = true,
    val isManaged: Boolean = false,
    val trustStatus: String? = null
)

data class HookGroup(
    val cwd: String,
    val hooks: List<HookItem> = emptyList(),
    val warnings: List<String> = emptyList(),
    val errors: List<String> = emptyList()
)

data class AutomationCatalogState(
    val apps: List<AutomationAppItem> = emptyList(),
    val nextAppsCursor: String? = null,
    val mcpServers: List<McpServerItem> = emptyList(),
    val nextMcpCursor: String? = null,
    val hookGroups: List<HookGroup> = emptyList(),
    val isLoadingApps: Boolean = false,
    val isLoadingMcp: Boolean = false,
    val isLoadingHooks: Boolean = false,
    val appsError: String? = null,
    val mcpError: String? = null,
    val hooksError: String? = null,
    val lastSyncedAtMs: Long? = null,
    val isStale: Boolean = false
)

data class AppsCatalogPage(
    val apps: List<AutomationAppItem>,
    val nextCursor: String?
)

data class McpCatalogPage(
    val servers: List<McpServerItem>,
    val nextCursor: String?
)

data class HooksCatalog(
    val groups: List<HookGroup>
)

fun buildSkillsListParams(cwd: String?, forceReload: Boolean): JsonObject = buildJsonObject {
    cwd.cleanOrNull()?.let { put("cwds", buildJsonArray { add(JsonPrimitive(it)) }) }
    if (forceReload) {
        put("forceReload", true)
    }
}

fun buildPluginListParams(cwd: String?): JsonObject = buildJsonObject {
    cwd.cleanOrNull()?.let { put("cwds", buildJsonArray { add(JsonPrimitive(it)) }) }
}

fun buildAppsListParams(threadId: String?, cursor: String?, forceRefetch: Boolean): JsonObject = buildJsonObject {
    put("limit", WorkflowPageLimit)
    threadId.cleanOrNull()?.let { put("threadId", it) }
    cursor.cleanOrNull()?.let { put("cursor", it) }
    if (forceRefetch) {
        put("forceRefetch", true)
    }
}

fun buildMcpServerStatusListParams(cursor: String?): JsonObject = buildJsonObject {
    put("limit", WorkflowPageLimit)
    put("detail", "full")
    cursor.cleanOrNull()?.let { put("cursor", it) }
}

fun buildHooksListParams(cwd: String?): JsonObject = buildJsonObject {
    cwd.cleanOrNull()?.let { put("cwds", buildJsonArray { add(JsonPrimitive(it)) }) }
}

fun parseSkillsCatalog(result: JsonObject): SkillsCatalogState = SkillsCatalogState(
    groups = result.arrayForAny("data", "entries").mapNotNull { entryElement ->
        val entry = entryElement.safeJsonObject() ?: return@mapNotNull null
        SkillGroup(
            cwd = entry.stringForAny("cwd") ?: "",
            skills = entry.arrayForAny("skills").mapNotNull { it.safeJsonObject()?.toSkillItem() },
            errors = entry.arrayForAny("errors").mapNotNull { it.errorText() }
        )
    }
)

fun parsePluginCatalog(result: JsonObject): PluginCatalogState = PluginCatalogState(
    marketplaces = result.arrayForAny("marketplaces").mapNotNull { marketplaceElement ->
        val marketplace = marketplaceElement.safeJsonObject() ?: return@mapNotNull null
        PluginMarketplace(
            name = marketplace.stringForAny("name") ?: "marketplace",
            path = marketplace.stringForAny("path"),
            plugins = marketplace.arrayForAny("plugins").mapNotNull { it.safeJsonObject()?.toPluginItem() }
        )
    },
    errors = result.arrayForAny("marketplaceLoadErrors", "errors").mapNotNull { it.errorText() },
    featuredPluginIds = result.arrayForAny("featuredPluginIds", "featured_plugin_ids").mapNotNull { it.jsonPrimitiveOrNullContent() }
)

fun parseAppsCatalog(result: JsonObject): AppsCatalogPage = AppsCatalogPage(
    apps = result.arrayForAny("data", "apps").mapNotNull { appElement ->
        val app = appElement.safeJsonObject() ?: return@mapNotNull null
        val id = app.stringForAny("id") ?: return@mapNotNull null
        AutomationAppItem(
            id = id,
            name = app.stringForAny("name", "displayName") ?: id,
            description = app.stringForAny("description"),
            logoUrl = app.stringForAny("logoUrl", "logoUrlDark"),
            installUrl = app.stringForAny("installUrl"),
            isAccessible = app.booleanForAny("isAccessible") ?: false,
            isEnabled = app.booleanForAny("isEnabled") ?: false,
            pluginDisplayNames = app.arrayForAny("pluginDisplayNames").mapNotNull { it.jsonPrimitiveOrNullContent() }
        )
    },
    nextCursor = result.stringForAny("nextCursor", "next_cursor")
)

fun parseMcpCatalog(result: JsonObject): McpCatalogPage = McpCatalogPage(
    servers = result.arrayForAny("data", "servers").mapNotNull { serverElement ->
        val server = serverElement.safeJsonObject() ?: return@mapNotNull null
        val name = server.stringForAny("name") ?: return@mapNotNull null
        McpServerItem(
            name = name,
            authStatus = server.stringForAny("authStatus", "auth_status"),
            toolCount = server["tools"]?.safeJsonObject()?.size ?: 0,
            resourceCount = server.arrayForAny("resources").size,
            resourceTemplateCount = server.arrayForAny("resourceTemplates").size
        )
    },
    nextCursor = result.stringForAny("nextCursor", "next_cursor")
)

fun parseHooksCatalog(result: JsonObject): HooksCatalog = HooksCatalog(
    groups = result.arrayForAny("data", "entries").mapNotNull { entryElement ->
        val entry = entryElement.safeJsonObject() ?: return@mapNotNull null
        HookGroup(
            cwd = entry.stringForAny("cwd") ?: "",
            hooks = entry.arrayForAny("hooks").mapNotNull { it.safeJsonObject()?.toHookItem() },
            warnings = entry.arrayForAny("warnings").mapNotNull { it.jsonPrimitiveOrNullContent() },
            errors = entry.arrayForAny("errors").mapNotNull { it.errorText() }
        )
    }
)

fun SkillsCatalogState.withWorkflowLoadFailure(message: String): SkillsCatalogState = copy(
    isLoading = false,
    error = message
)

fun PluginCatalogState.withWorkflowLoadFailure(message: String): PluginCatalogState = copy(
    isLoading = false,
    error = message
)

fun AutomationCatalogState.withAppsLoadFailure(message: String): AutomationCatalogState = copy(
    isLoadingApps = false,
    appsError = message
)

fun AutomationCatalogState.withMcpLoadFailure(message: String): AutomationCatalogState = copy(
    isLoadingMcp = false,
    mcpError = message
)

fun AutomationCatalogState.withHooksLoadFailure(message: String): AutomationCatalogState = copy(
    isLoadingHooks = false,
    hooksError = message
)

fun CodexRemoteUiState.withWorkflowSection(section: WorkflowSection): CodexRemoteUiState = copy(
    workflowSection = section
)

fun CodexRemoteUiState.withUpdatedAppsFromNotification(
    params: JsonObject,
    nowMillis: Long = System.currentTimeMillis()
): CodexRemoteUiState {
    val page = parseAppsCatalog(params)
    return copy(
        automationCatalog = automationCatalog.copy(
            apps = page.apps,
            nextAppsCursor = page.nextCursor,
            isLoadingApps = false,
            appsError = null,
            lastSyncedAtMs = nowMillis,
            isStale = false
        )
    )
}

fun CodexRemoteUiState.withUpdatedMcpServerStatus(
    params: JsonObject,
    nowMillis: Long = System.currentTimeMillis()
): CodexRemoteUiState {
    val name = params.stringForAny("name") ?: return this
    val status = params.stringForAny("status")
    val error = params.stringForAny("error")
    val existing = automationCatalog.mcpServers
    val patched = if (existing.any { it.name == name }) {
        existing.map { server ->
            if (server.name == name) {
                server.copy(startupStatus = status ?: server.startupStatus, error = error)
            } else {
                server
            }
        }
    } else {
        existing + McpServerItem(name = name, startupStatus = status, error = error)
    }
    return copy(
        automationCatalog = automationCatalog.copy(
            mcpServers = patched,
            lastSyncedAtMs = nowMillis,
            isStale = false
        )
    )
}

private fun JsonObject.toSkillItem(): SkillItem? {
    val name = stringForAny("name") ?: return null
    val interfaceObject = this["interface"]?.safeJsonObject()
    val displayName = interfaceObject?.stringForAny("displayName", "display_name")
        ?: stringForAny("displayName", "display_name")
        ?: name
    val description = interfaceObject?.stringForAny("shortDescription", "short_description")
        ?: stringForAny("shortDescription", "short_description")
        ?: stringForAny("description")
        ?: ""
    return SkillItem(
        name = name,
        displayName = displayName,
        description = description,
        path = stringForAny("path"),
        scope = stringForAny("scope"),
        enabled = booleanForAny("enabled") ?: true,
        defaultPrompt = interfaceObject?.stringForAny("defaultPrompt", "default_prompt")
    )
}

private fun JsonObject.toPluginItem(): PluginItem? {
    val id = stringForAny("id") ?: return null
    val name = stringForAny("name") ?: id
    val interfaceObject = this["interface"]?.safeJsonObject()
    return PluginItem(
        id = id,
        name = name,
        displayName = interfaceObject?.stringForAny("displayName", "display_name")
            ?: stringForAny("displayName", "display_name")
            ?: name,
        description = interfaceObject?.stringForAny("shortDescription", "short_description", "longDescription", "long_description")
            ?: stringForAny("description")
            ?: "",
        installed = booleanForAny("installed") ?: false,
        enabled = booleanForAny("enabled") ?: false,
        availability = stringForAny("availability"),
        source = stringForAny("source"),
        capabilities = interfaceObject?.arrayForAny("capabilities")?.mapNotNull { it.jsonPrimitiveOrNullContent() }.orEmpty(),
        keywords = arrayForAny("keywords").mapNotNull { it.jsonPrimitiveOrNullContent() },
        defaultPrompts = interfaceObject?.arrayForAny("defaultPrompt", "default_prompt")?.mapNotNull { it.jsonPrimitiveOrNullContent() }.orEmpty(),
        brandColor = interfaceObject?.stringForAny("brandColor", "brand_color")
    )
}

private fun JsonObject.toHookItem(): HookItem? {
    val key = stringForAny("key") ?: return null
    return HookItem(
        key = key,
        eventName = stringForAny("eventName", "event_name"),
        handlerType = stringForAny("handlerType", "handler_type"),
        matcher = stringForAny("matcher"),
        command = stringForAny("command"),
        timeoutSec = stringForAny("timeoutSec", "timeout_sec"),
        statusMessage = stringForAny("statusMessage", "status_message"),
        sourcePath = stringForAny("sourcePath", "source_path"),
        pluginId = stringForAny("pluginId", "plugin_id"),
        enabled = booleanForAny("enabled") ?: true,
        isManaged = booleanForAny("isManaged") ?: false,
        trustStatus = stringForAny("trustStatus", "trust_status")
    )
}

private fun JsonElement.errorText(): String? =
    safeJsonObject()?.stringForAny("message", "detail", "error")
        ?: jsonPrimitiveOrNullContent()

private fun JsonObject.arrayForAny(vararg keys: String): JsonArray {
    for (key in keys) {
        val value = this[key]
        if (value is JsonArray) return value
    }
    return JsonArray(emptyList())
}

private fun JsonObject.stringForAny(vararg keys: String): String? {
    for (key in keys) {
        val value = this[key]?.jsonPrimitiveOrNullContent()?.trim()
        if (!value.isNullOrEmpty()) return value
    }
    return null
}

private fun JsonObject.booleanForAny(vararg keys: String): Boolean? {
    for (key in keys) {
        val value = this[key] ?: continue
        val primitive = runCatching { value.jsonPrimitive }.getOrNull() ?: continue
        primitive.booleanOrNull?.let { return it }
        primitive.contentOrNull?.toBooleanStrictOrNull()?.let { return it }
    }
    return null
}

private fun JsonElement.safeJsonObject(): JsonObject? =
    runCatching { jsonObject }.getOrNull()

private fun JsonElement.jsonPrimitiveOrNullContent(): String? {
    if (this is JsonNull) return null
    return runCatching { jsonPrimitive.contentOrNull ?: jsonPrimitive.content }.getOrNull()
}

private fun String?.cleanOrNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
