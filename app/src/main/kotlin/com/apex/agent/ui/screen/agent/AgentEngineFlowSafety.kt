package com.apex.agent.ui.screen.agent

import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import com.apex.agent.R
import com.apex.agent.core.engine.AgentEvent
import com.apex.agent.core.engine.ApexAgentEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

// ─────────────────────────────────────────────────────────────────────────────
// 引擎回合生命周期（turn lifecycle）—— Agent 屏流式输出 / 输入框执行态的
// 统一收口层（自 AgentEngineFlowSafety.kt 演进；P1-2 修复的全面强化版）。
//
// ═══ 问题背景（用户反馈：任务结束后发送按钮仍显示执行中）═══
//
// 旧实现把 isLoading 的复位完全寄托在「终止事件（Complete/Error/Aborted）
// 能送达一个活着的收集器」上，存在多条结构性漏洞：
//
//  1. runEngine / collectEngineFlowSafely 的 catch(CancellationException) 重抛
//     后没有任何 finally 兜底 —— 收集协程一旦在约定外路径被取消（恢复任务
//     覆盖 / 会话切换 / Complete 分支 withContext 挂起瞬间被取消），终止事件
//     永远到不了 UI，isLoading 永久为 true → 发送按钮卡在「停止」。
//  2. resumeTask / retryTask / resumeCrashedTask 三条路径不置 isLoading=true
//     —— 恢复/重试运行期间输入栏显示「发送」而非「停止」，与任务状态卡的
//     「执行中」互相矛盾，用户也无法从输入栏中止。
//  3. 会话切换（restoreChatSession/newChat）只取消 UI 收集器，不取消
//     TaskRuntime 里的僵尸任务 —— 引擎在后台继续烧 token / 执行工具，且
//     restoreHistory/clearHistory 与引擎循环并发修改同一 ArrayList。
//
// ═══ 修复模式（对齐 CodeViewModel.runEngine 的 finally 收尾范式）═══
//
//  - launchEngineTurn：所有引擎回合的统一启动器 —— block 无论正常结束 /
//    异常 / 取消，finally 必经 finalizeEngineTurn 兜底；
//  - finalizeEngineTurn：守卫式幂等收尾 —— 仅当「本回合仍是当前回合」时
//    执行（防止迟到的旧回合收尾踩掉新回合刚建立的执行态），刷出缓冲、
//    salvage 残留流式产物、复位 isLoading 与全部运行态槽位；
//  - beginEngineTurn：发送瞬间即置执行态（不等僵尸任务收尾的最长 10s
//    空窗），发送键立即变停止键、加载条立即出现。
//
// 依赖的 _uiState / handleEvent / currentJob 已在 AgentChatViewModel 开放 internal。
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 引擎回合统一启动器：viewModelScope.launch + 无条件收尾守卫。
 *
 * block 的任何退出路径（正常返回 / 抛异常 / 协程取消）都会触发
 * [finalizeEngineTurn] —— 「任务结束后输入框发送按钮还显示执行中」的
 * 结构性根治：收尾不再依赖任何事件的到达。
 */
internal fun AgentChatViewModel.launchEngineTurn(
    block: suspend CoroutineScope.() -> Unit
): Job = viewModelScope.launch {
    try {
        block()
    } finally {
        finalizeEngineTurn(coroutineContext[Job])
    }
}

/** 回合开始：即刻置执行态（发送键→停止键、加载条出现；不等僵尸任务收尾）。 */
internal fun AgentChatViewModel.beginEngineTurn() {
    _uiState.update { it.copy(isLoading = true) }
}

/**
 * 回合收尾（守卫式 + 幂等）。
 *
 * 守卫：[turnJob] 不再是 [AgentChatViewModel.currentJob] 时直接跳过 ——
 * 旧回合被新回合覆盖时（sendMessage / resume / 会话切换），迟到的收尾
 * 不得踩掉新回合已建立的 isLoading=true 或新回合正在累积的流式内容。
 * 新回合的 runEngine 启动段自有同款 salvage（残留产物落为 isPartial）。
 * turnJob == null（无主收尾，如 newChat 清理协程）：任何活跃回合在场
 * 时同样跳过，不踩新回合。
 *
 * 幂等：正常路径下终止事件（Complete/Error/Aborted）已把各槽位清空，
 * 此处 flush/salvage 全为 no-op；StateFlow.copy 的字段相等性也会让多余
 * 一次 update 被合并（不触发重组）。
 */
