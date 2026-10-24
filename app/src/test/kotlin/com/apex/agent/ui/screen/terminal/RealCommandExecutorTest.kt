package com.apex.agent.ui.screen.terminal

import com.apex.agent.platform.TerminalWait.WaitCondition
import com.apex.agent.platform.TerminalWait.WaitResult
import com.apex.agent.platform.terminal.events.CloseCause
import com.apex.agent.platform.terminal.events.ExitCause
import com.apex.agent.platform.terminal.events.TerminalEvent
import com.apex.agent.platform.terminal.io.InputOwner
import com.apex.agent.platform.terminal.io.TerminalKey
import com.apex.agent.platform.terminal.io.UnixSignal
import com.apex.agent.platform.terminal.policy.PrivilegeLevel
import com.apex.agent.platform.terminal.protocol.ShellMarkers
import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.state.TerminalSemanticState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RealCommandExecutor] 真实执行管线测试（T96）。
 *
 * FakeRuntime 为手写替身（无 mock 框架 —— AGENTS.md 测试纪律）；场景脚本
 * 由 [ShellMarkers.wrapCommand] 构造真实 marker 包装，回显/输出/提示符尾
 * 与生产 PTY 链路同构。
 */
class RealCommandExecutorTest {

    // ───────────────────────── FakeRuntime（手写替身）─────────────────────────

