package com.apex.agent.di

import com.apex.agent.core.tools.ToolExecutorBuilder
import com.apex.agent.core.tools.hook.HookEvent
import com.apex.agent.core.tools.hook.HookRegistry
import com.apex.agent.core.tools.pluginhost.bridge.PluginEventBus
import com.apex.agent.core.tools.pluginhost.bridge.PluginEventPayloads

/**
 * 执行器工具事件插槽装配 —— 自 ToolModule.kt 拆分（SRP 行数预算卫星，
 * 模式同 ToolModuleFusionRegistration / ToolModuleRegistrySections）。
 *
 * Issue #165 —— PreToolUse/PostToolUse 插槽桥接 [HookRegistry]：
 * - isNoOp（无钩子关心）时返回 null，执行器走原路径零开销；
 * - blocked 透传 reason 短路（文案对齐 gate 拒绝）；
 * - modifiedArgs 改写后续 schema 校验按新参数执行（钩子改不出绕过校验的载荷）。
 *
 * Phase 6 收尾 —— [PluginEventBus] 工具事件生产者：BEFORE / AFTER(_FAILURE)
 * _TOOL_CALL 经总线广播，OpenCode 钩子转发与 Claude Hooks 订阅由此收到真实
 * 事件（此前总线零工具事件生产者，插件订阅永远空转）。
 *
 * 注：builder 的 setter 形参非空且持期望类型——经本函数条件装配，
 * lambda 才能推成 suspend（?.let{} / if/else 直传均不行，K2 限制，见
 * .verify/scratch/t2c/test/BridgePatternCheck.kt 三轮验证）。
 */
internal fun ToolExecutorBuilder.installToolHookSlots(
    hookRegistry: HookRegistry?,
    pluginEventBus: PluginEventBus?
): ToolExecutorBuilder {
    if (hookRegistry == null && pluginEventBus == null) return this
    beforeToolHooks { toolId, args ->
        pluginEventBus?.dispatch(
            PluginEventBus.PluginHostEvent.BEFORE_TOOL_CALL,
            PluginEventPayloads.toolCall(toolId, args.raw)
        )
        hookRegistry?.dispatch(HookEvent.PreToolUse(toolId, args))
            ?.takeUnless { it.isNoOp }
    }
    afterToolHooks { toolId, args, result, isError, durationMs ->
        pluginEventBus?.dispatch(
            if (isError) {
                PluginEventBus.PluginHostEvent.AFTER_TOOL_CALL_FAILURE
            } else {
                PluginEventBus.PluginHostEvent.AFTER_TOOL_CALL
            },
            PluginEventPayloads.toolCall(toolId, args.raw)
        )
        hookRegistry?.dispatch(
            HookEvent.PostToolUse(toolId, args, result, isError, durationMs)
        )
    }
    return this
}
