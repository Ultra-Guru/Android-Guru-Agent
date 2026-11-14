package com.apex.agent.update

import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 更新检查链路的镜像回退 —— 「检查更新必须开梯子」的根治点。
 *
 * 背景：检查链路的三份清单（version.json / patches.json / changelog.json）
 * 全部托管在 raw.githubusercontent.com，国内直连基本不可达（下载端早有
 * [DownloadMirror] 加速，但检查端一直裸连，用户仍需代理才能触发更新）。
 * 公共反代镜像以前缀拼接方式同样代理 raw 域，把检查请求交给它们即可
 * 无梯子完成检查。
 *
 * 设计约束：
 * - **与下载镜像同源**：反代清单复用 [DownloadMirror] 实体节点 —— 一处
 *   增删，检查与下载两端同时受益，不另立镜像注册表；
 * - **镜像在前、直连殿后**：镜像档按节点顺序逐个尝试，全部失败才回退
 *   直连 —— 海外/代理环境行为不变（多花的是死节点的快速失败）；
 * - **短超时快速降级**：镜像档用 4s/6s 派生客户端 —— 镜像要么秒回要么
 *   已死，不把检查拖过直连超时；直连保留 [UpdateHttp.client] 原超时语义；
 * - **伪 200 防御**：部分镜像存活的假象是回 HTML 挑战页（HTTP 200），
 *   三份清单均为 JSON 对象，响应体不以 `{` 开头即按失败降级下一候选，
 *   不让坏镜像截断回退链；
 * - **失败语义不变**：全链失败抛最后一次（直连）的异常 —— 调用方的
 *   三态结果/折叠 null 逻辑零改动；
 * - **镜像只加速、不做信任源（S-02）**：签名敏感清单走 [fetchVerified]
 *   —— 每个候选（镜像改写前后同理）拉回的正文都要过内置发布者公钥的
 *   detached 签名验证，镜像本身不参与信任决策。
 */
object CheckMirror {

