package com.apex.agent.ui.screen.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BUG-08 + PR1 恢复净化语义：OpenTabsSaver.restore 逐项经别名表解析到
 * 稳定 page.id —— 旧 route 静默升级；未知/失效项**只丢弃自身**，绝不因
 * 一个失效标签清空全部标签。
 */
class SettingsTabSanitizerTest {

    @Test
    fun `未知route只丢弃自身_其余标签保留`() {
        val restored = sanitizeRestoredTabs(
            listOf("model/setup", "old/gone", "data/backup").joinToString("\u0000")
        )
        assertEquals(listOf("model.connection", "system.backup"), restored)
    }

    @Test
    fun `全部未知时返回空列表`() {
        assertTrue(sanitizeRestoredTabs("a/b\u0000c/d").isEmpty())
    }

    @Test
    fun `空段与空白段被过滤`() {
        val restored = sanitizeRestoredTabs("\u0000\u0000model/tools\u0000")
        assertEquals(listOf("model.tools"), restored)
    }

    @Test
    fun `page_id输入幂等返回自身`() {
        val restored = sanitizeRestoredTabs("model.tools\u0000agent.policy")
        assertEquals(listOf("model.tools", "agent.policy"), restored)
    }

    @Test
    fun `合并页两旧route解析到同一page_id`() {
        assertEquals(
            sanitizeRestoredTabs("model/setup"),
            sanitizeRestoredTabs("model/providers-roles")
        )
    }

    @Test
    fun `全部23个旧route均可解析到注册表内page_id`() {
        val allLegacy = SettingsNavAliases.aliases.keys
        assertEquals(23, allLegacy.size)
        val resolved = sanitizeRestoredTabs(allLegacy.joinToString("\u0000"))
        assertEquals(allLegacy.size, resolved.size)
        resolved.forEach { assertTrue(it in SettingsPages.ids) }
    }

    @Test
    fun `resolvePageId_未知返回null_page_id幂等`() {
        assertNull(SettingsNavAliases.resolvePageId("old/gone"))
        assertEquals("model.tools", SettingsNavAliases.resolvePageId("model.tools"))
    }
}
