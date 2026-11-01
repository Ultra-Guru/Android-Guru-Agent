package com.apex.agent.core.code.thinking

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [CodeAdaptiveSignalTracker] 状态机测试。
 *
 * 断言四件事：
 * 1. 单 run 时序契约：预检读归档 → beginRun 只清本轮 → endRun 归档本轮；
 * 2. 滑窗尺寸：超窗弹出最旧（与观察器 RECENT_WINDOW=3 口径一致）；
 * 3. 会话边界：resetConversation 全量清零（跨会话信号不泄漏）；
 * 4. 与选档器联训：tracker 快照直接喂 select / escalateOnDeepWater。
 */
class CodeAdaptiveSignalTrackerTest {

    // ═══ 单 run 时序契约 ═══

    @Test
    fun `precheck reads archive and beginRun keeps archive`() {
        val t = CodeAdaptiveSignalTracker()
        // run #1：2 次失败 + 1 次成功
        t.onToolComplete(false)
        t.onToolComplete(false)
        t.onToolComplete(true)
        t.endRun()

        // 预检（run #2 发送前）：读到 run #1 的归档
        val pre = t.preCheckSignals()
        assertEquals(3, pre.lastRunToolCalls)
        assertEquals(2, pre.lastRunErrors)

        // beginRun 清本轮、**不动归档**（预检刚消费的信号不能被自己清掉）
        t.beginRun()
        assertEquals(
            "beginRun 后归档必须保持（预检信号不被清）",
            CodeAdaptiveSignalTracker.PreCheckSignals(3, 2),
            t.preCheckSignals()
        )
        assertEquals(
            "beginRun 后本轮计数归零",
            CodeAdaptiveSignalTracker.EscalationSignals(0, 0),
            t.escalationSignals()
        )
    }

    @Test
    fun `endRun archives current window error count`() {
        val t = CodeAdaptiveSignalTracker()
        t.beginRun()
        t.onToolComplete(true)
        t.onToolComplete(false)
        t.onToolComplete(true)
        t.onToolComplete(true)
        t.endRun()
        // 4 次调用全计入归档总数；窗口内（后 3 次）失败 1 次
        val pre = t.preCheckSignals()
        assertEquals(4, pre.lastRunToolCalls)
        assertEquals(1, pre.lastRunErrors)
    }

    // ═══ 滑窗尺寸 ═══

    @Test
    fun `window slides beyond size`() {
        val t = CodeAdaptiveSignalTracker(windowSize = 3)
        t.beginRun()
        t.onToolComplete(false)
        t.onToolComplete(false)
        t.onToolComplete(true)
        // 第 4 次把最旧的失败弹出：窗口 = [false, true, false] → 2 失败
        t.onToolComplete(false)
        val esc = t.escalationSignals()
        assertEquals(4, esc.runToolCalls)
        assertEquals(2, esc.recentWindowErrors)
    }

    @Test
    fun `custom window size one keeps only latest`() {
        val t = CodeAdaptiveSignalTracker(windowSize = 1)
        t.beginRun()
        t.onToolComplete(false)
        t.onToolComplete(true)
        t.onToolComplete(false)
        assertEquals(1, t.escalationSignals().recentWindowErrors)
    }

    // ═══ 会话边界 ═══

    @Test
    fun `resetConversation clears everything`() {
        val t = CodeAdaptiveSignalTracker()
        t.onToolComplete(false)
        t.onToolComplete(false)
        t.endRun()
        t.resetConversation()
        assertEquals(
            "会话边界后预检信号必须全零（旧会话错误史不带入新会话）",
            CodeAdaptiveSignalTracker.PreCheckSignals(0, 0),
            t.preCheckSignals()
        )
        assertEquals(
            CodeAdaptiveSignalTracker.EscalationSignals(0, 0),
            t.escalationSignals()
        )
    }

    @Test
    fun `resetConversation mid archive keeps endRun semantics after`() {
        val t = CodeAdaptiveSignalTracker()
        t.onToolComplete(false)
        t.endRun()
        t.resetConversation()
        // 新会话第一轮：干净的预检
        assertEquals(0, t.preCheckSignals().lastRunErrors)
        // 新会话首轮 run 结束后归档恢复正常
        t.beginRun()
        t.onToolComplete(false)
        t.endRun()
        assertEquals(1, t.preCheckSignals().lastRunErrors)
    }

    // ═══ 与选档器联训（快照直喂，契约不漂移）═══

    @Test
    fun `snapshot feeds selector precheck`() {
        val t = CodeAdaptiveSignalTracker()
        t.onToolComplete(false)
        t.onToolComplete(false)
        t.endRun()
        t.beginRun()
        val pre = t.preCheckSignals()
        // 短平快文本本身选 LIGHT（基线见选档器测试）；错误史 2 次 → +2 分仍
        // < 3，但「错误史升一档」显式抬升 → STANDARD（预检被旧 run 信号抬档）。
        val d = CodeAdaptiveThinkingSelector().select("改下 README 拼写", pre.lastRunToolCalls, pre.lastRunErrors)
        assertEquals(CodeThinkingLevel.STANDARD, d.level)
    }

    @Test
    fun `snapshot feeds selector escalation`() {
        val t = CodeAdaptiveSignalTracker()
        t.beginRun()
        repeat(31) { i -> t.onToolComplete(i < 2) } // 31 调用、最近 3 次全失败（≥2 触发线）
        val esc = t.escalationSignals()
        val d = CodeAdaptiveThinkingSelector().escalateOnDeepWater(
            runToolCalls = esc.runToolCalls,
            recentWindowErrors = esc.recentWindowErrors,
            current = CodeThinkingLevel.STANDARD
        )
        assertEquals(CodeThinkingLevel.ULTRACODE, d?.level)
    }
}
