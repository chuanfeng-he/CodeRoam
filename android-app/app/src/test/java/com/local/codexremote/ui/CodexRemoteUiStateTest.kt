package com.local.codexremote.ui

import com.local.codexremote.data.ChatMessage
import com.local.codexremote.data.CodexJson
import com.local.codexremote.data.ThreadSummary
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexRemoteUiStateTest {
    @Test
    fun newConversationClearsActiveChatButKeepsConnectionAndThreads() {
        val threads = listOf(ThreadSummary(id = "thread_alpha", title = "已有任务"))
        val before = CodexRemoteUiState(
            connected = true,
            secure = true,
            status = "Codex 正在处理",
            activeThreadId = "thread_alpha",
            threads = threads,
            messages = listOf(ChatMessage(ChatMessage.Role.User, "继续任务")),
            isGenerating = true
        )

        val after = before.forNewConversation()

        assertNull(after.activeThreadId)
        assertEquals(emptyList<ChatMessage>(), after.messages)
        assertFalse(after.isGenerating)
        assertEquals("已加密连接", after.status)
        assertEquals(threads, after.threads)
        assertEquals(true, after.connected)
        assertEquals(true, after.secure)
    }

    @Test
    fun ordinaryDisconnectKeepsWorkspaceVisibleForSavedPairing() {
        val before = CodexRemoteUiState(
            connected = true,
            secure = true,
            hasSavedPairing = true,
            connectionPhase = ConnectionPhase.Ready,
            messages = listOf(ChatMessage(ChatMessage.Role.Assistant, "仍然可见"))
        )

        val after = before.afterRelayClosed(RelayDisconnect.Close(code = 1006, reason = "network lost"))

        assertFalse(after.connected)
        assertFalse(after.secure)
        assertEquals(ConnectionPhase.Reconnecting, after.connectionPhase)
        assertFalse(after.shouldShowPairing)
        assertEquals(before.messages, after.messages)
    }

    @Test
    fun softReconnectReadyKeepsWorkspaceVisibleAndDoesNotForceHistoryLoading() {
        val approval = PendingApproval(
            requestId = 7,
            method = "item/commandExecution/requestApproval",
            kind = PendingApprovalKind.CommandExecution,
            title = "允许执行命令？",
            body = "pytest"
        )
        val interaction = PendingInteraction(
            requestId = 8,
            method = "item/tool/requestUserInput",
            kind = PendingInteractionKind.ToolUserInput,
            title = "需要输入",
            body = "继续吗？"
        )
        val plan = ActiveTurnPlan(
            threadId = "thread_1",
            turnId = "turn_1",
            explanation = "继续当前任务",
            steps = listOf(TurnPlanStepUi("保持工作区", TurnPlanStatus.Pending))
        )
        val messages = listOf(ChatMessage(ChatMessage.Role.Assistant, "当前输出", threadId = "thread_1"))
        val before = CodexRemoteUiState(
            connected = false,
            secure = false,
            hasSavedPairing = true,
            connectionPhase = ConnectionPhase.Reconnecting,
            activeThreadId = "thread_1",
            messages = messages,
            pendingApproval = approval,
            pendingInteraction = interaction,
            activePlan = plan,
            isLoadingHistory = true,
            reconnectAttempt = 2,
            reconnectMessage = "network lost"
        )

        val after = before.afterSecureChannelReady(
            savedDisplayName = "",
            softReconnect = true,
            nowMillis = 1770000000000L
        )

        assertTrue(after.connected)
        assertTrue(after.secure)
        assertEquals(ConnectionPhase.Ready, after.connectionPhase)
        assertEquals(messages, after.messages)
        assertEquals("thread_1", after.activeThreadId)
        assertEquals(approval, after.pendingApproval)
        assertEquals(interaction, after.pendingInteraction)
        assertEquals(plan, after.activePlan)
        assertFalse(after.isLoadingHistory)
        assertTrue(after.isSoftSyncing)
        assertEquals(0, after.reconnectAttempt)
        assertNull(after.reconnectMessage)
        assertEquals(1770000000000L, after.lastSyncedAtMs)
    }

    @Test
    fun reconnectSyncsActiveThreadEvenWhenMessagesAreCached() {
        assertTrue(
            CodexRemoteUiState(
                activeThreadId = "thread_1",
                messages = listOf(ChatMessage(ChatMessage.Role.Assistant, "cached"))
            ).shouldSyncActiveThreadAfterReconnect()
        )
        assertTrue(
            CodexRemoteUiState(
                activeThreadId = "thread_1",
                messages = emptyList()
            ).shouldSyncActiveThreadAfterReconnect()
        )
        assertFalse(CodexRemoteUiState(messages = listOf(ChatMessage(ChatMessage.Role.Assistant, "cached"))).shouldSyncActiveThreadAfterReconnect())
    }

    @Test
    fun timelineEntriesConvertHistoryMessagesIntoUnifiedStream() {
        val threadObject = CodexJson.parseToJsonElement(
            """
            {
              "turns": [
                {
                  "id": "turn_1",
                  "status": "failed",
                  "items": [
                    { "id": "item_user", "type": "userMessage", "text": "分析项目" },
                    { "id": "item_reasoning", "type": "reasoningSummary", "summary": ["先读代码"] },
                    { "id": "item_command", "type": "commandExecution", "command": "pytest", "cwd": "/tmp/project", "status": "completed", "exitCode": 0 },
                    { "id": "item_file", "type": "fileChange", "path": "app.py", "operation": "modified" },
                    { "id": "item_assistant", "type": "agentMessage", "text": "完成分析" }
                  ],
                  "error": { "message": "回归测试失败" }
                }
              ]
            }
            """.trimIndent()
        ).jsonObject
        val state = CodexRemoteUiState(
            activeThreadId = "thread_1",
            messages = parseThreadHistoryMessages("thread_1", threadObject)
        )

        val entries = state.timelineEntries

        assertTrue(entries.any { it is TimelineEntry.Text && it.role == TimelineTextRole.User && it.text == "分析项目" })
        assertTrue(entries.any { it is TimelineEntry.Reasoning && it.body == "先读代码" })
        assertTrue(entries.any { it is TimelineEntry.CommandExecution && it.commandLine.contains("pytest") })
        assertTrue(entries.any { it is TimelineEntry.FileChange && it.body.contains("app.py") })
        assertTrue(entries.any { it is TimelineEntry.Text && it.role == TimelineTextRole.Assistant && it.text == "完成分析" })
        assertTrue(entries.any { it is TimelineEntry.Failure && it.detail.contains("回归测试失败") })
    }

    @Test
    fun timelineEntriesIncludeInteractiveWorkflowEvents() {
        val approval = PendingApproval(
            requestId = 11,
            method = "item/commandExecution/requestApproval",
            kind = PendingApprovalKind.CommandExecution,
            title = "允许执行命令？",
            body = "命令：npm test"
        )
        val interaction = PendingInteraction(
            requestId = 12,
            method = "item/tool/requestUserInput",
            kind = PendingInteractionKind.ToolUserInput,
            title = "需要输入",
            body = "请选择方案"
        )
        val plan = ActiveTurnPlan(
            threadId = "thread_1",
            turnId = "turn_1",
            explanation = "先确认计划",
            steps = listOf(TurnPlanStepUi("补同步", TurnPlanStatus.Pending))
        )
        val state = CodexRemoteUiState(
            activeThreadId = "thread_1",
            messages = listOf(ChatMessage(ChatMessage.Role.Assistant, "当前输出", threadId = "thread_1")),
            pendingApproval = approval,
            pendingInteraction = interaction,
            activePlan = plan,
            turnDiff = TurnDiffSummary(threadId = "thread_1", turnId = "turn_1", diff = "diff --git a/app b/app"),
            runtimeNotices = listOf(RuntimeNotice(RuntimeNoticeKind.ContextCompacted, "thread_1", "上下文已压缩", "已压缩")),
            recoverableError = RecoverableError(
                kind = RecoverableErrorKind.TurnFailed,
                title = "任务失败",
                detail = "模型返回失败",
                actions = listOf(RecoveryActionUi(RecoveryAction.RefreshThreads, "刷新会话")),
                threadId = "thread_1"
            )
        )

        val entries = state.timelineEntries

        assertTrue(entries.any { it is TimelineEntry.ApprovalRequest && it.approval.requestId == 11 })
        assertTrue(entries.any { it is TimelineEntry.InputRequest && it.interaction.requestId == 12 })
        assertTrue(entries.any { it is TimelineEntry.PlanReview && it.plan.steps.single().step == "补同步" })
        assertTrue(entries.any { it is TimelineEntry.Diff && it.diff.diff.startsWith("diff --git") })
        assertTrue(entries.any { it is TimelineEntry.RuntimeNotice && it.notice.title == "上下文已压缩" })
        assertTrue(entries.any { it is TimelineEntry.Failure && it.actions.single().label == "刷新会话" })
    }

    @Test
    fun disconnectedPromptSendIsBlockedWithoutChangingMessages() {
        val messages = listOf(ChatMessage(ChatMessage.Role.Assistant, "cached"))
        val state = CodexRemoteUiState(
            secure = false,
            connectionPhase = ConnectionPhase.Reconnecting,
            messages = messages
        )

        val blocked = state.withPromptSendBlocked()

        assertEquals(messages, blocked.messages)
        assertEquals("正在重连，稍后再发送", blocked.sendBlockedReason)
        assertEquals("正在重连，稍后再发送", blocked.status)
    }

    @Test
    fun staleConnectionEventsAreIgnoredByGeneration() {
        assertTrue(isCurrentConnectionGeneration(eventGeneration = 4, currentGeneration = 4))
        assertFalse(isCurrentConnectionGeneration(eventGeneration = 3, currentGeneration = 4))
    }

    @Test
    fun workflowSectionSwitchKeepsActiveConversationState() {
        val approval = PendingApproval(
            requestId = 1,
            method = "item/commandExecution/requestApproval",
            kind = PendingApprovalKind.CommandExecution,
            title = "允许执行命令？",
            body = "npm test"
        )
        val messages = listOf(ChatMessage(ChatMessage.Role.Assistant, "仍在显示", threadId = "thread_1"))
        val before = CodexRemoteUiState(
            activeThreadId = "thread_1",
            messages = messages,
            pendingApproval = approval,
            isGenerating = true,
            workflowSection = WorkflowSection.Chat
        )

        val after = before.withWorkflowSection(WorkflowSection.Skills)

        assertEquals(WorkflowSection.Skills, after.workflowSection)
        assertEquals("thread_1", after.activeThreadId)
        assertEquals(messages, after.messages)
        assertEquals(approval, after.pendingApproval)
        assertTrue(after.isGenerating)
    }

    @Test
    fun appListNotificationReplacesAutomationAppsWithoutTouchingChat() {
        val message = ChatMessage(ChatMessage.Role.Assistant, "cached", threadId = "thread_1")
        val state = CodexRemoteUiState(
            activeThreadId = "thread_1",
            messages = listOf(message),
            automationCatalog = AutomationCatalogState(
                apps = listOf(AutomationAppItem(id = "old", name = "Old"))
            )
        )
        val params = CodexJson.parseToJsonElement(
            """
            {
              "data": [
                {
                  "id": "new",
                  "name": "New App",
                  "description": "Updated",
                  "isAccessible": true,
                  "isEnabled": true,
                  "pluginDisplayNames": []
                }
              ]
            }
            """.trimIndent()
        ).jsonObject

        val after = state.withUpdatedAppsFromNotification(params, nowMillis = 1770000000000L)

        assertEquals(listOf(message), after.messages)
        assertEquals("thread_1", after.activeThreadId)
        assertEquals("new", after.automationCatalog.apps.single().id)
        assertEquals(1770000000000L, after.automationCatalog.lastSyncedAtMs)
    }

    @Test
    fun mcpStatusNotificationPatchesSingleServerAndKeepsExistingInventory() {
        val state = CodexRemoteUiState(
            automationCatalog = AutomationCatalogState(
                mcpServers = listOf(
                    McpServerItem(
                        name = "github",
                        authStatus = "notLoggedIn",
                        startupStatus = "ready",
                        toolCount = 2,
                        resourceCount = 1
                    )
                )
            )
        )
        val params = CodexJson.parseToJsonElement(
            """{"name":"github","status":"failed","error":"token expired"}"""
        ).jsonObject

        val after = state.withUpdatedMcpServerStatus(params, nowMillis = 1770000000000L)

        val server = after.automationCatalog.mcpServers.single()
        assertEquals("github", server.name)
        assertEquals("failed", server.startupStatus)
        assertEquals("token expired", server.error)
        assertEquals(2, server.toolCount)
        assertEquals(1, server.resourceCount)
    }

    @Test
    fun replacementCloseRequiresFreshPairing() {
        val before = CodexRemoteUiState(
            connected = true,
            secure = true,
            hasSavedPairing = true,
            connectionPhase = ConnectionPhase.Ready
        )

        val after = before.afterRelayClosed(RelayDisconnect.Close(code = 4003, reason = "replaced"))

        assertEquals(ConnectionPhase.Fatal, after.connectionPhase)
        assertFalse(after.hasSavedPairing)
        assertTrue(after.shouldShowPairing)
    }

    @Test
    fun validatesExpiredPairingOnlyForNewScans() {
        val expired = PairingConnectionProfile(
            relay = "wss://relay.example/relay",
            sessionId = "session_1",
            hostDeviceId = "host_1",
            hostIdentityPublicKey = "host-key",
            expiresAt = 10L,
            displayName = "linux",
            relayHostOverrides = mapOf("relay.example" to "203.0.113.10")
        )

        assertFalse(expired.isUsableForNewPairing(nowMillis = 20L))
        val saved = expired.toSavedPairingProfile()
        assertTrue(saved.isUsableForReconnect())
        assertEquals(mapOf("relay.example" to "203.0.113.10"), saved.toConnectionProfile().relayHostOverrides)
    }

    @Test
    fun threadSummaryUsesTitleThenReadableIdFallback() {
        assertEquals("已有任务", ThreadSummary(id = "thread_alpha", title = "已有任务").displayTitle())
        assertEquals("thread_abcdef12", ThreadSummary(id = "thread_abcdef1234567890").displayTitle())
    }

    @Test
    fun parsesThreadSummariesFromCodexResultArray() {
        val array = CodexJson.parseToJsonElement(
            """
            [
              {"id":"thread_1","name":"手动标题","preview":"第一个任务","cwd":"/home/user","updatedAt":1770000000},
              {"id":"thread_2","preview":"第二个任务","current_working_directory":"/home/user/demo"}
            ]
            """.trimIndent()
        ).jsonArray

        val threads = parseThreadSummaries(array)

        assertEquals(
            listOf(
                ThreadSummary(
                    id = "thread_1",
                    title = "手动标题",
                    preview = "第一个任务",
                    cwd = "/home/user",
                    updatedAt = "1770000000"
                ),
                ThreadSummary(
                    id = "thread_2",
                    title = "第二个任务",
                    preview = "第二个任务",
                    cwd = "/home/user/demo"
                )
            ),
            threads
        )
    }

    @Test
    fun parsesThreadListResultWithCursor() {
        val result = CodexJson.parseToJsonElement(
            """
            {
              "data": [
                {"id":"thread_1","preview":"第一个任务"}
              ],
              "nextCursor": "cursor_next"
            }
            """.trimIndent()
        ).jsonObject

        val page = parseThreadListPage(result)

        assertEquals("cursor_next", page.nextCursor)
        assertEquals(listOf(ThreadSummary(id = "thread_1", title = "第一个任务", preview = "第一个任务")), page.threads)
    }

    @Test
    fun buildsThreadListParamsForSearchAndPagination() {
        val params = buildThreadListParams(limit = 40, cursor = "cursor_next", searchTerm = "历史")

        assertEquals("40", params["limit"]!!.jsonPrimitive.content)
        assertEquals("cursor_next", params["cursor"]!!.jsonPrimitive.content)
        assertEquals("历史", params["searchTerm"]!!.jsonPrimitive.content)
        assertEquals("updated_at", params["sortKey"]!!.jsonPrimitive.content)
        assertEquals("desc", params["sortDirection"]!!.jsonPrimitive.content)
    }

    @Test
    fun parsesCoreMessagesFromThreadReadPayload() {
        val thread = CodexJson.parseToJsonElement(
            """
            {
              "id": "thread_1",
              "turns": [
                {
                  "id": "turn_1",
                  "items": [
                    {"type":"userMessage","id":"u1","content":[{"type":"text","text":"帮我改界面","text_elements":[]}]},
                    {"type":"agentMessage","id":"a1","text":"已经改好了","phase":"final"},
                    {"type":"plan","id":"p1","text":"1. 检查代码\n2. 修改 UI"},
                    {"type":"commandExecution","id":"c1","command":"ls","cwd":"/home/user","status":"completed","exitCode":0,"commandActions":[]},
                    {"type":"fileChange","id":"f1","path":"app.kt","operation":"modified","status":"completed"}
                  ],
                  "status": "completed"
                },
                {
                  "id": "turn_2",
                  "items": [
                    {"type":"unknownThing","id":"x1","text":"忽略我"}
                  ],
                  "status": "failed",
                  "error": {"message": "网络中断"}
                }
              ]
            }
            """.trimIndent()
        ).jsonObject

        val messages = parseThreadHistoryMessages("thread_1", thread)

        assertEquals(
            listOf(
                ChatMessage(ChatMessage.Role.User, "帮我改界面", threadId = "thread_1", turnId = "turn_1", itemId = "u1"),
                ChatMessage(ChatMessage.Role.Assistant, "已经改好了", threadId = "thread_1", turnId = "turn_1", itemId = "a1"),
                ChatMessage(ChatMessage.Role.System, "计划\n1. 检查代码\n2. 修改 UI", threadId = "thread_1", turnId = "turn_1", itemId = "p1"),
                ChatMessage(ChatMessage.Role.System, "命令\n/home/user $ ls\ncompleted (0)", threadId = "thread_1", turnId = "turn_1", itemId = "c1"),
                ChatMessage(ChatMessage.Role.System, "文件\nmodified app.kt", threadId = "thread_1", turnId = "turn_1", itemId = "f1"),
                ChatMessage(ChatMessage.Role.System, "任务失败：网络中断", threadId = "thread_1", turnId = "turn_2")
            ),
            messages
        )
    }

    @Test
    fun parsesSingleCompletedItemsWithSameRulesAsHistory() {
        val command = CodexJson.parseToJsonElement(
            """{"type":"commandExecution","id":"cmd_1","command":"pwd","cwd":"/home/user","status":"completed","exitCode":0}"""
        ).jsonObject
        val fileChange = CodexJson.parseToJsonElement(
            """{"type":"fileChange","id":"file_1","path":"ui.kt","operation":"modified"}"""
        ).jsonObject

        assertEquals(
            ChatMessage(ChatMessage.Role.System, "命令\n/home/user $ pwd\ncompleted (0)", "thread_1", "turn_1", "cmd_1"),
            parseThreadHistoryItem("thread_1", "turn_1", command)
        )
        assertEquals(
            ChatMessage(ChatMessage.Role.System, "文件\nmodified ui.kt", "thread_1", "turn_1", "file_1"),
            parseThreadHistoryItem("thread_1", "turn_1", fileChange)
        )
    }

    @Test
    fun ignoresRateLimitUpdateItemsInChatTimeline() {
        val noticeItem = CodexJson.parseToJsonElement(
            """
            {
              "type": "message",
              "id": "notice_1",
              "role": "assistant",
              "text": "限额已更新\n{\"rateLimits\":{\"limitId\":\"codex\",\"limitName\":null,\"primary\":null,\"secondary\":null,\"credits\":null,\"individualLimit\":null,\"planType\":null,\"rateLimitReachedType\":null}}"
            }
            """.trimIndent()
        ).jsonObject

        val message = parseThreadHistoryItem("thread_1", "turn_1", noticeItem)

        assertNull(message)
    }

    @Test
    fun ignoresRateLimitUpdateItemsInThreadHistory() {
        val thread = CodexJson.parseToJsonElement(
            """
            {
              "turns": [
                {
                  "id": "turn_1",
                  "items": [
                    {"type":"userMessage","id":"u1","content":[{"type":"text","text":"你好"}]},
                    {"type":"agentMessage","id":"a1","text":"你好。有什么我可以直接帮你处理的？"},
                    {"type":"message","id":"notice_1","role":"assistant","text":"限额已更新\n{\"rateLimits\":{\"limitId\":\"codex\"}}"}
                  ],
                  "status": "completed"
                }
              ]
            }
            """.trimIndent()
        ).jsonObject

        val messages = parseThreadHistoryMessages("thread_1", thread)

        assertEquals(
            listOf(
                ChatMessage(ChatMessage.Role.User, "你好", threadId = "thread_1", turnId = "turn_1", itemId = "u1"),
                ChatMessage(ChatMessage.Role.Assistant, "你好。有什么我可以直接帮你处理的？", threadId = "thread_1", turnId = "turn_1", itemId = "a1")
            ),
            messages
        )
    }

    @Test
    fun parsesMessageTimestampsFromHistoryItems() {
        val item = CodexJson.parseToJsonElement(
            """{"type":"agentMessage","id":"a1","text":"完成","createdAtMs":1770000000123}"""
        ).jsonObject

        val message = parseThreadHistoryItem("thread_1", "turn_1", item)

        assertEquals(1770000000123L, message?.createdAtMs)
    }

    @Test
    fun completedUserMessageMergesWithOptimisticDuplicate() {
        val optimistic = ChatMessage(
            role = ChatMessage.Role.User,
            text = "继续任务",
            createdAtMs = 1770000000000L
        )
        val completed = ChatMessage(
            role = ChatMessage.Role.User,
            text = "继续任务",
            threadId = "thread_1",
            turnId = "turn_1",
            itemId = "u1",
            createdAtMs = 1770000000123L
        )

        val merged = mergeCompletedMessage(listOf(optimistic), completed)

        assertEquals(
            listOf(completed.copy(createdAtMs = optimistic.createdAtMs)),
            merged
        )
    }

    @Test
    fun parsesRealtimeReasoningDeltasForMessageStream() {
        val params = CodexJson.parseToJsonElement(
            """{"threadId":"thread_1","turnId":"turn_1","itemId":"r1","delta":"正在分析协议"}"""
        ).jsonObject

        val delta = parseReasoningDelta(params)
        val message = ChatMessage(
            role = ChatMessage.Role.System,
            text = "思考\n${delta?.delta}",
            threadId = delta?.threadId,
            turnId = delta?.turnId,
            itemId = delta?.itemId,
            createdAtMs = 1770000000123L
        )

        assertEquals("thread_1", delta?.threadId)
        assertEquals("turn_1", delta?.turnId)
        assertEquals("r1", delta?.itemId)
        assertEquals("正在分析协议", delta?.delta)
        assertTrue(message.isReasoningMessage())
        assertEquals("正在分析协议", message.reasoningBody())
    }

    @Test
    fun parsesReasoningSummaryItemsFromHistory() {
        val item = CodexJson.parseToJsonElement(
            """{"type":"reasoningSummary","id":"r2","summary":[{"text":"先检查状态"}],"content":"再同步会话"}"""
        ).jsonObject

        val message = parseThreadHistoryItem("thread_1", "turn_1", item)

        assertNotNull(message)
        assertTrue(message!!.isReasoningMessage())
        assertEquals("先检查状态\n再同步会话", message.reasoningBody())
    }

    @Test
    fun parsesModelListFromCommonResultShapes() {
        val result = CodexJson.parseToJsonElement(
            """
            {
              "data": [
                {"id":"gpt-5.5","label":"GPT 5.5"},
                {"model":"o4-mini","name":"o4 mini","hidden":true}
              ]
            }
            """.trimIndent()
        ).jsonObject

        val models = parseRuntimeModelOptions(result)

        assertEquals(
            listOf(
                RuntimeModelOption(id = "gpt-5.5", label = "GPT 5.5"),
                RuntimeModelOption(id = "o4-mini", label = "o4 mini", hidden = true)
            ),
            models
        )
    }

    @Test
    fun parsesModelReasoningEffortsAndFallsBackToAllEfforts() {
        val result = CodexJson.parseToJsonElement(
            """
            {
              "models": [
                {
                  "id": "gpt-5.5",
                  "label": "GPT 5.5",
                  "supportedReasoningEfforts": [
                    {"reasoningEffort":"low","description":"low effort"},
                    {"reasoningEffort":"high","description":"high effort"},
                    {"reasoningEffort":"xhigh","description":"extra high effort"}
                  ]
                },
                {"id": "o4-mini", "label": "o4 mini"}
              ]
            }
            """.trimIndent()
        ).jsonObject

        val models = parseRuntimeModelOptions(result)

        assertEquals(listOf(ReasoningEffort.Low, ReasoningEffort.High, ReasoningEffort.XHigh), models[0].supportedReasoningEfforts)
        assertEquals(DefaultReasoningEfforts, models[1].supportedReasoningEfforts)
        assertEquals("xhigh", ReasoningEffort.XHigh.wireName)
        assertEquals("超高", ReasoningEffort.XHigh.label)
    }

    @Test
    fun modelReasoningOptionsExposeKnownXHighWhenRuntimeOmitsIt() {
        val result = CodexJson.parseToJsonElement(
            """
            {
              "models": [
                {
                  "id": "gpt-5.5",
                  "label": "GPT 5.5",
                  "supportedReasoningEfforts": [
                    {"reasoningEffort":"low"},
                    {"reasoningEffort":"medium"},
                    {"reasoningEffort":"high"}
                  ]
                }
              ]
            }
            """.trimIndent()
        ).jsonObject

        val models = parseRuntimeModelOptions(result)

        assertEquals(
            listOf(ReasoningEffort.Low, ReasoningEffort.Medium, ReasoningEffort.High, ReasoningEffort.XHigh),
            models.single().supportedReasoningEfforts
        )
    }

    @Test
    fun selectsModelAndAccessModeInStateHelpers() {
        val state = CodexRemoteUiState(
            availableModels = listOf(
                RuntimeModelOption(
                    id = "gpt-5.5",
                    label = "5.5 超高",
                    supportedReasoningEfforts = listOf(ReasoningEffort.Medium, ReasoningEffort.High, ReasoningEffort.XHigh)
                ),
                RuntimeModelOption(
                    id = "o4-mini",
                    label = "o4 mini",
                    supportedReasoningEfforts = listOf(ReasoningEffort.Low)
                )
            ),
            selectedModelId = "gpt-5.5",
            modelLabel = "5.5 超高",
            selectedReasoningEffort = ReasoningEffort.High
        )

        val modelSelected = state.withSelectedModel("o4-mini")
        val accessSelected = modelSelected.withSelectedAccessMode(AccessMode.FullAccess)

        assertEquals("o4-mini", modelSelected.selectedModelId)
        assertEquals("o4 mini", modelSelected.modelLabel)
        assertEquals(ReasoningEffort.Low, modelSelected.selectedReasoningEffort)
        assertEquals(AccessMode.FullAccess, accessSelected.selectedAccessMode)
        assertEquals("完全访问", accessSelected.permissionLabel)
    }

    @Test
    fun selectsReasoningFastModeAndThemeInStateHelpers() {
        val state = CodexRemoteUiState(
            availableModels = listOf(
                RuntimeModelOption(
                    id = "gpt-5.5",
                    label = "5.5 超高",
                    supportedReasoningEfforts = listOf(ReasoningEffort.Medium, ReasoningEffort.High, ReasoningEffort.XHigh)
                )
            ),
            selectedModelId = "gpt-5.5"
        )

        val updated = state
            .withSelectedReasoningEffort(ReasoningEffort.XHigh)
            .withFastModeEnabled(true)
            .withSelectedTheme(AppTheme.DarkComfort)

        assertEquals(ReasoningEffort.XHigh, updated.selectedReasoningEffort)
        assertTrue(updated.fastModeEnabled)
        assertEquals(AppTheme.DarkComfort, updated.selectedTheme)
    }

    @Test
    fun composerModelChipCombinesModelAndReasoningSettings() {
        val normal = CodexRemoteUiState(
            modelLabel = "5.5",
            selectedReasoningEffort = ReasoningEffort.XHigh,
            fastModeEnabled = false
        )
        val fast = normal.copy(fastModeEnabled = true)
        val alreadyLabeled = normal.copy(modelLabel = "5.5 超高")

        assertEquals("5.5 / 超高", normal.composerModelSettingsLabel)
        assertEquals("5.5 / 超高 / Fast", fast.composerModelSettingsLabel)
        assertEquals("5.5 超高", alreadyLabeled.composerModelSettingsLabel)
    }

    @Test
    fun refreshedComposerControlsSplitModelReasoningAndAccessMode() {
        val state = CodexRemoteUiState(
            modelLabel = "GPT-5.5 超高",
            selectedReasoningEffort = ReasoningEffort.XHigh,
            selectedAccessMode = AccessMode.OnRequest,
            fastModeEnabled = true
        )

        val labels = state.composerControlLabels

        assertEquals("GPT-5.5", labels.model)
        assertEquals("超高 / Fast", labels.reasoning)
        assertEquals("需要确认", labels.access)
    }

    @Test
    fun emptyConversationPromptAddsContextAndQuickActionsForNewThread() {
        val prompt = CodexRemoteUiState(
            activeThreadId = null,
            projectName = "codex-android-remote"
        ).emptyConversationPrompt

        assertEquals("今天想让 Codex 做什么？", prompt.title)
        assertTrue(prompt.subtitle.contains("codex-android-remote"))
        assertEquals(listOf("总结项目", "检查变更", "继续上次任务"), prompt.quickActions.map { it.label })
        assertTrue(prompt.quickActions.first().prompt.contains("总结"))
    }

    @Test
    fun drawerHostStatusLineSummarizesConnectionState() {
        val connected = CodexRemoteUiState(secure = true, isSoftSyncing = false, status = "已加密连接")
        val syncing = connected.copy(isSoftSyncing = true)
        val reconnecting = connected.copy(secure = false, status = "正在重连")

        assertEquals("已同步 · 端到端加密", connected.drawerHostStatusLine)
        assertEquals("同步中 · 端到端加密", syncing.drawerHostStatusLine)
        assertEquals("正在重连", reconnecting.drawerHostStatusLine)
    }

    @Test
    fun threadSummaryPreviewAndMetadataSupportDrawerRows() {
        val thread = ThreadSummary(
            id = "thread_alpha",
            title = "定位效果总结",
            preview = "输出当前版本建图和定位的效果。",
            updatedAt = "2026-06-24T02:30:00Z"
        )
        val fallback = ThreadSummary(id = "thread_beta", title = "只有标题")

        assertEquals("输出当前版本建图和定位的效果。", thread.displayPreviewLine())
        assertEquals("2026-06-24", thread.displayMetaLine())
        assertEquals("", fallback.displayPreviewLine())
        assertEquals("最近", fallback.displayMetaLine())
    }

    @Test
    fun pairingLoginPresentationKeepsQrScanAsPrimaryPath() {
        val presentation = buildPairingLoginPresentation(
            pairingText = "",
            status = "未连接",
            backupExpanded = false,
            cameraDenied = false
        )

        assertEquals("把手机连接到你的 Codex 工作区", presentation.heroTitle)
        assertEquals("扫码配对", presentation.primaryTitle)
        assertEquals("打开相机扫描二维码", presentation.primaryActionLabel)
        assertFalse(presentation.showJsonInput)
        assertEquals("等待配对", presentation.statusTitle)
        assertTrue(presentation.statusDetail.contains("同一个 relay"))
    }

    @Test
    fun pairingLoginPresentationExpandsBackupJsonFlow() {
        val presentation = buildPairingLoginPresentation(
            pairingText = """{"relay":"wss://relay.example/relay"}""",
            status = "已识别二维码",
            backupExpanded = true,
            cameraDenied = false
        )

        assertTrue(presentation.showJsonInput)
        assertEquals("Pairing JSON", presentation.backupTitle)
        assertEquals("使用粘贴内容连接", presentation.backupActionLabel)
        assertEquals("已识别配对内容", presentation.statusTitle)
        assertTrue(presentation.canConnectWithJson)
        assertNull(presentation.warningText)
    }

    @Test
    fun pairingLoginPresentationShowsCameraPermissionWarning() {
        val presentation = buildPairingLoginPresentation(
            pairingText = "",
            status = "相机权限被拒绝",
            backupExpanded = false,
            cameraDenied = true
        )

        assertEquals("相机权限被拒绝。请在系统设置中允许相机，或直接使用 Pairing JSON。", presentation.warningText)
        assertEquals("相机权限不可用", presentation.statusTitle)
    }

    @Test
    fun appThemesExposeAdditionalSelectablePalettes() {
        assertTrue(AppTheme.entries.contains(AppTheme.DarkComfort))
        assertTrue(AppTheme.entries.contains(AppTheme.HighContrast))
        assertFalse(
            appColorPaletteFor(AppTheme.Default).appBackground ==
                appColorPaletteFor(AppTheme.HighContrast).appBackground
        )
    }

    @Test
    fun modelLoadFailureKeepsCurrentSelection() {
        val state = CodexRemoteUiState(
            availableModels = listOf(RuntimeModelOption(id = "gpt-5.5", label = "5.5 超高")),
            selectedModelId = "gpt-5.5",
            modelLabel = "5.5 超高",
            isLoadingModels = true
        )

        val failed = state.withModelLoadFailure("模型列表加载失败")

        assertFalse(failed.isLoadingModels)
        assertEquals("gpt-5.5", failed.selectedModelId)
        assertEquals("5.5 超高", failed.modelLabel)
        assertEquals("模型列表加载失败", failed.modelLoadError)
    }

    @Test
    fun buildsTurnStartParamsWithRuntimeSettingsAndFallbacks() {
        val full = buildTurnStartParams(
            threadId = "thread_1",
            prompt = "继续任务",
            modelId = "gpt-5.5",
            accessMode = AccessMode.FullAccess,
            reasoningEffort = ReasoningEffort.XHigh,
            fastModeEnabled = true,
            includeApprovalPolicy = true,
            includeSandboxPolicy = true
        )

        assertEquals("gpt-5.5", full["model"]!!.jsonPrimitive.content)
        assertEquals("xhigh", full["reasoningEffort"]!!.jsonPrimitive.content)
        assertEquals("fast", full["profile"]!!.jsonPrimitive.content)
        assertEquals("never", full["approvalPolicy"]!!.jsonPrimitive.content)
        assertEquals("dangerFullAccess", full["sandboxPolicy"]!!.jsonObject["type"]!!.jsonPrimitive.content)

        val fallback = full.withoutTurnStartField("sandboxPolicy")
        assertNull(fallback["sandboxPolicy"])
        assertEquals("never", fallback["approvalPolicy"]!!.jsonPrimitive.content)

        val noProfile = full.withoutTurnStartField("profile")
        assertNull(noProfile["profile"])
        assertEquals("xhigh", noProfile["reasoningEffort"]!!.jsonPrimitive.content)

        val compatibilityNoProfile = full.nextRuntimeCompatibilityFallback()!!
        assertNull(compatibilityNoProfile["profile"])
        assertEquals("xhigh", compatibilityNoProfile["reasoningEffort"]!!.jsonPrimitive.content)

        val compatibilityNoReasoning = compatibilityNoProfile.nextRuntimeCompatibilityFallback()!!
        assertNull(compatibilityNoReasoning["reasoningEffort"])
        assertEquals("dangerFullAccess", compatibilityNoReasoning["sandboxPolicy"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun buildsThreadStartAndResumeParamsWithSelectedRuntimeSettings() {
        val start = buildThreadStartParams(
            modelId = "o4-mini",
            accessMode = AccessMode.FullAccess,
            reasoningEffort = ReasoningEffort.Low,
            fastModeEnabled = true,
            cwd = "/home/user/project"
        )
        val resume = buildThreadResumeParams(
            threadId = "thread_1",
            modelId = "o4-mini",
            accessMode = AccessMode.FullAccess,
            reasoningEffort = ReasoningEffort.Low,
            fastModeEnabled = true
        )

        assertEquals("o4-mini", start["model"]!!.jsonPrimitive.content)
        assertEquals("low", start["reasoningEffort"]!!.jsonPrimitive.content)
        assertEquals("fast", start["profile"]!!.jsonPrimitive.content)
        assertEquals("never", start["approvalPolicy"]!!.jsonPrimitive.content)
        assertEquals("danger-full-access", start["sandbox"]!!.jsonPrimitive.content)
        assertEquals("/home/user/project", start["cwd"]!!.jsonPrimitive.content)

        assertEquals("thread_1", resume["threadId"]!!.jsonPrimitive.content)
        assertEquals("o4-mini", resume["model"]!!.jsonPrimitive.content)
        assertEquals("low", resume["reasoningEffort"]!!.jsonPrimitive.content)
        assertEquals("fast", resume["profile"]!!.jsonPrimitive.content)
        assertEquals("never", resume["approvalPolicy"]!!.jsonPrimitive.content)
        assertEquals("danger-full-access", resume["sandbox"]!!.jsonPrimitive.content)
    }

    @Test
    fun buildsProjectWorkspacesFromKnownDesktopCwds() {
        val threads = listOf(
            ThreadSummary(id = "thread_1", title = "A", cwd = "/home/user/alpha"),
            ThreadSummary(id = "thread_2", title = "A2", cwd = "/home/user/alpha"),
            ThreadSummary(id = "thread_3", title = "B", cwd = "/home/user/beta")
        )
        val skills = SkillsCatalogState(groups = listOf(SkillGroup(cwd = "/home/user/gamma")))
        val automation = AutomationCatalogState(
            hookGroups = listOf(HookGroup(cwd = "/home/user/beta"))
        )

        val projects = buildProjectWorkspaces(threads, skills, automation)

        assertEquals(listOf("/home/user/alpha", "/home/user/beta", "/home/user/gamma"), projects.map { it.cwd })
        assertEquals("alpha", projects[0].name)
        assertEquals(2, projects[0].threadCount)
        assertEquals(ProjectWorkspaceSource.ThreadHistory, projects[0].source)
    }

    @Test
    fun selectedProjectDrivesEmptyWorkspaceAndThreadStartParams() {
        val selected = CodexRemoteUiState(
            projectName = "workspace",
            selectedProjectCwd = null,
            threads = listOf(ThreadSummary(id = "thread_1", cwd = "/home/user/old"))
        ).withSelectedProjectCwd("/home/user/project")

        assertEquals("/home/user/project", selected.selectedProjectCwd)
        assertEquals("project", selected.projectName)
        assertEquals("/home/user/project", selected.currentWorkspaceCwd())

        val raw = CodexJson.encodeToString(
            RuntimeSettingsSnapshot(
                selectedProjectCwd = "/home/user/project",
                selectedReasoningEffort = ReasoningEffort.High,
                fastModeEnabled = true,
                selectedTheme = AppTheme.DarkComfort
            )
        )
        val restored = CodexJson.decodeFromString<RuntimeSettingsSnapshot>(raw)
        assertEquals("/home/user/project", restored.selectedProjectCwd)
        assertEquals(ReasoningEffort.High, restored.selectedReasoningEffort)
        assertTrue(restored.fastModeEnabled)
        assertEquals(AppTheme.DarkComfort, restored.selectedTheme)
    }

    @Test
    fun manualProjectPathBecomesSelectableWorkspace() {
        val selected = CodexRemoteUiState(
            projectName = "workspace",
            projectWorkspaces = listOf(
                ProjectWorkspace(
                    cwd = "/home/user/known",
                    name = "known",
                    source = ProjectWorkspaceSource.ThreadHistory
                )
            )
        ).withSelectedProjectCwd(" /home/user/manual/ ")

        assertEquals("/home/user/manual", selected.selectedProjectCwd)
        assertEquals("manual", selected.projectName)
        assertEquals("/home/user/manual", selected.currentWorkspaceCwd())
        assertEquals(
            listOf("/home/user/manual", "/home/user/known"),
            selected.selectableProjectWorkspaces().map { it.cwd }
        )
    }

    @Test
    fun buildsDesktopTopOptionsFromStateAndDesktopStatus() {
        val state = CodexRemoteUiState(
            selectedModelId = "gpt-5.5",
            selectedAccessMode = AccessMode.OnRequest,
            activePlan = ActiveTurnPlan(
                threadId = "thread_1",
                turnId = "turn_1",
                explanation = null,
                steps = listOf(TurnPlanStepUi("检查", TurnPlanStatus.InProgress))
            ),
            pluginsCatalog = PluginCatalogState(
                marketplaces = listOf(
                    PluginMarketplace(
                        name = "local",
                        plugins = listOf(
                            PluginItem(id = "github", name = "github", installed = true, enabled = true),
                            PluginItem(id = "figma", name = "figma", installed = true, enabled = false)
                        )
                    )
                )
            )
        )
        val desktopStatus = DesktopStatusUi(
            model = "provider/47-104-gpt54",
            approvalPolicy = "on-request",
            accountType = "chatgpt"
        )
        val goal = ThreadGoalUi(objective = "完成同步", status = ThreadGoalStatus.InProgress)

        val options = buildDesktopTopOptions(state, desktopStatus = desktopStatus, goal = goal)

        assertEquals("provider/47-104-gpt54", options.providerLabel)
        assertEquals("本地模式", options.localModeLabel)
        assertEquals("计划模式", options.planLabel)
        assertEquals("追求目标", options.goalLabel)
        assertEquals("1 个插件启用", options.pluginLabel)
        assertEquals("需要确认", options.permissionLabel)
    }

    @Test
    fun activeGoalDrivesDesktopTopOptions() {
        val state = CodexRemoteUiState(
            activeGoal = ThreadGoalUi(objective = "完成 Android 对齐", status = ThreadGoalStatus.InProgress)
        )

        val options = buildDesktopTopOptions(state)

        assertEquals("追求目标", options.goalLabel)
    }

    @Test
    fun recoversThreadNotFoundWithoutLosingPrompt() {
        val before = CodexRemoteUiState(
            activeThreadId = "thread_missing_1",
            messages = listOf(ChatMessage(ChatMessage.Role.User, "开始执行计划")),
            isGenerating = true,
            isLoadingHistory = true
        )

        val after = before.withThreadNotFoundRecovery(
            prompt = "开始执行计划",
            message = "thread not found: thread_missing_1"
        )

        assertTrue(isThreadNotFoundError("thread not found: thread_missing_1"))
        assertNull(after.activeThreadId)
        assertFalse(after.isGenerating)
        assertFalse(after.isLoadingHistory)
        assertEquals("开始执行计划", after.pendingPromptAfterThreadLoss)
        assertEquals(RecoverableErrorKind.ThreadNotFound, after.recoverableError?.kind)
    }

    @Test
    fun recoverableTurnFailureKeepsThreadAndOffersExitActions() {
        val before = CodexRemoteUiState(
            activeThreadId = "thread_1",
            messages = listOf(ChatMessage(ChatMessage.Role.User, "继续")),
            isGenerating = true,
            pendingApproval = PendingApproval(1, "execCommandApproval", PendingApprovalKind.LegacyCommand, "允许？", "pytest"),
            pendingInteraction = PendingInteraction(2, "item/tool/requestUserInput", PendingInteractionKind.ToolUserInput, "输入", "继续？")
        )

        val after = before.withRecoverableFailure(
            kind = RecoverableErrorKind.TurnFailed,
            threadId = "thread_1",
            turnId = "turn_1",
            prompt = "继续",
            detail = "context window exceeded"
        )

        assertEquals("thread_1", after.activeThreadId)
        assertFalse(after.isGenerating)
        assertNull(after.pendingApproval)
        assertNull(after.pendingInteraction)
        assertEquals("继续", after.pendingPromptAfterThreadLoss)
        assertEquals(RecoverableErrorKind.TurnFailed, after.recoverableError?.kind)
        assertEquals(
            listOf(RecoveryAction.StartNewWithPrompt, RecoveryAction.ArchiveThread, RecoveryAction.RefreshThreads, RecoveryAction.Dismiss),
            after.recoverableError?.actions?.map { it.action }
        )
    }

    @Test
    fun parsesRuntimeFailuresAndThreadLifecycleNotices() {
        val errorParams = CodexJson.parseToJsonElement(
            """{"threadId":"thread_1","turnId":"turn_1","willRetry":false,"error":{"message":"compact failed","info":"contextWindowExceeded"}}"""
        ).jsonObject
        val completedParams = CodexJson.parseToJsonElement(
            """{"threadId":"thread_1","turn":{"id":"turn_2","status":"failed","error":{"message":"网络中断"}}}"""
        ).jsonObject
        val closedParams = CodexJson.parseToJsonElement("""{"threadId":"thread_1"}""").jsonObject
        val compactedParams = CodexJson.parseToJsonElement("""{"threadId":"thread_1","turnId":"turn_3"}""").jsonObject

        val failure = parseRuntimeFailure("error", errorParams)
        val completed = parseTurnCompletedFailure(completedParams, fallbackThreadId = null, fallbackTurnId = null)
        val closed = parseThreadClosedFailure(closedParams, activeThreadId = "thread_1")
        val notice = parseContextCompactedNotice(compactedParams)

        assertEquals(RecoverableErrorKind.CompactFailed, failure?.kind)
        assertEquals("thread_1", failure?.threadId)
        assertEquals("turn_1", failure?.turnId)
        assertFalse(failure!!.willRetry)
        assertEquals(RecoverableErrorKind.TurnFailed, completed?.kind)
        assertEquals("turn_2", completed?.turnId)
        assertEquals(RecoverableErrorKind.ThreadClosed, closed.kind)
        assertEquals("thread_1", notice.threadId)
        assertEquals("上下文已压缩", notice.title)
    }

    @Test
    fun parsesImageInputsAsVisibleHistoryPlaceholders() {
        val item = CodexJson.parseToJsonElement(
            """
            {
              "type": "userMessage",
              "content": [
                {"type":"text","text":"看这张图"},
                {"type":"localImage","path":"/tmp/screen.png"},
                {"type":"image","url":"https://example.com/a.png"}
              ]
            }
            """.trimIndent()
        ).jsonObject

        val message = parseThreadHistoryItem("thread_1", "turn_1", item)

        assertEquals(ChatMessage.Role.User, message?.role)
        assertTrue(message!!.text.contains("看这张图"))
        assertTrue(message.text.contains("[图片: /tmp/screen.png]"))
        assertTrue(message.text.contains("[图片: https://example.com/a.png]"))
    }

    @Test
    fun parsesTurnPlanUpdatesForMobilePlanReview() {
        val params = CodexJson.parseToJsonElement(
            """
            {
              "threadId": "thread_1",
              "turnId": "turn_1",
              "explanation": "先确认方案",
              "plan": [
                {"step": "检查协议", "status": "completed"},
                {"step": "实现手机审批", "status": "inProgress"},
                {"step": "跑测试", "status": "pending"}
              ]
            }
            """.trimIndent()
        ).jsonObject

        val plan = parseTurnPlan(params)

        assertEquals("thread_1", plan?.threadId)
        assertEquals("turn_1", plan?.turnId)
        assertEquals("先确认方案", plan?.explanation)
        assertEquals(
            listOf(TurnPlanStatus.Completed, TurnPlanStatus.InProgress, TurnPlanStatus.Pending),
            plan?.steps?.map { it.status }
        )
    }

    @Test
    fun parsesCommandApprovalRequestAndBuildsResponse() {
        val params = CodexJson.parseToJsonElement(
            """
            {
              "threadId": "thread_1",
              "turnId": "turn_1",
              "itemId": "cmd_1",
              "startedAtMs": 1770000000000,
              "command": "pytest -q",
              "cwd": "/home/user/project",
              "reason": "需要运行测试"
            }
            """.trimIndent()
        ).jsonObject

        val pending = parsePendingApproval(
            requestId = 7,
            method = "item/commandExecution/requestApproval",
            params = params
        )
        val accepted = buildApprovalResult(pending!!, ApprovalDecision.Accept)
        val declined = buildApprovalResult(pending, ApprovalDecision.Decline)

        assertEquals(PendingApprovalKind.CommandExecution, pending.kind)
        assertEquals("thread_1", pending.threadId)
        assertTrue(pending.body.contains("pytest -q"))
        assertTrue(pending.body.contains("/home/user/project"))
        assertEquals("accept", accepted["decision"]!!.jsonPrimitive.content)
        assertEquals("decline", declined["decision"]!!.jsonPrimitive.content)
    }

    @Test
    fun buildsPermissionApprovalResponsesFromRequestedPermissions() {
        val params = CodexJson.parseToJsonElement(
            """
            {
              "threadId": "thread_1",
              "turnId": "turn_1",
              "itemId": "perm_1",
              "startedAtMs": 1770000000000,
              "cwd": "/home/user/project",
              "reason": "需要完整访问",
              "permissions": {
                "network": {"host": "example.com"},
                "fileSystem": {"writableRoots": ["/home/user/project"]}
              }
            }
            """.trimIndent()
        ).jsonObject

        val pending = parsePendingApproval(
            requestId = 8,
            method = "item/permissions/requestApproval",
            params = params
        )
        val accepted = buildApprovalResult(pending!!, ApprovalDecision.AcceptForSession)
        val declined = buildApprovalResult(pending, ApprovalDecision.Decline)

        assertEquals(PendingApprovalKind.Permissions, pending.kind)
        assertEquals("session", accepted["scope"]!!.jsonPrimitive.content)
        assertEquals(params["permissions"], accepted["permissions"])
        assertEquals("turn", declined["scope"]!!.jsonPrimitive.content)
        assertEquals(JsonObject(emptyMap()), declined["permissions"])
    }

    @Test
    fun parsesToolUserInputRequestAndBuildsAnswers() {
        val params = CodexJson.parseToJsonElement(
            """
            {
              "threadId": "thread_1",
              "turnId": "turn_1",
              "itemId": "tool_1",
              "questions": [
                {
                  "id": "choice",
                  "header": "模式",
                  "question": "怎么继续？",
                  "isOther": false,
                  "isSecret": false,
                  "options": [
                    {"label": "开始执行", "description": "进入 Default 并执行"},
                    {"label": "继续规划", "description": "留在 Plan"}
                  ]
                }
              ]
            }
            """.trimIndent()
        ).jsonObject

        val request = parsePendingInteraction(
            requestId = 9,
            method = "item/tool/requestUserInput",
            params = params
        )
        val result = buildInteractionResult(
            request = request!!,
            answers = mapOf("choice" to listOf("开始执行")),
            action = InteractionAction.Accept
        )

        assertEquals(PendingInteractionKind.ToolUserInput, request.kind)
        assertEquals("怎么继续？", request.questions.single().question)
        assertEquals(listOf("开始执行", "继续规划"), request.questions.single().options.map { it.label })
        assertEquals("开始执行", result["answers"]!!.jsonObject["choice"]!!.jsonObject["answers"]!!.jsonArray.single().jsonPrimitive.content)
    }

    @Test
    fun parsesMultiQuestionUserInputRequest() {
        val params = CodexJson.parseToJsonElement(
            """
            {
              "threadId": "thread_1",
              "questions": [
                {
                  "id": "mode",
                  "question": "选择模式",
                  "options": ["开始执行", "继续规划"]
                },
                {
                  "id": "note",
                  "header": "补充",
                  "question": "还有什么要说明？",
                  "isOther": true,
                  "isSecret": true
                }
              ]
            }
            """.trimIndent()
        ).jsonObject

        val request = parsePendingInteraction(11, "item/tool/requestUserInput", params)
        val result = buildInteractionResult(
            request = request!!,
            answers = mapOf("mode" to listOf("继续规划"), "note" to listOf("保密说明")),
            action = InteractionAction.Accept
        )

        assertEquals(2, request.questions.size)
        assertEquals(listOf("开始执行", "继续规划"), request.questions[0].options.map { it.label })
        assertTrue(request.questions[1].isOther)
        assertTrue(request.questions[1].isSecret)
        assertEquals("继续规划", result["answers"]!!.jsonObject["mode"]!!.jsonObject["answers"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("保密说明", result["answers"]!!.jsonObject["note"]!!.jsonObject["answers"]!!.jsonArray.single().jsonPrimitive.content)
    }

    @Test
    fun parsesMcpElicitationUrlAndBuildsCancelResponse() {
        val params = CodexJson.parseToJsonElement(
            """
            {
              "threadId": "thread_1",
              "turnId": null,
              "serverName": "github",
              "mode": "url",
              "_meta": null,
              "message": "需要登录",
              "url": "https://example.com/login",
              "elicitationId": "login_1"
            }
            """.trimIndent()
        ).jsonObject

        val request = parsePendingInteraction(
            requestId = 10,
            method = "mcpServer/elicitation/request",
            params = params
        )
        val result = buildInteractionResult(request!!, answers = emptyMap(), action = InteractionAction.Cancel)

        assertEquals(PendingInteractionKind.McpElicitation, request.kind)
        assertEquals("github", request.serverName)
        assertEquals("https://example.com/login", request.url)
        assertEquals("cancel", result["action"]!!.jsonPrimitive.content)
        assertNull(result["content"])
    }

    @Test
    fun parsesMcpElicitationFormAndBuildsContentResponse() {
        val params = CodexJson.parseToJsonElement(
            """
            {
              "serverName": "github",
              "mode": "form",
              "message": "创建 issue",
              "fields": [
                {"id": "title", "label": "标题", "type": "text"},
                {"id": "visibility", "label": "可见性", "options": ["public", "private"]}
              ]
            }
            """.trimIndent()
        ).jsonObject

        val request = parsePendingInteraction(12, "mcpServer/elicitation/request", params)
        val result = buildInteractionResult(
            request = request!!,
            answers = mapOf("title" to listOf("bug"), "visibility" to listOf("private")),
            action = InteractionAction.Accept
        )

        assertEquals(PendingInteractionKind.McpElicitation, request.kind)
        assertEquals(listOf("title", "visibility"), request.questions.map { it.id })
        assertEquals(listOf("public", "private"), request.questions[1].options.map { it.label })
        assertEquals("accept", result["action"]!!.jsonPrimitive.content)
        assertEquals("bug", result["content"]!!.jsonObject["title"]!!.jsonPrimitive.content)
        assertEquals("private", result["content"]!!.jsonObject["visibility"]!!.jsonPrimitive.content)
    }

    @Test
    fun parsesTurnDiffCommandOutputAndRuntimeNotices() {
        val diffParams = CodexJson.parseToJsonElement(
            """
            {"threadId":"thread_1","turnId":"turn_1","diff":"diff --git a/a.kt b/a.kt\n+new line"}
            """.trimIndent()
        ).jsonObject
        val commandParams = CodexJson.parseToJsonElement(
            """{"threadId":"thread_1","turnId":"turn_1","itemId":"cmd_1","delta":"running tests\n"}"""
        ).jsonObject
        val noticeParams = CodexJson.parseToJsonElement(
            """{"threadId":"thread_1","message":"model changed","fromModel":"gpt-5.5","toModel":"o4-mini"}"""
        ).jsonObject

        val diff = parseTurnDiff(diffParams)
        val commandOutput = parseCommandOutputDelta(commandParams)
        val notice = parseRuntimeNotice("model/rerouted", noticeParams)

        assertEquals("turn_1", diff?.turnId)
        assertTrue(diff!!.diff.contains("+new line"))
        assertEquals("cmd_1", commandOutput?.itemId)
        assertEquals("running tests\n", commandOutput?.delta)
        assertEquals(RuntimeNoticeKind.ModelRerouted, notice?.kind)
        assertTrue(notice!!.body.contains("o4-mini"))
    }

    @Test
    fun ignoresEmptyRateLimitRuntimeNotice() {
        val noticeParams = CodexJson.parseToJsonElement(
            """
            {"rateLimits":{"limitId":"codex","limitName":null,"primary":null,"secondary":null,"credits":null,"individualLimit":null,"planType":null,"rateLimitReachedType":null}}
            """.trimIndent()
        ).jsonObject

        val notice = parseRuntimeNotice("account/rateLimits/updated", noticeParams)

        assertNull(notice)
    }

    @Test
    fun ignoresRateLimitRuntimeNoticeEvenWithSummary() {
        val noticeParams = CodexJson.parseToJsonElement(
            """{"message":"remaining quota changed","rateLimits":[{"limitId":"codex","credits":42}]}"""
        ).jsonObject

        val notice = parseRuntimeNotice("account/rateLimits/updated", noticeParams)

        assertNull(notice)
    }

    @Test
    fun buildsDesktopThreadActionParams() {
        val fork = buildThreadForkParams(
            "thread_1",
            modelId = "gpt-5.5",
            accessMode = AccessMode.OnRequest,
            reasoningEffort = ReasoningEffort.Medium,
            fastModeEnabled = true
        )
        val rollback = buildThreadRollbackParams("thread_1", 1)
        val compact = buildThreadCompactParams("thread_1")
        val review = buildReviewStartParams("thread_1")
        val goal = buildThreadGoalSetParams("thread_1", objective = "完成恢复", status = ThreadGoalStatus.InProgress, tokenBudget = 12000)

        assertEquals("thread_1", fork["threadId"]!!.jsonPrimitive.content)
        assertEquals("gpt-5.5", fork["model"]!!.jsonPrimitive.content)
        assertEquals("medium", fork["reasoningEffort"]!!.jsonPrimitive.content)
        assertEquals("fast", fork["profile"]!!.jsonPrimitive.content)
        assertEquals("thread_1", rollback["threadId"]!!.jsonPrimitive.content)
        assertEquals(1, rollback["numTurns"]!!.jsonPrimitive.int)
        assertEquals("thread_1", compact["threadId"]!!.jsonPrimitive.content)
        assertEquals("inline", review["delivery"]!!.jsonPrimitive.content)
        assertEquals("uncommittedChanges", review["target"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("inProgress", goal["status"]!!.jsonPrimitive.content)
        assertEquals(12000, goal["tokenBudget"]!!.jsonPrimitive.int)
    }

    @Test
    fun countsRollbackTurnsFromEditedMessageToLatestTurn() {
        val messages = listOf(
            ChatMessage(ChatMessage.Role.User, "第一问", threadId = "thread_1", turnId = "turn_1"),
            ChatMessage(ChatMessage.Role.Assistant, "第一答", threadId = "thread_1", turnId = "turn_1"),
            ChatMessage(ChatMessage.Role.User, "第二问", threadId = "thread_1", turnId = "turn_2"),
            ChatMessage(ChatMessage.Role.Assistant, "第二答", threadId = "thread_1", turnId = "turn_2"),
            ChatMessage(ChatMessage.Role.User, "第三问", threadId = "thread_1", turnId = "turn_3"),
            ChatMessage(ChatMessage.Role.Assistant, "第三答", threadId = "thread_1", turnId = "turn_3")
        )

        assertEquals(2, countRollbackTurnsFromMessage(messages, threadId = "thread_1", turnId = "turn_2"))
        assertEquals(1, countRollbackTurnsFromMessage(messages, threadId = "thread_1", turnId = "turn_3"))
        assertEquals(null, countRollbackTurnsFromMessage(messages, threadId = "thread_1", turnId = "turn_missing"))
    }

    @Test
    fun removesEditedAndLaterTurnsOnlyForMatchingThread() {
        val messages = listOf(
            ChatMessage(ChatMessage.Role.User, "第一问", threadId = "thread_1", turnId = "turn_1"),
            ChatMessage(ChatMessage.Role.Assistant, "第一答", threadId = "thread_1", turnId = "turn_1"),
            ChatMessage(ChatMessage.Role.User, "第二问", threadId = "thread_1", turnId = "turn_2"),
            ChatMessage(ChatMessage.Role.Assistant, "第二答", threadId = "thread_1", turnId = "turn_2"),
            ChatMessage(ChatMessage.Role.User, "第三问", threadId = "thread_1", turnId = "turn_3"),
            ChatMessage(ChatMessage.Role.Assistant, "第三答", threadId = "thread_1", turnId = "turn_3"),
            ChatMessage(ChatMessage.Role.User, "其它线程", threadId = "thread_2", turnId = "turn_2"),
            ChatMessage(ChatMessage.Role.User, "本地乐观消息")
        )

        val remaining = removeMessagesFromRolledBackTurns(messages, threadId = "thread_1", turnId = "turn_2")

        assertEquals(
            listOf(
                ChatMessage(ChatMessage.Role.User, "第一问", threadId = "thread_1", turnId = "turn_1"),
                ChatMessage(ChatMessage.Role.Assistant, "第一答", threadId = "thread_1", turnId = "turn_1"),
                ChatMessage(ChatMessage.Role.User, "其它线程", threadId = "thread_2", turnId = "turn_2"),
                ChatMessage(ChatMessage.Role.User, "本地乐观消息")
            ),
            remaining
        )
    }

    @Test
    fun parsesGoalFileStatusAndMcpResourceResponses() {
        val goalResult = CodexJson.parseToJsonElement(
            """{"goal":{"objective":"修复恢复","status":"inProgress","tokenBudget":12000}}"""
        ).jsonObject
        val dirResult = CodexJson.parseToJsonElement(
            """{"entries":[{"fileName":"README.md","isDirectory":false,"isFile":true},{"fileName":"app","isDirectory":true,"isFile":false}]}"""
        ).jsonObject
        val fileResult = CodexJson.parseToJsonElement("""{"dataBase64":"SGVsbG8="}""").jsonObject
        val accountResult = CodexJson.parseToJsonElement(
            """{"account":{"type":"chatgpt","email":"a@example.com","planType":"plus"},"requiresOpenaiAuth":false}"""
        ).jsonObject
        val configResult = CodexJson.parseToJsonElement(
            """{"config":{"model":"gpt-5.5","approval_policy":"on-request"}}"""
        ).jsonObject
        val resourceResult = CodexJson.parseToJsonElement(
            """{"contents":[{"uri":"file://a","mimeType":"text/plain","text":"hello"},{"uri":"file://b","blob":"SGk="}]}"""
        ).jsonObject

        val goal = parseThreadGoal(goalResult)
        val dir = parseFileDirectory(dirResult, path = "/home/user")
        val file = parseFilePreview(fileResult, path = "/home/user/README.md")
        val account = parseDesktopStatus(configResult, accountResult, null)
        val resource = parseMcpResourcePreview(resourceResult)

        assertEquals("修复恢复", goal?.objective)
        assertEquals(ThreadGoalStatus.InProgress, goal?.status)
        assertEquals(listOf("app", "README.md"), dir.entries.map { it.name })
        assertEquals("Hello", file.text)
        assertEquals("a@example.com", account.accountEmail)
        assertEquals("gpt-5.5", account.model)
        assertEquals("hello", resource.contents[0].text)
        assertEquals("[二进制资源: file://b]", resource.contents[1].text)
    }

    @Test
    fun buildsRemoteDirectoryPathSuggestionsForProjectPicker() {
        val params = buildReadDirectoryParams("/home")
        val dirResult = CodexJson.parseToJsonElement(
            """
            {
              "entries": [
                {"name":"zeta.txt","isDirectory":false,"isFile":true},
                {"name":"user","isDirectory":true,"isFile":false},
                {"name":"demo","isDirectory":true,"isFile":false}
              ]
            }
            """.trimIndent()
        ).jsonObject

        val directory = parseFileDirectory(dirResult, path = "/home")
        val suggestions = buildProjectPathSuggestions(directory)

        assertEquals("/home", params["path"]!!.jsonPrimitive.content)
        assertEquals(listOf("/home/demo", "/home/user", "/home/zeta.txt"), suggestions.map { it.path })
        assertEquals(listOf(true, true, false), suggestions.map { it.isDirectory })
    }

    @Test
    fun topOptionsExposeGoalModeAsToggleState() {
        val off = buildDesktopTopOptions(CodexRemoteUiState())
        val on = buildDesktopTopOptions(
            CodexRemoteUiState(
                goalModeEnabled = true
            )
        )

        assertEquals("目标关闭", off.goalLabel)
        assertEquals("追求目标", on.goalLabel)
    }

    @Test
    fun goalModeUsesCurrentPromptAsHiddenObjective() {
        val state = CodexRemoteUiState(goalModeEnabled = true)

        val goal = state.goalForPrompt(" 完成 APK 同步 ")

        assertEquals("完成 APK 同步", goal?.objective)
        assertEquals(ThreadGoalStatus.InProgress, goal?.status)
    }

    @Test
    fun projectPathCompletionReadsParentDirectoryAndFiltersPrefix() {
        val request = buildProjectPathCompletionRequest("/home/user/分类")
        val directory = FileDirectoryUi(
            path = "/home/user",
            entries = listOf(
                FileEntryUi(name = "分类系统", isDirectory = true, isFile = false),
                FileEntryUi(name = "分类系统_windows_no_symlinks.zip", isDirectory = false, isFile = true),
                FileEntryUi(name = "catkin_ws", isDirectory = true, isFile = false)
            )
        )

        val suggestions = buildProjectPathSuggestions(directory, prefix = request.prefix)

        assertEquals("/home/user", request.directoryPath)
        assertEquals("分类", request.prefix)
        assertEquals(
            listOf("/home/user/分类系统", "/home/user/分类系统_windows_no_symlinks.zip"),
            suggestions.map { it.path }
        )
    }

    @Test
    fun projectPathCompletionReadsTrailingSlashDirectoryWithEmptyPrefix() {
        val request = buildProjectPathCompletionRequest("/home/user/")
        val directory = FileDirectoryUi(
            path = "/home/user",
            entries = listOf(
                FileEntryUi(name = "codex-android-remote", isDirectory = true, isFile = false),
                FileEntryUi(name = "Downloads", isDirectory = true, isFile = false),
                FileEntryUi(name = "secret.txt", isDirectory = false, isFile = true)
            )
        )

        val suggestions = buildProjectPathSuggestions(directory, prefix = request.prefix)

        assertEquals("/home/user", request.directoryPath)
        assertEquals("", request.prefix)
        assertEquals(
            listOf("/home/user/codex-android-remote", "/home/user/Downloads", "/home/user/secret.txt"),
            suggestions.map { it.path }
        )
    }

    @Test
    fun directoryProjectPathSuggestionKeepsTrailingSlashForFurtherCompletion() {
        val selectedPath = ProjectPathSuggestionUi(
            path = "/home/user",
            name = "user",
            isDirectory = true,
            isFile = false
        ).pathForManualSelection()
        val request = buildProjectPathCompletionRequest(selectedPath)

        assertEquals("/home/user/", selectedPath)
        assertEquals("/home/user", request.directoryPath)
        assertEquals("", request.prefix)
    }

    @Test
    fun buildsCwdMetadataValidationParams() {
        val params = buildGetMetadataParams("/home/user/分类系统/")
        val metadata = parseFileMetadata(
            CodexJson.parseToJsonElement("""{"path":"/home/user/分类系统","isDirectory":true,"isFile":false}""").jsonObject,
            fallbackPath = "/home/user/分类系统"
        )

        assertEquals("/home/user/分类系统", params["path"]!!.jsonPrimitive.content)
        assertTrue(metadata.isUsableCwd)
    }

    @Test
    fun buildsAttachmentUploadParamsAndTurnInputs() {
        val attachment = PendingAttachmentUi(
            id = "att_1",
            displayName = "screen.png",
            mimeType = "image/png",
            hostPath = "/tmp/codex-remote-uploads/att_1/screen.png",
            status = AttachmentUploadStatus.Ready
        )
        val mkdir = buildCreateDirectoryParams("/tmp/codex-remote-uploads/att_1")
        val write = buildWriteFileParams(attachment.hostPath, byteArrayOf(72, 105))
        val turn = buildTurnStartParams(
            threadId = "thread_1",
            prompt = "看这张图",
            modelId = "gpt-5.5",
            accessMode = AccessMode.OnRequest,
            attachments = listOf(attachment)
        )
        val input = turn["input"]!!.jsonArray

        assertEquals("/tmp/codex-remote-uploads/att_1", mkdir["path"]!!.jsonPrimitive.content)
        assertEquals("SGk=", write["dataBase64"]!!.jsonPrimitive.content)
        assertEquals("text", input[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("localImage", input[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(attachment.hostPath, input[1].jsonObject["path"]!!.jsonPrimitive.content)
    }

    @Test
    fun planImplementationLeavesPlanModeBeforeBuildingExecutionPrompt() {
        val plan = ActiveTurnPlan(
            threadId = "thread_1",
            turnId = "turn_1",
            explanation = "先确认计划",
            steps = listOf(TurnPlanStepUi("修改 UI", TurnPlanStatus.Pending))
        )
        val before = CodexRemoteUiState(planModeEnabled = true, activePlan = plan)

        val after = before.forPlanImplementation()

        assertFalse(after.planModeEnabled)
        assertNull(after.activePlan)
        assertEquals("Implement the plan.", after.promptForSend("Implement the plan."))
    }

    @Test
    fun planReviewUsesSingleDesktopStyleThreeChoicePrompt() {
        val actions = defaultPlanReviewActions()

        assertEquals("Plan 模式需要选择", planReviewPromptTitle())
        assertEquals(listOf("执行任务", "继续完善", "暂不执行"), actions.map { it.label })
        assertEquals(PlanReviewAction.ExecuteTask, actions.first().action)
        assertTrue(actions.first().recommended)
    }

    @Test
    fun planInteractionRecognizesDesktopStyleExecuteAndContinueLabels() {
        val interaction = PendingInteraction(
            requestId = 9,
            method = "item/tool/requestUserInput",
            kind = PendingInteractionKind.ToolUserInput,
            title = "Plan 模式需要选择",
            body = "请选择下一步"
        )
        val before = CodexRemoteUiState(
            planModeEnabled = true,
            pendingInteraction = interaction,
            activePlan = ActiveTurnPlan(
                threadId = "thread_1",
                turnId = "turn_1",
                explanation = null,
                steps = listOf(TurnPlanStepUi("修改 UI", TurnPlanStatus.Pending))
            )
        )

        val execute = before.afterPendingInteractionResponse(
            action = InteractionAction.Accept,
            answers = mapOf("choice" to listOf("执行任务"))
        )
        val continuePlan = before.afterPendingInteractionResponse(
            action = InteractionAction.Accept,
            answers = mapOf("choice" to listOf("继续完善"))
        )

        assertFalse(execute.planModeEnabled)
        assertNull(execute.activePlan)
        assertTrue(continuePlan.planModeEnabled)
        assertNull(continuePlan.activePlan)
    }

    @Test
    fun runtimeMessagesExposeDesktopStyleWarningAndErrorTones() {
        val warning = parseRuntimeNotice(
            "warning",
            CodexJson.parseToJsonElement("""{"message":"需要注意"}""").jsonObject
        )
        val failure = RecoverableError(
            kind = RecoverableErrorKind.TurnFailed,
            title = "任务失败",
            detail = "boom"
        )

        assertEquals(MessageTone.Warning, warning?.tone())
        assertEquals(MessageTone.Error, failure.tone())
    }

    @Test
    fun continuedPlanningKeepsPlanModeEnabled() {
        val plan = ActiveTurnPlan(
            threadId = "thread_1",
            turnId = "turn_1",
            explanation = null,
            steps = listOf(TurnPlanStepUi("补充方案", TurnPlanStatus.Pending))
        )
        val before = CodexRemoteUiState(planModeEnabled = true, activePlan = plan)

        val after = before.forContinuedPlanning()

        assertTrue(after.planModeEnabled)
        assertNull(after.activePlan)
        assertTrue(after.promptForSend("继续完善计划。").startsWith("请先制定计划"))
    }

    @Test
    fun acceptingPlanExecutionInteractionLeavesPlanMode() {
        val interaction = PendingInteraction(
            requestId = 9,
            method = "item/tool/requestUserInput",
            kind = PendingInteractionKind.ToolUserInput,
            title = "计划完成",
            body = "怎么继续？"
        )
        val before = CodexRemoteUiState(
            planModeEnabled = true,
            pendingInteraction = interaction,
            activePlan = ActiveTurnPlan(
                threadId = "thread_1",
                turnId = "turn_1",
                explanation = null,
                steps = listOf(TurnPlanStepUi("修改 UI", TurnPlanStatus.Pending))
            )
        )

        val after = before.afterPendingInteractionResponse(
            action = InteractionAction.Accept,
            answers = mapOf("choice" to listOf("立刻执行"))
        )

        assertFalse(after.planModeEnabled)
        assertNull(after.pendingInteraction)
        assertNull(after.activePlan)
    }

    @Test
    fun acceptingContinuedPlanningInteractionKeepsPlanMode() {
        val before = CodexRemoteUiState(
            planModeEnabled = true,
            pendingInteraction = PendingInteraction(
                requestId = 9,
                method = "item/tool/requestUserInput",
                kind = PendingInteractionKind.ToolUserInput,
                title = "计划完成",
                body = "怎么继续？"
            )
        )

        val after = before.afterPendingInteractionResponse(
            action = InteractionAction.Accept,
            answers = mapOf("choice" to listOf("继续完善计划"))
        )

        assertTrue(after.planModeEnabled)
        assertNull(after.pendingInteraction)
    }

    @Test
    fun removedAttachmentIsNotRestoredByUploadCompletion() {
        val uploading = PendingAttachmentUi(
            id = "att_1",
            displayName = "screen.png",
            mimeType = "image/png",
            hostPath = "/tmp/codex-remote-uploads/att_1/screen.png",
            status = AttachmentUploadStatus.Uploading
        )
        val state = CodexRemoteUiState(pendingAttachments = listOf(uploading))
            .withoutPendingAttachment("att_1")
            .withAttachmentUploadCompleted("att_1")

        assertTrue(state.pendingAttachments.isEmpty())
    }

    @Test
    fun parsesMessageMarkdownIntoReadableBlocks() {
        val blocks = parseMessageMarkdown(
            """
            是，**收到的消息没有复制选项**。

            普通消息只是 `Text`，文件在 [CodexRemoteApp.kt](/home/user/project/CodexRemoteApp.kt:2703)。

            ```kotlin
            Text(message.text, ...)
            ```
            """.trimIndent()
        )

        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is MessageMarkdownBlock.Paragraph)
        assertTrue((blocks[0] as MessageMarkdownBlock.Paragraph).spans.any { it is MessageMarkdownSpan.Bold && it.text == "收到的消息没有复制选项" })
        assertTrue((blocks[1] as MessageMarkdownBlock.Paragraph).spans.any { it is MessageMarkdownSpan.Code && it.text == "Text" })
        assertTrue((blocks[1] as MessageMarkdownBlock.Paragraph).spans.any { it is MessageMarkdownSpan.Link && it.label == "CodexRemoteApp.kt" })
        assertEquals(
            MessageMarkdownBlock.Code(language = "kotlin", code = "Text(message.text, ...)"),
            blocks[2]
        )
    }

    @Test
    fun goalNotificationsUpdateModeAndGoalState() {
        val updated = CodexRemoteUiState().withGoalUpdatedFromNotification(
            CodexJson.parseToJsonElement(
                """{"threadId":"thread_1","goal":{"objective":"继续完成当前任务","status":"inProgress"}}"""
            ).jsonObject
        )
        val cleared = updated.withGoalClearedFromNotification(
            CodexJson.parseToJsonElement("""{"threadId":"thread_1"}""").jsonObject
        )

        assertTrue(updated.goalModeEnabled)
        assertEquals("继续完成当前任务", updated.activeGoal?.objective)
        assertFalse(cleared.goalModeEnabled)
        assertNull(cleared.activeGoal)
    }

    @Test
    fun filtersArchivedThreadsUnlessRequested() {
        val threads = listOf(
            ThreadSummary(id = "thread_1", title = "保留"),
            ThreadSummary(id = "thread_2", title = "归档")
        )

        assertEquals(listOf(threads[0]), filterThreadsForArchiveState(threads, setOf("thread_2"), showArchived = false))
        assertEquals(threads, filterThreadsForArchiveState(threads, setOf("thread_2"), showArchived = true))
    }

    @Test
    fun renamedThreadUpdatesTitleInState() {
        val state = CodexRemoteUiState(
            activeThreadId = "thread_1",
            threads = listOf(ThreadSummary(id = "thread_1", title = "旧标题"))
        )

        val renamed = state.withRenamedThread("thread_1", "新标题")

        assertEquals("新标题", renamed.threads.single().title)
        assertEquals("新标题", renamed.activeThreadTitle)
    }
}
