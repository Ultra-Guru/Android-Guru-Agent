package com.apex.agent.core.engine.templates.runtime.builtin

import com.apex.agent.core.engine.templates.runtime.TemplateExecutionContext
import com.apex.agent.core.engine.templates.runtime.TemplateTemplateSnapshot
import com.apex.agent.core.engine.templates.runtime.TemplateTurnRequest
import com.apex.agent.core.engine.templates.runtime.TemplateVariableAssignment
import com.apex.agent.core.engine.templates.runtime.TemplateVariableReport
import com.apex.agent.core.engine.templates.runtime.TemplateVariableSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR 8 测试 · 内容总结行为。
 */
class SummarizeTemplateBehaviorTest {

    private val behavior = SummarizeTemplateBehavior()

    private fun context(
        content: String,
        perspective: String = "",
        length: String = ""
    ): TemplateExecutionContext {
        fun source(value: String) =
            if (value.isBlank()) TemplateVariableSource.MISSING else TemplateVariableSource.USER
        return TemplateExecutionContext(
            template = TemplateTemplateSnapshot(
                id = "builtin_summarize",
                name = "内容总结",
                content = "请总结下面的内容。",
                declaredVariableNames = listOf("content", "perspective", "length"),
                categoryLabel = "ANALYSIS",
                isBuiltIn = true
            ),
            variables = TemplateVariableReport(
                listOf(
                    TemplateVariableAssignment("content", content, TemplateVariableSource.USER),
                    TemplateVariableAssignment("perspective", perspective, source(perspective)),
                    TemplateVariableAssignment("length", length, source(length))
                )
            )
        )
    }

    private suspend fun analyzeFor(content: String, length: String = "") = behavior.analyze(
        context(content, length = length), behavior.prepare(context(content, length = length))
    )

    private fun requestStub() = TemplateTurnRequest(prompt = "stub", behaviorId = "builtin_summarize")

    // ═══ 长度预算 ═══

    @Test
    fun `length budget parses upper bound forms`() {
        assertEquals(200, SummarizeAnalyzer.parseLengthBudget("200 字以内").maxChars)
        assertEquals(300, SummarizeAnalyzer.parseLengthBudget("不超过300字").maxChars)
        assertEquals(50, SummarizeAnalyzer.parseLengthBudget("≤50字").maxChars)
        assertEquals(100, SummarizeAnalyzer.parseLengthBudget("最多 100 字").maxChars)
    }

    @Test
    fun `length budget parses range and approximate forms`() {
        val range = SummarizeAnalyzer.parseLengthBudget("50-80 字")
        assertEquals(50, range.minChars)
        assertEquals(80, range.maxChars)
        assertTrue(range.parsed)

        val tilde = SummarizeAnalyzer.parseLengthBudget("50~80 字")
        assertEquals(80, tilde.maxChars)

        val approx = SummarizeAnalyzer.parseLengthBudget("300 字左右")
        assertTrue(approx.parsed)
        assertTrue(approx.minChars < 300 && approx.maxChars > 300)
        assertEquals(300, (approx.minChars + approx.maxChars) / 2)
    }

    @Test
    fun `length budget parses chinese numerals and falls back`() {
        assertEquals(200, SummarizeAnalyzer.parseLengthBudget("两百以内").maxChars)
        assertEquals(300, SummarizeAnalyzer.parseLengthBudget("三百以内").maxChars)
        val fallback = SummarizeAnalyzer.parseLengthBudget("简短一些")
        assertFalse(fallback.parsed)
        assertEquals(200, fallback.maxChars)
    }

    @Test
    fun `chinese number parser handles common forms`() {
        assertEquals(2, SummarizeAnalyzer.parseChineseNumber("两"))
        assertEquals(10, SummarizeAnalyzer.parseChineseNumber("十"))
        assertEquals(200, SummarizeAnalyzer.parseChineseNumber("两百"))
        assertNull(SummarizeAnalyzer.parseChineseNumber("火星"))
        assertNull(SummarizeAnalyzer.parseChineseNumber(null))
    }

