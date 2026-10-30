package com.apex.agent.core.engine.templates.runtime.builtin

import com.apex.agent.core.engine.templates.runtime.TemplateExecutionContext
import com.apex.agent.core.engine.templates.runtime.TemplateSeverity
import com.apex.agent.core.engine.templates.runtime.TemplateTemplateSnapshot
import com.apex.agent.core.engine.templates.runtime.TemplateTurnRequest
import com.apex.agent.core.engine.templates.runtime.TemplateVariableAssignment
import com.apex.agent.core.engine.templates.runtime.TemplateVariableReport
import com.apex.agent.core.engine.templates.runtime.TemplateVariableSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR 14 测试 · 正则解释行为（解析器 + 解释器 + 风险分析）。
 */
class RegexExplainTemplateBehaviorTest {

    private val behavior = RegexExplainTemplateBehavior()

    private fun context(pattern: String, sample: String = ""): TemplateExecutionContext =
        TemplateExecutionContext(
            template = TemplateTemplateSnapshot(
                id = "builtin_regex_explain",
                name = "正则解释",
                content = "请解释下面的正则表达式。",
                declaredVariableNames = listOf("pattern", "sample"),
                categoryLabel = "ANALYSIS",
                isBuiltIn = true
            ),
            variables = TemplateVariableReport(
                listOf(
                    TemplateVariableAssignment("pattern", pattern, TemplateVariableSource.USER),
                    TemplateVariableAssignment(
                        "sample", sample,
                        if (sample.isBlank()) TemplateVariableSource.MISSING else TemplateVariableSource.USER
                    )
                )
            )
        )

    private suspend fun analyzeFor(pattern: String, sample: String = "") =
        behavior.analyze(context(pattern, sample), behavior.prepare(context(pattern, sample)))

    private fun requestStub() = TemplateTurnRequest(prompt = "stub", behaviorId = "builtin_regex_explain")

    // ═══ 解析器 ═══

    @Test
    fun `parser handles literals classes and quantifiers`() {
        val result = RegexParser.parse("a[0-9]+b?")
        assertNull(result.error)
        assertEquals(0, result.groupCount)  // 该模式无捕获组
        // 结构断言：Sequence(Literal, Quantifier(CharClass), Quantifier(Literal))
        val root = result.root as RegexNode.Sequence
        assertTrue(root.nodes[0] is RegexNode.Literal)
        assertTrue(root.nodes[1] is RegexNode.Quantifier)
        assertTrue((root.nodes[1] as RegexNode.Quantifier).body is RegexNode.CharClass)
    }

    @Test
    fun `parser handles groups alternation and anchors`() {
        val result = RegexParser.parse("^(?:get|post)-(\\w{2,4})$")
        assertNull(result.error)
        // 捕获组 1 个（非捕获不计）
        assertEquals(1, result.groupCount)
        val root = result.root as RegexNode.Sequence
        assertTrue(root.nodes[0] is RegexNode.Anchor)
        val alternation = (root.nodes[1] as RegexNode.Group).body as RegexNode.Alternation
        assertEquals(2, alternation.options.size)
    }

    @Test
    fun `parser counts named and capturing groups`() {
        val result = RegexParser.parse("(?<year>\\d{4})-(\\d{2})-(?:\\d{2})")
        assertNull(result.error)
        assertEquals(2, result.groupCount)
    }

    @Test
    fun `parser reports syntax errors defensively`() {
        val unclosed = RegexParser.parse("(abc")
        assertNotNull(unclosed.error)
        assertTrue(unclosed.error!!.contains(")"))

        val dangling = RegexParser.parse("a\\")
        assertNotNull(dangling.error)

        val doubleQuantifier = RegexParser.parse("a**")
        assertNotNull(doubleQuantifier.error)
        assertTrue(doubleQuantifier.error!!.contains("量词"))

        val loneQuantifier = RegexParser.parse("*abc")
        assertNotNull(loneQuantifier.error)
    }

    @Test
    fun `parser handles escapes and predefined classes`() {
        val result = RegexParser.parse("\\d{3}-\\d{4}|\\w+@\\w+\\.[a-z]{2,3}")
        assertNull(result.error)
        val segments = RegexParser.explain(result.root!!)
        assertTrue(segments.any { it.explanation.contains("数字") })
        assertTrue(segments.any { it.explanation.contains("单词字符") || it.explanation.contains("字符类") })
    }

