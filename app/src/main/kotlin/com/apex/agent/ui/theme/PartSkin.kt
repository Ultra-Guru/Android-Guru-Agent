package com.apex.agent.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * ═══════════════════════════════════════════════════════════════
 *  PartSkin — 部件级皮肤注册表（Spec v1.0 · 2026-11-08）
 * ═══════════════════════════════════════════════════════════════
 *
 * 在「全局 UiStyle（MINIMAL / LIQUID_GLASS）」与「输出风格预设
 * （TIMELINE / RIKKA）」之上补一层**部件级粒度**：每个卡片/气泡/工具块/
 * 输入区可独立选材质，带「部件覆盖 → 输出风格预设 → 全局 UiStyle」
 * 三级回退（FR-3）。
 *
 * 依赖纪律：本文件是纯模型（枚举 + 不可变快照 + 纯函数回退链），
 * **禁止反向 import ui/glass / ui.screen.*** —— SkinContext 由
 * ui/screen/code/PartSkinOps.kt 组装注入，theme 包保持可独立复用
 * （GlassLab / 预览页 / JVM 单测，NFR-8）。
 *
 * 持久化约定（FR-10）：`AgentSettings.partSkins` 是稀疏
 * `Map<String, String>`（`"stream.thinking" -> "pale_liquid"`），
 * **缺席 = 跟随上级**（与 roleSkillToggles 同款稀疏语义）；旧 JSON 无该
 * 字段 → 空 Map → 走 §4 新默认皮肤列，零迁移。
 */

/**
 * 部件族 —— §5 允许矩阵的载体。
 *
 * | 族 | PALE_LIQUID | CLASSIC_GLASS | FLAT | SOLID | NEON_TERMINAL |
 * |---|---|---|---|---|---|
 * | CARD（时间轴卡片族 / 状态行 / 输入与弹层） | ✔ | ✔ | ✔ | — | — |
 * | BUBBLE（用户气泡） | ✔ | ✔ | ✔ | ✔ | — |
 * | TERMINAL（终端 / 代码窗） | — | ✔ | ✔ | — | ✔ |
 */
enum class SkinFamily(val allowed: Set<PartSkin>) {
    CARD(setOf(PartSkin.PALE_LIQUID, PartSkin.CLASSIC_GLASS, PartSkin.FLAT)),
    BUBBLE(setOf(PartSkin.PALE_LIQUID, PartSkin.CLASSIC_GLASS, PartSkin.FLAT, PartSkin.SOLID)),
    TERMINAL(setOf(PartSkin.CLASSIC_GLASS, PartSkin.FLAT, PartSkin.NEON_TERMINAL))
}

/**
 * 可独立换肤的渲染单元（FR-1）。ID 即持久化 key（格式 `group.name`，
 * 全小写），与 `StreamEntry` 类型解耦 —— 同一 entry 在两套输出风格下
 * 映射到同一 Part。**新增部件一律追加，不重命名既有 ID**。
 *
 * @param id 稳定持久化 ID（§4 注册表）
 * @param suggestedSkin §4「新默认皮肤」列 —— DEFAULT 预设的建议值；
 *   `FOLLOW` = 无建议，穿透到全局 UiStyle 默认
 * @param family 允许矩阵族（§5）
 */
