package com.apex.agent.core.engine.templates.runtime.builtin

import com.apex.agent.core.engine.templates.runtime.TemplateAnalysis
import com.apex.agent.core.engine.templates.runtime.TemplateAnalysisStats
import com.apex.agent.core.engine.templates.runtime.TemplateBehaviorBase
import com.apex.agent.core.engine.templates.runtime.TemplateBehaviorFeature
import com.apex.agent.core.engine.templates.runtime.TemplateBehaviorManifest
import com.apex.agent.core.engine.templates.runtime.TemplateExecutionContext
import com.apex.agent.core.engine.templates.runtime.TemplateFindingCategory
import com.apex.agent.core.engine.templates.runtime.TemplateOutputContract
import com.apex.agent.core.engine.templates.runtime.TemplatePhaseKind
import com.apex.agent.core.engine.templates.runtime.TemplatePreparedInput
import com.apex.agent.core.engine.templates.runtime.TemplatePromptEnrichmentMode
import com.apex.agent.core.engine.templates.runtime.TemplateRenderedReport
import com.apex.agent.core.engine.templates.runtime.TemplateSectionRequirement
import com.apex.agent.core.engine.templates.runtime.TemplateTurnRequest
import com.apex.agent.core.engine.templates.runtime.TemplateLanguageDetector

/**
 * ═══ 内置模板行为 · 测试用例（builtin_test_cases，PR 12）═══
 *
 * 「测试用例」模板的代码化行为：
 * 1. **ANALYZE**：函数签名提取（复用函数探测）→ 类型等价类表 →
 *    边界值推导（数字/字符串/集合/浮点特值/空安全）→ 测试矩阵骨架；
 *    框架约定库（JUnit4/5、Kotest、pytest、Jest、go test）随附录注入；
 * 2. **VALIDATE**：三必需章节（等价类与边界条件 / 覆盖矩阵 / 测试代码），
 *    测试代码章节必须含围栏代码块；
 * 3. **RENDER**：机器摘要（函数数/参数数/等价类数/用例骨架数）。
 */
open class TestCasesTemplateBehavior : TemplateBehaviorBase() {

    override val behaviorId: String = TEMPLATE_ID
    override val primaryInputName: String = "code"
    override val secondaryInputNames: List<String> = listOf("framework")