internal fun AgentChatViewModel.finalizeEngineTurn(turnJob: Job?) {
    when {
        turnJob != null -> if (currentJob !== turnJob) return
        currentJob?.isActive == true -> return
    }
    salvageStreamingOutput()
    _uiState.update { state ->
        state.copy(
            isLoading = false,
            pendingUserInput = null,
            awaitingPlanConfirmation = false,
            awaitingSpecConfirmation = false,
            currentStepIndex = -1
        )
    }
}

/**
 * 无守卫版流式收尾：刷出缓冲 → 残留部分产物 salvage 为消息 → 清空全部
 * 流式 / 工具运行态。
 *
 * 供三方调用：[finalizeEngineTurn]（回合兜底）、abort()（用户中止 ——
 * 引擎 Aborted 事件到不了已取消的收集器，必须本地补偿）、会话切换
 * （restore/newChat 归档前保住在途内容，不再无声丢失）。
 *
 * 与旧 resetStreamingState() 的差异：缓冲先 flush、currentResponse/
 * currentThinking 的非空快照落为 isPartial 消息（abort() 同款语义 ——
 * 已流出的内容不该无声消失），再执行清空。
 */
internal fun AgentChatViewModel.salvageStreamingOutput() {
    streamBuffers.flush()
    val partialResponse = _uiState.value.currentResponse
    val partialThinking = _uiState.value.currentThinking
    val partialThinkingMs = if (thinkingStartElapsed > 0) {
        SystemClock.elapsedRealtime() - thinkingStartElapsed
    } else 0L
    thinkingStartElapsed = 0

    // 工具运行态清理（与 abort() 旧清理面同集合：flush Job / 活跃工具 /
    // 输出缓冲 / 活输出步 / 步骤流）。
    toolFlushJob?.cancel()
    toolFlushJob = null
    activeToolCallId = null
    toolOutputBuffer.setLength(0)
    liveOutputStepId = null
    currentToolCallSteps = null

    // 流水线横幅收口（在途 banner 置为完成态，停止脉冲）。
    finishActiveBanner()
    routeContextKind = null
    routeContextName = null

    _uiState.update { state ->
        val salvage = buildList<AgentUiMessage> {
            if (partialResponse.isNotBlank()) {
                add(AgentUiMessage.Agent(text = partialResponse, isPartial = true))
            }
            if (partialThinking.isNotBlank()) {
                add(AgentUiMessage.ThinkingMessage(partialThinking, partialThinkingMs))
            }
        }
        state.copy(
            messages = state.messages + salvage,
            currentResponse = "",
            currentThinking = "",
            currentThinkingStartElapsed = 0,
            currentToolCall = null
        )
    }
}

/**
 * 引擎流安全收集器（原 P1-2 修复语义保留 + 回合收尾强化）。
 *
 * resumeTask / retryTask / resumeCrashedTask / 斜杠与多 chip 流水线 /
 * runEngine 五条路径的公共收集体：
 *  - CancellationException 重抛（保留协程取消语义；收尾由
 *    [launchEngineTurn] 的 finally 统一兜底，不再依赖事件到达）；
 *  - 其余异常转可重试的 AgentUiMessage.Error 并 salvage 部分产物；
 *  - 已被新回合覆盖（currentJob 更替）时只留痕不打扰 —— 旧回合的错误
 *    气泡与槽位清空不得串进新回合的时间线。
 */
