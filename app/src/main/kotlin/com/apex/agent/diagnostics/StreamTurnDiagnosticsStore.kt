package com.apex.agent.diagnostics

import com.apex.agent.core.llm.StreamDiagnostics
import javax.inject.Inject
import javax.inject.Singleton

/**
 * # Stream Turn Diagnostics Store — 最近 N 次流式回合诊断环形存储（SPE-08 P1-1）
 *
 * 采集链路：`StreamingOpenAiClient.chatStream`（帧级）→ `LlmStreamChunk.diagnostics`
 * → `AgentEvent.StreamTurnStats`（引擎转发）→ `CodeViewModel` 收束（
 * CodeStreamDiagnosticsOps）→ 本存储；诊断页「最近 N 次流式回合」列表与
 * 一键诊断报告（[DiagnosticsCollector]）双消费。
 *
 * 环形上限 [CAPACITY]：诊断只关心**最近**的回合（旧回合的帧间隔/丢帧数
 * 对当前问题定位价值递减），无界累积反而把诊断页撑爆。
 *
 * 线程模型：事件收集在主线程、诊断页读取在主线程，但存储自身用
 * `synchronized` 防御（Agent 屏后续接入时事件源可能在不同调度器）。
 * 数据本身不可变（[StreamDiagnostics] 纯值对象），读取方拿到的列表快照
 * 与后续写入隔离。
 */
@Singleton
class StreamTurnDiagnosticsStore @Inject constructor() {

    private val lock = Any()
    private val turns = ArrayDeque<StreamDiagnostics>(CAPACITY)

    /** 记录一次流式回合的诊断快照（环形：超限淘汰最旧）。 */
    fun record(stats: StreamDiagnostics) {
        synchronized(lock) {
            if (turns.size >= CAPACITY) turns.removeFirst()
            turns.addLast(stats)
        }
    }

    /** 最近回合快照（新→旧；空 = 本进程尚无流式回合）。 */
    fun recent(): List<StreamDiagnostics> = synchronized(lock) {
        turns.toList().asReversed()
    }

    /** 已记录回合数（诊断页空态判断）。 */
    fun size(): Int = synchronized(lock) { turns.size }

    private companion object {
        /** 环形容量：最近 10 次流式回合。 */
        const val CAPACITY = 10
    }
}
