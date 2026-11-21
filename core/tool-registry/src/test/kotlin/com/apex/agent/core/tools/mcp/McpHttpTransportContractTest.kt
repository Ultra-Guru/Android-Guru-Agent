package com.apex.agent.core.tools.mcp

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * #348 / #345：McpHttpTransport 错误契约回归锁。
 *
 * 类 KDoc 承诺「所有假成功必须在这里被掐掉」，但两处恰好漏网：
 * 1. #348 —— HTTP 200 空响应体：旧实现 `body?.string() ?: return null`
 *    把空体折叠成 null 上抛，表象正是该类声称已消灭的「握手成功、零
 *    工具」（网关/健康检查误路由返回空体时零防御）。现在带 id 的请求
 *    路径空体必须抛 [McpException]；
 * 2. #345 —— 响应体无上限整体读入：被劫持的 DNS/代理或异常网关返回
 *    超大响应直接 OOM。现在 16MB 有界读取，超限抛 [McpException]。
 *
 * 同时锁定既有语义不回归：非 2xx 带错误片段、通知（id == null）收
 * 202 空体不报错（P3 修复行为）、正常 JSON-RPC 响应照常解析。
 *
 * 纯 JVM + MockWebServer（仓库无 mock 框架纪律下的既有模式，
 * 与 AppDownloadInstallToolTest 同构）。
 */
class McpHttpTransportContractTest {

    private lateinit var server: MockWebServer
    private lateinit var transport: McpHttpTransport

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val config = McpServerConfig(
            name = "mock-remote",
            url = server.url("/mcp").toString(),
            transport = McpTransport.HTTP,
            enabled = true
        )
        transport = McpHttpTransport(config, OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ── #348：空响应体必须是协议错误 ──────────────────────────────────

    @Test
    fun `HTTP 200 完全空体在请求路径抛 McpException`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(""))

        try {
            transport.send(id = 1, payload = rpcRequest(1, "tools/list"))
            fail("空体 200 必须抛 McpException，不得折叠成 null 假成功")
        } catch (e: McpException) {
            assertTrue(
                "报错应指明空响应体: ${e.message}",
                e.message.orEmpty().contains("空响应体")
            )
        }
    }

    @Test
    fun `HTTP 200 空白体在请求路径抛 McpException`() = runTest {
        // 网关偶发回换行/空格 —— 与空体同罪（blank 折叠）
        server.enqueue(MockResponse().setResponseCode(200).setBody("  \n\r\n"))

        try {
            transport.send(id = 1, payload = rpcRequest(1, "tools/list"))
            fail("空白体 200 必须抛 McpException")
        } catch (e: McpException) {
            assertTrue(e.message.orEmpty().contains("空响应体"))
        }
    }

    @Test
    fun `通知路径 202 空体仍是无错误`() = runTest {
        // P3 修复行为不回归：通知（id == null）只看状态码，2xx 即送达
        server.enqueue(MockResponse().setResponseCode(202))

        val result = transport.send(id = null, payload = """{"jsonrpc":"2.0","method":"notifications/initialized"}""")

        assertNull("通知 202 空体必须返回 null（送达、无响应可解析）", result)
    }

    // ── #345：响应体必须有界 ──────────────────────────────────────────

    @Test
    fun `超过 16MB 上限的响应体抛 McpException`() = runTest {
        // 16MB 上限 + 1 字节越界（本地回环传输，纯内存，无磁盘）
        val huge = okio.Buffer().write(ByteArray(16 * 1024 * 1024 + 1))
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(huge)
        )

        try {
            transport.send(id = 1, payload = rpcRequest(1, "tools/list"))
            fail("超大响应体必须抛 McpException（OOM 防御），不得无上限读入")
        } catch (e: McpException) {
            assertTrue(
                "报错应指明上限: ${e.message}",
                e.message.orEmpty().contains("上限")
            )
        }
    }

    @Test
    fun `错误体同样有界不改变错误主文案`() = runTest {
        // 非 2xx：状态码仍是主文案（错误体有界读取失败也不吞掉 HTTP 状态）
        server.enqueue(
            MockResponse().setResponseCode(502)
                .setBody("Bad Gateway from hijacked proxy")
        )

        try {
            transport.send(id = 1, payload = rpcRequest(1, "tools/list"))
            fail("非 2xx 必须抛 McpException")
        } catch (e: McpException) {
            assertTrue(
                "状态码必须是主文案: ${e.message}",
                e.message.orEmpty().contains("HTTP 502")
            )
            assertTrue(
                "错误体片段应保留（有界读取的前 200 字符）: ${e.message}",
                e.message.orEmpty().contains("Bad Gateway")
            )
        }
    }

    // ── 既有语义不回归 ────────────────────────────────────────────────

    @Test
    fun `正常 JSON-RPC 响应照常解析`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"jsonrpc":"2.0","id":1,"result":{"tools":[]}}""")
        )

        val result = transport.send(id = 1, payload = rpcRequest(1, "tools/list"))

        assertNotNull("正常响应必须解析出对象", result)
        assertEquals(1, result!!["id"]?.jsonPrimitive?.intOrNull)
    }

    private fun rpcRequest(id: Int, method: String): String =
        """{"jsonrpc":"2.0","id":$id,"method":"$method","params":{}}"""
}
