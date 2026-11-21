package com.apex.agent.terminalview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T91（D1）：TerminalViewSettings 契约测试 —— **捏合缩放默认关闭**的行为锁。
 *
 * 背景（用户反馈「缩放一坨」→ T90 症状修复 → T91 定性收敛）：
 *  - T90 修了 pinchScaleAccum 手势结束不复位（残留 1.05..1.24 累积凭空 ±1sp）；
 *  - 但捏合与双指拖动/鼠标模式手势上下文冲突是结构性的，默认关闭后该路径
 *    成为死代码，稳定性上限拉满；
 *  - 字号调节保留更受控的入口 —— 宿主设置页 Slider（TerminalSettingsSheet
 *    → ViewModel.setFontSize → updateSettings 原子替换，8..24sp 链路）。
 *
 * 本测试锁定的契约：
 *  1. `pinchZoomEnabled` 默认 false（宿主不显式传 copy(pinchZoomEnabled=true)
 *     时捏合永远沉寂 —— View 层 handleGesture 短路 + handlePinch 双保险）；
 *  2. 字号调节链路（clampFontSize / stepFontSize / withFontSize）在捏合关闭
 *     后依然完整 —— Slider 路径不受影响；
 *  3. 宿主显式开启的能力保留（copy 语义不丢字段）。
 */
class TerminalViewSettingsTest {

    // ─── D1 行为锁：捏合默认关闭 ───

    @Test
    fun `pinch zoom is disabled by default (T91 D1)`() {
        val settings = TerminalViewSettings()
        assertFalse(
            "捏合缩放必须默认关闭 —— 宿主未显式开启时 View 层短路所有 Pinch 事件",
            settings.pinchZoomEnabled
        )
    }

    @Test
    fun `host constructed settings inherit disabled pinch (app TerminalViewHost path)`() {
        // 复刻 app TerminalViewHost 的构造形态：只传字号/单色/调色板，
        // 不显式传 pinchZoomEnabled —— 捏合必须继承默认关闭。
        val settings = TerminalViewSettings(
            fontSizeSp = 14f,
            minFontSp = 8f,
            maxFontSp = 24f,
            monochrome = false,
            palette = TerminalPalette.TERMUX_DARK
        )
        assertFalse(settings.pinchZoomEnabled)
    }

    @Test
    fun `explicit opt-in preserves pinch zoom capability`() {
        // 宿主确要恢复捏合：显式 copy(pinchZoomEnabled = true) 仍可用（能力保留，
        // 只是默认值翻转）。
        val settings = TerminalViewSettings().copy(pinchZoomEnabled = true)
        assertTrue(settings.pinchZoomEnabled)
    }

    // ─── 字号调节链路完整性（Slider 替代路径） ───

    @Test
    fun `clampFontSize keeps slider driven size in bounds`() {
        val settings = TerminalViewSettings(fontSizeSp = 14f, minFontSp = 8f, maxFontSp = 24f)
        assertEquals(8f, settings.clampFontSize(3f))
        assertEquals(24f, settings.clampFontSize(99f))
        assertEquals(16f, settings.clampFontSize(16f))
        // 非法输入（NaN/Infinity）回落到当前字号，不进渲染管线
        assertEquals(14f, settings.clampFontSize(Float.NaN))
    }

    @Test
    fun `stepFontSize drives slider stepping with clamping`() {
        val settings = TerminalViewSettings(fontSizeSp = 14f, minFontSp = 8f, maxFontSp = 24f)
        assertEquals(15f, settings.stepFontSize(+1))
        assertEquals(13f, settings.stepFontSize(-1))
        // 边界处不再外溢
        val atMax = TerminalViewSettings(fontSizeSp = 24f, minFontSp = 8f, maxFontSp = 24f)
        assertEquals(24f, atMax.stepFontSize(+2))
        val atMin = TerminalViewSettings(fontSizeSp = 8f, minFontSp = 8f, maxFontSp = 24f)
        assertEquals(8f, atMin.stepFontSize(-2))
    }

    @Test
    fun `withFontSize derives new settings without touching pinch flag`() {
        val base = TerminalViewSettings(fontSizeSp = 14f) // pinchZoomEnabled=false（默认）
        val bigger = base.withFontSize(18f)
        assertEquals(18f, bigger.fontSizeSp)
        assertFalse(
            "字号派生不得顺手恢复捏合 —— copy 链路保持 D1 默认",
            bigger.pinchZoomEnabled
        )
    }

    // ─── 防御性归一（既有行为回归锁） ───

    @Test
    fun `bad settings rejected before entering render pipeline`() {
        try {
            TerminalViewSettings(fontSizeSp = 0f)
            throw AssertionError("fontSizeSp=0 必须被 require 拒绝")
        } catch (expected: IllegalArgumentException) {
            // CI 单测锁定：坏配置不允许进入渲染管线
        }
        try {
            TerminalViewSettings(lineHeightFactor = 0.5f)
            throw AssertionError("lineHeightFactor<1 必须被 require 拒绝")
        } catch (expected: IllegalArgumentException) {
        }
    }
}
