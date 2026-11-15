package com.apex.agent.ui.screen.code

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update

/**
 * 胶囊时间轴渲染 ticker（自 CodeViewModel 抽出保 1200 行 SRP 预算，
 * 仓库 Ops 扩展模式同 CodeOutputModeOps.kt）。
 *
 * 启动后每 [CodeViewModel.RENDER_TICK_MS] 一拍：先冲回复聚合缓冲
 * （P1 Event Aggregator，与胶囊快照同节拍），再拉胶囊新快照推 UI。
 * run 收尾必须调 [stopRenderTicker] 冲最后一次快照（尾巴事件不丢）。
 */

/** 启动渲染 ticker（25ms ≤40Hz；脏才推快照，无变更零重组）。 */
internal fun CodeViewModel.startRenderTicker() {
    renderJob?.cancel()
    renderJob = viewModelScope.launch {
        while (isActive) {
            delay(CodeViewModel.RENDER_TICK_MS)
            flushToolChunks() // perf P0-1：工具分片批量归约（先于文本，保时序）
            responseAggregator.flush { reduce(it) } // P1 聚合冲刷（与胶囊快照同节拍）
            applyMessagesWindow() // perf P0-3：UI 消息滑动窗口（超限才裁，O(1) 检查）
            streamSession.tick()?.let { snap ->
                _uiState.update { it.copy(stream = snap) }
            }
        }
    }
}

/** 停止 ticker 并冲最后一次快照（run 收尾调用）。 */
internal fun CodeViewModel.stopRenderTicker() {
    renderJob?.cancel()
    renderJob = null
    streamSession.snapshot().let { snap ->
        _uiState.value = _uiState.value.copy(stream = snap)
    }
}
