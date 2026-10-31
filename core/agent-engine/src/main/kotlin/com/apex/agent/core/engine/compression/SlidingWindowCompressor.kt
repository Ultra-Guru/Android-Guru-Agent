package com.apex.agent.core.engine.compression

import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.tools.util.TextRelevance

/**
 * 滑动窗口压缩器
 *
 * 不消耗额外LLM调用，纯规则驱动。
 * 移除最早的消息，生成简单文本摘要。
 *
 * 策略：
 * 1. 保留 system prompt（index 0）
 * 2. 保留最近 preserveRecent 条消息
 * 3. 中间的消息被压缩为一条摘要
 * 4. 摘要包含：用户意图、使用的工具、关键结果
 *
 * ═══ 上下文检索增强（相关性感知保留）═══
 *
 * 旧摘要取「前 3 条用户消息 + 前 3 条工具结果」—— 与当前任务无关的
 * 早期闲聊占据了摘要，而真正关键的任务细节（报错原文 / 关键参数 /
 * 用户约束）却可能被丢弃。现在以**当前任务锚点**（保留窗内最后一条
 * User 消息）对被压缩段做 BM25 相关性排序：
 * - 摘要中的用户请求 / 关键结果按相关性选录（相关性同名次时才按时间序）；
 * - 额外注入 `[RELEVANT RECALL]` 块 —— 被压缩段中与任务最相关的
 *   [RECALL_EXCERPTS] 条消息逐字保留摘录（每条 [RECALL_EXCERPT_CHARS]
 *   字），压缩后模型仍能“检索”到任务关键细节，而不是凭摘要复述。
 */
class SlidingWindowCompressor : ContextCompressor {

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

        if (history.size <= preserveRecent + 2) {
            return CompressionReport(
                beforeTokens = beforeTokens,
                afterTokens = beforeTokens,
                strategy = CompressionStrategy.NONE,
                summary = "No compression needed",
                messagesRemoved = 0,
                messagesTruncated = 0
            )
        }

        // 分割：[system] + [要压缩的] + [保留的]
        // 找到system prompt的位置（通常是index 0）
        val systemEnd = if (history.isNotEmpty() && history[0] is LlmMessage.System) 1 else 0

        var preserveStart = maxOf(systemEnd, history.size - preserveRecent)

        // ═══ 混沌工程加固（CR #D2）：保留窗首条不得是孤儿 ToolResult ═══
        //
        // ReAct 历史中 Assistant(tool_calls) 与 ToolResult 成对出现；OpenAI 兼容端点
        // 要求 role:"tool" 消息必须紧跟在携带对应 tool_calls 的 assistant 之后。
        // 旧实现按条数硬切，preserveStart 恰好落在两者之间时：assistant 被压进摘要、
        // ToolResult 留在保留窗 → 下次请求 400；且 ApexAgentEngine.compressNow() 会把
        // 损坏历史经 memory.save() 持久化，之后每轮请求都 400，只能清空对话。
        // 修复：边界向前回扩，把与保留窗内 ToolResult 配对的 assistant 一并保留。
        while (preserveStart > systemEnd && history[preserveStart] is LlmMessage.ToolResult) {
            preserveStart--
        }

        if (preserveStart <= systemEnd) {
            return CompressionReport(
                beforeTokens = beforeTokens,
                afterTokens = beforeTokens,
                strategy = CompressionStrategy.NONE,
                summary = "Nothing to compress",
                messagesRemoved = 0,
                messagesTruncated = 0
            )
        }

        // 先把要保留的段落拷贝出来，避免 clear() 之后丢失引用
        val systemMsgs = history.subList(0, systemEnd).toList()
        val toCompress = history.subList(systemEnd, preserveStart).toList()
        val preserved = history.subList(preserveStart, history.size).toList()

        // 生成摘要（任务锚点 = 保留窗内最后一条 User 消息，相关性排序选录）
        val taskAnchor = history.filterIsInstance<LlmMessage.User>().lastOrNull()?.content.orEmpty()
        val summary = generateSimpleSummary(toCompress, taskAnchor)

        // 重建 history
        history.clear()
        history.addAll(systemMsgs)
        history.add(LlmMessage.System("[CONTEXT COMPRESSED]\n$summary\n[END COMPRESSED CONTEXT]"))
        history.addAll(preserved)

        val afterTokens = TokenEstimator.estimateHistory(history)

