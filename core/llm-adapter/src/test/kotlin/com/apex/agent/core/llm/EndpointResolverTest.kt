package com.apex.agent.core.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S-c — [EndpointResolver] 单元测试（完全自定义 Base URL 语义）。
 *
 * 覆盖 chat / models 两条归一化路径的全部分支：
 * - chat：根路径与 /v1 追加、完整 /chat/completions 原样直发、旧版
 *   /completions 原样直发、结尾斜杠与首尾空白收敛；
 * - models：误粘 /chat/completions 尾巴剥掉重拼、完整 /models 直发、
 *   空串折返；
 * - o-series 严格推理模型识别的词边界（正则自 StreamingOpenAiClient 上提，
 *   口径必须逐例保持）。
 */
class EndpointResolverTest {

    // ── chat 端点：追加分支（既有行为兼容）──────────────────────────

    @Test
    fun `chat root path appends chat completions`() {
        assertEquals(
            "https://api.example.com/chat/completions",
            EndpointResolver.chatCompletions("https://api.example.com")
        )
    }

    @Test
    fun `chat v1 path appends chat completions`() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            EndpointResolver.chatCompletions("https://api.openai.com/v1")
        )
    }

    @Test
    fun `chat trailing slash and whitespace are trimmed`() {
        assertEquals(
            "https://api.example.com/v1/chat/completions",
            EndpointResolver.chatCompletions("  https://api.example.com/v1/ ")
        )
    }

    // ── chat 端点：完全自定义分支（用户反馈核心诉求）────────────────

    @Test
    fun `chat full endpoint with chat completions is used as-is`() {
        val url = "https://relay.example.com/v1/chat/completions"
        assertEquals(url, EndpointResolver.chatCompletions(url))
    }

    @Test
    fun `chat full endpoint with legacy completions is used as-is`() {
        val url = "https://legacy.example.com/v1/completions"
        assertEquals(url, EndpointResolver.chatCompletions(url))
    }

    @Test
    fun `chat azure style deployment path still appends`() {
        // Azure 风格非标准结尾：不属于已知 chat 路径 → 照旧追加（行为可预期）
        assertEquals(
            "https://x.openai.azure.com/openai/deployments/gpt-4o/chat/completions",
            EndpointResolver.chatCompletions("https://x.openai.azure.com/openai/deployments/gpt-4o")
        )
    }

    // ── models 端点 ────────────────────────────────────────────────

    @Test
    fun `models appends to plain base`() {
        assertEquals(
            "https://api.example.com/v1/models",
            EndpointResolver.models("https://api.example.com/v1")
        )
    }

    @Test
    fun `models strips pasted chat completions tail`() {
        assertEquals(
            "https://api.example.com/v1/models",
            EndpointResolver.models("https://api.example.com/v1/chat/completions")
        )
    }

    @Test
    fun `models strips pasted legacy completions tail`() {
        assertEquals(
            "https://api.example.com/v1/models",
            EndpointResolver.models("https://api.example.com/v1/completions")
        )
    }

    @Test
    fun `models full endpoint used as-is`() {
        val url = "https://api.example.com/v1/models"
        assertEquals(url, EndpointResolver.models(url))
    }

    @Test
    fun `models blank base folds to empty`() {
        assertEquals("", EndpointResolver.models("   "))
    }

    // ── o-series 严格推理模型识别（口径迁移自客户端）────────────────

    @Test
    fun `strict reasoner matches o-series and gpt-5`() {
        listOf("o1", "o3", "o3-mini", "o3-mini-high", "o1-preview", "o4-mini", "gpt-5", "gpt-5-mini", "O3-MINI")
            .forEach { assertTrue("应命中: $it", EndpointResolver.isStrictOpenAiReasoner(it)) }
    }

    @Test
    fun `strict reasoner rejects lookalikes`() {
        listOf("neo3x", "hero1", "o1abc", "gpt-4o", "gpt-4.1", "deepseek-chat")
            .forEach { assertFalse("不应命中: $it", EndpointResolver.isStrictOpenAiReasoner(it)) }
    }
}
