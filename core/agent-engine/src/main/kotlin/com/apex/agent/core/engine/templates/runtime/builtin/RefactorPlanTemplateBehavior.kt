package com.apex.agent.core.engine.templates.runtime.builtin

import com.apex.agent.core.engine.templates.runtime.TemplateAnalysis
import com.apex.agent.core.engine.templates.runtime.TemplateAnalysisStats
import com.apex.agent.core.engine.templates.runtime.TemplateBehaviorBase
import com.apex.agent.core.engine.templates.runtime.TemplateBehaviorFeature
import com.apex.agent.core.engine.templates.runtime.TemplateBehaviorManifest
import com.apex.agent.core.engine.templates.runtime.TemplateExecutionContext
import com.apex.agent.core.engine.templates.runtime.TemplateFinding
import com.apex.agent.core.engine.templates.runtime.TemplateFindingCategory
import com.apex.agent.core.engine.templates.runtime.TemplateOutputContract
import com.apex.agent.core.engine.templates.runtime.TemplatePhaseKind
import com.apex.agent.core.engine.templates.runtime.TemplatePreparedInput
import com.apex.agent.core.engine.templates.runtime.TemplatePromptEnrichmentMode
import com.apex.agent.core.engine.templates.runtime.TemplateRenderedReport
import com.apex.agent.core.engine.templates.runtime.TemplateSectionRequirement
import com.apex.agent.core.engine.templates.runtime.TemplateSeverity
import com.apex.agent.core.engine.templates.runtime.TemplateTurnRequest
import com.apex.agent.core.engine.templates.runtime.TemplateLanguageDetector

/**
 * ═══ 内置模板行为 · 重构方案（builtin_refactor_plan，PR 9）═══
 *
 * 「重构方案」模板的代码化行为：
 *
 * 1. **ANALYZE**：语言判定 + 结构度量（复用 [CodeReviewStructureMetrics]：
 *    复杂度/重复/超长/嵌套/参数）→ [RefactorPlanPlanner] 产出按影响排序的
 *    诊断清单 + 确定性步骤骨架（保护网先行）+ 风险税则 + 验证清单
 *    + 风险模型（PR 18：安全序 DAG / 四维风险矩阵 / 变更面估算 /
 *    回滚模板库 / 验证矩阵）；
 * 2. **COMPOSE**：正文 + 「诊断与规划附录」（问题清单 + 步骤骨架 +
 *    风险回滚表，LLM 基于证据填充细节）；
 * 3. **VALIDATE**：回包四必需章节（现状问题诊断 / 分步重构方案 /
 *    每步风险与回滚 / 验证清单），步骤章节数与骨架对齐检查
 *    + 安全序门禁（PR 18：回包步骤顺序与依赖图不一致 = 可修复违规）；
 * 4. **RENDER**：四段结构 + 机器摘要（诊断数/步骤数/最高风险/保护网存在
 *    /综合风险分/变更面预告）。
 */
open class RefactorPlanTemplateBehavior : TemplateBehaviorBase() {

    override val behaviorId: String = TEMPLATE_ID
    override val primaryInputName: String = "code"
    override val secondaryInputNames: List<String> = listOf("goal", "constraints")

    override fun manifest(): TemplateBehaviorManifest = TemplateBehaviorManifest(
        behaviorId = behaviorId,
        displayName = "重构方案",
        summary = "结构度量诊断 + 确定性步骤骨架（保护网先行）+ 风险税则表",
        supportedPhases = setOf(
            TemplatePhaseKind.PREPARE,
            TemplatePhaseKind.ANALYZE,
            TemplatePhaseKind.COMPOSE,
            TemplatePhaseKind.VALIDATE,
            TemplatePhaseKind.REPAIR,
            TemplatePhaseKind.RENDER
        ),
        features = setOf(
            TemplateBehaviorFeature.LANGUAGE_DETECTION,
            TemplateBehaviorFeature.RISK_ROLLBACK
        ),
        outputContract = TemplateOutputContract(
            requiredSections = listOf(
                TemplateSectionRequirement("现状问题诊断", aliases = listOf("问题诊断", "现状诊断"), required = true),
                TemplateSectionRequirement(
                    "分步重构方案",
                    aliases = listOf("重构步骤", "分步方案"),
                    listItemCountRange = 1..12,
                    required = true
                ),
                TemplateSectionRequirement("每步的风险与回滚", aliases = listOf("风险与回滚"), required = true),
                TemplateSectionRequirement("重构完成后的验证清单", aliases = listOf("验证清单"), required = true)
            )
        ),
        deterministicPreAnalysis = true,
        promptEnrichmentMode = TemplatePromptEnrichmentMode.APPEND_FINDINGS,
        behaviorVersion = 2
    )

