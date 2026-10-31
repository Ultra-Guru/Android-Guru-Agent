package com.apex.agent.terminalemulator

import org.junit.Assert.*
import org.junit.Test

/**
 * v0.3 DECRQM/DECRPM 全模式矩阵 + DECREQTPARM —— 任务的「模式应答」组测试。
 *
 * 状态码语义（xterm ctlseqs / DEC STD 070 / native vt_engine.cpp decrqm()
 * 三方一致，作为断言基准）：
 *   `CSI ? N $ p` → `CSI ? N ; Pm $ y`，Pm：0 = 未识别 / 1 = 置位 / 2 = 复位。
 * （任务描述文字里的「set→2/reset→0/unknown→1」是笔误 —— 详见
 * [TerminalReports] 文件头注释。）
 *
 * 覆盖：DECAWM 7 / DECTCEM 25 / DECSCNM 5 / DECCKM 1 / DECOM 6 / DECLRMM 69 /
 * 2004 / 47 / 1047 / 1049 / 9 / 1000 / 1001未实现→0 / 1002 / 1003 / 1005 /
 * 1006 / 1015 / 1016 / 1004 / 1007 / 10060 / IRM 4（ANSI）/ LNM 20（ANSI）/
 * 未知模式 / DECSTR 软复位后查询 / DECREQTPARM。
 */
class DecrqmTest {

    private fun core(rows: Int = 4, cols: Int = 20): TerminalCore = TerminalCore(rows, cols)

    /** 收集 responseSink 应答（ASCII 解码）。 */
    private class Sink {
        val replies = mutableListOf<String>()
        fun attach(c: TerminalCore) {
            c.responseSink = { replies.add(String(it, Charsets.US_ASCII)) }
        }
        fun last(): String = replies.last()
        fun count(): Int = replies.size
    }

    /** 查询私有模式 → 期待应答 `CSI ?mode;status$y`。 */
    private fun askPrivate(c: TerminalCore, s: Sink, mode: Int): String {
        c.feed("\u001B[?$mode\$p".toByteArray())
        return s.last()
    }

    private fun askAnsi(c: TerminalCore, s: Sink, mode: Int): String {
        c.feed("\u001B[$mode\$p".toByteArray())
        return s.last()
    }

    private fun setPrivate(c: TerminalCore, mode: Int, on: Boolean) =
        c.feed("\u001B[?$mode${if (on) 'h' else 'l'}".toByteArray())

    // ═══ DEC 私有模式：开 → 1 / 关 → 2 ═══

    @Test fun `DECRQM DECCKM 1 reports set and reset`() {
        val c = core(); val s = Sink(); s.attach(c)
        assertEquals("\u001B[?1;2\$y", askPrivate(c, s, 1))
        setPrivate(c, 1, true)
        assertEquals("\u001B[?1;1\$y", askPrivate(c, s, 1))
        setPrivate(c, 1, false)
        assertEquals("\u001B[?1;2\$y", askPrivate(c, s, 1))
    }

    @Test fun `DECRQM DECSLRM insert-style mode 4 private mirror`() {
        // DECSET 4 == ANSI IRM：私有查询面也镜像 insertMode（xterm 行为）。
        val c = core(); val s = Sink(); s.attach(c)
        assertEquals("\u001B[?4;2\$y", askPrivate(c, s, 4))
        setPrivate(c, 4, true)
        assertEquals("\u001B[?4;1\$y", askPrivate(c, s, 4))
    }

    @Test fun `DECRQM DECSCNM 5 reports set and reset`() {
        val c = core(); val s = Sink(); s.attach(c)
        setPrivate(c, 5, true)
        assertEquals("\u001B[?5;1\$y", askPrivate(c, s, 5))
        setPrivate(c, 5, false)
        assertEquals("\u001B[?5;2\$y", askPrivate(c, s, 5))
    }

    @Test fun `DECRQM DECOM 6 reports set and reset`() {
        val c = core(); val s = Sink(); s.attach(c)
        assertEquals("\u001B[?6;2\$y", askPrivate(c, s, 6))
        setPrivate(c, 6, true)
        assertEquals("\u001B[?6;1\$y", askPrivate(c, s, 6))
    }

