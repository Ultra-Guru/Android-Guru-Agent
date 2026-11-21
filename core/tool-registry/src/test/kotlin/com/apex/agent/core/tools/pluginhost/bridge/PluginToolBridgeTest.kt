package com.apex.agent.core.tools.pluginhost.bridge

import com.apex.agent.core.tools.DefaultToolRegistry
import com.apex.agent.core.tools.pluginhost.PluginEcosystem
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PluginToolBridge 契约锁（纯 JVM，fake executor）。
 *
 * 真实 Worker 链路的注册/调用/清理行为由 PluginLifecycleManagerTest
 * （真实 node）补足；本套锁定桥自身的注册语义。
 */
class PluginToolBridgeTest {

    private val registry = DefaultToolRegistry()

    private fun fakeExecutor(outputs: MutableMap<String, String> = mutableMapOf()) =
        PluginAgentTool.PluginToolExecutor { _, toolName, argsJson ->
            val canned = outputs[toolName]
            if (canned != null) PluginAgentTool.ToolExecutionOutcome.success("$canned:$argsJson")
            else PluginAgentTool.ToolExecutionOutcome.failure("no canned output")
        }

    @Test
    fun `bind registers plugin tools as first-class agent tools`() = runTest {
        val bridge = PluginToolBridge(registry)
        val result = bridge.bind(
            "opencode.scope-tool", PluginEcosystem.OPENCODE_V2,
            listOf(
                PluginAgentTool.ToolDescriptor("echo", "Echo input", """{"type":"object","properties":{"text":{"type":"string"}}}"""),
                PluginAgentTool.ToolDescriptor("fetch_data", "Fetch data")
            ),
            fakeExecutor()
        )
        assertEquals(2, result.registered.size)
        assertEquals(0, result.rejected.size)
        // v5 命名：Plugin 前缀 + 无分隔符 PascalCase
        assertTrue(result.registered.contains("PluginOpencodeScopeToolEcho"))
        assertTrue(result.registered.contains("PluginOpencodeScopeToolFetchData"))
        // 一等注册：ToolRegistry 可寻址
        assertNotNull(registry.getTool("PluginOpencodeScopeToolEcho"))
        // schema 清洗：有 schema 的保留 object 根，无 schema 的降级骨架
        val echo = registry.getTool("PluginOpencodeScopeToolEcho")!!
        assertTrue(echo.parametersSchema.contains("object"))
        val fetch = registry.getTool("PluginOpencodeScopeToolFetchData")!!
        assertEquals("""{"type":"object","properties":{}}""", fetch.parametersSchema)
    }

    @Test
    fun `tool execution routes through executor with error protocol`() = runTest {
        val bridge = PluginToolBridge(registry)
        bridge.bind(
            "opencode.scope-tool", PluginEcosystem.OPENCODE_V2,
            listOf(PluginAgentTool.ToolDescriptor("echo", "Echo")),
            fakeExecutor(outputs = mutableMapOf("echo" to "canned"))
        )
        val tool = registry.getTool("PluginOpencodeScopeToolEcho")!!
        val ok = tool.execute("""{"text":"hi"}""")
        assertTrue(ok.startsWith("canned:"))

        // 执行失败 → "Error:" 前缀协议（引擎字符串成功判定依据）
        bridge.bind(
            "opencode.failing", PluginEcosystem.OPENCODE_V2,
            listOf(PluginAgentTool.ToolDescriptor("broken", "Broken")),
            PluginAgentTool.PluginToolExecutor { _, _, _ ->
                PluginAgentTool.ToolExecutionOutcome.failure("worker died")
            }
        )
        val broken = registry.getTool("PluginOpencodeFailingBroken")!!
        val err = broken.execute("{}")
        assertTrue(err.startsWith("Error:"))
        assertTrue(err.contains("worker died"))
        assertTrue(err.contains("NOT aborted"))
    }

    @Test
    fun `bind rejects conflict with non-plugin tool`() = runTest {
        val bridge = PluginToolBridge(registry)
        // 预置一个「恰好会撞名」的非插件工具
        registry.register(FakeBuiltinTool("PluginOpencodeScopeToolEcho"))

        val result = bridge.bind(
            "opencode.scope-tool", PluginEcosystem.OPENCODE_V2,
            listOf(PluginAgentTool.ToolDescriptor("echo", "Echo")),
            fakeExecutor()
        )
        assertEquals(0, result.registered.size)
        assertEquals(1, result.rejected.size)
        assertTrue(result.rejected.single().reason.contains("冲突"))
        // 原工具未被覆盖（数据永不 REPLACE 代码）
        assertTrue(registry.getTool("PluginOpencodeScopeToolEcho") is FakeBuiltinTool)
    }

    @Test
    fun `rebind same plugin replaces its own tools idempotently`() = runTest {
        val bridge = PluginToolBridge(registry)
        bridge.bind(
            "opencode.scope-tool", PluginEcosystem.OPENCODE_V2,
            listOf(PluginAgentTool.ToolDescriptor("echo", "Echo")),
            fakeExecutor()
        )
        // Worker 重启重放：同插件再 bind（新旧工具混合）
        val result = bridge.bind(
            "opencode.scope-tool", PluginEcosystem.OPENCODE_V2,
            listOf(
                PluginAgentTool.ToolDescriptor("echo", "Echo v2"),
                PluginAgentTool.ToolDescriptor("extra", "Extra")
            ),
            fakeExecutor()
        )
        assertEquals(2, result.registered.size)
        // 追踪集合无重复
        assertEquals(setOf("PluginOpencodeScopeToolEcho", "PluginOpencodeScopeToolExtra"), bridge.boundToolIds("opencode.scope-tool"))
    }

    @Test
    fun `unbind removes all plugin tools and is idempotent`() = runTest {
        val bridge = PluginToolBridge(registry)
        bridge.bind(
            "opencode.scope-tool", PluginEcosystem.OPENCODE_V2,
            listOf(
                PluginAgentTool.ToolDescriptor("echo", "Echo"),
                PluginAgentTool.ToolDescriptor("extra", "Extra")
            ),
            fakeExecutor()
        )
        assertEquals(2, bridge.unbind("opencode.scope-tool"))
        assertNull(registry.getTool("PluginOpencodeScopeToolEcho"))
        assertNull(registry.getTool("PluginOpencodeScopeToolExtra"))
        // 幂等：再 unbind 返回 0
        assertEquals(0, bridge.unbind("opencode.scope-tool"))
        assertTrue(bridge.boundToolIds("opencode.scope-tool").isEmpty())
        // 其它插件不受影响
        bridge.bind(
            "opencode.other", PluginEcosystem.OPENCODE_V2,
            listOf(PluginAgentTool.ToolDescriptor("echo", "Echo")),
            fakeExecutor()
        )
        bridge.unbind("opencode.scope-tool")
        assertNotNull(registry.getTool("PluginOpencodeOtherEcho"))
        assertFalse(bridge.boundPluginIds().isEmpty())
    }

    /** 非 PluginAgentTool 的占位工具（冲突测试用）。 */
    private class FakeBuiltinTool(override val id: String) :
        com.apex.agent.core.tools.AgentTool {
        override val name = id
        override val description = "builtin placeholder"
        override val parametersSchema = """{"type":"object","properties":{}}"""
        override suspend fun execute(arguments: String) = "builtin"
    }
}
