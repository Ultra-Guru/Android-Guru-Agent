package com.apex.agent.core.engine

import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import kotlinx.coroutines.delay

/** [finishEmptyTurn] 的终局裁决。 */
internal enum class EmptyTurnOutcome {
    /** reasoning-only 兜底已收尾（ResponseChunk + ResponseComplete 已发）。 */
    FINISHED,

    /** 空响应退避重试已排定（调用方回滚迭代计数后 continue）。 */
    RETRY,

    /** 重试预算耗尽，调用方按旧语义报 Error。 */
    FAILED
}

/**
 * 终局空轮处理（自 [ApexAgentEngine] 迁出，God-file 预算拆分；同包扩展 +
 * internal 成员直调 —— 与 EngineAskUserFlow 同一模式）。
 *
 * # 问题（2026-10 用户反馈「流水式输出只有思考过程，没有文字回答」的根因）
 * 部分思考型模型（弱蒸馏 R1 / 部分网关的思考档）把最终回答整体写进
 * reasoning_content，正文 content 一字不发；另有网关把正文挂在流式末帧的
 * choice.message 上（增量 delta 缺失，见 StreamingOpenAiClient 末帧兜底）。
 * 两种形态下引擎 contentBuilder 恒空 → 终局走「空响应」分支 → 盲目重试
 * 3 次（每次多出一张思考卡）→ 报「模型返回了无效响应」。reasoningBuilder
 * 里躺着完整答案却没有任何分支读它兜底 —— UI 表现为思考链胶囊照常显示、
 * 文字回答永远缺失。
 *
 * # 修复
 * 1. reasoning 非空且正文恒空 → 以 reasoning 文本兜底为最终回答：
 *    先补发 ResponseChunk 再 ResponseComplete（编码屏 CodeEventReducer 的
 *    ResponseComplete 只收尾不建消息 —— 没有 chunk 就没有气泡），warn 留痕。
 *    答案会同时出现在思考卡与回答气泡里各一次 —— 重复是可接受的代价，
 *    远好于丢答案 + 假报错。
 * 2. reasoning 也为空 → 维持既有空响应退避重试（弱网抖动不立即报错，
 *    预算用尽才 FAILED —— 行为与迁出前逐字节一致）。
 */
internal suspend fun ApexAgentEngine.finishEmptyTurn(
    reasoning: String,
    emit: suspend (AgentEvent) -> Unit
): EmptyTurnOutcome {
    if (reasoning.isNotEmpty()) {
        AppLogger.instance.warn(
            LogCategory.LLM, "ApexAgentEngine",
            "reasoning-only 响应：正文恒空，思考通道文本兜底为最终回答（${reasoning.length} chars）"
        )
        addMessage(LlmMessage.Assistant(reasoning))
        emit(AgentEvent.ResponseChunk(reasoning))
        emit(AgentEvent.ResponseComplete(reasoning))
        return EmptyTurnOutcome.FINISHED
    }
    val emptyRetry = resilience.onEmptyResponse() ?: return EmptyTurnOutcome.FAILED
    AppLogger.instance.warn(
        LogCategory.LLM, "ApexAgentEngine",
        "LLM 空响应（重试 ${emptyRetry.attempt}/${resilience.policy.maxEmptyResponseRetries}）"
    )
    emit(
        AgentEvent.ThinkingChunk(
            "[engine] 模型返回空响应 — ${emptyRetry.delayMs / 1000.0}s 后自动重试 " +
                "（${emptyRetry.attempt}/${resilience.policy.maxEmptyResponseRetries}）\n"
        )
    )
    delay(emptyRetry.delayMs)
    return EmptyTurnOutcome.RETRY
}
