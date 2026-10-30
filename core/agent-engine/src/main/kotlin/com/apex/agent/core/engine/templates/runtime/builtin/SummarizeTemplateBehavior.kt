package com.apex.agent.core.engine.templates.runtime.builtin

import com.apex.agent.core.engine.templates.runtime.TemplateAnalysis
import com.apex.agent.core.engine.templates.runtime.TemplateAnalysisStats
import com.apex.agent.core.engine.templates.runtime.TemplateBehaviorBase
import com.apex.agent.core.engine.templates.runtime.TemplateBehaviorFeature
import com.apex.agent.core.engine.templates.runtime.TemplateBehaviorManifest
import com.apex.agent.core.engine.templates.runtime.TemplateContractViolation
import com.apex.agent.core.engine.templates.runtime.TemplateContractViolationKind
import com.apex.agent.core.engine.templates.runtime.TemplateExecutionContext
import com.apex.agent.core.engine.templates.runtime.TemplateFinding
import com.apex.agent.core.engine.templates.runtime.TemplateFindingCategory
import com.apex.agent.core.engine.templates.runtime.TemplateOutputContract
import com.apex.agent.core.engine.templates.runtime.TemplatePhaseKind
import com.apex.agent.core.engine.templates.runtime.TemplatePreparedInput
import com.apex.agent.core.engine.templates.runtime.TemplatePromptEnrichmentMode
import com.apex.agent.core.engine.templates.runtime.TemplateRenderedReport
import com.apex.agent.core.engine.templates.runtime.TemplateSectionRequirement
import com.apex.agent.core.engine.templates.runtime.TemplateSectionParser
import com.apex.agent.core.engine.templates.runtime.TemplateSeverity
import com.apex.agent.core.engine.templates.runtime.TemplateTurnRequest
import com.apex.agent.core.engine.templates.runtime.TemplateValidationReport

/**
 * ═══ 内置模板行为 · 内容总结（builtin_summarize，PR 8）═══
 *
 * 「内容总结」模板的代码化行为：
 *
 * 1. **ANALYZE**：长度预算解析（中文数字/区间/左右全支持）、内容画像
 *    （五类体裁）、关键事实抽取（数字/百分比/日期/区间/倍数/否定句）、
 *    抽取式草稿（句级打分 + 去冗余 + 原文序）；
 * 2. **COMPOSE**：正文 + 预算明示 + 事实清单 + 草稿句附录；
 * 3. **VALIDATE**：结论先行 + 要点 3-5 条 + 长度预算 + 事实覆盖率 +
 *    否定语义保留（[SummarizeAnalyzer.validateSummary]）；
 * 4. **RENDER**：结论/要点/关键事实三段 + 机器摘要
 *    （体裁/预算达标/事实覆盖/要点数）。
 */
open class SummarizeTemplateBehavior : TemplateBehaviorBase() {

    override val behaviorId: String = TEMPLATE_ID
    override val primaryInputName: String = "content"
    override val secondaryInputNames: List<String> = listOf("perspective", "length")

