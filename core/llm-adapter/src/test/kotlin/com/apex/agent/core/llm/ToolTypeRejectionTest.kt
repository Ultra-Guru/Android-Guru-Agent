package com.apex.agent.core.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * diag-202611（422 unknown variant 复盘）— [ToolTypeRejection] 文案匹配器的
 * 契约锁。覆盖三类真实端点报错形态 + 宽松兜底 + 反例（上下文超长 / 鉴权 /
 * parallel_tool_calls 参数错等绝不误命中 —— 误命中会导致非必要的降级重发）。
 *
 * 报错样例取自真实网关（Rust serde / Pydantic v2 / OpenAI 官方风格），
 * 断言值先按各校验栈的真实文案核实，不臆造。
 */
class ToolTypeRejectionTest {

    // ── 形态 1：Rust serde（用户 422 复盘原文）──────────────────────

    @Test
    fun `serde unknown variant with backticks matches and locates field`() {
        // 严格 OpenAI 兼容网关（Rust serde 反序列化栈）的 422 原文，JSON 包裹。
        val body = """{"error":{"message":"tools[0].type: unknown variant `WebSearch`, expected `function` at line 1 column 296","type":"invalid_request_error"}}"""
        val hit = ToolTypeRejection.parse(body)
        assertNotNull(hit)
        assertEquals(0, hit!!.index)
        assertEquals("WebSearch", hit.offendingType)
        assertEquals("function", hit.expectedType)
    }

    @Test
    fun `serde axum body-prefixed variant matches`() {
        // axum 校验器给字段路径加 body. 前缀 —— 子串锚天然覆盖。
        val body = """{"detail":"body.tools[1].type: unknown variant 'web_search_20250305', expected 'function'"}"""
        val hit = ToolTypeRejection.parse(body)
        assertNotNull(hit)
        assertEquals(1, hit!!.index)
        assertEquals("web_search_20250305", hit.offendingType)
        assertEquals("function", hit.expectedType)
    }

    // ── 形态 2：OpenAI / Pydantic v1 风格 ─────────────────────────

    @Test
    fun `invalid type expected function matches`() {
        val body = """{"error":{"message":"Invalid type for 'tools[0].type': expected \"function\", but got \"WebSearch\"","type":"invalid_request_error","code":400}}"""
        assertNotNull(ToolTypeRejection.parse(body))
    }

    // ── 形态 3：Pydantic v2 / FastAPI literal_error ────────────────

    @Test
    fun `fastapi literal error loc matches`() {
        // vLLM / FastAPI 校验栈：detail 数组 + loc 含 tools…type + 字面量错误。
        val body = """
            {"object":"error","message":"[{\"type\":\"literal_error\",\"loc\":[\"body\",\"tools\",0,\"type\"],\"msg\":\"Input should be 'function'\",\"input\":\"web_search\",\"ctx\":{\"expected\":\"'function'\"}}]","code":400}
        """.trimIndent()
        assertNotNull(ToolTypeRejection.parse(body))
    }

    // ── 宽松兜底 ──────────────────────────────────────────────────

    @Test
    fun `loose fallback requires tool plus strong hint`() {
        // 强指向片段 + tool —— 命中（网关把 serde 错误再包了一层非标准 JSON）。
        assertNotNull(
            ToolTypeRejection.parse(
                """{"msg":"rejected: tools list contains unsupported entry, expected `function` only"}"""
            )
        )
        // 只有 tool、无强指向 —— 不命中（普通工具文案，防误伤）。
        assertNull(
            ToolTypeRejection.parse("The tool use failed because the tool is not available")
        )
    }

    // ── 反例：绝不降级重发的错误族 ─────────────────────────────────

    @Test
    fun `context length exceeded does not match`() {
        val body = """
            {"error":{"message":"This model's maximum context length is 4096 tokens.
            However, you requested 5000 tokens (4000 in the messages, 1000 in the completion).
            Please reduce the length of the messages or completion.","type":"invalid_request_error"}}
        """.trimIndent()
        assertNull(ToolTypeRejection.parse(body))
    }

    @Test
    fun `authentication failure does not match`() {
        assertNull(
            ToolTypeRejection.parse(
                """{"error":{"message":"Incorrect API key provided: sk-xxx. You can find your API key at https://platform.openai.com/account/api-keys.","type":"invalid_request_error","code":401}}"""
            )
        )
    }

    @Test
    fun `parallel_tool_calls argument error does not match`() {
        // 含 "tool" 子串但无 tools[].type 锚 / 强指向 —— 必须不命中，
        // 否则会触发非必要的搜索 tool 剔除重发。
        assertNull(
            ToolTypeRejection.parse(
                """{"error":{"message":"Unrecognized request argument supplied: parallel_tool_calls","type":"invalid_request_error","code":400}}"""
            )
        )
    }

    @Test
    fun `blank and null bodies do not match`() {
        assertNull(ToolTypeRejection.parse(null))
        assertNull(ToolTypeRejection.parse(""))
        assertNull(ToolTypeRejection.parse("   "))
        assertFalse(ToolTypeRejection.isUnknownToolTypeRejection(null))
    }
}
