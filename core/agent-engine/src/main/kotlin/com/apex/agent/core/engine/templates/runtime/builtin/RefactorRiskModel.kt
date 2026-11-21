package com.apex.agent.core.engine.templates.runtime.builtin

/**
 * ═══ 内置模板行为 · 重构方案 —— 风险模型与步骤编排（PR 18 深化 II）═══
 *
 * 重构方案行为的第二层确定性引擎：[RefactorPlanPlanner] 管「诊断出什么
 * 问题、步骤骨架长什么样」，本模块管「步骤顺序安不安全、每步风险有多
 * 大、回滚怎么写、变更面怎么估」——把重构安全学从 LLM 的直觉里搬到
 * 代码税则里。
 *
 * 能力族（全部纯函数、全部确定性）：
 * 1. **步骤依赖图**（step DAG）：步骤种类间的硬依赖（保护网先行、
 *    提取先于拆分、收敛参数最后）建成有向无环图，拓扑排序给出
 *    唯一安全序；LLM 回包的步骤顺序与安全序不一致 = 可修复违规；
 * 2. **四维风险评估**（risk matrix）：每步按 复杂度增益/耦合面/
 *    变更面/回滚成本 四维打分（0-4），合成综合风险与建议批次
 *    （单步合入 or 拆小步 or 需评审）；
 * 3. **变更面估算**（change surface）：从度量事实估算每步触及的
 *    函数数/行数/文件数区间，输出「本步 PR 体量预告」；
 * 4. **回滚模板库**（rollback templates）：每类步骤的确定性回滚
 *    手法（revert 提交 / 特征开关回退 / 数据迁移补偿）；
 * 5. **验证矩阵**（verification matrix）：步骤种类 × 验证手段
 *    （特征测试/编译门禁/指标阈值/人工抽查）的穷举表；
 * 6. **安全序校验**（safety gate）：回包步骤清单的顺序合法性判定，
 *    违序给出「应排在第 N 步之前」的确定性修复建议。
 *
 * 防御式纪律：空步骤/循环依赖/未知种类全部有退化路径，绝不抛。
 */

/** 步骤依赖边（from 必须先于 to 执行）。 */
data class StepDependency(
    val from: RefactorStep.StepKind,
    val to: RefactorStep.StepKind,
    val reason: String
)

/** 步骤编排产物（拓扑序 + 依赖说明）。 */
data class StepOrdering(
    /** 安全执行序（拓扑排序；未知种类追加在尾部保持原相对序）。 */
    val safeOrder: List<RefactorStep.StepKind>,
    /** 依赖边清单（人读，喂提示词与报告）。 */
    val dependencies: List<StepDependency>,
    /** 是否存在无法满足的依赖（如缺少保护网却要求拆分）。 */
    val hasUnmetDependency: Boolean,
    /** 未满足依赖的说明。 */
    val unmetReasons: List<String>
) {
    companion object {
        /** 空编排（防御式兜底）。 */
        fun empty(): StepOrdering = StepOrdering(emptyList(), emptyList(), false, emptyList())
    }
}

/** 单步四维风险打分。 */
data class StepRiskMatrix(
    val stepKind: RefactorStep.StepKind,
    /** 复杂度增益（0-4：重构后认知负担下降幅度）。 */
    val complexityRelief: Int,
    /** 耦合面（0-4：触及的协作模块数）。 */
    val couplingBreadth: Int,
    /** 变更面（0-4：触及的代码量级）。 */
    val changeSurface: Int,
    /** 回滚成本（0-4：回滚难度）。 */
    val rollbackCost: Int,
    /** 综合风险分（0-100，越高越危险）。 */
    val compositeScore: Int,
    /** 建议批次。 */
    val batchAdvice: String
) {
    init {
        require(complexityRelief in 0..4 && couplingBreadth in 0..4) { "risk dims must be 0..4" }
        require(changeSurface in 0..4 && rollbackCost in 0..4) { "risk dims must be 0..4" }
        require(compositeScore in 0..100) { "compositeScore must be 0..100" }
    }

    /** 单行展示（喂提示词附录 / 报告表格）。 */
    fun toLine(): String =
        "${stepKind.label}：复杂度增益 $complexityRelief / 耦合面 $couplingBreadth / " +
            "变更面 $changeSurface / 回滚成本 $rollbackCost → 综合 $compositeScore（$batchAdvice）"
}

