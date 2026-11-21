package com.apex.agent.core.engine.templates.runtime.builtin

import com.apex.agent.core.engine.templates.PromptTemplate
import com.apex.agent.core.engine.templates.TemplateVariable
import com.apex.agent.core.engine.templates.runtime.TemplateExecutionContext
import com.apex.agent.core.engine.templates.runtime.TemplateFinding
import com.apex.agent.core.engine.templates.runtime.TemplateReportSection
import com.apex.agent.core.engine.templates.runtime.TemplateRuntime
import com.apex.agent.core.engine.templates.runtime.TemplateRuntimeOptions
import com.apex.agent.core.engine.templates.runtime.TemplateSeverity
import com.apex.agent.core.engine.templates.runtime.TemplateTemplateSnapshot
import com.apex.agent.core.engine.templates.runtime.TemplateVariableAssignment
import com.apex.agent.core.engine.templates.runtime.TemplateVariableReport
import com.apex.agent.core.engine.templates.runtime.TemplateVariableSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR 5 测试 · 代码审查行为（确定性分析内核 + 契约 + 渲染）。
 *
 * 断言全部针对确定性输出：规则命中/行号/证据、语言解析、结构度量、
 * 回包契约校验与修复、严重度摘要。
 */
class CodeReviewTemplateBehaviorTest {

    private val behavior = CodeReviewTemplateBehavior()

    private fun context(
        code: String,
        language: String = "",
        focus: String = ""
    ): TemplateExecutionContext {
        val assignments = buildList {
            add(TemplateVariableAssignment("code", code, TemplateVariableSource.USER))
            add(TemplateVariableAssignment("language", language, if (language.isBlank()) TemplateVariableSource.MISSING else TemplateVariableSource.USER))
            add(TemplateVariableAssignment("focus", focus, if (focus.isBlank()) TemplateVariableSource.MISSING else TemplateVariableSource.USER))
        }
        return TemplateExecutionContext(
            template = TemplateTemplateSnapshot(
                id = "builtin_code_review",
                name = "代码审查",
                content = "你是一位资深代码评审专家。",
                declaredVariableNames = listOf("code", "language", "focus"),
                categoryLabel = "CODING",
                isBuiltIn = true
            ),
            variables = TemplateVariableReport(assignments)
        )
    }

    private suspend fun analyzeFor(
        code: String,
        language: String = "",
        focus: String = ""
    ) = behavior.analyze(
        context(code, language, focus),
        behavior.prepare(context(code, language, focus))
    )

    // ═══ 清单 ═══

    @Test
    fun `manifest declares full pipeline and contract`() {
        val manifest = behavior.manifest()
        assertEquals("builtin_code_review", manifest.behaviorId)
        assertTrue(manifest.deterministicPreAnalysis)
        assertEquals(6, manifest.supportedPhases.size)
        assertEquals(listOf("总体结论", "问题清单"), manifest.outputContract.requiredTitles)
        // 关键修复示例是可选章节
        assertFalse(
            manifest.outputContract.requiredSections.first { it.title == "关键修复示例" }.required
        )
    }

    // ═══ 语言判定 ═══

    @Test
    fun `declared language wins with alias normalization`() {
        assertEquals("kotlin", behavior.resolveLanguage("Kotlin", "anything"))
        assertEquals("kotlin", behavior.resolveLanguage("kt", "anything"))
        assertEquals("python", behavior.resolveLanguage("py", "anything"))
        assertEquals("typescript", behavior.resolveLanguage("TS", "anything"))
        assertEquals("cpp", behavior.resolveLanguage("C++", "anything"))
        assertEquals("shell", behavior.resolveLanguage("bash", "anything"))
    }

    @Test
    fun `auto detection falls back to detector with confidence gate`() {
        // 探测不了的散文 → null（不瞎猜）
        assertEquals(null, behavior.resolveLanguage("", "这是一段没有代码特征的文字描述"))
        // 可探测的代码 → 语言 id
        assertEquals("kotlin", behavior.resolveLanguage("", "data class A(val x: Int)\nfun f() = 1"))
    }

    // ═══ 逐语言规则 ═══

    @Test
    fun `kotlin rules hit with line locations`() = runTest {
        val code = """
            fun process(user: User?) {
                val name = user!!.name
                user!!.printStackTrace()
                GlobalScope.launch { refresh() }
            }
        """.trimIndent()
        val analysis = analyzeFor(code, language = "kotlin")
        val ruleIds = analysis.findings.map { it.ruleId }
        assertTrue(ruleIds.contains("kotlin.double-bang"))
        assertTrue(ruleIds.contains("kotlin.globalscope"))
        // 行号定位：user!! 在第 2 行
        val doubleBang = analysis.findings.first { it.ruleId == "kotlin.double-bang" }
        assertTrue(doubleBang.location.contains("第 2 行"))
        assertTrue(doubleBang.evidence.contains("user!!"))
        assertEquals("kotlin", analysis.detectedLanguage)
    }

