package com.apex.agent.di

import android.content.Context
import com.apex.agent.core.engine.AgentEngine
import com.apex.agent.core.engine.ApexAgentEngine
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.core.tools.fusion.FusionBundleActivator
import com.apex.agent.core.tools.fusion.FusionBundlePresets
import com.apex.agent.core.tools.fusion.FusionBundleRegistry
import com.apex.agent.core.tools.fusion.FusionMcpConnector
import com.apex.agent.core.tools.fusion.FusionPluginInstaller
import com.apex.agent.core.tools.fusion.FusionPromptInjector
import com.apex.agent.core.tools.fusion.FusionSkillInstaller
import com.apex.agent.core.tools.fusion.rea.ReaCommandRunner
import com.apex.agent.core.tools.fusion.rea.ReaPageFetcher
import com.apex.agent.core.tools.fusion.rea.ReaWorkspace
import com.apex.agent.core.tools.fusion.shrimp.ShrimpTaskStore
import com.apex.agent.core.tools.mcp.McpManager
import com.apex.agent.core.tools.mcp.McpServerConfig
import com.apex.agent.core.tools.mcp.McpTransport
import com.apex.agent.core.tools.skill.SkillRegistry
import com.apex.agent.fusion.ReaCommandRunnerImpl
import com.apex.agent.fusion.ReaPageFetcherImpl
import com.apex.agent.github.GithubTokenManager
import com.apex.agent.platform.terminal.proot.PRootCapabilitySource
import com.apex.agent.platform.terminal.proot.PRootHostEnvironment
import com.apex.agent.platform.TerminalExec.ExecEngine
import com.apex.agent.tools.ProotCommandSpawner
import com.apex.agent.tools.PrivilegedCommandSpawner
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton
import okhttp3.OkHttpClient

/**
 * # FusionModule —— FusionBundle 体系的 app 层接线
 *
 * 提供：
 * - [FusionBundleRegistry]（`filesDir/fusion-bundles`）+ 内置预置的
 *   幂等释放（installBundled —— @Provides 副作用模式，同 McpModule 的
 *   BuiltinXxxBootstrap 惯例）；
 * - [FusionBundleActivator]（三端口编排；引擎端口经 `Lazy<AgentEngine>`
 *   延迟解析 —— DI 破环：ToolRegistry → Controller → Activator → 引擎
 *   → ToolRegistry 的环在 Lazy 处断开，激活只发生在组件装配完成后）；
 * - REA 引擎运行时端口：命令执行（既有 ExecEngine + ProotCommandSpawner
 *   终端链，PRoot 优先回落权限链）与页面抓取（OkHttp）；
 * - 引擎工作目录（`filesDir/fusion` —— 证据导出/提取落盘根）。
 *
 * [com.apex.agent.fusion.FusionBundleController] 为 @Singleton @Inject
 * 自给（依赖 registry + activator，均由本模块提供，无环）。
 */
@Module
@InstallIn(SingletonComponent::class)
object FusionModule {

    @Provides
    @Singleton
    fun provideFusionBundleRegistry(@ApplicationContext context: Context): FusionBundleRegistry {
        val registry = FusionBundleRegistry(
            bundlesDir = File(context.filesDir, "fusion-bundles")
        )
        // 内置预置释放：幂等 + 版本守卫（bundled=true 不可卸载，升级替换）
        for ((manifest, json) in FusionBundlePresets.all()) {
            registry.installBundled(manifest, json)
                .onFailure {
                    AppLogger.instance.warn(
                        LogCategory.SYSTEM, "FusionModule",
                        "内置 FusionBundle '${manifest.id}' 释放失败: ${it.message}"
                    )
                }
        }
        return registry
    }

    @Provides
    @Singleton
    fun provideReaWorkspace(@ApplicationContext context: Context): ReaWorkspace =
        ReaWorkspace(root = File(context.filesDir, "fusion"))

    /**
     * Shrimp 任务存储（第七台内置 MCP 服务器的持久层）：
     * `filesDir/fusion/shrimp/tasks.json`（原子写）+ 备份链。单例共享
     * —— 工厂每连接新建 transport，任务数据跨连接持久。
     */
    @Provides
    @Singleton
    fun provideShrimpTaskStore(@ApplicationContext context: Context): ShrimpTaskStore =
        ShrimpTaskStore(dir = File(context.filesDir, "fusion/shrimp"))

