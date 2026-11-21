package com.apex.agent.di

import android.content.Context
import com.apex.agent.core.engine.PromptStageRunner
import com.apex.agent.core.tools.DefaultToolRegistry
import com.apex.agent.core.tools.SafeAgentTool
import com.apex.agent.core.tools.builtin.context.PromptDumpTool
import com.apex.agent.core.tools.builtin.context.PromptSectionListTool
import com.apex.agent.core.tools.prompt.PromptSnapshot
import com.apex.agent.tools.ToolAuditLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * P97 快照源挂载点：DI 期把**深潜线引擎**（ApexAgentEngine）的 Prompt
 * 段级管道接给 `PromptDump` / `PromptSectionList` 工具（CodeModule 的
 * provideCodeAgentEngine 构造引擎后 attach；kit 三线在库内组装提示词，
 * 本仓不可及——快照的 `line` 字段如实标注）。
 */
@Singleton
class PromptPipelineHolder @Inject constructor() {

    @Volatile
    private var source: (() -> List<PromptSnapshot>)? = null

    /** 引擎构造点接线（幂等：后者覆盖前者——多引擎场景取最近装配的）。 */
    fun attach(pipeline: PromptStageRunner) {
        source = { pipeline.recentSnapshots() }
    }

    /** 最近快照（新→旧；未接线 = 空列表，工具按「未启用」口径说明）。 */
    fun recent(): List<PromptSnapshot> = source?.invoke() ?: emptyList()
}

/**
 * P97 Prompt 段级观测工具注册体 —— 卫星文件范式（ToolModule.kt 只加一行
 * 函数调用，见 ToolModuleCapabilityIntrospection 同款拆分惯例）。
 */
internal fun registerPromptIntrospectionTools(
    registry: DefaultToolRegistry,
    holder: PromptPipelineHolder,
    auditLogger: ToolAuditLogger,
    @ApplicationContext appContext: Context
) {
    registry.register(
        SafeAgentTool(
            PromptSectionListTool(
                snapshots = { holder.recent() }
            )
        )
    )
    registry.register(
        SafeAgentTool(
            PromptDumpTool(
                snapshots = { holder.recent() },
                dumpDir = { File(appContext.filesDir, "logs/prompt-dumps") },
                audit = { path, bytes ->
                    // 审计只记路径与字节数——正文与脱敏产物一律不进审计
                    auditLogger.log(
                        ToolAuditLogger.Event(
                            tool = "PromptDump",
                            decision = "persisted",
                            detail = "$path | $bytes chars"
                        )
                    )
                }
            )
        )
    )
}
