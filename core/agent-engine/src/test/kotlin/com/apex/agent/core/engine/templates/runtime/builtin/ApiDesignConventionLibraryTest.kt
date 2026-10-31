package com.apex.agent.core.engine.templates.runtime.builtin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR 18 测试 · API 设计约定库与骨架生成。
 */
class ApiDesignConventionLibraryTest {

    // ═══ 端点解析 ═══

    @Test
    fun `endpoint lines parse method and segments`() {
        val endpoints = ApiDesignConventionLibrary.parseEndpoints(
            """
            ## 资源与端点清单
            - GET /orders — 订单列表
            - POST /orders — 新建订单
            - GET /orders/{orderId} — 订单详情
            - DELETE /orders/{orderId} — 删除订单
            """.trimIndent()
        )
        assertEquals(4, endpoints.size)
        assertEquals("GET", endpoints[0].method)
        assertEquals(listOf("orders"), endpoints[0].segments)
        assertEquals(listOf("orderId"), endpoints[2].params)
    }

    @Test
    fun `non endpoint lines are ignored`() {
        assertTrue(
            ApiDesignConventionLibrary.parseEndpoints("纯文本需求，没有端点形态").isEmpty()
        )
    }

    // ═══ 命名规范校验 ═══

    @Test
    fun `verb in path is flagged`() {
        val issues = ApiDesignConventionLibrary.lintNaming(
            ApiDesignConventionLibrary.parseEndpoints("GET /orders/create")
        )
        assertEquals(1, issues.size)
        assertEquals(ApiNamingIssue.Kind.VERB_IN_PATH, issues.first().kind)
        assertTrue(issues.first().fix.contains("方法"))
    }

    @Test
    fun `singular resource is flagged with plural fix`() {
        val issues = ApiDesignConventionLibrary.lintNaming(
            ApiDesignConventionLibrary.parseEndpoints("GET /order")
        )
        assertEquals(1, issues.size)
        assertEquals(ApiNamingIssue.Kind.SINGULAR_NOUN, issues.first().kind)
        assertEquals("orders", issues.first().fix)
    }

    @Test
    fun `irregular plural is suggested correctly`() {
        val issues = ApiDesignConventionLibrary.lintNaming(
            ApiDesignConventionLibrary.parseEndpoints("GET /category")
        )
        assertEquals("categories", issues.first().fix)
    }

    @Test
    fun `underscore in path is flagged with kebab fix`() {
        val issues = ApiDesignConventionLibrary.lintNaming(
            ApiDesignConventionLibrary.parseEndpoints("GET /order_items")
        )
        assertEquals(ApiNamingIssue.Kind.BAD_CASE, issues.first().kind)
        assertTrue(issues.first().fix.startsWith("order-items"))
    }

    @Test
    fun `deep nesting is flagged`() {
        val issues = ApiDesignConventionLibrary.lintNaming(
            ApiDesignConventionLibrary.parseEndpoints("GET /users/{id}/orders/{oid}/items")
        )
        assertTrue(issues.any { it.kind == ApiNamingIssue.Kind.TOO_DEEP })
        assertTrue(issues.first { it.kind == ApiNamingIssue.Kind.TOO_DEEP }.fix.contains("顶层"))
    }

    @Test
    fun `mixed param naming styles are flagged`() {
        val issues = ApiDesignConventionLibrary.lintNaming(
            ApiDesignConventionLibrary.parseEndpoints(
                "GET /orders/{order_id}/items/{itemId}"
            )
        )
        assertTrue(
            issues.any { it.kind == ApiNamingIssue.Kind.INCONSISTENT_PARAMS }
        )
    }

    @Test
    fun `clean rest endpoints pass lint`() {
        val issues = ApiDesignConventionLibrary.lintNaming(
            ApiDesignConventionLibrary.parseEndpoints(
                """
                GET /orders
                POST /orders
                GET /orders/{id}
                PATCH /orders/{id}
                DELETE /orders/{id}
                """.trimIndent()
            )
        )
        assertTrue(issues.isEmpty())
    }

    // ═══ 状态码税则 ═══

    @Test
    fun `status advice covers crud actions`() {
        assertEquals("201 Created + Location 头", ApiDesignConventionLibrary.statusAdviceFor("create")!!.success)
        assertEquals("204 No Content", ApiDesignConventionLibrary.statusAdviceFor("delete")!!.success)
        assertTrue(ApiDesignConventionLibrary.statusAdviceFor("list")!!.antiPattern.contains("404"))
    }

