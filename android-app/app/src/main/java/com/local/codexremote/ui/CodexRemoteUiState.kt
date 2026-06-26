package com.local.codexremote.ui

import com.local.codexremote.data.ChatMessage
import com.local.codexremote.data.PairingPayload
import com.local.codexremote.data.ThreadSummary
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64

private const val ThreadListSortKey = "updated_at"
private const val ThreadListSortDirection = "desc"
private const val DefaultModelId = "gpt-5.5"

data class CodexRemoteUiState(
    val connected: Boolean = false,
    val secure: Boolean = false,
    val connectionPhase: ConnectionPhase = ConnectionPhase.NoPairing,
    val hasSavedPairing: Boolean = false,
    val reconnectAttempt: Int = 0,
    val reconnectMessage: String? = null,
    val status: String = "未连接",
    val pairingText: String = "",
    val hostDisplayName: String = "linux-codex",
    val projectName: String = "workspace",
    val modelLabel: String = "5.5 超高",
    val permissionLabel: String = "默认权限",
    val availableModels: List<RuntimeModelOption> = listOf(RuntimeModelOption(DefaultModelId, "5.5 超高")),
    val selectedModelId: String = DefaultModelId,
    val selectedAccessMode: AccessMode = AccessMode.OnRequest,
    val selectedReasoningEffort: ReasoningEffort = ReasoningEffort.Medium,
    val fastModeEnabled: Boolean = false,
    val selectedTheme: AppTheme = AppTheme.Default,
    val selectedProjectCwd: String? = null,
    val planModeEnabled: Boolean = false,
    val goalModeEnabled: Boolean = false,
    val projectWorkspaces: List<ProjectWorkspace> = emptyList(),
    val showArchivedThreads: Boolean = false,
    val archivedThreadIds: Set<String> = emptySet(),
    val isLoadingModels: Boolean = false,
    val modelLoadError: String? = null,
    val activeThreadId: String? = null,
    val threads: List<ThreadSummary> = emptyList(),
    val messages: List<ChatMessage> = emptyList(),
    val isGenerating: Boolean = false,
    val isLoadingThreads: Boolean = false,
    val isLoadingHistory: Boolean = false,
    val threadSearchTerm: String = "",
    val nextThreadCursor: String? = null,
    val recoverableError: RecoverableError? = null,
    val pendingPromptAfterThreadLoss: String? = null,
    val activePlan: ActiveTurnPlan? = null,
    val activeGoal: ThreadGoalUi? = null,
    val projectPathSuggestions: FileDirectoryUi? = null,
    val projectPathSuggestionPath: String? = null,
    val projectPathSuggestionPrefix: String = "",
    val isLoadingProjectPathSuggestions: Boolean = false,
    val projectPathSuggestionError: String? = null,
    val isValidatingProjectPath: Boolean = false,
    val projectPathValidationError: String? = null,
    val pendingAttachments: List<PendingAttachmentUi> = emptyList(),
    val attachmentUploadError: String? = null,
    val pendingApproval: PendingApproval? = null,
    val pendingInteraction: PendingInteraction? = null,
    val turnDiff: TurnDiffSummary? = null,
    val runtimeNotices: List<RuntimeNotice> = emptyList(),
    val workflowSection: WorkflowSection = WorkflowSection.Chat,
    val skillsCatalog: SkillsCatalogState = SkillsCatalogState(),
    val pluginsCatalog: PluginCatalogState = PluginCatalogState(),
    val automationCatalog: AutomationCatalogState = AutomationCatalogState(),
    val isSoftSyncing: Boolean = false,
    val lastSyncedAtMs: Long? = null,
    val editDraftRequest: EditDraftRequest? = null,
    val sendBlockedReason: String? = null
) {
    val activeThreadTitle: String
        get() = threads.firstOrNull { it.id == activeThreadId }?.displayTitle() ?: projectName

    val shouldShowPairing: Boolean
        get() = !secure && !hasSavedPairing

    val visibleThreads: List<ThreadSummary>
        get() = filterThreadsForArchiveState(threads, archivedThreadIds, showArchivedThreads)

    val composerModelSettingsLabel: String
        get() {
            val cleanModel = modelLabel.ifBlank { selectedModelId }
            val effortLabel = selectedReasoningEffort.label
            val modelWithEffort = if (cleanModel.contains(effortLabel, ignoreCase = true)) {
                cleanModel
            } else {
                "$cleanModel / $effortLabel"
            }
            return if (fastModeEnabled && !modelWithEffort.contains("Fast", ignoreCase = true)) {
                "$modelWithEffort / Fast"
            } else {
                modelWithEffort
            }
        }

    val composerControlLabels: ComposerControlLabels
        get() {
            val effortLabel = selectedReasoningEffort.label
            val model = modelLabel
                .ifBlank { selectedModelId }
                .withoutTrailingLabel(effortLabel)
                .withoutTrailingLabel("Fast")
                .ifBlank { selectedModelId }
            val reasoning = if (fastModeEnabled) "$effortLabel / Fast" else effortLabel
            return ComposerControlLabels(
                model = model,
                reasoning = reasoning,
                access = selectedAccessMode.label
            )
        }

    val emptyConversationPrompt: EmptyConversationPrompt
        get() = if (activeThreadId == null) {
            EmptyConversationPrompt(
                title = "今天想让 Codex 做什么？",
                subtitle = "当前项目 $projectName 已同步，可以直接继续会话、检查代码，或让 Codex 先整理项目状态。",
                quickActions = listOf(
                    EmptyConversationQuickAction(
                        label = "总结项目",
                        prompt = "帮我总结当前项目的结构、关键模块和最近状态。"
                    ),
                    EmptyConversationQuickAction(
                        label = "检查变更",
                        prompt = "检查当前工作区的改动并指出需要注意的问题。"
                    ),
                    EmptyConversationQuickAction(
                        label = "继续上次任务",
                        prompt = "继续上次任务，先概括当前上下文和下一步。"
                    )
                )
            )
        } else {
            EmptyConversationPrompt(
                title = "这个会话还没有可显示的历史",
                subtitle = "等待 Codex 同步这个会话，或从侧边栏切换到其他会话。",
                quickActions = emptyList()
            )
        }

    val drawerHostStatusLine: String
        get() = when {
            secure && isSoftSyncing -> "同步中 · 端到端加密"
            secure -> "已同步 · 端到端加密"
            else -> status.ifBlank { "未连接" }
        }

    val timelineEntries: List<TimelineEntry>
        get() = buildTimelineEntries(this)
}

data class EditDraftRequest(
    val id: Long,
    val text: String
)

data class ComposerControlLabels(
    val model: String,
    val reasoning: String,
    val access: String
)

data class EmptyConversationPrompt(
    val title: String,
    val subtitle: String,
    val quickActions: List<EmptyConversationQuickAction>
)

data class EmptyConversationQuickAction(
    val label: String,
    val prompt: String
)

data class PairingLoginPresentation(
    val brandTitle: String,
    val brandSubtitle: String,
    val securityPill: String,
    val heroTitle: String,
    val heroSubtitle: String,
    val primaryTitle: String,
    val primaryDescription: String,
    val primaryActionLabel: String,
    val backupTitle: String,
    val backupDescription: String,
    val backupActionLabel: String,
    val showJsonInput: Boolean,
    val canConnectWithJson: Boolean,
    val statusTitle: String,
    val statusDetail: String,
    val warningText: String?
)

enum class ProjectWorkspaceSource {
    ThreadHistory,
    Skills,
    Hooks
}

data class ProjectWorkspace(
    val cwd: String,
    val name: String,
    val source: ProjectWorkspaceSource,
    val threadCount: Int = 0
)

enum class ConnectionPhase {
    NoPairing,
    Connecting,
    Handshaking,
    Ready,
    Reconnecting,
    Disconnected,
    Fatal
}

enum class AccessMode(
    val label: String,
    val approvalPolicy: String,
    val sandboxMode: String
) {
    OnRequest("需要确认", "on-request", "workspace-write"),
    FullAccess("完全访问", "never", "danger-full-access")
}

@Serializable
enum class ReasoningEffort(
    val wireName: String,
    val label: String
) {
    Low("low", "低"),
    Medium("medium", "中"),
    High("high", "高"),
    XHigh("xhigh", "超高");

    companion object {
        fun fromWireName(value: String?): ReasoningEffort? =
            entries.firstOrNull { it.wireName.equals(value?.trim(), ignoreCase = true) }
    }
}

val DefaultReasoningEfforts: List<ReasoningEffort> = listOf(
    ReasoningEffort.Low,
    ReasoningEffort.Medium,
    ReasoningEffort.High,
    ReasoningEffort.XHigh
)

@Serializable
enum class AppTheme(
    val label: String
) {
    Default("默认"),
    DarkComfort("深色护眼"),
    HighContrast("高对比")
}

enum class RecoverableErrorKind {
    ThreadNotFound,
    TurnFailed,
    CompactFailed,
    ThreadClosed,
    TransportLost
}

enum class RecoveryAction {
    StartNewWithPrompt,
    ArchiveThread,
    RefreshThreads,
    Dismiss
}

data class RecoveryActionUi(
    val action: RecoveryAction,
    val label: String
)

data class RecoverableError(
    val kind: RecoverableErrorKind,
    val title: String,
    val detail: String,
    val actions: List<RecoveryActionUi> = emptyList(),
    val threadId: String? = null,
    val turnId: String? = null,
    val primaryActionLabel: String = actions.firstOrNull()?.label ?: "忽略",
    val secondaryActionLabel: String? = actions.drop(1).firstOrNull()?.label
)

enum class TurnPlanStatus {
    Pending,
    InProgress,
    Completed
}

data class TurnPlanStepUi(
    val step: String,
    val status: TurnPlanStatus
)

data class ActiveTurnPlan(
    val threadId: String?,
    val turnId: String?,
    val explanation: String?,
    val steps: List<TurnPlanStepUi>
)

enum class PendingApprovalKind {
    CommandExecution,
    FileChange,
    Permissions,
    LegacyCommand,
    LegacyPatch,
    Unsupported
}

enum class ApprovalDecision {
    Accept,
    AcceptForSession,
    Decline,
    Cancel
}

data class PendingApproval(
    val requestId: Int,
    val method: String,
    val kind: PendingApprovalKind,
    val title: String,
    val body: String,
    val threadId: String? = null,
    val turnId: String? = null,
    val itemId: String? = null,
    val requestParams: JsonObject = buildJsonObject {}
)

enum class PendingInteractionKind {
    ToolUserInput,
    McpElicitation,
    Unsupported
}

enum class InteractionAction {
    Accept,
    Cancel
}

data class PendingInteractionOption(
    val label: String,
    val description: String? = null
)

data class PendingInteractionQuestion(
    val id: String,
    val header: String?,
    val question: String,
    val isOther: Boolean = false,
    val isSecret: Boolean = false,
    val options: List<PendingInteractionOption> = emptyList()
)

data class PendingInteraction(
    val requestId: Int,
    val method: String,
    val kind: PendingInteractionKind,
    val title: String,
    val body: String,
    val threadId: String? = null,
    val turnId: String? = null,
    val itemId: String? = null,
    val questions: List<PendingInteractionQuestion> = emptyList(),
    val serverName: String? = null,
    val url: String? = null,
    val requestParams: JsonObject = buildJsonObject {}
)

data class TurnDiffSummary(
    val threadId: String?,
    val turnId: String?,
    val diff: String
)

data class CommandOutputDelta(
    val threadId: String?,
    val turnId: String?,
    val itemId: String?,
    val delta: String
)

data class ReasoningDelta(
    val threadId: String?,
    val turnId: String?,
    val itemId: String?,
    val delta: String
)

enum class RuntimeNoticeKind {
    Warning,
    GuardianWarning,
    ModelRerouted,
    UnsupportedRequest,
    ContextCompacted
}

