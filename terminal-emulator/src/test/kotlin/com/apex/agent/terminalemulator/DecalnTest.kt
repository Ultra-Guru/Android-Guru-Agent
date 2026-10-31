package com.apex.agent.terminalemulator

import org.junit.Assert.*
import org.junit.Test

/**
 * v0.3 DECALN（ESC # 8）测试 —— 任务的「DECALN」组。
 *
 * xterm 语义：全屏填 'E'；滚区/左右边距复位；光标归位 (0,0)；
 * 清除 wrapPending 与既有内容（含接续标志断链）。
 */
class DecalnTest {

    private fun feed(c: TerminalCore, s: String) = c.feed(s.toByteArray())
    private fun text(c: TerminalCore, row: Int): String =
        c.renderSnapshot().lines.getOrNull(row)?.joinToString("") { it.text } ?: ""

    @Test fun `fills every row and column with E`() {
        val c = TerminalCore(3, 5)
        feed(c, "\u001B#8")
        for (r in 0 until 3) assertEquals("EEEEE", text(c, r))
    }

    @Test fun `previous content is fully replaced`() {
        val c = TerminalCore(2, 8)
        feed(c, "old content here")
        feed(c, "\u001B#8")
        assertEquals("EEEEEEEE", text(c, 0))
        assertEquals("EEEEEEEE", text(c, 1))
    }

    @Test fun `homes cursor and clears wrap pending`() {
        val c = TerminalCore(3, 4)
        feed(c, "ABCD")              // 打满一行 → wrapPending
        feed(c, "\u001B#8")
        assertEquals(0, c.snapshot().cursorRow)
        assertEquals(0, c.snapshot().cursorCol)
        feed(c, "X")                  // 不应再折行
        assertEquals(0, c.snapshot().cursorRow)
        assertEquals(1, c.snapshot().cursorCol)
        assertEquals("XEEE", text(c, 0))
    }

    @Test fun `resets scroll region`() {
        val c = TerminalCore(4, 6)
        feed(c, "\u001B[2;3r")        // 滚区 [1..2]
        feed(c, "\u001B#8")
        // 滚区复位 → 底行 LF 滚屏、光标钉在底行。
        feed(c, "\u001B[4;1H\n")
        assertEquals("滚区已复位：光标钉底行", 3, c.snapshot().cursorRow)
        // T88 修正：LF 触发滚屏后底行被清为空白（滚屏语义 = 内容上移、底行
        // 重置），不是保留 E 行。
        assertEquals("滚屏后底行为空白", "", text(c, 3))
    }

    @Test fun `resets left-right margins`() {
        val c = TerminalCore(3, 10)
        feed(c, "\u001B[?69h\u001B[2;8s")
        feed(c, "\u001B#8")
        feed(c, "\u001B[1;10H")
        assertEquals("边距复位 → 可到最右列", 9, c.snapshot().cursorCol)
    }

    @Test fun `on alternate screen fills the alternate buffer only`() {
        val c = TerminalCore(3, 5)
        feed(c, "main!")
        feed(c, "\u001B[?1049h")     // 进备用屏（主屏内容保留在 mainBuffer）
        feed(c, "\u001B#8")
        for (r in 0 until 3) assertEquals("EEEEE", text(c, r))
        feed(c, "\u001B[?1049l")     // 退回主屏
        assertEquals("主屏内容未被动过", "main!", text(c, 0))
    }

    @Test fun `E cells carry default style`() {
        val c = TerminalCore(1, 4)
        feed(c, "\u001B[31;44m")
        feed(c, "\u001B#8")
        val cell = c.renderSnapshot().lines[0][0]
        assertEquals(0L, cell.fg)
        assertEquals(0L, cell.bg)
        assertEquals("E", cell.text)
    }

    @Test fun `stale soft-wrap chains are broken by DECALN`() {
        val c = TerminalCore(3, 4)
        feed(c, "ABCDEFGH")           // 两条软换行行
        feed(c, "\u001B#8")
        c.resize(3, 8)                // 若接续标志残留，E 行会被错误拼接成一行
        // T88 修正：接续标志已被 DECALN 断链 → 3 条独立逻辑行各自重排到
        // 8 列（EEEE + 空白填充），不会拼成 8 个 E 的一行。
        assertEquals("EEEE", text(c, 0))
        assertEquals("EEEE", text(c, 1))
        assertEquals("EEEE", text(c, 2))
    }
}
