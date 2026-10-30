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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR 7 测试 · 专业翻译行为。
 *
 * 覆盖：术语表双向命中、领域选择与通用兜底、脚本普查、源语言猜测、
 * 目标语言归一、一致性检查、行为分析链路、纯载荷契约、保真校验、
 * 修复与渲染、端到端。
 */
class TranslateTemplateBehaviorTest {

    private val behavior = TranslateTemplateBehavior()

    private fun context(
        text: String,
        targetLanguage: String = "英文",
        domain: String = "通用"
    ): TemplateExecutionContext {
        fun source(value: String) =
            if (value.isBlank()) TemplateVariableSource.MISSING else TemplateVariableSource.USER
        return TemplateExecutionContext(
            template = TemplateTemplateSnapshot(
                id = "builtin_translate",
                name = "专业翻译",
                content = "请将下面的文本翻译为{{target_language}}。",
                declaredVariableNames = listOf("text", "target_language", "domain"),
                categoryLabel = "TRANSLATION",
                isBuiltIn = true
            ),
            variables = TemplateVariableReport(
                listOf(
                    TemplateVariableAssignment("text", text, TemplateVariableSource.USER),
                    TemplateVariableAssignment("target_language", targetLanguage, source(targetLanguage)),
                    TemplateVariableAssignment("domain", domain, source(domain))
                )
            )
        )
    }

    private suspend fun analyzeFor(
        text: String,
        targetLanguage: String = "英文",
        domain: String = "通用"
    ) = behavior.analyze(
        context(text, targetLanguage, domain),
        behavior.prepare(context(text, targetLanguage, domain))
    )

    private fun requestStub() = TemplateTurnRequest(prompt = "stub", behaviorId = "builtin_translate")

    // ═══ 术语表 ═══

    @Test
    fun `technical glossary matches chinese to english`() {
        val hits = TranslateGlossary.matchTerms(
            "系统采用协程与结构化并发，配合熔断降级防止缓存雪崩。",
            GlossaryDomain.TECHNICAL, "en"
        )
        val terms = hits.map { it.entry.term }
        assertTrue(terms.contains("协程"))
        assertTrue(terms.contains("熔断"))
        assertTrue(terms.contains("缓存雪崩"))
        val coroutine = hits.first { it.entry.term == "协程" }
        assertEquals("coroutine", coroutine.suggested)
        assertEquals(1, coroutine.count)
    }

    @Test
    fun `glossary matches english to chinese with word boundary`() {
        val hits = TranslateGlossary.matchTerms(
            "The rollback path uses optimistic locking and a heartbeat.",
            GlossaryDomain.TECHNICAL, "zh"
        )
        val suggested = hits.map { it.suggested }
        assertTrue(suggested.contains("回滚"))
        assertTrue(suggested.contains("乐观锁"))
        assertTrue(suggested.contains("心跳"))
        // 词边界：locking 单独出现不算 optimistic locking
        assertFalse(hits.any { it.entry.term == "锁定" })
    }

    @Test
    fun `domain selection loads specific table with general fallback`() {
        val medical = TranslateGlossary.matchTerms("高血压与糖尿病的预后", GlossaryDomain.MEDICAL, "en")
        assertTrue(medical.map { it.entry.term }.containsAll(listOf("高血压", "糖尿病", "预后")))

        // 非医学领域不加载医学表，但通用表兜底
        val technical = TranslateGlossary.matchTerms("高血压与糖尿病的预后", GlossaryDomain.TECHNICAL, "en")
        assertFalse(technical.map { it.entry.term }.contains("高血压"))

        // 领域表 + 通用表同时命中
        val mixed = TranslateGlossary.matchTerms("项目截止日期临近，先做灰度发布", GlossaryDomain.TECHNICAL, "en")
        assertTrue(mixed.map { it.entry.term }.contains("灰度发布"))
        assertTrue(mixed.map { it.entry.term }.contains("截止日期"))
    }

    @Test
    fun `legal and finance tables carry real correspondences`() {
        val legal = TranslateGlossary.matchTerms("不可抗力导致的违约责任", GlossaryDomain.LEGAL, "en")
        assertEquals("force majeure", legal.first { it.entry.term == "不可抗力" }.suggested)
        assertEquals("breach of contract", legal.first { it.entry.term == "违约" }.suggested)

        val finance = TranslateGlossary.matchTerms("市盈率与净现值", GlossaryDomain.FINANCE, "en")
        assertEquals("price-to-earnings ratio", finance.first { it.entry.term == "市盈率" }.suggested)
        assertEquals("net present value", finance.first { it.entry.term == "净现值" }.suggested)
    }

