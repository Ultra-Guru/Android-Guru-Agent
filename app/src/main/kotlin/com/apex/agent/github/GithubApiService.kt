package com.apex.agent.github

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory

@Singleton
class GithubApiService @Inject constructor(
    private val tokenManager: GithubTokenManager
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // 新增 writeTimeout，避免 PUT 大 base64 文件时无限挂起（原配置缺失）。
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // @PublishedApi internal：供 public inline [fetchPage] / private inline [apiCall] 调用。
    // inline 函数体被内联到外部调用点，如果该调用点不在 GithubApiService 内部，
    // 需要成员可见性提升到 internal+@PublishedApi，否则 Kotlin 编译器会报 “Public-API
    // inline function cannot access private ...”。
    @PublishedApi
    internal val json = Json { ignoreUnknownKeys = true }
    @PublishedApi
    internal val jsonMediaType = "application/json".toMediaType()

    internal companion object {

        /** Git 审计 P0-4：Bearer 头只发往的 API host（Link 分页跟随同此白名单）。 */
        private const val API_HOST = "api.github.com"

        /** 相对路径 API 基址。 */
        private const val API_BASE = "https://api.github.com"

        /**
         * 重试决策纯函数（Git 审计 P1-10/11，internal 供单测穷举）：
         * 返回本轮重试的退避毫秒数；null = 不重试（方法非幂等 / 状态码
         * 不在重试集 / 重试预算用尽）。
         *
         * - **仅 GET 可重试**——POST/PUT 重发会重复建 Issue/PR/提交；
         * - 429/503 尊重 Retry-After（封顶 60s，预算 3 次）；
         * - 其他 5xx 指数退避 1s/2s（预算 2 次）。
         */
        internal fun retryDelayMs(
            method: String,
            code: Int,
            attempt: Int,
            retryAfterSeconds: Long?,
        ): Long? {
            if (method != "GET") return null
            val retryable = code == 429 || code == 503 || code in 500..599
            if (!retryable) return null
            val isThrottle = code == 429 || code == 503
            val maxRetries = if (isThrottle) 3 else 2
            if (attempt >= maxRetries) return null
            return if (isThrottle) {
                (retryAfterSeconds ?: 5L).coerceAtMost(60L) * 1000L
            } else {
                (1L shl attempt) * 1000L
            }
        }

        /**
         * URL 白名单解析（Git 审计 P0-4，internal 供单测穷举）：相对路径补
         * API 基址；完整 URL 仅 https + [API_HOST] 被跟随——fetchPage 翻页
         * 的 nextUrl 被代理/中间人篡改、http 明文、相似主机
         * （api.github.com.evil.com）全部拒绝，Bearer 头只发往 api.github.com。
         */
        internal fun resolveApiUrl(path: String): String {
            if (!path.startsWith("http")) return "$API_BASE$path"
            val uri = runCatching { java.net.URI(path) }.getOrNull()
                ?: throw GithubApiException("拒绝发送请求：无法解析的 URL（${path.take(120)}）")
            if (uri.scheme != "https") {
                throw GithubApiException(
                    "拒绝发送认证请求：仅允许 https（收到 ${uri.scheme ?: "?"}://）——" +
                        "明文 http 会把 Bearer 头暴露给中间人（Git 审计 P0-4）"
                )
            }
            if (uri.host != API_HOST) {
                throw GithubApiException(
                    "拒绝向 ${uri.host ?: "?"} 发送认证请求：Bearer 头仅发往 $API_HOST。" +
                        "分页 Link 头被代理/中间人篡改时，凭据不会外泄（Git 审计 P0-4）"
                )
            }
            return path
        }
    }

    private fun authHeader(): String {
        val token = tokenManager.getToken()
            ?: throw GithubApiException("未连接 GitHub，请先配置 Token")
        return "Bearer $token"
    }

    // ═══ URL 编码辅助 ═══
    // 之前 owner/repo/path/query 全部直接字符串插值到 URL 中：含 `?`/`#`/`/`/非 ASCII 的
    // 输入会破坏 URL 结构，触发 404 / 400 / 解析异常。

    /** URL-encode 一个 path 段（空格用 %20 而非 +，符合 URL path 规范）。 */
    private fun encodeSegment(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** URL-encode 一个 query 值（空格用 +，符合 application/x-www-form-urlencoded）。 */
    private fun encodeQuery(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8")

    /** 对含 `/` 分隔符的 path（如 Contents API 的 path）逐段编码，保留 `/` 作为分隔符。 */
    private fun encodePath(path: String): String =
        path.split("/").filter { it.isNotEmpty() }.joinToString("/") { encodeSegment(it) }

    suspend fun getCurrentUser(): GithubUser = apiCall("/user")

    suspend fun listRepos(username: String? = null, type: String = "owner", sort: String = "updated", perPage: Int = 30): List<GithubRepo> {
        val user = username ?: tokenManager.getUsername() ?: "me"
        val path = if (user == "me") "/user/repos" else "/users/${encodeSegment(user)}/repos"
        return apiCall("$path?type=${encodeQuery(type)}&sort=${encodeQuery(sort)}&per_page=$perPage")
    }

    suspend fun getFileContent(owner: String, repo: String, path: String, branch: String? = null): GithubFileContent {
        val ref = if (branch != null) "?ref=${encodeQuery(branch)}" else ""
        return apiCall("/repos/${encodeSegment(owner)}/${encodeSegment(repo)}/contents/${encodePath(path)}$ref")
    }

    /**
     * 仓库元数据（#273 private-fork 写保护的可见性探针入口）：
     * GET /repos/{owner}/{repo} → [GithubRepo]（含 private 标志；
     * Json ignoreUnknownKeys 已开，响应其余字段自动忽略）。
     */
    suspend fun getRepo(owner: String, repo: String): GithubRepo =
        apiCall("/repos/${encodeSegment(owner)}/${encodeSegment(repo)}")

    suspend fun createOrUpdateFile(owner: String, repo: String, path: String, content: String, message: String, branch: String? = null, sha: String? = null): GithubCommitResult {
        val body = buildJsonObject {
            put("message", message)
            put("content", java.util.Base64.getEncoder().encodeToString(content.toByteArray()))
            if (branch != null) put("branch", branch)
            if (sha != null) put("sha", sha)
        }
        return apiCall("/repos/${encodeSegment(owner)}/${encodeSegment(repo)}/contents/${encodePath(path)}", "PUT", body.toString())
    }

    suspend fun listIssues(owner: String, repo: String, state: String = "open", perPage: Int = 20): List<GithubIssue> =
        apiCall("/repos/${encodeSegment(owner)}/${encodeSegment(repo)}/issues?state=${encodeQuery(state)}&per_page=$perPage")

    suspend fun createIssue(owner: String, repo: String, title: String, body: String, labels: List<String> = emptyList()): GithubIssue {
        val reqBody = buildJsonObject {
            put("title", title)
            put("body", body)
            if (labels.isNotEmpty()) putJsonArray("labels") { labels.forEach { add(it) } }
        }
        return apiCall("/repos/${encodeSegment(owner)}/${encodeSegment(repo)}/issues", "POST", reqBody.toString())
    }

    suspend fun listBranches(owner: String, repo: String): List<GithubBranch> =
        apiCall("/repos/${encodeSegment(owner)}/${encodeSegment(repo)}/branches")

    /**
     * 创建 Pull Request（POST /repos/{o}/{r}/pulls）。head 含冒号（跨仓
     * fork PR："user:branch" 形态）时原样透传；同仓分支 PR 只传分支名。
     */
    suspend fun createPullRequest(
        owner: String,
        repo: String,
        title: String,
        head: String,
        base: String,
        body: String? = null,
    ): GithubPullRequest {
        val reqBody = buildJsonObject {
            put("title", title)
            put("head", head)
            put("base", base)
            if (!body.isNullOrBlank()) put("body", body)
        }
        return apiCall("/repos/${encodeSegment(owner)}/${encodeSegment(repo)}/pulls", "POST", reqBody.toString())
    }

    /**
     * 代码搜索。GitHub legacy /search/code 硬约束：query 必须含至少一个
     * repo:/user:/org: 限定符（纯关键词搜索返回 422 Validation Failed）。
     * 调用方（GithubSearchCodeTool）负责在缺限定符时提前拒绝并给出指引。
     */
    suspend fun searchCode(
        query: String,
        repo: String? = null,
        org: String? = null,
        user: String? = null,
        perPage: Int = 10
    ): GithubSearchResult {
        val qualifiers = buildString {
            repo?.let { append("+repo:").append(encodeQuery(it)) }
            org?.let { append("+org:").append(encodeQuery(it)) }
            user?.let { append("+user:").append(encodeQuery(it)) }
        }
        return apiCall("/search/code?q=${encodeQuery(query)}$qualifiers&per_page=$perPage")
    }

    /**
     * 仓库搜索（/search/repositories，按 star 降序）。
     * 与 [searchCode] 对称：返回包装类（含 total_count 与 items），
     * 供工具展示命中总数而非仅当前页条数。
     */
    suspend fun searchRepositories(query: String, perPage: Int = 10): GithubSearchReposResult =
        apiCall("/search/repositories?q=${encodeQuery(query)}&per_page=$perPage&sort=stars")

    /**
     * 拉取一页列表 + 下一页 URL（解析 `Link: rel="next"` 头）。
     *
     * 调用方应循环调用直到 `nextUrl == null` 以遍历所有页面：
     * ```
     * var page = api.fetchPage<GithubIssue>("/repos/$owner/$repo/issues")
     * val all = page.items.toMutableList()
     * while (page.nextUrl != null) { page = api.fetchPage(page.nextUrl!!); all += page.items }
     * ```
     *
     * @param path 首次调用使用 GitHub API 路径（如 `/repos/owner/repo/issues`）；
     *   后续调用使用返回的 [PagedResult.nextUrl]（已是完整 URL，含 host——
     *   Git 审计 P0-4：仅 https + api.github.com 被跟随，篡改即拒绝）。
     */
    suspend inline fun <reified T> fetchPage(
        path: String,
        method: String = "GET",
        body: String? = null
    ): PagedResult<T> = withContext(Dispatchers.IO) {
        val resp = executeWithRetry(path, method, body)
        if (resp.code !in 200..299) {
            val msg = try {
                Json.parseToJsonElement(resp.body).jsonObject["message"]?.jsonPrimitive?.content ?: resp.body.take(200)
            } catch (e: Exception) { resp.body.take(200) }
            throw GithubApiException("GitHub API ${resp.code}: $msg", resp.code)
        }
        val items = decodeBody<List<T>>(resp.code, resp.body)
        PagedResult(items, resp.nextLink)
    }

    // ═══ 内部 HTTP 层 ═══

    /** 单次 HTTP 请求返回的原始数据（含 Retry-After / Link 头）。 */
    @PublishedApi
    internal data class ApiResponse(
        val code: Int,
        val body: String,
        val retryAfterSeconds: Long?,
        val nextLink: String?
    )

    /** 解析 `Link: <url>; rel="next", <url>; rel="last"` 头，返回 rel="next" 的 URL。 */
    private fun parseLinkHeader(linkHeader: String?): String? {
        if (linkHeader.isNullOrBlank()) return null
        for (part in linkHeader.split(",")) {
            val segs = part.split(";").map { it.trim() }
            val url = Regex("<([^>]+)>").find(segs.firstOrNull() ?: "")?.groupValues?.get(1) ?: continue
            if (segs.any { it == "rel=\"next\"" }) return url
        }
        return null
    }

    /** 单次 HTTP 请求（含日志），不重试，不抛业务异常。 */
    @PublishedApi
    internal suspend fun executeOnce(path: String, method: String, body: String?): ApiResponse {
        // Git 审计 P0-4：URL host 白名单校验（nextUrl 完整 URL / http 明文 /
        // 任意主机 → 拒绝——Bearer 头只发往 api.github.com）。
        val url = resolveApiUrl(path)
        val builder = Request.Builder()
            .url(url)
            .header("Authorization", authHeader())
            .header("Accept", "application/vnd.github.v3+json")
            .header("User-Agent", "ApexAgent/1.0")

        when (method) {
            "GET" -> builder.get()
            "POST" -> builder.post((body ?: "{}").toRequestBody(jsonMediaType))
            "PUT" -> builder.put((body ?: "{}").toRequestBody(jsonMediaType))
            "DELETE" -> builder.delete()
        }

        AppLogger.instance.info(
            LogCategory.NETWORK, "GithubApi", "$method $path",
            tags = arrayOf("http", method.lowercase(), "request")
        )

        // Git 审计 P1-10：同步 execute() 在 suspend 里不可取消（叠加最长
        // 3×60s 重试 ≈ 挂 3 分钟）——改 enqueue + invokeOnCancellation{cancel}，
        // 协程取消在毫秒级打断在途请求。
        return suspendCancellableCoroutine { cont ->
            val call = client.newCall(builder.build())
            cont.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    // 调用方取消触发的 failure（loop canceled）不可再 resume
                    if (cont.isActive) cont.resumeWith(Result.failure(e))
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    val result = runCatching {
                        response.use { resp ->
                            val responseBody = resp.body?.string() ?: ""
                            if (!resp.isSuccessful) {
                                val msg = try {
                                    Json.parseToJsonElement(responseBody).jsonObject["message"]?.jsonPrimitive?.content
                                        ?: responseBody.take(200)
                                } catch (e: Exception) { responseBody.take(200) }
                                AppLogger.instance.error(
                                    LogCategory.NETWORK, "GithubApi", "HTTP ${resp.code} $method $path: $msg",
                                    tags = arrayOf("http", method.lowercase(), "error", "code:${resp.code}")
                                )
                            } else {
                                AppLogger.instance.debug(
                                    LogCategory.NETWORK, "GithubApi",
                                    "HTTP ${resp.code} $method $path (${responseBody.length} bytes)",
                                    tags = arrayOf("http", method.lowercase(), "response")
                                )
                            }
                            ApiResponse(
                                resp.code,
                                responseBody,
                                resp.header("Retry-After")?.toLongOrNull(),
                                parseLinkHeader(resp.header("Link"))
                            )
                        }
                    }
                    if (cont.isActive) cont.resumeWith(result)
                }
            })
        }
    }

    /**
     * 带重试的请求（Git 审计 P1-10/11 修正）：
     * - **仅 GET 自动重试**——POST/PUT 重发会重复建 Issue/PR/提交（与
     *   ToolExecutionPolicy 的 NON_IDEMPOTENT 契约直接冲突），429/5xx
     *   直接返回给调用方换可行动错误；
     * - 429 / 503：读 `Retry-After` 头（封顶 60s），重试至多 3 次；
     * - 其他 5xx：指数退避（1s, 2s …），重试至多 2 次；
     * - 每轮重试前 ensureActive——协程取消立即终止重试链。
     */
    @PublishedApi
    internal suspend fun executeWithRetry(path: String, method: String, body: String?): ApiResponse {
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val resp = executeOnce(path, method, body)
            val backoffMs = retryDelayMs(method, resp.code, attempt, resp.retryAfterSeconds)
                ?: return resp
            delay(backoffMs)
            attempt++
        }
    }

    /**
     * 解码响应体：204 / 空 body 时根据 [T] 返回类型匹配的"空"实例；
     * 否则 `json.decodeFromString`。
     *
     * 之前 `json.decodeFromString<T>(responseBody)` 在 204 / 空字符串上抛
     * `SerializationException`，整个工具调用崩成"未捕获异常"。
     */
    @PublishedApi
    internal inline fun <reified T> decodeBody(code: Int, body: String): T {
        if (code == 204 || body.isBlank()) {
            val kClass = T::class
            @Suppress("UNCHECKED_CAST")
            return when {
                kClass == JsonObject::class -> JsonObject(emptyMap()) as T
                kClass == JsonArray::class -> JsonArray(emptyList()) as T
                List::class.java.isAssignableFrom(kClass.java) -> emptyList<Any>() as T
                else -> throw GithubApiException("GitHub API returned empty body (HTTP $code) but expected $kClass", code)
            }
        }
        return json.decodeFromString<T>(body)
    }

    private suspend inline fun <reified T> apiCall(path: String, method: String = "GET", body: String? = null): T =
        withContext(Dispatchers.IO) {
            val resp = executeWithRetry(path, method, body)
            if (resp.code !in 200..299) {
                val msg = try {
                    Json.parseToJsonElement(resp.body).jsonObject["message"]?.jsonPrimitive?.content ?: resp.body.take(200)
                } catch (e: Exception) { resp.body.take(200) }
                throw GithubApiException("GitHub API ${resp.code}: $msg", resp.code)
            }
            decodeBody<T>(resp.code, resp.body)
        }
}