internal suspend fun AgentChatViewModel.collectEngineFlowSafely(flow: Flow<AgentEvent>) {
    val turnJob = coroutineContext[Job]
    try {
        flow.collect { event -> handleEvent(event) }
    } catch (e: CancellationException) {
        throw e // 协程取消必须重抛（用户发送新消息会 cancel 旧 job）
    } catch (e: Exception) {
        if (currentJob !== turnJob) {
            // 已被新回合接管：错误信息不落新时间线（旧时间线也已不可达）。
            android.util.Log.w(
                "AgentChatViewModel",
                "superseded turn failed (suppressed): ${e::class.simpleName}: ${e.message}"
            )
            return
        }
        // 与 AgentEvent.Error 分支同款收尾：异常冒泡时事件流戛然而止，
        // 部分回复 salvage 为 isPartial、思考流式槽清空 —— 否则「推理中…」
        // 气泡会挂到下一条消息。isLoading 复位交给 launchEngineTurn 的
        // finally（此处不重复置位，避免与 finally 竞态）。
        streamBuffers.flush()
        val partial = _uiState.value.currentResponse
        _uiState.update { s ->
            s.copy(
                messages = s.messages +
                    (if (partial.isNotBlank()) {
                        listOf(AgentUiMessage.Agent(text = partial, isPartial = true))
                    } else {
                        emptyList()
                    }) +
                    listOf(
                        AgentUiMessage.Error(
                            message = strFmt(
                                R.string.chat_execution_failed,
                                e.message ?: e::class.simpleName ?: ""
                            ),
                            canRetry = true
                        )
                    ),
                currentResponse = "",
                currentThinking = "",
                currentThinkingStartElapsed = 0
            )
        }
    }
}

/**
 * Complete 事件的轻重分离：重指标（全量历史 JSON 解析 + token 逐字符
 * 估算）与完成通知脱离收集协程后台补算 —— 收集器在关键收尾（isLoading
 * 复位 + salvage + 总结卡）之后的任何瞬间被取消，都不再吞掉这些收尾
 * （旧实现在 withContext(Dispatchers.Default) 挂起点被取消时，
 * isLoading=false 的最终更新整段跳过）。
 */
internal fun AgentChatViewModel.completeTurnMetricsAsync(summary: String) {
    viewModelScope.launch(Dispatchers.Default) {
        // 防御式 IO：historyCount()（全量 JSON 解析）/ currentTokenCount() 在
        // 历史损坏时可能抛异常 —— 本协程已脱离收集器的 catch 链，裸抛会炸
        // viewModelScope；指标是增益数据，失败只留痕（仪表盘保持旧值）。
        runCatching {
            val engine = agentEngine as? ApexAgentEngine
            val historyDepth = engine?.historyCount()
            val usedTokens = engine?.currentTokenCount()
            val maxTokens = engine?.maxContextTokens()
            _uiState.update {
                it.copy(
                    historyDepth = historyDepth ?: it.historyDepth,
                    contextUsedTokens = usedTokens ?: it.contextUsedTokens,
                    contextMaxTokens = maxTokens ?: it.contextMaxTokens
                )
            }
        }.onFailure { e ->
            android.util.Log.w(
                "AgentChatViewModel",
                "turn metrics failed (suppressed): ${e::class.simpleName}: ${e.message}"
            )
        }
        // ═══ v1.4.4 #4：任务完成通知（App 在后台时才提醒；前台静音）═══
        // 判定顺序：设置开关 → 用户是否在场 → 通知权限；三者全过才发射。
        // 通知是增益路径，任何一环不满足都静默跳过（绝不阻塞收尾）。
        // #237：设置开 + 后台 + 权限被拒的组合不再静默 —— 置位引导条。
        runCatching {
            val settingsSnapshot = settingsRepository.agentSettings.value
            if (settingsSnapshot.taskCompletionNotify && !foregroundTracker.isForeground) {
                if (notifications.canPost(context)) {
                    val triggerText = _uiState.value.messages
                        .lastOrNull { it is AgentUiMessage.User }
                        ?.let { (it as AgentUiMessage.User).text?.trim()?.take(40) }
                    val title = triggerText?.takeIf { it.isNotBlank() }
                        ?: str(R.string.notif_task_done_fallback_title)
                    notifications.notifyTaskDone(context, title, summary)
                } else {
                    _uiState.update { it.copy(notifPermissionHint = true) }
                }
            }
        }
    }
}
