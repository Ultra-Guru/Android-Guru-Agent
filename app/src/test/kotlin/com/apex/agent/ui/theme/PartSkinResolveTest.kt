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
        // v1.2（FR-16）：24 → 74，只追加不改（附录 F 全表）
        assertEquals(74, ids.size)
        // 原 24 个 ID 逐字保留（老设置 JSON 零迁移）
        val v11 = setOf(
            "stream.user_bubble", "stream.assistant_bubble", "stream.thinking",
            "stream.tool_capsule", "stream.verify_cycle", "stream.status_line",
            "stream.system_line", "stream.error", "stream.stop", "stream.file_chips",
            "stream.git_op", "stream.run_stats", "stream.generating",
            "input.bar", "input.queue_card", "input.question_card", "input.plan_confirm",
            "overlay.error_bar", "overlay.offline_banner", "overlay.update_banner",
            "overlay.tool_detail_sheet",
            "terminal.panel", "code.diff", "code.streaming_window"
        )
        assertTrue(ids.containsAll(v11))
    }

    // ═══ ⑤ v1.2 四级回退：部件 > 组 > 预设 > 全局（FR-17）═══

    @Test
    fun `group key sits between part override and preset`() {
        val ctx = SkinContext(globalDefault = PartSkin.CLASSIC_GLASS, minimal = false)
        // 组键 @g1 = FLAT：无部件覆盖时组级默认压过预设建议（思考卡预设浅白）
        val withGroup = PartSkinOverrides(groups = mapOf(SkinGroup.G1 to PartSkin.FLAT))
        assertEquals(PartSkin.FLAT, withGroup.resolve(CodingPart.STREAM_THINKING, ctx))
        // 部件显式覆盖仍最高
        val withBoth = withGroup.copy(
            map = mapOf(CodingPart.STREAM_THINKING to PartSkin.CLASSIC_GLASS)
        )
        assertEquals(
            PartSkin.CLASSIC_GLASS,
            withBoth.resolve(CodingPart.STREAM_THINKING, ctx)
        )
        // 无组键无覆盖 → 预设建议（浅白）
        assertEquals(
            PartSkin.PALE_LIQUID,
            PartSkinOverrides().resolve(CodingPart.STREAM_THINKING, ctx)
        )
    }

    @Test
    fun `group key does not leak across groups`() {
        val ctx = SkinContext(globalDefault = PartSkin.CLASSIC_GLASS, minimal = false)
        // @g2 = FLAT 不影响 G1 部件（不越组）
        val withG2 = PartSkinOverrides(groups = mapOf(SkinGroup.G2 to PartSkin.FLAT))
        assertEquals(
            PartSkin.PALE_LIQUID,
            withG2.resolve(CodingPart.STREAM_THINKING, ctx)
        )
        // G2 成员吃到组值
        assertEquals(
            PartSkin.FLAT,
            withG2.resolve(CodingPart.STREAM_THINKING_HEADER, ctx)
        )
    }

    @Test
    fun `group expansion filters by allow matrix`() {
        // @g2 = NEON：G2 里只有 code_block（TERMINAL 族）允许 NEON —— 组展开
        // 不得把 NEON 写给 EMBEDDED 族成员
        val parsed = PartSkinOverrides.fromPersisted(
            mapOf("@g2" to "neon_terminal"),
            null
        )
        assertEquals(PartSkin.NEON_TERMINAL, parsed.resolve(CodingPart.STREAM_CODE_BLOCK, glassCtx))
        // EMBEDDED 族成员不受组值影响 → 走预设（EMBEDDED）
        assertEquals(
            PartSkin.EMBEDDED,
            parsed.resolve(CodingPart.STREAM_THINKING_HEADER, glassCtx)
        )
    }

    @Test
    fun `fromPersisted parses group and part keys together`() {
        // 老数据（24 部件键）+ 新组键混合 → 零丢弃、零崩溃
        val parsed = PartSkinOverrides.fromPersisted(
            mapOf(
                "stream.thinking" to "pale_liquid",   // 老 24 项之一
                "@g1" to "flat",                       // v1.2 组键
                "@G3" to " CLASSIC_GLASS ",            // 大小写/空白容错
                "@g99" to "flat",                      // 未知组键 → 丢弃
                "no.such.part" to "flat"               // 未知部件 → 丢弃
            ),
            null
        )
        assertEquals(mapOf(CodingPart.STREAM_THINKING to PartSkin.PALE_LIQUID), parsed.map)
        assertEquals(
            mapOf(SkinGroup.G1 to PartSkin.FLAT, SkinGroup.G3 to PartSkin.CLASSIC_GLASS),
            parsed.groups
        )
    }

    @Test
    fun `toPersisted roundtrips parts and groups`() {
        val original = PartSkinOverrides(
            map = mapOf(CodingPart.STREAM_THINKING to PartSkin.PALE_LIQUID),
            groups = mapOf(SkinGroup.G5 to PartSkin.FLAT),
            glow = 0.6f
        )
        val reparsed = PartSkinOverrides.fromPersisted(original.toPersisted(), 0.6f)
        assertEquals(original.map, reparsed.map)
        assertEquals(original.groups, reparsed.groups)
    }

    // ═══ ⑥ H-4 嵌套退化（FR-19 / §6.6）═══

    @Test
    fun `glass parent forces child to embedded unless explicit`() {
        val ctx = SkinContext(globalDefault = PartSkin.CLASSIC_GLASS, minimal = false)
        val overrides = PartSkinOverrides()
        // 父 = PALE_LIQUID、子解析为 PALE（预设）→ 自动退化 EMBEDDED
        assertEquals(
            PartSkin.EMBEDDED,
            overrides.resolveEffective(CodingPart.STREAM_THINKING_HEADER, ctx, PartSkin.PALE_LIQUID)
        )
        // 父 = CLASSIC_GLASS、子解析为 SOLID（头像预设）→ 同样退化
        assertEquals(
            PartSkin.EMBEDDED,
            overrides.resolveEffective(CodingPart.STREAM_USER_AVATAR, ctx, PartSkin.CLASSIC_GLASS)
        )
        // 用户显式覆盖 → 尊重（自担嵌套风险）
        val explicit = PartSkinOverrides(
            map = mapOf(CodingPart.STREAM_THINKING_HEADER to PartSkin.PALE_LIQUID)
        )
        assertEquals(
            PartSkin.PALE_LIQUID,
            explicit.resolveEffective(CodingPart.STREAM_THINKING_HEADER, ctx, PartSkin.PALE_LIQUID)
        )
        // 父 = FLAT（非玻璃）→ 不退化，子按原解析
        assertEquals(
            PartSkin.PALE_LIQUID,
            overrides.resolveEffective(CodingPart.STREAM_THINKING, ctx, PartSkin.FLAT)
        )
        // 顶层部件（parentId == null）永不退化
        assertEquals(
            PartSkin.PALE_LIQUID,
            overrides.resolveEffective(CodingPart.STREAM_THINKING, ctx, PartSkin.PALE_LIQUID)
        )
        // 无父上下文（parentSkin = null）→ 不退化
        assertEquals(
            PartSkin.EMBEDDED,
            overrides.resolveEffective(CodingPart.STREAM_THINKING_HEADER, ctx, null)
        )
    }

    @Test
    fun `embedded family defaults and matrix gates`() {
        // EMBEDDED 族：EMBEDDED/FLAT/PALE ✔，CLASSIC/SOLID/NEON ✗
        assertTrue(CodingPart.STREAM_THINKING_HEADER.allows(PartSkin.EMBEDDED))
        assertTrue(CodingPart.STREAM_THINKING_HEADER.allows(PartSkin.FLAT))
        assertTrue(CodingPart.STREAM_THINKING_HEADER.allows(PartSkin.PALE_LIQUID))
        assertFalse(CodingPart.STREAM_THINKING_HEADER.allows(PartSkin.CLASSIC_GLASS))
        assertFalse(CodingPart.STREAM_TOOL_CHIP.allows(PartSkin.SOLID))
        // CHROME 族（G6/G7 外壳）：PALE/CLASSIC/FLAT/EMBEDDED ✔
        assertTrue(CodingPart.SHELL_TOP_BAR.allows(PartSkin.CLASSIC_GLASS))
        assertTrue(CodingPart.PAGE_HISTORY_CARD.allows(PartSkin.PALE_LIQUID))
        assertFalse(CodingPart.PAGE_HISTORY_CARD.allows(PartSkin.SOLID))
        // isContainer / isGlassLike
        assertFalse(PartSkin.EMBEDDED.isContainer)
        assertTrue(PartSkin.PALE_LIQUID.isContainer)
        assertTrue(PartSkin.PALE_LIQUID.isGlassLike)
        assertFalse(PartSkin.FLAT.isGlassLike)
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