    // ═══ 脚本与语言 ═══

    @Test
    fun `script census recognizes writing systems`() {
        assertEquals("cjk", TranslateGlossary.censusScript("这是一段中文").dominant)
        assertEquals("latin", TranslateGlossary.censusScript("plain english text").dominant)
        assertEquals("kana", TranslateGlossary.censusScript("これはテストです").dominant)
        assertEquals("hangul", TranslateGlossary.censusScript("이것은 테스트입니다").dominant)
        assertEquals("cyrillic", TranslateGlossary.censusScript("это тест").dominant)
        assertEquals("arabic", TranslateGlossary.censusScript("هذا اختبار").dominant)
    }

    @Test
    fun `mixture ratio reflects mixed scripts`() {
        val pure = TranslateGlossary.censusScript("全是中文的句子")
        val mixed = TranslateGlossary.censusScript("中文夹 English 的句子")
        assertTrue(mixed.mixtureRatio > pure.mixtureRatio)
    }

    @Test
    fun `source language guess uses markers`() {
        assertEquals("zh", TranslateGlossary.guessSourceLanguage("我们使用协程处理并发")?.first)
        assertEquals("ja", TranslateGlossary.guessSourceLanguage("これはテストです。します。")?.first)
        assertEquals("de", TranslateGlossary.guessSourceLanguage("Der Test ist nicht mit der Basis und das Ergebnis")?.first)
        assertEquals("fr", TranslateGlossary.guessSourceLanguage("Le résultat est une des meilleures solutions")?.first)
        assertEquals("en", TranslateGlossary.guessSourceLanguage("The quick brown fox jumps")?.first)
        assertNull(TranslateGlossary.guessSourceLanguage("12345 !!!"))
    }

    @Test
    fun `target language normalization covers aliases`() {
        assertEquals("zh", TranslateGlossary.normalizeTargetLanguage("中文"))
        assertEquals("zh", TranslateGlossary.normalizeTargetLanguage("英语".replace("英语", "Chinese")))
        assertEquals("en", TranslateGlossary.normalizeTargetLanguage("English"))
        assertEquals("en", TranslateGlossary.normalizeTargetLanguage("英文"))
        assertEquals("ja", TranslateGlossary.normalizeTargetLanguage("日文"))
        assertEquals("ko", TranslateGlossary.normalizeTargetLanguage("korean"))
        assertEquals("de", TranslateGlossary.normalizeTargetLanguage("德语"))
        assertNull(TranslateGlossary.normalizeTargetLanguage("火星语"))
        assertTrue(TranslateGlossary.isRightToLeft("ar"))
        assertFalse(TranslateGlossary.isRightToLeft("zh"))
    }

    // ═══ 一致性检查 ═══

    @Test
    fun `consistency check flags divergent translations`() {
        // 源文出现两次「回滚」，译文一次 rollback 一次 roll back（变体）
        val issues = TranslateGlossary.checkConsistency(
            "回滚与回滚重试", "rollback and roll back retry",
            GlossaryDomain.TECHNICAL, "en"
        )
        // rollback 与 roll back 词边界各自命中 → 两个变体
        assertTrue(issues.any { it.sourceTerm == "回滚" })
    }

    @Test
    fun `consistency check passes on uniform translation`() {
        val issues = TranslateGlossary.checkConsistency(
            "回滚与回滚重试", "rollback and rollback retry",
            GlossaryDomain.TECHNICAL, "en"
        )
        assertFalse(issues.any { it.sourceTerm == "回滚" })
    }

    // ═══ 行为链路 ═══

    @Test
    fun `analyze injects glossary and fidelity requirements`() = runTest {
        val analysis = analyzeFor(
            "## 发布计划\n- 灰度发布先行\n- 回滚预案就绪\n版本号 {{version}}",
            targetLanguage = "英文",
            domain = "技术"
        )
        val ruleIds = analysis.findings.map { it.ruleId }
        assertTrue(ruleIds.contains("translate.fidelity-requirements"))
        assertTrue(ruleIds.contains("translate.term-hit"))
        assertTrue(analysis.enrichedHints.any { it.contains("术语对照") })
        assertTrue(analysis.enrichedHints.any { it.contains("占位符" ) })
        assertEquals("en", analysis.metadata["targetLanguage"])
        assertEquals("TECHNICAL", analysis.metadata["domain"])
    }

    @Test
    fun `analyze warns on same source and target language`() = runTest {
        val analysis = analyzeFor("这是一段中文", targetLanguage = "中文")
        assertTrue(analysis.findings.any { it.ruleId == "translate.same-language" })
    }

