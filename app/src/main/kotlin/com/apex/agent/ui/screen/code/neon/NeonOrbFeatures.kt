package com.apex.agent.ui.screen.code.neon

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * ═══ 霓虹球功能聚合（Neon Orb Feature Hub）—— 纯逻辑层 ═══
 *
 * Coding 输入栏「霓虹球 mini 图标」背后的功能目录与会话注入组装器。
 * 本文件**零 Android 依赖**（纯 Kotlin + JDK），可被 `testDebugUnitTest`
 * 直接覆盖；持久化壳（SharedPreferences）见 [NeonOrbStore]，视觉壳
 * （霓虹球图标 + 聚合面板）见 [NeonOrbButton]。
 *
 * 16 项能力全部**真实生效**：每轮发送前由 CodeViewModel 调
 * [buildNeonSessionContext] 组装成「### 霓虹球 · 功能聚合」段落，经
 * `CodeAgentEngine.updateSessionExtras` 拼进 additionalSystemContext ——
 * 与小圆环（#197）同一注入通道，逐条随请求下发给模型。
 *
 * 三档单选（[NeonOutputFormat] / [NeonLanguageLock] / [NeonTone]）与
 * 13 个开关共同构成 16 项功能；目录契约（≥15 项 + 三项钦定能力在场）
 * 由 [FEATURE_IDS] 锁定，单测 NeonOrbFeaturesTest 防回退。
 */

// ═══ 单选项 ═══

/** 结构化输出格式（霓虹球独立于小圆环的一档，含 TOML 档）。 */
enum class NeonOutputFormat(val label: String) {
    NONE("关闭"),
    JSON("JSON"),
    XML("XML"),
    YAML("YAML"),
    MARKDOWN_TABLE("Markdown Table"),
    TOML("TOML"),
}

/** 回复语言锁定。 */
enum class NeonLanguageLock(val label: String) {
    OFF("跟随输入"),
    CHINESE("中文"),
    ENGLISH("English"),
}

/** 语气人设。 */
enum class NeonTone(val label: String) {
    OFF("默认"),
    PROFESSIONAL("专业严谨"),
    FRIENDLY("亲切友好"),
    DIRECT("直截了当"),
}

/**
 * 霓虹球聚合状态快照（不可变；由 [NeonOrbStore] 持有并持久化）。
 * 全 false / NONE / OFF = 零注入（不往 system prompt 塞任何东西）。
 */
data class NeonOrbState(
    // ── 会话增强 ──────────────────────────────────────────
    /** 时间注入：每轮实时注入日期/星期/时区（调用时刻取值，不落盘旧值）。 */
    val timeInjection: Boolean = false,
    /** 网络搜索：涉时效性问题强制先搜后答。 */
    val webSearch: Boolean = false,
    /** 结构化输出档（JSON/XML/YAML/表格/TOML）。 */
    val outputFormat: NeonOutputFormat = NeonOutputFormat.NONE,
    /** 深度推理：先分步分析再下结论。 */
    val deepReasoning: Boolean = false,
    /** 目标复述：动手前先复述任务与计划。 */
    val goalEcho: Boolean = false,
    /** 诚实边界：禁止编造，不确定必须明说。 */
    val antiHallucination: Boolean = false,
    // ── 输出口径 ──────────────────────────────────────────
    /** 语言锁定档。 */
    val languageLock: NeonLanguageLock = NeonLanguageLock.OFF,
    /** 简洁模式：砍掉客套与复读。 */
    val concise: Boolean = false,
    /** 详细讲解：教学式展开 + 示例。 */
    val teachMode: Boolean = false,
    /** 步骤编号：操作指引必须有序号。 */
    val numberedSteps: Boolean = false,
    /** 无表情符号：回复中禁用 emoji。 */
    val noEmoji: Boolean = false,
    /** 语气人设档。 */
    val tone: NeonTone = NeonTone.OFF,
    // ── 编码偏好 ──────────────────────────────────────────
    /** 代码注释：生成代码带充分注释。 */
    val richComments: Boolean = false,
    /** Diff 优先：代码修改以 unified diff 呈现。 */
    val diffFirst: Boolean = false,
    /** 风险预警：危险操作先亮红旗。 */
    val riskWarnings: Boolean = false,
    /** 安全命令：破坏性 shell 操作需先给替代方案。 */
    val safeCommands: Boolean = false
) {
    /** 任一功能激活（驱动霓虹球点亮态 + 徽标计数）。 */
    val anyActive: Boolean
        get() = timeInjection || webSearch || outputFormat != NeonOutputFormat.NONE ||
            deepReasoning || goalEcho || antiHallucination ||
            languageLock != NeonLanguageLock.OFF || concise || teachMode ||
            numberedSteps || noEmoji || tone != NeonTone.OFF ||
            richComments || diffFirst || riskWarnings || safeCommands

    /** 激活功能数（徽标显示；单选档按 1 计）。 */
    val activeCount: Int
        get() = listOf(
            timeInjection, webSearch, outputFormat != NeonOutputFormat.NONE,
            deepReasoning, goalEcho, antiHallucination,
            languageLock != NeonLanguageLock.OFF, concise, teachMode,
            numberedSteps, noEmoji, tone != NeonTone.OFF,
            richComments, diffFirst, riskWarnings, safeCommands
        ).count { it }
}