    @Test
    fun `python security rules hit`() = runTest {
        val code = """
            def load(config):
                try:
                    data = eval(config)
                except:
                    data = None
                return data
        """.trimIndent()
        val analysis = analyzeFor(code, language = "python")
        val ruleIds = analysis.findings.map { it.ruleId }
        assertTrue(ruleIds.contains("python.eval"))
        assertTrue(ruleIds.contains("python.bare-except"))
    }

    @Test
    fun `javascript xss and equality rules hit`() = runTest {
        val code = """
            function render(userInput) {
                var el = document.getElementById('x');
                el.innerHTML = userInput;
                if (userInput == null) return;
            }
        """.trimIndent()
        val analysis = analyzeFor(code, language = "javascript")
        val ruleIds = analysis.findings.map { it.ruleId }
        assertTrue(ruleIds.contains("js.innerhtml"))
        assertTrue(ruleIds.contains("js.var-declare"))
        assertTrue(ruleIds.contains("js.loose-equality"))
    }

    @Test
    fun `go ignored error rule matches blank assignment`() = runTest {
        val code = """
            func load() {
                _, _ = doWork()
                _ = maybeError()
                for k, _ := range m {
                    use(k)
                }
            }
        """.trimIndent()
        val analysis = analyzeFor(code, language = "go")
        val ignored = analysis.findings.filter { it.ruleId == "go.ignored-error" }
        // range 的 _ 是惯用法不计；_, _ = 与 _ = 两条都命中
        assertTrue(ignored.size >= 2)
        assertTrue(ignored.all { it.severity == TemplateSeverity.BLOCKER })
    }

    @Test
    fun `rust unwrap and unsafe are blockers`() = runTest {
        val code = """
            fn main() {
                let value: Result<i32, String> = compute();
                let x = value.unwrap();
                unsafe { raw(); }
            }
        """.trimIndent()
        val analysis = analyzeFor(code, language = "rust")
        val ruleIds = analysis.findings.map { it.ruleId }
        assertTrue(ruleIds.contains("rust.unwrap"))
        assertTrue(ruleIds.contains("rust.unsafe"))
        assertTrue(
            analysis.findings.filter { it.ruleId in setOf("rust.unwrap", "rust.unsafe") }
                .all { it.severity == TemplateSeverity.BLOCKER }
        )
    }

    @Test
    fun `sql rules hit with ignore case`() = runTest {
        val code = "select * from users where name like '%张%' and id = 1"
        val analysis = analyzeFor(code, language = "sql")
        val ruleIds = analysis.findings.map { it.ruleId }
        assertTrue(ruleIds.contains("sql.select-star"))
        assertTrue(ruleIds.contains("sql.leading-like"))
    }

    // ═══ 结构规则 ═══

    @Test
    fun `work item markers are flagged`() = runTest {
        val analysis = analyzeFor(
            "fun a() {\n    // TODO: 待实现\n    // FIXME: 崩溃修复\n}",
            language = "kotlin"
        )
        val markers = analysis.findings.filter { it.ruleId == "common.work-item-marker" }
        assertEquals(2, markers.size)
    }

    @Test
    fun `long function detected by brace matching`() = runTest {
        val body = (1..80).joinToString("\n") { "    val v$it = $it" }
        val code = "fun giant() {\n$body\n}"
        val analysis = analyzeFor(code, language = "kotlin")
        val longFn = analysis.findings.firstOrNull { it.ruleId == "common.long-function" }
        assertNotNull("应检出超长函数", longFn)
        assertTrue(longFn!!.location.contains("giant"))
    }

    @Test
    fun `deep nesting detected`() = runTest {
        val code = """
            fun f() {
                if (a) {
                    if (b) {
                        if (c) {
                            if (d) {
                                if (e) {
                                    work()
                                }
                            }
                        }
                    }
                }
            }
        """.trimIndent()
        val analysis = analyzeFor(code, language = "kotlin")
        assertTrue(analysis.findings.any { it.ruleId == "common.deep-nesting" })
    }

