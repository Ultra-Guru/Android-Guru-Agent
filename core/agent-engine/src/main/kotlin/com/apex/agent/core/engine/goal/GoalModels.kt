package com.apex.agent.core.engine.goal

import kotlinx.serialization.Serializable

/**
 * # Goal 模式数据模型（v3；v4 扩展目标驱动状态机）
 *
 * 目标 = 陈述（做什么）+ **可验证的完成条件**（做成什么样才算完，如
 * 「所有测试通过」「页面在 300ms 内返回 200」）。引擎每轮自然收尾时由
 * 快速模型（FAST 角色）对照条件验收，未达标自动续跑。
 *
 * 生命周期（[GoalStatus]）：`ACTIVE →（验收通过）→ ACHIEVED`
 *                `└→（轮次耗尽 / 用户停止 / 验收器异常放弃）→ STOPPED`
 * 未达成的目标保持 ACTIVE（跨会话续跑由 UI 层「继续」按钮重放引擎）。
 *
 * v4 阶段状态机（[GoalPhase]，展示层）：目标驱动循环对用户可见化为
 * `CLARIFYING → SPEC_READY → EXECUTING ⇄ VERIFYING → DONE / BLOCKED`，
 * 由 [GoalModeCoordinator] 在既有生命周期钩子上同步迁移（见其 KDoc）。
 */
@Serializable
data class GoalSpec(
    /** 目标陈述（做什么），≤400 字符（UI 层截断）。 */
    val statement: String,
    /** 可验证的完成条件（验收判据；多行时每行一条，验收器逐条判定），≤2000 字符。 */
    val acceptanceCriteria: String,
    /** 最大验收轮次（每轮 = 一次自然收尾 + 一次快速模型验收）。 */
    val maxRounds: Int = DEFAULT_MAX_ROUNDS,
    /** 创建时间（epoch ms）。 */
    val createdAt: Long = 0L,
    /** 目标创建时的首个用户提示（重放/续跑用），可为空（目标即提示）。 */
    val initialPrompt: String = "",
    // ═══ v4 GoalSpec 扩展字段（全部可选，默认空 = 既有行为零变化）═══
    /** 约束（必须遵守的限制，如「不改公共 API」），空 = 未填写。 */
    val constraints: String = "",
    /** 非目标（明确不做的事，防止范围蔓延），空 = 未填写。 */
    val nonGoals: String = "",
    /** 成功指标（达成后可观测的量化指标，与验收条件互补），空 = 未填写。 */
    val successMetrics: String = "",
    /** 已知风险（启动时已识别的风险与应对），空 = 未填写。 */
    val knownRisks: String = "",
    /** 自主级别快照（AutonomyLevel.persistenceName；空 = 未记录，兼容旧目标）。 */
    val autonomyLevel: String = "",
    /** 完全自主「开始前集中澄清」快照（仅 FULLY 有意义，默认关）。 */
    val preClarify: Boolean = false,
) {
    companion object {
        const val DEFAULT_MAX_ROUNDS = 8

        /** 单轮工作汇报送验前截断（防长回复打爆快速模型上下文）。 */
        const val WORK_REPORT_BUDGET = 6000

        /** 逐条验收清单上限（acceptanceCriteria 非空行数截断，防几十行打爆协议）。 */
        const val CRITERIA_LINES_LIMIT = 12
    }
}

/** 目标生命周期状态。 */
enum class GoalStatus {
    ACTIVE,
    ACHIEVED,
    STOPPED,
}

/**
 * v4 目标阶段（展示层状态机；生命周期仍由 [GoalStatus] 承载，两轴正交）：
 *
 * `CLARIFYING → SPEC_READY → EXECUTING → VERIFYING →（回 EXECUTING）`
 * `→ DONE（ACHIEVED）/ BLOCKED（STOPPED 未达成）`
 *
 * 迁移点（全部在 GoalModeCoordinator）：startGoal（按 spec 的自主级别与
 * 前置澄清决定 CLARIFYING / SPEC_READY）→ 引擎 run 启动 markExecuting →
 * 每轮收尾钩子 onAgentTurn 入口置 VERIFYING → Continue 回 EXECUTING /
 * Stop 落 DONE / BLOCKED；用户 stopGoal 落 BLOCKED。
 */
enum class GoalPhase {
    /** 前置澄清中（完全自主 + 集中澄清开，目标已设定、首条 run 启动前）。 */
    CLARIFYING,

    /** 规格就绪，待发送首条消息开始推进。 */
    SPEC_READY,

    /** 引擎执行中（本轮 run 进行中；含前置澄清后的实际工作段）。 */
    EXECUTING,

    /** 快速模型验收中（每轮收尾钩子运行期间）。 */
    VERIFYING,

    /** 已达成（ACHIEVED 终态）。 */
    DONE,

    /** 已阻塞（STOPPED 未达成：轮次耗尽 / 用户停止 / 高风险停手）。 */
    BLOCKED,
}

/**
 * 单轮验收报告：快速模型对「当前工作 vs 完成条件」的判定。
 *
 * @param achieved 是否达成。
 * @param reason 判定理由（达成 = 满足了什么；未达成 = 差什么），≤500 字符。
 * @param evidence 判定依据引用（工具输出/文件路径等），≤500 字符，可空。
 * @param criteriaResults v4 逐条验收结果（与 acceptanceCriteria 非空行序对齐，
 *   每项 true = 该条通过）；null = 验收器未按行输出（旧协议 / 解析失败），
 *   UI 回退到整体 ✓/✗ 展示。
 */
data class GoalCheckResult(
    val achieved: Boolean,
    val reason: String,
    val evidence: String? = null,
    val criteriaResults: List<Boolean>? = null,
) {
    companion object {
        /** 验收器不可用（快速模型连续失败）时的兜底判定：不拦进度，放行收尾。 */
        fun unknown(reason: String): GoalCheckResult =
            GoalCheckResult(achieved = true, reason = "验收器不可用，按达成放行：$reason")
    }
}

/**
 * 引擎每轮收到的验收决策（[GoalModeCoordinator.onAgentTurn] 的返回）。
 */
sealed interface GoalDecision {
    /** 未达标：注入差距说明继续跑（引擎 addMessage(System) + continue）。 */
    data class Continue(val continuationNote: String, val userNotice: String) : GoalDecision

    /**
     * 停止验收循环：达成 / 轮次耗尽 / 验收器放弃 / 目标被外部停止。
     * 引擎照常收尾（ResponseComplete），[userNotice] 以 ThinkingChunk 透出。
     */
    data class Stop(val userNotice: String, val achieved: Boolean) : GoalDecision
}

/** 目标运行时快照（UI 状态展示用，含轮次进度与最近一次验收结果）。 */
data class GoalRuntimeState(
    val spec: GoalSpec,
    val status: GoalStatus,
    val roundsDone: Int = 0,
    val lastCheck: GoalCheckResult? = null,
    val lastCheckAt: Long = 0L,
    /** v4 当前阶段（展示层状态机，见 [GoalPhase]；默认 SPEC_READY）。 */
    val phase: GoalPhase = GoalPhase.SPEC_READY,
)
