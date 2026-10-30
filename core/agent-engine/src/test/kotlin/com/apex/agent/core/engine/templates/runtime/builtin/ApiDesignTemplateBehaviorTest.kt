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
 * PR 11 测试 · API 设计行为。
 */
class ApiDesignTemplateBehaviorTest {

    private val behavior = ApiDesignTemplateBehavior()

    private fun context(requirement: String, style: String = ""): TemplateExecutionContext =
        TemplateExecutionContext(
            template = TemplateTemplateSnapshot(
                id = "builtin_api_design",
                name = "API 设计",
                content = "请为以下需求设计一组 API。",
                declaredVariableNames = listOf("requirement", "style"),
                categoryLabel = "CODING",
                isBuiltIn = true
            ),
            variables = TemplateVariableReport(
                listOf(
                    TemplateVariableAssignment("requirement", requirement, TemplateVariableSource.USER),
                    TemplateVariableAssignment(
                        "style", style,
                        if (style.isBlank()) TemplateVariableSource.MISSING else TemplateVariableSource.USER
                    )
                )
            )
        )

    private suspend fun analyzeFor(requirement: String, style: String = "") =
        behavior.analyze(context(requirement, style), behavior.prepare(context(requirement, style)))

    private fun requestStub() = TemplateTurnRequest(prompt = "stub", behaviorId = "builtin_api_design")

    private val sampleRequirement = """
        商品管理：查询商品列表、按 id 获取商品详情、创建商品、更新价格、删除商品。
        订单：创建订单、查询订单列表、订单审核（通过/驳回）、导出订单报表。
        用户需要登录鉴权；金额字段精确到分；状态字段有取值域。
    """.trimIndent()

    // ═══ 资源抽取 ═══

    @Test
    fun `resource extraction clusters chinese and english nouns`() {
        val analysis = ApiDesignAnalyzer.analyze(sampleRequirement, "RESTful")
        val names = analysis.resources.map { it.name }
        assertTrue(names.contains("商品"))
        assertTrue(names.contains("订单"))
        // 按提及次数排序（商品/订单靠前）
        assertEquals("商品", analysis.resources.first().name)
    }

    @Test
    fun `resource extraction normalizes english plurals`() {
        assertEquals("products", ApiDesignAnalyzer.normalizeResourceName("product"))
        assertEquals("products", ApiDesignAnalyzer.normalizeResourceName("products"))
        assertEquals("order-items", ApiDesignAnalyzer.normalizeResourceName("orderItem"))
    }

    @Test
    fun `collection suffixes are stripped`() {
        val analysis = ApiDesignAnalyzer.analyze("订单列表需要分页", "RESTful")
        assertTrue(analysis.resources.any { it.name == "订单" })
    }

    // ═══ 操作推断 ═══

    @Test
    fun `operation inference maps verbs to methods`() {
        val analysis = ApiDesignAnalyzer.analyze(sampleRequirement, "RESTful")
        val actions = analysis.operations.map { it.action }
        assertTrue(actions.contains("list"))
        assertTrue(actions.contains("get"))
        assertTrue(actions.contains("create"))
        assertTrue(actions.contains("update"))
        assertTrue(actions.contains("delete"))
        assertTrue(actions.contains("review"))
        assertTrue(actions.contains("export"))
        // REST 建议形态
        val create = analysis.operations.first { it.action == "create" }
        assertTrue(create.restHint.startsWith("POST /"))
        val list = analysis.operations.first { it.action == "list" }
        assertTrue(list.restHint.startsWith("GET /"))
        // 幂等标注
        assertTrue(list.idempotent)
        assertTrue(!create.idempotent)
    }

    @Test
    fun `operation hints include graphql alternative`() {
        val analysis = ApiDesignAnalyzer.analyze("查询订单列表", "GraphQL")
        val list = analysis.operations.first { it.action == "list" }
        assertTrue(list.graphQlHint.contains("query"))
    }

    // ═══ 字段推断 ═══

    @Test
    fun `field type inference maps semantics`() {
        val fields = ApiDesignAnalyzer.inferFields("按 id 查询；返回创建时间与更新时间；金额精确；status 有取值域；需要分页")
        val types = fields.map { it.type }
        assertTrue(types.any { it.contains("ID") })
        assertTrue(types.any { it.contains("ISO-8601") })
        assertTrue(types.any { it.contains("decimal") })
        assertTrue(types.any { it == "enum" })
        assertTrue(types.any { it.contains("pagination") })
    }

    @Test
    fun `money fields map to decimal string`() {
        val fields = ApiDesignAnalyzer.inferFields("价格与金额不能有浮点误差")
        assertTrue(fields.any { it.type.contains("decimal") })
        assertTrue(fields.none { it.type.contains("float") })
    }

