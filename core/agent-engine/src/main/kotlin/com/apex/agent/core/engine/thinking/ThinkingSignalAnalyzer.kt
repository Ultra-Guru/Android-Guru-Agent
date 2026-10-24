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
 * ## 共享计分表
 *
 * | 维度 | 条件 | 加分 | 标签示例 |
 * |------|------|------|----------|
 * | 文本长度 | > 1500 字 | +3 | `超长文本+3` |
 * |          | > 500 字（替换不叠加） | +2 | `长文本+2` |
 * | 多步指示词 | 先/然后/接着/步骤/first/then/step/plan 等 | +2 | `多步指示(然后)+2` |
 * | 代码/命令 | ``` 围栏 / `$ ` 提示符 / git·npm·gradle 等命令词 | +2 | `代码/命令(git)+2` |
 * | 风险词 | rm -rf/删除/卸载/格式化/刷机 等 | +3 | `风险词(删除)+3` |
 *
 * ## 引擎专有维度的插入位（[analyze] 的 [midDimension] 钩子）
 *
 * Coding 选档器多一个「编码意图 +1」维度，其理由标签在既有产出中位于
 * 代码/命令与风险词**之间**——为保持两个选档器的理由串字节级不变，
 * [analyze] 允许调用方传入一个在该位置求值的维度检测器；Agent 侧不传
 * （null）即得四维度原生顺序。
 *
 * 纯函数、无状态：同一输入永远得到同一分析结果。
 */
object ThinkingSignalAnalyzer {

    /** 单个计分维度：权重 + 可解释标签（直接进决策理由串）。 */
    data class Signal(val weight: Int, val label: String)

    /**
     * 文本信号分析结果。
     *
     * @param signals 按稳定顺序排列的命中维度（长度 → 多步 → 代码/命令 →
     *   引擎专有 → 风险词；未命中的维度不出现）。
     * @param riskWord 命中的首个风险词（供选档器的「风险词保底 DEEP」规则
     *   使用）；未命中为 null。
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
     *   在顺序上插在代码/命令之后、风险词之前（保持两侧理由串既有顺序，
     *   见类 KDoc）。null = 无引擎专有维度。
     */
    fun analyze(
        text: String,
        midDimension: ((String) -> Signal?)? = null
    ): TextSignalAnalysis {
        val signals = mutableListOf<Signal>()

        // ── 维度 1：文本长度（>500 +2；>1500 +3，替换不叠加）──
        when {
            text.length > LONG_TEXT_HEAVY -> signals.add(Signal(3, "超长文本+3"))
            text.length > LONG_TEXT_LIGHT -> signals.add(Signal(2, "长文本+2"))
        }

        // ── 维度 2：多步指示词 ──
        MULTI_STEP_INDICATORS.firstOrNull { text.contains(it, ignoreCase = true) }
            ?.let { signals.add(Signal(2, "多步指示($it)+2")) }

        // ── 维度 3：代码 / 命令信号 ──
        codeSignalOf(text)?.let { signals.add(Signal(2, "代码/命令($it)+2")) }

        // ── 引擎专有维度（如 Coding 的编码意图 +1）──
        midDimension?.invoke(text)?.let { signals.add(it) }

        // ── 维度 4：风险词（选档器另有「保底 DEEP」规则消费 [riskWord]）──
        val riskWord = RISK_KEYWORDS.firstOrNull { text.contains(it, ignoreCase = true) }
        if (riskWord != null) signals.add(Signal(3, "风险词($riskWord)+3"))

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
     * 零因子显示「无信号」，零调整省略括号段——与两侧选档器既有产出一致。
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
     * 代码/命令信号检测：命中任意信号返回**首个**命中的信号词（写进理由），
     * 未命中返回 null。
     *
     * - ``` 围栏代码块；
     * - `$ ` shell 提示符（dollar + 空格）；
     * - git / npm / apt / pip / gradle / adb / shell / curl 等命令词。
     */
    fun codeSignalOf(text: String): String? = when {
        text.contains("```") -> "```"
        text.contains("\$ ") -> "\$ "
        else -> CODE_COMMAND_WORDS.firstOrNull { text.contains(it, ignoreCase = true) }
    }

    /** 多步指示词（中英双语；「多任务」按词匹配，其余按子串）。 */
    val MULTI_STEP_INDICATORS = listOf(
        "先", "然后", "接着", "步骤", "多任务", "first", "then", "step", "plan"
    )

    /** 风险词：破坏性 / 不可逆 / 系统级操作的强信号。 */
    val RISK_KEYWORDS = listOf(
        "rm -rf", "删除", "uninstall", "卸载", "格式化", "刷机", "权限", "factory reset", "wipe"
    )

    /** 命令词（技术任务信号）。 */
    val CODE_COMMAND_WORDS = listOf(
        "git", "npm", "apt", "pip", "gradle", "adb", "shell", "curl", "kotlin", "python", "terminal"
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
