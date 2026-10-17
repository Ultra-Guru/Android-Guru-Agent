package com.apex.agent.slash

import com.apex.agent.plugin.host.functional.FunctionalSlashCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SlashCommandRouter].
 *
 * Covers:
 * - The four generic command types (Skill / Mcp-other / Connector / Plugin)
 *   produce the expected system-message prefix and an agent prompt that
 *   embeds args + user extra.
 * - `/mcp:github` is the special case: with a connected context it emits a
 *   prompt that explicitly enumerates the `github_*` tool IDs; with a
 *   disconnected context it short-circuits to `requestGithubConnect = true`
 *   and an empty agent prompt.
 * - Unknown commands forward the raw text verbatim.
 * - `/logic:<mode>`（v1.5 双思考逻辑）是本地路由命令：只产引导性系统消息
 *   且 agentPrompt 恒为空（Coding 屏在路由前截获执行；Agent 屏走到此处
 *   只展示引导不进引擎）。
 * - Plugin System v2：`/plugin:<id>` 命中功能性插件指令快照时注入插件声
 *   明的定制 prompt；未命中 / 空 prompt / 旧调用方不携带快照时既有通用
 *   插件路由零回归。
 */
class SlashCommandRouterTest {

    // ═══════════════════════════════════════════════════════════
    // Generic command types
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `Skill route has skill emoji and forwards args + user extra to prompt`() {
        val cmd = SlashCommand.Skill(
            id = "WebSearch",
            args = mapOf("query" to "Android"),
            userExtra = "latest news"
        )
        val route = SlashCommandRouter.route(cmd)

        assertEquals("🧩 激活 Skill: WebSearch", route.systemMessage)
        assertFalse(route.requestGithubConnect)
        // Prompt must surface the command, args, and user extra.
        assertTrue(route.agentPrompt.contains("/skill:WebSearch"))
        assertTrue(route.agentPrompt.contains("query=Android"))
        assertTrue(route.agentPrompt.contains("用户附加要求: latest news"))
    }

    @Test
    fun `Connector route has connector emoji and generic prompt`() {
        val cmd = SlashCommand.Connector(id = "ssh", args = mapOf("host" to "1.2.3.4"))
        val route = SlashCommandRouter.route(cmd)

        assertEquals("🔗 使用连接器: ssh", route.systemMessage)
        assertFalse(route.requestGithubConnect)
        assertTrue(route.agentPrompt.contains("/connector:ssh"))
        assertTrue(route.agentPrompt.contains("host=1.2.3.4"))
    }

    @Test
    fun `Plugin route has plugin emoji and registered-tools status`() {
        val cmd = SlashCommand.Plugin(id = "pdf_reader")
        val route = SlashCommandRouter.route(cmd)

        // 插件工具桥已激活（IApexPluginHost + PluginManager 真实注册）：
        // 路由词应指向已注册的插件工具，并引导模型直接调用。
        assertEquals("📦 插件: pdf_reader（工具已注册进 Agent 工具表）", route.systemMessage)
        assertFalse(route.requestGithubConnect)
        assertTrue(route.agentPrompt.contains("/plugin:pdf_reader"))
        assertTrue(route.agentPrompt.contains("已注册进 ToolRegistry"))
        assertFalse(route.agentPrompt.contains("尚未接线"))
    }

    // ═══════════════════════════════════════════════════════════
    // Plugin System v2 — functional plugin command routing
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `Functional plugin command injects declared prompt and plugin badge`() {
        val cmd = SlashCommand.Plugin(id = "briefing")
        val context = SlashRouteContext(
            functionalCommands = mapOf(
                "briefing" to FunctionalSlashCommand(
                    commandId = "briefing",
                    pluginId = "functional.daily-briefing",
                    pluginName = "每日简报",
                    title = "每日简报",
                    description = "生成简报",
                    prompt = "使用 briefing_run 工具生成今日简报"
                )
            )
        )
        val route = SlashCommandRouter.route(cmd, context)

        // 命中功能性指令：插件名徽章 + 定制 prompt（路由器不越权改写声明文本）。
        assertTrue(route.systemMessage.contains("功能插件"))
        assertTrue(route.systemMessage.contains("每日简报"))
        assertTrue(route.agentPrompt.contains("/plugin:briefing"))
        assertTrue(route.agentPrompt.contains("使用 briefing_run 工具生成今日简报"))
        assertEquals("plugin", route.routeKind)
        assertEquals("briefing", route.sourceName)
        assertFalse(route.requestGithubConnect)
    }

