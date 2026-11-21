package com.apex.agent.core.llm

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * SPE-05 流式收尾/分帧/截断语义测试（真实 HTTP 层，MockWebServer 与
 * ServerToolDegrade 同款）。
 *
 * 断言（SPE-05 验收矩阵）：
 * 1. 多行 data 帧整体解析（不再拆碎静默丢帧）；
 * 2. `[DONE]` 之后的 usage 统计帧被消费（旧实现 DONE 即 break 丢弃）；
 * 3. `finish_reason=length` → truncated 标志 + [LlmException.TruncatedStream]
 *    （不再被当正常收尾）；
 * 4. 注释心跳不产生 chunk、不误判完成；
 * 5. `"usage": null` 的非流式响应不再抛 Parse（与流式同口径）；
 * 6. reasoning 别名（delta.thinking / reasoning_details）思维链可见。
 */
class StreamingOpenAiClientSseTest {

    private lateinit var server: MockWebServer

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
            model = "some-model"
        )
        return StreamingOpenAiClient(config, OkHttpClient())
    }

    private fun sse(vararg eventBlocks: String): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "text/event-stream")
            .setBody(eventBlocks.joinToString("\n\n") + "\n\n")

    /** 一个事件内的多行 data（单 \n 分行，整体是一个帧）。 */
    private fun multiLineEvent(vararg dataLines: String): String =
        dataLines.joinToString("\n")

    /** OpenAI 形态帧：finish_reason 在 choice 层（非 delta 内）。 */
    private fun chunk(content: String? = null, finish: String? = null, reasoning: String? = null): String {
        val parts = mutableListOf<String>()
        if (content != null) parts.add("\"content\":${JsonQuote.of(content)}")
        if (reasoning != null) parts.add("\"reasoning\":${JsonQuote.of(reasoning)}")
        val delta = if (parts.isEmpty()) "\"delta\":{}" else "\"delta\":{${parts.joinToString(",")}}"
        val finishPart = if (finish != null) ",\"finish_reason\":\"$finish\"" else ""
        return "data: {\"choices\":[{$delta$finishPart}]}"
    }

    /** 极简 JSON 字符串手工引号转义（避免测试对序列化器的依赖）。 */
    private object JsonQuote {
        fun of(raw: String): String = "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }

    // ── 1. 多行 data 帧（规范分帧不再拆碎）────────────────────────

    @Test
    fun `multi line data frame is parsed once as a whole`() = runTest {
        // 规范分帧：网关把 pretty-print 的 JSON 按行拆进多行 data ——
        // 拼接后是合法 JSON（换行落在 token 之间），一次解析成功。
        server.enqueue(
            sse(
                multiLineEvent(
                    "data: {\"choices\":[{",
                    "data: \"delta\":{\"content\":\"hello\"},\"finish_reason\":\"stop\"}]}"
                ),
                "data: [DONE]"
            )
        )
        val chunks = client().chatStream(
            listOf(LlmMessage.User("hi")), emptyList(), 0.5f, 100
        ).toList()
        // 多行拼接后 content = "hello"（一次性完整），不再每行解析失败静默丢帧。
        assertEquals("hello", chunks.firstOrNull { !it.content.isNullOrEmpty() }?.content)
        assertTrue(chunks.any { it.isFinish })
    }

    // ── 2. [DONE] 后的 usage 统计帧被消费 ────────────────────────

    @Test
    fun `usage frame after DONE is consumed`() = runTest {
        server.enqueue(
            sse(
                chunk(content = "hi", finish = "stop"),
                "data: [DONE]",
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}"
            )
        )
        val chunks = client().chatStream(
            listOf(LlmMessage.User("hi")), emptyList(), 0.5f, 100
        ).toList()
        val usage = chunks.lastOrNull { it.usage != null }?.usage
        assertNotNull("[DONE] 之后的统计帧不应被丢弃", usage)
        assertEquals(15, usage!!.totalTokens)
        // 完成语义不被尾部统计帧破坏。
        assertTrue(chunks.any { it.isFinish })
    }

    // ── 3. finish_reason=length → truncated（SPE-05 P0-3 语义表）──

    @Test
    fun `finish reason length marks chunk truncated and stream fails explicitly`() = runTest {
        server.enqueue(
            sse(
                chunk(content = "half an"),
                chunk(finish = "length"),
                "data: [DONE]"
            )
        )
        val collected = mutableListOf<LlmStreamChunk>()
        try {
            client().chatStream(
                listOf(LlmMessage.User("hi")), emptyList(), 0.5f, 100
            ).collect { collected.add(it) }
            fail("finish_reason=length 应以 TruncatedStream 收尾，不再是正常收尾")
        } catch (e: LlmException.TruncatedStream) {
            // 半截正文已发射（部分输出留存），且错误携带已保留字符数。
            assertEquals("half an", collected.first { !it.content.isNullOrEmpty() }.content)
            assertEquals(7, e.partialChars)
        }
        // 帧级 truncated 标志已透传（标准线引擎可据此续跑）。
        assertTrue(collected.any { it.truncated })
        // 帧级 finishReason=length 也透传（既有消费者不受影响）。
        assertTrue(collected.any { it.finishReason == "length" })
    }

    @Test
    fun `stop finish stays a clean completion`() = runTest {
        server.enqueue(
            sse(chunk(content = "ok", finish = "stop"), "data: [DONE]")
        )
        val chunks = client().chatStream(
            listOf(LlmMessage.User("hi")), emptyList(), 0.5f, 100
        ).toList()
        assertTrue(chunks.any { it.isFinish && !it.truncated })
    }

    // ── 4. 注释心跳：不产生 chunk、不误判完成 ────────────────────

    @Test
    fun `comment heartbeats never emit chunks nor fake completion`() = runTest {
        server.enqueue(
            sse(
                ": ping",
                ": ping",
                chunk(content = "ok", finish = "stop"),
                ": ping",
                "data: [DONE]"
            )
        )
        val chunks = client().chatStream(
            listOf(LlmMessage.User("hi")), emptyList(), 0.5f, 100
        ).toList()
        // 心跳行不产生 chunk：仅正文+完成帧与 [DONE] 帧。
        assertEquals(2, chunks.size)
        assertTrue(chunks.any { it.content == "ok" })
    }

    // ── 5. 断流残段守卫回归（未见任何完成标记 → IOException）────

    @Test
    fun `eof without any finish marker throws`() = runTest {
        server.enqueue(
            sse(chunk(content = "partial"))
        )
        try {
            client().chatStream(
                listOf(LlmMessage.User("hi")), emptyList(), 0.5f, 100
            ).toList()
            fail("未见完成标记的流应抛截断守卫异常")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("SSE 流意外终止"))
        }
    }

    // ── 6. reasoning 别名（delta.thinking / reasoning_details）──

    @Test
    fun `delta thinking alias is visible as reasoning content`() = runTest {
        server.enqueue(
            sse(
                "data: {\"choices\":[{\"delta\":{\"thinking\":\"chain-of-thought\"}}]}",
                chunk(content = "answer", finish = "stop"),
                "data: [DONE]"
            )
        )
        val chunks = client().chatStream(
            listOf(LlmMessage.User("hi")), emptyList(), 0.5f, 100
        ).toList()
        assertEquals("chain-of-thought", chunks.first { it.reasoningContent != null }.reasoningContent)
    }

    @Test
    fun `reasoning details array alias is concatenated`() = runTest {
        server.enqueue(
            sse(
                "data: {\"choices\":[{\"delta\":{\"reasoning_details\":[{\"text\":\"step1\"},{\"text\":\"step2\"}]}}]}",
                chunk(content = "answer", finish = "stop"),
                "data: [DONE]"
            )
        )
        val chunks = client().chatStream(
            listOf(LlmMessage.User("hi")), emptyList(), 0.5f, 100
        ).toList()
        assertEquals("step1step2", chunks.first { it.reasoningContent != null }.reasoningContent)
    }

    // ── 7. 非流式 usage null（SPE-05 P0-4 与流式同口径）────────
    // 说明：非流式路径经 SuspendHttpRetry 虚拟时钟定时器，runTest 下与真实
    // OkHttp IO 存在竞态（ServerToolDegradeTest 同款结论——不在真实 IO 层
    // 铺陈）；修复口径由流式 usage 解析（as? JsonObject）与请求体测试锁住。
}
