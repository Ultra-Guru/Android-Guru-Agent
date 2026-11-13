package com.apex.agent.core.tools.pluginhost.doctor

import com.apex.agent.core.tools.DefaultToolRegistry
import com.apex.agent.core.tools.pluginhost.PluginDescriptor
import com.apex.agent.core.tools.pluginhost.PluginEcosystem
import com.apex.agent.core.tools.pluginhost.bridge.PluginEventBus
import com.apex.agent.core.tools.pluginhost.bridge.PluginToolBridge
import com.apex.agent.core.tools.pluginhost.lifecycle.PluginLifecycleManager
import com.apex.agent.core.tools.pluginhost.worker.BunRuntimeProvider
import com.apex.agent.core.tools.pluginhost.worker.LocalProcessWorkerPort
import com.apex.agent.core.tools.pluginhost.worker.NodeRuntimeProvider
import com.apex.agent.core.tools.pluginhost.worker.PluginWorkerSupervisor
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * # PluginHostDoctorTool 端到端测试（真实 node，Phase 6 验收锚点）
 *
 * Agent 视角的诊断面：启动一个真实 fixture 插件 → Doctor 报告 ACTIVE 状态 +
 * 已绑定工具 + Worker 健康 → 停止 → Doctor 反映 STOPPED 与零绑定。
 */
class PluginHostDoctorToolTest {

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

    @Test
    fun `doctor reports live plugin state tools and runtime`() {
        assumeTrue("node not available — test honestly skipped", nodeAvailable())
        val dir = tmp.newFolder()
        val entry = File(dir, "index.mjs")
        entry.writeText(
            """
            export const tools = {
              greet: { description: 'Greet', execute: async () => ({ output: 'hi' }) }
            };
            """.trimIndent()
        )

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
            val manager = PluginLifecycleManager(supervisor, toolBridge, eventBus, ledgerRemover = { })

            manager.start(
                PluginDescriptor(id = "opencode.fixture", name = "Fixture", ecosystem = PluginEcosystem.OPENCODE_V2),
                dir, entry.path, "esm"
            ).getOrThrow()

            val doctor = PluginHostDoctorTool(
                lifecycle = manager,
                supervisor = supervisor,
                toolBridge = toolBridge,
                nodeProvider = NodeRuntimeProvider(NoProbePort()),
                bunProvider = BunRuntimeProvider(NoProbePort())
            )

            // 运行态报告：ACTIVE + 工具已绑定 + Worker 健康
            val live = doctor.execute("{}")
            assertTrue(live.contains("opencode.fixture: ACTIVE"))
            assertTrue(live.contains("[tools] bound plugins: 1"))
            assertTrue(live.contains("PluginOpencodeFixtureGreet"))
            assertTrue(live.contains("[workers] supervised: 1"))
            assertTrue(live.contains("alive=true"))

            // 生态评级通道（探测走 NoProbe 端口 → missing 如实展示，不臆测）
            val compat = doctor.execute("""{"ecosystem":"opencode"}""")
            assertTrue(compat.contains("[compat] OPENCODE_V2"))
            assertTrue(compat.contains("node=missing"))

            // 停止后报告翻转
            manager.stop("opencode.fixture")
            val stopped = doctor.execute("{}")
            assertTrue(stopped.contains("opencode.fixture: STOPPED"))
            assertTrue(stopped.contains("[tools] bound plugins: 0"))
            supervisor.stopAll()
        }
    }

    private class NoProbePort : com.apex.agent.core.tools.extension.NodeInstallPort {
        override suspend fun exec(
            command: List<String>,
            cwd: File,
            timeoutMs: Long,
            stdin: String?
        ): Result<com.apex.agent.core.tools.extension.NodeInstallPort.ExecOutcome> =
            Result.failure(UnsupportedOperationException("probe not wired"))

        override suspend fun probeRuntime() =
            com.apex.agent.core.tools.extension.NodeInstallPort.RuntimeProbe(null, null)
    }
}