    @Test
    fun `Functional plugin command with blank prompt falls back to generic tool guidance`() {
        val cmd = SlashCommand.Plugin(id = "pulse")
        val context = SlashRouteContext(
            functionalCommands = mapOf(
                "pulse" to FunctionalSlashCommand(
                    commandId = "pulse",
                    pluginId = "functional.repo-pulse",
                    pluginName = "仓库脉搏",
                    title = "仓库脉搏",
                    description = "",
                    prompt = ""
                )
            )
        )
        val route = SlashCommandRouter.route(cmd, context)

        // 空定制 prompt：退化为「工具已注册，直接调用」的通用引导（与 APK
        // 插件通用路由同语义），不产生空转提示词。
        assertTrue(route.agentPrompt.contains("已注册进 ToolRegistry"))
        assertTrue(route.agentPrompt.contains("仓库脉搏"))
    }

    @Test
    fun `Plugin command not matching functional snapshot keeps generic APK routing`() {
        val cmd = SlashCommand.Plugin(id = "com.apex.agent.plugin.workflow")
        val context = SlashRouteContext(
            functionalCommands = mapOf(
                "briefing" to FunctionalSlashCommand(
                    commandId = "briefing",
                    pluginId = "functional.daily-briefing",
                    pluginName = "每日简报",
                    title = "每日简报",
                    description = "",
                    prompt = "x"
                )
            )
        )
        val route = SlashCommandRouter.route(cmd, context)

        // 未命中（id 是 APK 包名）：既有通用路由零回归。
        assertEquals("📦 插件: com.apex.agent.plugin.workflow（工具已注册进 Agent 工具表）", route.systemMessage)
        assertTrue(route.agentPrompt.contains("已注册进 ToolRegistry"))
    }

    @Test
    fun `Unknown route forwards raw text verbatim to agent`() {
        val cmd = SlashCommand.Unknown(raw = "/help")
        val route = SlashCommandRouter.route(cmd)

        assertEquals("⚡ 指令: /help", route.systemMessage)
        assertEquals("/help", route.agentPrompt)
        assertFalse(route.requestGithubConnect)
    }

    // ═══════════════════════════════════════════════════════════
    // /mcp:github — the real binding (P0)
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `mcp github when disconnected requests connect flow and emits no agent prompt`() {
        val cmd = SlashCommand.Mcp(id = "github")
        val route = SlashCommandRouter.route(cmd, SlashRouteContext.Empty)

        assertTrue(route.requestGithubConnect)
        assertEquals("", route.agentPrompt)
        // System message must tell the user *what* to do.
        assertTrue(route.systemMessage.contains("GitHub 未连接"))
    }

    @Test
    fun `mcp github when disconnected ignores any args or user extra`() {
        // Even if the user typed /mcp:github repo=owner/name list issues,
        // we cannot execute — so no prompt is built. Args are simply dropped
        // (acceptable: the user will re-issue after connecting).
        val cmd = SlashCommand.Mcp(
            id = "github",
            args = mapOf("repo" to "owner/name"),
            userExtra = "list issues"
        )
        val route = SlashCommandRouter.route(cmd, SlashRouteContext.Empty)

        assertTrue(route.requestGithubConnect)
        assertEquals("", route.agentPrompt)
    }

    @Test
    fun `mcp github when connected lists github tool ids in agent prompt`() {
        val cmd = SlashCommand.Mcp(id = "github")
        val ctx = SlashRouteContext(githubConnected = true, githubUsername = "octocat")
        val route = SlashCommandRouter.route(cmd, ctx)

        assertFalse(route.requestGithubConnect)
        // System message must surface the bound username.
        assertTrue(route.systemMessage.contains("octocat"))
        // Agent prompt must enumerate the github_* tools so the LLM prefers them.
        listOf(
            "GithubGetUser", "GithubListRepos", "GithubReadFile",
            "GithubWriteFile", "GithubCreateIssue", "GithubListIssues",
            "GithubSearchCode"
        ).forEach { toolId ->
            assertTrue("prompt should mention $toolId", route.agentPrompt.contains(toolId))
        }
    }

    @Test
    fun `mcp github when connected forwards args and user extra into prompt`() {
        val cmd = SlashCommand.Mcp(
            id = "github",
            args = mapOf("repo" to "owner/name"),
            userExtra = "list open issues"
        )
        val ctx = SlashRouteContext(githubConnected = true, githubUsername = "octocat")
        val route = SlashCommandRouter.route(cmd, ctx)

        assertFalse(route.requestGithubConnect)
        assertTrue(route.agentPrompt.contains("repo=owner/name"))
        assertTrue(route.agentPrompt.contains("用户附加要求: list open issues"))
    }

