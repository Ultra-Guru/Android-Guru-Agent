package com.apex.agent.core.engine.templates.runtime.builtin

import com.apex.agent.core.engine.templates.runtime.TemplateFinding
import com.apex.agent.core.engine.templates.runtime.TemplateFindingCategory
import com.apex.agent.core.engine.templates.runtime.TemplateSeverity

/**
 * ═══ 内置模板行为 · 重构方案 —— 诊断与规划器（PR 9）═══
 *
 * 重构方案行为的分析层：复用 [CodeReviewStructureMetrics] 的结构度量
 * （复杂度/重复/参数/嵌套），在其上构建重构领域逻辑：
 *
 * 1. **问题诊断**：度量 → 按影响排序的「现状问题清单」（每条带
 *    影响等级与证据）；
 * 2. **步骤规划**：问题 → 确定性步骤骨架（每步 = 目标 + 涉及范围 +
 *    验证手段），步序遵循「先绿色保护网、再小步重构」的安全序；
 * 3. **风险税则表**：每类步骤的风险等级 + 回滚方式 + 回滚成本；
 * 4. **验证清单生成**：按代码形态给出可执行的验证项（测试/编译/指标阈值）。
 *
 * 设计取舍：步骤骨架是「确定性结构 + 待 LLM 填充细节」——顺序、风险、
 * 回滚全部由代码定，细节（具体函数怎么拆）由 LLM 基于证据补全。
 */

/** 现状问题诊断（按影响排序）。 */
data class RefactorDiagnosis(
    val kind: Kind,
    /** 影响等级（1 高 … 3 低）。 */
    val impactLevel: Int,
    val title: String,
    val evidence: String,
    /** 关联的步骤序号（规划后回填）。 */
    val relatedMetric: String
) {
    enum class Kind(val label: String) {
        HIGH_COMPLEXITY("高复杂度"),
        DUPLICATION("重复代码"),
        LONG_FUNCTION("超长函数"),
        DEEP_NESTING("过深嵌套"),
        TOO_MANY_PARAMS("参数过多"),
        NO_TEST_SAFETY_NET("缺少测试保护网"),
        UNDETECTED_LANGUAGE("语言未识别")
    }
}

/** 重构步骤骨架。 */
data class RefactorStep(
    val order: Int,
    val title: String,
    /** 步骤种类（决定风险与回滚模板）。 */
    val kind: StepKind,
    /** 涉及范围（函数名/指标）。 */
    val scope: String,
    /** 验证手段（确定性给出）。 */
    val verification: String,
    val risk: RiskAssessment
) {
    enum class StepKind(val label: String) {
        /** 补测试保护网（永远第一步）。 */
        SAFETY_NET("保护网"),
        /** 提取重复。 */
        EXTRACT_DUPLICATE("提取重复"),
        /** 拆分函数。 */
        SPLIT_FUNCTION("拆分函数"),
        /** 降低嵌套。 */
        REDUCE_NESTING("降低嵌套"),
        /** 参数对象化。 */
        PARAMS_OBJECT("参数收敛"),
        /** 其它（LLM 填充的领域步骤）。 */
        DOMAIN_SPECIFIC("领域步骤")
    }
}

/** 风险评估（税则表驱动）。 */
data class RiskAssessment(
    val level: RiskLevel,
    val rollbackMethod: String,
    /** 回滚成本（低/中/高）。 */
    val rollbackCost: String
) {
    enum class RiskLevel(val label: String, val marker: String) {
        LOW("低", "🟢"),
        MEDIUM("中", "🟡"),
        HIGH("高", "🔴")
    }
}

/** 规划产物。 */
data class RefactorPlan(
    val diagnoses: List<RefactorDiagnosis>,
    val steps: List<RefactorStep>,
    /** 验证清单（收尾步骤用）。 */
    val verificationChecklist: List<String>,
    /** 代码侧判断的可行性注记。 */
    val notes: List<String>
)

/**
 * 重构规划器（纯函数）。
 */
object RefactorPlanPlanner {

