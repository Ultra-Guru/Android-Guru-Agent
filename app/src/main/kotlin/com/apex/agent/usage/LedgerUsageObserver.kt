package com.apex.agent.usage

import com.apex.agent.core.llm.runtime.ModelUsageObserver
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把模型运行时的每请求用量写进 [UsageLedger]（#221）。
 *
 * 与旧链路的区别：旧实现由 UI 在 `AgentEvent.UsageUpdated` 到达时按**当前默认
 * Profile** 记账，于是角色路由（VISION/SUMMARY/REASONING）的消耗记到默认模型
 * 头上，子代理与上下文压缩摘要这些不经主事件流的请求则完全不记账。
 * 这里在运行时执行层收到**真实** profile/model/role，直接按真实值入账。
 *
 * 纪律：
 * - 端点没返回 usage（`usage == null`）时**不记**，也不填 0 假装计量过 ——
 *   漏账要可见，不要被假 0 掩盖；
 * - 观测点每逻辑请求最多回调一次（流式多帧已在运行时层收敛）；
 * - 落账走 [UsageLedger.record]，其内部已做负值钳制与异步落盘。
 */
@Singleton
class LedgerUsageObserver @Inject constructor(
    private val ledger: UsageLedger,
    private val currentSession: CurrentUsageSession
) : ModelUsageObserver {

    override fun onUsage(
        requestId: String,
        role: String,
        profileId: String,
        modelId: String,
        usage: com.apex.agent.core.llm.Usage?
    ) {
        if (usage == null) return
        ledger.record(
            sessionId = currentSession.sessionId,
            modelId = modelId,
            promptTokens = usage.promptTokens.toLong(),
            completionTokens = usage.completionTokens.toLong()
        )
    }
}
