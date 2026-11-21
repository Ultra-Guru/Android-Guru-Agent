package com.apex.agent.core.llm

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader

/**
 * SPE-08 P1-1/P1-2 流式回合诊断与看门狗测试（TurnStatsCollector 假钟 +
 * MockWebServer 真实 HTTP 层，与 StreamingOpenAiClientSseTest 同款）。
 *
 * 断言：
 * 1. 采集器（假钟）：首 token 延迟 / 帧间隔 P50·P95 / 帧账本 /
 *    usageSource / 截断与收尾语义；
 * 2. chatStream（真实 SSE）：流尾最后一帧携带诊断快照（帧数对账 /
 *    统计帧到达 = REAL / 缺席 = MISSING）；
 * 3. content_filter：独立异常类型 + 诊断快照仍在异常前上抛；
 * 4. 心跳停滞看门狗（假钟）：只发心跳超过阈值 → StreamStalled；
 * 5. 错误文案映射：新异常类型 → 独立中文文案。
 */
class StreamDiagnosticsTest {

    // ═══ 1. TurnStatsCollector（纯状态机，假钟）═══

    private fun collectorOf(clockTicks: MutableList<Long>): TurnStatsCollector =
        TurnStatsCollector(clock = { clockTicks.removeFirstOrNull() ?: clockTicks.lastOrNull() ?: 0L })

    @Test
    fun `collector counts frames parsed dropped and first token latency`() {
        val ticks = mutableListOf(100L, 100L, 160L, 200L, 260L, 300L, 300L, 300L)
        val c = collectorOf(ticks)
        // 构造 100ms：帧 A（未解析成功 → dropped）、帧 B（含内容 = 首 token）……
        c.onFrameArrived()                 // t=100
        c.onFirstToken()                   // 首 token @160
        c.onFrameArrived()                 // t=200
        c.onFrameArrived()                 // t=260
        c.onFrameArrived()                 // t=300
        c.onFrameParsed()
        c.onFrameParsed()
        val snap = c.snapshot()
        assertEquals(4, snap.framesReceived)
        assertEquals(2, snap.framesParsed)
        assertEquals(2, snap.framesDropped)
        assertEquals(60L, snap.firstTokenLatencyMs)
        // 帧间隔样本 [100, 60, 40] → P50=60（中位）、P95=100（最大样本）
        assertEquals(60L, snap.interFrameMsP50)
        assertEquals(100L, snap.interFrameMsP95)
        assertNull("无截断无收尾原因", snap.finishReason)
        assertEquals(UsageSource.MISSING, snap.usageSource)
    }

    @Test
    fun `collector percentile null below three gap samples`() {
        val ticks = mutableListOf(0L, 0L, 10L, 20L)
        val c = collectorOf(ticks)
        c.onFrameArrived() // 0
        c.onFrameArrived() // 10（gap 样本 1）
        c.onFrameArrived() // 20（gap 样本 2 —— 样本 < 3 无分位数）
        val snap = c.snapshot()
        assertNull(snap.interFrameMsP50)
        assertNull(snap.interFrameMsP95)
    }

    @Test
    fun `collector records finish reason truncation and usage source`() {
        val ticks = mutableListOf(0L, 0L, 5L, 5L)
        val c = collectorOf(ticks)
        c.onFrameArrived()
        c.onFrameParsed()
        c.onTruncated()
        c.onFinishReason("length")
        c.onUsageSeen()
        val snap = c.snapshot()
        assertTrue(snap.truncated)
        assertEquals("length", snap.finishReason)
        assertEquals(UsageSource.REAL, snap.usageSource)
        assertTrue(snap.turnId.isNotBlank())
    }

    // ═══ 2-3. chatStream 真实 SSE 层（诊断快照随流尾上抛）═══

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

    private fun chunk(content: String? = null, finish: String? = null): String {
        val delta = if (content != null) "\"delta\":{\"content\":\"$content\"}" else "\"delta\":{}"
        val finishPart = if (finish != null) ",\"finish_reason\":\"$finish\"" else ""
        return "data: {\"choices\":[{$delta$finishPart}]}"
    }

    /** 收集 + 捕获异常（截断/过滤路径流尾抛错，诊断帧在异常前已上抛）。 */
    private fun collectWithThrowable(vararg blocks: String): Pair<List<LlmStreamChunk>, Throwable?> {
        server.enqueue(sse(*blocks))
        val collected = mutableListOf<LlmStreamChunk>()
        val error = try {
            client().chatStream(listOf(LlmMessage.User("hi")), emptyList(), 0.5f, 100)
                .collect { collected += it }
            null
        } catch (e: Exception) {
            e
        }
        return collected to error
    }

