package com.apex.agent.core.engine.plan

import com.apex.agent.core.engine.ApexAgentEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

/**
 * Plan/Spec 确认与 ask_user 共用的等待上限（自引擎 private companion 迁出，
 * 单一来源：引擎、[awaitPlanConfirmationDecision] 与 Spec/user-input 等待
 * 全部引用本常量）。
 */
internal const val PLAN_CONFIRMATION_TIMEOUT_MS: Long = 5L * 60 * 1000 // 5 minutes

/**
 * #169 等待用户的计划确认决策（Boolean → [PlanDecision] 升级版）。
 *
 * 自 [ApexAgentEngine] 迁出的纯等待逻辑（God-file 1200 行预算拆分，模式同
 * EngineAskUserFlow.kt）：注册 fresh deferred → 挂起等待 UI 提交 → finally
 * 清引用防泄漏。超时**不在此折叠**：TimeoutCancellationException 沿调用链
 * 上抛至引擎 `execute` 的统一 TCE 分支（保持 4-a 之前的外层超时语义 ——
 * 区分"本层确认超时"与"外层任务取消穿透"）。
 *
 * 引擎侧配套（见 ApexAgentEngine）：
 *  - `planConfirmationDeferred: CompletableDeferred<PlanDecision>?`（internal，
 *    供本扩展注册/清理）；
 *  - `submitPlanConfirmation(confirmed)` 旧签名 = `PlanDecision.legacy`；
 *  - `submitPlanConfirmation(confirmed, enabledSteps, order)` 三参重载承接
 *    UI 人控三元组。
 */
internal suspend fun ApexAgentEngine.awaitPlanConfirmationDecision(
    onRegistered: (suspend () -> Unit)? = null
): PlanDecision {
    val deferred = CompletableDeferred<PlanDecision>()
    // #340：注册先行 —— deferred 先于 PlanAwaitingConfirmation 的 emit
    //（onRegistered）创建。旧顺序「先 emit 再创建 deferred」在 TaskRuntime
    // 镜像收集（UNLIMITED tap 使 emit 立即返回）下无 happens-before 保证，
    // 迟到的 submit 在 deferred 创建前到达 → complete 落空 → 引擎挂 5 分钟
    //（PR #330 实证竞态，测试侧轮询补丁由此退役）。
    planConfirmationDeferred.set(deferred)
    return try {
        onRegistered?.invoke()
        withTimeout(PLAN_CONFIRMATION_TIMEOUT_MS) { deferred.await() }
    } finally {
        // #340：仅清理自己那一代（不覆写 abort 后的新注册）。
        planConfirmationDeferred.compareAndSet(deferred, null)
    }
}
