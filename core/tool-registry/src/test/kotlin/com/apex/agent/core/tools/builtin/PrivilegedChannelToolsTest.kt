package com.apex.agent.core.tools.builtin

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Issue #239 / #240 回归：特权通道工具路由。
 *
 * - #239：`screenshot` 工具接特权链（无障碍 API 30+ → root screencap）——
 *   旧行为恒走裸 shell，无障碍开启但无 root 的设备截图永远失败。锁死：
 *   特权成功直接落盘、特权失败回退 shell、双失败并列上报。
 * - #240：`UiNotifications` 工具（open/close）—— a11y 语义通道优先，
 *   shell 回退走 `cmd statusbar`（绝不 keyevent 26 电源键）。
 */
class PrivilegedChannelToolsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ═══════════════════════ #239 screenshot ═══════════════════════

    @Test
    fun `privileged screenshot success writes png directly`() = runTest {
        val out = File(tmp.root, "out/shot.png")
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)
        val tool = ScreenshotTool(
            shellExecutor = { fail("特权成功时不得再走 shell") },
            privilegedScreenshot = { ScreenshotTool.PrivilegedScreenshot(png, null) }
        )
        val result = tool.execute("{\"path\":\"${out.absolutePath.replace("\\", "\\\\")}\"}")
        assertTrue(result, result.startsWith("OK: Screenshot saved"))
        assertTrue("字节逐位落盘", out.readBytes().contentEquals(png))
    }

    @Test
    fun `privileged failure falls back to shell and reports both errors`() = runTest {
        var shellCommand: String? = null
        val tool = ScreenshotTool(
            shellExecutor = { cmd ->
                shellCommand = cmd
                "Error: java.io.IOException: Cannot run program \"screencap\": error=13, Permission denied"
            },
            privilegedScreenshot = { ScreenshotTool.PrivilegedScreenshot(null, "a11y off, no root") }
        )
        val result = tool.execute("{}")
        assertTrue(result, result.startsWith("Error: screenshot failed"))
        assertTrue("特权链根因上报", result.contains("a11y off, no root"))
        assertTrue("shell 兜底输出并列上报", result.contains("Permission denied"))
        assertEquals(
            "shell 兜底命令形态不变（路径带引号）",
            "screencap -p '/sdcard/Pictures/apex_screen.png'",
            shellCommand
        )
    }

    @Test
    fun `no privileged channel keeps legacy shell-only behavior`() = runTest {
        var shellCommand: String? = null
        val tool = ScreenshotTool(shellExecutor = { cmd -> shellCommand = cmd; "" })
        val result = tool.execute("{}")
        assertTrue(result, result.startsWith("OK:"))
        assertEquals("screencap -p '/sdcard/Pictures/apex_screen.png'", shellCommand)
    }

    @Test
    fun `shell fallback success after privileged failure still succeeds`() = runTest {
        // 模拟器/调试设备：特权链失败但非特权 screencap 可用 —— 旧行为保留
        val tool = ScreenshotTool(
            shellExecutor = { "" },
            privilegedScreenshot = { ScreenshotTool.PrivilegedScreenshot(null, "a11y off, no root") }
        )
        val result = tool.execute("{}")
        assertTrue(result, result.startsWith("OK: Screenshot saved"))
    }

    // ═══════════════════════ #240 UiNotifications ═══════════════════════

    /** 记录型手势通道（a11y 就绪）。 */
    private class RecordingUiProvider(private val available: Boolean = true) : UiInteractionProvider {
        val gestures = mutableListOf<GestureAction>()
        override val isAvailable: Boolean get() = available
        override suspend fun performGesture(action: GestureAction): String {
            gestures += action
            return "OK: gesture done"
        }
        override suspend fun dumpUiTree(maxDepth: Int): String = "<tree/>"
    }

    @Test
    fun `UiNotifications open routes semantic gesture when a11y available`() = runTest {
        val provider = RecordingUiProvider()
        val tool = UiNotificationsTool(shellExecutor = { fail("a11y 就绪不得走 shell") }, uiProvider = provider)
        val result = tool.execute("{}")
        assertTrue(result, result.startsWith("OK"))
        assertEquals(listOf(GestureAction.OpenNotifications), provider.gestures)
    }

    @Test
    fun `UiNotifications close routes close gesture`() = runTest {
        val provider = RecordingUiProvider()
        val tool = UiNotificationsTool(shellExecutor = { fail("a11y 就绪不得走 shell") }, uiProvider = provider)
        val result = tool.execute("{\"action\":\"close\"}")
        assertTrue(result, result.startsWith("OK"))
        assertEquals(listOf(GestureAction.CloseNotifications), provider.gestures)
    }

    @Test
    fun `UiNotifications shell fallback uses cmd statusbar never keyevent 26`() = runTest {
        val commands = mutableListOf<String>()
        val tool = UiNotificationsTool(
            shellExecutor = { cmd -> commands += cmd; "" },
            uiProvider = null
        )
        tool.execute("{}")
        tool.execute("{\"action\":\"close\"}")
        assertEquals(
            "#240 原始事故回归：绝不再发 keyevent 26（电源键）",
            listOf("cmd statusbar expand-notifications", "cmd statusbar collapse"),
            commands
        )
    }

    @Test
    fun `UiNotifications rejects invalid action argument`() = runTest {
        val tool = UiNotificationsTool(shellExecutor = { "" })
        val result = tool.execute("{\"action\":\"toggle\"}")
        assertTrue(result, result.startsWith("Error:"))
        assertTrue(result, result.contains("open") && result.contains("close"))
    }

    private fun fail(message: String): String = throw AssertionError(message)
}