    /** 风险税则表：步骤种类 → (风险等级, 回滚方式, 回滚成本)。 */
    private val RISK_TAXONOMY: Map<RefactorStep.StepKind, Triple<RiskAssessment.RiskLevel, String, String>> = mapOf(
        RefactorStep.StepKind.SAFETY_NET to Triple(
            RiskAssessment.RiskLevel.LOW,
            "删除新增测试即可（纯增量）",
            "低"
        ),
        RefactorStep.StepKind.EXTRACT_DUPLICATE to Triple(
            RiskAssessment.RiskLevel.LOW,
            "保留旧调用点备份（rename 前提交），revert 单个 commit",
            "低"
        ),
        RefactorStep.StepKind.SPLIT_FUNCTION to Triple(
            RiskAssessment.RiskLevel.MEDIUM,
            "每步独立 commit；旧函数保留 @Deprecated 委托一个发布周期",
            "中"
        ),
        RefactorStep.StepKind.REDUCE_NESTING to Triple(
            RiskAssessment.RiskLevel.MEDIUM,
            "卫语句改写逐步提交，任一验证失败 revert 该步",
            "中"
        ),
        RefactorStep.StepKind.PARAMS_OBJECT to Triple(
            RiskAssessment.RiskLevel.LOW,
            "参数对象与散参重载并存过渡，稳定后删重载",
            "低"
        ),
        RefactorStep.StepKind.DOMAIN_SPECIFIC to Triple(
            RiskAssessment.RiskLevel.MEDIUM,
            "由 LLM 按具体改动评估（默认按中风险处理）",
            "中"
        )
    )

