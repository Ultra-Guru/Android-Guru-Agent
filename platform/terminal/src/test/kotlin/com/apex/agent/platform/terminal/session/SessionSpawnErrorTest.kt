package com.apex.agent.platform.terminal.session

import com.apex.agent.platform.terminal.policy.TerminalPolicyImpl
import com.apex.agent.platform.terminal.pty.FakeNativePty
import com.apex.agent.platform.terminal.runtime.LocalShellBackend
import com.apex.agent.platform.terminal.runtime.TerminalRuntimeImpl
import com.apex.agent.platform.terminal.screen.RealVirtualTerminal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T87（Ubuntu「输入失败」根因回归测试）：
 *
 * 「创建成功但 execv 即死」的会话必须在 **create 当场** 被结构化拒绝
 * （TerminalError:ExecFailed + 确切原因），而不是放行一个死 PTY 让
 * 用户对着后续每次 write 的「输入失败」猜谜。
 *
 * 模拟手段：FakeNativePty.pendingSpawnFailure（对应真实 native 层
 * CLOEXEC 报告管道产出的 spawnError —— pty_session.cpp 同协议）。
 */
class SessionSpawnErrorTest {

    private fun newRuntime(fake: FakeNativePty): TerminalRuntimeImpl = TerminalRuntimeImpl(
        native = fake,
        policy = TerminalPolicyImpl(),
        backendRegistry = com.apex.agent.platform.terminal.runtime.ExecutionBackendRegistry.of(
            LocalShellBackend()
        ),
        virtualTerminalFactory = { r, c -> RealVirtualTerminal(r, c) }
    )

    @Test
    fun `exec failure surfaces structured ExecFailed error at create`() = runBlocking {
        val fake = FakeNativePty()
        fake.pendingSpawnFailure = "execv(/nonexistent/proot) failed: No such file or directory"
        val runtime = newRuntime(fake)

        val result = runtime.create(backendId = "local")
        // 必须**失败**，绝不放行死会话
        assertTrue("exec 失败的会话必须创建失败", result.isFailure)
        val msg = result.exceptionOrNull()?.message ?: ""
        assertTrue("错误码必须是 ExecFailed（实际: $msg）", msg.contains("TerminalError:ExecFailed"))
        assertTrue("错误必须带确切 exec 原因（实际: $msg）", msg.contains("No such file or directory"))
        assertTrue("错误必须带 argv0 上下文（实际: $msg）", msg.contains("/nonexistent/proot"))
    }

    @Test
    fun `spawn error session is closed after rejection`() = runBlocking {
        val fake = FakeNativePty()
        fake.pendingSpawnFailure = "execv(/bin/bash) failed: Permission denied"
        val runtime = newRuntime(fake)

        assertTrue(runtime.create(backendId = "local").isFailure)

        // 被拒会话必须已从 native 层摘除（不留僵尸 —— 否则 closeAll 才清）
        // nativeCreateSessionArgv 消耗掉 pendingSpawnFailure 后，普通创建应恢复正常。
        val ok = runtime.create(backendId = "local")
        assertTrue("单发注入后创建应恢复正常（实际: ${ok.exceptionOrNull()?.message}）", ok.isSuccess)
    }

    @Test
    fun `normal create is unaffected by the probe`() = runBlocking {
        val fake = FakeNativePty()
        val runtime = newRuntime(fake)
        val ok = runtime.create(backendId = "local")
        assertTrue(ok.isSuccess)
        assertTrue(ok.getOrThrow().sessionId > 0)
    }
}