    @Test
    fun `diagnostics snapshot rides the final chunk with frame accounting`() = runTest {
        val (chunks, error) = collectWithThrowable(
            chunk(content = "he"), chunk(content = "llo", finish = "stop"),
            "data: [DONE]",
            "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}"
        )
        assertNull("干净收尾无异常", error)
        val diag = chunks.lastOrNull { it.diagnostics != null }?.diagnostics
        assertNotNull("流尾最后一帧应携带诊断快照", diag)
        diag!!
        // 帧账本：3 个数据帧（he/llo/统计帧）+ [DONE] = received 4；[DONE] 不走
        // parseStreamChunk → parsed 3（he、llo、usage 统计帧）。
        assertEquals(4, diag.framesReceived)
        assertEquals(3, diag.framesParsed)
        assertEquals(0, diag.framesDropped)
        assertEquals("done", diag.finishReason)
        assertEquals(UsageSource.REAL, diag.usageSource)
        assertNotNull("有内容帧 → 首 token 延迟可测", diag.firstTokenLatencyMs)
        assertTrue(chunks.any { it.usage != null })
    }

    @Test
    fun `missing usage frame yields MISSING source`() = runTest {
        val (chunks, error) = collectWithThrowable(
            chunk(content = "hi", finish = "stop"), "data: [DONE]"
        )
        assertNull(error)
        val diag = chunks.last { it.diagnostics != null }.diagnostics!!
        assertEquals(UsageSource.MISSING, diag.usageSource)
    }

    @Test
    fun `content filter finishes with distinct exception and diagnostics`() = runTest {
        val (chunks, error) = collectWithThrowable(
            chunk(content = "partial"), chunk(finish = "content_filter"), "data: [DONE]"
        )
        assertTrue("content_filter → 独立异常类型", error is LlmException.ContentFiltered)
        assertEquals(1, (error as LlmException.ContentFiltered).partialChars)
        // 诊断快照先于异常上抛（诊断不因异常丢失）。
        val diag = chunks.lastOrNull { it.diagnostics != null }?.diagnostics
        assertNotNull("异常路径同样携带诊断快照", diag)
        assertEquals("content_filter", diag!!.finishReason)
    }

    @Test
    fun `truncation diagnostics mark truncated before exception`() = runTest {
        val (chunks, error) = collectWithThrowable(
            chunk(content = "half"), chunk(finish = "length"), "data: [DONE]"
        )
        assertTrue(error is LlmException.TruncatedStream)
        val diag = chunks.last { it.diagnostics != null }.diagnostics!!
        assertTrue(diag.truncated)
        assertEquals("length", diag.finishReason)
    }

    // ═══ 4. 心跳停滞看门狗（读取器内联 + 假钟）═══

    @Test
    fun `heartbeat stall watchdog fires after gap threshold`() {
        // 假钟：构造 t=0（lastDataLineMs 基准）；心跳行到达时 t=80 > 阈值 70 → 触发。
        var now = 0L
        val reader = SseFrameReader(
            BufferedReader(StringReader(": ping\n: ping\n")),
            clock = { now },
            heartbeatStallTimeoutMs = 70L
        )
        now = 80L
        try {
            reader.nextFrame()
            throw AssertionError("纯心跳流应触发停滞异常")
        } catch (expected: LlmException.StreamStalled) {
            assertEquals(80L, expected.gapMs)
        }
    }

    @Test
    fun `heartbeat watchdog stays silent within threshold and disabled by default`() {
        var now = 0L
        val reader = SseFrameReader(
            BufferedReader(StringReader(": ping\n\n")),
            clock = { now },
            heartbeatStallTimeoutMs = 70L
        )
        now = 30L // 单心跳 30ms < 70 → 不抛
        assertNull(reader.nextFrame())
        assertEquals(1, reader.commentLines)
        // 默认关闭（null）：旧调用零变化。
        val legacy = SseFrameReader(BufferedReader(StringReader(": ping\n\n")))
        assertNull(legacy.nextFrame())
        assertEquals(1, legacy.commentLines)
    }

    // ═══ 5. 错误文案映射（独立类型独立文案）═══

    @Test
    fun `new watchdog errors map to distinct user texts`() {
        assertEquals(LlmErrorText.CONTENT_FILTERED, LlmErrorText.userMessage(LlmException.ContentFiltered(3)))
        assertEquals(LlmErrorText.NO_FIRST_TOKEN, LlmErrorText.userMessage(LlmException.NoFirstToken(120_000L)))
        assertEquals(LlmErrorText.STREAM_STALLED, LlmErrorText.userMessage(LlmException.StreamStalled(80_000L, 12)))
        // 旧映射不受影响（回归锚点）。
        assertEquals(LlmErrorText.RESPONSE_TRUNCATED, LlmErrorText.userMessage(LlmException.TruncatedStream(5)))
    }
}
