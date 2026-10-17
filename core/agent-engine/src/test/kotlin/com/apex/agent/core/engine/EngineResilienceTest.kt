package com.apex.agent.core.engine

import com.apex.agent.core.engine.orchestrator.RetryPolicy
import com.apex.agent.core.llm.runtime.ModelRuntimeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.random.Random

/**
 * [EngineResilienceGuard] 单测：LLM 瞬时错误重试判定、空响应重试、
 * 工具重试预算、连续失败换路提示、退避曲线。全部纯决策逻辑，
 * 假随机固定抖动（Random(42) 固定序列）驱动确定性断言。
 */
class EngineResilienceTest {

    private fun guard(policy: EngineResiliencePolicy = EngineResiliencePolicy.FAST) =
        EngineResilienceGuard(policy, random = Random(42))

    // ═══ 1. LLM 瞬时错误重试 ═══

    @Test
    fun `rate limited and timeout and unavailable are retried with backoff`() {
        val g = guard()
        val decision = g.onLlmFailure(
            ModelRuntimeException.ModelRateLimited("429 too many requests", profileId = "p1")
        )
        assertTrue(decision is EngineResilienceGuard.LlmRetryDecision.Retry)
        assertEquals(1, (decision as EngineResilienceGuard.LlmRetryDecision.Retry).attempt)
    }

    @Test
    fun `auth failure and request rejection are not retried`() {
        val g = guard()
        assertTrue(
            g.onLlmFailure(ModelRuntimeException.ModelAuthenticationFailed("401", "p1"))
                is EngineResilienceGuard.LlmRetryDecision.Stop
        )
        assertTrue(
            g.onLlmFailure(ModelRuntimeException.ModelRequestRejected("400 bad tools", "p1"))
                is EngineResilienceGuard.LlmRetryDecision.Stop
        )
    }

    @Test
    fun `raw io network exception is retried via classifier`() {
        val g = guard()
        val decision = g.onLlmFailure(IOException("connection reset by peer"))
        assertTrue(decision is EngineResilienceGuard.LlmRetryDecision.Retry)
    }

    @Test
    fun `llm retry budget exhausts after max retries`() {
        // 显式预算（不再依赖 DEFAULT 默认值 —— 默认预算已随弱网韧性调整过，
        // 测试断言与生产默认解耦）：3 次重试后第 4 次失败必须 Stop。
        val g = guard(
            EngineResiliencePolicy(
                maxLlmRetries = 3,
                initialBackoffMs = 0, maxBackoffMs = 0, jitterRatio = 0.0
            )
        )
        var last: EngineResilienceGuard.LlmRetryDecision? = null
        repeat(4) {
            last = g.onLlmFailure(ModelRuntimeException.ModelTimeout("timeout", "p1"))
        }
        assertTrue(last is EngineResilienceGuard.LlmRetryDecision.Stop)
    }

    @Test
    fun `response invalid is retried as transient`() {
        // 白名单扩展：空响应/解析失败（网关抖动）值得一次自动重试。
        val g = guard()
        val decision = g.onLlmFailure(
            ModelRuntimeException.ModelResponseInvalid("empty response", profileId = "p1")
        )
        assertTrue(decision is EngineResilienceGuard.LlmRetryDecision.Retry)
    }

    @Test
    fun `guard reset restores budgets for a new task`() {
        val g = guard(
            EngineResiliencePolicy(
                maxLlmRetries = 3,
                initialBackoffMs = 0, maxBackoffMs = 0, jitterRatio = 0.0
            )
        )
        // 用尽 LLM 预算
        repeat(4) { g.onLlmFailure(ModelRuntimeException.ModelTimeout("timeout", "p1")) }
        assertTrue(
            g.onLlmFailure(ModelRuntimeException.ModelRateLimited("429", "p1"))
                is EngineResilienceGuard.LlmRetryDecision.Stop
        )
        g.resetForTask()
        assertTrue(
            g.onLlmFailure(ModelRuntimeException.ModelRateLimited("429", "p1"))
                is EngineResilienceGuard.LlmRetryDecision.Retry
        )
    }

    // ═══ 2. 空响应重试 ═══

    @Test
    fun `empty response retries until budget then returns null`() {
        // 显式预算（与生产默认解耦，见上）：2 次重试后第 3 次返回 null。
        val g = guard(
            EngineResiliencePolicy(
                maxEmptyResponseRetries = 2,
                initialBackoffMs = 0, maxBackoffMs = 0, jitterRatio = 0.0
            )
        )
        assertNotNull(g.onEmptyResponse())
        assertNotNull(g.onEmptyResponse())
        assertNull(g.onEmptyResponse())
    }

    // ═══ 3. 工具瞬时重试 ═══