enum class CodingPart(
    val id: String,
    val suggestedSkin: PartSkin,
    val family: SkinFamily
) {
    // ═══ 4.1 时间轴卡片族（两套输出风格共享同一 ID）═══
    STREAM_USER_BUBBLE("stream.user_bubble", PartSkin.SOLID, SkinFamily.BUBBLE),
    STREAM_ASSISTANT_BUBBLE("stream.assistant_bubble", PartSkin.PALE_LIQUID, SkinFamily.CARD),
    STREAM_THINKING("stream.thinking", PartSkin.PALE_LIQUID, SkinFamily.CARD),
    STREAM_TOOL_CAPSULE("stream.tool_capsule", PartSkin.PALE_LIQUID, SkinFamily.CARD),
    STREAM_VERIFY_CYCLE("stream.verify_cycle", PartSkin.PALE_LIQUID, SkinFamily.CARD),
    STREAM_STATUS_LINE("stream.status_line", PartSkin.FLAT, SkinFamily.CARD),
    STREAM_SYSTEM_LINE("stream.system_line", PartSkin.FLAT, SkinFamily.CARD),
    STREAM_ERROR("stream.error", PartSkin.PALE_LIQUID, SkinFamily.CARD),
    STREAM_STOP("stream.stop", PartSkin.PALE_LIQUID, SkinFamily.CARD),
    STREAM_FILE_CHIPS("stream.file_chips", PartSkin.FLAT, SkinFamily.CARD),
    STREAM_GIT_OP("stream.git_op", PartSkin.FOLLOW, SkinFamily.CARD),
    STREAM_RUN_STATS("stream.run_stats", PartSkin.PALE_LIQUID, SkinFamily.CARD),
    STREAM_GENERATING("stream.generating", PartSkin.PALE_LIQUID, SkinFamily.CARD),

    // ═══ 4.2 输入区 / 悬浮态 ═══
    INPUT_BAR("input.bar", PartSkin.CLASSIC_GLASS, SkinFamily.CARD),
    INPUT_QUEUE_CARD("input.queue_card", PartSkin.PALE_LIQUID, SkinFamily.CARD),
    INPUT_QUESTION_CARD("input.question_card", PartSkin.PALE_LIQUID, SkinFamily.CARD),
    INPUT_PLAN_CONFIRM("input.plan_confirm", PartSkin.PALE_LIQUID, SkinFamily.CARD),
    OVERLAY_ERROR_BAR("overlay.error_bar", PartSkin.PALE_LIQUID, SkinFamily.CARD),
    OVERLAY_OFFLINE_BANNER("overlay.offline_banner", PartSkin.FLAT, SkinFamily.CARD),
    OVERLAY_UPDATE_BANNER("overlay.update_banner", PartSkin.FLAT, SkinFamily.CARD),
    OVERLAY_TOOL_DETAIL_SHEET("overlay.tool_detail_sheet", PartSkin.CLASSIC_GLASS, SkinFamily.CARD),

    // ═══ 4.3 终端 / 代码（独立语言，不参与浅白改造）═══
    TERMINAL_PANEL("terminal.panel", PartSkin.NEON_TERMINAL, SkinFamily.TERMINAL),
    CODE_DIFF("code.diff", PartSkin.FOLLOW, SkinFamily.TERMINAL),
    CODE_STREAMING_WINDOW("code.streaming_window", PartSkin.NEON_TERMINAL, SkinFamily.TERMINAL);

    /** §5 允许矩阵判定：该皮肤可否被显式选用于本部件。 */
    fun allows(skin: PartSkin): Boolean = skin in family.allowed

    companion object {
        /** 持久化 ID → 枚举（大小写/空白容错，未知值 null —— 防御式，绝不抛）。 */
        fun fromId(id: String?): CodingPart? =
            entries.firstOrNull { it.id == id?.trim()?.lowercase() }
    }
}

/**
 * 皮肤枚举（FR-2）——材质 + 形状 + 光效 + 状态色策略的组合，**不含布局
 * 与文案**。换肤只换「皮」不换「骨」（FR-9）。
 *
 * @param key 持久化字符串（小写下划线）
 */
enum class PartSkin(val key: String) {
    /** 跟随上级（预设 → 全局）；稀疏存储下「显式跟随」等价于缺席。 */
    FOLLOW("follow"),

    /** 浅白色液态玻璃（本 spec 新增）：乳白霜面 + 微彩光晕 + 白热轮廓流光。 */
    PALE_LIQUID("pale_liquid"),

    /** 经典彩色玻璃：既有 GlassStyle 七档管线，允许 accent 染色。 */
    CLASSIC_GLASS("classic_glass"),

    /** 极简扁平：实色 surfaceContainer + 1dp outlineVariant 发丝描边。 */
    FLAT("flat"),

    /** 实色气泡：M3 Surface + primaryContainer（用户气泡专用）。 */
    SOLID("solid"),

    /** 终端暗色霓虹：沿用 TerminalGlass，不受 LocalUiStyle 短路以外的影响。 */
    NEON_TERMINAL("neon_terminal");

