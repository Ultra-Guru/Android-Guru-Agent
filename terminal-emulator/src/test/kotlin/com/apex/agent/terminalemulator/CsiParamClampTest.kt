package com.apex.agent.terminalemulator

import org.junit.Assert.*
import org.junit.Test

/**
 * diag-20261017 崩溃热修回归（#395）—— CSI 大参数整型溢出三连：
 *
 * 真机闪退根因：终端输出流中的 `ESC[2147483647@/P/X`（解析层 parseToken
 * 恰好放行 INT32_MAX）使 ICH/DCH/ECH 的列运算有符号溢出 ——
 *  - native 引擎（C++）：memmove 负回绕 size → SIGSEGV（ASAN 宿主实证）；
 *  - Kotlin 回退引擎：`start + count` 溢出为负 → downTo 循环 engineLock 内
 *    空转 ~2^31 次（ANR/卡死）。
 *
 * 本测试锁定三层防线语义：派发层钳位（TerminalCore 'X'）、操作层饱和
 * （CsiOps.insertChars/deleteChars）、屏幕层钳位（ScreenBuffer.eraseRow）。
 * 大参数必须存活、快速返回，且常规参数语义不回归。
 */
class CsiParamClampTest {

    private fun core(rows: Int = 6, cols: Int = 10): TerminalCore = TerminalCore(rows, cols)
    private fun feed(c: TerminalCore, s: String) = c.feed(s.toByteArray())
    private fun text(c: TerminalCore, row: Int): String =
        c.renderSnapshot().lines.getOrNull(row)?.joinToString("") { it.text } ?: ""

    // ═══ 大参数存活（修复前：Kotlin 侧 ANR 级空转 / IOOBE）═══

    @Test fun `ICH with INT32_MAX saturates and survives`() {
        val c = core()
        feed(c, "abcdef")
        feed(c, "\u001B[1;1H")   // 行首
        feed(c, "\u001B[2147483647@")  // INT32_MAX 插入
        assertEquals("光标不移动（ICH 语义）", 0, c.snapshot().cursorRow)
        assertEquals("饱和插入 = 整行空白", " ".repeat(10), text(c, 0))
    }

    @Test fun `ICH with INT32_MAX at mid-row survives`() {
        val c = core()
        feed(c, "abcdefghij")
        feed(c, "\u001B[1;5H")   // col 4（0 基）
        feed(c, "\u001B[2147483647@")
        assertEquals("光标列不越界", 4, c.snapshot().cursorCol)
        assertEquals("起点前内容保留、起点起饱和补空白", "abcd      ", text(c, 0))
    }

    @Test fun `DCH with INT32_MAX saturates and survives`() {
        val c = core()
        feed(c, "abcdefghij")
        feed(c, "\u001B[1;3H")   // col 2
        feed(c, "\u001B[2147483647P")
        assertEquals("起点前内容保留、起点起全删补空白", "ab        ", text(c, 0))
    }

    @Test fun `ECH with INT32_MAX saturates and survives`() {
        val c = core()
        feed(c, "abcdefghij")
        feed(c, "\u001B[1;4H")   // col 3
        feed(c, "\u001B[2147483647X")
        assertEquals("光标不越界", 3, c.snapshot().cursorCol)
        assertEquals("起点起饱和擦除到行尾", "abc       ", text(c, 0))
    }

    @Test fun `wide-char row survives INT32_MAX ops`() {
        val c = core()
        // 中文宽字符行 + 大参数三连 —— 配对腰斩探测路径不得越界
        feed(c, "中文测试")
        feed(c, "\u001B[2147483647@")
        feed(c, "\u001B[2147483647P")
        feed(c, "\u001B[2147483647X")
        assertEquals("存活且行内无孤儿残留", 10, text(c, 0).length)
    }

    // ═══ 常规参数语义不回归 ═══

    @Test fun `normal ICH still shifts right by n`() {
        val c = core()
        feed(c, "abcdef")
        feed(c, "\u001B[1;1H")
        feed(c, "\u001B[3@")
        assertEquals("   abcdef", text(c, 0).substring(0, 9))
    }

    @Test fun `normal DCH still deletes n cells`() {
        val c = core()
        feed(c, "abcdef")
        feed(c, "\u001B[1;1H")
        feed(c, "\u001B[2P")
        assertEquals("cdef", text(c, 0).substring(0, 4))
    }

    @Test fun `normal ECH still erases to min(n, end) with margin clip`() {
        val c = core()
        feed(c, "abcdefghij")
        feed(c, "\u001B[1;4H")
        feed(c, "\u001B[2X")
        assertEquals("abc  fghij", text(c, 0))
    }

    @Test fun `normal ECH beyond line end erases to end`() {
        val c = core()
        feed(c, "abc")
        feed(c, "\u001B[1;1H")
        feed(c, "\u001B[500X")   // 超行宽 → 钳到行尾（不是溢出，是常规饱和）
        assertEquals(" ".repeat(10), text(c, 0))
    }
}
