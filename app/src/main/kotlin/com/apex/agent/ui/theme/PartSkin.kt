package com.apex.agent.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * ═══════════════════════════════════════════════════════════════
 *  PartSkin — 部件级皮肤注册表（Spec v1.2 · 74 部件 / 7 组 / 四级回退）
 * ═══════════════════════════════════════════════════════════════
 *
 * 在「全局 UiStyle（MINIMAL / LIQUID_GLASS）」与「输出风格预设
 * （TIMELINE / RIKKA）」之上补一层**部件级粒度**：每个卡片/气泡/工具块/
 * 输入区可独立选材质，带「部件覆盖 → 组覆盖 `@gN` → 输出风格预设 →
 * 全局 UiStyle」**四级回退**（FR-3 / FR-17）。
 *
 * v1.2 相对 v1.1 的模型变更：
 *  - [PartSkin] 增 [PartSkin.EMBEDDED]（零容器嵌入态）与 [PartSkin.isContainer]；
 *  - [SkinFamily] 3 → 5 族（增 EMBEDDED / CHROME，§5 允许矩阵）；
 *  - [SkinGroup] `@g1`…`@g7` 组键（FR-17 组级覆盖）；
 *  - [CodingPart] 24 → 74（附录 F 全表；**原 24 个 ID 与 suggestedSkin
 *    逐字不动**，老 JSON 零迁移）；增 `group` 与 `parentId`（字符串引用，
 *    避免 enum 自引用初始化顺序问题）；
 *  - [PartSkinOverrides.resolve] 三级 → 四级回退（部件 → 组 → 预设 → 全局）；
 *  - [PartSkinOverrides.resolveEffective] 实现 **H-4 嵌套退化**（父卡是
 *    玻璃时内部件强制 EMBEDDED，杜绝玻璃套玻璃 / 框中框复发）。
 *
 * 依赖纪律：本文件是纯模型（枚举 + 不可变快照 + 纯函数回退链），
 * **禁止反向 import ui/glass / ui.screen.*** —— SkinContext 由
 * ui/screen/code/PartSkinOps.kt 组装注入，theme 包保持可独立复用
 * （GlassLab / 预览页 / JVM 单测，NFR-8）。
 *
 * 持久化约定（FR-10）：`AgentSettings.partSkins` 是稀疏
 * `Map<String, String>`，部件键（`"stream.thinking" -> "pale_liquid"`）与
 * 组键（`"@g1" -> "pale_liquid"`）同存；**缺席 = 跟随上级**（与
 * roleSkillToggles 同款稀疏语义）；旧 JSON 无该字段 → 空 Map → 走 §4
 * 新默认皮肤列，零迁移。
 */

/**
 * 部件族 —— §5 允许矩阵的载体（v1.2：3 → 5 族）。
 *
 * | 族 | PALE | CLASSIC | FLAT | SOLID | NEON | EMBEDDED |
 * |---|---|---|---|---|---|---|
 * | CARD（G1 卡片族 / G3 输入 / G4 覆盖） | ✔ | ✔ | ✔ | — | — | ✔ |
 * | BUBBLE（用户气泡 / 发送按钮） | ✔ | ✔ | ✔ | ✔ | — | ✔ |
 * | TERMINAL（G5 / 代码块 / 灯箱） | — | ✔ | ✔ | — | ✔ | ✔ |
 * | EMBEDDED（G2 内部件 / 导航项 / 行控件） | ✔(受限) | — | ✔ | — | — | ✔(默认) |
 * | CHROME（G6 外壳 / G7 设置卡） | ✔ | ✔ | ✔ | — | — | ✔ |
 */
enum class SkinFamily(val allowed: Set<PartSkin>) {
    CARD(setOf(PartSkin.PALE_LIQUID, PartSkin.CLASSIC_GLASS, PartSkin.FLAT, PartSkin.EMBEDDED)),
    BUBBLE(setOf(PartSkin.PALE_LIQUID, PartSkin.CLASSIC_GLASS, PartSkin.FLAT, PartSkin.SOLID, PartSkin.EMBEDDED)),
    TERMINAL(setOf(PartSkin.CLASSIC_GLASS, PartSkin.FLAT, PartSkin.NEON_TERMINAL, PartSkin.EMBEDDED)),
    EMBEDDED(setOf(PartSkin.EMBEDDED, PartSkin.FLAT, PartSkin.PALE_LIQUID)),
    CHROME(setOf(PartSkin.PALE_LIQUID, PartSkin.CLASSIC_GLASS, PartSkin.FLAT, PartSkin.EMBEDDED))
}

