package com.apex.agent.di

import com.apex.agent.core.tools.DefaultToolRunPolicyResolver
import com.apex.agent.core.tools.hook.HookEvent
import com.apex.agent.core.tools.hook.HookRegistry
import com.apex.agent.core.tools.ToolCircuitBreaker
import com.apex.agent.core.tools.ToolEnvironmentGate
import com.apex.agent.core.tools.ToolEnvironmentState
import com.apex.agent.core.tools.ToolExecutor
import com.apex.agent.core.tools.ToolExecutorBuilder
import com.apex.agent.core.tools.ToolRateLimiter
import com.apex.agent.core.tools.ToolRegistry
import com.apex.agent.core.tools.ToolTraceRecorder
import com.apex.agent.core.tools.ToolUsageTracker
import com.apex.agent.vault.SecretRedactor
import com.apex.agent.vault.SecretRedactingExecutor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * # Engine Tool Executors Module — 引擎级执行器装配（仅环境门）
 *
 * 从 ToolModule 按职责缝拆出（单文件行预算纪律）：**自带权限门的引擎线**
 * 专用执行器装配——与主执行器（PermissionAwareToolGate 组合门）分开，
 * 防同一动作被问两次（#230）。
 *
 * | 执行器 | 消费方 | 引擎侧权限门 |
 * |---|---|---|
 * | @Named("standardEngineTools") | StandardKitAdapter（标准线） | standard-loop 库内权限门 |
 * | @Named("loomEngineTools") | LoomEngine（织造线） | LoomPermissionGovernor |
 * | @Named("courierEngineTools") | CourierEngine（信使线） | CourierPermitGate（勘合门） |
 *
 * 共同形态：限流 / 熔断 / 策略 / 追踪 / 钩子 / 脱敏全部保留，**权限确认
 * 归引擎层独占**。深潜线（CodeAgentEngine 包装 ApexAgentEngine——无引擎
 * 级门）与 Agent 聊天线继续用主执行器（见 ToolModule.provideToolExecutor）。
 */
@Module
@InstallIn(SingletonComponent::class)
object EngineToolExecutorsModule {

    /** 标准线执行器（#230 防双弹窗——历史语义见 git blame，v1.5 起由本模块承载）。 */
    @Provides
    @Singleton
    @javax.inject.Named("standardEngineTools")
    fun provideStandardEngineToolExecutor(
        registry: ToolRegistry,
        toolUsageTracker: ToolUsageTracker,
        environmentState: ToolEnvironmentState,
        traceRecorder: ToolTraceRecorder,
        circuitBreaker: ToolCircuitBreaker,
        hookRegistry: HookRegistry,
        secretRedactor: SecretRedactor
    ): ToolExecutor = buildEnvOnlyExecutor(
        registry, toolUsageTracker, environmentState, traceRecorder,
        circuitBreaker, hookRegistry, secretRedactor
    )

    /**
     * 织造线执行器：织造引擎自带权限三态门（OBSERVE/FABRIC/UNBOUND ×
     * READ/WRITE × 工作区边界 + 会话批准记忆）——同装**仅环境门**。
     */
    @Provides
    @Singleton
    @javax.inject.Named("loomEngineTools")
    fun provideLoomEngineToolExecutor(
        registry: ToolRegistry,
        toolUsageTracker: ToolUsageTracker,
        environmentState: ToolEnvironmentState,
        traceRecorder: ToolTraceRecorder,
        circuitBreaker: ToolCircuitBreaker,
        hookRegistry: HookRegistry,
        secretRedactor: SecretRedactor
    ): ToolExecutor = buildEnvOnlyExecutor(
        registry, toolUsageTracker, environmentState, traceRecorder,
        circuitBreaker, hookRegistry, secretRedactor
    )

    /**
     * 信使线执行器：信使引擎自带勘合门（`*`/`?` 通配则例三态法则 +
     * always 记忆 + 复合命令逐段核验）——同装**仅环境门**。
     */
    @Provides
    @Singleton
    @javax.inject.Named("courierEngineTools")
    fun provideCourierEngineToolExecutor(
        registry: ToolRegistry,
        toolUsageTracker: ToolUsageTracker,
        environmentState: ToolEnvironmentState,
        traceRecorder: ToolTraceRecorder,
        circuitBreaker: ToolCircuitBreaker,
        hookRegistry: HookRegistry,
        secretRedactor: SecretRedactor
    ): ToolExecutor = buildEnvOnlyExecutor(
        registry, toolUsageTracker, environmentState, traceRecorder,
        circuitBreaker, hookRegistry, secretRedactor
    )

    /** 仅环境门执行器装配（三线同构——策略常量与主执行器同口径）。 */
    private fun buildEnvOnlyExecutor(
        registry: ToolRegistry,
        toolUsageTracker: ToolUsageTracker,
        environmentState: ToolEnvironmentState,
        traceRecorder: ToolTraceRecorder,
        circuitBreaker: ToolCircuitBreaker,
        hookRegistry: HookRegistry,
        secretRedactor: SecretRedactor
    ): ToolExecutor = SecretRedactingExecutor(
        delegate = ToolExecutorBuilder(registry)
            .gate(ToolEnvironmentGate(environmentState))
            .usageTracker(toolUsageTracker)
            .policyResolver(
                DefaultToolRunPolicyResolver(
                    TERMINAL_TOOL_RUN_POLICIES + MARKET_TOOL_RUN_POLICIES + JIGUAN_TOOL_RUN_POLICIES,
                    OPEN_WORLD_PREFIX_TOOL_RUN_POLICIES
                )
            )
            .rateLimiter(ToolRateLimiter())
            .breaker(circuitBreaker)
            .tracer(traceRecorder)
            .apply {
                beforeToolHooks { toolId, args ->
                    hookRegistry.dispatch(HookEvent.PreToolUse(toolId, args))
                        .takeUnless { it.isNoOp }
                }
                afterToolHooks { toolId, args, result, isError, durationMs ->
                    hookRegistry.dispatch(
                        HookEvent.PostToolUse(toolId, args, result, isError, durationMs)
                    )
                }
            }
            .build(),
        redactor = secretRedactor
    )
}