    /** 镜像档短超时客户端（自共享 [UpdateHttp.client] 派生，连接池复用）。 */
    private val mirrorClient: OkHttpClient by lazy {
        UpdateHttp.client.newBuilder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    /** raw 直链 → 候选 URL 列表（镜像在前、直连殿后）。 */
    fun candidates(rawUrl: String): List<String> =
        DownloadMirror.NODES.map { it.prefix + rawUrl } + rawUrl

    /**
     * 按回退链拉取清单文本 —— changelog 等非签名敏感清单的出口。
     *
     * 单个候选失败静默降级（debug 留痕）；协程取消立即中止整链。
     */
    suspend fun fetch(rawUrl: String): String {
        var lastError: Exception? = null
        for (url in candidates(rawUrl)) {
            try {
                return fetchUrl(url, requireJsonBody = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                AppLogger.instance.debug(
                    LogCategory.SYSTEM, "CheckMirror",
                    "清单端点失败，降级下一候选：$url（${e.message}）"
                )
            }
        }
        throw lastError ?: IOException("all update-check endpoints failed")
    }

    /**
     * 按回退链拉取**受信**清单 —— S-02 起签名敏感清单（version.json /
     * patches.json）的统一出口。
     *
     * 单候选语义（镜像与直连同规则）：
     * 1. 拉清单正文（沿用伪 200 防御：非 JSON 对象体按网络失败降级）；
     * 2. 拉同源 sidecar 签名（清单 URL + `.sig` 后缀，与正文同一候选 ——
     *    镜像改写前后两侧都校验同一签名）：形态合法才进入验签，挑战页 /
     *    HTML 按「无签名」处理（不误判篡改）；
     * 3. 验签通过 → 立即返回 [TrustedManifest.Verified]（后候选不再试探）；
     *    验签失败 → 记「坏签名」标记并降级下一候选（后续候选可能干净）；
     *    签名缺失 → 记「无签名」标记并降级下一候选（后续候选可能带签名）。
     *
     * 全链失败裁决（fail-closed 优先级）：
     * - 出现过**坏签名**（base64 形态合法但验签不过）→ 抛
     *   [ManifestTrustException]：链上检测到篡改，任何「无签名放行」都
     *   不适用；
     * - 仅「无签名」→ 过渡期内返回 [TrustedManifest.LegacyUnsigned]
     *   （WARN 留痕），过渡期后抛 [ManifestTrustException]；
     * - 全链连清单正文都拉不到 → 抛最后一次（直连）的网络异常（与
     *   [fetch] 同语义，调用方折叠 Failed / null）。
     *
     * @param nowEpochMs 过渡期判定时钟（默认系统时钟；测试注入）
     * @param fetcher 传输层（默认真实 OkHttp；测试注入内存 fake）
     * @param verifier 验签器（默认内置发布者公钥；测试注入自有密钥对 ——
     *   协议逻辑与密钥本身解耦单测）
     */
    suspend fun fetchVerified(
        rawUrl: String,
        nowEpochMs: Long = System.currentTimeMillis(),
        fetcher: suspend (url: String, signatureSidecar: Boolean) -> String =
            { url, sidecar -> fetchUrl(url, requireJsonBody = !sidecar) },
        verifier: (payload: ByteArray, signatureB64: String) -> Boolean =
            UpdateTrust::verifyWithPublisherKeys
    ): TrustedManifest {
        var lastError: Exception? = null
        var sawBadSignature = false
        var unsignedManifestText: String? = null
        for (url in candidates(rawUrl)) {
            val manifestText = try {
                fetcher(url, false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                AppLogger.instance.debug(
                    LogCategory.SYSTEM, "CheckMirror",
                    "受信清单端点失败，降级下一候选：$url（${e.message}）"
                )
                continue
            }
            val signature = try {
                fetcher("$url.sig", true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (signature == null || !UpdateTrust.looksLikeBase64Signature(signature)) {
                // sidecar 缺失 / 形态非法（挑战页）：后候选可能带签名，先记账
                unsignedManifestText = manifestText
                AppLogger.instance.debug(
                    LogCategory.SYSTEM, "CheckMirror",
                    "清单 sidecar 签名缺失（候选 $url）—— 继续探测后续候选"
                )
                continue
            }
            if (verifier(manifestText.toByteArray(Charsets.UTF_8), signature)) {
                return TrustedManifest.Verified(manifestText)
            }
            sawBadSignature = true
            AppLogger.instance.warn(
                LogCategory.SYSTEM, "CheckMirror",
                "清单签名验证失败（候选 $url，疑似篡改）—— 降级下一候选"
            )
        }
        if (sawBadSignature) {
            throw ManifestTrustException(
                "清单签名验证失败，疑似被篡改（${rawUrl.substringAfterLast('/')}）"
            )
        }
        unsignedManifestText?.let { text ->
            if (UpdateTrust.legacySchemaAllowed(nowEpochMs)) {
                AppLogger.instance.warn(
                    LogCategory.SYSTEM, "CheckMirror",
                    "清单无发布者签名（过渡期内按旧 schema 放行，截止 " +
                        "2027-03-01）：$rawUrl"
                )
                return TrustedManifest.LegacyUnsigned(text)
            }
            throw ManifestTrustException(
                "清单缺少发布者签名，已拒绝（${rawUrl.substringAfterLast('/')}）"
            )
        }
        throw lastError ?: IOException("all update-check endpoints failed")
    }

    /**
     * 单候选拉取（按 URL 形态选客户端：镜像档短超时派生 / 直连档原超时）。
     *
     * @param requireJsonBody true = 清单正文（伪 200 防御：非 `{` 开头按失败）；
     *   false = sidecar 签名（纯 base64 文本，不做 JSON 形态检查）
     */
    private suspend fun fetchUrl(url: String, requireJsonBody: Boolean): String {
        val client = if (DownloadMirror.NODES.any { url.startsWith(it.prefix) }) {
            mirrorClient
        } else {
            UpdateHttp.client
        }
        return withContext(Dispatchers.IO) { client.awaitText(url, requireJsonBody) }
    }

    /**
     * 可取消的同步化请求（响应体自动关闭；非 2xx / 空 body 均按失败）。
     *
     * @param requireJsonBody true 时执行伪 200 防御：响应体不以 `{` 开头
     *   （挑战页/错误页）按失败处理 —— 不得冒充清单。
     */
    private suspend fun OkHttpClient.awaitText(
        url: String,
        requireJsonBody: Boolean
    ): String =
        suspendCancellableCoroutine { cont ->
            val call = newCall(
                Request.Builder()
                    .url(url)
                    .header("Cache-Control", "no-cache")
                    .build()
            )
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val text = runCatching {
                            if (!it.isSuccessful) error("HTTP ${it.code}")
                            val body = it.body?.string() ?: error("empty manifest body")
                            if (requireJsonBody && !body.trimStart().startsWith("{")) {
                                error("non-JSON response (mirror challenge page?)")
                            }
                            body
                        }
                        cont.resumeWith(text)
                    }
                }

                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
            })
        }
}
