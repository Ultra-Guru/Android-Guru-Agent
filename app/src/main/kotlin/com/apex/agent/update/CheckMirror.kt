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
 *   三态结果/折叠 null 逻辑零改动。
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
     * 按回退链拉取清单文本 —— 更新检查三处请求（[UpdateChecker.check] /
     * [UpdateChecker.fetchPatchIndex] / changelog 的 fetchRemote）的统一出口。
     *
     * 单个候选失败静默降级（debug 留痕）；协程取消立即中止整链。
     */
    suspend fun fetch(rawUrl: String): String {
        val urls = candidates(rawUrl)
        var lastError: Exception? = null
        for ((index, url) in urls.withIndex()) {
            val client = if (index == urls.lastIndex) UpdateHttp.client else mirrorClient
            try {
                return withContext(Dispatchers.IO) { client.awaitText(url) }
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

    /** 可取消的同步化请求（响应体自动关闭；非 2xx / 非 JSON 体均按失败）。 */
    private suspend fun OkHttpClient.awaitText(url: String): String =
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
                            // 伪 200 防御：挑战页/错误页（HTML）不得冒充清单
                            if (!body.trimStart().startsWith("{")) {
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
