package com.apex.agent.core.code.thinking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // ═══ SPE-02：滞回闸门状态机（表驱动）═══

    @Test
    fun `escalation gates blocked by cooldown after shift`() {
        val t = CodeAdaptiveSignalTracker()
        t.beginRun()
        t.markEscalation() // 升档落地：冷却重启 + 连击清零
        val gates = t.adaptiveShiftGates()
        assertEquals(1, gates.runEscalations)
        assertEquals("升档后冷却进度从 0 起算", 0, gates.callsSinceLastShift)
        assertEquals(0, gates.consecutiveSuccesses)
        // 冷却窗口内（< cooldownToolCalls=15）：升级观察器被门控拒绝
        repeat(5) { t.onToolComplete(true) }
        val selector = CodeAdaptiveThinkingSelector()
        val esc = t.escalationSignals()
        assertNull(
            "冷却内的深水区信号（5 调用 0 失败不触发是参数原因，另测冷却）",
            selector.escalateOnDeepWater(esc.runToolCalls, esc.recentWindowErrors, CodeThinkingLevel.STANDARD, t.adaptiveShiftGates())
        )
    }

    @Test
    fun `escalation cooldown blocks even with deep water signals`() {
        // 冷却期内即使满足深水区信号也不重复升档（SPE-02 验收 1）。
        val t = CodeAdaptiveSignalTracker()
        t.beginRun()
        t.markEscalation()
        // 满足深水区信号：31 调用、滑窗 2 失败
        t.onToolComplete(false)
        t.onToolComplete(false)
        repeat(29) { t.onToolComplete(true) }
        t.onToolComplete(false)
        t.onToolComplete(false)
        val esc = t.escalationSignals()
        val selector = CodeAdaptiveThinkingSelector()
        assertNull(
            "冷却未过（callsSinceLastShift=31 ≥ 15 已过…）——此例冷却已过，应按升档上限拒绝",
            selector.escalateOnDeepWater(esc.runToolCalls, esc.recentWindowErrors, CodeThinkingLevel.STANDARD, t.adaptiveShiftGates())
        )
    }

    @Test
    fun `escalation count capped per run`() {
        // 单 run 升档次数上限（maxAutoEscalationsPerRun=1）：升过一次后
        // 即使冷却结束且信号再现也不再升（SPE-02 验收 6 成本上界）。
        val t = CodeAdaptiveSignalTracker()
        t.beginRun()
        t.markEscalation()
        repeat(20) { t.onToolComplete(true) } // 冷却已过（20 ≥ 15）
        t.onToolComplete(false)
        t.onToolComplete(false)
        val esc = t.escalationSignals()
        val gates = t.adaptiveShiftGates()
        assertEquals(1, gates.runEscalations)
        assertNull(
            "升档上限已到（1 次），重复升级被拒",
            CodeAdaptiveThinkingSelector().escalateOnDeepWater(
                esc.runToolCalls + 10, 2, CodeThinkingLevel.STANDARD, gates
            )
        )
    }

    @Test
    fun `deescalation triggers after cooldown and success streak`() {
        // 稳定回落：冷却已过 + 连续成功 ≥ 5 → 回落到基准档（SPE-02 验收 1 后半）。
        val t = CodeAdaptiveSignalTracker()
        t.beginRun()
        t.markEscalation()
        repeat(20) { t.onToolComplete(true) } // 冷却 20 ≥ 15、连击 20 ≥ 5
        val gates = t.adaptiveShiftGates()
        val d = CodeAdaptiveThinkingSelector().deescalateOnStabilized(
            gates = gates,
            current = CodeThinkingLevel.ULTRACODE,
            base = CodeThinkingLevel.STANDARD
        ) ?: error("冷却已过且连击 20 次成功应触发回落")
        assertEquals(CodeThinkingLevel.STANDARD, d.level)
        assertTrue(d.reason.contains("回落"))
    }

    @Test
    fun `deescalation blocked during cooldown or short streak`() {
        val t = CodeAdaptiveSignalTracker()
        t.beginRun()
        t.markEscalation()
        repeat(10) { t.onToolComplete(true) } // 冷却未过（10 < 15）、连击 10
        assertNull(
            "冷却未过：回落应被拒",
            CodeAdaptiveThinkingSelector().deescalateOnStabilized(
                t.adaptiveShiftGates(), CodeThinkingLevel.ULTRACODE, CodeThinkingLevel.STANDARD
            )
        )
        // 冷却过了但连击不足：失败打断连击
        t.beginRun() // 清闸门（run 边界）
        repeat(20) { t.onToolComplete(true) }
        t.onToolComplete(false) // 连击清零
        repeat(3) { t.onToolComplete(true) } // 连击 3 < 5
        assertNull(
            "连击不足：回落应被拒",
            CodeAdaptiveThinkingSelector().deescalateOnStabilized(
                t.adaptiveShiftGates(), CodeThinkingLevel.ULTRACODE, CodeThinkingLevel.STANDARD
            )
        )
    }

    @Test
    fun `deescalation never targets above current`() {
        // current ≤ base → null（防「降档变升档」）。
        assertNull(
            CodeAdaptiveThinkingSelector().deescalateOnStabilized(
                AdaptiveShiftGates(callsSinceLastShift = 99, consecutiveSuccesses = 99),
                CodeThinkingLevel.STANDARD,
                CodeThinkingLevel.ULTRACODE
            )
        )
    }

    @Test
    fun `beginRun resets hysteresis gates`() {
        val t = CodeAdaptiveSignalTracker()
        t.beginRun()
        t.markEscalation()
        t.onToolComplete(true)
        t.beginRun() // 新 run：闸门全清
        val gates = t.adaptiveShiftGates()
        assertEquals(0, gates.runEscalations)
        assertEquals(Int.MAX_VALUE, gates.callsSinceLastShift)
        assertEquals(0, gates.consecutiveSuccesses)
    }

    // ═══ SPE-02：任务指纹衰减（错误史跨任务残留）═══

    @Test
    fun `same task keeps error history`() {
        val t = CodeAdaptiveSignalTracker()
        t.beginRun()
        t.onToolComplete(false)
        t.onToolComplete(false)
        t.endRun("修好这个 bug") // 归档 + 记指纹
        // 同任务续跑：错误史保留（预检 +2 且升一档）
        t.onNewTaskBoundary("修好这个 bug")
        assertEquals(2, t.preCheckSignals().lastRunErrors)
    }

    @Test
    fun `new task decays error history`() {
        // SPE-02 验收 2：同会话换任务，上一轮错误史归零、不保底 ULTRACODE。
        val t = CodeAdaptiveSignalTracker()
        t.beginRun()
        repeat(45) { i -> t.onToolComplete(i < 2) } // 45 调用、滑窗内 2 失败
        t.endRun("旧的长任务")
        t.onNewTaskBoundary("一个完全无关的新任务")
        val pre = t.preCheckSignals()
        assertEquals("换任务后错误史衰减为 0", 0, pre.lastRunErrors)
        assertEquals("工具调用数保留（弱语义：长会话反复跑工具）", 45, pre.lastRunToolCalls)
        // 预检不再保底 ULTRACODE（errors=2 条件失效），也不升档
        val d = CodeAdaptiveThinkingSelector().select("一个完全无关的新任务", pre.lastRunToolCalls, pre.lastRunErrors)
        assertTrue(
            "换任务后预检不应保底 ULTRACODE（实际 ${d.level}）",
            d.level < CodeThinkingLevel.ULTRACODE
        )
    }

    @Test
    fun `fingerprint normalizes whitespace and case`() {
        val t = CodeAdaptiveSignalTracker()
        t.beginRun()
        t.onToolComplete(false)
        t.endRun("  Fix The Bug  ")
        t.onNewTaskBoundary("fix the bug") // 归一化后同指纹 → 保留错误史
        assertEquals(1, t.preCheckSignals().lastRunErrors)
    }

    @Test
    fun `resetConversation clears fingerprint`() {
        val t = CodeAdaptiveSignalTracker()
        t.beginRun()
        t.onToolComplete(false)
        t.endRun("task")
        t.resetConversation()
        t.onNewTaskBoundary("task") // 指纹已清：无衰减基准（保留现状=零错误史）
        assertEquals(0, t.preCheckSignals().lastRunErrors)
    }
}
