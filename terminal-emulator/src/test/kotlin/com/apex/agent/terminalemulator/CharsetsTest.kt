package com.apex.agent.terminalemulator

import org.junit.Assert.*
import org.junit.Test

/**
 * v0.3 字符集测试 —— G0–G3 指定 + SI/SO 移位 + DECSC/DECRC 快照 +
 * RIS/DECSTR 复位 + 序列化往返（任务的「Charsets」组）。
 *
 * 语义（xterm / Termux 对齐）：
 *  - `ESC ( X` / `ESC ) X` / `ESC * X` / `ESC + X` 指定 G0/G1/G2/G3；
 *  - SO(0x0E) 移入 G1、SI(0x0F) 移回 G0 —— 之后的可打印字符按 GL 槽重映射；
 *  - DEC graphics（`0`）：制图线；UK(`A`)：#→£；German(`K`)；French(`R`)；
 *  - 表外码点恒等（NRC 只重定义列间少数位置）。
 */
class CharsetsTest {

    private fun core(): TerminalCore = TerminalCore(4, 10)
    private fun feed(c: TerminalCore, s: String) = c.feed(s.toByteArray())
    private fun rowText(c: TerminalCore, row: Int = 0): String =
        c.renderSnapshot().lines.getOrNull(row)?.joinToString("") { it.text } ?: ""

    // ═══ 指定 + SI/SO ═══

    @Test fun `default charset is ASCII passthrough`() {
        val c = core()
        feed(c, "qj")
        assertEquals("qj", rowText(c))
    }

    @Test fun `G0 DEC graphics designation remaps box drawing`() {
        val c = core()
        feed(c, "\u001B(0")            // G0 = DEC Special Graphics
        feed(c, "qjkl")
        // T88 修正：q(0x71)→U+2500 横线（xterm DECgraphics 表；旧断言误写 0x2502 竖线，
        // 竖线是 x(0x78) 的映射）。
        assertEquals("─┘┐┌", rowText(c))   // 0x2500/0x2518/0x2510/0x250C
    }

    @Test fun `redesignating G0 back to ASCII restores passthrough`() {
        val c = core()
        feed(c, "\u001B(0q\u001B(Bq")
        assertEquals("─q", rowText(c))
    }

    @Test fun `SO shifts to G1 and SI shifts back to G0`() {
        val c = core()
        feed(c, "\u001B)0")            // G1 = DEC graphics（G0 仍 ASCII）
        feed(c, "a")
        feed(c, "\u000E")              // SO → G1
        feed(c, "a")
        feed(c, "\u000F")              // SI → G0
        feed(c, "a")
        assertEquals("a▒a", rowText(c))    // 'a' 在 graphics 表 = 0x2592
    }

    @Test fun `G1 designated via ESC-paren-1 works through SO`() {
        val c = core()
        feed(c, "\u001B)K\u000E")      // G1 = German NRC + SO
        feed(c, "[")                   // 0x5B → Ä
        assertEquals("Ä", rowText(c))
    }

    @Test fun `double SO stays in G1 and double SI stays in G0`() {
        val c = core()
        feed(c, "\u001B)0\u000E\u000E")   // SO 两次仍 G1
        feed(c, "q")
        feed(c, "\u000F\u000F")            // SI 两次仍 G0
        feed(c, "q")
        assertEquals("─q", rowText(c))
    }

    @Test fun `G2 and G3 designation slots are stored`() {
        val c = core()
        feed(c, "\u001B*0\u001B+0")    // G2 / G3 = DEC graphics（指定即可）
        feed(c, "q")
        assertEquals("指定不生效移位 → 仍 ASCII", "q", rowText(c))
    }

    @Test fun `unknown designator final byte keeps current designation`() {
        val c = core()
        feed(c, "\u001B(0")
        feed(c, "\u001B(X")            // 未知 final → 忽略，G0 仍 graphics
        feed(c, "q")
        assertEquals("─", rowText(c))
    }

    @Test fun `non-designator ESC intermediates fall through safely`() {
        val c = core()
        feed(c, "\u001B#8")            // DECALN —— 不是字符集指定
        assertEquals("EEEEEEEEEE", rowText(c))
    }

    // ═══ NRC 表格矩阵 ═══

    @Test fun `UK NRC maps hash to pound`() {
        val c = core()
        feed(c, "\u001B(A")
        feed(c, "#z")
        assertEquals("£z", rowText(c))     // 仅 # 替换，其余恒等
    }

    @Test fun `German NRC maps the full 8-code table`() {
        val c = core()
        feed(c, "\u001B(K")
        // T88 修正：原 feed 串缺 '}'（第 7 槽）—— 8 槽 DIN 66003 表需要完整输入。
        feed(c, "@[\\]{|}~")
        assertEquals("§ÄÖÜäöüß", rowText(c))
    }

