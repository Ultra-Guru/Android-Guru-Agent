package com.apex.agent.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v4 三档自主级别 —— 持久化名解析与提问策略指令的画像锁。
 *
 * 指令正文是 EnginePrompts "## Autonomy" 段与 kit 线 sessionExtras 透传的
 * 单一事实源：本测试锁关键行为句（前置澄清开/关、半自主记录假设、完全
 * 自主高风险停手），防后续改动悄悄漂移。
 */
class AutonomyLevelTest {

    @Test
    fun `persistence name round trip`() {
        AutonomyLevel.entries.forEach { level ->
            assertEquals(level, AutonomyLevel.fromName(level.persistenceName))
            // 大小写容错
            assertEquals(level, AutonomyLevel.fromName(level.persistenceName.uppercase()))
        }
    }

    @Test
    fun `fromName unknown or blank falls to null`() {
        assertNull(AutonomyLevel.fromName(null))
        assertNull(AutonomyLevel.fromName(""))
        assertNull(AutonomyLevel.fromName("   "))
        assertNull(AutonomyLevel.fromName("autonomous"))
    }

    @Test
    fun `interactive instruction keeps asking on ambiguity`() {
        val text = AutonomyLevel.INTERACTIVE.promptInstruction(preClarify = false)
        assertTrue(text.contains("## Autonomy"))
        assertTrue(text.contains("correctness-affecting ambiguity"))
        // 同类问题合并 + 不重复问
        assertTrue(text.contains("Merge same-topic questions"))
        assertTrue(text.contains("never re-ask"))
        // 前置澄清是 FULLY 专属：INTERACTIVE 不出现
        assertFalse(text.contains("BEFORE starting the task"))
    }

    @Test
    fun `semi instruction asks only material decisions and records assumptions`() {
        val text = AutonomyLevel.SEMI.promptInstruction(preClarify = false)
        assertTrue(text.contains("Ask ONLY when a decision materially affects"))
        assertTrue(text.contains("record the assumptions"))
    }

    @Test
    fun `fully instruction with pre clarification batches questions once`() {
        val text = AutonomyLevel.FULLY.promptInstruction(preClarify = true)
        assertTrue(text.contains("BEFORE starting the task"))
        assertTrue(text.contains("ONE batch"))
        assertTrue(text.contains("NEVER ask"))
        // 高风险停手 + 零提问
        assertTrue(text.contains("High-risk operations"))
        assertTrue(text.contains("do NOT ask"))
    }

    @Test
    fun `fully instruction without pre clarification never asks`() {
        val text = AutonomyLevel.FULLY.promptInstruction(preClarify = false)
        assertTrue(text.contains("Do NOT ask the user anything, neither before nor during execution"))
        // 开启分支的批量提问句不出现
        assertFalse(text.contains("ONE batch"))
        assertTrue(text.contains("assumptions and risks"))
        assertTrue(text.contains("report the blocker"))
    }
}
