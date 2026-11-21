package com.apex.agent.core.llm

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 回归：tool_calls 字段的 JsonNull 陷阱（2026-11「MCP null」全链失效事故）。
 *
 * kotlinx 的 JsonNull 是 JsonPrimitive，`.content` 返回字面串 "null"；旧解析
 * `?.jsonPrimitive?.content ?: ""` 的 elvis 只兜底「键缺失」，兜不了「值为
 * JSON null」—— 网关在参数续块上发 "function":{"name":null} 时，工具名被
 * 解析成字符串 "null"，下游注册表报 Tool 'null' not found，模型的有效 MCP
 * 调用全链报废。修复后（contentOrNull）JsonNull 一律视为字段缺席（""）。
 *
 * 断言口径与解析源码逐条核实：parseStreamChunk 的 tool_calls 分支 /
 * parseNonStreamResponse 的 tool_calls 分支（本文件全部为纯 JVM 单测，
 * OkHttp 拦截器伪造 SSE/JSON 响应，与 StreamingInterruptionSafetyTest 同款）。
 */
class ToolCallJsonNullNameTest {

    private val config = LlmConfig(
        baseUrl = "https://example.test/v1", model = "test-model",
        retryCount = 0, retryDelayMs = 1, maxRetryDelayMs = 2
    )

    private fun client(body: String): StreamingOpenAiClient =
        StreamingOpenAiClient(
            config,
            OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("test")
                    .body(body.toResponseBody("text/event-stream".toMediaType()))
                    .build()
            }).build()
        )

    private suspend fun streamChunks(body: String): List<LlmStreamChunk> =
        client(body).chatStream(listOf(LlmMessage.User("hi")), emptyList(), -1f, -1).toList()

    @Test
    fun `流式帧 name 与 id 为 JSON null 时解析为空串而非字面 null`() = runTest {
        val chunks = streamChunks(
            sse(
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":null,""" +
                    """"function":{"name":null,"arguments":"{\"q\":\"mcp\"}"}}]}}]}""",
                done = true
            )
        )
        val tc = chunks.flatMap { it.toolCalls }.single()
        // JsonNull 经 contentOrNull 视为缺席 → ""，绝不能是字符串 "null"
        assertEquals("", tc.name)
        assertEquals("", tc.id)
        assertEquals("""{"q":"mcp"}""", tc.arguments)
        assertEquals(0, tc.index)
    }

    @Test
    fun `流式续块 null 名不携带名字 解析层输出空名分片`() = runTest {
        // 首片带真名，续片 name 为 JSON null 且继续给参数 —— 解析层对续片
        // 产出 name="" 的分片（真名由引擎侧 StreamingToolCallAccumulator 保留）。
        val chunks = streamChunks(
            sse(
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1",""" +
                    """"function":{"name":"McpGithubCreateIssue","arguments":"{\"q\":"}}]}}]}""",
                """{"choices":[{"delta":{"tool_calls":[{"index":0,""" +
                    """"function":{"name":null,"arguments":"\"mcp\"}"}}]}}]}""",
                done = true
            )
        )
        val calls = chunks.flatMap { it.toolCalls }
        assertEquals(2, calls.size)
        // 首片：真名完整到达
        assertEquals("McpGithubCreateIssue", calls[0].name)
        // 续片：null 名 → 空串（修复前是字面 "null"，会反向覆盖累积器里的真名）
        assertEquals("", calls[1].name)
        assertEquals("\"mcp\"}", calls[1].arguments)
    }

    @Test
    fun `流式 arguments 为 JSON null 时解析为空串`() = runTest {
        val chunks = streamChunks(
            sse(
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_2",""" +
                    """"function":{"name":"ToolList","arguments":null}}]}}]}""",
                done = true
            )
        )
        val tc = chunks.flatMap { it.toolCalls }.single()
        assertEquals("ToolList", tc.name)
        assertEquals("", tc.arguments)
    }

    // 注：chat()（非流式）经 SuspendHttpRetry 的 withTimeoutOrNull(requestTimeoutMs)
    // 包裹 —— runTest 虚拟时间下，等待 OkHttp 真实线程回调时调度器唯一可推进的
    // 定时任务就是那个总预算超时，虚拟时间会瞬间跳到超时点误报「预算耗尽」。
    // 与 DefaultModelRuntimeTest 同款改用 runBlocking（真实时钟）驱动。
    @Test
    fun `非流式响应 name 为 JSON null 时解析为空串而非字面 null`() = runBlocking {
        val body =
            """{"choices":[{"message":{"content":null,"tool_calls":[{"id":null,""" +
                """"type":"function","function":{"name":null,"arguments":"{}"}}]}}],""" +
                """"usage":null}"""
        val response = client(body).chat(listOf(LlmMessage.User("hi")))
        val tc = response.toolCalls.single()
        assertEquals("", tc.name)
        assertEquals("", tc.id)
    }

    @Test
    fun `非流式响应合法工具调用不受影响`() = runBlocking {
        val body =
            """{"choices":[{"message":{"content":null,"tool_calls":[{"id":"call_9",""" +
                """"type":"function","function":{"name":"McpSearchlightWebSearch",""" +
                """"arguments":"{\"query\":\"a\"}"}}]}}],"usage":null}"""
        val response = client(body).chat(listOf(LlmMessage.User("hi")))
        val tc = response.toolCalls.single()
        assertEquals("McpSearchlightWebSearch", tc.name)
        assertEquals("call_9", tc.id)
        assertEquals("""{"query":"a"}""", tc.arguments)
    }

    /** 拼装 SSE 帧体：每帧一条 data: 行，末尾按需补 [DONE]（收尾守卫要求干净收尾）。 */
    private fun sse(vararg frames: String, done: Boolean = false): String = buildString {
        frames.forEach { append("data: ").append(it).append("\n\n") }
        if (done) append("data: [DONE]\n\n")
    }
}
