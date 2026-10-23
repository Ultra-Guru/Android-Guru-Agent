package com.apex.agent.ui.screen.agent

import androidx.lifecycle.viewModelScope
import com.apex.agent.R
import com.apex.agent.core.engine.ApexAgentEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

// ─────────────────────────────────────────────────────────────────────────────
// 历史对话 —— AgentChatViewModel 的 internal 扩展（God-file 预算拆分，
// 模式同 AgentChatQuestionHandler.kt：调用点无感知，依赖的成员已开放 internal）。
//
// 归档策略：
//  - uiState 消息流 800ms 防抖快照落盘（ChatHistoryManager）；
//  - 首条消息产生时分配 sessionId，之后同一会话 upsert；
//  - newChat() 前 flushChatHistoryNow() 同步冲刷 + 复位 sessionId；
//  - 恢复会话 = 消息回填 UI + user/agent 文本对回填引擎上下文（详见
//    ChatHistoryModels.kt 文件头「设计取舍」）。
// ─────────────────────────────────────────────────────────────────────────────

/** 安装历史会话自动归档（VM init 调用一次，幂等）。 */
internal fun AgentChatViewModel.installChatHistoryAutoPersist() {
    // 会话列表初始加载（IO 读盘）—— launchSafely：JSON/磁盘异常不炸进程
    launchSafely(Dispatchers.IO, tag = "chatHistoryLoad") {
        _chatSessions.value = chatHistory.loadSessions()
    }
    // 消息流防抖归档：collect 时捕获当时的 sessionId —— 800ms 内切了新会话，
    // 旧快照仍归档进旧会话，不会错挂到新会话头上。
    launchSafely(tag = "chatHistoryAutoPersist") {
        uiState.collect { state ->
            historyPersistJob?.cancel()
            val sessionIdAtCollect = currentHistorySessionId
            historyPersistJob = launch {
                delay(PERSIST_DEBOUNCE_MS)
                persistChatHistorySnapshot(state, sessionIdAtCollect)
            }
        }
    }
}

/**
 * 把当前消息快照归档进历史。
 *
 * @param presetSessionId collect 时捕获的会话 id（null = 当时是新会话）。
 *   执行时 currentHistorySessionId 已非 null（如恢复会话）则沿用现值，
 *   避免同一会话被拆成两条。
 */
internal fun AgentChatViewModel.persistChatHistorySnapshot(
    state: AgentChatUiState,
    presetSessionId: String?
) {
    val historyMessages = state.messages.mapNotNull { it.toHistoryMessage() }
    if (historyMessages.isEmpty()) return

    val sessionId = when {
        // presetSessionId 是函数参数（val）：smart cast 到 String 成立
        presetSessionId != null -> presetSessionId
        // currentHistorySessionId 是跨类可变属性：无法 smart cast，用 ?: 兜底建新档
        else -> currentHistorySessionId ?: UUID.randomUUID().toString()
            .also { currentHistorySessionId = it }
    }
    val createdAt = currentHistorySessionCreatedAt ?: System.currentTimeMillis()
    if (currentHistorySessionCreatedAt == null) currentHistorySessionCreatedAt = createdAt

    // 模型标记（展示用）：当前选中档案的 modelId / 名称
    val modelLabel = profiles.value.firstOrNull { it.id == currentProfileId.value }
        ?.let { p -> p.modelId.ifBlank { p.name } } ?: ""

    // v1.4.4 #5：重命名（customTitle）与置顶（pinned）跨归档保留 ——
    // 自动归档重建 summary 时从内存会话索引回读这两个用户标记，
    // 否则每次 800ms 防抖落盘都会把自定义标题打回首条消息截断、置顶清零。
    val existing = _chatSessions.value.firstOrNull { it.id == sessionId }
    val customTitle = existing?.customTitle == true
    val pinned = existing?.pinned ?: false

    val summary = ChatSessionSummary(
        id = sessionId,
        title = if (customTitle && existing != null) existing.title else historyMessages.historyTitle(),
        createdAt = createdAt,
        updatedAt = System.currentTimeMillis(),
        messageCount = historyMessages.size,
        modelId = modelLabel,
        pinned = pinned,
        customTitle = customTitle
    )
    viewModelScope.launch(Dispatchers.IO) {
        // v1.4.9 闪退防御：归档链（JSON 编码 / 索引合并 / 磁盘写）任一异常
        // 只留痕 —— 会话快照丢失可接受，进程存活优先。
        runCatching {
            // Issue #220：滚动归档不再静默删数据 —— 有会话被移入归档区时给用户
            // 可见提示（旧实现直接 editor.remove，最旧会话无声消失）。
            val evicted = chatHistory.saveSession(summary, historyMessages)
            _chatSessions.value = chatHistory.loadSessions()
            evicted.firstOrNull()?.let { oldest ->
                _uiFeedback.tryEmit(strFmt(R.string.chat_history_archived_notice, oldest.title))
            }
        }.onFailure { e ->
            android.util.Log.e(
                "AgentChatViewModel",
                "chat history persist failed (suppressed): ${e.message}",
                e
            )
        }
    }
}

