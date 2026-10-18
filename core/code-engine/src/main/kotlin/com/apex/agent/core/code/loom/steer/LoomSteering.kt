package com.apex.agent.core.code.loom.steer

import com.apex.agent.core.code.loom.LoomEngineConfig
import java.util.concurrent.atomic.AtomicBoolean

/**
 * # Loom Steering — 运行中转向队列
 *
 * 任务运行中用户补充的指令：入队 → 下个回合边界织入用户轮（优先遵循）。
 * 与「中止重发」相比，转向保住已织进度——模型在读到新指令前不丢上下文。
 *
 * ## 语义
 *
 * - 仅运行中受理（[running] 置位期间 [offer] 生效；闲时 offer 落空，
 *   调用方改走常规发送）；
 * - 容量有界（[LoomEngineConfig.steeringQueueMax]——防连点灌爆上下文）；
 * - 回合边界 [drain]——一次性取出全部待织指令并清队列；
 * - 会话清空 / 引擎复位时 [clear]。
 */
class LoomSteering(private val config: LoomEngineConfig) {

    private val queue = ArrayDeque<String>()

    @Volatile
    private var running = false

    private val lock = Any()

    /** 引擎在 execute 起止处置位 / 复位。 */
    fun setRunning(value: Boolean) {
        running = value
        if (!value) clear()
    }

    /** 运行中？ */
    fun isRunning(): Boolean = running

    /**
     * 入队一条转向指令。
     *
     * @return true = 已受理；false = 非运行中或队列已满（调用方提示）。
     */
    fun offer(text: String): Boolean {
        val trimmed = text.trim()
        if (!running || trimmed.isEmpty()) return false
        synchronized(lock) {
            if (queue.size >= config.steeringQueueMax) return false
            queue.addLast(trimmed)
        }
        return true
    }

    /** 回合边界：取出全部待织指令（按到达序）并清队列。 */
    fun drain(): List<String> = synchronized(lock) {
        val out = queue.toList()
        queue.clear()
        out
    }

    /** 待织数量（UI 徽标用）。 */
    fun pendingCount(): Int = synchronized(lock) { queue.size }

    /** 清空。 */
    fun clear() {
        synchronized(lock) { queue.clear() }
    }
}
