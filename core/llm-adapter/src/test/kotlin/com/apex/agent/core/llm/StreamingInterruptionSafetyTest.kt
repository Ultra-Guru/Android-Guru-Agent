package com.apex.agent.core.llm

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** POST chat completions is not replayable without an explicit server continuation protocol. */
class StreamingInterruptionSafetyTest {
    private val config = LlmConfig(
        baseUrl = "https://example.test/v1", model = "test-model",
        retryCount = 3, retryDelayMs = 1, maxRetryDelayMs = 2
    )

    private fun stream(interceptor: Interceptor) = StreamingOpenAiClient(
        config, OkHttpClient.Builder().addInterceptor(interceptor).build()
    ).chatStream(listOf(LlmMessage.User("hello")), emptyList(), 0.7f, 128)

    private fun response(chain: Interceptor.Chain, body: ResponseBody, code: Int = 200): Response =
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .code(code).message("test").body(body).build()

    @Test fun `connection lost before headers never replays POST`() = runBlocking {
        val calls = AtomicInteger()
        try {
            stream(Interceptor { chain ->
                assertEquals("POST", chain.request().method)
                calls.incrementAndGet()
                throw IOException("connection reset after upload")
            }).toList()
            fail("connection loss must surface")
        } catch (_: Exception) {
            assertEquals("ambiguous upload must not repeat", 1, calls.get())
        }
    }

    @Test fun `retryable server error does not replay uncertain POST`() = runBlocking {
        val calls = AtomicInteger()
        try {
            stream(Interceptor { chain ->
                calls.incrementAndGet()
                response(chain, "busy".toResponseBody(), code = 503)
            }).toList()
            fail("HTTP error must surface")
        } catch (e: LlmException.Http) {
            assertEquals(503, e.code)
            assertEquals(1, calls.get())
        }
    }

    @Test fun `partial text survives broken body and never retries`() = runBlocking {
        val calls = AtomicInteger()
        val chunks = mutableListOf<LlmStreamChunk>()
        try {
            stream(Interceptor { chain ->
                calls.incrementAndGet()
                response(chain, brokenBody())
            }).collect { chunks += it }
            fail("broken body must not look like success")
        } catch (_: Exception) {
            assertEquals(1, calls.get())
            assertEquals(listOf("partial"), chunks.mapNotNull { it.content })
            assertFalse(chunks.any { it.isFinish })
        }
    }

    @Test fun `EOF without finish marker is not a completed answer`() = runBlocking {
        val chunks = mutableListOf<LlmStreamChunk>()
        try {
            stream(Interceptor { chain ->
                response(chain, "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n".toResponseBody())
            }).collect { chunks += it }
            fail("truncated SSE must not succeed")
        } catch (e: Exception) {
            assertEquals("partial", chunks.single().content)
            assertTrue(e is IOException || e is LlmException)
        }
    }

    private fun brokenBody(): ResponseBody {
        val frame = Buffer().writeUtf8("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n")
        val source: BufferedSource = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (frame.size == 0L) throw IOException("socket reset mid-stream")
                return frame.read(sink, byteCount)
            }
            override fun timeout(): Timeout = Timeout.NONE
            override fun close() = Unit
        }.buffer()
        return object : ResponseBody() {
            override fun contentType() = "text/event-stream".toMediaType()
            override fun contentLength() = -1L
            override fun source() = source
        }
    }
}
