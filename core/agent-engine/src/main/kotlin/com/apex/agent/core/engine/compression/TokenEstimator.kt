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
 * - 图片（#827）：服务端 vision 计费与图片内容相关，本地无法精确；
 *   计保守固定成本（每张 [IMAGE_FIXED_TOKENS]）+ base64 体量分量，
 *   使压缩门与用量仪表盘对图片占位不再失明（旧实现计 0 → 永不触发压缩
 *   → 服务端 400 兑底）。远端 URL 图片按 URL 文本估算 + 固定成本。
 */
object TokenEstimator {

    /**
     * 单张图片保守 token 成本（对齐主流 vision 服务的下限量级：
     * 低分辨率档约数百 token；取 1000 使压缩门偏早触发而非偏晚）。
     */
    private const val IMAGE_FIXED_TOKENS = 1000

    /**
     * 估算单条消息的token数
     */
    fun estimateMessage(msg: LlmMessage): Int {
        val text = when (msg) {
            is LlmMessage.System -> msg.content
            is LlmMessage.User -> msg.content
            is LlmMessage.Assistant -> {
                msg.content + msg.toolCalls.sumOf { it.name.length + it.arguments.length }
            }
            is LlmMessage.ToolResult -> msg.content
        }
        val imageTokens = if (msg is LlmMessage.User) estimateImages(msg) else 0
        return estimateText(text) + imageTokens
    }

    /**
     * #827：估算 User 消息携带图片的 token 成本（旧实现恒 0）。
     *
     * - base64 图：固定成本 + 体量分量（约 4KB base64 → 1 token 的保守折算，
     *   只用于压缩门与仪表盘「看到占位」，不冒充计费精确值）；
     * - 远端 URL 图：固定成本 + URL 文本估算。
     */
    fun estimateImages(msg: LlmMessage.User): Int = msg.images.sumOf { image ->
        val urlTokens = image.url?.let { estimateText(it) } ?: 0
        val payloadTokens = if (image.url != null) 0 else image.base64Data.length / 4096
        IMAGE_FIXED_TOKENS + urlTokens + payloadTokens
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
