package com.apex.agent.core.tools.builtin

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #854 legacy ClipboardTool 门禁回归（纯 JVM —— shell 通道手写 fake 录制，
 * 与 PrivilegedChannelToolsTest 同款分层）。
 *
 * 门禁契约与 apexctl 桥（app 层 AndroidBridgeHandlers）同一开关同一错误码：
 * gate（DI 注入，实时读设置）关闭时 read/write 一律结构化拒绝
 * （CLIPBOARD_ACCESS_DISABLED + 引导文案），且**不触碰** shell 通道；
 * gate = null 的既有调用方保持原行为（向后兼容）。
 */
class SystemControlToolsGateTest {

    /** 录制 shell 命令的手写 fake（拒绝路径零触碰断言的数据源）。 */
    private class RecordingShell {
        val commands = mutableListOf<String>()
        val executor: suspend (String) -> String = { cmd ->
            commands += cmd
            "shell-result"
        }
    }

    @Test
    fun `disabled gate refuses read with structured error and no shell touch`() = runTest {
        val shell = RecordingShell()
        val tool = ClipboardTool(shell.executor, gate = { false })
        val result = tool.execute("""{"action": "read"}""")

        assertTrue(result.contains("\"ok\": false"))
        assertTrue(result.contains("CLIPBOARD_ACCESS_DISABLED"))
        assertTrue("refusal must carry a guiding hint", result.contains("\"hint\":"))
        assertTrue("hint must point to the vault", result.contains("VaultSave"))
        // 零触碰红线：拒绝路径绝不起 shell（更不可能读剪贴板）
        assertTrue("shell must stay untouched while disabled", shell.commands.isEmpty())
    }

    @Test
    fun `disabled gate refuses write too (no silent clipboard poisoning)`() = runTest {
        val shell = RecordingShell()
        val tool = ClipboardTool(shell.executor, gate = { false })
        val result = tool.execute("""{"action": "write", "content": "poisoned"}""")

        assertTrue(result.contains("CLIPBOARD_ACCESS_DISABLED"))
        assertTrue(shell.commands.isEmpty())
    }

    @Test
    fun `enabled gate passes read through to the shell channel`() = runTest {
        val shell = RecordingShell()
        val tool = ClipboardTool(shell.executor, gate = { true })
        val result = tool.execute("""{"action": "read"}""")

        assertFalse(result.contains("CLIPBOARD_ACCESS_DISABLED"))
        assertEquals(1, shell.commands.size)
        assertTrue(shell.commands.single().contains("clipboard"))
        // 既有语义保持：fake 返回非空 → 原样透传（诚实而非静默）
        assertEquals("shell-result", result)
    }

    @Test
    fun `enabled gate passes write through with shell-escaped content`() = runTest {
        val shell = RecordingShell()
        val tool = ClipboardTool(shell.executor, gate = { true })
        val result = tool.execute("""{"action": "write", "content": "it's"}""")

        assertFalse(result.contains("CLIPBOARD_ACCESS_DISABLED"))
        assertEquals(1, shell.commands.size)
        // ShellQuote 注入防线（既有行为）不受门禁影响
        assertTrue(shell.commands.single().contains("clipper.set"))
    }

    @Test
    fun `null gate keeps legacy behavior (backward compat for existing callers)`() = runTest {
        val shell = RecordingShell()
        val tool = ClipboardTool(shell.executor)
        val result = tool.execute("""{"action": "read"}""")

        assertFalse(result.contains("CLIPBOARD_ACCESS_DISABLED"))
        assertEquals(1, shell.commands.size)
    }

    @Test
    fun `gate flips take effect immediately (live read, no restart)`() = runTest {
        val shell = RecordingShell()
        val enabled = java.util.concurrent.atomic.AtomicBoolean(false)
        val tool = ClipboardTool(shell.executor, gate = { enabled.get() })

        val denied = tool.execute("""{"action": "read"}""")
        assertTrue(denied.contains("CLIPBOARD_ACCESS_DISABLED"))

        enabled.set(true)   // 设置页开关切换 → 下一次调用即放行
        val allowed = tool.execute("""{"action": "read"}""")
        assertFalse(allowed.contains("CLIPBOARD_ACCESS_DISABLED"))
        assertEquals(1, shell.commands.size)
    }
}
