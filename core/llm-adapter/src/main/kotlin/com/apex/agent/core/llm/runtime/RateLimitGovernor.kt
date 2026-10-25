package com.apex.agent.core.llm.runtime

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * ═══ 模型请求节流器（限速友好模式的核心执行件）═══
 *
 * 面向**有 RPM / TPM / 日请求次数配额**的模型厂商用户（免费档、按次计费
 * 网关、严格限流端点）。开启后所有经过 [DefaultModelRuntime] 的请求
 * （引擎 ReAct 轮 / 压缩摘要 / 子代理 / 目标验收 / 任何消费方）在发出前
 * 先在此排队——相邻两次请求**起点**间隔不小于 [RateLimitPolicy.minIntervalMs]，
 * 把请求速率钉在配额之下，从源头消灭 429 与重试风暴。
 *
 * 设计要点：
 * - **单一节流点**：所有 LLM 请求（含降级重试的每一次尝试）都过
 *   [awaitPermit]——没有旁路，节流语义完整；
 * - **策略热读**：每次 permit 拉取 [policyProvider] 最新快照（设置页改
 *   参数 → 下一个请求立即生效，无需重启）；
 * - **关停零开销**：策略 disabled 时只读一次 volatile 级 lambda 返回值，
 *   无锁无挂起（主路径零回归）；
 * - **时钟可注入**：`clock: () -> Long` 供单测假钟驱动（测试纪律：
 *   不依赖墙钟睡眠）。
 *
 * 与既有重试层的分工：[com.apex.agent.core.llm.LlmClientFactory] 的
 * RetryInterceptor 负责**单请求内**的 429 指数退避；本节流器负责**请求间**
 * 的主动限速（让 429 根本不发生）。两层正交。
 */
class RateLimitGovernor(
    private val policyProvider: () -> RateLimitPolicy,
    private val clock: () -> Long = System::currentTimeMillis
) {

    /** 限速策略快照（值对象，设置层 → 运行时的单向数据流）。 */
    data class RateLimitPolicy(
        /** 总开关：false = 不节流（主路径零开销）。 */
        val enabled: Boolean = false,
        /** 相邻两次请求起点的最小间隔（毫秒）；enabled 且 > 0 才生效。 */
        val minIntervalMs: Long = 0L
    )

    companion object {
        /** 全局禁用的共享实例（DI 默认值，语义 = 历史行为零变化）。 */
        val NOOP: RateLimitGovernor = RateLimitGovernor({ RateLimitPolicy() })
    }

    /** 节流串行锁：排队语义（FIFO 公平性由 Mutex 挂起序近似保证）。 */
    private val mutex = Mutex()

    /** 上一次放行请求的起点时刻（仅 [hasDispatched] 为 true 时有意义）。 */
    private var lastDispatchMs = 0L

    /** 是否已放行过至少一次（虚拟时钟从 0 起步，不能拿 0 当哨兵）。 */
    private var hasDispatched = false

    /**
     * 申请一次请求许可（挂起直至距上次请求足够远）。
     *
     * 并发调用者在此排队；每个拿到许可的请求从 [clock] 时刻起算占用
     * 一个间隔槽。策略关闭时立即返回（不进锁）。首个请求不等待
     * （[hasDispatched] 显式标记，避免虚拟时钟/epoch 0 值歧义）。
     */
    suspend fun awaitPermit() {
        val policy = policyProvider()
        if (!policy.enabled || policy.minIntervalMs <= 0) return
        mutex.withLock {
            val now = clock()
            val earliest = if (hasDispatched) lastDispatchMs + policy.minIntervalMs else now
            val waitMs = earliest - now
            if (waitMs > 0) delay(waitMs)
            lastDispatchMs = clock()
            hasDispatched = true
        }
    }

    /** 测试 / 诊断：最近一次放行时刻（epoch ms；0 = 尚未放行）。 */
    internal fun lastDispatchAt(): Long = lastDispatchMs

    /** 测试 / 诊断：是否已放行过（首次请求不等待的依据）。 */
    internal fun hasDispatched(): Boolean = hasDispatched
}
