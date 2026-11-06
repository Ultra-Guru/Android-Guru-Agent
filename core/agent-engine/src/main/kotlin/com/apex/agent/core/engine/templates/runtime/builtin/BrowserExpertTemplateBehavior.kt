package com.apex.agent.core.engine.templates.runtime.builtin

import com.apex.agent.core.engine.templates.runtime.AgentTemplateBehavior
import com.apex.agent.core.engine.templates.runtime.TemplateAnalysis
import com.apex.agent.core.engine.templates.runtime.TemplateAnalysisStats
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
import com.apex.agent.core.engine.templates.runtime.TemplateReportSection
import com.apex.agent.core.engine.templates.runtime.TemplateSectionParser
import com.apex.agent.core.engine.templates.runtime.TemplateSectionRequirement
import com.apex.agent.core.engine.templates.runtime.TemplateSeverity
import com.apex.agent.core.engine.templates.runtime.TemplateTurnRequest
import com.apex.agent.core.engine.templates.runtime.TemplateValidationReport

/**
 * ═══ 内置模板行为 · 浏览器自动化专家（builtin_browser_expert，角色模板）═══
 *
 * 与其余十个「分析管线」模板不同，本模板是**角色模板**（无变量）：
 * 激活即由 app 层授予 browser-automation 会话角色（toolRole 可信来源约束，
 * 见 TemplateStudioViewModel.onTemplateActivatedForRole），行为层职责收窄为：
 *
 * 1. **ANALYZE**：把浏览器工具族的执行纪律作为确定性提示注入附录
 *   （BrowserTask 编排 / CHECKPOINT 决策 / 增量快照 / STALE 校验闭环）；
 * 2. **VALIDATE**：三段式执行计划契约（任务理解 / 步骤编排 / 风险与确认点）
 *   ——角色首轮产出应是可审计的计划，而非直接开跑；
 * 3. **RENDER**：结构化章节 + 机器摘要（计划步骤数 / 确认点数）。
 *
 * 不继承 [com.apex.agent.core.engine.templates.runtime.TemplateBehaviorBase]：
 * 角色模板无输入变量（主输入对齐测试对 base 子类生效，本行为天然豁免）。
 */
class BrowserExpertTemplateBehavior : AgentTemplateBehavior {

    override val behaviorId: String = TEMPLATE_ID

    override fun manifest(): TemplateBehaviorManifest = TemplateBehaviorManifest(
        behaviorId = behaviorId,
        displayName = "浏览器自动化专家",
        summary = "BrowserTask 编排纪律 + 异步导航协议 + 增量快照约定的角色注入",
        supportedPhases = setOf(
            TemplatePhaseKind.PREPARE,
            TemplatePhaseKind.ANALYZE,
            TemplatePhaseKind.COMPOSE,
            TemplatePhaseKind.VALIDATE,
            TemplatePhaseKind.REPAIR,
            TemplatePhaseKind.RENDER
        ),
        features = setOf(TemplateBehaviorFeature.RISK_ROLLBACK),
        outputContract = TemplateOutputContract(
            requiredSections = listOf(
                TemplateSectionRequirement(
                    "任务理解与拆解",
                    aliases = listOf("任务理解", "目标理解"),
                    required = true
                ),
                TemplateSectionRequirement(
                    "BrowserTask 步骤编排",
                    aliases = listOf("步骤编排", "执行步骤编排"),
                    required = true
                ),
                TemplateSectionRequirement(
                    "风险与人工确认点",
                    aliases = listOf("风险与确认点", "人工确认点"),
                    required = true
                )
            )
        ),
        deterministicPreAnalysis = true,
        promptEnrichmentMode = TemplatePromptEnrichmentMode.APPEND_FINDINGS,
        // 目录深度接线锁（TemplateRuntimeEndToEndTest）要求 ≥ 2：
        // ANALYZE 静态纪律事实 + TemplateSectionParser 校验/修复/渲染全链真实接线
        behaviorVersion = 2
    )

    // ═══ PREPARE（角色模板无变量 —— 空壳即可，契约要求永不 null）═══

    override suspend fun prepare(context: TemplateExecutionContext): TemplatePreparedInput =
        TemplatePreparedInput.empty(ROLE_INPUT)

    // ═══ ANALYZE（静态纪律事实：无输入也有产出，附录注入的确定性来源）═══

