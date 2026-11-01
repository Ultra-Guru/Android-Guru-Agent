package com.apex.agent.di

import android.content.Context
import com.apex.agent.core.tools.extension.ExtensionAdapterRegistry
import com.apex.agent.core.tools.extension.ExtensionInstaller
import com.apex.agent.core.tools.extension.ExtensionRegistry
import com.apex.agent.core.tools.extension.adapters.McpExtensionAdapter
import com.apex.agent.core.tools.extension.adapters.SkillExtensionAdapter
import com.apex.agent.core.tools.mcp.McpManager
import com.apex.agent.core.tools.mcp.McpServerConfig
import com.apex.agent.core.tools.skill.SkillActivationStore
import com.apex.agent.core.tools.skill.SkillRegistry
import com.apex.agent.marketplace.ExtensionMcpCatalogLookup
import com.apex.agent.marketplace.ExtensionTextFetcher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/**
 * # ExtensionModule —— 统一扩展模型（市场完全重构 PR-A）的 app 层接线
 *
 * core:tool-registry 的 extension 包是纯 JVM 闸门（resolve→inspect→install +
 * hash 门/确认门/账本），本模块把两个 PR-A 适配器接到真实子系统：
 * - Skill：SkillRegistry 直连（core 内同类）+ 市场热加载同款激活闭环；
 * - MCP：McpManager 经 [McpExtensionAdapter.McpConfigPort] 端口暴露
 *   add/remove/list（单测手写 fake，不真连进程）；精选目录（`mcp:<目录名>`）
 *   经 [ExtensionMcpCatalogLookup]（assets/mcp_catalog，Hub v2 后空集合规）。
 *
 * 数据源以 @Inject 类注入（[ExtensionTextFetcher] / [ExtensionMcpCatalogLookup]）
 * —— 跨模块裸函数类型绑定按 Java 擦除匹配易碎，类绑定是本仓 DI 惯例。
 */
@Module
@InstallIn(SingletonComponent::class)
object ExtensionModule {

    /** 扩展账本目录（与 skill/fusion/mcp_config 同代：<filesDir>/extensions）。 */
    @Provides
    @Singleton
    fun provideExtensionRegistry(@ApplicationContext context: Context): ExtensionRegistry =
        ExtensionRegistry(dir = File(context.filesDir, "extensions"))

    /** MCP 配置端口：McpManager 最小交面（add/remove/list —— 不真连进程）。 */
    @Provides
    @Singleton
    fun provideMcpConfigPort(mcpManager: McpManager): McpExtensionAdapter.McpConfigPort =
        object : McpExtensionAdapter.McpConfigPort {
            override suspend fun add(config: McpServerConfig) = mcpManager.addServer(config)
            override suspend fun remove(name: String) = mcpManager.removeServer(name)
            override fun all(): List<McpServerConfig> = mcpManager.getConfigs()
        }

    /** 适配器注册表（type → adapter 路由；doctor/resolve 引导消费可用面）。 */
    @Provides
    @Singleton
    fun provideExtensionAdapterRegistry(
        skillRegistry: SkillRegistry,
        skillActivation: SkillActivationStore,
        textFetcher: ExtensionTextFetcher,
        mcpConfigPort: McpExtensionAdapter.McpConfigPort,
        catalogLookup: ExtensionMcpCatalogLookup
    ): ExtensionAdapterRegistry = ExtensionAdapterRegistry(
        listOf(
            SkillExtensionAdapter(
                skillRegistry = skillRegistry,
                activation = skillActivation,
                fetchText = { url -> textFetcher.fetch(url) }
            ),
            McpExtensionAdapter(
                configs = mcpConfigPort,
                catalogLookup = { name -> catalogLookup.lookup(name) }
            )
        )
    )

    /** 统一安装闸门（resolve/inspect/install 编排 + hash 门/确认门 + 账本）。 */
    @Provides
    @Singleton
    fun provideExtensionInstaller(
        registry: ExtensionRegistry,
        adapters: ExtensionAdapterRegistry
    ): ExtensionInstaller = ExtensionInstaller(adapters = adapters, registry = registry)
}
