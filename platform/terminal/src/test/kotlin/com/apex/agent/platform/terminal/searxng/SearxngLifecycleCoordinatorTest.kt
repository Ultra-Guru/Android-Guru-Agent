package com.apex.agent.platform.terminal.searxng

import com.apex.agent.platform.TerminalWait.WaitCondition
import com.apex.agent.platform.TerminalWait.WaitResult
import com.apex.agent.platform.terminal.events.ExitCause
import com.apex.agent.platform.terminal.events.TerminalEvent
import com.apex.agent.platform.terminal.io.InputOwner
import com.apex.agent.platform.terminal.io.TerminalKey
import com.apex.agent.platform.terminal.policy.PrivilegeLevel
import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.screen.TerminalScreenState
import com.apex.agent.platform.terminal.state.TerminalSemanticState
import com.apex.agent.terminalemulator.TerminalRenderSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [SearxngLifecycleCoordinator] 单测（Spec §5 状态机转移表 + §9 自愈 + INV1-5）。
 *
 * 虚拟时间纪律（两层）：
 *  - clock 注入 TestScope 虚拟时间 —— healthz 30s 窗口经 delay 步进确定性推进；
 *  - **不用 advanceUntilIdle**：Ready 后 watchdog 是自续 delay 循环（15s 周期），
 *    全量推进会无限自旋 —— 用 runCurrent() 执行即时链、advanceTimeBy(n) 做
 *    有界时间推进；
 *  - FakeRuntime.wait 的 Timeout 路径真实 delay —— 与 WaitEngine 同语义
 *    （分片等待可被打断），否则观察者循环退化为零延迟自旋。
 *
 * 覆盖：enable→Ready / ubuntu 未就绪 / 端口占用 / healthz 超时关会话 / disable
 * 关会话回 Off / retry 门禁 / reconcile 双向对账 / 引擎变更重启 / 守护退出自愈。
 */
class SearxngLifecycleCoordinatorTest {

    // ─────────────────────────── 测试替身 ───────────────────────────

    /** 可编程 TerminalRuntime 假体（门面全操作 —— 未编排路径返回失败）。 */
    private class FakeRuntime : TerminalRuntime {
        var nextSessionId = 100L
        var nextJobId = 1L
        val createdSessions = mutableListOf<Long>()
        val closedSessions = mutableListOf<Pair<Long, Boolean>>()
        val ranCommands = mutableListOf<String>()
        /** 单发守护退出开关（消费一次后自动复位 —— 重启后的新守护稳定存活）。 */
        var daemonExitOnce: Boolean = false

        override suspend fun create(
            cwd: String, rows: Int, cols: Int, env: Map<String, String>,
            privilege: PrivilegeLevel, backendId: String, workspaceId: String?
        ): Result<TerminalRuntime.CreateResult> {
            val id = nextSessionId++
            createdSessions.add(id)
            return Result.success(
                TerminalRuntime.CreateResult(
                    sessionId = id, pid = 1000, shell = "bash", cwd = cwd,
                    rows = rows, cols = cols, privilege = privilege, state = "ACTIVE",
                    cursor = 0, backendId = backendId, runtimeType = "LINUX",
                    rootfsId = "ubuntu", guestCwd = "/root", workspaceId = workspaceId
                )
            )
        }

        override suspend fun run(
            sessionId: Long, command: String, owner: InputOwner,
            background: Boolean, timeoutMs: Long
        ): Result<TerminalRuntime.RunResult> {
            ranCommands.add(command)
            return Result.success(
                TerminalRuntime.RunResult(
                    jobId = nextJobId++, sessionId = sessionId, state = "RUNNING",
                    startCursor = 0, owner = owner, background = background
                )
            )
        }

        override suspend fun wait(
            sessionId: Long, condition: WaitCondition, timeoutMs: Long
        ): Result<WaitResult> {
            val result: WaitResult = when (condition) {
                is WaitCondition.PromptDetected -> WaitResult.Matched()
                is WaitCondition.ProcessExited -> {
                    if (daemonExitOnce) {
                        daemonExitOnce = false
                        WaitResult.Matched(
                            TerminalEvent.ProcessExited(
                                id = 0, sessionId = sessionId, timestamp = 0, cursor = 0,
                                jobId = condition.jobId, pid = 1000,
                                exitCode = 1, signal = null, cause = ExitCause.NORMAL
                            )
                        )
                    } else {
                        delay(timeoutMs)
                        WaitResult.Timeout(waitedMs = timeoutMs)
                    }
                }
                else -> {
                    delay(timeoutMs)
                    WaitResult.Timeout(waitedMs = timeoutMs)
                }
            }
            return Result.success(result)
        }

