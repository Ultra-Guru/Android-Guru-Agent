package com.apex.agent.ui.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ═══ GlassLabGoldenTest —— Gate-0 P-5 回归金样（G0-7）═══
 *
 * 金样矩阵：配方 × API 档（28/31/33）× 短路态 → 「实际渲染配置」快照串。
 * 任何配方参数 / 降级规则的改动都会让快照串漂移即红 —— 这是 CI 可跑的
 * 数值金样（kotlinc 裸编译 + :app:testDebugUnitTest 双线）。
 *
 * 诚实边界：像素级截图金样（NIGHT/DAY × 档位 × API 的位图 diff）需要
 * instrumentation / Robolectric 通道，本仓库当前依赖矩阵不含；本测试
 * 锁定的是「参数 → 渲染管线配置」的完整映射（漂移检测的数值面），
 * 位图金样在 Gate-0 签字后随 instrumentation 通道补齐（见
 * docs/glass-lab-gate0.md §回归边界）。
 */
class GlassLabGoldenTest {

    /** 四个实验室对照档（Gate-0 §4 D-4：Dock / Card / Floating / Pale）。 */
    private val benchRecipes = listOf(
        GlassRecipeBook.Dock,
        GlassRecipeBook.Card,
        GlassRecipeBook.Floating,
        GlassRecipeBook.PaleLiquid
    )

    /**
     * 快照串：配方 → 效果链配置（跟随 GlassSurface 效果块的真实判定顺序：
     * vibrancy > 0 才挂 ColorFilter、blur 恒挂（API 31+）、lens 仅
     * lensHeight > 0 时挂、色散跟随 lens）× 阶梯命中档。
     *
     * 效果链只在玻璃管线档（BACKDROP / AMBIENT）真实运行 —— PALE_FLAT
     * 短路与 FROSTED 降级的快照里效果链必须显示为 off（实际渲染路径）。
     */
    private fun goldenString(recipe: GlassRecipe, apiLevel: Int, minimal: Boolean): String {
        val ladder = GlassLadder.read(
            apiLevel = apiLevel,
            hasBackdrop = true,
            conversationUiStyleKey = if (minimal) "minimal" else null,
            globalUiStyleKey = "liquid_glass"
        )
        val pipelineOn = ladder.renderTier == GlassRenderTier.BACKDROP ||
            ladder.renderTier == GlassRenderTier.AMBIENT
        val blur = if (pipelineOn) "blur=${recipe.blurRadiusDp.toInt()}" else "blur=off"
        val lens = when {
            !pipelineOn || !ladder.lensOn -> "lens=off"
            recipe.lensHeightDp <= 0f -> "lens=skip"
            else -> "lens=${recipe.lensHeightDp.toInt()}/${recipe.lensAmountDp.toInt()}${if (recipe.chromaticAberration) "+chroma" else ""}"
        }
        val vibrancy = if (pipelineOn && recipe.vibrancy > 0f) "vib=${recipe.vibrancy}" else "vib=off"
        return "${recipe.id}@$apiLevel:${ladder.renderTier}|$blur|$lens|$vibrancy|" +
            "tint=${recipe.tintAlpha}|edge=${recipe.edgeAlpha}|spec=${recipe.specularAlpha}|" +
            "elev=${recipe.elevationDp.toInt()}|scrim=${recipe.scrimAlpha}|corner=${recipe.cornerDp.toInt()}"
    }

    // ═══ 金样本体：实验室四档 × API 三档（玻璃态）═══

