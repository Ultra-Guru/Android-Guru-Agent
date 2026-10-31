package com.apex.agent.core.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对话实录 #8 回归锁 —— 必填参数 camelCase↔snake_case 别名互认。
 *
 * 实录事故：模型把会话 id 写成 session_id 调 TerminalRun，schema 校验把
 * session_id 当“未知多余键”静默忽略，再报 'sessionId': required but missing
 * —— 同一参数白丢一整轮调用（错误信息也没提驼峰写法）。
 *
 * 修复承诺：
 * 1. camelCase 必填参数的 snake_case 形式在场 → 校验通过（首轮即成功）；
 * 2. snake_case 必填参数的 camelCase 形式在场 → 同样通过（反向对称）；
 * 3. 两种写法都不在 → 仍报 required but missing；
 * 4. 相似但非别名的键不得“顺手”满足必填项。
 */
class ToolSchemaAliasTest {

    /** TerminalRun 形态：sessionId/command 必填（camelCase）。 */
    private val sessionSchema = toolSchema {
        integer("sessionId", required = true)
        string("command", required = true)
        boolean("background")
        integer("timeoutMs")
    }

    /** AgentProviderSetKey 形态：provider_id/api_key 必填（snake_case）。 */
    private val providerSchema = toolSchema {
        string("provider_id", required = true)
        string("api_key", required = true)
    }

    @Test
    fun `snake_case alias satisfies camelCase required param`() {
        // 实录同款：模型写 session_id —— 旧实现在此报 required but missing
        val args = buildJsonObject {
            put("session_id", 1)
            put("command", "ls -la")
        }
        assertTrue("session_id 必须被 sessionId 互认", sessionSchema.validate(args).isValid)
    }

    @Test
    fun `camelCase alias satisfies snake_case required param`() {
        val args = buildJsonObject {
            put("providerId", "openai")
            put("apiKey", "sk-test")
        }
        assertTrue("providerId 必须被 provider_id 互认", providerSchema.validate(args).isValid)
    }

    @Test
    fun `missing param with no alias still reports violation`() {
        val args = buildJsonObject { put("command", "ls") }
        val validation = sessionSchema.validate(args)
        assertFalse("真缺失必须仍报错", validation.isValid)
        assertTrue(
            "报错必须点名 sessionId",
            validation.summary().contains("'sessionId': required but missing")
        )
    }

    @Test
    fun `similar but non-alias keys do not satisfy required param`() {
        val args = buildJsonObject {
            put("command", "ls")
            put("session", 1)          // 相似但非别名
            put("sessionIdX", 1)       // 相似但非别名
        }
        assertFalse(sessionSchema.validate(args).isValid)
    }

    @Test
    fun `validateArguments text path honours aliases end to end`() {
        val raw = """{"session_id": 7, "command": "echo hi"}"""
        assertTrue(sessionSchema.validateArguments(raw).isValid)
        val broken = """{"command": "echo hi"}"""
        assertFalse(sessionSchema.validateArguments(broken).isValid)
    }

    @Test
    fun `name conversions round-trip the two conventions`() {
        assertEquals("session_id", ToolSchema.camelToSnake("sessionId"))
        assertEquals("after_cursor", ToolSchema.camelToSnake("afterCursor"))
        assertEquals("sessionId", ToolSchema.snakeToCamel("session_id"))
        assertEquals("providerId", ToolSchema.snakeToCamel("provider_id"))
        // 边界：已符合目标风格 / 连续下划线
        assertEquals("url", ToolSchema.camelToSnake("url"))
        assertEquals("url", ToolSchema.snakeToCamel("url"))
        assertEquals("aB", ToolSchema.snakeToCamel("a__b"))
    }

    @Test
    fun `fromRendered schema keeps alias behaviour for hand-written terminal tools`() {
        // v1 手写 schema（真实 TerminalRun 用的是字符串 schema → fromRendered 导入）
        val rendered = """
            {"type":"object","properties":{"sessionId":{"type":"integer"},
            "command":{"type":"string"}},"required":["sessionId","command"]}
        """.trimIndent()
        val schema = ToolSchema.fromRendered(rendered)!!
        val args = Json.parseToJsonElement("""{"session_id":1,"command":"pwd"}""").jsonObject
        assertTrue(schema.validate(args).isValid)
    }
}