    override fun manifest(): TemplateBehaviorManifest = TemplateBehaviorManifest(
        behaviorId = behaviorId,
        displayName = "内容总结",
        summary = "长度预算/体裁画像/关键事实抽取/否定语义保护，总结质量可校验",
        supportedPhases = setOf(
            TemplatePhaseKind.PREPARE,
            TemplatePhaseKind.ANALYZE,
            TemplatePhaseKind.COMPOSE,
            TemplatePhaseKind.VALIDATE,
            TemplatePhaseKind.REPAIR,
            TemplatePhaseKind.RENDER
        ),
        features = setOf(TemplateBehaviorFeature.TEXT_SCORING),
        outputContract = TemplateOutputContract(
            requiredSections = listOf(
                TemplateSectionRequirement(
                    title = "一句话结论",
                    aliases = listOf("结论"),
                    required = true
                ),
                TemplateSectionRequirement(
                    title = "要点",
                    aliases = listOf("关键要点", "要点列表"),
                    listItemCountRange = 3..5,
                    required = true
                ),
                TemplateSectionRequirement(
                    title = "保留的关键事实",
                    aliases = listOf("关键事实"),
                    listItemCountRange = 1..8,
                    required = false
                )
            ),
            requiresVerdictStyle = true
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
        val content = prepared.primaryInput
        if (content.isBlank()) {
            return TemplateAnalysis.of(
                findings = emptyList(),
                analyzerNotes = listOf("原文为空，跳过确定性分析")
            )
        }
        val perspective = context.variableOr("perspective", "客观中立")
        val lengthInput = context.variableOr("length", "200 字以内")
        val budget = SummarizeAnalyzer.parseLengthBudget(lengthInput)
        val findings = mutableListOf<TemplateFinding>()

        // 1) 长度预算（解析失败提示）
        if (!budget.parsed) {
            findings.add(
                TemplateFinding(
                    ruleId = "summary.budget-unparsed",
                    category = TemplateFindingCategory.COMPLETENESS,
                    severity = TemplateSeverity.MINOR,
                    title = "长度要求无法解析（$lengthInput），按默认 ${LengthBudget.DEFAULT.maxChars} 字执行",
                    suggestion = "结论 + 要点合计字符数控制在预算内（不含标题与空白）",
                    confidence = 1.0
                )
            )
        }

        // 2) 体裁画像
        val profile = SummarizeAnalyzer.profile(content)
        findings.add(
            TemplateFinding(
                ruleId = "summary.genre",
                category = TemplateFindingCategory.STRUCTURE,
                severity = TemplateSeverity.INFO,
                title = "体裁判定：${profile.genre.label}（${profile.paragraphs} 段 / 事实密度 ${"%.1f".format(profile.factDensityPer100)}/百字）",
                evidence = if (profile.listDominated) "列表为主体（保留列表形态）" else "句式主体",
                suggestion = when (profile.genre) {
                    com.apex.agent.core.engine.templates.runtime.builtin.ContentProfile.Genre.LIST_BASED ->
                        "要点保持列表形态，逐条对应原列表项"
                    com.apex.agent.core.engine.templates.runtime.builtin.ContentProfile.Genre.STRUCTURED_DOC ->
                        "按原文标题分组组织要点"
                    else -> "要点按重要性排序"
                },
                confidence = 0.85
            )
        )

        // 3) 关键事实（数字类 INFO + 否定类 IMPORTANT——总结最容易丢否定）
        val facts = SummarizeAnalyzer.extractKeyFacts(content)
        val numericFacts = facts.filter { it.kind != KeyFact.Kind.NEGATION }
        if (numericFacts.isNotEmpty()) {
            findings.add(
                TemplateFinding(
                    ruleId = "summary.key-facts",
                    category = TemplateFindingCategory.COMPLETENESS,
                    severity = TemplateSeverity.INFO,
                    title = "关键事实 ${numericFacts.size} 条（必须原样保留数字）",
                    evidence = numericFacts.take(8).joinToString("、") { it.text },
                    suggestion = "数字/百分比/日期逐字保留，不做四舍五入或改写单位",
                    confidence = 1.0
                )
            )
        }
        val negations = facts.filter { it.kind == KeyFact.Kind.NEGATION }
        if (negations.isNotEmpty()) {
            findings.add(
                TemplateFinding(
                    ruleId = "summary.negation-guard",
                    category = TemplateFindingCategory.CORRECTNESS,
                    severity = TemplateSeverity.IMPORTANT,
                    title = "原文含 ${negations.size} 处否定表述（语义不可丢失）",
                    evidence = negations.take(2).joinToString("；") { it.text.take(30) },
                    suggestion = "对应要点必须保留否定词（不/未/禁止…），禁止改写成肯定句",
                    confidence = 1.0
                )
            )
        }

        // 4) 抽取式草稿（每句一个 INFO finding，evidence=原句）
        val draft = SummarizeAnalyzer.draftSummary(content, budget)
        for (sentence in draft.take(MAX_DRAFT_HINTS)) {
            findings.add(
                TemplateFinding(
                    ruleId = "summary.draft-sentence",
                    category = TemplateFindingCategory.STRUCTURE,
                    severity = TemplateSeverity.INFO,
                    title = "候选要点（原句序 ${sentence.ordinal + 1}）",
                    evidence = sentence.text.take(80),
                    suggestion = "核对后改写为要点（保留数字与否定语义）",
                    confidence = sentence.score / 2.0
                )
            )
        }

        var stats = TemplateAnalysisStats.empty()
            .with(TemplateAnalysisStats.KEY_RULE_COUNT, findings.size)
            .with(TemplateAnalysisStats.KEY_ANALYZED_LINES, content.lines().size)
            .with(METRIC_FACTS, numericFacts.size)
            .with(METRIC_NEGATIONS, negations.size)
            .with(METRIC_DRAFT_SENTENCES, draft.size)
            .with(METRIC_BUDGET_MAX, budget.maxChars)

        val hints = mutableListOf<String>()
        hints += "总结视角：$perspective"
        hints += "长度预算：结论 + 要点合计 ${budget.minChars}-${budget.maxChars} 字（不含标题/空白）"
        hints += "体裁：${profile.genre.label}" + (if (profile.listDominated) "（要点保持列表形态）" else "")
        if (numericFacts.isNotEmpty()) {
            hints += "必须保留的事实：" + numericFacts.take(10).joinToString("、") { it.text }
        }
        if (negations.isNotEmpty()) {
            hints += "否定语义保护：${negations.size} 处否定表述的要点必须保留否定词"
        }

        return TemplateAnalysis.of(
            findings = findings,
            stats = stats,
            enrichedHints = hints,
            metadata = buildMap {
                put("genre", profile.genre.name)
                put("budgetMax", budget.maxChars.toString())
                put("factCount", numericFacts.size.toString())
                put("negationCount", negations.size.toString())
            }
        )
    }

    // ═══ VALIDATE ═══

    override fun validateOutput(
        raw: String,
        context: TemplateExecutionContext,
        request: TemplateTurnRequest
    ): TemplateValidationReport {
        val base = super.validateOutput(raw, context, request)
        val content = context.variable("content").orEmpty()
        val budget = SummarizeAnalyzer.parseLengthBudget(context.variableOr("length", "200 字以内"))
        val issues = SummarizeAnalyzer.validateSummary(raw, content, budget)
        val violations = base.violations.toMutableList()
        for (issue in issues) {
            violations.add(
                TemplateContractViolation(
                    kind = when (issue.kind) {
                        SummarizeAnalyzer.SummaryIssue.Kind.MISSING_VERDICT ->
                            TemplateContractViolationKind.MISSING_VERDICT
                        SummarizeAnalyzer.SummaryIssue.Kind.VERDICT_TOO_LONG ->
                            TemplateContractViolationKind.LIST_TOO_LONG
                        SummarizeAnalyzer.SummaryIssue.Kind.TOO_FEW_POINTS ->
                            TemplateContractViolationKind.LIST_TOO_SHORT
                        SummarizeAnalyzer.SummaryIssue.Kind.TOO_MANY_POINTS ->
                            TemplateContractViolationKind.LIST_TOO_LONG
                        SummarizeAnalyzer.SummaryIssue.Kind.FACT_LOST ->
                            TemplateContractViolationKind.PLACEHOLDER_LEAK
                        SummarizeAnalyzer.SummaryIssue.Kind.NEGATION_LOST ->
                            TemplateContractViolationKind.MISSING_VERDICT
                    },
                    section = "要点",
                    detail = "[${issue.kind.label}] ${issue.detail}"
                )
            )
        }
        return TemplateValidationReport(
            violations = violations,
            parsedSections = base.parsedSections,
            orphans = base.orphans
        )
    }

    // ═══ RENDER ═══

    override fun renderReport(
        raw: String,
        context: TemplateExecutionContext,
        request: TemplateTurnRequest
    ): TemplateRenderedReport {
        val base = super.renderReport(raw, context, request)
        val content = context.variable("content").orEmpty()
        val budget = SummarizeAnalyzer.parseLengthBudget(context.variableOr("length", "200 字以内"))
        val issues = SummarizeAnalyzer.validateSummary(raw, content, budget)
        val budgetOk = issues.none { it.kind == SummarizeAnalyzer.SummaryIssue.Kind.VERDICT_TOO_LONG }
        val factCoverage = factCoverageOf(raw, content)
        return base.copy(
            summary = mapOf(
                META_GENRE to (SummarizeAnalyzer.profile(content).genre.label),
                META_BUDGET_OK to budgetOk.toString(),
                META_FACT_COVERAGE to factCoverage.toString(),
                META_BULLET_COUNT to bulletCountOf(raw).toString()
            ),
            residualViolations = base.residualViolations
        )
    }

    private fun factCoverageOf(output: String, source: String): Int {
        val facts = SummarizeAnalyzer.extractKeyFacts(source)
            .filter { it.kind != KeyFact.Kind.NEGATION }
        if (facts.isEmpty()) return 100
        val covered = facts.count { output.contains(it.text) }
        return (covered * 100) / facts.size
    }

    private fun bulletCountOf(raw: String): Int =
        raw.lines().count { BULLET_COUNT_LINE.containsMatchIn(it.trim()) }

    companion object {
        const val TEMPLATE_ID = "builtin_summarize"
        const val MAX_DRAFT_HINTS = 6
        const val METRIC_FACTS = "keyFacts"
        const val METRIC_NEGATIONS = "negations"
        const val METRIC_DRAFT_SENTENCES = "draftSentences"
        const val METRIC_BUDGET_MAX = "budgetMaxChars"
        const val META_GENRE = "genre"
        const val META_BUDGET_OK = "budgetOk"
        const val META_FACT_COVERAGE = "factCoveragePercent"
        const val META_BULLET_COUNT = "bulletCount"

        /** 列表行形态（渲染统计用）。 */
        val BULLET_COUNT_LINE: Regex = Regex("""^(?:[-*+]|\d{1,3}[.)])\s+""")
    }
}
