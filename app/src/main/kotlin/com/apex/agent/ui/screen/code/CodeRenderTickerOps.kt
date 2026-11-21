package com.apex.agent.ui.screen.code

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 胶囊时间轴渲染 ticker（自 CodeViewModel 抽出保 1200 行 SRP 预算，
 * 仓库 Ops 扩展模式同 CodeOutputModeOps.kt）。
 *
 * SPE-07 P1-1 自适应节拍：每拍延迟由 [RenderTickerPolicy] 决定
 * （忙三档 16/25/33ms 按单拍负载升降；空闲降频 50/250ms 巡逻）；
 * **事件唤醒**替代纯轮询——`renderWakeSignal` 合流信号通道打断
 * `delay`，空闲巡逻期间事件到达 ≤ 1 拍上屏（首 token 不受巡逻档拖累）。
 *
 * run 收尾必须调 [stopRenderTicker] 冲最后一次快照（尾巴事件不丢）。
 */

/** 事件风暴钳制地板：相邻两拍间隔不小于 16ms（≤ 62.5Hz，忙档最低档同值）。 */
private const val MIN_TICK_SPACING_MS = 16L

/** 启动渲染 ticker（自适应节拍；脏才推快照，无变更零重组）。 */
internal fun CodeViewModel.startRenderTicker() {
    renderJob?.cancel()
    drainRenderWakeSignal()
    renderTickerPolicy.reset()
    renderJob = viewModelScope.launch {
        var lastTickAt = -MIN_TICK_SPACING_MS // 首拍不等待钳制
        while (isActive) {
            val delayMs = renderTickerPolicy.nextDelayMs()
            // 事件唤醒 / 超时巡逻二选一：空闲巡逻期间事件到达立即醒来
            //（SPE-07 P1-1），无事件按策略节拍照常巡逻一拍。
            withTimeoutOrNull(delayMs) { renderWakeSignal.receive() }
            drainRenderWakeSignal()
            // 最小拍距钳制：事件密集涌入时防超频渲染（合流信号只保 1 个
            // pending，配合地板 = 每拍处理掉全部缓冲后至少歇 16ms）
            val now = System.currentTimeMillis()
            val sinceLast = now - lastTickAt
            if (sinceLast in 0 until MIN_TICK_SPACING_MS) {
                delay(MIN_TICK_SPACING_MS - sinceLast)
            }
            lastTickAt = System.currentTimeMillis()
            val startedAt = System.currentTimeMillis()
            val hadWork = doRenderTick()
            renderTickerPolicy.recordTick(
                workMs = System.currentTimeMillis() - startedAt,
                hadWork = hadWork
            )
        }
    }
}

/** 停止 ticker 并冲最后一次快照（run 收尾调用）。 */
internal fun CodeViewModel.stopRenderTicker() {
    renderJob?.cancel()
    renderJob = null
    // 冲最后一次快照（尾巴事件不丢）；统一 _uiState.update 口径
    //（SPE-07 P1-4：与项目其余写法一致，杜绝直接赋值的语义分叉）
    streamSession.snapshot().let { snap ->
        _uiState.update { it.copy(stream = snap) }
    }
    // 收尾清残留唤醒信号 + 策略复位（停了 ticker 不留旧事件把下一轮
    // 首拍提前烧掉；startRenderTicker 亦会再排空一次，双保险）
    drainRenderWakeSignal()
    renderTickerPolicy.reset()
}

/** 单拍工作量（原 while 循环体四步）；返回是否有任何内容上屏（空闲检测输入）。 */
private fun CodeViewModel.doRenderTick(): Boolean {
    // perf P0-1：工具分片批量归约（先于文本，保时序）
    val toolFlushed = flushToolChunks()
    // P1 聚合冲刷（与胶囊快照同节拍）
    val textFlushed = responseAggregator.flush { reduce(it) }
    // perf P0-3：UI 消息滑动窗口（超限才裁，O(1) 检查）
    applyMessagesWindow()
    // 胶囊快照：脏才重建推 UI（tick 空转返回 null）
    val snap = streamSession.tick()
    if (snap != null) {
        _uiState.update { it.copy(stream = snap) }
    }
    return toolFlushed || textFlushed || snap != null
}

/** 排空合流唤醒信号（CONFLATED 至多 1 个 pending，循环为防御性写法）。 */
private fun CodeViewModel.drainRenderWakeSignal() {
    while (renderWakeSignal.tryReceive().isSuccess) {
        // 只清信号，不做工作——工作在下一拍统一做
    }
}
