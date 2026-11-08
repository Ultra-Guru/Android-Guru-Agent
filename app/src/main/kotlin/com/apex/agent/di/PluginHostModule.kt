package com.apex.agent.di

import android.content.Context
import com.apex.agent.core.tools.ToolRegistry
import com.apex.agent.core.tools.extension.NodeInstallPort
import com.apex.agent.core.tools.extension.adapters.McpExtensionAdapter
import com.apex.agent.core.tools.mcp.McpServerConfig
import com.apex.agent.core.tools.pluginhost.bridge.PluginEventBus
import com.apex.agent.core.tools.pluginhost.bridge.PluginToolBridge
import com.apex.agent.core.tools.pluginhost.claude.ClaudeHookAdapter
import com.apex.agent.core.tools.pluginhost.claude.ClaudeHookPort
import com.apex.agent.core.tools.pluginhost.claude.ClaudeMcpBridge
import com.apex.agent.core.tools.pluginhost.lifecycle.PluginLifecycleManager
import com.apex.agent.core.tools.pluginhost.opencode.OpenCodePluginLoader
import com.apex.agent.core.tools.pluginhost.worker.BunRuntimeProvider
import com.apex.agent.core.tools.pluginhost.worker.NodeRuntimeProvider
import com.apex.agent.core.tools.pluginhost.worker.PluginWorkerSupervisor
import com.apex.agent.core.tools.pluginhost.worker.WorkerProcessPort
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * # PluginHostModule —— Native Plugin Host 2.0 的 app 层接线（Phase 6）
 *
 * core:tool-registry 的 pluginhost 包是纯 JVM 运行时（模型/Worker/桥/加载器），
 * 本模块把它接到真实子系统：
 *
 * - **事件总线 / 工具桥 / 生命周期管理器 / OpenCode 加载器**：core 同类
 *   直连（ToolRegistry 注入既有实例 —— 不建第二套注册表）；
 * - **Worker 进程端口**：[SandboxWorkerProcessPort] —— PRoot 沙箱内长驻
 *   node 进程（argv 构造对齐 ProotCommandSpawner 的 bind 约定）。
 *   ⚠️ 诚实边界：沙箱长驻进程的实机行为（rootfs 就绪度、bind 映射、
 *   ARM64 node 兼容）属**待设备验证**（spec §16.5：CI 的 x86_64 JVM 不能
 *   冒充 Android ARM64 结论）；CI 验证的是编译 + JVM E2E 链路；
 * - **Claude 兼容层**：钩子端口经 ExecEngine 沙箱通道；MCP 桥经既有
 *   McpManager 的 McpConfigPort 面（与 McpExtensionAdapter 同款注入）。
 */
@Module
@InstallIn(SingletonComponent::class)
object PluginHostModule {

    /** 宿主级协程域（SupervisorJob 隔离：插件崩溃不传染 app）。 */
    @Provides
    @Singleton
    @PluginHostScope
    fun providePluginHostScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides
    @Singleton
    fun providePluginEventBus(@PluginHostScope scope: CoroutineScope): PluginEventBus =
        PluginEventBus(scope)

    @Provides
    @Singleton
    fun providePluginToolBridge(toolRegistry: ToolRegistry): PluginToolBridge =
        PluginToolBridge(toolRegistry)

    @Provides
    @Singleton
    fun provideRuntimeProviders(
        nodeInstallPort: NodeInstallPort
    ): Pair<NodeRuntimeProvider, BunRuntimeProvider> =
        NodeRuntimeProvider(nodeInstallPort) to BunRuntimeProvider(nodeInstallPort)

    @Provides
    @Singleton
    fun provideWorkerProcessPort(
        @ApplicationContext context: Context
    ): WorkerProcessPort = SandboxWorkerProcessPort(context)

    @Provides
    @Singleton
    fun provideWorkerSupervisor(
        spawnPort: WorkerProcessPort,
        providers: Pair<NodeRuntimeProvider, BunRuntimeProvider>,
        @ApplicationContext context: Context,
        @PluginHostScope scope: CoroutineScope
    ): PluginWorkerSupervisor = PluginWorkerSupervisor(
        spawnPort = spawnPort,
        // OpenCode Worker 的已验证路径是 node（bun 探测分离，缺失如实降级）
        provider = providers.first,
        bootstrapDir = File(context.filesDir, "linux/extensions"),
        parentScope = scope
    )

    @Provides
    @Singleton
    fun provideOpenCodeLoader(
        supervisor: PluginWorkerSupervisor,
        toolBridge: PluginToolBridge,
        eventBus: PluginEventBus
    ): OpenCodePluginLoader = OpenCodePluginLoader(supervisor, toolBridge, eventBus)

    @Provides
    @Singleton
    fun provideLifecycleManager(
        supervisor: PluginWorkerSupervisor,
        toolBridge: PluginToolBridge,
        eventBus: PluginEventBus,
        ledger: com.apex.agent.core.tools.extension.ExtensionRegistry
    ): PluginLifecycleManager = PluginLifecycleManager(
        supervisor = supervisor,
        toolBridge = toolBridge,
        eventBus = eventBus,
        ledgerRemover = { pluginId ->
            runCatching { ledger.uninstall(pluginId) }
                .onFailure {
                    com.apex.agent.core.logging.AppLogger.instance.warn(
                        com.apex.agent.core.logging.LogCategory.PLUGIN,
                        "PluginHostModule",
                        "插件账本移除失败 id=$pluginId: ${it.message}"
                    )
                }
        }
    )

    @Provides
    @Singleton
    fun provideClaudeHookPort(@ApplicationContext context: Context): ClaudeHookPort =
        SandboxClaudeHookPort(context)

    @Provides
    @Singleton
    fun provideClaudeHookAdapter(port: ClaudeHookPort): ClaudeHookAdapter =
        ClaudeHookAdapter(port)

    @Provides
    @Singleton
    fun provideClaudeMcpBridge(
        mcpManager: com.apex.agent.core.tools.mcp.McpManager,
        @ApplicationContext context: Context
    ): ClaudeMcpBridge = ClaudeMcpBridge(
        ledgerDir = File(context.filesDir, "extensions"),
        port = object : McpExtensionAdapter.McpConfigPort {
            override suspend fun add(config: McpServerConfig): Result<Unit> =
                runCatching { mcpManager.addServer(config) }

            override suspend fun remove(name: String) {
                mcpManager.removeServer(name)
            }

            override fun all(): List<McpServerConfig> = mcpManager.getConfigs()
        }
    )
}
