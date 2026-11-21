package com.apex.agent.core.tools.mcp

import com.apex.agent.core.tools.builtin.McpConnectTool
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * #344：McpConnect 重连闭环回归锁。
 *
 * 矛盾现场：McpCall 失败提示模型 "If the server disconnected, call
 * McpConnect('$server') once and retry"，但旧实现 mcp_connect 对已存在
 * 同名配置一概拒绝（"already exists... Choose a different name"）——
 * Supervisor 放弃重连/进程掉线后，模型按指引操作必然碰壁，自愈闭环断裂。
 *
 * 锁定的三支新语义：
 * 1. 纯重连（只给 name，其余连接参数全空）→ 跳过 url/command 必填校验与
 *    addServer，直接对既有存储配置 connect；
 * 2. 同名 + 参数一致 → 幂等重连（不重写用户配置，scope 等市场字段保留）；
 * 3. 同名 + 参数冲突 → 拒绝（P3 同名覆盖保护不回归），错误文案给出
 *    纯重连用法指引。
 *
 * 既有语义不回归：未知服务器 name-only 仍要求 url（参数校验）、新名称 +
 * url 正常添加并连接。
 *
 * 纯 JVM：MockWebServer 充当真实远端 MCP 端点（initialize 握手 + 通知
 * 202），McpManager 落盘到临时目录 —— 无 mock 框架，全链路真实。
 */
class McpConnectToolReconnectTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var manager: McpManager
    private lateinit var tool: McpConnectTool

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = EchoingMcpDispatcher()
        server.start()
        manager = McpManager(configDir = tmp.newFolder())
        tool = McpConnectTool(manager)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ── 分支 1：纯重连（#347 矛盾的主线场景）──────────────────────────

    @Test
    fun `纯重连只给 name 不再被 already exists 拒绝`() = runTest {
        seedServer("github", scope = "agent")

        val result = tool.execute("""{"name": "github"}""")

        // 旧实现在这里返回 "Error: ... already exists ... Choose a different name"
        assertFalse("纯重连不得被同名检查拒绝: $result", result.startsWith("Error"))
        assertTrue(
            "应明确标注重连语义: $result",
            result.contains("Reconnected")
        )
        assertTrue(
            "应声明未改写配置: $result",
            result.contains("existing configuration")
        )
    }

    @Test
    fun `纯重连不重写既有配置`() = runTest {
        // scope 是市场/UI 写入的字段（模型经 McpConnect 永远不感知）——
        // 若重连路径误走 addServer，会被默认值 "all" 抹掉
        seedServer("github", scope = "agent")

        tool.execute("""{"name": "github"}""")

        val stored = manager.getConfigs().single { it.name == "github" }
        assertEquals("重连不得重写用户配置的市场字段", "agent", stored.scope)
        assertEquals("重连不得改写 url", server.url("/mcp").toString(), stored.url)
    }

    // ── 分支 2：同名 + 参数一致 → 幂等重连 ─────────────────────────────

    @Test
    fun `同名且参数一致时幂等重连而非拒绝`() = runTest {
        seedServer("github", scope = "agent")
        val url = server.url("/mcp").toString()

        val result = tool.execute("""{"name": "github", "url": "$url"}""")

        assertFalse("参数一致必须放行（幂等重连）: $result", result.startsWith("Error"))
        assertTrue(result.contains("Reconnected"))
        // 一致路径同样不重写配置
        assertEquals("agent", manager.getConfigs().single { it.name == "github" }.scope)
    }

    // ── 分支 3：同名 + 参数冲突 → 拒绝并给出重连指引 ──────────────────

    @Test
    fun `同名但参数冲突仍被拒绝且指引纯重连用法`() = runTest {
        seedServer("github", scope = "agent")

        val result = tool.execute(
            """{"name": "github", "url": "http://elsewhere.example/mcp"}"""
        )

        assertTrue("参数冲突必须拒绝（防无声改写用户配置）", result.startsWith("Error"))
        assertTrue(
            "应说明是配置不一致: $result",
            result.contains("different settings")
        )
        assertTrue(
            "必须给出纯重连指引（自愈闭环的入口）: $result",
            result.contains("McpConnect('github') with no url/command")
        )
        // 用户配置原样保留
        assertEquals(
            server.url("/mcp").toString(),
            manager.getConfigs().single { it.name == "github" }.url
        )
    }

    // ── 既有语义不回归 ────────────────────────────────────────────────

    @Test
    fun `未知服务器 name-only 仍要求 url`() = runTest {
        val result = tool.execute("""{"name": "ghost"}""")

        // 参数校验行为不变：未知服务器必须给出可行动错误，而不是凭空连接
        assertTrue(result.startsWith("Error"))
        assertTrue(
            "应要求 url: $result",
            result.contains("'url' required")
        )
    }

    @Test
    fun `新名称加 url 正常添加并连接`() = runTest {
        val url = server.url("/mcp").toString()

        val result = tool.execute("""{"name": "fresh", "url": "$url"}""")

        assertFalse("新服务器必须正常连接: $result", result.startsWith("Error"))
        assertTrue(
            "应标注全新连接（非重连）: $result",
            result.contains("Connected to MCP tool source 'fresh'") &&
                !result.contains("Reconnected")
        )
        assertTrue(
            "配置必须落盘",
            manager.getConfigs().any { it.name == "fresh" }
        )
        assertTrue(
            "连接必须真实注册（握手完成）",
            manager.getConnectedServers().contains("fresh")
        )
    }

    // ── 辅助 ─────────────────────────────────────────────────────────

    /** 预置一台已启用的 HTTP MCP 服务器（等价用户经市场安装后的状态）。 */
    private suspend fun seedServer(name: String, scope: String) {
        manager.addServer(
            McpServerConfig(
                name = name,
                url = server.url("/mcp").toString(),
                transport = McpTransport.HTTP,
                enabled = true,
                scope = scope
            )
        )
    }

    /**
     * 回显 id 的最小 MCP JSON-RPC 端点：initialize → 带能力集的 result；
     * 通知 → 202；其余请求 → 空 result。McpClient.sendRequest 会校验
     * 响应 id 与请求 id 一致，因此必须按请求体回显。
     */
    private class EchoingMcpDispatcher : Dispatcher() {

        override fun dispatch(request: RecordedRequest): MockResponse {
            val body = request.body.readUtf8()
            val id = "\"id\":(\\d+)".toRegex().find(body)?.groupValues?.get(1) ?: "1"
            return when {
                body.contains("\"initialize\"") -> {
                    MockResponse().setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                            """{"jsonrpc":"2.0","id":$id,"result":{""" +
                                """"protocolVersion":"2024-11-05","capabilities":{""" +
                                """"tools":{}},"serverInfo":{"name":"mock","version":"1.0"}}}"""
                        )
                }
                body.contains("notifications/initialized") ->
                    MockResponse().setResponseCode(202)
                else ->
                    MockResponse().setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"jsonrpc":"2.0","id":$id,"result":{}}""")
            }
        }
    }
}
