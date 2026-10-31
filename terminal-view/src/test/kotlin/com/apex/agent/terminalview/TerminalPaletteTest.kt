package com.apex.agent.terminalview

import com.apex.agent.terminalemulator.RenderCell
import com.apex.agent.terminalemulator.TerminalColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * T88（2-a）：调色板颜色管线单测 —— 用引擎同款 Long 编码构造 fixture
 * （`TerminalColor.toRgb` → `0xFF000000L or rgb`；0 = 默认），锁定
 * bold-as-bright / inverse / dim / scheme 槽位语义与 app 旧渲染链一致。
 */
class TerminalPaletteTest {

    private val palette = TerminalPalette.TERMUX_DARK

    /** 引擎打包函数的本地复刻（与 TerminalCore.colorArgb 同式）。 */
    private fun enginePack(c: TerminalColor): Long = when (c) {
        TerminalColor.Default -> 0L
        else -> 0xFF000000L or TerminalColor.toRgb(c).toLong().and(0xFFFFFFL)
    }

    private fun cell(fg: Long, bg: Long, flags: Int) = RenderCell("x", fg, bg, flags)

    // ─── 默认色解析 ───

    @Test
    fun `default fg resolves to palette foreground`() {
        assertEquals(palette.foreground.toLong(), palette.resolveCell(0L, 0L, 0).first.toLong())
    }

    @Test
    fun `default bg resolves to palette background`() {
        assertEquals(palette.background.toLong(), palette.resolveCell(0L, 0L, 0).second.toLong())
    }

    @Test
    fun `indexed basic color maps to scheme ansi slot`() {
        // SGR 31（红）→ 引擎标准板 0x800000 → scheme ansi[1]
        val red = enginePack(TerminalColor.Indexed(1))
        val (fg, _) = palette.resolveCell(red, 0L, 0)
        assertEquals(palette.ansi[1].toLong(), fg.toLong())
    }

    @Test
    fun `bright indexed color maps to bright slot without bold`() {
        val brightRed = enginePack(TerminalColor.Indexed(9))
        val (fg, _) = palette.resolveCell(brightRed, 0L, 0)
        assertEquals(palette.ansi[9].toLong(), fg.toLong())
    }

    // ─── bold-as-bright ───

    @Test
    fun `bold base color promotes to bright slot`() {
        val red = enginePack(TerminalColor.Indexed(1))
        val (fg, _) = palette.resolveCell(red, 0L, RenderCell.FLAG_BOLD)
        assertEquals(palette.ansi[9].toLong(), fg.toLong())
    }

    @Test
    fun `bold bright color does not double promote`() {
        val brightRed = enginePack(TerminalColor.Indexed(9))
        val (fg, _) = palette.resolveCell(brightRed, 0L, RenderCell.FLAG_BOLD)
        assertEquals(palette.ansi[9].toLong(), fg.toLong())
    }

    @Test
    fun `bold promotion disabled when boldAsBright false`() {
        val p = palette.copy(boldAsBright = false)
        val red = enginePack(TerminalColor.Indexed(1))
        val (fg, _) = p.resolveCell(red, 0L, RenderCell.FLAG_BOLD)
        assertEquals(p.ansi[1].toLong(), fg.toLong())
    }

    @Test
    fun `bold does not affect background side`() {
        val red = enginePack(TerminalColor.Indexed(1))
        val (_, bg) = palette.resolveCell(0L, red, RenderCell.FLAG_BOLD)
        assertEquals(palette.ansi[1].toLong(), bg.toLong())
    }

    // ─── 256 色 / truecolor 透传 ───

    @Test
    fun `cube color passes through with opaque alpha`() {
        // 38;5;17 → i=1 → r=0,g=0,b=51 → 0x000033（不与标准 16 色撞车 → 透传）
        val c = enginePack(TerminalColor.Indexed(17))
        val (fg, _) = palette.resolveCell(c, 0L, 0)
        assertEquals(0xFF000033.toInt().toLong(), fg.toLong())
    }

