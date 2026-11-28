package com.apex.agent.platform.terminal.job

import com.apex.agent.platform.terminal.events.Confidence
import com.apex.agent.platform.terminal.events.ExitCause
import com.apex.agent.platform.terminal.events.TerminalEvent
import com.apex.agent.platform.terminal.io.InputOwner
import com.apex.agent.platform.terminal.policy.PrivilegeLevel
import com.apex.agent.platform.terminal.policy.TerminalPolicyImpl
import com.apex.agent.platform.terminal.pty.FakeNativePty
import com.apex.agent.platform.terminal.runtime.LocalShellBackend
import com.apex.agent.platform.terminal.runtime.SessionSpawnRequest
import com.apex.agent.platform.terminal.screen.RealVirtualTerminal
import com.apex.agent.platform.terminal.session.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #947 回归 — 合成退出三方竞态收敛：
 *
 * 1. **陈旧提示符门（甲）**：会话初始提示符在高负载下被 pump 迟读 ——
 *    WaitingInput(jobId=null, cursor ≤ job.startCursor) 在 job RUNNING 期间
 *    到达时，不得据此合成 ProcessExited(NORMAL)（原 flake 根因之一：
 *    T81JobTimeoutConvergenceTest 期望 TIMED_OUT 却拿到 EXITED）。
 * 2. **超时让位门（乙）**：TimeoutController 的 TERM 已发（关停已发起）
 *    时，提示符启发式让位 —— 终态由超时通道独占。
 * 3. **终态不可逆（first-writer-wins）**：迟到的 ProcessExited 不再把已
 *    终态 job（连同真实退出码/信号）整体覆写（TIMED_OUT → EXITED 回退）。
 */
class JobSyntheticExitRaceTest {

    // ─── harness（与 ShellMarkerProtocolTest.newJobManager 同骨架）───

    private class Fixture {
        val fake = FakeNativePty()
        val eventLog = com.apex.agent.platform.terminal.events.TerminalEventLogImpl()
        val eventBus = com.apex.agent.platform.terminal.events.TerminalEventBusImpl(
            eventLog, CoroutineScope(SupervisorJob())
        )
        val waitEngine = com.apex.agent.platform.TerminalWait.WaitEngineImpl(
            eventBus, CoroutineScope(SupervisorJob())
        )
        val input = com.apex.agent.platform.terminal.io.InputManagerImpl(
            TerminalPolicyImpl(), fake, eventLog, eventBus
        )
        val sm = com.apex.agent.platform.terminal.session.SessionManagerImpl(
            fake, eventLog, eventBus, waitEngine, input,
            { r, c -> RealVirtualTerminal(r, c) },
            TerminalPolicyImpl()
        )
        val timeoutController = com.apex.agent.platform.terminal.process.TimeoutController(
            input, CoroutineScope(SupervisorJob()), gracePeriodMs = 60_000L
        )
        val jm = JobManagerImpl(
            sessionManager = sm,
            inputManager = input,
            eventLog = eventLog,
            eventBus = eventBus,
            scope = CoroutineScope(SupervisorJob()),
            timeoutController = timeoutController,
            enableShellMarkers = false
        )
        val session: TerminalSession

        init {
            input.nativeIdResolver = { sid -> sm.assembly(sid)?.nativeSessionId }
            session = runBlocking {
                val spec = LocalShellBackend()
                    .prepare(SessionSpawnRequest(cwd = "/sdcard", rows = 24, cols = 80))
                    .getOrThrow()
                sm.createFromSpec(spec, 24, 80, PrivilegeLevel.NORMAL).getOrThrow()
            }
        }

        fun startJob(command: String): Long = runBlocking {
            jm.startJob(session.id, command, InputOwner.AGENT, background = false, timeoutMs = 0)
                .getOrThrow().id
        }

        fun waitingInput(cursor: Long): TerminalEvent.WaitingInput = TerminalEvent.WaitingInput(
            id = 0, sessionId = session.id, timestamp = System.currentTimeMillis(),
            cursor = cursor, jobId = null, confidence = Confidence.HIGH_CONFIDENCE
        )

        fun processExited(
            jobId: Long,
            cause: ExitCause,
            exitCode: Int
        ): TerminalEvent.ProcessExited = TerminalEvent.ProcessExited(
            id = 0, sessionId = session.id, timestamp = System.currentTimeMillis(),
            cursor = -1, jobId = jobId, pid = 0, exitCode = exitCode, signal = null, cause = cause
        )
    }

    // ─── 1. 陈旧提示符门 ───