    @Test fun `DECRQM DECAWM 7 default is set`() {
        val c = core(); val s = Sink(); s.attach(c)
        assertEquals("DECAWM 缺省开（xterm 默认）", "\u001B[?7;1\$y", askPrivate(c, s, 7))
        setPrivate(c, 7, false)
        assertEquals("\u001B[?7;2\$y", askPrivate(c, s, 7))
    }

    @Test fun `DECRQM X10 mouse 9 reports set and reset`() {
        val c = core(); val s = Sink(); s.attach(c)
        assertEquals("\u001B[?9;2\$y", askPrivate(c, s, 9))
        setPrivate(c, 9, true)
        assertEquals("\u001B[?9;1\$y", askPrivate(c, s, 9))
        setPrivate(c, 9, false)
        assertEquals("\u001B[?9;2\$y", askPrivate(c, s, 9))
    }

    @Test fun `DECRQM DECTCEM 25 default is set`() {
        val c = core(); val s = Sink(); s.attach(c)
        assertEquals("\u001B[?25;1\$y", askPrivate(c, s, 25))
        setPrivate(c, 25, false)
        assertEquals("\u001B[?25;2\$y", askPrivate(c, s, 25))
    }

    @Test fun `DECRQM DECLRMM 69 reports set and reset`() {
        val c = core(); val s = Sink(); s.attach(c)
        assertEquals("\u001B[?69;2\$y", askPrivate(c, s, 69))
        setPrivate(c, 69, true)
        assertEquals("\u001B[?69;1\$y", askPrivate(c, s, 69))
        setPrivate(c, 69, false)
        assertEquals("\u001B[?69;2\$y", askPrivate(c, s, 69))
    }

    // ═══ 备用屏三入口 47 / 1047 / 1049 ═══

    @Test fun `DECRQM 47 1047 1049 all mirror alternate screen`() {
        val c = core(); val s = Sink(); s.attach(c)
        for (m in intArrayOf(47, 1047, 1049)) {
            assertEquals("备用屏关：mode $m", "\u001B[?$m;2\$y", askPrivate(c, s, m))
        }
        setPrivate(c, 1049, true)
        for (m in intArrayOf(47, 1047, 1049)) {
            assertEquals("备用屏开：mode $m", "\u001B[?$m;1\$y", askPrivate(c, s, m))
        }
        setPrivate(c, 1049, false)
        for (m in intArrayOf(47, 1047, 1049)) {
            assertEquals("退出备用屏：mode $m", "\u001B[?$m;2\$y", askPrivate(c, s, m))
        }
    }

    @Test fun `DECRQM 47 and 1047 switch alternate screen without cursor save`() {
        val c = core(); val s = Sink(); s.attach(c)
        setPrivate(c, 47, true)
        assertTrue("47 进入备用屏", c.snapshot().alternateScreen)
        setPrivate(c, 1047, false)
        assertFalse("1047 退出备用屏", c.snapshot().alternateScreen)
    }

    // ═══ 鼠标追踪 1000 / 1002 / 1003（互斥枚举）═══

    @Test fun `DECRQM mouse tracking 1000 1002 1003 are exclusive`() {
        val c = core(); val s = Sink(); s.attach(c)
        assertEquals("\u001B[?1000;2\$y", askPrivate(c, s, 1000))
        setPrivate(c, 1002, true)
        // 1000 已被 1002 取代 → 复位
        assertEquals("开启 1002 后 1000 应复位", "\u001B[?1000;2\$y", askPrivate(c, s, 1000))
        assertEquals("\u001B[?1002;1\$y", askPrivate(c, s, 1002))
        setPrivate(c, 1003, true)
        assertEquals("\u001B[?1002;2\$y", askPrivate(c, s, 1002))
        assertEquals("\u001B[?1003;1\$y", askPrivate(c, s, 1003))
    }

    // ═══ 鼠标编码 1005 / 1006 / 1015（互斥枚举）═══

