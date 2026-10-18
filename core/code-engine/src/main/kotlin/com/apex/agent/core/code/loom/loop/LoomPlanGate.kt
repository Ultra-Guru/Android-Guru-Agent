package com.apex.agent.core.code.loom.loop

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * # Loom Plan Gate — 布样人控门（单发待决）
 *
 * PLAN 模式首轮布样出来后挂起等待用户裁决（确认 / 驳回）：
 *
 * - [await]：带窗口挂起（超时按**驳回**收场并附超时说明——任务继续、
 *   模型重织布样；不整任务终止）；
 * - [submit]：UI 裁决投递（迟到返回 false）；
 * - [cancel]：abort 抢占——挂起处抛 [CancellationException] → 回合机
 *   织 Aborted 收场。
 */
class LoomPlanGate {

    private data class Pending(
        val deferred: CompletableDeferred<Boolean>
    )

    private val pending = AtomicReference<Pending?>(null)

    /** 是否挂起待决。 */
    fun isAwaiting(): Boolean = pending.get() != null

    /**
     * 挂起等待裁决。
     *
     * @param timeoutMs 窗口；超时按驳回收场（[Verdict] 携带超时说明）。
     */
    suspend fun await(timeoutMs: Long): Verdict {
        val slot = Pending(CompletableDeferred())
        if (!pending.compareAndSet(null, slot)) return Verdict(confirmed = false, note = null)
        return try {
            val confirmed = withTimeoutOrNull(timeoutMs) { slot.deferred.await() }
            confirmed?.let { Verdict(confirmed = it, note = null) }
                ?: Verdict(confirmed = false, note = "确认窗口超时（${timeoutMs / 1000} 秒）未响应")
        } finally {
            pending.compareAndSet(slot, null)
        }
    }

    /** 投递裁决。 */
    fun submit(confirmed: Boolean): Boolean {
        val slot = pending.get() ?: return false
        return slot.deferred.complete(confirmed)
    }

    /** abort 抢占：挂起处抛取消异常。 */
    fun cancel() {
        val slot = pending.getAndSet(null) ?: return
        slot.deferred.cancel(CancellationException("loom-abort"))
    }

    /** 裁决结果。 */
    data class Verdict(
        val confirmed: Boolean,
        /** 附加说明（超时说明 / 驳回反馈通道——织入裁决轮）。 */
        val note: String?
    )
}
