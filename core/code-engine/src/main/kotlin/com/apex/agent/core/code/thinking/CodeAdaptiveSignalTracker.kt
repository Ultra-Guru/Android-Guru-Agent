package com.apex.agent.core.code.thinking

/**
 * # Code Adaptive Signal Tracker — AUTO 档信号追踪器（会话作用域状态机）
 *
 * [CodeAdaptiveThinkingSelector] 是**纯函数**（预检选档 / 深水区升级观察 /
 * 稳定回落观察），它消费的三类运行时信号由本类持有与维护：
 *
 * | 信号 | 消费方 | 生命周期 |
 * |------|--------|----------|
 * | lastRunToolCalls / lastRunErrors（上一轮归档） | [CodeAdaptiveThinkingSelector.select] 发送前预检 | 跨 run 存续，run 收尾时归档 |
 * | runToolCalls / 滑窗成败（本轮累计） | [CodeAdaptiveThinkingSelector.escalateOnDeepWater] 运行中观察 | 单 run，beginRun 重置 |
 * | 升降档滞回闸门（SPE-02） | 升级/回落观察器的冷却与次数门控 | 单 run，beginRun 重置 |
 *
 * ## 单 run 状态机（与 VM 发送链路的时序契约）
 *
 * ```text
 * 预检(读归档+指纹对比) → beginRun(清本轮) → onToolComplete×N
 *   → [升级/回落观察器按闸门决策] → endRun(归档本轮+记指纹)
 * ```
 *
 * 预检发生在 beginRun **之前**——新一轮还没开始时，上一轮的错误史才是
 * 有效输入；beginRun 只清本轮计数，**不动归档**（否则预检刚读的信号被
 * 自己清掉）。endRun 把本轮窗口按 3 滑窗口径归档（窗口内失败数，与
 * 观察器 [AdaptivePolicy.windowSize] 口径一致）。
 *
 * ## 会话边界（v1.5.17 修复：跨会话信号泄漏）
 *
 * 新会话 / 恢复历史 / 切换工作区都是**会话边界**：旧会话的错误史对新
 * 会话毫无意义。边界处调 [resetConversation] 全量清零。
 *
 * ## 任务边界（SPE-02 P0-2：错误史跨任务残留）
 *
 * 同一会话内**换任务**（goal 指纹变化）是轻量边界：旧任务的深水区失败
 * 不该给新任务强行抬档。[onNewTaskBoundary] 按 goal 归一化指纹衰减
 * `lastRunErrors`（归零），`lastRunToolCalls` 只保留「是否同一长任务
 * 续跑」的布尔语义（> [ThinkingSignalAnalyzer.DEEP_WATER_TOOL_CALLS]）。
 * 指纹由 [endRun] 收尾时记录（下一轮预检对比）。
 *
 * ## 升降档滞回闸门（SPE-02 P0-1）
 *
 * [escalateOnDeepWater] 曾是「只升不降」的单向门：升级后直到 run 结束
 * 不回落、无冷却、无次数上限。本类为纯函数观察器提供显式闸门快照
 * （[adaptiveShiftGates]），升降档落地由 [markEscalation] /
 * [markDeescalation] 记账（冷却重启、连击清零、升档计数）。
 *
 * 纯 JVM 状态机、无 IO、无协程依赖（主线程读写，与 VM 事件归约同线程）。
 */