    @Test fun `French NRC maps its table`() {
        val c = core()
        feed(c, "\u001B(R")
        // T88 修正：同上 —— 原串缺 '}'（→ è）。
        feed(c, "@[\\]{|}~")
        assertEquals("à°ç§éùè¨", rowText(c))
    }

    @Test fun `DEC graphics outside the table passes through`() {
        val c = core()
        feed(c, "\u001B(0")
        feed(c, "AZ09")               // 大写/数字不在替换表
        assertEquals("AZ09", rowText(c))
    }

    @Test fun `SO with undesignated G1 is ASCII`() {
        val c = core()
        feed(c, "\u000E")             // SO 但 G1 仍是 ASCII 缺省
        feed(c, "q")
        assertEquals("q", rowText(c))
    }

    // ═══ DECSC / DECRC 快照 ═══

    @Test fun `DECRC restores the shifted slot`() {
        val c = core()
        feed(c, "\u001B)0\u000E")     // G1 graphics + SO（GL=G1）
        feed(c, "\u001B7")            // DECSC：保存字符集状态
        feed(c, "\u000F")             // SI → G0
        feed(c, "\u001B8")            // DECRC：恢复 GL=G1
        feed(c, "q")
        assertEquals("─", rowText(c))
    }

    @Test fun `DECRC restores designations not just the shift`() {
        val c = core()
        feed(c, "\u001B(0\u001B7")    // G0 = graphics，保存
        feed(c, "\u001B(B")           // G0 改回 ASCII
        feed(c, "\u001B8")            // 恢复 → G0 = graphics
        feed(c, "q")
        assertEquals("─", rowText(c))
    }

    @Test fun `CSI s SCOSC does not save charset state`() {
        val c = core()
        feed(c, "\u001B)0\u000E")
        feed(c, "\u001B[s")           // SCOSC 只存光标/样式（边距关）
        feed(c, "\u000F")
        feed(c, "\u001B[u")
        feed(c, "q")
        assertEquals("SCOSC 不存字符集 → SI 生效", "q", rowText(c))
    }

    // ═══ 复位 ═══

    @Test fun `RIS resets designations and shift`() {
        val c = core()
        feed(c, "\u001B(0\u000E")
        feed(c, "\u001Bc")            // RIS
        feed(c, "q")
        assertEquals("q", rowText(c))
    }

    @Test fun `DECSTR resets charsets`() {
        val c = core()
        feed(c, "\u001B(0\u000E")
        feed(c, "\u001B[!p")          // DECSTR
        feed(c, "q")
        assertEquals("q", rowText(c))
    }

    // ═══ 序列化（toArray / fromArray）═══

    @Test fun `charset state round-trips through serialization arrays`() {
        val cs = CharsetState()
        cs.designate(0, CharsetName.DEC_GRAPHICS)
        cs.designate(1, CharsetName.GERMAN)
        cs.designate(2, CharsetName.FRENCH)
        cs.designate(3, CharsetName.UK)
        cs.shiftOut()
        cs.save()
        val restored = CharsetState().also { it.fromArray(cs.toArray()) }
        // T88 修正：shift=1（SO）时 active 是 G1=GERMAN；G0 检查先 shiftIn。
        assertEquals(CharsetName.GERMAN, restored.active())
        restored.shiftIn()
        assertEquals(CharsetName.DEC_GRAPHICS, restored.active())
        restored.shiftOut()
        assertEquals(CharsetName.GERMAN, restored.active())
    }

    @Test fun `saved DECSC snapshot round-trips`() {
        val cs = CharsetState()
        cs.designate(1, CharsetName.DEC_GRAPHICS)
        cs.shiftOut()
        cs.save()
        // T88 修正：不能用 reset() 打散 —— RIS 语义上连 DECSC 快照一起清
        //（xterm：RIS 后无可恢复的保存光标）。改为手动打散后验证快照恢复。
        cs.shiftIn()
        cs.designate(1, CharsetName.ASCII)
        cs.restore()
        assertEquals(CharsetName.DEC_GRAPHICS, cs.active())   // 恢复 shift=1 + G1 指定
    }

    @Test fun `fromArray with garbage resets to ASCII safely`() {
        val cs = CharsetState()
        cs.fromArray(intArrayOf(99, -5, 99, 99, 7))   // 全非法序数
        assertEquals(CharsetName.ASCII, cs.active())
        assertEquals(0, cs.shift)
    }

    @Test fun `fromArray with short array resets to defaults`() {
        val cs = CharsetState()
        cs.designate(0, CharsetName.UK)
        cs.fromArray(intArrayOf(1, 2))               // 长度不足 → reset
        assertEquals(CharsetName.ASCII, cs.active())
    }
}
