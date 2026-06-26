@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.local.codexremote.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val WorkflowBackground: Color
    @Composable get() = LocalAppColors.current.appBackground
private val WorkflowCardBackground: Color
    @Composable get() = LocalAppColors.current.surfaceBackground
private val WorkflowBorder: Color
    @Composable get() = LocalAppColors.current.border
private val WorkflowTextPrimary: Color
    @Composable get() = LocalAppColors.current.textPrimary
private val WorkflowTextSecondary: Color
    @Composable get() = LocalAppColors.current.textSecondary
private val WorkflowAccent: Color
    @Composable get() = LocalAppColors.current.accent
private val WorkflowWarning: Color
    @Composable get() = LocalAppColors.current.warning
private val WorkflowMuted: Color
    @Composable get() = LocalAppColors.current.sidebarBackground

private enum class AutomationTab {
    Apps,
    Mcp,
    Hooks
}

@Composable
fun WorkflowWorkArea(
    state: CodexRemoteUiState,
    showMenu: Boolean,
    onOpenMenu: () -> Unit,
    onSelectWorkflowSection: (WorkflowSection) -> Unit,
    onRefreshWorkflowSection: () -> Unit,
    onLoadMoreAutomationApps: () -> Unit,
    onLoadMoreMcpServers: () -> Unit,
    onSendPrompt: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WorkflowBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        WorkflowTopBar(
            state = state,
            showMenu = showMenu,
            onOpenMenu = onOpenMenu,
            onRefreshWorkflowSection = onRefreshWorkflowSection
        )
        WorkflowSectionTabs(
            selected = state.workflowSection,
            onSelectWorkflowSection = onSelectWorkflowSection
        )
        if (!state.secure) {
            WorkflowInlineNotice("连接中断，当前列表会保留缓存，重连后可刷新。")
        }
        when (state.workflowSection) {
            WorkflowSection.Chat -> Unit
            WorkflowSection.Skills -> SkillsPanel(
                modifier = Modifier.weight(1f),
                catalog = state.skillsCatalog,
                onSendPrompt = onSendPrompt
            )
            WorkflowSection.Plugins -> PluginsPanel(
                modifier = Modifier.weight(1f),
                catalog = state.pluginsCatalog,
                onSendPrompt = onSendPrompt
            )
            WorkflowSection.Automation -> AutomationPanel(
                modifier = Modifier.weight(1f),
                catalog = state.automationCatalog,
                onLoadMoreApps = onLoadMoreAutomationApps,
                onLoadMoreMcp = onLoadMoreMcpServers
            )
        }
    }
}

@Composable
private fun WorkflowTopBar(
    state: CodexRemoteUiState,
    showMenu: Boolean,
    onOpenMenu: () -> Unit,
    onRefreshWorkflowSection: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (showMenu) {
            IconButton(onClick = onOpenMenu, modifier = Modifier.size(40.dp)) {
                Text("☰", color = WorkflowTextSecondary, style = MaterialTheme.typography.titleMedium)
            }
        }
        Text(
            state.workflowSection.title(),
            modifier = Modifier.weight(1f),
            color = WorkflowTextPrimary,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            state.status,
            color = WorkflowTextSecondary,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        TextButton(enabled = state.secure, onClick = onRefreshWorkflowSection) {
            Text("刷新")
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun WorkflowSectionTabs(
    selected: WorkflowSection,
    onSelectWorkflowSection: (WorkflowSection) -> Unit
) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        listOf(WorkflowSection.Skills, WorkflowSection.Plugins, WorkflowSection.Automation).forEach { section ->
            WorkflowTabChip(
                label = section.title(),
                selected = selected == section,
                onClick = { onSelectWorkflowSection(section) }
            )
        }
    }
}

@Composable
private fun SkillsPanel(
    modifier: Modifier = Modifier,
    catalog: SkillsCatalogState,
    onSendPrompt: (String) -> Unit
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            CatalogStatus(
                loading = catalog.isLoading,
                stale = catalog.isStale,
                error = catalog.error,
                empty = catalog.groups.all { it.skills.isEmpty() },
                emptyText = "暂无可用技能"
            )
        }
        catalog.groups.forEach { group ->
            item(key = "skill-group-${group.cwd}") {
                GroupHeader(title = group.cwd.ifBlank { "当前工作区" })
            }
            group.errors.forEachIndexed { index, error ->
                item(key = "skill-error-${group.cwd}-$index") {
                    WorkflowInlineNotice(error)
                }
            }
            items(group.skills, key = { "skill-${group.cwd}-${it.path ?: it.name}" }) { skill ->
                SkillCard(skill = skill, onSendPrompt = onSendPrompt)
            }
        }
        item { PanelBottomSpacer() }
    }
}

