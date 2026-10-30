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

/**
 * ═══ 内置模板行为 · 正则解释（builtin_regex_explain，PR 14）═══
 *
 * 「正则解释」模板的代码化行为：
 * 1. **ANALYZE**：真解析器（AST）→ 逐段中文解释 → 匹配/不匹配样本
 *    推导 → 回溯风险分析（嵌套量词/相邻无界量词/交替重叠）→ 简化建议；
 * 2. **VALIDATE**：四必需章节（整体作用 / 逐段拆解 / 匹配与不匹配示例 /
 *    性能陷阱与简化建议）；
 * 3. **RENDER**：机器摘要（解析成功/段数/风险数/最高风险/捕获组数）。
 */
open class RegexExplainTemplateBehavior : TemplateBehaviorBase() {

    override val behaviorId: String = TEMPLATE_ID
    override val primaryInputName: String = "pattern"
    override val secondaryInputNames: List<String> = listOf("sample")

    override fun manifest(): TemplateBehaviorManifest = TemplateBehaviorManifest(
        behaviorId = behaviorId,
        displayName = "正则解释",
        summary = "真解析器（AST）逐段解释 + 样本推导 + 灾难性回溯风险分析",
        supportedPhases = setOf(
            TemplatePhaseKind.PREPARE,
            TemplatePhaseKind.ANALYZE,
            TemplatePhaseKind.COMPOSE,
            TemplatePhaseKind.VALIDATE,
            TemplatePhaseKind.REPAIR,
            TemplatePhaseKind.RENDER
        ),
        features = setOf(TemplateBehaviorFeature.REGEX_AST),
        outputContract = TemplateOutputContract(
            requiredSections = listOf(
                TemplateSectionRequirement("整体作用", required = true),
                TemplateSectionRequirement("逐段拆解", aliases = listOf("逐段解析"), required = true),
                TemplateSectionRequirement(
                    "匹配与不匹配示例",
                    aliases = listOf("示例", "匹配示例"),
                    listItemCountRange = 2..10,
                    required = true
                ),
                TemplateSectionRequirement("性能陷阱与简化建议", aliases = listOf("性能陷阱", "简化建议"), required = true)
            )
        ),
        deterministicPreAnalysis = true,
        promptEnrichmentMode = TemplatePromptEnrichmentMode.APPEND_FINDINGS,
        behaviorVersion = 1
    )

