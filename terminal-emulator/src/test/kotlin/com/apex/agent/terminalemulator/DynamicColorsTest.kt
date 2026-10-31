package com.apex.agent.terminalemulator

import org.junit.Assert.*
import org.junit.Test

/**
 * v0.3 动态颜色测试 —— OSC 10/11/12 set + `?` 查询 + 110/111/112/104 重置：
 * 任务的「DynamicColors」组。解析矩阵覆盖 xterm 全部颜色规格形式
 * （`rgb:RRRR/GGGG/BBBB` 1–4 位/通道、`rgba:`（alpha 忽略）、`#RGB`、
 * `#RRGGBB`、`#RRGGBBAA`）与畸形输入（未识别 → 状态不动，绝不半更新）。
 * 查询应答 `rgb:XXXX/XXXX/XXXX`（8bit × 0x101 扩展到 16bit，xterm 原生精度）。
 */
class DynamicColorsTest {

    // ═══ 解析矩阵（xterm 颜色规格）═══

    @Test fun `parses 16-bit-per-channel rgb form`() {
        assertEquals(TerminalColor.RGB(255, 0, 255), DynamicColors.parse("rgb:ffff/0000/ffff"))
    }

    @Test fun `parses 8-bit-per-channel rgb form`() {
        assertEquals(TerminalColor.RGB(255, 128, 0), DynamicColors.parse("rgb:ff/80/00"))
    }

    @Test fun `parses 4-bit-per-channel rgb form with normalization`() {
        // 每通道 4 位：0xFFF → 255（满量程映射）。
        assertEquals(TerminalColor.RGB(255, 255, 255), DynamicColors.parse("rgb:fff/fff/fff"))
    }

    @Test fun `parses 1-digit rgb form`() {
        // 1 位/通道：0xF → 255。
        assertEquals(TerminalColor.RGB(255, 0, 0), DynamicColors.parse("rgb:f/0/0"))
    }

    @Test fun `parses mixed channel widths`() {
        // xterm 允许各通道位宽不同（各自归一化）。
        assertEquals(TerminalColor.RGB(255, 128, 0), DynamicColors.parse("rgb:f/80/0"))
    }

    @Test fun `parses rgba ignoring alpha`() {
        assertEquals(TerminalColor.RGB(18, 52, 86), DynamicColors.parse("rgba:12/34/56/78"))
    }

    @Test fun `parses hash RRGGBB`() {
        assertEquals(TerminalColor.RGB(0x1E, 0x1E, 0x2E), DynamicColors.parse("#1e1e2e"))
    }

    @Test fun `parses hash RGB expanding to double digits`() {
        assertEquals("0xA → 0xAA", TerminalColor.RGB(255, 136, 0), DynamicColors.parse("#f80"))
    }

    @Test fun `parses hash RRGGBBAA ignoring alpha`() {
        assertEquals(TerminalColor.RGB(17, 34, 51), DynamicColors.parse("#11223344"))
    }

    @Test fun `parsing is case-insensitive`() {
        assertEquals(TerminalColor.RGB(255, 128, 0), DynamicColors.parse("RGB:FF/80/00"))
    }

    @Test fun `surrounding whitespace is tolerated`() {
        assertEquals(TerminalColor.RGB(255, 0, 0), DynamicColors.parse(" rgb:ff/0/0 "))
    }

    // ═══ 畸形输入 → null（状态绝不半更新）═══

    @Test fun `empty spec is rejected`() {
        assertNull(DynamicColors.parse(""))
    }

    @Test fun `color names are rejected`() {
        assertNull(DynamicColors.parse("red"))
    }

    @Test fun `wrong part count is rejected`() {
        assertNull(DynamicColors.parse("rgb:1/2"))
        assertNull(DynamicColors.parse("rgb:1/2/3/4/5"))
    }

    @Test fun `non-hex channel is rejected`() {
        assertNull(DynamicColors.parse("rgb:zz/00/00"))
    }

