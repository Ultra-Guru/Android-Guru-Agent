package com.apex.agent.di

import android.content.Context
import com.apex.agent.core.tools.extension.ExtensionAdapterRegistry
import com.apex.agent.core.tools.extension.ExtensionDirLayout
import com.apex.agent.core.tools.extension.ExtensionInstaller
import com.apex.agent.core.tools.extension.ExtensionRegistry
import com.apex.agent.core.tools.extension.NodeInstallPort
import com.apex.agent.core.tools.extension.adapters.ClaudeExtensionAdapter
import com.apex.agent.core.tools.extension.adapters.ApkPackageExtensionAdapter
import com.apex.agent.core.tools.extension.adapters.DshExtensionAdapter
import com.apex.agent.core.tools.extension.adapters.McpExtensionAdapter
import com.apex.agent.core.tools.extension.adapters.OpenCodeExtensionAdapter
import com.apex.agent.core.tools.extension.adapters.SkillExtensionAdapter
import com.apex.agent.core.tools.fusion.FusionBundleRegistry
import com.apex.agent.core.tools.mcp.McpManager
import com.apex.agent.core.tools.mcp.McpServerConfig
import com.apex.agent.core.tools.skill.SkillActivationStore
import com.apex.agent.core.tools.skill.SkillRegistry
import com.apex.agent.github.GithubTokenManager
import com.apex.agent.marketplace.ExtensionMcpCatalogLookup
import com.apex.agent.marketplace.ExtensionTextFetcher
import com.apex.agent.platform.TerminalExec.ExecEngine
import com.apex.agent.platform.TerminalExec.ExecRequest
import com.apex.agent.platform.terminal.proot.PRootCapabilitySource
import com.apex.agent.platform.terminal.proot.PRootHostEnvironment
import com.apex.agent.tools.ProotCommandSpawner
import com.apex.agent.tools.PrivilegedCommandSpawner
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Singleton

