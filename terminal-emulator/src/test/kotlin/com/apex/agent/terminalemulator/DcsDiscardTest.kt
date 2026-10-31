package com.apex.agent.terminalemulator

import org.junit.Assert.*
import org.junit.Test

/**
 * v0.3 DCS 丢弃式消费测试 —— 任务的「DCS consume-discard」组。
 *
 * 契约：DCS 载荷（tmux/sixel 尝试流）**绝不落屏**；正常终止（ST）、
 * CAN(0x18)/SUB(0x1A) 中止、100KB 上限兜底三条路径全部保证：
 * 终止之后的可打印字符正常落屏，内存有界（超大 DCS 不 OOM）。
 */
class DcsDiscardTest {

    private fun core(rows: Int = 4, cols: Int = 20): TerminalCore = TerminalCore(rows, cols)
    private fun feed(c: TerminalCore, s: String) = c.feed(s.toByteArray())
    private fun screen(c: TerminalCore): String =
        c.snapshot().renderedText.lines().filter { it.isNotBlank() }.joinToString("\n")

    @Test fun `DCS payload is discarded and following text lands on screen`() {
        val c = core()
        feed(c, "\u001BPq;1;2q6;1;1;1;100;1#0;2;0;0;0#1;2;100;100;100#2~~!1@")   // sixel 尝试
        feed(c, "\u001B\\")          // ST 终止
        feed(c, "hello")
        assertEquals("hello", screen(c))
    }

    @Test fun `DCS with BEL terminator still discards payload`() {
        // 注意：BEL 在 DCS 里**不是**终止符（VT 规范）—— 它只是载荷字节；
        // 只有 ST / CAN / SUB 结束串。
        val c = core()
        feed(c, "\u001BPq;0qab\u0007cd\u001B\\")
        feed(c, "X")
        assertEquals("X", screen(c))
    }

    @Test fun `CAN aborts the DCS and subsequent text prints`() {
        val c = core()
        feed(c, "\u001BPq;1;2qab")
        c.feed(byteArrayOf(0x18))    // CAN → 立即回 GROUND，载荷丢弃
        feed(c, "cd")
        assertEquals("cd", screen(c))
    }

    @Test fun `SUB aborts the DCS and subsequent text prints`() {
        val c = core()
        feed(c, "\u001BPq;1;2qab")
        c.feed(byteArrayOf(0x1A))    // SUB
        feed(c, "cd")
        assertEquals("cd", screen(c))
    }

    @Test fun `CAN during DCS entry also aborts`() {
        val c = core()
        feed(c, "\u001BPq;1")
        c.feed(byteArrayOf(0x18))
        feed(c, "2q payload")
        assertEquals("2q payload", screen(c))
    }

    @Test fun `oversized DCS payload does not OOM and parser recovers`() {
        val c = core()
        val huge = ByteArray(150_000) { 'x'.code.toByte() }   // 超过 100KB 上限
        c.feed(byteArrayOf(0x1B, 'P'.code.toByte()))               // ESC P → DCS_ENTRY
        c.feed(huge)
        feed(c, "\u001B\\")                                    // ST
        feed(c, "ok")
        assertEquals("ok", screen(c))
    }

    @Test fun `oversized DCS recovery keeps subsequent CSI working`() {
        val c = core()
        val huge = ByteArray(VtParser.MAX_STRING_SEQUENCE_LENGTH + 64) { 'x'.code.toByte() }
        c.feed(byteArrayOf(0x1B, 'P'.code.toByte()))
        c.feed(huge)
        feed(c, "\u001B\\")
        feed(c, "\u001B[2J")                                   // 超限恢复后 CSI 仍正常
        feed(c, "after")
        assertEquals("after", screen(c))
    }

    @Test fun `DCS interrupted by a new escape emits discard and processes it`() {
        val c = core()
        feed(c, "\u001BPq;1;2qpayload")
        feed(c, "\u001BD")          // ESC 不是 ST → 丢弃 DCS，转而处理 ESC D (IND)
        feed(c, "Z")                // IND 后光标在 row1 → Z 落 row1
        assertEquals("Z", screen(c))
        assertEquals(1, c.snapshot().cursorRow)
    }

    @Test fun `DCS event never places characters on screen`() {
        val c = core()
        // 两种结束符组合 + 中文载荷（多字节）—— 全部丢弃。
        feed(c, "\u001BPq;q中文载荷~\u001B\\")
        assertEquals("", screen(c))
    }

    @Test fun `DCS split across feeds is still discarded`() {
        val c = core()
        feed(c, "\u001BPq;1;")
        feed(c, "2q payload-part-1")
        feed(c, " payload-part-2")
        feed(c, "\u001B\\")
        feed(c, "visible")
        assertEquals("visible", screen(c))
    }
}
