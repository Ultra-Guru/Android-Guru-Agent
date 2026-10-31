package com.apex.agent.terminalemulator

import org.junit.Assert.*
import org.junit.Test

/**
 * v0.3 边距测试 —— DECLRMM（CSI ?69h/l）+ DECSLRM（CSI Pl;Pr s）全语义：
 * 光标钳制、IL/DL/ICH/DCH/ECH/EL 边距窗口裁剪、SU/SD 区间滚动、
 * IND/RI 触区滚屏、`CSI s` 双义（SCOSC vs DECSLRM）、DECALN 复位边距。
 *
 * 语义基准（T88 修复后，逐条对照 xterm ctlseqs / DEC STD 070 / Termux）：
 *  - `CSI ?69h` 单独出现不裁剪任何东西（边距 = 全屏宽）；关闭同。
 *  - DECSLRM 有效 → 光标归位到**左边距**（绝不停在窗口外）；
 *    无效（left >= right / 越界）→ 整条忽略，光标不动。
 *  - 打印在右边距处软换行、回到左边距（不是列 0）。
 *  - IND/RI/LF 滚屏作用域 = 垂直滚区 ∩ 左右边距窗口（Termux 四参数
 *    scrollScreen 语义）。
 */
class MarginModeTest {

    private fun core(rows: Int = 6, cols: Int = 10): TerminalCore = TerminalCore(rows, cols)
    private fun feed(c: TerminalCore, s: String) = c.feed(s.toByteArray())
    private fun text(c: TerminalCore, row: Int): String =
        c.renderSnapshot().lines.getOrNull(row)?.joinToString("") { it.text } ?: ""

    // ═══ 模式开关 + DECSLRM ═══

    @Test fun `DECLRMM off makes CSI s save cursor (legacy behavior)`() {
        val c = core()
        feed(c, "\u001B[2;3H")
        feed(c, "\u001B[s")            // 边距关闭 → SCOSC
        feed(c, "\u001B[1;1H")
        feed(c, "\u001B[u")            // 恢复到 (2,3)
        val s = c.snapshot()
        assertEquals(1, s.cursorRow)   // 0 基
        assertEquals(2, s.cursorCol)
    }

    @Test fun `DECSLRM sets margins and homes cursor to LEFT margin`() {
        val c = core()
        feed(c, "\u001B[?69h")         // DECLRMM on
        feed(c, "\u001B[3;5;H")        // 光标移走
        feed(c, "\u001B[2;8s")         // 左边距 col2、右边距 col8（1 基）
        val s = c.snapshot()
        assertEquals("DECSLRM 归位光标到行首", 0, s.cursorRow)
        // xterm/Termux：归位到**左边距**（0 基 col1），不是列 0 —— 光标恒在
        // 边距窗口内。旧实现归列 0 会让后续可打印字符落在窗口外。
        assertEquals("归位到左边距（不是列 0）", 1, s.cursorCol)
    }

    @Test fun `DECSLRM ignored when DECLRMM off`() {
        val c = core()
        feed(c, "\u001B[3;5H")
        feed(c, "\u001B[2;8s")         // 无 69 → 保存光标（不设边距）
        // 验证：光标仍可在全宽移动（边距没生效）
        feed(c, "\u001B[1;9H")
        assertEquals(8, c.snapshot().cursorCol)
    }

    @Test fun `invalid DECSLRM params are ignored`() {
        val c = core()
        feed(c, "\u001B[?69h")
        feed(c, "\u001B[3;5;H")
        feed(c, "\u001B[5;2s")         // left >= right → 整条忽略，光标不动
        assertEquals(2, c.snapshot().cursorRow)
        assertEquals(4, c.snapshot().cursorCol)
        // 越界参数同样是整条忽略（xterm：不 coerce 进合法域）。
        feed(c, "\u001B[12;20s")       // 12/20 都在 10 列屏外 → 忽略
        assertEquals(4, c.snapshot().cursorCol)
        // 无效设置不改变已生效边距：`CSI 1;4H` 后 20C 仍被右边距 col4 钳住
        //（若 12;20s 生效了窗口会被改写）。
        feed(c, "\u001B[2;5s")         // 有效：窗口 [1..4]
        feed(c, "\u001B[5;1s")         // 又一条无效 → 边距维持 [1..4]
        feed(c, "\u001B[1;1H\u001B[20C")
        assertEquals("边距未被无效序列改写", 4, c.snapshot().cursorCol)
    }

