package com.apex.agent.core.engine.plan

import com.apex.agent.core.engine.ToolOutputEnvelope
import com.apex.agent.core.llm.LlmMessage

/**
 * #828 PLAN 模式执行记录摘要 —— Phase 4 历史的有界 digest。
 *
 * 旧 Phase 5 反思请求只带 System + User(buildReflectionPrompt)，会话历史
 * （含全部步骤真实执行的工具结果）不随请求下发 —— 模型被要求「State the
 * final outcome and key deliverables」却拿不到任何产出，只能凭空编造。
 *
 * 本对象把 Phase 4 每步的历史切片蒸煮成**有界**摘要（不搬全量历史 ——
 * 全量搬运正是压缩门要解决的 token 问题）：
 *  - [stepRecords]：单步切片 → 逐条记录行。ToolResult 一行
 *    `step <n>: <toolCall name> → <正文前 200 字>`（信封内容拆封取正文）；
 *    有正文的 Assistant 同样前 200 字。System/User（步骤提示词等样板）跳过；
 *  - [boundedDigest]：全部记录行 → 总量 8KB 封顶的 digest。超预算保**靠后**
 *    的记录（终局反思最关心最后几步的真实产出），被丢弃的前置记录以
 *    计数告示替代。
 *
 * 纯函数、无状态 —— 与 PlanGraph 同一拆分模式，可独立单测。
 */
internal object PlanExecutionDigest {

    /** 单条记录正文摘录上限（字符）。 */
    internal const val RECORD_SNIPPET_CHARS = 200

    /** 整份 digest 的总量上限（字符）—— 反思请求的 token 安全阀。 */
    internal const val DIGEST_CHAR_CAP = 8 * 1024

    /** 超预算丢弃前置记录时的计数告示（计入总量预算内）。 */
    private const val OVERCAP_NOTICE =
        "[... earlier execution records omitted to fit the digest budget ...]"

    /**
     * 单步历史切片 → 逐条记录行（空切片 → 空表）。
     *
     * 工具名解析：ToolResult 只带 toolCallId，名称取自其配对的
     * Assistant(tool_calls) 消息（配对不变量保证 Assistant 恒在前）；
     * 解析不到（历史回放/畸形输入）退化为 `unknown-tool`，绝不因缺名丢记录。
     */
    fun stepRecords(stepNumber: Int, history: List<LlmMessage>): List<String> {
        val lines = mutableListOf<String>()
        val toolNameById = mutableMapOf<String, String>()
        history.forEach { msg ->
            when (msg) {
                is LlmMessage.Assistant -> {
                    msg.toolCalls.forEach { tc ->
                        if (tc.id.isNotBlank() && tc.name.isNotBlank()) {
                            toolNameById[tc.id] = tc.name
                        }
                    }
                    if (msg.content.isNotBlank()) {
                        lines += recordLine(stepNumber, "assistant", msg.content)
                    }
                }
                is LlmMessage.ToolResult -> {
                    // 历史中的工具结果已带 #831 定界信封 —— 摘正文而非信封，
                    // digest 行自带工具名，信封在此只是重复开销。
                    val payload = ToolOutputEnvelope.unwrapOrNull(msg.content)?.payload
                        ?: msg.content
                    val name = toolNameById[msg.toolCallId] ?: "unknown-tool"
                    lines += recordLine(stepNumber, name, payload)
                }
                else -> { /* System / User（步骤提示词等样板）不进 digest */ }
            }
        }
        return lines
    }

    /** 全部记录行 → 总量封顶 digest（空表 → 空串）。 */
    fun boundedDigest(lines: List<String>): String {
        if (lines.isEmpty()) return ""
        val joined = lines.joinToString("\n")
        if (joined.length <= DIGEST_CHAR_CAP) return joined
        // 超预算：从尾部回收保留（最新记录优先），预算含头部的丢弃告示。
        val kept = mutableListOf<String>()
        var used = OVERCAP_NOTICE.length
        for (line in lines.asReversed()) {
            val cost = line.length + if (kept.isEmpty()) 0 else 1
            if (used + cost > DIGEST_CHAR_CAP) break
            kept.add(0, line)
            used += cost
        }
        if (kept.isEmpty()) return OVERCAP_NOTICE
        return "$OVERCAP_NOTICE\n" + kept.joinToString("\n")
    }

    /** 单条记录行：`step <n>: <来源> → <摘录>`（换行折叠为空格，保单行）。 */
    private fun recordLine(stepNumber: Int, source: String, text: String): String =
        "step $stepNumber: $source → ${snippet(text)}"

    /** 正文摘录：去首尾空白、换行折叠为空格、截到 [RECORD_SNIPPET_CHARS]。 */
    private fun snippet(text: String): String =
        text.trim().replace('\n', ' ').take(RECORD_SNIPPET_CHARS)
}
