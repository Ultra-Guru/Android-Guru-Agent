package com.apex.agent.core.tools.pluginhost.claude

import com.apex.agent.core.tools.extension.adapters.McpExtensionAdapter
import com.apex.agent.core.tools.mcp.McpServerConfig
import com.apex.agent.core.tools.mcp.McpTransport
import com.apex.agent.core.tools.pluginhost.bridge.PluginEventBus
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * # Phase 5 契约锁：Claude Hooks / MCP / LSP 兼容层
 *
 * 钩子调度用**真实 /bin/sh**（官方协议是 shell 命令 + stdin JSON +
 * stdout JSON + exit code 语义）；MCP 桥用手写 fake port（仓库纪律：
 * 无 mock 框架）。
 */
class ClaudeCompatTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun shAvailable(): Boolean = File("/bin/sh").canExecute()

    // ── hooks.json 解析（官方形状）────────────────────────────────────

    @Test
    fun `parses official hooks json shape`() {
        val manifest = ClaudeHooksManifest.parse(
            """
            { "hooks": {
                "PreToolUse": [
                  { "matcher": "Bash",
                    "hooks": [
                      { "type": "command", "command": "${'$'}{CLAUDE_PLUGIN_ROOT}/hooks/block-rm.sh", "args": [], "timeout": 30 }
                    ] },
                  { "matcher": "Edit|Write",
                    "hooks": [
                      { "type": "command", "command": "/path/to/lint.sh" }
                    ] }
                ],
                "SessionStart": [
                  { "hooks": [ { "type": "command", "command": "echo hi" } ] }
                ],
                "SomeFutureEvent": [
                  { "hooks": [ { "type": "command", "command": "echo future" } ] }
                ]
            } }
            """.trimIndent()
        )
        assertNotNull(manifest)
        manifest!!
        assertEquals(2, manifest.groups["PreToolUse"]?.size)
        assertEquals("Bash", manifest.groups["PreToolUse"]?.get(0)?.matcher)
        assertEquals(30, manifest.groups["PreToolUse"]?.get(0)?.hooks?.single()?.timeout)
        // 无 matcher 的组 = 全匹配
        assertTrue(manifest.groups["SessionStart"]?.single()?.matcher?.isEmpty() == true)
        // 未知官方事件保留但如实标注未映射
        assertTrue(manifest.unmappedEvents.contains("SomeFutureEvent"))
    }

    @Test
    fun `broken hooks json yields null not crash`() {
        assertNull(ClaudeHooksManifest.parse("not json"))
        assertNull(ClaudeHooksManifest.parse("""{"hooks": "wrong"}"""))
        // 空对象 = 无钩子声明的合法形态（空清单，不是损坏）
        val empty = ClaudeHooksManifest.parse("""{}""")
        assertTrue(empty?.groups?.isEmpty() == true)
    }

    @Test
    fun `matcher follows official tool name regex semantics`() {
        val group = ClaudeHooksManifest.MatcherGroup(matcher = "Edit|Write", hooks = emptyList())
        assertTrue(ClaudeHooksManifest.matchesTool(group, "Edit"))
        assertTrue(ClaudeHooksManifest.matchesTool(group, "Write"))
        assertFalse(ClaudeHooksManifest.matchesTool(group, "Bash"))
        // 空 matcher = 全匹配；工具事件无工具名 = 不匹配
        val all = ClaudeHooksManifest.MatcherGroup(matcher = "", hooks = emptyList())
        assertTrue(ClaudeHooksManifest.matchesTool(all, "Anything"))
        assertFalse(ClaudeHooksManifest.matchesTool(group, null))
    }

    @Test
    fun `host event mapping table is explicit and honest`() {
        assertEquals("PreToolUse", ClaudeHookAdapter.toClaudeEvent(PluginEventBus.PluginHostEvent.BEFORE_TOOL_CALL))
        assertEquals("PostToolUse", ClaudeHookAdapter.toClaudeEvent(PluginEventBus.PluginHostEvent.AFTER_TOOL_CALL))
        assertEquals(
            "PostToolUseFailure",
            ClaudeHookAdapter.toClaudeEvent(PluginEventBus.PluginHostEvent.AFTER_TOOL_CALL_FAILURE)
        )
        assertEquals("SessionStart", ClaudeHookAdapter.toClaudeEvent(PluginEventBus.PluginHostEvent.SESSION_START))
        assertEquals("UserPromptSubmit", ClaudeHookAdapter.toClaudeEvent(PluginEventBus.PluginHostEvent.USER_PROMPT_SUBMIT))
        assertEquals("Notification", ClaudeHookAdapter.toClaudeEvent(PluginEventBus.PluginHostEvent.NOTIFICATION))
        // 宿主内部事件无官方对应 —— 如实 null
        assertNull(ClaudeHookAdapter.toClaudeEvent(PluginEventBus.PluginHostEvent.PLUGIN_STARTED))
        assertNull(ClaudeHookAdapter.toClaudeEvent(PluginEventBus.PluginHostEvent.PLUGIN_CRASHED))
    }

    // ── 钩子调度（真实 /bin/sh E2E）──────────────────────────────────

    @Test
    fun `hook dispatch runs official protocol against real shell`() = runTest {
        assumeTrue("sh not available", shAvailable())
        val root = tmp.newFolder()
        root.resolve("hooks").mkdirs()
        // 官方形状的上下文注入钩子：stdin JSON → stdout hookSpecificOutput
        //（shell 变量用 Kotlin 转义美元符书写）
        root.resolve("hooks/context.sh").writeText(
            """
            INPUT=$(cat)
            EVENT_NAME=$(echo "${'$'}INPUT" | sed -n 's/.*"hook_event_name":"\([^"]*\)".*/\1/p')
            TOOL=$(echo "${'$'}INPUT" | sed -n 's/.*"tool_name":"\([^"]*\)".*/\1/p')
            printf '{"hookSpecificOutput":{"hookEventName":"%s","additionalContext":"ctx from %s"}}' "${'$'}EVENT_NAME" "${'$'}TOOL"
            """.trimIndent()
        )
        // 官方钩子命令直指脚本路径 —— 需要可执行位（与官方一致）
        root.resolve("hooks/context.sh").setExecutable(true)
        val manifest = ClaudeHooksManifest.parse(
            """
            { "hooks": { "PreToolUse": [
                { "matcher": "", "hooks": [ { "type": "command", "command": "${'$'}{CLAUDE_PLUGIN_ROOT}/hooks/context.sh", "timeout": 10 } ] }
            ] } }
            """.trimIndent()
        )!!

        val adapter = ClaudeHookAdapter(ClaudeHookPort.LocalShellHookPort())
        val outcome = adapter.dispatch(
            manifest = manifest,
            event = PluginEventBus.PluginHostEvent.BEFORE_TOOL_CALL,
            pluginRoot = root,
            toolName = "ShellExecute",
            toolInputJson = """{"command":"ls"}"""
        )

        assertEquals(1, outcome.results.size)
        val result = outcome.results.single()
        assertEquals(0, result.exitCode)
        assertFalse(result.blocking)
        assertEquals("ctx from ShellExecute", result.additionalContext)
        assertEquals(listOf("ctx from ShellExecute"), outcome.contexts)
        assertFalse(outcome.denied)
    }

    @Test
    fun `exit code 2 is blocking and deny decision is surfaced`() = runTest {
        assumeTrue("sh not available", shAvailable())
        val root = tmp.newFolder()
        root.resolve("block.sh").writeText(
            """
            printf '{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"deny","permissionDecisionReason":"blocked by policy"}}'
            exit 2
            """.trimIndent()
        )
        root.resolve("block.sh").setExecutable(true)
        val manifest = ClaudeHooksManifest.parse(
            """{ "hooks": { "PreToolUse": [
                { "matcher": "Bash", "hooks": [ { "type": "command", "command": "${'$'}{CLAUDE_PLUGIN_ROOT}/block.sh" } ] }
            ] } }"""
        )!!

        val adapter = ClaudeHookAdapter(ClaudeHookPort.LocalShellHookPort())
        val outcome = adapter.dispatch(
            manifest, PluginEventBus.PluginHostEvent.BEFORE_TOOL_CALL, root,
            toolName = "Bash", toolInputJson = "{}"
        )
        assertTrue(outcome.blocked)
        assertTrue(outcome.denied)
        assertEquals("deny", outcome.results.single().permissionDecision)

        // matcher 不匹配的工具不受影响
        val other = adapter.dispatch(
            manifest, PluginEventBus.PluginHostEvent.BEFORE_TOOL_CALL, root,
            toolName = "Read", toolInputJson = "{}"
        )
        assertTrue(other.results.isEmpty())
    }

    @Test
    fun `unmapped host event dispatches nothing and non command types are honest`() = runTest {
        assumeTrue("sh not available", shAvailable())
        val root = tmp.newFolder()
        val manifest = ClaudeHooksManifest.parse(
            """
            { "hooks": {
                "PreToolUse": [
                  { "matcher": "", "hooks": [ { "type": "prompt", "prompt": "check" } ] }
                ]
            } }
            """.trimIndent()
        )!!
        val adapter = ClaudeHookAdapter(ClaudeHookPort.LocalShellHookPort())

        // prompt 类型钩子：如实 skippedNotSupported
        val outcome = adapter.dispatch(
            manifest, PluginEventBus.PluginHostEvent.BEFORE_TOOL_CALL, root, toolName = "Bash"
        )
        assertEquals(1, outcome.results.size)
        assertTrue(outcome.results.single().skippedNotSupported)

        // 宿主内部事件（无官方映射）：零调度
        val none = adapter.dispatch(
            manifest, PluginEventBus.PluginHostEvent.PLUGIN_STARTED, root
        )
        assertTrue(none.results.isEmpty())
    }

    @Test
    fun `hook timeout is enforced not hung`() = runTest {
        assumeTrue("sh not available", shAvailable())
        val root = tmp.newFolder()
        root.resolve("slow.sh").writeText("sleep 30")
        root.resolve("slow.sh").setExecutable(true)
        val manifest = ClaudeHooksManifest.parse(
            """{ "hooks": { "SessionStart": [
                { "hooks": [ { "type": "command", "command": "${'$'}{CLAUDE_PLUGIN_ROOT}/slow.sh", "timeout": 1 } ] }
            ] } }"""
        )!!
        val adapter = ClaudeHookAdapter(ClaudeHookPort.LocalShellHookPort())
        val outcome = adapter.dispatch(
            manifest, PluginEventBus.PluginHostEvent.SESSION_START, root
        )
        val result = outcome.results.single()
        assertTrue(result.timedOut)
        assertFalse(result.blocking) // 超时按官方语义是非阻塞错误
    }

    // ── MCP 桥（fake port）────────────────────────────────────────────

    private class FakeMcpPort : McpExtensionAdapter.McpConfigPort {
        val added = CopyOnWriteArrayList<McpServerConfig>()
        val removed = CopyOnWriteArrayList<String>()
        override suspend fun add(config: McpServerConfig): Result<Unit> {
            added += config
            return Result.success(Unit)
        }
        override suspend fun remove(name: String) {
            removed += name
        }
        override fun all(): List<McpServerConfig> = added
    }

    @Test
    fun `mcp bridge registers stdio and http servers via existing port`() = runTest {
        val port = FakeMcpPort()
        val bridge = ClaudeMcpBridge(tmp.newFolder(), port)
        val applied = bridge.apply(
            "claude.pdf-helper",
            """
            { "mcpServers": {
                "fs": { "command": "npx", "args": ["-y", "@mcp/server-fs", "/data"], "env": {"KEY":"v"} },
                "hub": { "type": "http", "url": "https://mcp.example.com/hub", "headers": {"Authorization":"Bearer x"} }
            } }
            """.trimIndent()
        ).getOrThrow()

        assertEquals(setOf("fs", "hub"), applied.addedServers.toSet())
        assertTrue(applied.skipped.isEmpty())
        // stdio：Android 上必须沙箱执行
        val fs = port.added.first { it.name == "fs" }
        assertEquals(McpTransport.STDIO, fs.transport)
        assertEquals("npx", fs.command)
        assertTrue(fs.runInSandbox)
        assertEquals(listOf("-y", "@mcp/server-fs", "/data"), fs.args)
        // http：URL 直连 + headers
        val hub = port.added.first { it.name == "hub" }
        assertEquals(McpTransport.HTTP, hub.transport)
        assertEquals("https://mcp.example.com/hub", hub.url)
        assertEquals(mapOf("Authorization" to "Bearer x"), hub.headers)
        // 来源归属账本
        assertEquals(setOf("fs", "hub"), bridge.ownedServers("claude.pdf-helper"))
    }

    @Test
    fun `existing server names are not overwritten and removeOwned only removes plugin owned`() = runTest {
        val port = FakeMcpPort()
        // 用户自建的既有服务器
        port.added += McpServerConfig(name = "memory", transport = McpTransport.HTTP, url = "https://x")
        val bridge = ClaudeMcpBridge(tmp.newFolder(), port)

        val applied = bridge.apply(
            "claude.plugin",
            """{ "mcpServers": {
                "memory": { "command": "npx" },
                "fresh": { "command": "node", "args": ["server.js"] }
            } }"""
        ).getOrThrow()
        // 同名跳过（不覆盖用户配置），新增照常
        assertTrue(applied.skipped.contains("memory"))
        assertEquals(listOf("fresh"), applied.addedServers)

        // 卸载：只删插件带来的 fresh，用户的 memory 保留
        assertEquals(1, bridge.removeOwned("claude.plugin"))
        assertEquals(listOf("fresh"), port.removed)
        assertTrue(port.added.any { it.name == "memory" })
        // 账本清理后幂等
        assertEquals(0, bridge.removeOwned("claude.plugin"))
    }

    @Test
    fun `broken mcp json fails honestly`() = runTest {
        val bridge = ClaudeMcpBridge(tmp.newFolder(), FakeMcpPort())
        assertTrue(bridge.apply("claude.plugin", "not json").isFailure)
        assertTrue(bridge.apply("claude.plugin", """{"wrong": {}}""").isFailure)
    }

    // ── LSP 诚实报告 ──────────────────────────────────────────────────

    @Test
    fun `lsp detection is honest about missing host runtime`() {
        val empty = tmp.newFolder()
        val absent = ClaudeLspReport.scan(empty)
        assertFalse(absent.detected)

        val pluginRoot = tmp.newFolder()
        pluginRoot.resolve(".lsp.json").writeText(
            """{ "lspServers": { "typescript": { "command": "typescript-language-server" }, "rust": { "command": "rust-analyzer" } } }"""
        )
        val report = ClaudeLspReport.scan(pluginRoot)
        assertTrue(report.detected)
        assertEquals(setOf("typescript", "rust"), report.serverNames.toSet())
        // 诚实结论：识别了但明确说宿主运行时未就绪
        assertTrue(report.verdict.contains("尚未就绪"))
        assertTrue(report.verdict.contains("不启动"))
    }
}
