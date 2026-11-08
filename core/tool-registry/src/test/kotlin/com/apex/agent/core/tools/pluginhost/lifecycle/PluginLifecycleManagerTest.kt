package com.apex.agent.core.tools.pluginhost.lifecycle

import com.apex.agent.core.tools.DefaultToolRegistry
import com.apex.agent.core.tools.pluginhost.PluginDescriptor
import com.apex.agent.core.tools.pluginhost.PluginEcosystem
import com.apex.agent.core.tools.pluginhost.PluginLifecycleState
import com.apex.agent.core.tools.pluginhost.bridge.PluginEventBus
import com.apex.agent.core.tools.pluginhost.bridge.PluginToolBridge
import com.apex.agent.core.tools.pluginhost.worker.LocalProcessWorkerPort
import com.apex.agent.core.tools.pluginhost.worker.NodeRuntimeProvider
import com.apex.agent.core.tools.pluginhost.worker.PluginWorkerSupervisor
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * # PluginLifecycleManager 端到端测试（真实 node，Phase 3 验收锚点）
 *
 * 锁定完整链路（spec §16.3 Agent 集成测试）：
 * ```
 * 启动插件 → Worker 加载 → 工具注册进 ToolRegistry → 引擎调用工具 →
 * Worker 执行 → 结果返回 → 停止 → 工具注销
 * ```
 * 外加：禁用/启用/卸载的状态机语义、启动失败的诚实 FAILED 状态。
 */
class PluginLifecycleManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun nodeAvailable(): Boolean = runCatching {
        ProcessBuilder("node", "--version").start().waitFor() == 0
    }.getOrDefault(false)

    private fun realIo(timeoutMs: Long = 90_000, block: suspend CoroutineScope.() -> Unit) {
        val latch = CountDownLatch(1)
        var failure: Throwable? = null
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
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

    private suspend fun awaitCondition(timeoutMs: Long = 20_000, condition: suspend () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!condition()) delay(25)
        }
    }

    private fun writeFixture(dir: File): File {
        val entry = File(dir, "index.mjs")
        entry.writeText(
            """
            export const tools = {
              greet: {
                description: 'Greet a person',
                parametersSchema: '{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}',
                execute: async (args) => ({ output: 'hello ' + (args.name || 'world') })
              }
            };
            """.trimIndent()
        )
        return entry
    }

    private fun descriptor(id: String) = PluginDescriptor(
        id = id,
        name = "Fixture",
        ecosystem = PluginEcosystem.OPENCODE_V2
    )

    @Test
    fun `full chain start bind call stop unbind`() {
        assumeTrue("node not available — test honestly skipped", nodeAvailable())
        val dir = tmp.newFolder()
        val entry = writeFixture(dir)

        realIo {
            val registry = DefaultToolRegistry()
            val eventBus = PluginEventBus(this)
            val toolBridge = PluginToolBridge(registry)
            val supervisor = PluginWorkerSupervisor(
                spawnPort = LocalProcessWorkerPort(),
                provider = NodeRuntimeProvider(NoProbePort()),
                bootstrapDir = dir,
                parentScope = this
            )
            val uninstalled = CopyOnWriteArrayList<String>()
            val manager = PluginLifecycleManager(
                supervisor, toolBridge, eventBus,
                ledgerRemover = { uninstalled += it }
            )

            // 启动：LOADING → ACTIVE，工具注册
            val status = manager.start(descriptor("opencode.fixture"), dir, entry.path, "esm").getOrThrow()
            assertEquals(PluginLifecycleState.ACTIVE, status.state)
            val toolId = "PluginOpencodeFixtureGreet"
            assertNotNull(registry.getTool(toolId))

            // 引擎调用：ToolRegistry 寻址 → Worker 执行 → 结果返回
            val tool = registry.getTool(toolId)!!
            assertEquals("hello apex", tool.execute("""{"name":"apex"}"""))

            // 停止：STOPPING → STOPPED，工具注销（spec §7.3 逆序清理）
            val stopped = manager.stop("opencode.fixture").getOrThrow()
            assertEquals(PluginLifecycleState.STOPPED, stopped.state)
            assertNull(registry.getTool(toolId))
            assertEquals(0, toolBridge.boundToolIds("opencode.fixture").size)

            // 幂等：再 stop 不炸
            assertEquals(PluginLifecycleState.STOPPED, manager.stop("opencode.fixture").getOrThrow().state)
            supervisor.stopAll()
        }
    }

    @Test
    fun `disable enable and restart flow`() {
        assumeTrue("node not available — test honestly skipped", nodeAvailable())
        val dir = tmp.newFolder()
        val entry = writeFixture(dir)

        realIo {
            val registry = DefaultToolRegistry()
            val eventBus = PluginEventBus(this)
            val toolBridge = PluginToolBridge(registry)
            val supervisor = PluginWorkerSupervisor(
                spawnPort = LocalProcessWorkerPort(),
                provider = NodeRuntimeProvider(NoProbePort()),
                bootstrapDir = dir,
                parentScope = this
            )
            val manager = PluginLifecycleManager(
                supervisor, toolBridge, eventBus, ledgerRemover = { }
            )

            manager.start(descriptor("opencode.fixture"), dir, entry.path, "esm").getOrThrow()
            // 禁用：停止 + DISABLED
            assertEquals(
                PluginLifecycleState.DISABLED,
                manager.disable("opencode.fixture").getOrThrow().state
            )
            assertNull(registry.getTool("PluginOpencodeFixtureGreet"))
            // 启用：PREPARED（待显式 start）
            assertEquals(
                PluginLifecycleState.PREPARED,
                manager.enable("opencode.fixture").getOrThrow().state
            )
            // 再启动：工具恢复注册、可调用
            manager.start(descriptor("opencode.fixture"), dir, entry.path, "esm").getOrThrow()
            assertEquals(
                "hello world",
                registry.getTool("PluginOpencodeFixtureGreet")!!.execute("{}")
            )
            supervisor.stopAll()
        }
    }

    @Test
    fun `uninstall runs ledger removal and clears status`() {
        assumeTrue("node not available — test honestly skipped", nodeAvailable())
        val dir = tmp.newFolder()
        val entry = writeFixture(dir)

        realIo {
            val registry = DefaultToolRegistry()
            val eventBus = PluginEventBus(this)
            val toolBridge = PluginToolBridge(registry)
            val supervisor = PluginWorkerSupervisor(
                spawnPort = LocalProcessWorkerPort(),
                provider = NodeRuntimeProvider(NoProbePort()),
                bootstrapDir = dir,
                parentScope = this
            )
            val uninstalled = CopyOnWriteArrayList<String>()
            val manager = PluginLifecycleManager(
                supervisor, toolBridge, eventBus,
                ledgerRemover = { uninstalled += it }
            )

            manager.start(descriptor("opencode.fixture"), dir, entry.path, "esm").getOrThrow()
            manager.uninstall("opencode.fixture").getOrThrow()

            assertEquals(listOf("opencode.fixture"), uninstalled.toList())
            assertNull(registry.getTool("PluginOpencodeFixtureGreet"))
            // 状态表清空：DISCOVERED（无记录）语义
            assertEquals(
                PluginLifecycleState.DISCOVERED,
                manager.statusOf("opencode.fixture").state
            )
            assertTrue(manager.statuses().none { it.pluginId == "opencode.fixture" })
            supervisor.stopAll()
        }
    }

    @Test
    fun `startup failure lands in FAILED with honest reason`() {
        assumeTrue("node not available — test honestly skipped", nodeAvailable())
        val dir = tmp.newFolder()

        realIo {
            val registry = DefaultToolRegistry()
            val eventBus = PluginEventBus(this)
            val toolBridge = PluginToolBridge(registry)
            val supervisor = PluginWorkerSupervisor(
                spawnPort = LocalProcessWorkerPort(),
                provider = NodeRuntimeProvider(NoProbePort()),
                bootstrapDir = dir,
                parentScope = this
            )
            val manager = PluginLifecycleManager(
                supervisor, toolBridge, eventBus, ledgerRemover = { }
            )

            // 入口不存在 → Worker 启动成功但 load 失败 → FAILED（带原因）
            val result = manager.start(
                descriptor("opencode.missing"), dir, File(dir, "nope.mjs").path, "esm"
            )
            assertTrue(result.isFailure)
            assertEquals(
                PluginLifecycleState.FAILED,
                manager.statusOf("opencode.missing").state
            )
            assertTrue(
                manager.statusOf("opencode.missing").lastReason?.contains("失败") == true
            )
            // 失败插件不残留工具
            assertTrue(toolBridge.boundToolIds("opencode.missing").isEmpty())
            supervisor.stopAll()
        }
    }

    /** 探测通道假实现（Worker 路径不依赖探测）。 */
    private class NoProbePort : com.apex.agent.core.tools.extension.NodeInstallPort {
        override suspend fun exec(
            command: List<String>,
            cwd: File,
            timeoutMs: Long
        ): Result<com.apex.agent.core.tools.extension.NodeInstallPort.ExecOutcome> =
            Result.failure(UnsupportedOperationException("probe not wired"))

        override suspend fun probeRuntime() =
            com.apex.agent.core.tools.extension.NodeInstallPort.RuntimeProbe(null, null)
    }
}
