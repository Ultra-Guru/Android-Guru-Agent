package com.apex.agent.ui.screen.code

import com.apex.agent.core.code.subagent.SubAgentPrompts
import com.apex.agent.ui.screen.memory.profile.topForInjection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 发送前引擎上下文组装（perf P0-2，自 [CodeViewModel.runEngine] 同步段
 * 迁出 —— 仓库 Ops 扩展模式同 CodeOutputModeOps.kt）。
 *
 * 旧实现这整段跑在 UI 线程（runEngine 在 viewModelScope.launch 之前同步
 * 执行）：用户画像快照（首次调用同步读盘）、功能聚合/子代理目录组装、
 * 经验召回、AUTO 预检（读归档）、以及 prepareForTask → refreshContext 的
 * **同步磁盘 IO**（工作区探测 contextProvider.provide + 项目规则读取
 * rulesProvider.loadProjectRules，注释自证「调用方 = VM 的 prepareForTask
 * 链路」）——手机/PRoot 上几十~几百毫秒随机 IO 全部压在「发送」点击与
 * runJob 启动之间，即点击发送先冻结再出字的主因。
 *
 * 迁出后调用点在 runJob（主调度器）内：本扩展挂起，[prepareForTask] 的
 * 同步 IO 以 `withContext(Dispatchers.IO)` 隔离；其余段为内存读-改-写
 * （_uiState/StateFlow 线程安全），随段在 IO 调度器执行无害。
 *
 * 顺序契约（原语义不变）：extras 组装 → resolveRuntimeThinkingLevel
 * （预检档位先落引擎字段）→ prepareForTask（refreshContext 按档注入
 * 编码特化指令）——引擎在 execute 启动前就绪，与旧时序一致。
 */
internal suspend fun CodeViewModel.prepareEngineContext(goal: String) {
    // Issue #164：发送前同步全局规则（设置页改动无需重启，下轮生效）。
    codeEngineImpl?.updateGlobalRules(settingsRepository.agentSettings.value.globalRules)

    // ═══ #197 「小圆环」函数调用会话附加段 + v4 强制工具计划 ═══
    // 功能聚合注入段（16 项能力）：小圆环在前、功能聚合在后；用户画像
    // （五维长期特征）置信度 Top-8 段拼最前（个性化基线）；v1.9 角色经验
    // 卡：召回的相关经验段拼末位（同类任务先读历史教训）。
    val profileSection = userProfileStore.snapshotBlocking()
        .topForInjection()
        .toSystemContextSection()
    // v1.5.17 自定义子代理类型目录：CodeTask 的工具描述是静态的（DI 期
    // 构建），自定义角色对主模型不可见——目录拼进会话附加段（空清单零注入）。
    val baseExtras = neonOrbStore.mergeWith(toolkitStore.buildSessionContext())
        .let { base ->
            SubAgentPrompts.customTypeCatalog(
                settingsRepository.agentSettings.value.subagent.customTypes
            ).takeIf { it.isNotBlank() }?.let { "$base\n\n$it" } ?: base
        }
    codeEngineImpl?.updateSessionExtras(
        roleExperience.enrichExtras(
            base = if (profileSection == null) baseExtras else "$profileSection\n\n$baseExtras",
            goal = goal
        )
    )
    codeEngineImpl?.updateForcedTools(
        forcedToolIds = toolkitStore.forcedToolIds(),
        exposeAll = toolkitStore.exposeAllToolsEnabled()
    )

    // ═══ AUTO 档自治：发送前预检选档 ═══
    // 先解析、后 prepareForTask（旧序「先 refresh 再预检」会让深潜线 AUTO
    // 的编码指令恒滞后一轮）；非 AUTO 档直接用用户显式档（幂等）。
    resolveRuntimeThinkingLevel(goal)

    // JIT 上下文刷新：工作区探测 + 项目规则读取为同步磁盘 IO，隔离到
    // IO 调度器（接口零变更：CodeEngineFacade.prepareForTask 保持同步签名，
    // 线程归属由调用方决定）。
    withContext(Dispatchers.IO) {
        codeEngineImpl?.prepareForTask()
    }
}
