package com.apex.agent.core.tools.mcp

import com.apex.agent.core.tools.network.BoundedReads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 远端 MCP 传输：`HTTP`（单次 JSON-RPC POST，即 Streamable HTTP 的无流式回退）
 * 与 `SSE`（同端点 POST，服务端可以 `text/event-stream` 分帧回）共用实现。
 *
 * 所有"假成功"必须在这里被掐掉：
 * - 非 2xx → 抛 [McpException]（旧实现把它折叠成 null，
 *   上层再折叠成"已连接、零工具"）；
 * - HTTP 200 但响应体不是 JSON（网关/鉴权拦截页同理处理）；
 * - HTTP 200 但响应体为空或纯空白（Issue #348：网关误路由/健康检查
 *   探测返回空体，旧实现 `?: return@use null` 折叠成假成功）；
 * - 鉴权头缺失会让服务端直接 401 —— 因此 key/headers 一定带上。
 *
 * ## 响应体有界读取（Issue #345）
 *
 * 错误体限 64KB 截断读取、正常响应体限 16MB —— HTTP 传输长期持有
 * 远端服务器响应（resources/read 可返回大块内容），被劫持的 DNS/代理
 * 返回超大响应时无上限读入会直接 OOM。
 *
 * ## Streamable HTTP 会话头（P1 修复）
 *
 * MCP Streamable HTTP 规范：服务端在 initialize 响应里返回
 * `Mcp-Session-Id` 时，客户端**必须**在后续所有请求回带该头。旧实现
 * 从不读取/存储/回带 —— 有状态服务器（官方 TS SDK stateful 模式、
 * FastMCP 网关等）在 initialize 之后的第一条 tools/list 就被 404 拒绝，
 * 表象是「握手成功但零工具」。现在从响应头捕获会话 id，后续请求自动
 * 回带，并同步发送 `MCP-Protocol-Version` 请求头（2024-11-05）。
 *
 * ## 通知的 202 语义（P3 修复）
 *
 * 规范要求服务器对通知（id == null）回 `202 Accepted`（无响应体）。
 * 旧实现对通知也走完整响应体解析 —— 空体触发「非 JSON 响应」异常，
 * 侥幸被 McpClient.sendNotification 的 runCatching 吞掉，错误契约形同
 * 虚设。现在通知只看状态码：2xx 即成功返回 null，不解析响应体。
 */
