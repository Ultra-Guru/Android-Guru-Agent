package com.apex.agent.core.code.loom

/**
 * # Loom Engine Config — 织造引擎预算配置
 *
 * 织造线（Coding 第三思考逻辑线）的运行预算与协议参数。命名隐喻：
 * 任务完成 = 经纬交织——**思考链为经线**（每轮先 deliberation 再行动）、
 * **工具调用为纬线**（in-band XML 往返）、**记忆为布面**（digest 摘要织入）、
 * 循环为**梭**（shuttle：流式回合往返）。
 *
 * ## 值域纪律
 *
 * 所有数值字段都有防御性下限（[sanitized] 统一钳位）——配置面暴露给
 * DI 层，脏值（0 / 负数 / 超界）不得击穿循环：
 *
 * | 字段 | 语义 | 默认 | 下限 |
 * |---|---|---|---|
 * | [shuttleRoundsMax] | 单次任务最大梭回合数 | 32 | ≥4，≤200 |
 * | [outcomeBudgetChars] | 单个工具结果的常规裁剪预算 | 12000 | ≥1000 |
 * | [outcomeHardCapChars] | 结果绝对上限（超预算工具的兜底天花板） | 64000 | ≥预算 |
 * | [maxContextTokens] | 上下文窗口预算 | 128000 | ≥8000 |
 * | [compactAtRatio] | 触发织入压缩的占比阈值 | 0.8 | 0.4..0.95 |
 * | [keepRecentTurns] | 压缩时保留的近期轮次 | 8 | ≥2 |
 * | [nativeToolCalling] | 是否随请求下发原生 tools 面 | true | — |
 * | [approvalTimeoutMs] | 权限问答窗口（超时按拒绝收尾） | 120000 | ≥5000 |
 * | [transientRetryMax] | 瞬时故障重试次数 | 3 | 0..6 |
 * | [transientRetryBaseDelayMs] | 重试阶梯基延迟 | 1500 | ≥100 |
 * | [steeringQueueMax] | 运行中转向队列容量 | 8 | ≥1 |
 * | [threadConcurrencyMax] | 子线程（swarm）并发上限 | 2 | ≥1，≤8 |
 * | [threadRoundCap] | 子线程单飞最大梭回合数 | 12 | ≥2 |
 * | [threadOutputCapChars] | 子线程回执裁剪预算 | 8000 | ≥500 |
 * | [nudgeMaxCount] | 纯思考回应的反馈催促上限 | 2 | ≥0，≤4 |
 *
 * ## contextTag 语义
 *
 * 三个 tag 分别用于：主循环请求（[contextTag]）、digest 摘要请求
 * （[digestContextTag]，路由到 SUMMARY 角色）、子线程请求
 * （[threadContextTag]）——诊断面板可按 tag 区分流量来源。
 */
