package com.apex.agent.ui.screen.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 浏览器式标签关闭回退规则（closeSettingsTab）的边界用例锁。
 *
 * 规则：关闭非激活标签 → 激活态不变；关闭激活标签 → 回退相邻标签
 * （左优先，无左取右）；全部关闭 → null（上层据此重开设置抽屉）。
 */
class SettingsTabBarTest {

    private val tabs = listOf("model/setup", "model/generation", "agent/execution")

    @Test
    fun `close inactive tab keeps active unchanged`() {
        val (next, active) = closeSettingsTab(tabs, "model/setup", "model/generation")
        assertEquals(listOf("model/setup", "agent/execution"), next)
        assertEquals("model/setup", active)
    }

    @Test
    fun `close active tab falls back to left neighbor`() {
        val (next, active) = closeSettingsTab(tabs, "model/generation", "model/generation")
        assertEquals(listOf("model/setup", "agent/execution"), next)
        assertEquals("model/setup", active)
    }

    @Test
    fun `close active first tab falls back to right neighbor`() {
        val (next, active) = closeSettingsTab(tabs, "model/setup", "model/setup")
        assertEquals(listOf("model/generation", "agent/execution"), next)
        assertEquals("model/generation", active)
    }

    @Test
    fun `close last remaining tab returns null active`() {
        val (next, active) = closeSettingsTab(listOf("agent/roles"), "agent/roles", "agent/roles")
        assertTrue(next.isEmpty())
        assertNull(active)
    }

    @Test
    fun `close unknown route is a no-op`() {
        val (next, active) = closeSettingsTab(tabs, "model/setup", "data/backup")
        assertEquals(tabs, next)
        assertEquals("model/setup", active)
    }

    @Test
    fun `close active last of three falls back to middle`() {
        val (next, active) = closeSettingsTab(tabs, "agent/execution", "agent/execution")
        assertEquals(listOf("model/setup", "model/generation"), next)
        assertEquals("model/generation", active)
    }
}