data class RuntimeNotice(
    val kind: RuntimeNoticeKind,
    val threadId: String?,
    val title: String,
    val body: String
)

enum class MessageTone {
    Neutral,
    Warning,
    Error
}

data class RuntimeFailure(
    val kind: RecoverableErrorKind,
    val threadId: String?,
    val turnId: String?,
    val detail: String,
    val willRetry: Boolean = false
)

enum class TimelineTextRole {
    User,
    Assistant
}

sealed interface TimelineEntry {
    val key: String
    val threadId: String?
    val turnId: String?
    val itemId: String?
    val createdAtMs: Long?

    data class Text(
        val role: TimelineTextRole,
        val text: String,
        override val key: String,
        override val threadId: String? = null,
        override val turnId: String? = null,
        override val itemId: String? = null,
        override val createdAtMs: Long? = null
    ) : TimelineEntry

    data class Reasoning(
        val body: String,
        override val key: String,
        override val threadId: String? = null,
        override val turnId: String? = null,
        override val itemId: String? = null,
        override val createdAtMs: Long? = null
    ) : TimelineEntry

    data class CommandExecution(
        val commandLine: String,
        val body: String,
        override val key: String,
        override val threadId: String? = null,
        override val turnId: String? = null,
        override val itemId: String? = null,
        override val createdAtMs: Long? = null
    ) : TimelineEntry

    data class FileChange(
        val body: String,
        override val key: String,
        override val threadId: String? = null,
        override val turnId: String? = null,
        override val itemId: String? = null,
        override val createdAtMs: Long? = null
    ) : TimelineEntry

    data class Diff(
        val diff: TurnDiffSummary,
        override val key: String = "diff-${diff.threadId}-${diff.turnId}-${diff.diff.hashCode()}"
    ) : TimelineEntry {
        override val threadId: String? = diff.threadId
        override val turnId: String? = diff.turnId
        override val itemId: String? = null
        override val createdAtMs: Long? = null
    }

    data class ApprovalRequest(
        val approval: PendingApproval,
        override val key: String = "approval-${approval.requestId}"
    ) : TimelineEntry {
        override val threadId: String? = approval.threadId
        override val turnId: String? = approval.turnId
        override val itemId: String? = approval.itemId
        override val createdAtMs: Long? = null
    }

    data class InputRequest(
        val interaction: PendingInteraction,
        override val key: String = "interaction-${interaction.requestId}"
    ) : TimelineEntry {
        override val threadId: String? = interaction.threadId
        override val turnId: String? = interaction.turnId
        override val itemId: String? = interaction.itemId
        override val createdAtMs: Long? = null
    }

    data class PlanReview(
        val plan: ActiveTurnPlan,
        override val key: String = "plan-${plan.threadId}-${plan.turnId}-${plan.steps.hashCode()}"
    ) : TimelineEntry {
        override val threadId: String? = plan.threadId
        override val turnId: String? = plan.turnId
        override val itemId: String? = null
        override val createdAtMs: Long? = null
    }

    data class RuntimeNotice(
        val notice: com.local.codexremote.ui.RuntimeNotice,
        override val key: String
    ) : TimelineEntry {
        override val threadId: String? = notice.threadId
        override val turnId: String? = null
        override val itemId: String? = null
        override val createdAtMs: Long? = null
    }

    data class SystemNotice(
        val title: String,
        val body: String,
        override val key: String,
        override val threadId: String? = null,
        override val turnId: String? = null,
        override val itemId: String? = null,
        override val createdAtMs: Long? = null
    ) : TimelineEntry

    data class Failure(
        val title: String,
        val detail: String,
        val actions: List<RecoveryActionUi> = emptyList(),
        override val key: String,
        override val threadId: String? = null,
        override val turnId: String? = null,
        override val itemId: String? = null,
        override val createdAtMs: Long? = null
    ) : TimelineEntry
}

enum class ThreadGoalStatus(val wireName: String) {
    Active("active"),
    Paused("paused"),
    Blocked("blocked"),
    UsageLimited("usageLimited"),
    BudgetLimited("budgetLimited"),
    InProgress("inProgress"),
    Complete("complete")
}

data class ThreadGoalUi(
    val objective: String,
    val status: ThreadGoalStatus,
    val tokenBudget: Int? = null,
    val tokensUsed: Int? = null,
    val timeUsedSeconds: Int? = null
)

enum class PlanReviewAction {
    ExecuteTask,
    ContinuePlanning,
    Dismiss
}

data class PlanReviewActionUi(
    val action: PlanReviewAction,
    val label: String,
    val description: String,
    val recommended: Boolean = false
)

enum class AttachmentUploadStatus {
    Uploading,
    Ready,
    Failed
}

data class PendingAttachmentUi(
    val id: String,
    val displayName: String,
    val mimeType: String?,
    val hostPath: String,
    val status: AttachmentUploadStatus,
    val error: String? = null
) {
    val isImage: Boolean
        get() = mimeType?.startsWith("image/", ignoreCase = true) == true

    val isReady: Boolean
        get() = status == AttachmentUploadStatus.Ready
}

data class FileEntryUi(
    val name: String,
    val isDirectory: Boolean,
    val isFile: Boolean
)

data class FileDirectoryUi(
    val path: String,
    val entries: List<FileEntryUi>
)

data class ProjectPathSuggestionUi(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val isFile: Boolean
)

fun ProjectPathSuggestionUi.pathForManualSelection(): String =
    if (isDirectory) "${path.trimEnd('/')}/" else path

data class ProjectPathCompletionRequest(
    val directoryPath: String,
    val prefix: String
)

data class FileMetadataUi(
    val path: String,
    val isDirectory: Boolean,
    val isFile: Boolean
) {
    val isUsableCwd: Boolean
        get() = isDirectory
}

data class FilePreviewUi(
    val path: String,
    val text: String
)

data class DesktopStatusUi(
    val model: String? = null,
    val approvalPolicy: String? = null,
    val accountType: String? = null,
    val accountEmail: String? = null,
    val rateLimitSummary: String? = null
)

data class McpResourceContentUi(
    val uri: String,
    val mimeType: String? = null,
    val text: String
)

data class McpResourcePreviewUi(
    val contents: List<McpResourceContentUi>
)

data class DesktopTopOptionsUi(
    val projectLabel: String,
    val providerLabel: String,
    val localModeLabel: String,
    val planLabel: String,
    val goalLabel: String,
    val pluginLabel: String,
    val permissionLabel: String
)

@Serializable
data class RuntimeModelOption(
    val id: String,
    val label: String = id,
    val hidden: Boolean = false,
    val supportedReasoningEfforts: List<ReasoningEffort> = DefaultReasoningEfforts
)

@Serializable
data class SavedPairingProfile(
    val relay: String,
    val sessionId: String,
    val hostDeviceId: String,
    val hostIdentityPublicKey: String,
    val displayName: String = "",
    val relayHostOverrides: Map<String, String> = emptyMap()
) {
    fun isUsableForReconnect(): Boolean =
        relay.isNotBlank() && sessionId.isNotBlank() && hostDeviceId.isNotBlank() && hostIdentityPublicKey.isNotBlank()

    fun toConnectionProfile(): PairingConnectionProfile = PairingConnectionProfile(
        relay = relay,
        sessionId = sessionId,
        hostDeviceId = hostDeviceId,
        hostIdentityPublicKey = hostIdentityPublicKey,
        expiresAt = Long.MAX_VALUE,
        displayName = displayName,
        relayHostOverrides = relayHostOverrides
    )
}

data class PairingConnectionProfile(
    val relay: String,
    val sessionId: String,
    val hostDeviceId: String,
    val hostIdentityPublicKey: String,
    val expiresAt: Long,
    val displayName: String = "",
    val relayHostOverrides: Map<String, String> = emptyMap()
) {
    fun isUsableForNewPairing(nowMillis: Long = System.currentTimeMillis()): Boolean = expiresAt > nowMillis

    fun isUsableForReconnect(): Boolean =
        relay.isNotBlank() && sessionId.isNotBlank() && hostDeviceId.isNotBlank() && hostIdentityPublicKey.isNotBlank()

    fun toSavedPairingProfile(): SavedPairingProfile = SavedPairingProfile(
        relay = relay,
        sessionId = sessionId,
        hostDeviceId = hostDeviceId,
        hostIdentityPublicKey = hostIdentityPublicKey,
        displayName = displayName,
        relayHostOverrides = relayHostOverrides
    )

    fun toPairingPayload(expiresAtOverride: Long = expiresAt): PairingPayload = PairingPayload(
        v = 1,
        relay = relay,
        sessionId = sessionId,
        hostDeviceId = hostDeviceId,
        hostIdentityPublicKey = hostIdentityPublicKey,
        expiresAt = expiresAtOverride,
        displayName = displayName,
        relayHostOverrides = relayHostOverrides
    )
}

fun PairingPayload.toConnectionProfile(): PairingConnectionProfile = PairingConnectionProfile(
    relay = relay,
    sessionId = sessionId,
    hostDeviceId = hostDeviceId,
    hostIdentityPublicKey = hostIdentityPublicKey,
    expiresAt = expiresAt,
    displayName = displayName,
    relayHostOverrides = relayHostOverrides
)

sealed class RelayDisconnect {
    abstract val displayMessage: String

    data class Close(val code: Int, val reason: String) : RelayDisconnect() {
        override val displayMessage: String = reason.ifBlank { "连接已关闭 ($code)" }
    }

    data class Failure(val message: String) : RelayDisconnect() {
        override val displayMessage: String = message.ifBlank { "连接失败" }
    }

    val requiresFreshPairing: Boolean
        get() = this is Close && code == 4003
}

fun CodexRemoteUiState.afterRelayClosed(disconnect: RelayDisconnect): CodexRemoteUiState {
    if (disconnect.requiresFreshPairing) {
        return copy(
            connected = false,
            secure = false,
            connectionPhase = ConnectionPhase.Fatal,
            hasSavedPairing = false,
            reconnectAttempt = 0,
            reconnectMessage = null,
            status = "连接已被替换，请重新扫码",
            isGenerating = false
        )
    }
    return if (hasSavedPairing) {
        copy(
            connected = false,
            secure = false,
            connectionPhase = ConnectionPhase.Reconnecting,
            reconnectAttempt = reconnectAttempt + 1,
            reconnectMessage = disconnect.displayMessage,
            status = "连接中断，正在重连",
            isGenerating = false
        )
    } else {
        copy(
            connected = false,
            secure = false,
            connectionPhase = ConnectionPhase.Disconnected,
            reconnectMessage = disconnect.displayMessage,
            status = disconnect.displayMessage,
            isGenerating = false
        )
    }
}

fun CodexRemoteUiState.afterSecureChannelReady(
    savedDisplayName: String?,
    softReconnect: Boolean,
    nowMillis: Long = System.currentTimeMillis()
): CodexRemoteUiState = copy(
    connected = true,
    secure = true,
    connectionPhase = ConnectionPhase.Ready,
    hasSavedPairing = true,
    reconnectAttempt = 0,
    reconnectMessage = null,
    isLoadingHistory = false,
    isSoftSyncing = softReconnect && activeThreadId != null,
    lastSyncedAtMs = nowMillis,
    sendBlockedReason = null,
    status = if (softReconnect) "已重连，后台同步中" else "已加密连接",
    hostDisplayName = savedDisplayName?.ifBlank { hostDisplayName } ?: hostDisplayName
)

fun CodexRemoteUiState.shouldReadActiveThreadAfterReconnect(): Boolean =
    activeThreadId != null && messages.isEmpty()

fun CodexRemoteUiState.shouldSyncActiveThreadAfterReconnect(): Boolean =
    activeThreadId != null