    @Test
    fun `status advice resolves aliases`() {
        assertEquals(
            ApiDesignConventionLibrary.statusAdviceFor("create"),
            ApiDesignConventionLibrary.statusAdviceFor("新增")
        )
        assertTrue(ApiDesignConventionLibrary.statusAdviceFor("删除") != null)
    }

    @Test
    fun `unknown action has no advice`() {
        assertTrue(ApiDesignConventionLibrary.statusAdviceFor("玄学") == null)
    }

    // ═══ 错误模型 ═══

    @Test
    fun `problem skeleton contains rfc7807 fields`() {
        val skeleton = ApiDesignConventionLibrary.problemSkeleton(409, "order-conflict")
        assertTrue(skeleton.contains("\"type\""))
        assertTrue(skeleton.contains("\"title\""))
        assertTrue(skeleton.contains("\"status\": 409"))
        assertTrue(skeleton.contains("order-conflict"))
    }

    @Test
    fun `error registry skeleton has table structure`() {
        val registry = ApiDesignConventionLibrary.errorCodeRegistrySkeleton("order")
        assertTrue(registry.contains("ORDER_NOT_FOUND"))
        assertTrue(registry.contains("ORDER_CONFLICT"))
        assertTrue(registry.contains("一码多用"))
    }

    // ═══ 横切关注点清单 ═══

    @Test
    fun `required checklist covers five pillars`() {
        val required = ApiDesignConventionLibrary.requiredChecklist()
        assertTrue(required.size >= 5)
        assertTrue(required.any { it.name == "分页" })
        assertTrue(required.any { it.name == "鉴权" })
        assertTrue(required.any { it.name == "幂等" })
        assertTrue(required.none { it.name == "限流" })
    }

    @Test
    fun `each checklist item has pitfall guidance`() {
        for (item in ApiDesignConventionLibrary.CROSS_CUTTING_CHECKLIST) {
            assertTrue("${item.name} 缺约定", item.convention.isNotBlank())
            assertTrue("${item.name} 缺陷阱", item.pitfall.isNotBlank())
        }
    }

    // ═══ OpenAPI 骨架生成 ═══

    @Test
    fun `openapi skeleton builds paths and schemas`() {
        val analysis = ApiDesignAnalyzer.analyze(
            "管理订单：支持查询订单列表、创建订单、删除订单", "RESTful"
        )
        val skeleton = ApiDesignConventionLibrary.openApiSkeleton(analysis)
        assertTrue(skeleton.contains("openapi: 3.1.0"))
        assertTrue(skeleton.contains("paths:"))
        assertTrue(skeleton.contains("/orders:"))
        assertTrue(skeleton.contains("get:"))
        assertTrue(skeleton.contains("post:"))
        assertTrue(skeleton.contains("delete:"))
        assertTrue(skeleton.contains("components:"))
    }

    @Test
    fun `operation id follows verb noun convention`() {
        assertEquals("listOrders", ApiDesignConventionLibrary.operationIdOf("list", "orders"))
        assertEquals("getOrder", ApiDesignConventionLibrary.operationIdOf("get", "orders"))
        assertEquals("createOrder", ApiDesignConventionLibrary.operationIdOf("create", "orders"))
    }

    @Test
    fun `schema names are pascal case`() {
        assertEquals("OrderItem", ApiDesignConventionLibrary.schemaNameOf("order-item"))
        assertEquals("OrderItem", ApiDesignConventionLibrary.schemaNameOf("order_item"))
    }

    @Test
    fun `pluralize and singularize are inverse`() {
        for (word in listOf("order", "category", "policy", "box")) {
            val plural = ApiDesignConventionLibrary.pluralize(word)
            assertEquals(word, ApiDesignConventionLibrary.singularize(plural))
        }
    }

    // ═══ GraphQL 对照 ═══

    @Test
    fun `rest operations have graphql counterparts`() {
        val counterpart = ApiDesignConventionLibrary.graphQlCounterpartOf(
            ApiOperation("list", "orders", "GET /orders", "Query.orders", true)
        )
        assertTrue(counterpart.contains("Query."))
        assertTrue(counterpart.contains("Connection"))

        val mutation = ApiDesignConventionLibrary.graphQlCounterpartOf(
            ApiOperation("create", "orders", "POST /orders", "Mutation", true)
        )
        assertTrue(mutation.contains("Mutation.create"))
    }

    // ═══ 规则目录 ═══

    @Test
    fun `rule catalog is stable`() {
        assertEquals(9, ApiDesignConventionLibrary.RULE_IDS.size)
        assertTrue(ApiDesignConventionLibrary.RULE_IDS.contains("api.naming-verb-in-path"))
    }
}
