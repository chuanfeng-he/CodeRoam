package com.local.codexremote.ui

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.local.codexremote.crypto.AndroidIdentityStore
import com.local.codexremote.crypto.IdentityStoreProvider
import com.local.codexremote.crypto.SecureSession
import com.local.codexremote.data.ChatMessage
import com.local.codexremote.data.ClientAuth
import com.local.codexremote.data.CodexJson
import com.local.codexremote.data.PairingPayload
import com.local.codexremote.data.RpcError
import com.local.codexremote.data.RpcMessage
import com.local.codexremote.data.SecureEnvelope
import com.local.codexremote.data.SecureReady
import com.local.codexremote.data.ServerHello
import com.local.codexremote.net.RelayClient
import com.local.codexremote.net.RelayCloseEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class CodexRemoteViewModel(application: Application) : AndroidViewModel(application) {
    private val json = CodexJson
    private val identityStoreProvider = IdentityStoreProvider {
        AndroidIdentityStore(application)
    }
    private val savedPairingStore = SavedPairingStore(application, json)
    private val runtimeSettingsStore = RuntimeSettingsStore(application, json)
    private var secureSession: SecureSession? = null
    private var relayClient: RelayClient? = null
    private var currentPairingProfile: PairingConnectionProfile? = null
    private var reconnectJob: Job? = null
    private var suppressRelayDisconnect = false
    private var nextRpcId = 1
    private var pendingPrompt: String? = null
    private var pendingTurnAttachments: List<PendingAttachmentUi> = emptyList()
    private var pendingGoalObjective: String? = null
    private var activeAssistantItemId: String? = null
    private var activeReasoningItemId: String? = null
    private var activeTurnId: String? = null
    private var nextEditDraftRequestId = 1L
    private val connectionGeneration = AtomicInteger(0)
    private val pendingRequests = mutableMapOf<Int, RpcPurpose>()

    var state by mutableStateOf(CodexRemoteUiState())
        private set

    init {
        val settings = runtimeSettingsStore.load()
        state = state.copy(
            selectedModelId = settings.selectedModelId,
            selectedAccessMode = settings.selectedAccessMode,
            permissionLabel = settings.selectedAccessMode.label,
            selectedReasoningEffort = settings.selectedReasoningEffort,
            fastModeEnabled = settings.fastModeEnabled,
            selectedTheme = settings.selectedTheme,
            selectedProjectCwd = settings.selectedProjectCwd,
            planModeEnabled = settings.planModeEnabled,
            goalModeEnabled = settings.goalModeEnabled,
            projectName = settings.selectedProjectCwd?.projectNameFromPath() ?: state.projectName,
            showArchivedThreads = settings.showArchivedThreads,
            archivedThreadIds = settings.archivedThreadIds
        )
        savedPairingStore.load()?.let { saved ->
            currentPairingProfile = saved.toConnectionProfile()
            state = state.copy(
                hasSavedPairing = true,
                hostDisplayName = saved.displayName.ifBlank { state.hostDisplayName },
                connectionPhase = ConnectionPhase.Connecting,
                status = "正在重新连接"
            )
            connectWithProfile(saved.toConnectionProfile(), validateExpiry = false, isReconnect = true)
        }
    }

    fun updatePairingText(text: String) {
        state = state.copy(pairingText = text)
    }

    fun connectFromPairingText() {
        connectFromPairingText(state.pairingText)
    }

    fun connectFromPairingText(pairingText: String) {
        val raw = pairingText.trim()
        if (raw.isBlank()) {
            state = state.copy(status = "请先粘贴或扫描配对二维码内容")
            return
        }
        runCatching {
            json.decodeFromString<PairingPayload>(raw).toConnectionProfile()
        }.onSuccess { profile ->
            if (!profile.isUsableForNewPairing()) {
                state = state.copy(status = "配对二维码已过期，请在 Linux 端重新生成")
                return
            }
            savedPairingStore.clear()
            reconnectJob?.cancel()
            currentPairingProfile = profile
            state = state.copy(
                pairingText = raw,
                hasSavedPairing = false,
                connected = false,
                secure = false,
                connectionPhase = ConnectionPhase.Connecting,
                reconnectAttempt = 0,
                reconnectMessage = null,
                hostDisplayName = profile.displayName.ifBlank { "linux-codex" },
                status = "正在连接 Codex"
            )
            connectWithProfile(profile, validateExpiry = true, isReconnect = false)
        }.onFailure {
            state = state.copy(status = it.message ?: "配对失败")
        }
    }

    fun sendPrompt(prompt: String) {
        val clean = prompt.trim()
        if (clean.isEmpty()) return
        val promptForTurn = state.promptForSend(clean)
        val attachmentsForTurn = state.pendingAttachments.filter { it.isReady }
        val goalForTurn = state.goalForPrompt(clean)
        if (!state.secure) {
            state = state.withPromptSendBlocked()
            return
        }
        val userMessageText = clean.withAttachmentSummary(attachmentsForTurn)
        state = state.copy(
            messages = state.messages + ChatMessage(
                role = ChatMessage.Role.User,
                text = userMessageText,
                createdAtMs = System.currentTimeMillis()
            ),
            isGenerating = true,
            status = "Codex 正在处理",
            recoverableError = null,
            pendingPromptAfterThreadLoss = null,
            activeGoal = goalForTurn ?: state.activeGoal,
            pendingAttachments = state.pendingAttachments.filterNot { it.id in attachmentsForTurn.map { attachment -> attachment.id } },
            attachmentUploadError = null
        )
        val threadId = state.activeThreadId
        if (threadId == null) {
            val cwd = state.currentWorkspaceCwd()
            if (cwd != null) {
                validateCwdBeforeThreadStart(cwd, promptForTurn, attachmentsForTurn, goalForTurn?.objective)
            } else {
                startThreadForPrompt(promptForTurn, attachmentsForTurn, goalForTurn?.objective)
            }
        } else {
            goalForTurn?.let { goal ->
                sendRpc(
                    "thread/goal/set",
                    buildThreadGoalSetParams(threadId, goal.objective, goal.status),
                    RpcPurpose.SetGoal(goal.objective)
                )
            }
            sendTurn(threadId, promptForTurn, attachmentsForTurn)
        }
    }

    fun editUserMessage(entry: TimelineEntry.Text) {
        if (entry.role != TimelineTextRole.User) return
        if (state.isGenerating) {
            state = state.copy(status = "请先停止生成再编辑消息")
            return
        }
        if (!state.secure) {
            state = state.copy(status = "连接后才能编辑消息")
            return
        }
        val threadId = entry.threadId ?: state.activeThreadId
        val turnId = entry.turnId
        if (threadId.isNullOrBlank() || turnId.isNullOrBlank()) {
            state = state.copy(status = "这条消息还不能回滚编辑")
            return
        }
        val rollbackTurns = countRollbackTurnsFromMessage(state.messages, threadId, turnId)
        if (rollbackTurns == null) {
            state = state.copy(status = "找不到可回滚的消息")
            return
        }
        val requestId = sendRpc(
            "thread/rollback",
            buildThreadRollbackParams(threadId, rollbackTurns),
            RpcPurpose.RollbackForEdit(threadId, turnId, entry.text)
        )
        state = if (requestId == null) {
            state.copy(status = "连接不可用，回滚失败")
        } else {
            state.copy(status = "正在回滚这次提问")
        }
    }

    fun consumeEditDraftRequest(requestId: Long) {
        if (state.editDraftRequest?.id == requestId) {
            state = state.copy(editDraftRequest = null)
        }
    }

    fun interrupt() {
        val threadId = state.activeThreadId ?: return
        state = state.copy(status = "正在停止生成", isGenerating = false)
        sendRpc(
            "turn/interrupt",
            buildJsonObject {
                put("threadId", threadId)
                activeTurnId?.let { put("turnId", it) }
            },
            RpcPurpose.Interrupt
        )
    }

    fun startNewConversation() {
        pendingPrompt = null
        activeAssistantItemId = null
        activeReasoningItemId = null
        activeTurnId = null
        state = state.forNewConversation()
    }

    fun handleRecoveryAction(action: RecoveryAction) {
        val error = state.recoverableError
        val prompt = state.pendingPromptAfterThreadLoss
        when (action) {
            RecoveryAction.StartNewWithPrompt -> {
                state = state.withoutRecoverableError()
                if (prompt.isNullOrBlank()) {
                    refreshThreads()
                    return
                }
                if (!state.secure) {
                    state = state.withPromptSendBlocked()
                    return
                }
                activeAssistantItemId = null
                activeReasoningItemId = null
                activeTurnId = null
                state = state.copy(activeThreadId = null, isGenerating = true, status = "正在新建会话")
                startThreadForPrompt(prompt, emptyList(), null)
            }
            RecoveryAction.ArchiveThread -> {
                val threadId = error?.threadId ?: state.activeThreadId
                state = state.withoutRecoverableError()
                if (threadId.isNullOrBlank()) {
                    refreshThreads()
                    return
                }
                state = state.copy(
                    archivedThreadIds = state.archivedThreadIds + threadId,
                    activeThreadId = if (state.activeThreadId == threadId) null else state.activeThreadId,
                    messages = if (state.activeThreadId == threadId) emptyList() else state.messages,
                    isGenerating = false,
                    status = "已归档会话"
                )
                saveRuntimeSettings()
                if (state.secure) {
                    sendRpc(
                        "thread/archive",
                        buildJsonObject { put("threadId", threadId) },
                        RpcPurpose.ArchiveThread(threadId)
                    )
                }
            }
            RecoveryAction.RefreshThreads -> {
                state = state.withoutRecoverableError()
                refreshThreads()
            }
            RecoveryAction.Dismiss -> {
                state = state.withoutRecoverableError()
            }
        }
    }

    fun respondToPendingApproval(decision: ApprovalDecision) {
        val approval = state.pendingApproval ?: return
        if (!state.secure) {
            state = state.withPromptSendBlocked()
            return
        }
        sendRpcResult(approval.requestId, buildApprovalResult(approval, decision))
        state = state.copy(
            pendingApproval = null,
            status = when (decision) {
                ApprovalDecision.Accept -> "已允许操作"
                ApprovalDecision.AcceptForSession -> "本会话已允许操作"
                ApprovalDecision.Decline, ApprovalDecision.Cancel -> "已拒绝操作"
            }
        )
    }

    fun respondToPendingInteraction(action: InteractionAction, answers: Map<String, List<String>>) {
        val interaction = state.pendingInteraction ?: return
        if (!state.secure) {
            state = state.withPromptSendBlocked()
            return
        }
        sendRpcResult(interaction.requestId, buildInteractionResult(interaction, answers, action))
        val previousPlanModeEnabled = state.planModeEnabled
        val nextState = state.afterPendingInteractionResponse(action, answers)
        state = nextState
        if (nextState.planModeEnabled != previousPlanModeEnabled) {
            saveRuntimeSettings()
        }
    }

    fun startPlanImplementation() {
        if (!state.secure) {
            state = state.withPromptSendBlocked()
            return
        }
        state = state.forPlanImplementation()
        saveRuntimeSettings()
        sendPrompt("Implement the plan.")
    }

    fun continuePlanning() {
        if (!state.secure) {
            state = state.withPromptSendBlocked()
            return
        }
        state = state.forContinuedPlanning()
        saveRuntimeSettings()
        sendPrompt("继续完善计划。")
    }

    fun dismissActivePlan() {
        state = state.copy(activePlan = null)
    }

    fun setPlanModeEnabled(enabled: Boolean) {
        state = state.withPlanModeEnabled(enabled)
        saveRuntimeSettings()
    }

    fun setGoalModeEnabled(enabled: Boolean) {
        state = state.withGoalModeEnabled(enabled)
        saveRuntimeSettings()
        if (!enabled && state.secure && state.activeThreadId != null) {
            clearGoal()
        }
    }

    fun refreshGoal() {
        val threadId = state.activeThreadId ?: run {
            state = state.copy(status = "请先选择或创建会话")
            return
        }
        sendRpc("thread/goal/get", buildThreadGoalGetParams(threadId), RpcPurpose.GetGoal)
    }

    fun setGoalObjective(objective: String) {
        val clean = objective.trim()
        if (clean.isEmpty()) return
        val threadId = state.activeThreadId ?: run {
            state = state.copy(status = "请先选择或创建会话")
            return
        }
        state = state.copy(status = "正在设置目标")
        sendRpc(
            "thread/goal/set",
            buildThreadGoalSetParams(threadId, clean, ThreadGoalStatus.InProgress),
            RpcPurpose.SetGoal(clean)
        )
    }

    fun clearGoal() {
        val threadId = state.activeThreadId ?: run {
            state = state.copy(activeGoal = null, status = "没有活动目标")
            return
        }
        state = state.copy(activeGoal = null, status = "正在清除目标")
        sendRpc("thread/goal/clear", buildThreadGoalClearParams(threadId), RpcPurpose.ClearGoal)
    }

    fun selectThread(threadId: String) {
        val thread = state.threads.firstOrNull { it.id == threadId }
        state = state.copy(
            activeThreadId = threadId,
            messages = emptyList(),
            activeGoal = null,
            isGenerating = false,
            isLoadingHistory = true,
            status = "正在打开 ${thread?.displayTitle() ?: threadId.take(15)}"
        )
        activeAssistantItemId = null
        activeReasoningItemId = null
        activeTurnId = null
        val resumeParams = buildJsonObject {
            buildThreadResumeParams(
                threadId,
                state.selectedModelId,
                state.selectedAccessMode,
                state.selectedReasoningEffort,
                state.fastModeEnabled
            ).forEach { (key, value) ->
                put(key, value)
            }
            put("excludeTurns", true)
            put("persistExtendedHistory", true)
        }
        sendRpc(
            "thread/resume",
            resumeParams,
            RpcPurpose.ResumeThread(threadId, resumeParams)
        )
        sendRpc(
            "thread/read",
            buildJsonObject {
                put("threadId", threadId)
                put("includeTurns", true)
            },
            RpcPurpose.ReadThread(threadId)
        )
        refreshGoal()
    }

    fun updateThreadSearchTerm(term: String) {
        state = state.copy(threadSearchTerm = term)
    }

    fun refreshThreads() {
        if (!state.secure) return
        loadThreads(append = false, cursor = null)
        state.activeThreadId?.let { threadId ->
            refreshActiveThread(threadId)
        }
    }

    fun loadMoreThreads() {
        val cursor = state.nextThreadCursor?.takeIf { it.isNotBlank() } ?: return
        if (!state.isLoadingThreads) {
            loadThreads(append = true, cursor = cursor)
        }
    }

    fun selectWorkflowSection(section: WorkflowSection) {
        state = state.withWorkflowSection(section)
        refreshWorkflowSection(force = false)
    }

    fun refreshWorkflowSection(force: Boolean = true) {
        if (!state.secure) return
        when (state.workflowSection) {
            WorkflowSection.Chat -> Unit
            WorkflowSection.Skills -> {
                if (force || state.skillsCatalog.groups.isEmpty() || state.skillsCatalog.isStale) {
                    loadSkills(forceReload = force)
                }
            }
            WorkflowSection.Plugins -> {
                if (force || state.pluginsCatalog.marketplaces.isEmpty() || state.pluginsCatalog.isStale) {
                    loadPlugins()
                }
            }
            WorkflowSection.Automation -> {
                val catalog = state.automationCatalog
                if (force || catalog.apps.isEmpty() || catalog.isStale) {
                    loadApps(append = false, cursor = null, forceRefetch = force)
                }
                if (force || catalog.mcpServers.isEmpty() || catalog.isStale) {
                    loadMcpServers(append = false, cursor = null)
                }
                if (force || catalog.hookGroups.isEmpty() || catalog.isStale) {
                    loadHooks()
                }
            }
        }
    }

    fun loadMoreAutomationApps() {
        if (!state.secure || state.automationCatalog.isLoadingApps) return
        val cursor = state.automationCatalog.nextAppsCursor?.takeIf { it.isNotBlank() } ?: return
        loadApps(append = true, cursor = cursor, forceRefetch = false)
    }

    fun loadMoreMcpServers() {
        if (!state.secure || state.automationCatalog.isLoadingMcp) return
        val cursor = state.automationCatalog.nextMcpCursor?.takeIf { it.isNotBlank() } ?: return
        loadMcpServers(append = true, cursor = cursor)
    }

    fun renameActiveThread(name: String) {
        val threadId = state.activeThreadId ?: return
        val clean = name.trim()
        if (clean.isEmpty()) return
        state = state.withRenamedThread(threadId, clean).copy(status = "正在重命名")
        sendRpc(
            "thread/name/set",
            buildJsonObject {
                put("threadId", threadId)
                put("name", clean)
            },
            RpcPurpose.RenameThread(threadId, clean)
        )
    }

    fun selectModel(modelId: String) {
        state = state.withSelectedModel(modelId)
        saveRuntimeSettings()
        applyRuntimeSelectionToActiveThread()
    }

    fun selectAccessMode(accessMode: AccessMode) {
        state = state.withSelectedAccessMode(accessMode)
        saveRuntimeSettings()
        applyRuntimeSelectionToActiveThread()
    }

    fun selectReasoningEffort(reasoningEffort: ReasoningEffort) {
        state = state.withSelectedReasoningEffort(reasoningEffort)
        saveRuntimeSettings()
        applyRuntimeSelectionToActiveThread()
    }

    fun setFastModeEnabled(enabled: Boolean) {
        state = state.withFastModeEnabled(enabled)
        saveRuntimeSettings()
        applyRuntimeSelectionToActiveThread()
    }

    fun selectTheme(theme: AppTheme) {
        state = state.withSelectedTheme(theme)
        saveRuntimeSettings()
    }

    fun selectProjectWorkspace(cwd: String) {
        val clean = cwd.normalizedAbsolutePathOrNull()
        if (clean == null) {
            state = state.copy(projectPathValidationError = "请输入 Linux 绝对路径")
            return
        }
        if (!state.secure) {
            applyValidatedProjectWorkspace(clean)
            return
        }
        state = state.copy(
            isValidatingProjectPath = true,
            projectPathValidationError = null,
            status = "正在校验项目路径"
        )
        val requestId = sendRpc("fs/getMetadata", buildGetMetadataParams(clean), RpcPurpose.ValidateProjectCwd(clean))
        if (requestId == null) {
            state = state.copy(
                isValidatingProjectPath = false,
                projectPathValidationError = "路径校验不可用",
                status = "路径校验不可用"
            )
        }
    }

    fun requestProjectPathSuggestions(inputPath: String) {
        val rawPath = inputPath.trim()
        if (!rawPath.startsWith("/")) {
            state = state.copy(
                projectPathSuggestions = null,
                projectPathSuggestionPath = null,
                projectPathSuggestionPrefix = "",
                isLoadingProjectPathSuggestions = false,
                projectPathSuggestionError = null
            )
            return
        }
        val request = buildProjectPathCompletionRequest(rawPath)
        if (!state.secure) {
            state = state.copy(
                projectPathSuggestions = null,
                projectPathSuggestionPath = request.directoryPath,
                projectPathSuggestionPrefix = request.prefix,
                isLoadingProjectPathSuggestions = false,
                projectPathSuggestionError = "连接后可推荐路径"
            )
            return
        }
        if (
            state.projectPathSuggestionPath == request.directoryPath &&
            state.projectPathSuggestionPrefix == request.prefix &&
            state.isLoadingProjectPathSuggestions
        ) {
            return
        }
        state = state.copy(
            projectPathSuggestions = state.projectPathSuggestions?.takeIf { it.path == request.directoryPath },
            projectPathSuggestionPath = request.directoryPath,
            projectPathSuggestionPrefix = request.prefix,
            isLoadingProjectPathSuggestions = true,
            projectPathSuggestionError = null
        )
        val requestId = sendRpc(
            "fs/readDirectory",
            buildReadDirectoryParams(request.directoryPath),
            RpcPurpose.ReadProjectDirectory(request.directoryPath, request.prefix)
        )
        if (requestId == null) {
            state = state.copy(
                isLoadingProjectPathSuggestions = false,
                projectPathSuggestionError = "路径推荐不可用"
            )
        }
    }

    fun refreshModels() {
        if (state.secure) {
            loadModels()
        }
    }

    fun setShowArchivedThreads(showArchived: Boolean) {
        state = state.copy(showArchivedThreads = showArchived)
        saveRuntimeSettings()
    }

    fun archiveActiveThread() {
        val threadId = state.activeThreadId ?: return
        state = state.copy(
            archivedThreadIds = state.archivedThreadIds + threadId,
            activeThreadId = null,
            messages = emptyList(),
            isGenerating = false,
            status = "已归档会话"
        )
        saveRuntimeSettings()
        sendRpc(
            "thread/archive",
            buildJsonObject { put("threadId", threadId) },
            RpcPurpose.ArchiveThread(threadId)
        )
    }

    fun unarchiveThread(threadId: String) {
        state = state.copy(archivedThreadIds = state.archivedThreadIds - threadId, status = "已恢复会话")
        saveRuntimeSettings()
        sendRpc(
            "thread/unarchive",
            buildJsonObject { put("threadId", threadId) },
            RpcPurpose.UnarchiveThread(threadId)
        )
    }

    fun addAttachments(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (!state.secure) {
            state = state.copy(attachmentUploadError = "连接后可添加照片和文件")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            uris.forEach { uri ->
                val readResult = runCatching {
                    val resolver = getApplication<Application>().contentResolver
                    val displayName = resolver.displayNameForUri(uri)
                    val mimeType = resolver.getType(uri)
                    val bytes = resolver.openInputStream(uri)?.use { input -> input.readBytes() }
                        ?: error("无法读取文件内容")
                    AttachmentReadResult(
                        displayName = displayName,
                        mimeType = mimeType,
                        bytes = bytes
                    )
                }
                withContext(Dispatchers.Main) {
                    readResult
                        .onSuccess { result -> stageAttachmentUpload(result) }
                        .onFailure { failure ->
                            state = state.copy(
                                attachmentUploadError = failure.message ?: "附件读取失败",
                                status = "附件读取失败"
                            )
                        }
                }
            }
        }
    }

    fun removeAttachment(id: String) {
        state = state.withoutPendingAttachment(id)
    }

    fun clearPairing() {
        savedPairingStore.clear()
        reconnectJob?.cancel()
        suppressRelayDisconnect = true
        relayClient?.close()
        relayClient = null
        secureSession = null
        currentPairingProfile = null
        pendingRequests.clear()
        state = CodexRemoteUiState(
            pairingText = state.pairingText,
            selectedModelId = state.selectedModelId,
            selectedAccessMode = state.selectedAccessMode,
            permissionLabel = state.selectedAccessMode.label,
            availableModels = state.availableModels,
            selectedReasoningEffort = state.selectedReasoningEffort,
            fastModeEnabled = state.fastModeEnabled,
            selectedTheme = state.selectedTheme,
            selectedProjectCwd = state.selectedProjectCwd,
            planModeEnabled = state.planModeEnabled,
            goalModeEnabled = state.goalModeEnabled,
            projectName = state.projectName,
            projectWorkspaces = state.projectWorkspaces,
            archivedThreadIds = state.archivedThreadIds,
            showArchivedThreads = state.showArchivedThreads,
            status = "已清除配对，请重新扫码"
        )
        suppressRelayDisconnect = false
    }

    private fun connectWithProfile(
        profile: PairingConnectionProfile,
        validateExpiry: Boolean,
        isReconnect: Boolean
    ) {
        val generation = connectionGeneration.incrementAndGet()
        val previousRelay = relayClient
        relayClient = null
        previousRelay?.close()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                if (validateExpiry) {
                    require(profile.isUsableForNewPairing()) { "配对二维码已过期，请在 Linux 端重新生成" }
                } else {
                    require(profile.isUsableForReconnect()) { "保存的配对资料不完整，请重新扫码" }
                }
                currentPairingProfile = profile
                val pairing = profile.toPairingPayload(
                    expiresAtOverride = if (validateExpiry) profile.expiresAt else Long.MAX_VALUE
                )
                val session = SecureSession(pairing, identityStoreProvider.get(), json)
                secureSession = session
                relayClient = RelayClient(
                    relayUrl = profile.relay,
                    sessionId = profile.sessionId,
                    relayHostOverrides = profile.relayHostOverrides,
                    onOpen = {
                        if (isCurrentConnection(generation)) {
                            setConnectionState(
                                connected = true,
                                secure = false,
                                phase = ConnectionPhase.Handshaking,
                                status = "已连接 relay，正在加密握手"
                            )
                            sendPlain(json.encodeToString(session.createClientHello()))
                        }
                    },
                    onText = { text ->
                        if (isCurrentConnection(generation)) {
                            handleRelayText(text, isReconnect)
                        }
                    },
                    onClosed = { event ->
                        if (isCurrentConnection(generation)) {
                            handleRelayDisconnect(event.toRelayDisconnect())
                        }
                    },
                    onError = { event ->
                        if (isCurrentConnection(generation)) {
                            handleRelayDisconnect(event.toRelayDisconnect())
                        }
                    }
                ).also { it.connect() }
            }.onFailure {
                if (!isCurrentConnection(generation)) {
                    return@onFailure
                }
                if (isReconnect && state.hasSavedPairing) {
                    handleRelayDisconnect(RelayDisconnect.Failure(it.message ?: "连接失败"))
                } else {
                    setConnectionState(
                        connected = false,
                        secure = false,
                        phase = ConnectionPhase.Disconnected,
                        status = it.message ?: "配对失败"
                    )
                }
            }
        }
    }

    private fun isCurrentConnection(generation: Int): Boolean =
        isCurrentConnectionGeneration(generation, connectionGeneration.get())

    private fun handleRelayDisconnect(disconnect: RelayDisconnect) {
        if (suppressRelayDisconnect) return
        viewModelScope.launch(Dispatchers.Main) {
            state = state.afterRelayClosed(disconnect)
            pendingRequests.clear()
            if (disconnect.requiresFreshPairing) {
                savedPairingStore.clear()
                currentPairingProfile = null
                reconnectJob?.cancel()
                return@launch
            }
            if (state.connectionPhase == ConnectionPhase.Reconnecting) {
                scheduleReconnect()
            }
        }
    }

    private fun scheduleReconnect() {
        val saved = savedPairingStore.load() ?: currentPairingProfile?.toSavedPairingProfile() ?: return
        reconnectJob?.cancel()
        val delayMs = reconnectDelayMillis(state.reconnectAttempt)
        reconnectJob = viewModelScope.launch(Dispatchers.IO) {
            delay(delayMs)
            connectWithProfile(saved.toConnectionProfile(), validateExpiry = false, isReconnect = true)
        }
    }

    private fun markSecureReady(isReconnect: Boolean) {
        val saved = currentPairingProfile?.toSavedPairingProfile()
        if (saved != null) {
            savedPairingStore.save(saved)
        }
        reconnectJob?.cancel()
        val hasVisibleWorkspace = state.activeThreadId != null ||
            state.messages.isNotEmpty() ||
            state.pendingApproval != null ||
            state.pendingInteraction != null ||
            state.activePlan != null
        val softReconnect = isReconnect && hasVisibleWorkspace
        state = state.afterSecureChannelReady(
            savedDisplayName = saved?.displayName,
            softReconnect = softReconnect
        )
        if (!softReconnect || state.threads.isEmpty()) {
            loadThreads(append = false, cursor = null)
        }
        if (!softReconnect) {
            loadModels()
        }
        if (state.workflowSection != WorkflowSection.Chat) {
            refreshWorkflowSection(force = false)
        }
        val threadId = state.activeThreadId
        if (threadId != null) {
            resumeActiveThreadAfterReconnect(threadId, readHistory = state.shouldSyncActiveThreadAfterReconnect())
        }
    }

    private fun handleRelayText(text: String, isReconnect: Boolean) {
        runCatching {
            val kind = json.parseToJsonElement(text).jsonObject["kind"]?.jsonPrimitive?.content
            when (kind) {
                "serverHello" -> {
                    val auth: ClientAuth = secureSession!!.handleServerHello(json.decodeFromString<ServerHello>(text))
                    sendPlain(json.encodeToString(auth))
                }
                "secureReady" -> {
                    secureSession!!.handleSecureReady(json.decodeFromString<SecureReady>(text))
                    markSecureReady(isReconnect)
                }
                "encryptedEnvelope" -> {
                    val plaintext = secureSession!!.decrypt(json.decodeFromString<SecureEnvelope>(text))
                    handleRpcText(plaintext)
                }
            }
        }.onFailure {
            setStatus(connected = state.connected, secure = state.secure, status = it.message ?: "消息处理失败")
        }
    }

    private fun handleRpcText(text: String) {
        val message = json.decodeFromString<RpcMessage>(text)
        when {
            message.result != null -> handleRpcResult(message)
            message.error != null -> {
                val id = message.id?.jsonPrimitive?.int
                val purpose = if (id != null) pendingRequests.remove(id) else null
                val errorMessage = message.error.message
                if (isThreadNotFoundError(errorMessage)) {
                    handleThreadNotFound(purpose, errorMessage)
                    return
                }
                if (purpose is RpcPurpose.StartThread && retryRuntimeRequestWithFallback(
                        method = "thread/start",
                        params = purpose.params,
                        message = errorMessage,
                        status = "正在启动会话",
                        nextPurpose = { purpose.copy(params = it) }
                    )
                ) {
                    return
                }
                if (purpose is RpcPurpose.ResumeThread && retryRuntimeRequestWithFallback(
                        method = "thread/resume",
                        params = purpose.params,
                        message = errorMessage,
                        status = "正在同步会话",
                        nextPurpose = { purpose.copy(params = it) }
                    )
                ) {
                    return
                }
                if (purpose is RpcPurpose.TurnStart && retryTurnStartWithFallback(purpose, errorMessage)) {
                    return
                }
                if (purpose is RpcPurpose.TurnStart) {
                    handleRuntimeFailure(
                        RuntimeFailure(
                            kind = if (errorMessage.contains("compact", ignoreCase = true)) {
                                RecoverableErrorKind.CompactFailed
                            } else {
                                RecoverableErrorKind.TurnFailed
                            },
                            threadId = purpose.threadId,
                            turnId = activeTurnId,
                            detail = errorMessage
                        ),
                        prompt = purpose.prompt
                    )
                    return
                }
                if (purpose is RpcPurpose.RollbackForEdit) {
                    state = state.copy(status = "回滚失败：$errorMessage")
                    return
                }
                if (id != null) {
                    handleRpcError(purpose)
                    if (purpose.isWorkflowCatalogRequest()) {
                        return
                    }
                }
                showSystemMessage("Codex 错误：$errorMessage")
            }
            message.id != null && message.method != null -> {
                if (handleServerRequest(message)) {
                    return
                }
            }
            message.method == "error" -> {
                val error = message.params
                    ?.jsonObject
                    ?.get("error")
                    ?.jsonObject
                    ?.get("message")
                    ?.jsonPrimitive
                    ?.content
                    ?: message.params?.toString()
                    ?: "Codex 返回错误"
                if (isThreadNotFoundError(error)) {
                    handleThreadNotFound(null, error)
                    return
                }
                val failure = parseRuntimeFailure("error", message.params?.jsonObject)
                if (failure != null) {
                    handleRuntimeFailure(failure)
                    return
                }
                showSystemMessage("Codex 错误：$error")
            }
            message.method == "host/appServerExited" -> {
                val failure = parseRuntimeFailure("host/appServerExited", message.params?.jsonObject) ?: return
                handleRuntimeFailure(failure)
            }
            message.method == "thread/closed" || message.method == "threadClosed" -> {
                val failure = parseThreadClosedFailure(message.params?.jsonObject, activeThreadId = state.activeThreadId)
                handleRuntimeFailure(failure, prompt = null)
            }
            message.method == "thread/archived" -> {
                val threadId = message.params?.jsonObject?.get("threadId")?.jsonPrimitive?.content
                    ?: message.params?.jsonObject?.get("thread_id")?.jsonPrimitive?.content
                    ?: return
                state = state.copy(
                    archivedThreadIds = state.archivedThreadIds + threadId,
                    activeThreadId = if (state.activeThreadId == threadId) null else state.activeThreadId,
                    messages = if (state.activeThreadId == threadId) emptyList() else state.messages,
                    isGenerating = if (state.activeThreadId == threadId) false else state.isGenerating,
                    status = "会话已归档"
                )
                saveRuntimeSettings()
            }
            message.method == "thread/unarchived" || message.method == "thread/unarchive" -> {
                val threadId = message.params?.jsonObject?.get("threadId")?.jsonPrimitive?.content
                    ?: message.params?.jsonObject?.get("thread_id")?.jsonPrimitive?.content
                    ?: return
                state = state.copy(archivedThreadIds = state.archivedThreadIds - threadId, status = "会话已恢复")
                saveRuntimeSettings()
            }
            message.method == "turn/plan/updated" -> {
                val params = message.params?.jsonObject ?: return
                val plan = parseTurnPlan(params) ?: return
                val threadId = plan.threadId
                if (threadId != null && state.activeThreadId != null && threadId != state.activeThreadId) return
                state = state.copy(activePlan = plan, status = "计划已更新")
            }
            message.method == "turn/diff/updated" -> {
                val diff = parseTurnDiff(message.params?.jsonObject ?: return) ?: return
                if (diff.threadId != null && state.activeThreadId != null && diff.threadId != state.activeThreadId) return
                state = state.copy(turnDiff = diff, status = "Diff 已更新")
            }
            message.method == "item/fileChange/patchUpdated" -> {
                val diff = parseTurnDiff(message.params?.jsonObject ?: return) ?: return
                if (diff.threadId != null && state.activeThreadId != null && diff.threadId != state.activeThreadId) return
                state = state.copy(turnDiff = diff, status = "文件变更已更新")
            }
            message.method == "item/commandExecution/outputDelta" -> {
                val output = parseCommandOutputDelta(message.params?.jsonObject ?: return) ?: return
                if (output.threadId != null && state.activeThreadId != null && output.threadId != state.activeThreadId) return
                appendCommandOutputDelta(output)
            }
            message.method?.isReasoningDeltaMethod() == true -> {
                val reasoning = parseReasoningDelta(message.params?.jsonObject ?: return) ?: return
                if (reasoning.threadId != null && state.activeThreadId != null && reasoning.threadId != state.activeThreadId) return
                appendReasoningDelta(reasoning)
            }
            message.method in setOf(
                "warning",
                "guardianWarning",
                "model/rerouted",
                "context/compacted",
                "thread/compacted"
            ) -> {
                val method = message.method ?: return
                val notice = parseRuntimeNotice(method, message.params?.jsonObject) ?: return
                if (notice.threadId != null && state.activeThreadId != null && notice.threadId != state.activeThreadId) return
                state = state.copy(
                    runtimeNotices = (state.runtimeNotices + notice).takeLast(6),
                    status = notice.title
                )
            }
            message.method == "serverRequest/resolved" -> {
                val requestId = message.params
                    ?.jsonObject
                    ?.get("requestId")
                    ?.jsonPrimitive
                    ?.int
                if (requestId != null && state.pendingApproval?.requestId == requestId) {
                    state = state.copy(pendingApproval = null)
                }
                if (requestId != null && state.pendingInteraction?.requestId == requestId) {
                    state = state.copy(pendingInteraction = null)
                }
            }
            message.method == "skills/changed" -> {
                state = state.copy(skillsCatalog = state.skillsCatalog.copy(isStale = true))
            }
            message.method == "app/list/updated" -> {
                val params = message.params?.jsonObject ?: return
                state = state.withUpdatedAppsFromNotification(params)
            }
            message.method == "mcpServer/startupStatus/updated" -> {
                val params = message.params?.jsonObject ?: return
                state = state.withUpdatedMcpServerStatus(params)
            }
            message.method == "thread/settings/updated" -> {
                applyThreadSettingsUpdate(message.params?.jsonObject)
            }
            message.method == "thread/goal/updated" || message.method == "goal/updated" -> {
                val params = message.params?.jsonObject ?: return
                state = state.withGoalUpdatedFromNotification(params)
                saveRuntimeSettings()
            }
            message.method == "thread/goal/cleared" || message.method == "goal/cleared" -> {
                val params = message.params?.jsonObject ?: buildJsonObject {}
                state = state.withGoalClearedFromNotification(params)
                saveRuntimeSettings()
            }
            message.method == "turn/started" -> {
                val params = message.params?.jsonObject
                activeTurnId = params?.get("turn")?.jsonObject?.get("id")?.jsonPrimitive?.content
                    ?: params?.get("turnId")?.jsonPrimitive?.content
                state = state.copy(status = "Codex 正在处理", isGenerating = true)
            }
            message.method == "thread/started" -> {
                val thread = message.params?.jsonObject?.get("thread")?.jsonObject
                if (thread != null) {
                    upsertThreadSummary(thread)
                }
            }
            message.method == "thread/name/updated" -> {
                val params = message.params?.jsonObject
                val threadId = params?.get("threadId")?.jsonPrimitive?.content
                    ?: params?.get("thread_id")?.jsonPrimitive?.content
                val name = params?.get("name")?.jsonPrimitive?.content
                if (threadId != null && !name.isNullOrBlank()) {
                    state = state.withRenamedThread(threadId, name)
                }
            }
            message.method == "item/agentMessage/delta" -> {
                val params = message.params?.jsonObject ?: return
                val threadId = params["threadId"]?.jsonPrimitive?.content
                if (threadId != null && threadId != state.activeThreadId) return
                val itemId = params["itemId"]?.jsonPrimitive?.content
                val delta = params["delta"]?.jsonPrimitive?.content.orEmpty()
                if (delta.isNotBlank()) {
                    appendAssistantDelta(itemId, delta)
                }
            }
            message.method == "item/completed" -> {
                val item = message.params?.jsonObject?.get("item")?.jsonObject
                if (item != null) {
                    val messageFromItem = parseThreadHistoryItem(
                        threadId = state.activeThreadId,
                        turnId = activeTurnId,
                        item = item
                    )
                    if (messageFromItem != null) {
                        val completed = if (messageFromItem.createdAtMs == null) {
                            messageFromItem.copy(createdAtMs = System.currentTimeMillis())
                        } else {
                            messageFromItem
                        }
                        state = state.copy(messages = mergeCompletedMessage(state.messages, completed))
                    }
                }
            }
            message.method == "turn/completed" -> {
                val failure = parseTurnCompletedFailure(
                    params = message.params?.jsonObject,
                    fallbackThreadId = state.activeThreadId,
                    fallbackTurnId = activeTurnId
                )
                if (failure != null) {
                activeAssistantItemId = null
                activeReasoningItemId = null
                activeTurnId = null
                handleRuntimeFailure(failure)
                return
            }
            activeAssistantItemId = null
            activeReasoningItemId = null
            activeTurnId = null
            state = state.copy(status = "任务完成", isGenerating = false)
            }
            message.method == "turn/failed" -> {
                val failure = parseRuntimeFailure("turn/failed", message.params?.jsonObject)
                    ?: RuntimeFailure(
                        kind = RecoverableErrorKind.TurnFailed,
                        threadId = state.activeThreadId,
                        turnId = activeTurnId,
                        detail = message.params?.toString() ?: "任务失败"
                    )
                activeTurnId = null
                activeReasoningItemId = null
                handleRuntimeFailure(failure)
            }
        }
    }

    private fun handleServerRequest(message: RpcMessage): Boolean {
        val id = runCatching { message.id?.jsonPrimitive?.int }.getOrNull() ?: return false
        val method = message.method ?: return false
        val params = runCatching { message.params?.jsonObject }.getOrNull()
        val approval = parsePendingApproval(id, method, params)
        if (approval != null) {
            state = state.copy(pendingApproval = approval, pendingInteraction = null, status = approval.title)
            return true
        }
        val interaction = parsePendingInteraction(id, method, params)
        if (interaction != null) {
            state = state.copy(pendingInteraction = interaction, pendingApproval = null, status = interaction.title)
            return true
        }
        sendRpcError(id, -32601, "Unsupported server request: $method")
        state = state.copy(
            runtimeNotices = (
                state.runtimeNotices + RuntimeNotice(
                    kind = RuntimeNoticeKind.UnsupportedRequest,
                    threadId = null,
                    title = "请求暂不支持",
                    body = "$method 已安全拒绝"
                )
            ).takeLast(6),
            status = "请求暂不支持"
        )
        return true
    }

    private fun handleRpcResult(message: RpcMessage) {
        val id = message.id?.jsonPrimitive?.int ?: return
        val purpose = pendingRequests.remove(id)
        val result = message.result!!.jsonObject
        when (purpose) {
            is RpcPurpose.ListThreads -> {
                val page = parseThreadListPage(result)
                val threads = if (purpose.append) {
                    (state.threads + page.threads).distinctBy { it.id }
                } else {
                    page.threads
                }
                state = state.copy(
                    threads = threads,
                    nextThreadCursor = page.nextCursor,
                    projectWorkspaces = buildProjectWorkspaces(threads, state.skillsCatalog, state.automationCatalog),
                    isLoadingThreads = false,
                    isSoftSyncing = false,
                    lastSyncedAtMs = System.currentTimeMillis(),
                    projectName = state.selectedProjectCwd?.projectNameFromPath()
                        ?: threads.firstNotNullOfOrNull { it.cwd?.projectNameFromPath() }
                        ?: state.projectName,
                    status = if (state.secure) "已同步会话" else state.status
                )
            }
            is RpcPurpose.ReadThread -> {
                val threadObject = result["thread"]?.jsonObject
                if (threadObject != null && state.activeThreadId == purpose.threadId) {
                    val messages = parseThreadHistoryMessages(purpose.threadId, threadObject)
                    upsertThreadSummary(threadObject)
                    state = state.copy(
                        messages = messages,
                        isLoadingHistory = false,
                        isSoftSyncing = false,
                        lastSyncedAtMs = System.currentTimeMillis(),
                        projectName = threadObject["cwd"]?.jsonPrimitive?.content?.projectNameFromPath() ?: state.projectName,
                        status = "已打开会话"
                    )
                }
            }
            is RpcPurpose.ResumeThread -> {
                val threadObject = result["thread"]?.jsonObject
                if (threadObject != null) {
                    upsertThreadSummary(threadObject)
                    if (state.activeThreadId == purpose.threadId && state.messages.isEmpty()) {
                        val messages = parseThreadHistoryMessages(purpose.threadId, threadObject)
                        if (messages.isNotEmpty()) {
                            state = state.copy(messages = messages, isLoadingHistory = false, status = "已打开会话")
                        }
                    }
                }
                state = state.copy(isSoftSyncing = false, lastSyncedAtMs = System.currentTimeMillis())
            }
            is RpcPurpose.RenameThread -> {
                state = state.withRenamedThread(purpose.threadId, purpose.name).copy(status = "已重命名")
            }
            is RpcPurpose.RollbackForEdit -> {
                activeAssistantItemId = null
                activeReasoningItemId = null
                activeTurnId = null
                state = if (state.activeThreadId == purpose.threadId) {
                    state.copy(
                        messages = removeMessagesFromRolledBackTurns(
                            state.messages,
                            threadId = purpose.threadId,
                            turnId = purpose.turnId
                        ),
                        isGenerating = false,
                        isSoftSyncing = false,
                        editDraftRequest = EditDraftRequest(nextEditDraftRequestId++, purpose.text),
                        pendingApproval = state.pendingApproval?.takeUnless { it.threadId == purpose.threadId },
                        pendingInteraction = state.pendingInteraction?.takeUnless { it.threadId == purpose.threadId },
                        activePlan = state.activePlan?.takeUnless { it.threadId == purpose.threadId },
                        turnDiff = state.turnDiff?.takeUnless { it.threadId == purpose.threadId },
                        recoverableError = state.recoverableError?.takeUnless { it.threadId == purpose.threadId },
                        runtimeNotices = state.runtimeNotices.filterNot { it.threadId == purpose.threadId },
                        status = "已回滚，可修改后重新发送"
                    )
                } else {
                    state.copy(isSoftSyncing = false, status = "已回滚")
                }
            }
            is RpcPurpose.ListModels -> {
                val parsedModels = parseRuntimeModelOptions(result).filterNot { it.hidden }
                val models = parsedModels.ifEmpty { state.availableModels }
                val selected = models.firstOrNull { it.id == state.selectedModelId } ?: models.firstOrNull()
                val supportedEfforts = selected?.supportedReasoningEfforts.orEmpty()
                val selectedEffort = state.selectedReasoningEffort.takeIf { it in supportedEfforts }
                    ?: supportedEfforts.firstOrNull()
                    ?: ReasoningEffort.Medium
                state = state.copy(
                    availableModels = models,
                    selectedModelId = selected?.id ?: state.selectedModelId,
                    modelLabel = selected?.label ?: state.modelLabel,
                    selectedReasoningEffort = selectedEffort,
                    isLoadingModels = false,
                    modelLoadError = null
                )
            }
            is RpcPurpose.ListSkills -> {
                val catalog = parseSkillsCatalog(result)
                state = state.copy(
                    skillsCatalog = catalog.copy(
                        isLoading = false,
                        error = null,
                        lastSyncedAtMs = System.currentTimeMillis(),
                        isStale = false
                    ),
                    projectWorkspaces = buildProjectWorkspaces(state.threads, catalog, state.automationCatalog)
                )
            }
            is RpcPurpose.ListPlugins -> {
                val catalog = parsePluginCatalog(result)
                state = state.copy(
                    pluginsCatalog = catalog.copy(
                        isLoading = false,
                        error = null,
                        lastSyncedAtMs = System.currentTimeMillis(),
                        isStale = false
                    )
                )
            }
            is RpcPurpose.ListApps -> {
                val page = parseAppsCatalog(result)
                val apps = if (purpose.append) {
                    (state.automationCatalog.apps + page.apps).distinctBy { it.id }
                } else {
                    page.apps
                }
                state = state.copy(
                    automationCatalog = state.automationCatalog.copy(
                        apps = apps,
                        nextAppsCursor = page.nextCursor,
                        isLoadingApps = false,
                        appsError = null,
                        lastSyncedAtMs = System.currentTimeMillis(),
                        isStale = false
                    )
                )
            }
            is RpcPurpose.ListMcpServers -> {
                val page = parseMcpCatalog(result)
                val servers = if (purpose.append) {
                    (state.automationCatalog.mcpServers + page.servers).distinctBy { it.name }
                } else {
                    page.servers
                }
                state = state.copy(
                    automationCatalog = state.automationCatalog.copy(
                        mcpServers = servers,
                        nextMcpCursor = page.nextCursor,
                        isLoadingMcp = false,
                        mcpError = null,
                        lastSyncedAtMs = System.currentTimeMillis(),
                        isStale = false
                    )
                )
            }
            is RpcPurpose.ListHooks -> {
                val catalog = parseHooksCatalog(result)
                state = state.copy(
                    automationCatalog = state.automationCatalog.copy(
                        hookGroups = catalog.groups,
                        isLoadingHooks = false,
                        hooksError = null,
                        lastSyncedAtMs = System.currentTimeMillis(),
                        isStale = false
                    ).also { updated ->
                        state = state.copy(projectWorkspaces = buildProjectWorkspaces(state.threads, state.skillsCatalog, updated))
                    }
                )
            }
            is RpcPurpose.ArchiveThread -> {
                state = state.copy(status = "已归档会话")
            }
            is RpcPurpose.UnarchiveThread -> {
                state = state.copy(status = "已恢复会话")
            }
            RpcPurpose.GetGoal -> {
                state = state.copy(
                    activeGoal = parseThreadGoal(result),
                    status = "目标已同步"
                )
            }
            is RpcPurpose.SetGoal -> {
                state = state.copy(
                    activeGoal = parseThreadGoal(result) ?: ThreadGoalUi(
                        objective = purpose.objective,
                        status = ThreadGoalStatus.InProgress
                    ),
                    status = "目标已设置"
                )
            }
            RpcPurpose.ClearGoal -> {
                state = state.copy(activeGoal = null, status = "目标已清除")
            }
            is RpcPurpose.ReadProjectDirectory -> {
                if (
                    state.projectPathSuggestionPath == purpose.path &&
                    state.projectPathSuggestionPrefix == purpose.prefix
                ) {
                    state = state.copy(
                        projectPathSuggestions = parseFileDirectory(result, purpose.path),
                        isLoadingProjectPathSuggestions = false,
                        projectPathSuggestionError = null
                    )
                }
            }
            is RpcPurpose.ValidateProjectCwd -> {
                val metadata = parseFileMetadata(result, fallbackPath = purpose.cwd)
                if (metadata.isUsableCwd) {
                    applyValidatedProjectWorkspace(metadata.path)
                } else {
                    state = state.copy(
                        isValidatingProjectPath = false,
                        projectPathValidationError = "路径不是目录：${purpose.cwd}",
                        status = "项目路径不可用"
                    )
                }
            }
            is RpcPurpose.ValidateThreadStartCwd -> {
                val metadata = parseFileMetadata(result, fallbackPath = purpose.cwd)
                if (metadata.isUsableCwd) {
                    startThreadForPrompt(purpose.prompt, purpose.attachments, purpose.goalObjective)
                } else {
                    pendingPrompt = null
                    pendingTurnAttachments = emptyList()
                    pendingGoalObjective = null
                    state = state.copy(
                        isGenerating = false,
                        isValidatingProjectPath = false,
                        projectPathValidationError = "路径不是目录：${purpose.cwd}",
                        status = "项目路径不可用"
                    )
                }
            }
            is RpcPurpose.CreateAttachmentDirectory -> Unit
            is RpcPurpose.WriteAttachment -> {
                state = state.withAttachmentUploadCompleted(purpose.attachmentId)
            }
            is RpcPurpose.StartThread -> handleStartedThread(result)
            is RpcPurpose.TurnStart, RpcPurpose.Interrupt, null -> {
                if (result["data"] != null || result["threads"] != null) {
                    val page = parseThreadListPage(result)
                    state = state.copy(threads = page.threads, nextThreadCursor = page.nextCursor)
                } else {
                    handleStartedThread(result)
                }
            }
        }
    }

    private fun handleStartedThread(result: JsonObject) {
        val threadObject = result["thread"]?.jsonObject
        val newThreadId = threadObject?.get("id")?.jsonPrimitive?.content
        syncRuntimeSettingsFromResult(result)
        if (newThreadId != null) {
            state = state.copy(activeThreadId = newThreadId)
            upsertThreadSummary(threadObject)
            pendingPrompt?.let { prompt ->
                val attachments = pendingTurnAttachments
                val goalObjective = pendingGoalObjective
                pendingPrompt = null
                pendingTurnAttachments = emptyList()
                pendingGoalObjective = null
                if (!goalObjective.isNullOrBlank()) {
                    sendRpc(
                        "thread/goal/set",
                        buildThreadGoalSetParams(newThreadId, goalObjective, ThreadGoalStatus.InProgress),
                        RpcPurpose.SetGoal(goalObjective)
                    )
                }
                sendTurn(newThreadId, prompt, attachments)
            }
        }
    }

    private fun handleRpcError(purpose: RpcPurpose?) {
        state = when (purpose) {
            is RpcPurpose.ListThreads -> state.copy(isLoadingThreads = false)
            is RpcPurpose.ReadThread, is RpcPurpose.ResumeThread -> state.copy(isLoadingHistory = false, isSoftSyncing = false)
            is RpcPurpose.ListModels -> state.withModelLoadFailure("模型列表加载失败")
            is RpcPurpose.ListSkills -> state.copy(
                skillsCatalog = state.skillsCatalog.withWorkflowLoadFailure("技能列表加载失败")
            )
            is RpcPurpose.ListPlugins -> state.copy(
                pluginsCatalog = state.pluginsCatalog.withWorkflowLoadFailure("插件列表加载失败")
            )
            is RpcPurpose.ListApps -> state.copy(
                automationCatalog = state.automationCatalog.withAppsLoadFailure("App 列表加载失败")
            )
            is RpcPurpose.ListMcpServers -> state.copy(
                automationCatalog = state.automationCatalog.withMcpLoadFailure("MCP 状态加载失败")
            )
            is RpcPurpose.ListHooks -> state.copy(
                automationCatalog = state.automationCatalog.withHooksLoadFailure("Hook 列表加载失败")
            )
            is RpcPurpose.TurnStart -> state.copy(isGenerating = false, status = "任务启动失败")
            is RpcPurpose.ArchiveThread -> state.copy(
                archivedThreadIds = state.archivedThreadIds - purpose.threadId,
                status = "归档失败"
            )
            is RpcPurpose.UnarchiveThread -> state.copy(
                archivedThreadIds = state.archivedThreadIds + purpose.threadId,
                status = "恢复归档失败"
            )
            RpcPurpose.GetGoal -> state.copy(status = "目标同步失败")
            is RpcPurpose.SetGoal -> state.copy(status = "目标设置失败")
            RpcPurpose.ClearGoal -> state.copy(status = "目标清除失败")
            is RpcPurpose.ReadProjectDirectory -> {
                if (
                    state.projectPathSuggestionPath == purpose.path &&
                    state.projectPathSuggestionPrefix == purpose.prefix
                ) {
                    state.copy(
                        isLoadingProjectPathSuggestions = false,
                        projectPathSuggestionError = "目录无法读取"
                    )
                } else {
                    state
                }
            }
            is RpcPurpose.ValidateProjectCwd -> state.copy(
                isValidatingProjectPath = false,
                projectPathValidationError = "路径不存在或不可访问：${purpose.cwd}",
                status = "项目路径不可用"
            )
            is RpcPurpose.ValidateThreadStartCwd -> {
                pendingPrompt = null
                pendingTurnAttachments = emptyList()
                pendingGoalObjective = null
                state.copy(
                    isGenerating = false,
                    isValidatingProjectPath = false,
                    projectPathValidationError = "路径不存在或不可访问：${purpose.cwd}",
                    status = "项目路径不可用"
                )
            }
            is RpcPurpose.CreateAttachmentDirectory -> state
            is RpcPurpose.WriteAttachment -> state.withAttachmentUploadFailed(purpose.attachmentId, "附件上传失败")
            is RpcPurpose.RollbackForEdit -> state.copy(status = "回滚失败，消息未修改")
            else -> state
        }
        saveRuntimeSettings()
    }

    private fun upsertThreadSummary(threadObject: JsonObject) {
        val summary = parseThreadSummaries(JsonArray(listOf(threadObject))).firstOrNull() ?: return
        state = state.copy(
            threads = (listOf(summary) + state.threads.filterNot { it.id == summary.id }),
            projectName = summary.cwd?.projectNameFromPath() ?: state.projectName
        )
    }

    private fun appendAssistantDelta(itemId: String?, delta: String) {
        val messages = state.messages
        val shouldAppendToLast = itemId != null &&
            itemId == activeAssistantItemId &&
            messages.lastOrNull()?.role == ChatMessage.Role.Assistant
        if (itemId != null) {
            activeAssistantItemId = itemId
        }
        state = if (shouldAppendToLast) {
            state.copy(messages = messages.dropLast(1) + messages.last().copy(text = messages.last().text + delta))
        } else {
            state.copy(
                messages = messages + ChatMessage(
                    role = ChatMessage.Role.Assistant,
                    text = delta,
                    threadId = state.activeThreadId,
                    turnId = activeTurnId,
                    itemId = itemId,
                    createdAtMs = System.currentTimeMillis()
                )
            )
        }
    }

    private fun appendReasoningDelta(reasoning: ReasoningDelta) {
        val itemId = reasoning.itemId
        val messages = state.messages
        val shouldAppendToLast = itemId != null &&
            itemId == activeReasoningItemId &&
            messages.lastOrNull()?.isReasoningMessage() == true
        if (itemId != null) {
            activeReasoningItemId = itemId
        }
        state = if (shouldAppendToLast) {
            val last = messages.last()
            state.copy(
                messages = messages.dropLast(1) + last.copy(text = last.text + reasoning.delta),
                status = "思考已同步"
            )
        } else {
            state.copy(
                messages = messages + ChatMessage(
                    role = ChatMessage.Role.System,
                    text = "思考\n${reasoning.delta}",
                    threadId = reasoning.threadId ?: state.activeThreadId,
                    turnId = reasoning.turnId ?: activeTurnId,
                    itemId = itemId,
                    createdAtMs = System.currentTimeMillis()
                ),
                status = "思考已同步"
            )
        }
    }

    private fun appendCommandOutputDelta(output: CommandOutputDelta) {
        val itemId = output.itemId
        val messages = state.messages
        val last = messages.lastOrNull()
        state = if (itemId != null && last?.role == ChatMessage.Role.System && last.itemId == itemId) {
            state.copy(
                messages = messages.dropLast(1) + last.copy(text = last.text + output.delta),
                status = "命令输出已更新"
            )
        } else {
            state.copy(
                messages = messages + ChatMessage(
                    role = ChatMessage.Role.System,
                    text = "命令输出\n${output.delta}",
                    threadId = output.threadId ?: state.activeThreadId,
                    turnId = output.turnId ?: activeTurnId,
                    itemId = itemId,
                    createdAtMs = System.currentTimeMillis()
                ),
                status = "命令输出已更新"
            )
        }
    }

    private fun showSystemMessage(text: String, keepGenerating: Boolean = false) {
        state = state.copy(
            status = if (keepGenerating) state.status else text,
            messages = state.messages + ChatMessage(ChatMessage.Role.System, text, createdAtMs = System.currentTimeMillis()),
            isGenerating = if (keepGenerating) state.isGenerating else false
        )
    }

    private fun sendTurn(threadId: String, prompt: String, attachments: List<PendingAttachmentUi> = emptyList()) {
        val params = buildTurnStartParams(
            threadId = threadId,
            prompt = prompt,
            modelId = state.selectedModelId,
            accessMode = state.selectedAccessMode,
            reasoningEffort = state.selectedReasoningEffort,
            fastModeEnabled = state.fastModeEnabled,
            includeApprovalPolicy = true,
            includeSandboxPolicy = true,
            attachments = attachments
        )
        sendRpc("turn/start", params, RpcPurpose.TurnStart(params, threadId, prompt, attachments))
    }

    private fun validateCwdBeforeThreadStart(
        cwd: String,
        prompt: String,
        attachments: List<PendingAttachmentUi>,
        goalObjective: String?
    ) {
        state = state.copy(
            isValidatingProjectPath = true,
            projectPathValidationError = null,
            status = "正在校验项目路径"
        )
        val requestId = sendRpc(
            "fs/getMetadata",
            buildGetMetadataParams(cwd),
            RpcPurpose.ValidateThreadStartCwd(cwd, prompt, attachments, goalObjective)
        )
        if (requestId == null) {
            state = state.copy(
                isGenerating = false,
                isValidatingProjectPath = false,
                projectPathValidationError = "路径校验不可用",
                status = "路径校验不可用"
            )
        }
    }

    private fun startThreadForPrompt(
        prompt: String,
        attachments: List<PendingAttachmentUi>,
        goalObjective: String?
    ) {
        pendingPrompt = prompt
        pendingTurnAttachments = attachments
        pendingGoalObjective = goalObjective
        val params = buildThreadStartParams(
            modelId = state.selectedModelId,
            accessMode = state.selectedAccessMode,
            reasoningEffort = state.selectedReasoningEffort,
            fastModeEnabled = state.fastModeEnabled,
            cwd = state.currentWorkspaceCwd()
        )
        sendRpc(
            "thread/start",
            params,
            RpcPurpose.StartThread(params)
        )
    }

    private fun applyValidatedProjectWorkspace(cwd: String) {
        state = state.withSelectedProjectCwd(cwd).copy(
            activeThreadId = null,
            activeGoal = null,
            messages = emptyList(),
            isLoadingHistory = false
        )
        activeAssistantItemId = null
        activeReasoningItemId = null
        activeTurnId = null
        saveRuntimeSettings()
        if (state.secure && state.workflowSection != WorkflowSection.Chat) {
            refreshWorkflowSection(force = true)
        }
    }

    private fun stageAttachmentUpload(result: AttachmentReadResult) {
        val id = "att_${UUID.randomUUID().toString().replace("-", "")}"
        val directory = "/tmp/codex-remote-uploads/$id"
        val hostPath = "$directory/${result.displayName.sanitizedHostFileName()}"
        val attachment = PendingAttachmentUi(
            id = id,
            displayName = result.displayName,
            mimeType = result.mimeType,
            hostPath = hostPath,
            status = AttachmentUploadStatus.Uploading
        )
        state = state.copy(
            pendingAttachments = state.pendingAttachments + attachment,
            attachmentUploadError = null,
            status = "正在上传附件"
        )
        sendRpc("fs/createDirectory", buildCreateDirectoryParams(directory), RpcPurpose.CreateAttachmentDirectory(id))
        val requestId = sendRpc(
            "fs/writeFile",
            buildWriteFileParams(hostPath, result.bytes),
            RpcPurpose.WriteAttachment(id)
        )
        if (requestId == null) {
            state = state.withAttachmentUploadFailed(id, "附件上传不可用")
        }
    }

    private fun loadThreads(append: Boolean, cursor: String?) {
        state = state.copy(isLoadingThreads = true, status = "正在同步会话")
        sendRpc(
            "thread/list",
            buildThreadListParams(limit = 40, cursor = cursor, searchTerm = state.threadSearchTerm),
            RpcPurpose.ListThreads(append)
        )
    }

    private fun refreshActiveThread(threadId: String) {
        state = if (state.messages.isEmpty()) {
            state.copy(isLoadingHistory = true, status = "正在同步当前会话")
        } else {
            state.copy(isSoftSyncing = true, status = "正在同步当前会话")
        }
        sendRpc(
            "thread/read",
            buildJsonObject {
                put("threadId", threadId)
                put("includeTurns", true)
            },
            RpcPurpose.ReadThread(threadId)
        )
        refreshGoal()
    }

    private fun resumeActiveThreadAfterReconnect(threadId: String, readHistory: Boolean) {
        val params = buildThreadResumeParams(
            threadId,
            state.selectedModelId,
            state.selectedAccessMode,
            state.selectedReasoningEffort,
            state.fastModeEnabled
        )
        sendRpc(
            "thread/resume",
            params,
            RpcPurpose.ResumeThread(threadId, params)
        )
        if (readHistory) {
            state = if (state.messages.isEmpty()) {
                state.copy(isLoadingHistory = true)
            } else {
                state.copy(isSoftSyncing = true, status = "已重连，正在同步消息")
            }
            sendRpc(
                "thread/read",
                buildJsonObject {
                    put("threadId", threadId)
                    put("includeTurns", true)
                },
                RpcPurpose.ReadThread(threadId)
            )
        }
    }

    private fun loadModels() {
        state = state.copy(isLoadingModels = true, modelLoadError = null)
        val requestId = sendRpc("model/list", buildModelListParams(), RpcPurpose.ListModels)
        if (requestId == null) {
            state = state.withModelLoadFailure("模型列表加载失败")
        }
    }

    private fun loadSkills(forceReload: Boolean) {
        state = state.copy(skillsCatalog = state.skillsCatalog.copy(isLoading = true, error = null))
        val requestId = sendRpc(
            "skills/list",
            buildSkillsListParams(currentWorkflowCwd(), forceReload),
            RpcPurpose.ListSkills
        )
        if (requestId == null) {
            state = state.copy(skillsCatalog = state.skillsCatalog.withWorkflowLoadFailure("技能列表加载失败"))
        }
    }

    private fun loadPlugins() {
        state = state.copy(pluginsCatalog = state.pluginsCatalog.copy(isLoading = true, error = null))
        val requestId = sendRpc(
            "plugin/list",
            buildPluginListParams(currentWorkflowCwd()),
            RpcPurpose.ListPlugins
        )
        if (requestId == null) {
            state = state.copy(pluginsCatalog = state.pluginsCatalog.withWorkflowLoadFailure("插件列表加载失败"))
        }
    }

    private fun loadApps(append: Boolean, cursor: String?, forceRefetch: Boolean) {
        state = state.copy(
            automationCatalog = state.automationCatalog.copy(isLoadingApps = true, appsError = null)
        )
        val requestId = sendRpc(
            "app/list",
            buildAppsListParams(state.activeThreadId, cursor, forceRefetch),
            RpcPurpose.ListApps(append)
        )
        if (requestId == null) {
            state = state.copy(automationCatalog = state.automationCatalog.withAppsLoadFailure("App 列表加载失败"))
        }
    }

    private fun loadMcpServers(append: Boolean, cursor: String?) {
        state = state.copy(
            automationCatalog = state.automationCatalog.copy(isLoadingMcp = true, mcpError = null)
        )
        val requestId = sendRpc(
            "mcpServerStatus/list",
            buildMcpServerStatusListParams(cursor),
            RpcPurpose.ListMcpServers(append)
        )
        if (requestId == null) {
            state = state.copy(automationCatalog = state.automationCatalog.withMcpLoadFailure("MCP 状态加载失败"))
        }
    }

    private fun loadHooks() {
        state = state.copy(
            automationCatalog = state.automationCatalog.copy(isLoadingHooks = true, hooksError = null)
        )
        val requestId = sendRpc(
            "hooks/list",
            buildHooksListParams(currentWorkflowCwd()),
            RpcPurpose.ListHooks
        )
        if (requestId == null) {
            state = state.copy(automationCatalog = state.automationCatalog.withHooksLoadFailure("Hook 列表加载失败"))
        }
    }

    private fun retryTurnStartWithFallback(purpose: RpcPurpose.TurnStart, message: String): Boolean {
        return retryRuntimeRequestWithFallback(
            method = "turn/start",
            params = purpose.params,
            message = message,
            status = "Codex 正在处理",
            nextPurpose = { purpose.copy(params = it) }
        )
    }

    private fun retryRuntimeRequestWithFallback(
        method: String,
        params: JsonObject,
        message: String,
        status: String,
        nextPurpose: (JsonObject) -> RpcPurpose
    ): Boolean {
        if (!message.isRuntimePayloadError()) return false
        val nextParams = params.nextRuntimeCompatibilityFallback() ?: return false
        sendRpc(method, nextParams, nextPurpose(nextParams))
        state = state.copy(status = status)
        return true
    }

    private fun handleThreadNotFound(purpose: RpcPurpose?, message: String) {
        val prompt = when (purpose) {
            is RpcPurpose.TurnStart -> purpose.prompt
            else -> pendingPrompt
        }
        pendingPrompt = null
        activeAssistantItemId = null
        activeReasoningItemId = null
        activeTurnId = null
        state = state.withThreadNotFoundRecovery(prompt, message)
        if (state.secure) {
            loadThreads(append = false, cursor = null)
        }
    }

    private fun handleRuntimeFailure(failure: RuntimeFailure, prompt: String? = latestUserPromptForFailure()) {
        if (failure.threadId != null && state.activeThreadId != null && failure.threadId != state.activeThreadId) {
            return
        }
        if (failure.willRetry) {
            state = state.copy(
                runtimeNotices = (
                    state.runtimeNotices + RuntimeNotice(
                        kind = RuntimeNoticeKind.Warning,
                        threadId = failure.threadId,
                        title = "Codex 正在重试",
                        body = failure.detail
                    )
                ).takeLast(6),
                status = "Codex 正在重试"
            )
            return
        }
        activeAssistantItemId = null
        activeReasoningItemId = null
        activeTurnId = null
        state = state.withRecoverableFailure(
            kind = failure.kind,
            threadId = failure.threadId ?: state.activeThreadId,
            turnId = failure.turnId,
            prompt = prompt,
            detail = failure.detail
        )
    }

    private fun latestUserPromptForFailure(): String? =
        state.messages.lastOrNull { it.role == ChatMessage.Role.User }?.text

    private fun currentWorkflowCwd(): String? =
        state.threads.firstOrNull { it.id == state.activeThreadId }?.cwd
            ?: state.selectedProjectCwd
            ?: state.threads.firstNotNullOfOrNull { it.cwd }

    private fun applyRuntimeSelectionToActiveThread() {
        val threadId = state.activeThreadId ?: return
        if (!state.secure) return
        if (state.isGenerating) return
        val params = buildThreadResumeParams(
            threadId,
            state.selectedModelId,
            state.selectedAccessMode,
            state.selectedReasoningEffort,
            state.fastModeEnabled
        )
        sendRpc(
            "thread/resume",
            params,
            RpcPurpose.ResumeThread(threadId, params)
        )
        state = state.copy(status = "已更新运行设置")
    }

    private fun applyThreadSettingsUpdate(params: JsonObject?) {
        val settings = params?.get("threadSettings")?.jsonObject ?: return
        val model = settings["model"]?.jsonPrimitive?.content
        val reasoningEffort = ReasoningEffort.fromWireName(settings["reasoningEffort"]?.jsonPrimitive?.content)
            ?: ReasoningEffort.fromWireName(settings["reasoning_effort"]?.jsonPrimitive?.content)
        val fastModeEnabled = settings["profile"]?.jsonPrimitive?.content == "fast"
        val approvalPolicy = settings["approvalPolicy"]?.jsonPrimitive?.content
        val sandboxType = runCatching {
            settings["sandboxPolicy"]?.jsonObject?.get("type")?.jsonPrimitive?.content
        }.getOrNull()
        val accessMode = when {
            approvalPolicy == AccessMode.FullAccess.approvalPolicy || sandboxType == "dangerFullAccess" -> AccessMode.FullAccess
            approvalPolicy == AccessMode.OnRequest.approvalPolicy -> AccessMode.OnRequest
            else -> null
        }
        state = state.copy(
            selectedModelId = model?.takeIf { it.isNotBlank() } ?: state.selectedModelId,
            modelLabel = model?.takeIf { it.isNotBlank() } ?: state.modelLabel,
            selectedAccessMode = accessMode ?: state.selectedAccessMode,
            selectedReasoningEffort = reasoningEffort ?: state.selectedReasoningEffort,
            fastModeEnabled = fastModeEnabled || state.fastModeEnabled,
            permissionLabel = accessMode?.label ?: state.permissionLabel
        )
        saveRuntimeSettings()
    }

    private fun syncRuntimeSettingsFromResult(result: JsonObject) {
        val model = result["model"]?.jsonPrimitive?.content
        val reasoningEffort = ReasoningEffort.fromWireName(result["reasoningEffort"]?.jsonPrimitive?.content)
            ?: ReasoningEffort.fromWireName(result["reasoning_effort"]?.jsonPrimitive?.content)
        val fastProfile = result["profile"]?.jsonPrimitive?.content == "fast"
        val approvalPolicy = result["approvalPolicy"]?.jsonPrimitive?.content
        val sandboxType = runCatching {
            result["sandbox"]?.jsonObject?.get("type")?.jsonPrimitive?.content
        }.getOrNull()
        val accessMode = when {
            approvalPolicy == AccessMode.FullAccess.approvalPolicy || sandboxType == "dangerFullAccess" -> AccessMode.FullAccess
            approvalPolicy == AccessMode.OnRequest.approvalPolicy -> AccessMode.OnRequest
            else -> null
        }
        if (!model.isNullOrBlank() || accessMode != null || reasoningEffort != null || fastProfile) {
            state = state.copy(
                selectedModelId = model ?: state.selectedModelId,
                modelLabel = model ?: state.modelLabel,
                selectedAccessMode = accessMode ?: state.selectedAccessMode,
                selectedReasoningEffort = reasoningEffort ?: state.selectedReasoningEffort,
                fastModeEnabled = fastProfile || state.fastModeEnabled,
                permissionLabel = accessMode?.label ?: state.permissionLabel
            )
            saveRuntimeSettings()
        }
    }

    private fun sendRpc(method: String, params: JsonObject, purpose: RpcPurpose? = null): Int? {
        val id = nextRpcId++
        val message = RpcMessage(
            id = kotlinx.serialization.json.JsonPrimitive(id),
            method = method,
            params = params
        )
        val plaintext = json.encodeToString(message)
        val envelope = secureSession?.encrypt(plaintext) ?: return null
        if (purpose != null) {
            pendingRequests[id] = purpose
        }
        sendPlain(json.encodeToString(envelope))
        return id
    }

    private fun sendRpcResult(id: Int, result: JsonObject) {
        val message = RpcMessage(
            id = kotlinx.serialization.json.JsonPrimitive(id),
            result = result
        )
        val plaintext = json.encodeToString(message)
        val envelope = secureSession?.encrypt(plaintext) ?: return
        sendPlain(json.encodeToString(envelope))
    }

    private fun sendRpcError(id: Int, code: Int, message: String) {
        val rpcMessage = RpcMessage(
            id = kotlinx.serialization.json.JsonPrimitive(id),
            error = RpcError(code = code, message = message)
        )
        val plaintext = json.encodeToString(rpcMessage)
        val envelope = secureSession?.encrypt(plaintext) ?: return
        sendPlain(json.encodeToString(envelope))
    }

    private fun sendPlain(text: String) {
        relayClient?.send(text)
    }

    private fun setStatus(connected: Boolean, secure: Boolean, status: String) {
        viewModelScope.launch(Dispatchers.Main) {
            state = state.copy(
                connected = connected,
                secure = secure,
                connectionPhase = when {
                    secure -> ConnectionPhase.Ready
                    connected -> ConnectionPhase.Handshaking
                    state.hasSavedPairing -> ConnectionPhase.Reconnecting
                    else -> ConnectionPhase.Disconnected
                },
                status = status,
                isGenerating = if (secure) state.isGenerating else false
            )
        }
    }

    private fun setConnectionState(
        connected: Boolean,
        secure: Boolean,
        phase: ConnectionPhase,
        status: String
    ) {
        viewModelScope.launch(Dispatchers.Main) {
            state = state.copy(
                connected = connected,
                secure = secure,
                connectionPhase = phase,
                status = status,
                isGenerating = if (secure) state.isGenerating else false
            )
        }
    }

    private fun saveRuntimeSettings() {
        runtimeSettingsStore.save(
            RuntimeSettingsSnapshot(
                selectedModelId = state.selectedModelId,
                selectedAccessMode = state.selectedAccessMode,
                selectedReasoningEffort = state.selectedReasoningEffort,
                fastModeEnabled = state.fastModeEnabled,
                selectedTheme = state.selectedTheme,
                selectedProjectCwd = state.selectedProjectCwd,
                planModeEnabled = state.planModeEnabled,
                goalModeEnabled = state.goalModeEnabled,
                showArchivedThreads = state.showArchivedThreads,
                archivedThreadIds = state.archivedThreadIds
            )
        )
    }

    override fun onCleared() {
        suppressRelayDisconnect = true
        reconnectJob?.cancel()
        relayClient?.close()
        super.onCleared()
    }
}