    // ═══ ANALYZE ═══

    override suspend fun analyze(
        context: TemplateExecutionContext,
        prepared: TemplatePreparedInput
    ): TemplateAnalysis {
        val code = prepared.primaryInput
        if (code.isBlank()) {
            return TemplateAnalysis.of(
                findings = emptyList(),
                analyzerNotes = listOf("代码为空，跳过确定性分析")
            )
        }
        val goal = context.variableOr("goal", "")
        val constraints = context.variableOr("constraints", "无")
        val language = TemplateLanguageDetector.detect(code).takeIf { it.isConfident }?.detected
        val plan = RefactorPlanPlanner.plan(code, language, goal, constraints)
        val findings = mutableListOf<TemplateFinding>()

        // 1) 诊断清单（影响 1 级 → IMPORTANT，其余 MINOR）
        for (diagnosis in plan.diagnoses) {
            findings.add(
                TemplateFinding(
                    ruleId = "refactor.diagnosis",
                    category = TemplateFindingCategory.MAINTAINABILITY,
                    severity = if (diagnosis.impactLevel <= 1) TemplateSeverity.IMPORTANT else TemplateSeverity.MINOR,
                    title = "[${diagnosis.kind.label}] ${diagnosis.title}",
                    location = diagnosis.relatedMetric,
                    evidence = diagnosis.evidence.take(80),
                    suggestion = "步骤规划已关联（见步骤骨架）",
                    confidence = 0.9
                )
            )
        }

        // 2) 步骤骨架（INFO finding，evidence = 步骤摘要）
        for (step in plan.steps) {
            findings.add(
                TemplateFinding(
                    ruleId = "refactor.step-skeleton",
                    category = TemplateFindingCategory.STRUCTURE,
                    severity = TemplateSeverity.INFO,
                    title = "步骤 ${step.order}（${step.kind.label}/${step.risk.level.marker}${step.risk.level.label}）：${step.title}",
                    evidence = "验证：${step.verification}",
                    suggestion = "回滚：${step.risk.rollbackMethod}",
                    confidence = 1.0
                )
            )
        }

        // 2b) 风险模型（PR 18）：安全序 DAG + 四维矩阵 + 变更面 + 回滚模板
        val spans = CodeReviewTemplateBehavior().findFunctions(code, language)
        val metrics = CodeReviewStructureMetrics.report(code, language, spans)
        val longestFunctionLines = metrics.functions.maxOfOrNull { it.lineCount } ?: 0
        val maxNestingDepth = metrics.functions.maxOfOrNull { it.maxNesting } ?: 0
        val ordering = RefactorRiskModel.computeOrdering(plan.steps)
        if (ordering.dependencies.isNotEmpty()) {
            findings.add(
                TemplateFinding(
                    ruleId = "refactor.safe-ordering",
                    category = TemplateFindingCategory.RISK,
                    severity = TemplateSeverity.INFO,
                    title = "安全执行序已定（${ordering.safeOrder.joinToString(" → ") { it.label }}）",
                    evidence = ordering.dependencies.joinToString("；") { "${it.from.label}→${it.to.label}" },
                    suggestion = "回包步骤顺序必须遵循该序（保护网永远先行）",
                    confidence = 1.0
                )
            )
        }
        for (reason in ordering.unmetReasons) {
            findings.add(
                TemplateFinding(
                    ruleId = "refactor.order-unmet",
                    category = TemplateFindingCategory.RISK,
                    severity = TemplateSeverity.IMPORTANT,
                    title = "步骤依赖未满足",
                    evidence = reason,
                    suggestion = "在方案中补齐缺失的前置步骤",
                    confidence = 1.0
                )
            )
        }
        val matrices = RefactorRiskModel.riskMatrices(
            plan.steps,
            longestFunctionLines = longestFunctionLines,
            maxNestingDepth = maxNestingDepth
        )
        for (matrix in matrices.take(MAX_MATRIX_FINDINGS)) {
            findings.add(
                TemplateFinding(
                    ruleId = "refactor.risk-matrix",
                    category = TemplateFindingCategory.RISK,
                    severity = if (matrix.compositeScore >= HIGH_RISK_THRESHOLD) {
                        TemplateSeverity.IMPORTANT
                    } else {
                        TemplateSeverity.INFO
                    },
                    title = "风险矩阵：${matrix.toLine()}",
                    evidence = "四维评估（增益${matrix.complexityRelief}/耦合${matrix.couplingBreadth}/变更${matrix.changeSurface}/回滚${matrix.rollbackCost}）",
                    suggestion = matrix.batchAdvice,
                    confidence = 1.0
                )
            )
        }
        for (step in plan.steps.take(MAX_SURFACE_FINDINGS)) {
            val surface = RefactorRiskModel.estimateChangeSurface(
                step, longestFunctionLines
            )
            findings.add(
                TemplateFinding(
                    ruleId = "refactor.change-surface",
                    category = TemplateFindingCategory.MAINTAINABILITY,
                    severity = TemplateSeverity.INFO,
                    title = "变更面预告（${step.kind.label}）：${surface.functionsRange.first}-${surface.functionsRange.last} 个函数 / ${surface.linesRange.first}-${surface.linesRange.last} 行",
                    evidence = "提交粒度：${surface.commitGranularity}",
                    suggestion = "按粒度拆提交，每步独立可验证可回滚",
                    confidence = 0.9
                )
            )
        }

        var stats = TemplateAnalysisStats.empty()
            .with(TemplateAnalysisStats.KEY_RULE_COUNT, findings.size)
            .with(TemplateAnalysisStats.KEY_ANALYZED_LINES, code.lines().size)
            .with(METRIC_DIAGNOSES, plan.diagnoses.size)
            .with(METRIC_STEPS, plan.steps.size)
            .with(METRIC_MAX_RISK, plan.steps.maxOfOrNull { it.risk.level.ordinal }?.toString() ?: "0")

        val hints = mutableListOf<String>()
        hints += "重构目标：${goal.ifBlank { "（未指定，按结构健康度驱动）" }}"
        hints += "约束条件：$constraints"
        language?.let { hints += "语言判定：$it（自动探测）" } ?: hints.add("语言未识别，度量按通用口径")
        if (plan.steps.firstOrNull()?.kind == RefactorStep.StepKind.SAFETY_NET) {
            hints += "安全序约束：第一步必须是测试保护网（特征测试快照现有行为），之后才能动实现"
        }
        hints += "步骤骨架共 ${plan.steps.size} 步（顺序与风险已定，请填充每步的具体手法与代码位置）"
        hints += "验证清单基线：" + plan.verificationChecklist.take(3).joinToString("；")
        if (ordering.safeOrder.isNotEmpty()) {
            hints += "安全序（硬约束）：" + ordering.safeOrder.joinToString(" → ") { it.label }
        }
        matrices.firstOrNull()?.let {
            hints += "最高风险步骤：${it.stepKind.label}（综合 ${it.compositeScore}/100，${it.batchAdvice}）"
        }
        hints += "回滚模板（确定性）：" +
            plan.steps.take(3).joinToString("；") { "${it.kind.label}→${RefactorRiskModel.rollbackTemplateOf(it).take(30)}" }
        hints += "验证矩阵：" + RefactorRiskModel.verificationMatrixOf(plan.steps).take(4).joinToString("；")

        return TemplateAnalysis.of(
            findings = findings,
            stats = stats,
            detectedLanguage = language,
            enrichedHints = hints,
            metadata = buildMap {
                put("diagnosisCount", plan.diagnoses.size.toString())
                put("stepCount", plan.steps.size.toString())
                put("hasSafetyNet", (plan.steps.firstOrNull()?.kind == RefactorStep.StepKind.SAFETY_NET).toString())
                language?.let { put("language", it) }
            },
            analyzerNotes = plan.notes
        )
    }