// ═══ 功能目录（契约：≥15 项，时间注入/结构化输出/网络搜索三项钦定在场）═══

/** 功能目录条目：UI 分组渲染 + 契约测试共用的单一可信清单。 */
data class NeonFeatureDef(
    /** 稳定 id（契约测试断言用；与状态字段一一对应）。 */
    val id: String,
    /** 分组（会话增强 / 输出口径 / 编码偏好）。 */
    val group: NeonFeatureGroup,
    /** 是否为单选档（true = 点开选档；false = 直接开关）。 */
    val exclusive: Boolean
)

/** 霓虹球面板分组。 */
enum class NeonFeatureGroup(val id: String) {
    SESSION("session"),
    STYLE("style"),
    CODING("coding")
}

/**
 * 16 项功能目录 —— 新增功能必须登记在此（契约测试锁 ≥15 项且三项
 * 钦定能力在场：时间注入 / 结构化输出 / 网络搜索）。
 */
val FEATURE_IDS: List<NeonFeatureDef> = listOf(
    NeonFeatureDef("time_injection", NeonFeatureGroup.SESSION, exclusive = false),
    NeonFeatureDef("web_search", NeonFeatureGroup.SESSION, exclusive = false),
    NeonFeatureDef("structured_output", NeonFeatureGroup.SESSION, exclusive = true),
    NeonFeatureDef("deep_reasoning", NeonFeatureGroup.SESSION, exclusive = false),
    NeonFeatureDef("goal_echo", NeonFeatureGroup.SESSION, exclusive = false),
    NeonFeatureDef("anti_hallucination", NeonFeatureGroup.SESSION, exclusive = false),
    NeonFeatureDef("language_lock", NeonFeatureGroup.STYLE, exclusive = true),
    NeonFeatureDef("concise", NeonFeatureGroup.STYLE, exclusive = false),
    NeonFeatureDef("teach_mode", NeonFeatureGroup.STYLE, exclusive = false),
    NeonFeatureDef("numbered_steps", NeonFeatureGroup.STYLE, exclusive = false),
    NeonFeatureDef("no_emoji", NeonFeatureGroup.STYLE, exclusive = false),
    NeonFeatureDef("tone", NeonFeatureGroup.STYLE, exclusive = true),
    NeonFeatureDef("rich_comments", NeonFeatureGroup.CODING, exclusive = false),
    NeonFeatureDef("diff_first", NeonFeatureGroup.CODING, exclusive = false),
    NeonFeatureDef("risk_warnings", NeonFeatureGroup.CODING, exclusive = false),
    NeonFeatureDef("safe_commands", NeonFeatureGroup.CODING, exclusive = false)
)

/** 目录契约：钦定三项必须在场（用户需求原文点名）。 */
val MANDATORY_FEATURE_IDS: Set<String> = setOf(
    "time_injection", "structured_output", "web_search"
)

// ═══ 注入组装（纯函数，发送时刻取实时值）═══

/**
 * 组装霓虹球注入段（空态返回空串 —— 零负担）。
 *
 * @param state 当前聚合状态
 * @param now   取「现在」（参数化便于单测注入固定时钟）
 * @return 追加到会话上下文的段落；全关时为空字符串
 */