fun CodexRemoteUiState.withPromptSendBlocked(): CodexRemoteUiState = copy(
    sendBlockedReason = "正在重连，稍后再发送",
    status = "正在重连，稍后再发送"
)

fun isCurrentConnectionGeneration(eventGeneration: Int, currentGeneration: Int): Boolean =
    eventGeneration == currentGeneration

fun CodexRemoteUiState.forNewConversation(): CodexRemoteUiState = copy(
    activeThreadId = null,
    messages = emptyList(),
    isGenerating = false,
    isLoadingHistory = false,
    recoverableError = null,
    pendingPromptAfterThreadLoss = null,
    activePlan = null,
    activeGoal = null,
    pendingApproval = null,
    pendingInteraction = null,
    turnDiff = null,
    status = if (secure) "已加密连接" else status
)

fun isThreadNotFoundError(message: String): Boolean =
    message.contains("thread not found", ignoreCase = true)

fun CodexRemoteUiState.withThreadNotFoundRecovery(prompt: String?, message: String): CodexRemoteUiState =
    withRecoverableFailure(
        kind = RecoverableErrorKind.ThreadNotFound,
        threadId = null,
        turnId = null,
        prompt = prompt,
        detail = message
    ).copy(activeThreadId = null)

fun CodexRemoteUiState.withRecoverableFailure(
    kind: RecoverableErrorKind,
    threadId: String?,
    turnId: String?,
    prompt: String?,
    detail: String
): CodexRemoteUiState {
    val cleanPrompt = prompt?.trim()?.takeIf { it.isNotEmpty() }
    val actions = buildList {
        if (cleanPrompt != null) add(RecoveryActionUi(RecoveryAction.StartNewWithPrompt, "新对话继续"))
        if (threadId != null) add(RecoveryActionUi(RecoveryAction.ArchiveThread, "结束会话"))
        add(RecoveryActionUi(RecoveryAction.RefreshThreads, "刷新会话"))
        add(RecoveryActionUi(RecoveryAction.Dismiss, "忽略"))
    }
    return copy(
        isGenerating = false,
        isLoadingHistory = false,
        activePlan = null,
        pendingApproval = null,
        pendingInteraction = null,
        pendingPromptAfterThreadLoss = cleanPrompt,
        recoverableError = RecoverableError(
            kind = kind,
            title = kind.recoveryTitle(),
            detail = detail,
            actions = actions,
            threadId = threadId,
            turnId = turnId
        ),
        status = kind.recoveryStatus()
    )
}

private fun RecoverableErrorKind.recoveryTitle(): String = when (this) {
    RecoverableErrorKind.ThreadNotFound -> "桌面端会话已不可用"
    RecoverableErrorKind.TurnFailed -> "任务失败"
    RecoverableErrorKind.CompactFailed -> "上下文压缩失败"
    RecoverableErrorKind.ThreadClosed -> "桌面端已结束会话"
    RecoverableErrorKind.TransportLost -> "Codex 服务已断开"
}

private fun RecoverableErrorKind.recoveryStatus(): String = when (this) {
    RecoverableErrorKind.ThreadNotFound -> "会话不存在，可新建对话继续"
    RecoverableErrorKind.TurnFailed -> "任务失败，可选择恢复方式"
    RecoverableErrorKind.CompactFailed -> "压缩失败，可选择恢复方式"
    RecoverableErrorKind.ThreadClosed -> "会话已结束，可选择恢复方式"
    RecoverableErrorKind.TransportLost -> "Codex 服务断开，可选择恢复方式"
}

fun RuntimeNotice.tone(): MessageTone = when (kind) {
    RuntimeNoticeKind.Warning,
    RuntimeNoticeKind.GuardianWarning,
    RuntimeNoticeKind.ModelRerouted,
    RuntimeNoticeKind.ContextCompacted -> MessageTone.Warning
    RuntimeNoticeKind.UnsupportedRequest -> MessageTone.Error
}

fun RecoverableError.tone(): MessageTone = MessageTone.Error

fun planReviewPromptTitle(): String = "Plan 模式需要选择"

fun defaultPlanReviewActions(): List<PlanReviewActionUi> = listOf(
    PlanReviewActionUi(
        action = PlanReviewAction.ExecuteTask,
        label = "执行任务",
        description = "退出 Plan 模式并开始按计划修改。",
        recommended = true
    ),
    PlanReviewActionUi(
        action = PlanReviewAction.ContinuePlanning,
        label = "继续完善",
        description = "保持 Plan 模式，继续补充或调整计划。"
    ),
    PlanReviewActionUi(
        action = PlanReviewAction.Dismiss,
        label = "暂不执行",
        description = "关闭选择，不发送执行指令。"
    )
)

fun CodexRemoteUiState.withoutRecoverableError(): CodexRemoteUiState = copy(
    recoverableError = null,
    pendingPromptAfterThreadLoss = null
)

fun CodexRemoteUiState.withRenamedThread(threadId: String, name: String): CodexRemoteUiState {
    val clean = name.trim()
    if (clean.isEmpty()) return this
    return copy(threads = threads.map { thread ->
        if (thread.id == threadId) thread.copy(title = clean) else thread
    })
}

fun CodexRemoteUiState.withSelectedModel(modelId: String): CodexRemoteUiState {
    val clean = modelId.trim()
    if (clean.isEmpty()) return this
    val selectedModel = availableModels.firstOrNull { it.id == clean }
    val label = selectedModel?.label ?: clean
    val supportedEfforts = selectedModel?.supportedReasoningEfforts.orEmpty()
    val nextEffort = selectedReasoningEffort.takeIf { it in supportedEfforts }
        ?: supportedEfforts.firstOrNull()
        ?: ReasoningEffort.Medium
    return copy(
        selectedModelId = clean,
        modelLabel = label,
        selectedReasoningEffort = nextEffort
    )
}

fun CodexRemoteUiState.withSelectedAccessMode(accessMode: AccessMode): CodexRemoteUiState =
    copy(selectedAccessMode = accessMode, permissionLabel = accessMode.label)

fun CodexRemoteUiState.withSelectedReasoningEffort(reasoningEffort: ReasoningEffort): CodexRemoteUiState {
    val supportedEfforts = availableModels
        .firstOrNull { it.id == selectedModelId }
        ?.supportedReasoningEfforts
        .orEmpty()
    val nextEffort = if (supportedEfforts.isEmpty() || reasoningEffort in supportedEfforts) {
        reasoningEffort
    } else {
        supportedEfforts.first()
    }
    return copy(
        selectedReasoningEffort = nextEffort,
        status = "推理强度已切换为 ${nextEffort.label}"
    )
}

fun CodexRemoteUiState.withFastModeEnabled(enabled: Boolean): CodexRemoteUiState =
    copy(
        fastModeEnabled = enabled,
        status = if (enabled) "快速模式已开启" else "快速模式已关闭"
    )

fun CodexRemoteUiState.withSelectedTheme(theme: AppTheme): CodexRemoteUiState =
    copy(
        selectedTheme = theme,
        status = "主题已切换为 ${theme.label}"
    )

fun CodexRemoteUiState.withPlanModeEnabled(enabled: Boolean): CodexRemoteUiState =
    copy(
        planModeEnabled = enabled,
        status = if (enabled) "计划模式已开启" else "计划模式已关闭"
    )

fun CodexRemoteUiState.forPlanImplementation(): CodexRemoteUiState =
    copy(
        activePlan = null,
        planModeEnabled = false,
        status = "计划模式已关闭，开始执行"
    )

fun CodexRemoteUiState.forContinuedPlanning(): CodexRemoteUiState =
    copy(
        activePlan = null,
        planModeEnabled = true,
        status = "继续完善计划"
    )

fun CodexRemoteUiState.afterPendingInteractionResponse(
    action: InteractionAction,
    answers: Map<String, List<String>>
): CodexRemoteUiState {
    val selectedLabels = answers.values.flatten()
    val base = copy(pendingInteraction = null)
    return when {
        action == InteractionAction.Accept && selectedLabels.any { it.isPlanExecutionChoice() } ->
            base.forPlanImplementation()
        action == InteractionAction.Accept && selectedLabels.any { it.isContinuedPlanningChoice() } ->
            base.forContinuedPlanning()
        else -> base.copy(status = if (action == InteractionAction.Accept) "已提交输入" else "已取消输入")
    }
}

private fun String.isPlanExecutionChoice(): Boolean {
    val clean = trim()
    return (clean.contains("执行任务") ||
        clean.contains("立刻执行") ||
        clean.contains("开始执行") ||
        clean.equals("implement", ignoreCase = true)) &&
        !clean.contains("暂不") &&
        !clean.contains("不执行")
}

private fun String.isContinuedPlanningChoice(): Boolean {
    val clean = trim()
    return clean.contains("继续完善") || clean.contains("继续规划")
}

fun CodexRemoteUiState.withGoalModeEnabled(enabled: Boolean): CodexRemoteUiState =
    copy(
        goalModeEnabled = enabled,
        activeGoal = if (enabled) activeGoal else null,
        status = if (enabled) "追求目标已开启" else "追求目标已关闭"
    )

fun CodexRemoteUiState.goalForPrompt(prompt: String): ThreadGoalUi? {
    if (!goalModeEnabled) return null
    val clean = prompt.trim()
    if (clean.isEmpty()) return null
    return ThreadGoalUi(objective = clean, status = ThreadGoalStatus.InProgress)
}

fun CodexRemoteUiState.withSelectedProjectCwd(cwd: String?): CodexRemoteUiState {
    val clean = cwd.cleanPathOrNull()
    return copy(
        selectedProjectCwd = clean,
        projectName = clean?.projectNameFromCwd() ?: projectName,
        isValidatingProjectPath = false,
        projectPathValidationError = null,
        status = clean?.let { "已选择项目 ${it.projectNameFromCwd()}" } ?: status
    )
}

fun CodexRemoteUiState.currentWorkspaceCwd(): String? =
    activeThreadId
        ?.let { threadId -> threads.firstOrNull { it.id == threadId }?.cwd.cleanPathOrNull() }
        ?: selectedProjectCwd.cleanPathOrNull()
        ?: threads.firstNotNullOfOrNull { it.cwd.cleanPathOrNull() }

fun CodexRemoteUiState.selectableProjectWorkspaces(): List<ProjectWorkspace> {
    val selected = selectedProjectCwd.cleanPathOrNull()
    val selectedWorkspace = selected
        ?.takeIf { cwd -> projectWorkspaces.none { it.cwd == cwd } }
        ?.let { cwd ->
            ProjectWorkspace(
                cwd = cwd,
                name = cwd.projectNameFromCwd(),
                source = ProjectWorkspaceSource.ThreadHistory
            )
        }
    return (listOfNotNull(selectedWorkspace) + projectWorkspaces).distinctBy { it.cwd }
}

fun CodexRemoteUiState.promptForSend(prompt: String): String =
    if (!planModeEnabled) {
        prompt
    } else {
        "请先制定计划并等待确认，不要直接修改文件或执行实现步骤。用户需求：$prompt"
    }

fun CodexRemoteUiState.withoutPendingAttachment(id: String): CodexRemoteUiState =
    copy(
        pendingAttachments = pendingAttachments.filterNot { it.id == id },
        attachmentUploadError = null
    )

fun CodexRemoteUiState.withAttachmentUploadCompleted(id: String): CodexRemoteUiState =
    copy(
        pendingAttachments = pendingAttachments.map { attachment ->
            if (attachment.id == id) {
                attachment.copy(status = AttachmentUploadStatus.Ready, error = null)
            } else {
                attachment
            }
        },
        attachmentUploadError = null,
        status = if (pendingAttachments.any { it.id == id }) "附件已添加" else status
    )

