package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.device.DeviceCapabilitySnapshot
import com.apex.agent.core.tools.device.DeviceOpResult
import com.apex.agent.core.tools.device.DeviceOpsPort
import com.apex.agent.core.tools.device.DevicePrivilege
import com.apex.agent.core.tools.device.IntentCatalog
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ═══ DCP 工具面测试（S01/S05/S07/S10）═══
 *
 * 覆盖：DeviceCapability / DeviceOps / DeviceAuditQuery 三工具 + AppUninstall /
 * AppForceStop 的 S05 委托兼容层。全部手写 fake port / probe（无 mock 框架）。
 */
class DeviceControlToolsTest {

    // ── fakes ──────────────────────────────────────────────────────

    private class FakePort : DeviceOpsPort {
        val calls = mutableListOf<Triple<String, Map<String, String>, Boolean>>()
        var token: String? = null
        var result: DeviceOpResult = DeviceOpResult(
            ok = true, intentId = "package.list", channel = "shizuku", uid = 2000, dryRun = false
        )

        override suspend fun execute(
            intentId: String,
            params: Map<String, String>,
            dryRun: Boolean,
            token: String?
        ): DeviceOpResult {
            calls += Triple(intentId, params, dryRun)
            return result
        }

        override suspend fun executeLegacy(intentId: String, params: Map<String, String>): DeviceOpResult {
            calls += Triple(intentId, params, false)
            return result
        }
    }

    private class FakeProbe(private val snapshot: DeviceCapabilitySnapshot) : com.apex.agent.core.tools.device.CapabilityProbe {
        override fun snapshot(): DeviceCapabilitySnapshot = snapshot
    }

    private fun loadCatalog(): IntentCatalog {
        val file = listOf(
            File("app/src/main/assets/device/intents.json"),
            File("../app/src/main/assets/device/intents.json")
        ).firstOrNull { it.isFile } ?: error("intents.json 不存在")
        return IntentCatalog.load(file.readText())
    }

    // ── DeviceCapabilityTool（S01）──────────────────────────────────

