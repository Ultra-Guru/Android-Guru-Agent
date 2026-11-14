package com.apex.agent.core.engine.goal

import com.apex.agent.core.engine.AutonomyLevel
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * # Goal 模式协调器（v3；v4 增加阶段状态机）
 *
 * 持有当前活动目标的全局状态（单例——同一时刻一个目标），供两侧消费：
 * - **引擎侧**（[com.apex.agent.core.engine.ApexAgentEngine] 每轮纯文本收尾钩子）：
 *   [onAgentTurn] 调快速模型验收，返回 [GoalDecision]（Continue=注入差距续跑 /
 *   Stop=照常收尾）；
 * - **UI 侧**（CodeViewModel / 设置页）：[startGoal] / [stopGoal] / [state]
 *   驱动目标设定对话框与进度卡。
 *
 * v4 阶段状态机（[GoalPhase]）：在既有生命周期钩子上同步迁移——
 * startGoal（CLARIFYING / SPEC_READY）→ [markExecuting]（引擎 run 启动，
 * ApexAgentEngine GOAL 分派处调用）→ onAgentTurn 入口 VERIFYING →
 * Continue 回 EXECUTING / Stop 落 DONE（达成）或 BLOCKED（未达成停止）；
 * 用户 [stopGoal] 落 BLOCKED（achieved=true 时 DONE）。
 *
 * 设计纪律（AGENTS.md「薄包装不重写」）：协调器不持有引擎引用、不改消息
 * 时间线——续跑注入由引擎按惯例（同 HUMAN_ASSIST/REFLECTION 拦截）执行；
 * 状态互斥用 [Mutex]（验收挂起期间 UI 可能 stopGoal）。
 *
 * 防御式：[onAgentTurn] 内部吞掉一切非取消异常折叠为 Stop（绝不阻断主循环）；
 * 轮次耗尽时目标转 STOPPED（UI 提示「继续」可重新 startGoal 续跑）。
 */