    override suspend fun analyze(
        context: TemplateExecutionContext,
        prepared: TemplatePreparedInput
    ): TemplateAnalysis = TemplateAnalysis.of(
        findings = listOf(
            TemplateFinding(
                ruleId = ROLE_RULE_ID,
                category = TemplateFindingCategory.STRUCTURE,
                severity = TemplateSeverity.INFO,
                title = "角色激活：browser-automation（可信来源 = 内置模板 toolRole）",
                evidence = "敏感四件套（execute_js / get_cookies / file_upload / clear_data）逐会话弹确认",
                suggestion = "按附录执行纪律使用浏览器工具族；被拒的敏感调用不重试",
                confidence = 1.0
            )
        ),
        stats = TemplateAnalysisStats.empty()
            .with(TemplateAnalysisStats.KEY_RULE_COUNT, 1),
        enrichedHints = listOf(
            "BrowserTask 优先：确定性连续操作一次提交，运行时自主推进、返回压缩结果",
            "CHECKPOINT：selector 多命中时任务暂停返回候选 ref，由你决策后重新提交剩余步骤",
            "异步并行：BrowserNavigateAsync 立即返回句柄，BrowserDocState(mode=wait) 等就绪",
            "增量上下文：整页一次（记 doc_id）；局部 focused(root_selector)、变化 diff(base_doc_id)",
            "动作带 expected_doc_id：STALE_DOCUMENT 说明页面已变，先重新快照再重试",
            "等待纪律：点击/提交后 BrowserWaitFor 或任务内 wait_for，network_idle 有 750ms 静默窗口",
            "SYSTEM_LOCKED = 用户接管中（可等待恢复，不是失败）；接管后重新提交任务"
        ),
        analyzerNotes = listOf("角色模板：无输入变量，纪律事实为静态注入")
    )

    // ═══ COMPOSE（正文 + 确定性分析附录；与 base 同格式保证「附录」锚点）═══

    override fun composePrompt(
        context: TemplateExecutionContext,
        prepared: TemplatePreparedInput,
        analysis: TemplateAnalysis
    ): TemplateTurnRequest {
        val base = context.template.content
        val enrichment = if (context.options.injectAnalysisIntoPrompt) {
            buildString {
                appendLine("──────────")
                appendLine("【确定性分析附录 · 由模板行为运行时自动注入】")
                for (finding in analysis.findings) {
                    appendLine("- ${finding.toPromptLine()}")
                }
                if (analysis.enrichedHints.isNotEmpty()) {
                    appendLine()
                    appendLine("附加情报：")
                    analysis.enrichedHints.forEach { appendLine("- $it") }
                }
                appendLine("统计：${analysis.findings.size} 条规则命中")
                appendLine("要求：首轮先给三段式执行计划（任务理解 / BrowserTask 步骤编排 / 风险与人工确认点），确认后再开跑。")
            }
        } else ""
        val prompt = if (enrichment.isEmpty()) base else "$base\n\n$enrichment"
        return TemplateTurnRequest(
            prompt = prompt,
            behaviorId = behaviorId,
            declaredPhases = manifest().supportedPhases,
            enrichmentNotes = if (enrichment.isEmpty()) emptyList()
            else listOf("注入 ${analysis.findings.size} 条角色纪律命中 + ${analysis.enrichedHints.size} 条附加情报"),
            metadata = mapOf(
                "promptChars" to prompt.length.toString(),
                "behaviorVersion" to manifest().behaviorVersion.toString()
            )
        )
    }

    // ═══ VALIDATE / REPAIR（复用章节解析器的通用机械）═══

    override fun validateOutput(
        raw: String,
        context: TemplateExecutionContext,
        request: TemplateTurnRequest
    ): TemplateValidationReport {
        val contract = manifest().outputContract
        val skeleton = TemplateSectionParser.parse(raw, contract)
        return TemplateSectionParser.validate(
            skeleton,
            contract,
            strict = context.options.strictValidation
        )
    }

    override fun repairOutput(
        raw: String,
        report: TemplateValidationReport,
        context: TemplateExecutionContext
    ): String {
        val (repaired, notes) = TemplateSectionParser.repair(raw, report)
        if (notes.isNotEmpty()) {
            context.log("repaired: ${notes.joinToString("; ")}")
        }
        return repaired
    }

    // ═══ RENDER（结构化章节 + 机器摘要）═══

    override fun renderReport(
        raw: String,
        context: TemplateExecutionContext,
        request: TemplateTurnRequest
    ): TemplateRenderedReport {
        val contract = manifest().outputContract
        val skeleton = TemplateSectionParser.parse(raw, contract)
        val report = TemplateRenderedReport(
            finalText = raw.trim(),
            sections = skeleton.sections.mapIndexed { index, section ->
                TemplateReportSection(
                    title = section.headerText.ifBlank { "段落 ${index + 1}" },
                    kind = TemplateReportSection.Kind.BODY,
                    lines = section.bodyLines,
                    orderKey = index
                )
            },
            summary = mapOf(
                META_PLAN_SECTIONS to skeleton.sections.size.toString(),
                META_HINT_COUNT to HINT_COUNT.toString(),
                KEY_FINDING_COUNT to "1"
            ),
            residualViolations = emptyList(),
            qualityScore = if (skeleton.sections.isEmpty()) 0.5 else 1.0
        )
        return report
    }

    companion object {
        const val TEMPLATE_ID = "builtin_browser_expert"
        const val ROLE_INPUT = "role"
        const val ROLE_RULE_ID = "role.browser-automation-activation"
        const val META_PLAN_SECTIONS = "planSectionCount"
        const val META_HINT_COUNT = "disciplineHintCount"
        const val KEY_FINDING_COUNT = "findingCount"
        const val HINT_COUNT = 7
    }
}
