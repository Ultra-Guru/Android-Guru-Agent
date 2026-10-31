package com.apex.agent.ui.screen.code

/**
 * # Queued Task Policy — 任务排队的收尾裁决（纯函数）
 *
 * 从 CodeViewModel 抽出的决策核心（God-file 预算 + 可单测双收益）：
 * 上一轮 run 收尾时，排队任务该走哪条路——
 *
 * - **AUTO_DISPATCH**：排队任务处于等待发送态（WAITING_AUTO）且上一轮
 *   干净完成（无错误、未中止）→ 自动发送（用户已获预览确认，无破坏性
 *   风险——只是继续用户明示的意图）；
 * - **NEEDS_CONFIRM**：排队任务已处于等待排队态（NEEDS_CONFIRM——此前的
 *   失败已把它转过来）**或**上一轮失败/被中止 → 不自动接力。已待确认的
 *   卡即使用户后来手动跑了别的任务且干净完成，也不自动接力——确认门
 *   一旦落下就只有用户点「开始排队」能开（用户需求原话：「需要用户点击
 *   开始排队」）；
 * - **NONE**：没有排队任务（常态）。
 *
 * VM 侧接线见 runEngine 收尾与 startQueuedTask（人控入口）。
 */
internal object QueuedTaskPolicy {

    /** 收尾裁决结果。 */
    enum class Outcome { AUTO_DISPATCH, NEEDS_CONFIRM, NONE }

    /**
     * 上一轮 run 收尾时裁决排队任务去向。
     *
     * @param completedCleanly true = 无错误且未被中止（LongTaskStatus.COMPLETED
     *   的同口径）；false = FAILED / ABORTED。
     * @param queued 当前排队任务（null = 无）。
     */
    fun resolveAfterRun(completedCleanly: Boolean, queued: QueuedTask?): Outcome {
        if (queued == null) return Outcome.NONE
        return when {
            // 已在待确认态：确认门不开，后续任何干净完成也不自动接力
            queued.state == QueuedTaskState.NEEDS_CONFIRM -> Outcome.NEEDS_CONFIRM
            completedCleanly -> Outcome.AUTO_DISPATCH
            else -> Outcome.NEEDS_CONFIRM
        }
    }

    /**
     * 收尾裁决 → 下一帧的排队卡状态（NEEDS_CONFIRM 时原地转态；自动路径
     * 由 VM 摘卡发送，不经过本函数的返回值）。
     */
    fun nextQueuedState(outcome: Outcome, queued: QueuedTask): QueuedTask? = when (outcome) {
        Outcome.NEEDS_CONFIRM -> queued.copy(state = QueuedTaskState.NEEDS_CONFIRM)
        // AUTO_DISPATCH / NONE 不改状态（发送路径由 VM 摘卡；null 语义不适用）
        else -> null
    }
}
