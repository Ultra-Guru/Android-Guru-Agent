package com.apex.agent.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * 自定义背景 · 专属优化纯函数测试（scrimAlphaFor 亮度自适应纱罩）。
 *
 * 不变量：任何输入（主题 / 亮度 / 用户强度）都钳在 [0.10, 0.85] ——
 * 文字可读性有下限，纱罩永不完全不透明（背景图永远可见）。
 * 纯 JVM 可跑（Color 是 value class，组件读取零 Android 依赖）。
 */
class BackgroundScrimTest {

    @Test
    fun `dark theme deepens scrim for brighter images`() {
        // 暗色主题：图片越亮自适应基数越深（亮图上的浅色文字需要更深的底）
        val dark = scrimAlphaFor(darkTheme = true, luminance = 0.2f, userDim = 0.4f)
        val bright = scrimAlphaFor(darkTheme = true, luminance = 0.9f, userDim = 0.4f)
        assertEquals(0.2f * 0.35f + 0.4f, dark, 0.001f)  // 0.47
        assertTrue("brighter image → deeper scrim", bright > dark)
    }

    @Test
    fun `light theme strengthens scrim for darker images`() {
        // 亮色主题：图片越暗白纱越厚（暗图上的深色文字需要更白的底）
        val darkImage = scrimAlphaFor(darkTheme = false, luminance = 0.1f, userDim = 0.4f)
        val brightImage = scrimAlphaFor(darkTheme = false, luminance = 0.9f, userDim = 0.4f)
        assertTrue("darker image → thicker scrim", darkImage > brightImage)
        assertEquals(0.9f * 0.35f + 0.4f, brightImage, 0.001f)  // autoBase = (1-0.9)×0.35
    }

    @Test
    fun `alpha always clamps into readability bounds`() {
        for (dark in listOf(true, false)) {
            for (lum in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
                for (dim in listOf(0.1f, 0.4f, 0.8f)) {
                    val a = scrimAlphaFor(dark, lum, dim)
                    assertTrue("within [0.10, 0.85]: $a", a in 0.10f..0.85f)
                }
            }
        }
    }

    @Test
    fun `extreme inputs hit the clamps`() {
        // 全亮图 + 最大手动强度：钳在上限（背景仍可见）
        assertEquals(0.85f, scrimAlphaFor(true, 1f, 0.8f), 0.0001f)
        // 最暗图（暗色主题的最小基数）+ 最小手动强度：钳在下限（文字仍有底）
        assertEquals(0.10f, scrimAlphaFor(true, 0f, 0.1f), 0.0001f)
        // 全亮图（亮色主题的最小基数）+ 最小手动强度：同样钳在下限
        assertEquals(0.10f, scrimAlphaFor(false, 1f, 0.1f), 0.0001f)
    }

    @Test
    fun `scrim colors use near-black for dark and near-white for light`() {
        val dark = scrimColor(true)
        val light = scrimColor(false)
        assertEquals(7, (dark.red * 255).roundToInt())
        assertEquals(10, (dark.green * 255).roundToInt())
        assertEquals(14, (dark.blue * 255).roundToInt())
        assertEquals(246, (light.red * 255).roundToInt())
        assertEquals(248, (light.green * 255).roundToInt())
        assertEquals(250, (light.blue * 255).roundToInt())
    }
}
