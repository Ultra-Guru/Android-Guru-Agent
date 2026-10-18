package com.apex.agent.core.code.loom.thinking

import com.apex.agent.core.code.thinking.CodeThinkingLevel

/**
 * # Loom Thinking Policy — 织造线思考档位策略
 *
 * 宿主七档思考（[CodeThinkingLevel]）在织造线的三重表达：
 *
 * 1. **梭回合预算倍率**（[roundBudgetMultiplier]）：档位越高、单次任务
 *    允许的往返越多（深任务需要更多经纬交织）；
 * 2. **努力取向文案**（[effortHint]）：织入思考段——告诉模型这一档
 *    期望的推理深度（档位语义对模型的传达通道）；
 * 3. **原生推理偏好**（[prefersNativeReasoning]）：按模型家族规则判断
 *    该模型是否原生流式推理（reasoningContent）——偏好原生的家族
 *    少写 in-band deliberate（避免双份思考），其余家族鼓励 deliberate。
 *
 * 与深潜线（七档全画像 + AUTO 预检）的分工：织造线**没有 AUTO 预检**
 * （currentThinkingDecision 恒 null）——档位即预算，简单直接。
 */
class LoomThinkingPolicy {

    /** 原生推理家族规则（model id 大小写不敏感子串匹配，先中先得）。 */
    private val nativeReasoningFamilies = listOf(
        "o1", "o3", "o4", // OpenAI o 系列
        "r1", "deepseek-r", // DeepSeek R 家族
        "qwen3", "qwq", // Qwen thinking 线
        "glm-z", "glm4.5", "glm-4.5", // GLM 思考系
        "thinking", "reasoner", "reasoning" // 通用命名惯例
    )

    /** 梭回合预算倍率（钳位 0.6..2.5）。 */
    fun roundBudgetMultiplier(level: CodeThinkingLevel): Float = when (level) {
        CodeThinkingLevel.NONE -> 0.6f
        CodeThinkingLevel.LIGHT -> 0.8f
        CodeThinkingLevel.STANDARD -> 1.0f
        CodeThinkingLevel.DEEP -> 1.4f
        CodeThinkingLevel.MAXIMUM -> 1.8f
        CodeThinkingLevel.ULTRACODE -> 2.2f
        CodeThinkingLevel.APEXCODE -> 2.5f
        CodeThinkingLevel.AUTO -> 1.2f // AUTO 落到织造线按中档预算
    }

    /** 努力取向文案（空串 = 不额外织入——NONE/LIGHT 档）。 */
    fun effortHint(level: CodeThinkingLevel): String = when (level) {
        CodeThinkingLevel.NONE -> ""
        CodeThinkingLevel.LIGHT -> "本任务浅思考即可：一两句权衡后直接行动。"
        CodeThinkingLevel.STANDARD -> "本任务按标准深度思考：明确方案再动手。"
        CodeThinkingLevel.DEEP -> "本任务要求深思考：多方案对比、预判风险后再动手。"
        CodeThinkingLevel.MAXIMUM ->
            "本任务要求最深推理：完整思维链、自我质疑、关键步骤双重验证后再动手。"
        CodeThinkingLevel.ULTRACODE ->
            "本任务为超高强度推理：先建立完整心智模型，逐层拆解，交叉验证每个假设。"
        CodeThinkingLevel.APEXCODE ->
            "本任务为极限推理：穷尽方案空间、对每个结论给出可证伪的依据，再动手。"
        CodeThinkingLevel.AUTO ->
            "本任务按中等深度思考：按需加深，避免过度分析。"
    }

    /** 该模型家族是否偏好原生流式推理（true = 少写 in-band 思考块）。 */
    fun prefersNativeReasoning(modelId: String?): Boolean {
        if (modelId.isNullOrBlank()) return false
        val lower = modelId.lowercase()
        return nativeReasoningFamilies.any { lower.contains(it) }
    }

    /** 思考段文案（努力取向 + 家族偏好合成；两空则空段）。 */
    fun deliberationDirective(level: CodeThinkingLevel, modelId: String?): String {
        val hint = effortHint(level)
        val native = if (prefersNativeReasoning(modelId)) {
            "本模型自带原生推理通道——优先使用原生思考，in-band 思考块仅在需要留痕时使用。"
        } else {
            ""
        }
        return listOf(hint, native).filter { it.isNotBlank() }.joinToString("\n")
    }
}