    @Test fun `DECLRMM disable releases margins`() {
        val c = core()
        feed(c, "\u001B[?69h\u001B[2;8s")
        feed(c, "\u001B[?69l")         // 关闭 → 边距即刻失效
        feed(c, "\u001B[1;10H")        // 可以到最右列
        assertEquals(9, c.snapshot().cursorCol)
    }

    // ═══ 光标钳制 ═══

    @Test fun `cursor movement clamps to right margin`() {
        val c = core()
        feed(c, "\u001B[?69h\u001B[2;5s")   // 边距 [1..4]（0 基）
        feed(c, "\u001B[1;1H\u001B[20C")    // 右移 20 → 只能到 col4
        assertEquals(4, c.snapshot().cursorCol)
    }

    @Test fun `cursor movement clamps to left margin`() {
        val c = core()
        feed(c, "\u001B[?69h\u001B[2;5s")
        feed(c, "\u001B[1;4H\u001B[20D")    // 左移 20 → 只能到 col1
        assertEquals(1, c.snapshot().cursorCol)
    }

    @Test fun `CR returns to left margin not column 0`() {
        val c = core()
        feed(c, "\u001B[?69h\u001B[2;5s")
        feed(c, "\u001B[1;4HX")              // 在边距内打印
        feed(c, "\r")                        // CR → 左边距（0 基 col1）
        assertEquals(1, c.snapshot().cursorCol)
    }

    @Test fun `printing wraps at right margin and returns to left margin`() {
        val c = core(cols = 10)
        feed(c, "\u001B[?69h\u001B[2;5s")    // 可打印窗口 4 列（col1..col4）
        feed(c, "ABCDE")                     // 第 5 个字符应在边距处折行
        val snap = c.renderSnapshot()
        // 光标经 DECSLRM 归位到左边距 col1 → A..D 落 col1..4（col0 是窗口外
        // 的空白）；E 折行后回到**左边距 col1**（不是列 0）—— 行首空格正是
        // “回到左边距而非列 0”的直接证据。
        assertEquals(" ABCD", snap.lines[0].joinToString("") { it.text })
        assertEquals(" E", snap.lines[1].joinToString("") { it.text })
    }

    // ═══ EL / ECH 边距裁剪 ═══

    @Test fun `EL 2 erases only within margins`() {
        val c = core(cols = 10)
        feed(c, "0123456789")
        feed(c, "\u001B[?69h\u001B[2;9s")   // 窗口 col1..col8（0 基）
        feed(c, "\u001B[1;2H\u001B[2K")     // EL2 → 只清窗口内
        val t = text(c, 0)
        assertEquals("col0 与 col9 必须保留", "0        9", t)
    }

    @Test fun `ECH erase stops at right margin`() {
        val c = core(cols = 10)
        feed(c, "0123456789")
        feed(c, "\u001B[?69h\u001B[2;6s")   // 窗口 col1..col5
        feed(c, "\u001B[1;2H\u001B[10X")    // ECH 10 → 只到 col5
        assertEquals("0     6789", text(c, 0))
    }

    // ═══ ICH / DCH 边距裁剪 ═══

    @Test fun `ICH shifts cells only within margins`() {
        val c = core(cols = 10)
        feed(c, "0123456789")
        feed(c, "\u001B[?69h\u001B[2;6s")   // 窗口 col1..col5
        feed(c, "\u001B[1;2H\u001B[2@")     // 在 col1 插 2 空格
        // 窗口内右移、col6..9 原封不动；col5 的 '5' 被推出窗口（丢弃）
        assertEquals("0  1236789", text(c, 0))
    }

    @Test fun `DCH deletes cells only within margins`() {
        val c = core(cols = 10)
        feed(c, "0123456789")
        feed(c, "\u001B[?69h\u001B[2;6s")
        feed(c, "\u001B[1;2H\u001B[2P")     // 在 col1 删 2 格
        // 窗口内左移 + 窗口右缘补空；窗口外（col0/col6..9）不动
        assertEquals("0345  6789", text(c, 0))
    }

    // ═══ SU / SD 区间滚动 ═══

    @Test fun `SU scrolls only margin columns`() {
        val c = core(rows = 3, cols = 6)
        feed(c, "ABCDEF\r\nGHIJKL\r\nMNOPQR")
        feed(c, "\u001B[?69h\u001B[2;4s")   // 列窗口 col1..col3
        feed(c, "\u001B[S")                 // 上滚 1 行（只有窗口列动）
        assertEquals("AHIJEF", text(c, 0))  // row1 窗口内容上移，窗口外列不动
        assertEquals("MNOPQR".replace("NOP", "   "), text(c, 2))  // 底行窗口列清空
    }

