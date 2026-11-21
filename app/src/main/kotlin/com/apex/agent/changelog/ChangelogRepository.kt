package com.apex.agent.changelog

import android.content.Context
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.update.CheckMirror
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * 更新日志读取仓 —— 远端（发布仓库 raw CDN）与内置（APK assets）双源。
 *
 * 职责边界：
 * - **远端**：[fetchRemote] 拉 [CHANGELOG_URL]（与 UpdateChecker 同走
 *   [CheckMirror] 镜像回退链 —— 同一连接池/可取消语义，国内无梯子可达）。
 *   失败折叠 null —— 更新日志是增益路径，网络异常绝不打断启动（静默降级）；
 * - **内置**：[loadEmbedded] 读 APK 内置副本（release.yml 构建期注入
 *   app 的 assets，与本 APK 版本严格对应）。老构建（通道上线前）无此
 *   文件 —— 同样折叠 null；
 * - **零 Token**：发布仓库是公开仓库，raw CDN 零鉴权（与 version.json
 *   的读取口径一致），客户端代码绝不携带任何 GitHub 凭据。
 */
class ChangelogRepository(
    private val context: Context
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 远端清单。经 [CheckMirror.fetch] 走镜像回退链；协程取消同步 cancel
     * 底层 OkHttp 调用；任何失败（网络/解析/非 200）一律折叠 null 并留痕
     * —— 调用方走离线兜底。
     */
    suspend fun fetchRemote(): ChangelogManifest? = runCatching {
        val body = CheckMirror.fetch(CHANGELOG_URL)
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

    private companion object {
        const val EMBEDDED_ASSET_PATH = "changelog.json"
    }
}