    override fun manifest(): TemplateBehaviorManifest = TemplateBehaviorManifest(
        behaviorId = behaviorId,
        displayName = "测试用例",
        summary = "签名提取 → 等价类与边界推导 → 测试矩阵骨架，框架约定随行",
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
            TemplateBehaviorFeature.BOUNDARY_DERIVATION
        ),
        outputContract = TemplateOutputContract(
            requiredSections = listOf(
                TemplateSectionRequirement("等价类与边界条件", aliases = listOf("等价类", "边界条件"), required = true),
                TemplateSectionRequirement("覆盖矩阵", aliases = listOf("测试矩阵"), listItemCountRange = 1..16, required = true),
                TemplateSectionRequirement("测试代码", required = true)
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
        val code = prepared.primaryInput
        if (code.isBlank()) {
            return TemplateAnalysis.of(
                findings = emptyList(),
                analyzerNotes = listOf("被测代码为空，跳过确定性分析")
            )
        }
        val framework = context.variableOr("framework", "JUnit")
        val language = TemplateLanguageDetector.detect(code).takeIf { it.isConfident }?.detected
        val analysis = TestCasesAnalyzer.analyze(code, language, framework)
        val findings = TestCasesAnalyzer.findingsFrom(analysis).toMutableList()

        if (language == null) {
            findings.add(
                com.apex.agent.core.engine.templates.runtime.TemplateFinding(
                    ruleId = "test.language-unknown",
                    category = TemplateFindingCategory.COMPLETENESS,
                    severity = com.apex.agent.core.engine.templates.runtime.TemplateSeverity.MINOR,
                    title = "语言未高置信识别，等价类按类型通用表推导",
                    suggestion = "以代码实际语言复核类型等价类",
                    confidence = 1.0
                )
            )
        }

        var stats = TemplateAnalysisStats.empty()
            .with(TemplateAnalysisStats.KEY_RULE_COUNT, findings.size)
            .with(TemplateAnalysisStats.KEY_ANALYZED_LINES, code.lines().size)
            .with(METRIC_FUNCTIONS, analysis.functions.size)
            .with(METRIC_PARAMS, analysis.functions.sumOf { it.params.size })
            .with(METRIC_CLASSES, analysis.equivalenceClasses.size)
            .with(METRIC_SKELETONS, analysis.skeletons.size)

        val hints = mutableListOf<String>()
        hints += "测试框架：$framework"
        hints += "框架约定：" + TestCasesAnalyzer.frameworkConventions(framework).joinToString("；")
        if (analysis.functions.isNotEmpty()) {
            hints += "被测函数基线：" + analysis.functions.joinToString("、") { it.name }
            hints += "等价类总数 ${analysis.equivalenceClasses.size}（正常/边界/异常三分），用例骨架 ${analysis.skeletons.size} 条（已按函数分组）"
        }
        for (note in analysis.notes) hints += note

        return TemplateAnalysis.of(
            findings = findings,
            stats = stats,
            detectedLanguage = language,
            enrichedHints = hints,
            metadata = buildMap {
                put("functionCount", analysis.functions.size.toString())
                put("paramCount", analysis.functions.sumOf { it.params.size }.toString())
                put("skeletonCount", analysis.skeletons.size.toString())
                put("framework", framework)
            },
            analyzerNotes = if (analysis.notes.isEmpty()) emptyList() else analysis.notes
        )
    }

    override fun validateOutput(
        raw: String,
        context: TemplateExecutionContext,
        request: TemplateTurnRequest
    ): com.apex.agent.core.engine.templates.runtime.TemplateValidationReport {
        val base = super.validateOutput(raw, context, request)
        val violations = base.violations.toMutableList()
        // 专项：测试代码章节必须含围栏代码块
        val hasFence = raw.contains("```")
        if (!hasFence && base.parsedSections.isNotEmpty()) {
            violations.add(
                com.apex.agent.core.engine.templates.runtime.TemplateContractViolation(
                    kind = com.apex.agent.core.engine.templates.runtime.TemplateContractViolationKind.SECTION_EMPTY,
                    section = "测试代码",
                    detail = "测试代码章节必须包含围栏代码块（``` 包裹的可运行代码）"
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
        request: TemplateTurnRequest
    ): TemplateRenderedReport {
        val base = super.renderReport(raw, context, request)
        val code = context.variable("code").orEmpty()
        val language = TemplateLanguageDetector.detect(code).takeIf { it.isConfident }?.detected
        val analysis = TestCasesAnalyzer.analyze(code, language, context.variableOr("framework", "JUnit"))
        return base.copy(
            summary = mapOf(
                META_FUNCTION_COUNT to analysis.functions.size.toString(),
                META_PARAM_COUNT to analysis.functions.sumOf { it.params.size }.toString(),
                META_CLASS_COUNT to analysis.equivalenceClasses.size.toString(),
                META_SKELETON_COUNT to analysis.skeletons.size.toString()
            )
        )
    }

    companion object {
        const val TEMPLATE_ID = "builtin_test_cases"
        const val METRIC_FUNCTIONS = "functions"
        const val METRIC_PARAMS = "params"
        const val METRIC_CLASSES = "equivalenceClasses"
        const val METRIC_SKELETONS = "skeletons"
        const val META_FUNCTION_COUNT = "functionCount"
        const val META_PARAM_COUNT = "paramCount"
        const val META_CLASS_COUNT = "classCount"
        const val META_SKELETON_COUNT = "skeletonCount"
    }
}