    @Test
    fun `cube color colliding with basic16 is remapped like indexed`() {
        // 38;5;196 → 0xFF0000 == 标准板 bright red —— 与旧 TerminalAnsiRemapper 语义
        // 一致（视觉等价零歧义）
        val c = enginePack(TerminalColor.Indexed(196))
        val (fg, _) = palette.resolveCell(c, 0L, 0)
        assertEquals(palette.ansi[9].toLong(), fg.toLong())
    }

    @Test
    fun `grayscale color passes through`() {
        // 38;5;232 → v = 0*10+8 = 8
        val c = enginePack(TerminalColor.Indexed(232))
        val (fg, _) = palette.resolveCell(c, 0L, 0)
        assertEquals(0xFF080808.toInt().toLong(), fg.toLong())
    }

    @Test
    fun `truecolor passes through unchanged`() {
        val c = enginePack(TerminalColor.RGB(10, 20, 30))
        val (fg, _) = palette.resolveCell(c, 0L, 0)
        assertEquals(0xFF0A141E.toInt().toLong(), fg.toLong())
    }

    @Test
    fun `truecolor equal to standard color is still mapped as indexed`() {
        // 38;2;0;0;255 == 标准板 bright blue（12 槽）—— 与旧 TerminalAnsiRemapper 语义
        // 一致（视觉等价零歧义；标准板 blue 是 0x000080 不是 0x0000FF）
        val c = enginePack(TerminalColor.RGB(0, 0, 255))
        val (fg, _) = palette.resolveCell(c, 0L, 0)
        assertEquals(palette.ansi[12].toLong(), fg.toLong())
    }

    // ─── inverse ───

    @Test
    fun `inverse with both defaults swaps to light bg`() {
        val (fg, bg) = palette.resolveCell(0L, 0L, RenderCell.FLAG_INVERSE)
        assertEquals(palette.background.toLong(), fg.toLong())
        assertEquals(palette.foreground.toLong(), bg.toLong())
    }

    @Test
    fun `inverse swaps explicit colors`() {
        val fgColor = enginePack(TerminalColor.Indexed(2))
        val bgColor = enginePack(TerminalColor.Indexed(4))
        val (fg, bg) = palette.resolveCell(fgColor, bgColor, RenderCell.FLAG_INVERSE)
        assertEquals(palette.ansi[4].toLong(), fg.toLong())
        assertEquals(palette.ansi[2].toLong(), bg.toLong())
    }

    @Test
    fun `inverse skips bold-as-bright`() {
        val fgColor = enginePack(TerminalColor.Indexed(1))
        val (_, bg) = palette.resolveCell(fgColor, 0L, RenderCell.FLAG_INVERSE or RenderCell.FLAG_BOLD)
        assertEquals(palette.ansi[1].toLong(), bg.toLong())
    }

    // ─── dim ───

    @Test
    fun `dim blends fg halfway toward bg`() {
        // 白/黑 truecolor 均命中标准板 → scheme 槽（TERMUX_DARK 的 ansi[15]/ansi[0]
        // 恰为纯白/纯黑）→ 50% 混合 = 0x808080（roundToInt 对称）
        val fgColor = enginePack(TerminalColor.RGB(255, 255, 255))
        val bgColor = enginePack(TerminalColor.RGB(0, 0, 0))
        val (fg, _) = palette.resolveCell(fgColor, bgColor, RenderCell.FLAG_DIM)
        assertEquals(0xFF808080.toInt().toLong(), fg.toLong())
    }

    @Test
    fun `dim on inverse blends effective colors`() {
        // 反显后：fg=bg侧(黑)，bg=fg侧(白) → dim 后 fg 仍 ≈ 0x80（对称混合）
        val fgColor = enginePack(TerminalColor.RGB(255, 255, 255))
        val bgColor = enginePack(TerminalColor.RGB(0, 0, 0))
        val (fg, _) = palette.resolveCell(fgColor, bgColor, RenderCell.FLAG_INVERSE or RenderCell.FLAG_DIM)
        assertEquals(0xFF808080.toInt().toLong(), fg.toLong())
    }

    // ─── resolve() 单色 API ───

    @Test
    fun `resolve single color fg default`() {
        assertEquals(palette.foreground.toLong(), palette.resolve(0L, isFg = true, flags = 0).toLong())
    }