fun CodexRemoteUiState.withAttachmentUploadFailed(id: String, message: String): CodexRemoteUiState =
    copy(
        pendingAttachments = pendingAttachments.map { attachment ->
            if (attachment.id == id) {
                attachment.copy(status = AttachmentUploadStatus.Failed, error = message)
            } else {
                attachment
            }
        },
        attachmentUploadError = if (pendingAttachments.any { it.id == id }) message else attachmentUploadError,
        status = if (pendingAttachments.any { it.id == id }) message else status
    )

fun CodexRemoteUiState.withModelLoadFailure(message: String): CodexRemoteUiState = copy(
    isLoadingModels = false,
    modelLoadError = message
)

fun ThreadSummary.displayTitle(): String {
    val cleanTitle = title?.trim().orEmpty()
    val cleanPreview = preview?.trim().orEmpty()
    return cleanTitle.ifEmpty { cleanPreview.ifEmpty { id.take(15) } }
}

fun ThreadSummary.displayPreviewLine(): String {
    val cleanPreview = preview?.trim().orEmpty()
    val cleanTitle = title?.trim().orEmpty()
    return cleanPreview.takeUnless { it.isBlank() || it == cleanTitle }.orEmpty()
}

fun ThreadSummary.displayMetaLine(): String {
    val cleanUpdatedAt = updatedAt?.trim().orEmpty()
    return if (cleanUpdatedAt.length >= 10 && cleanUpdatedAt[4] == '-' && cleanUpdatedAt[7] == '-') {
        cleanUpdatedAt.take(10)
    } else {
        cleanUpdatedAt.ifBlank { "最近" }
    }
}

fun buildPairingLoginPresentation(
    pairingText: String,
    status: String,
    backupExpanded: Boolean,
    cameraDenied: Boolean
): PairingLoginPresentation {
    val cleanPairingText = pairingText.trim()
    val showJsonInput = backupExpanded || cleanPairingText.isNotBlank()
    val statusTitle = when {
        cameraDenied -> "相机权限不可用"
        cleanPairingText.isNotBlank() -> "已识别配对内容"
        status.isNotBlank() && status != "未连接" -> status
        else -> "等待配对"
    }
    val statusDetail = when {
        cameraDenied -> "可以在系统设置中允许相机，或直接粘贴 Pairing JSON。"
        cleanPairingText.isNotBlank() -> "确认后将建立端到端加密连接"
        else -> "手机和 Linux bridge 需要访问同一个 relay"
    }
    return PairingLoginPresentation(
        brandTitle = "CodeRoam",
        brandSubtitle = "连接桌面 Codex",
        securityPill = if (showJsonInput) "本地优先" else "端到端加密",
        heroTitle = if (showJsonInput) "使用配对信息连接桌面端" else "把手机连接到你的 Codex 工作区",
        heroSubtitle = if (showJsonInput) {
            "适合二维码无法扫描、远程桌面转发或相机权限不可用的情况。"
        } else {
            "在 Linux 端启动 bridge 后，扫描二维码即可恢复项目、会话和工具状态。"
        },
        primaryTitle = if (showJsonInput) "仍可扫码" else "扫码配对",
        primaryDescription = if (showJsonInput) {
            "如果二维码恢复可用，扫码依然是最快路径。"
        } else {
            "打开相机，将 Linux bridge 生成的二维码放在画面中央。"
        },
        primaryActionLabel = "打开相机扫描二维码",
        backupTitle = if (showJsonInput) "Pairing JSON" else "备用方式",
        backupDescription = if (showJsonInput) {
            "粘贴 Linux bridge 输出的完整内容"
        } else {
            "二维码不可用时粘贴 Pairing JSON"
        },
        backupActionLabel = "使用粘贴内容连接",
        showJsonInput = showJsonInput,
        canConnectWithJson = cleanPairingText.isNotBlank(),
        statusTitle = statusTitle,
        statusDetail = statusDetail,
        warningText = if (cameraDenied) {
            "相机权限被拒绝。请在系统设置中允许相机，或直接使用 Pairing JSON。"
        } else {
            null
        }
    )
}