/**
 * 立即归档当前会话并复位会话 id（newChat / 恢复其它会话前调用）。
 * 取消未触发的防抖任务，保证「最后一次编辑不丢」且不与下一次归档竞争。
 */
internal fun AgentChatViewModel.flushChatHistoryNow() {
    historyPersistJob?.cancel()
    historyPersistJob = null
    val state = _uiState.value
    if (state.messages.isNotEmpty()) {
        persistChatHistorySnapshot(state, currentHistorySessionId)
    }
    currentHistorySessionId = null
    currentHistorySessionCreatedAt = null
}

/**
 * 恢复历史会话：消息回填消息流，user/agent 文本对回填引擎上下文，
 * 后续对话自然接续该会话的历史。
 *
 * 回合修复（僵尸任务 + 引擎历史并发修改）：
 *  - 旧实现只取消 UI 收集器（currentJob），TaskRuntime 镜像收集器与引擎
 *    继续在后台烧 token / 执行工具；restoreHistory 直接 clear+addAll 替换
 *    引擎正在迭代的 conversationHistory（无锁 ArrayList）→ 跨会话上下文
 *    污染 + ConcurrentModificationException 风险；下一轮 sendMessage 还要
 *    等僵尸收尾最长 10s。现在并行（读盘 × 任务取消）后串行恢复；
 *  - 切换前 salvage 在途流式产物并随归档落盘（旧实现直接丢弃）；
 *  - 恢复期间用户发了新消息（currentJob 已被新一轮替换）→ 放弃恢复，
 *    新意图优先（新消息不会被旧会话的上下文覆盖）。
 */
internal fun AgentChatViewModel.restoreChatSession(sessionId: String) {
    // 混沌审查修复：与 sendMessage 对齐，覆盖前取消旧收集器，防双消费者交错写 _uiState
    currentJob?.cancel()
    currentJob = viewModelScope.launch {
        try {
            // 并行：僵尸任务收尾（最长 10s）× 历史读盘（IO）—— 总耗时取 max。
            val zombieCancel = async { taskController.cancel() }
            val (messages, createdAt) = withContext(Dispatchers.IO) {
                chatHistory.loadMessages(sessionId) to chatHistory.sessionCreatedAt(sessionId)
            }
            zombieCancel.await() // 串行化：引擎历史变更必须等在途任务真正停下来

            // 切换期间用户已发新消息（currentJob 已被新一轮替换）→ 放弃恢复（新意图优先）
            if (currentJob !== coroutineContext[Job]) return@launch

            // 在途部分产物 salvage 进消息流 → 随归档落盘（不再无声丢失）
            salvageStreamingOutput()
            flushChatHistoryNow()

            if (messages.isEmpty()) {
                // ═══ 静默失败修复：消息读不到（msg_ 键损坏/孤儿键清理）时给出
                // 可见反馈 —— 抽屉已关、若无提示用户视角=点了没反应。═══
                _uiFeedback.tryEmit(str(R.string.chat_restore_empty))
                return@launch
            }

            // 引擎上下文恢复（内存 history + 持久化 memory 同步替换）
            val llmMessages = messages.mapNotNull { it.toLlmMessage() }
            (agentEngine as? ApexAgentEngine)?.restoreHistory(llmMessages)

            resetStreamingState()

            _uiState.update {
                it.copy(
                    messages = messages.mapNotNull { m -> m.toUiMessage() },
                    currentThinking = "",
                    currentResponse = "",
                    currentToolCall = null,
                    plan = null,
                    awaitingPlanConfirmation = false,
                    spec = null,
                    awaitingSpecConfirmation = false,
                    pendingUserInput = null,
                    isLoading = false,
                    historyDepth = llmMessages.size
                )
            }
            currentHistorySessionId = sessionId
            currentHistorySessionCreatedAt = createdAt ?: System.currentTimeMillis()
        } finally {
            // 恢复协程自身被取消/完成时兜底复位执行态（守卫式，不踩新回合）。
            finalizeEngineTurn(coroutineContext[Job])
        }
    }
}