    /**
     * 诊断 → 规划：从结构度量报告生成排序的问题清单与步骤骨架。
     *
     * @param code 原代码
     * @param language 语言（可空 = 未识别）
     * @param goal 重构目标（用户变量）
     * @param constraints 约束（用户变量）
     */
    fun plan(
        code: String,
        language: String?,
        goal: String,
        constraints: String
    ): RefactorPlan {
        val notes = mutableListOf<String>()
        val spans = CodeReviewTemplateBehavior().findFunctions(code, language)
        val metrics = CodeReviewStructureMetrics.report(code, language, spans)
        val diagnoses = mutableListOf<RefactorDiagnosis>()

        // ── 诊断：按影响排序（影响 = 严重度 × 波及面）──
        for (function in metrics.highComplexityFunctions) {
            diagnoses.add(
                RefactorDiagnosis(
                    kind = RefactorDiagnosis.Kind.HIGH_COMPLEXITY,
                    impactLevel = 1,
                    title = "${function.name} 圈复杂度代理 ${function.cyclomaticProxy}（阈值 ${CodeReviewStructureMetrics.HIGH_COMPLEXITY}）",
                    evidence = "分支 ${function.branchCount} / 参数 ${function.parameterCount} / 嵌套 ${function.maxNesting} / 跨度 ${function.lineCount} 行",
                    relatedMetric = "complexity:${function.name}"
                )
            )
        }
        for (duplicate in metrics.duplicates) {
            diagnoses.add(
                RefactorDiagnosis(
                    kind = RefactorDiagnosis.Kind.DUPLICATION,
                    impactLevel = 1,
                    title = "重复块出现 ${duplicate.occurrences} 次",
                    evidence = duplicate.sample,
                    relatedMetric = "duplication:${duplicate.firstLine}"
                )
            )
        }
        for (function in metrics.functions.filter { it.lineCount > CodeReviewTemplateBehavior.LONG_FUNCTION_THRESHOLD }) {
            diagnoses.add(
                RefactorDiagnosis(
                    kind = RefactorDiagnosis.Kind.LONG_FUNCTION,
                    impactLevel = 2,
                    title = "${function.name} 长 ${function.lineCount} 行（阈值 ${CodeReviewTemplateBehavior.LONG_FUNCTION_THRESHOLD}）",
                    evidence = "自第 ${function.startLine} 行起",
                    relatedMetric = "length:${function.name}"
                )
            )
        }
        val deepNested = metrics.functions.filter { it.maxNesting > CodeReviewStructureMetrics.MAX_NESTING }
        for (function in deepNested) {
            diagnoses.add(
                RefactorDiagnosis(
                    kind = RefactorDiagnosis.Kind.DEEP_NESTING,
                    impactLevel = 2,
                    title = "${function.name} 嵌套 ${function.maxNesting} 层",
                    evidence = "最深缩进 ${function.maxNesting * 4} 空格",
                    relatedMetric = "nesting:${function.name}"
                )
            )
        }
        for (function in metrics.functions.filter { it.hasTooManyParams }) {
            diagnoses.add(
                RefactorDiagnosis(
                    kind = RefactorDiagnosis.Kind.TOO_MANY_PARAMS,
                    impactLevel = 3,
                    title = "${function.name} 参数 ${function.parameterCount} 个（阈值 ${CodeReviewStructureMetrics.MAX_PARAMS}）",
                    evidence = "第 ${function.startLine} 行签名",
                    relatedMetric = "params:${function.name}"
                )
            )
        }
        if (language == null) {
            diagnoses.add(
                RefactorDiagnosis(
                    kind = RefactorDiagnosis.Kind.UNDETECTED_LANGUAGE,
                    impactLevel = 3,
                    title = "语言未能高置信识别，结构度量按通用口径执行",
                    evidence = "函数识别覆盖率取决于语言族判定",
                    relatedMetric = "language:unknown"
                )
            )
            notes.add("语言未识别：复杂度/函数跨度按通用分支关键字表估算，请以实际语言复核")
        }
        // 保护网诊断：代码体量足够但没有任何测试痕迹
        val hasTestTrace = TEST_TRACE_PATTERNS.any { it.containsMatchIn(code) }
        if (code.lines().size >= MIN_LINES_FOR_SAFETY_NET && !hasTestTrace && language != null) {
            diagnoses.add(
                RefactorDiagnosis(
                    kind = RefactorDiagnosis.Kind.NO_TEST_SAFETY_NET,
                    impactLevel = 1,
                    title = "重构前缺少测试保护网（代码内无测试痕迹）",
                    evidence = "输入为纯实现代码，未携带或引用任何测试",
                    relatedMetric = "safety-net"
                )
            )
        }
        val sorted = diagnoses.sortedBy { it.impactLevel }

        // ── 规划：确定性步骤骨架 ──
        val steps = mutableListOf<RefactorStep>()
        var order = 1
        // 第一步永远是保护网（有代码无测试时）
        if (sorted.any { it.kind == RefactorDiagnosis.Kind.NO_TEST_SAFETY_NET }) {
            steps.add(
                RefactorStep(
                    order = order++,
                    title = "为待重构区域补齐特征测试（characterization tests）",
                    kind = RefactorStep.StepKind.SAFETY_NET,
                    scope = "全部待改函数的现有行为快照",
                    verification = "新增测试全绿；覆盖待改函数的主要分支（复杂度代理 ≥ ${CodeReviewStructureMetrics.HIGH_COMPLEXITY} 的函数必须覆盖）",
                    risk = riskOf(RefactorStep.StepKind.SAFETY_NET)
                )
            )
        }
        // 重复提取（影响 1 级且风险低 → 优先）
        for (diagnosis in sorted.filter { it.kind == RefactorDiagnosis.Kind.DUPLICATION }.take(2)) {
            steps.add(
                RefactorStep(
                    order = order++,
                    title = "提取重复块为公共函数（第 ${diagnosis.relatedMetric.substringAfter(':')} 行起）",
                    kind = RefactorStep.StepKind.EXTRACT_DUPLICATE,
                    scope = diagnosis.evidence.take(60),
                    verification = "重复计数归零（重跑结构度量 duplicates 为空）；全部既有测试通过",
                    risk = riskOf(RefactorStep.StepKind.EXTRACT_DUPLICATE)
                )
            )
        }
        // 拆分高复杂 / 超长函数
        for (diagnosis in sorted.filter {
            it.kind == RefactorDiagnosis.Kind.HIGH_COMPLEXITY || it.kind == RefactorDiagnosis.Kind.LONG_FUNCTION
        }.take(3)) {
            steps.add(
                RefactorStep(
                    order = order++,
                    title = "拆分 ${diagnosis.title.substringBefore("（")}",
                    kind = RefactorStep.StepKind.SPLIT_FUNCTION,
                    scope = diagnosis.relatedMetric.substringAfter(':'),
                    verification = "拆分后单函数圈复杂度代理 ≤ ${CodeReviewStructureMetrics.HIGH_COMPLEXITY}，跨度 ≤ ${CodeReviewTemplateBehavior.LONG_FUNCTION_THRESHOLD} 行；测试全绿",
                    risk = riskOf(RefactorStep.StepKind.SPLIT_FUNCTION)
                )
            )
        }
        // 降低嵌套
        if (sorted.any { it.kind == RefactorDiagnosis.Kind.DEEP_NESTING }) {
            steps.add(
                RefactorStep(
                    order = order++,
                    title = "卫语句化降低嵌套（最深 ≤ ${CodeReviewStructureMetrics.MAX_NESTING} 层）",
                    kind = RefactorStep.StepKind.REDUCE_NESTING,
                    scope = deepNested.joinToString("、") { it.name },
                    verification = "结构度量最深嵌套 ≤ ${CodeReviewStructureMetrics.MAX_NESTING}；行为测试不变",
                    risk = riskOf(RefactorStep.StepKind.REDUCE_NESTING)
                )
            )
        }
        // 参数收敛
        if (sorted.any { it.kind == RefactorDiagnosis.Kind.TOO_MANY_PARAMS }) {
            steps.add(
                RefactorStep(
                    order = order++,
                    title = "参数对象化收敛（每函数参数 ≤ ${CodeReviewStructureMetrics.MAX_PARAMS}）",
                    kind = RefactorStep.StepKind.PARAMS_OBJECT,
                    scope = metrics.functions.filter { it.hasTooManyParams }.joinToString("、") { it.name },
                    verification = "签名参数计数达标；调用点编译通过；测试全绿",
                    risk = riskOf(RefactorStep.StepKind.PARAMS_OBJECT)
                )
            )
        }
        // 目标关联步骤（用户 goal 命中关键词时给领域步骤占位）
        if (goal.isNotBlank() && steps.isNotEmpty()) {
            steps.add(
                RefactorStep(
                    order = order++,
                    title = "面向重构目标的领域调整（$goal）",
                    kind = RefactorStep.StepKind.DOMAIN_SPECIFIC,
                    scope = goal.take(40),
                    verification = "目标相关的行为测试（由 LLM 基于目标补充）全绿",
                    risk = riskOf(RefactorStep.StepKind.DOMAIN_SPECIFIC)
                )
            )
        }
        if (steps.isEmpty()) {
            notes.add("结构度量未发现超阈值项：重构方案转向目标驱动（由 LLM 依据目标规划领域步骤）")
            steps.add(
                RefactorStep(
                    order = 1,
                    title = "目标驱动的领域重构（$goal）",
                    kind = RefactorStep.StepKind.DOMAIN_SPECIFIC,
                    scope = goal.take(40),
                    verification = "按约束逐项验收（$constraints）",
                    risk = riskOf(RefactorStep.StepKind.DOMAIN_SPECIFIC)
                )
            )
        }

        return RefactorPlan(
            diagnoses = sorted,
            steps = steps,
            verificationChecklist = buildChecklist(metrics, language),
            notes = notes
        )
    }

