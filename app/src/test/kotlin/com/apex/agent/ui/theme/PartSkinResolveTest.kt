package com.apex.agent.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PartSkinOverrides.resolve] 三级回退链 + 持久化容错单测（PartSkin spec §11.1）。
 *
 * 覆盖清单：
 *  - 三级回退优先级：override > preset > globalDefault（ctx.presetSkin 优先于
 *    快照预设）；FOLLOW 穿透；
 *  - 允许矩阵外组合 → 丢弃（解析时回退 FOLLOW 语义，不抛异常）；
 *  - 反序列化容错：空 Map / null / 未知 key / 未知值 / 大小写与空白 /
 *    显式 FOLLOW 稀疏化 / glow 越界 clamp；
 *  - 稀疏语义：「重新跟随 = 移除键」由 fromPersisted 落地；
 *  - 预设生成可复现、幂等；「全部浅白」只写允许集（不写 terminal.panel）。
 *
 * 全部纯 JVM —— theme 包零反向依赖（NFR-8）的直接证据：本测试不引入
 * 任何 Android / glass / screen 依赖。
 */
class PartSkinResolveTest {

    private val glassCtx = SkinContext(globalDefault = PartSkin.CLASSIC_GLASS, minimal = false)
    private val minimalCtx = SkinContext(globalDefault = PartSkin.FLAT, minimal = true)

    // ═══ ① 三级回退优先级 ═══

    @Test
    fun `override beats preset beats global`() {
        val ctx = SkinContext(globalDefault = PartSkin.CLASSIC_GLASS, minimal = false)
        // 无覆盖无建议 → 全局默认
        assertEquals(PartSkin.CLASSIC_GLASS, PartSkinOverrides().resolve(CodingPart.STREAM_GIT_OP, ctx))
        // 预设建议（§4：思考卡默认浅白）覆盖全局默认
        assertEquals(
            PartSkin.PALE_LIQUID,
            PartSkinOverrides().resolve(CodingPart.STREAM_THINKING, ctx)
        )
        // 显式覆盖（经典玻璃）压过预设建议（浅白）
        val withOverride = PartSkinOverrides(
            map = mapOf(CodingPart.STREAM_THINKING to PartSkin.CLASSIC_GLASS)
        )
        assertEquals(
            PartSkin.CLASSIC_GLASS,
            withOverride.resolve(CodingPart.STREAM_THINKING, ctx)
        )
        // ctx.presetSkin（组装方写入）优先于快照预设建议
        val ctxWithPreset = ctx.copy(presetSkin = PartSkin.FLAT)
        assertEquals(
            PartSkin.FLAT,
            PartSkinOverrides().resolve(CodingPart.STREAM_THINKING, ctxWithPreset)
        )
        // 但显式覆盖仍然最高
        assertEquals(
            PartSkin.CLASSIC_GLASS,
            withOverride.resolve(CodingPart.STREAM_THINKING, ctxWithPreset)
        )
    }

    @Test
    fun `follow passthrough falls to global default`() {
        // git_op / diff 的 §4 建议是 FOLLOW（无建议）→ 穿透到全局 UiStyle 默认
        assertEquals(PartSkin.CLASSIC_GLASS, PartSkinOverrides().resolve(CodingPart.STREAM_GIT_OP, glassCtx))
        assertEquals(PartSkin.CLASSIC_GLASS, PartSkinOverrides().resolve(CodingPart.CODE_DIFF, glassCtx))
        assertEquals(PartSkin.FLAT, PartSkinOverrides().resolve(CodingPart.CODE_DIFF, minimalCtx))
    }

    @Test
    fun `minimal context resolves flat for follow parts`() {
        // MINIMAL：无建议部件走 FLAT（PALE 建议部件的 PaleFlat 降级在渲染层）
        assertEquals(PartSkin.FLAT, PartSkinOverrides().resolve(CodingPart.STREAM_GIT_OP, minimalCtx))
        // PALE 建议在 MINIMAL 下照常解析为 PALE_LIQUID（NFR-5 降级是渲染层职责）
        assertEquals(
            PartSkin.PALE_LIQUID,
            PartSkinOverrides().resolve(CodingPart.STREAM_THINKING, minimalCtx)
        )
    }

    // ═══ ② 允许矩阵 ═══

    @Test
    fun `out-of-matrix override is ignored not thrown`() {
        // user_bubble 不允许 NEON_TERMINAL；terminal.panel 不允许 PALE/SOLID
        val invalid = PartSkinOverrides(
            map = mapOf(
                CodingPart.STREAM_USER_BUBBLE to PartSkin.NEON_TERMINAL,
                CodingPart.TERMINAL_PANEL to PartSkin.SOLID
            )
        )
        // map 中非法组合被 resolve 忽略 → 走预设建议（SOLID / NEON）
        assertEquals(PartSkin.SOLID, invalid.resolve(CodingPart.STREAM_USER_BUBBLE, glassCtx))
        assertEquals(PartSkin.NEON_TERMINAL, invalid.resolve(CodingPart.TERMINAL_PANEL, glassCtx))
    }

