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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * # PluginWorker 真实 node 端到端测试（Phase 2 验收锚点）
 *
 * 用真实 node 子进程验证完整 IPC 链路：spawn → hello/ready → load/loaded
 * （ESM fixture）→ tool.execute/tool.result → ping → shutdown。
 *
 * ## 为什么不用 runTest
 *
 * 子进程是**真实时间**实体（启动/模块加载都是真实耗时），runTest 的虚拟
 * 时间会在协程空转时自动推进、令 withTimeout 虚假到点 —— 真实 IO 与虚拟
 * 时间混用正是 issue #276 测试纪律要禁的坑。真实子进程测试因此走专用
 * [realIo] harness（CountDownLatch + Dispatchers.IO，单次有界等待，
 * 协程 delay 轮询 —— 棘轮基线不新增命中）。
 *
 * ## node 缺失时的诚实跳过
 *
 * `Assume.assumeTrue`：环境无 node 时测试标记为 skipped（不是 passed），
 * 与 spec §16.5「不能将跳过的测试显示为通过」的分层标记一致 —— CI 的
 * ubuntu runner 预装 node，本套件在那里是硬验收。
 */
class PluginWorkerEndToEndTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun nodeAvailable(): Boolean = runCatching {
        ProcessBuilder("node", "--version").start().waitFor() == 0
    }.getOrDefault(false)

    /** 真实 IO 测试 harness（见类 KDoc 的选型说明）。 */
    private fun realIo(timeoutMs: Long = 60_000, block: suspend CoroutineScope.() -> Unit) {
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

    /** 挂起内轮询等待条件（coroutine delay，非阻塞睡眠）。 */
    private suspend fun awaitCondition(timeoutMs: Long = 15_000, condition: suspend () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!condition()) delay(25)
        }
    }

    private fun writeEsmFixture(dir: File): File {
        val entry = File(dir, "index.mjs")
        entry.writeText(
            """
            export const tools = {
              echo: {
                description: 'Echo the input text',
                parametersSchema: '{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}',
                execute: async (args) => ({ output: 'echo: ' + (args.text || '') })
              },
              fail: {
                description: 'Always fails',
                execute: async () => { throw new Error('boom'); }
              },
              slow: {
                description: 'Slow tool for in-flight crash tests',
                execute: async () => {
                  console.error('slow tool dispatched');
                  await new Promise(function (resolve) { setTimeout(resolve, 15000); });
                  return { output: 'late' };
                }
              }
            };
            export async function setup(ctx) {
              ctx.worker.log('fixture setup called');
            }
            """.trimIndent()
        )
        return entry
    }

    @Test
    fun `full ipc chain against real node`() {
        assumeTrue("node not available — test honestly skipped", nodeAvailable())
        val dir = tmp.newFolder()
        val entry = writeEsmFixture(dir)

        realIo {
            val bootstrap = PluginWorkerBootstrap.writeTo(dir)
            val process = LocalProcessWorkerPort()
                .spawn(WorkerProcessPort.SpawnSpec.of(listOf("node", bootstrap.path), dir))
                .getOrThrow()
            val worker = PluginWorker("test.esm", process, this)

            // 握手：runtime 标识为 node，协议版本对齐
            val ready = worker.handshake().getOrThrow()
            assertEquals("node", ready.runtime)
            assertEquals(WorkerProtocol.VERSION, ready.protocolVersion)
            assertTrue(ready.runtimeVersion.startsWith("v"))

            // 加载 ESM fixture：三个工具被发现（echo/fail/slow），setup 被调用
            val loaded = worker.load(entry.path, "esm").getOrThrow()
            assertTrue(loaded.setupOk)
            assertEquals(listOf("echo", "fail", "slow"), loaded.tools.map { it.name }.sorted())

            // 热调用：工具结果经 IPC 往返
            assertEquals("echo: hi", worker.callTool("echo", """{"text":"hi"}""").getOrThrow())

            // 错误隔离：工具抛异常 → 失败结果，进程不死
            val failure = worker.callTool("fail", "{}")
            assertTrue(failure.isFailure)
            assertTrue(failure.exceptionOrNull()?.message?.contains("boom") == true)
            assertTrue(worker.alive)

            // 未知工具：明确报错
            assertTrue(worker.callTool("nope", "{}").isFailure)

            // 心跳
            assertTrue(worker.ping().getOrThrow() >= 0)

            // 事件下发：Phase 4 前如实回执 delivered=false
            val receipt = worker.emitEvent("session.start", "{}").getOrThrow()
            assertTrue(receipt.contains("\"delivered\":false"))

            // stderr 观测：fixture setup 的日志进入了环形缓冲
            awaitCondition { worker.stderrSnapshot().any { it.contains("fixture setup called") } }

            // 优雅停机
            worker.shutdown()
            awaitCondition { !process.isAlive }
            assertFalse(worker.alive)
        }
    }

    @Test
    fun `worker process exit fails in-flight requests instead of hanging`() {
        assumeTrue("node not available — test honestly skipped", nodeAvailable())
        val dir = tmp.newFolder()
        val entry = writeEsmFixture(dir)

        realIo {
            val bootstrap = PluginWorkerBootstrap.writeTo(dir)
            val process = LocalProcessWorkerPort()
                .spawn(WorkerProcessPort.SpawnSpec.of(listOf("node", bootstrap.path), dir))
                .getOrThrow()
            val worker = PluginWorker("test.crash", process, this)
            worker.handshake().getOrThrow()
            worker.load(entry.path, "esm").getOrThrow()

            // 强杀进程 → 在途请求失败而非悬挂（有界等待内返回）。
            // 用 slow 工具保证调用真正在途（stderr 标记 dispatched 后再杀）。
            val call = launch {
                val result = worker.callTool("slow", "{}")
                assertTrue(result.isFailure)
            }
            awaitCondition { worker.stderrSnapshot().any { it.contains("slow tool dispatched") } }
            worker.kill()
            kotlinx.coroutines.withTimeout(10_000) { call.join() }
            assertFalse(worker.alive)
        }
    }

    @Test
    fun `load failure is reported honestly not swallowed`() {
        assumeTrue("node not available — test honestly skipped", nodeAvailable())
        val dir = tmp.newFolder()

        realIo {
            val bootstrap = PluginWorkerBootstrap.writeTo(dir)
            val process = LocalProcessWorkerPort()
                .spawn(WorkerProcessPort.SpawnSpec.of(listOf("node", bootstrap.path), dir))
                .getOrThrow()
            val worker = PluginWorker("test.missing", process, this)
            worker.handshake().getOrThrow()

            // 入口不存在 → loaded 消息 setupOk=false + error 原文
            val loaded = worker.load(File(dir, "nope.mjs").path, "esm").getOrThrow()
            assertFalse(loaded.setupOk)
            assertTrue(loaded.error?.contains("not found") == true)
            worker.shutdown()
        }
    }
}