    /** 验证清单（收尾章节的确定性骨架）。 */
    private fun buildChecklist(
        metrics: StructureMetricsReport,
        language: String?
    ): List<String> = buildList {
        add("全量测试通过（含新增特征测试）")
        add("结构度量复查：高复杂函数数 = ${metrics.highComplexityFunctions.size}（目标 0）")
        add("结构度量复查：重复块 = ${metrics.duplicates.size}（目标 0）")
        if (language != null) {
            add("$language 编译/静态检查零告警（或告警数不增加）")
        }
        add("公共 API 无未声明的破坏性变更（或已按流程声明）")
        add("重构前后行为对比：关键路径输出一致")
    }

    private fun riskOf(kind: RefactorStep.StepKind): RiskAssessment {
        val (level, rollback, cost) = RISK_TAXONOMY[kind]
            ?: Triple(RiskAssessment.RiskLevel.MEDIUM, "按中风险处理", "中")
        return RiskAssessment(level, rollback, cost)
    }

    /** 测试痕迹模式（保护网判定：输入代码自身像测试或有测试引用）。 */
    private val TEST_TRACE_PATTERNS = listOf(
        Regex("""@Test\b"""),
        Regex("""fun\s+\w*[Tt]est\w*\s*\("""),
        Regex("""def\s+test_\w+"""),
        Regex("""func\s+Test\w+\("""),
        Regex("""\bassert\w*\("""),
        Regex("""class\s+\w*Test\b"""),
        Regex("""class\s+\w*Spec\b"""),
        Regex("""describe\s*\(""")
    )

    const val MIN_LINES_FOR_SAFETY_NET = 20
}