    @Test
    fun `matrix gates per family`() {
        // CARD 族：PALE/CLASSIC/FLAT ✔，SOLID/NEON ✗
        assertTrue(CodingPart.STREAM_THINKING.allows(PartSkin.PALE_LIQUID))
        assertTrue(CodingPart.STREAM_THINKING.allows(PartSkin.CLASSIC_GLASS))
        assertTrue(CodingPart.STREAM_THINKING.allows(PartSkin.FLAT))
        assertFalse(CodingPart.STREAM_THINKING.allows(PartSkin.SOLID))
        assertFalse(CodingPart.STREAM_THINKING.allows(PartSkin.NEON_TERMINAL))
        // BUBBLE 族：+ SOLID
        assertTrue(CodingPart.STREAM_USER_BUBBLE.allows(PartSkin.SOLID))
        // TERMINAL 族：CLASSIC/FLAT/NEON ✔，PALE/SOLID ✗
        assertTrue(CodingPart.TERMINAL_PANEL.allows(PartSkin.NEON_TERMINAL))
        assertFalse(CodingPart.TERMINAL_PANEL.allows(PartSkin.PALE_LIQUID))
        assertFalse(CodingPart.CODE_STREAMING_WINDOW.allows(PartSkin.SOLID))
    }

    // ═══ ③ 反序列化容错（§10 / §11.1）═══

    @Test
    fun `empty and null maps parse to defaults`() {
        assertEquals(PartSkinOverrides(), PartSkinOverrides.fromPersisted(null, null))
        assertEquals(PartSkinOverrides(), PartSkinOverrides.fromPersisted(emptyMap(), null))
        // 空 Map → §4 新默认（思考卡浅白）
        val parsed = PartSkinOverrides.fromPersisted(emptyMap(), 0.35f)
        assertEquals(PartSkin.PALE_LIQUID, parsed.resolve(CodingPart.STREAM_THINKING, glassCtx))
    }

    @Test
    fun `unknown keys and values are dropped`() {
        val parsed = PartSkinOverrides.fromPersisted(
            mapOf(
                "stream.thinking" to "pale_liquid",
                "no.such.part" to "flat",          // 未知 key → 丢弃
                "stream.stop" to "liquid_gold",     // 未知值 → 丢弃
                "stream.error" to null.toString()   // "null" 字符串 → 未知值 → 丢弃
            ),
            null
        )
        assertEquals(mapOf(CodingPart.STREAM_THINKING to PartSkin.PALE_LIQUID), parsed.map)
        // 被丢弃部件回到预设建议（错误卡默认浅白）
        assertEquals(PartSkin.PALE_LIQUID, parsed.resolve(CodingPart.STREAM_ERROR, glassCtx))
    }

    @Test
    fun `case and whitespace tolerant parsing`() {
        val parsed = PartSkinOverrides.fromPersisted(
            mapOf("  STREAM.THINKING " to "  PALE_Liquid "),
            null
        )
        assertEquals(
            PartSkin.PALE_LIQUID,
            parsed.resolve(CodingPart.STREAM_THINKING, glassCtx)
        )
    }

    @Test
    fun `explicit follow entries are sparsed away`() {
        // 显式 FOLLOW 等价缺席（稀疏语义：重新跟随 = 移除键）
        val parsed = PartSkinOverrides.fromPersisted(
            mapOf(CodingPart.STREAM_THINKING.id to PartSkin.FOLLOW.key),
            null
        )
        assertTrue(parsed.map.isEmpty())
        assertEquals(
            PartSkin.PALE_LIQUID,
            parsed.resolve(CodingPart.STREAM_THINKING, glassCtx)
        )
    }

    @Test
    fun `glow out of range is clamped`() {
        assertEquals(0.35f, PartSkinOverrides.fromPersisted(null, null).glow, 0f)
        assertEquals(1f, PartSkinOverrides.fromPersisted(null, 42f).glow, 0f)
        assertEquals(0f, PartSkinOverrides.fromPersisted(null, -0.5f).glow, 0f)
        assertEquals(0.8f, PartSkinOverrides.fromPersisted(null, 0.8f).glow, 0f)
    }

    @Test
    fun `invalid combo stored in json is dropped at parse`() {
        // 持久化里塞了非法组合（终端选 PALE）→ 解析时丢弃，不报错
        val parsed = PartSkinOverrides.fromPersisted(
            mapOf(
                CodingPart.TERMINAL_PANEL.id to PartSkin.PALE_LIQUID.key,
                CodingPart.CODE_DIFF.id to PartSkin.SOLID.key
            ),
            null
        )
        assertTrue(parsed.map.isEmpty())
        // 终端照常走预设建议（NEON）
        assertEquals(PartSkin.NEON_TERMINAL, parsed.resolve(CodingPart.TERMINAL_PANEL, glassCtx))
    }