        override suspend fun observe(
            sessionId: Long, mode: TerminalRuntime.ObserveMode, afterCursor: Long,
            maxBytes: Int, maxEvents: Int, scrollbackLines: Int
        ): Result<TerminalRuntime.ObserveResult> = Result.success(
            TerminalRuntime.ObserveResult(
                mode = mode, sessionId = sessionId, cursor = 100,
                raw = if (mode == TerminalRuntime.ObserveMode.RAW) "stderr-tail" else null
            )
        )

        override suspend fun close(sessionId: Long, force: Boolean): Result<TerminalRuntime.CloseResult> {
            closedSessions.add(sessionId to force)
            return Result.success(
                TerminalRuntime.CloseResult(closed = true, cause = "NORMAL", finalCursor = 0)
            )
        }

        // ── 不可达门面（coordinator 不触碰）──
        override suspend fun backends(): List<TerminalRuntime.BackendStatus> = emptyList()
        override suspend fun write(sessionId: Long, owner: InputOwner, kind: TerminalRuntime.WriteKind, text: String?, key: TerminalKey?, bytes: ByteArray?): Result<TerminalRuntime.WriteResult> = Result.failure(UnsupportedOperationException())
        override suspend fun signal(sessionId: Long, signal: com.apex.agent.platform.terminal.io.UnixSignal, owner: InputOwner, jobId: Long?): Result<TerminalRuntime.SignalResult> = Result.failure(UnsupportedOperationException())
        override suspend fun signalForeground(sessionId: Long, signal: com.apex.agent.platform.terminal.io.UnixSignal, owner: InputOwner, jobId: Long?): Result<TerminalRuntime.SignalResult> = Result.failure(UnsupportedOperationException())
        override suspend fun cancel(sessionId: Long, jobId: Long): Result<TerminalRuntime.CancelResult> = Result.failure(UnsupportedOperationException())
        override suspend fun resize(sessionId: Long, rows: Int, cols: Int): Result<TerminalRuntime.ResizeResult> = Result.failure(UnsupportedOperationException())
        override suspend fun snapshot(mode: TerminalRuntime.SnapshotMode, sessionId: Long?, recentEvents: Int, recentOutputBytes: Int): Result<TerminalRuntime.SnapshotResult> = Result.failure(UnsupportedOperationException())
        override suspend fun stop(sessionId: Long): Result<TerminalRuntime.StopResult> = Result.failure(UnsupportedOperationException())
        override suspend fun shutdown(): Result<TerminalRuntime.ShutdownResult> = Result.success(TerminalRuntime.ShutdownResult(0, 0, true))
        override suspend fun recover(): List<Long> = emptyList()
        override suspend fun recoveredSnapshot(sessionId: Long): TerminalSemanticState? = null
        override fun screenStateFlow(sessionId: Long): Flow<TerminalScreenState>? = null
        override fun semanticStateFlow(sessionId: Long): Flow<TerminalSemanticState>? = null
        override fun styledScreenFlow(sessionId: Long): Flow<TerminalRenderSnapshot?>? = null
        override fun terminalEventFlow(sessionId: Long, afterCursor: Long): Flow<TerminalEvent>? = null
    }

    /**
     * 进程内意图端口。字段名刻意避开接口方法名（JVM 平台声明冲突：
     * `var enabled` 生成 setEnabled(Z)V 与 override 撞签名 —— Gradle 编译
     * 硬错误，kotlinc CLI 镜像编译不查后端签名故本地漏网）。
     */
    private class FakeIntent : SearxngIntentPort {
        var enabledState = false
        var enginesState: Set<String> = SEARXNG_DEFAULT_ENGINES
        override fun isEnabled(): Boolean = enabledState
        override fun setEnabled(value: Boolean) { enabledState = value }
        override fun engines(): Set<String> = enginesState
        override fun setEngines(engines: Set<String>) { this.enginesState = engines }
        override fun secretKey(): String = "s".repeat(64)
    }

    /** rootfs 临时目录命令端口（exec 一律成功）。 */
    private class AlwaysOkCommands(private val rootfs: File) : SearxngCommandPort {
        override suspend fun exec(command: String, timeoutMs: Long, env: Map<String, String>): Result<SearxngExecResult> =
            Result.success(SearxngExecResult(0, "", "", timedOut = false))
        override suspend fun rootfsDir(): File = rootfs
    }

