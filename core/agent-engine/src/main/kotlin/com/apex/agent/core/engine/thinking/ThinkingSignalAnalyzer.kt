package com.apex.agent.core.engine.thinking

/**
 * # Thinking Signal Analyzer — 自适应选档的共享文本信号计分核心
 *
 * [AdaptiveThinkingSelector]（Agent 模式）与 code-engine 的
 * `CodeAdaptiveThinkingSelector`（Coding 模式）是**两套独立的选档策略**
 * （档位枚举、阈值表、保底规则、升档语义各自演化，见各自 KDoc），但它们的
 * 文本信号词表与计分算术曾经逐行复制——新增一个风险词要同步改两个模块，
 * 漂移已是既成事实的隐患。本类把**策略无关**的部分收敛为单一事实源：
 *
 * - 四个共享维度的检测与计分（文本长度 / 多步指示词 / 代码命令信号 /
 *   风险词）——词表、权重、可解释标签全部只此一份；
 * - 评分阈值与深水区常量（两侧数值相同，锚定于此防各自漂移）；
 * - 决策理由串的统一拼装格式（`因子列表 → 评分 N → 档位（调整项）`）；
 * - 短平快标注的判定。
 *
 * **策略相关**的部分（错误信号加分、档位阈值映射、保底与升档）留在各
 * 选档器——它们引用不同的档位枚举，语义确实互不相同。
 *
 * ## SPE-01 评分口径升级（v1.5.x）
 *
 * 旧口径的四类系统性偏高已修复（详见 SPE-01 规格）：
 *
 * 1. **英文词一律词边界匹配**——`digit` 不再命中 `git`、`adapt` 不再
 *    命中 `apt`、`explain` 不再命中 `plan`；
 * 2. **风险词分层**：强风险（rm -rf / 格式化 / 刷机 / factory reset /
 *    wipe）+3 且保留「硬保底 DEEP」语义（[riskWord] 仅强风险置位）；
 *    弱风险（删除 / uninstall / 卸载）+1 不保底；语境风险（权限）
 *    仅在「申请/修改/关闭/授予… + 权限」动作搭配时 +1——Android 语境
 *    最高频词不再一律抬档；
 * 3. **否定 / 只读抑制**：命中词落在否定窗口（`不要`、`别把`、`只读`、
 *    `dry-run` 等向前 [NEGATION_WINDOW_CHARS] 字符）内 → 该维度不计分、
 *    [riskWord] 置 null（「不要删除任何文件，只读分析」不再按破坏性
 *    任务升档）；
 * 4. **有效长度**：剥离代码围栏、`@file:line` 引用、URL 后按 Unicode
 *    code point 计数——粘贴代码块的短任务不再被判超长；
 * 5. **理由串净化**：维度标签只输出「维度名+权重」（如 `风险词+3`），
 *    不回显命中词原文——用户输入里的指令片段无法借理由串进入
 *    system prompt（prompt injection 面收窄）。
 *
 * ## 共享计分表
 *
 * | 维度 | 条件 | 加分 | 标签示例 |
 * |------|------|------|----------|
 * | 文本长度（有效口径） | > 1500 字 +3；> 500 字 +2（替换不叠加） | 见左 | `超长文本+3` / `长文本+2` |
 * | 多步指示词 | 然后/接着/步骤/多任务 或词边界 first/then/step/plan | +2 | `多步指示+2` |
 * | 代码/命令 | 围栏 / `$ ` 提示符 / 词边界 git·npm·gradle 等命令词 | +2 | `代码/命令+2` |
 * | 强风险词 | rm -rf/格式化/刷机/factory reset/wipe | +3 且保底 DEEP | `风险词+3` |
 * | 弱风险词 | 删除/uninstall/卸载 | +1（无保底） | `弱风险词+1` |
 * | 语境风险词 | 动作动词 + 权限 | +1（无保底） | `语境风险+1` |
 *
 * ## 引擎专有维度的插入位（[analyze] 的 [midDimension] 钩子）
 *
 * Coding 选档器多一个「编码意图 +1」维度，其信号在代码/命令与风险词
 * **之间**插入（保持两个选档器的维度顺序稳定）。
 *
 * 纯函数、无状态：同一输入永远得到同一分析结果。
 */
object ThinkingSignalAnalyzer {

    /** 单个计分维度：权重 + 可解释标签（直接进决策理由串；已净化不回显原文）。 */
    data class Signal(val weight: Int, val label: String)

