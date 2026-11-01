package com.apex.agent.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #509 回归：AskUser 等待的三种结局必须被区分。
 *
 * 旧实现只区分「null=超时」与「其余=作答」，于是 cancelUserInput / abort 的
 * 空串唤醒被当成正常回答回填 `User answered: `（success=true）—— 用户点了
 * 取消，模型却收到一个肯定答复。这些用例锁定三态语义。
 */
class AskUserOutcomeTest {

    @Test
    fun `取消的空串不被当作作答`() {
        val outcome = askUserOutcome("")
        assertEquals(AskUserOutcome.NoAnswer, outcome)
        assertFalse(outcome.isSuccess)
        assertFalse(outcome.toolResultNote.startsWith("User answered:"))
        assertTrue(outcome.toolResultNote.contains("cancelled"))
        assertTrue(outcome.toolResultNote.contains("Do not treat cancellation as consent"))
    }

    @Test
    fun `超时仍然是独立结局`() {
        val outcome = askUserOutcome(null)
        assertEquals(AskUserOutcome.TimedOut, outcome)
        assertFalse(outcome.isSuccess)
        assertTrue(outcome.toolResultNote.contains("timed out"))
    }

    @Test
    fun `正常作答保持原契约`() {
        val outcome = askUserOutcome("use the staging bucket")
        assertEquals(AskUserOutcome.Answered("use the staging bucket"), outcome)
        assertTrue(outcome.isSuccess)
        assertEquals("User answered: use the staging bucket", outcome.toolResultNote)
    }

    @Test
    fun `空白作答按作答传递而不是取消`() {
        // 空白字符是用户真的输入了内容，由 UI 层拒绝提交；引擎侧不应把它
        // 静默改写成「取消」，否则模型看到的原因与实际不符。
        val outcome = askUserOutcome("   ")
        assertEquals(AskUserOutcome.Answered("   "), outcome)
        assertTrue(outcome.isSuccess)
    }
}
