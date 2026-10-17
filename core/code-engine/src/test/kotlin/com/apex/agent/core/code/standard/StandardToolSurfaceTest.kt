package com.apex.agent.core.code.standard

import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.DefaultToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * StandardToolSurface 工具编排层测试：
 * 别名归一 / 画像过滤 / 合成 task 工具 / 指南文本。
 */
class StandardToolSurfaceTest {

    private class FakeTool(
        override val id: String,
        override val description: String = "fake"
    ) : AgentTool {
        override val name: String get() = id
        override val parametersSchema: String =
            """{"type":"object","properties":{},"required":[]}"""
        override suspend fun execute(arguments: String): String = "ok"
    }

    private fun registry(vararg ids: String): DefaultToolRegistry =
        DefaultToolRegistry().apply { ids.forEach { register(FakeTool(it)) } }

    // ═══ 别名归一 ═══

    @Test
    fun `aliases resolve to registry ids`() {
        assertEquals("CodeRead", StandardToolSurface.resolveRegistryId("read"))
        assertEquals("CodeRead", StandardToolSurface.resolveRegistryId("Read"))
        assertEquals("CodeWrite", StandardToolSurface.resolveRegistryId("write"))
        assertEquals("CodeEdit", StandardToolSurface.resolveRegistryId("edit"))
        assertEquals("CodeEdit", StandardToolSurface.resolveRegistryId("multiedit"))
        assertEquals("ShellExecute", StandardToolSurface.resolveRegistryId("bash"))
        assertEquals("CodeGrep", StandardToolSurface.resolveRegistryId("grep"))
        assertEquals("CodeGlob", StandardToolSurface.resolveRegistryId("glob"))
        assertEquals("CodeTodo", StandardToolSurface.resolveRegistryId("todowrite"))
        assertEquals("CodeCheck", StandardToolSurface.resolveRegistryId("lint"))
    }

    @Test
    fun `unknown and mcp names pass through unchanged`() {
        assertEquals("McpGithubCreateIssue", StandardToolSurface.resolveRegistryId("McpGithubCreateIssue"))
        assertEquals("CodeRead", StandardToolSurface.resolveRegistryId("CodeRead"))
        assertEquals("WebSearch", StandardToolSurface.resolveRegistryId("WebSearch"))
    }

    @Test
    fun `resolveRegistryId tolerates case and separator drift`() {
        // 大小写漂移
        assertEquals("ShellExecute", StandardToolSurface.resolveRegistryId("Bash"))
        assertEquals("CodeTodo", StandardToolSurface.resolveRegistryId("TODO"))
        assertEquals("Task", StandardToolSurface.resolveRegistryId("TASK"))
        // 连字符 / 点号 → 下划线（multiedit 别名无分隔符，大小写变体单独验）
        assertEquals("CodeRead", StandardToolSurface.resolveRegistryId("Read-File"))
        assertEquals("CodeRead", StandardToolSurface.resolveRegistryId("read.file"))
        assertEquals("CodeEdit", StandardToolSurface.resolveRegistryId("MultiEdit"))
        // 归一后仍未命中 → 原名原样返回（既有语义，不改写大小写/分隔符）
        assertEquals("Totally-Unknown", StandardToolSurface.resolveRegistryId("Totally-Unknown"))
    }

    @Test
    fun `suggestToolIds finds near misses within distance or prefix`() {
        val surface = listOf(
            "CodeRead", "CodeEdit", "CodeWrite", "CodeGrep", "ShellExecute", "Task"
        )
        // 编辑距离 ≤ 2（red → read 距离1 / grep 距离2，均命中，保持原序）
        assertEquals(
            listOf("CodeRead", "CodeGrep"),
            StandardToolSurface.suggestToolIds("red", surface)
        )
        // 前缀/包含（tas → task）
        assertEquals(listOf("Task"), StandardToolSurface.suggestToolIds("tas", surface))
        // 归一后完全命中（大小写 + 连字符 + code_ 前缀）
        assertEquals(listOf("CodeGrep"), StandardToolSurface.suggestToolIds("Code-Grep", surface))
        assertEquals(listOf("CodeRead"), StandardToolSurface.suggestToolIds("READ", surface))
        // 无相近候选 → 空
        assertEquals(emptyList<String>(), StandardToolSurface.suggestToolIds("frobnicate", surface))
        // 空名/只剩前缀 → 空（防短名噪音）
        assertEquals(emptyList<String>(), StandardToolSurface.suggestToolIds("", surface))
        assertEquals(emptyList<String>(), StandardToolSurface.suggestToolIds("code_", surface))
    }

    @Test
    fun `suggestToolIds caps at three candidates in available order`() {
        val surface = listOf("CodeRead", "CodeEdit", "CodeGrep", "ShellExecute")
        // “e” 前缀/包含命中多个 → 只取前 3（保持 available 原序）
        assertEquals(
            listOf("CodeRead", "CodeEdit", "CodeGrep"),
            StandardToolSurface.suggestToolIds("e", surface)
        )
    }

    @Test
    fun `task tool detection`() {
        assertTrue(StandardToolSurface.isSyntheticTaskTool("Task"))
        assertTrue(StandardToolSurface.isSyntheticTaskTool("subagent"))
        assertFalse(StandardToolSurface.isSyntheticTaskTool("CodeRead"))
    }

    // ═══ 工具面构建 ═══

    @Test
    fun `build profile sees full registry plus task tool`() {
        val r = registry("CodeRead", "CodeEdit", "ShellExecute", "WebSearch", "McpXy")
        val plan = StandardToolSurface.buildToolPlan(StandardAgentCatalog.BUILD, r)
        val names = plan.definitions.map { it.name }
        assertTrue(names.contains("CodeRead"))
        assertTrue(names.contains("ShellExecute"))
        assertTrue(names.contains("McpXy"))
        assertTrue("合成 task 恒在场", names.contains("Task"))
    }

