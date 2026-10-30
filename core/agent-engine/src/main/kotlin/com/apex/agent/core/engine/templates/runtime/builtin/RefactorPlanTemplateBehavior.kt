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
 *    诊断清单 + 确定性步骤骨架（保护网先行）+ 风险税则 + 验证清单；
 * 2. **COMPOSE**：正文 + 「诊断与规划附录」（问题清单 + 步骤骨架 +
 *    风险回滚表，LLM 基于证据填充细节）；
 * 3. **VALIDATE**：回包四必需章节（现状问题诊断 / 分步重构方案 /
 *    每步风险与回滚 / 验证清单），步骤章节数与骨架对齐检查；
 * 4. **RENDER**：四段结构 + 机器摘要（诊断数/步骤数/最高风险/保护网存在）。
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
        behaviorVersion = 1
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
        return base.copy(
            summary = mapOf(
                META_DIAGNOSIS_COUNT to plan.diagnoses.size.toString(),
                META_STEP_COUNT to plan.steps.size.toString(),
                META_MAX_RISK to (maxRisk?.level?.label ?: "无"),
                META_HAS_SAFETY_NET to (plan.steps.firstOrNull()?.kind == RefactorStep.StepKind.SAFETY_NET).toString()
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
    }
}
