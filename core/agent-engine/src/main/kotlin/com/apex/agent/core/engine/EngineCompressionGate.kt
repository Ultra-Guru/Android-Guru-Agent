package com.apex.agent.core.engine

import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.ToolDefinition
import com.apex.agent.core.tools.contextpolicy.ContextPreset
import com.apex.agent.core.engine.compression.TokenEstimator
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory

/**
 * P7 — 上下文压缩触发门（自 [ApexAgentEngine.maybeCompressContext] 迁出，
 * SRP 预算腾挪；模式与 EngineAskUserFlow 相同：
 * 同包顶层扩展 + internal 成员直调，引擎调用点零改动）。
 *
 * 每轮迭代开始时判定：估算 token 超过阈值 →
 * 1. #165 PreCompact 插桩（HookRegistry 可观测）；
 * 2. 压缩器收敛历史（preserveRecent 保护最近轮次）；
 * 3. 持久化 + 发射 [AgentEvent.ContextCompressed] 让 UI 感知。
 *
 * 失败路径绝不中断主流程，但必须留痕（否则每轮无日志地反复触发
 * 同一个失败的压缩器，且 UI 无法感知上下文已逼近上限）。
 */
internal suspend fun ApexAgentEngine.maybeCompressContext(emit: suspend (AgentEvent) -> Unit) {
    val compressor = contextCompressor ?: return

    // #826：组装态口径 —— 历史只是真实请求的一部分；system prompt（每轮
    // 重建，工具目录 + 技能 + 环境摘要）与 tools 数组载荷是「固定开销」，
    // 旧实现完全不计量 → 8K 窗口模型固定开销即可超窗，压缩门却永远
    // 「合格」，只能等服务端 400 兜底。lastRequestOverheadTokens 在组装点
    // 顺手计量缓存（见 executeBuildLoop）；首轮（尚未组装）为 0，首轮
    // 历史本来就小，无害。
    val currentTokens = TokenEstimator.estimateHistory(conversationHistory) +
        lastRequestOverheadTokens
    // P99：白名单档位（SettingsRepository.contextPolicyPreset）实时读取——
    // preset.maxTokens 只会更早压缩（coerceAtMost 引擎窗口，防越窗覆写）；
    // keepLastN 只裁非 SYSTEM 轮次（压缩器既有语义，SYSTEM 段永不裁）。
    val policyPreset = contextPolicyPresetProvider()?.let { ContextPreset.from(it) }
    val thresholdBaseTokens = (policyPreset?.maxTokens ?: config.maxContextTokens)
        .coerceAtMost(config.maxContextTokens)
    val thresholdTokens = (thresholdBaseTokens *
        thinkingController.resolveCompressionThreshold(config.compressionThreshold)).toInt()

    if (currentTokens <= thresholdTokens) return

    // #165：PreCompact——自动压缩（阈值已判定）。
    sessionHooks?.onPreCompact()

    // 需要压缩
    val report = try {
        compressor.compress(
            history = conversationHistory,
            preserveRecent = policyPreset?.keepLastN ?: config.preserveRecentTurns
        )
    } catch (e: Exception) {
        // 压缩失败不应该中断主流程，但必须留痕：否则每轮迭代都会无日志地
        // 反复触发同一个失败的压缩器，且 UI 无法感知上下文已逼近上限。
        AppLogger.instance.error(
            LogCategory.ENGINE, "ApexAgentEngine",
            "上下文压缩失败，本轮跳过压缩（tokens=${currentTokens}，阈值=${thresholdTokens}）: ${e.message}",
            e
        )
        return
    }

    // 同步到持久化记忆（如果存在）
    memory?.save(conversationHistory)

    // P99：压缩事实置位（ContextPolicyGet 口径；「压缩前抢救」保护由
    // SessionContextArchive 既有纪律承担，本门无关闭开关）。
    lastContextCompressed = true

    // 压缩后失效陈旧真实 usage（圆环口径修复 2026-11）：lastRealUsage 是
    // 压缩前全上下文的统计，保留会让 currentTokenCount 在压缩后回跳到
    // 压缩前水位 —— 回落估算器口径，下一轮真实 usage 到达前按压缩后
    // 历史估算，与 ContextCompressed.afterTokens 一致。
    usageTracker.resetRealUsage()

    // 发射压缩事件
    emit(
        AgentEvent.ContextCompressed(
            beforeTokens = report.beforeTokens,
            afterTokens = report.afterTokens,
            strategy = report.strategy.name,
            summary = report.summary.take(200),
            messagesRemoved = report.messagesRemoved,
            messagesTruncated = report.messagesTruncated
        )
    )
}

/**
 * #826 — 请求固定开销估算（system prompt + tools 数组载荷的 token 量）。
 *
 * 在组装点（buildMessages + buildToolPlan 之后）由 executeBuildLoop 调用，
 * 结果缓存进 [ApexAgentEngine.lastRequestOverheadTokens] 供压缩门读取 ——
 * 避免压缩门重复整段估算（system prompt 每轮已重建，计量即组装副产品）。
 * tools 侧按 name + description + parametersSchema 字符数折算（与
 * ToolRequestBudget.MAX_TOTAL_BYTES 的字节口径同源，token ≈ bytes/4）。
 */
internal fun estimateRequestOverhead(
    messages: List<LlmMessage>,
    tools: List<ToolDefinition>
): Int {
    val promptTokens = (messages.firstOrNull() as? LlmMessage.System)
        ?.let { TokenEstimator.estimateText(it.content) } ?: 0
    val toolsBytes = tools.sumOf { it.name.length + it.description.length + it.parametersSchema.length }
    return promptTokens + toolsBytes / 4
}
