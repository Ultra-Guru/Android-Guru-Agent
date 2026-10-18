package com.apex.agent.core.code.loom.memory

import com.apex.agent.core.code.loom.LoomDigestSummary
import com.apex.agent.core.code.loom.LoomEngineConfig
import com.apex.agent.core.code.loom.LoomPrompts
import com.apex.agent.core.code.loom.loop.LoomLogger
import com.apex.agent.core.code.loom.loop.LoomModelClient
import com.apex.agent.core.code.loom.loop.LoomTokenEstimator
import com.apex.agent.core.code.loom.markup.LoomMarkupGrammar
import com.apex.agent.core.llm.LlmMessage

/**
 * # Loom Memory Weaver — 布面压缩器（digest 摘要织入）
 *
 * 历史超预算时把**旧轮结晶成摘要轮**（loom-digest 块，恒居历史头）：
 *
 * ```
 * [旧轮…] [近期轮×keepRecentTurns]  →  [loom-digest 摘要轮] [近期轮×keepRecentTurns]
 * ```
 *
 * ## 策略阶梯
 *
 * 1. **LOOM_DIGEST**（首选）：把旧轮（含既有摘要轮——摘要可再结晶）交给
 *    SUMMARY 角色（contextTag = digestContextTag）压成一段摘要正文；
 * 2. **SLIDING_WINDOW**（兜底）：digest 请求失败（网络 / 角色缺失 / 空回）
 *    时保留近期轮 + 既有摘要轮，丢弃其余旧轮——绝不丢历史头部的摘要结晶。
 *
 * ## 压缩纪律
 *
 * - 阈值线 [LoomEngineConfig.compactAtTokens]（占窗口比 × 窗口）；
 * - 近期轮数 [LoomEngineConfig.keepRecentTurns]——不足时无处折叠，放弃压缩
 *   （返回 null，交由请求级溢出处理）；
 * - digest 源文本限幅（每轮 600 字符 / 既有摘要 4000 字符）——防摘要请求
 *   本身撞窗口；
 * - 纯函数式：历史进、新历史出——落账由调用方（回合机）执行。
 */