    @Test fun `DECRQM mouse encoding 1005 1006 1015 are exclusive`() {
        val c = core(); val s = Sink(); s.attach(c)
        assertEquals("\u001B[?1006;2\$y", askPrivate(c, s, 1006))
        setPrivate(c, 1005, true)
        assertEquals("\u001B[?1005;1\$y", askPrivate(c, s, 1005))
        setPrivate(c, 1006, true)
        assertEquals("\u001B[?1005;2\$y", askPrivate(c, s, 1005))
        assertEquals("\u001B[?1006;1\$y", askPrivate(c, s, 1006))
        setPrivate(c, 1015, true)
        assertEquals("\u001B[?1015;1\$y", askPrivate(c, s, 1015))
        assertEquals("\u001B[?1006;2\$y", askPrivate(c, s, 1006))
    }

    @Test fun `DECRQM pixel coordinates 1016 reports unit`() {
        val c = core(); val s = Sink(); s.attach(c)
        assertEquals("\u001B[?1016;2\$y", askPrivate(c, s, 1016))
        setPrivate(c, 1016, true)
        assertEquals("\u001B[?1016;1\$y", askPrivate(c, s, 1016))
    }

    @Test fun `DECRQM focus reporting 1004 reports set and reset`() {
        val c = core(); val s = Sink(); s.attach(c)
        assertEquals("\u001B[?1004;2\$y", askPrivate(c, s, 1004))
        setPrivate(c, 1004, true)
        assertEquals("\u001B[?1004;1\$y", askPrivate(c, s, 1004))
        setPrivate(c, 1004, false)
        assertEquals("\u001B[?1004;2\$y", askPrivate(c, s, 1004))
    }

    @Test fun `DECRQM alt scroll 1007 reports set and reset`() {
        val c = core(); val s = Sink(); s.attach(c)
        assertEquals("\u001B[?1007;2\$y", askPrivate(c, s, 1007))
        setPrivate(c, 1007, true)
        assertEquals("\u001B[?1007;1\$y", askPrivate(c, s, 1007))
    }

    @Test fun `DECRQM SGR extended 10060 reports set and reset`() {
        val c = core(); val s = Sink(); s.attach(c)
        assertEquals("\u001B[?10060;2\$y", askPrivate(c, s, 10060))
        setPrivate(c, 10060, true)
        assertEquals("\u001B[?10060;1\$y", askPrivate(c, s, 10060))
    }

    @Test fun `DECRQM bracketed paste 2004 reports set and reset`() {
        val c = core(); val s = Sink(); s.attach(c)
        assertEquals("\u001B[?2004;2\$y", askPrivate(c, s, 2004))
        setPrivate(c, 2004, true)
        assertEquals("\u001B[?2004;1\$y", askPrivate(c, s, 2004))
        setPrivate(c, 2004, false)
        assertEquals("\u001B[?2004;2\$y", askPrivate(c, s, 2004))
    }

    // ═══ ANSI 形式（无 `?` 前缀）═══

    @Test fun `DECRQM ANSI IRM 4 reports set and reset without marker`() {
        val c = core(); val s = Sink(); s.attach(c)
        assertEquals("ANSI 形式不带 ? 前缀", "\u001B[4;2\$y", askAnsi(c, s, 4))
        c.feed("\u001B[4h".toByteArray())    // ANSI IRM set
        assertEquals("\u001B[4;1\$y", askAnsi(c, s, 4))
        c.feed("\u001B[4l".toByteArray())
        assertEquals("\u001B[4;2\$y", askAnsi(c, s, 4))
    }

    @Test fun `DECRQM ANSI LNM 20 reports set and reset`() {
        val c = core(); val s = Sink(); s.attach(c)
        assertEquals("\u001B[20;2\$y", askAnsi(c, s, 20))
        c.feed("\u001B[20h".toByteArray())
        assertEquals("\u001B[20;1\$y", askAnsi(c, s, 20))
    }

    // ═══ 未知模式 → 0（未识别）═══

    @Test fun `unknown private mode reports not recognized`() {
        val c = core(); val s = Sink(); s.attach(c)
        assertEquals("\u001B[?99;0\$y", askPrivate(c, s, 99))
        assertEquals("\u001B[?9999;0\$y", askPrivate(c, s, 9999))
    }

