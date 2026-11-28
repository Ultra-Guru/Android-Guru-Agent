package com.apex.agent.platform.terminal.bridge

import com.apex.agent.platform.TerminalBridge.BridgeAccessRefused
import com.apex.agent.platform.TerminalBridge.GuestBridgeHandler
import com.apex.agent.platform.TerminalBridge.GuestBridgeService
import com.apex.agent.platform.terminal.tools.v2.TerminalBridgeTool
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
 * #854 桥门禁契约（平台层）：handler 抛 [BridgeAccessRefused] →
 * `ok:false` + error（稳定错误码）+ hint（引导文案）的结构化拒绝，
 * 直连（agent / TerminalBridgeTool）与文件队列（apexctl）两条路径同一语义。
 *
 * 剪贴板桥默认关闭（app 层 AndroidBridgeHandlers 门禁）走的就是这条契约：
 * 门禁拒绝不是普通失败，绝不折叠成裸异常 message。
 */
class GuestBridgeGateTest {

    /** 手写 fake：门禁可切换的 handler（app 层剪贴板 handler 的最小替身）。 */
    private class GatedHandler : GuestBridgeHandler {
        @Volatile
        var enabled = false
        override val action = "clipboard"
        override val description = "gated test action"
        override suspend fun handle(argsJson: String): String {
            if (!enabled) {
                throw BridgeAccessRefused(
                    code = "CLIPBOARD_ACCESS_DISABLED",
                    hint = "剪贴板桥已默认关闭（安全策略）——请在设置开启，或改用金库 VaultSave/VaultPaste"
                )
            }
            return "clip:$argsJson"
        }
    }

    private fun bridgeWith(handler: GatedHandler): Pair<GuestBridgeService, File> {
        val dir = File.createTempFile("bridgegate", "").let { it.delete(); it.mkdirs(); it }
        return GuestBridgeService(
            bridgeRoot = File(dir, "bridge"),
            handlers = listOf(handler),
            scope = CoroutineScope(SupervisorJob())
        ) to dir
    }

    @Test
    fun `refusal serializes structured error code and hint (not a raw message)`() = runTest {
        val (bridge, dir) = bridgeWith(GatedHandler())
        val resp = bridge.dispatch("clipboard", "get")

        assertFalse(resp.ok)
        assertNull(resp.data)
        assertEquals("CLIPBOARD_ACCESS_DISABLED", resp.error)
        assertTrue(resp.hint.orEmpty().contains("VaultSave"))

        val json = resp.toJson()
        assertTrue(json.contains("\"ok\":false"))
        assertTrue(json.contains("\"error\":\"CLIPBOARD_ACCESS_DISABLED\""))
        assertTrue(json.contains("\"hint\":"))
        assertFalse(json.contains("BridgeAccessRefused"))   // 异常类名绝不外漏
        dir.deleteRecursively()
    }

    @Test
    fun `refusal round trips through the guest file queue`() = runTest {
        val (bridge, dir) = bridgeWith(GatedHandler())
        bridge.ensureInstalled()
        File(dir, "bridge/req/q1.json").writeText("""{"id":"q1","action":"clipboard","args":"get"}""")
        assertEquals(1, bridge.pollOnce())

        val resp = File(dir, "bridge/resp/q1.json").readText()
        assertTrue(resp.contains("\"ok\":false"))
        assertTrue(resp.contains("\"error\":\"CLIPBOARD_ACCESS_DISABLED\""))
        assertTrue(resp.contains("\"hint\":"))
        dir.deleteRecursively()
    }

    @Test
    fun `agent tool surfaces the hint so the model can guide the user`() = runTest {
        val gated = GatedHandler()
        val (bridge, dir) = bridgeWith(gated)
        val tool = TerminalBridgeTool(bridge)

        val denied = tool.invoke("""{"action":"call","bridgeAction":"clipboard","args":"get"}""")
        assertTrue(denied.contains("\"ok\":false"))
        assertTrue(denied.contains("CLIPBOARD_ACCESS_DISABLED"))
        assertTrue("hint must reach the model", denied.contains("\"hint\":"))

        // 开门后同一条直连路径恢复放行（门禁实时生效，status 仍列出该动作）
        assertTrue(bridge.supportedActions().containsKey("clipboard"))
        gated.enabled = true
        val allowed = tool.invoke("""{"action":"call","bridgeAction":"clipboard","args":"get"}""")
        assertTrue(allowed.contains("\"data\":\"clip:get\""))
        dir.deleteRecursively()
    }

    @Test
    fun `plain handler failures stay hint-free (gate refusals are distinct)`() = runTest {
        val dir = File.createTempFile("bridgefail", "").let { it.delete(); it.mkdirs(); it }
        val bridge = GuestBridgeService(
            bridgeRoot = File(dir, "bridge"),
            handlers = listOf(object : GuestBridgeHandler {
                override val action = "boom"
                override val description = "always fails"
                override suspend fun handle(argsJson: String): String = throw IllegalStateException("kaboom")
            }),
            scope = CoroutineScope(SupervisorJob())
        )
        val resp = bridge.dispatch("boom", "null")
        assertFalse(resp.ok)
        assertEquals("kaboom", resp.error)
        assertNull(resp.hint)   // 普通失败不带 hint —— hint 是门禁拒绝专属语义
        dir.deleteRecursively()
    }
}