    // ═══ 解释器 ═══

    @Test
    fun `explainer produces chinese semantics per segment`() {
        val result = RegexParser.parse("\\b\\w+@\\w+\\.com\\b")
        val segments = RegexParser.explain(result.root!!)
        assertTrue(segments.any { it.explanation.contains("单词边界") })
        assertTrue(segments.any { it.explanation.contains("一次或多次") || it.explanation.contains("至少") })
        assertTrue(segments.any { it.explanation.contains("字面字符") })
        // 量词的贪婪/惰性标注
        assertTrue(segments.any { it.explanation.contains("贪婪") })
    }

    @Test
    fun `explainer covers quantifier range forms`() {
        val exact = RegexParser.explain(RegexParser.parse("a{3}").root!!)
        assertTrue(exact.any { it.explanation.contains("恰好 3 次") })

        val range = RegexParser.explain(RegexParser.parse("a{2,5}").root!!)
        assertTrue(range.any { it.explanation.contains("2 到 5 次") })

        val atLeast = RegexParser.explain(RegexParser.parse("a{2,}").root!!)
        assertTrue(atLeast.any { it.explanation.contains("至少 2 次") })

        val lazy = RegexParser.explain(RegexParser.parse("a+?").root!!)
        assertTrue(lazy.any { it.explanation.contains("惰性") })
    }

    // ═══ 样例生成 ═══

    @Test
    fun `sample generation derives matching and non-matching`() {
        val samples = RegexParser.generateSamples(RegexParser.parse("[a-z]+\\d{2}").root!!)
        assertNotNull(samples)
        assertEquals("00", samples!!.first.takeLast(2))
        assertTrue(samples!!.second.isNotEmpty())

        val optional = RegexParser.generateSamples(RegexParser.parse("colou?r").root!!)
        assertEquals("color", optional!!.first)
    }

    @Test
    fun `sample generation respects anchors as zero width`() {
        val samples = RegexParser.generateSamples(RegexParser.parse("^ab$").root!!)
        assertEquals("ab", samples!!.first)
    }

    // ═══ 风险分析 ═══

    @Test
    fun `risk analysis catches nested quantifiers as catastrophic`() {
        val risks = RegexParser.analyzeRisks(RegexParser.parse("(a+)+").root!!)
        assertTrue(risks.any { it.level == RegexRiskLevel.CATASTROPHIC })
    }

    @Test
    fun `risk analysis catches adjacent unbounded quantifiers`() {
        val risks = RegexParser.analyzeRisks(RegexParser.parse("a*b*").root!!)
        // a* 与 b* 字符域不重叠 → 不告警
        assertTrue(risks.none { it.pattern.contains("相邻") })

        val overlapping = RegexParser.analyzeRisks(RegexParser.parse("\\w*\\w*").root!!)
        assertTrue(overlapping.any { it.level == RegexRiskLevel.HIGH })
    }

    @Test
    fun `risk analysis catches alternation prefix overlap`() {
        val risks = RegexParser.analyzeRisks(RegexParser.parse("com|common|net").root!!)
        assertTrue(risks.any { it.level == RegexRiskLevel.MODERATE && it.detail.contains("前缀") })
    }

    @Test
    fun `risk analysis passes clean regex`() {
        val risks = RegexParser.analyzeRisks(RegexParser.parse("^\\d{4}-\\d{2}$").root!!)
        assertTrue(risks.isEmpty())
    }

    // ═══ 简化建议 ═══

    @Test
    fun `simplification suggestions use rule table`() {
        val suggestions = RegexParser.simplifications("[0-9]+@(a|b)\\.[a-z]{2,3}")
        assertTrue(suggestions.any { it.contains("\\d") })
        assertTrue(suggestions.any { it.contains("[ab]") })
    }

    @Test
    fun `simplification flags missing anchors on long patterns`() {
        val suggestions = RegexParser.simplifications("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}")
        assertTrue(suggestions.any { it.contains("锚点") })
    }

