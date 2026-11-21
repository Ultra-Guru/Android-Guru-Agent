package com.apex.agent.core.code.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TerminalPulseBuffer] 四条件脉冲测试。
 *
 * 条件①体量 ②行数 ③时间窗 ④显式 flush；环形窗口与关闭/重置语义。
 */
class TerminalPulseBufferTest {

    @Test
    fun `no flush before any condition is met`() {
        val buf = TerminalPulseBuffer()
        buf.append("hello") // 5 字节、0 换行、时间窗未到（now=0 不判定）
        assertNull(buf.tick(0))
        assertEquals(5, buf.pendingSize())
    }

    @Test
    fun `condition one size threshold flushes`() {
        val buf = TerminalPulseBuffer(sizeThresholdBytes = 16)
        buf.append("x".repeat(16))
        val pulse = buf.tick(0)
        assertEquals("x".repeat(16), pulse)
        assertEquals(0, buf.pendingSize())
    }

    @Test
    fun `condition two line count flushes`() {
        val buf = TerminalPulseBuffer(flushLineCount = 3)
        buf.append("a\nb\nc\n")
        assertEquals("a\nb\nc\n", buf.tick(0))
    }

    @Test
    fun `condition three time window flushes`() {
        val buf = TerminalPulseBuffer(windowMs = 120)
        buf.append("sparse output")
        assertNull(buf.tick(1_000)) // 首拍只记录基准时刻
        assertNull(buf.tick(1_100)) // 100ms < 120ms
        assertEquals("sparse output", buf.tick(1_121)) // 121ms ≥ 120ms
    }

    @Test
    fun `condition four explicit flush drains tail`() {
        val buf = TerminalPulseBuffer()
        buf.append("tail line")
        assertEquals("tail line", buf.flush(500))
        assertNull(buf.flush(500))
    }

    @Test
    fun `ring window keeps only latest chars`() {
        val buf = TerminalPulseBuffer(windowChars = 10, sizeThresholdBytes = 4)
        buf.append("0123456789AAAA")
        buf.tick(0)
        buf.append("BBBBBBBBBB")
        buf.tick(0)
        // 尾窗 = 最新 10 字符
        assertEquals("AAAABBBBBBBBBB".takeLast(10), buf.content())
    }

    @Test
    fun `close makes append a no-op`() {
        val buf = TerminalPulseBuffer()
        buf.append("before")
        buf.close()
        buf.append("after")
        assertEquals("before", buf.flush(0))
        assertNull(buf.flush(0))
    }

    @Test
    fun `reset clears everything for next run`() {
        val buf = TerminalPulseBuffer()
        buf.append("old run output")
        buf.flush(0)
        buf.reset()
        assertEquals("", buf.content())
        assertEquals(0, buf.pendingSize())
        assertNull(buf.tick(0))
    }

    @Test
    fun `pulses are integral blocks not character drips`() {
        val buf = TerminalPulseBuffer(flushLineCount = 2)
        buf.append("line1\n")
        assertNull("单行不满足行数条件", buf.tick(0))
        buf.append("line2\nline3\n")
        // 一次脉冲 = 攒住的整块（3 行）
        assertEquals("line1\nline2\nline3\n", buf.tick(0))
    }

    @Test
    fun `rendered content has no marker when nothing scrolled off`() {
        val buf = TerminalPulseBuffer(windowChars = 100)
        buf.append("short output")
        buf.flush(0)
        assertEquals(0L, buf.clippedChars())
        assertEquals("short output", buf.renderedContent())
    }

    @Test
    fun `rendered content prefixes clip marker once window slides`() {
        val buf = TerminalPulseBuffer(windowChars = 10, sizeThresholdBytes = 4)
        buf.append("0123456789AAAA")
        buf.tick(0)
        buf.append("BBBBBBBBBB")
        buf.tick(0)
        // 总 flush 24 字符，窗口只留最新 10 → 滚出 14
        assertEquals(14L, buf.clippedChars())
        val rendered = buf.renderedContent()
        assertTrue(rendered.startsWith("[... 14 字符已滚出（仅保留最新尾窗） ...]\n"))
        assertEquals("AAAABBBBBBBBBB".takeLast(10), rendered.substringAfter('\n'))
        // 原始 content() 保持无标记（既有契约零变化）
        assertEquals("AAAABBBBBBBBBB".takeLast(10), buf.content())
    }

    @Test
    fun `reset clears clip accounting too`() {
        val buf = TerminalPulseBuffer(windowChars = 5, sizeThresholdBytes = 2)
        buf.append("0123456789")
        buf.tick(0)
        assertTrue(buf.clippedChars() > 0)
        buf.reset()
        assertEquals(0L, buf.clippedChars())
        assertEquals("", buf.renderedContent())
    }
}
