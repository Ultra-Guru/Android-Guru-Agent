package com.apex.agent.core.engine.templates.runtime.builtin

import com.apex.agent.core.engine.templates.runtime.TemplateExecutionContext
import com.apex.agent.core.engine.templates.runtime.TemplateTemplateSnapshot
import com.apex.agent.core.engine.templates.runtime.TemplateTurnRequest
import com.apex.agent.core.engine.templates.runtime.TemplateVariableAssignment
import com.apex.agent.core.engine.templates.runtime.TemplateVariableReport
import com.apex.agent.core.engine.templates.runtime.TemplateVariableSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR 12 测试 · 测试用例行为。
 */
class TestCasesTemplateBehaviorTest {

    private val behavior = TestCasesTemplateBehavior()

    private fun context(code: String, framework: String = ""): TemplateExecutionContext =
        TemplateExecutionContext(
            template = TemplateTemplateSnapshot(
                id = "builtin_test_cases",
                name = "测试用例",
                content = "请为下面的代码编写单元测试。",
                declaredVariableNames = listOf("code", "framework"),
                categoryLabel = "CODING",
                isBuiltIn = true
            ),
            variables = TemplateVariableReport(
                listOf(
                    TemplateVariableAssignment("code", code, TemplateVariableSource.USER),
                    TemplateVariableAssignment(
                        "framework", framework,
                        if (framework.isBlank()) TemplateVariableSource.MISSING else TemplateVariableSource.USER
                    )
                )
            )
        )

    private suspend fun analyzeFor(code: String, framework: String = "") =
        behavior.analyze(context(code, framework), behavior.prepare(context(code, framework)))

    private fun requestStub() = TemplateTurnRequest(prompt = "stub", behaviorId = "builtin_test_cases")

    private val kotlinCode = """
        fun processOrder(
            orderId: String,
            amount: Int,
            coupon: String? = null,
            items: List<String>
        ): String {
            return "ok"
        }

        suspend fun refreshCache(force: Boolean = false) {
        }
    """.trimIndent()

    // ═══ 签名提取 ═══

    @Test
    fun `function extraction captures names params and suspend flag`() {
        val functions = TestCasesAnalyzer.extractFunctions(kotlinCode, "kotlin")
        assertEquals(2, functions.size)
        val process = functions.first { it.name == "processOrder" }
        assertEquals(4, process.params.size)
        assertEquals("orderId", process.params[0].name)
        assertEquals("String", process.params[0].type)
        assertEquals("Int", process.params[1].type)
        assertTrue(process.params[2].nullable)
        assertTrue(process.params[2].hasDefault)
        assertEquals(true, functions.first { it.name == "refreshCache" }.isSuspend)
    }

    @Test
    fun `generic params are not split by inner comma`() {
        val params = TestCasesAnalyzer.extractParams("fun f(pairs: Map<String, Int>, flag: Boolean): Unit")
        assertEquals(2, params.size)
        assertEquals("Map<String, Int>", params[0].type)
        assertEquals("Boolean", params[1].type)
    }

    @Test
    fun `pythonic default values infer types`() {
        val params = TestCasesAnalyzer.extractParams("def f(limit=20, name=\"x\", enabled=True, tags=[]):")
        assertEquals(4, params.size)
        assertEquals("Int", params[0].type)
        assertEquals("String", params[1].type)
        assertEquals("Boolean", params[2].type)
        assertEquals("List", params[3].type)
    }

    // ═══ 等价类 ═══

    @Test
    fun `integer params derive numeric boundaries`() {
        val classes = TestCasesAnalyzer.equivalenceClassesFor(TestCaseParam("amount", "Int", nullable = false, hasDefault = false))
        val boundary = classes.first { it.category == "边界值" }
        assertTrue(boundary.values.contains("0"))
        assertTrue(boundary.values.contains("1"))
        assertTrue(boundary.values.contains("-1"))
        assertTrue(boundary.values.contains("Int.MAX_VALUE"))
    }