    /** 通用夹具：组装 coordinator + 可编程挂件。 */
    private class Harness(scope: TestScope, rootfs: File) {
        val runtime = FakeRuntime()
        val intent = FakeIntent()
        var healthOk: Boolean = true
        var ubuntuOk: Boolean = true
        var portBusy: Boolean = false
        val commands = AlwaysOkCommands(rootfs)
        // 虚拟时间确定性：文件写走 TestDispatcher（见 Writer 的 ioDispatcher 注记）
        val writer = SearxngSettingsWriter(
            commands, intent,
            kotlinx.coroutines.test.StandardTestDispatcher(scope.testScheduler)
        )
        val coordinator = SearxngLifecycleCoordinator(
            runtime = runtime,
            installer = SearxngInstaller(commands),
            settingsWriter = writer,
            intent = intent,
            healthProbe = { healthOk },
            ubuntuReady = { ubuntuOk },
            scope = scope.backgroundScope,
            portBusyProbe = { portBusy },
            clock = { scope.testScheduler.currentTime }
        )
    }

    private fun tempRootfs(): File = kotlin.io.path.createTempDirectory("searxng-test").toFile()

    // ─────────────────────────── 用例 ───────────────────────────

    @Test
    fun `enable reaches ready with open session and daemon command`() = runTest {
        val h = Harness(this, tempRootfs())
        val enableResult = h.coordinator.enable()
        runCurrent()

        assertTrue("enable accepted: ${enableResult.exceptionOrNull()}", enableResult.isSuccess)
        assertTrue(h.intent.isEnabled())
        val state = h.coordinator.state.value
        assertTrue("state=$state", state is SearxngState.Ready)
        assertEquals(1, h.runtime.createdSessions.size)
        assertTrue(h.runtime.closedSessions.isEmpty())
        assertTrue(h.coordinator.isReady())
        // 守护命令带 SEARXNG_SETTINGS_PATH + werkzeug 启动形态
        val cmd = h.runtime.ranCommands.single()
        assertTrue(cmd.contains("SEARXNG_SETTINGS_PATH=/opt/searxng/searxng.yml"))
        assertTrue(cmd.contains("run_simple('127.0.0.1', 8888"))
    }

    @Test
    fun `enable fails when ubuntu not ready`() = runTest {
        val h = Harness(this, tempRootfs())
        h.ubuntuOk = false
        h.coordinator.enable()
        runCurrent()
        val state = h.coordinator.state.value
        assertTrue("state=$state", state is SearxngState.Failed)
        assertEquals("ubuntu not ready", (state as SearxngState.Failed).reason)
        assertTrue(state.retryable)
    }

    @Test
    fun `enable fails when port busy`() = runTest {
        val h = Harness(this, tempRootfs())
        h.portBusy = true
        h.coordinator.enable()
        runCurrent()
        val state = h.coordinator.state.value
        assertTrue("state=$state", state is SearxngState.Failed)
        assertEquals("port in use", (state as SearxngState.Failed).reason)
    }

    @Test
    fun `healthz never passes - startup timeout closes session`() = runTest {
        val h = Harness(this, tempRootfs())
        h.healthOk = false
        h.coordinator.enable()
        runCurrent()
        // 虚拟时间推进 70s —— healthz 30s 窗口耗尽（轮询步进 1s + 探测分片 1s）
        advanceTimeBy(70_000)
        runCurrent()

        val state = h.coordinator.state.value
        assertTrue("state=$state", state is SearxngState.Failed)
        assertTrue((state as SearxngState.Failed).reason.startsWith("startup timeout"))
        // Spec §5：超时必须关闭会话（不留僵尸）
        assertEquals(1, h.runtime.closedSessions.size)
        assertFalse(h.coordinator.isReady())
    }

    @Test
    fun `disable from ready closes session and writes intent off`() = runTest {
        val h = Harness(this, tempRootfs())
        h.coordinator.enable()
        runCurrent()
        assertTrue(h.coordinator.state.value is SearxngState.Ready)

        val disableResult = h.coordinator.disable()
        assertTrue(disableResult.isSuccess)

        assertTrue(h.coordinator.state.value is SearxngState.Off)
        assertFalse(h.intent.isEnabled())
        assertEquals(1, h.runtime.closedSessions.size)
        assertTrue(h.runtime.closedSessions[0].second) // force=true（N3 ≤3s 三级杀）
        assertFalse(h.coordinator.isReady())
    }

