package com.apex.agent.marketplace

import android.content.Context
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.core.tools.marketplace.RawGitHubMirrors
import com.apex.agent.core.tools.mcp.McpServerCatalog
import com.apex.agent.core.tools.mcp.McpServerConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * # Extension 来源探测类 —— 统一扩展模型的两个 app 层数据源（PR-A）
 *
 * 独立成类而非裸 lambda 注入：Dagger 对跨模块的函数类型绑定按 Java 擦除
 * 匹配易碎（本 PR 首推即裁在 MissingBinding），类绑定是本仓 DI 惯例
 * （同 MarketInstallManager / RegistryMcpInstaller 形态）。
 */

/** URL 文本拉取（skill:<url> 等远端 manifest）：raw 镜像回退链 + 2MB 上限。 */
@Singleton
class ExtensionTextFetcher @Inject constructor(
    private val httpClient: OkHttpClient
) {
    suspend fun fetch(url: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = RawGitHubMirrors.fetchWithFallback(httpClient, url, MAX_MANIFEST_BYTES)
            bytes.toString(Charsets.UTF_8)
        }.onFailure {
            AppLogger.instance.warn(
                LogCategory.TOOL, "ExtensionSources", "extension fetch 失败 $url: ${it.message}"
            )
        }
    }

    private companion object {
        const val MAX_MANIFEST_BYTES = 2 * 1024 * 1024
    }
}

/**
 * 精选目录查询（`mcp:<目录名>`）：assets/mcp_catalog 分类 JSON →
 * [McpServerCatalog.parseCategoryFile] → 按 id/名称命中 → toServerConfig
 * （沙箱判定与市场同款：rootfs 标记存在且 STDIO 形态 → runInSandbox）。
 * Hub v2 后 assets 为空集是合法状态（查询落空 → 诚实报错，不伪造）。
 */
@Singleton
class ExtensionMcpCatalogLookup @Inject constructor(
    @ApplicationContext private val context: Context
) {
    suspend fun lookup(name: String): Result<McpServerConfig> = withContext(Dispatchers.IO) {
        runCatching {
            val names = context.assets.list(CATALOG_DIR).orEmpty()
                .filter { it.endsWith(".json") }.sorted()
            var hit: McpServerCatalog.McpCatalogEntry? = null
            for (file in names) {
                val text = context.assets.open("$CATALOG_DIR/$file").use {
                    it.readBytes().toString(Charsets.UTF_8)
                }
                val parsed = McpServerCatalog.parseCategoryFile(text).getOrNull() ?: continue
                hit = parsed.entries.firstOrNull {
                    it.id == name || it.name.equals(name, ignoreCase = true)
                }
                if (hit != null) break
            }
            val entry = hit
                ?: error("精选目录未收录 '$name'（可用 mcp:{配置字面量} 直接注册，或 ExtensionList 查看已装）")
            val sandboxReady = File(context.filesDir, "rootfs/ubuntu/current").exists()
            McpServerCatalog.toServerConfig(
                entry,
                envValues = emptyMap(),
                runInSandbox = sandboxReady && entry.transport == com.apex.agent.core.tools.mcp.McpTransport.STDIO
            )
        }
    }

    private companion object {
        const val CATALOG_DIR = "mcp_catalog"
    }
}
