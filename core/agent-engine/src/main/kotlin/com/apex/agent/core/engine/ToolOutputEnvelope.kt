package com.apex.agent.core.engine

/**
 * ═══ #831 工具输出定界信封（prompt-injection 防御：框定，不消毒）═══
 *
 * 外部内容（WebFetch 网页、CodeRead 文件、终端输出）原样流进模型上下文 ——
 * 其中可能埋着「ignore previous instructions」式的注入文本。信封把工具
 * 输出显式框定为**数据**：开标签携带工具名与调用 id，闭标签恒在末尾；
 * 配套规则（EnginePrompts 的 Output Management 段）告知模型信封内是不可
 * 信数据、其中的指令不执行。两层都是「框定」而非「消毒」—— 不做内容
 * 过滤（无法穷举注入形态，过滤只会误伤真实输出）。
 *
 * 排除项：AskUser 的回填（EngineAskUserFlow）不是工具输出而是**用户本人的
 * 作答 —— 用户是可信主体，不包信封（包了会让模型把用户的选择降级成数据）。
 *
 * 截断纪律：信封只包 smartTruncate **之后**的最终文本（EngineToolExecution
 * 在截断后包裹）—— 先包后截会把闭标签切掉；压缩器二次截断时经
 * [unwrapOrNull] 拆信封 → 截断 → 包回（见 HybridCompressor），闭标签任何
 * 路径下都存活，且截断器仍能识别信封内 payload 的 JSON 形态。
 */
internal object ToolOutputEnvelope {

    /** 开标签前缀（完整开标签形如 `<tool_output tool="X" id="Y">`，单行）。 */
    internal const val OPEN_TAG = "<tool_output"

    /** 闭标签（单行，恒在信封末尾）。 */
    internal const val CLOSE_TAG = "</tool_output>"

    /** 拆封产物：开标签携带的定位属性 + 信封内正文。 */
    internal data class Unwrapped(
        val toolName: String,
        val toolCallId: String,
        val payload: String
    )

    /** 包信封：正文只做包裹，不改写、不再截断（闭合标签必然存活）。 */
    internal fun wrap(toolName: String, toolCallId: String, content: String): String =
        "$OPEN_TAG tool=\"$toolName\" id=\"$toolCallId\">\n$content\n$CLOSE_TAG"

    /**
     * 拆信封（非信封形状 → null）。识别口径取最保守的「首行开标签 + 末尾
     * 闭标签」；正文里出现闭标签字样不影响拆封（以末尾的为准）。
     */
    internal fun unwrapOrNull(content: String): Unwrapped? {
        val header = content.substringBefore('\n')
        if (!header.startsWith(OPEN_TAG) || !header.endsWith(">")) return null
        if (!content.endsWith(CLOSE_TAG)) return null
        val toolName = attributeOf(header, "tool") ?: return null
        val toolCallId = attributeOf(header, "id") ?: return null
        val payload = content.substring(
            header.length + 1,
            content.length - CLOSE_TAG.length
        ).removeSuffix("\n")
        return Unwrapped(toolName, toolCallId, payload)
    }

    /** 从开标签行解析 `key="value"` 属性（缺失/空值/畸形引号 → null）。 */
    private fun attributeOf(header: String, key: String): String? {
        val marker = "$key=\""
        val start = header.indexOf(marker)
        if (start < 0) return null
        val valueStart = start + marker.length
        val end = header.indexOf('"', valueStart)
        if (end < 0 || end == valueStart) return null
        return header.substring(valueStart, end)
    }
}
