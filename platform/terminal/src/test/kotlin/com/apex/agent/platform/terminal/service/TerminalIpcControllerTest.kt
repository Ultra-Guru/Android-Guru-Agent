package com.apex.agent.platform.terminal.service

import com.apex.agent.platform.terminal.pty.FakeNativePty
import com.apex.agent.platform.terminal.policy.TerminalPolicyImpl
import com.apex.agent.platform.terminal.runtime.ExecutionBackendRegistry
import com.apex.agent.platform.terminal.runtime.LocalShellBackend
import com.apex.agent.platform.terminal.runtime.TerminalRuntimeImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * T91（D2-D4）：Terminal IPC 控制器全链路测试 —— 真实 [TerminalRuntimeImpl] +
 * FakeNativePty（仓库标准模式：runtime 级行为不 mock，走 Fake PTY 全链路）。
 *
 * 覆盖的契约（与 ITerminalService.aidl 注释一一对应）：
 *  - createSession 成功/失败编码（十进制 sessionId vs "ERR:…"）；
 *  - 事件驱动输出推送（onOutput 收到会话诞生后的输出，非轮询）；
 *  - write 回显 → onOutput（USER 语义，字节直通）；
 *  - listSessions JSON 投影 / ping 契约版本；
 *  - closeSession → onExit 单次派发（ProcessExited/SessionClosed 双源合并）；
 *  - 防御式 env 解析（"K=V" 形态外整条跳过）；
 *  - runtime 未安装 → ERR:RUNTIME_NOT_INSTALLED。
 */
class TerminalIpcControllerTest {

    private lateinit var pty: FakeNativePty
    private lateinit var runtime: TerminalRuntimeImpl
    private lateinit var scope: CoroutineScope
    private lateinit var controller: TerminalIpcController
    private var installed: TerminalRuntimeImpl? = null

    /** T92：剔除测试专用低阈值控制器（阈值 3 → 确定性收敛）。 */
    private lateinit var evictionController: TerminalIpcController

    /** 录制回调（线程安全 —— 事件收集在 Dispatchers.Default 上派发）。 */
    private class RecordingCallback : TerminalIpcController.Callback {
        val outputs = ConcurrentLinkedQueue<Pair<Long, String>>()
        val exits = ConcurrentLinkedQueue<Pair<Long, Pair<Int, String>>>()
        val states = ConcurrentLinkedQueue<Pair<Long, String>>()

        override fun onOutput(sessionId: Long, data: ByteArray) {
            outputs.add(sessionId to String(data, Charsets.UTF_8))
        }

        override fun onExit(sessionId: Long, exitCode: Int, cause: String) {
            exits.add(sessionId to (exitCode to cause))
        }

        override fun onSessionStateChanged(sessionId: Long, state: String) {
            states.add(sessionId to state)
        }
    }

    @Before
    fun setUp() {
        pty = FakeNativePty()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        runtime = TerminalRuntimeImpl(
            native = pty, policy = TerminalPolicyImpl(),
            backendRegistry = ExecutionBackendRegistry.of(LocalShellBackend())
        )
        installed = runtime
        controller = TerminalIpcController(
            runtimeProvider = { installed },
            scope = scope,
            createTimeoutMs = 5_000L
        )
        evictionController = TerminalIpcController(
            runtimeProvider = { installed },
            scope = scope,
            createTimeoutMs = 5_000L,
            callbackEvictionThreshold = 3
        )
    }

    @After
    fun tearDown() {
        controller.shutdown()
        evictionController.shutdown()
        runCatching { kotlinx.coroutines.runBlocking { runtime.shutdown() } }
        scope.cancel()
    }