    // ═══ 体裁画像 ═══

    @Test
    fun `genre detection classifies structured doc list dialogue narrative`() {
        val doc = (1..4).joinToString("\n\n") { "## 第 $it 节\n内容行甲\n内容行乙" }
        assertEquals(ContentProfile.Genre.STRUCTURED_DOC, SummarizeAnalyzer.profile(doc).genre)

        val list = (1..6).joinToString("\n") { "- 列表项 $it" }
        assertEquals(ContentProfile.Genre.LIST_BASED, SummarizeAnalyzer.profile(list).genre)
        assertTrue(SummarizeAnalyzer.profile(list).listDominated)

        val dialogue = "问：第一问\n答：第一答\n问：第二问\n答：第二答"
        assertEquals(ContentProfile.Genre.DIALOGUE, SummarizeAnalyzer.profile(dialogue).genre)

        val narrative = "这是一段平实的叙述。没有结构，没有列表。就是普通句子。"
        assertEquals(ContentProfile.Genre.NARRATIVE, SummarizeAnalyzer.profile(narrative).genre)
    }

    @Test
    fun `paragraph counting uses blank line blocks`() {
        assertEquals(3, SummarizeAnalyzer.countParagraphs("甲甲甲\n\n乙乙乙\n\n丙丙丙"))
        assertEquals(1, SummarizeAnalyzer.countParagraphs("单段\n继续同段"))
        assertEquals(0, SummarizeAnalyzer.countParagraphs("  \n  "))
    }

    // ═══ 事实抽取 ═══

    @Test
    fun `key fact extraction covers numbers percent dates negation`() {
        val text = "系统延迟从 320ms 降到 45ms，命中率提升 30%，2024 年 Q3 上线。该接口不支持批量调用，未经审计不得开放。"
        val facts = SummarizeAnalyzer.extractKeyFacts(text)
        val kinds = facts.map { it.kind }.toSet()
        assertTrue(kinds.contains(KeyFact.Kind.PERCENT))
        assertTrue(kinds.contains(KeyFact.Kind.NUMBER))
        assertTrue(kinds.contains(KeyFact.Kind.DATE))
        assertTrue(kinds.contains(KeyFact.Kind.NEGATION))
        assertTrue(facts.any { it.text.contains("30%") })
        // 否定句整句抽取
        val negation = facts.first { it.kind == KeyFact.Kind.NEGATION }
        assertTrue(negation.text.contains("批量") || negation.text.contains("审计"))
    }

    // ═══ 抽取式草稿 ═══

    @Test
    fun `draft summary picks top sentences in original order`() {
        val text = """
            核心结论：缓存层重构收益最大。
            缓存层重构使接口延迟从 320ms 降到 45ms，命中率提升 30%。
            中间是一些普通描述内容，用来稀释关键词密度让打分差异更明显一些。
            缓存层重构避免了缓存雪崩风险。
            结尾：缓存层重构是本季度最重要的工作。
        """.trimIndent()
        val draft = SummarizeAnalyzer.draftSummary(text, LengthBudget.DEFAULT)
        assertTrue(draft.size >= 2)
        // 原文顺序
        val ordinals = draft.map { it.ordinal }
        assertEquals(ordinals, ordinals.sorted())
        // 高分句（含缓存层）入选，稀释句落选
        assertTrue(draft.none { it.text.contains("稀释") })
        assertTrue(draft.any { it.text.contains("缓存层") })
        // 每句带理由
        assertTrue(draft.all { it.rationale.isNotBlank() })
    }