    @Test
    fun `string params derive empty and length boundaries`() {
        val classes = TestCasesAnalyzer.equivalenceClassesFor(TestCaseParam("name", "String", nullable = false, hasDefault = false))
        val boundary = classes.first { it.category == "边界值" }
        assertTrue(boundary.values.any { it.contains("空串") })
        assertTrue(boundary.values.any { it.contains("单字符") })
        assertTrue(boundary.values.any { it.contains("超长") })
    }

    @Test
    fun `nullable params get null safety class`() {
        val classes = TestCasesAnalyzer.equivalenceClassesFor(TestCaseParam("coupon", "String", nullable = true, hasDefault = true))
        assertTrue(classes.any { it.values.any { v -> v.contains("null") } })
    }

    @Test
    fun `float params include special values`() {
        val classes = TestCasesAnalyzer.equivalenceClassesFor(TestCaseParam("ratio", "Double", nullable = false, hasDefault = false))
        val boundary = classes.first { it.category == "边界值" }
        assertTrue(boundary.values.contains("NaN"))
        assertTrue(boundary.values.contains("Infinity"))
    }

    @Test
    fun `collection params derive empty and single element`() {
        val classes = TestCasesAnalyzer.equivalenceClassesFor(TestCaseParam("items", "List<String>", nullable = false, hasDefault = false))
        val boundary = classes.first { it.category == "边界值" }
        assertTrue(boundary.values.any { it.contains("空集合") })
        assertTrue(boundary.values.any { it.contains("单元素") })
    }

    // ═══ 测试矩阵 ═══

    @Test
    fun `skeletons cover three paths per function`() {
        val analysis = TestCasesAnalyzer.analyze(kotlinCode, "kotlin", "JUnit")
        assertTrue(analysis.skeletons.isNotEmpty())
        val categories = analysis.skeletons.map { it.category }.toSet()
        assertTrue(categories.contains("正常路径"))
        assertTrue(categories.contains("边界值"))
        assertTrue(categories.contains("异常路径"))
        // 命名含函数名
        assertTrue(analysis.skeletons.all { it.name.contains("processOrder") || it.name.contains("refreshCache") })
    }

    @Test
    fun `analysis notes suspend and default params`() {
        val analysis = TestCasesAnalyzer.analyze(kotlinCode, "kotlin", "JUnit")
        assertTrue(analysis.notes.any { it.contains("suspend") })
        assertTrue(analysis.notes.any { it.contains("默认值参数") })
    }

    @Test
    fun `analysis on code without functions degrades gracefully`() {
        val analysis = TestCasesAnalyzer.analyze("这是一段没有函数的描述", null, "JUnit")
        assertTrue(analysis.functions.isEmpty())
        assertTrue(analysis.notes.any { it.contains("未探测到函数签名") })
    }

    // ═══ 框架约定 ═══

    @Test
    fun `framework conventions switch by framework variable`() {
        val junit4 = TestCasesAnalyzer.frameworkConventions("JUnit")
        assertTrue(junit4.any { it.contains("JUnit4") || it.contains("一个测试只验证一个行为") })
        val kotest = TestCasesAnalyzer.frameworkConventions("Kotest")
        assertTrue(kotest.any { it.contains("FunSpec") || it.contains("Arb") })
        val pytest = TestCasesAnalyzer.frameworkConventions("pytest")
        assertTrue(pytest.any { it.contains("parametrize") })
        val jest = TestCasesAnalyzer.frameworkConventions("Jest")
        assertTrue(jest.any { it.contains("it.each") || it.contains("describe") })
        val go = TestCasesAnalyzer.frameworkConventions("go test")
        assertTrue(go.any { it.contains("表驱动") })
        val junit5 = TestCasesAnalyzer.frameworkConventions("JUnit5")
        assertTrue(junit5.any { it.contains("ParameterizedTest") || it.contains("DisplayName") })
    }

    // ═══ 行为 ═══