    @Test
    fun `disable is idempotent from off`() = runTest {
        val h = Harness(this, tempRootfs())
        val r = h.coordinator.disable()
        assertTrue(r.isSuccess)
        assertTrue(h.coordinator.state.value is SearxngState.Off)
    }

    @Test
    fun `retry only valid from failed`() = runTest {
        val h = Harness(this, tempRootfs())
        // Off 态 retry 拒绝
        assertTrue(h.coordinator.retry().isFailure)
        // 引导至 Failed
        h.ubuntuOk = false
        h.coordinator.enable()
        runCurrent()
        assertTrue(h.coordinator.state.value is SearxngState.Failed)
        // Failed 态 retry 接受且可恢复
        h.ubuntuOk = true
        assertTrue(h.coordinator.retry().isSuccess)
        runCurrent()
        assertTrue(h.coordinator.state.value is SearxngState.Ready)
    }

    @Test
    fun `reconcile starts when intent enabled and runtime off`() = runTest {
        val h = Harness(this, tempRootfs())
        h.intent.setEnabled(true)
        h.coordinator.reconcile()
        runCurrent()
        assertTrue(
            "state=${h.coordinator.state.value}",
            h.coordinator.state.value is SearxngState.Ready
        )
    }

    @Test
    fun `reconcile stops residual runtime when intent disabled`() = runTest {
        val h = Harness(this, tempRootfs())
        h.coordinator.enable()
        runCurrent()
        assertTrue(h.coordinator.state.value is SearxngState.Ready)
        h.intent.setEnabled(false)
        h.coordinator.reconcile()
        runCurrent()
        assertTrue(
            "state=${h.coordinator.state.value}",
            h.coordinator.state.value is SearxngState.Off
        )
        assertEquals(1, h.runtime.closedSessions.size)
    }

    @Test
    fun `engine change while ready restarts daemon with new session`() = runTest {
        val h = Harness(this, tempRootfs())
        h.coordinator.enable()
        runCurrent()
        assertTrue(h.coordinator.state.value is SearxngState.Ready)
        val firstSession = h.runtime.createdSessions.single()

        h.coordinator.setEngines(setOf("wikipedia"))
        runCurrent()

        // 意图更新 + 旧会话关闭 + 新会话建立（Restarting → Ready）
        assertEquals(setOf("wikipedia"), h.intent.engines())
        assertTrue(
            "state=${h.coordinator.state.value}",
            h.coordinator.state.value is SearxngState.Ready
        )
        assertEquals(2, h.runtime.createdSessions.size)
        assertTrue(h.runtime.closedSessions.contains(firstSession to true))
    }

    @Test
    fun `daemon exit triggers self-healing restart`() = runTest {
        val h = Harness(this, tempRootfs())
        h.coordinator.enable()
        runCurrent()
        assertTrue(h.coordinator.state.value is SearxngState.Ready)
        assertEquals(1, h.runtime.createdSessions.size)

        // 守护进程单发死亡（watcher 分片唤醒后 Matched）
        h.runtime.daemonExitOnce = true
        advanceTimeBy(70_000)
        runCurrent()

        // 自愈：新会话建立、回到 Ready（单发退出已消费，新守护稳定）
        assertTrue(
            "state=${h.coordinator.state.value}",
            h.coordinator.state.value is SearxngState.Ready
        )
        assertEquals("重启必须建立新会话", 2, h.runtime.createdSessions.size)
        assertEquals("旧会话必须关闭", 1, h.runtime.closedSessions.size)
    }

    @Test
    fun `enable is idempotent while already ready`() = runTest {
        val h = Harness(this, tempRootfs())
        h.coordinator.enable()
        runCurrent()
        val r = h.coordinator.enable()
        assertTrue(r.isSuccess)
        // 不重复建会话（INV1 单飞）
        assertEquals(1, h.runtime.createdSessions.size)
    }

    @Test
    fun `onHostShutdown goes off without touching intent`() = runTest {
        val h = Harness(this, tempRootfs())
        h.coordinator.enable()
        runCurrent()
        assertTrue(h.intent.isEnabled())

        h.coordinator.onHostShutdown()

        assertTrue(h.coordinator.state.value is SearxngState.Off)
        // F6/F7：意图保持 —— 下次 reconcile 恢复
        assertTrue(h.intent.isEnabled())
    }
}