    @Test
    fun `plan profile filters to readonly allowlist without task`() {
        val r = registry(
            "CodeRead", "CodeGrep", "CodeGlob", "CodeCheck",
            "CodeGitStatus", "CodeGitDiff", "CodeGitLog",
            "CodeWrite", "CodeEdit", "ShellExecute", "WebSearch"
        )
        val plan = StandardToolSurface.buildToolPlan(StandardAgentCatalog.PLAN, r)
        val names = plan.definitions.map { it.name }
        assertTrue(names.contains("CodeRead"))
        assertTrue(names.contains("CodeGitStatus"))
        assertFalse("规划师不该看到写工具", names.contains("CodeWrite"))
        assertFalse(names.contains("CodeEdit"))
        assertFalse(names.contains("ShellExecute"))
        // WebSearch 不在 PLAN 白名单（提示词宣告其不可用）
        assertFalse(names.contains("WebSearch"))
    }

    @Test
    fun `explore subagent gets readonly surface and no task tool`() {
        val r = registry("CodeRead", "CodeGrep", "CodeWrite", "ShellExecute")
        val plan = StandardToolSurface.buildToolPlan(StandardAgentCatalog.EXPLORE, r)
        val names = plan.definitions.map { it.name }
        assertTrue(names.contains("CodeRead"))
        assertFalse(names.contains("CodeWrite"))
        assertFalse(names.contains("ShellExecute"))
        assertFalse("子代理不能再派子代理", names.contains("Task"))
    }

    @Test
    fun `forced tools narrow the surface and drop task`() {
        val r = registry("CodeRead", "CodeEdit", "CodeWrite")
        val plan = StandardToolSurface.buildToolPlan(
            StandardAgentCatalog.BUILD, r,
            forcedToolIds = setOf("CodeEdit")
        )
        val names = plan.definitions.map { it.name }
        assertEquals(listOf("CodeEdit"), names)
    }

    @Test
    fun `exposeAll overrides profile allowlist`() {
        val r = registry("CodeRead", "CodeWrite", "ShellExecute")
        val plan = StandardToolSurface.buildToolPlan(
            StandardAgentCatalog.PLAN, r, exposeAll = true
        )
        val names = plan.definitions.map { it.name }
        assertTrue(names.contains("CodeWrite"))
    }

    @Test
    fun `tool plan is capped at budget`() {
        val ids = (1..80).map { "tool_%02d".format(it) }
        val r = registry(*ids.toTypedArray())
        val plan = StandardToolSurface.buildToolPlan(StandardAgentCatalog.GENERAL, r)
        // ≤ MAX_TOOLS + 1（task 合成不占预算）
        assertTrue(plan.definitions.size <= StandardToolSurface.MAX_TOOLS + 1)
    }

    // ═══ 合成工具定义 ═══

    @Test
    fun `synthetic task tool definition has description and schema`() {
        val def = StandardToolSurface.syntheticTaskTool()
        assertEquals("Task", def.name)
        assertTrue(def.description.contains("sub-agent"))
        // 四种子代理类型（explore / research / general / reviewer，v3）
        assertTrue(def.parameters.contains("\"enum\": [\"explore\", \"research\", \"general\", \"reviewer\"]"))
        assertTrue(def.description.contains("path:line evidence"))
        assertTrue(def.description.contains("sourced conclusions"))
        assertTrue(def.description.contains("merge verdict"))
        assertTrue(def.description.contains("full-surface autonomous executor"))
        // 用法精华：自包含 / 写码或纯调研 / 结果仅主代理可见
        assertTrue(def.description.contains("does NOT see this conversation"))
        assertTrue(def.description.contains("WRITE CODE or only"))
        assertTrue(def.description.contains("summarize it for the user"))
        assertTrue(def.parameters.contains("description"))
        assertTrue(def.parameters.contains("prompt"))
        assertTrue(def.parameters.contains("subagent_type"))
        assertTrue(def.parameters.contains("whether to write code or research only"))
    }

    // ═══ 指南文本 ═══

    @Test
    fun `tool guide lists tools and aliases`() {
        val guide = StandardToolSurface.buildToolGuide(
            StandardAgentCatalog.BUILD,
            listOf("CodeRead", "CodeEdit", "Task")
        )
        assertTrue(guide.contains("CodeRead"))
        assertTrue(guide.contains("Task"))
        assertTrue(guide.contains("read→CodeRead"))
        assertTrue(guide.contains("subagent_type"))
        // 三类型 + 结果对用户不可见需自己转述
        assertTrue(guide.contains("explore ="))
        assertTrue(guide.contains("research ="))
        assertTrue(guide.contains("general ="))
        assertTrue(guide.contains("invisible to the user"))
    }

    @Test
    fun `tool guide empty surface answers directly`() {
        val guide = StandardToolSurface.buildToolGuide(StandardAgentCatalog.BUILD, emptyList())
        assertTrue(guide.contains("answer directly"))
    }

    @Test
    fun `readonly guide declares the boundary`() {
        val guide = StandardToolSurface.buildToolGuide(
            StandardAgentCatalog.PLAN, listOf("CodeRead")
        )
        assertTrue(guide.contains("READ-ONLY"))
    }

    @Test
    fun `planHasTodo detection`() {
        assertTrue(StandardToolSurface.planHasTodo(listOf("CodeTodo")))
        assertTrue(StandardToolSurface.planHasTodo(listOf("todo")))
        assertFalse(StandardToolSurface.planHasTodo(listOf("CodeRead")))
    }
}
