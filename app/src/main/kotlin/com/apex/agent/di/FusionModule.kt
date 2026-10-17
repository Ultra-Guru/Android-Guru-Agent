package com.apex.agent.di

import android.content.Context
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.core.tools.fusion.FusionBundlePresets
import com.apex.agent.core.tools.fusion.FusionBundleRegistry
import com.apex.agent.core.tools.fusion.rea.ReaCommandRunner
import com.apex.agent.core.tools.fusion.rea.ReaPageFetcher
import com.apex.agent.core.tools.fusion.rea.ReaWorkspace
import com.apex.agent.fusion.ReaCommandRunnerImpl
import com.apex.agent.fusion.ReaPageFetcherImpl
import com.apex.agent.github.GithubTokenManager
import com.apex.agent.platform.terminal.proot.PRootCapabilitySource
import com.apex.agent.platform.terminal.proot.PRootHostEnvironment
import com.apex.agent.platform.terminal.exec.ExecEngine
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
 * - [FusionBundleRegistry]（`filesDir/fusion-bundles`）+ 内置 REA 预置的
 *   幂等释放（installBundled —— @Provides 副作用模式，同 McpModule 的
 *   BuiltinXxxBootstrap 惯例）；
 * - REA 引擎运行时端口：命令执行（既有 ExecEngine + ProotCommandSpawner
 *   终端链，PRoot 优先回落权限链）与页面抓取（OkHttp）；
 * - 引擎工作目录（`filesDir/fusion` —— 证据导出/提取落盘根）。
 *
 * [com.apex.agent.fusion.FusionBundleController] 为 @Singleton @Inject
 * 自给（依赖本模块 + SkillModule/McpModule/AgentModule 既有绑定，无环）。
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
     * REA 命令执行端口 —— 复用 terminal.exec 的同一条执行链
     * （ExecEngine + ProotCommandSpawner：rootfs 就绪进沙箱，Android 命令
     * 回落 su > Shizuku > local-sh）。构造参数与 ToolModule 的
     * terminal.exec 装配逐项对齐（同一 rootfs/家目录/能力门语义）。
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
}
