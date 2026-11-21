package com.apex.agent.core.llm.runtime

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ═══ 模型请求节流器（限速友好模式核心执行件）单元测试 ═══
 *
 * 虚拟时钟口径（测试纪律：不依赖墙钟睡眠）：clock 注入 TestScheduler
 * 虚拟时间，delay 虚拟化推进 —— 间隔断言全部是确定性虚拟时刻，零抖动。
 */
class RateLimitGovernorTest {

    @Test
    fun `disabled policy is zero overhead and never waits`() = runTest {
        var clock = 0L
        val governor = RateLimitGovernor(
            policyProvider = { RateLimitGovernor.RateLimitPolicy(enabled = false, minIntervalMs = 5000) },
            clock = { clock }
        )
        repeat(5) { governor.awaitPermit() }
        // 无挂起、无等待：虚拟时钟一步未走。
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun `enabled policy spaces sequential requests by the interval`() = runTest {
        val clock: () -> Long = { testScheduler.currentTime }
        val governor = RateLimitGovernor(
            policyProvider = { RateLimitGovernor.RateLimitPolicy(enabled = true, minIntervalMs = 4000) },
            clock = clock
        )
        // 第 1 个立即放行（t=0）；第 2 个等到 t=4000；第 3 个等到 t=8000。
        governor.awaitPermit()
        assertEquals(0L, testScheduler.currentTime)
        governor.awaitPermit()
        assertEquals(4000L, testScheduler.currentTime)
        governor.awaitPermit()
        assertEquals(8000L, testScheduler.currentTime)
    }

    @Test
    fun `concurrent callers queue up instead of bursting`() = runTest {
        val clock: () -> Long = { testScheduler.currentTime }
        val governor = RateLimitGovernor(
            policyProvider = { RateLimitGovernor.RateLimitPolicy(enabled = true, minIntervalMs = 1000) },
            clock = clock
        )
        // 4 个并发申请：放行时刻应依次落在 0 / 1000 / 2000 / 3000。
        val permits = List(4) { async { governor.awaitPermit(); testScheduler.currentTime } }
        val times = permits.awaitAll().sorted()
        assertEquals(listOf(0L, 1000L, 2000L, 3000L), times)
    }

    @Test
    fun `policy is read per permit - hot changes apply to the next request`() = runTest {
        var enabled = false
        val clock: () -> Long = { testScheduler.currentTime }
        val governor = RateLimitGovernor(
            policyProvider = { RateLimitGovernor.RateLimitPolicy(enabled = enabled, minIntervalMs = 3000) },
            clock = clock
        )
        // 关闭态：两个请求零等待。
        governor.awaitPermit()
        governor.awaitPermit()
        assertEquals(0L, testScheduler.currentTime)

        // 开启（模拟设置页改参数）：下一个请求起节流生效。
        enabled = true
        governor.awaitPermit() // 节流域内的首个请求：放行但占用一个间隔槽
        assertEquals(0L, testScheduler.currentTime)
        governor.awaitPermit() // 第二个：距上次放行 0ms < 3000 → 推进到 3000
        assertEquals(3000L, testScheduler.currentTime)
    }

    @Test
    fun `zero interval while enabled means no throttling`() = runTest {
        val clock: () -> Long = { testScheduler.currentTime }
        val governor = RateLimitGovernor(
            policyProvider = { RateLimitGovernor.RateLimitPolicy(enabled = true, minIntervalMs = 0) },
            clock = clock
        )
        repeat(3) { governor.awaitPermit() }
        assertEquals(0L, testScheduler.currentTime)
        assertTrue(governor.lastDispatchAt() >= 0L)
    }
}
