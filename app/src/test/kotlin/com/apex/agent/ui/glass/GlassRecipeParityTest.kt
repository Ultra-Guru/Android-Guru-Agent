package com.apex.agent.ui.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ═══ GlassRecipeParityTest —— Gate-0 P-1 同源锁（G0-1 / G0-4 / H-7）═══
 *
 * 期望值全部从 Gate-0 规格书附录与 main 分支 GlassStyle companion 的
 * 原始数值逐字段抄录 —— GlassStyle 九档已改为从 GlassRecipeBook 生成，
 * 本测试保证「配方真源」与「规格书基线」逐字段相等：
 *  - 任何一档参数漂移（含视觉上不可察觉的微调）即红；
 *  - 调参必须先改 GlassRecipeBook（单一真源）再看本测试 —— 这就是
 *    H-7「禁止直接改 GlassStyle 常量绕过实验室」的机械化落点。
 *
 * 纯 JVM（GlassRecipe.kt 零 Compose 依赖）—— 在 :app:testDebugUnitTest
 * 与 Gate-0 workflow（kotlinc 裸编译）双线运行。
 */
class GlassRecipeParityTest {

    // ═══ 生产九档：逐字段锁定（抄录自规格书核对基线 main 的 GlassStyle）═══

    private fun assertRecipe(
        recipe: GlassRecipe,
        id: String,
        tier: GlassTier,
        blur: Float,
        tint: Float,
        lensH: Float,
        lensA: Float,
        chroma: Boolean,
        vibrancy: Float,
        edge: Float,
        specular: Float,
        elevation: Float,
        pressed: Float,
        scrim: Float
    ) {
        assertEquals("id($id)", id, recipe.id)
        assertEquals("tier($id)", tier, recipe.tier)
        assertEquals("blur($id)", blur, recipe.blurRadiusDp, 0f)
        assertEquals("tint($id)", tint, recipe.tintAlpha, 0f)
        assertEquals("lensHeight($id)", lensH, recipe.lensHeightDp, 0f)
        assertEquals("lensAmount($id)", lensA, recipe.lensAmountDp, 0f)
        assertEquals("chroma($id)", chroma, recipe.chromaticAberration)
        assertEquals("vibrancy($id)", vibrancy, recipe.vibrancy, 0f)
        assertEquals("edge($id)", edge, recipe.edgeAlpha, 0f)
        assertEquals("specular($id)", specular, recipe.specularAlpha, 0f)
        assertEquals("elevation($id)", elevation, recipe.elevationDp, 0f)
        assertEquals("pressed($id)", pressed, recipe.pressedScale, 0f)
        assertEquals("scrim($id)", scrim, recipe.scrimAlpha, 0f)
    }

    @Test
    fun `subtle tier parity`() = assertRecipe(
        GlassRecipeBook.Subtle, "subtle", GlassTier.Subtle,
        blur = 8f, tint = 0.30f, lensH = 0f, lensA = 0f, chroma = false,
        vibrancy = 0f, edge = 0.10f, specular = 0.05f,
        elevation = 0f, pressed = 1f, scrim = 0.42f
    )

    @Test
    fun `control tier parity`() = assertRecipe(
        GlassRecipeBook.Control, "control", GlassTier.Control,
        blur = 10f, tint = 0.38f, lensH = 6f, lensA = 12f, chroma = false,
        vibrancy = 0f, edge = 0.16f, specular = 0.08f,
        elevation = 1f, pressed = 0.94f, scrim = 0.52f
    )

    @Test
    fun `card tier parity`() = assertRecipe(
        GlassRecipeBook.Card, "card", GlassTier.Card,
        blur = 12f, tint = 0.42f, lensH = 8f, lensA = 16f, chroma = false,
        vibrancy = 0f, edge = 0.18f, specular = 0.10f,
        elevation = 2f, pressed = 1f, scrim = 0.58f
    )