private fun String.withoutTrailingLabel(label: String): String {
    val cleanLabel = label.trim()
    if (cleanLabel.isBlank()) return trim()
    return trim()
        .replace(Regex("\\s*/\\s*${Regex.escape(cleanLabel)}\\s*$", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s+${Regex.escape(cleanLabel)}\\s*$", RegexOption.IGNORE_CASE), "")
        .trim()
}

fun buildProjectWorkspaces(
    threads: List<ThreadSummary>,
    skillsCatalog: SkillsCatalogState = SkillsCatalogState(),
    automationCatalog: AutomationCatalogState = AutomationCatalogState()
): List<ProjectWorkspace> {
    val threadCounts = threads.mapNotNull { it.cwd.cleanPathOrNull() }.groupingBy { it }.eachCount()
    val sourceByCwd = linkedMapOf<String, ProjectWorkspaceSource>()
    threadCounts.keys.forEach { sourceByCwd[it] = ProjectWorkspaceSource.ThreadHistory }
    skillsCatalog.groups.mapNotNull { it.cwd.cleanPathOrNull() }.forEach { cwd ->
        sourceByCwd.putIfAbsent(cwd, ProjectWorkspaceSource.Skills)
    }
    automationCatalog.hookGroups.mapNotNull { it.cwd.cleanPathOrNull() }.forEach { cwd ->
        sourceByCwd.putIfAbsent(cwd, ProjectWorkspaceSource.Hooks)
    }
    return sourceByCwd.map { (cwd, source) ->
        ProjectWorkspace(
            cwd = cwd,
            name = cwd.projectNameFromCwd(),
            source = source,
            threadCount = threadCounts[cwd] ?: 0
        )
    }.sortedWith(compareByDescending<ProjectWorkspace> { it.threadCount }.thenBy { it.name.lowercase() })
}

fun buildDesktopTopOptions(
    state: CodexRemoteUiState,
    desktopStatus: DesktopStatusUi? = null,
    goal: ThreadGoalUi? = null
): DesktopTopOptionsUi {
    val enabledPlugins = state.pluginsCatalog.marketplaces
        .flatMap { it.plugins }
        .count { it.installed && it.enabled }
    val approvalPolicy = desktopStatus?.approvalPolicy
    val permissionLabel = AccessMode.entries.firstOrNull { it.approvalPolicy == approvalPolicy }?.label
        ?: state.selectedAccessMode.label
    return DesktopTopOptionsUi(
        projectLabel = state.projectName,
        providerLabel = desktopStatus?.model?.takeIf { it.isNotBlank() } ?: state.selectedModelId,
        localModeLabel = "本地模式",
        planLabel = if (state.planModeEnabled || state.activePlan != null) "计划模式" else "计划关闭",
        goalLabel = if (state.goalModeEnabled || goal != null || state.activeGoal != null) "追求目标" else "目标关闭",
        pluginLabel = if (enabledPlugins > 0) "$enabledPlugins 个插件启用" else "插件",
        permissionLabel = permissionLabel
    )
}

data class ThreadListPage(
    val threads: List<ThreadSummary>,
    val nextCursor: String?
)

fun buildThreadListParams(
    limit: Int,
    cursor: String? = null,
    searchTerm: String? = null
): JsonObject = buildJsonObject {
    put("limit", limit)
    put("sortKey", ThreadListSortKey)
    put("sortDirection", ThreadListSortDirection)
    val cleanCursor = cursor?.trim().orEmpty()
    if (cleanCursor.isNotEmpty()) {
        put("cursor", cleanCursor)
    }
    val cleanSearch = searchTerm?.trim().orEmpty()
    if (cleanSearch.isNotEmpty()) {
        put("searchTerm", cleanSearch)
    }
}

fun buildModelListParams(limit: Int = 50): JsonObject = buildJsonObject {
    put("cursor", kotlinx.serialization.json.JsonNull)
    put("limit", limit)
    put("includeHidden", false)
}

fun buildThreadStartParams(
    modelId: String,
    accessMode: AccessMode,
    cwd: String? = null,
    reasoningEffort: ReasoningEffort = ReasoningEffort.Medium,
    fastModeEnabled: Boolean = false
): JsonObject = buildJsonObject {
    cwd.cleanPathOrNull()?.let { put("cwd", it) }
    putRuntimeSettings(modelId, accessMode, reasoningEffort, fastModeEnabled, includeSandboxMode = true)
}

fun buildThreadResumeParams(
    threadId: String,
    modelId: String,
    accessMode: AccessMode,
    reasoningEffort: ReasoningEffort = ReasoningEffort.Medium,
    fastModeEnabled: Boolean = false
): JsonObject = buildJsonObject {
    put("threadId", threadId)
    putRuntimeSettings(modelId, accessMode, reasoningEffort, fastModeEnabled, includeSandboxMode = true)
}

fun buildThreadForkParams(
    threadId: String,
    modelId: String,
    accessMode: AccessMode,
    reasoningEffort: ReasoningEffort = ReasoningEffort.Medium,
    fastModeEnabled: Boolean = false
): JsonObject = buildJsonObject {
    put("threadId", threadId)
    putRuntimeSettings(modelId, accessMode, reasoningEffort, fastModeEnabled, includeSandboxMode = true)
}

fun buildThreadRollbackParams(threadId: String, numTurns: Int): JsonObject = buildJsonObject {
    put("threadId", threadId)
    put("numTurns", numTurns.coerceAtLeast(1))
}

fun countRollbackTurnsFromMessage(
    messages: List<ChatMessage>,
    threadId: String,
    turnId: String
): Int? {
    val startIndex = messages.indexOfFirst { message ->
        message.threadId == threadId && message.turnId == turnId
    }
    if (startIndex < 0) return null
    return messages
        .asSequence()
        .drop(startIndex)
        .filter { it.threadId == threadId }
        .mapNotNull { it.turnId }
        .distinct()
        .count()
        .takeIf { it > 0 }
}

fun removeMessagesFromRolledBackTurns(
    messages: List<ChatMessage>,
    threadId: String,
    turnId: String
): List<ChatMessage> {
    val startIndex = messages.indexOfFirst { message ->
        message.threadId == threadId && message.turnId == turnId
    }
    if (startIndex < 0) return messages
    val rolledBackTurnIds = messages
        .asSequence()
        .drop(startIndex)
        .filter { it.threadId == threadId }
        .mapNotNull { it.turnId }
        .distinct()
        .toSet()
    return messages.filterNot { message ->
        message.threadId == threadId && message.turnId in rolledBackTurnIds
    }
}

fun buildThreadCompactParams(threadId: String): JsonObject = buildJsonObject {
    put("threadId", threadId)
}

fun buildReviewStartParams(threadId: String): JsonObject = buildJsonObject {
    put("threadId", threadId)
    put("delivery", "inline")
    put("target", buildJsonObject {
        put("type", "uncommittedChanges")
    })
}

fun buildThreadGoalSetParams(
    threadId: String,
    objective: String,
    status: ThreadGoalStatus,
    tokenBudget: Int? = null
): JsonObject = buildJsonObject {
    put("threadId", threadId)
    put("objective", objective)
    put("status", status.wireName)
    tokenBudget?.let { put("tokenBudget", it) }
}

fun buildThreadGoalGetParams(threadId: String): JsonObject = buildJsonObject {
    put("threadId", threadId)
}

fun buildThreadGoalClearParams(threadId: String): JsonObject = buildJsonObject {
    put("threadId", threadId)
}

fun buildGetMetadataParams(path: String): JsonObject = buildJsonObject {
    put("path", path.cleanPathOrNull() ?: path.trim())
}

fun buildReadDirectoryParams(path: String): JsonObject = buildJsonObject {
    put("path", path.cleanPathOrNull() ?: path.trim())
}

fun buildProjectPathCompletionRequest(inputPath: String): ProjectPathCompletionRequest {
    val raw = inputPath.trim().takeIf { it.startsWith("/") } ?: "/"
    if (raw.length > 1 && raw.endsWith("/")) {
        return ProjectPathCompletionRequest(directoryPath = raw.trimEnd('/'), prefix = "")
    }
    val clean = raw.trimEnd('/').ifEmpty { "/" }
    if (clean == "/") {
        return ProjectPathCompletionRequest(directoryPath = "/", prefix = "")
    }
    val slashIndex = clean.lastIndexOf('/')
    val directoryPath = when {
        slashIndex <= 0 -> "/"
        else -> clean.substring(0, slashIndex)
    }
    val prefix = clean.substring(slashIndex + 1)
    return ProjectPathCompletionRequest(directoryPath = directoryPath, prefix = prefix)
}

fun buildProjectPathSuggestions(directory: FileDirectoryUi?, prefix: String = ""): List<ProjectPathSuggestionUi> {
    val rawBasePath = directory?.path?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
    val basePath = rawBasePath.trimEnd('/').ifEmpty { "/" }
    val cleanPrefix = prefix.trim()
    return directory.entries
        .filter { entry -> cleanPrefix.isEmpty() || entry.name.startsWith(cleanPrefix, ignoreCase = true) }
        .map { entry ->
            ProjectPathSuggestionUi(
                path = joinHostPath(basePath, entry.name),
                name = entry.name,
                isDirectory = entry.isDirectory,
                isFile = entry.isFile
            )
        }
}

fun parseRuntimeModelOptions(result: JsonObject): List<RuntimeModelOption> {
    val array = result["data"]?.safeJsonArray()
        ?: result["items"]?.safeJsonArray()
        ?: result["models"]?.safeJsonArray()
        ?: JsonArray(emptyList())
    return array.mapNotNull { element ->
        val obj = element.safeJsonObject() ?: return@mapNotNull null
        val id = obj.stringForAny("id", "model", "value") ?: return@mapNotNull null
        RuntimeModelOption(
            id = id,
            label = obj.stringForAny("label", "name", "displayName", "display_name") ?: id,
            hidden = obj["hidden"]?.jsonPrimitiveOrNull()?.booleanOrNull ?: false,
            supportedReasoningEfforts = parseSupportedReasoningEfforts(obj)
        )
    }
}

private fun parseSupportedReasoningEfforts(model: JsonObject): List<ReasoningEffort> {
    val efforts = model["supportedReasoningEfforts"]?.safeJsonArray()
        ?: model["supported_reasoning_efforts"]?.safeJsonArray()
        ?: return DefaultReasoningEfforts
    val parsed = efforts.mapNotNull { element ->
        val value = element.safeJsonObject()?.stringForAny("reasoningEffort", "reasoning_effort", "value", "id")
            ?: element.jsonPrimitiveOrNullContent()
        ReasoningEffort.fromWireName(value)
    }.distinct()
    return parsed.ifEmpty { DefaultReasoningEfforts }.withKnownReasoningEfforts()
}

private fun List<ReasoningEffort>.withKnownReasoningEfforts(): List<ReasoningEffort> =
    if (ReasoningEffort.XHigh in this) this else this + ReasoningEffort.XHigh

fun buildTurnStartParams(
    threadId: String,
    prompt: String,
    modelId: String,
    accessMode: AccessMode,
    reasoningEffort: ReasoningEffort = ReasoningEffort.Medium,
    fastModeEnabled: Boolean = false,
    includeApprovalPolicy: Boolean = true,
    includeSandboxPolicy: Boolean = true,
    attachments: List<PendingAttachmentUi> = emptyList()
): JsonObject = buildJsonObject {
    put("threadId", threadId)
    val readyAttachments = attachments.filter { it.isReady }
    val fileReferences = readyAttachments.filterNot { it.isImage }
    val promptWithFiles = if (fileReferences.isEmpty()) {
        prompt
    } else {
        buildString {
            append(prompt)
            append("\n\n已上传文件，可从 Linux 主机路径读取：")
            fileReferences.forEach { attachment ->
                append("\n- ")
                append(attachment.displayName)
                append(": ")
                append(attachment.hostPath)
            }
        }
    }
    put("input", kotlinx.serialization.json.buildJsonArray {
        add(buildJsonObject {
            put("type", "text")
            put("text", promptWithFiles)
            put("text_elements", JsonArray(emptyList()))
        })
        readyAttachments.filter { it.isImage }.forEach { attachment ->
            add(buildJsonObject {
                put("type", "localImage")
                put("path", attachment.hostPath)
                attachment.mimeType?.let { put("mimeType", it) }
            })
        }
    })
    val cleanModel = modelId.trim()
    if (cleanModel.isNotEmpty()) {
        put("model", cleanModel)
    }
    put("reasoningEffort", reasoningEffort.wireName)
    if (fastModeEnabled) {
        put("profile", "fast")
    }
    if (includeApprovalPolicy) {
        put("approvalPolicy", accessMode.approvalPolicy)
    }
    if (includeSandboxPolicy) {
        accessMode.turnSandboxPolicy()?.let { put("sandboxPolicy", it) }
    }
}

fun buildCreateDirectoryParams(path: String): JsonObject = buildJsonObject {
    put("path", path.cleanPathOrNull() ?: path.trim())
    put("recursive", true)
}

fun buildWriteFileParams(path: String, bytes: ByteArray): JsonObject = buildJsonObject {
    put("path", path.cleanPathOrNull() ?: path.trim())
    put("dataBase64", Base64.getEncoder().encodeToString(bytes))
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putRuntimeSettings(
    modelId: String,
    accessMode: AccessMode,
    reasoningEffort: ReasoningEffort,
    fastModeEnabled: Boolean,
    includeSandboxMode: Boolean
) {
    val cleanModel = modelId.trim()
    if (cleanModel.isNotEmpty()) {
        put("model", cleanModel)
    }
    put("reasoningEffort", reasoningEffort.wireName)
    if (fastModeEnabled) {
        put("profile", "fast")
    }
    put("approvalPolicy", accessMode.approvalPolicy)
    if (includeSandboxMode) {
        put("sandbox", accessMode.sandboxMode)
    }
}

private fun AccessMode.turnSandboxPolicy(): JsonObject? = when (this) {
    AccessMode.FullAccess -> buildJsonObject {
        put("type", "dangerFullAccess")
    }
    AccessMode.OnRequest -> null
}

fun JsonObject.withoutTurnStartField(field: String): JsonObject = buildJsonObject {
    this@withoutTurnStartField.forEach { (key, value) ->
        if (key != field) {
            put(key, value)
        }
    }
}

fun JsonObject.nextRuntimeCompatibilityFallback(): JsonObject? {
    val field = listOf(
        "profile",
        "reasoningEffort",
        "reasoning_effort",
        "sandboxPolicy",
        "approvalPolicy",
        "sandbox",
        "model"
    ).firstOrNull { this[it] != null } ?: return null
    return withoutTurnStartField(field)
}

fun filterThreadsForArchiveState(
    threads: List<ThreadSummary>,
    archivedThreadIds: Set<String>,
    showArchived: Boolean
): List<ThreadSummary> {
    if (showArchived) return threads
    return threads.filterNot { it.id in archivedThreadIds }
}

fun parseThreadListPage(result: JsonObject): ThreadListPage {
    val array = result["data"]?.jsonArray ?: result["threads"]?.jsonArray ?: JsonArray(emptyList())
    return ThreadListPage(
        threads = parseThreadSummaries(array),
        nextCursor = result.stringForAny("nextCursor", "next_cursor")
    )
}

fun parseThreadSummaries(array: JsonArray): List<ThreadSummary> = array.mapNotNull { element ->
    val obj = element.jsonObject
    val id = obj["id"]?.jsonPrimitive?.content?.trim().orEmpty()
    if (id.isEmpty()) {
        null
    } else {
        val metadata = obj["metadata"]?.safeJsonObject()
        val preview = obj.stringForAny("preview")
        val title = obj.stringForAny("name", "title")
            ?: preview
            ?: metadata?.stringForAny("name", "title", "preview")
        ThreadSummary(
            id = id,
            title = title,
            preview = preview,
            cwd = obj.stringForAny("cwd", "current_working_directory", "working_directory")
                ?: metadata?.stringForAny("cwd", "current_working_directory", "working_directory", "projectPath", "project_path"),
            updatedAt = obj.stringForAny("updatedAt", "updated_at")
        )
    }
}

fun parseThreadHistoryMessages(threadId: String, threadObject: JsonObject): List<ChatMessage> {
    val turns = threadObject["turns"]?.safeJsonArray() ?: return emptyList()
    return buildList {
        for (turnElement in turns) {
            val turnObject = turnElement.safeJsonObject() ?: continue
            val turnId = turnObject.stringForAny("id")
            val items = turnObject["items"]?.safeJsonArray() ?: JsonArray(emptyList())
            for (itemElement in items) {
                val item = itemElement.safeJsonObject() ?: continue
                parseThreadHistoryItem(threadId, turnId, item)?.let { add(it) }
            }
            if (turnObject.stringForAny("status") == "failed") {
                val error = turnObject["error"]?.decodeErrorMessage() ?: "未知错误"
                add(ChatMessage(ChatMessage.Role.System, "任务失败：$error", threadId, turnId))
            }
        }
    }
}

fun parseThreadHistoryItem(threadId: String?, turnId: String?, item: JsonObject): ChatMessage? {
    val itemId = item.stringForAny("id")
    val createdAtMs = item.timestampMillis()
    if (item.isRateLimitUpdateItem()) return null
    return when (item.stringForAny("type")?.normalizedType()) {
        "usermessage" -> item.decodeUserMessageText()
            ?.let { text -> ChatMessage(ChatMessage.Role.User, text, threadId, turnId, itemId, createdAtMs) }
        "agentmessage", "assistantmessage" -> item.stringForAny("text")
            ?.takeIf { it.isNotBlank() }
            ?.let { text -> ChatMessage(ChatMessage.Role.Assistant, text, threadId, turnId, itemId, createdAtMs) }
        "message" -> item.stringForAny("text")
            ?.takeIf { it.isNotBlank() }
            ?.let { text ->
                val role = if (item.stringForAny("role")?.contains("user", ignoreCase = true) == true) {
                    ChatMessage.Role.User
                } else {
                    ChatMessage.Role.Assistant
                }
                ChatMessage(role, text, threadId, turnId, itemId, createdAtMs)
            }
        "plan" -> item.stringForAny("text")
            ?.takeIf { it.isNotBlank() }
            ?.let { text -> ChatMessage(ChatMessage.Role.System, "计划\n$text", threadId, turnId, itemId, createdAtMs) }
        "reasoning", "reasoningsummary", "reasoningmessage" -> item.decodeReasoningText()
            ?.takeIf { it.isNotBlank() }
            ?.let { text -> ChatMessage(ChatMessage.Role.System, "思考\n$text", threadId, turnId, itemId, createdAtMs) }
        "commandexecution", "command" -> item.decodeCommandExecutionText()
            ?.let { text -> ChatMessage(ChatMessage.Role.System, "命令\n$text", threadId, turnId, itemId, createdAtMs) }
        "filechange", "filediff", "patch" -> item.decodeFileChangeText()
            ?.let { text -> ChatMessage(ChatMessage.Role.System, "文件\n$text", threadId, turnId, itemId, createdAtMs) }
        "error" -> item.decodeErrorText()
            ?.let { text -> ChatMessage(ChatMessage.Role.System, "错误\n$text", threadId, turnId, itemId, createdAtMs) }
        else -> null
    }
}

private fun JsonObject.isRateLimitUpdateItem(): Boolean {
    if (this["rateLimits"] != null || this["rate_limits"] != null) return true
    val method = stringForAny("method", "event", "name")
    if (method == "account/rateLimits/updated") return true
    val text = stringForAny("text", "message", "content", "body") ?: return false
    return text.contains("rateLimits") && text.contains("limitId") ||
        text.contains("限额已更新")
}

fun mergeCompletedMessage(messages: List<ChatMessage>, completed: ChatMessage): List<ChatMessage> {
    val existingItemIndex = completed.itemId?.let { itemId ->
        messages.indexOfLast { it.itemId == itemId }
    } ?: -1
    if (existingItemIndex >= 0) {
        return messages.replaceAt(existingItemIndex, messages[existingItemIndex].mergedWith(completed))
    }
    val optimisticUserIndex = if (completed.role == ChatMessage.Role.User) {
        messages.indexOfLast { existing ->
            existing.role == ChatMessage.Role.User &&
                existing.itemId == null &&
                existing.text.normalizedMessageText() == completed.text.normalizedMessageText() &&
                existing.threadId.compatibleWith(completed.threadId)
        }
    } else {
        -1
    }
    if (optimisticUserIndex >= 0) {
        return messages.replaceAt(optimisticUserIndex, messages[optimisticUserIndex].mergedWith(completed))
    }
    return messages + completed
}

fun ChatMessage.isReasoningMessage(): Boolean =
    role == ChatMessage.Role.System && text.trimStart().startsWith("思考")

fun ChatMessage.reasoningBody(): String =
    text.trimStart().removePrefix("思考").trimStart('\n', '：', ':', ' ')

fun buildTimelineEntries(state: CodexRemoteUiState): List<TimelineEntry> = buildList {
    state.messages.forEachIndexed { index, message ->
        message.toTimelineEntry(index)?.let { add(it) }
    }
    state.turnDiff
        ?.takeIf { it.threadId.matchesTimelineThread(state.activeThreadId) }
        ?.let { add(TimelineEntry.Diff(it)) }
    state.runtimeNotices.forEachIndexed { index, notice ->
        if (notice.threadId.matchesTimelineThread(state.activeThreadId)) {
            add(TimelineEntry.RuntimeNotice(notice, key = "notice-${notice.threadId}-${notice.title}-${notice.body.hashCode()}-$index"))
        }
    }
    state.pendingApproval
        ?.takeIf { it.threadId.matchesTimelineThread(state.activeThreadId) }
        ?.let { add(TimelineEntry.ApprovalRequest(it)) }
    state.pendingInteraction
        ?.takeIf { it.threadId.matchesTimelineThread(state.activeThreadId) }
        ?.let { add(TimelineEntry.InputRequest(it)) }
    state.activePlan
        ?.takeIf { it.threadId.matchesTimelineThread(state.activeThreadId) }
        ?.let { add(TimelineEntry.PlanReview(it)) }
    state.recoverableError
        ?.takeIf { it.threadId.matchesTimelineThread(state.activeThreadId) }
        ?.let { error ->
            add(
                TimelineEntry.Failure(
                    title = error.title,
                    detail = error.detail,
                    actions = error.actions,
                    key = "failure-${error.threadId}-${error.turnId}-${error.detail.hashCode()}",
                    threadId = error.threadId,
                    turnId = error.turnId
                )
            )
        }
}

private fun ChatMessage.toTimelineEntry(index: Int): TimelineEntry? {
    val key = timelineMessageKey(index)
    return when (role) {
        ChatMessage.Role.User -> TimelineEntry.Text(
            role = TimelineTextRole.User,
            text = text,
            key = key,
            threadId = threadId,
            turnId = turnId,
            itemId = itemId,
            createdAtMs = createdAtMs
        )
        ChatMessage.Role.Assistant -> TimelineEntry.Text(
            role = TimelineTextRole.Assistant,
            text = text,
            key = key,
            threadId = threadId,
            turnId = turnId,
            itemId = itemId,
            createdAtMs = createdAtMs
        )
        ChatMessage.Role.System -> systemMessageToTimelineEntry(key)
    }
}

private fun ChatMessage.systemMessageToTimelineEntry(key: String): TimelineEntry {
    val clean = text.trimStart()
    return when {
        isReasoningMessage() -> TimelineEntry.Reasoning(
            body = reasoningBody(),
            key = key,
            threadId = threadId,
            turnId = turnId,
            itemId = itemId,
            createdAtMs = createdAtMs
        )
        clean.startsWith("命令输出") -> commandEntry(
            body = clean.removeTimelinePrefix("命令输出"),
            key = key,
            titleFallback = "命令输出"
        )
        clean.startsWith("命令") -> commandEntry(
            body = clean.removeTimelinePrefix("命令"),
            key = key,
            titleFallback = "命令"
        )
        clean.startsWith("文件") -> TimelineEntry.FileChange(
            body = clean.removeTimelinePrefix("文件"),
            key = key,
            threadId = threadId,
            turnId = turnId,
            itemId = itemId,
            createdAtMs = createdAtMs
        )
        clean.startsWith("错误") -> TimelineEntry.Failure(
            title = "错误",
            detail = clean.removeTimelinePrefix("错误"),
            key = key,
            threadId = threadId,
            turnId = turnId,
            itemId = itemId,
            createdAtMs = createdAtMs
        )
        clean.startsWith("任务失败") -> TimelineEntry.Failure(
            title = "任务失败",
            detail = clean.removeTimelinePrefix("任务失败").ifBlank { clean },
            key = key,
            threadId = threadId,
            turnId = turnId,
            itemId = itemId,
            createdAtMs = createdAtMs
        )
        clean.startsWith("计划") -> TimelineEntry.SystemNotice(
            title = "计划",
            body = clean.removeTimelinePrefix("计划"),
            key = key,
            threadId = threadId,
            turnId = turnId,
            itemId = itemId,
            createdAtMs = createdAtMs
        )
        else -> TimelineEntry.SystemNotice(
            title = "系统",
            body = text,
            key = key,
            threadId = threadId,
            turnId = turnId,
            itemId = itemId,
            createdAtMs = createdAtMs
        )
    }
}

private fun ChatMessage.commandEntry(body: String, key: String, titleFallback: String): TimelineEntry.CommandExecution {
    val cleanBody = body.trim()
    val commandLine = cleanBody.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() } ?: titleFallback
    return TimelineEntry.CommandExecution(
        commandLine = commandLine,
        body = cleanBody,
        key = key,
        threadId = threadId,
        turnId = turnId,
        itemId = itemId,
        createdAtMs = createdAtMs
    )
}

private fun ChatMessage.timelineMessageKey(index: Int): String =
    itemId ?: "${role.name}-${createdAtMs ?: "na"}-${text.hashCode()}-$index"

private fun String.removeTimelinePrefix(prefix: String): String =
    removePrefix(prefix).trimStart('\n', '：', ':', ' ')

private fun String?.matchesTimelineThread(activeThreadId: String?): Boolean =
    activeThreadId == null || this == null || this == activeThreadId

fun parseTurnPlan(params: JsonObject): ActiveTurnPlan? {
    val steps = params["plan"]?.safeJsonArray()?.mapNotNull { element ->
        val step = element.safeJsonObject() ?: return@mapNotNull null
        val text = step.stringForAny("step", "text", "description") ?: return@mapNotNull null
        TurnPlanStepUi(
            step = text,
            status = when (step.stringForAny("status")?.normalizedType()) {
                "completed", "done" -> TurnPlanStatus.Completed
                "inprogress", "active", "running" -> TurnPlanStatus.InProgress
                else -> TurnPlanStatus.Pending
            }
        )
    }.orEmpty()
    if (steps.isEmpty() && params.stringForAny("explanation").isNullOrBlank()) {
        return null
    }
    return ActiveTurnPlan(
        threadId = params.stringForAny("threadId", "thread_id"),
        turnId = params.stringForAny("turnId", "turn_id"),
        explanation = params.stringForAny("explanation"),
        steps = steps
    )
}

fun parsePendingApproval(requestId: Int, method: String, params: JsonObject?): PendingApproval? {
    val requestParams = params ?: buildJsonObject {}
    val threadId = requestParams.stringForAny("threadId", "thread_id")
    val turnId = requestParams.stringForAny("turnId", "turn_id")
    val itemId = requestParams.stringForAny("itemId", "item_id")
    return when (method) {
        "item/commandExecution/requestApproval" -> {
            val command = requestParams.stringForAny("command")
                ?: requestParams["command"]?.approvalDisplayText()
                ?: "未知命令"
            PendingApproval(
                requestId = requestId,
                method = method,
                kind = PendingApprovalKind.CommandExecution,
                title = "允许执行命令？",
                body = approvalBody(
                    "命令" to command,
                    "目录" to requestParams.stringForAny("cwd"),
                    "原因" to requestParams.stringForAny("reason")
                ),
                threadId = threadId,
                turnId = turnId,
                itemId = itemId,
                requestParams = requestParams
            )
        }
        "item/fileChange/requestApproval" -> PendingApproval(
            requestId = requestId,
            method = method,
            kind = PendingApprovalKind.FileChange,
            title = "允许文件变更？",
            body = approvalBody(
                "目录" to requestParams.stringForAny("grantRoot", "cwd"),
                "原因" to requestParams.stringForAny("reason")
            ),
            threadId = threadId,
            turnId = turnId,
            itemId = itemId,
            requestParams = requestParams
        )
        "item/permissions/requestApproval" -> PendingApproval(
            requestId = requestId,
            method = method,
            kind = PendingApprovalKind.Permissions,
            title = "允许提升权限？",
            body = approvalBody(
                "目录" to requestParams.stringForAny("cwd"),
                "原因" to requestParams.stringForAny("reason"),
                "权限" to requestParams["permissions"]?.approvalDisplayText()
            ),
            threadId = threadId,
            turnId = turnId,
            itemId = itemId,
            requestParams = requestParams
        )
        "execCommandApproval" -> PendingApproval(
            requestId = requestId,
            method = method,
            kind = PendingApprovalKind.LegacyCommand,
            title = "允许执行命令？",
            body = approvalBody(
                "命令" to (requestParams["command"]?.approvalDisplayText() ?: requestParams.stringForAny("command")),
                "目录" to requestParams.stringForAny("cwd"),
                "原因" to requestParams.stringForAny("reason")
            ),
            threadId = threadId,
            turnId = turnId,
            itemId = itemId,
            requestParams = requestParams
        )
        "applyPatchApproval" -> PendingApproval(
            requestId = requestId,
            method = method,
            kind = PendingApprovalKind.LegacyPatch,
            title = "允许应用补丁？",
            body = approvalBody(
                "原因" to requestParams.stringForAny("reason"),
                "路径" to (requestParams["writableRoot"]?.approvalDisplayText() ?: requestParams["changes"]?.approvalDisplayText())
            ),
            threadId = threadId,
            turnId = turnId,
            itemId = itemId,
            requestParams = requestParams
        )
        else -> null
    }
}

fun buildApprovalResult(pending: PendingApproval, decision: ApprovalDecision): JsonObject = when (pending.kind) {
    PendingApprovalKind.CommandExecution -> buildJsonObject {
        put("decision", decision.commandApprovalValue())
    }
    PendingApprovalKind.FileChange -> buildJsonObject {
        put("decision", decision.fileChangeApprovalValue())
    }
    PendingApprovalKind.Permissions -> buildJsonObject {
        if (decision == ApprovalDecision.Accept || decision == ApprovalDecision.AcceptForSession) {
            put("permissions", pending.requestParams["permissions"] ?: buildJsonObject {})
            put("scope", if (decision == ApprovalDecision.AcceptForSession) "session" else "turn")
            put("strictAutoReview", false)
        } else {
            put("permissions", buildJsonObject {})
            put("scope", "turn")
            put("strictAutoReview", false)
        }
    }
    PendingApprovalKind.LegacyCommand, PendingApprovalKind.LegacyPatch -> buildJsonObject {
        put("decision", decision.legacyReviewValue())
    }
    PendingApprovalKind.Unsupported -> buildJsonObject {}
}

fun parsePendingInteraction(requestId: Int, method: String, params: JsonObject?): PendingInteraction? {
    val requestParams = params ?: buildJsonObject {}
    val threadId = requestParams.stringForAny("threadId", "thread_id")
    val turnId = requestParams.stringForAny("turnId", "turn_id")
    val itemId = requestParams.stringForAny("itemId", "item_id")
    return when (method) {
        "item/tool/requestUserInput" -> {
            val questions = requestParams.parseQuestionArray("questions")
            PendingInteraction(
                requestId = requestId,
                method = method,
                kind = PendingInteractionKind.ToolUserInput,
                title = "需要输入",
                body = questions.firstOrNull()?.question ?: "Codex 请求继续前确认。",
                threadId = threadId,
                turnId = turnId,
                itemId = itemId,
                questions = questions,
                requestParams = requestParams
            )
        }
        "mcpServer/elicitation/request" -> {
            val questions = requestParams.parseQuestionArray("fields")
                .ifEmpty { requestParams.parseSchemaQuestions() }
            PendingInteraction(
                requestId = requestId,
                method = method,
                kind = PendingInteractionKind.McpElicitation,
                title = requestParams.stringForAny("serverName", "server_name")?.let { "$it 请求输入" } ?: "MCP 请求输入",
                body = requestParams.stringForAny("message", "prompt") ?: "外部服务请求继续前确认。",
                threadId = threadId,
                turnId = turnId,
                itemId = itemId,
                questions = questions,
                serverName = requestParams.stringForAny("serverName", "server_name"),
                url = requestParams.stringForAny("url"),
                requestParams = requestParams
            )
        }
        else -> null
    }
}

private fun JsonObject.parseQuestionArray(key: String): List<PendingInteractionQuestion> =
    this[key]?.safeJsonArray()?.mapNotNull { element ->
        val question = element.safeJsonObject() ?: return@mapNotNull null
        question.toPendingInteractionQuestion()
    }.orEmpty()

private fun JsonObject.parseSchemaQuestions(): List<PendingInteractionQuestion> {
    val schema = this["schema"]?.safeJsonObject()
        ?: this["inputSchema"]?.safeJsonObject()
        ?: this["input_schema"]?.safeJsonObject()
        ?: return emptyList()
    val properties = schema["properties"]?.safeJsonObject() ?: return emptyList()
    return properties.mapNotNull { (id, element) ->
        val property = element.safeJsonObject() ?: return@mapNotNull null
        property.toPendingInteractionQuestion(fallbackId = id)
    }
}

private fun JsonObject.toPendingInteractionQuestion(fallbackId: String? = null): PendingInteractionQuestion? {
    val id = stringForAny("id", "name", "key") ?: fallbackId ?: return null
    val type = stringForAny("type", "format")
    return PendingInteractionQuestion(
        id = id,
        header = stringForAny("header", "title"),
        question = stringForAny("question", "prompt", "label", "title", "description") ?: id,
        isOther = this["isOther"]?.jsonPrimitiveOrNull()?.booleanOrNull ?: false,
        isSecret = this["isSecret"]?.jsonPrimitiveOrNull()?.booleanOrNull == true ||
            type?.contains("password", ignoreCase = true) == true ||
            type?.contains("secret", ignoreCase = true) == true,
        options = parseInteractionOptions()
    )
}

private fun JsonObject.parseInteractionOptions(): List<PendingInteractionOption> {
    val optionArray = this["options"]?.safeJsonArray()
        ?: this["enum"]?.safeJsonArray()
        ?: return emptyList()
    return optionArray.mapNotNull { optionElement ->
        optionElement.safeJsonObject()?.let { option ->
            val label = option.stringForAny("label", "value", "id", "name") ?: return@mapNotNull null
            PendingInteractionOption(
                label = label,
                description = option.stringForAny("description")
            )
        } ?: optionElement.jsonPrimitiveOrNullContent()?.takeIf { it.isNotBlank() }?.let { label ->
            PendingInteractionOption(label = label)
        }
    }
}

fun buildInteractionResult(
    request: PendingInteraction,
    answers: Map<String, List<String>>,
    action: InteractionAction
): JsonObject = when (request.kind) {
    PendingInteractionKind.ToolUserInput -> buildJsonObject {
        put("answers", buildJsonObject {
            answers.forEach { (id, values) ->
                put(id, buildJsonObject {
                    put("answers", buildJsonArray {
                        values.forEach { add(JsonPrimitive(it)) }
                    })
                })
            }
        })
    }
    PendingInteractionKind.McpElicitation -> buildJsonObject {
        put("action", if (action == InteractionAction.Accept) "accept" else "cancel")
        if (action == InteractionAction.Accept) {
            put("content", buildJsonObject {
                answers.forEach { (id, values) ->
                    put(id, values.joinToString("\n"))
                }
            })
            request.requestParams["_meta"]?.takeUnless { it is JsonNull }?.let { put("_meta", it) }
        }
    }
    PendingInteractionKind.Unsupported -> buildJsonObject {
        put("action", "cancel")
    }
}

fun parseTurnDiff(params: JsonObject): TurnDiffSummary? {
    val diff = params.stringForAny("diff", "patch") ?: params["changes"]?.approvalDisplayText() ?: return null
    return TurnDiffSummary(
        threadId = params.stringForAny("threadId", "thread_id"),
        turnId = params.stringForAny("turnId", "turn_id"),
        diff = diff
    )
}

fun parseCommandOutputDelta(params: JsonObject): CommandOutputDelta? {
    val delta = params["delta"]?.jsonPrimitiveOrNullContent()
        ?: params["text"]?.jsonPrimitiveOrNullContent()
        ?: params["output"]?.jsonPrimitiveOrNullContent()
        ?: return null
    return CommandOutputDelta(
        threadId = params.stringForAny("threadId", "thread_id"),
        turnId = params.stringForAny("turnId", "turn_id"),
        itemId = params.stringForAny("itemId", "item_id"),
        delta = delta
    )
}

fun parseReasoningDelta(params: JsonObject): ReasoningDelta? {
    val delta = params["delta"]?.jsonPrimitiveOrNullContent()
        ?: params["summaryDelta"]?.jsonPrimitiveOrNullContent()
        ?: params["summary_delta"]?.jsonPrimitiveOrNullContent()
        ?: params["contentDelta"]?.jsonPrimitiveOrNullContent()
        ?: params["content_delta"]?.jsonPrimitiveOrNullContent()
        ?: params["text"]?.jsonPrimitiveOrNullContent()
        ?: params["summary"]?.jsonPrimitiveOrNullContent()
        ?: params["content"]?.jsonPrimitiveOrNullContent()
        ?: return null
    return ReasoningDelta(
        threadId = params.stringForAny("threadId", "thread_id"),
        turnId = params.stringForAny("turnId", "turn_id"),
        itemId = params.stringForAny("itemId", "item_id", "id"),
        delta = delta
    )
}

fun parseRuntimeNotice(method: String, params: JsonObject?): RuntimeNotice? {
    val requestParams = params ?: buildJsonObject {}
    val kind = when (method) {
        "warning" -> RuntimeNoticeKind.Warning
        "guardianWarning" -> RuntimeNoticeKind.GuardianWarning
        "model/rerouted" -> RuntimeNoticeKind.ModelRerouted
        "account/rateLimits/updated" -> return null
        "context/compacted", "thread/compacted" -> RuntimeNoticeKind.ContextCompacted
        else -> return null
    }
    val title = when (kind) {
        RuntimeNoticeKind.Warning -> "运行警告"
        RuntimeNoticeKind.GuardianWarning -> "安全提示"
        RuntimeNoticeKind.ModelRerouted -> "模型已切换"
        RuntimeNoticeKind.UnsupportedRequest -> "请求暂不支持"
        RuntimeNoticeKind.ContextCompacted -> "上下文已压缩"
    }
    val body = when (kind) {
        RuntimeNoticeKind.ModelRerouted -> listOfNotNull(
            requestParams.stringForAny("message"),
            requestParams.stringForAny("fromModel", "from_model")?.let { "从 $it" },
            requestParams.stringForAny("toModel", "to_model")?.let { "到 $it" }
        ).joinToString("，")
        else -> requestParams.stringForAny("message", "detail", "text") ?: requestParams.toString()
    }.ifBlank { requestParams.toString() }
    return RuntimeNotice(
        kind = kind,
        threadId = requestParams.stringForAny("threadId", "thread_id"),
        title = title,
        body = body
    )
}

fun parseRuntimeFailure(method: String, params: JsonObject?): RuntimeFailure? {
    val requestParams = params ?: return null
    val errorDetail = requestParams["error"]?.decodeErrorMessage()
        ?: requestParams.stringForAny("message", "detail", "text")
        ?: requestParams["error"]?.approvalDisplayText()
        ?: requestParams.toString()
    val info = requestParams["error"]?.safeJsonObject()?.stringForAny("info", "code")
        ?: requestParams.stringForAny("info", "code")
    val detail = listOfNotNull(errorDetail, info).distinct().joinToString("：")
    val normalized = "$method $detail".lowercase()
    val kind = when {
        method == "host/appServerExited" -> RecoverableErrorKind.TransportLost
        normalized.contains("compact") ||
            normalized.contains("contextwindow") ||
            normalized.contains("context window") -> RecoverableErrorKind.CompactFailed
        method == "thread/closed" || method == "threadClosed" -> RecoverableErrorKind.ThreadClosed
        method == "error" || method == "turn/failed" -> RecoverableErrorKind.TurnFailed
        else -> return null
    }
    return RuntimeFailure(
        kind = kind,
        threadId = requestParams.stringForAny("threadId", "thread_id"),
        turnId = requestParams.stringForAny("turnId", "turn_id"),
        detail = detail,
        willRetry = requestParams["willRetry"]?.jsonPrimitiveOrNull()?.booleanOrNull ?: false
    )
}

fun parseTurnCompletedFailure(
    params: JsonObject?,
    fallbackThreadId: String?,
    fallbackTurnId: String?
): RuntimeFailure? {
    val requestParams = params ?: return null
    val turn = requestParams["turn"]?.safeJsonObject() ?: requestParams
    if (turn.stringForAny("status", "state")?.equals("failed", ignoreCase = true) != true) {
        return null
    }
    val threadId = requestParams.stringForAny("threadId", "thread_id") ?: fallbackThreadId
    val turnId = turn.stringForAny("id", "turnId", "turn_id") ?: requestParams.stringForAny("turnId", "turn_id") ?: fallbackTurnId
    val detail = turn["error"]?.decodeErrorMessage()
        ?: turn.stringForAny("message", "detail")
        ?: "任务失败"
    return RuntimeFailure(
        kind = RecoverableErrorKind.TurnFailed,
        threadId = threadId,
        turnId = turnId,
        detail = detail
    )
}

fun parseThreadClosedFailure(params: JsonObject?, activeThreadId: String?): RuntimeFailure {
    val requestParams = params ?: buildJsonObject {}
    val threadId = requestParams.stringForAny("threadId", "thread_id") ?: activeThreadId
    return RuntimeFailure(
        kind = RecoverableErrorKind.ThreadClosed,
        threadId = threadId,
        turnId = requestParams.stringForAny("turnId", "turn_id"),
        detail = requestParams.stringForAny("message", "detail") ?: "desktop closed the thread"
    )
}

fun parseContextCompactedNotice(params: JsonObject?): RuntimeNotice {
    val requestParams = params ?: buildJsonObject {}
    return RuntimeNotice(
        kind = RuntimeNoticeKind.ContextCompacted,
        threadId = requestParams.stringForAny("threadId", "thread_id"),
        title = "上下文已压缩",
        body = requestParams.stringForAny("message", "detail") ?: "当前会话上下文已压缩。"
    )
}

fun parseThreadGoal(result: JsonObject): ThreadGoalUi? {
    val goal = result["goal"]?.safeJsonObject() ?: result
    val objective = goal.stringForAny("objective", "goal") ?: return null
    val statusName = goal.stringForAny("status") ?: ThreadGoalStatus.Active.wireName
    val status = ThreadGoalStatus.values().firstOrNull {
        it.wireName.equals(statusName, ignoreCase = true) || it.name.equals(statusName, ignoreCase = true)
    } ?: ThreadGoalStatus.Active
    return ThreadGoalUi(
        objective = objective,
        status = status,
        tokenBudget = goal.intForAny("tokenBudget", "token_budget"),
        tokensUsed = goal.intForAny("tokensUsed", "tokens_used"),
        timeUsedSeconds = goal.intForAny("timeUsedSeconds", "time_used_seconds")
    )
}

fun CodexRemoteUiState.withGoalUpdatedFromNotification(params: JsonObject): CodexRemoteUiState {
    val goal = parseThreadGoal(params) ?: return this
    return copy(
        goalModeEnabled = true,
        activeGoal = goal,
        status = "目标已同步"
    )
}

fun CodexRemoteUiState.withGoalClearedFromNotification(params: JsonObject): CodexRemoteUiState {
    val threadId = params.stringForAny("threadId", "thread_id")
    if (threadId != null && activeThreadId != null && threadId != activeThreadId) {
        return this
    }
    return copy(
        goalModeEnabled = false,
        activeGoal = null,
        status = "目标已清除"
    )
}

fun parseFileDirectory(result: JsonObject, path: String): FileDirectoryUi {
    val entries = (result["entries"]?.safeJsonArray() ?: result["data"]?.safeJsonArray() ?: JsonArray(emptyList()))
        .mapNotNull { element ->
            val entry = element.safeJsonObject() ?: return@mapNotNull null
            val name = entry.stringForAny("fileName", "name", "path") ?: return@mapNotNull null
            FileEntryUi(
                name = name,
                isDirectory = entry["isDirectory"]?.jsonPrimitiveOrNull()?.booleanOrNull == true,
                isFile = entry["isFile"]?.jsonPrimitiveOrNull()?.booleanOrNull ?: false
            )
        }
        .sortedWith(compareByDescending<FileEntryUi> { it.isDirectory }.thenBy { it.name.lowercase() })
    return FileDirectoryUi(path = path, entries = entries)
}

fun parseFileMetadata(result: JsonObject, fallbackPath: String): FileMetadataUi {
    val metadata = result["metadata"]?.safeJsonObject() ?: result
    return FileMetadataUi(
        path = metadata.stringForAny("path", "filePath", "file_path") ?: fallbackPath,
        isDirectory = metadata["isDirectory"]?.jsonPrimitiveOrNull()?.booleanOrNull == true ||
            metadata.stringForAny("type", "kind")?.equals("directory", ignoreCase = true) == true,
        isFile = metadata["isFile"]?.jsonPrimitiveOrNull()?.booleanOrNull == true ||
            metadata.stringForAny("type", "kind")?.equals("file", ignoreCase = true) == true
    )
}

fun parseFilePreview(result: JsonObject, path: String): FilePreviewUi {
    val text = result.stringForAny("text", "content", "data")
        ?: result.stringForAny("dataBase64", "base64")?.let { encoded ->
            runCatching { String(Base64.getDecoder().decode(encoded), Charsets.UTF_8) }.getOrNull()
        }
        ?: ""
    return FilePreviewUi(path = path, text = text)
}

fun parseDesktopStatus(
    configResult: JsonObject?,
    accountResult: JsonObject?,
    rateLimitResult: JsonObject?
): DesktopStatusUi {
    val config = configResult?.get("config")?.safeJsonObject() ?: configResult
    val account = accountResult?.get("account")?.safeJsonObject() ?: accountResult
    return DesktopStatusUi(
        model = config?.stringForAny("model", "modelId", "model_id"),
        approvalPolicy = config?.stringForAny("approvalPolicy", "approval_policy"),
        accountType = account?.stringForAny("type", "planType", "plan_type"),
        accountEmail = account?.stringForAny("email", "accountEmail", "account_email"),
        rateLimitSummary = rateLimitResult?.stringForAny("summary", "message")
            ?: rateLimitResult?.approvalDisplayText()
    )
}

fun parseMcpResourcePreview(result: JsonObject): McpResourcePreviewUi {
    val contents = (result["contents"]?.safeJsonArray() ?: result["data"]?.safeJsonArray() ?: JsonArray(emptyList()))
        .mapNotNull { element ->
            val content = element.safeJsonObject() ?: return@mapNotNull null
            val uri = content.stringForAny("uri", "url", "name") ?: return@mapNotNull null
            McpResourceContentUi(
                uri = uri,
                mimeType = content.stringForAny("mimeType", "mime_type"),
                text = content.stringForAny("text", "content")
                    ?: content.stringForAny("blob", "dataBase64", "base64")?.let { "[二进制资源: $uri]" }
                    ?: ""
            )
        }
    return McpResourcePreviewUi(contents = contents)
}

private fun ApprovalDecision.commandApprovalValue(): String = when (this) {
    ApprovalDecision.Accept -> "accept"
    ApprovalDecision.AcceptForSession -> "acceptForSession"
    ApprovalDecision.Decline -> "decline"
    ApprovalDecision.Cancel -> "cancel"
}

private fun ApprovalDecision.fileChangeApprovalValue(): String = commandApprovalValue()

private fun ApprovalDecision.legacyReviewValue(): String = when (this) {
    ApprovalDecision.Accept -> "approved"
    ApprovalDecision.AcceptForSession -> "approved_for_session"
    ApprovalDecision.Decline -> "denied"
    ApprovalDecision.Cancel -> "abort"
}

private fun approvalBody(vararg rows: Pair<String, String?>): String =
    rows.mapNotNull { (label, value) ->
        value?.trim()?.takeIf { it.isNotEmpty() }?.let { "$label：$it" }
    }.joinToString("\n").ifBlank { "Codex 请求继续执行受限操作。" }

private fun JsonObject.decodeUserMessageText(): String? {
    val content = this["content"]?.safeJsonArray() ?: return stringForAny("text")
    val text = content.mapNotNull { item ->
        val obj = item.safeJsonObject() ?: return@mapNotNull null
        when (obj.stringForAny("type")?.normalizedType()) {
            "text" -> obj.stringForAny("text")
            "localimage" -> obj.stringForAny("path", "file", "filePath")?.let { "[图片: $it]" }
            "image" -> obj.stringForAny("url", "uri", "path")?.let { "[图片: $it]" }
            else -> null
        }
    }.joinToString("\n").trim()
    return text.ifEmpty { null }
}

private fun JsonObject.decodeReasoningText(): String? {
    val summary = this["summary"]?.reasoningTextParts().orEmpty()
    val content = this["content"]?.reasoningTextParts().orEmpty()
    val text = stringForAny("text", "message", "delta")
    return (summary + content + listOfNotNull(text)).joinToString("\n").trim().ifEmpty { null }
}

private fun JsonObject.decodeCommandExecutionText(): String? {
    val command = stringForAny("command", "cmd", "text") ?: return null
    val cwd = stringForAny("cwd", "workingDirectory", "working_directory")
    val status = stringForAny("status", "state")
    val exitCode = stringForAny("exitCode", "exit_code", "code")
    val commandLine = if (cwd.isNullOrBlank()) command else "$cwd $ $command"
    val outcome = when {
        !status.isNullOrBlank() && !exitCode.isNullOrBlank() -> "$status ($exitCode)"
        !status.isNullOrBlank() -> status
        !exitCode.isNullOrBlank() -> "exit $exitCode"
        else -> null
    }
    return listOfNotNull(commandLine, outcome).joinToString("\n").trim().ifEmpty { null }
}

private fun JsonObject.decodeFileChangeText(): String? {
    val path = stringForAny("path", "file", "filename", "name") ?: return null
    val operation = stringForAny("operation", "action", "status") ?: "changed"
    return "$operation $path"
}

private fun JsonObject.decodeErrorText(): String? =
    stringForAny("message", "error", "text") ?: this["error"]?.decodeErrorMessage()

private fun JsonElement.decodeErrorMessage(): String? {
    val obj = safeJsonObject()
    return obj?.stringForAny("message", "error") ?: jsonPrimitiveOrNullContent()
}

private fun JsonObject.stringForAny(vararg keys: String): String? {
    for (key in keys) {
        val value = this[key]?.jsonPrimitiveOrNullContent()?.trim()
        if (!value.isNullOrEmpty() && value != "null") {
            return value
        }
    }
    return null
}

private fun JsonObject.timestampMillis(): Long? {
    val raw = stringForAny(
        "createdAtMs",
        "created_at_ms",
        "timestampMs",
        "timestamp_ms",
        "createdAt",
        "created_at",
        "timestamp"
    ) ?: return null
    val numeric = raw.toLongOrNull() ?: return null
    return if (numeric in 1L..9_999_999_999L) numeric * 1000L else numeric
}

private fun JsonObject.intForAny(vararg keys: String): Int? {
    for (key in keys) {
        val value = this[key]?.jsonPrimitiveOrNullContent()?.trim()?.toIntOrNull()
        if (value != null) return value
    }
    return null
}

private fun JsonElement.safeJsonObject(): JsonObject? = runCatching { jsonObject }.getOrNull()

private fun JsonElement.safeJsonArray(): JsonArray? = runCatching { jsonArray }.getOrNull()

private fun JsonElement.safeStringArray(): List<String>? = safeJsonArray()?.mapNotNull {
    it.jsonPrimitiveOrNullContent()
}

private fun JsonElement.reasoningTextParts(): List<String> = when {
    safeJsonArray() != null -> safeJsonArray()
        .orEmpty()
        .mapNotNull { element ->
            element.jsonPrimitiveOrNullContent()
                ?: element.safeJsonObject()?.stringForAny("text", "summary", "content", "message")
        }
    else -> listOfNotNull(jsonPrimitiveOrNullContent() ?: safeJsonObject()?.stringForAny("text", "summary", "content", "message"))
}

private fun JsonElement.approvalDisplayText(): String? = when {
    safeJsonArray() != null -> safeJsonArray()
        ?.mapNotNull { it.jsonPrimitiveOrNullContent() ?: it.safeJsonObject()?.toString() }
        ?.joinToString(" ")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    else -> jsonPrimitiveOrNullContent()?.takeIf { it.isNotBlank() } ?: safeJsonObject()?.toString()
}

private fun JsonElement.jsonPrimitiveOrNull(): JsonPrimitive? =
    runCatching { jsonPrimitive }.getOrNull()

private fun JsonElement.jsonPrimitiveOrNullContent(): String? =
    runCatching { jsonPrimitive.contentOrNull ?: jsonPrimitive.content }.getOrNull()

private fun String?.cleanPathOrNull(): String? =
    this?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }

private fun joinHostPath(basePath: String, childName: String): String =
    if (basePath == "/") "/${childName.trim('/')}" else "${basePath.trimEnd('/')}/${childName.trim('/')}"

private fun String.projectNameFromCwd(): String =
    trim().trimEnd('/').substringAfterLast('/').takeIf { it.isNotBlank() } ?: this

private fun String.normalizedType(): String = lowercase().replace("_", "").replace("-", "")

private fun List<ChatMessage>.replaceAt(index: Int, message: ChatMessage): List<ChatMessage> =
    mapIndexed { currentIndex, current -> if (currentIndex == index) message else current }

private fun ChatMessage.mergedWith(completed: ChatMessage): ChatMessage =
    completed.copy(createdAtMs = createdAtMs ?: completed.createdAtMs)

private fun String.normalizedMessageText(): String =
    trim().replace(Regex("\\s+"), " ")

private fun String?.compatibleWith(other: String?): Boolean =
    this == null || other == null || this == other
