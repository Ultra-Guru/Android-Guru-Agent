package com.apex.agent.core.tools.extension.dsh

import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.ToolRegistry
import com.apex.agent.core.tools.extension.ExtensionDirLayout
import com.apex.agent.core.tools.extension.NodeInstallPort
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * # DshToolRegistrar 契约锁（能力快照 → 一等工具的最后一公里）
 *
 * 核查稿结论：`DshToolRegistrar` 此前只存在于 KDoc 引用——能力快照
 * （capabilities.json）从未变成 ToolRegistry 里的一等工具。本测试锁住：
 *
 * - **红线**：只注册 observed available 工具；unavailable 绝不进注册表；
 * - **新鲜度**：包内容指纹与快照不一致（插件已变）→ 拒绝注册（不拿旧账）；
 * - **冷执行契约**：`node dsh-run-tool.js <pkg> <tool>` + stdin JSON 参数；
 *   envelope `{ok, result|error}` 解析（ok=false → "Error:" 前缀失败语义）；
 * - **stale 清算 / 卸载注销**：只动自己注册的 id 集。
 */
class DshToolRegistrarTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeRegistry : ToolRegistry {
        val tools = linkedMapOf<String, AgentTool>()
        override fun register(tool: AgentTool) { tools[tool.id] = tool }
        override fun unregister(toolId: String) { tools.remove(toolId) }
        override fun getTool(toolId: String): AgentTool? = tools[toolId]
        override fun getAllTools(): List<AgentTool> = tools.values.toList()
        override fun getToolDefinitions(): List<com.apex.agent.core.llm.ToolDefinition> = emptyList()
    }

    /** 冷执行端口 fake：捕获 argv/stdin，回放 envelope。 */
    private class FakePort : NodeInstallPort {
        val calls = CopyOnWriteArrayList<Triple<List<String>, File, String?>>()
        var envelope: String = """{"ok":true,"tool":"echo","result":{"echo":"hi"}}"""
        var exitCode: Int = 0
        override suspend fun exec(
            command: List<String>,
            cwd: File,
            timeoutMs: Long,
            stdin: String?
        ): Result<NodeInstallPort.ExecOutcome> {
            calls += Triple(command, cwd, stdin)
            return Result.success(NodeInstallPort.ExecOutcome(exitCode, envelope, ""))
        }
        override suspend fun probeRuntime(): NodeInstallPort.RuntimeProbe =
            NodeInstallPort.RuntimeProbe("v24.9.0", "10.0.0")
    }

    /** 造一个最小 DSH 插件 pkg（package.json + index.js）并落能力快照。 */
    private fun fixturePlugin(
        layout: ExtensionDirLayout,
        store: DshCapabilitiesStore,
        extId: String,
        tools: List<DshCapabilities.ToolCapability>
    ): File {
        val pkg = layout.pkgDir(extId).apply { mkdirs() }
        pkg.resolve("package.json").writeText("""{"name":"dsh-fixture","version":"1.0.0","main":"index.js"}""")
        pkg.resolve("index.js").writeText("module.exports = (ctx) => {}")
        val fingerprint = DshPackageFingerprint.compute(pkg).fingerprint
        store.write(
            extId,
            DshCapabilities(
                plugin = DshCapabilities.PluginIdentity(id = extId, name = "dsh-fixture", version = "1.0.0"),
                tools = tools,
                analyzedAt = System.currentTimeMillis(),
                inputFingerprint = fingerprint
            )
        )
        return pkg
    }

    private fun availableTool(name: String = "echo") = DshCapabilities.ToolCapability(
        name = name,
        description = "Echo the arguments",
        parametersSchema = """{"type":"object","properties":{"text":{"type":"string"}}}""",
        available = true
    )

    private fun unavailableTool(name: String = "broken") = DshCapabilities.ToolCapability(
        name = name,
        description = "Depends on llm service",
        available = false,
        unavailableReason = "依赖未支持服务 llm（宿主 shim 不提供）"
    )

    @Test
    fun `registers observed available tools as first-class and executes cold-start`() = runTest {
        val layout = ExtensionDirLayout(tmp.newFolder())
        val store = DshCapabilitiesStore(layout)
        val registry = FakeRegistry()
        val port = FakePort()
        val pkg = fixturePlugin(
            layout, store, "dsh.fixture",
            listOf(availableTool("echo"), unavailableTool("broken"))
        )
        val registrar = DshToolRegistrar(
            registryProvider = { registry },
            store = store,
            layout = layout,
            port = port,
            extensionRoot = layout.extensionRoot
        )

        val notes = registrar.registerPlugin("dsh.fixture")
        // 红线：只有 available 工具进注册表；unavailable 如实进 notes
        assertEquals(1, registry.tools.size)
        val tool = registry.tools.values.single()
        assertEquals("PluginDshFixtureEcho", tool.id)
        assertTrue(tool.parametersSchema.contains("text"))
        assertTrue(notes.any { it.contains("PluginDshFixtureEcho") })
        assertTrue(notes.any { it.contains("broken") && it.contains("llm") })

        // 冷执行契约：node dsh-run-tool.js <pkg> <tool> + stdin JSON
        val output = tool.execute("""{"text":"hi"}""")
        assertTrue(output.contains("echo"))
        val (command, cwd, stdin) = port.calls.single()
        assertEquals("node", command.first())
        assertTrue(command[1].endsWith("dsh-run-tool.js"))
        assertEquals(pkg.canonicalFile.absolutePath, command[2])
        assertEquals("echo", command[3])
        assertEquals(pkg.canonicalFile, cwd.canonicalFile)
        assertEquals("""{"text":"hi"}""", stdin)
    }

    @Test
    fun `envelope error maps to Error-prefix failure protocol`() = runTest {
        val layout = ExtensionDirLayout(tmp.newFolder())
        val store = DshCapabilitiesStore(layout)
        val registry = FakeRegistry()
        val port = FakePort().apply {
            envelope = """{"ok":false,"tool":"echo","error":{"code":"execute-failed","message":"boom"}}"""
        }
        fixturePlugin(layout, store, "dsh.fixture", listOf(availableTool("echo")))
        val registrar = DshToolRegistrar({ registry }, store, layout, port, layout.extensionRoot)

        registrar.registerPlugin("dsh.fixture")
        val output = registry.tools.values.single().execute("{}")
        // 引擎成败判定是字符串协议级："Error:" 前缀必须出现（裸错误文本会被误判成功）
        assertTrue(output.startsWith("Error:"))
        assertTrue(output.contains("boom"))
    }

    @Test
    fun `stale fingerprint refuses registration`() = runTest {
        val layout = ExtensionDirLayout(tmp.newFolder())
        val store = DshCapabilitiesStore(layout)
        val registry = FakeRegistry()
        val pkg = fixturePlugin(layout, store, "dsh.fixture", listOf(availableTool()))
        val registrar = DshToolRegistrar({ registry }, store, layout, FakePort(), layout.extensionRoot)

        // 插件内容已变（快照过期）→ 拒绝注册
        pkg.resolve("index.js").writeText("module.exports = (ctx) => { /* changed */ }")
        val notes = registrar.registerPlugin("dsh.fixture")
        assertTrue(registry.tools.isEmpty())
        assertTrue(notes.any { it.contains("过期") || it.contains("缺失") })
    }

    @Test
    fun `upgrade shrinks tool face with stale cleanup and unregister removes all`() = runTest {
        val layout = ExtensionDirLayout(tmp.newFolder())
        val store = DshCapabilitiesStore(layout)
        val registry = FakeRegistry()
        val port = FakePort()
        val extId = "dsh.fixture"
        fixturePlugin(layout, store, extId, listOf(availableTool("echo"), availableTool("ping")))
        val registrar = DshToolRegistrar({ registry }, store, layout, port, layout.extensionRoot)

        registrar.registerPlugin(extId)
        assertEquals(setOf("PluginDshFixtureEcho", "PluginDshFixturePing"), registry.tools.keys)

        // 升级：工具面缩水（ping 移除）→ 快照重写 → 重注册 → stale 清算
        val pkg = layout.pkgDir(extId)
        pkg.resolve("index.js").writeText("module.exports = (ctx) => { /* v2 */ }")
        val fingerprint = DshPackageFingerprint.compute(pkg).fingerprint
        store.write(
            extId,
            DshCapabilities(
                plugin = DshCapabilities.PluginIdentity(id = extId, version = "2.0.0"),
                tools = listOf(availableTool("echo")),
                inputFingerprint = fingerprint
            )
        )
        registrar.registerPlugin(extId)
        assertEquals(setOf("PluginDshFixtureEcho"), registry.tools.keys)

        // 卸载：全部注销（幂等）
        registrar.unregisterPlugin(extId)
        assertTrue(registry.tools.isEmpty())
        registrar.unregisterPlugin(extId) // 幂等
        assertTrue(registry.tools.isEmpty())
    }

    @Test
    fun `missing pkg directory skips honestly`() = runTest {
        val layout = ExtensionDirLayout(tmp.newFolder())
        val store = DshCapabilitiesStore(layout)
        val registrar = DshToolRegistrar({ FakeRegistry() }, store, layout, FakePort(), layout.extensionRoot)
        val notes = registrar.registerPlugin("dsh.ghost")
        assertTrue(registrar.registeredSnapshot().isEmpty())
        assertTrue(notes.any { it.contains("pkg 目录缺失") })
    }
}
