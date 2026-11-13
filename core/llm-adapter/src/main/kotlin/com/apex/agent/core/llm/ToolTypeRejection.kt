package com.apex.agent.core.llm

/**
 * ═══ 「未知 tools[].type」类请求级拒绝的文案匹配器（纯函数，2026-11）═══
 *
 * 背景：严格 OpenAI 兼容端点（Rust serde / Pydantic / FastAPI 校验栈）只接受
 * `tools[].type == "function"`，其余变体（"WebSearch" / "web_search_20250305"
 * 等 server-side 搜索 tool 形态）在反序列化层直接拒绝**整个请求**：
 *
 * - Rust serde（422）：`tools[0].type: unknown variant \`WebSearch\`, expected \`function\``；
 * - Pydantic v2 / FastAPI（400/422）：`Input should be 'function'`（loc 含
 *   `["body","tools",N,"type"]`）；
 * - OpenAI 官方风格：`Invalid type for 'tools[0].type': expected "function", but got "WebSearch"`。
 *
 * 这类错误的语义 = 「请求体里 tools 数组存在非法 type 变体」，可定位、可自动
 * 修复（剔除非法条目重发即可）。本匹配器供 [StreamingOpenAiClient] 的降级
 * 重发判定使用；命中 → 剔除 server-side 搜索 tool 后重试一次（见其
 * `executeWithServerToolDegrade`），不命中 → 原样上抛（诚实暴露真错）。
 *
 * 判定为纯文本启发式：宽松兜底要求同时命中「tool」与强指向片段
 * （unknown variant / expected `function` / Input should be 'function'），
 * 上下文超长、鉴权失败、parallel_tool_calls 参数错等**绝不**误命中。
 */
object ToolTypeRejection {

    /**
     * 定位结果：`tools[index].type` 的非法值与端点期望值。
     * [offendingType]/[expectedType] 为空 = 只命中宽松兜底（无法精确定位，
     * 仅足以判定「属于该错误族」）。index 为 -1 同义。
     */
    data class RejectedToolType(
        val index: Int,
        val offendingType: String,
        val expectedType: String
    )

    // ── 形态 1：Rust serde —— "tools[0].type: unknown variant `WebSearch`, expected `function`"
    // 引定界符宽容（反引号 / 单双引号 / 裸值）；“unknown variant” 与 “expected”
    // 之间的逗号可选（部分网关省略）。axum 前缀（body.tools[0].type）由子串
    // 锚 `tools\[(\d+)]\.type` 天然覆盖。
    private val SERDE_UNKNOWN_VARIANT = Regex(
        """tools\[(\d+)]\.type[^\n]{0,60}?unknown variant\s+[`'"]?([\w.-]+)[`'"]?\s*,?\s*expected\s+[`'"]?(\w+)"""
    )

    // ── 形态 2：OpenAI / Pydantic v1 —— "Invalid type for 'tools[0].type':
    // expected \"function\", but got \"WebSearch\""（含 "must be" 变体）。
    // 起始定界符允许 JSON 转义形态（\\?['"`]）：错误体再包一层 JSON 时，
    // 引号以 \" 出现 —— 旧版裸 ['"`] 对 `function\"` 的收尾匹配失败
    //（scratch 复盘 2026-11）；收尾定界符不强制（正文后续字符不受控）。
    private val INVALID_TYPE_EXPECTED_FUNCTION = Regex(
        """tools\[(\d+)]\.type[^\n]{0,120}?(?:expected|must be)[^\n]{0,60}?(?:\\?['"`])function""",
        RegexOption.IGNORE_CASE
    )

    // ── 形态 3：Pydantic v2 / FastAPI —— detail 数组里 loc 含 "tools"...“type”
    // 且 msg 为字面量校验失败（"Input should be 'function'"）。
    private val FASTAPI_LITERAL_ERROR = Regex(
        """"loc"\s*:\s*\[[^\]]*"tools"[^\]]*"type"[^\]]*\][^{}]{0,80}?"msg"\s*:\s*"[^"]*function"""
    )

    /** 宽松兜底的强指向片段（与 “tool” 共同命中才算；防上下文超长等误判）。 */
    private val STRONG_HINTS = listOf(
        "unknown variant",
        "expected `function`",
        "expected 'function'",
        "expected \"function\"",
        "input should be 'function'",
        "must be one of: 'function'"
    )

    /**
     * 解析错误文案是否属于「tools[].type 非法变体」族。
     * @return 定位结果；null = 不属于（调用方不得降级重试）。
     */
    fun parse(body: String?): RejectedToolType? {
        if (body.isNullOrBlank()) return null
        SERDE_UNKNOWN_VARIANT.find(body)?.let { m ->
            return RejectedToolType(
                index = m.groupValues[1].toIntOrNull() ?: -1,
                offendingType = m.groupValues[2],
                expectedType = m.groupValues[3].ifBlank { "function" }
            )
        }
        INVALID_TYPE_EXPECTED_FUNCTION.find(body)?.let {
            return RejectedToolType(index = -1, offendingType = "", expectedType = "function")
        }
        FASTAPI_LITERAL_ERROR.find(body)?.let {
            return RejectedToolType(index = -1, offendingType = "", expectedType = "function")
        }
        // 宽松兜底：三类主形态未命中，但文案同时含「tool」与任一强指向片段
        //（例：网关把 serde 错误再包了一层非标准 JSON）。“tool” 单独出现
        // 不算（parallel_tool_calls / "tool use" 等普通文案会误伤）。
        val lower = body.lowercase()
        val hitStrongHint = STRONG_HINTS.any { lower.contains(it) }
        if (hitStrongHint && lower.contains("tool")) {
            return RejectedToolType(index = -1, offendingType = "", expectedType = "function")
        }
        return null
    }

    /** 便捷入口：是否属于「未知 tools[].type」请求级拒绝。 */
    fun isUnknownToolTypeRejection(body: String?): Boolean = parse(body) != null
}
