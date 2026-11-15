package com.apex.agent.ui.screen.code

import com.apex.agent.core.engine.AgentEvent

/**
 * 工具输出分片聚合器（perf P0-1，与 [ResponseAggregator] 同款模式）。
 *
 * 多工具并发时 `ToolOutputChunk` 以行/帧级频率涌入（一次调用数十到数百
 * 片，多工具并行相乘），旧实现逐片 `reduce()` → 每片全量复制 messages
 * 列表 + O(n) 定位 + 整屏重组，是「多工具时严重卡顿」的主因。本聚合器
 * 把分片缓冲为文本，由渲染 ticker（25ms ≤40Hz）批量归约——归约频率从
 * 「每分片一次」降到「每拍至多一次」，与 ResponseChunk 的 P0 修复同口径。
 *
 * 聚合安全性：引擎侧「发言权协议」（EngineToolExecution 按序放行完整
 * 生命周期）保证同一时刻只有一个工具在流式输出，UI 活跃工具卡唯一——
 * 按时间窗合并分片不改变归约语义（reducer 只认「当前活跃工具消息」，
 * 不按 callId 定位）。边界事件（工具开始/收束/错误/系统行插队/取消）
 * 强制 [flush]，顺序不乱、不丢字。
 *
 * 线程模型：仅主线程读写（collect/ticker 均在 viewModelScope 主调度器），
 * 无并发需求。
 */
internal class ToolChunkAggregator {

    private val buffer = StringBuilder()

    /** 追加一个工具输出分片（聚合等待批量归约）。 */
    fun append(text: String) {
        buffer.append(text)
    }

    /** 冲刷缓冲 → 合并分片文本（空则 null，调用方零开销跳过）。 */
    fun flushOrNull(): String? {
        if (buffer.isEmpty()) return null
        val text = buffer.toString()
        buffer.setLength(0)
        return text
    }
}

/** 冲刷工具分片缓冲 → 归约（无内容零开销）。callId 置空：reducer 按
 *  「当前活跃工具消息」定位（发言权协议下单活跃，见类 KDoc）。 */
internal fun CodeViewModel.flushToolChunks() {
    toolChunkAggregator.flushOrNull()?.let { text ->
        reduce(AgentEvent.ToolOutputChunk(callId = "", chunk = text))
    }
}