/**
 * 新开会话：当前会话先归档进历史（含取消未触发的防抖归档任务），
 * 再清空引擎历史 / 持久化记忆 / UI 消息流与全部流式运行态。
 * （自 AgentChatViewModel.kt 迁入 —— 行数预算拆分；调用点 viewModel.newChat() 无感知。）
 *
 * 回合修复：① 在途流式产物 salvage 后随归档落盘（不再无声丢失）；
 * ② 僵尸任务收尾串行化 —— 旧实现不取消 TaskRuntime 在途任务，
 * clearHistory 与引擎循环并发修改同一 ArrayList（跨会话污染 +
 * ConcurrentModificationException 风险）；③ 切换期间用户发新消息
 * （currentJob 已被替换）→ 放弃清空（新意图优先）。
 */
internal fun AgentChatViewModel.newChat() {
    // 切换令牌：此刻的在途回合（下方取消）—— 清空历史前比对 currentJob，
    // 令牌被替换即说明用户在僵尸收尾期间发了新消息（新意图优先）。
    val switchToken = currentJob
    currentJob?.cancel()
    // 在途部分产物 salvage 进消息流 → 随归档落盘（不再无声丢失）
    salvageStreamingOutput()
    // 当前会话先归档进历史（含取消未触发的防抖归档），再复位会话 id
    flushChatHistoryNow()
    // 清空所有流式/工具运行态，防止残留缓冲串入新会话。
    resetStreamingState()
    viewModelScope.launch {
        try {
            // 僵尸任务收尾：镜像收集器/执行锁不随 UI 收集器取消而停 ——
            // 引擎历史变更（clearHistory）必须等在途任务真正停下来。
            taskController.cancel()
            // 切换期间用户已发新消息（currentJob 已被令牌外的回合替换，
            // 含「已快速跑完」的回合）→ 放弃清空（新意图优先）。
            if (currentJob !== switchToken && currentJob != null) return@launch
            (agentEngine as? ApexAgentEngine)?.clearHistory()
            _uiState.update {
                it.copy(
                    messages = emptyList(),
                    currentThinking = "",
                    currentResponse = "",
                    currentToolCall = null,
                    plan = null,
                    awaitingPlanConfirmation = false,
                    spec = null,
                    awaitingSpecConfirmation = false,
                    pendingUserInput = null,
                    isLoading = false,
                    historyDepth = 0
                )
            }
        } finally {
            // 兜底复位执行态（守卫式：新会话清理协程被新一轮覆盖时不踩新回合）。
            finalizeEngineTurn(null)
        }
    }
}

