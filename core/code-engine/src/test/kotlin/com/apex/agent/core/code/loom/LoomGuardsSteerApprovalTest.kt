package com.apex.agent.core.code.loom

import com.apex.agent.core.code.loom.loop.LoomGuards
import com.apex.agent.core.code.loom.permission.LoomApprovalBox
import com.apex.agent.core.code.loom.steer.LoomSteering
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守卫 / 转向 / 问答信箱三件套测试：
 *
 * - LoomGuards：重复纬线三警四收敛 / 空转超限 / 纯思考识别 / nudge 预算；
 * - LoomSteering：仅运行中受理 / 有界队列 / 回合边界 drain；
 * - LoomApprovalBox：问答送达 / 超时未决 / 抢占取消 / 迟到回答。
 */
class LoomGuardsSteerApprovalTest {

    // ═══════════════════════════ LoomGuards ═══════════════════════════

    private fun batch(vararg calls: Pair<String, String>): List<LoomInvocation> =
        calls.map { LoomInvocation(name = it.first, args = listOf(LoomArg(it.second, "v"))) }

    @Test
    fun `guards proceed on first two identical batches`() {
        val g = LoomGuards(LoomEngineConfig())
        assertTrue(g.observeBatch(batch("A" to "x")) is LoomGuards.Verdict.Proceed)
        assertTrue(g.observeBatch(batch("A" to "x")) is LoomGuards.Verdict.Proceed)
    }

    @Test
    fun `guards caution on third and converge on fourth repeat`() {
        val g = LoomGuards(LoomEngineConfig())
        repeat(2) { g.observeBatch(batch("A" to "x")) }
        val third = g.observeBatch(batch("A" to "x"))
        assertTrue(third is LoomGuards.Verdict.Caution)
        val fourth = g.observeBatch(batch("A" to "x"))
        assertTrue(fourth is LoomGuards.Verdict.ForceConverge)
    }

    @Test
    fun `different batches do not trip the repeat guard`() {
        val g = LoomGuards(LoomEngineConfig())
        repeat(6) { i ->
            val verdict = g.observeBatch(batch("A" to "x$i"))
            assertTrue("第${i + 1}批应放行", verdict is LoomGuards.Verdict.Proceed)
        }
    }

    @Test
    fun `empty batch always proceeds`() {
        val g = LoomGuards(LoomEngineConfig())
        assertTrue(g.observeBatch(emptyList()) is LoomGuards.Verdict.Proceed)
    }

    @Test
    fun `empty rounds terminate after limit`() {
        val g = LoomGuards(LoomEngineConfig())
        assertFalse(g.noteRoundShape(hasText = false, hasDeliberation = false, hasInvocations = false))
        assertTrue(g.noteRoundShape(hasText = false, hasDeliberation = false, hasInvocations = false))
        // 有产出即清零
        assertFalse(g.noteRoundShape(hasText = true, hasDeliberation = false, hasInvocations = false))
        assertFalse(g.noteRoundShape(hasText = false, hasDeliberation = false, hasInvocations = false))
    }

    @Test
    fun `pure deliberation is detected and nudge budget enforced`() {
        val g = LoomGuards(LoomEngineConfig())
        assertTrue(
            g.isPureDeliberation(hasText = false, hasDeliberation = true, hasInvocations = false)
        )
        assertFalse(
            g.isPureDeliberation(hasText = true, hasDeliberation = true, hasInvocations = false)
        )
        assertTrue(g.tryConsumeNudge())
        assertTrue(g.tryConsumeNudge())
        assertFalse(g.tryConsumeNudge()) // nudgeMaxCount 默认 2
        g.resetForTask()
        assertTrue(g.tryConsumeNudge()) // 任务重置恢复预算
    }

    // ═══════════════════════════ LoomSteering ═══════════════════════════

    @Test
    fun `steering only accepted while running`() {
        val s = LoomSteering(LoomEngineConfig(steeringQueueMax = 2))
        assertFalse(s.offer("闲时指令"))
        s.setRunning(true)
        assertTrue(s.offer("运行中指令"))
        s.setRunning(false)
        assertTrue(s.pendingCount() == 0) // 收工清队列
    }

    @Test
    fun `steering queue is bounded and drains in order`() {
        val s = LoomSteering(LoomEngineConfig(steeringQueueMax = 2))
        s.setRunning(true)
        assertTrue(s.offer("一"))
        assertTrue(s.offer("二"))
        assertFalse(s.offer("三")) // 容量 2
        assertEquals(listOf("一", "二"), s.drain())
        assertEquals(0, s.pendingCount())
        assertTrue(s.drain().isEmpty()) // drain 幂等清空
    }

    // ═══════════════════════════ LoomApprovalBox ═══════════════════════════

    @Test
    fun `approval answer is delivered`() = runTest {
        val box = LoomApprovalBox()
        val deferred = async { box.await("允许执行吗？", timeoutMs = 60_000) }
        advanceUntilIdle() // 让 await 先挂起安装
        assertTrue(box.submit("允许"))
        assertEquals("允许", deferred.await())
        assertFalse(box.isPending())
    }

    @Test
    fun `approval timeout yields null`() = runTest {
        val box = LoomApprovalBox()
        val answer = box.await("允许吗？", timeoutMs = 10)
        assertNull(answer)
        assertFalse(box.isPending())
    }

    @Test
    fun `approval late submit is rejected`() = runTest {
        val box = LoomApprovalBox()
        val answer = box.await("允许吗？", timeoutMs = 10)
        assertNull(answer) // 超时收口
        assertFalse(box.submit("迟到的允许"))
    }

    @Test
    fun `approval cancel resolves pending as undecided`() = runTest {
        val box = LoomApprovalBox()
        val deferred = async { box.await("确认？", timeoutMs = 60_000) }
        advanceUntilIdle()
        assertTrue(box.cancelPending())
        assertNull(deferred.await())
    }
}