/**
 * 部件分组（v1.2，FR-17）——组级覆盖 `@gN` 的键面。
 *
 * 组键与部件键同存于稀疏 `partSkins`，解析时组覆盖作为「组级默认」插入
 * 回退链第 2 级；组行选「跟随」= 移除组键。
 */
enum class SkinGroup(val key: String) {
    G1("@g1"),
    G2("@g2"),
    G3("@g3"),
    G4("@g4"),
    G5("@g5"),
    G6("@g6"),
    G7("@g7");

    /** 组内成员（注册表追加即自动归组；O(74) 仅供设置页 / 展开缓存用）。 */
    val parts: List<CodingPart> get() = CodingPart.entries.filter { it.group == this }

    companion object {
        /** 持久化组键 → 枚举（`@g1` 大小写容错；未知值 null）。 */
        fun fromKey(key: String?): SkinGroup? =
            entries.firstOrNull { it.key == key?.trim()?.lowercase() }
    }
}

/**
 * 可独立换肤的渲染单元（FR-1 / FR-16）。ID 即持久化 key（格式
 * `group.name`，全小写），与 `StreamEntry` 类型解耦 —— 同一 entry 在两套
 * 输出风格下映射到同一 Part。**新增部件一律追加，不重命名既有 ID**。
 *
 * 原 24 项（v1.1）的 `id` 与 `suggestedSkin` 逐字保留；v1.2 新增 50 项
 * 只追加（附录 F 全表，`[v1.2]` 标记者为新增）。
 *
 * @param id 稳定持久化 ID（§4 注册表）
 * @param suggestedSkin §4「新默认皮肤」列 —— DEFAULT 预设的建议值；
 *   `FOLLOW` = 无建议，穿透到组级 / 全局默认
 * @param family 允许矩阵族（§5）
 * @param group 所属分组（组级覆盖 `@gN` 的归属面）
 * @param parentId 父部件持久化 ID（G2 内部件嵌在 G1 卡片内；字符串引用
 *   避免 enum 自引用初始化顺序问题）。非空者受 H-4 嵌套退化约束。
 */