    /**
     * 脚本化桩：run 记录命令；observe 按 afterCursor 切片返回 scriptedRaw；
     * wait 在两倍轮询周期后返回 scriptedWait（给 pump 至少一轮窗口）。
     * 其余接口返回中性值（executor 不触达）。
     */
    private open class FakeRuntime(
        private val scriptedRaw: String,
        private val scriptedExit: Int?,
        private val pollIntervalMs: Long,
        private val scriptedWait: WaitResult? = null
    ) : TerminalRuntime {
        var runCommand: String? = null
        var runRefused: Boolean = false
        var sigIntCount = 0

        override suspend fun run(
            sessionId: Long, command: String, owner: InputOwner,
            background: Boolean, timeoutMs: Long
        ): Result<TerminalRuntime.RunResult> {
            if (runRefused) return Result.failure(RuntimeException("TerminalError:PermissionDenied"))
            runCommand = command
            return Result.success(
                TerminalRuntime.RunResult(
                    jobId = 7, sessionId = sessionId, state = "RUNNING",
                    startCursor = 0, owner = owner, background = background
                )
            )
        }

        override suspend fun observe(
            sessionId: Long, mode: TerminalRuntime.ObserveMode, afterCursor: Long,
            maxBytes: Int, maxEvents: Int, scrollbackLines: Int
        ): Result<TerminalRuntime.ObserveResult> {
            val from = afterCursor.toInt()
            val to = scriptedRaw.length.coerceAtMost(from + maxBytes)
            return Result.success(
                TerminalRuntime.ObserveResult(
                    mode = mode, sessionId = sessionId, cursor = to.toLong(),
                    raw = if (to > from) scriptedRaw.substring(from, to) else ""
                )
            )
        }

        override suspend fun wait(
            sessionId: Long, condition: WaitCondition, timeoutMs: Long
        ): Result<WaitResult> {
            // 两倍轮询周期后终局 —— 保证 pump 至少跑一轮（虚拟时钟，瞬时完成）
            delay(pollIntervalMs * 2)
            val wait = scriptedWait ?: WaitResult.Matched(
                TerminalEvent.ProcessExited(
                    id = 1, sessionId = sessionId, timestamp = 0, cursor = scriptedRaw.length.toLong(),
                    jobId = 7, pid = 4242,
                    exitCode = scriptedExit, signal = null, cause = ExitCause.NORMAL
                )
            )
            return Result.success(wait)
        }

        override suspend fun signalForeground(
            sessionId: Long, signal: UnixSignal, owner: InputOwner, jobId: Long?
        ): Result<TerminalRuntime.SignalResult> {
            if (signal == UnixSignal.SIGINT) sigIntCount++
            return Result.success(
                TerminalRuntime.SignalResult(
                    sent = true, signal = signal, targetJobId = jobId, foregroundDelivered = true
                )
            )
        }

        override suspend fun create(
            shell: String, cwd: String, rows: Int, cols: Int, env: Map<String, String>,
            privilege: PrivilegeLevel, backendId: String, workspaceId: String?
        ): Result<TerminalRuntime.CreateResult> = Result.failure(RuntimeException("unused"))

        override suspend fun backends(): List<TerminalRuntime.BackendStatus> = emptyList()

        override suspend fun write(
            sessionId: Long, owner: InputOwner, kind: TerminalRuntime.WriteKind,
            text: String?, key: TerminalKey?, bytes: ByteArray?
        ): Result<TerminalRuntime.WriteResult> = Result.failure(RuntimeException("unused"))

        override suspend fun signal(
            sessionId: Long, signal: UnixSignal, owner: InputOwner, jobId: Long?
        ): Result<TerminalRuntime.SignalResult> = Result.success(
            TerminalRuntime.SignalResult(sent = true, signal = signal, targetJobId = jobId)
        )

        override suspend fun cancel(sessionId: Long, jobId: Long): Result<TerminalRuntime.CancelResult> =
            Result.success(TerminalRuntime.CancelResult(cancelled = true, jobId = jobId, finalState = "EXITED"))

        override suspend fun stop(sessionId: Long): Result<TerminalRuntime.StopResult> =
            Result.success(TerminalRuntime.StopResult(stopped = true, jobId = null, finalState = "STOPPED"))

        override suspend fun resize(sessionId: Long, rows: Int, cols: Int): Result<TerminalRuntime.ResizeResult> =
            Result.success(TerminalRuntime.ResizeResult(resized = true, rows = rows, cols = cols))

        override suspend fun snapshot(
            mode: TerminalRuntime.SnapshotMode, sessionId: Long?,
            recentEvents: Int, recentOutputBytes: Int
        ): Result<TerminalRuntime.SnapshotResult> = Result.success(
            TerminalRuntime.SnapshotResult(
                sessions = emptyList(), globalCursor = 0,
                recentEvents = emptyList(), recentOutput = ""
            )
        )

        override suspend fun close(sessionId: Long, force: Boolean): Result<TerminalRuntime.CloseResult> =
            Result.success(TerminalRuntime.CloseResult(closed = true, cause = "USER", finalCursor = 0))

        override suspend fun recover(): List<Long> = emptyList()

        override suspend fun recoveredSnapshot(sessionId: Long): TerminalSemanticState? = null

        override suspend fun shutdown(): Result<TerminalRuntime.ShutdownResult> =
            Result.success(TerminalRuntime.ShutdownResult(sessionsClosed = 0, jobsCancelled = 0, clean = true))

        override fun screenStateFlow(sessionId: Long): Flow<com.apex.agent.platform.terminal.screen.TerminalScreenState>? = null
        override fun semanticStateFlow(sessionId: Long): Flow<TerminalSemanticState>? = null
        override fun styledScreenFlow(sessionId: Long): Flow<com.apex.agent.terminalemulator.TerminalRenderSnapshot?>? = null
        override fun terminalEventFlow(sessionId: Long, afterCursor: Long): Flow<TerminalEvent>? = null
    }

    // ───────────────────────── 场景 ─────────────────────────