/**
 * GitHub API 异常。
 * @param code HTTP 状态码（如 404、422、429），可能为 null（如网络层异常时未拿到状态码）。
 */
class GithubApiException(message: String, val code: Int? = null) : Exception(message)

/**
 * 分页结果。
 * @param items 当前页的条目
 * @param nextUrl 下一页 URL（来自 `Link: rel="next"`），为 null 表示已是最后一页
 */
data class PagedResult<T>(val items: List<T>, val nextUrl: String?)

@Serializable data class GithubUser(val login: String, val name: String? = null, val public_repos: Int = 0)
@Serializable data class GithubRepo(val name: String, val full_name: String, val description: String? = null, val private: Boolean = false, val html_url: String = "", val default_branch: String = "main", val language: String? = null, val stargazers_count: Int = 0)
@Serializable data class GithubFileContent(val name: String, val path: String, val sha: String, val size: Long = 0, val content: String? = null, val encoding: String? = null) {
    fun decoded(): String { if (content == null || encoding != "base64") return ""; return String(java.util.Base64.getDecoder().decode(content.replace("\n", ""))) }
}
@Serializable data class GithubCommitResult(val commit: GithubCommitInfo? = null)
@Serializable data class GithubCommitInfo(val sha: String = "", val message: String = "")
@Serializable data class GithubIssue(val number: Int, val title: String, val body: String? = null, val state: String = "open", val html_url: String = "")
@Serializable data class GithubBranch(val name: String, val commit: GithubBranchCommit? = null)
@Serializable data class GithubBranchCommit(val sha: String = "")
/** PR 创建结果（POST /pulls 返回体的消费子集）。 */
@Serializable data class GithubPullRequest(
    val number: Int,
    val title: String = "",
    val state: String = "open",
    val html_url: String = "",
    val head: GithubPullRef? = null,
    val base: GithubPullRef? = null,
)
@Serializable data class GithubPullRef(val ref: String = "", val sha: String = "")
@Serializable data class GithubSearchResult(val total_count: Int = 0, val items: List<GithubSearchItem> = emptyList())
@Serializable data class GithubSearchItem(val name: String = "", val path: String = "", val html_url: String = "", val repository: GithubRepo? = null)
@Serializable data class GithubSearchReposResult(val total_count: Int = 0, val items: List<GithubRepo> = emptyList())
