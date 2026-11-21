package com.apex.agent.ui.screen.code

import com.apex.agent.core.engine.AgentEvent
import com.apex.agent.core.llm.StreamDiagnostics
import kotlinx.coroutines.flow.update

/**
 * CodeStreamDiagnosticsOps — 流式回合诊断收束（SPE-08 P1-1 / P1-4，
 * 自 CodeViewModel 抽出保 1200 行 SRP 预算，仓库 Ops 扩展模式同
 * CodeLogicModeOps.kt）。
 *
 * 职责：
 *  - [onStreamTurnStats]：引擎转发的 `AgentEvent.StreamTurnStats`（每次
 *    chatStream 随流尾一条）→ 诊断存储（诊断页「最近 N 次流式回合」）
 *    + uiState.usageSource（上下文仪表盘用量来源徽标口径）。
 */
internal fun CodeViewModel.onStreamTurnStats(event: AgentEvent.StreamTurnStats) {
    val stats: StreamDiagnostics = event.diagnostics
    streamTurnStore.record(stats)
    // SPE-08 P1-4：用量来源徽标——统计帧到达 = 真实（服务端）；缺失时
    // 明确「未获取」而非静默切估算（估算路径接入后续批次）。
    _uiState.update { it.copy(usageSource = stats.usageSource) }
}