    /**
     * 文本信号分析结果。
     *
     * @param signals 按稳定顺序排列的命中维度（长度 → 多步 → 代码/命令 →
     *   引擎专有 → 风险词；未命中的维度不出现）。
     * @param riskWord 命中的首个**强**风险词（供选档器的「风险词保底 DEEP」
     *   规则使用；弱/语境风险与否定窗口内的命中都不置位）；未命中为 null。
     */
    data class TextSignalAnalysis(
        val signals: List<Signal>,
        val riskWord: String?
    ) {
        /** 共享维度的计分小计（引擎专有维度与错误信号由选档器自行累加）。 */
        val score: Int get() = signals.sumOf { it.weight }

        /** 理由串用的标签列表（与分析顺序一致）。 */
        val labels: List<String> get() = signals.map { it.label }
    }

    /**
     * 分析文本的共享信号维度。
     *
     * @param text 用户输入（Agent 模式本轮文本 / Coding 模式任务 goal）
     * @param midDimension 引擎专有维度检测器：返回 null = 未命中；其信号
     *   在顺序上插在代码/命令之后、风险词之前。null = 无引擎专有维度。
     */
    fun analyze(
        text: String,
        midDimension: ((String) -> Signal?)? = null
    ): TextSignalAnalysis {
        val signals = mutableListOf<Signal>()

        // ── 维度 1：文本长度（有效口径：剥离围栏/@file/URL 后按 code point 计数）──
        val effectiveLength = effectiveTextLength(text)
        when {
            effectiveLength > LONG_TEXT_HEAVY -> signals.add(Signal(3, "超长文本+3"))
            effectiveLength > LONG_TEXT_LIGHT -> signals.add(Signal(2, "长文本+2"))
        }

        // ── 维度 2：多步指示词（中文子串 + 英文词边界）──
        if (multiStepIndicatorOf(text) != null) signals.add(Signal(2, "多步指示+2"))

        // ── 维度 3：代码 / 命令信号 ──
        if (codeSignalOf(text) != null) signals.add(Signal(2, "代码/命令+2"))

        // ── 引擎专有维度（如 Coding 的编码意图 +1）──
        midDimension?.invoke(text)?.let { signals.add(it) }

        // ── 维度 4：风险词（分层 + 否定抑制；选档器另有「保底 DEEP」规则
        // 消费 [riskWord]——只有强风险置位）──
        var riskWord: String? = null
        strongRiskOf(text)?.let {
            riskWord = it
            signals.add(Signal(3, "风险词+3"))
        }
        if (riskWord == null && weakRiskOf(text) != null) signals.add(Signal(1, "弱风险词+1"))
        if (riskWord == null && contextRiskOf(text) != null) signals.add(Signal(1, "语境风险+1"))

        return TextSignalAnalysis(signals, riskWord)
    }

    /**
     * 短平快标注：零信号因子且文本 < [QUICK_TEXT_MAX] 字符时返回标签
     * （此时评分必然 0，选档为 LIGHT——标注只为理由可读性）；否则 null。
     *
     * @param factorCount 选档器当前**全部**因子数（含错误史等引擎专有因子，
     *   口径与历史行为一致：有任何因子即不算短平快）。
     */
    fun quickLabel(text: String, factorCount: Int): String? =
        if (factorCount == 0 && text.length < QUICK_TEXT_MAX) "短平快(<${QUICK_TEXT_MAX}字符)" else null

    /**
     * 统一决策理由拼装：`因子1、因子2 → 评分 N → LEVEL（调整1；调整2）`。
     * 零因子显示「无信号」，零调整省略括号段。
     *
     * SPE-01 净化约束：因子标签由各检测器产出（维度名+权重），本函数
     * 原样拼接——命中词原文不进理由串，不随 system prompt 下发。
     */
    fun buildReason(factors: List<String>, score: Int, levelName: String, adjustments: List<String>): String =
        buildString {
            append(factors.joinToString("、").ifEmpty { "无信号" })
            append(" → 评分 ").append(score)
            append(" → ").append(levelName)
            if (adjustments.isNotEmpty()) {
                append("（").append(adjustments.joinToString("；")).append("）")
            }
        }

