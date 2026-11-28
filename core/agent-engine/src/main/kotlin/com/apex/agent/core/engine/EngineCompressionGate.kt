package com.apex.agent.core.engine

import com.apex.agent.core.tools.catalog.ToolRequestBudget
import com.apex.agent.core.tools.contextpolicy.ContextPreset
import com.apex.agent.core.engine.compression.TokenEstimator
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory

/**
 * P7 — 上下文压缩触发门（自 [ApexAgentEngine.maybeCompressContext] 迁出，
 * SRP 预算腾挪；模式与 EngineAskUserFlow 相同：
 * 同包顶层扩展 + internal 成员直调，引擎调用点零改动）。
 *
 * 每轮迭代开始时判定：估算 token + headroom 超过阈值 →
 * 1. #165 PreCompact 插桩（HookRegistry 可观测）；
 * 2. 压缩器收敛历史（preserveRecent 保护最近轮次）；
 * 3. 持久化 + 发射 [AgentEvent.ContextCompressed] 让 UI 感知。
 *
 * #826 — 门限计入 headroom（system prompt + 工具 schema 载荷）：
 * 旧公式只比「估算历史 vs 阈值」，但实际请求 = 历史 + system prompt（数 KB）
 * + tools 数组（[ToolRequestBudget.MAX_TOTAL_BYTES] 48KB 预算上限，实测常驻），
 * 图片另计（[TokenEstimator.IMAGE_TOKEN_ESTIMATE]）。8K 窗口模型的可用历史
 * 空间实为负数，压缩门却「永远不触发」—— 现按 history + headroom > threshold
 * 判定（[clampCompressionHeadroom] 钳制防小窗死循环）。
 *
 * 失败路径绝不中断主流程，但必须留痕（否则每轮无日志地反复触发
 * 同一个失败的压缩器，且 UI 无法感知上下文已逼近上限）。
 */
internal suspend fun ApexAgentEngine.maybeCompressContext(
    emit: suspend (AgentEvent) -> Unit,
    systemPromptTokens: Long = 0,
    toolsBudgetTokens: Long = 0
) {
    val compressor = contextCompressor ?: return

    val currentTokens = TokenEstimator.estimateHistory(conversationHistory)
    // P99：白名单档位（SettingsRepository.contextPolicyPreset）实时读取——
    // preset.maxTokens 只会更早压缩（coerceAtMost 引擎窗口，防越窗覆写）；
    // keepLastN 只裁非 SYSTEM 轮次（压缩器既有语义，SYSTEM 段永不裁）。
    val policyPreset = contextPolicyPresetProvider()?.let { ContextPreset.from(it) }
    val thresholdBaseTokens = (policyPreset?.maxTokens ?: config.maxContextTokens)
        .coerceAtMost(config.maxContextTokens)
    val thresholdTokens = (thresholdBaseTokens *
        thinkingController.resolveCompressionThreshold(config.compressionThreshold)).toInt()

    // #826：headroom 计入门限（输入语义见 [compressionGateInputs]）。
    val headroom = clampCompressionHeadroom(
        systemPromptTokens + toolsBudgetTokens, thresholdTokens.toLong()
    )
    if (currentTokens + headroom <= thresholdTokens) return

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
            "上下文压缩失败，本轮跳过压缩（tokens=${currentTokens}，阈值=${thresholdTokens}，" +
                "headroom=${headroom}）: ${e.message}",
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
 * #826 — headroom 钳制：上限 = 阈值窗口的 60%（[HEADROOM_CAP_NUMERATOR] /
 * [HEADROOM_CAP_DENOMINATOR]）。
 *
 * 极小窗口（如 8K 以下本地模型）的 system prompt + 工具 schema 载荷本身
 * 就可能逼近甚至超过阈值 —— 不钳制时 history + headroom > threshold
 * **恒成立**，压缩器每轮空转（压完仍超限再压，无解的压缩循环，preserveRecent
 * 保护下永远收敛不到门下）。钳到 60% 保证压缩门永远留有「history 可以达标」
 * 的解空间，最坏退化为「窗口 40% 起压」的激进策略而非死循环。
 * 负输入（异常调用方）折叠为 0。
 */
internal fun clampCompressionHeadroom(rawHeadroomTokens: Long, thresholdTokens: Long): Long {
    if (thresholdTokens <= 0) return 0
    val cap = thresholdTokens * HEADROOM_CAP_NUMERATOR / HEADROOM_CAP_DENOMINATOR
    return rawHeadroomTokens.coerceIn(0L, cap)
}

/**
 * #826 — 压缩门 headroom 输入（system prompt + 工具 schema 载荷的 token 估算）。
 *
 * 时序约束：压缩门在本轮工具计划 / 系统提示词构建**之前**运行（P7 位置，
 * 门压缩在请求组装前完成才有效，后移会造成「带着未压缩历史组装请求」），
 * 本轮的 plan 与 prompt 尚不存在 —— 故取**上一轮的实测值**：
 * - systemPromptTokens：[ApexAgentEngine.promptPipeline] 上一轮组装快照
 *   （[TokenEstimator.estimateText] 估算；跨任务残留的快照同样成立）；
 * - toolsBudgetTokens：[ApexAgentEngine.currentToolPlan].totalBytes ÷ 4
 *   （payloadBytes/4 启发，与 TokenEstimator 的 ASCII 口径同量级）。
 * 首轮（无快照 / 无计划）取保守常量：工具侧按 [ToolRequestBudget.MAX_TOTAL_BYTES]
 * 预算上限折算（48KB → 12K token 上界），system prompt 侧
 * [DEFAULT_SYSTEM_PROMPT_TOKENS]。
 */
internal fun ApexAgentEngine.compressionGateInputs(): Pair<Long, Long> {
    val systemPromptTokens = promptPipeline.lastSnapshot()
        ?.let { TokenEstimator.estimateText(it.systemPrompt).toLong() }
        ?: DEFAULT_SYSTEM_PROMPT_TOKENS
    val toolsBudgetTokens = currentToolPlan?.let { plan ->
        (plan.totalBytes / TOOLS_PAYLOAD_BYTES_PER_TOKEN).toLong()
    } ?: DEFAULT_TOOLS_PAYLOAD_TOKENS
    return systemPromptTokens to toolsBudgetTokens
}

/** headroom 钳制比例：3/5 = 60%（语义见 [clampCompressionHeadroom]）。 */
private const val HEADROOM_CAP_NUMERATOR = 3L
private const val HEADROOM_CAP_DENOMINATOR = 5L

/** 工具 schema 载荷 token 折算（bytes/token，ASCII 口径同源）。 */
private const val TOOLS_PAYLOAD_BYTES_PER_TOKEN = 4

/** 首轮无快照时的 system prompt 保守估算（数 KB 提示词 → 4K token 量级）。 */
internal const val DEFAULT_SYSTEM_PROMPT_TOKENS = 4_096L

/** 首轮无计划时的工具载荷保守估算：48KB 预算上限 ÷ 4。 */
internal const val DEFAULT_TOOLS_PAYLOAD_TOKENS = ToolRequestBudget.MAX_TOTAL_BYTES / 4L
