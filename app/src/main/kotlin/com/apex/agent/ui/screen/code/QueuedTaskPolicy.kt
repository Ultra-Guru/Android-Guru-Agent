package com.apex.agent.ui.screen.code

/**
 * # Queued Task Policy — 任务排队的收尾裁决（纯函数）
 *
 * 从 CodeViewModel 抽出的决策核心（God-file 预算 + 可单测双收益）：
 * 上一轮 run 收尾时，排队任务该走哪条路——
 *
 * - **AUTO_DISPATCH**：上一任务干净完成（无错误、未中止）→ 自动发送
 *   排队任务（用户已获预览确认，无破坏性风险——只是继续用户明示的意图）；
 * - **NEEDS_CONFIRM**：上一任务失败或被中止 → **不自动接力**。失败现场
 *   需要用户先看错误条（可能一键重试原任务）；中止更可能是用户改了主意。
 *   排队卡转「等待排队」态，亮出「开始排队」按钮，点击才继续；
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
        return if (completedCleanly) Outcome.AUTO_DISPATCH else Outcome.NEEDS_CONFIRM
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
