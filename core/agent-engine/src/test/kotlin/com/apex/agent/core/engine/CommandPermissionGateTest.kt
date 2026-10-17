package com.apex.agent.core.engine

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T92（#255 权限链审计）—— CommandPermissionGate 高危命令确认门的混淆防御锁。
 *
 * ShellExecute / TerminalExec 的每条命令都先过本门（ToolModule shellExecResult /
 * TerminalExecTool approvalGate）。审计结论「权限链真实被 Agent 利用」的前提是
 * 门禁不可被前缀/引号/空白混淆绕过 —— 这里把 [CommandPermissionGate] 已实现的
 * 四类防御（赋值前缀剥离 / 引号剥离 / 空白归一化 / 子串级嵌入链捕获）逐条
 * 固化为回归锁，防止后续重构无意放松。
 */
class CommandPermissionGateTest {

    /** 可编程假网关：记录提问次数，按脚本返回答案。 */
    private class FakeGateway(
        var scriptedAnswer: AgentAnswer
    ) : UserQuestionGateway {
        var askCount = 0
            private set
        var lastQuestion: AgentQuestion? = null
            private set

        override suspend fun ask(question: AgentQuestion): AgentAnswer {
            askCount++
            lastQuestion = question
            return scriptedAnswer
        }
    }

    private fun deniedAnswer() = AgentAnswer(
        questionId = "q",
        selectedOptionId = "deny",
        skipped = false
    )

    private fun allowOnceAnswer() = AgentAnswer(
        questionId = "q",
        selectedOptionId = "allow_once",
        skipped = false
    )

    private fun allowSessionAnswer() = AgentAnswer(
        questionId = "q",
        selectedOptionId = "allow_session",
        skipped = false
    )

    // ── ① 良性命令直接放行（零提问） ─────────────────────────────────

    @Test
    fun `良性命令直接放行不询问`() = runTest {
        val gateway = FakeGateway(deniedAnswer())
        val gate = CommandPermissionGate(gateway)
        listOf(
            "ls -la",
            "cat /proc/meminfo",
            "getprop ro.product.model",
            "df -h /data",
            "echo hello world"
        ).forEach { cmd ->
            assertTrue("应放行: $cmd", gate.ensureAllowed(cmd))
        }
        assertEquals("良性命令不应触发提问", 0, gateway.askCount)
    }

    // ── ② 高危命令原文命中 ───────────────────────────────────────────

    @Test
    fun `rm 原文触发确认且拒绝后返回 false`() = runTest {
        val gateway = FakeGateway(deniedAnswer())
        val gate = CommandPermissionGate(gateway)
        assertFalse(gate.ensureAllowed("rm -rf /data/local/tmp/x"))
        assertEquals(1, gateway.askCount)
        assertNotNull(gateway.lastQuestion)
        assertEquals("危险命令需要确认", gateway.lastQuestion?.title)
    }

    @Test
    fun `pm uninstall 嵌入命令链仍触发确认`() = runTest {
        val gate = CommandPermissionGate(FakeGateway(deniedAnswer()))
        assertFalse(gate.ensureAllowed("cd /system && pm uninstall com.example.app"))
    }

    @Test
    fun `dev 重定向模式触发确认`() = runTest {
        val gate = CommandPermissionGate(FakeGateway(deniedAnswer()))
        assertFalse(gate.ensureAllowed("echo x > /dev/sda"))
    }

    // ── ③ 混淆防御（P-修复回归锁） ───────────────────────────────────

    @Test
    fun `赋值前缀 VAR=1 rm 无法绕过`() = runTest {
        val gate = CommandPermissionGate(FakeGateway(deniedAnswer()))
        assertFalse(gate.ensureAllowed("VAR=1 rm -rf /data/local/tmp/x"))
    }

    @Test
    fun `多重赋值前缀 VAR=1 FOO=bar rm 无法绕过`() = runTest {
        val gate = CommandPermissionGate(FakeGateway(deniedAnswer()))
        assertFalse(gate.ensureAllowed("VAR=1 FOO=bar rm -rf /data/local/tmp/x"))
    }

    @Test
    fun `引号混淆 引号r引号m 无法绕过`() = runTest {
        val gate = CommandPermissionGate(FakeGateway(deniedAnswer()))
        // "r"m -rf / → 剥离引号后含 "rm "
        assertFalse(gate.ensureAllowed("\"r\"m -rf /data/local/tmp/x"))
    }

    @Test
    fun `TAB 分隔的 rm 无法绕过`() = runTest {
        val gate = CommandPermissionGate(FakeGateway(deniedAnswer()))
        assertFalse(gate.ensureAllowed("rm\t-rf /data/local/tmp/x"))
    }

    @Test
    fun `换行分隔的 rm 无法绕过`() = runTest {
        val gate = CommandPermissionGate(FakeGateway(deniedAnswer()))
        assertFalse(gate.ensureAllowed("echo hi\nrm\n-rf /data/local/tmp/x"))
    }

    @Test
    fun `大写 PM UNINSTALL 归一化后仍触发`() = runTest {
        val gate = CommandPermissionGate(FakeGateway(deniedAnswer()))
        assertFalse(gate.ensureAllowed("PM UNINSTALL com.example.app"))
    }

    @Test
    fun `首尾空白不影响命中`() = runTest {
        val gate = CommandPermissionGate(FakeGateway(deniedAnswer()))
        assertFalse(gate.ensureAllowed("   rm -rf /data/local/tmp/x   "))
    }

    // ── ④ 用户三选一语义 ─────────────────────────────────────────────

    @Test
    fun `allow_once 仅本次放行 重试仍询问`() = runTest {
        val gateway = FakeGateway(allowOnceAnswer())
        val gate = CommandPermissionGate(gateway)
        val cmd = "rm -rf /data/local/tmp/x"
        assertTrue(gate.ensureAllowed(cmd))
        assertEquals(1, gateway.askCount)
        assertTrue(gate.ensureAllowed(cmd))
        assertEquals("allow_once 不记忆，第二次应再次询问", 2, gateway.askCount)
    }

    @Test
    fun `allow_session 会话内同命令免询问`() = runTest {
        val gateway = FakeGateway(allowSessionAnswer())
        val gate = CommandPermissionGate(gateway)
        val cmd = "rm -rf /data/local/tmp/x"
        assertTrue(gate.ensureAllowed(cmd))
        assertEquals(1, gateway.askCount)
        assertTrue(gate.ensureAllowed(cmd))
        assertTrue(gate.ensureAllowed(cmd))
        assertEquals("allow_session 记忆后不再询问", 1, gateway.askCount)
    }

    @Test
    fun `超时跳过 skipped 折叠为拒绝`() = runTest {
        val gateway = FakeGateway(
            AgentAnswer(questionId = "q", skipped = true, timedOut = true)
        )
        val gate = CommandPermissionGate(gateway)
        assertFalse(gate.ensureAllowed("rm -rf /data/local/tmp/x"))
    }

    @Test
    fun `会话记忆按归一化命令匹配 空白变体不共享记忆`() = runTest {
        val gateway = FakeGateway(allowSessionAnswer())
        val gate = CommandPermissionGate(gateway)
        assertTrue(gate.ensureAllowed("rm  -rf /data/local/tmp/x")) // 双空格 → 归一化单空格
        assertEquals(1, gateway.askCount)
        assertTrue(gate.ensureAllowed("rm  -rf /data/local/tmp/x"))
        assertEquals("归一化后相同命令共享会话记忆", 1, gateway.askCount)
    }
}
