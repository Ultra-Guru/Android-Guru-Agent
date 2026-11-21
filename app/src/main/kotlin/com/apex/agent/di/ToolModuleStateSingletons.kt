package com.apex.agent.di

import com.apex.agent.core.tools.ShortcutRegistry
import com.apex.agent.core.tools.ToolCircuitBreaker
import com.apex.agent.core.tools.ToolEnvironmentState
import com.apex.agent.core.tools.ToolTraceRecorder
import com.apex.agent.core.tools.ToolUsageTracker
import com.apex.agent.core.tools.catalog.ToolActivationStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 工具系统无状态单例工厂（God-file 预算纪律：从 [ToolModule] 拆出——
 * Hilt @InstallIn(SingletonComponent) 自动聚合，零接线）。
 *
 * 六个纯工厂 provider：环境态 / 调用追踪 / 熔断 / 组合动作 / 使用统计 /
 * 会话激活存储，均一行构造体、无外部依赖，绑定语义与拆出前完全一致。
 */
@Module
@InstallIn(SingletonComponent::class)
object ToolModuleStateSingletons {

    /**
     * 工具使用统计（v2）：DefaultToolExecutor 每次调用后记录
     * 成败/耗时；设置页与诊断报告从这读取。单例，随进程存活。
     */
    @Provides
    @Singleton
    fun provideToolUsageTracker(): ToolUsageTracker = ToolUsageTracker()

    // ═══ Tool System v3 单例：环境态 / 追踪 / 熔断 / 组合动作 ═══

    /** 环境能力快照（EnvironmentStateUpdater 灌入；门控与 prompt 共用）。 */
    @Provides
    @Singleton
    fun provideToolEnvironmentState(): ToolEnvironmentState = ToolEnvironmentState()

    /** 结构化调用追踪（v3）：每工具调用一个 span，环形缓冲 + 监听器分发。 */
    @Provides
    @Singleton
    fun provideToolTraceRecorder(): ToolTraceRecorder = ToolTraceRecorder(capacity = 300)

    /** 工具熔断器（v3）：连续失败短路由，防止模型对已损坏工具的无效重试。 */
    @Provides
    @Singleton
    fun provideToolCircuitBreaker(): ToolCircuitBreaker = ToolCircuitBreaker()

    /** 组合动作注册表（v3）：ShortcutDefine 定义 → 热注册为一等工具。 */
    @Provides
    @Singleton
    fun provideShortcutRegistry(): ShortcutRegistry = ShortcutRegistry()

    // ═══ Tool System v4：会话激活 / 目录 / MCP 一等工具 ═══

    /** v4 — 会话激活存储（引擎/目录工具/编排器共享单例）。 */
    @Provides
    @Singleton
    fun provideToolActivationStore(): ToolActivationStore = ToolActivationStore()
}