    @Test
    fun `draft count derives from budget`() {
        assertEquals(2, SummarizeAnalyzer.deriveDraftCount(LengthBudget(0, 100), 10))
        assertEquals(3, SummarizeAnalyzer.deriveDraftCount(LengthBudget(0, 200), 10))
        assertEquals(6, SummarizeAnalyzer.deriveDraftCount(LengthBudget(0, 400), 10))
        // 不超过句子总数
        assertEquals(2, SummarizeAnalyzer.deriveDraftCount(LengthBudget(0, 400), 2))
    }

    @Test
    fun `bigram overlap measures redundancy`() {
        val high = SummarizeAnalyzer.bigramOverlap(
            "缓存层重构提升命中率", "缓存层重构提升了命中率"
        )
        val low = SummarizeAnalyzer.bigramOverlap(
            "缓存层重构提升命中率", "团队组织了团建活动"
        )
        assertTrue(high > low)
        assertTrue(high > 0.5)
    }

    // ═══ 输出校验 ═══

    @Test
    fun `summary validator flags structural issues`() {
        val source = "原文内容。系统延迟从 320ms 降到 45ms。命中率提升 30%。"
        val bad = "- 要点一\n- 要点二"  // 无结论 + 要点过少
        val issues = SummarizeAnalyzer.validateSummary(bad, source, LengthBudget.DEFAULT)
        assertTrue(issues.any { it.kind == SummarizeAnalyzer.SummaryIssue.Kind.MISSING_VERDICT })
        assertTrue(issues.any { it.kind == SummarizeAnalyzer.SummaryIssue.Kind.TOO_FEW_POINTS })
    }

    @Test
    fun `summary validator flags over budget and fact loss`() {
        val source = "延迟从 320ms 降到 45ms。命中率提升 30%。错误率 0.5%。"
        val longSummary = "结论：性能全面改善。" + "详细展开说明各维度改善情况与后续计划安排。".repeat(20) +
            "\n- 要点一\n- 要点二\n- 要点三"
        val issues = SummarizeAnalyzer.validateSummary(longSummary, source, LengthBudget.DEFAULT)
        assertTrue(issues.any { it.kind == SummarizeAnalyzer.SummaryIssue.Kind.VERDICT_TOO_LONG })
        assertTrue(issues.any { it.kind == SummarizeAnalyzer.SummaryIssue.Kind.FACT_LOST })
    }

    @Test
    fun `summary validator flags negation loss`() {
        val source = "新接口不支持批量调用。其余功能正常。"
        val dropped = "结论：接口功能齐备可批量使用。\n- 要点一\n- 要点二\n- 要点三"
        val issues = SummarizeAnalyzer.validateSummary(dropped, source, LengthBudget.DEFAULT)
        assertTrue(issues.any { it.kind == SummarizeAnalyzer.SummaryIssue.Kind.NEGATION_LOST })
    }

    @Test
    fun `summary validator accepts good summary`() {
        val source = "延迟从 320ms 降到 45ms，命中率提升 30%，该功能不支持灰度。"
        val good = """
            结论：重构后性能显著改善但灰度受限。

            - 延迟从 320ms 降到 45ms
            - 命中率提升 30%
            - 该功能不支持灰度
            - 其余能力保持稳定
        """.trimIndent()
        val issues = SummarizeAnalyzer.validateSummary(good, source, LengthBudget.DEFAULT)
        assertTrue(issues.none { it.kind == SummarizeAnalyzer.SummaryIssue.Kind.MISSING_VERDICT })
        assertTrue(issues.none { it.kind == SummarizeAnalyzer.SummaryIssue.Kind.FACT_LOST })
        assertTrue(issues.none { it.kind == SummarizeAnalyzer.SummaryIssue.Kind.NEGATION_LOST })
    }

    // ═══ 行为链路 ═══