    override suspend fun analyze(
        context: TemplateExecutionContext,
        prepared: TemplatePreparedInput
    ): TemplateAnalysis {
        val pattern = prepared.primaryInput
        if (pattern.isBlank()) {
            return TemplateAnalysis.of(
                findings = emptyList(),
                analyzerNotes = listOf("正则为空，跳过确定性分析")
            )
        }
        val userSample = prepared.input("sample")
        val findings = mutableListOf<TemplateFinding>()
        val result = RegexParser.parse(pattern)

        if (result.error != null) {
            findings.add(
                TemplateFinding(
                    ruleId = "regex.parse-error",
                    category = TemplateFindingCategory.CORRECTNESS,
                    severity = TemplateSeverity.BLOCKER,
                    title = "正则解析失败：${result.error}",
                    evidence = pattern.take(60),
                    suggestion = "先修正语法，再谈解释与性能；输出中需指出错误位置与修复方向",
                    confidence = 1.0
                )
            )
            return TemplateAnalysis.of(
                findings = findings,
                stats = TemplateAnalysisStats.empty()
                    .with(TemplateAnalysisStats.KEY_RULE_COUNT, findings.size),
                enrichedHints = listOf("正则存在语法错误，解释输出以错误定位为主"),
                metadata = mapOf("parseOk" to "false")
            )
        }

        val root = result.root!!
        val segments = RegexParser.explain(root)
        val samples = RegexParser.generateSamples(root)
        val risks = RegexParser.analyzeRisks(root)
        val simplifications = RegexParser.simplifications(pattern)

        // 1) 结构概览（INFO）
        findings.add(
            TemplateFinding(
                ruleId = "regex.structure",
                category = TemplateFindingCategory.STRUCTURE,
                severity = TemplateSeverity.INFO,
                title = "解析成功：${segments.size} 个语义段 / ${result.groupCount} 个捕获组",
                evidence = segments.take(6).joinToString("；") { "${it.fragment}=${it.explanation}" },
                suggestion = "逐段拆解按解析顺序展开",
                confidence = 1.0
            )
        )

        // 2) 样本推导（INFO：确定性样本供 LLM 核对）
        if (samples != null) {
            findings.add(
                TemplateFinding(
                    ruleId = "regex.samples",
                    category = TemplateFindingCategory.STRUCTURE,
                    severity = TemplateSeverity.INFO,
                    title = "推导样本：应匹配「${samples.first.take(20)}」/ 不应匹配「${samples.second.take(20)}」",
                    evidence = "样本由 AST 推导（首分支/量词下限/类代表字符）",
                    suggestion = "示例章节以此为基线扩展边界样本" +
                        (if (userSample.isNotBlank()) "；用户提供了样本文本：${userSample.take(40)}，验证其归类" else ""),
                    confidence = 0.9
                )
            )
        }

        // 3) 风险（按等级升级严重度）
        for (risk in risks) {
            findings.add(
                TemplateFinding(
                    ruleId = "regex.risk",
                    category = TemplateFindingCategory.PERFORMANCE,
                    severity = when (risk.level) {
                        RegexRiskLevel.CATASTROPHIC -> TemplateSeverity.BLOCKER
                        RegexRiskLevel.HIGH -> TemplateSeverity.IMPORTANT
                        RegexRiskLevel.MODERATE -> TemplateSeverity.MINOR
                        RegexRiskLevel.LOW -> TemplateSeverity.INFO
                    },
                    title = "[${risk.level.label}] ${risk.pattern}",
                    evidence = risk.detail,
                    suggestion = risk.suggestion,
                    confidence = 0.9
                )
            )
        }

        // 4) 简化建议（MINOR）
        for (suggestion in simplifications) {
            findings.add(
                TemplateFinding(
                    ruleId = "regex.simplification",
                    category = TemplateFindingCategory.MAINTAINABILITY,
                    severity = TemplateSeverity.MINOR,
                    title = suggestion,
                    suggestion = "性能陷阱章节给出等价改写",
                    confidence = 1.0
                )
            )
        }

        var stats = TemplateAnalysisStats.empty()
            .with(TemplateAnalysisStats.KEY_RULE_COUNT, findings.size)
            .with(METRIC_SEGMENTS, segments.size)
            .with(METRIC_GROUPS, result.groupCount)
            .with(METRIC_RISKS, risks.size)

        val hints = mutableListOf<String>()
        hints += "解析状态：成功（${segments.size} 个语义段，${result.groupCount} 个捕获组）"
        samples?.let { hints += "AST 推导样本：应匹配「${it.first.take(30)}」，不应匹配「${it.second.take(30)}」" }
        if (risks.isNotEmpty()) {
            hints += "回溯风险：${risks.size} 处（最高 ${risks.first().level.label}）——性能陷阱章节必须逐条回应"
        } else {
            hints += "回溯风险：未检出结构性风险（仍需说明常规回溯行为）"
        }
        if (simplifications.isNotEmpty()) {
            hints += "简化建议：" + simplifications.joinToString("；")
        }

        return TemplateAnalysis.of(
            findings = findings,
            stats = stats,
            enrichedHints = hints,
            metadata = buildMap {
                put("parseOk", "true")
                put("segmentCount", segments.size.toString())
                put("groupCount", result.groupCount.toString())
                put("riskCount", risks.size.toString())
                put("topRisk", risks.firstOrNull()?.level?.label ?: "无")
            }
        )
    }

    override fun renderReport(
        raw: String,
        context: TemplateExecutionContext,
        request: com.apex.agent.core.engine.templates.runtime.TemplateTurnRequest
    ): TemplateRenderedReport {
        val base = super.renderReport(raw, context, request)
        val pattern = context.variable("pattern").orEmpty()
        val result = RegexParser.parse(pattern)
        val risks = result.root?.let { RegexParser.analyzeRisks(it) } ?: emptyList()
        val topRisk = when (risks.firstOrNull()?.level) {
            RegexRiskLevel.CATASTROPHIC -> "灾难性回溯"
            RegexRiskLevel.HIGH -> "高回溯风险"
            RegexRiskLevel.MODERATE -> "中等风险"
            else -> if (result.error != null) "解析失败" else "无"
        }
        return base.copy(
            summary = mapOf(
                META_PARSE_OK to (result.error == null).toString(),
                META_SEGMENT_COUNT to (result.root?.let { RegexParser.explain(it).size } ?: 0).toString(),
                META_GROUP_COUNT to result.groupCount.toString(),
                META_TOP_RISK to topRisk
            )
        )
    }

    companion object {
        const val TEMPLATE_ID = "builtin_regex_explain"
        const val METRIC_SEGMENTS = "segments"
        const val METRIC_GROUPS = "groups"
        const val METRIC_RISKS = "risks"
        const val META_PARSE_OK = "parseOk"
        const val META_SEGMENT_COUNT = "segmentCount"
        const val META_GROUP_COUNT = "groupCount"
        const val META_TOP_RISK = "topRisk"
    }
}