    @Test
    fun `DeviceCapability 返回结构化档位快照`() = runTest {
        val snapshot = DeviceCapabilitySnapshot(
            privilege = DevicePrivilege.SHIZUKU, uid = 2000,
            shizukuBinderAlive = true, shizukuGranted = true, shizukuApiLevel = 13,
            supports = setOf("package.list"), denies = mapOf("file.write" to "需要 ROOT"),
            probedAtMillis = 1_700_000_000_000L
        )
        val tool = DeviceCapabilityTool(FakeProbe(snapshot))
        val out = tool.execute("{}")
        val json = Json.parseToJsonElement(out).jsonObject
        assertEquals(true, (json["ok"] as kotlinx.serialization.json.JsonPrimitive).content.toBoolean())
        assertEquals("SHIZUKU", (json["privilege"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals(2000, (json["uid"] as kotlinx.serialization.json.JsonPrimitive).content.toInt())
        val denies = json["denies"] as kotlinx.serialization.json.JsonObject
        assertTrue("denies 应含 file.write", denies.containsKey("file.write"))
    }

    @Test
    fun `DeviceCapability 探针异常时结构化失败不崩溃`() = runTest {
        val tool = DeviceCapabilityTool(object : com.apex.agent.core.tools.device.CapabilityProbe {
            override fun snapshot(): DeviceCapabilitySnapshot = throw IllegalStateException("binder died")
        })
        val out = tool.execute("{}")
        val json = Json.parseToJsonElement(out).jsonObject
        assertEquals(false, (json["ok"] as kotlinx.serialization.json.JsonPrimitive).content.toBoolean())
        assertEquals("PROBE_FAILED", (json["error"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun `DeviceCapability 元数据是 LOW 且只读`() {
        val tool = DeviceCapabilityTool(
            FakeProbe(
                DeviceCapabilitySnapshot(DevicePrivilege.NORMAL_SHELL, null, false, false, null, emptySet(), emptyMap(), 0L)
            )
        )
        assertEquals(com.apex.agent.core.tools.ToolRisk.LOW, tool.metadata.risk)
        assertTrue(tool.metadata.annotations.readOnlyHint)
    }

    // ── DeviceOpsTool（S07）────────────────────────────────────────

    @Test
    fun `DeviceOps 解析四字段并透传到 port`() = runTest {
        val port = FakePort()
        val tool = DeviceOpsTool(port, loadCatalog())
        val out = tool.execute(
            """{"intent":"package.uninstall_for_user","params":{"package":"com.x","user":"0"},"dry_run":true,"confirm_token":"v1.1.abc"}"""
        )
        assertEquals(1, port.calls.size)
        val (intentId, params, dryRun) = port.calls[0]
        assertEquals("package.uninstall_for_user", intentId)
        assertEquals("com.x", params["package"])
        assertTrue(dryRun)
        // 输出是结构化 JSON（port 结果透传）
        val json = Json.parseToJsonElement(out).jsonObject
        assertNotNull(json["ok"])
    }

    @Test
    fun `DeviceOps 缺 intent 时结构化拒绝`() = runTest {
        val tool = DeviceOpsTool(FakePort(), loadCatalog())
        val out = tool.execute("""{"params":{}}""")
        val json = Json.parseToJsonElement(out).jsonObject
        assertEquals("MISSING_ARGUMENT", (json["error"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun `DeviceOps 非法 JSON 时结构化拒绝`() = runTest {
        val tool = DeviceOpsTool(FakePort(), loadCatalog())
        val out = tool.execute("not json")
        val json = Json.parseToJsonElement(out).jsonObject
        assertEquals("INVALID_JSON", (json["error"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun `DeviceOps 描述由 manifest 动态派生 intent 枚举`() {
        val tool = DeviceOpsTool(FakePort(), loadCatalog())
        val catalog = loadCatalog()
        catalog.all().forEach { intent ->
            assertTrue("描述应含 ${intent.id}", tool.description.contains(intent.id))
        }
        assertTrue(tool.description.contains("dry_run"))
        assertTrue(tool.description.contains("confirm_token"))
    }

    @Test
    fun `DeviceOps params 值必须是字符串`() = runTest {
        val tool = DeviceOpsTool(FakePort(), loadCatalog())
        val out = tool.execute("""{"intent":"file.write","params":{"path":"/sdcard/a","content":{"nested":1}}}""")
        val json = Json.parseToJsonElement(out).jsonObject
        assertEquals("INVALID_PARAMS", (json["error"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    // ── AppUninstall / AppForceStop 的 S05 委托 ────────────────────

    @Test
    fun `AppUninstall DCP 启用时委托 port 且参数映射 keep_data`() = runTest {
        val port = FakePort()
        val tool = AppUninstallTool(shellExecutor = { "old-path-not-used" }, deviceOps = port)
        val out = tool.execute("""{"package":"com.example.app","keep_data":true}""")
        val (intentId, params, _) = port.calls[0]
        assertEquals("package.uninstall", intentId)
        assertEquals("com.example.app", params["package"])
        assertEquals("-k", params["keep_data"])
        // 默认结果 ok=true → 输出 Success 形态（旧提示词兼容）
        assertTrue(out.startsWith("Success"))
    }

    @Test
    fun `AppUninstall DCP_DISABLED 时回退旧 shellExec 链`() = runTest {
        val port = FakePort()
        port.result = DeviceOpResult(
            ok = false, intentId = "package.uninstall", channel = "none", uid = null, dryRun = false,
            error = "DCP_DISABLED", hint = "设备控制面未启用"
        )
        var legacyCmd: String? = null
        val tool = AppUninstallTool(shellExecutor = { cmd -> legacyCmd = cmd; "Success" }, deviceOps = port)
        val out = tool.execute("""{"package":"com.example.app"}""")
        assertEquals("pm uninstall com.example.app", legacyCmd)
        assertEquals("Success", out)
    }

    @Test
    fun `AppUninstall 未注入 port 时行为与旧版一致`() = runTest {
        var legacyCmd: String? = null
        val tool = AppUninstallTool(shellExecutor = { cmd -> legacyCmd = cmd; "Success" })
        tool.execute("""{"package":"com.example.app","keep_data":true}""")
        assertEquals("pm uninstall -k com.example.app", legacyCmd)
    }

    @Test
    fun `AppUninstall 非法包名不进任何通道`() = runTest {
        val port = FakePort()
        var legacyCmd: String? = null
        val tool = AppUninstallTool(shellExecutor = { cmd -> legacyCmd = cmd; "ok" }, deviceOps = port)
        val out = tool.execute("""{"package":"com.e;vil"}""")
        assertTrue(out.startsWith("Error:"))
        assertEquals(0, port.calls.size)
        assertEquals(null, legacyCmd)
    }

    @Test
    fun `AppUninstall 委托失败时输出 Error 前缀形态`() = runTest {
        val port = FakePort()
        port.result = DeviceOpResult(
            ok = false, intentId = "package.uninstall", channel = "none", uid = 2000, dryRun = false,
            error = "DEVICE_PERMISSION_REQUIRED", hint = "启动 Shizuku 授权"
        )
        val tool = AppUninstallTool(shellExecutor = { "old" }, deviceOps = port)
        val out = tool.execute("""{"package":"com.example.app"}""")
        assertTrue(out.startsWith("Error: DEVICE_PERMISSION_REQUIRED"))
        assertTrue(out.contains("Shizuku"))
    }

    @Test
    fun `AppForceStop DCP 启用时委托 force_stop 意图`() = runTest {
        val port = FakePort()
        val tool = AppForceStopTool(shellExecutor = { "old-path" }, deviceOps = port)
        tool.execute("""{"package":"com.example.app"}""")
        val (intentId, params, _) = port.calls[0]
        assertEquals("package.force_stop", intentId)
        assertEquals("com.example.app", params["package"])
    }

    // ── DeviceAuditQueryTool（S10）─────────────────────────────────

    private class FakeAuditHost : DeviceAuditQueryTool.Host {
        val entries = listOf(
            entry(1L, "preflight", "package.uninstall", auditId = "dcp-a1"),
            entry(2L, "approved", "package.uninstall", auditId = "dcp-a1"),
            entry(3L, "executed", "package.uninstall", auditId = "dcp-a1", success = true),
            entry(4L, "denied_by_user", "package.clear", auditId = "dcp-b2", success = false)
        )

        override suspend fun query(
            intentId: String?,
            sinceMillis: Long?,
            decision: String?,
            limit: Int
        ): List<DeviceAuditQueryTool.Entry> =
            entries.asSequence()
                .filter { intentId == null || it.intentId == intentId }
                .filter { sinceMillis == null || it.ts >= sinceMillis }
                .filter { decision == null || it.decision == decision }
                .take(limit)
                .toList()

        private fun entry(
            ts: Long, decision: String, intentId: String, auditId: String, success: Boolean? = null
        ) = DeviceAuditQueryTool.Entry(
            ts, decision, intentId, "CRITICAL", "SHIZUKU", 2000,
            decision == "preflight", "abcd1234", false, "重装", auditId, "pm uninstall com.x", success
        )
    }

    @Test
    fun `按 auditId 回放一次完整确认链`() = runTest {
        val tool = DeviceAuditQueryTool(FakeAuditHost())
        val out = tool.execute("""{"auditId":"dcp-a1"}""")
        val json = Json.parseToJsonElement(out).jsonObject
        assertEquals(3, (json["count"] as kotlinx.serialization.json.JsonPrimitive).content.toInt())
        val entries = json["entries"] as kotlinx.serialization.json.JsonArray
        val decisions = entries.map {
            ((it as kotlinx.serialization.json.JsonObject)["decision"] as kotlinx.serialization.json.JsonPrimitive).content
        }
        assertEquals(listOf("preflight", "approved", "executed"), decisions)
    }

    @Test
    fun `按 decision 过滤且 limit 收窄`() = runTest {
        val tool = DeviceAuditQueryTool(FakeAuditHost())
        val out = tool.execute("""{"decision":"denied_by_user","limit":1}""")
        val json = Json.parseToJsonElement(out).jsonObject
        assertEquals(1, (json["count"] as kotlinx.serialization.json.JsonPrimitive).content.toInt())
    }

    @Test
    fun `limit 越界被钳制到 1 到 200`() = runTest {
        val tool = DeviceAuditQueryTool(FakeAuditHost())
        val out = tool.execute("""{"limit":99999}""")
        val json = Json.parseToJsonElement(out).jsonObject
        assertTrue((json["ok"] as kotlinx.serialization.json.JsonPrimitive).content.toBoolean())
    }

    @Test
    fun `空参数与非法 JSON 不崩溃`() = runTest {
        val tool = DeviceAuditQueryTool(FakeAuditHost())
        val empty = tool.execute("")
        assertTrue((Json.parseToJsonElement(empty).jsonObject)["ok"] != null)
        val bad = tool.execute("{{{")
        assertEquals(
            "INVALID_JSON",
            (Json.parseToJsonElement(bad).jsonObject)["error"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }
        )
    }
}
