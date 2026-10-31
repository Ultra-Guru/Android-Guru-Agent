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
 *    REST 约定库（方法语义 + 状态码税则 + 横切模板）随附录注入
 *    + 约定库（PR 18：端点命名规范校验 / 状态码建议 / RFC 7807 错误模型
 *    / 横切必查清单 / OpenAPI 3.1 骨架生成 / GraphQL 对照）；
 * 2. **VALIDATE**：四必需章节（资源与端点清单 / 请求响应结构 /
 *    错误码设计 / 横切关注点说明）+ 命名规范门禁（PR 18：回包端点
 *    路径逐条 lint，动词入径/单数名词/嵌套过深 = 可修复违规）；
 * 3. **RENDER**：机器摘要（资源数/操作数/风格/命名违规数）+
 *    OpenAPI 骨架章节。
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
        behaviorVersion = 2
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

        // PR 18：约定库注入（状态码建议 + 横切必查清单 + 命名基线）
        for (operation in analysis.operations.distinctBy { it.action }.take(MAX_STATUS_FINDINGS)) {
            val advice = ApiDesignConventionLibrary.statusAdviceFor(operation.action) ?: continue
            findings.add(
                com.apex.agent.core.engine.templates.runtime.TemplateFinding(
                    ruleId = "api.status-advice",
                    category = com.apex.agent.core.engine.templates.runtime.TemplateFindingCategory.CONSISTENCY,
                    severity = com.apex.agent.core.engine.templates.runtime.TemplateSeverity.INFO,
                    title = "状态码税则（${advice.action}）：成功 ${advice.success}",
                    evidence = advice.errors.joinToString("；") + "｜反模式：" + advice.antiPattern,
                    suggestion = "端点清单中每个操作的状态码按此税则对齐",
                    confidence = 1.0
                )
            )
        }
        val requiredItems = ApiDesignConventionLibrary.requiredChecklist()
        findings.add(
            com.apex.agent.core.engine.templates.runtime.TemplateFinding(
                ruleId = "api.cross-cutting-required",
                category = com.apex.agent.core.engine.templates.runtime.TemplateFindingCategory.COMPLETENESS,
                severity = com.apex.agent.core.engine.templates.runtime.TemplateSeverity.INFO,
                title = "横切必查清单 ${requiredItems.size} 项（分页/过滤/鉴权/幂等/版本化）",
                evidence = requiredItems.joinToString("；") { "${it.name}：${it.convention}" },
                suggestion = "横切关注点章节必须逐项回答（含陷阱规避）",
                confidence = 1.0
            )
        )
        // 需求文本里若已含端点形态，先行 lint（用户草稿反馈环）
        val draftEndpoints = ApiDesignConventionLibrary.parseEndpoints(requirement)
        if (draftEndpoints.isNotEmpty()) {
            val draftIssues = ApiDesignConventionLibrary.lintNaming(draftEndpoints)
            for (issue in draftIssues.take(MAX_NAMING_FINDINGS)) {
                findings.add(
                    com.apex.agent.core.engine.templates.runtime.TemplateFinding(
                        ruleId = "api.naming-lint",
                        category = com.apex.agent.core.engine.templates.runtime.TemplateFindingCategory.STYLE,
                        severity = com.apex.agent.core.engine.templates.runtime.TemplateSeverity.MINOR,
                        title = "需求内端点命名：[${issue.kind.label}] ${issue.endpoint}",
                        evidence = issue.detail,
                        suggestion = "修复建议：${issue.fix}",
                        confidence = 1.0
                    )
                )
            }
        }

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
        hints += "横切必查（PR 18）：" + requiredItems.joinToString("；") { "${it.name}（${it.pitfall.take(18)}…）" }
        if (!isGraphQl) {
            hints += "错误模型基线（RFC 7807 problem+json）：type/title/status/detail/instance 五字段，业务错误码与 HTTP 一一映射"
        }
        if (analysis.resources.isNotEmpty() && !isGraphQl) {
            hints += "OpenAPI 骨架已生成（回包渲染区可直接取用）：paths 按 " +
                analysis.operations.take(3).joinToString("；") { "${it.action} ${it.resource ?: ""}".trim() }
        }

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

    // ═══ VALIDATE（命名规范门禁）═══

    override fun validateOutput(
        raw: String,
        context: TemplateExecutionContext,
        request: com.apex.agent.core.engine.templates.runtime.TemplateTurnRequest
    ): com.apex.agent.core.engine.templates.runtime.TemplateValidationReport {
        val base = super.validateOutput(raw, context, request)
        val violations = base.violations.toMutableList()
        // 专项（PR 18）：回包端点清单逐条命名 lint
        val endpoints = ApiDesignConventionLibrary.parseEndpoints(raw)
        for (issue in ApiDesignConventionLibrary.lintNaming(endpoints).take(MAX_NAMING_FINDINGS)) {
            violations.add(
                com.apex.agent.core.engine.templates.runtime.TemplateContractViolation(
                    kind = com.apex.agent.core.engine.templates.runtime.TemplateContractViolationKind.BAD_SEVERITY_MARK,
                    section = "资源与端点清单",
                    detail = "[${issue.kind.label}] ${issue.detail}",
                    evidence = issue.endpoint.take(50)
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
    ): com.apex.agent.core.engine.templates.runtime.TemplateRenderedReport {
        val base = super.renderReport(raw, context, request)
        val analysis = ApiDesignAnalyzer.analyze(
            context.variable("requirement").orEmpty(),
            context.variableOr("style", "RESTful")
        )
        val isGraphQlStyle = context.variableOr("style", "RESTful").contains("graphql", true)
        val namingIssues = ApiDesignConventionLibrary.lintNaming(
            ApiDesignConventionLibrary.parseEndpoints(raw)
        )
        val openApiSection = if (analysis.resources.isNotEmpty() && !isGraphQlStyle) {
            listOf(
                com.apex.agent.core.engine.templates.runtime.TemplateReportSection(
                    title = "OpenAPI 骨架（代码侧生成）",
                    kind = com.apex.agent.core.engine.templates.runtime.TemplateReportSection.Kind.BODY,
                    lines = ApiDesignConventionLibrary.openApiSkeleton(analysis).lines().take(MAX_SKELETON_LINES),
                    orderKey = -1
                )
            )
        } else {
            emptyList()
        }
        return base.copy(
            sections = (openApiSection + base.sections).sortedBy { it.orderKey },
            summary = mapOf(
                META_RESOURCE_COUNT to analysis.resources.size.toString(),
                META_OPERATION_COUNT to analysis.operations.size.toString(),
                META_STYLE to context.variableOr("style", "RESTful"),
                META_NAMING_ISSUES to namingIssues.size.toString()
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
        const val META_NAMING_ISSUES = "namingIssueCount"
        const val MAX_STATUS_FINDINGS = 4
        const val MAX_NAMING_FINDINGS = 6
        const val MAX_SKELETON_LINES = 40
    }
}