@Composable
private fun PluginsPanel(
    modifier: Modifier = Modifier,
    catalog: PluginCatalogState,
    onSendPrompt: (String) -> Unit
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            CatalogStatus(
                loading = catalog.isLoading,
                stale = catalog.isStale,
                error = catalog.error,
                empty = catalog.marketplaces.all { it.plugins.isEmpty() },
                emptyText = "暂无可用插件"
            )
        }
        catalog.errors.forEachIndexed { index, error ->
            item(key = "plugin-error-$index") {
                WorkflowInlineNotice(error)
            }
        }
        catalog.marketplaces.forEach { marketplace ->
            item(key = "marketplace-${marketplace.name}") {
                GroupHeader(title = marketplace.name, subtitle = marketplace.path)
            }
            items(marketplace.plugins, key = { "plugin-${marketplace.name}-${it.id}" }) { plugin ->
                PluginCard(
                    plugin = plugin,
                    featured = plugin.id in catalog.featuredPluginIds,
                    onSendPrompt = onSendPrompt
                )
            }
        }
        item { PanelBottomSpacer() }
    }
}

@Composable
private fun AutomationPanel(
    modifier: Modifier = Modifier,
    catalog: AutomationCatalogState,
    onLoadMoreApps: () -> Unit,
    onLoadMoreMcp: () -> Unit
) {
    var selectedTabName by rememberSaveable { mutableStateOf(AutomationTab.Apps.name) }
    val selectedTab = runCatching { AutomationTab.valueOf(selectedTabName) }.getOrDefault(AutomationTab.Apps)
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AutomationTabs(
            selected = selectedTab,
            onSelect = { selectedTabName = it.name }
        )
        when (selectedTab) {
            AutomationTab.Apps -> AppsPanel(modifier = Modifier.weight(1f), catalog = catalog, onLoadMoreApps = onLoadMoreApps)
            AutomationTab.Mcp -> McpPanel(modifier = Modifier.weight(1f), catalog = catalog, onLoadMoreMcp = onLoadMoreMcp)
            AutomationTab.Hooks -> HooksPanel(modifier = Modifier.weight(1f), catalog = catalog)
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun AutomationTabs(
    selected: AutomationTab,
    onSelect: (AutomationTab) -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AutomationTab.entries.forEach { tab ->
            WorkflowTabChip(label = tab.label(), selected = selected == tab, onClick = { onSelect(tab) })
        }
    }
}

@Composable
private fun AppsPanel(
    modifier: Modifier = Modifier,
    catalog: AutomationCatalogState,
    onLoadMoreApps: () -> Unit
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            CatalogStatus(
                loading = catalog.isLoadingApps,
                stale = catalog.isStale,
                error = catalog.appsError,
                empty = catalog.apps.isEmpty(),
                emptyText = "暂无可用 App"
            )
        }
        items(catalog.apps, key = { "app-${it.id}" }) { app ->
            AppCard(app)
        }
        if (catalog.nextAppsCursor != null) {
            item {
                TextButton(
                    enabled = !catalog.isLoadingApps,
                    onClick = onLoadMoreApps,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (catalog.isLoadingApps) "加载中..." else "加载更多 App")
                }
            }
        }
        item { PanelBottomSpacer() }
    }
}

