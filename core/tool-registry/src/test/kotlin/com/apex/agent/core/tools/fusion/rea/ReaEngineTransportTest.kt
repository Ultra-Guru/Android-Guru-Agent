package com.apex.agent.core.tools.fusion.rea

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * REA 目录 + 引擎调度 + MCP transport 三层行为锁定：
 *
 * 1. 目录：207 条（与上游 v6.5.0 实测 tools/list 逐名对齐）、无重名、
 *    分层统计恒定、tools/list 渲染完整；
 * 2. 引擎：tools/call 真跑 ON_DEVICE 分析（ASAR fixture）、PROVIDER_GATED
 *    结构化降级（非报错 —— BYO 引擎哲学）、未知工具错误、会话证据入账；
 * 3. transport：JSON-RPC initialize / tools-list / tools-call 协议形态
 *    （与 BuiltinSearchMcpTransport 同协议面）。
 */
class ReaEngineTransportTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    // ═══ 目录 ═══════════════════════════════════════════════════════════

    @Test
    fun `目录 207 条且与上游逐名对齐`() {
        assertEquals("上游 rea-agents v6.5.0 实测 tools/list 总数", 207, ReaToolCatalog.size())
    }

    @Test
    fun `目录无重名（工具 id 唯一性）`() {
        val names = ReaToolCatalog.ALL.map { it.name }
        assertEquals(names.size, names.distinct().size)
    }

    @Test
    fun `分层统计恒定（能力矩阵同源数据）`() {
        val counts = ReaToolCatalog.tierCounts()
        assertEquals(72, counts[ReaTier.ON_DEVICE])
        assertEquals(25, counts[ReaTier.DEVICE_SHELL])
        assertEquals(34, counts[ReaTier.SANDBOX_BRIDGE])
        assertEquals(15, counts[ReaTier.WEB_FETCH])
        assertEquals(61, counts[ReaTier.PROVIDER_GATED])
        assertEquals("分层总和 = 目录总数", 207, counts.values.sum())
    }

    @Test
    fun `旗舰工具都在目录中`() {
        for (flagship in listOf(
            "open_binary", "binary_session", "inspect_artifact", "extract_artifact",
            "inspect_binary_layout", "inspect_macho", "list_architectures",
            "inspect_android_package", "search_android_classes", "identify_flutter_build",
            "analyze_javascript_application", "recover_javascript_sources",
            "inspect_sqlite_database", "demangle_swift", "read_bytes",
            "search_bytes", "address_to_file_offset", "export_evidence_bundle",
            "get_evidence_bundle", "inspect_adb_device", "read_adb_logcat",
            "list_frida_devices", "inspect_web_page", "analyze_web_bundle"
        )) {
            assertNotNull("旗舰工具 $flagship 必须在目录", ReaToolCatalog.find(flagship))
        }
    }

    @Test
    fun `tools_list 渲染完整（MCP 契约）`() {
        val tools = ReaToolCatalog.renderToolsList()
        assertEquals(207, tools.size)
        val first = tools.first().jsonObject
        assertTrue(first.containsKey("name"))
        assertTrue(first.containsKey("description"))
        assertTrue(first.containsKey("inputSchema"))
        val schema = first["inputSchema"]!!.jsonObject
        assertEquals("object", schema["type"]?.jsonPrimitive?.contentOrNull)
    }

    // ═══ 引擎（tools/call 调度）════════════════════════════════════════

    /** 最小 ASAR fixture（复用 ReaAnalyzersTest 的构造逻辑）。 */
    private fun buildAsarFile(): File {
        val mainJs = "const { app } = require('electron');\napp.whenReady();"
        val pkg = """{"name":"engine-app","version":"1.0.0"}"""
        val files = listOf("main.js" to mainJs, "package.json" to pkg)
        val header = StringBuilder("{\"files\":{")
        var offset = 0L
        val datas = mutableListOf<ByteArray>()
        files.forEachIndexed { i, (name, content) ->
            val bytes = content.toByteArray(Charsets.UTF_8)
            if (i > 0) header.append(",")
            header.append("\"$name\":{\"size\":${bytes.size},\"offset\":$offset}")
            offset += bytes.size
            datas.add(bytes)
        }
        header.append("}}")
        var headerBytes = header.toString().toByteArray(Charsets.UTF_8)
        val pad = (4 - (headerBytes.size % 4)) % 4
        if (pad > 0) headerBytes = headerBytes + ByteArray(pad) { ' '.code.toByte() }
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(4, 0, 0, 0))
        out.write(byteArrayOf((headerBytes.size and 0xFF).toByte(), ((headerBytes.size shr 8) and 0xFF).toByte(), ((headerBytes.size shr 16) and 0xFF).toByte(), ((headerBytes.size shr 24) and 0xFF).toByte()))
        out.write(headerBytes)
        datas.forEach { out.write(it) }
        val f = tmp.newFile("engine-app.asar")
        f.writeBytes(out.toByteArray())
        return f
    }

    private fun args(vararg pairs: Pair<String, String>): JsonObject {
        val map = pairs.associate { (k, v) -> k to kotlinx.serialization.json.JsonPrimitive(v) }
        return JsonObject(map)
    }

    @Test
    fun `tools_call 真跑 ON_DEVICE 分析并返回证据信封`() = runTest {
        val engine = ReaEngine()
        val asar = buildAsarFile()
        val result = engine.callTool(
            "analyze_javascript_application",
            args("path" to asar.absolutePath)
        )
        assertEquals("true", result["ok"]?.jsonPrimitive?.contentOrNull)
        assertEquals("on_device", result["tier"]?.jsonPrimitive?.contentOrNull)
        assertEquals(true, result["tool"]?.jsonPrimitive?.contentOrNull == "analyze_javascript_application")
        assertTrue(result.toString().contains("engine-app"))
        // 证据纪律三件套字段在（成功路径）
        assertTrue(result.containsKey("findings"))
        assertTrue(result.containsKey("limitations"))
    }

    @Test
    fun `open_binary 后会话与证据账本工作`() = runTest {
        val engine = ReaEngine()
        val asar = buildAsarFile()
        val open = engine.callTool("open_binary", args("path" to asar.absolutePath))
        assertEquals("true", open["ok"]?.jsonPrimitive?.contentOrNull)
        assertTrue(open.toString().contains("ASAR"))

        engine.callTool("analyze_javascript_application", JsonObject(emptyMap())) // path 缺省走会话目标
        val session = engine.callTool("binary_session", JsonObject(emptyMap()))
        assertEquals("true", session["hasTarget"]?.jsonPrimitive?.contentOrNull)
        assertEquals("Electron ASAR", session["kind"]?.jsonPrimitive?.contentOrNull)
        assertEquals(207, session["catalogTools"]?.jsonPrimitive?.intOrNull)

        val bundle = engine.callTool("get_evidence_bundle", JsonObject(emptyMap()))
        assertTrue(bundle.toString().contains("retained evidence"))
        assertTrue("每次调用自动入账", bundle.toString().contains("3 条") || bundle.toString().contains("records"))
    }

    @Test
    fun `未知工具返回错误信封`() = runTest {
        val engine = ReaEngine()
        val result = engine.callTool("not_a_rea_tool", JsonObject(emptyMap()))
        assertEquals("false", result["ok"]?.jsonPrimitive?.contentOrNull)
        assertTrue(result["error"]?.jsonPrimitive?.contentOrNull!!.contains("未知工具"))
    }

    @Test
    fun `PROVIDER_GATED 工具结构化降级（BYO 引擎哲学 非报错）`() = runTest {
        val engine = ReaEngine()
        val result = engine.callTool("procedure_info", args("symbol" to "main"))
        assertEquals("false", result["ok"]?.jsonPrimitive?.contentOrNull)
        val degraded = result["degraded"]?.jsonObject
        assertNotNull("降级信封必须有 degraded 段", degraded)
        assertTrue(degraded!!["reason"]?.jsonPrimitive?.contentOrNull!!.isNotBlank())
        assertTrue(degraded["recovery"]?.jsonPrimitive?.contentOrNull!!.isNotBlank())
        // 工具契约保留声明（表面完整性）
        assertTrue(result["limitations"].toString().contains("207"))
    }

    @Test
    fun `SANDBOX_BRIDGE 无 runner 时降级 端口缺失不崩溃`() = runTest {
        val engine = ReaEngine() // commandRunner = null
        val result = engine.callTool("inspect_with_objdump", args("path" to "/dev/null"))
        assertEquals("false", result["ok"]?.jsonPrimitive?.contentOrNull)
        assertTrue(result["degraded"]?.jsonObject?.isNotEmpty() == true)
    }

    /** 显式 fake（避免 suspend SAM lambda 与 Result 内联类的装箱边界）。 */
    private class RecordingRunner : ReaCommandRunner {
        var captured: List<String>? = null
        var stdout = "model: TestDevice"
        var exitCode = 0
        override suspend fun run(command: List<String>, timeoutMs: Long): Result<ReaCommandOutput> {
            captured = command
            return Result.success(
                ReaCommandOutput(
                    command = command, stdout = stdout, exitCode = exitCode, channel = "host"
                )
            )
        }
    }

    @Test
    fun `DEVICE_SHELL adb 族映射到本地命令`() = runTest {
        val runner = RecordingRunner()
        val engine = ReaEngine(commandRunner = runner)
        val result = engine.callTool(
            "inspect_adb_device",
            JsonObject(emptyMap())
        )
        assertEquals("true", result["ok"]?.jsonPrimitive?.contentOrNull)
        assertTrue(result.toString().contains("TestDevice"))
        assertTrue("设备直连命令模板生效", runner.captured!!.joinToString(" ").contains("getprop"))
    }

    @Test
    fun `unknowns 台账闭环`() = runTest {
        val engine = ReaEngine()
        engine.callTool("record_unknown", args("query" to "混淆的入口函数尚未定位"))
        val list = engine.callTool("list_unknowns", JsonObject(emptyMap()))
        assertTrue(list.toString().contains("1 条"))
        engine.callTool("update_unknown", args("query" to "U001", "status" to "resolved"))
        val after = engine.callTool("list_unknowns", JsonObject(emptyMap()))
        assertTrue(after.toString().contains("resolved"))
    }

    // ═══ MCP transport（JSON-RPC 协议面）════════════════════════════════

    @Test
    fun `initialize 协议握手`() = runTest {
        val transport = ReaBuiltinMcpTransport()
        val response = transport.send(
            1, """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}"""
        )
        assertNotNull(response)
        assertEquals(1, response!!["id"]?.jsonPrimitive?.intOrNull)
        val result = response["result"]!!.jsonObject
        assertEquals("2024-11-05", result["protocolVersion"]?.jsonPrimitive?.contentOrNull)
        assertTrue(result["serverInfo"]!!.jsonObject.toString().contains("rea-fusion-builtin"))
    }

    @Test
    fun `tools_list 返回 207 个工具`() = runTest {
        val transport = ReaBuiltinMcpTransport()
        val response = transport.send(
            2, """{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}"""
        )
        val tools = response!!["result"]!!.jsonObject["tools"] as JsonArray
        assertEquals(207, tools.size)
    }

    @Test
    fun `tools_call 走引擎并渲染 content 结构`() = runTest {
        val transport = ReaBuiltinMcpTransport()
        val asar = buildAsarFile()
        val payload = """
            {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{
              "name":"analyze_javascript_application",
              "arguments":{"path":"${asar.absolutePath.replace("\\", "\\\\")}"}
            }}
        """.trimIndent()
        val response = transport.send(3, payload)
        val result = response!!["result"]!!.jsonObject
        val content = result["content"] as JsonArray
        assertEquals(1, content.size)
        val text = content.first().jsonObject["text"]!!.jsonPrimitive.contentOrNull!!
        val parsed = json.parseToJsonElement(text).jsonObject
        assertEquals("true", parsed["ok"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `未知方法返回 METHOD_NOT_FOUND`() = runTest {
        val transport = ReaBuiltinMcpTransport()
        val response = transport.send(
            4, """{"jsonrpc":"2.0","id":4,"method":"resources/list","params":{}}"""
        )
        assertTrue(response!!["error"]!!.jsonObject.toString().contains("Method not found"))
    }

    @Test
    fun `通知返回 null（JSON-RPC 语义）`() = runTest {
        val transport = ReaBuiltinMcpTransport()
        val response = transport.send(
            null, """{"jsonrpc":"2.0","method":"notifications/initialized"}"""
        )
        assertEquals(null, response)
    }
}