data class LoomEngineConfig(
    /** 单次任务（一次用户输入 → 完成）最大梭回合数。 */
    val shuttleRoundsMax: Int = 32,
    /** 单个工具 outcome 的常规裁剪预算（字符）；超出以截断标记收尾。 */
    val outcomeBudgetChars: Int = 12_000,
    /** outcome 绝对上限（防御性天花板——预算再大也不越过这里）。 */
    val outcomeHardCapChars: Int = 64_000,
    /** 上下文窗口预算（token 估算口径）。 */
    val maxContextTokens: Int = 128_000,
    /** 估算 token 占窗口比达到该阈值 → 触发 digest 织入压缩。 */
    val compactAtRatio: Float = 0.8f,
    /** 压缩时保留的近期轮次数（旧轮折入 digest）。 */
    val keepRecentTurns: Int = 8,
    /** 是否把工具目录以原生 tools 参数下发（false = 纯提示词协议）。 */
    val nativeToolCalling: Boolean = true,
    /** 权限问答窗口毫秒数（超时 → 该次调用按用户拒绝收尾）。 */
    val approvalTimeoutMs: Long = 120_000,
    /** 瞬时故障（网络抖动/限流）重试次数上限。 */
    val transientRetryMax: Int = 3,
    /** 重试阶梯基延迟（指数退避：基延迟 × 2 的幂）。 */
    val transientRetryBaseDelayMs: Long = 1_500,
    /** 运行中转向（steering）队列容量。 */
    val steeringQueueMax: Int = 8,
    /** 子线程并发上限。 */
    val threadConcurrencyMax: Int = 2,
    /** 子线程单飞最大梭回合数。 */
    val threadRoundCap: Int = 12,
    /** 子线程单飞壁钟超时（毫秒）。 */
    val threadTimeoutMs: Long = 240_000,
    /** 子线程回执裁剪预算（字符）。 */
    val threadOutputCapChars: Int = 8_000,
    /** 纯思考回应（无文本无工具）的催促反馈上限——超过即强制收尾。 */
    val nudgeMaxCount: Int = 2,
    /** 主循环请求 contextTag。 */
    val contextTag: String = "loom_loop",
    /** digest 摘要请求 contextTag（SUMMARY 角色）。 */
    val digestContextTag: String = "loom_digest",
    /** 子线程请求 contextTag。 */
    val threadContextTag: String = "loom_thread_loop"
) {
    init {
        // 构造期即校验硬伤（编程错误）；运行时脏值由 sanitized() 兜底
        // ——双保险，但 init 层用宽松下限避免反射/序列化构造炸裂。
        require(shuttleRoundsMax >= 1) { "shuttleRoundsMax must be >= 1" }
        require(outcomeBudgetChars >= 100) { "outcomeBudgetChars must be >= 100" }
    }

    /**
     * 防御性钳位副本：所有数值字段夹回安全区间。
     *
     * init 只拦编程级硬伤；sanitized 兜运行时脏值（如设置层把预算改成
     * -1）——引擎构造处统一过一遍，任何字段都不至于击穿循环。
     */
    fun sanitized(): LoomEngineConfig = copy(
        shuttleRoundsMax = shuttleRoundsMax.coerceIn(4, 200),
        outcomeBudgetChars = outcomeBudgetChars.coerceAtLeast(1_000),
        outcomeHardCapChars = outcomeHardCapChars
            .coerceAtLeast(outcomeBudgetChars.coerceAtLeast(1_000)),
        maxContextTokens = maxContextTokens.coerceAtLeast(8_000),
        compactAtRatio = compactAtRatio.coerceIn(0.4f, 0.95f),
        keepRecentTurns = keepRecentTurns.coerceAtLeast(2),
        approvalTimeoutMs = approvalTimeoutMs.coerceAtLeast(5_000),
        transientRetryMax = transientRetryMax.coerceIn(0, 6),
        transientRetryBaseDelayMs = transientRetryBaseDelayMs.coerceAtLeast(100),
        steeringQueueMax = steeringQueueMax.coerceAtLeast(1),
        threadConcurrencyMax = threadConcurrencyMax.coerceIn(1, 8),
        threadRoundCap = threadRoundCap.coerceAtLeast(2),
        threadTimeoutMs = threadTimeoutMs.coerceAtLeast(10_000),
        threadOutputCapChars = threadOutputCapChars.coerceAtLeast(500),
        nudgeMaxCount = nudgeMaxCount.coerceIn(0, 4)
    )

    /** 压缩触发线（token 估算值）。 */
    fun compactAtTokens(): Int =
        (maxContextTokens * compactAtRatio).toInt().coerceAtLeast(2_000)

    companion object {
        /** 子代理/测试缺省配置（纯内存会话场景，预算收紧）。 */
        fun forThread(): LoomEngineConfig = LoomEngineConfig(
            shuttleRoundsMax = 12,
            outcomeBudgetChars = 8_000,
            outcomeHardCapChars = 24_000,
            maxContextTokens = 64_000,
            keepRecentTurns = 6,
            threadTimeoutMs = 180_000,
            contextTag = "loom_thread_loop",
            digestContextTag = "loom_thread_digest",
            threadContextTag = "loom_thread_loop"
        )
    }
}