        return CompressionReport(
            beforeTokens = beforeTokens,
            afterTokens = afterTokens,
            strategy = CompressionStrategy.SLIDING_WINDOW,
            summary = summary,
            messagesRemoved = toCompress.size,
            messagesTruncated = 0
        )
    }

    /**
     * 生成简单摘要（不需要LLM）
     *
     * 相关性感知版：[taskAnchor] 是当前任务锚点（最近的用户指令原文），
     * 用户请求与关键结果按 BM25 相关性选录（TextRelevance），最相关的
     * [RECALL_EXCERPTS] 条消息逐字进 `[RELEVANT RECALL]` 块。
     * 锚点为空（如纯工具回放场景）时退回旧时间序选录 —— 行为兼容。
     */
    private fun generateSimpleSummary(messages: List<LlmMessage>, taskAnchor: String): String {
        val userMessages = messages.filterIsInstance<LlmMessage.User>()
        val assistantMessages = messages.filterIsInstance<LlmMessage.Assistant>()
        val toolResults = messages.filterIsInstance<LlmMessage.ToolResult>()

        // 相关性选录（锚点非空时）：按与任务的相关性排序，同分按出现顺序
        val rankedUsers = rankByRelevance(taskAnchor, userMessages) { it.content }
        val rankedResults = rankByRelevance(taskAnchor, toolResults) { it.content }

        // 提取工具调用信息
        val toolCalls = assistantMessages.flatMap { it.toolCalls }
        val toolNames = toolCalls.map { it.name }.distinct()

        return buildString {
            appendLine("Previous ${messages.size} messages compressed.")
            appendLine()

            if (rankedUsers.isNotEmpty()) {
                appendLine("User requests (relevance-ranked, top of list is most relevant to the current task):")
                rankedUsers.take(3).forEach { msg ->
                    appendLine("  - ${msg.content.take(150)}")
                }
                appendLine()
            }

            if (toolNames.isNotEmpty()) {
                appendLine("Tools used: ${toolNames.joinToString(", ")}")
                appendLine()
            }

            if (rankedResults.isNotEmpty()) {
                appendLine("Key results (relevance-ranked):")
                rankedResults.take(3).forEach { result ->
                    appendLine("  - ${result.content.take(100)}")
                }
            }

            // 最后的assistant回复（通常是阶段性总结）
            assistantMessages.lastOrNull()?.let { last ->
                if (last.content.isNotBlank()) {
                    appendLine()
                    appendLine("Last assistant response: ${last.content.take(200)}")
                }
            }

            // ═══ 相关性检索块：任务关键细节逐字保留 ═══
            // 被压缩段中与任务锚点最相关的消息（用户请求/助手结论/工具结果均可入块），
            // 逐字摘录 —— 压缩后模型仍能引用原文细节（报错原文、参数、约束）。
            if (taskAnchor.isNotBlank()) {
                val recallDocs = messages.map { it to relevanceText(it) }
                    .filter { it.second.isNotBlank() }
                val ranked = TextRelevance.rank(
                    taskAnchor, recallDocs.map { it.second }, limit = RECALL_EXCERPTS
                )
                if (ranked.isNotEmpty()) {
                    appendLine()
                    appendLine("[RELEVANT RECALL] verbatim excerpts most relevant to the current task:")
                    ranked.forEach { scored ->
                        val (msg, _) = recallDocs[scored.index]
                        appendLine("  ${msg::class.simpleName}: ${relevanceText(msg).take(RECALL_EXCERPT_CHARS)}")
                    }
                    appendLine("[END RELEVANT RECALL]")
                }
            }
        }
    }

    /** 消息参与相关性排序的文本（工具调用参数也计入 —— 参数里的路径/名称常是检索目标）。 */
    private fun relevanceText(msg: LlmMessage): String = when (msg) {
        is LlmMessage.System -> ""
        is LlmMessage.User -> msg.content
        is LlmMessage.Assistant ->
            msg.content + msg.toolCalls.joinToString(" ") { it.name + " " + it.arguments }
        is LlmMessage.ToolResult -> msg.content
    }

    /** 相关性排序：锚点非空时按分数降序（rank 已按 index 升序稳定排序），否则保持原序。 */
    private fun <T> rankByRelevance(
        anchor: String,
        items: List<T>,
        textOf: (T) -> String
    ): List<T> {
        if (anchor.isBlank() || items.size <= 1) return items
        val texts = items.map(textOf)
        // rank 只返回过阈值的条目（分数降序）—— 未过阈值的低相关信息
        // 不强行排序，按时间序缀在末尾（保留完整性，压缩率不受影响）。
        val ranked = TextRelevance.rank(anchor, texts, limit = items.size)
        if (ranked.isEmpty()) return items
        val rankedOrder = ranked.map { it.index }.toSet()
        val rest = items.indices.filter { it !in rankedOrder }
        return (ranked.map { items[it.index] } + rest.map { items[it] })
    }

    private companion object {
        /** `[RELEVANT RECALL]` 块内逐字保留的消息条数。 */
        const val RECALL_EXCERPTS = 3

        /** 单条摘录的最大字符数。 */
        const val RECALL_EXCERPT_CHARS = 300
    }
}
