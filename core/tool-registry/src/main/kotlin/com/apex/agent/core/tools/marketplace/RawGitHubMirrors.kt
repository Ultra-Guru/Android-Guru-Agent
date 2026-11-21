package com.apex.agent.core.tools.marketplace

import com.apex.agent.core.tools.network.BoundedReads
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * ═══ raw.githubusercontent.com 镜像 fallback（Issue #339）═══
 *
 * App 面向国内用户，但四条链路（Hub 目录/manifest、ModelScope SKILL.md、
 * Operit mcp_config.json 探测、GitHub 仓库技能安装）此前全部直连
 * raw.githubusercontent.com —— 国内大面积不可达。现在按序尝试
 * jsdelivr 三级 CDN + 直连：
 *
 * 1. `cdn.jsdelivr.net/gh/{owner}/{repo}@{branch}/{path}`
 * 2. `fastly.jsdelivr.net/gh/...`（同形，Fastly 节点）
 * 3. `gcore.jsdelivr.net/gh/...`（同形，Gcore 节点）
 * 4. 原始 raw.githubusercontent.com 直连（兜底，保海外网络行为不变）
 *
 * 约束与边界：
 * - 非 raw.githubusercontent.com 的 URL：[candidates] 原样单元素返回，
 *   [fetchWithFallback] 退化为单次直取（透传支持，调用方无需分流）；
 * - 分支为 `HEAD`（符号引用）时 jsdelivr 不识别，直接只试直连；
 * - 含 `/` 的分支名在 jsdelivr 形态下有歧义（@version 取第一段），
 *   该候选会 404 后自然回退直连，不做特殊处理；
 * - 鉴权 [bearerForRawOnly] **只**附加在 raw.githubusercontent.com 候选上，
 *   绝不发给第三方 CDN（GitHub token 不出域）；
 * - 响应体一律经 [BoundedReads] 限读（上限参数化，JSON 惯例 2MB）；
 * - 单源失败经 [onWarn] 留痕（"源 X 失败：原因，切换下一源"），
 *   全部失败抛聚合 [IOException]，消息逐一列出各源失败摘要并附
 *   「国内网络可能需要镜像加速」提示；
 * - 协程取消（CancellationException）原样重抛，绝不折叠进聚合异常。
 */
object RawGitHubMirrors {

    /**
     * raw URL 的镜像候选（按尝试顺序）。识别
     * `https://raw.githubusercontent.com/{owner}/{repo}/{branch}/{path}`；
     * 非 raw URL / 段不足 / HEAD 符号引用 → 仅直连单元素。
     */
    fun candidates(rawUrl: String): List<String> {
        if (!rawUrl.startsWith(RAW_HOST_PREFIX)) return listOf(rawUrl)
        val segments = rawUrl.removePrefix(RAW_HOST_PREFIX).split('/', limit = 4)
        if (segments.size < 4) return listOf(rawUrl)
        val owner = segments[0]
        val repo = segments[1]
        val branch = segments[2]
        val path = segments[3]
        if (owner.isEmpty() || repo.isEmpty() || branch.isEmpty() || path.isEmpty()) {
            return listOf(rawUrl)
        }
        // HEAD 是符号引用而非分支名，jsdelivr 的 gh 形态不解析它
        if (branch == "HEAD") return listOf(rawUrl)
        return MIRROR_HOSTS.map { host ->
            "https://$host/gh/$owner/$repo@$branch/$path"
        } + rawUrl
    }

    /**
     * 按序尝试候选 URL，首个成功者返回其响应字节。
     *
     * @param maxBytes 响应体读取上限（超限按单源失败处理并切换下一源；
     *   文件本体超限时各镜像结果一致，最终落入聚合异常）
     * @param timeoutMs 单次调用的整体超时（OkHttp callTimeout，覆盖连接 +
     *   读 + 体温）；非正值时沿用 [client] 自带超时
     * @param truncateAtLimit true = 超限返回头部字节（截断语义，frontmatter
     *   探测用）而非失败
     * @param bearerForRawOnly 仅附加给 raw.githubusercontent.com 直连候选的
     *   GitHub token（提升直连配额；绝不发给 CDN 镜像）
     * @param onWarn 单源失败留痕回调（IO 线程调用；core 无日志依赖，
     *   由宿主注入 AppLogger，默认 no-op）
     * @throws IOException 全部候选失败（消息含各源失败摘要与镜像加速提示）
     */
    suspend fun fetchWithFallback(
        client: OkHttpClient,
        rawUrl: String,
        maxBytes: Int,
        timeoutMs: Long = DEFAULT_CALL_TIMEOUT_MS,
        truncateAtLimit: Boolean = false,
        bearerForRawOnly: String? = null,
        onWarn: (String) -> Unit = {}
    ): ByteArray = withContext(Dispatchers.IO) {
        val urls = candidates(rawUrl)
        val perCallClient = if (timeoutMs > 0) {
            client.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
        } else {
            client
        }
        val failures = mutableListOf<String>()
        for (url in urls) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .apply {
                        if (bearerForRawOnly != null && url.startsWith(RAW_HOST_PREFIX)) {
                            header("Authorization", "Bearer $bearerForRawOnly")
                        }
                    }
                    .build()
                val bytes = perCallClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    val stream = resp.body?.byteStream()
                        ?: throw IOException("响应体为空")
                    stream.use { s ->
                        if (truncateAtLimit) {
                            BoundedReads.readHead(s, maxBytes)
                        } else {
                            BoundedReads.readAtMost(s, maxBytes)
                                ?: throw IOException("响应体超过 ${maxBytes} 字节上限")
                        }
                    }
                }
                return@withContext bytes
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = e.message ?: e::class.simpleName ?: "未知错误"
                failures += "${hostOf(url)}：$reason"
                onWarn("镜像源 ${hostOf(url)} 失败：$reason，切换下一源")
            }
        }
        throw IOException(
            "全部下载源失败（${failures.joinToString("；")}）——国内网络可能需要镜像加速"
        )
    }

    /** URL → 主机名（失败摘要用；非法形态原样返回前缀片段）。 */
    private fun hostOf(url: String): String {
        val afterScheme = url.removePrefix("https://").removePrefix("http://")
        return afterScheme.substringBefore('/')
    }

    private const val RAW_HOST_PREFIX = "https://raw.githubusercontent.com/"

    /** jsdelivr 三级 CDN 主机（同一 gh 形态，不同边缘网络，按序尝试）。 */
    private val MIRROR_HOSTS = listOf(
        "cdn.jsdelivr.net",
        "fastly.jsdelivr.net",
        "gcore.jsdelivr.net"
    )

    /** 单次镜像调用整体超时（连接 + 读 + 体温；镜像未命中时及时切换）。 */
    const val DEFAULT_CALL_TIMEOUT_MS = 20_000L

    private const val USER_AGENT = "ApexAgent/1.0"
}