    @Test
    fun `resolve single color bg default`() {
        assertEquals(palette.background.toLong(), palette.resolve(0L, isFg = false, flags = 0).toLong())
    }

    @Test
    fun `resolve bold fg uses bright slot`() {
        val red = enginePack(TerminalColor.Indexed(1))
        assertEquals(
            palette.ansi[9].toLong(),
            palette.resolve(red, isFg = true, flags = RenderCell.FLAG_BOLD).toLong()
        )
    }

    // ─── 工具函数 / 方案注入 ───

    @Test
    fun `standard16Index matches engine basic16`() {
        for (i in 0..15) {
            val idx = TerminalPalette.standard16Index(enginePack(TerminalColor.Indexed(i)))
            assertEquals(i.toLong(), (idx ?: -1).toLong())
        }
    }

    @Test
    fun `fromSchemeMap overrides semantic slots`() {
        val map = mapOf(
            -1 to 0xFF123456.toInt(),  // background
            -2 to 0xFFABCDEF.toInt(),  // foreground
            1 to 0xFF112233.toInt()    // ansi red
        )
        val p = TerminalPalette.fromSchemeMap(map)
        assertEquals(0xFF123456.toInt().toLong(), p.background.toLong())
        assertEquals(0xFFABCDEF.toInt().toLong(), p.foreground.toLong())
        assertEquals(0xFF112233.toInt().toLong(), p.ansi[1].toLong())
        // 未覆盖槽位回落标准板
        assertEquals(0xFF008000.toInt().toLong(), p.ansi[2].toLong())
    }

    @Test
    fun `fromSchemeMap extended overrides and flags`() {
        val map = mapOf(200 to 0xFF00FF00.toInt(), -9 to 0, -10 to 0)
        val p = TerminalPalette.fromSchemeMap(map, dark = true)
        assertEquals(0xFF00FF00.toInt().toLong(), p.color256(200).toLong())
        assertEquals(false, p.boldAsBright)
        assertEquals(false, p.dark)
    }

    @Test
    fun `color256 bounds and passthrough`() {
        assertEquals(palette.ansi[0].toLong(), palette.color256(0).toLong())
        assertEquals(TerminalPalette.standardExtended(16).toLong(), palette.color256(16).toLong())
        assertEquals(TerminalPalette.standardExtended(255).toLong(), palette.color256(255).toLong())
        assertEquals(palette.foreground.toLong(), palette.color256(-5).toLong())
    }

    @Test
    fun `blend endpoints and midpoint`() {
        assertEquals(0xFF000000.toInt().toLong(), TerminalPalette.blend(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0f).toLong())
        assertEquals(0xFFFFFFFF.toInt().toLong(), TerminalPalette.blend(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 1f).toLong())
        assertEquals(0xFF808080.toInt().toLong(), TerminalPalette.blend(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0.5f).toLong())
        // 对称性：方向翻转同值（roundToInt —— 截断法会得 0x7F）
        assertEquals(0xFF808080.toInt().toLong(), TerminalPalette.blend(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0.5f).toLong())
    }

    @Test
    fun `built-in palettes have distinct fg bg and full size`() {
        for (p in listOf(TerminalPalette.TERMUX_DARK, TerminalPalette.TERMUX_LIGHT, TerminalPalette.MONOCHROME)) {
            assertEquals(16, p.ansi.size)
            assertEquals(240, p.extended.size)
            assertNotEquals(p.foreground.toLong(), p.background.toLong())
        }
        assertEquals(true, TerminalPalette.TERMUX_LIGHT.dark.not())
    }

    @Test
    fun `malformed packed long with missing alpha gets forced opaque`() {
        // 引擎恒写 FF alpha；防御：低 24 位仍在（alpha 位脏数据）
        val dirty = 0x00FF0000L
        val (fg, _) = palette.resolveCell(dirty, 0L, 0)
        // 0xFF0000 命中标准板 red? 标准板 red=0x800000；0xFF0000 = bright red (idx 9)
        assertEquals(palette.ansi[9].toLong(), fg.toLong())
        assertNull(TerminalPalette.standard16Index(0x123456L))
        assertNotNull(TerminalPalette.standard16Index(0x800000L))
    }
}
