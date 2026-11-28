package com.apex.agent.bridge

import com.apex.agent.core.tools.device.DeviceOpResult
import com.apex.agent.core.tools.device.DeviceOpsPort
import com.apex.agent.platform.TerminalBridge.GuestBridgeService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * ═══ S09 guest 桥 device handler 测试 ═══
 *
 * `apexctl device <DeviceOps-JSON>` 经 GuestBridgeService.dispatch 与
 * Agent 侧走**同一个** [DeviceOpsPort]（协议不另造）；未知 intent /
 * 参数非法走既有 ok:false 降级语义；dry_run / token 透传。
 */
class DeviceBridgeHandlerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakePort : DeviceOpsPort {
        val calls = mutableListOf<Triple<String, Map<String, String>, Boolean>>()
        var tokens = mutableListOf<String?>()

        override suspend fun execute(
            intentId: String,
            params: Map<String, String>,
            dryRun: Boolean,
            token: String?
        ): DeviceOpResult {
            calls += Triple(intentId, params, dryRun)
            tokens += token
            return DeviceOpResult(
                ok = true, intentId = intentId, channel = "shizuku", uid = 2000, dryRun = dryRun
            )
        }

        override suspend fun executeLegacy(intentId: String, params: Map<String, String>): DeviceOpResult {
            calls += Triple(intentId, params, false)
            return DeviceOpResult(
                ok = true, intentId = intentId, channel = "shizuku", uid = 2000, dryRun = false
            )
        }
    }

    private fun service(port: FakePort): GuestBridgeService =
        GuestBridgeService(
            bridgeRoot = tmp.newFolder("bridge"),
            handlers = listOf(AndroidBridgeHandlers.deviceBridge(port)),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        )

    @Test
    fun `device action 透传 DeviceOps JSON 到 port`() = runTest {
        val port = FakePort()
        val resp = service(port).dispatch(
            "device",
            """{"intent":"package.uninstall_for_user","params":{"package":"com.x"},"dry_run":true,"confirm_token":"v1.1.t"}"""
        )
        assertTrue(resp.ok)
        val (intentId, params, dryRun) = port.calls.single()
        assertEquals("package.uninstall_for_user", intentId)
        assertEquals("com.x", params["package"])
        assertEquals(true, dryRun)
        assertEquals("v1.1.t", port.tokens.single())
        // 返回体是 DeviceOpResult JSON。
        assertTrue(resp.data!!.contains("\"intent\":\"package.uninstall_for_user\""))
    }

    @Test
    fun `status 参数走只读 probe 意图`() = runTest {
        val port = FakePort()
        service(port).dispatch("device", "status")
        val (intentId, params, dryRun) = port.calls.single()
        assertEquals("probe.id", intentId)
        assertEquals(true, params.isEmpty())
        assertEquals(false, dryRun)
    }

    @Test
    fun `未知 action 沿用 BRIDGE_UNKNOWN_ACTION 语义`() = runTest {
        val resp = service(FakePort()).dispatch("no-such-action", "{}")
        assertEquals(false, resp.ok)
        assertEquals("BRIDGE_UNKNOWN_ACTION", resp.error)
    }

    @Test
    fun `非法 JSON 结构化拒绝且 ok 为 false`() = runTest {
        val port = FakePort()
        val resp = service(port).dispatch("device", "not json")
        assertEquals(false, resp.ok)
        assertTrue(resp.error!!.contains("DeviceOps JSON"))
        assertEquals("handler 参数解析失败时零 port 调用", 0, port.calls.size)
    }

    @Test
    fun `缺 intent 字段按 handler 异常降级`() = runTest {
        val port = FakePort()
        val resp = service(port).dispatch("device", """{"params":{}}""")
        assertEquals(false, resp.ok)
        assertTrue(resp.error!!.contains("intent"))
        assertEquals(0, port.calls.size)
    }
}