class McpHttpTransport(
    private val config: McpServerConfig,
    private val httpClient: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : McpTransportHandle {

    /**
     * 服务端分配的会话 id（initialize 响应头 `Mcp-Session-Id` 捕获）。
     * @Volatile：send 并发（tools/list 与 tools/call 并行）时读写可见。
     */
    @Volatile
    private var sessionId: String? = null

    override suspend fun send(id: Int?, payload: String): JsonObject? =
        withContext(Dispatchers.IO) { post(id, payload) }

    override fun isHealthy(): Boolean = true

    /** HTTP 是无状态请求/响应，无连接可关（保持空闲即可）。 */
    override fun close() {
        // 会话 id 随 transport 实例生命周期丢弃；下次 connect 构造新
        // transport，initialize 重新协商新会话。
        sessionId = null
    }

    private fun post(id: Int?, body: String): JsonObject? {
        val builder = Request.Builder()
            .url(config.url)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json, text/event-stream")
            .post(body.toRequestBody("application/json".toMediaType()))

        // P1 修复：Streamable HTTP 会话头 —— initialize 之后的所有请求
        // 回带服务端分配的 Mcp-Session-Id；有状态服务器缺此头即 404。
        sessionId?.let { builder.addHeader(SESSION_ID_HEADER, it) }
        builder.addHeader(PROTOCOL_VERSION_HEADER, PROTOCOL_VERSION)

        config.apiKey?.takeIf { it.isNotBlank() }?.let { key ->
            builder.addHeader("Authorization", "Bearer $key")
        }
        config.headers.forEach { (k, v) ->
            if (k.isNotBlank()) builder.addHeader(k, v)
        }

        return httpClient.newCall(builder.build()).execute().use { response ->
            // 捕获服务端新分配/刷新的会话 id（initialize 响应携带；
            // 服务器也可能在会话续期时更新它）。
            response.header(SESSION_ID_HEADER)?.takeIf { it.isNotBlank() }?.let {
                sessionId = it
            }
            if (!response.isSuccessful) {
                // #345：错误体限 64KB 截断读取（旧 `body?.string()` 无上限，
                // 异常网关返回超大错误页即 OOM）；反正摘要只取前 200 字符。
                val errBody = runCatching {
                    response.body?.byteStream()?.use { BoundedReads.readHead(it, MAX_ERROR_BODY_BYTES) }
                }.getOrNull()
                val snippet = errBody?.toString(charsetOf(response))
                    ?.take(200)?.trim().orEmpty().ifEmpty { response.message }
                throw McpException("HTTP ${response.code}: $snippet")
            }
            // P3 修复：通知（id == null）按规范收 202/204 —— 2xx 即送达，
            // 不解析响应体（空体不是错误）。
            if (id == null) return@use null
            // #348：请求路径（id != null）空体/纯空白 = 网关误路由或服务器
            // 异常，不再折叠成 null（上层会置 initialized=true 装出
            // 「握手成功、零工具」的假成功）。注：OkHttp 对空体返回非 null
            // body（读出空串），body 缺失与空流经 orEmpty 归一后再判 isBlank。
            val responseBody = response.body?.byteStream()?.use { stream ->
                val bytes = BoundedReads.readAtMost(stream, MAX_RESPONSE_BODY_BYTES)
                    ?: throw McpException(
                        "HTTP ${response.code} 响应体超过 " +
                            "${MAX_RESPONSE_BODY_BYTES / 1024 / 1024}MB 上限（不可信远端，拒绝整体读入）"
                    )
                String(bytes, charsetOf(response))
            }.orEmpty()
            if (responseBody.isBlank()) {
                throw McpException(
                    "HTTP ${response.code} 空响应体：远端未返回合法 JSON-RPC 帧" +
                        "（网关误路由或服务器异常）"
                )
            }
            parseBody(responseBody, response.code)
        }
    }

    /**
     * 解析响应体。兼容两种形态：
     * - 裸 JSON-RPC 对象（多数 Streamable HTTP 实现）；
     * - SSE 分帧（`event: message` / `data: {...}`）—— 取出最后一个 `data:` 载荷。
     *
     * #348：任一形态解析不出 JSON-RPC 帧都抛 [McpException]（旧实现返回
     * null，上层折叠成「工具已运行但无输出」的假成功）。
     */
    private fun parseBody(raw: String, code: Int): JsonObject {
        val candidate = raw.trim()
        if (candidate.startsWith("data:")) {
            val last = candidate.lineSequence()
                .filter { it.startsWith("data:") }
                .map { it.removePrefix("data:").trim() }
                .lastOrNull { it.isNotBlank() && it != "[DONE]" }
                ?: throw McpException(
                    "HTTP $code SSE 响应中不含 data 帧（疑似网关拦截页或服务器异常输出）"
                )
            return parseJson(last, code)
        }
        return parseJson(candidate, code)
    }

    private fun parseJson(text: String, code: Int): JsonObject {
        return try {
            json.parseToJsonElement(text) as? JsonObject
                ?: throw McpException("HTTP $code 返回的不是 JSON 对象：${text.take(120)}")
        } catch (e: McpException) {
            throw e
        } catch (e: Exception) {
            throw McpException(
                "HTTP $code 返回非 JSON 响应（疑似网关/鉴权拦截页）：" +
                    text.replace('\n', ' ').trim().take(120)
            )
        }
    }

    /** 响应体字符集（Content-Type 声明优先，缺省 UTF-8 —— JSON-RPC 规范恒 UTF-8）。 */
    private fun charsetOf(response: okhttp3.Response) =
        response.body?.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8

    companion object {
        /** Streamable HTTP 会话头（客户端回带服务端分配的会话 id）。 */
        const val SESSION_ID_HEADER = "Mcp-Session-Id"

        /** 协议版本请求头（规范要求 initialize 之后的所有请求携带）。 */
        const val PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version"

        /** 客户端宣告/协商的协议版本（与 initialize 参数一致）。 */
        const val PROTOCOL_VERSION = "2024-11-05"

        /** 错误体读取上限（截断语义：摘要只取前 200 字符，Issue #345）。 */
        const val MAX_ERROR_BODY_BYTES = 64 * 1024

        /** 正常响应体上限（16MB：resources/read 可返回大块内容，Issue #345）。 */
        const val MAX_RESPONSE_BODY_BYTES = 16 * 1024 * 1024
    }
}
