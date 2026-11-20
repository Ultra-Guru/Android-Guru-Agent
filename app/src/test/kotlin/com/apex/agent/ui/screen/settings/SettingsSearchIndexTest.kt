package com.apex.agent.ui.screen.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR3 全局搜索索引契约(FR-302~306 页级简版):
 * - 空查询零结果;`key:` 前缀按 sectionId 匹配;
 * - 排序 = 精确别名 > 标题包含 > 别名包含;
 * - 每个结果都指向注册表内真实 page.id。
 */
class SettingsSearchIndexTest {

    @Test
    fun `空查询返回空`() {
        assertTrue(SettingsSearchIndex.query(SettingsPages.all, "").isEmpty())
        assertTrue(SettingsSearchIndex.query(SettingsPages.all, "   ").isEmpty())
    }

    @Test
    fun `精确别名命中得分最高`() {
        val hits = SettingsSearchIndex.query(SettingsPages.all, "SearXNG")
        assertTrue(hits.isNotEmpty())
        assertEquals("services.search", hits.first().pageId)
        assertEquals(0, hits.first().score)
    }

    @Test
    fun `key语法按sectionId匹配`() {
        val hits = SettingsSearchIndex.query(SettingsPages.all, "key:rateLimit")
        assertTrue(hits.any { it.pageId == "system.diagnostics" })
        hits.forEach { assertNotNull(it.sectionId) }
    }

    @Test
    fun `结果全部指向注册表内真实页面`() {
        val hits = SettingsSearchIndex.query(SettingsPages.all, "model")
        hits.forEach { assertTrue(SettingsPages.byId.containsKey(it.pageId)) }
    }

    @Test
    fun `无结果查询返回空`() {
        assertTrue(SettingsSearchIndex.query(SettingsPages.all, "zzzznotexist").isEmpty())
    }
}

private fun assertNotNull(value: Any?) {
    org.junit.Assert.assertNotNull(value)
}
