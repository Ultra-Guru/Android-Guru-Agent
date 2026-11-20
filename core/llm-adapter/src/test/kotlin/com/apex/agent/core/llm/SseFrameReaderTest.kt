package com.apex.agent.core.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader

/**
 * [SseFrameReader] 标准 SSE 帧状态机测试（SPE-05 P0-1 验收矩阵）。
 *
 * 断言：
 * 1. 多行 `data:` 按 `\n` 拼接成一个完整帧（一帧只解析一次）；
 * 2. `event:` / `id:` / `retry:` / 注释行不进数据缓冲（心跳只计数）；
 * 3. 空行分帧：相邻事件各自 dispatch；
 * 4. 无 data 行的事件（纯注释/纯 event 头）不产生帧；
 * 5. EOF 冲刷半截事件（宁解析勿丢帧）；
 * 6. 单行护栏：超长行跳过 + 计数。
 */
class SseFrameReaderTest {

    private fun readerOf(vararg lines: String): SseFrameReader {
        val text = lines.joinToString("\n") + "\n"
        return SseFrameReader(BufferedReader(StringReader(text)))
    }

    @Test
    fun `multi line data lines are joined with newline into one frame`() {
        // SPE-05 验收 1：多行 data 拼成一个完整 JSON 帧 → 只解析一次。
        val r = readerOf(
            "data: {\"choices\":[{",
            "data: \"delta\":{\"content\":\"hello\"}}]}",
            ""
        )
        assertEquals("{\"choices\":[{\n\"delta\":{\"content\":\"hello\"}}]}", r.nextFrame())
        assertNull("拼接后无更多帧", r.nextFrame())
    }

    @Test
    fun `comment heartbeat lines counted and never parsed as data`() {
        // SPE-05 验收 2：心跳不产生 chunk、不误判完成。
        val r = readerOf(
            ": ping",
            "data: {\"a\":1}",
            "",
            ": ping",
            ": keep-alive",
            "data: [DONE]",
            ""
        )
        assertEquals("{\"a\":1}", r.nextFrame())
        assertEquals("[DONE]", r.nextFrame())
        assertEquals("注释行只进心跳计数", 3, r.commentLines)
        assertNull(r.nextFrame())
    }

    @Test
    fun `event id and retry lines are consumed without becoming data`() {
        val r = readerOf(
            "event: message",
            "id: 42",
            "retry: 5000",
            "data: payload",
            ""
        )
        assertEquals("payload", r.nextFrame())
    }

    @Test
    fun `blank line separates consecutive events`() {
        val r = readerOf(
            "data: first",
            "",
            "data: second",
            ""
        )
        assertEquals("first", r.nextFrame())
        assertEquals("second", r.nextFrame())
    }

    @Test
    fun `event with no data lines dispatches nothing`() {
        val r = readerOf(
            "event: ping",
            "",
            "data: real",
            ""
        )
        assertEquals("real", r.nextFrame())
    }

    @Test
    fun `eof flushes pending partial event instead of discarding`() {
        // 严格 SSE 规范会丢弃 EOF 截断的最后事件；本读取器面向良莠不齐的
        // 网关（[DONE] 后无空行 / 截断重连残段）—— 宁解析勿丢帧。
        val r = readerOf("data: tail-without-blank-line")
        assertEquals("tail-without-blank-line", r.nextFrame())
    }

    @Test
    fun `data prefix without space is accepted`() {
        // 兼容 `data:{...}`（旧实现只认 `data: ` 带空格形态）。
        val r = readerOf("data:{\"x\":1}", "")
        assertEquals("{\"x\":1}", r.nextFrame())
    }

    @Test
    fun `oversized lines are skipped and counted`() {
        val huge = "x".repeat(64)
        val r = SseFrameReader(
            BufferedReader(StringReader("data: $huge\n\ndata: ok\n\n")),
            maxLineChars = 10
        )
        assertEquals("超长行被跳过", "ok", r.nextFrame())
        assertEquals(1, r.oversizedLinesSkipped)
    }

    @Test
    fun `empty stream yields null immediately`() {
        assertNull(readerOf().nextFrame())
    }

    @Test
    fun `only blank and comment lines yield no frames`() {
        val r = readerOf("", ": ping", "", ": pong", "")
        assertNull(r.nextFrame())
        assertEquals(2, r.commentLines)
    }
}