/** 变更面估算（每步的体量预告）。 */
data class ChangeSurfaceEstimate(
    val stepKind: RefactorStep.StepKind,
    /** 预计触及函数数（下限/上限）。 */
    val functionsRange: IntRange,
    /** 预计变更行数（下限/上限）。 */
    val linesRange: IntRange,
    /** 建议提交粒度。 */
    val commitGranularity: String
)

/** 顺序违规（回包步骤序与安全序不一致）。 */
data class StepOrderViolation(
    val stepTitle: String,
    val stepKind: RefactorStep.StepKind,
    /** 当前位于第几步（1 起）。 */
    val currentPosition: Int,
    /** 应不晚于第几步（1 起）。 */
    val requiredBeforePosition: Int,
    val reason: String
)

/**
 * 风险模型主体（纯函数集合）。
 */
object RefactorRiskModel {

    // ═══ 1) 步骤依赖图 ═══

    /**
     * 硬依赖边（安全序的骨架）：
     * - 保护网 → 一切实现改动（没有安全网不许动代码）；
     * - 提取重复 → 拆分函数（先合并重复再拆，避免拆出两份重复）；
     * - 拆分函数 → 参数收敛（拆完才知道参数怎么收敛）；
     * - 降低嵌套 → 参数收敛（嵌套压平后参数语义才稳定）。
     */
    val HARD_DEPENDENCIES: List<StepDependency> = listOf(
        StepDependency(
            RefactorStep.StepKind.SAFETY_NET, RefactorStep.StepKind.EXTRACT_DUPLICATE,
            "保护网先行：特征测试锁定现有行为后才能提取重复"
        ),
        StepDependency(
            RefactorStep.StepKind.SAFETY_NET, RefactorStep.StepKind.SPLIT_FUNCTION,
            "保护网先行：拆分函数前必须有用例锁定行为"
        ),
        StepDependency(
            RefactorStep.StepKind.SAFETY_NET, RefactorStep.StepKind.REDUCE_NESTING,
            "保护网先行：压平嵌套前必须有用例锁定分支行为"
        ),
        StepDependency(
            RefactorStep.StepKind.SAFETY_NET, RefactorStep.StepKind.PARAMS_OBJECT,
            "保护网先行：参数收敛前必须锁定调用点行为"
        ),
        StepDependency(
            RefactorStep.StepKind.EXTRACT_DUPLICATE, RefactorStep.StepKind.SPLIT_FUNCTION,
            "提取先于拆分：先合并重复再拆分，避免拆出两份重复实现"
        ),
        StepDependency(
            RefactorStep.StepKind.SPLIT_FUNCTION, RefactorStep.StepKind.PARAMS_OBJECT,
            "拆分先于收敛：函数边界稳定后参数收敛才有意义"
        ),
        StepDependency(
            RefactorStep.StepKind.REDUCE_NESTING, RefactorStep.StepKind.PARAMS_OBJECT,
            "压平先于收敛：嵌套结构变化会改变参数语义"
        )
    )

