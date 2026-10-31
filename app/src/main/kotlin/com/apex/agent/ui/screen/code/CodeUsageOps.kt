package com.apex.agent.ui.screen.code

import com.apex.agent.core.code.longtask.LongTaskStatus
import com.apex.agent.core.engine.AgentEvent
import kotlinx.coroutines.flow.update

// ─────────────────────────────────────────────────────────────────────────────
// 会话用量操作流 —— CodeViewModel 同包 internal 扩展（God-file 预算纪律，
// 模式同 CodeLongTaskCenterOps.kt：VM 只开放成员，逻辑住这里）。
//
// 职责（上下文圆环 + 详情弹层 + 用量账本 + 任务完成通知的数据面）：
//  - recordUsage：AgentEvent.UsageUpdated → 圆环实时推进（contextUsedTokens）
//    + 会话累计（输入/输出/缓存命中）+ UsageLedger 逐轮落账（用量仪表盘
//    从此有真数据，不再是空账本）；
//  - contextWindowTokens：圆环分母 —— 默认模型 Profile 的 contextWindow
//    （effectiveContextWindow 真接线，Issue #222 的「生效值=Profile 窗口」
//    从注释承诺变成事实），Profile 缺失/非法时回退引擎配置；
//  - notifyRunCompletion：任务完成通知（设置 → 通知 开关真接线）——
//    App 在后台时跑完发系统通知，前台静音（用户正盯着屏幕，通知是噪音），
//    ABORTED 不发（用户自己按的停）。
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 一轮 LLM 真实用量到达（服务端 usage 统计）：推进上下文圆环 + 累加会话
 * 统计 + 落账用量仪表盘。
 *
 * 每轮 LLM 请求至多一条事件（低频，直接更新状态流零压力）；totalTokens
 * = 本轮 prompt+completion ≈ 请求后的上下文规模，与引擎侧
 * EngineUsageTracker.currentContextTokens 同口径。
 */
internal fun CodeViewModel.recordUsage(event: AgentEvent.UsageUpdated) {
    _uiState.update { state ->
        state.copy(
            contextUsedTokens = event.totalTokens,
            sessionInputTokens = state.sessionInputTokens + event.promptTokens,
            sessionOutputTokens = state.sessionOutputTokens + event.completionTokens,
            sessionCachedTokens = state.sessionCachedTokens + event.cachedTokens
        )
    }
    // 用量账本（fire-and-forget，内部 IO scope 落盘）：会话 id 用工作区，
    // 模型 id 取当前默认 Profile —— 仪表盘的按模型/按日聚合据此成真。
    val modelId = settingsRepository.profiles.value
        .firstOrNull { it.isDefault }?.modelId.orEmpty()
    usageLedger.record(
        sessionId = boundWorkspaceId,
        modelId = modelId,
        promptTokens = event.promptTokens.toLong(),
        completionTokens = event.completionTokens.toLong()
    )
}

/**
 * 上下文窗口预算（圆环分母）：默认模型 Profile 的 contextWindow 优先，
 * 缺失/非法（≤0）回退引擎配置（防分母塌 0）。
 *
 * 引擎 AgentConfig 是启动快照，切换模型不重建引擎 —— 圆环侧按 Profile
 * 实时取值，模型切换后分母即刻正确（压缩门的引擎内分母下一进程生效）。
 */
internal fun CodeViewModel.contextWindowTokens(): Int {
    val engineMax = codeEngineImpl?.maxContextTokens() ?: 0
    val profile = settingsRepository.profiles.value
        .firstOrNull { it.isDefault }
        ?: settingsRepository.profiles.value.firstOrNull()
    return profile?.effectiveContextWindow(engineMax)?.takeIf { it > 0 } ?: engineMax
}

/**
 * 任务完成通知（设置 → 通知「任务完成通知」开关真接线）。
 *
 * 静音口径（与 ForegroundTracker 语义一致）：前台不弹（流式回复已经在
 * 屏幕上，通知是噪音）；后台才发「跑完了」通知，让用户切回来看结果。
 * ABORTED 不通知（用户主动中止，无「办完了」语义）。
 */
internal fun CodeViewModel.notifyRunCompletion(status: LongTaskStatus, goal: String?) {
    if (status == LongTaskStatus.ABORTED) return
    if (!settingsRepository.agentSettings.value.taskCompletionNotify) return
    if (foregroundTracker.isForeground) return
    // 预览取末条助手消息（无则通用完成文案由通知层兜底）
    val preview = _uiState.value.messages
        .lastOrNull { it.role == CodeChatMessage.Role.ASSISTANT }?.text.orEmpty()
    apexNotifications.notifyTaskDone(appContext, goal ?: "", preview)
}