class GoalModeCoordinator(
    private val verifier: GoalVerifier,
    /** 验收开关（设置层实时快照：关闭后信任主模型自评，不调快速模型）。 */
    private val verifyEnabled: () -> Boolean = { true },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** 引擎钩子回调互斥：验收挂起期间不允许 start/stop 并发改状态。 */
    private val mutex = Mutex()

    /**
     * #351②：start/stop 与 onAgentTurn 写回的互斥 —— 旧实现 stopGoal 无锁直写
     * vs onAgentTurn 持锁但写回用**验证前快照**：用户在 fast 模型验收期间
     * stopGoal（→STOPPED）后，onAgentTurn 完成时整体覆写 state，目标被
     * 复活为 ACTIVE，用户的停止被静默撤销。改为所有状态写入经同一 JVM
     * 锁 + 写回前「代际比对」（仅当仍是快照那一代才覆写）。不能复用
     * [mutex]：start/stop 是非挂起函数，不能挂起等锁。
     */
    private val stateLock = Any()

    private val _state = MutableStateFlow<GoalRuntimeState?>(null)
    val state: StateFlow<GoalRuntimeState?> = _state.asStateFlow()

    val isActive: Boolean get() = _state.value?.status == GoalStatus.ACTIVE

    /** 当前活动目标（无 / 非 ACTIVE 返回 null）。 */
    fun activeSpec(): GoalSpec? = _state.value?.takeIf { it.status == GoalStatus.ACTIVE }?.spec

    /**
     * 设定新目标（覆盖旧目标——同一时刻一个目标，符合「单任务使命」语义）。
     * v4：按 spec 的自主级别快照决定初始阶段——完全自主 + 前置澄清开 →
     * CLARIFYING（首批 run 启动前 agent 需一次性问完），否则 SPEC_READY。
     */
    fun startGoal(spec: GoalSpec) = synchronized(stateLock) {
        val initialPhase = if (
            spec.preClarify &&
            AutonomyLevel.fromName(spec.autonomyLevel) == AutonomyLevel.FULLY
        ) {
            GoalPhase.CLARIFYING
        } else {
            GoalPhase.SPEC_READY
        }
        _state.value = GoalRuntimeState(
            spec = spec,
            status = GoalStatus.ACTIVE,
            roundsDone = 0,
            phase = initialPhase,
        )
        AppLogger.instance.info(
            LogCategory.ENGINE, TAG,
            "goal started: '${spec.statement.take(80)}' rounds=${spec.maxRounds} phase=$initialPhase"
        )
    }

    /**
     * 停止目标（用户手动 / 新目标覆盖前 / 会话清空）。
     * @param achieved 显式达成标记（默认按未达成 STOPPED 收尾）。
     * v4：achieved=true → DONE，否则 BLOCKED（展示层阶段）。
     */
    fun stopGoal(achieved: Boolean = false) = synchronized(stateLock) {
        val cur = _state.value ?: return
        if (cur.status != GoalStatus.ACTIVE) return
        _state.value = cur.copy(
            status = if (achieved) GoalStatus.ACHIEVED else GoalStatus.STOPPED,
            phase = if (achieved) GoalPhase.DONE else GoalPhase.BLOCKED,
        )
        AppLogger.instance.info(LogCategory.ENGINE, TAG, "goal stopped (achieved=$achieved)")
    }

    /**
     * v4 阶段迁移：引擎 run 启动（ApexAgentEngine GOAL 分派处，每条用户消息
     * 一次）→ EXECUTING。非 ACTIVE 零行为（迟到调用不复活目标）；终态
     * （DONE/BLOCKED）不回退。CLARIFYING 也在此时切 EXECUTING——前置澄清
     * 的批量提问发生在首条 run 内（pendingQuestion 卡承载），阶段展示由
     * UI 按提问挂起态叠加「前置澄清」徽标。
     */
    fun markExecuting() = synchronized(stateLock) {
        val cur = _state.value ?: return
        if (cur.status != GoalStatus.ACTIVE) return
        if (cur.phase == GoalPhase.EXECUTING) return
        _state.value = cur.copy(phase = GoalPhase.EXECUTING)
    }

    /**
     * 引擎每轮纯文本收尾钩子（GOAL 模式且目标 ACTIVE 时被调用）。
     *
     * 返回 null = 无活动目标 / 验收关闭（引擎默认收尾）；非 null = 引擎按
     * [GoalDecision] 执行（Continue → System 注入 + continue；Stop → 收尾）。
     * v4：入口置 VERIFYING（代际链基座换为 verifying 快照，后续写回以其为
     * 基准，外部 stop 仍然胜出）。
     */
    suspend fun onAgentTurn(workReport: String): GoalDecision? = mutex.withLock {
        // #351②：快照读取同锁 —— 与 start/stop 的写入互斥。
        val snapshot = synchronized(stateLock) { _state.value } ?: return@withLock null
        if (snapshot.status != GoalStatus.ACTIVE) return@withLock null
        val spec = snapshot.spec
        val roundsLeft = spec.maxRounds - snapshot.roundsDone
        if (roundsLeft <= 0) {
            commitGeneration(
                snapshot,
                snapshot.copy(status = GoalStatus.STOPPED, phase = GoalPhase.BLOCKED)
            )
            return@withLock GoalDecision.Stop(
                userNotice = "\n[goal] 已达最大验收轮次（${spec.maxRounds}），目标停止——可点「继续」重启目标续跑。",
                achieved = false,
            )
        }

        // v4：阶段 → 验收中；写回基座切换为 verifying（若此处被外部 stop
        // 掉则后续结果写回同样不落，用户停止语义与 #351② 一致）。
        val verifying = snapshot.copy(phase = GoalPhase.VERIFYING)
        commitGeneration(snapshot, verifying)

        val result = try {
            if (verifyEnabled()) {
                verifier.verify(spec, workReport)
            } else {
                // 验收关闭：主模型自评「完成报告」关键词放行（弱判定，兜底用）。
                val selfClaimed = workReport.contains("完成报告") || workReport.contains("verified", true)
                GoalCheckResult(selfClaimed, "验收器关闭，按主模型自评处理")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // 防御式：验收器异常绝不拖垮主循环——unknown 放行。
            GoalCheckResult.unknown("${e::class.simpleName}: ${e.message?.take(120)}")
        }

        val roundsDone = verifying.roundsDone + 1
        val notice = GoalPrompts.userNotice(roundsDone, spec.maxRounds, result)
        if (result.achieved) {
            commitGeneration(
                verifying,
                verifying.copy(
                    status = GoalStatus.ACHIEVED,
                    roundsDone = roundsDone,
                    lastCheck = result,
                    lastCheckAt = clock(),
                    phase = GoalPhase.DONE,
                )
            )
            GoalDecision.Stop(userNotice = notice + "\n[goal] 目标达成 🎉", achieved = true)
        } else if (roundsDone >= spec.maxRounds) {
            commitGeneration(
                verifying,
                verifying.copy(
                    status = GoalStatus.STOPPED,
                    roundsDone = roundsDone,
                    lastCheck = result,
                    lastCheckAt = clock(),
                    phase = GoalPhase.BLOCKED,
                )
            )
            GoalDecision.Stop(
                userNotice = notice + "\n[goal] 轮次耗尽，目标停止——可调整条件后重启。",
                achieved = false,
            )
        } else {
            commitGeneration(
                verifying,
                verifying.copy(
                    roundsDone = roundsDone,
                    lastCheck = result,
                    lastCheckAt = clock(),
                    phase = GoalPhase.EXECUTING,
                )
            )
            GoalDecision.Continue(
                continuationNote = GoalPrompts.continuationNote(result, spec.maxRounds - roundsDone),
                userNotice = notice,
            )
        }
    }

    /**
     * #351② 代际比对写回：仅当当前 state 仍等于快照（验收期间无人
     * start/stop）才覆写 —— 用户在验收挂起期间 stopGoal（→STOPPED）后，
     * 验证前快照不再复活目标；新 startGoal 的全新状态同样不被旧轮覆写。
     */
    private fun commitGeneration(snapshot: GoalRuntimeState, mutated: GoalRuntimeState) {
        synchronized(stateLock) {
            if (_state.value == snapshot) _state.value = mutated
        }
    }

    private companion object {
        const val TAG = "GoalModeCoordinator"
    }
}