class CodeAdaptiveSignalTracker(
    /** 成败滑窗尺寸（观察器口径 3；构造可覆盖仅为可测性）。 */
    private val windowSize: Int = DEFAULT_WINDOW_SIZE
) {

    /** 发送前预检输入（上一轮归档信号 + 任务指纹对比口径）。 */
    data class PreCheckSignals(val lastRunToolCalls: Int, val lastRunErrors: Int)

    /** 运行中升级观察输入（本轮累计信号）。 */
    data class EscalationSignals(val runToolCalls: Int, val recentWindowErrors: Int)

    private var runToolCalls = 0
    private val recentOutcomes = ArrayDeque<Boolean>()
    private var lastRunToolCalls = 0
    private var lastRunErrors = 0

    // ── SPE-02：升降档滞回闸门状态 ──
    private var runEscalations = 0
    private var callsSinceLastShift = Int.MAX_VALUE
    private var consecutiveSuccesses = 0

    // ── SPE-02：任务指纹（错误史跨任务衰减的判定依据）──
    private var lastRunGoalFingerprint: Int? = null

    /**
     * 工具回合完成回调（VM 在每次 ToolCallComplete 后调用）：
     * 本轮计数 +1，成败入滑窗（超出窗口尺寸弹出最旧）；
     * 冷却进度 +1；成败计入连击（失败清零连击）。
     */
    fun onToolComplete(success: Boolean) {
        runToolCalls++
        recentOutcomes.addLast(success)
        while (recentOutcomes.size > windowSize) recentOutcomes.removeFirst()
        if (callsSinceLastShift != Int.MAX_VALUE) callsSinceLastShift++
        consecutiveSuccesses = if (success) consecutiveSuccesses + 1 else 0
    }

    /**
     * 新 run 开始：清**本轮**计数与滞回闸门（预检已消费完归档信号，
     * 此后归档保持不动直到 endRun 覆写）。
     */
    fun beginRun() {
        runToolCalls = 0
        recentOutcomes.clear()
        runEscalations = 0
        callsSinceLastShift = Int.MAX_VALUE
        consecutiveSuccesses = 0
    }

    /**
     * run 收尾：本轮信号按滑窗口径归档（下轮预检的输入）+ 记录本轮
     * goal 指纹（下轮 [onNewTaskBoundary] 的对比基准）。
     *
     * @param goalText 本轮任务 goal 原文（null = 调用方未提供——跳过指纹
     *   记录，衰减判定退化为「总按同任务处理」）。
     */
    fun endRun(goalText: String? = null) {
        lastRunToolCalls = runToolCalls
        lastRunErrors = recentOutcomes.count { !it }
        if (goalText != null) lastRunGoalFingerprint = goalFingerprintOf(goalText)
    }

    /** 发送前预检信号快照（上一轮归档）。 */
    fun preCheckSignals(): PreCheckSignals =
        PreCheckSignals(lastRunToolCalls = lastRunToolCalls, lastRunErrors = lastRunErrors)

    /** 运行中升级观察信号快照（本轮累计 + 滑窗失败数）。 */
    fun escalationSignals(): EscalationSignals =
        EscalationSignals(runToolCalls = runToolCalls, recentWindowErrors = recentOutcomes.count { !it })

    /** 升降档滞回闸门快照（纯函数观察器的显式输入）。 */
    fun adaptiveShiftGates(): AdaptiveShiftGates =
        AdaptiveShiftGates(
            runEscalations = runEscalations,
            callsSinceLastShift = callsSinceLastShift,
            consecutiveSuccesses = consecutiveSuccesses
        )

    /**
     * 升档落地记账：升档计数 +1、冷却重启（从当前调用数起算）、连击清零
     * （升档后旧连击不再表征「升级前的稳定」）。
     */
    fun markEscalation() {
        runEscalations++
        callsSinceLastShift = 0
        consecutiveSuccesses = 0
    }

    /** 降档落地记账：冷却重启、连击清零（回落也要重新积累稳定证据）。 */
    fun markDeescalation() {
        callsSinceLastShift = 0
        consecutiveSuccesses = 0
    }

    /**
     * 任务边界（SPE-02 P0-2）：同一会话内**换任务**的轻量信号衰减。
     *
     * - goal 指纹与上一轮一致（同任务续跑）→ 不动（错误史有效）；
     * - 指纹不同（新任务）→ `lastRunErrors` 衰减为 0（旧任务的失败不再
     *   抬档），`lastRunToolCalls` 保留（只影响「上轮深水区保底
     *   STANDARD」的弱语义——长会话里反复跑过工具的事实仍有参考价值，
     *   而「上轮深水区连环失败保底 ULTRACODE」需要 errors≥2 已被清零
     *   天然失效）。
     *
     * VM 在预检（[preCheckSignals] 读取）前调用；会话边界仍走
     * [resetConversation] 全量清零。
     */
    fun onNewTaskBoundary(goalText: String) {
        val fingerprint = goalFingerprintOf(goalText)
        if (lastRunGoalFingerprint != null && lastRunGoalFingerprint != fingerprint) {
            lastRunErrors = 0
        }
    }

    /** 会话边界（新会话 / 恢复历史 / 切换工作区）：全量清零。 */
    fun resetConversation() {
        runToolCalls = 0
        recentOutcomes.clear()
        lastRunToolCalls = 0
        lastRunErrors = 0
        runEscalations = 0
        callsSinceLastShift = Int.MAX_VALUE
        consecutiveSuccesses = 0
        lastRunGoalFingerprint = null
    }

    companion object {
        /** 与升级观察器的滑窗口径一致（[AdaptivePolicy.windowSize] 默认 3）。 */
        const val DEFAULT_WINDOW_SIZE = 3

        /**
         * goal 归一化指纹（SPE-02 P0-2）：剥空白/大小写归一后的稳定 hash。
         *
         * 用 `hashCode` 而非加密 hash：指纹只做「同一会话内前后两次输入
         * 是否大致同一任务」的对比，冲突代价是少衰减一次错误史（保守
         * 方向），不值得引入 MessageDigest 的开销与依赖。
         */
        internal fun goalFingerprintOf(goalText: String): Int =
            goalText.trim().lowercase().hashCode()
    }
}
