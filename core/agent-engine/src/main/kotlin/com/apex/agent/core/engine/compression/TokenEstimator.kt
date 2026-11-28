package com.apex.agent.core.engine.compression

import com.apex.agent.core.llm.LlmMessage

/**
 * Token估算器
 *
 * 精确token计数需要tokenizer（如tiktoken），但这会引入重依赖。
 * 使用启发式估算，精度足够用于触发压缩决策。
 *
 * 估算规则：
 * - 英文：1 token ≈ 4 chars
 * - 中文：1 token ≈ 1.5 chars（中文字符信息密度更高）
 * - 其他：1 token ≈ 3 chars
 */
object TokenEstimator {

    /**
     * #827 — 单图 token 计费估算（启发式常量）。
     *
     * 视觉 token 取决于分辨率与 provider 切片策略（GPT-4o 低清 ~85 / 高清
     * ~1100+，Claude ~1.1k-1.6k，Gemini ~258/图），取 1000/图是**保守量级**：
     * 用途是压缩门与上下文仪表盘的「图片不免费」可见性，而非精确计费。
     * 不用数据长度估算：base64 字节数与 token 无线性关系（同一图片不同
     * 压缩率字节差一个量级），按字节估反而系统性失真，故用固定常量。
     */
    const val IMAGE_TOKEN_ESTIMATE = 1_000

    /**
     * 估算单条消息的token数
     */
    fun estimateMessage(msg: LlmMessage): Int = when (msg) {
        is LlmMessage.System -> estimateText(msg.content)
        is LlmMessage.User ->
            // #827：图片按 [IMAGE_TOKEN_ESTIMATE]/图计费 —— 旧实现只计文本，
            // 携图消息在压缩门与仪表盘口径下「零成本」，门永远看不到图片驻留。
            estimateText(msg.content) + msg.images.size * IMAGE_TOKEN_ESTIMATE
        is LlmMessage.Assistant ->
            estimateText(msg.content + msg.toolCalls.sumOf { it.name.length + it.arguments.length })
        is LlmMessage.ToolResult -> estimateText(msg.content)
    }

    /**
     * 估算整个对话历史的token数
     */
    fun estimateHistory(history: List<LlmMessage>): Int {
        // 每条消息有角色标记等开销，约4 tokens
        val overhead = history.size * 4
        return history.sumOf { estimateMessage(it) } + overhead
    }

    /**
     * 估算文本token数
     */
    fun estimateText(text: String): Int {
        if (text.isEmpty()) return 0

        var cjkChars = 0
        var asciiChars = 0
        var otherChars = 0

        for (char in text) {
            when {
                char.code in 0x4E00..0x9FFF ||   // CJK统一汉字
                char.code in 0x3400..0x4DBF ||   // CJK扩展A
                char.code in 0x3000..0x303F ||   // CJK标点
                char.code in 0xFF00..0xFFEF      // 全角字符
                -> cjkChars++

                char.code < 128 -> asciiChars++

                else -> otherChars++
            }
        }

        // 中文：约1.5 chars/token
        // 英文：约4 chars/token
        // 其他：约3 chars/token
        val cjkTokens = cjkChars / 1.5
        val asciiTokens = asciiChars / 4.0
        val otherTokens = otherChars / 3.0

        return (cjkTokens + asciiTokens + otherTokens).toInt().coerceAtLeast(1)
    }

    /**
     * 快速检查：文本是否超过指定token数
     * 比精确计算快，用于早期判断
     */
    fun exceedsTokens(text: String, maxTokens: Int): Boolean {
        // 最坏情况：全是中文，1 char = 1 token
        if (text.length <= maxTokens) return false
        // 最好情况：全是英文，4 chars = 1 token
        if (text.length > maxTokens * 4) return true
        // 需要精确计算
        return estimateText(text) > maxTokens
    }
}
