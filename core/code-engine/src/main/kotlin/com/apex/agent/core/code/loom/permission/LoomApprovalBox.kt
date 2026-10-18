package com.apex.agent.core.code.loom.permission

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.withTimeoutOrNull

/**
 * # Loom Approval Box — 权限问答窗口（单发待答信箱）
 *
 * 权限门 ask 决策的人机交接点：循环协程挂起等待（带超时窗口），
 * UI 线程经引擎门面 submitUserInput 投递回答。
 *
 * ## 语义
 *
 * - **单发**：同一时刻至多一个待答问题（新问题必须等旧问题收口——
 *   循环是串行的，天然满足）；
 * - **窗口**：[await] 带超时——超时按「用户未决」收口（调用方按拒绝
 *   语义处理，与宿主 UserInputExpired 事件对齐）；
 * - **抢占取消**：[cancelPending] 让 abort 即时收场（不等窗口耗尽）；
 * - **迟到回答**：问题已收口后 submit 返回 false（UI 显式提示未送达）。
 *
 * 线程契约：await 在执行协程；submit/cancelPending 可在任意线程。
 */
class LoomApprovalBox {

    /** 待答问题（prompt 展示用；null = 无挂起）。 */
    private data class Pending(
        val prompt: String,
        val deferred: kotlinx.coroutines.CompletableDeferred<String?>
    )

    private val pending = AtomicReference<Pending?>(null)

    /** 当前挂起的问题文案（UI 展示用；无挂起为 null）。 */
    fun pendingPrompt(): String? = pending.get()?.prompt

    /**
     * 挂起等待一个回答。
     *
     * @return 回答文本；null = 超时或被取消（按拒绝语义收口）。
     */
    suspend fun await(prompt: String, timeoutMs: Long): String? {
        val deferred = kotlinx.coroutines.CompletableDeferred<String?>()
        val slot = Pending(prompt = prompt, deferred = deferred)
        if (!pending.compareAndSet(null, slot)) {
            // 上一问未收口（理论不可达——循环串行；防御：返回未决）
            return null
        }
        return try {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            pending.compareAndSet(slot, null)
        }
    }

    /**
     * 投递回答。
     *
     * @return true = 已送达挂起问题；false = 无挂起或已收口（迟到）。
     */
    fun submit(answer: String): Boolean {
        val slot = pending.get() ?: return false
        return slot.deferred.complete(answer.trim())
    }

    /**
     * 抢占收口（abort / 引擎复位路径）——挂起问题按未决收束。
     *
     * @return true = 收掉了一个挂起问题。
     */
    fun cancelPending(): Boolean {
        val slot = pending.getAndSet(null) ?: return false
        return slot.deferred.complete(null)
    }

    /** 是否有挂起问题（诊断用）。 */
    fun isPending(): Boolean = pending.get() != null
}