    @Test
    fun `analyze produces function and skeleton findings`() = runTest {
        val analysis = analyzeFor(kotlinCode)
        val ruleIds = analysis.findings.map { it.ruleId }
        assertTrue(ruleIds.contains("test.functions"))
        assertTrue(ruleIds.contains("test.skeleton"))
        assertTrue(analysis.enrichedHints.any { it.contains("框架约定") })
        assertTrue(analysis.enrichedHints.any { it.contains("等价类总数") })
        assertEquals("2", analysis.metadata["functionCount"])
    }

    @Test
    fun `compose injects matrix appendix`() = runTest {
        val ctx = context(kotlinCode)
        val prepared = behavior.prepare(ctx)
        val analysis = behavior.analyze(ctx, prepared)
        val request = behavior.composePrompt(ctx, prepared, analysis)
        assertTrue(request.prompt.contains("确定性分析附录"))
        assertTrue(request.prompt.contains("被测函数"))
        assertTrue(request.prompt.contains("用例骨架"))
    }

    @Test
    fun `contract requires three sections and code fence`() {
        assertEquals(
            listOf("等价类与边界条件", "覆盖矩阵", "测试代码"),
            behavior.manifest().outputContract.requiredTitles
        )
        // 无围栏 → 违规
        val report = behavior.validateOutput(
            "## 等价类与边界条件\n数字边界\n## 覆盖矩阵\n- 正常\n## 测试代码\n文字描述没有代码块",
            context("x"),
            requestStub()
        )
        assertTrue(report.violations.any { it.detail.contains("围栏") })
    }

    @Test
    fun `validate accepts fenced test code`() {
        val raw = """
            ## 等价类与边界条件
            - amount: 0/1/-1/MAX

            ## 覆盖矩阵
            - 正常路径 1 条
            - 边界 4 条
            - 异常 2 条

            ## 测试代码
            ```
            @Test
            fun testProcessOrder_normal() {}
            ```
        """.trimIndent()
        val report = behavior.validateOutput(raw, context(kotlinCode), requestStub())
        assertTrue(report.ok)
    }

    @Test
    fun `render summary carries derivation stats`() {
        val raw = """
            ## 等价类与边界条件
            - 数字边界 0/1/-1

            ## 覆盖矩阵
            - 正常 1
            - 边界 3

            ## 测试代码
            ```
            @Test fun t() {}
            ```
        """.trimIndent()
        val rendered = behavior.renderReport(raw, context(kotlinCode), requestStub())
        assertEquals("2", rendered.summary["functionCount"])
        assertTrue((rendered.summary["paramCount"]?.toIntOrNull() ?: 0) >= 5)
        assertTrue((rendered.summary["skeletonCount"]?.toIntOrNull() ?: 0) >= 6)
    }

    // ═══ 端到端 ═══

    @Test
    fun `full turn through runtime`() = runTest {
        val dir = java.io.File("/tmp/testcases-runtime-${System.nanoTime()}").apply { mkdirs() }
        val registry = com.apex.agent.core.engine.templates.PromptTemplateRegistry(dir)
        registry.reseedBuiltIns()
        val runtime = com.apex.agent.core.engine.templates.runtime.TemplateRuntime(
            registry = registry,
            behaviors = mapOf(behavior.behaviorId to behavior)
        )
        val activation = runtime.activate(
            "builtin_test_cases",
            mapOf("code" to kotlinCode, "framework" to "JUnit")
        )
        assertTrue(activation.ok)
        val outcome = runtime.prepareTurn(activation)
        assertTrue(outcome.ok)
        assertTrue(outcome.request!!.prompt.contains("被测函数基线"))
        val report = runtime.processResponse(
            activation,
            """
            ## 等价类与边界条件
            - amount: 0 / 1 / -1

            ## 覆盖矩阵
            - processOrder 正常
            - processOrder 边界
            - processOrder 异常

            ## 测试代码
            ```
            @Test
            fun `processOrder empty order id`() {}
            ```
            """.trimIndent(),
            outcome.request
        )
        assertTrue(report.trace!!.allOk)
        assertEquals("2", report.summary["functionCount"])
    }
}