    /**
     * REA 命令执行端口 —— 复用 TerminalExec 的同一条执行链
     * （ExecEngine + ProotCommandSpawner：rootfs 就绪进沙箱，Android 命令
     * 回落 su > Shizuku > local-sh）。构造参数与 ToolModule 的
     * TerminalExec 装配逐项对齐（同一 rootfs/家目录/能力门语义）。
     */
    @Provides
    @Singleton
    fun provideReaCommandRunner(
        @ApplicationContext context: Context,
        hostEnvironment: PRootHostEnvironment,
        rootfsBaseDir: File,
        capabilitySource: PRootCapabilitySource,
        githubTokenManager: GithubTokenManager
    ): ReaCommandRunner = ReaCommandRunnerImpl(
        engine = ExecEngine(
            ProotCommandSpawner(
                hostEnvironment = hostEnvironment,
                rootfsDir = rootfsBaseDir,
                isRootfsReady = { File(rootfsBaseDir, "current").exists() },
                defaultWorkspaceDir = File(context.filesDir, "linux/workspaces/default"),
                persistentHomeDir = File(context.filesDir, "linux/home"),
                fallback = PrivilegedCommandSpawner(),
                capabilities = capabilitySource::invoke,
                gitHubTokenProvider = { githubTokenManager.getToken() }
            )
        )
    )

    @Provides
    @Singleton
    fun provideReaPageFetcher(httpClient: OkHttpClient): ReaPageFetcher =
        ReaPageFetcherImpl(httpClient)

    /**
     * 激活器装配（四端口编排中枢）。
     *
     * DI 破环（两处 Lazy）：
     * 1. 引擎端口：ToolModule 的注册表提供方需要控制器 → 控制器需要本激活器；
     *    若引擎端口直依赖 AgentEngine，则 AgentEngine（需要 ToolRegistry）
     *    与注册表提供方互为前置成 Dagger 环 —— 注入 `Lazy<AgentEngine>`，
     *    apply/restore 首次调用才解析。
     * 2. 插件端口：控制器 → 激活器 → UnifiedPluginRegistry → ToolRegistry →
     *    控制器 同理成环 —— 注入 `Lazy<UnifiedPluginRegistry>`，激活期
     *    （组件装配完成后）才解析。
     */
    @Provides
    @Singleton
    fun provideFusionBundleActivator(
        registry: FusionBundleRegistry,
        skillRegistry: SkillRegistry,
        mcpManager: McpManager,
        engineLazy: dagger.Lazy<AgentEngine>,
        pluginRegistryLazy: dagger.Lazy<com.apex.agent.plugin.host.functional.UnifiedPluginRegistry>,
        hubSource: com.apex.agent.core.tools.marketplace.HubSource
    ): FusionBundleActivator = FusionBundleActivator(
        registry = registry,
        skillInstaller = SkillInstallerPort(skillRegistry),
        mcpConnector = McpConnectorPort(mcpManager),
        promptInjector = EnginePromptPort(engineLazy),
        pluginInstaller = PluginInstallerPort(pluginRegistryLazy),
        hubInstaller = HubInstallerPort(hubSource, skillRegistry, mcpManager)
    )
}

// ── 五端口的 app 层适配（core 接口 → 真实子系统）────────────────────

/** 技能安装端口 → SkillRegistry.install / get。 */
private class SkillInstallerPort(
    private val skills: SkillRegistry
) : FusionSkillInstaller {
    override fun isInstalled(skillId: String): Boolean = skills.get(skillId) != null

    override suspend fun ensureInstalled(manifestJson: String): Result<Unit> =
        skills.install(manifestJson).map { Unit }
}

/** MCP 连接端口 → McpManager（BUILTIN 幂等预置 / 其他形态 addServer + connect）。 */
private class McpConnectorPort(
    private val manager: McpManager
) : FusionMcpConnector {
    override suspend fun ensureConnected(config: McpServerConfig): Result<Unit> {
        if (config.transport == McpTransport.BUILTIN) {
            // 幂等预置（用户禁用不劫持）→ 连接
            manager.ensureBuiltinServer(config).onFailure { return Result.failure(it) }
            return manager.connect(config.name).map { Unit }
        }
        // STDIO/HTTP：写入配置（同 = 更新）→ 连接
        manager.addServer(config).onFailure { return Result.failure(it) }
        return manager.connect(config.name).map { Unit }
    }

    /** installMembers（只装不激活）路径：仅配置就绪，不发起连接。 */
    override suspend fun ensureConfigured(config: McpServerConfig): Result<Unit> {
        if (config.transport == McpTransport.BUILTIN) {
            // 内置服务器能力在二进制里：预置即就绪（连接是惰性的，工具经
            // transport 工厂按需起）——预置幂等即满足「配置就绪」。
            return manager.ensureBuiltinServer(config)
        }
        return manager.addServer(config)
    }
}

