package com.apex.agent.core.code.thinking

/**
 * # Adaptive Policy — AUTO 升降档策略参数单一事实源（SPE-02）
 *
 * SPE-02 之前，深水区升级/降档相关常量散落四处（本包内 [CodeAdaptiveThinkingSelector]
 * 的 `DEEP_WATER_ULTRACODE_CALLS` / `ULTRACODE_ERROR_FLOOR` / `RECENT_WINDOW`、
 * [CodeAdaptiveSignalTracker] 的 `DEFAULT_WINDOW_SIZE`、共享分析器的
 * `DEEP_WATER_TOOL_CALLS`、agent 侧控制器的滑窗常量）——改一处即漂移。
 * 本数据类把**策略参数**收口为一份可注入、可覆盖（可测性）的配置对象：
 *
 * | 参数 | 旧出处 | 语义 |
 * |------|--------|------|
 * | [windowSize] | Tracker.DEFAULT_WINDOW_SIZE = 3 | 成败滑窗尺寸 |
 * | [deepWaterCalls] | Selector.DEEP_WATER_ULTRACODE_CALLS = 30 | 深水区升级的工具调用阈值 |
 * | [errorFloor] | Selector.ULTRACODE_ERROR_FLOOR = 2 | 深水区升级的滑窗失败数下限 |
 * | [cooldownToolCalls] | 无（SPE-02 新增） | 升/降档后冷却窗口：冷却内不再升/降 |
 * | [maxAutoEscalationsPerRun] | 无（SPE-02 新增） | 单 run 自动升档次数上限（成本防线） |
 * | [deescalateSuccessStreak] | 无（SPE-02 新增） | 连续成功多少次才允许回落 |
 * | [sessionTokenCeiling] | 无（SPE-02 预留） | 会话 token 上限（触顶回落 + UI 提示） |
 *
 * ## 与旧常量的兼容
 *
 * [DEFAULT] 的数值与旧散落常量逐一相等（windowSize=3 / deepWaterCalls=30 /
 * errorFloor=2）——迁移是**行为等价**的收口，新增的冷却/上限/回落参数是
 * 旧实现缺失的能力（旧实现只升不降、无冷却、无次数上限）。
 *
 * 纯数据、无副作用；建议值均为 SPE 起点，落地后用回归用例与真机回放标定。
 */
data class AdaptivePolicy(
    /** 成败滑窗尺寸（升级观察器的失败计数窗口）。 */
    val windowSize: Int = 3,
    /** 深水区升级阈值：本轮工具调用 > 此值且滑窗失败 ≥ [errorFloor] 才触发。 */
    val deepWaterCalls: Int = 30,
    /** 深水区升级的滑窗失败数下限。 */
    val errorFloor: Int = 2,
    /** 升/降档后的冷却窗口（冷却期内的工具调用数）：冷却内不再升/降（滞回）。 */
    val cooldownToolCalls: Int = 15,
    /** 单 run 自动升档次数上限：触顶后本 run 不再自动升（成本防线）。 */
    val maxAutoEscalationsPerRun: Int = 1,
    /** 连续成功多少次工具调用才允许回落（防抖：偶发成功不回落）。 */
    val deescalateSuccessStreak: Int = 5,
    /**
     * 会话累计 token 上限（SPE-02 P1-5 预留位；null = 未启用）。
     * 启用时触顶强制回落一档并在 UI 提示——由 VM 侧接线消费。
     */
    val sessionTokenCeiling: Int? = null
) {
    companion object {
        /** 与旧散落常量逐一等价的默认策略（行为等价迁移 + 新增滞回参数）。 */
        val DEFAULT = AdaptivePolicy()
    }
}

/**
 * 升降档闸门状态快照（[CodeAdaptiveSignalTracker] 持有的滞回状态，纯函数
 * [CodeAdaptiveThinkingSelector] 的显式输入）。
 *
 * SPE-02 的核心洞察：升级观察器保持**纯函数**（可测），冷却/次数/连击
 * 等**有状态**的滞回闸门外置到 Tracker —— 状态与决策分离。
 *
 * @param runEscalations 本 run 已发生的自动升档次数（beginRun 清零）。
 * @param callsSinceLastShift 最近一次升/降档以来的工具调用数（冷却进度）。
 * @param consecutiveSuccesses 自最近一次升/降档（或失败）以来的连续成功数。
 */
data class AdaptiveShiftGates(
    val runEscalations: Int = 0,
    val callsSinceLastShift: Int = Int.MAX_VALUE,
    val consecutiveSuccesses: Int = 0
)