enum class CodingPart(
    val id: String,
    val suggestedSkin: PartSkin,
    val family: SkinFamily,
    val group: SkinGroup,
    val parentId: String? = null
) {
    // ═══ G1 — 时间轴条目壳（13 项，v1.1 原样，ID/默认皮肤零改动）═══
    STREAM_USER_BUBBLE("stream.user_bubble", PartSkin.SOLID, SkinFamily.BUBBLE, SkinGroup.G1),
    STREAM_ASSISTANT_BUBBLE("stream.assistant_bubble", PartSkin.PALE_LIQUID, SkinFamily.CARD, SkinGroup.G1),
    STREAM_THINKING("stream.thinking", PartSkin.PALE_LIQUID, SkinFamily.CARD, SkinGroup.G1),
    STREAM_TOOL_CAPSULE("stream.tool_capsule", PartSkin.PALE_LIQUID, SkinFamily.CARD, SkinGroup.G1),
    STREAM_VERIFY_CYCLE("stream.verify_cycle", PartSkin.PALE_LIQUID, SkinFamily.CARD, SkinGroup.G1),
    STREAM_STATUS_LINE("stream.status_line", PartSkin.FLAT, SkinFamily.CARD, SkinGroup.G1),
    STREAM_SYSTEM_LINE("stream.system_line", PartSkin.FLAT, SkinFamily.CARD, SkinGroup.G1),
    STREAM_ERROR("stream.error", PartSkin.PALE_LIQUID, SkinFamily.CARD, SkinGroup.G1),
    STREAM_STOP("stream.stop", PartSkin.PALE_LIQUID, SkinFamily.CARD, SkinGroup.G1),
    STREAM_FILE_CHIPS("stream.file_chips", PartSkin.FLAT, SkinFamily.CARD, SkinGroup.G1),
    STREAM_GIT_OP("stream.git_op", PartSkin.FOLLOW, SkinFamily.CARD, SkinGroup.G1),
    STREAM_RUN_STATS("stream.run_stats", PartSkin.PALE_LIQUID, SkinFamily.CARD, SkinGroup.G1),
    STREAM_GENERATING("stream.generating", PartSkin.PALE_LIQUID, SkinFamily.CARD, SkinGroup.G1),

    // ═══ G2 — 时间轴条目内部件（14 项，v1.2 新增；默认 EMBEDDED 零容器，受 H-4 约束）═══
    STREAM_THINKING_HEADER("stream.thinking_header", PartSkin.EMBEDDED, SkinFamily.EMBEDDED, SkinGroup.G2, "stream.thinking"),
    STREAM_THINKING_BODY("stream.thinking_body", PartSkin.EMBEDDED, SkinFamily.EMBEDDED, SkinGroup.G2, "stream.thinking"),
    STREAM_ASSISTANT_MARKDOWN("stream.assistant_markdown", PartSkin.EMBEDDED, SkinFamily.EMBEDDED, SkinGroup.G2, "stream.assistant_bubble"),
    STREAM_CODE_BLOCK("stream.code_block", PartSkin.NEON_TERMINAL, SkinFamily.TERMINAL, SkinGroup.G2, "stream.assistant_bubble"),
    STREAM_TOOL_ROW("stream.tool_row", PartSkin.EMBEDDED, SkinFamily.EMBEDDED, SkinGroup.G2, "stream.tool_capsule"),
    STREAM_TOOL_CHIP("stream.tool_chip", PartSkin.FLAT, SkinFamily.EMBEDDED, SkinGroup.G2, "stream.tool_capsule"),
    STREAM_FILE_CHIP("stream.file_chip", PartSkin.FLAT, SkinFamily.EMBEDDED, SkinGroup.G2, "stream.file_chips"),
    STREAM_EXPAND_CONTROL("stream.expand_control", PartSkin.EMBEDDED, SkinFamily.EMBEDDED, SkinGroup.G2, "stream.error"),
    STREAM_USER_AVATAR("stream.user_avatar", PartSkin.SOLID, SkinFamily.BUBBLE, SkinGroup.G2, "stream.user_bubble"),
    STREAM_ASSISTANT_IDENTITY("stream.assistant_identity", PartSkin.EMBEDDED, SkinFamily.EMBEDDED, SkinGroup.G2, "stream.assistant_bubble"),
    STREAM_TIMESTAMP("stream.timestamp", PartSkin.EMBEDDED, SkinFamily.EMBEDDED, SkinGroup.G2),
    STREAM_STREAM_CURSOR("stream.stream_cursor", PartSkin.EMBEDDED, SkinFamily.EMBEDDED, SkinGroup.G2, "stream.assistant_bubble"),
    STREAM_RETRY_BUTTON("stream.retry_button", PartSkin.FLAT, SkinFamily.EMBEDDED, SkinGroup.G2, "stream.error"),
    STREAM_STATS_ROW("stream.stats_row", PartSkin.EMBEDDED, SkinFamily.EMBEDDED, SkinGroup.G2, "stream.run_stats"),

    // ═══ G3 — 输入区（10 项：v1.1 原 4 + v1.2 新 6）═══
    INPUT_BAR("input.bar", PartSkin.CLASSIC_GLASS, SkinFamily.CARD, SkinGroup.G3),
    INPUT_QUEUE_CARD("input.queue_card", PartSkin.PALE_LIQUID, SkinFamily.CARD, SkinGroup.G3),
    INPUT_QUESTION_CARD("input.question_card", PartSkin.PALE_LIQUID, SkinFamily.CARD, SkinGroup.G3),
    INPUT_PLAN_CONFIRM("input.plan_confirm", PartSkin.PALE_LIQUID, SkinFamily.CARD, SkinGroup.G3),
    INPUT_TEXT_FIELD("input.text_field", PartSkin.EMBEDDED, SkinFamily.EMBEDDED, SkinGroup.G3, "input.bar"),
    INPUT_SEND_BUTTON("input.send_button", PartSkin.SOLID, SkinFamily.BUBBLE, SkinGroup.G3, "input.bar"),
    INPUT_TOOL_ROW("input.tool_row", PartSkin.EMBEDDED, SkinFamily.EMBEDDED, SkinGroup.G3, "input.bar"),
    INPUT_SLASH_MENU("input.slash_menu", PartSkin.CLASSIC_GLASS, SkinFamily.CARD, SkinGroup.G3),
    INPUT_SKILL_CHIP_FIELD("input.skill_chip_field", PartSkin.FLAT, SkinFamily.EMBEDDED, SkinGroup.G3, "input.bar"),
    INPUT_TODO_PANEL("input.todo_panel", PartSkin.PALE_LIQUID, SkinFamily.CARD, SkinGroup.G3),

    // ═══ G4 — 悬浮 / 覆盖层（12 项：v1.1 原 4 + v1.2 新 8）═══
    OVERLAY_ERROR_BAR("overlay.error_bar", PartSkin.PALE_LIQUID, SkinFamily.CARD, SkinGroup.G4),
    OVERLAY_OFFLINE_BANNER("overlay.offline_banner", PartSkin.FLAT, SkinFamily.CARD, SkinGroup.G4),
    OVERLAY_UPDATE_BANNER("overlay.update_banner", PartSkin.FLAT, SkinFamily.CARD, SkinGroup.G4),
    OVERLAY_TOOL_DETAIL_SHEET("overlay.tool_detail_sheet", PartSkin.CLASSIC_GLASS, SkinFamily.CARD, SkinGroup.G4),
    OVERLAY_DIALOG("overlay.dialog", PartSkin.CLASSIC_GLASS, SkinFamily.CARD, SkinGroup.G4),
    OVERLAY_SHEET("overlay.sheet", PartSkin.CLASSIC_GLASS, SkinFamily.CARD, SkinGroup.G4),
    OVERLAY_TOAST("overlay.toast", PartSkin.FLAT, SkinFamily.CARD, SkinGroup.G4),
    OVERLAY_IMAGE_LIGHTBOX("overlay.image_lightbox", PartSkin.NEON_TERMINAL, SkinFamily.TERMINAL, SkinGroup.G4),
    OVERLAY_HTML_PREVIEW("overlay.html_preview", PartSkin.CLASSIC_GLASS, SkinFamily.CARD, SkinGroup.G4),
    OVERLAY_GITHUB_MCP_DIALOG("overlay.github_mcp_dialog", PartSkin.CLASSIC_GLASS, SkinFamily.CARD, SkinGroup.G4),
    OVERLAY_FEEDBACK("overlay.feedback", PartSkin.PALE_LIQUID, SkinFamily.CARD, SkinGroup.G4),
    OVERLAY_RELIABILITY_TIER("overlay.reliability_tier", PartSkin.FLAT, SkinFamily.EMBEDDED, SkinGroup.G4),

    // ═══ G5 — 终端 / 代码（7 项：v1.1 原 3 + v1.2 新 4）═══
    TERMINAL_PANEL("terminal.panel", PartSkin.NEON_TERMINAL, SkinFamily.TERMINAL, SkinGroup.G5),
    CODE_DIFF("code.diff", PartSkin.FOLLOW, SkinFamily.TERMINAL, SkinGroup.G5),
    CODE_STREAMING_WINDOW("code.streaming_window", PartSkin.NEON_TERMINAL, SkinFamily.TERMINAL, SkinGroup.G5),
    TERMINAL_LINE("terminal.line", PartSkin.EMBEDDED, SkinFamily.EMBEDDED, SkinGroup.G5, "terminal.panel"),
    TERMINAL_PROMPT("terminal.prompt", PartSkin.EMBEDDED, SkinFamily.EMBEDDED, SkinGroup.G5, "terminal.panel"),
    CODE_DIFF_HEADER("code.diff_header", PartSkin.EMBEDDED, SkinFamily.EMBEDDED, SkinGroup.G5, "code.diff"),
    CODE_LANGUAGE_ICON("code.language_icon", PartSkin.EMBEDDED, SkinFamily.EMBEDDED, SkinGroup.G5),

    // ═══ G6 — 外壳 / 导航（8 项，v1.2 新增）═══
    SHELL_TOP_BAR("shell.top_bar", PartSkin.FLAT, SkinFamily.CHROME, SkinGroup.G6),
    SHELL_WORKSPACE_BAR("shell.workspace_bar", PartSkin.FLAT, SkinFamily.CHROME, SkinGroup.G6),
    SHELL_DRAWER("shell.drawer", PartSkin.CLASSIC_GLASS, SkinFamily.CHROME, SkinGroup.G6),
    SHELL_NAV_ITEM("shell.nav_item", PartSkin.EMBEDDED, SkinFamily.EMBEDDED, SkinGroup.G6, "shell.drawer"),
    SHELL_NEW_CHAT_BUTTON("shell.new_chat_button", PartSkin.FLAT, SkinFamily.CHROME, SkinGroup.G6),
    SHELL_HISTORY_BUTTON("shell.history_button", PartSkin.FLAT, SkinFamily.CHROME, SkinGroup.G6),
    SHELL_CONTEXT_RING("shell.context_ring", PartSkin.EMBEDDED, SkinFamily.EMBEDDED, SkinGroup.G6),
    SHELL_BRAND_LOGO("shell.brand_logo", PartSkin.EMBEDDED, SkinFamily.EMBEDDED, SkinGroup.G6),

    // ═══ G7 — 设置页 / 其他页面（10 项，v1.2 新增）═══
    SETTINGS_SECTION_CARD("settings.section_card", PartSkin.CLASSIC_GLASS, SkinFamily.CHROME, SkinGroup.G7),
    SETTINGS_DROPDOWN_ROW("settings.dropdown_row", PartSkin.EMBEDDED, SkinFamily.EMBEDDED, SkinGroup.G7, "settings.section_card"),
    SETTINGS_SLIDER_ROW("settings.slider_row", PartSkin.EMBEDDED, SkinFamily.EMBEDDED, SkinGroup.G7, "settings.section_card"),
    SETTINGS_SWITCH_ROW("settings.switch_row", PartSkin.EMBEDDED, SkinFamily.EMBEDDED, SkinGroup.G7, "settings.section_card"),
    SETTINGS_PREVIEW_CARD("settings.preview_card", PartSkin.PALE_LIQUID, SkinFamily.CARD, SkinGroup.G7),
    SETTINGS_PRESET_BUTTON("settings.preset_button", PartSkin.FLAT, SkinFamily.EMBEDDED, SkinGroup.G7),
    PAGE_ABOUT_CARD("page.about_card", PartSkin.CLASSIC_GLASS, SkinFamily.CHROME, SkinGroup.G7),
    PAGE_GLASS_LAB_SAMPLE("page.glass_lab_sample", PartSkin.CLASSIC_GLASS, SkinFamily.CHROME, SkinGroup.G7),
    PAGE_HISTORY_CARD("page.history_card", PartSkin.PALE_LIQUID, SkinFamily.CARD, SkinGroup.G7),
    PAGE_LONG_TASK_CARD("page.long_task_card", PartSkin.PALE_LIQUID, SkinFamily.CARD, SkinGroup.G7);

    /** §5 允许矩阵判定：该皮肤可否被显式选用于本部件。 */
    fun allows(skin: PartSkin): Boolean = skin in family.allowed

    /** 父部件枚举（parentId 字符串解析；无父 / 引用失效 → null，防御式）。 */
    val parent: CodingPart? get() = parentId?.let { fromId(it) }

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
    /** 跟随上级（组 → 预设 → 全局）；稀疏存储下「显式跟随」等价于缺席。 */
    FOLLOW("follow"),

    /** 浅白色液态玻璃（v1.0 新增）：乳白霜面 + 微彩光晕 + 白热轮廓流光。 */
    PALE_LIQUID("pale_liquid"),

    /** 经典彩色玻璃：既有 GlassStyle 七档管线，允许 accent 染色。 */
    CLASSIC_GLASS("classic_glass"),

    /** 极简扁平：实色 surfaceContainer + 1dp outlineVariant 发丝描边。 */
    FLAT("flat"),

    /** 实色气泡：M3 Surface + primaryContainer（用户气泡 / 发送按钮）。 */
    SOLID("solid"),

    /** 终端暗色霓虹：沿用 TerminalGlass，不受 LocalUiStyle 短路以外的影响。 */
    NEON_TERMINAL("neon_terminal"),

    /**
     * 嵌入态（v1.2 新增）：**零容器** —— 不创建任何 Surface / GlassSurface，
     * 只透传 modifier 与 contentPadding，继承父卡背景，是 G2 内部件的
     * 默认形态（H-4 嵌套退化的终态，FR-19）。
     */
    EMBEDDED("embedded");

    /** 是否产生自有容器（EMBEDDED 零容器；FOLLOW 恒不作为解析终值出现）。 */
    val isContainer: Boolean get() = this != EMBEDDED && this != FOLLOW

    /** 玻璃质容器（H-4 判定用：父为玻璃且子也为容器级实色/玻璃时退化）。 */
    val isGlassLike: Boolean get() = this == PALE_LIQUID || this == CLASSIC_GLASS

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
     * 组级 / 全局默认）。生成结果确定、幂等（§11.1 可复现）。
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
 * ui/screen/code/PartSkinOps.kt 组装（[skinContextFor]），theme 包保持可
 * 独立复用。`presetSkin` 供组装方按 Part 写入预设建议时使用（默认
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
 *
 * v1.2：增 [groups] 组级覆盖表；[groupSkins] 惰性展开缓存保证 74 部件
 * 解析仍是 O(1) 哈希查表 + 最多 4 次回退（NFR-11，禁止每次重组遍历）。
 */
@Immutable
data class PartSkinOverrides(
    /** 稀疏覆盖表：缺席 = 跟随上级；显式 FOLLOW 不入表。 */
    val map: Map<CodingPart, PartSkin> = emptyMap(),
    /** 组级覆盖表（`@g1`…`@g7`）：作为组内部件的「组级默认」。 */
    val groups: Map<SkinGroup, PartSkin> = emptyMap(),
    /** 光晕强度 0..1（越界读入时已 clamp）。 */
    val glow: Float = 0.35f,
    /** 建议预设（默认 §4 表；预览页可覆写）。 */
    val preset: PartSkinPreset = PartSkinPreset.DEFAULT
) {
    /**
     * 组键预展开缓存（NFR-11）：`@gN` → 组内**允许该皮肤**的成员映射。
     * `@Immutable` data class 的 equals 只看构造参数 —— 本缓存不参与等值，
     * 快照复用比较零开销。
     */
    val groupSkins: Map<CodingPart, PartSkin> by lazy {
        if (groups.isEmpty()) {
            emptyMap()
        } else {
            buildMap {
                groups.forEach { (group, skin) ->
                    group.parts.forEach { part ->
                        if (part.allows(skin)) put(part, skin)
                    }
                }
            }
        }
    }

    /** 用户是否对该部件有显式覆盖（H-4「显式指定则尊重」判定）。 */
    fun hasExplicit(part: CodingPart): Boolean = map.containsKey(part)

    /**
     * 四级回退链（FR-3 / FR-17，纯函数，零 Android / 零 Compose 依赖，
     * 可 JVM 单测）：
     *
     * ```
     * map[part] 若非 FOLLOW 且在该 Part 允许矩阵内      → 直接返回（①部件）
     *         ↓ 否则
     * groupSkins[part] 组键预展开缓存（允许矩阵过滤后）  → 组级默认（②组）
     *         ↓ 否则
     * ctx.presetSkin ?: preset.skinFor(part)             → 预设建议（③预设）
     *         ↓ 若预设未定义该 Part（null / FOLLOW）
     * ctx.globalDefault（MINIMAL 时 FLAT；PALE 建议的    → 全局 UiStyle（④全局）
     * PaleFlat 降级由 PartSurface 渲染层处理，见 NFR-5）
     *         ↓ 任何解析异常（防御兜底，绝不抛、绝不空白）
     * 终值 FLAT
     * ```
     */
    fun resolve(part: CodingPart, ctx: SkinContext): PartSkin = try {
        // ① 显式覆盖：稀疏表命中且在允许矩阵内
        map[part]?.takeIf { it != PartSkin.FOLLOW && part.allows(it) }?.let { return it }
        // ② 组级默认：组键预展开缓存（组值不在该部件允许矩阵内 = 视为缺席）
        groupSkins[part]?.takeIf { it != PartSkin.FOLLOW && part.allows(it) }?.let { return it }
        // ③ 预设建议：组装方写入的 per-Part 建议优先，否则本快照预设
        val suggestion = ctx.presetSkin ?: preset.skinFor(part)
        suggestion?.takeIf { it != PartSkin.FOLLOW && part.allows(it) }?.let { return it }
        // ④ 全局 UiStyle 默认（MINIMAL 恒 FLAT）
        if (ctx.minimal) PartSkin.FLAT else ctx.globalDefault
    } catch (_: Exception) {
        // 防御兜底：任何意外（矩阵演进 / 数据形态变化）都不抛、不空白
        PartSkin.FLAT
    }

    /**
     * H-4 嵌套退化（FR-19，§6.6）——带父皮肤上下文的最终解析：
     *
     * ```
     * raw = resolve(part, ctx)                        // 四级回退结果
     * if (part.parentId == null) return raw           // 顶层部件无退化
     * if (map 含该 part 的显式覆盖) return raw         // 用户显式指定 → 尊重
     * if (parentSkin 是玻璃 && raw 是容器级玻璃/实色)
     *     return EMBEDDED                              // ★ 自动退化（零容器）
     * return raw
     * ```
     *
     * 父卡已是玻璃时内部件再生成一层玻璃 = DEF-1 框中框原样复发，故凡
     * 父解析为 PALE_LIQUID / CLASSIC_GLASS 且子解析为 PALE_LIQUID /
     * CLASSIC_GLASS / SOLID（会叠出双底边 / 双圆角的组合）时强制 EMBEDDED。
     */
    fun resolveEffective(
        part: CodingPart,
        ctx: SkinContext,
        parentSkin: PartSkin?
    ): PartSkin = try {
        val raw = resolve(part, ctx)
        when {
            part.parentId == null -> raw
            hasExplicit(part) -> raw
            parentSkin != null && parentSkin.isGlassLike &&
                (raw.isGlassLike || raw == PartSkin.SOLID) -> PartSkin.EMBEDDED
            else -> raw
        }
    } catch (_: Exception) {
        PartSkin.EMBEDDED
    }

    /** 序列化回持久化稀疏表（部件键 + 组键；「撤销待生效」回滚用）。 */
    fun toPersisted(): Map<String, String> = buildMap {
        map.forEach { (part, skin) -> put(part.id, skin.key) }
        groups.forEach { (group, skin) -> put(group.key, skin.key) }
    }

    companion object {
        /**
         * 持久化容错解析（§10 兼容矩阵 / §11.1）：
         *  - 空 Map / null → 默认快照（走 §4 新默认，零迁移）；
         *  - 部件键与 `@gN` 组键同存（v1.2）；未知 key / 未知值 / 显式
         *    FOLLOW → 丢弃（缺席 = 跟随）；
         *  - 大小写 / 首尾空白容错；
         *  - 不允许的组合（如 user_bubble 选 NEON）→ 丢弃，解析时回退
         *    FOLLOW 语义（穿透回退链），不报错；
         *  - 组键展开时逐部件过允许矩阵（不越组、不越矩阵）；
         *  - glow 越界 clamp 到 0..1，null 回默认 0.35。
         */
        fun fromPersisted(
            raw: Map<String, String>?,
            glow: Float? = null
        ): PartSkinOverrides {
            val partMap = mutableMapOf<CodingPart, PartSkin>()
            val groupMap = mutableMapOf<SkinGroup, PartSkin>()
            raw.orEmpty().forEach { (rawKey, rawValue) ->
                val key = rawKey.trim().lowercase()
                val skin = PartSkin.fromKey(rawValue) ?: return@forEach
                if (skin == PartSkin.FOLLOW) return@forEach
                val group = SkinGroup.fromKey(key)
                if (group != null) {
                    groupMap[group] = skin
                    return@forEach
                }
                val part = CodingPart.fromId(key) ?: return@forEach
                if (part.allows(skin)) partMap[part] = skin
            }
            return PartSkinOverrides(
                map = partMap,
                groups = groupMap,
                glow = (glow ?: DEFAULT_GLOW).coerceIn(0f, 1f)
            )
        }

        /** 光晕默认（FR-13：0 = 纯白霜面，1 = 完整彩光，默认 0.35 微彩）。 */
        const val DEFAULT_GLOW: Float = 0.35f
    }
}

/**
 * 当前部件皮肤快照（CodeScreen 注入会话快照值；未注入时兜底默认 ——
 * 独立预览场景不崩，§8-16）。
 */
val LocalPartSkins = staticCompositionLocalOf { PartSkinOverrides() }

/** 当前皮肤上下文（ApexTheme 按 uiStyle 注入默认；CodeScreen 覆写）。 */
val LocalSkinContext = staticCompositionLocalOf {
    SkinContext(globalDefault = PartSkin.FLAT, minimal = true)
}

/**
 * 父部件已解析皮肤（v1.2，H-4）——容器型 PartSurface 在内容外层下发自身
 * skin，内部件 PartSurface 读取后参与 [PartSkinOverrides.resolveEffective]
 * 的嵌套退化判定；Coding 子树根部注入 null（§7.6）。
 */
val LocalParentSkin = staticCompositionLocalOf<PartSkin?> { null }