    /**
     * 计算安全执行序（Kahn 拓扑排序，同层保持声明序）：
     * 输入为步骤清单（含 LLM 回包解析出的领域步骤），输出安全序与
     * 未满足依赖说明（如清单里根本没有保护网）。
     */
    fun computeOrdering(steps: List<RefactorStep>): StepOrdering {
        if (steps.isEmpty()) return StepOrdering.empty()
        val kinds = steps.map { it.kind }.distinct()
        // 只保留清单里实际出现的种类之间的依赖边
        val edges = HARD_DEPENDENCIES.filter { it.from in kinds && it.to in kinds }
        val unmet = mutableListOf<String>()
        // OR 语义：一个步骤只要有任一硬依赖前驱在清单中即视为满足；
        // 全部前驱都缺席才报未满足（如清单只有拆分函数却没有保护网/提取重复）。
        for (target in kinds) {
            val predecessors = HARD_DEPENDENCIES.filter { it.to == target }.map { it.from }
            if (predecessors.isEmpty()) continue
            if (predecessors.none { it in kinds }) {
                val representative = HARD_DEPENDENCIES.first { it.to == target }
                unmet += "${target.label} 需要前置步骤（${representative.reason}），但清单中缺少全部前置（" +
                    predecessors.joinToString(" 或 ") { it.label } + "）"
            }
        }
        // Kahn：入度表 + 队列（声明序决胜）
        val indegree = kinds.associateWith { 0 }.toMutableMap()
        val adjacency = kinds.associateWith { mutableListOf<RefactorStep.StepKind>() }
        for (edge in edges) {
            adjacency.getValue(edge.from) += edge.to
            indegree.merge(edge.to, 1, Int::plus)
        }
        val sorted = mutableListOf<RefactorStep.StepKind>()
        val queue = ArrayDeque(kinds.filter { indegradeSafe(indegree, it) == 0 })
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            sorted += current
            for (next in adjacency.getValue(current)) {
                val remaining = indegree.merge(next, -1, Int::plus) ?: 0
                if (remaining == 0) queue += next
            }
        }
        // 循环依赖兜底：剩余种类按声明序追加（不抛）
        if (sorted.size < kinds.size) {
            sorted += kinds.filter { it !in sorted }
        }
        return StepOrdering(
            safeOrder = sorted,
            dependencies = edges,
            hasUnmetDependency = unmet.isNotEmpty(),
            unmetReasons = unmet
        )
    }

    private fun indegradeSafe(map: Map<RefactorStep.StepKind, Int>, key: RefactorStep.StepKind): Int =
        map[key] ?: 0

    // ═══ 2) 四维风险评估 ═══

    /** 各步骤种类的出厂四维评分（复杂度增益/耦合面/变更面/回滚成本）。 */
    private val RISK_TABLE: Map<RefactorStep.StepKind, IntArray> = mapOf(
        RefactorStep.StepKind.SAFETY_NET to intArrayOf(1, 0, 1, 0),
        RefactorStep.StepKind.EXTRACT_DUPLICATE to intArrayOf(3, 1, 2, 1),
        RefactorStep.StepKind.SPLIT_FUNCTION to intArrayOf(4, 2, 3, 2),
        RefactorStep.StepKind.REDUCE_NESTING to intArrayOf(3, 2, 2, 2),
        RefactorStep.StepKind.PARAMS_OBJECT to intArrayOf(2, 3, 3, 2),
        RefactorStep.StepKind.DOMAIN_SPECIFIC to intArrayOf(2, 2, 2, 3)
    )

    /** 综合分权重（耦合面与回滚成本主导 —— 重构事故多源于此）。 */
    private const val W_RELIEF = 0.15
    private const val W_COUPLING = 0.35
    private const val W_SURFACE = 0.25
    private const val W_ROLLBACK = 0.25

    /**
     * 单步风险矩阵：出厂评分 + 结构度量微调（超长函数加变更面、
     * 深嵌套加耦合面），合成 0-100 综合分与批次建议。
     */
    fun riskMatrixOf(
        step: RefactorStep,
        longestFunctionLines: Int = 0,
        maxNestingDepth: Int = 0
    ): StepRiskMatrix {
        val base = RISK_TABLE[step.kind] ?: intArrayOf(2, 2, 2, 2)
        var relief = base[0]
        var coupling = base[1]
        var surface = base[2]
        var rollback = base[3]
        // 度量微调：体量事实加重风险
        if (longestFunctionLines > LONG_FUNCTION_HARD) surface = (surface + 1).coerceAtMost(4)
        if (maxNestingDepth > DEEP_NESTING_HARD) coupling = (coupling + 1).coerceAtMost(4)
        if (step.risk.level == RiskAssessment.RiskLevel.HIGH) rollback = (rollback + 1).coerceAtMost(4)
        val composite = (
            relief * W_RELIEF + coupling * W_COUPLING +
                surface * W_SURFACE + rollback * W_ROLLBACK
            ) / 4.0 * 100.0
        val score = composite.toInt().coerceIn(0, 100)
        val advice = when {
            score <= 25 -> "单步合入（低风险，常规评审）"
            score <= 50 -> "单步合入 + 双人评审"
            score <= 75 -> "拆小步执行（每步独立可验证）"
            else -> "需评审 + 特征开关灰度"
        }
        return StepRiskMatrix(
            stepKind = step.kind,
            complexityRelief = relief,
            couplingBreadth = coupling,
            changeSurface = surface,
            rollbackCost = rollback,
            compositeScore = score,
            batchAdvice = advice
        )
    }

    /** 全部步骤的矩阵清单（按综合分降序 = 最危险的最先被看见）。 */
    fun riskMatrices(
        steps: List<RefactorStep>,
        longestFunctionLines: Int = 0,
        maxNestingDepth: Int = 0
    ): List<StepRiskMatrix> =
        steps.map { riskMatrixOf(it, longestFunctionLines, maxNestingDepth) }
            .sortedByDescending { it.compositeScore }

    // ═══ 3) 变更面估算 ═══

    /** 各步骤种类的变更面出厂参数（函数数区间/行数区间/提交粒度）。 */
    private val SURFACE_TABLE: Map<RefactorStep.StepKind, Triple<IntRange, IntRange, String>> = mapOf(
        RefactorStep.StepKind.SAFETY_NET to Triple(1..3, 20..80, "单提交：测试文件独立合入"),
        RefactorStep.StepKind.EXTRACT_DUPLICATE to Triple(2..5, 30..120, "单提交：提取 + 调用点替换一次完成"),
        RefactorStep.StepKind.SPLIT_FUNCTION to Triple(1..4, 50..200, "单提交：拆分 + 委托保留旧签名"),
        RefactorStep.StepKind.REDUCE_NESTING to Triple(1..3, 30..150, "单提交：卫语句逐个压平"),
        RefactorStep.StepKind.PARAMS_OBJECT to Triple(2..8, 20..100, "单提交：参数对象 + 全调用点机械替换"),
        RefactorStep.StepKind.DOMAIN_SPECIFIC to Triple(1..6, 40..300, "视领域而定：建议按业务边界拆提交")
    )

    /**
     * 变更面估算：出厂参数 × 体量放大系数（超长函数按行数比例放大
     * 行数区间上限）。
     */
    fun estimateChangeSurface(step: RefactorStep, longestFunctionLines: Int = 0): ChangeSurfaceEstimate {
        val base = SURFACE_TABLE[step.kind]
            ?: Triple(1..6, 40..300, "视领域而定：建议按业务边界拆提交")
        var lines = base.second
        if (longestFunctionLines > LONG_FUNCTION_HARD) {
            val boost = (longestFunctionLines / 50).coerceIn(1, 3)
            lines = lines.first..(lines.last * boost)
        }
        return ChangeSurfaceEstimate(
            stepKind = step.kind,
            functionsRange = base.first,
            linesRange = lines,
            commitGranularity = base.third
        )
    }

    // ═══ 4) 回滚模板库 ═══

    /** 各步骤种类的确定性回滚手法（喂「每步的风险与回滚」章节）。 */
    fun rollbackTemplateOf(step: RefactorStep): String {
        val generic = step.risk.rollbackMethod
        return when (step.kind) {
            RefactorStep.StepKind.SAFETY_NET ->
                "删除测试提交（revert 单提交即可；保护网无生产代码路径）"
            RefactorStep.StepKind.EXTRACT_DUPLICATE ->
                "revert 提取提交，调用点随提交一并回滚；无数据形态变化"
            RefactorStep.StepKind.SPLIT_FUNCTION ->
                "保留旧签名的委托层使回滚 = 还原委托实现（单提交 revert）；" +
                    "若已删旧签名，按保护网测试重建"
            RefactorStep.StepKind.REDUCE_NESTING ->
                "revert 压平提交；分支行为由特征测试兜底确认"
            RefactorStep.StepKind.PARAMS_OBJECT ->
                "revert 参数对象提交；调用点替换为机械 diff，冲突面小"
            RefactorStep.StepKind.DOMAIN_SPECIFIC ->
                "$generic（领域步骤按各自提交粒度 revert）"
        }
    }

    // ═══ 5) 验证矩阵 ═══

    /** 步骤种类 × 验证手段穷举表（喂「验证清单」章节的确定性底稿）。 */
    fun verificationMatrixOf(steps: List<RefactorStep>): List<String> {
        val checklist = mutableListOf<String>()
        for (step in steps.distinctBy { it.kind }) {
            when (step.kind) {
                RefactorStep.StepKind.SAFETY_NET ->
                    checklist += "保护网：新增特征测试全部通过（现有行为快照零变化）"
                RefactorStep.StepKind.EXTRACT_DUPLICATE ->
                    checklist += "提取重复：重复代码计数下降（度量前后对比）+ 全部测试绿"
                RefactorStep.StepKind.SPLIT_FUNCTION ->
                    checklist += "拆分函数：最长函数行数降到阈值内 + 拆分前后特征测试输出一致"
                RefactorStep.StepKind.REDUCE_NESTING ->
                    checklist += "压平嵌套：最大嵌套深度降到阈值内 + 分支覆盖率不下降"
                RefactorStep.StepKind.PARAMS_OBJECT ->
                    checklist += "参数收敛：参数个数超过 4 的函数清零 + 编译门禁绿"
                RefactorStep.StepKind.DOMAIN_SPECIFIC ->
                    checklist += "领域步骤：${step.verification}（步骤内声明）"
            }
        }
        checklist += "收尾：结构度量对比（复杂度/重复/嵌套/参数四项全面不劣化）"
        return checklist
    }

    // ═══ 6) 安全序校验 ═══

    /**
     * 回包步骤清单的安全序校验（VALIDATE 阶段调用）：
     * 按 [HARD_DEPENDENCIES] 检查实际顺序，返回违序清单（每条带
     * 「应排在第 N 步之前」的确定性修复建议）。
     *
     * @param orderedSteps 回包解析出的步骤（按文档出现序）
     */
    fun checkOrdering(orderedSteps: List<RefactorStep>): List<StepOrderViolation> {
        if (orderedSteps.size < 2) return emptyList()
        val violations = mutableListOf<StepOrderViolation>()
        for (edge in HARD_DEPENDENCIES) {
            val fromIndex = orderedSteps.indexOfFirst { it.kind == edge.from }
            val toIndex = orderedSteps.indexOfFirst { it.kind == edge.to }
            if (fromIndex < 0 || toIndex < 0) continue
            if (fromIndex > toIndex) {
                val step = orderedSteps[toIndex]
                violations += StepOrderViolation(
                    stepTitle = step.title,
                    stepKind = step.kind,
                    currentPosition = toIndex + 1,
                    requiredBeforePosition = fromIndex + 1,
                    reason = edge.reason
                )
            }
        }
        return violations.sortedBy { it.currentPosition }
    }

    /**
     * 违序修复建议（单行形态，喂违规报告）。
     */
    fun violationAdvice(violation: StepOrderViolation): String =
        "「${violation.stepTitle}」（第 ${violation.currentPosition} 步）应移到第 " +
            "${violation.requiredBeforePosition} 步之前：${violation.reason}"

    // ═══ 常量 ═══

    /** 超长函数硬阈值（与 CodeReviewStructureMetrics 口径对齐的独立常量）。 */
    const val LONG_FUNCTION_HARD = 80

    /** 深嵌套硬阈值。 */
    const val DEEP_NESTING_HARD = 4

    /** 规则目录（工坊能力预览与目录完整性测试锚点）。 */
    val RULE_IDS: List<String> = listOf(
        "refactor.order-safety-net-first",
        "refactor.order-extract-before-split",
        "refactor.order-split-before-params",
        "refactor.order-nesting-before-params",
        "refactor.risk-high-composite",
        "refactor.surface-large-change",
        "refactor.rollback-missing",
        "refactor.verification-missing"
    )
}
