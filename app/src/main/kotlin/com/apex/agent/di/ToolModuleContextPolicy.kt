package com.apex.agent.di

import com.apex.agent.core.engine.ContextPolicyState
import com.apex.agent.core.tools.DefaultToolRegistry
import com.apex.agent.core.tools.SafeAgentTool
import com.apex.agent.core.tools.builtin.context.ContextPolicyGet
import com.apex.agent.core.tools.builtin.context.ContextPolicySet
import com.apex.agent.tools.ToolAuditLogger
import com.apex.agent.ui.screen.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * P99 状态源挂载点：CodeModule 构造深潜线引擎后把 `currentContextPolicyState`
 * 接给 `ContextPolicyGet`（同源不另起计数；引擎侧组装档位/窗口/估算/压缩事实）。
 */
@Singleton
class ContextPolicyStateHolder @Inject constructor() {

    @Volatile
    private var source: (() -> ContextPolicyState?)? = null

    /** 引擎构造点接线（幂等：后者覆盖前者）。 */
    fun attach(provider: () -> ContextPolicyState) {
        source = { provider() }
    }

    fun state(): ContextPolicyState? = source?.invoke()
}

/**
 * P99 上下文治理工具注册体 —— 卫星文件范式（ToolModule.kt 只加一行函数
 * 调用，见 ToolModulePromptIntrospection 同款拆分惯例）。
 *
 * - `ContextPolicyGet`：状态源 = [ContextPolicyStateHolder]（引擎侧组装）；
 * - `ContextPolicySet`：写入 = `settingsRepository.updateAgentSettings`
 *   （原子持久化由其既有实现保证）；审计经 [ToolAuditLogger]（只记档位
 *   与理由摘要长度，不记值）。
 */
internal fun registerContextPolicyTools(
    registry: DefaultToolRegistry,
    stateHolder: ContextPolicyStateHolder,
    settingsRepository: SettingsRepository,
    auditLogger: ToolAuditLogger
) {
    registry.register(
        SafeAgentTool(
            ContextPolicyGet(state = { stateHolder.state() })
        )
    )
    registry.register(
        SafeAgentTool(
            ContextPolicySet(
                write = { preset, reason ->
                    runCatching {
                        settingsRepository.updateAgentSettings {
                            copy(contextPolicyPreset = preset)
                        }
                    }.isSuccess.also { ok ->
                        if (ok) {
                            auditLogger.log(
                                ToolAuditLogger.Event(
                                    tool = "ContextPolicySet",
                                    decision = "executed",
                                    detail = "preset=$preset | reason ${reason?.length ?: 0} chars (value not logged)"
                                )
                            )
                        }
                    }
                }
            )
        )
    )
}