    @Test fun `over-long channel is rejected`() {
        assertNull(DynamicColors.parse("rgb:12345/0/0"))
    }

    @Test fun `malformed hash lengths are rejected`() {
        assertNull(DynamicColors.parse("#12345"))
        assertNull(DynamicColors.parse("#"))
        assertNull(DynamicColors.parse("#12"))
    }

    @Test fun `non-hex hash is rejected`() {
        assertNull(DynamicColors.parse("#GGGGGG"))
    }

    @Test fun `oversized input is rejected defensively`() {
        assertNull(DynamicColors.parse("#" + "f".repeat(80)))
    }

    // ═══ set / 槽位 / 状态保持 ═══

    @Test fun `set writes the correct slot`() {
        val d = DynamicColors()
        assertTrue(d.set(10, "#ff0000"))
        assertTrue(d.set(11, "#00ff00"))
        assertTrue(d.set(12, "#0000ff"))
        assertEquals(TerminalColor.RGB(255, 0, 0), d.foreground)
        assertEquals(TerminalColor.RGB(0, 255, 0), d.background)
        assertEquals(TerminalColor.RGB(0, 0, 255), d.cursor)
    }

    @Test fun `unknown slot is rejected`() {
        val d = DynamicColors()
        assertFalse(d.set(13, "#ff0000"))
    }

    @Test fun `invalid spec leaves state unchanged`() {
        val d = DynamicColors()
        d.set(10, "#ff0000")
        assertFalse("解析失败返回 false", d.set(10, "not-a-color"))
        assertEquals(TerminalColor.RGB(255, 0, 0), d.foreground)
    }

    // ═══ 重置 ═══

    @Test fun `precise resets clear only their slot`() {
        val d = DynamicColors()
        d.set(10, "#111111"); d.set(11, "#222222"); d.set(12, "#333333")
        d.reset(110)
        assertNull(d.foreground); assertNotNull(d.background); assertNotNull(d.cursor)
        d.reset(111)
        assertNull(d.background); assertNotNull(d.cursor)
        d.reset(112)
        assertNull(d.cursor)
    }

    @Test fun `OSC 104 reset clears all three slots`() {
        val d = DynamicColors()
        d.set(10, "#111111"); d.set(11, "#222222"); d.set(12, "#333333")
        d.reset(104)
        assertNull(d.foreground); assertNull(d.background); assertNull(d.cursor)
    }

    @Test fun `clear resets everything (RIS)`() {
        val d = DynamicColors()
        d.set(11, "#222222")
        d.clear()
        assertNull(d.background)
    }

    @Test fun `default colors for unset slots`() {
        assertEquals(TerminalColor.RGB(255, 255, 255), DynamicColors.defaultFor(10))
        assertEquals(TerminalColor.RGB(0, 0, 0), DynamicColors.defaultFor(11))
        assertEquals(TerminalColor.RGB(255, 255, 255), DynamicColors.defaultFor(12))
    }

    // ═══ 查询应答格式 ═══

    @Test fun `format renders 16-bit channels`() {
        assertEquals("rgb:ffff/0000/ffff", DynamicColors.format(TerminalColor.RGB(255, 0, 255)))
        assertEquals("rgb:0000/0000/0000", DynamicColors.format(TerminalColor.RGB(0, 0, 0)))
    }

    @Test fun `format expands 8-bit values to 16-bit precision`() {
        // 0x80 × 0x101 = 0x8080（xterm 的 8→16bit 扩展）。
        assertEquals("rgb:8080/8080/8080", DynamicColors.format(TerminalColor.RGB(128, 128, 128)))
    }

    // ═══ 引擎集成（OSC 消费 + 快照透出 + 查询应答）═══

    private fun core(): TerminalCore = TerminalCore(3, 10)
    private fun feed(c: TerminalCore, s: String) = c.feed(s.toByteArray())

