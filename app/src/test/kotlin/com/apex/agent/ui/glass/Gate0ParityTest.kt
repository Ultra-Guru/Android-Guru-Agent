package com.apex.agent.ui.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ═══ Gate0ParityTest —— 实验室 ⇄ 生产同路径断言（Gate-0 G0-8 / D-10）═══
 *
 * D-10 根因：生产 GlassSurface 走 `LocalConversationSkin` 决定 MINIMAL
 * 短路，而 GlassLab 完全不消费它 —— 实验室验证的材质路径 ≠ 生产实际
 * 路径。修复把短路判定提取为 [GlassSkinGate]（纯函数），生产与并排台
 * （GlassLabBench 显式 provide LocalConversationSkin 预置态）读同一函数。
 *
 * 本测试锁三件事：
 *  1. 短路判定语义（会话优先 / 全局兜底 / minimal 命中）；
 *  2. [Gate0SkinPresets] 预置态覆盖「短路 / 不短路」两分支（并排台
 *     的预置面永远有效）；
 *  3. [GlassLadder] 降级阶梯在 API 28/31/33 × 短路 × 采样源矩阵下的
 *     真实命中档（P-4「降级档真实命中」的纯函数面）。
 */
class Gate0ParityTest {

    // ═══ 1. 短路判定（生产 GlassSurface 与实验室并排台共用）═══

    @Test
    fun `conversation skin wins over global style`() {
        // 会话快照优先：Coding 子树内会话极简 → 短路（哪怕全局是液态玻璃）
        assertTrue(
            GlassSkinGate.shortCircuitsToFlat(
                conversationUiStyleKey = "minimal",
                globalUiStyleKey = "liquid_glass"
            )
        )
        // 会话液态 → 不短路（哪怕全局是极简 —— 壳层回落语义的正交面）
        assertFalse(
            GlassSkinGate.shortCircuitsToFlat(
                conversationUiStyleKey = "liquid_glass",
                globalUiStyleKey = "minimal"
            )
        )
    }

    @Test
    fun `null conversation falls back to global style`() {
        // 实验室 / 壳层无会话快照 → 回落全局 LocalUiStyle
        assertTrue(
            GlassSkinGate.shortCircuitsToFlat(null, "minimal")
        )
        assertFalse(
            GlassSkinGate.shortCircuitsToFlat(null, "liquid_glass")
        )
    }

    @Test
    fun `unknown style keys never short circuit`() {
        // 未知 key 回退（UiStyle.fromKey 防御语义）：不命中 minimal 即玻璃
        assertFalse(GlassSkinGate.shortCircuitsToFlat("neon", "flat"))
        assertFalse(GlassSkinGate.shortCircuitsToFlat(null, ""))
    }

    // ═══ 2. 并排台预置态覆盖两分支（D-10）═══

    @Test
    fun `bench presets cover both short circuit branches`() {
        val results = Gate0SkinPresets.cases.map { (conversationKey, expected) ->
            GlassSkinGate.shortCircuitsToFlat(conversationKey, Gate0SkinPresets.GLOBAL_LIQUID)
        }
        // 至少一个短路、至少一个不短路 —— 并排台的 MINIMAL/LIQUID 切换
        // 永远能演示生产短路路径与玻璃路径的对照
        assertTrue("presets must include a flat case", results.any { it })
        assertTrue("presets must include a glass case", results.any { !it })
        // 预置期望与判定逐条一致
        Gate0SkinPresets.cases.forEach { (conversationKey, expected) ->
            assertEquals(
                "preset $conversationKey",
                expected,
                GlassSkinGate.shortCircuitsToFlat(conversationKey, Gate0SkinPresets.GLOBAL_LIQUID)
            )
        }
    }

    // ═══ 3. 降级阶梯（P-4：真实命中档，禁止静态谎言）═══

    @Test
    fun `ladder api 28 falls to frosted`() {
        val r = GlassLadder.read(
            apiLevel = 28, hasBackdrop = true,
            conversationUiStyleKey = null, globalUiStyleKey = "liquid_glass"
        )
        assertEquals(GlassRenderTier.FROSTED, r.renderTier)
        assertFalse(r.blurOn)
        assertFalse(r.lensOn)
    }

