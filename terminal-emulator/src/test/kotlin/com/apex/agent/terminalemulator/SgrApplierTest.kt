package com.apex.agent.terminalemulator

import org.junit.Assert.*
import org.junit.Test

/**
 * v0.3 SGR 测试 —— SGR 58/59 下划线描色（38/48/58 全对称）+ 拆分后的
 * [SgrApplier] 单元级覆盖（构造 CSISequence 直接驱动，无需整屏 feed）。
 */
class SgrApplierTest {

    private fun sgr(vararg params: Any): VtParser.CSISequence {
        // Any 元素：Int = 整参数；String = 冒号分隔 token（拆成 subParams）。
        // 直接构造 CSISequence（不经真实解析器）：边界用例（负数/超界参数）
        // 会被 VtParser 按畸形丢弃，永远到不了 SgrApplier —— 而这里的测试目标
        // 正是 SgrApplier 对已解析畸形参数的 clamp 纪律（防御纵深），用真实
        // 解析器构造会让测试目标失效（T88 修复：原实现 out[0] 越界崩溃）。
        val ints = mutableListOf<Int>()
        val subParams = mutableMapOf<Int, IntArray>()
        for (p in params) {
            when (p) {
                is Int -> ints.add(p)
                is String -> {
                    val sub = p.split(':').map { it.toIntOrNull() ?: 0 }
                    ints.add(sub.first())
                    subParams[ints.size - 1] = sub.toIntArray()
                }
            }
        }
        return VtParser.CSISequence(
            privateMarker = null,
            params = ints.toIntArray(),
            intermediates = CharArray(0),
            finalByte = 'm',
            subParams = subParams
        )
    }

    // ═══ SGR 58：下划线描色（分号扩展）═══

    @Test fun `SGR 58 semicolon 256-color sets underline color`() {
        val s = SgrApplier.apply(TerminalStyle.DEFAULT, sgr(58, 5, 196))
        assertEquals(TerminalColor.Indexed(196), s.underlineColor)
    }

    @Test fun `SGR 58 semicolon truecolor sets underline color`() {
        val s = SgrApplier.apply(TerminalStyle.DEFAULT, sgr(58, 2, 10, 20, 30))
        assertEquals(TerminalColor.RGB(10, 20, 30), s.underlineColor)
    }

    @Test fun `SGR 59 resets underline color to Default`() {
        val s0 = SgrApplier.apply(TerminalStyle.DEFAULT, sgr(58, 5, 196))
        val s1 = SgrApplier.apply(s0, sgr(59))
        assertEquals(TerminalColor.Default, s1.underlineColor)
    }

    @Test fun `SGR 58 and 38 and 48 coexist independently`() {
        // 一次序列三个颜色槽 + 下划线样式：kitty 拼写检查的典型叠加
        val s = SgrApplier.apply(TerminalStyle.DEFAULT, sgr(4, 38, 5, 196, 48, 2, 1, 2, 3, 58, 2, 9, 8, 7))
        assertEquals(TerminalColor.Indexed(196), s.foreground)
        assertEquals(TerminalColor.RGB(1, 2, 3), s.background)
        assertEquals(TerminalColor.RGB(9, 8, 7), s.underlineColor)
        assertEquals(UnderlineStyle.SINGLE, s.underline)
    }

    @Test fun `SGR 58 does not touch foreground or background`() {
        val s = SgrApplier.apply(TerminalStyle.DEFAULT, sgr(38, 5, 100, 58, 5, 200))
        assertEquals(TerminalColor.Indexed(100), s.foreground)
        assertEquals(TerminalColor.Default, s.background)
        assertEquals(TerminalColor.Indexed(200), s.underlineColor)
    }

    // ═══ SGR 58：冒号子参数形式 ═══

    @Test fun `SGR 58 colon 256-color parses`() {
        val s = SgrApplier.apply(TerminalStyle.DEFAULT, sgr("58:5:208"))
        assertEquals(TerminalColor.Indexed(208), s.underlineColor)
    }

    @Test fun `SGR 58 colon truecolor parses`() {
        val s = SgrApplier.apply(TerminalStyle.DEFAULT, sgr("58:2:1:2:3"))
        assertEquals(TerminalColor.RGB(1, 2, 3), s.underlineColor)
    }

    @Test fun `SGR 58 colon truecolor with colorspace prefix ignores cs`() {
        val s = SgrApplier.apply(TerminalStyle.DEFAULT, sgr("58:2:0:4:5:6"))
        assertEquals(TerminalColor.RGB(4, 5, 6), s.underlineColor)
    }

    @Test fun `SGR 58 malformed sub-params leave state unchanged`() {
        val base = TerminalStyle.DEFAULT.copy(underlineColor = TerminalColor.Indexed(9))
        val s = SgrApplier.apply(base, sgr("58:9"))   // 未知颜色空间 → no-op
        assertEquals(TerminalColor.Indexed(9), s.underlineColor)
    }

