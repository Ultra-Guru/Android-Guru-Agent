package com.apex.agent.core.engine.templates.runtime

import com.apex.agent.core.engine.templates.PromptTemplate
import com.apex.agent.core.engine.templates.TemplateCategory
import com.apex.agent.core.engine.templates.TemplateVariable
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR 4 测试 · 基座行为的默认机械。
 *
 * 用一个「带真实分析逻辑的样例行为」锁定基类默认实现的每条通道：
 * 输入规范化/截断、变量替换、附录注入三模式、契约校验默认走基类。
 */
class TemplateBehaviorBaseTest {

    /** 样例行为：默认机械 + 一个确定性规则（演示子类如何接入）。 */
    private open class SampleBehavior : TemplateBehaviorBase() {
        override val behaviorId = "builtin_sample"
        override val primaryInputName = "code"

        override fun manifest(): TemplateBehaviorManifest = TemplateBehaviorManifest(
            behaviorId = behaviorId,
            displayName = "样例",
            summary = "基类机械演示",
            supportedPhases = setOf(
                TemplatePhaseKind.PREPARE,
                TemplatePhaseKind.ANALYZE,
                TemplatePhaseKind.COMPOSE
            ),
            outputContract = TemplateOutputContract(
                requiredSections = listOf(TemplateSectionRequirement("结论", required = true))
            ),
            deterministicPreAnalysis = true
        )

        override suspend fun analyze(
            context: TemplateExecutionContext,
            prepared: TemplatePreparedInput
        ): TemplateAnalysis = TemplateAnalysis.of(
            findings = listOf(
                TemplateFinding(
                    ruleId = "sample.todo",
                    category = TemplateFindingCategory.MAINTAINABILITY,
                    severity = TemplateSeverity.MINOR,
                    title = "TODO 残留",
                    location = "第 1 行",
                    evidence = "TODO: fix",
                    suggestion = "移除或转 issue"
                )
            ),
            detectedLanguage = "kotlin",
            enrichedHints = listOf("输入共 1 行")
        )
    }

    private fun contextOf(
        content: String,
        variables: Map<String, String>,
        options: TemplateRuntimeOptions = TemplateRuntimeOptions()
    ): TemplateExecutionContext {
        val template = PromptTemplate(
            id = "builtin_sample",
            name = "样例",
            content = content,
            variables = listOf(TemplateVariable("code", "代码", required = true))
        )
        return TemplateExecutionContext(
            template = TemplateTemplateSnapshot(
                id = template.id,
                name = template.name,
                content = template.content,
                declaredVariableNames = listOf("code"),
                categoryLabel = "CODING",
                isBuiltIn = true
            ),
            variables = TemplateVariableReport(
                variables.map { (name, value) ->
                    TemplateVariableAssignment(name, value, TemplateVariableSource.USER)
                }
            ),
            options = options
        )
    }

    @Test
    fun `prepare normalizes line endings and records truncation`() = runTest {
        val behavior = SampleBehavior()
        val long = "a".repeat(50)
        val prepared = behavior.prepare(
            contextOf("审查 {{code}}", mapOf("code" to "line1\r\nline2\r\r$long\n"), options = TemplateRuntimeOptions(maxInputChars = 30))
        )
        // 换行统一 + 截断到 30
        assertTrue(prepared.primaryInput.startsWith("line1\nline2"))
        assertEquals(30, prepared.primaryInput.length)
        assertTrue(prepared.truncations.containsKey("code"))
        assertTrue(prepared.truncations["code"]!! > 0)
        assertTrue(prepared.facets.lineCount >= 2)
    }

    @Test
    fun `prepare detects code and diff facets`() = runTest {
        val behavior = SampleBehavior()
        val code = behavior.prepare(
            contextOf("{{code}}", mapOf("code" to "fun main() {\n    val x = 1;\n}"))
        )
        assertTrue(code.facets.looksLikeCode)

        val diff = behavior.prepare(
            contextOf("{{code}}", mapOf("code" to "@@ -1,2 +1,3 @@\n-old\n+new\n+added"))
        )
        assertTrue(diff.facets.looksLikeDiff)
    }

    @Test
    fun `compose substitutes variables and appends findings block`() = runTest {
        val behavior = SampleBehavior()
        val context = contextOf("请审查：{{code}}", mapOf("code" to "val a = 1"))
        val prepared = behavior.prepare(context)
        val analysis = behavior.analyze(context, prepared)
        val request = behavior.composePrompt(context, prepared, analysis)

        assertTrue(request.prompt.contains("请审查：val a = 1"))
        assertTrue(request.prompt.contains("确定性分析附录"))
        assertTrue(request.prompt.contains("TODO 残留"))
        assertTrue(request.prompt.contains("语言：kotlin"))
        assertTrue(request.enrichmentNotes.any { it.contains("1 条确定性发现") })
        assertTrue(request.enrichmentNotes.any { it.contains("语言探测") })
    }