    @Test fun `OSC 10 set foreground is exposed via snapshot`() {
        val c = core()
        feed(c, "\u001B]10;rgb:ff/00/00\u001B\\")
        assertEquals(0xFFFF0000L, c.renderSnapshot().dynamicForeground)
        assertNull(c.renderSnapshot().dynamicBackground)
    }

    @Test fun `OSC 11 set background is exposed via snapshot`() {
        val c = core()
        feed(c, "\u001B]11;#1e1e2e\u001B\\")
        assertEquals(0xFF1E1E2EL, c.renderSnapshot().dynamicBackground)
    }

    @Test fun `OSC 12 set cursor color is exposed via snapshot`() {
        val c = core()
        feed(c, "\u001B]12;#00ff00\u0007")   // BEL 终止的 OSC 同样有效
        assertEquals(0xFF00FF00L, c.renderSnapshot().dynamicCursorColor)
    }

    @Test fun `malformed OSC 11 keeps previous color`() {
        val c = core()
        feed(c, "\u001B]11;#101010\u001B\\")
        feed(c, "\u001B]11;purple\u001B\\")   // 未识别 → 状态不动
        assertEquals(0xFF101010L, c.renderSnapshot().dynamicBackground)
    }

    @Test fun `OSC query answers with current color`() {
        val c = core()
        val replies = mutableListOf<String>()
        c.responseSink = { replies.add(String(it, Charsets.US_ASCII)) }
        feed(c, "\u001B]10;#ff00ff\u001B\\")
        feed(c, "\u001B]10;?\u001B\\")
        assertEquals(listOf("\u001B]10;rgb:ffff/0000/ffff\u001B\\"), replies)
    }

    @Test fun `OSC 11 query unset answers black default`() {
        val c = core()
        val replies = mutableListOf<String>()
        c.responseSink = { replies.add(String(it, Charsets.US_ASCII)) }
        feed(c, "\u001B]11;?\u001B\\")
        assertEquals("\u001B]11;rgb:0000/0000/0000\u001B\\", replies.last())
    }

    @Test fun `OSC 10 and 12 query unset answer white default`() {
        val c = core()
        val replies = mutableListOf<String>()
        c.responseSink = { replies.add(String(it, Charsets.US_ASCII)) }
        feed(c, "\u001B]10;?\u001B\\")
        feed(c, "\u001B]12;?\u001B\\")
        assertEquals("\u001B]10;rgb:ffff/ffff/ffff\u001B\\", replies[0])
        assertEquals("\u001B]12;rgb:ffff/ffff/ffff\u001B\\", replies[1])
    }

    @Test fun `query after set answers the set value`() {
        val c = core()
        val replies = mutableListOf<String>()
        c.responseSink = { replies.add(String(it, Charsets.US_ASCII)) }
        feed(c, "\u001B]11;rgb:80/80/80\u001B\\")
        feed(c, "\u001B]11;?\u001B\\")
        assertEquals("\u001B]11;rgb:8080/8080/8080\u001B\\", replies.last())
    }

    @Test fun `OSC 110 resets foreground in engine`() {
        val c = core()
        feed(c, "\u001B]10;#ff0000\u001B\\")
        feed(c, "\u001B]110\u001B\\")
        assertNull(c.renderSnapshot().dynamicForeground)
    }

    @Test fun `OSC 104 resets all dynamic colors in engine`() {
        val c = core()
        feed(c, "\u001B]10;#ff0000\u001B\\\u001B]11;#00ff00\u001B\\\u001B]12;#0000ff\u001B\\")
        feed(c, "\u001B]104\u001B\\")
        val snap = c.renderSnapshot()
        assertNull(snap.dynamicForeground)
        assertNull(snap.dynamicBackground)
        assertNull(snap.dynamicCursorColor)
    }

    @Test fun `RIS clears dynamic colors`() {
        val c = core()
        feed(c, "\u001B]11;#00ff00\u001B\\")
        feed(c, "\u001Bc")
        assertNull(c.renderSnapshot().dynamicBackground)
    }
}