    /** 有界等待谓词为真（事件流为异步派发 —— 真实调度器下的收敛等待）。 */
    private fun awaitTrue(timeoutMs: Long = 5_000L, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    // ─── createSession 编码契约 ───

    @Test
    fun `createSession returns decimal sessionId and streams initial output`() {
        val cb = RecordingCallback()
        controller.registerCallback(cb)

        val result = controller.createSession(backendId = "local", rows = 24, cols = 80, cwd = null, envAssignments = null)
        assertTrue("got: $result", result.toLongOrNull() != null && result.toLong() >= 0)

        val sid = result.toLong()
        // FakeNativePty 诞生即输出 "FakeNativePty shell ready" —— 事件驱动推送
        assertTrue(
            "onOutput must deliver the initial prompt",
            awaitTrue { cb.outputs.any { it.first == sid && it.second.contains("FakeNativePty shell ready") } }
        )
    }

    @Test
    fun `createSession failure maps to ERR line with runtime message`() {
        // 未知后端 → runtime.create 失败 → "ERR:…" 单往返携带原因
        val result = controller.createSession(backendId = "no-such-backend", rows = 24, cols = 80, cwd = null, envAssignments = null)
        assertTrue("must be ERR line: $result", result.startsWith(TerminalIpcController.ERROR_PREFIX))
    }

    @Test
    fun `runtime not installed maps to ERR RUNTIME_NOT_INSTALLED`() {
        installed = null
        assertEquals(
            TerminalIpcController.ERR_RUNTIME_NOT_INSTALLED,
            controller.createSession("local", 24, 80, null, null)
        )
    }

    // ─── 输入 → 输出全链路 ───

    @Test
    fun `writeText echoes through PTY and pushes output events`() {
        val cb = RecordingCallback()
        controller.registerCallback(cb)
        val sid = controller.createSession("local", 24, 80, null, null).toLong()

        controller.writeText(sid, "echo ipc-bridge-ok\n")

        assertTrue(
            "echo output must stream back",
            awaitTrue(timeoutMs = 10_000L) { cb.outputs.any { it.first == sid && it.second.contains("ipc-bridge-ok") } }
        )
    }

    @Test
    fun `write carries raw bytes without charset round trip`() {
        val cb = RecordingCallback()
        controller.registerCallback(cb)
        val sid = controller.createSession("local", 24, 80, null, null).toLong()

        val payload = "echo utf8-bridge\n".toByteArray(Charsets.UTF_8)
        controller.write(sid, payload)

        assertTrue(
            "payload must survive the byte path",
            awaitTrue(timeoutMs = 10_000L) {
                cb.outputs.any { it.first == sid && it.second.contains("utf8-bridge") }
            }
        )
    }

    // ─── listSessions / ping ───

    @Test
    fun `listSessions returns JSON projection with alive flag`() {
        val created = controller.createSession("local", 24, 80, null, null)
        val listJson = controller.listSessions()

        val sid = created.toLong()
        assertTrue("JSON contains session id: $listJson", listJson.contains("\"id\":$sid"))
        assertTrue("JSON contains state: $listJson", listJson.contains("\"state\""))
        // runtime not installed → honest empty
        installed = null
        assertEquals("[]", controller.listSessions())
    }

    @Test
    fun `ping carries api version`() {
        assertEquals(
            "pong:${com.apex.agent.platform.terminal.api.TerminalApiVersion.versionString}",
            controller.ping()
        )
    }

    // ─── close → onExit 单次派发 ───

    @Test
    fun `closeSession announces exit exactly once`() {
        val cb = RecordingCallback()
        controller.registerCallback(cb)
        val sid = controller.createSession("local", 24, 80, null, null).toLong()

        controller.closeSession(sid, force = false)

        assertTrue(
            "onExit must fire after close",
            awaitTrue(timeoutMs = 10_000L) { cb.exits.any { it.first == sid } }
        )
        // 双源（ProcessExited + SessionClosed）合并 —— 等待可能的第二个源送达后断言单次
        Thread.sleep(300)
        assertEquals("onExit fires exactly once per session", 1, cb.exits.count { it.first == sid })
    }

    // ─── 防御式 env 解析 ───

    @Test
    fun `malformed env assignments are skipped not fatal`() {
        val env = TerminalIpcController.parseEnvAssignments(
            listOf("GOOD=1", "=broken", "novalue", "ALSO_GOOD=two=equals", "BAD KEY=x")
        )
        assertEquals(mapOf("GOOD" to "1", "ALSO_GOOD" to "two=equals"), env)
    }

    @Test
    fun `env assignments reach the session`() {
        val result = controller.createSession(
            backendId = "local", rows = 24, cols = 80, cwd = null,
            envAssignments = listOf("IPC_MARKER=42", "junk-entry")
        )
        val sid = result.toLong()
        val nativeId = runtime.sessionManager.assembly(sid)?.nativeSessionId
        assertNotNull(nativeId)
        assertEquals("42", pty.spawnEnvOf(nativeId!!)["IPC_MARKER"])
        assertFalse(pty.spawnEnvOf(nativeId).containsKey("junk-entry"))
    }

    // ─── unregister 停止派发 ───

    @Test
    fun `unregisterCallback stops dispatch for that callback`() {
        val cb = RecordingCallback()
        controller.registerCallback(cb)
        val sid = controller.createSession("local", 24, 80, null, null).toLong()
        assertTrue(awaitTrue { cb.outputs.isNotEmpty() })

        controller.unregisterCallback(cb)
        val received = AtomicBoolean(false)
        val cb2 = object : TerminalIpcController.Callback {
            override fun onOutput(sessionId: Long, data: ByteArray) { received.set(true) }
            override fun onExit(sessionId: Long, exitCode: Int, cause: String) {}
            override fun onSessionStateChanged(sessionId: Long, state: String) {}
        }
        controller.registerCallback(cb2)
        controller.writeText(sid, "echo after-unregister\n")
        assertTrue(awaitTrue(timeoutMs = 10_000L) { received.get() })
        // 注销后的 cb 不再收新输出
        val before = cb.outputs.count { it.second.contains("after-unregister") }
        Thread.sleep(300)
        val after = cb.outputs.count { it.second.contains("after-unregister") }
        assertEquals("unregistered callback must not receive new output", before, after)
    }

    // ─── T92：回调失败计数与剔除（oneway 静默丢数据防线） ───

    /** 恒抛回调（模拟 binder oneway 缓冲耗尽 / 死客户端）—— 记录被调用次数。 */
    private class DeadCallback(val calls: java.util.concurrent.atomic.AtomicInteger) : TerminalIpcController.Callback {
        override fun onOutput(sessionId: Long, data: ByteArray) {
            calls.incrementAndGet()
            throw RuntimeException("binder async buffer exhausted / dead client")
        }
        override fun onExit(sessionId: Long, exitCode: Int, cause: String) {
            calls.incrementAndGet()
            throw RuntimeException("dead")
        }
        override fun onSessionStateChanged(sessionId: Long, state: String) {
            calls.incrementAndGet()
            throw RuntimeException("dead")
        }
    }

    @Test
    fun `T92 dead callback is evicted after consecutive failures while healthy one keeps streaming`() {
        // 低阈值控制器（3）：FakeNativePty 是命令解释型 fake —— 用 `echo x`
        // 产生真实输出事件（每个 echo 至少一个 OutputProduced）
        val healthy = RecordingCallback()
        evictionController.registerCallback(healthy)
        val deadCalls = java.util.concurrent.atomic.AtomicInteger(0)
        evictionController.registerCallback(DeadCallback(deadCalls))
        val sid = evictionController.createSession("local", 24, 80, null, null).toLong()

        // 40 次 echo 输出（+初始提示）远超阈值 3 —— 死回调必被剔除。
        // 健康侧断言用字符计数（事件可能合并 —— 字节数不受影响）
        repeat(40) { evictionController.writeText(sid, "echo x\n") }
        assertTrue(
            "健康回调持续收到输出（PTY 泵不被死回调拖垮）",
            awaitTrue(timeoutMs = 10_000L) {
                healthy.outputs.filter { it.first == sid }.sumOf { it.second.count { c -> c == 'x' } } >= 30
            }
        )
        // 等死回调的失败计数稳定（达到阈值后不再增长）
        assertTrue(
            "死回调失败计数必须达到剔除阈值",
            awaitTrue(timeoutMs = 10_000L) { deadCalls.get() >= 3 }
        )
        val settled = deadCalls.get()
        Thread.sleep(300)
        assertEquals("剔除后不再向死回调派发", settled, deadCalls.get())

        // 再写 5 次：健康回调继续收（剔除不影响在场订阅者），死回调零增长
        // （zz 是唯一标记 —— 初始提示 "FakeNativePty shell ready" 不含 z）
        repeat(5) { evictionController.writeText(sid, "echo zz\n") }
        assertTrue(
            awaitTrue(timeoutMs = 10_000L) {
                healthy.outputs.filter { it.first == sid }.sumOf { it.second.count { c -> c == 'z' } } >= 5
            }
        )
        Thread.sleep(300)
        assertEquals("剔除是终态（连续失败计数不再增长）", settled, deadCalls.get())
    }

    @Test
    fun `T92 intermittent callback failures are forgiven (strike reset on success)`() {
        // 间歇性失败（每 3 次 1 失败 —— 连续失败永不超过 1，远低于阈值 3）
        // 不误杀：成功一次即清零计数。断言语义是「存活 + 持续接收」而非
        // 事件计数（runtime 输出事件按读取周期批量合并，次数不等于写入数）。
        val flakyReceived = java.util.concurrent.atomic.AtomicInteger(0)
        val flaky = object : TerminalIpcController.Callback {
            override fun onOutput(sessionId: Long, data: ByteArray) {
                val n = flakyReceived.incrementAndGet()
                if (n % 3 == 0) throw RuntimeException("transient binder pressure")
            }
            override fun onExit(sessionId: Long, exitCode: Int, cause: String) {}
            override fun onSessionStateChanged(sessionId: Long, state: String) {}
        }
        evictionController.registerCallback(flaky)
        val healthy = RecordingCallback()
        evictionController.registerCallback(healthy)
        val sid = evictionController.createSession("local", 24, 80, null, null).toLong()

        // 第一批：flaky 收到事件（部分失败）但不被剔除
        repeat(30) { evictionController.writeText(sid, "echo x\n") }
        assertTrue(
            "flaky 收到首批事件（间歇失败不阻断接收）",
            awaitTrue(timeoutMs = 10_000L) { flakyReceived.get() > 0 }
        )
        assertTrue(
            awaitTrue(timeoutMs = 10_000L) {
                healthy.outputs.filter { it.first == sid }.sumOf { it.second.count { c -> c == 'x' } } >= 20
            }
        )
        val midCount = flakyReceived.get()

        // 第二批：flaky 仍在接收（未被误杀）—— 事件数继续增长
        repeat(30) { evictionController.writeText(sid, "echo zz\n") }
        assertTrue(
            "间歇失败回调未被误杀（成功清零 strike，继续接收）",
            awaitTrue(timeoutMs = 10_000L) { flakyReceived.get() > midCount }
        )
        assertTrue(
            awaitTrue(timeoutMs = 10_000L) {
                healthy.outputs.filter { it.first == sid }.sumOf { it.second.count { c -> c == 'z' } } >= 5
            }
        )
    }
}
