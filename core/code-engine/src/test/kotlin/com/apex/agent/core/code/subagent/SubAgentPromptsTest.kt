package com.apex.agent.core.code.subagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SubAgentPrompts.customTypeCatalog] 主代理可见性目录测试。
 *
 * 断言四件事：
 * 1. 空输入零注入（无自定义类型不产生任何 token 开销）；
 * 2. 目录要素齐全：调用方式引导 + key + 描述 + 工具白名单；
 * 3. 缺省回退：无描述回退展示名、无工具省略括注、空 key 条目剔除；
 * 4. 输出为干净段落（无尾随空行），可直接拼进 sessionExtras。
 */
class SubAgentPromptsTest {

    // ═══ 空输入零注入 ═══

    @Test
    fun `empty types produce blank catalog`() {
        assertEquals("", SubAgentPrompts.customTypeCatalog(emptyList()))
    }

    @Test
    fun `blank keys only produce blank catalog`() {
        val types = listOf(CustomSubAgent(key = "  ", displayName = "废条目"))
        assertEquals("全部 key 无效 → 空串（调用方跳过注入）", "", SubAgentPrompts.customTypeCatalog(types))
    }

    // ═══ 目录要素齐全 ═══

    @Test
    fun `catalog contains usage guidance key description and tools`() {
        val catalog = SubAgentPrompts.customTypeCatalog(
            listOf(
                CustomSubAgent(
                    key = "api-auditor",
                    displayName = "API 审计员",
                    description = "审计 OpenAPI 定义与实现的一致性",
                    toolIds = listOf("CodeRead", "CodeGrep")
                )
            )
        )
        assertTrue("标题必须点明经 CodeTask 的调用方式", catalog.contains("subagent_type=custom"))
        assertTrue(catalog.contains("custom_name"))
        assertTrue("key 必须原样出现（模型按它传参）", catalog.contains("api-auditor"))
        assertTrue("描述必须出现（模型据此选角色）", catalog.contains("审计 OpenAPI 定义与实现的一致性"))
        assertTrue("工具白名单必须出现", catalog.contains("CodeRead, CodeGrep"))
    }

    @Test
    fun `catalog lists multiple types in order`() {
        val catalog = SubAgentPrompts.customTypeCatalog(
            listOf(
                CustomSubAgent(key = "a-one", displayName = "甲", description = "甲职责"),
                CustomSubAgent(key = "b-two", displayName = "乙", description = "乙职责")
            )
        )
        val a = catalog.indexOf("- a-one")
        val b = catalog.indexOf("- b-two")
        assertTrue(a >= 0 && b >= 0 && a < b)
    }

    // ═══ 缺省回退 ═══

    @Test
    fun `missing description falls back to display name`() {
        val catalog = SubAgentPrompts.customTypeCatalog(
            listOf(CustomSubAgent(key = "perf-analyst", displayName = "性能分析师"))
        )
        assertTrue(catalog.contains("性能分析师"))
        assertFalse("无描述时不应留下空的破折号尾巴", catalog.contains("— \n"))
    }

    @Test
    fun `blank tool ids omit tools annotation`() {
        val catalog = SubAgentPrompts.customTypeCatalog(
            listOf(
                CustomSubAgent(
                    key = "writer",
                    displayName = "文档写手",
                    description = "整理变更文档",
                    toolIds = listOf("", "  ")
                )
            )
        )
        assertFalse(catalog.contains("工具："))
    }

    // ═══ 干净段落 ═══

    @Test
    fun `catalog has no trailing blank line`() {
        val catalog = SubAgentPrompts.customTypeCatalog(
            listOf(CustomSubAgent(key = "k", displayName = "名", description = "职责"))
        )
        assertFalse("拼进 sessionExtras 前不应带尾随空行", catalog.endsWith("\n"))
        assertTrue(catalog.startsWith("###"))
    }
}