private fun String.projectNameFromPath(): String? =
    trim().trimEnd('/').substringAfterLast('/').takeIf { it.isNotBlank() }

private fun String.normalizedAbsolutePathOrNull(): String? {
    val trimmed = trim()
    if (!trimmed.startsWith("/")) return null
    return trimmed.trimEnd('/').ifEmpty { "/" }
}

private data class AttachmentReadResult(
    val displayName: String,
    val mimeType: String?,
    val bytes: ByteArray
)

private fun ContentResolver.displayNameForUri(uri: Uri): String {
    query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) {
            val name = cursor.getString(index)
            if (!name.isNullOrBlank()) return name
        }
    }
    return uri.lastPathSegment
        ?.substringAfterLast('/')
        ?.takeIf { it.isNotBlank() }
        ?: "attachment"
}

private fun String.sanitizedHostFileName(): String {
    val clean = trim().replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_')
    return clean.ifEmpty { "attachment" }
}

private fun String.withAttachmentSummary(attachments: List<PendingAttachmentUi>): String {
    if (attachments.isEmpty()) return this
    return buildString {
        append(this@withAttachmentSummary)
        append("\n\n")
        attachments.forEach { attachment ->
            append("[附件: ")
            append(attachment.displayName)
            append(" -> ")
            append(attachment.hostPath)
            append("]\n")
        }
    }.trim()
}

