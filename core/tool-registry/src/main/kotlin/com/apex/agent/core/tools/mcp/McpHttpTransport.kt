package com.apex.agent.core.tools.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
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
 * - HTTP 200 空响应体（#348：网关/健康检查误路由返回空体时，表象正是
 *   本类声称已消灭的"握手成功、零工具"——带 id 的请求路径空体永远
 *   是协议错误，直接抛 McpException，不再折叠成 null）；
 * - 鉴权头缺失会让服务端直接 401 —— 因此 key/headers 一定带上；
 * - 响应体一律有界读取（#345：16MB 上限，超限抛错——被劫持链路回
 *   超大响应不再无上限 string() 整体读入 OOM）。
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
                // #345：错误体同样有界读取——被劫持的 DNS/代理或异常网关
                // 回超大错误页时不再无上限整体读入（OOM 防御与市场源对齐）。
                // 读取超限抛的 McpException 被 runCatching 折叠，状态码仍是主文案。
                val errBody = runCatching { readBodyLimited(response) }.getOrNull()
                val snippet = errBody?.take(200)?.trim().orEmpty().ifEmpty { response.message }
                throw McpException("HTTP ${response.code}: $snippet")
            }
            // P3 修复：通知（id == null）按规范收 202/204 —— 2xx 即送达，
            // 不解析响应体（空体不是错误）。
            if (id == null) return@use null
            // #348：请求路径（带 id）的空响应体是协议错误，不再折叠成 null
            //（旧实现恰好漏过本类 KDoc 承诺要掐掉的"握手成功、零工具"假成功：
            // McpClient 对 null 响应只能靠逐调用点自查，这里直接掐断源头）。
            // 超限（#345）与空体都在这里以 McpException 上抛，上层折叠为
            // Result.failure 并附重连/换路指引。
            val responseBody = readBodyLimited(response)
            if (responseBody.isBlank()) {
                throw McpException("HTTP ${response.code} 空响应体（期望 JSON-RPC 响应）")
            }
            parseBody(responseBody, response.code, id)
        }
    }

    /**
     * 解析响应体。兼容两种形态：
     * - 裸 JSON-RPC 对象（多数 Streamable HTTP 实现）；
     * - SSE 分帧：规范服务器会把每个事件按 `event:` / `id:` / `data:` 字段
     *   分帧（首帧常是 `event: endpoint`，中间可能夹通知，心跳是 `: ping`）。
     *
     * P1 修复：旧实现只认"整个 body 以 data: 开头"，于是规范的 SSE 端点必然
     * 落到"疑似网关/鉴权拦截页"分支；而且只取**最后一条** data，响应后面跟一条
     * 通知就会取错帧。现在按行分帧、逐条解析，并按 JSON-RPC id 选中属于本次
     * 请求的那一条（没有 id 可匹配时回退最后一条可解析对象）。
     */
    private fun parseBody(raw: String, code: Int, id: Int? = null): JsonObject? {
        val candidate = raw.trim()
        if (candidate.isEmpty()) return null
        val lines = candidate.lineSequence().map { it.trimEnd('\r') }.toList()
        val looksLikeSse = lines.any { it.startsWith("data:") } ||
            candidate.startsWith("event:") || candidate.startsWith(":")
        if (!looksLikeSse) return parseJson(candidate, code)

        val payloads = lines.asSequence()
            .filter { it.startsWith("data:") }
            .map { it.removePrefix("data:").trim() }
            .filter { it.isNotBlank() && it != "[DONE]" }
            .toList()
        if (payloads.isEmpty()) return null

        var fallback: JsonObject? = null
        for (payload in payloads) {
            val parsed = runCatching { parseJson(payload, code) }.getOrNull() ?: continue
            if (id == null) return parsed
            val parsedId = parsed["id"]?.jsonPrimitive?.intOrNull
            if (parsedId == id) return parsed
            fallback = parsed
        }
        return fallback
    }

    private fun parseJson(text: String, code: Int): JsonObject? {
        return try {
            val element = json.parseToJsonElement(text)
            element as? JsonObject
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

    /**
     * 有界读取响应体（#345）：流式读至多 [MAX_BODY_BYTES] 字节，超限抛
     * [McpException]（被劫持的 DNS/代理或异常网关返回超大响应不再无上限
     * `string()` 整体读入）。body 缺失返回空串。
     *
     * 上限比市场源的 2MB JSON 口径宽：HTTP 传输长期持有远端服务器响应，
     * resources/read 可返回大块内容，因此放宽到 16MB 但必须有界。
     */
    private fun readBodyLimited(response: okhttp3.Response): String {
        val body = response.body ?: return ""
        val bytes = body.byteStream().use { stream ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var total = 0
            while (total < MAX_BODY_BYTES) {
                val n = stream.read(buf, 0, minOf(buf.size, MAX_BODY_BYTES - total))
                if (n < 0) break
                out.write(buf, 0, n)
                total += n
            }
            if (total >= MAX_BODY_BYTES && stream.read() >= 0) {
                throw McpException(
                    "HTTP 响应体超过 ${MAX_BODY_BYTES / 1024 / 1024}MB 上限（疑似异常网关/被劫持链路）"
                )
            }
            out.toByteArray()
        }
        return String(bytes, Charsets.UTF_8)
    }

    companion object {
        /** Streamable HTTP 会话头（客户端回带服务端分配的会话 id）。 */
        const val SESSION_ID_HEADER = "Mcp-Session-Id"

        /** 协议版本请求头（规范要求 initialize 之后的所有请求携带）。 */
        const val PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version"

        /** 客户端宣告/协商的协议版本（与 initialize 参数一致）。 */
        const val PROTOCOL_VERSION = "2024-11-05"

        /**
         * 响应体字节上限（#345）：MCP 请求/响应可能携带大块 content
         * （resources/read），放宽到 16MB 但必须有界。
         */
        private const val MAX_BODY_BYTES = 16 * 1024 * 1024
    }
}
