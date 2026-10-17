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
     * 激活器装配（三端口编排中枢）。
     *
     * DI 破环：ToolModule 的注册表提供方需要控制器 → 控制器需要本激活器；
     * 若引擎端口直依赖 AgentEngine，则 AgentEngine（需要 ToolRegistry）
     * 与注册表提供方互为前置成 Dagger 环 —— 故注入 `Lazy<AgentEngine>`：
     * apply/restore 首次调用才解析，此时组件装配早已完成（UI/模型侧
     * 入口均晚于启动），环在图层面被 Lazy 断开。
     */
    @Provides
    @Singleton
    fun provideFusionBundleActivator(
        registry: FusionBundleRegistry,
        skillRegistry: SkillRegistry,
        mcpManager: McpManager,
        engineLazy: dagger.Lazy<AgentEngine>
    ): FusionBundleActivator = FusionBundleActivator(
        registry = registry,
        skillInstaller = SkillInstallerPort(skillRegistry),
        mcpConnector = McpConnectorPort(mcpManager),
        promptInjector = EnginePromptPort(engineLazy)
    )
}

// ── 三端口的 app 层适配（core 接口 → 真实子系统）────────────────────

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
