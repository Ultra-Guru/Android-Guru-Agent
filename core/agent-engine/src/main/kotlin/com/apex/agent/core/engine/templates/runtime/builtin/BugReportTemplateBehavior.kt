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
import com.apex.agent.core.engine.templates.runtime.TemplateReportSection
import com.apex.agent.core.engine.templates.runtime.TemplateSectionRequirement
import com.apex.agent.core.engine.templates.runtime.TemplateSeverity
import com.apex.agent.core.engine.templates.runtime.TemplateTurnRequest

/**
 * ═══ 内置模板行为 · BUG 报告（builtin_bug_report，PR 10）═══
 *
 * 「BUG 报告」模板的代码化行为：
 *
 * 1. **ANALYZE**（确定性）：严重度推断（26 组关键词分级）、复现质量
 *    评分（步骤/确定性/具体工件三维）、环境结构化解析、原因假设排序
 *    （8 类启发表）、五要素结构性矛盾检测；
 * 2. **COMPOSE**：正文 + 判读附录（严重度建议/质量评分/假设排序/缺口清单）；
 * 3. **VALIDATE**：回包必需章节 = 标题 / 严重程度评估 / 可能原因分析（按
 *    可能性排序）/ 下一步排查建议；严重度与代码侧建议冲突检测；
 * 4. **RENDER**：报告骨架 + 机器摘要（严重度/复现分/假设数/缺口数）。
 */
open class BugReportTemplateBehavior : TemplateBehaviorBase() {

    override val behaviorId: String = TEMPLATE_ID
    override val primaryInputName: String = "symptom"
    override val secondaryInputNames: List<String> = listOf("reproduce", "expected", "actual", "environment")