    companion object {
        /** 持久化 key → 枚举（大小写/空白容错，未知值 null —— 防御式，绝不抛）。 */
        fun fromKey(key: String?): PartSkin? =
            entries.firstOrNull { it.key == key?.trim()?.lowercase() }
    }
}

/**
 * 预设（§7.2）——一组 Part→Skin 的批量映射。**预设是动作而非持久态**：
 * 点「全部浅白」时把 [generateOverrides] 的结果写入 `partSkins`，不额外
 * 存预设字段（避免双源不一致）；「恢复跟随全局」= 清空稀疏表，缺席即
 * 跟随 DEFAULT 预设的 §4 建议。
 */
enum class PartSkinPreset(val key: String) {
    /** §4「新默认皮肤」列（思考卡 / 卡片族 = 浅白液态玻璃）。 */
    DEFAULT("default"),

    /** 所有允许 PALE_LIQUID 的 Part 全设浅白（用户一键；终端/代码窗不写）。 */
    PALE_ALL("pale_all"),

    /** 全部回到现有彩色玻璃观感（回滚通道）。 */
    CLASSIC_ALL("classic_all"),

    /** 全部 FLAT 极简扁平。 */
    MINIMAL_ALL("minimal_all");

    /**
     * 预设对本 Part 的建议皮肤；null = 预设未定义该 Part（FOLLOW 穿透到
     * 全局 UiStyle 默认）。生成结果确定、幂等（§11.1 可复现）。
     */
    fun skinFor(part: CodingPart): PartSkin? = when (this) {
        DEFAULT -> part.suggestedSkin.takeIf { it != PartSkin.FOLLOW }
        PALE_ALL -> PartSkin.PALE_LIQUID.takeIf { part.allows(it) }
        CLASSIC_ALL -> PartSkin.CLASSIC_GLASS.takeIf { part.allows(it) }
        MINIMAL_ALL -> PartSkin.FLAT.takeIf { part.allows(it) }
    }

    /**
     * 预设动作生成的覆盖表（稀疏语义：FOLLOW 不落盘）。
     *
     * 幂等性：同一预设生成的表恒定；「全部浅白」只写允许集 ——
     * `terminal.panel` 等 NEON 独占部件绝不入表（§11.1）。
     */
    fun generateOverrides(): Map<CodingPart, PartSkin> =
        CodingPart.entries.mapNotNull { part -> skinFor(part)?.let { part to it } }.toMap()

    companion object {
        /** 持久化 key → 枚举（未知值回退 DEFAULT）。 */
        fun fromKey(key: String?): PartSkinPreset =
            entries.firstOrNull { it.key == key?.trim()?.lowercase() } ?: DEFAULT
    }
}

/**
 * 上层把 CodeOutputStyle / UiStyle 压成的上下文（§7.2）。
 *
 * ★ 依赖纪律：ui/theme 禁止反向 import ui/screen/code —— 本 Context 由
 * ui/screen/code/PartSkinOps.kt 组装（[skinContextFor]），theme 包保持
 * 可独立复用。`presetSkin` 供组装方按 Part 写入预设建议时使用（默认
 * null = 走 [PartSkinOverrides.preset] 字段的建议）。
 */
@Immutable
data class SkinContext(
    /** 全局兜底默认：LIQUID_GLASS → CLASSIC_GLASS；MINIMAL → FLAT。 */
    val globalDefault: PartSkin,
    /** 组装方写入的预设建议（按 Part）；null = 未定义。 */
    val presetSkin: PartSkin? = null,
    /** 当前全局风格是否为 MINIMAL（PALE_LIQUID 另走 PaleFlat 降级，NFR-5）。 */
    val minimal: Boolean = false,
    /** 每 Part 显式豁免 MINIMAL 短路（默认关；开启后 PALE 玻璃照常渲染）。 */
    val forceGlassInMinimal: Boolean = false,
    /** 光晕强度 0..1（0 = 纯白霜面，1 = 完整彩光）。 */
    val glow: Float = 0.35f
)

/**
 * 不可变皮肤快照，供 CompositionLocal 下发（NFR-1：
 * `staticCompositionLocalOf` —— 皮肤变更只失效读取该 Local 的
 * PartSurface，不波及整个时间轴 Compose 树）。
 */
