package com.apex.agent.terminalemulator

import org.junit.Assert.*
import org.junit.Test

/**
 * v0.3 Resize 软换行重排测试 —— 任务的「Reflow」组。
 *
 * 契约（native vt_reflow.cpp 对齐；[Reflow] 已接进 TerminalCore.resize 的
 * **宽度变化**路径）：scrollback + 可见屏按接续标志拼回逻辑行 → 新宽度重排；
 * 宽字符绝不跨行拆对；样式随 cell 整体流动（右侧填充继承末格样式）；
 * 光标按「逻辑行内基元格序数」重放；高度变化走裁剪路径；备用屏不重排。
 */
class ReflowTest {

    private fun feed(c: TerminalCore, s: String) = c.feed(s.toByteArray())
    private fun text(c: TerminalCore, row: Int): String =
        c.renderSnapshot().lines.getOrNull(row)?.joinToString("") { it.text } ?: ""

    // ═══ 宽度增长：逻辑行合并 ═══

    @Test fun `growing width joins soft-wrapped lines`() {
        val c = TerminalCore(3, 5)
        feed(c, "ABCDEFGHIJ")        // row0 "ABCDE"(wrapped), row1 "FGHIJ"
        c.resize(3, 10)
        assertEquals("ABCDEFGHIJ", text(c, 0))
        assertEquals("", text(c, 1))
    }

    @Test fun `grow joins hard-newline-separated lines never merge`() {
        val c = TerminalCore(2, 5)
        feed(c, "AB\r\nCD")          // 两条独立逻辑行
        c.resize(2, 10)
        assertEquals("AB", text(c, 0))
        assertEquals("CD", text(c, 1))
    }

    // ═══ 宽度收缩：逻辑行拆分 ═══

    @Test fun `shrinking width splits lines at the new boundary`() {
        val c = TerminalCore(3, 10)
        feed(c, "ABCDEFGHIJ")
        c.resize(3, 5)
        assertEquals("ABCDE", text(c, 0))
        assertEquals("FGHIJ", text(c, 1))
    }

    @Test fun `content is never lost when shrinking`() {
        val c = TerminalCore(3, 4)
        feed(c, "0123456789")
        c.resize(3, 3)
        val all = c.scrollbackText(10).joinToString("") +
            (0 until 3).joinToString("") { text(c, it) }
        assertEquals("全部 10 个字符都保留（含溢出回灌 scrollback 的头部）", "0123456789", all)
    }

    // ═══ CJK 边界：宽字符不拆对 ═══

    @Test fun `wide char never splits across rows when shrinking`() {
        val c = TerminalCore(4, 6)
        feed(c, "AB中C")             // row0: A B 中(2列) C
        c.resize(4, 3)
        // 3 列宽：A B 后放不下「中」→ 孤儿列补空白、整对下移。
        assertEquals("AB", text(c, 0))
        assertEquals("中C", text(c, 1))
        val cells = c.renderSnapshot()
        assertTrue("row1 col0 是宽字符 lead", cells.lines[1][0].flags and RenderCell.FLAG_WIDE != 0)
    }

    @Test fun `wide pair moves down intact with orphan blank carrying style`() {
        val c = TerminalCore(4, 4)
        feed(c, "\u001B[41mAB中\u001B[0mC")   // 红底样式 + 宽字符
        c.resize(4, 3)
        // row0 = A B + 孤儿空白（继承红底样式）→ 宽字符对完整出现在 row1。
        // T88 修正：带样式的尾部空白是设计行为（reflow 契约「右侧填充继承末格
        // 样式」）—— renderRow 只裁剪 DEFAULT 样式的空白，故行文本为 "AB "。
        assertEquals("AB ", text(c, 0))
        assertEquals("中C", text(c, 1))
    }

    @Test fun `cjk-heavy line rewraps to exact new width`() {
        val c = TerminalCore(4, 8)
        feed(c, "中中中中")           // 8 列 4 宽字符
        c.resize(4, 5)
        assertEquals("中中", text(c, 0))   // 2 宽字符 = 4 列，第 5 列孤儿
        assertEquals("中中", text(c, 1))
    }

    // ═══ 样式保留 ═══

    @Test fun `cell styles survive reflow`() {
        val c = TerminalCore(3, 5)
        feed(c, "\u001B[31mAB\u001B[0mCDE")  // AB 红
        c.resize(3, 3)
        val snap = c.renderSnapshot()
        assertEquals("ABC", text(c, 0))       // 拆行：ABC | DE
        assertEquals("DE", text(c, 1))
        // T88 修正：SGR 31 是标准红（Indexed(1) → BASIC_16[1] = 0x800000），
        // 亮红（0xFF0000）是 SGR 91。旧断言把两者搞混。
        assertEquals(0xFF800000L, snap.lines[0][0].fg)
        assertEquals(0L, snap.lines[1][0].fg)
    }

    @Test fun `trailing padding inherits the last cell style`() {
        val c = TerminalCore(3, 5)
        feed(c, "\u001B[44mAB\u001B[0m")      // 蓝底 AB（行尾即样式末格）
        c.resize(3, 10)
        val snap = c.renderSnapshot()
        // 输出行右侧填充继承末格（蓝底）样式 —— xterm 的 bg 继承语义。
        assertEquals("Indexed(4) 蓝底", 0xFF000080L, snap.lines[0][0].bg)
        assertEquals("填充格继承末格背景", 0xFF000080L, snap.lines[0][9].bg)
    }

    // ═══ 光标位置 ═══

