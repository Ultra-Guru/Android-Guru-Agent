package com.apex.agent.core.engine

import com.apex.agent.core.engine.compression.HistoryImageEvictor
import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory

/**
 * #827 — 历史图片退场接线（引擎侧，[ApexAgentEngine.buildMessages] 每轮
 * 请求组装前调用）。
 *
 * 早前轮次（超出 keep-last-K 的携图轮）的图片以占位文本替换 —— 旧图不再
 * 每轮重传/重计费，内存历史不再无限驻留；VISION 路由基于**当轮外发消息**
 * 是否含图（messagesContainImages），退场后只剩近图，路由自动回落 PRIMARY，
 * 无需另行干预。纯策略见 [HistoryImageEvictor]（不可变 copy，绝不动共享
 * history 的元素）。
 */
internal fun ApexAgentEngine.evictAgedHistoryImages() {
    // 常见路径（历史无图）零分配短路。
    val before = residentImageCount(conversationHistory)
    if (before == 0) return
    val evicted = HistoryImageEvictor.evict(conversationHistory)
    if (evicted === conversationHistory) return
    // #809：clear+addAll 复合替换经 historyLock 串行化（与 restoreHistory /
    // repairDanglingToolCalls 同语义；COW 列表单操作本身已原子）。
    synchronized(historyLock) {
        conversationHistory.clear()
        conversationHistory.addAll(evicted)
    }
    memory?.save(conversationHistory)
    AppLogger.instance.info(
        LogCategory.ENGINE, "ApexAgentEngine",
        "history images evicted (keep-last-K=${HistoryImageEvictor.DEFAULT_KEEP_LAST_K}): " +
            "$before -> ${residentImageCount(conversationHistory)} images resident"
    )
}

/** 历史中驻留的图片总数（#827 退场观测口径）。 */
private fun residentImageCount(history: List<LlmMessage>): Int =
    history.sumOf { (it as? LlmMessage.User)?.images?.size ?: 0 }
