package com.apex.agent.ui.screen.agent

import com.apex.agent.core.engine.AgentEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.update

// ─────────────────────────────────────────────────────────────────────────────
// 引擎流安全收集器 —— P1-2 修复（6-c，从 AgentChatViewModel.kt 抽出以守住
// God-file 1200 行 SRP 预算，拆分惯例参考 AgentChatQuestionHandler.kt）。
//
// resumeTask / retryTask / resumeCrashedTask / handleSlashCommand(execute)
// 四条路径原先裸 collect 引擎流、无 try/catch —— runEngine 的"缺陷 4 修复"
// 证明引擎会在发射 AgentEvent.Error 之前抛异常（如 LLM IO），异常冒泡到
// viewModelScope（无 handler）→ 应用崩溃。
//
// 本收集器复制 runEngine 的兜底模式：
//  - CancellationException 重抛（保留协程取消语义，发送新消息会 cancel 旧 job）；
//  - 其余异常转可重试的 AgentUiMessage.Error 并复位 isLoading。
//
// 依赖的 _uiState / handleEvent 已在 AgentChatViewModel 中开放为 internal。
// ─────────────────────────────────────────────────────────────────────────────

internal suspend fun AgentChatViewModel.collectEngineFlowSafely(flow: Flow<AgentEvent>) {
    try {
        flow.collect { event -> handleEvent(event) }
    } catch (e: CancellationException) {
        throw e // 协程取消必须重抛（用户发送新消息会 cancel 旧 job）
    } catch (e: Exception) {
        // 与 AgentEvent.Error 分支同款收尾（2026-10 思考卡死补齐）：异常
        // 冒泡时事件流戛然而止，部分回复 salvage 为 isPartial、思考流式
        // 槽清空 —— 否则「推理中…」气泡会挂到下一条消息。
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
                            message = "执行失败：${e.message ?: e::class.simpleName}",
                            canRetry = true
                        )
                    ),
                isLoading = false,
                currentResponse = "",
                currentThinking = "",
                currentThinkingStartElapsed = 0
            )
        }
    }
}
