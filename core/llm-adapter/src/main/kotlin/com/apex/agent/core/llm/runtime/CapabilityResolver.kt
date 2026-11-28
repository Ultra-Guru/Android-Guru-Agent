package com.apex.agent.core.llm.runtime

import com.apex.agent.core.llm.ModelCapabilities

/**
 * T72 §二十一 — Provider / Profile 能力一致性解析。
 *
 * 修复 AUDIT-LLM 发现的"capability lie"：[com.apex.agent.core.llm.LlmConfig.fromProfile]
 * 旧实现直接写 `capabilities = profile.capabilities`，完全忽略
 * [com.apex.agent.core.llm.ProviderConfig.capabilities]。结果一个挂载在
 * text-only Provider 上的 Profile 仍可声称 `vision=true`，运行时静默把图片
 * 发给不支持视觉的端点。
 *
 * 本解析器按任务 §二十一 的明确定义计算 effective capabilities：
 *
 * ```
 * effectiveCapabilities = Provider capabilities ∩ Profile capabilities
 * ```
 *
 * 每个能力位取交集 —— 只有 Provider 与 Profile **同时**声明该能力，effective
 * 才为 true。这样：
 *  - 挂在 text-only Provider 上的 vision Profile → effective vision=false →
 *    [ModelRoleRouter] 不会把 VISION 请求路由给它，避免静默丢图。
 *  - 挂在 vision Provider 上的 vision Profile → effective vision=true → 正常路由。
 *
 * 对于用户未显式配置 Provider 能力的内置 Provider（如 `custom_openai`，默认
 * 仅 text/toolCalling/streaming），effective 视觉能力为 false。若用户在
 * 自定义 OpenAI 兼容端点上确实启用了视觉，应在 Settings 里把该 Provider 的
 * `capabilities.vision` 置 true（UI 已可编辑）。
 *
 * 这是**安全优先**的取舍：宁可让用户多勾一个开关，也不允许静默把图片发给
 * 不支持的端点。
 */
object CapabilityResolver {

    /**
     * 计算 effective capabilities。
     *
     * @param provider Provider 级能力，null 视为"全部未知"——退化为 Profile 自身能力
     *                  （向后兼容旧调用 / 单 Provider 无能力声明的场景）。
     * @param profile  Profile 级能力
     * @return 逐位 AND 后的 effective 能力
     */
    fun effective(
        provider: ModelCapabilities?,
        profile: ModelCapabilities
    ): ModelCapabilities {
        if (provider == null) return profile
        // 真机 P0 修复（发任何图片立即「当前模型不具备该任务所需的能力」）：
        // 能力模型里 imageInput 是 vision 的必要子集，但 17 个内置 Provider 只声明
        // `vision = true`、从未声明 `imageInput`（默认 false）→ 逐位 AND 后恒 false，
        // VISION 角色路由（要求 text+vision+imageInput）永远无候选。
        // 语义修正：vision 蕴含 imageInput（两级同理）——想关图片输入就关 vision；
        // 显式 imageInput=true 仍被尊重（Provider/Profile 任一方 vision 关闭时仍可
        // 单独保留图片输入，例如纯 image→text 的专用端点）。
        val providerAcceptsImages = provider.imageInput || provider.vision
        val profileAcceptsImages = profile.imageInput || profile.vision
        return ModelCapabilities(
            text = provider.text && profile.text,
            vision = provider.vision && profile.vision,
            toolCalling = provider.toolCalling && profile.toolCalling,
            structuredOutput = provider.structuredOutput && profile.structuredOutput,
            streaming = provider.streaming && profile.streaming,
            reasoning = provider.reasoning && profile.reasoning,
            jsonMode = provider.jsonMode && profile.jsonMode,
            imageInput = providerAcceptsImages && profileAcceptsImages,
            longContext = provider.longContext && profile.longContext,
        )
    }

    /**
     * 校验 [actual] 是否满足 [required] 的全部能力位。
     *
     * 满足条件：对 required 中每个为 true 的能力位，actual 对应位也必须为 true。
     * required 为 false 的位不约束 actual（不强求模型"不具备"某能力）。
     *
     * @return 不满足的能力位名称列表；空列表表示完全满足。
     */
    fun missingCapabilities(
        required: ModelCapabilities,
        actual: ModelCapabilities
    ): List<String> {
        val missing = mutableListOf<String>()
        if (required.text && !actual.text) missing += "text"
        if (required.vision && !actual.vision) missing += "vision"
        if (required.toolCalling && !actual.toolCalling) missing += "toolCalling"
        if (required.structuredOutput && !actual.structuredOutput) missing += "structuredOutput"
        if (required.streaming && !actual.streaming) missing += "streaming"
        if (required.reasoning && !actual.reasoning) missing += "reasoning"
        if (required.jsonMode && !actual.jsonMode) missing += "jsonMode"
        if (required.imageInput && !actual.imageInput) missing += "imageInput"
        if (required.longContext && !actual.longContext) missing += "longContext"
        return missing
    }

    /**
     * 便捷判定：[actual] 是否完全满足 [required]。
     */
    fun satisfies(
        required: ModelCapabilities,
        actual: ModelCapabilities
    ): Boolean = missingCapabilities(required, actual).isEmpty()
}
