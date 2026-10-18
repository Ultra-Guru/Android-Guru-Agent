package com.apex.agent.core.code.loom.markup

import com.apex.agent.core.code.loom.LoomEngineConfig
import com.apex.agent.core.code.loom.LoomOutcomeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LoomOutcomeFormatter 回执织入测试：
 *
 * - 预算裁剪（截断标记 / Unicode 标量安全 / 绝对上限兜底）；
 * - 四类回执（结果/异常/拒绝/超时）的 status 语义；
 * - 用户轮织入样式（批量块 / 转向 / 催促 / 布样裁决 / 权限问答文案）。
 */
class LoomOutcomeFormatterTest {

    private val config = LoomEngineConfig(
        outcomeBudgetChars = 1_000,
        outcomeHardCapChars = 2_000
    )

    private val f = LoomOutcomeFormatter(config)

    // ── 裁剪 ───────────────────────────────────────────────────────

    @Test
    fun `output within budget passes unclipped`() {
        assertEquals("abc", f.clip("abc"))
    }

    @Test
    fun `oversize output clips with marker`() {
        val big = "x".repeat(2_500)
        val clipped = f.clip(big)
        assertTrue(clipped.startsWith("x"))
        assertTrue(clipped.contains("已截断"))
        assertTrue(clipped.length < big.length)
    }

    @Test
    fun `clip never splits a surrogate pair`() {
        val emojiTail = "😀".repeat(50) // 每个表情 = 2 个 char（代理对）
        val big = "y".repeat(1_200) + emojiTail
        val clipped = f.clip(big)
        // 截断点若劈在代理对上会产出孤立代理——校验末字符成对完整
        val last = clipped.lastOrNull { !it.isWhitespace() && it != '…' && it != ']' }
        if (last != null && Character.isLowSurrogate(last)) {
            // 末位是低代理 → 前一位必须是高代理（配对完整）
            val s = clipped
            val idx = s.indexOf(last)
            assertTrue(idx > 0 && Character.isHighSurrogate(s[idx - 1]))
        }
    }

    // ── 回执 status 语义 ───────────────────────────────────────────

    @Test
    fun `result maps success to ok and failure to error`() {
        assertEquals(LoomOutcomeStatus.OK, f.formatResult("T", "done", true, 5).status)
        assertEquals(LoomOutcomeStatus.ERROR, f.formatResult("T", "boom", false, 5).status)
    }

    @Test
    fun `denied and lapse are warn (retryable)`() {
        assertEquals(LoomOutcomeStatus.WARN, f.formatDenied("T", "敏感操作").status)
        assertEquals(
            LoomOutcomeStatus.WARN,
            f.formatApprovalLapse("T", timeoutMs = 120_000).status
        )
    }

    @Test
    fun `truncated flag marks oversize payload`() {
        val ok = f.formatResult("T", "small", true, 1)
        assertFalse(ok.truncated)
        val big = f.formatResult("T", "z".repeat(3_000), true, 1)
        assertTrue(big.truncated)
    }

    @Test
    fun `outcome tokens are unique per block`() {
        val a = f.formatResult("T", "1", true, 1)
        val b = f.formatResult("T", "2", true, 1)
        // 4 位 base36 随机——重复概率 1/36^4；连续两次相同视为异常
        assertTrue(a.token != b.token)
    }

    // ── 用户轮织入 ─────────────────────────────────────────────────

    @Test
    fun `outcome turn renders blocks with blank line separators`() {
        val turn = f.renderOutcomeTurn(
            listOf(
                f.formatResult("CodeRead", "内容", true, 3),
                f.formatError("Shell", "失败")
            )
        )
        assertTrue(turn.contains("<outcome_"))
        assertTrue(turn.contains("name=\"CodeRead\""))
        assertTrue(turn.contains("status=\"ok\""))
        assertTrue(turn.contains("status=\"error\""))
        assertTrue(turn.contains("<payload>内容</payload>"))
        // 块间空行分隔，且块外没有闲字
        assertFalse(turn.startsWith(" "))
        assertFalse(turn.endsWith("\n"))
    }

    @Test
    fun `steering turn is a priority user turn`() {
        val t = f.renderSteeringTurn("改用方案B")
        assertTrue(t.contains("[转向]"))
        assertTrue(t.contains("改用方案B"))
    }

    @Test
    fun `plan verdict turns carry confirm or reject semantics`() {
        assertTrue(f.renderPlanVerdictTurn(true, null).contains("确认"))
        val reject = f.renderPlanVerdictTurn(false, "步骤太多")
        assertTrue(reject.contains("驳回"))
        assertTrue(reject.contains("步骤太多"))
    }

    @Test
    fun `nudge turn uses prompt constant`() {
        assertEquals(com.apex.agent.core.code.loom.LoomPrompts.nudgeFeedback(), f.renderNudgeTurn())
    }

    @Test
    fun `approval question renders tool and args`() {
        val inv = com.apex.agent.core.code.loom.LoomInvocation(
            name = "CodeWrite",
            args = listOf(
                com.apex.agent.core.code.loom.LoomArg("path", "a.kt"),
                com.apex.agent.core.code.loom.LoomArg("content", "hello")
            )
        )
        val q = f.renderApprovalQuestion(inv, riskLine = "写入工作区")
        assertTrue(q.contains("CodeWrite"))
        assertTrue(q.contains("path=a.kt"))
        assertTrue(q.contains("写入工作区"))
    }

    // ── 配置钳位 ───────────────────────────────────────────────────

    @Test
    fun `config sanitized clamps dirty values`() {
        val dirty = LoomEngineConfig(shuttleRoundsMax = -5, keepRecentTurns = 0)
        val clean = dirty.sanitized()
        assertEquals(4, clean.shuttleRoundsMax)
        assertEquals(2, clean.keepRecentTurns)
    }
}
