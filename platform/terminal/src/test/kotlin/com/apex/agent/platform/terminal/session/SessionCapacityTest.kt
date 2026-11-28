package com.apex.agent.platform.terminal.session

import com.apex.agent.platform.terminal.pty.FakeNativePty
import com.apex.agent.platform.terminal.policy.TerminalPolicyImpl
import com.apex.agent.platform.terminal.runtime.ExecutionBackendRegistry
import com.apex.agent.platform.terminal.runtime.FixtureLinuxBackend
import com.apex.agent.platform.terminal.runtime.LocalShellBackend
import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.runtime.TerminalRuntimeImpl
import com.apex.agent.platform.terminal.screen.RealVirtualTerminal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #839 — 会话容量门禁 + 内存压力驱逐测试（纯 JVM，FakeNativePty）。
 *
 * 锁定契约：
 * 1. 上限 16：第 17 个 create 驱逐最旧 IDLE（READY）会话腾位后成功；
 *    全部在用（RUNNING）时第 17 个 create 结构化失败 SessionLimitReached；
 * 2. onTrimMemory：RUNNING_LOW(10)+ 才生效；驱逐 IDLE（EXITED 优先于
 *    READY、同态最旧优先）至保留数 4；在用会话（RUNNING）绝不驱逐。
 *
 * 确定性：pumpScope 注入 UnconfinedTestDispatcher —— 驱逐协程在测试调度器
 * 上执行，runTest.advanceUntilIdle 完成即收敛（真实 IO 池异步轮询的替代）。
 */
class SessionCapacityTest {

    private suspend fun sessionCount(rt: TerminalRuntimeImpl): Int =
        rt.snapshot(TerminalRuntime.SnapshotMode.SESSIONS).getOrThrow().sessions.size

    @Test
    fun `cap 16 - 17th create evicts oldest idle session then succeeds`() = runTest {
        val rt = TerminalRuntimeImpl(
            native = FakeNativePty(),
            policy = TerminalPolicyImpl(),
            backendRegistry = ExecutionBackendRegistry.of(LocalShellBackend(), FixtureLinuxBackend()),
            virtualTerminalFactory = { r, c -> RealVirtualTerminal(r, c) },
            pumpScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        )
        val first = rt.create().getOrThrow()
        repeat(SessionManagerImpl.MAX_SESSIONS - 1) { rt.create().getOrThrow() }
        assertEquals("16 会话满额", SessionManagerImpl.MAX_SESSIONS, sessionCount(rt))

        // 第 17 个 create：最旧 IDLE（READY）被驱逐腾位，创建成功，总数仍 16。
        val s17 = rt.create().getOrThrow()
        assertEquals("驱逐腾位后总数不超上限", SessionManagerImpl.MAX_SESSIONS, sessionCount(rt))
        assertFalse(
            "最旧 IDLE 会话应已被驱逐",
            rt.snapshot(TerminalRuntime.SnapshotMode.SESSIONS).getOrThrow()
                .sessions.any { it.session.id == first.sessionId }
        )
        assertTrue("新会话在册", sessionCount(rt) in 1..SessionManagerImpl.MAX_SESSIONS)
        assertNotNull(s17)
    }

    @Test
    fun `onTrimMemory below RUNNING_LOW is a no-op`() = runTest {
        val rt = TerminalRuntimeImpl(
            native = FakeNativePty(),
            policy = TerminalPolicyImpl(),
            backendRegistry = ExecutionBackendRegistry.of(LocalShellBackend(), FixtureLinuxBackend()),
            virtualTerminalFactory = { r, c -> RealVirtualTerminal(r, c) },
            pumpScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        )
        repeat(3) { rt.create().getOrThrow() }
        val scheduled = rt.sessionManager.onTrimMemory(5) // RUNNING_MODERATE：不足档
        assertFalse("低于 RUNNING_LOW 不应调度驱逐", scheduled)
        advanceUntilIdle()
        assertEquals("无驱逐发生", 3, sessionCount(rt))
    }

    @Test
    fun `onTrimMemory evicts idle beyond keep-count and never touches running`() = runTest {
        val rt = TerminalRuntimeImpl(
            native = FakeNativePty(),
            policy = TerminalPolicyImpl(),
            backendRegistry = ExecutionBackendRegistry.of(LocalShellBackend(), FixtureLinuxBackend()),
            virtualTerminalFactory = { r, c -> RealVirtualTerminal(r, c) },
            pumpScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        )
        // 超保留数两个 IDLE 会话：RUNNING_LOW 后保留 4、驱逐最旧 2 个。
        val ids = (1..6).map { rt.create().getOrThrow().sessionId }
        assertTrue(rt.sessionManager.onTrimMemory(10))
        advanceUntilIdle()
        assertEquals(
            "驱逐至保留数 4",
            SessionManagerImpl.TRIM_KEEP_IDLE_SESSIONS,
            sessionCount(rt)
        )
        // 最旧的两个被驱逐、最新的存活。
        val survivors = rt.snapshot(TerminalRuntime.SnapshotMode.SESSIONS).getOrThrow()
            .sessions.map { it.session.id }
        assertFalse(ids[0] in survivors)
        assertFalse(ids[1] in survivors)
        assertTrue(ids[2] in survivors)
        assertTrue(ids[5] in survivors)
    }
}