    @Test fun `cursor column is preserved when growing`() {
        val c = TerminalCore(3, 5)
        feed(c, "AB")
        val before = c.snapshot()
        c.resize(3, 10)
        val after = c.snapshot()
        assertEquals(before.cursorRow, after.cursorRow)
        assertEquals(before.cursorCol, after.cursorCol)
    }

    @Test fun `cursor tracks its cell across a wrap boundary`() {
        val c = TerminalCore(3, 5)
        feed(c, "ABCDEFG")          // row0 "ABCDE", row1 "FG" → 光标 (1,2)
        c.resize(3, 10)             // 合并成一行 → 光标 (0,7)
        val s = c.snapshot()
        assertEquals(0, s.cursorRow)
        assertEquals(7, s.cursorCol)
    }

    @Test fun `cursor survives shrinking when its line splits`() {
        val c = TerminalCore(3, 10)
        feed(c, "ABCDEF")           // 光标 (0,6)
        c.resize(3, 4)              // row0 "ABCD" row1 "EF" → 光标 (1,2)
        val s = c.snapshot()
        assertEquals(1, s.cursorRow)
        assertEquals(2, s.cursorCol)
    }

    @Test fun `wrapPending cursor wraps after reflow`() {
        val c = TerminalCore(3, 5)
        feed(c, "ABCDE")            // 打满一行 → wrapPending
        c.resize(3, 10)
        feed(c, "F")                // 仍应落在 (0,5)（悬挂换行重放）
        assertEquals(0, c.snapshot().cursorRow)
        assertEquals(6, c.snapshot().cursorCol)
    }

    // ═══ scrollback 参与 ═══

    @Test fun `scrollback lines reflow together with the screen`() {
        val c = TerminalCore(2, 5, maxScrollback = 10)
        feed(c, "AAAAAAAAAAAB")     // 12 字符：1 行滚入 scrollback + 屏上 "AB"
        assertEquals(1, c.scrollbackCount)
        c.resize(2, 10)
        assertEquals("AAAAAAAAAA", text(c, 0))
        // T88 修正：12 字符重排到 10 列 → 第二行是 "AB"（2 字符，非 "B"）。
        assertEquals("AB", text(c, 1))
        assertEquals("滚入的行已并回逻辑行（无残留重复）", 0, c.scrollbackCount)
    }

    @Test fun `hard-newline scrollback lines stay separate`() {
        val c = TerminalCore(2, 5, maxScrollback = 10)
        feed(c, "AAAAA\r\nBBBBB\r\nCCCCC")   // 1 行入 scrollback，屏上 B/C 行
        c.resize(2, 10)
        // T88 修正：3 条逻辑行重排到 10 列仍是 3 行；屏 2 行只能容纳后两条
        //（首条 AAAAA 溢出回灌 scrollback；光标锚定 C 行在屏内）。旧断言
        // 期望 BBBBB 同时出现在 scrollback 与屏上 —— 自相矛盾。
        assertEquals("硬换行不合并：首行溢出回灌 scrollback", 1, c.scrollbackCount)
        assertEquals(listOf("AAAAA"), c.scrollbackText(10))
        assertEquals("BBBBB", text(c, 0))
        assertEquals("CCCCC", text(c, 1))
    }

    @Test fun `overflowing reflow spills the head back into scrollback`() {
        val c = TerminalCore(2, 5, maxScrollback = 10)
        feed(c, "12345\r\n67890\r\nabcde")   // "12345" 入 scrollback；屏上 67890/abcde
        c.resize(2, 3)                           // 每条逻辑行拆成 2 行 → 溢出回灌
        assertEquals("拆分行溢出回灌 scrollback（光标行留屏）", 4, c.scrollbackCount)
        assertEquals(listOf("123", "45", "678", "90"), c.scrollbackText(10))
        assertEquals("abc", text(c, 0))
        assertEquals("de", text(c, 1))
    }

    // ═══ 备用屏 / 高度变化 / reflow 关闭 ═══

    @Test fun `alt screen never reflows on width change`() {
        val c = TerminalCore(3, 10)
        feed(c, "\u001B[?1049h")
        feed(c, "ABCDEFGHIJ")       // 备用屏 row0
        c.resize(3, 5)
        assertEquals("备用屏裁剪而非重排", "ABCDE", text(c, 0))
        assertEquals("", text(c, 1))
    }

    @Test fun `height-only change uses the crop path`() {
        val c = TerminalCore(3, 5)
        feed(c, "A\r\nB\r\nC")
        c.resize(2, 5)
        assertEquals("顶行滚入 scrollback", "A", c.scrollbackText(10)[0])
        assertEquals("B", text(c, 0))
        assertEquals("C", text(c, 1))
    }

    @Test fun `reflow disabled falls back to crop`() {
        val c = TerminalCore(3, 5, reflowOnResize = false)
        feed(c, "ABCDEFGHIJ")
        c.resize(3, 3)
        // T88 修正：裁剪语义 = 每行保左上角 → 第二行是原 row1 的前 3 列
        //（"FGH"），不是 row0 的后续段（"DEF" —— 那是 reflow 语义）。
        assertEquals("不做软换行重排：保左上角裁剪", "ABC", text(c, 0))
        assertEquals("FGH", text(c, 1))
    }

    @Test fun `wrapped flags are recomputed after reflow`() {
        val c = TerminalCore(3, 5)
        feed(c, "ABCDEFGHIJ")
        c.resize(3, 10)
        c.resize(3, 5)              // 再缩回去 → 又是两条软换行行
        assertEquals("ABCDE", text(c, 0))
        assertEquals("FGHIJ", text(c, 1))
        // 再增长一次 → 内容仍完整（双向重排幂等）。
        c.resize(3, 10)
        assertEquals("ABCDEFGHIJ", text(c, 0))
    }
}