    @Test
    fun `transient tool failure is retried, permission failure is not`() {
        val g = guard()
        val transient = g.onToolFailure("WebFetch", "c1", "socket timeout", attempt = 1)
        assertTrue(transient is RetryPolicy.RetryDecision.Retry)

        val permission = g.onToolFailure("fs_write", "c2", "permission denied", attempt = 1)
        assertTrue(permission is RetryPolicy.RetryDecision.Stop)
    }

    @Test
    fun `tool retry budget is task level across different calls`() {
        // FAST 继承 DEFAULT toolRetryPolicy：budget=6, maxRetries=2
        val g = guard()
        // 同一调用 3 次失败（1 次初始 + 2 次重试）→ 第 3 次 attempt 超限 Stop
        var decision = g.onToolFailure("t", "c", "network error", attempt = 1)
        assertTrue(decision is RetryPolicy.RetryDecision.Retry)
        decision = g.onToolFailure("t", "c", "network error", attempt = 2)
        assertTrue(decision is RetryPolicy.RetryDecision.Retry)
        decision = g.onToolFailure("t", "c", "network error", attempt = 3)
        assertTrue(decision is RetryPolicy.RetryDecision.Stop)
    }

    // ═══ 4. 连续失败 → 换路提示 ═══

    @Test
    fun `three consecutive tool failures inject recovery prompt`() {
        val g = guard() // threshold = 3
        assertNull(g.onToolCallOutcome("WebFetch", success = false))
        assertNull(g.onToolCallOutcome("WebFetch", success = false))
        val prompt = g.onToolCallOutcome("WebFetch", success = false)
        assertNotNull(prompt)
        assertTrue(prompt!!.contains("[ENGINE RECOVERY"))
        assertTrue(prompt.contains("different tool"))
        // 注入后连败计数清零：再一次失败不再立即触发
        assertNull(g.onToolCallOutcome("WebFetch", success = false))
    }

    @Test
    fun `success resets consecutive failure counter`() {
        val g = guard()
        g.onToolCallOutcome("t", success = false)
        g.onToolCallOutcome("t", success = false)
        g.onToolCallOutcome("t", success = true)
        assertNull(g.onToolCallOutcome("t", success = false))
        assertNull(g.onToolCallOutcome("t", success = false))
        // 第 3 连败才触发（成功清零过）
        assertNotNull(g.onToolCallOutcome("t", success = false))
    }

    @Test
    fun `recovery prompt budget exhausts and stays silent`() {
        val g = guard() // maxRecoveryPrompts = 3
        repeat(3) {
            // 每组 3 连败
            g.onToolCallOutcome("t", success = false)
            g.onToolCallOutcome("t", success = false)
            assertNotNull(g.onToolCallOutcome("t", success = false))
        }
        g.onToolCallOutcome("t", success = false)
        g.onToolCallOutcome("t", success = false)
        assertNull(g.onToolCallOutcome("t", success = false))
    }

    // ═══ 5. 退避曲线 ═══

    @Test
    fun `backoff grows exponentially and is capped`() {
        val policy = EngineResiliencePolicy(
            initialBackoffMs = 1000,
            backoffMultiplier = 2.0,
            maxBackoffMs = 5000,
            jitterRatio = 0.0
        )
        val g = EngineResilienceGuard(policy, random = Random(1))
        assertEquals(1000L, g.backoffMs(1))
        assertEquals(2000L, g.backoffMs(2))
        assertEquals(4000L, g.backoffMs(3))
        assertEquals(5000L, g.backoffMs(4)) // cap
        assertEquals(5000L, g.backoffMs(10))
    }

    @Test
    fun `backoff with jitter stays within band`() {
        val policy = EngineResiliencePolicy(
            initialBackoffMs = 1000,
            backoffMultiplier = 1.0,
            maxBackoffMs = 1000,
            jitterRatio = 0.25
        )
        val g = EngineResilienceGuard(policy, random = Random(7))
        repeat(20) {
            val d = g.backoffMs(1)
            assertTrue("backoff $d out of band", d in 750L..1250L)
        }
    }

    // ═══ 6. DISABLED 策略（旧「遇错即停」行为兼容）═══

    @Test
    fun `disabled policy stops immediately on every failure`() {
        val g = guard(EngineResiliencePolicy.DISABLED)
        assertTrue(
            g.onLlmFailure(ModelRuntimeException.ModelRateLimited("429", "p"))
                is EngineResilienceGuard.LlmRetryDecision.Stop
        )
        assertNull(g.onEmptyResponse())
        assertTrue(
            g.onToolFailure("t", "c", "network error", 1)
                is RetryPolicy.RetryDecision.Stop
        )
        assertNull(g.onToolCallOutcome("t", success = false))
    }
}