class LoomMemoryWeaver(
    private val config: LoomEngineConfig,
    private val modelClient: LoomModelClient,
    private val estimator: LoomTokenEstimator,
    private val logger: LoomLogger
) {

    /** 压缩结果（事件数据）。 */
    data class CompactionResult(
        val beforeTokens: Int,
        val afterTokens: Int,
        val strategy: String,
        val summary: String,
        val turnsRemoved: Int,
        val turnsKept: Int
    )

    /** 压缩产物（新历史 + 事件数据）。 */
    data class CompactionOutcome(
        val newHistory: List<LlmMessage>,
        val result: CompactionResult
    )

    /**
     * 压缩入口：超阈值且可折叠 → 产出新历史；否则 null。
     */
    suspend fun maybeCompact(history: List<LlmMessage>): CompactionOutcome? {
        val beforeTokens = estimateHistory(history)
        if (beforeTokens < config.compactAtTokens()) return null
        if (history.size <= config.keepRecentTurns) return null
        val tail = history.takeLast(config.keepRecentTurns)
        val head = history.dropLast(config.keepRecentTurns)
        // 尝试 LLM 摘要（旧摘要一并再结晶）；失败走滑窗兜底
        val digestedText = runCatching { digestText(head) }.getOrNull()
        return if (digestedText != null) {
            val digestTurn = LlmMessage.User(
                content = LoomMarkupGrammar.digestBlock(
                    LoomDigestSummary(text = digestedText, roundsCovered = head.size)
                )
            )
            val newHistory = listOf(digestTurn) + tail
            outcome(newHistory, beforeTokens, "LOOM_DIGEST", digestedText, history)
        } else {
            // 滑窗兜底：既有摘要轮保留，其余旧轮丢弃
            val preservedDigest = head.firstOrNull { isDigestTurn(it) }
            val newHistory = listOfNotNull(preservedDigest) + tail
            outcome(newHistory, beforeTokens, "SLIDING_WINDOW", "", history)
        }
    }

    // ═══════════════════════════ 摘要路径 ═══════════════════════════

    /** 旧轮 → 摘要正文。失败抛异常（调用方兜底滑窗）。 */
    private suspend fun digestText(head: List<LlmMessage>): String {
        val source = renderForDigest(head)
        val response = modelClient.chat(
            contextTag = config.digestContextTag,
            messages = listOf(
                LlmMessage.System(LoomPrompts.digestInstruction()),
                LlmMessage.User(source)
            ),
            temperature = DIGEST_TEMPERATURE,
            maxTokens = DIGEST_MAX_TOKENS
        )
        val text = response.content?.trim()?.takeIf { it.isNotBlank() }
        if (text == null) {
            logger.warn(TAG, "digest response blank — falling back to sliding window")
            error("digest response blank")
        }
        return text
    }

    /** digest 源文本渲染（限幅：普通轮 600 字符 / 既有摘要 4000 字符）。 */
    internal fun renderForDigest(head: List<LlmMessage>): String = buildString {
        append("以下是一次编码任务的对话历史（按序），请压缩成摘要：\n\n")
        head.forEachIndexed { i, msg ->
            val label = when (msg) {
                is LlmMessage.User -> "用户"
                is LlmMessage.Assistant -> "助手"
                is LlmMessage.System -> "系统"
                is LlmMessage.ToolResult -> "工具"
            }
            val cap = if (isDigestTurn(msg)) DIGEST_SOURCE_CAP else TURN_SOURCE_CAP
            val content = plainContent(msg)
            append("第").append(i + 1).append("轮·").append(label).append("：")
                .append(content.take(cap))
            if (content.length > cap) append("…（后略）")
            append('\n')
        }
    }.trimEnd()

    // ═══════════════════════════ 工具 ═══════════════════════════

    private fun outcome(
        newHistory: List<LlmMessage>,
        beforeTokens: Int,
        strategy: String,
        summary: String,
        oldHistory: List<LlmMessage>
    ): CompactionOutcome = CompactionOutcome(
        newHistory = newHistory,
        result = CompactionResult(
            beforeTokens = beforeTokens,
            afterTokens = estimateHistory(newHistory),
            strategy = strategy,
            summary = summary.take(EVENT_SUMMARY_CAP),
            turnsRemoved = oldHistory.size - newHistory.size,
            turnsKept = newHistory.size
        )
    )

    /** 消息明文（各角色 content）。 */
    private fun plainContent(message: LlmMessage): String = when (message) {
        is LlmMessage.System -> message.content
        is LlmMessage.User -> message.content
        is LlmMessage.Assistant -> message.content
        is LlmMessage.ToolResult -> message.content
    }

    /** 是否摘要轮（用户轮 + loom-digest 块包裹）。 */
    internal fun isDigestTurn(message: LlmMessage): Boolean =
        message is LlmMessage.User &&
            message.content.startsWith("<" + LoomMarkupGrammar.TAG_DIGEST) &&
            message.content.contains("</" + LoomMarkupGrammar.TAG_DIGEST + ">")

    /** 历史估算 token（含逐消息开销与图片成本）。 */
    fun estimateHistory(history: List<LlmMessage>): Int {
        var total = 0
        for (m in history) {
            total += estimator.estimateTokens(plainContent(m)) + PER_TURN_OVERHEAD
            if (m is LlmMessage.User) total += IMAGES_TOKEN_COST * m.images.size
        }
        return total
    }

    companion object {
        private const val TAG = "LoomMemoryWeaver"
        private const val DIGEST_TEMPERATURE = 0.2f
        private const val DIGEST_MAX_TOKENS = 1_200
        private const val TURN_SOURCE_CAP = 600
        private const val DIGEST_SOURCE_CAP = 4_000
        private const val EVENT_SUMMARY_CAP = 400
        private const val PER_TURN_OVERHEAD = 4
        private const val IMAGES_TOKEN_COST = 800
    }
}
