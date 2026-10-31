package com.apex.agent.platform.mcphost

import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.DefaultToolExecutor
import com.apex.agent.core.tools.DefaultToolRegistry
import com.apex.agent.core.tools.GateDecision
import com.apex.agent.core.tools.ToolExecutionGate
import com.apex.agent.core.tools.ToolExecutor
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * McpHostBridge 单测：白名单分类过滤 / 黑名单 / legacy 排除 / vault 硬拦截 /
 * 调用转发 / isError 语义。
 */
class McpHostBridgeTest {

    /** 测试工具：脚本化执行结果（元数据按 id 推断，与生产工具同规则）。 */
    private class FakeTool(
        override val id: String,
        override val description: String = "fake tool $id",
        override val parametersSchema: String = """{"type":"object","properties":{"a":{"type":"integer"}}}""",
        private val handler: suspend (String) -> String = { "ok:$id" }
    ) : AgentTool {
        override val name: String = id
        override val metadata: ToolMetadata
            get() = ToolMetadata.infer(id)
        override suspend fun execute(arguments: String): String = handler(arguments)
    }

    private fun registryOf(vararg tools: AgentTool): ToolRegistry =
        DefaultToolRegistry().apply { tools.forEach { register(it) } }

    private fun configOf(
        categories: List<String> = listOf("UTILITY"),
        blocked: List<String> = emptyList()
    ) = McpHostConfig(
        enabled = true,
        allowedCategories = categories,
        blockedToolIds = blocked
    )

    // ═══ toolDefinitions 白名单 ═══

    @Test
    fun `category whitelist filters tool list`() {
        val registry = registryOf(
            FakeTool("Calculate"),                       // → UTILITY
            FakeTool("Read"),                       // → FILE
            FakeTool("ShellExecute")                    // → SHELL
        )
        val bridge = McpHostBridge(
            registry, DefaultToolExecutor(registry),
            configOf(categories = listOf("UTILITY")).let { c -> { c } }
        )
        val names = bridge.toolDefinitions().map { it.name }
        assertEquals(listOf("Calculate"), names)
    }

    @Test
    fun `multiple allowed categories union`() {
        val registry = registryOf(FakeTool("Calculate"), FakeTool("Read"))
        val bridge = McpHostBridge(registry, DefaultToolExecutor(registry)) {
            configOf(categories = listOf("UTILITY", "FILE"))
        }
        assertEquals(setOf("Calculate", "Read"), bridge.toolDefinitions().map { it.name }.toSet())
    }

    @Test
    fun `blocked tool ids excluded even when category allowed`() {
        val registry = registryOf(FakeTool("Calculate"), FakeTool("ShareContent"))
        // ShareContent 推断为 UTILITY（无前缀规则命中），分类放行但被黑名单拦截
        val bridge = McpHostBridge(registry, DefaultToolExecutor(registry)) {
            configOf(categories = listOf("UTILITY"), blocked = listOf("ShareContent"))
        }
        assertEquals(listOf("Calculate"), bridge.toolDefinitions().map { it.name })
    }

    @Test
    fun `legacy alias tools excluded`() {
        val registry = registryOf(FakeTool("TerminalExecLegacy"), FakeTool("Calculate"))
        val bridge = McpHostBridge(registry, DefaultToolExecutor(registry)) {
            configOf(categories = listOf("UTILITY", "TERMINAL"))
        }
        // TERMINAL 分类即使放行，legacy 别名 TerminalExecLegacy 仍不暴露
        assertEquals(listOf("Calculate"), bridge.toolDefinitions().map { it.name })
    }

    @Test
    fun `vault tools never exposed even when security allowed and unblocked`() {
        val registry = registryOf(FakeTool("VaultSave"), FakeTool("Calculate"))
        val bridge = McpHostBridge(registry, DefaultToolExecutor(registry)) {
            // 用户把 SECURITY 加入白名单且把 vault_* 移出黑名单 —— 仍拦截（写死红线）
            configOf(categories = listOf("UTILITY", "SECURITY"), blocked = emptyList())
        }
        assertEquals(listOf("Calculate"), bridge.toolDefinitions().map { it.name })
        assertTrue(bridge.isVaultTool("VaultSave"))
        assertTrue(bridge.isVaultTool("VaultPaste"))
        assertFalse(bridge.isVaultTool("Calculate"))
    }

    @Test
    fun `tool list sorted by id and schema passthrough`() {
        val registry = registryOf(
            FakeTool("zz_last", parametersSchema = """{"type":"object"}"""),
            FakeTool("aa_first")
        )
        val bridge = McpHostBridge(registry, DefaultToolExecutor(registry)) {
            configOf(categories = listOf("UTILITY"))
        }
        val defs = bridge.toolDefinitions()
        assertEquals(listOf("aa_first", "zz_last"), defs.map { it.name })
        assertEquals("""{"type":"object"}""", defs[1].inputSchemaJson)
        assertTrue(defs[0].description.isNotBlank())
    }

    // ═══ callTool 转发与语义 ═══

