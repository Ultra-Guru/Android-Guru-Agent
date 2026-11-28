package com.apex.agent.core.engine.compression

import com.apex.agent.core.engine.ToolOutputEnvelope
import com.apex.agent.core.llm.LlmClient
import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.runtime.ModelRuntime

/**
 * 混合压缩器（推荐）
 *
 * 组合所有策略，按需逐级执行：
 * 1. 先截断工具输出（零成本）
 * 2. 如果仍超限，执行滑动窗口（零成本）
 * 3. 如果仍超限，执行LLM摘要（消耗一次调用）
 *
 * 设计原则：能用便宜方案解决的，不用贵方案。
 *
 * T72 §十：把 [modelRuntime] 透传给内部的 [LlmSummaryCompressor]，使第 3 层
 * 摘要走 SUMMARY 角色（路由 + 降级）。为空时 [LlmSummaryCompressor] 自行回退到
 * [com.apex.agent.core.llm.runtime.SingleClientModelRuntime]，保留旧行为。
 */
class HybridCompressor(
    private val llmClient: LlmClient,
    private val toolTruncator: ToolOutputTruncator = ToolOutputTruncator(),
    maxContextTokens: Int = 128000,
    private val threshold: Float = 0.8f,
    modelRuntime: ModelRuntime? = null
) : ContextCompressor {

    private val slidingWindow = SlidingWindowCompressor()
    private val llmSummarizer = LlmSummaryCompressor(llmClient, modelRuntime)

    /**
     * Issue #222：分层收敛的停止阈值，必须与引擎压缩门同源（可热更新）。
     * 构造参数 → 可变字段：模型切换时经 [updateContextWindow] 同步，
     * @Volatile 保证 IO 线程压缩路径对新值可见；≤0 非法值拒收
     * （防外部形状异常把阈值打穿）。
     */
    @Volatile
    private var contextWindowTokens: Int = maxContextTokens

    override fun updateContextWindow(tokens: Int) {
        if (tokens > 0) contextWindowTokens = tokens
    }

    override fun needsCompression(
        history: List<LlmMessage>,
        maxTokens: Int,
        threshold: Float
    ): Boolean {
        val currentTokens = TokenEstimator.estimateHistory(history)
        return currentTokens > (maxTokens * threshold).toInt()
    }

    override suspend fun compress(
        history: MutableList<LlmMessage>,
        preserveRecent: Int
    ): CompressionReport {
        val beforeTokens = TokenEstimator.estimateHistory(history)
        val thresholdTokens = (contextWindowTokens * threshold).toInt()
        var totalTruncated = 0
        var totalRemoved = 0

        // ═══ Layer 1: 截断工具输出 ═══
        totalTruncated = truncateToolOutputs(history)

        var currentTokens = TokenEstimator.estimateHistory(history)
        if (currentTokens <= thresholdTokens) {
            return CompressionReport(
                beforeTokens = beforeTokens,
                afterTokens = currentTokens,
                strategy = CompressionStrategy.TOOL_TRUNCATION,
                summary = "Truncated $totalTruncated tool outputs",
                messagesRemoved = 0,
                messagesTruncated = totalTruncated
            )
        }

        // ═══ Layer 2: 滑动窗口 ═══
        if (history.size > preserveRecent + 6) {
            val windowResult = slidingWindow.compress(history, preserveRecent)
            totalRemoved = windowResult.messagesRemoved

            currentTokens = TokenEstimator.estimateHistory(history)
            if (currentTokens <= thresholdTokens) {
                return CompressionReport(
                    beforeTokens = beforeTokens,
                    afterTokens = currentTokens,
                    strategy = CompressionStrategy.SLIDING_WINDOW,
                    summary = windowResult.summary,
                    messagesRemoved = totalRemoved,
                    messagesTruncated = totalTruncated
                )
            }
        }

        // ═══ Layer 3: LLM摘要 ═══
        val summaryResult = llmSummarizer.compress(history, preserveRecent)

        return CompressionReport(
            beforeTokens = beforeTokens,
            afterTokens = TokenEstimator.estimateHistory(history),
            strategy = CompressionStrategy.HYBRID,
            summary = summaryResult.summary,
            messagesRemoved = totalRemoved + summaryResult.messagesRemoved,
            messagesTruncated = totalTruncated
        )
    }

    /**
     * 截断所有过长的工具输出
     * @return 截断的数量
     */
    private fun truncateToolOutputs(history: MutableList<LlmMessage>): Int {
        var truncatedCount = 0

        for (i in history.indices) {
            val msg = history[i]
            if (msg is LlmMessage.ToolResult && msg.content.length > toolTruncator.maxChars) {
                // #831：历史中的工具输出带定界信封（EngineToolExecution 写入时
                // 包裹）—— 二次截断必须先拆信封再截：直接截会把闭合标签切掉
                // （模型看到永不闭合的 <tool_output>），且信封前缀会废掉
                // payload 的 JSON 形态检测（exit_code 字段级截断失效）。
                // 截完包回，开标签的真实工具名还顺带给截断器做工具定制策略。
                val envelope = ToolOutputEnvelope.unwrapOrNull(msg.content)
                val payload = envelope?.payload ?: msg.content
                val result = toolTruncator.smartTruncate(payload, envelope?.toolName ?: "")
                if (result.truncated) {
                    val content = envelope?.let {
                        ToolOutputEnvelope.wrap(it.toolName, it.toolCallId, result.text)
                    } ?: result.text
                    history[i] = LlmMessage.ToolResult(
                        toolCallId = msg.toolCallId,
                        content = content
                    )
                    truncatedCount++
                }
            }
        }

        return truncatedCount
    }
}