    @Test
    fun `golden matrix bench tiers by api level`() {
        val expected = mapOf(
            // API 28（无 RenderEffect）：整档 Frosted，效果链全灭（诚实降级）
            "dock@28" to "dock@28:FROSTED|blur=off|lens=off|vib=off|tint=0.42|edge=0.18|spec=0.1|elev=2|scrim=0.58|corner=34",
            "card@28" to "card@28:FROSTED|blur=off|lens=off|vib=off|tint=0.42|edge=0.18|spec=0.1|elev=2|scrim=0.58|corner=12",
            "floating@28" to "floating@28:FROSTED|blur=off|lens=off|vib=off|tint=0.46|edge=0.24|spec=0.14|elev=6|scrim=0.64|corner=50",
            "pale@28" to "pale@28:FROSTED|blur=off|lens=off|vib=off|tint=0.3|edge=0.2|spec=0.14|elev=1|scrim=0.44|corner=12",
            // API 31（blur 有 / lens 无）
            "dock@31" to "dock@31:BACKDROP|blur=14|lens=off|vib=off|tint=0.42|edge=0.18|spec=0.1|elev=2|scrim=0.58|corner=34",
            "card@31" to "card@31:BACKDROP|blur=12|lens=off|vib=off|tint=0.42|edge=0.18|spec=0.1|elev=2|scrim=0.58|corner=12",
            "floating@31" to "floating@31:BACKDROP|blur=16|lens=off|vib=off|tint=0.46|edge=0.24|spec=0.14|elev=6|scrim=0.64|corner=50",
            "pale@31" to "pale@31:BACKDROP|blur=10|lens=off|vib=off|tint=0.3|edge=0.2|spec=0.14|elev=1|scrim=0.44|corner=12",
            // API 33（blur + AGSL lens；Dock/Pale 零折射 → skip；Floating 色散）
            "dock@33" to "dock@33:BACKDROP|blur=14|lens=skip|vib=off|tint=0.42|edge=0.18|spec=0.1|elev=2|scrim=0.58|corner=34",
            "card@33" to "card@33:BACKDROP|blur=12|lens=8/16|vib=off|tint=0.42|edge=0.18|spec=0.1|elev=2|scrim=0.58|corner=12",
            "floating@33" to "floating@33:BACKDROP|blur=16|lens=10/20+chroma|vib=off|tint=0.46|edge=0.24|spec=0.14|elev=6|scrim=0.64|corner=50",
            "pale@33" to "pale@33:BACKDROP|blur=10|lens=skip|vib=off|tint=0.3|edge=0.2|spec=0.14|elev=1|scrim=0.44|corner=12"
        )
        benchRecipes.forEach { recipe ->
            listOf(28, 31, 33).forEach { api ->
                val key = "${recipe.id}@$api"
                val golden = expected.getValue(key)
                assertEquals(
                    "golden drift for $key — 参数或降级规则变化，请先在玻璃实验室确认视觉再更新金样（H-7）",
                    golden,
                    goldenString(recipe, api, minimal = false)
                )
            }
        }
    }

    // ═══ MINIMAL 短路态：任何配置都扁平化（D-10 生产路径）═══

    @Test
    fun `minimal snapshot flattens every tier at every api`() {
        benchRecipes.forEach { recipe ->
            listOf(28, 31, 33).forEach { api ->
                val golden = goldenString(recipe, api, minimal = true)
                assertTrue(
                    "minimal must flatten $golden",
                    golden.contains(":PALE_FLAT|blur=off|lens=off|vib=off")
                )
            }
        }
    }

    // ═══ 漂移灵敏度自检（金样测试自身的「能抓 bug」证明）═══

    @Test
    fun `golden detects a one step blur drift`() {
        // 把 Dock 的 blur 偷偷改 1dp → 金样串必变（漂移检测灵敏度）
        val drifted = GlassRecipeBook.Dock.copy(blurRadiusDp = 15f)
        assertNotEquals(
            goldenString(GlassRecipeBook.Dock, 31, minimal = false),
            goldenString(drifted, 31, minimal = false)
        )
        // 把色散打开 → Floating 之外的档也能被抓到
        val chromaDrift = GlassRecipeBook.Card.copy(chromaticAberration = true)
        assertNotEquals(
            goldenString(GlassRecipeBook.Card, 33, minimal = false),
            goldenString(chromaDrift, 33, minimal = false)
        )
    }
}