    @Test
    fun `hardcoded secrets are blockers`() = runTest {
        val code = """
            val config = mapOf(
                "password" to "hunter2-secret-value",
                "api_key" to "sk_live_abcdef1234567890"
            )
        """.trimIndent()
        val analysis = analyzeFor(code, language = "kotlin")
        val secrets = analysis.findings.filter { it.ruleId == "security.hardcoded-credential" }
        assertTrue(secrets.isNotEmpty())
        assertTrue(secrets.all { it.severity == TemplateSeverity.BLOCKER })
    }

    @Test
    fun `shell script without strict mode flagged`() = runTest {
        val code = """
            #!/bin/bash
            echo start
            cd /data/work
            cp a.txt b.txt
            echo done
        """.trimIndent()
        val analysis = analyzeFor(code, language = "shell")
        assertTrue(analysis.findings.any { it.ruleId == "common.shell-no-strict-mode" })
        assertTrue(analysis.findings.any { it.ruleId == "sh.cd-no-check" })
    }

    // ═══ 结构度量（复杂度 / 重复 / 可维护性）═══

    @Test
    fun `high complexity function is flagged by metrics layer`() = runTest {
        val branches = (1..16).joinToString("\n") { "    if (cond$it) { work($it) }" }
        val code = "fun tangled(x: Int) {\n$branches\n}"
        val analysis = analyzeFor(code, language = "kotlin")
        val complexity = analysis.findings.first { it.ruleId == "metrics.high-complexity" }
        assertTrue(complexity.title.contains("tangled"))
        assertTrue(complexity.evidence.contains("分支节点"))
        // 可维护性评分已注入附加情报
        assertTrue(analysis.enrichedHints.any { it.contains("可维护性评分") })
    }

    @Test
    fun `duplicate blocks detected with occurrence count`() {
        val block = """
            val total = items.size
            val limited = items.take(20)
            val joined = limited.joinToString(",")
        """.trimIndent()
        val code = block + "\n\nfun other() {\n" + block + "\n}\n\nfun third() {\n" + block + "\n}"
        val duplicates = CodeReviewStructureMetrics.detectDuplicates(code)
        assertTrue(duplicates.isNotEmpty())
        val top = duplicates.first()
        assertTrue(top.occurrences >= 2)
        assertTrue(top.sample.contains("items.size"))
    }

    @Test
    fun `metrics report scores clean code high`() {
        val code = """
            fun add(a: Int, b: Int): Int {
                return a + b
            }

            fun greet(name: String): String {
                return "hi " + name
            }
        """.trimIndent()
        val spans = behavior.findFunctions(code, "kotlin")
        val report = CodeReviewStructureMetrics.report(code, "kotlin", spans)
        assertEquals(2, report.functions.size)
        assertTrue(report.maintainabilityScore >= 90)
        assertTrue(report.duplicates.isEmpty())
        assertEquals(2, report.functions[0].parameterCount)
        assertTrue(report.scoreRationale.any { it.contains("结构健康") })
    }

    @Test
    fun `parameter counting handles multi-line signature`() {
        val code = """
            fun build(
                a: Int,
                b: String,
                c: Double,
                d: List<String>,
                e: Map<String, Int>,
                f: Boolean
            ): String = "x"
        """.trimIndent()
        val spans = behavior.findFunctions(code, "kotlin")
        assertEquals(1, spans.size)
        val metrics = CodeReviewStructureMetrics.measureComplexity(code, spans, "kotlin")
        assertEquals(6, metrics[0].parameterCount)
        assertTrue(metrics[0].hasTooManyParams)
    }

    @Test
    fun `line normalization masks literals for duplicate detection`() {
        assertEquals(
            CodeReviewStructureMetrics.normalizeLine("delay(300)"),
            CodeReviewStructureMetrics.normalizeLine("delay(500)")
        )
        assertEquals(
            CodeReviewStructureMetrics.normalizeLine("""name("alice")"""),
            CodeReviewStructureMetrics.normalizeLine("""name("bob")""")
        )
        // 结构不同则不折叠
        assertTrue(
            CodeReviewStructureMetrics.normalizeLine("delay(300)") !=
                CodeReviewStructureMetrics.normalizeLine("cancel(300)")
        )
    }

    // ═══ 空输入与提示词组装 ═══

    @Test
    fun `blank input yields empty analysis with note`() = runTest {
        val analysis = analyzeFor("   ")
        assertTrue(analysis.findings.isEmpty())
        assertTrue(analysis.analyzerNotes.isNotEmpty())
    }