fun buildNeonSessionContext(state: NeonOrbState, now: Date = Date()): String = buildString {
    if (!state.anyActive) return@buildString

    appendLine("### 霓虹球 · 功能聚合（用户本轮开启的能力）")

    if (state.timeInjection) {
        val tz = TimeZone.getDefault()
        val dateTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(now)
        val weekday = SimpleDateFormat("EEEE", Locale.CHINA).format(now)
        appendLine("- 当前时间：$dateTime（$weekday，时区 ${tz.id}）。回答涉及日期、星期、倒计时、时效性的问题时必须以此为准，禁止凭训练数据猜测。")
    }
    if (state.webSearch) {
        appendLine("- 网络搜索已开启：涉及实时信息、新闻、天气、价格、版本号、文档细节或不确定的事实时，必须先调用 WebSearch 工具获取最新结果（必要时 WebFetch 读全文）再作答，禁止仅凭训练知识作答；引用关键信息时注明来源。")
    }
    when (state.outputFormat) {
        NeonOutputFormat.NONE -> Unit
        NeonOutputFormat.JSON -> appendLine(
            "- 结构化输出（JSON）：最终结果必须是无额外文字、无 Markdown 代码块标记的合法 JSON，能被解析器直接解析。"
        )
        NeonOutputFormat.XML -> appendLine(
            "- 结构化输出（XML）：最终结果必须是根元素为 <response> 的合法 XML，不包含任何额外文字或前言后语。"
        )
        NeonOutputFormat.YAML -> appendLine(
            "- 结构化输出（YAML）：最终结果必须是合法 YAML，不包含任何额外文字或 Markdown 代码块标记。"
        )
        NeonOutputFormat.MARKDOWN_TABLE -> appendLine(
            "- 结构化输出（Markdown Table）：最终结果必须是 Markdown 表格（首行表头、次行分隔行），表格外不加文字。"
        )
        NeonOutputFormat.TOML -> appendLine(
            "- 结构化输出（TOML）：最终结果必须是合法 TOML，不包含任何额外文字或代码块标记。"
        )
    }
    if (state.deepReasoning) {
        appendLine("- 深度推理已开启：先在思考中分步拆解问题、列举关键约束与候选方案并比较，再给出最终结论；结论必须能追溯到推理步骤。")
    }
    if (state.goalEcho) {
        appendLine("- 目标复述已开启：动手前先用一两句话复述你理解的任务目标与执行计划，然后再开始执行；理解有歧义时先提出澄清问题。")
    }
    if (state.antiHallucination) {
        appendLine("- 诚实边界已开启：不知道、不确定、没有依据的内容必须明确说明，禁止编造 API、参数、命令、数据或来源；给不出可靠答案时如实说「不确定」并说明缺什么信息。")
    }
    when (state.languageLock) {
        NeonLanguageLock.OFF -> Unit
        NeonLanguageLock.CHINESE -> appendLine("- 语言锁定：全部回复使用简体中文（代码与专有名词除外）。")
        NeonLanguageLock.ENGLISH -> appendLine("- Language lock: reply entirely in English (code and proper nouns excepted).")
    }
    if (state.concise) {
        appendLine("- 简洁模式：直接给答案与关键信息，砍掉寒暄、客套、复述用户问题与总结性收尾；能用列表就不用大段文字。")
    }
    if (state.teachMode) {
        appendLine("- 详细讲解已开启：像老师一样分层展开 —— 先给结论，再讲原理，配一个可运行的最小示例，最后点出常见误区。")
    }
    if (state.numberedSteps) {
        appendLine("- 步骤编号已开启：所有操作指引、方案、教程必须用有序编号步骤呈现（1. 2. 3. …），每步一个动作。")
    }
    if (state.noEmoji) {
        appendLine("- 无表情符号已开启：回复中禁止使用任何 emoji 与表情符号，保持纯文本专业表达。")
    }
    when (state.tone) {
        NeonTone.OFF -> Unit
        NeonTone.PROFESSIONAL -> appendLine("- 语气人设：专业严谨 —— 措辞精确、有依据，避免口语化与夸张。")
        NeonTone.FRIENDLY -> appendLine("- 语气人设：亲切友好 —— 像耐心的同事，多用平实口语，鼓励提问。")
        NeonTone.DIRECT -> appendLine("- 语气人设：直截了当 —— 有什么说什么，先给结论与行动项，不绕弯。")
    }
    if (state.richComments) {
        appendLine("- 代码注释已开启：生成的代码必须带充分注释 —— 文件头说明用途、关键函数注解意图与参数、复杂逻辑逐段说明；注释与代码同样认真。")
    }
    if (state.diffFirst) {
        appendLine("- Diff 优先已开启：代码修改必须以 unified diff（--- a/… +++ b/… @@）形式呈现变更，diff 之外只附必要的改动说明。")
    }
    if (state.riskWarnings) {
        appendLine("- 风险预警已开启：执行删除、覆盖、强推、改权限、改系统配置等有风险的操作前，必须先用醒目的警告块说明影响范围与回滚方式。")
    }
    if (state.safeCommands) {
        appendLine("- 安全命令已开启：优先给出非破坏性替代命令（mv 到回收目录、rsync --dry-run、git stash 等）；确需破坏性 shell 操作时，先列出将被影响的目标并请求确认。")
    }
}.trim()