    override fun manifest(): TemplateBehaviorManifest = TemplateBehaviorManifest(
        behaviorId = behaviorId,
        displayName = "BUG 报告",
        summary = "严重度分级 + 复现质量评分 + 原因假设排序，缺陷报告结构化可判定",
        supportedPhases = setOf(
            TemplatePhaseKind.PREPARE,
            TemplatePhaseKind.ANALYZE,
            TemplatePhaseKind.COMPOSE,
            TemplatePhaseKind.VALIDATE,
            TemplatePhaseKind.REPAIR,
            TemplatePhaseKind.RENDER
        ),
        features = setOf(
            TemplateBehaviorFeature.SEVERITY_MODEL,
            TemplateBehaviorFeature.INPUT_QUALITY
        ),
        outputContract = TemplateOutputContract(
            requiredSections = listOf(
                TemplateSectionRequirement("标题", required = true),
                TemplateSectionRequirement("严重程度评估", aliases = listOf("严重程度", "严重度"), required = true),
                TemplateSectionRequirement(
                    "可能原因分析",
                    aliases = listOf("可能原因", "原因分析"),
                    listItemCountRange = 1..8,
                    required = true
                ),
                TemplateSectionRequirement("下一步排查建议", aliases = listOf("排查建议", "下一步建议"), required = true)
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
        val symptom = prepared.input("symptom")
        if (symptom.isBlank() && prepared.input("reproduce").isBlank()) {
            return TemplateAnalysis.of(
                findings = emptyList(),
                analyzerNotes = listOf("症状与复现均为空，跳过确定性分析")
            )
        }
        val reproduce = prepared.input("reproduce")
        val expected = prepared.input("expected")
        val actual = prepared.input("actual")
        val environment = prepared.input("environment")
        val findings = mutableListOf<TemplateFinding>()

        // 1) 严重度（代码侧判定结论）
        val severity = BugReportAnalyzer.assessSeverity(symptom, actual)
        findings.add(
            TemplateFinding(
                ruleId = "bugreport.severity",
                category = TemplateFindingCategory.RISK,
                severity = severity.severity,
                title = "代码侧严重度判定：${severity.severity.marker}${severity.severity.label}",
                evidence = severity.rationale + (severity.evidence.take(3).joinToString("；").takeIf { it.isNotBlank() }
                    ?.let { "｜$it" } ?: ""),
                suggestion = "报告中的严重程度评估应与该判定一致，不一致须给出理由",
                confidence = 0.85
            )
        )

        // 2) 复现质量
        val reproQuality = BugReportAnalyzer.assessReproQuality(reproduce)
        findings.add(
            TemplateFinding(
                ruleId = "bugreport.repro-quality",
                category = TemplateFindingCategory.COMPLETENESS,
                severity = if (reproQuality.score < 40) TemplateSeverity.IMPORTANT else TemplateSeverity.MINOR,
                title = "复现质量评分：${reproQuality.score}/100（${reproQuality.stepCount} 步）",
                evidence = buildString {
                    append(if (reproQuality.hasDeterministicMarkers) "确定性词汇✓" else "无确定性词汇")
                    append(" / ")
                    append(if (reproQuality.hasConcreteArtifacts) "含具体工件✓" else "无具体工件")
                },
                suggestion = reproQuality.suggestions.joinToString("；").ifBlank { "质量达标，直接引用步骤" },
                confidence = 1.0
            )
        )

        // 3) 环境解析（INFO：结构化字段）
        if (environment.isNotBlank()) {
            val parsed = BugReportAnalyzer.parseEnvironment(environment)
            findings.add(
                TemplateFinding(
                    ruleId = "bugreport.environment",
                    category = TemplateFindingCategory.STRUCTURE,
                    severity = TemplateSeverity.INFO,
                    title = "环境解析：" + listOfNotNull(
                        parsed.device?.let { "设备 $it" },
                        parsed.osVersion?.let { "系统 $it" },
                        parsed.appVersion?.let { "版本 $it" }
                    ).joinToString(" / ").ifBlank { "未识别出结构化字段" },
                    evidence = environment.take(60),
                    suggestion = "报告使用结构化环境字段（设备/系统/版本分列）",
                    confidence = 1.0
                )
            )
        }

        // 4) 假设排序（INFO）
        val hypotheses = BugReportAnalyzer.rankHypotheses(symptom, actual)
        for (hypothesis in hypotheses) {
            findings.add(
                TemplateFinding(
                    ruleId = "bugreport.hypothesis",
                    category = TemplateFindingCategory.CORRECTNESS,
                    severity = TemplateSeverity.INFO,
                    title = "原因假设 #$${hypothesis.priority}：${hypothesis.category}",
                    evidence = hypothesis.rationale,
                    suggestion = "按此顺序展开排查路径",
                    confidence = 0.8
                )
            )
        }

        // 5) 结构性质量问题
        findings += BugReportAnalyzer.findingsFromIssues(
            BugReportAnalyzer.qualityIssues(symptom, reproduce, expected, actual, environment)
        )

        var stats = TemplateAnalysisStats.empty()
            .with(TemplateAnalysisStats.KEY_RULE_COUNT, findings.size)
            .with(TemplateAnalysisStats.KEY_ANALYZED_LINES, (symptom + reproduce + actual).lines().size)
            .with(METRIC_REPRO_SCORE, reproQuality.score)
            .with(METRIC_HYPOTHESES, hypotheses.size)

        val hints = mutableListOf<String>()
        hints += "严重度基线：${severity.severity.marker}${severity.severity.label}（${severity.rationale}）"
        hints += "复现质量 ${reproQuality.score}/100：${reproQuality.stepCount} 步" +
            (if (reproQuality.hasDeterministicMarkers) "，含确定性表述" else "，未见确定性表述")
        if (hypotheses.isNotEmpty()) {
            hints += "原因假设优先序（代码侧排序）：" + hypotheses.joinToString(" > ") { it.category.substringBefore("：") }
        }
        if (reproQuality.suggestions.isNotEmpty()) {
            hints += "报告需引导补充：" + reproQuality.suggestions.joinToString("；")
        }

        return TemplateAnalysis.of(
            findings = findings,
            stats = stats,
            enrichedHints = hints,
            metadata = buildMap {
                put("severity", severity.severity.name)
                put("reproScore", reproQuality.score.toString())
                put("hypothesisCount", hypotheses.size.toString())
            }
        )
    }

    // ═══ RENDER ═══

    override fun renderReport(
        raw: String,
        context: TemplateExecutionContext,
        request: TemplateTurnRequest
    ): TemplateRenderedReport {
        val base = super.renderReport(raw, context, request)
        val symptom = context.variable("symptom").orEmpty()
        val actual = context.variable("actual").orEmpty()
        val severity = BugReportAnalyzer.assessSeverity(symptom, actual)
        val repro = BugReportAnalyzer.assessReproQuality(context.variable("reproduce").orEmpty())
        val hypotheses = BugReportAnalyzer.rankHypotheses(symptom, actual)
        val gaps = BugReportAnalyzer.qualityIssues(
            symptom,
            context.variable("reproduce").orEmpty(),
            context.variableOr("expected", ""),
            context.variableOr("actual", ""),
            context.variableOr("environment", "")
        )
        // 顶部严重度徽标章节
        val badge = TemplateReportSection(
            title = "缺陷概览",
            kind = TemplateReportSection.Kind.VERDICT,
            lines = listOf(
                "严重度：${severity.severity.marker}${severity.severity.label}（代码侧判定）｜ 复现质量：${repro.score}/100"
            ),
            orderKey = -1
        )
        return base.copy(
            sections = (listOf(badge) + base.sections).sortedBy { it.orderKey },
            summary = mapOf(
                TemplateRenderedReport.KEY_BLOCKER_COUNT to
                    (if (severity.severity == TemplateSeverity.BLOCKER) "1" else "0"),
                TemplateRenderedReport.KEY_SEVERITY_TOP to severity.severity.label,
                META_REPRO_SCORE to repro.score.toString(),
                META_HYPOTHESIS_COUNT to hypotheses.size.toString(),
                META_GAP_COUNT to gaps.size.toString()
            )
        )
    }

    companion object {
        const val TEMPLATE_ID = "builtin_bug_report"
        const val METRIC_REPRO_SCORE = "reproScore"
        const val METRIC_HYPOTHESES = "hypotheses"
        const val META_REPRO_SCORE = "reproScore"
        const val META_HYPOTHESIS_COUNT = "hypothesisCount"
        const val META_GAP_COUNT = "gapCount"
    }
}
