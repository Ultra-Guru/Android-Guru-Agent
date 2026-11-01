package com.apex.agent.ui.screen.code

import com.apex.agent.core.engine.AgentEvent

/**
 * 回复聚合器（Streaming Pipeline 2.0 · Event Aggregator，自 CodeViewModel
 * 抽出保住 1200 行 SRP 预算）。
 *
 * ResponseChunk 只追加缓冲，由渲染 ticker（25ms ≤40Hz）批量归约进
 * messages——消除逐 chunk 全消息列表拷贝。边界事件（工具开始/收束/错误/
 * 系统行插队/取消）强制 [flush]，聚合只合并不丢字。
 *
 * 线程模型：仅主线程读写（collect/ticker 均在 viewModelScope 主调度器），
 * 无并发需求。
 */
internal class ResponseAggregator {

    private val buffer = StringBuilder()

    /** 追加一个流式分片（聚合等待批量归约）。 */
    fun append(text: String) {
        buffer.append(text)
    }

    /** 冲刷缓冲 → 归约（无内容零开销；调用方传 [reduce] 出口）。 */
    fun flush(reduce: (AgentEvent) -> Unit) {
        if (buffer.isEmpty()) return
        val text = buffer.toString()
        buffer.setLength(0)
        reduce(AgentEvent.ResponseChunk(text))
    }
}