    // ═══ IL / DL 边距 ═══

    @Test fun `DL deletes lines within margin columns only`() {
        val c = core(rows = 3, cols = 6)
        feed(c, "ABCDEF\r\nGHIJKL\r\nMNOPQR")
        feed(c, "\u001B[?69h\u001B[2;4s")   // 列窗口 col1..col3
        feed(c, "\u001B[1;2H\u001B[M")      // row0 起删 1 行（窗口列）
        // 窗口列整体上移一行；窗口外列（col0/col4..5）不动
        assertEquals("AHIJEF", text(c, 0))
        assertEquals("GNOPKL", text(c, 1))
        assertEquals("M   QR", text(c, 2))
    }

    // ═══ IND / RI 触区滚屏（垂直滚区 + 边距列）═══

    @Test fun `IND at scroll region bottom scrolls margin window`() {
        val c = core(rows = 3, cols = 6)
        feed(c, "\u001B[1;2r")              // 垂直滚区 rows 0..1
        feed(c, "ABCDEF\r\nGHIJKL")       // row0/row1
        feed(c, "\u001B[?69h\u001B[2;4s")
        // DECSLRM 归位光标（xterm）会离开滚区底行 —— 显式把光标放回
        // 滚区底行（CUP 列被左边距钳到 col1），保持测试前提「IND 在滚区底」。
        feed(c, "\u001B[2;2H")
        feed(c, "\u001BD")                  // IND → 滚区滚 1 行（窗口列）
        assertEquals("AHIJEF", text(c, 0))  // row1 窗口内容上移
    }

    @Test fun `RI at scroll region top scrolls margin window down`() {
        val c = core(rows = 3, cols = 6)
        feed(c, "ABCDEF\r\nGHIJKL")
        feed(c, "\u001B[?69h\u001B[2;4s")  // 归位到 (0,1) → 光标恰在屏顶
        feed(c, "\u001BM")                  // RI 在屏顶 → 下滚（窗口列）
        assertEquals("A   EF", text(c, 0))  // 顶行窗口列被清空
        assertEquals("GBCDKL", text(c, 1))
    }

    // ═══ DECALN ═══

    @Test fun `DECALN fills screen with E and homes cursor`() {
        val c = core(rows = 3, cols = 5)
        feed(c, "abc")
        feed(c, "\u001B#8")
        val snap = c.renderSnapshot()
        assertEquals("EEEEE", snap.lines[0].joinToString("") { it.text })
        assertEquals("EEEEE", snap.lines[1].joinToString("") { it.text })
        assertEquals(0, c.snapshot().cursorRow)
        assertEquals(0, c.snapshot().cursorCol)
    }

    @Test fun `DECALN resets margins and scroll region`() {
        val c = core(rows = 4, cols = 10)
        feed(c, "\u001B[2;3r\u001B[?69h\u001B[2;8s")
        feed(c, "\u001B#8")
        // 边距复位 → 光标可到 (0, 9)；滚区复位 → 底行 LF 会滚全屏
        feed(c, "\u001B[1;10H")
        assertEquals(9, c.snapshot().cursorCol)
        feed(c, "\u001B[4;1H\n")
        assertEquals("滚区复位：底行 LF 滚屏后仍在底行", 3, c.snapshot().cursorRow)
    }

    // ═══ 边距补充矩阵（T88 完成轮新增）═══

    @Test fun `enabling DECLRMM alone keeps full-width margins`() {
        val c = core(cols = 10)
        feed(c, "\u001B[?69h")             // 只开模式，不设 DECSLRM
        feed(c, "\u001B[1;10H")            // 可到最右列（边距 = 全宽）
        assertEquals(9, c.snapshot().cursorCol)
        // 全宽边距下打印正常换行（在屏右缘 col9 悬挂换行）
        feed(c, "\u001B[1;10H")
        feed(c, "XY")
        val snap = c.renderSnapshot()
        assertEquals("X 在 col9", "         X", snap.lines[0].joinToString("") { it.text })
        assertEquals("Y 折行回列 0（左边距=0）", "Y", snap.lines[1].joinToString("") { it.text })
    }

    @Test fun `BS stops at left margin`() {
        val c = core(cols = 10)
        feed(c, "\u001B[?69h\u001B[2;5s")   // 窗口 [1..4]
        feed(c, "\u001B[1;3H")
        feed(c, "\b\b\b\b")                 // 退 4 格 → 停在左边距 col1
        assertEquals(1, c.snapshot().cursorCol)
    }

