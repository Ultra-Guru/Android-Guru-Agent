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
 *    （8 类启发表）、五要素结构性矛盾检测 + 分诊引擎（PR 18：P0-P4
 *    优先级 / 环境指纹与已知问题库 / 症状签名查重 / 根因排查树 /
 *    回归测试指引 / 升级规则）；
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
        behaviorVersion = 2
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

        // 6) 分诊引擎（PR 18）：优先级 / 环境指纹 / 签名 / 排查树 / 回归指引
        val triage = BugReportTriageEngine.triage(severity.severity, symptom, actual, reproduce)
        findings.add(
            TemplateFinding(
                ruleId = "bugreport.triage-priority",
                category = TemplateFindingCategory.RISK,
                severity = when (triage.priority) {
                    TriagePriority.P0 -> TemplateSeverity.BLOCKER
                    TriagePriority.P1 -> TemplateSeverity.IMPORTANT
                    TriagePriority.P2 -> TemplateSeverity.MINOR
                    TriagePriority.P3, TriagePriority.P4 -> TemplateSeverity.INFO
                },
                title = "工单优先级：${triage.priority.name}（${triage.priority.label}，${triage.priority.sla}）",
                evidence = triage.rationale,
                suggestion = "报告的严重程度评估应与该优先级对齐；处置节奏按 SLA 执行",
                confidence = 0.9
            )
        )
        if (triage.requiresEscalation) {
            findings.add(
                TemplateFinding(
                    ruleId = "bugreport.escalation",
                    category = TemplateFindingCategory.RISK,
                    severity = TemplateSeverity.BLOCKER,
                    title = "必须升级：安全/数据类缺陷",
                    evidence = triage.escalationReason,
                    suggestion = "报告需包含升级通道与止血方案（回滚/开关）",
                    confidence = 1.0
                )
            )
        }
        val fieldEscalation = BugReportTriageEngine.needsFieldEscalation(
            severity.severity, reproQuality.score
        )
        if (fieldEscalation.first) {
            findings.add(
                TemplateFinding(
                    ruleId = "bugreport.field-escalation",
                    category = TemplateFindingCategory.COMPLETENESS,
                    severity = TemplateSeverity.IMPORTANT,
                    title = "需升级补现场",
                    evidence = fieldEscalation.second,
                    suggestion = "在排查建议中列出需要用户补充的具体信息清单",
                    confidence = 1.0
                )
            )
        }
        if (environment.isNotBlank()) {
            val fingerprint = BugReportTriageEngine.fingerprint(environment)
            for (hit in fingerprint.knownIssueHits.take(MAX_KNOWN_ISSUE_FINDINGS)) {
                findings.add(
                    TemplateFinding(
                        ruleId = "bugreport.known-issue",
                        category = TemplateFindingCategory.CORRECTNESS,
                        severity = TemplateSeverity.IMPORTANT,
                        title = "已知问题命中：${hit.title}（指纹 ${fingerprint.signature()}）",
                        evidence = hit.patternId,
                        suggestion = hit.advice,
                        confidence = 0.85
                    )
                )
            }
            if (fingerprint.platform == "unknown") {
                findings.add(
                    TemplateFinding(
                        ruleId = "bugreport.fingerprint-unknown",
                        category = TemplateFindingCategory.COMPLETENESS,
                        severity = TemplateSeverity.MINOR,
                        title = "环境指纹未能识别（平台未知）",
                        suggestion = "建议按「平台 / 系统版本 / 设备 / 应用版本」四段式重写环境信息",
                        confidence = 1.0
                    )
                )
            }
        }
        val signature = BugReportTriageEngine.symptomSignature(symptom, actual)
        if (BugReportTriageEngine.signatureIsQueryable(signature)) {
            findings.add(
                TemplateFinding(
                    ruleId = "bugreport.duplicate-hint",
                    category = TemplateFindingCategory.CONSISTENCY,
                    severity = TemplateSeverity.INFO,
                    title = "症状签名已生成（查重提示）",
                    evidence = signature,
                    suggestion = "排查前先按签名检索历史工单，同签名大概率同一根因",
                    confidence = 1.0
                )
            )
        }
        for (hypothesis in hypotheses.take(MAX_TREE_FINDINGS)) {
            val tree = BugReportTriageEngine.investigationTreeFor(hypothesis.category)
            if (tree.isNotEmpty()) {
                findings.add(
                    TemplateFinding(
                        ruleId = "bugreport.investigation-tree",
                        category = TemplateFindingCategory.CORRECTNESS,
                        severity = TemplateSeverity.INFO,
                        title = "排查树（${hypothesis.category.substringBefore("：")}）：${tree.size} 步确定性路径",
                        evidence = tree.joinToString(" → ") { "${it.order}.${it.action.take(14)}" },
                        suggestion = "排查建议按此路径展开（每步有期望观察）",
                        confidence = 1.0
                    )
                )
            }
            BugReportTriageEngine.regressionGuideFor(hypothesis.category)?.let { guide ->
                findings.add(
                    TemplateFinding(
                        ruleId = "bugreport.regression-guide",
                        category = TemplateFindingCategory.MAINTAINABILITY,
                        severity = TemplateSeverity.INFO,
                        title = "回归测试指引：${guide.testName}",
                        evidence = guide.assertionTarget,
                        suggestion = "边界条件必覆盖：" + guide.boundaryConditions.joinToString("、"),
                        confidence = 1.0
                    )
                )
            }
        }

        var stats = TemplateAnalysisStats.empty()
            .with(TemplateAnalysisStats.KEY_RULE_COUNT, findings.size)
            .with(TemplateAnalysisStats.KEY_ANALYZED_LINES, (symptom + reproduce + actual).lines().size)
            .with(METRIC_REPRO_SCORE, reproQuality.score)
            .with(METRIC_HYPOTHESES, hypotheses.size)
            .with(METRIC_TRIAGE_SCORE, triage.compositeScore)
            .with(METRIC_KNOWN_HITS, BugReportTriageEngine.fingerprint(environment).knownIssueHits.size)

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
        hints += "分诊结论：${triage.priority.name}（${triage.priority.label}）—— ${triage.rationale}"
        hints += "处置节奏：${triage.priority.sla}"
        if (BugReportTriageEngine.signatureIsQueryable(signature)) {
            hints += "查重签名：$signature（先检索历史工单再排查）"
        }

        return TemplateAnalysis.of(
            findings = findings,
            stats = stats,
            enrichedHints = hints,
            metadata = buildMap {
                put("severity", severity.severity.name)
                put("reproScore", reproQuality.score.toString())
                put("hypothesisCount", hypotheses.size.toString())
                put("triagePriority", triage.priority.name)
                put("triageScore", triage.compositeScore.toString())
                put("requiresEscalation", triage.requiresEscalation.toString())
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
                META_GAP_COUNT to gaps.size.toString(),
                META_TRIAGE_PRIORITY to BugReportTriageEngine.triage(
                    severity.severity, symptom, actual,
                    context.variable("reproduce").orEmpty()
                ).priority.name
            )
        )
    }

    companion object {
        const val TEMPLATE_ID = "builtin_bug_report"
        const val METRIC_REPRO_SCORE = "reproScore"
        const val METRIC_HYPOTHESES = "hypotheses"
        const val METRIC_TRIAGE_SCORE = "triageScore"
        const val METRIC_KNOWN_HITS = "knownIssueHits"
        const val META_REPRO_SCORE = "reproScore"
        const val META_HYPOTHESIS_COUNT = "hypothesisCount"
        const val META_GAP_COUNT = "gapCount"
        const val META_TRIAGE_PRIORITY = "triagePriority"
        const val MAX_KNOWN_ISSUE_FINDINGS = 3
        const val MAX_TREE_FINDINGS = 3
    }
}