    // ═══ 约定库 ═══

    @Test
    fun `convention libraries are complete`() {
        assertEquals(5, ApiDesignAnalyzer.REST_METHOD_SEMANTICS.size)
        assertTrue(ApiDesignAnalyzer.STATUS_CODE_TAXONOMY.size >= 11)
        assertTrue(ApiDesignAnalyzer.STATUS_CODE_TAXONOMY.any { it.first == 429 })
        assertTrue(ApiDesignAnalyzer.CROSS_CUTTING_TEMPLATES.any { it.contains("幂等") })
        assertTrue(ApiDesignAnalyzer.GRAPHQL_NOTES.any { it.contains("Connections") })
    }

    // ═══ 行为 ═══

    @Test
    fun `analyze produces resources operations and style findings`() = runTest {
        val analysis = analyzeFor(sampleRequirement)
        val ruleIds = analysis.findings.map { it.ruleId }
        assertTrue(ruleIds.contains("api.resources"))
        assertTrue(ruleIds.contains("api.operation"))
        assertTrue(ruleIds.contains("api.style"))
        assertTrue(analysis.enrichedHints.any { it.contains("资源基线") })
        assertTrue(analysis.enrichedHints.any { it.contains("状态码税则") })
        assertEquals("false", analysis.metadata["isGraphQl"])
    }

    @Test
    fun `graphql style switches conventions`() = runTest {
        val analysis = analyzeFor(sampleRequirement, style = "GraphQL")
        assertTrue(analysis.enrichedHints.any { it.contains("GraphQL 横切要点") })
        assertEquals("true", analysis.metadata["isGraphQl"])
    }

    @Test
    fun `compose injects convention appendix`() = runTest {
        val ctx = context(sampleRequirement)
        val prepared = behavior.prepare(ctx)
        val analysis = behavior.analyze(ctx, prepared)
        val request = behavior.composePrompt(ctx, prepared, analysis)
        assertTrue(request.prompt.contains("确定性分析附录"))
        assertTrue(request.prompt.contains("资源清单"))
        assertTrue(request.prompt.contains("横切关注点"))
    }

    @Test
    fun `contract requires four sections`() {
        assertEquals(
            listOf("资源与端点清单", "请求与响应结构", "错误码设计", "横切关注点说明"),
            behavior.manifest().outputContract.requiredTitles
        )
    }

    @Test
    fun `validate flags missing sections`() {
        val raw = "## 资源与端点清单\n- GET /orders"
        val report = behavior.validateOutput(raw, context("x"), requestStub())
        assertTrue(report.violations.any { it.section == "请求与响应结构" })
        assertTrue(report.violations.any { it.section == "错误码设计" })
        assertTrue(report.violations.any { it.section == "横切关注点说明" })
    }

    @Test
    fun `render summary carries design stats`() {
        val raw = """
            ## 资源与端点清单
            - GET /orders

            ## 请求与响应结构
            { id, status }

            ## 错误码设计
            400 参数错误

            ## 横切关注点说明
            游标分页
        """.trimIndent()
        val rendered = behavior.renderReport(raw, context(sampleRequirement), requestStub())
        assertTrue((rendered.summary["resourceCount"]?.toIntOrNull() ?: 0) >= 2)
        assertTrue((rendered.summary["operationCount"]?.toIntOrNull() ?: 0) >= 5)
        assertEquals("RESTful", rendered.summary["style"])
    }

    // ═══ 端到端 ═══

    @Test
    fun `full turn through runtime`() = runTest {
        val dir = java.io.File("/tmp/api-runtime-${System.nanoTime()}").apply { mkdirs() }
        val registry = com.apex.agent.core.engine.templates.PromptTemplateRegistry(dir)
        registry.reseedBuiltIns()
        val runtime = com.apex.agent.core.engine.templates.runtime.TemplateRuntime(
            registry = registry,
            behaviors = mapOf(behavior.behaviorId to behavior)
        )
        val activation = runtime.activate(
            "builtin_api_design",
            mapOf("requirement" to sampleRequirement, "style" to "RESTful")
        )
        assertTrue(activation.ok)
        val outcome = runtime.prepareTurn(activation)
        assertTrue(outcome.ok)
        assertTrue(outcome.request!!.prompt.contains("资源基线"))
        val report = runtime.processResponse(
            activation,
            """
            ## 资源与端点清单
            - GET /orders
            - POST /orders

            ## 请求与响应结构
            订单结构体

            ## 错误码设计
            400/401/404

            ## 横切关注点说明
            游标分页 + 幂等键
            """.trimIndent(),
            outcome.request
        )
        assertTrue(report.trace!!.allOk)
    }
}