    @Test
    fun `callTool forwards to executor and returns Ok`() = runTest {
        val registry = registryOf(
            FakeTool("Calculate") { args -> "sum=" + args.length }
        )
        val bridge = McpHostBridge(registry, DefaultToolExecutor(registry)) {
            configOf(categories = listOf("UTILITY"))
        }
        val result = bridge.callTool("Calculate", """{"a":1}""")
        assertTrue(result is McpHostBridge.HostToolResult.Ok)
        assertEquals("sum=7", (result as McpHostBridge.HostToolResult.Ok).text)
    }

    @Test
    fun `tool returning Error text yields Err (isError semantics)`() = runTest {
        val registry = registryOf(
            FakeTool("Calculate") { "Error: division by zero" }
        )
        val bridge = McpHostBridge(registry, DefaultToolExecutor(registry)) {
            configOf(categories = listOf("UTILITY"))
        }
        val result = bridge.callTool("Calculate", "{}")
        assertTrue(result is McpHostBridge.HostToolResult.Err)
        assertEquals("Error: division by zero", (result as McpHostBridge.HostToolResult.Err).text)
    }

    @Test
    fun `unknown tool yields UnknownTool`() = runTest {
        val registry = registryOf(FakeTool("Calculate"))
        val bridge = McpHostBridge(registry, DefaultToolExecutor(registry)) {
            configOf(categories = listOf("UTILITY"))
        }
        val result = bridge.callTool("nonexistent_tool", "{}")
        assertTrue(result is McpHostBridge.HostToolResult.UnknownTool)
        assertEquals("nonexistent_tool", (result as McpHostBridge.HostToolResult.UnknownTool).toolName)
        assertTrue(result.reason.contains("not registered"))
    }

    @Test
    fun `whitelisted-out tool cannot be called directly (list 与调用一致)`() = runTest {
        val registry = registryOf(FakeTool("ShellExecute"))
        val bridge = McpHostBridge(registry, DefaultToolExecutor(registry)) {
            configOf(categories = listOf("UTILITY")) // SHELL 不在白名单
        }
        val result = bridge.callTool("ShellExecute", """{"command":"ls"}""")
        assertTrue(result is McpHostBridge.HostToolResult.UnknownTool)
        assertTrue((result as McpHostBridge.HostToolResult.UnknownTool).reason.contains("not in the allowed list"))
    }

    @Test
    fun `vault tool call hard-blocked`() = runTest {
        val registry = registryOf(FakeTool("VaultSave") { "should never run" })
        val bridge = McpHostBridge(registry, DefaultToolExecutor(registry)) {
            configOf(categories = listOf("UTILITY", "SECURITY"), blocked = emptyList())
        }
        val result = bridge.callTool("VaultSave", """{"label":"x"}""")
        assertTrue(result is McpHostBridge.HostToolResult.UnknownTool)
        assertTrue((result as McpHostBridge.HostToolResult.UnknownTool)
            .reason.contains("never exposed via MCP Host"))
    }

    @Test
    fun `permission gate denial yields interactive-authorization message`() = runTest {
        val registry = registryOf(FakeTool("Calculate"))
        val denyAll: ToolExecutionGate = object : ToolExecutionGate {
            override suspend fun check(tool: AgentTool, arguments: String): GateDecision =
                GateDecision.Deny("user denied")
        }
        val executor: ToolExecutor = DefaultToolExecutor(registry, gate = denyAll)
        val bridge = McpHostBridge(registry, executor) {
            configOf(categories = listOf("UTILITY"))
        }
        val result = bridge.callTool("Calculate", "{}")
        assertTrue(result is McpHostBridge.HostToolResult.Err)
        val text = (result as McpHostBridge.HostToolResult.Err).text
        // 面向外部客户端的明确指引（替代裸 permission denied）
        assertTrue(text.contains("interactive authorization"))
        assertTrue(text.contains("MCP Host"))
    }

    @Test
    fun `executor exception yields Err not throw`() = runTest {
        val registry = registryOf(
            FakeTool("Calculate") { throw IllegalStateException("boom") }
        )
        val bridge = McpHostBridge(registry, DefaultToolExecutor(registry)) {
            configOf(categories = listOf("UTILITY"))
        }
        val result = bridge.callTool("Calculate", "{}")
        // DefaultToolExecutor 已把异常转为 Error 文本
        assertTrue(result is McpHostBridge.HostToolResult.Err)
    }

    @Test
    fun `exposureBlockReason mirrors list filtering`() {
        val registry = registryOf(
            FakeTool("VaultSave"),
            FakeTool("TerminalExecLegacy"),
            FakeTool("Calculate"),
            FakeTool("ShareContent")
        )
        val bridge = McpHostBridge(registry, DefaultToolExecutor(registry)) {
            configOf(categories = listOf("UTILITY"), blocked = listOf("ShareContent"))
        }
        assertNull(bridge.exposureBlockReason("Calculate")) // 放行 → null
        assertNotNull(bridge.exposureBlockReason("VaultSave"))
        assertNotNull(bridge.exposureBlockReason("TerminalExecLegacy"))
        assertNotNull(bridge.exposureBlockReason("ShareContent"))
        assertNotNull(bridge.exposureBlockReason("ghost_tool"))
    }
}