    @Test
    fun `stale prompt before job start cursor must not synthesize exit`() = runBlocking {
        val f = Fixture()
        val jobId = f.startJob("sleep 60")
        // 会话初始提示符的读块终位 = job.startCursor（同位）→ 早于本 job。
        val startCursor = f.jm.get(jobId)!!.startCursor
        f.jm.onEvent(f.waitingInput(cursor = startCursor))
        f.jm.onEvent(f.waitingInput(cursor = startCursor - 1))
        assertEquals("RUNNING", f.jm.get(jobId)!!.state.name)
        f.jm.onEvent(f.waitingInput(cursor = startCursor + 1))
        // 新输出后的提示符才是完成证据 → 合成退出照常触发（既有行为不回归）。
        assertEquals("EXITED", f.jm.get(jobId)!!.state.name)
    }

    // ─── 2. 超时让位门（TERM 已发 → 启发式让位）─────────────────────

    @Test
    fun `prompt heuristic yields when timeout shutdown already initiated`() = runBlocking {
        val f = Fixture()
        val jobId = f.startJob("sleep 60")
        val startCursor = f.jm.get(jobId)!!.startCursor
        // 挂超时并让 TERM 序列发起（grace 拉长到 60s，模拟「TERM 已发、
        // SIGKILL/TIMEOUT 事件未到」的窗口）。
        var timeoutCallback = 0
        f.timeoutController.startTimeout(f.session.id, jobId, timeoutMs = 10) { timeoutCallback++ }
        // 轮询等关停登记（TERM 发出前登记，与调度延迟解耦）。
        kotlinx.coroutines.withTimeout(5_000) {
            while (!f.timeoutController.hasShutdownInitiated(jobId)) kotlinx.coroutines.delay(10)
        }
        assertEquals(true, f.timeoutController.hasShutdownInitiated(jobId))
        // TERM 后 shell 重绘提示符（cursor > startCursor）：旧实现会在此刻
        // 合成 NORMAL → EXITED → transition 撤销定时器 → 超时语义被吞。
        f.jm.onEvent(f.waitingInput(cursor = startCursor + 100))
        assertEquals("RUNNING", f.jm.get(jobId)!!.state.name)
        assertEquals(0, timeoutCallback)
        // 超时通道最终独占终态。
        f.jm.onEvent(f.processExited(jobId, ExitCause.TIMEOUT, 137))
        assertEquals("TIMED_OUT", f.jm.get(jobId)!!.state.name)
        f.timeoutController.cancelAll()
    }

    // ─── 3. 终态不可逆（迟到退出事件不覆写）─────────────────────────

    @Test
    fun `late process exited never overwrites a terminal state`() = runBlocking {
        val f = Fixture()
        val jobId = f.startJob("sleep 60")
        // 超时先到 → TIMED_OUT（真实终态）。
        f.jm.onEvent(f.processExited(jobId, ExitCause.TIMEOUT, 137))
        assertEquals("TIMED_OUT", f.jm.get(jobId)!!.state.name)
        // 迟到的启发式合成 NORMAL（原实现会整体覆写为 EXITED/exitCode=0）。
        f.jm.onEvent(f.processExited(jobId, ExitCause.NORMAL, 0))
        // 迟到的取消回调 USER_INTERRUPT（取消路径同型竞态）。
        f.jm.onEvent(f.processExited(jobId, ExitCause.USER_INTERRUPT, 143))
        val job = f.jm.get(jobId)!!
        assertEquals("TIMED_OUT", job.state.name)
        assertEquals(137, job.exitCode)
        // 前台标记同步收敛：终态后不再指向已结束的 job。
        assertNull(f.jm.foregroundJobId(f.session.id))
        // 正向路径不受影响：RUNNING 状态照常消费退出事件。
        val jobId2 = f.startJob("echo ok")
        f.jm.onEvent(f.processExited(jobId2, ExitCause.NORMAL, 0))
        assertEquals("EXITED", f.jm.get(jobId2)!!.state.name)
    }

    // ─── 4. TimeoutController 关停标记生命周期 ─────────────────────

    @Test
    fun `shutdown flag is cleared when timer is cancelled or replaced`() = runBlocking {
        val f = Fixture()
        val jobId = f.startJob("sleep 60")
        f.timeoutController.startTimeout(f.session.id, jobId, timeoutMs = 60_000) {}
        assertEquals(false, f.timeoutController.hasShutdownInitiated(jobId))
        f.timeoutController.cancelTimeout(jobId)
        assertEquals(false, f.timeoutController.hasShutdownInitiated(jobId))
        // job 正常完成后（transition → cancelTimeout）标记同样被清，
        // 后续同 session 新 job 不被旧标记误伤。
        val jobId2 = f.startJob("echo hi")
        assertNotNull(f.jm.get(jobId2))
        assertEquals(false, f.timeoutController.hasShutdownInitiated(jobId2))
        f.timeoutController.cancelAll()
    }
}