    @Test
    fun `ladder api 31 blur without lens`() {
        val r = GlassLadder.read(
            apiLevel = 31, hasBackdrop = true,
            conversationUiStyleKey = null, globalUiStyleKey = "liquid_glass"
        )
        assertEquals(GlassRenderTier.BACKDROP, r.renderTier)
        assertTrue(r.blurOn)
        assertFalse(r.lensOn)
    }

    @Test
    fun `ladder api 33 adds agsl lens`() {
        val r = GlassLadder.read(
            apiLevel = 33, hasBackdrop = true,
            conversationUiStyleKey = null, globalUiStyleKey = "liquid_glass"
        )
        assertEquals(GlassRenderTier.BACKDROP, r.renderTier)
        assertTrue(r.blurOn)
        assertTrue(r.lensOn)
    }

    @Test
    fun `ladder ambient when no backdrop source`() {
        // 纯色背景页面（无采样源）→ 程序化材质源（Ambient），不冒充 Backdrop
        val r = GlassLadder.read(
            apiLevel = 33, hasBackdrop = false,
            conversationUiStyleKey = null, globalUiStyleKey = "liquid_glass"
        )
        assertEquals(GlassRenderTier.AMBIENT, r.renderTier)
    }

    @Test
    fun `ladder minimal short circuit beats everything`() {
        // 短路优先于一切（连 API 33 + 真采样也整体扁平化）
        val r = GlassLadder.read(
            apiLevel = 33, hasBackdrop = true,
            conversationUiStyleKey = "minimal", globalUiStyleKey = "liquid_glass"
        )
        assertEquals(GlassRenderTier.PALE_FLAT, r.renderTier)
    }

    @Test
    fun `ladder boundaries are exact at 31 and 33`() {
        // 门槛是「>=」：30 仍 Frosted、31 起 Backdrop；32 无 lens、33 起 lens
        assertFalse(GlassLadder.read(30, true, null, "liquid_glass").blurOn)
        assertTrue(GlassLadder.read(31, true, null, "liquid_glass").blurOn)
        assertFalse(GlassLadder.read(32, true, null, "liquid_glass").lensOn)
        assertTrue(GlassLadder.read(33, true, null, "liquid_glass").lensOn)
    }

    // ═══ 附：对比度数学的量纲自检（P-4 数学底座可用性）═══

    @Test
    fun `contrast ratio anchors black white and identical`() {
        val black = 0xFF000000L
        val white = 0xFFFFFFFFL
        assertEquals(21.0, ContrastMath.contrastRatio(black, white), 0.01)
        assertEquals(21.0, ContrastMath.contrastRatio(white, black), 0.01)
        assertEquals(1.0, ContrastMath.contrastRatio(black, black), 0.001)
    }

    @Test
    fun `composite over is opaque source over`() {
        // 全不透明前景直接胜出；全透明前景原样透出
        assertEquals(0xFF112233L, ContrastMath.compositeOver(0xFF112233L, 0xFF445566L))
        assertEquals(0xFF445566L, ContrastMath.compositeOver(0x00112233L, 0xFF445566L))
        // 半透明白叠黑 → 中灰（alpha 通道归一）
        val half = ContrastMath.compositeOver(0x80FFFFFFL, 0xFF000000L)
        assertEquals(0xFFL, (half shr 24) and 0xFFL)
        assertEquals(128L, half and 0xFFL) // 0.5 × 255 ≈ 128（四舍五入）
    }

    @Test
    fun `delta e is zero for identical and large for inverted`() {
        val a = 0xFF336699L
        assertEquals(0.0, ContrastMath.deltaE76(a, a), 0.001)
        // 反相色（蓝底 → 黄字级别）ΔE 必须显著（> 30）
        val inverted = (a xor 0x00FFFFFFL) or 0xFF000000L
        assertTrue(ContrastMath.deltaE76(a, inverted) > 30.0)
        // 相近色 ΔE 小
        assertTrue(ContrastMath.deltaE76(0xFF336699L, 0xFF34679AL) < 2.0)
    }
}