private fun RelayCloseEvent.toRelayDisconnect(): RelayDisconnect {
    val eventCode = code
    return if (eventCode != null) {
        RelayDisconnect.Close(eventCode, displayMessage)
    } else {
        RelayDisconnect.Failure(displayMessage)
    }
}

private fun reconnectDelayMillis(attempt: Int): Long = when {
    attempt <= 1 -> 1_000L
    attempt == 2 -> 2_000L
    attempt == 3 -> 5_000L
    attempt == 4 -> 10_000L
    else -> 30_000L
}

private fun String.isRuntimePayloadError(): Boolean {
    val lower = lowercase()
    return lower.contains("unknown") ||
        lower.contains("unrecognized") ||
        lower.contains("invalid") ||
        lower.contains("field") ||
        lower.contains("schema") ||
        lower.contains("sandbox") ||
        lower.contains("approval") ||
        lower.contains("model") ||
        lower.contains("reasoning") ||
        lower.contains("profile") ||
        lower.contains("fast")
}

private sealed class RpcPurpose {
    data class ListThreads(val append: Boolean) : RpcPurpose()
    data class StartThread(val params: JsonObject) : RpcPurpose()
    data class ReadThread(val threadId: String) : RpcPurpose()
    data class ResumeThread(val threadId: String, val params: JsonObject = buildJsonObject {}) : RpcPurpose()
    data class RenameThread(val threadId: String, val name: String) : RpcPurpose()
    data class RollbackForEdit(val threadId: String, val turnId: String, val text: String) : RpcPurpose()
    data object ListModels : RpcPurpose()
    data object ListSkills : RpcPurpose()
    data object ListPlugins : RpcPurpose()
    data class ListApps(val append: Boolean) : RpcPurpose()
    data class ListMcpServers(val append: Boolean) : RpcPurpose()
    data object ListHooks : RpcPurpose()
    data class ArchiveThread(val threadId: String) : RpcPurpose()
    data class UnarchiveThread(val threadId: String) : RpcPurpose()
    data object GetGoal : RpcPurpose()
    data class SetGoal(val objective: String) : RpcPurpose()
    data object ClearGoal : RpcPurpose()
    data class ReadProjectDirectory(val path: String, val prefix: String) : RpcPurpose()
    data class ValidateProjectCwd(val cwd: String) : RpcPurpose()
    data class ValidateThreadStartCwd(
        val cwd: String,
        val prompt: String,
        val attachments: List<PendingAttachmentUi>,
        val goalObjective: String?
    ) : RpcPurpose()
    data class CreateAttachmentDirectory(val attachmentId: String) : RpcPurpose()
    data class WriteAttachment(val attachmentId: String) : RpcPurpose()
    data class TurnStart(
        val params: JsonObject,
        val threadId: String,
        val prompt: String,
        val attachments: List<PendingAttachmentUi> = emptyList()
    ) : RpcPurpose()
    data object Interrupt : RpcPurpose()
}

private fun RpcPurpose?.isWorkflowCatalogRequest(): Boolean = when (this) {
    RpcPurpose.ListSkills,
    RpcPurpose.ListPlugins,
    is RpcPurpose.ListApps,
    is RpcPurpose.ListMcpServers,
    RpcPurpose.ListHooks -> true
    else -> false
}

private fun String.isReasoningDeltaMethod(): Boolean =
    contains("reasoning", ignoreCase = true) && contains("delta", ignoreCase = true)