    // ═══ VALIDATE（安全序门禁）═══

    override fun validateOutput(
        raw: String,
        context: TemplateExecutionContext,
        request: TemplateTurnRequest
    ): TemplateValidationReport {
        val base = super.validateOutput(raw, context, request)
        val violations = base.violations.toMutableList()
        // 专项（PR 18）：回包步骤顺序与安全序依赖图比对
        val steps = parseStepsFromOutput(raw)
        for (violation in RefactorRiskModel.checkOrdering(steps)) {
            violations.add(
                com.apex.agent.core.engine.templates.runtime.TemplateContractViolation(
                    kind = com.apex.agent.core.engine.templates.runtime.TemplateContractViolationKind.LIST_TOO_LONG,
                    section = "分步重构方案",
                    detail = "步骤违序：${RefactorRiskModel.violationAdvice(violation)}",
                    evidence = violation.stepTitle.take(50)
                )
            )
        }
        return TemplateValidationReport(
            violations = violations,
            parsedSections = base.parsedSections,
            orphans = base.orphans
        )
    }

    /** 从回包解析步骤清单（关键词→步骤种类；解析不出的归领域步骤）。 */
    internal fun parseStepsFromOutput(raw: String): List<RefactorStep> {
        val steps = mutableListOf<RefactorStep>()
        for ((index, line) in raw.lines().withIndex()) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("-") && !Regex("""^\d{1,2}[.、)]""").containsMatchIn(trimmed)) continue
            val kind = when {
                trimmed.contains("保护网") || trimmed.contains("特征测试") || trimmed.contains("快照测试") ->
                    RefactorStep.StepKind.SAFETY_NET
                trimmed.contains("提取") || trimmed.contains("去重") || trimmed.contains("重复") ->
                    RefactorStep.StepKind.EXTRACT_DUPLICATE
                trimmed.contains("拆分") || trimmed.contains("拆函数") || trimmed.contains("函数拆") ->
                    RefactorStep.StepKind.SPLIT_FUNCTION
                trimmed.contains("嵌套") || trimmed.contains("压平") || trimmed.contains("卫语句") ->
                    RefactorStep.StepKind.REDUCE_NESTING
                trimmed.contains("参数") -> RefactorStep.StepKind.PARAMS_OBJECT
                else -> continue
            }
            steps.add(
                RefactorStep(
                    order = index,
                    title = trimmed.take(60),
                    kind = kind,
                    scope = "",
                    verification = "",
                    risk = RiskAssessment(
                        RiskAssessment.RiskLevel.LOW, "revert", "低"
                    )
                )
            )
        }
        return steps
    }

    // ═══ RENDER ═══

    override fun renderReport(
        raw: String,
        context: TemplateExecutionContext,
        request: TemplateTurnRequest
    ): TemplateRenderedReport {
        val base = super.renderReport(raw, context, request)
        val code = context.variable("code").orEmpty()
        val language = TemplateLanguageDetector.detect(code).takeIf { it.isConfident }?.detected
        val plan = RefactorPlanPlanner.plan(
            code, language,
            context.variableOr("goal", ""),
            context.variableOr("constraints", "无")
        )
        val maxRisk = plan.steps.maxByOrNull { it.risk.level.ordinal }?.risk
        val matrices = RefactorRiskModel.riskMatrices(plan.steps)
        return base.copy(
            summary = mapOf(
                META_DIAGNOSIS_COUNT to plan.diagnoses.size.toString(),
                META_STEP_COUNT to plan.steps.size.toString(),
                META_MAX_RISK to (maxRisk?.level?.label ?: "无"),
                META_HAS_SAFETY_NET to (plan.steps.firstOrNull()?.kind == RefactorStep.StepKind.SAFETY_NET).toString(),
                META_TOP_RISK_SCORE to (matrices.firstOrNull()?.compositeScore?.toString() ?: "0"),
                META_ORDER_VIOLATIONS to RefactorRiskModel.checkOrdering(
                    parseStepsFromOutput(raw)
                ).size.toString()
            )
        )
    }

    companion object {
        const val TEMPLATE_ID = "builtin_refactor_plan"
        const val METRIC_DIAGNOSES = "diagnoses"
        const val METRIC_STEPS = "steps"
        const val METRIC_MAX_RISK = "maxRiskLevel"
        const val META_DIAGNOSIS_COUNT = "diagnosisCount"
        const val META_STEP_COUNT = "stepCount"
        const val META_MAX_RISK = "maxRisk"
        const val META_HAS_SAFETY_NET = "hasSafetyNet"
        const val META_TOP_RISK_SCORE = "topRiskScore"
        const val META_ORDER_VIOLATIONS = "orderViolationCount"
        const val MAX_MATRIX_FINDINGS = 4
        const val MAX_SURFACE_FINDINGS = 3
        const val HIGH_RISK_THRESHOLD = 75
    }
}
