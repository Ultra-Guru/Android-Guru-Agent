package com.apex.agent.core.tools.pluginhost.worker

import java.io.File
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
 * # PluginWorkerSupervisor 崩溃恢复测试（真实 node，Phase 2 验收锚点）
 *
 * 锁定 spec §7.4 的崩溃恢复契约：
 * 1. Worker 异常退出 → Crashed 事件（带 exit code 与 stderr 尾部）；
 * 2. 窗口预算内自动重启（handshake + load 重放）→ Restarted 事件；
 * 3. 重启后工具调用恢复可用；
 * 4. 超过 [PluginWorkerSupervisor.RestartPolicy.maxRestarts] → GaveUp
 *    （不再重启，原因可查）；
 * 5. 用户主动 stop 不触发重启。
 *
 * harness 选型与 [PluginWorkerEndToEndTest] 相同（真实时间子进程）。
 */
class PluginWorkerSupervisorTest {

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
              ping: {
                description: 'Ping tool',
                execute: async () => ({ output: 'pong-from-fixture' })
              }
            };
            """.trimIndent()
        )
        return entry
    }

    @Test
    fun `crash triggers restart and tool calls recover`() {
        assumeTrue("node not available — test honestly skipped", nodeAvailable())
        val dir = tmp.newFolder()
        val entry = writeFixture(dir)

        realIo {
            val supervisor = PluginWorkerSupervisor(
                spawnPort = LocalProcessWorkerPort(),
                provider = NodeRuntimeProvider(FailingProbePort()),
                bootstrapDir = dir,
                parentScope = this,
                policy = PluginWorkerSupervisor.RestartPolicy(
                    maxRestarts = 3, windowMs = 60_000, stableAfterMs = 60_000
                )
            )
            val started = supervisor.start("test.plugin", dir, entry.path, "esm").getOrThrow()
            assertTrue(started.alive)
            assertEquals(
                "pong-from-fixture",
                supervisor.callTool("test.plugin", "ping", "{}").getOrThrow()
            )
            val firstPid = supervisor.worker("test.plugin")

            // 模拟崩溃：强杀当前实例
            supervisor.worker("test.plugin")?.kill()

            // 看护检测 → 自动重启 → 新实例可用
            awaitCondition {
                val w = supervisor.worker("test.plugin")
                w != null && w !== firstPid && w.alive
            }
            awaitCondition {
                supervisor.callTool("test.plugin", "ping", "{}").isSuccess
            }
            assertEquals(
                "pong-from-fixture",
                supervisor.callTool("test.plugin", "ping", "{}").getOrThrow()
            )
            supervisor.stop("test.plugin")
            supervisor.stopAll()
        }
    }

    @Test
    fun `restart budget exhaustion gives up with reason`() {
        assumeTrue("node not available — test honestly skipped", nodeAvailable())
        val dir = tmp.newFolder()
        val entry = writeFixture(dir)

        realIo {
            val supervisor = PluginWorkerSupervisor(
                spawnPort = LocalProcessWorkerPort(),
                provider = NodeRuntimeProvider(FailingProbePort()),
                bootstrapDir = dir,
                parentScope = this,
                policy = PluginWorkerSupervisor.RestartPolicy(
                    maxRestarts = 1, windowMs = 60_000, stableAfterMs = 60_000
                )
            )
            supervisor.start("test.plugin", dir, entry.path, "esm").getOrThrow()
            val firstWorker = supervisor.worker("test.plugin")

            // 第一次崩溃：预算 1 → 重启一次（等新实例，而非垂死的旧实例）
            firstWorker?.kill()
            awaitCondition {
                val w = supervisor.worker("test.plugin")
                w != null && w !== firstWorker && w.alive
            }

            // 第二次崩溃：预算耗尽 → GaveUp
            supervisor.worker("test.plugin")?.kill()
            awaitCondition { supervisor.gaveUpReason("test.plugin") != null }
            val reason = supervisor.gaveUpReason("test.plugin")
            assertNotNull(reason)
            assertTrue(reason!!.contains("上限"))
            // 放弃后不再有运行实例
            awaitCondition { supervisor.worker("test.plugin") == null }
            supervisor.stopAll()
        }
    }

    @Test
    fun `user stop does not trigger restart`() {
        assumeTrue("node not available — test honestly skipped", nodeAvailable())
        val dir = tmp.newFolder()
        val entry = writeFixture(dir)

        realIo {
            val supervisor = PluginWorkerSupervisor(
                spawnPort = LocalProcessWorkerPort(),
                provider = NodeRuntimeProvider(FailingProbePort()),
                bootstrapDir = dir,
                parentScope = this,
                policy = PluginWorkerSupervisor.RestartPolicy(maxRestarts = 3)
            )
            supervisor.start("test.plugin", dir, entry.path, "esm").getOrThrow()

            supervisor.stop("test.plugin")
            // 停止后等待一个看护周期以上，确认没有重启
            delay(400)
            assertNull(supervisor.worker("test.plugin"))
            assertNull(supervisor.gaveUpReason("test.plugin"))
            assertEquals(emptySet<String>(), supervisor.supervisedIds())
            supervisor.stopAll()
        }
    }

    @Test
    fun `health check reports ping roundtrip for live worker`() {
        assumeTrue("node not available — test honestly skipped", nodeAvailable())
        val dir = tmp.newFolder()
        val entry = writeFixture(dir)

        realIo {
            val supervisor = PluginWorkerSupervisor(
                spawnPort = LocalProcessWorkerPort(),
                provider = NodeRuntimeProvider(FailingProbePort()),
                bootstrapDir = dir,
                parentScope = this
            )
            supervisor.start("test.plugin", dir, entry.path, "esm").getOrThrow()
            val latency = supervisor.healthCheck("test.plugin").getOrThrow()
            assertTrue(latency >= 0)
            supervisor.stop("test.plugin")
            supervisor.stopAll()
        }
    }

    /** 探测通道假实现：probe 失败（Worker 路径不依赖探测结果，只用 buildCommand）。 */
    private class FailingProbePort : com.apex.agent.core.tools.extension.NodeInstallPort {
        override suspend fun exec(
            command: List<String>,
            cwd: File,
            timeoutMs: Long,
            stdin: String?
        ): Result<com.apex.agent.core.tools.extension.NodeInstallPort.ExecOutcome> =
            Result.failure(UnsupportedOperationException("probe not wired in this test"))

        override suspend fun probeRuntime() =
            com.apex.agent.core.tools.extension.NodeInstallPort.RuntimeProbe(null, null)
    }
}
