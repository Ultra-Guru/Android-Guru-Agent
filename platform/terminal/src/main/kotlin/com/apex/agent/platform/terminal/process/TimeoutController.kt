package com.apex.agent.platform.terminal.process

import com.apex.agent.platform.terminal.io.InputOwner
import com.apex.agent.platform.terminal.io.UnixSignal
import com.apex.agent.platform.terminal.io.InputManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Timeout Lifecycle (Spec PR #51 §4).
 *
 * Instead of timer-expired → immediate SIGKILL, gives graceful shutdown:
 *
 *   RUNNING
 *      │ timeoutMs elapsed
 *      ▼
 *   STOPPING  ── SIGTERM ──▶ grace period (default 5s)
 *      │
 *      │ grace expired, still alive
 *      ▼
 *   SIGKILL ──▶ TIMEOUT
 *
 * T81 (D-2)：本控制器是 job 超时的统一入口（startTimeout 此前生产零调用，
 * JobManager 私自用裸 SIGKILL 杀全组 —— 已改回统一走这里）。
 * timers 为并发安全结构；新增 [cancelSession]（按 session 收敛，修复
 * close 任意 session 时 cancelAll 全局误杀其他 session 定时器的缺陷）。
 */
class TimeoutController(
    private val inputManager: InputManager,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val gracePeriodMs: Long = 5000L
) {
    private data class TimerEntry(val sessionId: Long, val job: Job)

    // T81：普通 mutableMapOf 无锁 → ConcurrentHashMap（timers 曾被多线程读写）。
    private val timers = java.util.concurrent.ConcurrentHashMap<Long, TimerEntry>()  // jobId → timer

    /**
     * #947 — 已发起超时关停（SIGTERM 已发）的 job 集合。
     *
     * JobManager 的提示符启发式合成退出（WaitingInput(jobId=null) → RUNNING
     * 前台 job → ProcessExited(NORMAL)）与超时通道的 ProcessExited(TIMEOUT)
     * 存在竞态：TERM 杀掉命令后 shell 重绘提示符，启发式若先到会把 job 推成
     * EXITED 并（经 transition 撤销定时器）吞掉超时语义——CI 高负载下
     * T81JobTimeoutConvergenceTest 即由此翻红。TERM 发出即登记；定时器被
     * 撤销/替换时清除（此时 job 要么已终态、要么换了新定时器，语义归新主）。
     */
    private val shutdownInitiated = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    /** #947：该 job 的超时关停序列是否已发起（TERM 已发/在途）。 */
    fun hasShutdownInitiated(jobId: Long): Boolean = shutdownInitiated.contains(jobId)

    /**
     * Start a timeout timer for a job. On expiry: SIGTERM → wait grace → SIGKILL.
     * timer 协程被取消时（job 提前退出/正常完成）不再发任何信号。
     *
     * @param sessionId target session
     * @param jobId target job
     * @param timeoutMs total time before initiating shutdown
     * @param onTimeout callback when job is finally killed (state → TIMEOUT)
     */
    fun startTimeout(sessionId: Long, jobId: Long, timeoutMs: Long, onTimeout: () -> Unit) {
        cancelTimeout(jobId)  // replace any existing timer
        if (timeoutMs <= 0) return
        // T94：闭包经 AtomicReference 拿到自己的 Job —— finally 里条件移除。
        // 原实现无条件 timers.remove(jobId)：同 jobId 重挂定时器时
        // （start→cancel→start 序列），旧 timer 的 finally 异步执行可能误删
        // 【新】条目，新定时器脱管、cancelTimeout 失效（SIGTERM/SIGKILL 在
        // job 完成后仍发出）。
        val selfRef = java.util.concurrent.atomic.AtomicReference<Job?>()
        val job = scope.launch {
            try {
                delay(timeoutMs)
                // #947：TERM 发出前先登记关停已发起 —— 启发式通道从这一刻起
                // 让位（TERM 在途时 shell 重绘的提示符不是「命令正常完成」）。
                shutdownInitiated.add(jobId)
                // Phase 1: graceful SIGTERM（发给进程组 —— native kill(-PGID)）
                inputManager.sendSignal(sessionId, InputOwner.SYSTEM, UnixSignal.SIGTERM, jobId)
                // Phase 2: grace period（timer 在此期间被取消 = job 已自行退出/被外部终止）
                delay(gracePeriodMs)
                // Phase 3: force SIGKILL
                inputManager.sendSignal(sessionId, InputOwner.SYSTEM, UnixSignal.SIGKILL, jobId)
                onTimeout()
            } finally {
                // 仅当 map 里仍是本 timer 时才移除（重挂后旧 timer 的清理不误删
                // 新条目）。get()=null 防御：极小概率协程在 set 之前就跑完
                // finally —— 此刻条目尚未登记，无操作（登记后成为无害残留，
                // cancelTimeout/重挂可清）。
                selfRef.get()?.let { timers.remove(jobId, TimerEntry(sessionId, it)) }
            }
        }
        selfRef.set(job)
        timers[jobId] = TimerEntry(sessionId, job)
    }

    /** Cancel a timeout timer (e.g. job exited normally before timeout). */
    fun cancelTimeout(jobId: Long) {
        timers.remove(jobId)?.job?.cancel()
        // #947：撤销即清关停标记 —— job 已终态（状态自守）或换新定时器（语义归新主）。
        shutdownInitiated.remove(jobId)
    }

    /**
     * T81：取消一个 session 的全部定时器（session close / shutdown 路径）。
     * 修复：原 [cancelAll] 无 session 过滤，TerminalRuntimeImpl.close 关任意
     * session 都会误杀所有其他 session 的超时定时器。
     */
    fun cancelSession(sessionId: Long) {
        val iter = timers.entries.iterator()
        for (e in iter) {
            if (e.value.sessionId == sessionId) {
                e.value.job.cancel()
                iter.remove()
                shutdownInitiated.remove(e.key)
            }
        }
    }

    /** Cancel all timers (runtime shutdown only). */
    fun cancelAll() {
        timers.values.forEach { it.job.cancel() }
        timers.clear()
        shutdownInitiated.clear()
    }

    /** T81：活跃定时器数（诊断/测试）。 */
    fun activeTimers(): Int = timers.size
}

/**
 * Job Cancellation (Spec PR #51 §5).
 *
 * Agent calls terminal.cancel(jobId) — internally does graceful stop → wait → force kill.
 * Agent doesn't need to manually signal(SIGTERM) then signal(SIGKILL).
 *
 * T81 (D-2)：取消序列如实上报 —— 取消同时撤销该 job 的超时定时器（互不
 * 叠加发信号）。
 */
class JobCancellationController(
    private val inputManager: InputManager,
    private val timeoutController: TimeoutController,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val gracePeriodMs: Long = 5000L,
    /** Called right after SIGTERM is delivered, so the job can be marked terminal. */
    private val onCancelled: (sessionId: Long, jobId: Long) -> Unit = { _, _ -> },
) {
    /**
     * Cancel a job: SIGTERM → grace → SIGKILL.
     *
     * @param sessionId target session
     * @param jobId target job
     * @return true if cancellation initiated
     */
    fun cancel(sessionId: Long, jobId: Long): Boolean {
        // T81 (D-2)：先撤销超时定时器 —— 避免 timeout 与 cancel 对同一 job
        // 叠加发信号（SIGTERM×2 + SIGKILL×2 竞态）。
        timeoutController.cancelTimeout(jobId)
        scope.launch {
            // 1. Graceful stop（发给进程组 —— native kill(-PGID)）
            inputManager.sendSignal(sessionId, InputOwner.SYSTEM, UnixSignal.SIGTERM, jobId)
            // Mark the job as cancelled immediately (agent decided to stop it).
            onCancelled(sessionId, jobId)
            // 2. Grace period（期间进程自行退出 = 正常路径）
            delay(gracePeriodMs)
            // 3. Force kill（兑底 —— 进程已死则无副作用）
            inputManager.sendSignal(sessionId, InputOwner.SYSTEM, UnixSignal.SIGKILL, jobId)
        }
        return true
    }
}
