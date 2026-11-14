package com.apex.agent.core.tools.pluginhost.claude

import com.apex.agent.core.tools.extension.adapters.McpExtensionAdapter
import com.apex.agent.core.tools.mcp.McpServerConfig
import com.apex.agent.core.tools.pluginhost.bridge.PluginEventBus
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * # Phase 6 收尾契约锁：ClaudeCompatWiring（install 链 × 兼容层挂接）
 *
 * 核查稿结论：Phase 5 组件（HookAdapter/McpBridge/LspReport）实现且测绿，
 * 但 install 链 0 挂接。本测试锁住挂接器的新契约：
 * - `.mcp.json` 装后注册（enabled=false 语义由桥负责，此处锁 port 交互）；
 * - `hooks/hooks.json` 装后订阅，宿主工具事件真实触发钩子（真实 /bin/sh，
 *   官方 stdin JSON / `${CLAUDE_PLUGIN_ROOT}` 语义）；
 * - 缺组件 / 缺包根 / 坏 JSON 的诚实降级（notes 可见，不炸安装）；
 * - 卸载摘除：MCP 归属回收 + 钩子退订。
 *
 * 事件等待用真实时间 harness（总线处理器在真实调度器上跑，runTest 虚拟
 * 时钟会虚假到点 —— issue #276 测试纪律）。
 */
class ClaudeCompatWiringTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun shAvailable(): Boolean = File("/bin/sh").canExecute()

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

    /** 真实时间 harness（同 PluginEventBusTest 纪律）。 */
    private fun realAsync(timeoutMs: Long = 15_000, block: suspend CoroutineScope.() -> Unit) {
        val latch = CountDownLatch(1)
        var failure: Throwable? = null
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        scope.launch {
            try {
                block()
            } catch (t: Throwable) {
                failure = t
            } finally {
                latch.countDown()
            }
        }
        assertTrue("测试超时（${timeoutMs}ms）", latch.await(timeoutMs, TimeUnit.MILLISECONDS))
        failure?.let { throw it }
    }

    private suspend fun awaitCondition(timeoutMs: Long = 5_000, condition: suspend () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!condition()) kotlinx.coroutines.delay(10)
        }
    }

    // ── .mcp.json 挂接 ───────────────────────────────────────────────

    @Test
    fun `attach registers mcp servers and detach removes only owned`() {
        val port = FakeMcpPort()
        val bridge = ClaudeMcpBridge(tmp.newFolder(), port)
        val wiring = ClaudeCompatWiring(bridge, null, null)
        // 用户既有服务器（不得被插件卸载误删）
        port.added += McpServerConfig(name = "memory", transport = com.apex.agent.core.tools.mcp.McpTransport.HTTP, url = "https://x")

        val report = kotlinx.coroutines.runBlocking {
            wiring.attach(
                pluginId = "claude.helper",
                pluginRoot = null,
                hooksJson = null,
                mcpJson = """{ "mcpServers": { "fs": { "command": "npx", "args": ["-y", "s"] } } }"""
            )
        }
        assertEquals(listOf("fs"), report.mcpAddedServers)
        assertTrue(report.notes.any { it.contains("McpConnect") })
        // MCP 服务器注册语义：默认停用（用户显式连接）
        val fs = port.added.first { it.name == "fs" }
        assertFalse(fs.enabled)

        val detachNotes = kotlinx.coroutines.runBlocking { wiring.detach("claude.helper") }
        assertTrue(detachNotes.any { it.contains("1 台 MCP") })
        assertEquals(listOf("fs"), port.removed)
        assertTrue(port.added.any { it.name == "memory" }) // 用户自建保留
    }

    // ── hooks 挂接：宿主工具事件 → 官方协议真实执行 ───────────────────

    @Test
    fun `hooks are subscribed on attach and fire on host tool events`() {
        assumeTrue(shAvailable())
        realAsync {
            val bus = PluginEventBus(this, handlerTimeoutMs = 10_000)
            val adapter = ClaudeHookAdapter(ClaudeHookPort.LocalShellHookPort())
            val wiring = ClaudeCompatWiring(null, adapter, bus)

            // 常驻包根 + hooks/hooks.json（PreToolUse matcher=Bash，追加行计数证明执行）
            val pluginRoot = tmp.newFolder()
            pluginRoot.resolve("hooks").mkdirs()
            pluginRoot.resolve("hooks/mark.sh").writeText(
                "#!/bin/sh\ncat > /dev/null\necho fired >> \"${'$'}{CLAUDE_PLUGIN_ROOT}/fired.txt\"\n"
            )
            val marker = pluginRoot.resolve("fired.txt")
            fun fireCount(): Int = if (marker.isFile) marker.readLines().size else 0
            val hooksJson = """
                { "hooks": {
                    "PreToolUse": [
                      { "matcher": "Bash",
                        "hooks": [ { "type": "command", "command": "sh ${'$'}{CLAUDE_PLUGIN_ROOT}/hooks/mark.sh", "timeout": 20 } ] }
                    ] }
                }
            """.trimIndent()

            val report = wiring.attach(
                pluginId = "claude.guard",
                pluginRoot = pluginRoot,
                hooksJson = hooksJson,
                mcpJson = null
            )
            assertEquals(setOf("PreToolUse"), report.hookedEvents)
            assertTrue(report.notes.any { it.contains("钩子已挂接") })
            assertEquals(setOf("claude.guard"), wiring.hookedPluginIds())

            // 宿主工具事件（payload 契约）→ 钩子在包根内真实执行
            bus.dispatch(
                PluginEventBus.PluginHostEvent.BEFORE_TOOL_CALL,
                """{"tool_name": "Bash", "tool_input": {"command": "ls"}}"""
            )
            awaitCondition { fireCount() == 1 }

            // matcher 语义：非 Bash 工具不触发（行数不增）
            bus.dispatch(
                PluginEventBus.PluginHostEvent.BEFORE_TOOL_CALL,
                """{"tool_name": "Read", "tool_input": {}}"""
            )
            kotlinx.coroutines.delay(300)
            assertEquals(1, fireCount())

            // 卸载摘除：退订后事件不再触发（行数不增）
            wiring.detach("claude.guard")
            assertTrue(wiring.hookedPluginIds().isEmpty())
            bus.dispatch(
                PluginEventBus.PluginHostEvent.BEFORE_TOOL_CALL,
                """{"tool_name": "Bash", "tool_input": {}}"""
            )
            kotlinx.coroutines.delay(300)
            assertEquals(1, fireCount())
            bus.shutdown()
        }
    }

    // ── 诚实降级 ─────────────────────────────────────────────────────

    @Test
    fun `hooks without persisted root degrade honestly`() {
        // 组件齐全（桥/适配器/总线）但包根未持久化 → 走"包根未持久化"降级分支
        val bus = PluginEventBus(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default))
        val wiring = ClaudeCompatWiring(null, ClaudeHookAdapter(ClaudeHookPort.LocalShellHookPort()), bus)
        val report = kotlinx.coroutines.runBlocking {
            wiring.attach(
                pluginId = "claude.x",
                pluginRoot = null,
                hooksJson = """{ "hooks": { "SessionStart": [ { "hooks": [ { "type": "command", "command": "echo hi" } ] } ] } }""",
                mcpJson = null
            )
        }
        assertTrue(report.hookedEvents.isEmpty())
        assertTrue(report.notes.any { it.contains("包根未持久化") })
        assertTrue(wiring.hookedPluginIds().isEmpty())
        bus.shutdown()
    }

    @Test
    fun `broken hooks json degrades honestly without failing install`() {
        val wiring = ClaudeCompatWiring(null, ClaudeHookAdapter(ClaudeHookPort.LocalShellHookPort()), null)
        val report = kotlinx.coroutines.runBlocking {
            wiring.attach("claude.x", tmp.newFolder(), hooksJson = "not json", mcpJson = null)
        }
        assertTrue(report.notes.any { it.contains("解析失败") })
        assertTrue(wiring.hookedPluginIds().isEmpty())
    }

    @Test
    fun `unmapped events are reported honestly`() {
        val bus = PluginEventBus(kotlinx.coroutines.CoroutineScope(Dispatchers.Default))
        val wiring = ClaudeCompatWiring(null, ClaudeHookAdapter(ClaudeHookPort.LocalShellHookPort()), bus)
        val report = kotlinx.coroutines.runBlocking {
            wiring.attach(
                pluginId = "claude.x",
                pluginRoot = tmp.newFolder(),
                hooksJson = """{ "hooks": { "SomeFutureEvent": [ { "hooks": [ { "type": "command", "command": "echo x" } ] } ] } }""",
                mcpJson = null
            )
        }
        assertEquals(setOf("SomeFutureEvent"), report.unmappedEvents)
        assertTrue(report.hookedEvents.isEmpty())
        assertTrue(report.notes.any { it.contains("未映射事件") })
        bus.shutdown()
    }

    @Test
    fun `lsp names are reported as detected but not started`() {
        val wiring = ClaudeCompatWiring(null, null, null)
        val report = kotlinx.coroutines.runBlocking {
            wiring.attach(
                pluginId = "claude.x",
                pluginRoot = null,
                hooksJson = null,
                mcpJson = null,
                lspServerNames = listOf("typescript", "rust")
            )
        }
        assertTrue(report.notes.any { it.contains("2 个语言服务器") && it.contains("尚未就绪") })
    }

    @Test
    fun `missing wiring components degrade honestly`() {
        val wiring = ClaudeCompatWiring(mcpBridge = null, hookAdapter = null, eventBus = null)
        val report = kotlinx.coroutines.runBlocking {
            wiring.attach(
                pluginId = "claude.x",
                pluginRoot = tmp.newFolder(),
                hooksJson = """{ "hooks": { "PreToolUse": [ { "hooks": [ { "type": "command", "command": "echo x" } ] } ] } }""",
                mcpJson = """{ "mcpServers": { "fs": { "command": "npx" } } }"""
            )
        }
        assertTrue(report.notes.any { it.contains("MCP 桥未接线") })
        assertTrue(report.notes.any { it.contains("钩子执行组件未接线") })
    }
}
