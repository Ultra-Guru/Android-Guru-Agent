package com.apex.agent.core.engine

import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolRisk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编码工位提示词契约测试（原 #197 双工位分轨测试的 Coding-only 收敛版）。
 *
 * Agent 模式移除（PR 3/6）后的锁定纪律：
 *  1. 身份行：恒为 CODING agent 自述（真实项目工作区编码智能体），
 *     Agent 聊天屏的 "an AI AGENT" 身份措辞已随档位删除；
 *  2. 任务路由同源化：车道推荐只引用本轮实际可见的工具，绝推荐一个
 *     模型本轮调不到的工具；git 工具不可见（降级）时 GitHub 车道整条省略；
 *  3. 共享执行纪律（Tool-Use Policy / 权限阶梯 / 扩展攻略 / 工具清单）
 *     不因档位收敛而丢失。
 */
class WorkstationPromptSplitTest {

    // ── 测试工具桩（带真实 id，喂给任务路由同源判定）──────────────────

    private fun tool(id: String): AgentTool = object : AgentTool {
        override val id: String = id
        override val name: String = id
        override val description: String = "test stub for $id"
        override val parametersSchema: String = "{}"
        override val metadata: ToolMetadata = ToolMetadata(
            id = id,
            category = ToolCategory.UTILITY,
            risk = ToolRisk.LOW
        )

        override suspend fun execute(arguments: String): String = "stub"
    }

    /** Coding 工位典型工具面（含 git 通路）。 */
    private val codingSurface = listOf(
        tool("CodeGitRun"), tool("GithubCreatePullRequest"), tool("WebSearch"), tool("BrowserNavigate")
    )

    /** 无 git 通路的检索面（模拟降级剔除 Code 族工具后的形状）。 */
    private val searchOnlySurface = listOf(
        tool("WebSearch"), tool("WebFetch"), tool("BrowserNavigate"), tool("MemoryRecall")
    )

    private fun prompt(
        cfg: AgentConfig,
        tools: List<AgentTool>
    ): String = EnginePrompts.buildSystemPrompt(
        config = cfg,
        privilegeLevel = "NORMAL",
        visibleTools = tools,
        toolsUnavailable = false
    )

    // ── 档位收敛 ────────────────────────────────────────────────────

    @Test
    fun `coding screen modes cover the full enum`() {
        // Agent 模式移除（PR 3/6）：Agent 聊天屏档位（CHAT/AGENT/LOOP）与
        // 存量档位（SPEC/REFLECTION/HUMAN_ASSIST/CUSTOM）已删，枚举只剩编码三档。
        assertEquals(listOf(AgentMode.BUILD, AgentMode.PLAN, AgentMode.GOAL), AgentMode.entries.toList())
    }

    // ── 身份行 ──────────────────────────────────────────────────────

    @Test
    fun `coding workstation identity is coding oriented and mentions git lane`() {
        val p = prompt(AgentConfig(mode = AgentMode.BUILD), codingSurface)
        val identityBlock = p.lineSequence().take(3).joinToString("\n")
        assertTrue(identityBlock.contains("a CODING agent working in a real project workspace"))
        // Agent 聊天屏的身份措辞已删，不得再出现
        assertFalse(identityBlock.contains("an AI AGENT running"))
        // CODING 工位确实有 git/GitHub 工具 → 身份自述可以声明
        assertTrue(identityBlock.contains("git and GitHub"))
        assertTrue(p.contains("## Mode: BUILD"))
    }

    @Test
    fun `custom agent name applies to coding identity`() {
        val c = prompt(AgentConfig(mode = AgentMode.BUILD, agentName = "小钢炮"), codingSurface)
        assertTrue(c.startsWith("You are 小钢炮, a CODING agent"))
    }

    // ── 任务路由同源化 ──────────────────────────────────────────────

    @Test
    fun `task routing recommends git lane when coding tools are visible`() {
        val p = prompt(AgentConfig(mode = AgentMode.BUILD), codingSurface)
        assertTrue(p.contains("use CodeGitRun"))
        assertTrue(p.contains("GithubCreatePullRequest"))
        assertFalse(p.contains("lives in the CODING workstation"))
    }

    @Test
    fun `task routing omits github lane when git tools are not visible`() {
        // git 工具不可见（降级场景）→ GitHub 车道整条省略（诚实缺省），
        // 可见的车道照常推荐。
        val p = prompt(AgentConfig(mode = AgentMode.BUILD), searchOnlySurface)
        assertTrue(p.contains("## Task Routing"))
        assertFalse(p.contains("CodeGitRun"))
        assertFalse(p.contains("GithubCreatePullRequest"))
        assertTrue(p.contains("WebSearch for orientation"))
        assertTrue(p.contains("BrowserNavigate + Browser* family"))
    }

    @Test
    fun `task routing omitted when no tools are visible`() {
        // 零工具：路由段整段省略（指向不可调用工具只会自相矛盾）
        val p = prompt(AgentConfig(mode = AgentMode.BUILD), emptyList())
        assertFalse(p.contains("## Task Routing"))
        // 有工具但无路由相关工具（只有记忆类）→ 同样省略
        val memoryOnly = prompt(AgentConfig(mode = AgentMode.PLAN), listOf(tool("MemoryRecall")))
        assertFalse(memoryOnly.contains("## Task Routing"))
    }

    // ── 共享执行纪律不受伤 ──────────────────────────────────────────

    @Test
    fun `coding prompt keeps the shared execution spine`() {
        val p = prompt(AgentConfig(mode = AgentMode.BUILD), codingSurface)
        assertTrue(p.contains("## Tool-Use Policy (MANDATORY)"))
        assertTrue(p.contains("## Capability Expansion Playbook"))
        assertTrue(p.contains("## Available Tools"))
    }
}