    // ═══ SGR 0 / 链接编号保留（回归）═══

    @Test fun `SGR 0 resets attributes but keeps link index`() {
        val s0 = TerminalStyle.DEFAULT.copy(bold = true, linkIndex = 3)
        val s1 = SgrApplier.apply(s0, sgr(0))
        assertFalse(s1.bold)
        assertEquals(3, s1.linkIndex)
    }

    @Test fun `SGR 0 also resets underline color`() {
        val s0 = SgrApplier.apply(TerminalStyle.DEFAULT, sgr(58, 5, 196))
        val s1 = SgrApplier.apply(s0, sgr(0))
        assertEquals(TerminalColor.Default, s1.underlineColor)
    }

    // ═══ 边界（P1 纪律回归：畸形参数 clamp）═══

    @Test fun `negative SGR 38 index is clamped not crashing`() {
        val s = SgrApplier.apply(TerminalStyle.DEFAULT, sgr(38, 5, -7))
        assertEquals(TerminalColor.Indexed(0), s.foreground)
    }

    @Test fun `huge SGR 58 rgb components are clamped`() {
        val s = SgrApplier.apply(TerminalStyle.DEFAULT, sgr(58, 2, 99999, -1, 256))
        assertEquals(TerminalColor.RGB(255, 0, 255), s.underlineColor)
    }

    @Test fun `empty SGR params act as full reset`() {
        val s0 = TerminalStyle.DEFAULT.copy(bold = true, italic = true)
        val s1 = SgrApplier.apply(s0, sgr())
        assertEquals(TerminalStyle.DEFAULT, s1.copy(linkIndex = 0))
    }

    @Test fun `truncated 58 2 sequence leaves state unchanged`() {
        val s = SgrApplier.apply(TerminalStyle.DEFAULT, sgr(58, 2, 10))  // 缺 g/b → 不落
        assertEquals(TerminalColor.Default, s.underlineColor)
    }

    // ═══ SGR 58 补充矩阵（T88 完成轮）═══

    @Test fun `colon 58 without color index is a no-op`() {
        val base = TerminalStyle.DEFAULT.copy(underlineColor = TerminalColor.Indexed(7))
        val s = SgrApplier.apply(base, sgr("58:5"))   // 缺 n → 不落
        assertEquals(TerminalColor.Indexed(7), s.underlineColor)
    }

    @Test fun `curly underline style and 58 color coexist in one sequence`() {
        val s = SgrApplier.apply(TerminalStyle.DEFAULT, sgr("4:3", "58:2:9:8:7"))
        assertEquals(UnderlineStyle.CURLY, s.underline)
        assertEquals(TerminalColor.RGB(9, 8, 7), s.underlineColor)
    }

    @Test fun `SGR 59 mid-sequence resets only the underline slot`() {
        val s = SgrApplier.apply(
            TerminalStyle.DEFAULT.copy(bold = true),
            sgr(58, 5, 196, 59, 31)   // 设描色 → 59 归位 → 前景红
        )
        assertEquals(TerminalColor.Default, s.underlineColor)
        assertEquals(TerminalColor.Indexed(1), s.foreground)
        assertTrue("bold 不受影响", s.bold)
    }
}

/**
 * v0.3 SGR 58 端到端 —— 落屏 cell → RenderCell.underlineColorLong 映射。
 */
class Sgr58RenderTest {

    private fun feed(core: TerminalCore, s: String) = core.feed(s.toByteArray())

    @Test fun `underlined colored cell carries underlineColorLong`() {
        val core = TerminalCore(2, 10)
        feed(core, "\u001B[4m\u001B[58;2;255;0;0mX\u001B[0m")
        val cell = core.renderSnapshot().lines[0][0]
        assertTrue("有下划线", cell.flags and RenderCell.FLAG_UNDERLINE != 0)
        assertEquals(0xFFFF0000L, cell.underlineColorLong)
    }

    @Test fun `default underline color packs as 0`() {
        val core = TerminalCore(2, 10)
        feed(core, "\u001B[4mX")
        val cell = core.renderSnapshot().lines[0][0]
        assertTrue(cell.flags and RenderCell.FLAG_UNDERLINE != 0)
        assertEquals("未设置 SGR 58 → 主题默认 0", 0L, cell.underlineColorLong)
    }

    @Test fun `SGR 59 returns underline color to default`() {
        val core = TerminalCore(2, 10)
        feed(core, "\u001B[4;58;5;196mX\u001B[59mY")
        val snap = core.renderSnapshot()
        assertEquals("X 仍带描色（Indexed 196 = 红）", 0xFFFF0000L, snap.lines[0][0].underlineColorLong)
        assertEquals("Y 归默认", 0L, snap.lines[0][1].underlineColorLong)
    }
}