@Composable
private fun McpPanel(
    modifier: Modifier = Modifier,
    catalog: AutomationCatalogState,
    onLoadMoreMcp: () -> Unit
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            CatalogStatus(
                loading = catalog.isLoadingMcp,
                stale = catalog.isStale,
                error = catalog.mcpError,
                empty = catalog.mcpServers.isEmpty(),
                emptyText = "暂无 MCP 服务"
            )
        }
        items(catalog.mcpServers, key = { "mcp-${it.name}" }) { server ->
            McpCard(server)
        }
        if (catalog.nextMcpCursor != null) {
            item {
                TextButton(
                    enabled = !catalog.isLoadingMcp,
                    onClick = onLoadMoreMcp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (catalog.isLoadingMcp) "加载中..." else "加载更多 MCP")
                }
            }
        }
        item { PanelBottomSpacer() }
    }
}

@Composable
private fun HooksPanel(modifier: Modifier = Modifier, catalog: AutomationCatalogState) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            CatalogStatus(
                loading = catalog.isLoadingHooks,
                stale = catalog.isStale,
                error = catalog.hooksError,
                empty = catalog.hookGroups.all { it.hooks.isEmpty() },
                emptyText = "暂无 Hook"
            )
        }
        catalog.hookGroups.forEach { group ->
            item(key = "hook-group-${group.cwd}") {
                GroupHeader(title = group.cwd.ifBlank { "当前工作区" })
            }
            group.warnings.forEachIndexed { index, warning ->
                item(key = "hook-warning-${group.cwd}-$index") {
                    WorkflowInlineNotice(warning)
                }
            }
            group.errors.forEachIndexed { index, error ->
                item(key = "hook-error-${group.cwd}-$index") {
                    WorkflowInlineNotice(error)
                }
            }
            items(group.hooks, key = { "hook-${group.cwd}-${it.key}" }) { hook ->
                HookCard(hook)
            }
        }
        item { PanelBottomSpacer() }
    }
}

@Composable
private fun SkillCard(
    skill: SkillItem,
    onSendPrompt: (String) -> Unit
) {
    val clipboard = LocalClipboardManager.current
    WorkflowCard {
        CardHeader(title = skill.displayName, subtitle = skill.name)
        Text(
            skill.description.ifBlank { "无说明" },
            color = WorkflowTextSecondary,
            style = MaterialTheme.typography.bodySmall
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            WorkflowChip(if (skill.enabled) "启用" else "停用")
            skill.scope?.let { WorkflowChip(it) }
            skill.path?.let { WorkflowChip(it, monospace = true) }
        }
        skill.defaultPrompt?.takeIf { it.isNotBlank() }?.let { prompt ->
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onSendPrompt(prompt) }) {
                    Text("发送提示")
                }
                TextButton(onClick = { clipboard.setText(AnnotatedString(prompt)) }) {
                    Text("复制提示")
                }
            }
        }
    }
}

@Composable
private fun PluginCard(
    plugin: PluginItem,
    featured: Boolean,
    onSendPrompt: (String) -> Unit
) {
    val clipboard = LocalClipboardManager.current
    WorkflowCard {
        CardHeader(title = plugin.displayName, subtitle = plugin.id)
        Text(
            plugin.description.ifBlank { "无说明" },
            color = WorkflowTextSecondary,
            style = MaterialTheme.typography.bodySmall
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            WorkflowChip(if (plugin.installed) "已安装" else "未安装")
            WorkflowChip(if (plugin.enabled) "启用" else "停用")
            if (featured) WorkflowChip("精选")
            plugin.availability?.let { WorkflowChip(it) }
            plugin.source?.let { WorkflowChip(it) }
        }
        if (plugin.capabilities.isNotEmpty()) {
            Text(plugin.capabilities.joinToString(" · "), color = WorkflowTextSecondary, style = MaterialTheme.typography.labelMedium)
        }
        plugin.defaultPrompts.firstOrNull()?.let { prompt ->
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onSendPrompt(prompt) }) {
                    Text("发送提示")
                }
                TextButton(onClick = { clipboard.setText(AnnotatedString(prompt)) }) {
                    Text("复制提示")
                }
            }
        }
    }
}

