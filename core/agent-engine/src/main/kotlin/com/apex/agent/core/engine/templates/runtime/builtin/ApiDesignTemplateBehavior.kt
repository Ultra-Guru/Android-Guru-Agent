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
import com.apex.agent.core.engine.templates.runtime.TemplateSectionRequirement

/**
 * ═══ 内置模板行为 · API 设计（builtin_api_design，PR 11）═══
 *
 * 「API 设计」模板的代码化行为：
 * 1. **ANALYZE**：需求 → 资源抽取（中英双语 + 单复数归一）→ 操作推断
 *    （动词表 + REST/GraphQL 双建议）→ 字段类型推断（12 类语义表）；
 *    REST 约定库（方法语义 + 状态码税则 + 横切模板）随附录注入；
 * 2. **VALIDATE**：四必需章节（资源与端点清单 / 请求响应结构 /
 *    错误码设计 / 横切关注点说明）；
 * 3. **RENDER**：机器摘要（资源数/操作数/风格）。
 */
open class ApiDesignTemplateBehavior : TemplateBehaviorBase() {

    override val behaviorId: String = TEMPLATE_ID
    override val primaryInputName: String = "requirement"
    override val secondaryInputNames: List<String> = listOf("style")

    override fun manifest(): TemplateBehaviorManifest = TemplateBehaviorManifest(
        behaviorId = behaviorId,
        displayName = "API 设计",
        summary = "资源抽取 + 操作推断 + 字段类型推断 + REST/GraphQL 双约定库",
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
                TemplateSectionRequirement("资源与端点清单", aliases = listOf("端点清单", "资源与端点"), required = true),
                TemplateSectionRequirement("请求与响应结构", aliases = listOf("请求响应结构", "数据结构"), required = true),
                TemplateSectionRequirement("错误码设计", aliases = listOf("错误码"), required = true),
                TemplateSectionRequirement("横切关注点说明", aliases = listOf("横切关注点"), required = true)
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
        val requirement = prepared.primaryInput
        if (requirement.isBlank()) {
            return TemplateAnalysis.of(
                findings = emptyList(),
                analyzerNotes = listOf("需求描述为空，跳过确定性分析")
            )
        }
        val style = context.variableOr("style", "RESTful")
        val analysis = ApiDesignAnalyzer.analyze(requirement, style)
        val findings = ApiDesignAnalyzer.findingsFrom(analysis).toMutableList()

        // 风格适配注记（INFO finding：约定库切换）
        val isGraphQl = style.contains("graphql", true) || style.contains("图", true)
        findings.add(
            com.apex.agent.core.engine.templates.runtime.TemplateFinding(
                ruleId = "api.style",
                category = com.apex.agent.core.engine.templates.runtime.TemplateFindingCategory.STRUCTURE,
                severity = com.apex.agent.core.engine.templates.runtime.TemplateSeverity.INFO,
                title = "API 风格：$style" + if (isGraphQl) "（Query/Mutation 形态）" else "（REST 形态）",
                evidence = if (isGraphQl) "按 GraphQL 约定执行" else "按 REST 约定执行",
                suggestion = if (isGraphQl) "端点清单输出为 Query/Mutation 清单" else "方法语义遵循 REST 税则表",
                confidence = 1.0
            )
        )

        var stats = TemplateAnalysisStats.empty()
            .with(TemplateAnalysisStats.KEY_RULE_COUNT, findings.size)
            .with(TemplateAnalysisStats.KEY_ANALYZED_LINES, requirement.lines().size)
            .with(METRIC_RESOURCES, analysis.resources.size)
            .with(METRIC_OPERATIONS, analysis.operations.size)
            .with(METRIC_FIELDS, analysis.fields.size)

        val hints = mutableListOf<String>()
        hints += "API 风格：$style"
        if (analysis.resources.isNotEmpty()) {
            hints += "资源基线：" + analysis.resources.joinToString("、") { it.name }
        }
        if (analysis.operations.isNotEmpty()) {
            hints += "操作基线：" + analysis.operations.joinToString("；") { "${it.action} ${it.resource ?: ""}".trim() }
        }
        if (isGraphQl) {
            hints += "GraphQL 横切要点：" + ApiDesignAnalyzer.GRAPHQL_NOTES.joinToString("；")
        } else {
            hints += "REST 方法语义税则：" + ApiDesignAnalyzer.REST_METHOD_SEMANTICS.joinToString("；") { "${it.first}=${it.second}" }
            hints += "状态码税则（精选）：" + ApiDesignAnalyzer.STATUS_CODE_TAXONOMY.take(6).joinToString("；") { "${it.first} ${it.second}" }
        }
        hints += "横切关注点基线：" + ApiDesignAnalyzer.CROSS_CUTTING_TEMPLATES.take(4).joinToString("；")

        return TemplateAnalysis.of(
            findings = findings,
            stats = stats,
            enrichedHints = hints,
            metadata = buildMap {
                put("style", style)
                put("resourceCount", analysis.resources.size.toString())
                put("operationCount", analysis.operations.size.toString())
                put("isGraphQl", isGraphQl.toString())
            },
            analyzerNotes = analysis.notes
        )
    }

    override fun renderReport(
        raw: String,
        context: TemplateExecutionContext,
        request: com.apex.agent.core.engine.templates.runtime.TemplateTurnRequest
    ): com.apex.agent.core.engine.templates.runtime.TemplateRenderedReport {
        val base = super.renderReport(raw, context, request)
        val analysis = ApiDesignAnalyzer.analyze(
            context.variable("requirement").orEmpty(),
            context.variableOr("style", "RESTful")
        )
        return base.copy(
            summary = mapOf(
                META_RESOURCE_COUNT to analysis.resources.size.toString(),
                META_OPERATION_COUNT to analysis.operations.size.toString(),
                META_STYLE to context.variableOr("style", "RESTful")
            )
        )
    }

    companion object {
        const val TEMPLATE_ID = "builtin_api_design"
        const val METRIC_RESOURCES = "resources"
        const val METRIC_OPERATIONS = "operations"
        const val METRIC_FIELDS = "fields"
        const val META_RESOURCE_COUNT = "resourceCount"
        const val META_OPERATION_COUNT = "operationCount"
        const val META_STYLE = "style"
    }
}
