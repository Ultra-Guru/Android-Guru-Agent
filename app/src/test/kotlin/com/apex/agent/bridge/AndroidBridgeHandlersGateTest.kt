package com.apex.agent.bridge

import com.apex.agent.platform.TerminalBridge.GuestBridgeService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #854 剪贴板桥门禁回归（纯 JVM —— 门禁 + getter/setter 全 lambda 注入，
 * 与 VaultAgentTools 同款分层；Android ClipboardManager 装配由 DI 承担）。
 *
 * 两条暴露面共用同一道门（设置开关 clipboardBridgeEnabled，默认 false）：
 * - LLM 工具：TerminalBridgeTool call clipboard → GuestBridgeService.dispatch；
 * - guest 文件队列：apexctl clipboard → pollOnce → dispatch。
 *
 * 安全红线断言：门禁关闭时 get/set 一律 CLIPBOARD_ACCESS_DISABLED 结构化拒绝
 * （稳定错误码 + 引导文案），绝不触碰剪贴板 lambda（OTP/收码不出门）。
 */
class AndroidBridgeHandlersGateTest {

    /** 手写 fake：门禁读数可切换 + 剪贴板读写录制（证「拒绝时零触碰」）。 */
    private class FakeClipboard {
        @Volatile
        var enabled: Boolean = false
        @Volatile
        var text: String? = null
        val reads = mutableListOf<Unit>()
        val writes = mutableListOf<String>()

        val gate: () -> Boolean = { enabled }
        val getter: () -> String? = {
            reads += Unit
            text
        }
        val setter: (String) -> Unit = { writes += it }
    }

    private fun bridgeWith(fake: FakeClipboard): GuestBridgeService {
        val dir = File.createTempFile("clipgate", "").let { it.delete(); it.mkdirs(); it }
        return GuestBridgeService(
            bridgeRoot = File(dir, "bridge"),
            handlers = listOf(
                AndroidBridgeHandlers.clipboardHandler(
                    enabled = fake.gate,
                    textGetter = fake.getter,
                    textSetter = fake.setter
                )
            ),
            scope = CoroutineScope(SupervisorJob())
        )
    }

    @Test
    fun `disabled by default refuses clipboard get with structured error and hint`() = runTest {
        val fake = FakeClipboard().apply { text = "otp-987654" }
        val bridge = bridgeWith(fake)
        val resp = bridge.dispatch("clipboard", "get")

        assertFalse(resp.ok)
        assertEquals("CLIPBOARD_ACCESS_DISABLED", resp.error)
        assertNull(resp.data)
        // 拒绝必须携带引导文案（去设置开启 / 改用金库 —— 模型可据此引导用户）
        assertTrue("hint must guide the user, got: ${resp.hint}", resp.hint.orEmpty().contains("VaultSave"))
        // 零触碰红线：门禁关闭时连剪贴板读取都没发生
        assertTrue("clipboard must not be read while disabled", fake.reads.isEmpty())
    }

    @Test
    fun `disabled refuses clipboard set too (no silent poisoning)`() = runTest {
        val fake = FakeClipboard()
        val bridge = bridgeWith(fake)
        val resp = bridge.dispatch("clipboard", "set \"poisoned\"")

        assertFalse(resp.ok)
        assertEquals("CLIPBOARD_ACCESS_DISABLED", resp.error)
        assertTrue(fake.writes.isEmpty())
    }

    @Test
    fun `guest file-queue path shares the same gate (apexctl clipboard get)`() = runTest {
        val dir = File.createTempFile("clipqueue", "").let { it.delete(); it.mkdirs(); it }
        val fake = FakeClipboard().apply { text = "otp-123456" }
        // 轮询循环挂 backgroundScope（虚拟时间）—— 只走公共 API 驱动文件队列
        // （pollOnce 是 platform:terminal 的 internal，跨模块不可见）。
        val bridge = GuestBridgeService(
            bridgeRoot = File(dir, "bridge"),
            handlers = listOf(
                AndroidBridgeHandlers.clipboardHandler(
                    enabled = fake.gate,
                    textGetter = fake.getter,
                    textSetter = fake.setter
                )
            ),
            scope = backgroundScope
        )
        bridge.ensureInstalled()
        // guest 侧（任意 npm/pip 钩子进程同款）模拟：写请求文件 → host 轮询
        File(dir, "bridge/req/g1.json").writeText("""{"id":"g1","action":"clipboard","args":"get"}""")
        bridge.start()
        testScheduler.advanceTimeBy(1_000L)   // 虚拟推进轮询：首轮即刻处理 g1

        val resp = File(dir, "bridge/resp/g1.json").readText()
        assertTrue(resp.contains("\"ok\":false"))
        assertTrue(resp.contains("CLIPBOARD_ACCESS_DISABLED"))
        assertTrue(resp.contains("\"hint\":"))
        assertTrue(fake.reads.isEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun `enabled passes get through and returns verbatim clipboard content`() = runTest {
        val fake = FakeClipboard().apply { enabled = true; text = "hello from clipboard" }
        val bridge = bridgeWith(fake)
        val resp = bridge.dispatch("clipboard", "get")

        assertTrue(resp.ok)
        assertEquals("hello from clipboard", resp.data)
        assertEquals(1, fake.reads.size)
        assertNull(resp.hint)
    }

    @Test
    fun `enabled passes set through and records the write`() = runTest {
        val fake = FakeClipboard().apply { enabled = true }
        val bridge = bridgeWith(fake)
        val resp = bridge.dispatch("clipboard", "set \"hello from ubuntu\"")

        assertTrue(resp.ok)
        assertEquals("ok", resp.data)
        assertEquals(listOf("hello from ubuntu"), fake.writes)
    }

    @Test
    fun `enabled get maps empty clipboard to empty string (honest semantics kept)`() = runTest {
        val fake = FakeClipboard().apply { enabled = true; text = null }
        val bridge = bridgeWith(fake)
        val resp = bridge.dispatch("clipboard", "get")

        assertTrue(resp.ok)
        assertEquals("", resp.data)
    }

    @Test
    fun `gate flips take effect immediately (live read, no restart)`() = runTest {
        val fake = FakeClipboard().apply { text = "token-42" }
        val bridge = bridgeWith(fake)

        val denied = bridge.dispatch("clipboard", "get")
        assertFalse(denied.ok)

        fake.enabled = true   // 设置页开关切换 → 下一次调用即放行
        val allowed = bridge.dispatch("clipboard", "get")
        assertTrue(allowed.ok)
        assertEquals("token-42", allowed.data)
    }
}