/**
 * 删除单个历史会话（正在聊的那条也允许删：删除后当前会话脱离历史索引）。
 *
 * P2 修复：① 先取消在途防抖归档 + ChatHistoryManager 墓碑拦截，防删除后
 * 迟到快照把会话复活；② 删除前先收集该会话持久化的附件路径，删除索引后
 * 同步清理附件文件（旧实现只删 SharedPreferences，附件永久残留）。
 *
 * ═══ 幽灵会话修复：删除「正在聊的」会话 = 对齐 newChat() 语义 ═══
 * 旧实现只脱离索引（sessionId 置 null）不清 UI 消息流 —— 消息还挂在屏幕
 * 上，下一条消息触发 auto-persist 时 collector 捕获到 null 的 sessionId，
 * 把「已删对话全套消息 + 新消息」以新 UUID 存成全新会话（墓碑只拦旧 id，
 * 拦不住新 UUID）。现在删除当前会话时同步清空消息流与引擎历史 —— 删除
 * 即彻底，屏幕回到新会话状态。
 */
internal fun AgentChatViewModel.deleteChatSession(sessionId: String) {
    historyPersistJob?.cancel()
    historyPersistJob = null
    if (currentHistorySessionId == sessionId) {
        currentHistorySessionId = null
        currentHistorySessionCreatedAt = null
        // 对齐 newChat()：取消在途收集 + 清流式运行态 + 清引擎历史与消息流
        currentJob?.cancel()
        resetStreamingState()
        viewModelScope.launch {
            (agentEngine as? ApexAgentEngine)?.clearHistory()
            _uiState.update {
                it.copy(
                    messages = emptyList(),
                    currentThinking = "",
                    currentResponse = "",
                    currentToolCall = null,
                    plan = null,
                    awaitingPlanConfirmation = false,
                    spec = null,
                    awaitingSpecConfirmation = false,
                    pendingUserInput = null,
                    isLoading = false,
                    historyDepth = 0
                )
            }
        }
    }
    viewModelScope.launch(Dispatchers.IO) {
        // 先读后删：deleteSession 后 msg_ 键即消失，附件路径取不到了
        val attachmentPaths = chatHistory.loadMessages(sessionId)
            .flatMap { it.attachmentPaths }
        chatHistory.deleteSession(sessionId)
        if (attachmentPaths.isNotEmpty()) {
            attachmentCleanup.cleanupFiles(attachmentPaths)
        }
        _chatSessions.value = chatHistory.loadSessions()
    }
}

/**
 * 清空全部历史会话（当前会话同时脱离历史索引）。
 * 同样先取消在途归档 + 逐会话收集附件路径统一清理。
 */
internal fun AgentChatViewModel.clearAllChatSessions() {
    historyPersistJob?.cancel()
    historyPersistJob = null
    currentHistorySessionId = null
    currentHistorySessionCreatedAt = null
    viewModelScope.launch(Dispatchers.IO) {
        val attachmentPaths = (
            chatHistory.loadSessions().flatMap { s -> chatHistory.loadMessages(s.id) } +
                // Issue #220：归档区一并清空 —— 归档会话引用的附件文件同步回收
                chatHistory.loadArchivedSessions().flatMap { s -> chatHistory.loadArchivedMessages(s.id) }
            ).flatMap { it.attachmentPaths }
        chatHistory.clearAll()
        if (attachmentPaths.isNotEmpty()) {
            attachmentCleanup.cleanupFiles(attachmentPaths)
        }
        _chatSessions.value = chatHistory.loadSessions()
    }
}

/** 清空流式 / 工具运行态（newChat 与恢复会话共用，防残留缓冲串会话）。 */
internal fun AgentChatViewModel.resetStreamingState() {
    streamBuffers.reset()
    toolFlushJob?.cancel()
    toolFlushJob = null
    toolOutputBuffer.clear()
    activeToolCallId = null
    liveOutputStepId = null
    currentToolCallSteps = null
    activeBannerId = null
    routeContextKind = null
    routeContextName = null
    // 思考计时器同步归零（防会话切换后 live 秒数残留）。
    thinkingStartElapsed = 0
    _uiState.update { it.copy(currentThinkingStartElapsed = 0) }
}

private const val PERSIST_DEBOUNCE_MS = 800L