    @Test
    fun `mcp github connected with null username still routes as connected`() {
        // A token may be saved before username resolution completes. The
        // router must treat isConnected=true as the source of truth, not the
        // username — otherwise the user would be bounced back to connect.
        val cmd = SlashCommand.Mcp(id = "github")
        val ctx = SlashRouteContext(githubConnected = true, githubUsername = null)
        val route = SlashCommandRouter.route(cmd, ctx)

        assertFalse(route.requestGithubConnect)
        assertTrue(route.systemMessage.contains("GitHub"))
    }

    // ═══════════════════════════════════════════════════════════
    // /mcp:<other> — generic routing (connected) + running-gate (Hub 生态门控)
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `mcp with non-github id uses generic mcp routing when connected`() {
        val cmd = SlashCommand.Mcp(id = "postgres")
        val route = SlashCommandRouter.route(
            cmd,
            SlashRouteContext(githubConnected = true, mcpConnected = setOf("postgres"))
        )

        assertFalse(route.requestGithubConnect)
        assertEquals("🔌 连接 MCP: postgres", route.systemMessage)
        assertTrue(route.agentPrompt.contains("/mcp:postgres"))
        // Must NOT leak github tool ids into a postgres prompt.
        assertFalse(route.agentPrompt.contains("github_"))
    }

    @Test
    fun `mcp with non-github id not running is blocked with guidance not hollow prompt`() {
        // Hub 生态门控：未安装/未启动的 MCP 不可经斜杠使用——路由器拦截
        // （agentPrompt 置空，systemMessage 引导去市场启动），不再发
        // 「指令存在，能力待接线」的空转骨架提示词。
        val cmd = SlashCommand.Mcp(id = "postgres")
        val route = SlashCommandRouter.route(cmd, SlashRouteContext.Empty)

        assertFalse(route.requestGithubConnect)
        assertEquals("", route.agentPrompt)
        assertTrue(route.systemMessage.contains("postgres"))
        assertTrue(route.systemMessage.contains("市场"))
    }

    // ═══════════════════════════════════════════════════════════
    // /logic — v1.5 本地路由命令（双思考逻辑切换）
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `Logic route produces guidance message and never an agent prompt`() {
        // Coding 屏在路由前截获执行；到达路由层的只有 Agent 屏——必须
        // 空转（不生成 agentPrompt），防止命令原文被当自由文本误执行。
        val cmd = SlashCommand.Logic(id = "standard")
        val route = SlashCommandRouter.route(cmd)

        assertEquals("", route.agentPrompt)
        assertFalse(route.requestGithubConnect)
        assertTrue(route.systemMessage.contains("Coding"))
        // 引导消息必须给出可用的命令形态，用户照抄即可走通。
        assertTrue(route.systemMessage.contains("/logic:standard"))
        assertTrue(route.systemMessage.contains("/logic:deep_dive"))
    }

    @Test
    fun `Logic route ignores unknown mode id at routing layer`() {
        // 未知 id（/logic:foo）在路由层同样空转——id 合法性校验属于
        // Coding 屏 VM 的截获逻辑（fromName → null → 引导消息），路由层
        // 不重复判定，保持单一职责。
        val cmd = SlashCommand.Logic(id = "foo")
        val route = SlashCommandRouter.route(cmd)

        assertEquals("", route.agentPrompt)
        assertTrue(route.systemMessage.isNotEmpty())
    }

    @Test
    fun `Logic route carries no source metadata for pipeline badges`() {
        // routeKind/sourceName 是流水线横幅与来源徽章的数据源；本地路由
        // 命令不产生工具调用，不应携带这些元数据。
        val cmd = SlashCommand.Logic(id = "deep_dive")
        val route = SlashCommandRouter.route(cmd)

        assertEquals(null, route.routeKind)
        assertEquals(null, route.sourceName)
        assertEquals(null, route.skillName)
    }

    // ═══════════════════════════════════════════════════════════
    // Default context backward-compat
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `route without explicit context defaults to empty and treats github as disconnected`() {
        // Ensures existing call sites that omit context (e.g. future tooling)
        // get safe "not connected" behavior rather than a hollow prompt.
        val cmd = SlashCommand.Mcp(id = "github")
        val route = SlashCommandRouter.route(cmd)

        assertTrue(route.requestGithubConnect)
        assertEquals("", route.agentPrompt)
    }
}