    @Test
    fun `navigation tier parity`() = assertRecipe(
        GlassRecipeBook.Navigation, "navigation", GlassTier.Navigation,
        blur = 10f, tint = 0.34f, lensH = 5f, lensA = 10f, chroma = false,
        vibrancy = 0f, edge = 0.13f, specular = 0.06f,
        elevation = 0f, pressed = 0.98f, scrim = 0.46f
    )

    @Test
    fun `floating tier parity`() = assertRecipe(
        GlassRecipeBook.Floating, "floating", GlassTier.Floating,
        blur = 16f, tint = 0.46f, lensH = 10f, lensA = 20f, chroma = true,
        vibrancy = 0f, edge = 0.24f, specular = 0.14f,
        elevation = 6f, pressed = 0.92f, scrim = 0.64f
    )

    @Test
    fun `dialog tier parity`() = assertRecipe(
        GlassRecipeBook.Dialog, "dialog", GlassTier.Dialog,
        blur = 20f, tint = 0.52f, lensH = 10f, lensA = 20f, chroma = false,
        vibrancy = 0f, edge = 0.24f, specular = 0.12f,
        elevation = 12f, pressed = 1f, scrim = 0.74f
    )

    @Test
    fun `strong tier parity`() = assertRecipe(
        GlassRecipeBook.Strong, "strong", GlassTier.Strong,
        blur = 24f, tint = 0.58f, lensH = 12f, lensA = 24f, chroma = true,
        vibrancy = 0f, edge = 0.28f, specular = 0.16f,
        elevation = 4f, pressed = 1f, scrim = 0.82f
    )

    @Test
    fun `bubble tier parity`() = assertRecipe(
        GlassRecipeBook.Bubble, "bubble", GlassTier.Card,
        blur = 10f, tint = 0.34f, lensH = 6f, lensA = 12f, chroma = false,
        vibrancy = 0f, edge = 0.20f, specular = 0.12f,
        elevation = 1f, pressed = 1f, scrim = 0.48f
    )

    @Test
    fun `pale liquid tier parity`() = assertRecipe(
        GlassRecipeBook.PaleLiquid, "pale", GlassTier.Card,
        blur = 10f, tint = 0.30f, lensH = 0f, lensA = 0f, chroma = false,
        vibrancy = 0f, edge = 0.20f, specular = 0.14f,
        elevation = 1f, pressed = 1f, scrim = 0.44f
    )

    // ═══ Dock 新配方：Gate-0 §4 D-4 修复值 ═══

    @Test
    fun `dock recipe matches gate0 spec`() = assertRecipe(
        GlassRecipeBook.Dock, "dock", GlassTier.Navigation,
        blur = 14f, tint = 0.42f, lensH = 0f, lensA = 0f, chroma = false,
        vibrancy = 0f, edge = 0.18f, specular = 0.10f,
        elevation = 2f, pressed = 1f, scrim = 0.58f
    )

    @Test
    fun `dock corner is capsule 34dp and never refracts`() {
        // D-4：Floating 档的 10/20dp 折射与色散正是「框中框放大器」根因
        assertEquals(34f, GlassRecipeBook.Dock.cornerDp, 0f)
        assertFalse(GlassRecipeBook.Dock.chromaticAberration)
        assertEquals(0f, GlassRecipeBook.Dock.lensHeightDp, 0f)
        assertEquals(0f, GlassRecipeBook.Dock.lensAmountDp, 0f)
    }

    @Test
    fun `dock differs from floating`() {
        // Dock 不得再误用 FAB 档（D-4 回归锁）
        assertFalse(GlassRecipeBook.Dock.blurRadiusDp == GlassRecipeBook.Floating.blurRadiusDp)
        assertFalse(GlassRecipeBook.Dock.elevationDp == GlassRecipeBook.Floating.elevationDp)
        assertFalse(GlassRecipeBook.Dock.chromaticAberration)
        assertTrue(GlassRecipeBook.Floating.chromaticAberration)
    }

    // ═══ Book 结构完整性 ═══