/**
 * # ExtensionModule —— 统一扩展模型（市场完全重构）的 app 层接线
 *
 * core:tool-registry 的 extension 包是纯 JVM 闸门（resolve→inspect→install +
 * hash 门/确认门/账本/锁），本模块把适配器接到真实子系统：
 * - Skill：SkillRegistry 直连（core 内同类）+ 市场热加载同款激活闭环；
 * - MCP：McpManager 经 [McpExtensionAdapter.McpConfigPort] 端口暴露；
 * - OpenCode / DSH：ubuntu 域 Node 系安装（[provideNodeInstallPort] 经
 *   ExecEngine + ProotCommandSpawner，与 FusionModule 的 REA 命令链同款接线）；
 * - Claude：纯 android 域 zip 导入（FusionBundleRegistry + SkillRegistry 直连）。
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

    /** APK 系统安装端口绑定（接口 → ExtensionSources 的会话安装器实现）。 */
    @Provides
    @Singleton
    fun provideApkInstallPort(
        impl: com.apex.agent.marketplace.ExtensionApkInstallPort
    ): com.apex.agent.core.tools.extension.adapters.ApkPackageExtensionAdapter.ApkInstallPort = impl

    /** 沙箱扩展目录布局单例（适配器 install 与 ExtensionRun 共用同一 root）。 */
    @Provides
    @Singleton
    fun provideExtensionDirLayout(@ApplicationContext context: Context): com.apex.agent.core.tools.extension.ExtensionDirLayout =
        com.apex.agent.core.tools.extension.ExtensionDirLayout(File(context.filesDir, "linux/extensions"))

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
     * ubuntu 域 Node 系安装执行端口（OpenCode / DSH 适配器）：ExecEngine +
     * ProotCommandSpawner（rootfs 就绪进沙箱，Android 专有命令回落宿主链），
     * 与 FusionModule 的 REA 命令执行链同款接线（同 rootfs/home/能力门/PAT 语义）。
     */
    @Provides
    @Singleton
    fun provideNodeInstallPort(
        @ApplicationContext context: Context,
        hostEnvironment: PRootHostEnvironment,
        rootfsBaseDir: File,
        capabilitySource: PRootCapabilitySource,
        githubTokenManager: GithubTokenManager
    ): NodeInstallPort {
        val engine = ExecEngine(
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
        return object : NodeInstallPort {
            override suspend fun exec(
                command: List<String>,
                cwd: File,
                timeoutMs: Long
            ): Result<NodeInstallPort.ExecOutcome> = withContext(Dispatchers.IO) {
                runCatching {
                    // ExecRequest.command 为单字符串（shell 形态）——argv 单引号包安全拼接
                    val joined = command.joinToString(" ") { "'${it.replace("'", "")}'" }
                    val result = engine.execute(
                        ExecRequest(
                            command = joined,
                            cwd = cwd.absolutePath,
                            timeoutMs = timeoutMs,
                            maxOutputChars = 24_000
                        )
                    )
                    NodeInstallPort.ExecOutcome(
                        exitCode = result.exitCode ?: -1,
                        stdout = result.stdout,
                        stderr = result.stderr
                    )
                }
            }

            override suspend fun probeRuntime(): NodeInstallPort.RuntimeProbe {
                val extRoot = File(context.filesDir, "linux/extensions")
                val node = exec(listOf("node", "--version"), extRoot, 30_000).getOrNull()
                val npm = exec(listOf("npm", "--version"), extRoot, 30_000).getOrNull()
                return NodeInstallPort.RuntimeProbe(
                    nodeVersion = node?.takeIf { it.success }?.stdout?.trim()?.ifBlank { null },
                    npmVersion = npm?.takeIf { it.success }?.stdout?.trim()?.ifBlank { null }
                )
            }
        }
    }

    /** 适配器注册表（type → adapter 路由；doctor/resolve 引导消费可用面）。 */
    @Provides
    @Singleton
    fun provideExtensionAdapterRegistry(
        skillRegistry: SkillRegistry,
        skillActivation: SkillActivationStore,
        textFetcher: ExtensionTextFetcher,
        mcpConfigPort: McpExtensionAdapter.McpConfigPort,
        catalogLookup: ExtensionMcpCatalogLookup,
        fusionRegistry: FusionBundleRegistry,
        nodeInstallPort: NodeInstallPort,
        apkInstallPort: com.apex.agent.core.tools.extension.adapters.ApkPackageExtensionAdapter.ApkInstallPort,
        layout: com.apex.agent.core.tools.extension.ExtensionDirLayout
    ): ExtensionAdapterRegistry {
        return ExtensionAdapterRegistry(
            listOf(
                SkillExtensionAdapter(
                    skillRegistry = skillRegistry,
                    activation = skillActivation,
                    fetchText = { url -> textFetcher.fetch(url) }
                ),
                McpExtensionAdapter(
                    configs = mcpConfigPort,
                    catalogLookup = { name -> catalogLookup.lookup(name) }
                ),
                OpenCodeExtensionAdapter(port = nodeInstallPort, layout = layout),
                ClaudeExtensionAdapter(
                    fusionRegistry = fusionRegistry,
                    skillRegistry = skillRegistry,
                    fetchBytes = { url -> textFetcher.fetchBytes(url) }
                ),
                DshExtensionAdapter(port = nodeInstallPort, layout = layout),
                ApkPackageExtensionAdapter(port = apkInstallPort)
            )
        )
    }

    /** 统一安装闸门（resolve/inspect/install 编排 + hash 门/确认门 + 账本/锁）。 */
    @Provides
    @Singleton
    fun provideExtensionInstaller(
        registry: ExtensionRegistry,
        adapters: ExtensionAdapterRegistry
    ): ExtensionInstaller = ExtensionInstaller(adapters = adapters, registry = registry)

    /**
     * 启动期自动同步（PR-E）：设置开关 + 清单存在才评估；漂移/失败通知。
     * @Provides 副作用启动（同 SkillModule.releaseBundledSkills 惯例——
     * 自持 IO 协程，失败只记日志不阻断启动）。
     */
    @Provides
    @Singleton
    fun provideExtensionAutoSync(
        @ApplicationContext context: Context,
        installer: ExtensionInstaller,
        registry: ExtensionRegistry,
        settingsRepository: com.apex.agent.ui.screen.settings.SettingsRepository,
        notifications: com.apex.agent.notify.ApexNotifications
    ): com.apex.agent.marketplace.ExtensionAutoSync =
        com.apex.agent.marketplace.ExtensionAutoSync(context, installer, registry, settingsRepository, notifications)
            .also { it.start() }
}
