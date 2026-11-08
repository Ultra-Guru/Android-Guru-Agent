package com.apex.agent.core.llm

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * diag-202611（422 unknown variant 复盘）— server-side 搜索 tool「降级重发」
 * 契约锁（真实 HTTP 层，MockWebServer 与 :core:tool-registry 同款）。
 *
 * 锁定的核心行为（对应 422 复盘的修复诉求）：
 * 1. 端点以「未知 tools[].type」422 拒绝（Rust serde 文案）→ 剔除搜索
 *    server tool 后**自动重发一次**，任务继续（不再一遇 422 即终止）；
 * 2. 重发后的请求体不含任何非 function 形态的 tools 条目；
 * 3. 降级标记实例级持久 —— 同客户端后续请求首发即无搜索条目；
 * 4. 非该形态的 4xx（上下文超长等）不重试、原样上抛（诚实暴露真错）。
 *
 * 场景构造：providerId=glm（命中 pid 门控分支，与 AUTO 推断同口径）+
 * 中转 baseUrl —— 现实中的「智谱 Profile 换了中转端点」形态；被测端点
 * （MockWebServer）扮演严格 OpenAI 兼容网关。
 *
 * 用 runBlocking 而非 runTest：非流式路径经 [SuspendHttpRetry] 的
 * withTimeoutOrNull（虚拟时钟定时器），真实 OkHttp IO 的恢复与虚拟时间
 * 推进存在竞态（runTest 下 withTimeoutOrNull 会提前到点把整块判为预算
 * 耗尽，scratch 复盘 2026-11）；真实 IO 无虚拟时间收益，runBlocking 才是
 * 该组合的正确工具（流式路径无该定时器，两种上下文行为一致）。
 */
class StreamingOpenAiClientServerToolDegradeTest {

    private lateinit var server: MockWebServer

    private val serde422: MockResponse
        get() = MockResponse()
            .setResponseCode(422)
            .setHeader("Content-Type", "application/json")
            .setBody(
                "{\"error\":{\"message\":\"tools[0].type: unknown variant `WebSearch`, " +
                    "expected `function` at line 1 column 296\",\"type\":\"invalid_request_error\"}}"
            )

    private fun sseOk(vararg frames: String): MockResponse {
        val body = frames.joinToString("\n\n") + "\n\n"
        return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "text/event-stream")
            .setBody(body)
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(): StreamingOpenAiClient {
        val config = LlmConfig(
            baseUrl = server.url("/v1").toString(),
            apiKey = "k",
            model = "glm-4.7",
            // pid 命中门控（真实端点为智谱中转）→ 首发注入 server tool，
            // 由被测端点以 422 拒绝后走降级重发路径。
            providerId = "glm",
            webSearch = WebSearchMode.ZHIPU
        )
        return StreamingOpenAiClient(config, OkHttpClient())
    }

    /** 按序取出已录请求的 JSON 体（tools 数组形态断言用）。 */
    private fun recordedBody(): String = server.takeRequest().body.readUtf8()

    /** 提取请求体 tools 数组的 type 列表（无 tools 键 → 空列表）。 */
    private fun toolsTypesOf(body: String): List<String> =
        Json.parseToJsonElement(body).jsonObject["tools"]
            ?.jsonArray
            ?.map { it.jsonObject["type"]!!.jsonPrimitive.content }
            ?: emptyList()

    // ── 1. 关键回归：422 unknown variant → 自动降级 → 任务继续 ──────

    @Test
    fun `422 unknown variant strips server tool and retries stream once`() = runBlocking {
        // 首发被 422 拒（serde unknown variant）→ 降级重发 → 200 流式正常收尾。
        server.enqueue(serde422)
        server.enqueue(
            sseOk(
                """data: {"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}""",
                "data: [DONE]"
            )
        )

        val chunks = client().chatStream(
            messages = listOf(LlmMessage.User("hi")),
            tools = emptyList(),
            temperature = 0.5f,
            maxTokens = 128
        ).toList()

        // 任务继续：流正常完成并携带正文（旧实现在此直接抛 422 终止任务）。
        assertEquals("ok", chunks.firstNotNullOfOrNull { it.content })
        assertEquals(2, server.requestCount)

        // 首发：tools 含非标准 WebSearch 条目；重发：无任何 tools 条目。
        val first = recordedBody()
        val second = recordedBody()
        assertEquals(listOf("WebSearch"), toolsTypesOf(first))
        assertTrue("WebSearch" in first)
        assertEquals(emptyList<String>(), toolsTypesOf(second))
        assertFalse("WebSearch" in second)
    }

    @Test
    fun `degraded flag persists for subsequent calls on same client`() = runBlocking {
        server.enqueue(serde422)
        server.enqueue(
            sseOk(
                """data: {"choices":[{"delta":{"content":"a"},"finish_reason":"stop"}]}""",
                "data: [DONE]"
            )
        )
        val underTest = client()
        underTest.chatStream(
            messages = listOf(LlmMessage.User("hi")),
            tools = emptyList(), temperature = 0.5f, maxTokens = 128
        ).toList()

        // 第二次调用（同实例）：首发即无搜索条目，不再烧 422。
        server.enqueue(
            sseOk(
                """data: {"choices":[{"delta":{"content":"b"},"finish_reason":"stop"}]}""",
                "data: [DONE]"
            )
        )
        val second = underTest.chatStream(
            messages = listOf(LlmMessage.User("again")),
            tools = emptyList(), temperature = 0.5f, maxTokens = 128
        ).toList()

        assertEquals("b", second.firstNotNullOfOrNull { it.content })
        assertEquals(3, server.requestCount)
        // 依次取出三个请求体：①首发（含 WebSearch）→ ②降级重发 → ③新调用首发。
        recordedBody() // ①
        val third = recordedBody() // ②
        val fourth = recordedBody() // ③
        assertEquals(emptyList<String>(), toolsTypesOf(third))
        assertEquals(emptyList<String>(), toolsTypesOf(fourth))
        assertFalse("WebSearch" in fourth)
    }

    // ── 4. 非该形态 4xx：不重试、原样上抛 ──────────────────────────

    @Test
    fun `context length 400 is not retried and propagates`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody(
                "{\"error\":{\"message\":\"This model's maximum context length is 4096 tokens. " +
                    "However, you requested 5000 tokens.\",\"type\":\"invalid_request_error\"}}"
            )
        )
        try {
            client().chatStream(
                messages = listOf(LlmMessage.User("hi")),
                tools = emptyList(), temperature = 0.5f, maxTokens = 128
            ).toList()
            fail("expected LlmException.Http(400)")
        } catch (expected: LlmException.Http) {
            assertEquals(400, expected.code)
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `non stream chat degrades and retries once`() = runBlocking {
        server.enqueue(serde422)
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"role":"assistant","content":"ok"}}]}"""
            )
        )
        val response = client().chat(
            messages = listOf(LlmMessage.User("hi")),
            tools = emptyList(), temperature = 0.5f, maxTokens = 128
        )
        assertEquals("ok", response.content)
        assertEquals(2, server.requestCount)
        assertEquals(listOf("WebSearch"), toolsTypesOf(recordedBody()))
        assertEquals(emptyList<String>(), toolsTypesOf(recordedBody()))
    }
}