    @Test
    fun `analyze warns on unknown target language`() = runTest {
        val analysis = analyzeFor("这是一段中文", targetLanguage = "火星语")
        assertTrue(analysis.findings.any { it.ruleId == "translate.unknown-target" })
        assertEquals("en", analysis.metadata["targetLanguage"])  // 英文假设兜底
    }

    @Test
    fun `analyze flags rtl target`() = runTest {
        val analysis = analyzeFor("这是一段中文", targetLanguage = "阿拉伯语")
        assertTrue(analysis.findings.any { it.ruleId == "translate.rtl-target" })
    }

    @Test
    fun `payload contract rejects explanatory structure`() {
        val raw = "## 译文\n熔断机制\n\n## 翻译说明\n本词采用意译。"
        val report = behavior.validateOutput(
            raw, context("熔断机制", targetLanguage = "英文", domain = "技术"), requestStub()
        )
        assertTrue(report.violations.any {
            it.kind == com.apex.agent.core.engine.templates.runtime.TemplateContractViolationKind.NOISE_AROUND_PAYLOAD
        })
    }

    @Test
    fun `fidelity check flags placeholder loss`() {
        val report = behavior.validateOutput(
            "版本已发布",  // 丢了 {{version}}
            context("发布版本 {{version}}", targetLanguage = "英文"),
            requestStub()
        )
        assertTrue(report.violations.any { it.detail.contains("占位符") })
    }

    @Test
    fun `fidelity check flags heading and list loss`() {
        val report = behavior.validateOutput(
            "发布计划合并成一句了",
            context("## 发布计划\n- 灰度发布\n- 回滚预案", targetLanguage = "英文"),
            requestStub()
        )
        val fidelityViolations = report.violations.filter { it.detail.contains("保真缺口") }
        assertTrue(fidelityViolations.isNotEmpty())
    }

    @Test
    fun `clean translation passes validation`() {
        val report = behavior.validateOutput(
            "Circuit breaking is enabled.",
            context("熔断已开启", targetLanguage = "英文", domain = "技术"),
            requestStub()
        )
        assertTrue(report.ok)
    }

    @Test
    fun `repair strips noise head and trailing notes`() {
        val raw = "以下是译文：\nCircuit breaking enabled.\n\n翻译说明：采用意译。"
        val report = behavior.validateOutput(
            raw, context("熔断已开启", targetLanguage = "英文"), requestStub()
        )
        val repaired = behavior.repairOutput(raw, report, context("熔断已开启", targetLanguage = "英文"))
        assertFalse(repaired.contains("以下是译文"))
        assertFalse(repaired.contains("翻译说明"))
        assertTrue(repaired.contains("Circuit breaking"))
    }

    @Test
    fun `render produces translation summary`() {
        val rendered = behavior.renderReport(
            "Circuit breaking enabled.",
            context("熔断已开启，需要回滚预案", targetLanguage = "英文", domain = "技术"),
            requestStub()
        )
        assertEquals("en", rendered.summary["targetLanguage"])
        assertEquals("zh", rendered.summary["sourceLanguage"])
        assertEquals("技术", rendered.summary["domain"])
        assertTrue((rendered.summary["termHitCount"]?.toIntOrNull() ?: 0) >= 2)
        assertEquals("true", rendered.summary["fidelityOk"])
        assertTrue(rendered.sections.first().title == "译文")
    }

    // ═══ 端到端 ═══

    @Test
    fun `full turn through runtime`() = runTest {
        val dir = java.io.File("/tmp/translate-runtime-${System.nanoTime()}").apply { mkdirs() }
        val registry = com.apex.agent.core.engine.templates.PromptTemplateRegistry(dir)
        registry.reseedBuiltIns()
        val runtime = com.apex.agent.core.engine.templates.runtime.TemplateRuntime(
            registry = registry,
            behaviors = mapOf(behavior.behaviorId to behavior)
        )
        val activation = runtime.activate(
            "builtin_translate",
            mapOf(
                "text" to "系统启用熔断与灰度发布。",
                "target_language" to "英文",
                "domain" to "技术"
            )
        )
        assertTrue(activation.ok)
        val outcome = runtime.prepareTurn(activation)
        assertTrue(outcome.ok)
        assertTrue(outcome.request!!.prompt.contains("术语对照"))
        val report = runtime.processResponse(
            activation,
            "The system enables circuit breaking and canary release.",
            outcome.request
        )
        assertEquals("true", report.summary["fidelityOk"])
        assertTrue(report.trace!!.allOk)
    }
}
