package com.apex.agent.ui.screen.code

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RenderTickerPolicy] 渲染节拍自适应策略测试（SPE-07 P1-1 / SPE-09 回归）。
 *
 * 纯状态机、无时钟依赖——`recordTick(workMs, hadWork)` 脚本化序列驱动：
 * 1. 忙三档升降：单拍过载降档（25→33）、连续轻拍升档（25→16）；
 * 2. 空闲降频：3 空闲拍 → 50ms 巡逻；15 拍 → 250ms 深巡逻；事件唤醒复位；
 * 3. 验收基准（SPE-07 §4-2）：30s 无事件空闲期，ticker 唤醒次数相比
 *    固定 25ms 轮询降低 ≥ 80%（本测试按策略模拟完整 30s）；
 * 4. run 边界 reset 不携带上一轮的降档惯性。
 */
class RenderTickerPolicyTest {

    @Test
    fun `busy ladder defaults to 25ms baseline`() {
        val policy = RenderTickerPolicy()
        assertEquals(25L, policy.busyTickMs)
        assertEquals(25L, policy.nextDelayMs())
    }

    @Test
    fun `overloaded tick downshifts to 33ms`() {
        val policy = RenderTickerPolicy()
        // 单拍工作量 20ms > 25 × 0.67 ≈ 16.75 → 降档到 33ms
        policy.recordTick(workMs = 20L, hadWork = true)
        assertEquals(33L, policy.busyTickMs)
        assertEquals(33L, policy.nextDelayMs())
        // 顶档不再升（ladder 钳制）
        policy.recordTick(workMs = 30L, hadWork = true)
        assertEquals(33L, policy.busyTickMs)
    }

    @Test
    fun `sustained light ticks upshift to 16ms`() {
        val policy = RenderTickerPolicy()
        // 连续 8 拍轻负载（workMs < 25 × 0.25 ≈ 6.25）→ 升档到 16ms
        repeat(8) { policy.recordTick(workMs = 2L, hadWork = true) }
        assertEquals(16L, policy.busyTickMs)
        // 底档不再降（ladder 钳制）
        repeat(8) { policy.recordTick(workMs = 1L, hadWork = true) }
        assertEquals(16L, policy.busyTickMs)
    }

    @Test
    fun `single light tick does not upshift without streak`() {
        val policy = RenderTickerPolicy()
        // 单拍轻负载（无连续稳定观察）不升档——防单拍噪声
        policy.recordTick(workMs = 1L, hadWork = true)
        assertEquals(25L, policy.busyTickMs)
    }

    @Test
    fun `idle streak shifts to patrol then deep patrol`() {
        val policy = RenderTickerPolicy()
        // 忙拍 → 空闲连击
        policy.recordTick(workMs = 2L, hadWork = true)
        assertEquals(25L, policy.nextDelayMs()) // 1 拍空闲未到阈值
        policy.recordTick(workMs = 0L, hadWork = false)
        policy.recordTick(workMs = 0L, hadWork = false)
        assertEquals(25L, policy.nextDelayMs()) // 2 拍空闲仍未到阈值
        policy.recordTick(workMs = 0L, hadWork = false)
        assertEquals("3 空闲拍 → 50ms 巡逻", 50L, policy.nextDelayMs())
        repeat(RenderTickerPolicy.DEEP_IDLE_STREAK - RenderTickerPolicy.IDLE_STREAK) { policy.recordTick(workMs = 0L, hadWork = false) }
        assertEquals("空闲延续到深巡逻阈值 → 250ms", 250L, policy.nextDelayMs())
    }

    @Test
    fun `had work resets idle streak and wake up exits patrol`() {
        val policy = RenderTickerPolicy()
        // 进入深巡逻
        repeat(RenderTickerPolicy.DEEP_IDLE_STREAK + 2) { policy.recordTick(workMs = 0L, hadWork = false) }
        assertEquals(250L, policy.nextDelayMs())
        // 事件唤醒：退出巡逻回到忙档（不升档，仅复位空闲连击）
        policy.wakeUp()
        assertEquals(25L, policy.nextDelayMs())
        // 一次有内容拍同样复位空闲连击
        repeat(RenderTickerPolicy.IDLE_STREAK) { policy.recordTick(workMs = 0L, hadWork = false) }
        assertEquals(50L, policy.nextDelayMs())
        policy.recordTick(workMs = 5L, hadWork = true)
        assertEquals(25L, policy.nextDelayMs())
    }

    /**
     * SPE-07 §4-2 验收基准：30s 无事件空闲期唤醒数相比固定 25ms 降低 ≥ 80%。
     *
     * 固定 25ms = 30000 / 25 = 1200 次唤醒；策略模拟（事件唤醒通道由
     * ticker 循环保证 ≤ 1 拍上屏，此处只度量巡逻频率本身）。
     */
    @Test
    fun `thirty second idle reduces wakeups by at least eighty percent`() {
        val policy = RenderTickerPolicy()
        var elapsedMs = 0L
        var wakeups = 0
        while (elapsedMs < 30_000L) {
            val step = policy.nextDelayMs()
            elapsedMs += step
            wakeups++
            policy.recordTick(workMs = 0L, hadWork = false)
        }
        val fixed = 30_000L / 25L
        val reduction = 1.0 - wakeups.toDouble() / fixed.toDouble()
        assertTrue(
            "30s 空闲唤醒 $wakeups 次 vs 固定 25ms $fixed 次（降幅 ${(reduction * 100).toInt()}% < 80%）",
            reduction >= 0.80
        )
    }

    @Test
    fun `reset clears ladder inertia and idle streak`() {
        val policy = RenderTickerPolicy()
        // 打到降档 + 深巡逻
        policy.recordTick(workMs = 20L, hadWork = true)
        repeat(RenderTickerPolicy.DEEP_IDLE_STREAK) { policy.recordTick(workMs = 0L, hadWork = false) }
        assertEquals(33L, policy.busyTickMs)
        assertEquals(250L, policy.nextDelayMs())
        // run 边界复位：基线 25ms、空闲清零
        policy.reset()
        assertEquals(25L, policy.busyTickMs)
        assertEquals(25L, policy.nextDelayMs())
    }

}
