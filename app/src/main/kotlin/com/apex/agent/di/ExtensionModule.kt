package com.apex.agent.di

import android.content.Context
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.core.tools.extension.ExtensionAdapterRegistry
import com.apex.agent.core.tools.extension.ExtensionInstaller
import com.apex.agent.core.tools.extension.ExtensionRegistry
import com.apex.agent.core.tools.extension.adapters.McpExtensionAdapter
import com.apex.agent.core.tools.extension.adapters.SkillExtensionAdapter
import com.apex.agent.core.tools.marketplace.RawGitHubMirrors
import com.apex.agent.core.tools.mcp.McpManager
import com.apex.agent.core.tools.mcp.McpServerCatalog
import com.apex.agent.core.tools.mcp.McpServerConfig
import com.apex.agent.core.tools.mcp.McpTransport
import com.apex.agent.core.tools.skill.SkillActivationStore
import com.apex.agent.core.tools.skill.SkillRegistry
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Named
import javax.inject.Singleton

/**
 * # ExtensionModule —— 统一扩展模型（市场完全重构 PR-A）的 app 层接线
 *
 * core:tool-registry 的 extension 包是纯 JVM 闸门（resolve→inspect→install +
 * hash 门/确认门/账本），本模块把两个 PR-A 适配器接到真实子系统：
 * - Skill：SkillRegistry 直连（core 内同类）+ 市场热加载同款激活闭环；
 * - MCP：McpManager 经 [McpExtensionAdapter.McpConfigPort] 端口暴露
 *   add/remove/list（单测手写 fake，不真连进程）；精选目录（`mcp:<目录名>`）
 *   从 assets/mcp_catalog 解析（Hub v2 后为空集，查询落空诚实报错）。
 */
@Module
@InstallIn(SingletonComponent::class)
object ExtensionModule {

    /** 扩展账本目录（与 skill/fusion/mcp_config 同代：<filesDir>/extensions）。 */
    @Provides
    @Singleton
    fun provideExtensionRegistry(@ApplicationContext context: Context): ExtensionRegistry =
        ExtensionRegistry(dir = File(context.filesDir, "extensions"))

    /** URL 文本拉取（skill:<url> 等远端 manifest）：raw 镜像回退链 + 2MB 上限。 */
    @Provides
    @Singleton
    @Named("extensionFetchText")
    fun provideExtensionFetchText(httpClient: OkHttpClient): suspend (String) -> Result<String> =
        { url ->
            withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = RawGitHubMirrors.fetchWithFallback(httpClient, url, MAX_MANIFEST_BYTES)
                    bytes.toString(Charsets.UTF_8)
                }.onFailure {
                    AppLogger.instance.warn(
                        LogCategory.TOOL, "ExtensionModule", "extension fetch 失败 $url: ${it.message}"
                    )
                }
            }
        }

    /** MCP 配置端口：McpManager 最小交面（add/remove/list —— 不真连进程）。 */
    @Provides
    @Singleton
    fun provideMcpConfigPort(mcpManager: McpManager): McpExtensionAdapter.McpConfigPort =
        object : McpExtensionAdapter.McpConfigPort {
            override suspend fun add(config: McpServerConfig) = mcpManager.addServer(config)
            override suspend fun remove(name: String) = mcpManager.removeServer(name)
            override fun all(): List<McpServerConfig> = mcpManager.getConfigs()
        }

    /**
     * 精选目录查询（`mcp:<目录名>`）：assets/mcp_catalog 分类 JSON →
     * [McpServerCatalog.parseCategoryFile] → 按 id/名称命中 → toServerConfig
     * （沙箱判定与市场同款：rootfs 标记存在且 STDIO 形态 → runInSandbox）。
     */
    @Provides
    @Singleton
    @Named("extensionMcpCatalogLookup")
    fun provideMcpCatalogLookup(
        @ApplicationContext context: Context
    ): suspend (String) -> Result<McpServerConfig> = { name ->
        withContext(Dispatchers.IO) {
            runCatching {
                val names = context.assets.list("mcp_catalog").orEmpty()
                    .filter { it.endsWith(".json") }.sorted()
                var hit: McpServerCatalog.McpCatalogEntry? = null
                for (file in names) {
                    val text = context.assets.open("mcp_catalog/$file").use {
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
                    runInSandbox = sandboxReady && entry.transport == McpTransport.STDIO
                )
            }
        }
    }

    /** 统一安装闸门（resolve/inspect/install 编排 + hash 门/确认门）。 */
    @Provides
    @Singleton
    fun provideExtensionInstaller(
        registry: ExtensionRegistry,
        skillRegistry: SkillRegistry,
        skillActivation: SkillActivationStore,
        mcpConfigPort: McpExtensionAdapter.McpConfigPort,
        @Named("extensionFetchText") fetchText: suspend (String) -> Result<String>,
        @Named("extensionMcpCatalogLookup") catalogLookup: suspend (String) -> Result<McpServerConfig>
    ): ExtensionInstaller {
        val adapters = ExtensionAdapterRegistry(
            listOf(
                SkillExtensionAdapter(
                    skillRegistry = skillRegistry,
                    activation = skillActivation,
                    fetchText = fetchText
                ),
                McpExtensionAdapter(
                    configs = mcpConfigPort,
                    catalogLookup = catalogLookup
                )
            )
        )
        return ExtensionInstaller(adapters = adapters, registry = registry)
    }

    private const val MAX_MANIFEST_BYTES = 2 * 1024 * 1024
}