@Immutable
data class PartSkinOverrides(
    /** 稀疏覆盖表：缺席 = 跟随上级；显式 FOLLOW 不入表。 */
    val map: Map<CodingPart, PartSkin> = emptyMap(),
    /** 光晕强度 0..1（越界读入时已 clamp）。 */
    val glow: Float = 0.35f,
    /** 建议预设（默认 §4 表；预览页可覆写）。 */
    val preset: PartSkinPreset = PartSkinPreset.DEFAULT
) {
    /**
     * 三级回退链（FR-3，纯函数，零 Android / 零 Compose 依赖，可 JVM 单测）：
     *
     * ```
     * map[part] 若非 FOLLOW 且在该 Part 允许矩阵内  → 直接返回
     *         ↓ 否则
     * ctx.presetSkin ?: preset.skinFor(part)       → 预设建议
     *         ↓ 若预设未定义该 Part（null / FOLLOW）
     * ctx.globalDefault（MINIMAL 时 FLAT；PALE 建议的 PaleFlat 降级
     * 由 PartSurface 渲染层处理，见 NFR-5）
     *         ↓ 任何解析异常（防御兜底，绝不抛、绝不空白）
     * 终值 FLAT
     * ```
     */
    fun resolve(part: CodingPart, ctx: SkinContext): PartSkin = try {
        // ① 显式覆盖：稀疏表命中且在允许矩阵内
        map[part]?.takeIf { it != PartSkin.FOLLOW && part.allows(it) }?.let { return it }
        // ② 预设建议：组装方写入的 per-Part 建议优先，否则本快照预设
        val suggestion = ctx.presetSkin ?: preset.skinFor(part)
        suggestion?.takeIf { it != PartSkin.FOLLOW && part.allows(it) }?.let { return it }
        // ③ 全局 UiStyle 默认（MINIMAL 恒 FLAT）
        if (ctx.minimal) PartSkin.FLAT else ctx.globalDefault
    } catch (_: Exception) {
        // 防御兜底：任何意外（矩阵演进 / 数据形态变化）都不抛、不空白
        PartSkin.FLAT
    }

    companion object {
        /**
         * 持久化容错解析（§10 兼容矩阵 / §11.1）：
         *  - 空 Map / null → 默认快照（走 §4 新默认，零迁移）；
         *  - 未知 key / 未知值 / 显式 FOLLOW → 丢弃（缺席 = 跟随）；
         *  - 大小写 / 首尾空白容错；
         *  - 不允许的组合（如 user_bubble 选 NEON）→ 丢弃，解析时回退
         *    FOLLOW 语义（穿透回退链），不报错；
         *  - glow 越界 clamp 到 0..1，null 回默认 0.35。
         */
        fun fromPersisted(
            raw: Map<String, String>?,
            glow: Float? = null
        ): PartSkinOverrides {
            val parsed = raw.orEmpty().entries.mapNotNull { (rawKey, rawValue) ->
                val part = CodingPart.fromId(rawKey) ?: return@mapNotNull null
                val skin = PartSkin.fromKey(rawValue) ?: return@mapNotNull null
                if (skin == PartSkin.FOLLOW || !part.allows(skin)) return@mapNotNull null
                part to skin
            }.toMap()
            return PartSkinOverrides(
                map = parsed,
                glow = (glow ?: DEFAULT_GLOW).coerceIn(0f, 1f)
            )
        }

        /** 光晕默认（FR-13：0 = 纯白霜面，1 = 完整彩光，默认 0.35 微彩）。 */
        const val DEFAULT_GLOW: Float = 0.35f
    }
}

/**
 * 当前部件皮肤快照（CodeScreen 注入 run 捕获值；未注入时兜底默认 ——
 * 独立预览场景不崩，§8-16）。
 */
val LocalPartSkins = staticCompositionLocalOf { PartSkinOverrides() }

/** 当前皮肤上下文（ApexTheme 按 uiStyle 注入默认；CodeScreen 覆写）。 */
val LocalSkinContext = staticCompositionLocalOf {
    SkinContext(globalDefault = PartSkin.FLAT, minimal = true)
}
