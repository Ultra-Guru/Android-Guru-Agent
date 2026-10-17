package com.apex.agent.changelog

import android.content.Context
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.update.UpdateHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 更新日志读取仓 —— 远端（发布仓库 raw CDN）与内置（APK assets）双源。
 *
 * 职责边界：
 * - **远端**：[fetchRemote] 拉 [CHANGELOG_URL]（共享 [UpdateHttp.client]，
 *   与 UpdateChecker 同一连接池/超时/可取消语义）。失败折叠 null —— 更新
 *   日志是增益路径，网络异常绝不打断启动（静默降级）；
 * - **内置**：[loadEmbedded] 读 APK 内置副本（release.yml 构建期注入
 *   app 的 assets，与本 APK 版本严格对应）。老构建（通道上线前）无此
 *   文件 —— 同样折叠 null；
 * - **零 Token**：发布仓库是公开仓库，raw CDN 零鉴权（与 version.json
 *   的读取口径一致），客户端代码绝不携带任何 GitHub 凭据。
 */
class ChangelogRepository(
    private val context: Context,
    private val client: OkHttpClient = UpdateHttp.client
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 远端清单。协程取消同步 cancel 底层 OkHttp 调用；任何失败
     * （网络/解析/非 200）一律折叠 null 并留痕 —— 调用方走离线兜底。
     */
    suspend fun fetchRemote(): ChangelogManifest? = runCatching {
        val request = Request.Builder()
            .url(CHANGELOG_URL)
            .header("Cache-Control", "no-cache")
            .build()
        val body = withContext(Dispatchers.IO) { client.awaitBody(request) }
        json.decodeFromString<ChangelogManifest>(body)
    }.onFailure { e ->
        AppLogger.instance.info(
            LogCategory.SYSTEM,
            "ChangelogRepository",
            "更新日志远端拉取失败（离线兜底）：${e.message}"
        )
    }.getOrNull()

    /**
     * APK 内置副本（本版日志）。assets 缺失（通道上线前的老构建）或
     * 内容损坏时折叠 null —— 仅在 IO 线程调用（磁盘访问）。
     */
    suspend fun loadEmbedded(): ChangelogManifest? = withContext(Dispatchers.IO) {
        runCatching {
            context.assets.open(EMBEDDED_ASSET_PATH).bufferedReader().use {
                it.readText()
            }
        }.mapCatching { json.decodeFromString<ChangelogManifest>(it) }
            .onFailure { e ->
                AppLogger.instance.info(
                    LogCategory.SYSTEM,
                    "ChangelogRepository",
                    "内置更新日志副本不可用（老构建或缺失）：${e.message}"
                )
            }
            .getOrNull()
    }

    /** 与 update 包同款的可取消同步化请求（响应体自动关闭）。 */
    private suspend fun OkHttpClient.awaitBody(request: Request): String =
        suspendCancellableCoroutine { cont ->
            val call = newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val text = runCatching {
                            if (!it.isSuccessful) error("HTTP ${it.code}")
                            it.body?.string() ?: error("empty changelog body")
                        }
                        cont.resumeWith(text)
                    }
                }

                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
            })
        }

    private companion object {
        const val EMBEDDED_ASSET_PATH = "changelog.json"
    }
}
