package com.apex.agent.core.engine.templates.runtime.builtin

import com.apex.agent.core.engine.templates.runtime.TemplateAnalysis
import com.apex.agent.core.engine.templates.runtime.TemplateAnalysisStats
import com.apex.agent.core.engine.templates.runtime.TemplateBehaviorBase
import com.apex.agent.core.engine.templates.runtime.TemplateBehaviorFeature
import com.apex.agent.core.engine.templates.runtime.TemplateBehaviorManifest
import com.apex.agent.core.engine.templates.runtime.TemplateExecutionContext
import com.apex.agent.core.engine.templates.runtime.TemplateOutputContract
import com.apex.agent.core.engine.templates.runtime.TemplatePhaseKind
import com.apex.agent.core.engine.templates.runtime.TemplatePreparedInput
import com.apex.agent.core.engine.templates.runtime.TemplatePromptEnrichmentMode
import com.apex.agent.core.engine.templates.runtime.TemplateRenderedReport
import com.apex.agent.core.engine.templates.runtime.TemplateSectionRequirement

/**
 * ═══ 内置模板行为 · 技术文档（builtin_tech_doc，PR 13）═══
 *
 * 「技术文档」模板的代码化行为：
 * 1. **ANALYZE**：受众归一 → 五段式大纲（受众微调）→ 代码素材提取 →
 *    主题触发的追加章节 → 可读性预估；
 * 2. **VALIDATE**：五必需章节契约（概述/背景/核心概念/使用示例/FAQ），
 *    使用示例章节必须含围栏代码块；
 * 3. **RENDER**：机器摘要（受众/大纲章数/素材数/追加章节数）。
 */
open class TechDocTemplateBehavior : TemplateBehaviorBase() {

    override val behaviorId: String = TEMPLATE_ID
    override val primaryInputName: String = "topic"
    override val secondaryInputNames: List<String> = listOf("audience", "code")

