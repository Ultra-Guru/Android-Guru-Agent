package com.apex.agent.searxng

import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.platform.terminal.searxng.SearxngContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * 本地 SearXNG 实例的 HTTP 客户端（host 侧 OkHttp —— PRoot 与宿主共享网络栈，
 * guest 127.0.0.1:8888 直达）。
 *
 * 两类调用（Spec §9/§10）：
 *  - [healthz]：GET /healthz，200 = 健康（超时 3s —— N2 单次健康检查预算）；
 *  - [search]：GET /search?q=…&format=json，解析 results[] → [SearxngJsonResult]。
 *    非本地实例专用客户端（独立短超时 OkHttpClient：本地回环连接快，5s 足够
 *    覆盖引擎聚合的 P95 —— Spec §10 请求超时 5s）。
 */
class SearxngHttpClient(
    private val port: () -> Int,
    /** 专用 client（本地回环短超时 —— 不复用远程搜索的 15s/30s 配置）。 */
    private val client: OkHttpClient = defaultClient()
) {

    /** healthz 探测（true = 200；异常/超时 = false —— 供 watchdog 计数）。 */
    suspend fun healthz(timeoutMs: Long = SearxngContract.HEALTH_CHECK_TIMEOUT_MS): Boolean =
        try {
            withTimeout(timeoutMs) {
                val request = Request.Builder()
                    .url("http://${SearxngContract.BIND_ADDRESS}:${port()}${SearxngContract.HEALTHZ_PATH}")
                    .get()
                    .build()
                val code = client.newCall(request).await().use { it.code }
                code == 200
            }
        } catch (e: Exception) {
            false
        }

    /**
     * JSON 搜索（Spec §10 结果映射：title/url/content→snippet/engine→来源标记）。
     * null = 非 200/解析失败/空结果（调用方降级，不打断回退链）。
     */
    suspend fun search(
        query: String,
        limit: Int,
        timeoutMs: Long = SEARCH_TIMEOUT_MS
    ): List<SearxngJsonResult>? = withContext(Dispatchers.IO) {
        try {
            withTimeout(timeoutMs) {
                val url = "http://${SearxngContract.BIND_ADDRESS}:${port()}" +
                    "/search?q=" + java.net.URLEncoder.encode(query, "UTF-8") +
                    "&format=json&language=auto"
                val request = Request.Builder().url(url).get().build()
                val body: String = client.newCall(request).await().use { response ->
                    if (!response.isSuccessful) return@withTimeout null
                    response.body?.string() ?: return@withTimeout null
                }
                parseResults(body, limit)
            }
        } catch (e: Exception) {
            runCatching {
                AppLogger.instance.info(
                    LogCategory.TOOL, TAG, "local search failed: ${e.message ?: e::class.simpleName}"
                )
            }
            null
        }
    }

    /** SearXNG JSON results[] 的最小投影（content → snippet 映射）。 */
    data class SearxngJsonResult(
        val title: String,
        val url: String,
        val snippet: String,
        val engine: String?
    )

    private fun parseResults(body: String, limit: Int): List<SearxngJsonResult>? = try {
        val root = json.parseToJsonElement(body).jsonObject
        val results = root["results"]?.jsonArray ?: return null
        results
            .mapNotNull { el ->
                val o = el.jsonObject
                val url = o["url"]?.jsonPrimitive?.content ?: return@mapNotNull null
                SearxngJsonResult(
                    title = o["title"]?.jsonPrimitive?.content ?: url,
                    url = url,
                    snippet = o["content"]?.jsonPrimitive?.content ?: "",
                    // SearXNG 的 engine 字段可能是数组（多引擎聚合）—— 取字符串形态优先
                    engine = o["engine"]?.jsonPrimitive?.content
                        ?: o["engines"]?.jsonArray?.firstOrNull()
                            ?.jsonPrimitive?.content
                )
            }
            .take(limit)
            .takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }

    /** OkHttp 挂起适配（挂起取消 → call.cancel，不留僵尸连接）。 */
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWith(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response)
            }
        })
        cont.invokeOnCancellation { runCatching { cancel() } }
    }

    companion object {
        private const val TAG = "SearxngHttpClient"

        /** 本地搜索请求超时（Spec §10）。 */
        private const val SEARCH_TIMEOUT_MS = 5_000L

        private val json = Json { ignoreUnknownKeys = true }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            // 本地回环无需重试放大 —— 快速失败供回退链切换
            .retryOnConnectionFailure(false)
            .build()
    }
}
