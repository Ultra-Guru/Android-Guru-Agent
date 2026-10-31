package com.apex.agent.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #278（契约锁）：强调色单一事实源不漂移。
 *
 * 旧病灶：四套颜色体系（MaterialTheme / 终端 ConsoleTheme / 浏览器
 * ChromePalette / 玻璃 GlassPalette）各自取色 —— 用户在设置中心改强调色
 * 只覆盖 MaterialTheme，终端控制台硬编码 mint 永不跟随。统一后 AccentColors
 * 经 LocalAccentColors 下发，与 colorScheme 必须同出自 accentSetFor ——
 * 本测试锁死两路输出逐字段一致，任何一侧单独改色即红。
 */
class AccentColorsContractTest {

    /** 预设路径：Local 出口与 MaterialTheme colorScheme 强调槽位逐字段同源。 */
    @Test
    fun `accentColors 与 accentColorScheme 同源（单一事实源不漂移）`() {
        for (palette in AccentPalette.entries) {
            val local = accentColors(palette)
            val darkScheme = accentColorScheme(palette, dark = true)
            val lightScheme = accentColorScheme(palette, dark = false)
            assertEquals("dark.primary ${palette}", darkScheme.primary, local.dark.primary)
            assertEquals("dark.onPrimary ${palette}", darkScheme.onPrimary, local.dark.onPrimary)
            assertEquals("dark.primaryContainer ${palette}", darkScheme.primaryContainer, local.dark.primaryContainer)
            assertEquals("dark.secondary ${palette}", darkScheme.secondary, local.dark.secondary)
            assertEquals("dark.secondaryContainer ${palette}", darkScheme.secondaryContainer, local.dark.secondaryContainer)
            assertEquals("dark.tertiary ${palette}", darkScheme.tertiary, local.dark.tertiary)
            assertEquals("dark.tertiaryContainer ${palette}", darkScheme.tertiaryContainer, local.dark.tertiaryContainer)
            assertEquals("light.primary ${palette}", lightScheme.primary, local.light.primary)
            assertEquals("light.onPrimary ${palette}", lightScheme.onPrimary, local.light.onPrimary)
            assertEquals("light.primaryContainer ${palette}", lightScheme.primaryContainer, local.light.primaryContainer)
            assertEquals("light.secondary ${palette}", lightScheme.secondary, local.light.secondary)
            assertEquals("light.tertiary ${palette}", lightScheme.tertiary, local.light.tertiary)
        }
    }

    /** 兜底契约：未注入 Local 时的默认 = MINT（去霓虹重调后的品牌薄荷绿）。 */
    @Test
    fun `MINT 深色变体保持低饱和薄荷绿字面值（去霓虹重调后的品牌锚点）`() {
        val mint = accentColors(AccentPalette.MINT)
        // 去霓虹重调：旧 neon mint 0xFF4EE9B0 → 低饱和 sage mint 0xFF41A988 ——
        // 默认路径全局去霓虹，本锁防止回退到旧霓虹值
        assertEquals(Color(0xFF41A988), mint.dark.primary)
        // 深绿容器底不变（强调弱底语义，本就低饱和）
        assertEquals(Color(0xFF0C3A2C), mint.dark.primaryContainer)
    }

    /** 极简黑白契约：MONO 强调族是纯灰阶（R=G=B，「灰阶没有取色空间」）。 */
    @Test
    fun `MONO 强调族为纯灰阶`() {
        val mono = accentColors(AccentPalette.MONO)
        for (c in listOf(mono.dark.primary, mono.dark.secondary, mono.dark.tertiary,
            mono.light.primary, mono.light.secondary, mono.light.tertiary)) {
            // ⚠️ Compose 的 red/green/blue 是 inline val 扩展属性，纯 JVM unit test
            // classpath 下不可解析（luminance/toArgb 等顶层函数无此问题，实测
            // CI run 38112396663）—— 改走 toArgb() 位提取，语义等价
            //（AccentPalette 颜色均为 Color(0xFF…) 字面 sRGB 构造，量化无差）。
            val argb = c.toArgb()
            val r = (argb ushr 16) and 0xFF
            val g = (argb ushr 8) and 0xFF
            val b = argb and 0xFF
            assertEquals("灰阶要求 R=G 通道一致", r, g)
            assertEquals("灰阶要求 R=B 通道一致", r, b)
        }
    }

    /** 深浅双态必须独立调色（防两变体误接同一来源）。 */
    @Test
    fun `深浅变体独立调色`() {
        val mint = accentColors(AccentPalette.MINT)
        assertNotEquals(mint.dark.primary, mint.light.primary)
        // 深色态低饱和提亮（近黑底清晰可读）、浅色态可读深色（白底达标）
        assertTrue("深色态主色应更亮", mint.dark.primary.luminance() > mint.light.primary.luminance())
    }

    /** Dynamic Color 路径接入口：toAccentTone 从任意 ColorScheme 抽取强调族。 */
    @Test
    fun `toAccentTone 抽取 ColorScheme 强调槽位`() {
        val scheme = darkColorScheme(
            primary = Color(0xFF123456),
            primaryContainer = Color(0xFF654321),
            secondary = Color(0xFFABCDEF),
            tertiary = Color(0xFF112233)
        )
        val tone = scheme.toAccentTone()
        assertEquals(Color(0xFF123456), tone.primary)
        assertEquals(scheme.onPrimary, tone.onPrimary)
        assertEquals(Color(0xFF654321), tone.primaryContainer)
        assertEquals(scheme.onPrimaryContainer, tone.onPrimaryContainer)
        assertEquals(Color(0xFFABCDEF), tone.secondary)
        assertEquals(scheme.onSecondary, tone.onSecondary)
        assertEquals(scheme.secondaryContainer, tone.secondaryContainer)
        assertEquals(scheme.onSecondaryContainer, tone.onSecondaryContainer)
        assertEquals(Color(0xFF112233), tone.tertiary)
        assertEquals(scheme.onTertiary, tone.onTertiary)
        assertEquals(scheme.tertiaryContainer, tone.tertiaryContainer)
        assertEquals(scheme.onTertiaryContainer, tone.onTertiaryContainer)
    }
}