    @Test
    fun `book ids are unique and cover seven tiers`() {
        val ids = GlassRecipeBook.all.map { it.id }
        assertEquals(ids.size.toLong(), ids.toSet().size.toLong())
        // 七档枚举每一档至少有一个配方承载其语义
        GlassTier.entries.forEach { tier ->
            assertTrue(
                "tier $tier must be represented",
                GlassRecipeBook.all.any { it.tier == tier }
            )
        }
    }

    @Test
    fun `book lookup by id`() {
        assertNotNull(GlassRecipeBook.byId("dock"))
        assertEquals(GlassRecipeBook.Dock, GlassRecipeBook.byId("dock"))
        assertNull("unknown id folds to null (defensive)", GlassRecipeBook.byId("nope"))
        assertNull(GlassRecipeBook.byId(null))
    }

    @Test
    fun `alpha ranges are within bounds zero to one`() {
        GlassRecipeBook.all.forEach { r ->
            assertTrue("tint ${r.id}", r.tintAlpha in 0f..1f)
            assertTrue("edge ${r.id}", r.edgeAlpha in 0f..1f)
            assertTrue("specular ${r.id}", r.specularAlpha in 0f..1f)
            assertTrue("scrim ${r.id}", r.scrimAlpha in 0f..1f)
            assertTrue("glow ${r.id}", r.glow in 0f..1f)
            assertTrue("vibrancy ${r.id}", r.vibrancy in 0f..1f)
            assertTrue("pressed ${r.id}", r.pressedScale in 0.5f..1f)
        }
    }

    // ═══ P-3 导出闭环：Kotlin / JSON / 回读 ═══

    @Test
    fun `export kotlin is pasteable recipe block`() {
        val code = GlassRecipeBook.Dock.exportKotlin()
        assertTrue(code.startsWith("val Dock = GlassRecipe("))
        assertTrue(code.trimEnd().endsWith(")"))
        // 关键字段逐个在场（粘进 GlassRecipeBook 后语义完整）
        listOf(
            "id = \"dock\"",
            "tier = GlassTier.Navigation",
            "blurRadiusDp = 14f",
            "tintAlpha = 0.42f",
            "lensHeightDp = 0f",
            "chromaticAberration = false",
            "elevationDp = 2f",
            "edgeAlpha = 0.18f",
            "cornerDp = 34f",
            "partSkinKey = \"classic_glass\""
        ).forEach { fragment ->
            assertTrue("export must contain: $fragment", code.contains(fragment))
        }
    }

    @Test
    fun `json round trip is lossless for every recipe`() {
        GlassRecipeBook.all.forEach { recipe ->
            val restored = GlassRecipe.fromJson(recipe.exportJson())
            assertEquals("round trip ${recipe.id}", recipe, restored)
        }
    }

    @Test
    fun `json parser folds garbage to null`() {
        assertNull(GlassRecipe.fromJson(""))
        assertNull(GlassRecipe.fromJson("not json at all"))
        assertNull(GlassRecipe.fromJson("{\"tier\":\"Card\"}")) // 缺 id → null
        // 容错：非法数值回退默认而非抛异常（防御式 IO 纪律）
        val partial = GlassRecipe.fromJson("{\"id\":\"x\",\"blurRadiusDp\":\"oops\"}")
        assertNotNull(partial)
        assertEquals(12f, partial!!.blurRadiusDp, 0f)
    }

    // ═══ P-1：标本参数标签从配方读取（单一排版出口）═══

    @Test
    fun `param lines expose blur tint lens and corner`() {
        val lines = GlassRecipeBook.Dock.paramLines()
        assertEquals(6, lines.size.toLong())
        assertTrue(lines[0].contains("blur=14dp"))
        assertTrue(lines[1].contains("tint=42%"))
        assertTrue(lines[2].contains("lens=0/0dp"))
        assertTrue(lines[3].contains("edge=18%"))
        assertTrue(lines[4].contains("elev=2dp"))
        assertTrue(lines[5].contains("corner=34dp"))
        assertTrue(lines[5].contains("skin=classic_glass"))
    }
}