    @Test fun `LF at region bottom scrolls only margin columns`() {
        val c = core(rows = 3, cols = 6)
        feed(c, "ABCDEF\r\nGHIJKL\r\nMNOPQR")
        feed(c, "\u001B[?69h\u001B[2;4s")   // 窗口 [1..3]；归位 (0,1)
        feed(c, "\u001B[3;2H")              // 底行（滚区=全屏）
        feed(c, "\n")                       // LF 触底 → 窗口列上滚
        assertEquals("AHIJEF", text(c, 0))
        assertEquals("GNOPKL", text(c, 1))
        assertEquals("M   QR", text(c, 2))
    }

    @Test fun `NEL moves to next line and resets to left margin`() {
        val c = core(rows = 4, cols = 10)
        feed(c, "\u001B[?69h\u001B[3;7s")   // 窗口 [2..6]
        feed(c, "\u001B[2;5HX")
        feed(c, "\u001BE")                  // NEL：下移 + 回左边距 col2
        val s = c.snapshot()
        assertEquals(2, s.cursorRow)        // 从 row1 下移到 row2
        assertEquals(2, s.cursorCol)
    }

    @Test fun `wide char wraps as a pair at right margin`() {
        val c = core(cols = 6)
        feed(c, "\u001B[?69h\u001B[2;5s")   // 窗口 [1..4]（4 列）
        feed(c, "AB中C")                    // A B 中(2列) 占满 col1..4 → C 折行
        val snap = c.renderSnapshot()
        assertEquals(" AB中", snap.lines[0].joinToString("") { it.text })
        assertEquals("宽字符整对折行，不拆对", " C", snap.lines[1].joinToString("") { it.text })
    }

    @Test fun `ED 2 is not clipped by left-right margins`() {
        val c = core(rows = 2, cols = 10)
        feed(c, "0123456789")
        feed(c, "\u001B[?69h\u001B[2;9s")   // 窗口 [1..8]
        feed(c, "\u001B[2J")                // ED 整屏语义（xterm/DEC STD 070）
        assertEquals("", text(c, 0))
    }

    @Test fun `origin mode CUP is relative to left margin`() {
        val c = core(rows = 4, cols = 10)
        feed(c, "\u001B[?69h\u001B[2;9s")   // 窗口 [1..8]
        feed(c, "\u001B[2;3r")              // 滚区 [1..2]
        feed(c, "\u001B[?6h")               // DECOM on
        feed(c, "\u001B[1;1H")              // 原点 = (top=1, left=1)
        val s = c.snapshot()
        assertEquals(1, s.cursorRow)
        assertEquals(1, s.cursorCol)
        feed(c, "\u001B[1;2H")              // 原点 +1 列
        assertEquals(2, c.snapshot().cursorCol)
    }

    @Test fun `single-param DECSLRM defaults right margin to screen edge`() {
        val c = core(cols = 10)
        feed(c, "\u001B[?69h")
        feed(c, "\u001B[4s")                // 左边距 col4，右边距缺省 = 10 列
        feed(c, "\u001B[1;10H")
        assertEquals("右界 = 屏右缘", 9, c.snapshot().cursorCol)
        feed(c, "\u001B[20D")
        assertEquals("左界 = col3", 3, c.snapshot().cursorCol)
    }

    @Test fun `cursor position survives margin enable clamp`() {
        val c = core(cols = 10)
        feed(c, "\u001B[?69h\u001B[2;8s")   // 窗口 [1..7]，光标归位 (0,1)
        feed(c, "\u001B[1;5H")
        feed(c, "\u001B[?69l")              // 关闭 → 全宽（光标不动）
        assertEquals(4, c.snapshot().cursorCol)
        feed(c, "\u001B[1;10H")
        assertEquals(9, c.snapshot().cursorCol)
    }

    // ═══ DECRQM 边距模式应答（模式集成验证一条；全模式矩阵见 DecrqmTest）═══

    @Test fun `DECRQM reports DECLRMM state`() {
        val c = core()
        val replies = mutableListOf<ByteArray>()
        c.responseSink = { replies.add(it) }
        feed(c, "\u001B[?69\$p")
        // xterm ctlseqs / DEC STD 070 / native vt_engine.cpp decrqm()：
        // DECRPM 状态 0 = 未识别、1 = 置位、**2 = 复位**。
        assertEquals("\u001B[?69;2\$y", String(replies.last(), Charsets.US_ASCII))
        feed(c, "\u001B[?69h\u001B[?69\$p")
        assertEquals("\u001B[?69;1\$y", String(replies.last(), Charsets.US_ASCII))
    }
}