    // ═══ ④ 稀疏语义与预设生成（§11.1）═══

    @Test
    fun `pale_all writes only the allowed set`() {
        val overrides = PartSkinPreset.PALE_ALL.generateOverrides()
        // 终端 / 代码窗（NEON 独占族）绝不入表
        assertFalse(overrides.containsKey(CodingPart.TERMINAL_PANEL))
        assertFalse(overrides.containsKey(CodingPart.CODE_STREAMING_WINDOW))
        assertFalse(overrides.containsKey(CodingPart.CODE_DIFF))
        // 允许 PALE 的部件全部浅白
        assertTrue(overrides.values.all { it == PartSkin.PALE_LIQUID })
        assertTrue(overrides.containsKey(CodingPart.STREAM_THINKING))
        assertTrue(overrides.containsKey(CodingPart.INPUT_BAR))
    }

    @Test
    fun `preset generation is reproducible and idempotent`() {
        for (preset in PartSkinPreset.entries) {
            assertEquals(preset.generateOverrides(), preset.generateOverrides())
            // 幂等：把生成表作为「输入」再解析，逐项保持
            val serialized = preset.generateOverrides()
                .entries.associate { (part, skin) -> part.id to skin.key }
            val reparsed = PartSkinOverrides.fromPersisted(serialized, null)
            assertEquals(preset.generateOverrides(), reparsed.map)
        }
    }

    @Test
    fun `default preset mirrors spec table`() {
        // §4「新默认皮肤」列抽查：思考卡 / 生成中卡 = PALE；用户气泡 = SOLID；
        // 状态行 / 文件 chips = FLAT；输入栏 / 工具详情弹层 = CLASSIC；
        // git_op / diff = FOLLOW（null 建议）；终端 = NEON
        assertNull(PartSkinPreset.DEFAULT.skinFor(CodingPart.STREAM_GIT_OP))
        assertNull(PartSkinPreset.DEFAULT.skinFor(CodingPart.CODE_DIFF))
        assertEquals(PartSkin.PALE_LIQUID, PartSkinPreset.DEFAULT.skinFor(CodingPart.STREAM_THINKING))
        assertEquals(PartSkin.PALE_LIQUID, PartSkinPreset.DEFAULT.skinFor(CodingPart.STREAM_GENERATING))
        assertEquals(PartSkin.SOLID, PartSkinPreset.DEFAULT.skinFor(CodingPart.STREAM_USER_BUBBLE))
        assertEquals(PartSkin.FLAT, PartSkinPreset.DEFAULT.skinFor(CodingPart.STREAM_STATUS_LINE))
        assertEquals(PartSkin.FLAT, PartSkinPreset.DEFAULT.skinFor(CodingPart.STREAM_FILE_CHIPS))
        assertEquals(PartSkin.CLASSIC_GLASS, PartSkinPreset.DEFAULT.skinFor(CodingPart.INPUT_BAR))
        assertEquals(PartSkin.CLASSIC_GLASS, PartSkinPreset.DEFAULT.skinFor(CodingPart.OVERLAY_TOOL_DETAIL_SHEET))
        assertEquals(PartSkin.NEON_TERMINAL, PartSkinPreset.DEFAULT.skinFor(CodingPart.TERMINAL_PANEL))
    }

    @Test
    fun `registry ids are stable and unique`() {
        // ID 即持久化 key：全小写、group.name 格式、无重复（FR-1 只增不改）
        val ids = CodingPart.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it == it.lowercase() && it.contains('.') })
        assertEquals(24, ids.size)
    }

    @Test
    fun `fromId and fromKey tolerate garbage`() {
        assertEquals(CodingPart.STREAM_THINKING, CodingPart.fromId(" stream.thinking "))
        assertNull(CodingPart.fromId("stream.thinkin"))
        assertNull(CodingPart.fromId(null))
        assertEquals(PartSkin.PALE_LIQUID, PartSkin.fromKey("PALE_LIQUID"))
        assertNull(PartSkin.fromKey("hologram"))
        assertNull(PartSkin.fromKey(null))
        // 预设 key 未知回退 DEFAULT（防御式，绝不抛）
        assertEquals(PartSkinPreset.DEFAULT, PartSkinPreset.fromKey("bogus"))
    }

    @Test
    fun `snapshots with different maps are not equal`() {
        // observePartSkins 的变更检测依赖 data class equals
        val a = PartSkinOverrides(map = mapOf(CodingPart.STREAM_THINKING to PartSkin.FLAT))
        val b = PartSkinOverrides(map = mapOf(CodingPart.STREAM_THINKING to PartSkin.PALE_LIQUID))
        val c = PartSkinOverrides(map = mapOf(CodingPart.STREAM_THINKING to PartSkin.PALE_LIQUID))
        assertNotEquals(a, b)
        assertEquals(b, c)
    }
}