    @Test
    fun `analyze injects budget facts and negation guard`() = runTest {
        val analysis = analyzeFor(
            "系统延迟从 320ms 降到 45ms。命中率提升 30%。该接口不支持批量调用。",
            length = "300 字左右"
        )
        val ruleIds = analysis.findings.map { it.ruleId }
        assertTrue(ruleIds.contains("summary.genre"))
        assertTrue(ruleIds.contains("summary.key-facts"))
        assertTrue(ruleIds.contains("summary.negation-guard"))
        assertTrue(analysis.enrichedHints.any { it.contains("长度预算") })
        assertTrue(analysis.enrichedHints.any { it.contains("否定语义保护") })
        assertTrue(analysis.metadata["genre"] != null)
    }

    @Test
    fun `analyze warns when budget unparsed`() = runTest {
        val analysis = analyzeFor("普通内容若干。", length = "简短一些")
        assertTrue(analysis.findings.any { it.ruleId == "summary.budget-unparsed" })
    }

    @Test
    fun `compose injects facts and draft appendix`() = runTest {
        val ctx = context("延迟从 320ms 降到 45ms。命中率提升 30%。" , length = "200 字以内")
        val prepared = behavior.prepare(ctx)
        val analysis = behavior.analyze(ctx, prepared)
        val request = behavior.composePrompt(ctx, prepared, analysis)
        assertTrue(request.prompt.contains("确定性分析附录"))
        assertTrue(request.prompt.contains("关键事实"))
        assertTrue(request.prompt.contains("320ms"))
    }

    @Test
    fun `validate output integrates summary issues`() {
        val raw = "好的。\n## 要点\n- 一\n- 二"
        val report = behavior.validateOutput(
            raw, context("原文内容有事实。延迟 45ms。命中率 30%。错误率 0.5%。"), requestStub()
        )
        assertTrue(report.violations.any { it.detail.contains("缺结论") || it.detail.contains("MISSING_VERDICT") || it.kind == com.apex.agent.core.engine.templates.runtime.TemplateContractViolationKind.MISSING_VERDICT })
    }

    @Test
    fun `render summary carries genre budget coverage`() {
        val source = "延迟从 320ms 降到 45ms。命中率提升 30%。"
        val raw = """
            ## 一句话结论
            性能显著改善。

            ## 要点
            - 延迟从 320ms 降到 45ms
            - 命中率提升 30%
            - 其余保持稳定
        """.trimIndent()
        val rendered = behavior.renderReport(raw, context(source, length = "200 字以内"), requestStub())
        assertEquals("true", rendered.summary["budgetOk"])
        assertTrue((rendered.summary["factCoveragePercent"]?.toIntOrNull() ?: 0) >= 60)
        assertEquals(3, rendered.summary["bulletCount"]?.toIntOrNull())
    }

    // ═══ 端到端 ═══

    @Test
    fun `full turn through runtime`() = runTest {
        val dir = java.io.File("/tmp/summarize-runtime-${System.nanoTime()}").apply { mkdirs() }
        val registry = com.apex.agent.core.engine.templates.PromptTemplateRegistry(dir)
        registry.reseedBuiltIns()
        val runtime = com.apex.agent.core.engine.templates.runtime.TemplateRuntime(
            registry = registry,
            behaviors = mapOf(behavior.behaviorId to behavior)
        )
        val activation = runtime.activate(
            "builtin_summarize",
            mapOf(
                "content" to "缓存层重构使延迟从 320ms 降到 45ms，命中率提升 30%，且不支持降级开关。",
                "length" to "200 字以内"
            )
        )
        assertTrue(activation.ok)
        val outcome = runtime.prepareTurn(activation)
        assertTrue(outcome.ok)
        assertTrue(outcome.request!!.prompt.contains("关键事实"))
        val report = runtime.processResponse(
            activation,
            """
            ## 一句话结论
            缓存重构大幅改善性能，但无降级开关。

            ## 要点
            - 延迟从 320ms 降到 45ms
            - 命中率提升 30%
            - 不支持降级开关
            - 其余保持稳定
            """.trimIndent(),
            outcome.request
        )
        assertTrue(report.trace!!.allOk)
        assertTrue(report.summary["budgetOk"] == "true")
    }
}
