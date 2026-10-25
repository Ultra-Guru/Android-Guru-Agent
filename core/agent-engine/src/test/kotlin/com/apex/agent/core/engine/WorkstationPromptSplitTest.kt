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
 * #197 双工位绝对分离 — 提示词分轨契约测试。
 *
 * 锁定三条分轨纪律（防止未来重构把两工位的系统提示词又搅在一起）：
 *  1. 身份行分轨：AGENT 工位自称 AI AGENT（非编程全能），CODING 工位
 *     自称编码智能体；AGENT 身份不再声明 GitHub（工具计划已机械剔除，
 *     提示词与能力面同源，不教假事实）；
 *  2. 聊天 UX 段分轨：Conversational Openness / First-Turn Greeting /
 *     Emotional Attunement / Vague Request 是聊天屏交互规格，仅
 *     AGENT 工位注入 —— coding 会话不携带闲聊姿态；
 *  3. 任务路由同源化：车道推荐只引用本轮实际可见的工具，被工位隔离
 *     过滤掉的工具（Code 族与 Github 族）绝不出现在 AGENT 工位的推荐里。
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

    /** Agent 工位典型工具面（Code 族与 Github 族已被计划层剔除后的形状）。 */
    private val agentSurface = listOf(
        tool("WebSearch"), tool("WebFetch"), tool("BrowserNavigate"), tool("MemoryRecall")
    )

    /** Coding 工位典型工具面（含 git 通路）。 */
    private val codingSurface = listOf(
        tool("CodeGitRun"), tool("GithubCreatePullRequest"), tool("WebSearch"), tool("BrowserNavigate")
    )

    private fun prompt(
        cfg: AgentConfig,
        tools: List<AgentTool>,
        firstTurnGreeting: Boolean = false,
        chatSignal: ChatSignal? = null
    ): String = EnginePrompts.buildSystemPrompt(
        config = cfg,
        privilegeLevel = "NORMAL",
        visibleTools = tools,
        toolsUnavailable = false,
        firstTurnGreeting = firstTurnGreeting,
        chatSignal = chatSignal
    )

    // ── 工位推导 ────────────────────────────────────────────────────

    @Test
    fun `workstation derivation covers every mode`() {
        // Agent 屏三模式 + 存量四模式（历史活跃于聊天屏）→ AGENT 工位
        listOf(
            AgentMode.CHAT, AgentMode.AGENT, AgentMode.LOOP,
            AgentMode.SPEC, AgentMode.REFLECTION, AgentMode.HUMAN_ASSIST, AgentMode.CUSTOM
        ).forEach {
            assertEquals(EngineWorkstation.AGENT, it.workstation)
        }
        // Coding 屏三模式 → CODING 工位
        listOf(AgentMode.BUILD, AgentMode.PLAN, AgentMode.GOAL).forEach {
            assertEquals(EngineWorkstation.CODING, it.workstation)
        }
        assertEquals(EngineWorkstation.AGENT, EngineWorkstation.of(AgentMode.AGENT))
        assertEquals(EngineWorkstation.CODING, EngineWorkstation.of(AgentMode.GOAL))
    }

    // ── 身份行分轨 ──────────────────────────────────────────────────

    @Test
    fun `agent workstation keeps AI AGENT identity without github claim`() {
        val p = prompt(AgentConfig(mode = AgentMode.AGENT), agentSurface)
        val identityBlock = p.lineSequence().take(3).joinToString("\n")
        assertTrue(identityBlock.contains("an AI AGENT running on an Android device"))
        // 同源纪律：AGENT 工具计划剔除 Code 族与 Github 族工具，身份段不再声明 GitHub
        assertFalse(identityBlock.contains("GitHub"))
        assertTrue(p.contains("## Mode: AGENT"))
    }

    @Test
    fun `coding workstation identity is coding oriented and mentions git lane`() {
        val p = prompt(AgentConfig(mode = AgentMode.BUILD), codingSurface)
        val identityBlock = p.lineSequence().take(3).joinToString("\n")
        assertTrue(identityBlock.contains("a CODING agent working in a real project workspace"))
        // CODING 轨道不携带 AGENT 工位的身份措辞
        assertFalse(identityBlock.contains("an AI AGENT running"))
        // CODING 工位确实有 git/GitHub 工具 → 身份自述可以声明
        assertTrue(identityBlock.contains("git and GitHub"))
        assertTrue(p.contains("## Mode: BUILD"))
    }

    @Test
    fun `custom agent name applies on both tracks`() {
        val a = prompt(AgentConfig(mode = AgentMode.AGENT, agentName = "小钢炮"), agentSurface)
        val c = prompt(AgentConfig(mode = AgentMode.BUILD, agentName = "小钢炮"), codingSurface)
        assertTrue(a.startsWith("You are 小钢炮, an AI AGENT"))
        assertTrue(c.startsWith("You are 小钢炮, a CODING agent"))
    }

    // ── 聊天 UX 段分轨 ──────────────────────────────────────────────

    @Test
    fun `conversational openness is agent workstation only`() {
        val agent = prompt(AgentConfig(mode = AgentMode.AGENT), agentSurface)
        val coding = prompt(AgentConfig(mode = AgentMode.BUILD), codingSurface)
        assertTrue(agent.contains("## Conversational Openness"))
        assertFalse(coding.contains("## Conversational Openness"))
    }

    @Test
    fun `first turn greeting is agent workstation only`() {
        val signal = ChatSignal(emotion = ChatEmotion.NONE, emotionHits = 0, vague = false)
        val agent = prompt(
            AgentConfig(mode = AgentMode.AGENT), agentSurface,
            firstTurnGreeting = true, chatSignal = signal
        )
        val coding = prompt(
            AgentConfig(mode = AgentMode.BUILD), codingSurface,
            firstTurnGreeting = true, chatSignal = signal
        )
        assertTrue(agent.contains("## First-Turn Greeting (THIS TURN)"))
        assertFalse(coding.contains("## First-Turn Greeting"))
    }

    @Test
    fun `emotional attunement and vague request are agent workstation only`() {
        val signal = ChatSignal(emotion = ChatEmotion.JOYFUL, emotionHits = 2, vague = true)
        val agent = prompt(AgentConfig(mode = AgentMode.AGENT), agentSurface, chatSignal = signal)
        val coding = prompt(AgentConfig(mode = AgentMode.BUILD), codingSurface, chatSignal = signal)
        assertTrue(agent.contains("## Emotional Attunement (THIS TURN)"))
        assertTrue(agent.contains("## Vague Request (THIS TURN)"))
        assertFalse(coding.contains("## Emotional Attunement"))
        assertFalse(coding.contains("## Vague Request"))
    }

    // ── 任务路由同源化 ──────────────────────────────────────────────

    @Test
    fun `task routing never recommends tools filtered out of the plan`() {
        val p = prompt(AgentConfig(mode = AgentMode.AGENT), agentSurface)
        assertTrue(p.contains("## Task Routing"))
        // 被工位隔离剔除的工具绝不被推荐
        assertFalse(p.contains("CodeGitRun"))
        assertFalse(p.contains("GithubCreatePullRequest"))
        // git 车道改写为诚实指引：引导切换 Coding 工位
        assertTrue(p.contains("lives in the CODING workstation"))
        // 可见的车道照常推荐
        assertTrue(p.contains("WebSearch for orientation"))
        assertTrue(p.contains("BrowserNavigate + Browser* family"))
    }

    @Test
    fun `task routing recommends git lane when coding tools are visible`() {
        val p = prompt(AgentConfig(mode = AgentMode.BUILD), codingSurface)
        assertTrue(p.contains("use CodeGitRun"))
        assertTrue(p.contains("GithubCreatePullRequest"))
        assertFalse(p.contains("lives in the CODING workstation"))
    }

    @Test
    fun `task routing omitted when no tools are visible`() {
        // CHAT 零工具：路由段整段省略（指向不可调用工具只会自相矛盾）
        val chat = prompt(AgentConfig(mode = AgentMode.CHAT), emptyList())
        assertFalse(chat.contains("## Task Routing"))
        // 有工具但无路由相关工具（只有记忆类）→ 同样省略
        val memoryOnly = prompt(AgentConfig(mode = AgentMode.AGENT), listOf(tool("MemoryRecall")))
        assertFalse(memoryOnly.contains("## Task Routing"))
    }

    // ── 共享执行纪律不受伤 ──────────────────────────────────────────

    @Test
    fun `both workstations keep the shared execution spine`() {
        val agent = prompt(AgentConfig(mode = AgentMode.AGENT), agentSurface)
        val coding = prompt(AgentConfig(mode = AgentMode.BUILD), codingSurface)
        listOf(agent, coding).forEach { p ->
            // 分轨只拆「工位形态」，两工位都需要的执行纪律保持共享
            assertTrue(p.contains("## Tool-Use Policy (MANDATORY)"))
            assertTrue(p.contains("## Capability Expansion Playbook"))
            assertTrue(p.contains("## Available Tools"))
        }
    }
}
