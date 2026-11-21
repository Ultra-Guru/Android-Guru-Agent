package com.apex.agent.ui.screen.code

/**
 * # Render Ticker Policy — 渲染节拍自适应策略（SPE-07 P1-1）
 *
 * 旧实现固定 `delay(25)` 无条件轮询：低端机 40Hz 全量状态更新仍卡、
 * 高端机不够顺滑；等待首个 token / 工具执行的数十秒空闲期里持续空转
 * （手机端耗电主因之一）。
 *
 * 本策略是**纯状态机**（无时钟依赖——`workMs` 由调用方测量传入，
 * 假钟/脚本化序列可测）：
 *
 * - **忙三档** 16/25/33ms：设备负载代理 = 单拍工作量耗时（聚合冲刷 +
 *   胶囊快照重建 + 重组成本集中在这一拍）。单拍工作量吃掉当前节拍
 *   [OVERLOAD_RATIO] 以上 → 降档（给每拍留喘息）；连续
 *   [STABLE_STREAK_FOR_UPSHIFT] 拍轻负载 → 升档回 16ms（高端机顺滑）；
 * - **空闲降频**：连续 [IDLE_STREAK] 拍无任何内容上屏 → 50ms 巡逻档；
 *   空闲延续到 [DEEP_IDLE_STREAK] 拍 → 250ms 深巡逻（30s 空闲唤醒数
 *   相比固定 25ms 降低 ≥ 89%，验收见 RenderTickerPolicyTest）；
 * - **事件唤醒**：事件到达即 [wakeUp] 退出巡逻回到忙档——空闲延迟只
 *   影响「下一次醒来检查」，不影响事件响应（ticker 循环在
 *   `CodeRenderTickerOps.startRenderTicker` 用合流信号通道打断 delay）。
 *
 * 线程模型：仅主线程读写（ticker 与 collect 均在 viewModelScope 主
 * 调度器），无并发需求。
 */
internal class RenderTickerPolicy {

    /** 当前忙档节拍（16/25/33 三档 ladder，起始 25 = 旧默认）。 */
    var busyTickMs: Long = LADDER_MS[DEFAULT_LADDER_INDEX]
        private set

    /** 连续空闲拍计数（无任何内容上屏）。 */
    private var idleStreak = 0

    /** 连续轻负载拍计数（升档需连续稳定观察，防单拍噪声）。 */
    private var stableStreak = 0

    /** 当前档位在 ladder 的下标。 */
    private var ladderIndex: Int = DEFAULT_LADDER_INDEX

    /**
     * 事件到达信号：退出空闲巡逻回到忙档基线（不升档——升档需要
     * 连续稳定轻拍观察，单事件不构成负载证据）。
     */
    fun wakeUp() {
        idleStreak = 0
    }

    /**
     * 每拍记账。
     *
     * @param workMs 本拍工作量耗时（调用方测量：聚合冲刷 + 快照重建一拍）
     * @param hadWork 本拍是否有任何内容上屏（false = 空闲拍）
     */
    fun recordTick(workMs: Long, hadWork: Boolean) {
        if (!hadWork) {
            idleStreak++
            stableStreak = 0
            return
        }
        idleStreak = 0
        stableStreak++
        if (workMs > busyTickMs * OVERLOAD_RATIO) {
            // 单拍工作量已吃掉当前节拍的大半 → 降档（下一拍留出喘息，
            // 避免越拍越挤的恶性排队）
            shiftLadder(+1)
        } else if (stableStreak >= STABLE_STREAK_FOR_UPSHIFT && workMs < busyTickMs * LIGHT_RATIO) {
            // 连续轻拍 → 升档（更快上屏，高端机顺滑）
            shiftLadder(-1)
        }
    }

    /** 下一拍延迟：空闲连击越阈值 → 巡逻档（事件唤醒兜底首 token ≤ 1 拍）。 */
    fun nextDelayMs(): Long = when {
        idleStreak >= DEEP_IDLE_STREAK -> DEEP_PATROL_TICK_MS
        idleStreak >= IDLE_STREAK -> PATROL_TICK_MS
        else -> busyTickMs
    }

    /** run 边界复位（新 run 从 25ms 基线起步，不留上一轮的降档惯性）。 */
    fun reset() {
        ladderIndex = DEFAULT_LADDER_INDEX
        busyTickMs = LADDER_MS[DEFAULT_LADDER_INDEX]
        idleStreak = 0
        stableStreak = 0
    }

    private fun shiftLadder(delta: Int) {
        val next = (ladderIndex + delta).coerceIn(0, LADDER_MS.lastIndex)
        if (next == ladderIndex) return
        ladderIndex = next
        busyTickMs = LADDER_MS[next]
        stableStreak = 0
    }

    companion object {
        /** 忙三档（Pipeline 2.0 目标带 16~33ms）。 */
        val LADDER_MS = longArrayOf(16L, 25L, 33L)

        /** 起始档下标（25ms = 旧固定档，行为兼容起点）。 */
        const val DEFAULT_LADDER_INDEX = 1

        /** 巡逻档（浅：连续 3 空闲拍后）。 */
        const val PATROL_TICK_MS = 50L

        /** 深巡逻档（空闲延续到 15 拍后）。 */
        const val DEEP_PATROL_TICK_MS = 250L

        /** 浅巡逻触发空闲连击数。 */
        const val IDLE_STREAK = 3

        /** 深巡逻触发空闲连击数（50ms 档再空 12 拍 ≈ 0.75s 无人打扰）。 */
        const val DEEP_IDLE_STREAK = 15

        /** 降档负载比：单拍工作量 > 当前节拍 × 此值 → 降档。 */
        internal const val OVERLOAD_RATIO = 0.67f

        /** 升档轻载比：单拍工作量 < 当前节拍 × 此值才算「轻拍」。 */
        internal const val LIGHT_RATIO = 0.25f

        /** 升档所需连续轻拍数（~200ms 稳定期，防单拍噪声）。 */
        const val STABLE_STREAK_FOR_UPSHIFT = 8
    }
}
