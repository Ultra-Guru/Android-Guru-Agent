package com.apex.agent.plugin.host.functional

import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.DefaultToolRegistry
import com.apex.agent.core.tools.ToolExecutor
import com.apex.agent.core.tools.ToolStreamEvent
import com.apex.agent.plugin.api.PluginKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [UnifiedPluginRegistry] 统一门面单测（Plugin System v2）。
 *
 * 锁死的核心契约：
 * 1. 安装即激活（默认启用态）——工具宏真实注册进 ToolRegistry（可查可执行）；
 * 2. REJECT 语义——功能性插件的工具 id 与既有工具冲突时**永不覆盖**，
 *    冲突进条目 detail；其余贡献物照常激活（单贡献物隔离）；
 * 3. 禁用 = 工具回收（精确 unregister 自己注册的工具，不挖别人的位）；
 *    重新启用 = 再次激活；
 * 4. 升级 = 旧工具回收 + 新 manifest 重放；
 * 5. 卸载 = 回收 + 目录删除 + 目录流对账；
 * 6. 斜杠指令快照只含已激活插件；统一目录合并 APK 已加载态。
 */
class UnifiedPluginRegistryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** APK 门面 fake：无 Android 依赖的加载态流。 */
    private class FakeApkSource : ApkPluginSource {
        private val _loaded = MutableStateFlow<Map<String, String>>(emptyMap())
        override val loadedApkPlugins: StateFlow<Map<String, String>> = _loaded.asStateFlow()

        fun setLoaded(vararg entries: Pair<String, String>) {
            _loaded.value = entries.toMap()
        }
    }

    /** 宏步骤执行器 fake：固定回显。 */
    private class EchoExecutor : ToolExecutor {
        val calls = mutableListOf<Pair<String, String>>()

        override suspend fun execute(toolId: String, arguments: String): String {
            calls += toolId to arguments
            return "echo:$toolId"
        }

        override fun executeStream(toolId: String, arguments: String): Flow<ToolStreamEvent> =
            kotlinx.coroutines.flow.flow { emit(ToolStreamEvent.Output(execute(toolId, arguments))) }
    }

    private fun registry(
        apkSource: FakeApkSource = FakeApkSource(),
        registry: DefaultToolRegistry = DefaultToolRegistry(),
        executor: EchoExecutor = EchoExecutor()
    ): Triple<UnifiedPluginRegistry, DefaultToolRegistry, FakeApkSource> {
        val store = FunctionalPluginStore(tmp.newFolder("plugins"))
        return Triple(
            UnifiedPluginRegistry(store, apkSource, registry, executor),
            registry,
            apkSource
        )
    }

    private val manifestJson = """
        {
          "id": "functional.briefing",
          "name": "每日简报",
          "version": 1,
          "description": "组合工具生成每日简报",
          "contributions": {
            "tools": [{
              "id": "briefing_run",
              "description": "生成简报",
              "steps": [{"tool": "web_fetch", "args": {"url": "{{input.site}}"}}]
            }],
            "slashCommands": [{"id": "briefing", "title": "每日简报", "prompt": "生成简报"}]
          }
        }
    """.trimIndent()

    // ═══════════════ 安装即激活 ═══════════════

    @Test
    fun `安装后工具真实注册进 ToolRegistry 且目录状态 ACTIVE`() = runTest {
        val (unified, toolRegistry, _) = registry()

        val result = unified.installFunctionalPlugin(manifestJson)

        assertTrue(result is FunctionalPluginStore.InstallResult.Installed)
        val tool = toolRegistry.getTool("briefing_run")
        assertNotNull("工具应已注册", tool)
        val output = tool!!.execute("""{"site":"https://example.com"}""")
        assertTrue(output.startsWith("OK:"))
        assertTrue(output.contains("echo:web_fetch"))
        unified.refresh()
        val entry = unified.catalog.value.single()
        assertEquals(PluginKind.FUNCTIONAL, entry.kind)
        assertEquals(UnifiedPluginState.ACTIVE, entry.state)
    }

    @Test
    fun `拒绝安装的 manifest 不留任何激活痕迹`() = runTest {
        val (unified, toolRegistry, _) = registry()

        val result = unified.installFunctionalPlugin("""{"id":"bad id","version":1}""")

        assertTrue(result is FunctionalPluginStore.InstallResult.Rejected)
        unified.refresh()
        assertTrue(unified.catalog.value.isEmpty())
        assertNull(toolRegistry.getTool("briefing_run"))
    }

    // ═══════════════ REJECT 语义（永不 REPLACE） ═══════════════

    @Test
    fun `工具 id 冲突时 REJECT 不覆盖既有工具`() = runTest {
        val (unified, toolRegistry, _) = registry()
        // 预置一个同 id 的「宿主内置」工具（注册在先 = 占位者）。
        toolRegistry.register(
            HostAnchorTool("briefing_run"),
            com.apex.agent.core.tools.DuplicateToolIdPolicy.REJECT
        )

        unified.installFunctionalPlugin(manifestJson)
        unified.refresh()

        val tool = toolRegistry.getTool("briefing_run")
        assertTrue("内置工具位必须原封不动", tool is HostAnchorTool)
        // 冲突如实进条目 detail，其余贡献物（斜杠指令）照常激活。
        val entry = unified.catalog.value.single()
        assertTrue(entry.detail!!.contains("briefing_run"))
        assertTrue(unified.functionalSlashCommands().isNotEmpty())
    }

    /** 占位锚点工具：身份判据（===）用。 */
    private class HostAnchorTool(override val id: String) : AgentTool {
        override val name get() = "Host Anchor"
        override val description get() = "built-in anchor"
        override val parametersSchema get() = """{"type":"object","properties":{}}"""
        override suspend fun execute(arguments: String): String = "host-tool"
    }

    // ═══════════════ 启停（工具回收与恢复） ═══════════════

    @Test
    fun `禁用回收工具且重新启用恢复`() = runTest {
        val (unified, toolRegistry, _) = registry()
        unified.installFunctionalPlugin(manifestJson)
        unified.refresh()

        assertTrue(unified.setFunctionalEnabled("functional.briefing", false))
        unified.refresh()
        assertNull("禁用后工具应被回收", toolRegistry.getTool("briefing_run"))
        assertEquals(UnifiedPluginState.INSTALLED, unified.catalog.value.single().state)
        assertTrue(unified.functionalSlashCommands().isEmpty())

        assertTrue(unified.setFunctionalEnabled("functional.briefing", true))
        unified.refresh()
        assertNotNull("重新启用应恢复注册", toolRegistry.getTool("briefing_run"))
        assertEquals(UnifiedPluginState.ACTIVE, unified.catalog.value.single().state)
    }

    @Test
    fun `禁用只回收自己的工具不挖内置工具位`() = runTest {
        val (unified, toolRegistry, _) = registry()
        val anchor = HostAnchorTool("host_tool")
        toolRegistry.register(anchor)

        unified.installFunctionalPlugin(manifestJson)
        unified.setFunctionalEnabled("functional.briefing", false)

        assertTrue("内置工具位不受插件启停影响", toolRegistry.getTool("host_tool") === anchor)
    }

    @Test
    fun `未知 id 启停如实返回 false`() = runTest {
        val (unified, _, _) = registry()

        assertFalse(unified.setFunctionalEnabled("functional.ghost", true))
    }

    // ═══════════════ 升级与卸载 ═══════════════

    @Test
    fun `升级回收旧工具并按新 manifest 重放`() = runTest {
        val (unified, toolRegistry, _) = registry()
        unified.installFunctionalPlugin(manifestJson)
        val oldTool = toolRegistry.getTool("briefing_run")

        val upgraded = manifestJson
            .replace("\"version\": 1", "\"version\": 2")
            .replace("briefing_run", "briefing_run_v2")
        val result = unified.installFunctionalPlugin(upgraded)

        assertTrue(result is FunctionalPluginStore.InstallResult.Upgraded)
        assertNull("旧 id 应随旧贡献物回收", toolRegistry.getTool("briefing_run"))
        assertTrue("旧实例不可残留", toolRegistry.getTool("briefing_run_v2") !== oldTool)
        assertNotNull(toolRegistry.getTool("briefing_run_v2"))
    }

    @Test
    fun `卸载回收工具且目录删除`() = runTest {
        val (unified, toolRegistry, _) = registry()
        unified.installFunctionalPlugin(manifestJson)

        assertTrue(unified.uninstallFunctionalPlugin("functional.briefing"))
        unified.refresh()
        assertNull(toolRegistry.getTool("briefing_run"))
        assertTrue(unified.catalog.value.isEmpty())
        assertTrue(unified.functionalSlashCommands().isEmpty())
    }

    // ═══════════════ 斜杠指令与统一目录 ═══════════════

    @Test
    fun `斜杠指令快照只含已激活插件`() = runTest {
        val (unified, _, _) = registry()
        unified.installFunctionalPlugin(manifestJson)

        val commands = unified.functionalSlashCommands()

        val command = commands.single()
        assertEquals("briefing", command.commandId)
        assertEquals("functional.briefing", command.pluginId)
        assertEquals("每日简报", command.title)
        assertEquals("生成简报", command.prompt)
    }

    @Test
    fun `统一目录合并 APK 已加载态`() = runTest {
        val apkSource = FakeApkSource()
        val (unified, _, _) = registry(apkSource)
        unified.installFunctionalPlugin(manifestJson)
        apkSource.setLoaded("com.apex.agent.plugin.workflow" to "工作流引擎")
        unified.refresh()

        val entries = unified.catalog.value
        assertEquals(2, entries.size)
        val apkEntry = entries.first { it.kind == PluginKind.APK }
        assertEquals("com.apex.agent.plugin.workflow", apkEntry.id)
        assertEquals("工作流引擎", apkEntry.name)
        assertEquals(UnifiedPluginState.ACTIVE, apkEntry.state)
        assertTrue(apkEntry.enabled)
    }

    @Test
    fun `启动激活跳过禁用与 BROKEN 插件`() = runTest {
        val (unified, toolRegistry, _) = registry()
        unified.installFunctionalPlugin(manifestJson)
        unified.setFunctionalEnabled("functional.briefing", false)
        // 手写一个 BROKEN 目录（manifest 损坏）。
        val storeRoot = tmp.root.resolve("plugins/functional.broken").apply { mkdirs() }
        storeRoot.resolve("plugin.json").writeText("{corrupt")

        val activated = unified.activateEnabledFunctionalPlugins()

        assertTrue(activated.isEmpty())
        assertNull(toolRegistry.getTool("briefing_run"))
    }
}