    // ═══ 行为 ═══

    @Test
    fun `analyze produces structure samples and risk findings`() = runTest {
        val analysis = analyzeFor("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b", sample = "a@b.com")
        val ruleIds = analysis.findings.map { it.ruleId }
        assertTrue(ruleIds.contains("regex.structure"))
        assertTrue(ruleIds.contains("regex.samples"))
        assertEquals("true", analysis.metadata["parseOk"])
        assertTrue(analysis.enrichedHints.any { it.contains("解析状态") })
    }

    @Test
    fun `analyze reports parse errors as blocker`() = runTest {
        val analysis = analyzeFor("(unclosed")
        val errorFinding = analysis.findings.first { it.ruleId == "regex.parse-error" }
        assertEquals(TemplateSeverity.BLOCKER, errorFinding.severity)
        assertEquals("false", analysis.metadata["parseOk"])
    }

    @Test
    fun `analyze escalates catastrophic risk to blocker`() = runTest {
        val analysis = analyzeFor("(a+)+b")
        val risk = analysis.findings.first { it.ruleId == "regex.risk" }
        assertEquals(TemplateSeverity.BLOCKER, risk.severity)
        assertTrue(analysis.enrichedHints.any { it.contains("灾难性") })
    }

    @Test
    fun `compose injects segments appendix`() = runTest {
        val ctx = context("^(\\d{4})-(\\d{2})$")
        val prepared = behavior.prepare(ctx)
        val analysis = behavior.analyze(ctx, prepared)
        val request = behavior.composePrompt(ctx, prepared, analysis)
        assertTrue(request.prompt.contains("确定性分析附录"))
        assertTrue(request.prompt.contains("解析成功"))
        assertTrue(request.prompt.contains("推导样本"))
    }

    @Test
    fun `contract requires four sections`() {
        assertEquals(
            listOf("整体作用", "逐段拆解", "匹配与不匹配示例", "性能陷阱与简化建议"),
            behavior.manifest().outputContract.requiredTitles
        )
    }

    @Test
    fun `render summary carries parse and risk stats`() {
        val raw = """
            ## 整体作用
            匹配日期。

            ## 逐段拆解
            1. ^ 行首
            2. \d{4} 四位年份

            ## 匹配与不匹配示例
            - 匹配：2024-01
            - 不匹配：24-1

            ## 性能陷阱与简化建议
            无结构性风险。
        """.trimIndent()
        val rendered = behavior.renderReport(raw, context("\\d{4}-\\d{2}"), requestStub())
        assertEquals("true", rendered.summary["parseOk"])
        assertEquals("0", rendered.summary["groupCount"])
        assertEquals("无", rendered.summary["topRisk"])
    }

    // ═══ 端到端 ═══

    @Test
    fun `full turn through runtime`() = runTest {
        val dir = java.io.File("/tmp/regex-runtime-${System.nanoTime()}").apply { mkdirs() }
        val registry = com.apex.agent.core.engine.templates.PromptTemplateRegistry(dir)
        registry.reseedBuiltIns()
        val runtime = com.apex.agent.core.engine.templates.runtime.TemplateRuntime(
            registry = registry,
            behaviors = mapOf(behavior.behaviorId to behavior)
        )
        val activation = runtime.activate(
            "builtin_regex_explain",
            mapOf("pattern" to "^(\\d{4})-(\\d{2})-(\\d{2})$", "sample" to "2024-01-31")
        )
        assertTrue(activation.ok)
        val outcome = runtime.prepareTurn(activation)
        assertTrue(outcome.ok)
        assertTrue(outcome.request!!.prompt.contains("解析成功"))
        val report = runtime.processResponse(
            activation,
            """
            ## 整体作用
            匹配 ISO 日期。

            ## 逐段拆解
            1. ^：行首
            2. (\d{4})：捕获四位年份

            ## 匹配与不匹配示例
            - 匹配：2024-01-31
            - 不匹配：2024-1-1

            ## 性能陷阱与简化建议
            无回溯风险。
            """.trimIndent(),
            outcome.request
        )
        assertTrue(report.trace!!.allOk)
        assertEquals("true", report.summary["parseOk"])
    }
}