    override fun manifest(): TemplateBehaviorManifest = TemplateBehaviorManifest(
        behaviorId = behaviorId,
        displayName = "技术文档",
        summary = "受众归一 + 五段式大纲 + 代码素材提取 + 主题追加章节",
        supportedPhases = setOf(
            TemplatePhaseKind.PREPARE,
            TemplatePhaseKind.ANALYZE,
            TemplatePhaseKind.COMPOSE,
            TemplatePhaseKind.VALIDATE,
            TemplatePhaseKind.REPAIR,
            TemplatePhaseKind.RENDER
        ),
        features = setOf(TemplateBehaviorFeature.INPUT_QUALITY),
        outputContract = TemplateOutputContract(
            requiredSections = listOf(
                TemplateSectionRequirement("一句话概述与适用场景", aliases = listOf("概述与适用场景", "概述"), required = true),
                TemplateSectionRequirement("背景与动机", aliases = listOf("背景"), required = true),
                TemplateSectionRequirement("核心概念与工作原理", aliases = listOf("核心概念", "工作原理"), required = true),
                TemplateSectionRequirement("使用示例", aliases = listOf("示例", "使用范例"), required = true),
                TemplateSectionRequirement("常见问题与注意事项", aliases = listOf("常见问题", "FAQ"), required = true)
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
        val topic = prepared.primaryInput
        if (topic.isBlank() && prepared.input("code").isBlank()) {
            return TemplateAnalysis.of(
                findings = emptyList(),
                analyzerNotes = listOf("主题与参考代码均为空，跳过确定性分析")
            )
        }
        val audienceInput = context.variableOr("audience", "")
        val code = prepared.input("code")
        val plan = TechDocPlanner.plan(topic, audienceInput, code)
        val findings = TechDocPlanner.findingsFrom(plan, topic).toMutableList()

        var stats = TemplateAnalysisStats.empty()
            .with(TemplateAnalysisStats.KEY_RULE_COUNT, findings.size)
            .with(TemplateAnalysisStats.KEY_ANALYZED_LINES, (topic + code).lines().size)
            .with(METRIC_OUTLINE_SECTIONS, plan.outline.size)
            .with(METRIC_SNIPPETS, plan.snippets.size)
            .with(METRIC_EXTRA_SECTIONS, plan.extraSections.size)

        val hints = mutableListOf<String>()
        hints += "文档主题：${topic.ifBlank { "（待归纳，先给一句话概述）" }}"
        hints += "受众：${plan.audience.label}（语言深度与前置知识假设按此校准）"
        hints += "大纲基线：" + plan.outline.joinToString(" → ") { it.title }
        if (plan.snippets.isNotEmpty()) {
            hints += "代码素材：${plan.snippets.size} 段（使用示例章节优先真实素材，保证可运行）"
        }
        for (extra in plan.extraSections) {
            hints += "主题强相关追加章节：$extra"
        }
        for (note in plan.readabilityNotes) {
            hints += "写作纪律：$note"
        }

        return TemplateAnalysis.of(
            findings = findings,
            stats = stats,
            enrichedHints = hints,
            metadata = buildMap {
                put("audience", plan.audience.name)
                put("outlineSections", plan.outline.size.toString())
                put("snippetCount", plan.snippets.size.toString())
                put("extraSectionCount", plan.extraSections.size.toString())
            }
        )
    }

    override fun validateOutput(
        raw: String,
        context: TemplateExecutionContext,
        request: com.apex.agent.core.engine.templates.runtime.TemplateTurnRequest
    ): com.apex.agent.core.engine.templates.runtime.TemplateValidationReport {
        val base = super.validateOutput(raw, context, request)
        val violations = base.violations.toMutableList()
        // 专项：使用示例章节必须含围栏代码块
        val contract = manifest().outputContract
        val skeleton = com.apex.agent.core.engine.templates.runtime.TemplateSectionParser.parse(raw, contract)
        val exampleSection = skeleton.sections.firstOrNull {
            contract.requiredSections[3].matchesHeader(it.headerText)
        }
        if (exampleSection != null && !raw.contains("```")) {
            violations.add(
                com.apex.agent.core.engine.templates.runtime.TemplateContractViolation(
                    kind = com.apex.agent.core.engine.templates.runtime.TemplateContractViolationKind.SECTION_EMPTY,
                    section = "使用示例",
                    detail = "使用示例章节必须包含围栏代码块（可运行示例）"
                )
            )
        }
        return com.apex.agent.core.engine.templates.runtime.TemplateValidationReport(
            violations = violations,
            parsedSections = base.parsedSections,
            orphans = base.orphans
        )
    }

    override fun renderReport(
        raw: String,
        context: TemplateExecutionContext,
        request: com.apex.agent.core.engine.templates.runtime.TemplateTurnRequest
    ): TemplateRenderedReport {
        val base = super.renderReport(raw, context, request)
        val plan = TechDocPlanner.plan(
            context.variable("topic").orEmpty(),
            context.variableOr("audience", ""),
            context.variableOr("code", "")
        )
        return base.copy(
            summary = mapOf(
                META_AUDIENCE to plan.audience.label,
                META_OUTLINE_SECTIONS to plan.outline.size.toString(),
                META_SNIPPET_COUNT to plan.snippets.size.toString(),
                META_EXTRA_SECTIONS to plan.extraSections.size.toString()
            )
        )
    }

    companion object {
        const val TEMPLATE_ID = "builtin_tech_doc"
        const val METRIC_OUTLINE_SECTIONS = "outlineSections"
        const val METRIC_SNIPPETS = "snippets"
        const val METRIC_EXTRA_SECTIONS = "extraSections"
        const val META_AUDIENCE = "audience"
        const val META_OUTLINE_SECTIONS = "outlineSectionCount"
        const val META_SNIPPET_COUNT = "snippetCount"
        const val META_EXTRA_SECTIONS = "extraSectionCount"
    }
}