    /**
     * 代码/命令信号检测：命中任意信号返回首个命中的信号词（供诊断与
     * 策略分支；不进理由串——标签已净化），未命中返回 null。
     *
     * - 围栏代码块；
     * - `$ ` shell 提示符（dollar + 空格）；
     * - git / npm / apt / pip / gradle / adb / shell / curl 等命令词
     *   （英文一律词边界，`digit` / `adapt` 不再误命中）。
     */
    fun codeSignalOf(text: String): String? = when {
        text.contains("```") -> "```"
        text.contains("\$ ") -> "\$ "
        else -> CODE_COMMAND_REGEX.find(text)?.value
    }

    /** 多步指示词检测：中文子串或英文词边界命中，返回命中词（诊断用）。 */
    internal fun multiStepIndicatorOf(text: String): String? =
        MULTI_STEP_INDICATORS_ZH.firstOrNull { text.contains(it) }
            ?: MULTI_STEP_INDICATORS_EN.find(text)?.value

    /** 强风险词检测：返回命中词（供 [TextSignalAnalysis.riskWord] 保底消费）。 */
    internal fun strongRiskOf(text: String): String? {
        val zh = STRONG_RISK_ZH.firstOrNull { text.contains(it) }
        val en = STRONG_RISK_EN.find(text)?.value
        val hit = zh ?: en ?: return null
        // 否定窗口内的强风险同样抑制（计分与保底一并取消）。
        return if (isNegatedAt(text, text.indexOf(hit))) null else hit
    }

    /** 弱风险词检测：+1 不保底；否定窗口内不命中。 */
    internal fun weakRiskOf(text: String): String? {
        val zh = WEAK_RISK_ZH.firstOrNull { text.contains(it) }
        val en = WEAK_RISK_EN.find(text)?.value
        val hit = zh ?: en ?: return null
        return if (isNegatedAt(text, text.indexOf(hit))) null else hit
    }

    /** 语境风险词检测：动作动词 + 权限 搭配才命中；否定窗口内不命中。 */
    internal fun contextRiskOf(text: String): String? {
        var idx = text.indexOf(CONTEXT_RISK_WORD)
        while (idx >= 0) {
            if (!isNegatedAt(text, idx) && hasActionVerbBefore(text, idx)) return CONTEXT_RISK_WORD
            idx = text.indexOf(CONTEXT_RISK_WORD, idx + 1)
        }
        return null
    }

    /**
     * 有效文本长度（SPE-01 P0-4）：剥离代码围栏、@file 引用、URL 后按
     * Unicode code point 计数（对 CJK 与 ASCII 的复杂度姿态更一致）。
     */
    internal fun effectiveTextLength(text: String): Int {
        val stripped = NOISE_STRIPPERS.fold(text) { acc, re -> re.replace(acc, " ") }
        return stripped.codePointCount(0, stripped.length)
    }

    // ═══ 否定 / 语境判定（SPE-01 P0-2）═══

    /** 命中位置向前看是否落在否定窗口内（该维度不计分、不保底）。 */
    private fun isNegatedAt(text: String, index: Int): Boolean {
        if (index <= 0) return false
        val windowStart = (index - NEGATION_WINDOW_CHARS).coerceAtLeast(0)
        val window = text.substring(windowStart, index)
        return NEGATION_CUES.any { window.contains(it, ignoreCase = true) }
    }

    /** 语境风险词前 [CONTEXT_ACTION_SPAN] 字符内是否有权限动作动词。 */
    private fun hasActionVerbBefore(text: String, index: Int): Boolean {
        val windowStart = (index - CONTEXT_ACTION_SPAN).coerceAtLeast(0)
        val window = text.substring(windowStart, index)
        return CONTEXT_ACTION_VERBS.any { window.contains(it) }
    }

    // ═══ 词表（SPE-01 P0-1：分层 + 词边界）═══

    /** 中文多步指示词（子串匹配；单字「先」误报率高，序列语义由 然后/接着 承接）。 */
    private val MULTI_STEP_INDICATORS_ZH = listOf("然后", "接着", "步骤", "多任务")

    /** 英文多步指示词（词边界匹配——explain/planning/steps 不再误命中）。 */
    private val MULTI_STEP_INDICATORS_EN_WORDS = listOf("first", "then", "step", "plan")

    private val MULTI_STEP_INDICATORS_EN = Regex(
        "\\b(" + MULTI_STEP_INDICATORS_EN_WORDS.joinToString("|") + ")\\b",
        RegexOption.IGNORE_CASE
    )

    /** 强风险词·中文（+3 且硬保底 DEEP）。 */
    private val STRONG_RISK_ZH = listOf("格式化", "刷机")

