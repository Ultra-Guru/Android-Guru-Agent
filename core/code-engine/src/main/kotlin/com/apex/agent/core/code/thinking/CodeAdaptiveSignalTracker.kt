package com.apex.agent.core.code.thinking

/**
 * # Code Adaptive Signal Tracker — AUTO 档信号追踪器（会话作用域状态机）
 *
 * [CodeAdaptiveThinkingSelector] 是**纯函数**（预检选档 / 深水区升级观察），
 * 它消费的两类运行时信号由本类持有与维护：
 *
 * | 信号 | 消费方 | 生命周期 |
 * |------|--------|----------|
 * | lastRunToolCalls / lastRunErrors（上一轮归档） | [CodeAdaptiveThinkingSelector.select] 发送前预检 | 跨 run 存续，run 收尾时归档 |
 * | runToolCalls / 滑窗成败（本轮累计） | [CodeAdaptiveThinkingSelector.escalateOnDeepWater] 运行中观察 | 单 run，beginRun 重置 |
 *
 * ## 单 run 状态机（与 VM 发送链路的时序契约）
 *
 * ```text
 * 预检(读归档) → beginRun(清本轮) → onToolComplete×N → endRun(归档本轮)
 * ```
 *
 * 预检发生在 beginRun **之前**——新一轮还没开始时，上一轮的错误史才是
 * 有效输入；beginRun 只清本轮计数，**不动归档**（否则预检刚读的信号被
 * 自己清掉）。endRun 把本轮窗口按 3 滑窗口径归档（窗口内失败数，与
 * 观察器 [CodeAdaptiveThinkingSelector] 的 RECENT_WINDOW=3 口径一致）。
 *
 * ## 会话边界（v1.5.17 修复：跨会话信号泄漏）
 *
 * 新会话 / 恢复历史 / 切换工作区都是**会话边界**：旧会话的错误史对新
 * 会话毫无意义（上一个任务的深水区失败不该给新任务强行抬档）。边界处
 * 调 [resetConversation] 全量清零；此前这四个计数器散落在 VM 私有字段、
 * 无边界重置，AUTO 预检会把上一会话的信号错误地带入新会话首检。
 *
 * 纯 JVM 状态机、无 IO、无协程依赖（主线程读写，与 VM 事件归约同线程）。
 */
class CodeAdaptiveSignalTracker(
    /** 成败滑窗尺寸（观察器口径 3；构造可覆盖仅为可测性）。 */
    private val windowSize: Int = DEFAULT_WINDOW_SIZE
) {

    /** 发送前预检输入（上一轮归档信号）。 */
    data class PreCheckSignals(val lastRunToolCalls: Int, val lastRunErrors: Int)

    /** 运行中升级观察输入（本轮累计信号）。 */
    data class EscalationSignals(val runToolCalls: Int, val recentWindowErrors: Int)

    private var runToolCalls = 0
    private val recentOutcomes = ArrayDeque<Boolean>()
    private var lastRunToolCalls = 0
    private var lastRunErrors = 0

    /**
     * 工具回合完成回调（VM 在每次 ToolCallComplete 后调用）：
     * 本轮计数 +1，成败入滑窗（超出窗口尺寸弹出最旧）。
     */
    fun onToolComplete(success: Boolean) {
        runToolCalls++
        recentOutcomes.addLast(success)
        while (recentOutcomes.size > windowSize) recentOutcomes.removeFirst()
    }

    /**
     * 新 run 开始：清**本轮**计数（预检已消费完归档信号，此后归档保持
     * 不动直到 endRun 覆写）。
     */
    fun beginRun() {
        runToolCalls = 0
        recentOutcomes.clear()
    }

    /** run 收尾：本轮信号按滑窗口径归档（下轮预检的输入）。 */
    fun endRun() {
        lastRunToolCalls = runToolCalls
        lastRunErrors = recentOutcomes.count { !it }
    }

    /** 发送前预检信号快照（上一轮归档）。 */
    fun preCheckSignals(): PreCheckSignals =
        PreCheckSignals(lastRunToolCalls = lastRunToolCalls, lastRunErrors = lastRunErrors)

    /** 运行中升级观察信号快照（本轮累计 + 滑窗失败数）。 */
    fun escalationSignals(): EscalationSignals =
        EscalationSignals(runToolCalls = runToolCalls, recentWindowErrors = recentOutcomes.count { !it })

    /** 会话边界（新会话 / 恢复历史 / 切换工作区）：全量清零。 */
    fun resetConversation() {
        runToolCalls = 0
        recentOutcomes.clear()
        lastRunToolCalls = 0
        lastRunErrors = 0
    }

    companion object {
        /** 与 [CodeAdaptiveThinkingSelector] 深水区观察器的滑窗口径一致。 */
        const val DEFAULT_WINDOW_SIZE = 3
    }
}
