package com.apex.agent.mcp.builtin.memory

import com.apex.memkit.core.MemKit
import com.apex.memkit.core.MemKitConfig
import com.apex.memkit.core.store.FileStorageBackend
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #150 迁移版 —— 内置 Memory MCP transport 单测（纯 JVM）。
 *
 * 拆库（apex-kit-memory）后图谱语义住在库 [MemKit]（库内「图谱 MCP 语义」
 * 用例覆盖合并/去重/级联删除）；宿主侧只测 **JSON-RPC 适配层**：
 * tools/call 派发、参数解码、结果渲染、错误折叠（不再让进程内异常击穿
 * JSON-RPC 层）。存储走真实 FileStorageBackend（临时目录）。
 *
 * 协程纪律（issue #276）：runTest 虚拟时间 —— MemKit.create 仅同步 store.load()
 * （写管道须显式 start() 才启动，本测试不涉及），与 apex-kit-memory 库内
 * MemKitFacadeTest 同款 runTest 口径，无墙钟依赖。
 */
class KnowledgeGraphStoreTest {

    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun transport(): Pair<BuiltinMemoryMcpTransport, MemKit> {
        val kit = MemKit.create(
            config = MemKitConfig(),
            storage = FileStorageBackend(Files.createTempDirectory("mcp-memory-test").toFile()),
        )
        return BuiltinMemoryMcpTransport(kit.store) to kit
    }

    private suspend fun call(
        transport: BuiltinMemoryMcpTransport,
        tool: String,
        argsJson: String = "{}",
    ): String {
        val payload = """{"jsonrpc":"2.0","id":1,"method":"tools/call",
            "params":{"name":"$tool","arguments":$argsJson}}""".trimIndent().replace("\n", "")
        val response = transport.send(1, payload)!!
        val result = response["result"]!!.jsonObject
        assertFalse("工具不应报错：$result", "isError" in result)
        return result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
    }

    @Test
    fun `tools list advertises six memory tools`(): Unit = runTest {
        val (transport, _) = transport()
        val response = transport.send(1, """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")!!
        val tools = response["result"]!!.jsonObject["tools"]!!.jsonArray
        assertEquals(6, tools.size)
        assertTrue(tools.toString().contains("create_entities"))
    }

    @Test
    fun `create then read graph returns compact json snapshot`(): Unit = runTest {
        val (transport, _) = transport()
        val created = call(
            transport, "create_entities",
            """{"entities":[{"name":"apex","entityType":"project","observations":["Android 端 Agent 应用"]}]}""",
        )
        assertTrue(created.contains("apex"))

        val graph = call(transport, "read_graph")
        val parsed = json.parseToJsonElement(graph).jsonObject
        assertEquals(
            "apex",
            parsed["entities"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `add observations on unknown entity folds into tool error text`(): Unit = runTest {
        val (transport, _) = transport()
        call(transport, "create_entities", """{"entities":[{"name":"小明","entityType":"person"}]}""")
        call(transport, "add_observations", """{"entityName":"小明","contents":["程序员"]}""")

        // 未知实体：MemoryToolException → isError 文本（不抛出、不崩 JSON-RPC）
        val payload = """{"jsonrpc":"2.0","id":1,"method":"tools/call",
            "params":{"name":"add_observations","arguments":{"entityName":"不存在","contents":["x"]}}}"""
            .trimIndent().replace("\n", "")
        val response = transport.send(1, payload)!!
        val result = response["result"]!!.jsonObject
        assertTrue("isError" in result)
        assertTrue(result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content.contains("不存在"))
    }

    @Test
    fun `search nodes matches and delete cascades`(): Unit = runTest {
        val (transport, _) = transport()
        call(transport, "create_entities", """{"entities":[{"name":"小明","entityType":"person"}]}""")
        call(transport, "create_relations", """{"relations":[{"from":"小明","to":"项目A","relationType":"参与"}]}""")
        // create_relations 引用未建实体不级联建 —— 官方语义允许悬挂关系

        call(transport, "create_entities", """{"entities":[{"name":"项目A","entityType":"project"}]}""")
        val hit = call(transport, "search_nodes", """{"query":"小明"}""")
        assertTrue(hit.contains("小明"))

        call(transport, "delete_entities", """{"entityNames":["小明"]}""")
        val after = json.parseToJsonElement(call(transport, "read_graph")).jsonObject
        assertEquals(1, after["entities"]!!.jsonArray.size)
        // 级联删边：小明参与项目A 的关系随实体删除
        assertFalse(after["relations"]!!.jsonArray.toString().contains("参与"))
    }

    @Test
    fun `unknown tool name folds into error text not exception`(): Unit = runTest {
        val (transport, _) = transport()
        val payload = """{"jsonrpc":"2.0","id":1,"method":"tools/call",
            "params":{"name":"no_such_tool","arguments":{}}}""".trimIndent().replace("\n", "")
        val response = transport.send(1, payload)!!
        val result = response["result"]!!.jsonObject
        assertTrue("未知工具必须是 isError 文本", "isError" in result)
        val text = result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(text.contains("未知工具"))
    }

    @Test
    fun `malformed rpc payload throws mcp exception`(): Unit = runTest {
        val (transport, _) = transport()
        var threw = false
        try {
            transport.send(1, "not-json{{{")
        } catch (e: com.apex.agent.core.tools.mcp.McpException) {
            threw = true
        }
        assertTrue(threw)
    }
}
