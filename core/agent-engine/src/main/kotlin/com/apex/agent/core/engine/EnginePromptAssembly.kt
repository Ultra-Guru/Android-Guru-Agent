package com.apex.agent.core.engine

import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory

/**
 * EnginePromptAssembly — 请求组装卫星文件（自 [ApexAgentEngine] 迁出，
 * SRP 预算腾挪；仓库卫星文件范式同 [EngineSkillPromptResolver]）。
 *
 * P97 接线：[buildSystemPrompt] 组装后经 [ApexAgentEngine.promptPipeline]
 * 派发段级钩子并落 `PromptSnapshot`（无钩子注册时提示词**逐字节一致**）；
 * [buildMessages] 消费钩子的 `DropTurns` 结果（SYSTEM 永不被裁）。
 */
internal suspend fun ApexAgentEngine.buildMessages(): List<LlmMessage> {
    repairDanglingToolCalls()
    val messages = mutableListOf<LlmMessage>()
    messages.add(LlmMessage.System(buildSystemPrompt()))
    val dropped = promptPipeline.lastDroppedTurnIndices
    messages.addAll(
        if (dropped.isEmpty()) conversationHistory
        else conversationHistory.filterIndexed { idx, _ -> idx !in dropped }
    )
    return messages
}

/**
 * P0 修复（会话级报废根因）：发送前修补悬空 tool_call 历史。
 *
 * 引擎在 `addMessage(Assistant(toolCalls))` 后、ToolResult 补齐前被中断
 * （cancel/进程被杀/AskUser 等待中退出）→ 历史末尾留下无配对 tool_calls
 * → OpenAI 兼容端点对后续每次请求都 400，会话级报废。现每次构建请求前
 * 幂等修补（[DanglingToolCallRepair]）：无悬空零开销，有则改写内存历史
 * 并持久化。diag-20261011：修复器升级为全面归一化（分离应答回迁/孤儿
 * 丢弃/重复 id 剥离），返回是否有修补供自愈重试判定。
 *
 * @return true = 历史被归一化改写（调用方可安全重试请求）
 */
internal fun ApexAgentEngine.repairDanglingToolCalls(): Boolean {
    val report = DanglingToolCallRepair.repair(conversationHistory)
    if (!report.hasRepairs) return false
    // #809：clear+addAll 复合替换经 historyLock 串行化（与 restoreHistory
    // 同语义；repair() 自身的迭代在 COW 快照上进行，天然安全）。
    synchronized(historyLock) {
        conversationHistory.clear()
        conversationHistory.addAll(report.repairedHistory)
    }
    memory?.save(conversationHistory)
    AppLogger.instance.warn(
        LogCategory.ENGINE, "AgentEngine",
        "History normalized: ${report.repairedCallIds.size} synthesized, " +
            "${report.movedCallIds.size} moved back, " +
            "${report.droppedToolResultIds.size} orphan toolResults dropped, " +
            "${report.strippedCallIds.size} duplicate declarations stripped",
        tags = arrayOf("dangling-repair")
    )
    return true
}

/**
 * 系统 prompt 组装（原引擎成员函数，P97 后挂入段级管道）：
 * [EnginePrompts.buildSystemPrompt] 渲染原文 → [ApexAgentEngine.promptPipeline]
 * 解析段/派发钩子（预算内变异）/落 `PromptSnapshot` 快照 → 返回最终文本。
 * 无钩子注册时与旧输出**逐字节一致**（管道只读直通）。
 */
internal suspend fun ApexAgentEngine.buildSystemPrompt(planningPhase: Boolean = false): String {
    val raw = EnginePrompts.buildSystemPrompt(
        config = config,
        currentProfile = thinkingController.profileFor(config),
        planningPhase = planningPhase,
        privilegeLevel = privilegeInfoProvider?.currentLevel() ?: "NORMAL_SHELL",
        visibleTools = EngineToolPlanner.visibleToolsFor(currentToolPlan, toolRegistry),
        catalogTools = EngineToolPlanner.catalogToolsFor(currentToolPlan, toolRegistry),
        toolNameMap = EngineToolPlanner.idToProviderName(currentToolPlan),
        toolsUnavailable = currentToolPlan?.tools?.isEmpty() == true &&
            toolDegradationLevel >= EngineToolPlanner.DEGRADATION_NO_TOOLS,
        skillPrompts = skillPromptInjections, // 解析在 EngineSkillPromptResolver.kt
        skillCatalog = skillCatalogDigests,
        environmentSummary = environmentInfoProvider?.environmentSummary(),
        connectedServices = connectedServicesProvider?.connectedServicesSummary(),
        // Issue #164：全局规则（引擎直注通道；coding 实例不设值，见 updateGlobalRules KDoc）
        globalRules = globalRulesText,
        // 聊天记忆召回（execute() 置位，finally 复位）。
        memoryContext = chatMemoryNote
    )
    // P97：段级管道（快照供 PromptDump/PromptSectionList 消费；钩子默认
    // 空注册 = 原文直通。toolIds 与请求 tools 数组同源（计划可见集）。
    return promptPipeline.run(
        rawPrompt = raw,
        turns = PromptSnapshotBuilder.turnsOf(conversationHistory),
        toolIds = currentToolPlan?.visibleRegistryIds?.sorted() ?: emptyList(),
        line = "deep_dive"
    )
}
