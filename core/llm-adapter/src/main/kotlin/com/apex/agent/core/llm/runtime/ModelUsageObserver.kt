package com.apex.agent.core.llm.runtime

import com.apex.agent.core.llm.Usage

/**
 * 每次真实 LLM 请求的用量观测点（#221）。
 *
 * 为什么需要它：仪表盘旧口径在 `AgentEvent.UsageUpdated` 到达时取**当前默认
 * Profile** 记账，于是
 *
 * - 角色路由（VISION / SUMMARY / REASONING）用到非默认模型时，消耗被记到
 *   默认模型头上 —— 「哪个模型烧钱」的归因完全错误；
 * - 子代理、上下文压缩摘要这些**不经过主引擎事件流**的请求根本不入账 ——
 *   账单被系统性低估。
 *
 * 观测点放在 [ModelRuntime] 执行层（`DefaultModelRuntime`），那里恰好知道
 * **本次请求真实用了哪个 profile / model / 角色**，以及服务端返回的
 * [Usage]。上层（app）注入一个实现把事件写进 `UsageLedger`；未注入时是
 * [NOOP]，行为与旧版完全一致。
 *
 * 契约要点：
 * - 只上报**真实** usage —— 端点没返回统计时 [usage] 为 null，绝不填 0 假装
 *   计量过（漏账要可见，不要被假 0 掩盖）；
 * - 每次请求至多一次回调（含 fallback 后成功的那一次）；
 * - 实现必须非阻塞、不得抛异常（观测不能拖垮请求路径）。
 */
fun interface ModelUsageObserver {

    /**
     * @param requestId 请求标识（[LlmRequestContext.requestId]），同一次逻辑
     *   请求的多帧流式统计可据此去重
     * @param role 角色（PRIMARY / VISION / SUMMARY / REASONING …）
     * @param profileId 实际执行的 Profile id（不是默认 Profile）
     * @param modelId 实际执行的模型 id
     * @param usage 服务端返回的用量；null = 端点未返回统计
     */
    fun onUsage(
        requestId: String,
        role: String,
        profileId: String,
        modelId: String,
        usage: Usage?
    )

    companion object {
        /** 默认实现：什么都不做（未接线时零行为变化）。 */
        val NOOP = ModelUsageObserver { _, _, _, _, _ -> }
    }
}
