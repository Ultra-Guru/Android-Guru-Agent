package com.apex.agent.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P102 激活能力清单测试（纯 JVM；规格 06 §7）。
 *
 * 断言：空列表整段省略 / 渲染去重排序保持输入序 / 预算按声明序截断且
 * Thinking 行（末行）永不截 / 未知状态不编造（省略即如实）。
 */
class EngineActiveExtensionsTest {

    @Test
    fun `empty list omits whole section`() {
        // 空列表 → 段整体省略（不是输出空标题）——旧调用方零变化
        val out = EnginePrompts.renderActiveExtensions(emptyList())
        assertTrue(out.isEmpty())
    }

    @Test
    fun `budget keeps all lines when small`() {
        val lines = listOf(
            "Skills: skill_a, skill_b",
            "Opened tools: CodeRead, CodeGrep",
            "Thinking: STANDARD"
        )
        assertEquals(lines, EnginePrompts.renderActiveExtensions(lines))
    }

    @Test
    fun `budget truncates in declared order and keeps thinking line`() {
        // 五行大列表（超 500 token）：从头部按序截（Skills 先丢），
        // Thinking（末行）永不截 + 追加截断标记。
        val lines = listOf(
            "Skills: " + (1..40).joinToString(", ") { "skill_long_name_number_$it" },
            "Opened tools: " + (1..40).joinToString(", ") { "ToolLongName$it" },
            "MCP connected: server-a, server-b",
            "Fusion: bundle-x",
            "Thinking: AUTO"
        )
        val out = EnginePrompts.renderActiveExtensions(lines)
        assertTrue("超预算被截短", out.size < lines.size)
        assertEquals("Thinking 恒保留（末行不截）", "Thinking: AUTO", out.last())
        assertTrue("截断标记存在", out.any { it.startsWith("… (truncated)") })
        assertFalse("头部行（Skills）被按序牺牲", out.first().startsWith("Skills:"))
    }

    @Test
    fun `single line never truncated even over budget`() {
        // 只剩一行（Thinking）时即便超预算也不截——档位是决策关键
        val lines = listOf("Thinking: " + "AUTO".repeat(300))
        assertEquals(lines, EnginePrompts.renderActiveExtensions(lines))
    }
}