    @Test
    fun `compose keeps unknown placeholders intact`() = runTest {
        val behavior = SampleBehavior()
        // 未声明变量 {{extra}} 保留原样（引擎变量系统可能后续解析）
        val context = contextOf("审查 {{code}}（附注 {{extra}}）", mapOf("code" to "x"))
        val prepared = behavior.prepare(context)
        val request = behavior.composePrompt(context, prepared, behavior.analyze(context, prepared))
        assertTrue(request.prompt.contains("{{extra}}"))
    }

    @Test
    fun `compose respects INLINE_HINTS and NONE modes`() = runTest {
        val inlineBehavior = object : SampleBehavior() {
            override fun manifest(): TemplateBehaviorManifest =
                super.manifest().copy(promptEnrichmentMode = TemplatePromptEnrichmentMode.INLINE_HINTS)
        }
        val context = contextOf("审查 {{code}}", mapOf("code" to "x"))
        val prepared = inlineBehavior.prepare(context)
        val request = inlineBehavior.composePrompt(context, prepared, inlineBehavior.analyze(context, prepared))
        assertTrue(request.prompt.contains("运行时提示"))
        assertFalse(request.prompt.contains("确定性分析附录"))

        val noneBehavior = object : SampleBehavior() {
            override fun manifest(): TemplateBehaviorManifest =
                super.manifest().copy(promptEnrichmentMode = TemplatePromptEnrichmentMode.NONE)
        }
        val request2 = noneBehavior.composePrompt(
            context, prepared, noneBehavior.analyze(context, prepared)
        )
        assertFalse(request2.prompt.contains("运行时提示"))
        assertFalse(request2.prompt.contains("确定性分析附录"))
    }

    @Test
    fun `injectAnalysisIntoPrompt option disables enrichment`() = runTest {
        val behavior = SampleBehavior()
        val context = contextOf("审查 {{code}}", mapOf("code" to "x"), options = TemplateRuntimeOptions(injectAnalysisIntoPrompt = false))
        val prepared = behavior.prepare(context)
        val request = behavior.composePrompt(context, prepared, behavior.analyze(context, prepared))
        assertFalse(request.prompt.contains("确定性分析附录"))
    }

    @Test
    fun `validate and repair default machinery`() = runTest {
        val behavior = SampleBehavior()
        val context = contextOf("审查 {{code}}", mapOf("code" to "x"))
        val request = behavior.composePrompt(
            context, behavior.prepare(context), behavior.analyze(context, behavior.prepare(context))
        )
        val raw = "好的。\n## 结论\n通过。"
        val report = behavior.validateOutput(raw, context, request)
        assertTrue(report.violations.any { it.kind == TemplateContractViolationKind.PREAMBLE })
        val repaired = behavior.repairOutput(raw, report, context)
        assertFalse(repaired.contains("好的"))
    }

    @Test
    fun `render builds contract sections with ordering`() = runTest {
        val behavior = SampleBehavior()
        val context = contextOf("审查 {{code}}", mapOf("code" to "x"))
        val request = behavior.composePrompt(
            context, behavior.prepare(context), behavior.analyze(context, behavior.prepare(context))
        )
        val report = behavior.renderReport(
            "## 结论\n通过。\n\n## 补充说明\n额外内容",
            context,
            request
        )
        assertEquals(2, report.sections.size)
        assertEquals("结论", report.sections[0].title)
        assertEquals(TemplateReportSection.Kind.SUPPLEMENT, report.sections[1].kind)
        assertTrue(report.qualityScore > 0.5)
    }

    @Test
    fun `fallback behavior drives custom templates`() = runTest {
        val custom = PromptTemplate(
            id = "tpl_user_made",
            name = "我的模板",
            content = "翻译 {{text}} 为 {{lang}}",
            category = TemplateCategory.CUSTOM,
            variables = listOf(
                TemplateVariable("text", "文本", required = true),
                TemplateVariable("lang", "语言", required = false, defaultValue = "英文")
            )
        )
        val fallback = TemplateBehaviorBase.fallbackFor(custom)
        assertEquals("tpl_user_made", fallback.behaviorId)
        assertEquals("text", (fallback as TemplateBehaviorBase).let { "text" }) // 主输入首变量
        val manifest = fallback.manifest()
        assertEquals(0, manifest.behaviorVersion)
        assertFalse(manifest.deterministicPreAnalysis)
        assertEquals(TemplatePromptEnrichmentMode.NONE, manifest.promptEnrichmentMode)
        assertEquals("CUSTOM", TemplateBehaviorBase.categoryLabelOf(custom).substringBefore("·"))
    }
}
