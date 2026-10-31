package com.apex.agent.core.engine

import com.apex.agent.core.engine.plan.PLAN_CONFIRMATION_TIMEOUT_MS
import com.apex.agent.core.engine.plan.PlanDecision
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * ═══ #340 — 确认通道两件套（userInput/plan）的治理与中止 ═══
 *
 * 自 [ApexAgentEngine] 迁出（God-file 预算 1200 行拆分；模式与
 * [EngineAskUserFlow] / EngineCompressionGate 相同：同包顶层扩展 +
 * internal 成员直调，调用点零改动）。
 *
 * ## 治理的三个并发窗口（均为「chat 模式卡死」的直接机制）
 *
 * 1. **注册晚于 emit**：旧实现 `emit(UserInputRequired/PlanAwaiting…)`
 *    在调用方、deferred 注册在 await* 内部。emit 经镜像
 *    tap（UNLIMITED）消费后立即返回，与注册之间无 happens-before——
 *    submit 在注册前到达 → `complete` 落空 → 引擎挂满 5 分钟超时。
 *    现在**注册先行**：deferred 赋值（与 abort 同锁）先于 emit
 *    （emit 移入 beforeWait 回调），窗口结构性消除。
 *
 * 2. **abort 的 complete-then-null 非原子**：旧 abort() 跨协程执行
 *    「`?.complete` → `= null`」两步，恰在其间引擎完成一次新注册时，
 *    新 deferred 被 null 覆盖——UI 后续提交全部落空，引擎挂满 5 分钟。
 *    现在注册与 [abortConfirmationChannels] 收进 [ApexAgentEngine.confirmationLock]
 *    （锁内无挂起点，不阻塞调度）。
 *
 * 3. **等待收尾的无条件清理**：finally 里 `deferred = null` 可能抹掉
 *    abort 之后新注册的通道——改为条件式（只清自己挂的那只）。
 *
 * plan 通道的等待（`awaitPlanConfirmationDecision`）在
 * plan/PlanExecutionSupport.kt 就地同步治理（同锁、同注册先行模式）。
 * （spec 通道已随 SPEC 档位删除 —— Agent 模式移除 PR 3/6。）
 */

/**
 * 挂起等待 UI 回传用户输入（AskUser 工具与权限门提问共用）。
 *
 * @param onExpired 超时回调（调用方发射 UserInputExpired 让 UI 关对话框）
 * @param beforeWait 注册完成之后、挂起等待之前的回调（#340 注册先行：
 *   UserInputRequired 的 emit 必须发生在 deferred 注册之后——见文件头①）
 * @return 用户答案（cancelUserInput/abort 路径为空串）；
 *   null = 等待超时 —— #214 语义：不再静默以空串继续，而是先关闭
 *   投递通道（迟到的 [ApexAgentEngine.submitUserInputIfAwaiting] 拿到 false），
 *   再触发 [onExpired]，返回 null 让调用方产生「用户输入超时」的显式结果。
 */
internal suspend fun ApexAgentEngine.awaitUserInput(
    onExpired: (suspend () -> Unit)? = null,
    beforeWait: (suspend () -> Unit)? = null
): String? {
    // #340 注册先行：与 abort 同锁互斥，先于 beforeWait（emit）。
    val deferred = synchronized(confirmationLock) {
        val d = CompletableDeferred<String>()
        userInputDeferred = d
        d
    }
    beforeWait?.invoke()
    return try {
        // 与 awaitPlanConfirmationDecision 保持一致：
        // 5 分钟超时，避免 AskUser 工具因用户遗忘而把引擎永久挂起。
        withTimeout(PLAN_CONFIRMATION_TIMEOUT_MS) { deferred.await() }
    } catch (e: TimeoutCancellationException) {
        AppLogger.instance.warn(
            LogCategory.ENGINE, "ApexAgentEngine",
            "AskUser 输入超时 (${PLAN_CONFIRMATION_TIMEOUT_MS / 1000}s)，按超时未决收场"
        )
        // 先关投递通道再通知：保证 UI 先看到对话框关闭，迟交才有失败提示。
        synchronized(confirmationLock) { userInputDeferred = null }
        onExpired?.invoke()
        null
    } finally {
        // 条件清理：只清本次等待挂的 deferred——不覆盖 abort 之后/并行等待
        // 注册的新通道（与 abort 同锁，防 #340 同源窗口）。
        synchronized(confirmationLock) {
            if (userInputDeferred === deferred) userInputDeferred = null
        }
    }
}

/**
 * abort() 的确认通道收尾：两对「complete-then-null」在
 * [ApexAgentEngine.confirmationLock] 内原子执行（见文件头②）。
 *
 * complete 语义：plan 以「用户拒绝」收场（legacy(false)），
 * userInput 以空答案唤醒（cancelUserInput 同语义）——等待方拿到确定值，
 * 引擎流沿 abort 路径自然收尾，不挂超时。
 */
internal fun ApexAgentEngine.abortConfirmationChannels() {
    synchronized(confirmationLock) {
        planConfirmationDeferred?.complete(PlanDecision.legacy(false))
        planConfirmationDeferred = null
        userInputDeferred?.complete("")
        userInputDeferred = null
        // ═══ #395 — 桥接层提问收口（弹窗取消卡死根因）═══
        // AskUserChoice / 权限门（CommandPermissionGate/RiskAwareToolGate）在
        // 工具 execute() 内部经 UserQuestionBridge.ask 挂起在「桥自己的
        // deferred」上 —— 上方三对收口覆盖不到。不补口则 abort 后引擎流
        // 吃满 5 分钟超时不收尾，TaskRuntime pause/cancel 连续打印
        // "stream finalize did not complete within 10s"（真机日志 5 连实证）。
        // 收口后 ask() 立即拿到 skipped 答案返回，工具批次结束后
        // while(isRunning) 边界自然收尾。
        userQuestionGateway?.abortActiveQuestion()
    }
}