    @Test
    fun `compose injects findings appendix and focus`() = runTest {
        val ctx = context(
            code = "fun a() {\n    val x = user!!.name\n}",
            language = "kotlin",
            focus = "并发安全"
        )
        val prepared = behavior.prepare(ctx)
        val analysis = behavior.analyze(ctx, prepared)
        val request = behavior.composePrompt(ctx, prepared, analysis)
        assertTrue(request.prompt.contains("确定性分析附录"))
        assertTrue(request.prompt.contains("非空断言"))
        assertTrue(request.prompt.contains("并发安全"))
        assertTrue(request.prompt.contains("第 2 行"))
    }

    // ═══ 契约校验 / 修复 / 渲染 ═══

    private fun requestStub() = com.apex.agent.core.engine.templates.runtime.TemplateTurnRequest(
        prompt = "stub", behaviorId = "builtin_code_review"
    )

    @Test
    fun `validate flags unmarked severity and bad verdict`() {
        val raw = """
            ## 总体结论
            这个代码整体还行。

            ## 问题清单
            1. 命名不规范
            2. 缺少测试
        """.trimIndent()
        val report = behavior.validateOutput(raw, context("x"), requestStub())
        assertTrue(report.violations.any { it.kind == com.apex.agent.core.engine.templates.runtime.TemplateContractViolationKind.BAD_SEVERITY_MARK })
        assertTrue(report.violations.any { it.kind == com.apex.agent.core.engine.templates.runtime.TemplateContractViolationKind.MISSING_VERDICT })
    }

    @Test
    fun `validate accepts well formed output`() {
        val raw = """
            ## 总体结论
            有条件通过：核心逻辑正确，需处理下述阻断项。

            ## 问题清单
            1. 🔴 阻断 user!! 在空输入下崩溃
            2. 🟡 重要 循环内字符串拼接
            3. 🟢 次要 TODO 残留
        """.trimIndent()
        val report = behavior.validateOutput(raw, context("x"), requestStub())
        assertTrue(report.ok)
    }

    @Test
    fun `repair normalizes textual severity markers`() {
        val report = behavior.validateOutput(
            "## 总体结论\n通过。\n## 问题清单\n1. 【阻断】空指针",
            context("x"),
            requestStub()
        )
        val repaired = behavior.repairOutput(
            "## 总体结论\n通过。\n## 问题清单\n1. 【阻断】空指针",
            report,
            context("x")
        )
        assertTrue(repaired.contains("🔴阻断"))
    }

    @Test
    fun `render produces severity summary section`() {
        val raw = """
            ## 总体结论
            需修改。

            ## 问题清单
            1. 🔴 阻断 注入风险
            2. 🔴 阻断 弱哈希
            3. 🟡 重要 硬等待
            4. 🟢 次要 命名
        """.trimIndent()
        val rendered = behavior.renderReport(raw, context("x"), requestStub())
        val summarySection = rendered.sections.first { it.title == "评审摘要" }
        assertTrue(summarySection.lines[0].contains("🔴 阻断 2"))
        assertTrue(summarySection.lines[0].contains("🟡 重要 1"))
        assertEquals("2", rendered.summary[com.apex.agent.core.engine.templates.runtime.TemplateRenderedReport.KEY_BLOCKER_COUNT])
        assertEquals("阻断", rendered.summary[com.apex.agent.core.engine.templates.runtime.TemplateRenderedReport.KEY_SEVERITY_TOP])
        assertEquals("4", rendered.summary[com.apex.agent.core.engine.templates.runtime.TemplateRenderedReport.KEY_FINDING_COUNT])
    }

    // ═══ 端到端（经编排器）═══

    @Test
    fun `full turn through runtime produces enriched prompt`() = runTest {
        val registry = com.apex.agent.core.engine.templates.PromptTemplateRegistry(
            java.io.File("/tmp/code-review-runtime-test-${System.nanoTime()}").apply { mkdirs() }
        )
        registry.reseedBuiltIns()
        val runtime = TemplateRuntime(
            registry = registry,
            behaviors = mapOf(behavior.behaviorId to behavior)
        )
        val activation = runtime.activate(
            "builtin_code_review",
            mapOf("code" to "fun a() {\n    val x = p!!.name\n}", "language" to "kotlin")
        )
        assertTrue(activation.ok)
        val outcome = runtime.prepareTurn(activation)
        assertTrue(outcome.ok)
        assertTrue(outcome.request!!.prompt.contains("确定性分析附录"))
        assertTrue(outcome.request!!.prompt.contains("非空断言"))
        // 回包全链路
        val report = runtime.processResponse(
            activation,
            "## 总体结论\n有条件通过。\n\n## 问题清单\n1. 🔴 阻断 非空断言风险\n2. 🟢 次要 命名过短",
            outcome.request
        )
        assertTrue(report.sections.any { it.title == "评审摘要" })
        assertTrue(report.trace!!.allOk)
    }
}
