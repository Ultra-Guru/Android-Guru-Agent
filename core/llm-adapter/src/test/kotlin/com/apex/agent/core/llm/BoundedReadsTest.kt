package com.apex.agent.core.llm

import java.io.ByteArrayInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertTrue
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class BoundedReadsTest {
    @Test
    fun `readAtMost accepts a response exactly at the limit`() {
        val input = ByteArray(8_000_000) { it.toByte() }

        val result = BoundedReads.readAtMost(ByteArrayInputStream(input), input.size)

        assertArrayEquals(input, result)
    }

    @Test
    fun `readAtMost rejects a response over the limit`() {
        val input = ByteArray(8_000_001)

        val result = BoundedReads.readAtMost(ByteArrayInputStream(input), 8_000_000)

        assertNull(result)
    }

    @Test
    fun `readHead truncates error bodies without reading the tail`() {
        val input = "x".repeat(1_000).toByteArray()

        val result = BoundedReads.readHead(ByteArrayInputStream(input), 200)

        assertEquals(200, result.size)
    }

    @Test
    fun `non-stream chat rejects an oversized successful response`() = runTest {
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(ByteArray(8_000_001).toResponseBody())
                    .build()
            }
            .build()
        val client = StreamingOpenAiClient(
            LlmConfig(baseUrl = "https://example.com/v1", apiKey = "key", model = "model"),
            http
        )

        try {
            // chat() 内 SuspendHttpRetry 的总预算 withTimeoutOrNull 在 runTest
            // 虚拟时间下会被空闲快进抢先触发（真实 OkHttp 回调永远慢于虚拟时钟）
            // —— 把被测调用放回真实调度器，超时语义才与生产一致。
            withContext(Dispatchers.Default) {
                client.chat(listOf(LlmMessage.User("hello")), emptyList(), 0.5f, 16)
            }
            fail("expected an oversized response to be rejected")
        } catch (error: LlmException.ResponseTooLarge) {
            assertEquals(8_000_000, error.limitBytes)
            assertTrue(error.message!!.contains("8000000"))
        }
    }

    @Test
    fun `http error body is capped before it reaches the exception`() = runTest {
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(502)
                    .message("Bad Gateway")
                    .body("x".repeat(1_000).toResponseBody())
                    .build()
            }
            .build()
        val client = StreamingOpenAiClient(
            LlmConfig(baseUrl = "https://example.com/v1", apiKey = "key", model = "model"),
            http
        )

        try {
            // 同上：真实调度器承载被测调用（502 会走 retryOnCodes 真实退避）。
            withContext(Dispatchers.Default) {
                client.chat(listOf(LlmMessage.User("hello")), emptyList(), 0.5f, 16)
            }
            fail("expected the HTTP error to be propagated")
        } catch (error: LlmException.Http) {
            assertEquals(502, error.code)
            assertEquals(200, error.body.length)
        }
    }
}
