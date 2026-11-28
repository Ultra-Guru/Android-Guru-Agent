package com.apex.agent.core.engine

import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.core.engine.task.DanglingToolCallRepair

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
    stripConsumedHistoryImages()
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
 * #827 — 图片生命周期：首轮已消费的图片不再逐轮重传。
 *
 * 模型在含图轮次的 ReAct 请求里已经看过 base64 并产出文字描述/工具调用；
 * 此后每轮重发同一张图的边际价值趋零，却按 vision 全价计费、内存常驻。
 * 本函数把「已被模型响应过」的带图 User 消息（其后存在 Assistant /
 * ToolResult 即为响应证据；System 提示不算 —— 首轮组装前也可能先注入
 * System 注记）就地改写为文本占位：
 *
 * - 请求体不再携带原图 base64（第 2 轮起），vision 路由随之回落 PRIMARY；
 * - 内存历史与压缩门口径同步（占位文本按普通文本估算，不再恒计图片成本）；
 * - 幂等：无符合条件的消息时零开销直接返回（历史无图 = 一次 none 检查）。
 *
 * 仍处于尾部（尚无模型响应）的带图 User 消息保留原图 —— 本轮 vision
 * 请求需要它。改写经 [historyLock] 串行化（与 repairDanglingToolCalls /
 * restoreHistory 同语义），并同步持久化。
 */
internal fun ApexAgentEngine.stripConsumedHistoryImages() {
    if (conversationHistory.none { it is LlmMessage.User && it.images.isNotEmpty() }) return
    var changed = false
    val rewritten = conversationHistory.mapIndexed { idx, msg ->
        if (msg is LlmMessage.User && msg.images.isNotEmpty() &&
            conversationHistory.drop(idx + 1)
                .any { it is LlmMessage.Assistant || it is LlmMessage.ToolResult }
        ) {
            changed = true
            msg.copy(
                images = emptyList(),
                content = msg.content +
                    "\n[Images attached to this turn were already shown to you earlier; " +
                    "they are omitted from later requests to save context.]"
            )
        } else msg
    }
    if (!changed) return
    synchronized(historyLock) {
        conversationHistory.clear()
        conversationHistory.addAll(rewritten)
    }
    memory?.save(conversationHistory)
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
        memoryContext = chatMemoryNote,
        // P102：本轮激活能力清单（数据源注入——提示词层零 IO；空行集
        // 整段省略，500 token 预算内按序截断见 EnginePrompts）。
        activeExtensions = buildActiveExtensions()
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

/**
 * P102：本轮激活能力清单组装（引擎侧数据源：已激活技能 / 工具激活存储 +
 * 已连接服务工具 / 当前思考档位；未知状态整行省略——fail-open 不教假事实；
 * MCP 服务器名与 Fusion 激活集不在引擎可达面，行省略）。
 */
internal fun ApexAgentEngine.buildActiveExtensions(): List<String> {
    val lines = mutableListOf<String>()
    // 已激活技能（SkillActivate 的会话激活集；与 ## Active Skills 全文注入分工）
    skillActivation?.snapshot()?.takeIf { it.isNotEmpty() }?.let { ids ->
        lines += "Skills: " + ids.sorted().joinToString(", ")
    }
    // 已展开工具（ToolActivationStore 会话激活 + 已连接服务并入的工具 id，
    // 上限 24 与 ToolActivationStore.maxActive 同口径）
    val opened = (toolActivation.snapshot() +
        (connectedServicesProvider?.connectedToolIds() ?: emptySet()))
        .sorted()
        .take(ACTIVE_EXTENSIONS_TOOLS_CAP)
    if (opened.isNotEmpty()) {
        lines += "Opened tools: " + opened.joinToString(", ")
    }
    // 思考档位（决策关键——预算内永不截断，恒末行）
    val profile = thinkingController.profileFor(config)
    val reason = thinkingController.lastDecision?.reason
    lines += "Thinking: " + profile.level.name + (reason?.let { " (${it.take(REASON_HEAD_CHARS)})" } ?: "")
    return lines
}

private const val ACTIVE_EXTENSIONS_TOOLS_CAP = 24
private const val REASON_HEAD_CHARS = 60