    /** 强风险词·英文词形（词边界；+3 且硬保底 DEEP）。 */
    private val STRONG_RISK_EN_WORDS = listOf("rm -rf", "factory reset", "wipe")

    private val STRONG_RISK_EN = Regex(
        "\\b(" + STRONG_RISK_EN_WORDS.joinToString("|") + ")\\b",
        RegexOption.IGNORE_CASE
    )

    /** 弱风险词·中文（+1，无保底）。 */
    private val WEAK_RISK_ZH = listOf("删除", "卸载")

    /** 弱风险词·英文词形（词边界；+1，无保底）。 */
    private val WEAK_RISK_EN_WORDS = listOf("uninstall")

    private val WEAK_RISK_EN = Regex("\\buninstall\\b", RegexOption.IGNORE_CASE)

    /** 语境风险词：仅在动作动词搭配时计分（Android 高频词不再一律抬档）。 */
    private const val CONTEXT_RISK_WORD = "权限"

    /** 权限类动作动词（向前 [CONTEXT_ACTION_SPAN] 字符内搭配判定）。 */
    private val CONTEXT_ACTION_VERBS = listOf(
        "申请", "修改", "关闭", "授予", "撤销", "开启", "打开", "收回", "禁用", "调整", "限制"
    )

    /** 语境动作搭配窗口（动词与「权限」的最大间隔字符数）。 */
    private const val CONTEXT_ACTION_SPAN = 6

    /** 否定 / 只读抑制窗口（命中词向前该长度内出现否定词 → 抑制）。 */
    private const val NEGATION_WINDOW_CHARS = 12

    /** 否定 / 只读线索（保守词形：单字「别」误报高，只收复合形态）。 */
    private val NEGATION_CUES = listOf(
        "不要", "别把", "别删", "别动", "别改", "千万别", "无需", "避免", "禁止",
        "只读", "只看", "dry-run", "dry run", "don't", "do not"
    )

    /** 有效长度剥离项：代码围栏（含未闭合到 EOF）、@file 引用、URL。 */
    private val NOISE_STRIPPERS = listOf(
        Regex("`{3}[\\s\\S]*?(?:`{3}|$)"),
        Regex("@[\\w./-]+\\.[A-Za-z0-9]+(?::\\d+)?"),
        Regex("https?://\\S+")
    )

    /** 命令词（技术任务信号；英文词边界）。 */
    val CODE_COMMAND_WORDS: List<String> = listOf(
        "git", "npm", "apt", "pip", "gradle", "adb", "shell", "curl", "kotlin", "python", "terminal"
    )

    /** 多步指示词（诊断视图：中文子串 + 英文词边界合集）。 */
    val MULTI_STEP_INDICATORS: List<String> = MULTI_STEP_INDICATORS_ZH + MULTI_STEP_INDICATORS_EN_WORDS

    /** 风险词全集（分层后按旧名暴露给诊断；策略消费走 [TextSignalAnalysis.riskWord]）。 */
    val RISK_KEYWORDS: List<String> =
        STRONG_RISK_ZH + STRONG_RISK_EN_WORDS + WEAK_RISK_ZH + WEAK_RISK_EN_WORDS + listOf(CONTEXT_RISK_WORD)

    /** 命令词正则（词边界；词表见 [CODE_COMMAND_WORDS]）。 */
    private val CODE_COMMAND_REGEX = Regex(
        "\\b(" + CODE_COMMAND_WORDS.joinToString("|") + ")\\b",
        RegexOption.IGNORE_CASE
    )

    const val LONG_TEXT_LIGHT = 500
    const val LONG_TEXT_HEAVY = 1500
    const val QUICK_TEXT_MAX = 80

    /** 共享阈值：总评分 ≥ 3 → STANDARD 档起点（两侧选档器同值）。 */
    const val STANDARD_SCORE_FLOOR = 3

    /** 共享阈值：总评分 ≥ 6 → DEEP 档起点。 */
    const val DEEP_SCORE_FLOOR = 6

    /** 共享阈值：总评分 > 8 → 深度封顶档起点（Agent 侧 MAXIMUM / Coding 侧 MAXIMUM）。 */
    const val DEEP_SCORE_CEILING = 8

    /** 共享阈值：长任务深水区判定（历史累计工具调用 > 此值）。 */
    const val DEEP_WATER_TOOL_CALLS = 15
}