/** 引擎人设注入端口 → ApexAgentEngine.patchConfig 读改写（additionalSystemContext）。 */
private class EnginePromptPort(
    private val engineLazy: dagger.Lazy<AgentEngine>
) : FusionPromptInjector {
    /** 注入前的原始值（去激活恢复用）。 */
    private var original: String? = null

    override fun apply(segment: String) {
        // 接口 → 具体类型安全下行；非 Apex 实现时人设注入静默降级（聚合容错面）
        val apex = engineLazy.get() as? ApexAgentEngine ?: return
        if (original == null) {
            original = apex.currentConfig().additionalSystemContext
        }
        val base = original.orEmpty()
        val merged = if (base.isBlank()) segment else "$base\n\n$segment"
        apex.patchConfig { it.copy(additionalSystemContext = merged) }
    }

    override fun restore() {
        val apex = engineLazy.get() as? ApexAgentEngine ?: return
        val base = original
        original = null
        apex.patchConfig { it.copy(additionalSystemContext = base.orEmpty()) }
    }
}

/**
 * 官方仓库安装端口（安装型聚合的第五端口）→ HubSource 既有客户端 +
 * SkillRegistry.install + McpManager.addServer。
 *
 * 引用 id → 目录条目：先拉索引再按 id 匹配（skill 索引只放元数据、按需
 * 下载正文；MCP 目录单文件内联、一次请求即完整）。MCP 安装语义 =
 * 配置写盘 enabled=true **不连接**（连接留给激活路径或用户市场手动启动
 * —— 与 FusionMcpServerRef.autoConnect 的激活语义衔接）。
 */
private class HubInstallerPort(
    private val hubSource: com.apex.agent.core.tools.marketplace.HubSource,
    private val skills: SkillRegistry,
    private val mcpManager: McpManager
) : com.apex.agent.core.tools.fusion.FusionHubInstaller {

    override suspend fun installSkill(skillId: String): Result<Unit> {
        val entries = hubSource.listSkills().getOrElse {
            return Result.failure(Exception("技能仓库不可达: ${it.message}"))
        }
        val entry = entries.firstOrNull { it.id == skillId }
            ?: return Result.failure(Exception("技能仓库无此技能: $skillId"))
        return hubSource.downloadSkillManifest(entry).fold(
            onSuccess = { manifestJson -> skills.install(manifestJson).map { Unit } },
            onFailure = { Result.failure(it) }
        )
    }

    override suspend fun installMcp(serverId: String): Result<Unit> {
        val entries = hubSource.listMcpServers().getOrElse {
            return Result.failure(Exception("MCP 仓库不可达: ${it.message}"))
        }
        val entry = entries.firstOrNull { it.name == serverId }
            ?: return Result.failure(Exception("MCP 仓库无此服务器: $serverId"))
        val config = com.apex.agent.core.tools.mcp.McpServerConfig(
            name = entry.name,
            url = entry.url,
            transport = when (entry.transport.uppercase()) {
                "STDIO" -> McpTransport.STDIO
                "SSE" -> McpTransport.SSE
                else -> McpTransport.HTTP
            },
            command = entry.command,
            args = entry.args,
            env = entry.env,
            runInSandbox = entry.runInSandbox,
            enabled = true,
            scope = entry.scope
        )
        return mcpManager.addServer(config)
    }
}

/**
 * 功能性插件安装端口 → UnifiedPluginRegistry.installFunctionalPlugin /
 * catalog（已装判定经目录快照，与市场 UI 同一数据源）。经 `dagger.Lazy`
 * 装配（破环见 provideFusionBundleActivator 注记）——首条激活调用才解析。
 */
private class PluginInstallerPort(
    private val pluginsLazy: dagger.Lazy<com.apex.agent.plugin.host.functional.UnifiedPluginRegistry>
) : FusionPluginInstaller {
    override fun isInstalled(pluginId: String): Boolean =
        pluginsLazy.get().catalog.value.any {
            it.kind == com.apex.agent.plugin.api.PluginKind.FUNCTIONAL && it.id == pluginId
        }

    override suspend fun ensureInstalled(manifestJson: String): Result<Unit> =
        when (val r = pluginsLazy.get().installFunctionalPlugin(manifestJson)) {
            is com.apex.agent.plugin.host.functional.FunctionalPluginStore.InstallResult.Installed,
            is com.apex.agent.plugin.host.functional.FunctionalPluginStore.InstallResult.Upgraded,
            is com.apex.agent.plugin.host.functional.FunctionalPluginStore.InstallResult.Unchanged ->
                Result.success(Unit)
            is com.apex.agent.plugin.host.functional.FunctionalPluginStore.InstallResult.Rejected ->
                Result.failure(Exception(r.reason))
        }
}