    @Test fun `unknown ANSI mode reports not recognized`() {
        val c = core(); val s = Sink(); s.attach(c)
        assertEquals("\u001B[7;0\$y", askAnsi(c, s, 7))   // ANSI 7 不是我们跟踪的模式
        assertEquals("\u001B[12;0\$y", askAnsi(c, s, 12))
    }

    @Test fun `highlight tracking 1001 honestly reports not implemented`() {
        // 1001（高亮追踪）与 native 一致：不支持 → 0，不谎报。
        val c = core(); val s = Sink(); s.attach(c)
        assertEquals("\u001B[?1001;0\$y", askPrivate(c, s, 1001))
    }

    // ═══ DECSTR 软复位影响 + 多查询串行 ═══

    @Test fun `DECSTR soft reset restores DECAWM and clears DECCKM DECOM`() {
        val c = core(); val s = Sink(); s.attach(c)
        setPrivate(c, 7, false)             // 关掉 DECAWM
        setPrivate(c, 1, true)              // 开 DECCKM
        setPrivate(c, 6, true)              // 开 DECOM
        c.feed("\u001B[!p".toByteArray())   // DECSTR
        assertEquals("DECSTR 恢复 DECAWM=1", "\u001B[?7;1\$y", askPrivate(c, s, 7))
        assertEquals("DECSTR 清 DECCKM", "\u001B[?1;2\$y", askPrivate(c, s, 1))
        assertEquals("DECSTR 清 DECOM", "\u001B[?6;2\$y", askPrivate(c, s, 6))
    }

    @Test fun `DECSTR resets DECLRMM`() {
        val c = core(); val s = Sink(); s.attach(c)
        setPrivate(c, 69, true)
        c.feed("\u001B[!p".toByteArray())
        assertEquals("\u001B[?69;2\$y", askPrivate(c, s, 69))
    }

    @Test fun `consecutive queries each produce one reply`() {
        val c = core(); val s = Sink(); s.attach(c)
        val before = s.count()
        c.feed("\u001B[?7\$p\u001B[?25\$p\u001B[?2004\$p".toByteArray())
        assertEquals(3, s.count() - before)
        // T88 修复：逐条断言（原断言把第二条当 last —— 第三条是 2004 的应答）。
        val got = s.replies.subList(before, s.replies.size).toList()
        assertEquals(
            listOf("\u001B[?7;1\$y", "\u001B[?25;1\$y", "\u001B[?2004;2\$y"),
            got
        )
    }

    @Test fun `queries without response sink are dropped safely`() {
        val c = core()                       // responseSink = null（缺省）
        c.feed("\u001B[?7\$p".toByteArray()) // 不抛即过
        assertEquals(4, c.rows)
    }

    // ═══ DECREQTPARM（CSI Ps x）═══

    @Test fun `DECREQTPARM param 0 answers legacy form 2`() {
        val c = core(); val s = Sink(); s.attach(c)
        c.feed("\u001B[0x".toByteArray())
        assertEquals("\u001B[?2;1;1;120;120;1;0x", s.last())
    }

    @Test fun `DECREQTPARM param 1 answers legacy form 3`() {
        val c = core(); val s = Sink(); s.attach(c)
        c.feed("\u001B[1x".toByteArray())
        assertEquals("\u001B[?3;1;1;120;120;1;0x", s.last())
    }

    @Test fun `DECREQTPARM default param answers form 2`() {
        val c = core(); val s = Sink(); s.attach(c)
        c.feed("\u001B[x".toByteArray())     // Ps 缺省 = 0
        assertEquals("\u001B[?2;1;1;120;120;1;0x", s.last())
    }

    @Test fun `DECREQTPARM invalid param is ignored`() {
        val c = core(); val s = Sink(); s.attach(c)
        c.feed("\u001B[2x".toByteArray())
        c.feed("\u001B[99x".toByteArray())
        assertEquals("非法 Ps 无应答", 0, s.count())
    }

    @Test fun `DECREQTPARM with private marker is not a parameter request`() {
        // `CSI ? Ps x` 是别的序列（DECRQM 家族之外）—— 不应答。
        val c = core(); val s = Sink(); s.attach(c)
        c.feed("\u001B[?1x".toByteArray())
        assertEquals(0, s.count())
    }
}
