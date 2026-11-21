package com.apex.agent.di

import android.content.Context
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.core.tools.ToolExecutor
import com.apex.agent.core.tools.ToolExecutorBuilder
import com.apex.agent.core.tools.ToolRegistry
import com.apex.agent.plugin.api.PluginContract
import com.apex.agent.plugin.host.PluginManager
import com.apex.agent.plugin.host.functional.ApkPluginSource
import com.apex.agent.plugin.host.functional.DefaultApkPluginSource
import com.apex.agent.plugin.host.functional.FunctionalPluginStore
import com.apex.agent.plugin.host.functional.MacroStepExecutor
import com.apex.agent.plugin.host.functional.UnifiedPluginRegistry
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
 * # Plugin System v2 装配（双类型插件体系，docs/plugin-system-v2.md）
 *
 * 功能性插件（FUNCTIONAL，plugin.json 数据）与 APK 插件（APK，AIDL 代码）
 * 的宿主侧生产接线：
 *
 * - [FunctionalPluginStore]：根目录固定在 filesDir 之下的
 *   plugins/functional（[PluginContract.FUNCTIONAL_PLUGINS_DIR]）；
 *   core 层不依赖 core:logging——与 SkillRegistry 同款注入式日志桥，
 *   这里接到 AppLogger（LogCategory.PLUGIN）；
 * - [MacroStepExecutor] 执行器：宏步骤经 ToolExecutor 管线执行（查找
 *   建议 / schema 校验 / 错误折叠），**不带 UI 门控**——外层宏工具调用的
 *   风险门已是安全边界（宏风险按步骤最大值钳制，见 MacroToolAdapter）。
 *   独立限定符绑定，不与引擎执行器的既有绑定（带完整权限/风险/钩子门）
 *   冲突；
 * - [UnifiedPluginRegistry]：统一门面单例。专用 SupervisorJob + IO scope
 *   承载 APK 加载态流的 collect（插件进程死亡 / 卸载 → 目录即时对账）；
 * - [ApkPluginSource]：DefaultApkPluginSource 经本模块 @Provides 绑定到
 *   接口（PluginManager.loadedPlugins 的纯数据投影）。
 */
@Module
@InstallIn(SingletonComponent::class)
object PluginModule {

    /** 功能性插件注册表流对账专用 scope（失败隔离，不殊及其它单例初始化）。 */
    private val pluginScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides
    @Singleton
    fun provideFunctionalPluginStore(
        @ApplicationContext context: Context
    ): FunctionalPluginStore = FunctionalPluginStore(
        rootDir = File(context.filesDir, PluginContract.FUNCTIONAL_PLUGINS_DIR),
        logger = { level, message ->
            when (level) {
                FunctionalPluginStore.Level.INFO -> AppLogger.instance.info(
                    LogCategory.PLUGIN, "FunctionalPluginStore", message
                )
                FunctionalPluginStore.Level.WARN -> AppLogger.instance.warn(
                    LogCategory.PLUGIN, "FunctionalPluginStore", message
                )
            }
        }
    )

    /**
     * APK 侧纯数据门面：PluginManager.loadedPlugins（Android binder 类型）
     * 投影成 Map<包名, 展示名> 快照流——UnifiedPluginRegistry 由此保持
     * 纯 JVM（见 DefaultApkPluginSource KDoc）。
     */
    @Provides
    @Singleton
    fun provideApkPluginSource(pluginManager: PluginManager): ApkPluginSource =
        DefaultApkPluginSource(pluginManager)

    /**
     * 宏步骤执行器：管线基础能力（查找 / 校验 / 错误折叠）继承自
     * [ToolExecutorBuilder]，门控链刻意为空——宏工具外层调用的风险门
     * （宏风险已按步骤最大值钳制）是安全边界，内部步骤不再二次弹窗。
     */
    @Provides
    @Singleton
    @MacroStepExecutor
    fun provideMacroStepExecutor(registry: ToolRegistry): ToolExecutor =
        ToolExecutorBuilder(registry).build()

    @Provides
    @Singleton
    fun provideUnifiedPluginRegistry(
        store: FunctionalPluginStore,
        apkSource: ApkPluginSource,
        toolRegistry: ToolRegistry,
        @MacroStepExecutor stepExecutor: ToolExecutor
    ): UnifiedPluginRegistry = UnifiedPluginRegistry(
        store = store,
        apkSource = apkSource,
        toolRegistry = toolRegistry,
        stepExecutor = stepExecutor,
        scope = pluginScope,
        logger = { level, message ->
            when (level) {
                FunctionalPluginStore.Level.INFO -> AppLogger.instance.info(
                    LogCategory.PLUGIN, "UnifiedPluginRegistry", message
                )
                FunctionalPluginStore.Level.WARN -> AppLogger.instance.warn(
                    LogCategory.PLUGIN, "UnifiedPluginRegistry", message
                )
            }
        }
    )
}