@Composable
private fun AppCard(app: AutomationAppItem) {
    WorkflowCard {
        CardHeader(title = app.name, subtitle = app.id)
        app.description?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = WorkflowTextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            WorkflowChip(if (app.isEnabled) "启用" else "停用")
            WorkflowChip(if (app.isAccessible) "可访问" else "不可访问")
            app.pluginDisplayNames.forEach { WorkflowChip(it) }
        }
        app.installUrl?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = WorkflowTextSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun McpCard(server: McpServerItem) {
    WorkflowCard {
        CardHeader(title = server.name, subtitle = server.authStatus)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            WorkflowChip("工具 ${server.toolCount}")
            WorkflowChip("资源 ${server.resourceCount}")
            WorkflowChip("模板 ${server.resourceTemplateCount}")
            server.startupStatus?.let { WorkflowChip(it) }
        }
        server.error?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = WorkflowWarning, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun HookCard(hook: HookItem) {
    WorkflowCard {
        CardHeader(title = hook.key, subtitle = hook.eventName)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            WorkflowChip(if (hook.enabled) "启用" else "停用")
            WorkflowChip(if (hook.isManaged) "托管" else "本地")
            hook.handlerType?.let { WorkflowChip(it) }
            hook.trustStatus?.let { WorkflowChip(it) }
            hook.matcher?.let { WorkflowChip("匹配 $it") }
        }
        hook.command?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = WorkflowTextPrimary, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
        }
        hook.sourcePath?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = WorkflowTextSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun WorkflowCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = WorkflowCardBackground,
        border = BorderStroke(1.dp, WorkflowBorder)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
    }
}

@Composable
private fun CardHeader(title: String, subtitle: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            title,
            color = WorkflowTextPrimary,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        subtitle?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = WorkflowTextSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun GroupHeader(title: String, subtitle: String? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(title, color = WorkflowTextPrimary, style = MaterialTheme.typography.labelLarge)
        subtitle?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = WorkflowTextSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun CatalogStatus(
    loading: Boolean,
    stale: Boolean,
    error: String?,
    empty: Boolean,
    emptyText: String
) {
    when {
        loading -> WorkflowInlineNotice("正在同步...")
        error != null -> WorkflowInlineNotice(error)
        stale -> WorkflowInlineNotice("本地目录已变化，请刷新。")
        empty -> WorkflowInlineNotice(emptyText)
    }
}

@Composable
private fun WorkflowInlineNotice(text: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFFFFF8EC),
        border = BorderStroke(1.dp, Color(0xFFF2D6A2))
    ) {
        Text(
            text,
            modifier = Modifier.padding(10.dp),
            color = WorkflowTextSecondary,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun WorkflowTabChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .heightIn(min = 34.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) WorkflowAccent else WorkflowMuted,
        border = BorderStroke(1.dp, if (selected) WorkflowAccent else WorkflowBorder)
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            color = if (selected) Color.White else WorkflowTextSecondary,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun WorkflowChip(label: String, monospace: Boolean = false) {
    Surface(shape = CircleShape, color = WorkflowMuted, border = BorderStroke(1.dp, WorkflowBorder)) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            color = WorkflowTextSecondary,
            style = if (monospace) {
                MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace)
            } else {
                MaterialTheme.typography.labelSmall
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PanelBottomSpacer() {
    Text("", modifier = Modifier.heightIn(min = 8.dp))
}

private fun WorkflowSection.title(): String = when (this) {
    WorkflowSection.Chat -> "对话"
    WorkflowSection.Skills -> "技能"
    WorkflowSection.Plugins -> "插件"
    WorkflowSection.Automation -> "自动化"
}

private fun AutomationTab.label(): String = when (this) {
    AutomationTab.Apps -> "Apps"
    AutomationTab.Mcp -> "MCP"
    AutomationTab.Hooks -> "Hooks"
}
