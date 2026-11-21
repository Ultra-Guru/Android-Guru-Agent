package com.apex.agent.core.code.thinking

import com.apex.agent.core.engine.thinking.ThinkingSignalAnalyzer

/**
 * # Code Adaptive Thinking Selector — Coding 模式 AUTO 档自治选档器
 *
 * ## 与 agent-engine 选档器（AdaptiveThinkingSelector）的根本差异
 *
 * | 维度 | Agent 引擎侧选档器 | 本类（coding 自治） |
 * |------|--------------------|---------------------|
 * | 时机 | 引擎**逐轮** onIterationStart | **发送前预检一次** + 运行中深水区升级观察 |
 * | 位置 | agent-engine（Agent 模式共用） | code-engine（仅 Coding 页消费） |
 * | 信号 | 当轮 userText + 引擎内计数 | 任务 goal + **上一轮** lastRun 计数/错误史 |
 * | 专有维度 | — | **编码意图 +1**（coding 页输入几乎总含代码信号，弱信号不重复计权） |
 * | 档位阶梯 | 六档（止于 MAXIMUM） | 七档（MAXIMUM 之上有 ULTRACODE / APEXCODE） |
 *
 * 文本信号检测与计分（长度 / 多步指示词 / 代码命令 / 风险词）委托共享核心
 * [ThinkingSignalAnalyzer]（与 agent-engine 选档器共用一份词表与算术，防两
 * 模块漂移）；本类保留 coding 侧的**选档策略**：编码意图维度（经共享分析器
 * 的 mid 钩子插入计分序列）、七档阈值映射、错误史升档与保底规则、深水区
 * 升级观察器。
 *
 * 自治语义：引擎从不接收 AUTO——VM 在发送前用 [select] 把 AUTO 解析成
 * 具体深度档（决策进系统消息 + UI 回显），运行中用
 * [escalateOnDeepWater] 观察器在深水区连环失败时升级。**四线统一**：
 * 深潜 / 标准 / 织造 / 信使四条逻辑线共用本选档器（VM 侧 resolveRuntime
 * ThinkingLevel 单点驱动），预检与升级语义不再因切线而漂移；深潜线额外
 * 消费档位的编码特化指令与旋钮补偿（CodeThinkingPrompts / Profile），
 * kit 三线按档位映射各自的回合预算倍率。行为独立于 Agent 模式的 AUTO
 * （两页面各自解释自己的"自动"）。
 *
 * ## 预检评分模型（每项加权分，全部因子写进 [CodeAdaptiveDecision.reason]）
 *
 * | 维度 | 条件 | 加分 | 出处 |
 * |------|------|------|------|
 * | 文本长度 | > 500 字 +2；> 1500 字 +3（替换不叠加） | 长需求含隐式多步 | 共享 |
 * | 多步指示词 | 先/然后/接着/步骤/first/then/step/plan | +2 | 共享 |
 * | 代码/命令含量 | ``` 代码块 / `$ ` 提示符 / git·npm·gradle 等命令词 | +2 | 共享 |
 * | **编码意图**（专有） | 强编码信号：@file 引用 / CodeEdit·CodeWrite·CodeRead 语义词 / “实现/重构/修复+函数|类|接口” | **+1** | 本类 |
 * | 风险词 | 删除/uninstall/rm -rf/格式化 等 | +3，**硬保底 DEEP** | 共享 |
 * | 错误史 | lastRunErrors > 0（上一轮 run 的失败史） | +2 且显式升一档 | 本类 |
 *
 * ## 总分 → 档位阈值
 *
 * | 总分 | 档位 |
 * |------|------|
 * | < 3 | LIGHT |
 * | 3..5 | STANDARD |
 * | 6..8 | DEEP |
 * | 9..11 | MAXIMUM |
 * | > 11 | ULTRACODE |
 *
 * ## 档位下限（阈值结果之上取 max，写进理由）
 *
 * - **强风险词 → 至少 DEEP**（SPE-01 分层后：弱/语境风险不再保底）；
 * - 上一轮深水区（lastRunToolCalls > 15）→ 至少 STANDARD；
 * - 深水区 + 连环失败（lastRunToolCalls > 30 且 lastRunErrors >= 2）→
 *   至少 ULTRACODE（上轮系统性跑偏，本轮开局即编码深推理闭环）。
 *
 * **APEXCODE 仅用户显式指定**——预检任何路径都不可能返回 APEXCODE
 * （成本防线）。
 *
 * ## 深水区升降档（SPE-02：滞回 + 冷却 + 回落）
 *
 * [escalateOnDeepWater] 保持纯函数；冷却窗口 / 单 run 升档上限由
 * [AdaptiveShiftGates] 显式输入（状态外置在 Tracker）。
 * [deescalateOnStabilized] 是新增的**回落**通道：冷却结束且连续成功
 * 达标 → 回落到本轮预检基准档——旧实现只升不降，深水区升级后整个
 * run 锁死 ULTRACODE（×2.0 迭代 / 32768 预算）直到收尾。
 *
 * 纯函数、无状态：同一输入永远得到同一决策（可测、可解释）。
 */