    @Test
    fun `streaming happy path - echo suppressed, real lines out, exit code real`() = runTest {
        val cmd = "uname -a"
        val echo = ShellMarkers.wrapCommand(7, cmd)
        val raw = echo + "\r\n" +
            "Linux localhost 6.1 2026 aarch64\n" +
            "\u001b]633;APEX;j=7;e=0\u0007" +
            "user@android:/data \$ "
        val runtime = FakeRuntime(scriptedRaw = raw, scriptedExit = 0, pollIntervalMs = 120)
        val executor = RealCommandExecutor(runtime, pollIntervalMs = 120)

        val collected = mutableListOf<String>()
        val result = executor.execute(1L, cmd) { lines -> collected.addAll(lines) }

        assertTrue(result is RealCommandExecutor.ExecResult.Completed)
        assertEquals(0, (result as RealCommandExecutor.ExecResult.Completed).exitCode)
        assertEquals(listOf("Linux localhost 6.1 2026 aarch64"), collected)
        assertEquals(cmd, runtime.runCommand)
    }

    @Test
    fun `nonzero exit surfaces real code`() = runTest {
        val cmd = "false"
        val echo = ShellMarkers.wrapCommand(7, cmd)
        val raw = echo + "\r\nbash: nope: command not found\nroot@ubuntu:~# "
        val runtime = FakeRuntime(scriptedRaw = raw, scriptedExit = 127, pollIntervalMs = 120)
        val executor = RealCommandExecutor(runtime, pollIntervalMs = 120)

        val collected = mutableListOf<String>()
        val result = executor.execute(1L, cmd) { lines -> collected.addAll(lines) }

        assertTrue(result is RealCommandExecutor.ExecResult.Completed)
        assertEquals(127, (result as RealCommandExecutor.ExecResult.Completed).exitCode)
        assertEquals(listOf("bash: nope: command not found"), collected)
    }

    @Test
    fun `run refused maps to RunFailed`() = runTest {
        val runtime = FakeRuntime(scriptedRaw = "", scriptedExit = 0, pollIntervalMs = 120)
        runtime.runRefused = true
        val executor = RealCommandExecutor(runtime, pollIntervalMs = 120)
        val result = executor.execute(1L, "rm -rf /") { }
        assertTrue(result is RealCommandExecutor.ExecResult.RunFailed)
        assertEquals("TerminalError:PermissionDenied", (result as RealCommandExecutor.ExecResult.RunFailed).message)
    }

    @Test
    fun `sessionGone early return without finish flush`() = runTest {
        val cmd = "exit"
        val echo = ShellMarkers.wrapCommand(7, cmd)
        val raw = echo + "\r\nbye\n"
        val runtime = FakeRuntime(
            scriptedRaw = raw, scriptedExit = null, pollIntervalMs = 120,
            scriptedWait = WaitResult.SessionGone(CloseCause.NORMAL)
        )
        val executor = RealCommandExecutor(runtime, pollIntervalMs = 120)
        val collected = mutableListOf<String>()
        val result = executor.execute(1L, cmd) { lines -> collected.addAll(lines) }
        assertTrue(result is RealCommandExecutor.ExecResult.SessionGone)
        // 会话消失：已流出的行保留（诚实呈现），不再 finish 冲刷
        assertEquals(listOf("bye"), collected)
    }

    @Test
    fun `interrupt delivers sigint to foreground`() = runTest {
        val runtime = FakeRuntime(scriptedRaw = "", scriptedExit = 0, pollIntervalMs = 120)
        val executor = RealCommandExecutor(runtime, pollIntervalMs = 120)
        assertTrue(executor.interrupt(1L, jobId = 7))
        assertEquals(1, runtime.sigIntCount)
    }

    @Test
    fun `markers disabled uses plain command as echo`() = runTest {
        val cmd = "ls"
        val raw = "ls\r\nfile-a\nfile-b\n\$ "
        val runtime = FakeRuntime(scriptedRaw = raw, scriptedExit = 0, pollIntervalMs = 120)
        val executor = RealCommandExecutor(runtime, pollIntervalMs = 120, echoWrapsCommand = false)
        val collected = mutableListOf<String>()
        executor.execute(1L, cmd) { lines -> collected.addAll(lines) }
        assertEquals(listOf("file-a", "file-b"), collected)
    }
}