class CodeAdaptiveThinkingSelector(
    /** 深水区升降档策略（SPE-02 常量单一事实源；默认 [AdaptivePolicy.DEFAULT]）。 */
    private val policy: AdaptivePolicy = AdaptivePolicy.DEFAULT
) {

    /** 预检选档结果：档位 + 可解释理由（系统消息 + UI 回显）。 */
    data class CodeAdaptiveDecision(val level: CodeThinkingLevel, val reason: String)

    /**
     * 发送前预检：AUTO → 具体深度档。
     *
     * @param goalText 任务目标文本（用户输入，含 @file 引用原文）
     * @param lastRunToolCalls 上一轮 run 的累计工具调用数（错误史信号）
     * @param lastRunErrors 上一轮 run 的错误数（引擎侧 3 滑窗口径）
     */
    fun select(goalText: String, lastRunToolCalls: Int, lastRunErrors: Int): CodeAdaptiveDecision {
        val analysis = ThinkingSignalAnalyzer.analyze(goalText, ::codingIntentSignal)
        val factors = analysis.labels.toMutableList()
        var score = analysis.score

        // ── 本类专有维度：上一轮错误史 ──
        if (lastRunErrors > 0) {
            score += 2; factors.add("上轮错误史(${lastRunErrors}次)+2")
        }

        // ── 总分 → 基础档位 ──
        var level = when {
            score > ULTRACODE_SCORE_CEILING -> CodeThinkingLevel.ULTRACODE
            score > ThinkingSignalAnalyzer.DEEP_SCORE_CEILING -> CodeThinkingLevel.MAXIMUM
            score >= ThinkingSignalAnalyzer.DEEP_SCORE_FLOOR -> CodeThinkingLevel.DEEP
            score >= ThinkingSignalAnalyzer.STANDARD_SCORE_FLOOR -> CodeThinkingLevel.STANDARD
            else -> CodeThinkingLevel.LIGHT
        }

        ThinkingSignalAnalyzer.quickLabel(goalText, factors.size)?.let { factors.add(it) }

        // ── 调整（升档 / 保底，逐条写进理由）──
        val adjustments = mutableListOf<String>()

        // 错误史升一档：+2 之外显式保证「升一档」语义（跨不过档位边界时
        // 直接提升）；ULTRACODE 封顶（APEXCODE 不可自动选）。
        if (lastRunErrors > 0) {
            level = when (level) {
                CodeThinkingLevel.LIGHT -> CodeThinkingLevel.STANDARD
                CodeThinkingLevel.STANDARD -> CodeThinkingLevel.DEEP
                CodeThinkingLevel.DEEP -> CodeThinkingLevel.MAXIMUM
                CodeThinkingLevel.MAXIMUM -> CodeThinkingLevel.ULTRACODE
                CodeThinkingLevel.ULTRACODE, CodeThinkingLevel.APEXCODE -> CodeThinkingLevel.ULTRACODE
                CodeThinkingLevel.NONE, CodeThinkingLevel.AUTO -> level
            }
            adjustments.add("错误史升一档")
        }

        if (analysis.riskWord != null && level < CodeThinkingLevel.DEEP) {
            level = CodeThinkingLevel.DEEP
            adjustments.add("风险词保底DEEP")
        }
        if (lastRunToolCalls > ThinkingSignalAnalyzer.DEEP_WATER_TOOL_CALLS && level < CodeThinkingLevel.STANDARD) {
            level = CodeThinkingLevel.STANDARD
            adjustments.add("上轮深水区(>${ThinkingSignalAnalyzer.DEEP_WATER_TOOL_CALLS}次调用)保底STANDARD")
        }
        if (lastRunToolCalls > policy.deepWaterCalls &&
            lastRunErrors >= policy.errorFloor &&
            level < CodeThinkingLevel.ULTRACODE
        ) {
            level = CodeThinkingLevel.ULTRACODE
            adjustments.add("上轮深水区连环失败(${lastRunToolCalls}次调用&${lastRunErrors}错误)保底ULTRACODE")
        }

        val reason = ThinkingSignalAnalyzer.buildReason(factors, score, level.name, adjustments)
        return CodeAdaptiveDecision(level, reason)
    }

    /**
     * 运行中深水区升级观察器（VM 在每次 ToolCallComplete 后调用）。
     *
     * 本轮 run 工具调用 > [AdaptivePolicy.deepWaterCalls] 且最近滑窗内 ≥
     * [AdaptivePolicy.errorFloor] 次失败、当前生效档位低于 ULTRACODE →
     * 返回升级决策（VM 落地：引擎档位热切换 + 系统消息说明）；否则
     * null（继续观察）。
     *
     * 升级目标恒为 ULTRACODE（APEXCODE 不可自动选）；已 ≥ ULTRACODE 或
     * 用户显式选择低档的场景由调用方把关（观察器只看数字信号）。
     *
     * SPE-02 滞回门控（[gates] 显式输入，状态外置在 Tracker）：
     * - 单 run 升档次数已达 [AdaptivePolicy.maxAutoEscalationsPerRun] →
     *   null（成本防线：防连环升级烧 token）；
     * - 升/降档冷却窗口未过（[AdaptiveShiftGates.callsSinceLastShift] <
     *   [AdaptivePolicy.cooldownToolCalls]）→ null（冷却内不重复升降，
     *   防抖：刚升完又被滑窗噪声触发）。
     *
     * 默认 [gates] = 无门控哨兵（升档 0 次 / 冷却已过），与旧版纯函数
     * 行为逐参数等价——既有单测与调用方零迁移成本。
     */
    fun escalateOnDeepWater(
        runToolCalls: Int,
        recentWindowErrors: Int,
        current: CodeThinkingLevel,
        gates: AdaptiveShiftGates = NO_GATES
    ): CodeAdaptiveDecision? {
        if (current >= CodeThinkingLevel.ULTRACODE) return null
        if (gates.runEscalations >= policy.maxAutoEscalationsPerRun) return null
        if (gates.callsSinceLastShift < policy.cooldownToolCalls) return null
        if (runToolCalls <= policy.deepWaterCalls) return null
        if (recentWindowErrors < policy.errorFloor) return null
        return CodeAdaptiveDecision(
            level = CodeThinkingLevel.ULTRACODE,
            reason = "深水区升级：本轮 ${runToolCalls} 次调用、最近 ${policy.windowSize} 次 " +
                "工具 ${recentWindowErrors} 次失败 → 当前思路系统性跑偏，升级 ULTRACODE 重新锚定不变量"
        )
    }

    /**
     * 运行中稳定回落观察器（SPE-02 新增：深水区升级的**下降**通道）。
     *
     * 旧实现升级后直到 run 收尾不回落（只升不降）；本观察器在每次
     * ToolCallComplete 后与升级观察器**串联**调用（VM 侧先试升级、
     * 升级返回 null 再试回落）：
     *
     * - 冷却窗口已过（[AdaptiveShiftGates.callsSinceLastShift] ≥
     *   [AdaptivePolicy.cooldownToolCalls]）且连续成功 ≥
     *   [AdaptivePolicy.deescalateSuccessStreak]（稳定证据）且当前档
     *   **高于**本轮预检基准档 [base] → 回落到基准档；
     * - 其余 → null（继续观察）。
     *
     * 回落目标恒为 [base]（本轮预检 select 结果——「回落档位等于当轮
     * select() 结果」的 SPE 验收口径）；预检永不返回 APEXCODE，回落
     * 天然不触碰 APEXCODE。
     */
    fun deescalateOnStabilized(
        gates: AdaptiveShiftGates,
        current: CodeThinkingLevel,
        base: CodeThinkingLevel
    ): CodeAdaptiveDecision? {
        if (current <= base) return null
        if (gates.callsSinceLastShift < policy.cooldownToolCalls) return null
        if (gates.consecutiveSuccesses < policy.deescalateSuccessStreak) return null
        return CodeAdaptiveDecision(
            level = base,
            reason = "深水区回落：冷却已过且连续 ${gates.consecutiveSuccesses} 次工具成功 → " +
                "系统性跑偏已缓解，回落 ${base.name} 控成本"
        )
    }

    // ═══ 内部：编码意图检测（专有维度）═══

    /** 编码意图 → 计分信号（经共享分析器的 mid 钩子插入代码/命令之后）。 */
    private fun codingIntentSignal(text: String): ThinkingSignalAnalyzer.Signal? =
        if (codingIntentOf(text)) ThinkingSignalAnalyzer.Signal(1, "编码意图+1") else null

    /** 强编码意图检测：coding 页的高频任务形态（+1 维度）。 */
    private fun codingIntentOf(text: String): Boolean {
        if (text.contains("@") && FILE_REF_REGEX.containsMatchIn(text)) return true
        if (CODE_TOOL_VERBS.any { text.contains(it, ignoreCase = true) }) return true
        return EDIT_INTENT_REGEX.containsMatchIn(text)
    }

    private companion object {
        /** code 工具语义词（goal 里直说要用什么工具的强信号）。 */
        val CODE_TOOL_VERBS = listOf(
            "CodeEdit", "CodeWrite", "CodeRead", "CodeGrep", "CodeTask", "CodeTodo"
        )

        /** @file:line 引用形态。 */
        val FILE_REF_REGEX = Regex("""@[\w./-]+\.(kt|java|xml|gradle|kts|py|js|ts|md|sh|json|toml)(:\d+)?""")

        /** 「实现/重构/修复/新增 … 函数/类/接口/方法」意图形态。 */
        val EDIT_INTENT_REGEX = Regex("""(实现|重构|修复|新增|修改|封装|抽取).{0,12}(函数|方法|类|接口|组件|模块|API)""")

        const val ULTRACODE_SCORE_CEILING = 11

        /** 无门控哨兵（升档 0 次 / 冷却已过）：与旧版纯函数行为等价。 */
        val NO_GATES = AdaptiveShiftGates(
            runEscalations = 0,
            callsSinceLastShift = Int.MAX_VALUE,
            consecutiveSuccesses = 0
        )
    }
}
