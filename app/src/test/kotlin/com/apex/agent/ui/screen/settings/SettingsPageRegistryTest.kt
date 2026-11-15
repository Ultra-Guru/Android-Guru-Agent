package com.apex.agent.ui.screen.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR1 页面注册表契约(FR-101/102/103):
 * - page.id 全局唯一、groupId 全部被 SettingsNavGroups 引用;
 * - sectionId 跨页全局唯一(锚点寻址前提);
 * - 每页 keywords 非空(搜索别名,FR-302 的数据源);
 * - 分组不引用不存在页面;图标非空由编译期保证。
 */
class SettingsPageRegistryTest {

    @Test
    fun `page_id 全局唯一`() {
        val ids = SettingsPages.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `sectionId 跨页全局唯一`() {
        val sections = SettingsPages.allSectionIds
        assertEquals(sections.size, sections.toSet().size)
    }

    @Test
    fun `分组引用的页面全部存在_且覆盖全部页面`() {
        val grouped = SettingsNavGroups.all.flatMap { it.pageIds }
        grouped.forEach { assertNotNull(SettingsPages.byId[it]) }
        assertEquals(SettingsPages.all.map { it.id }.toSet(), grouped.toSet())
    }

    @Test
    fun `分组 id 与页面 groupId 一致`() {
        val groupIds = SettingsNavGroups.all.map { it.groupId }.toSet()
        SettingsPages.all.forEach { assertTrue(it.groupId in groupIds) }
    }

    @Test
    fun `每页搜索别名非空且至少3条`() {
        SettingsPages.all.forEach { page ->
            assertTrue("page ${page.id} keywords", page.keywords.size >= 3)
        }
    }

    @Test
    fun `每页 sectionId 非空`() {
        SettingsPages.all.forEach { page ->
            assertTrue("page ${page.id} sections", page.sectionIds.isNotEmpty())
        }
    }

    @Test
    fun `危险页标记_backup页为danger`() {
        assertTrue(SettingsPages.byId.getValue("system.backup").danger)
    }

    @Test
    fun `别名词值全部命中注册表_无悬空映射`() {
        SettingsNavAliases.aliases.values.forEach { target ->
            assertNotNull("alias target $target", SettingsPages.byId[target])
        }
    }

    @Test
    fun `alias表覆盖23个旧route_键无重复`() {
        assertEquals(23, SettingsNavAliases.aliases.size)
    }

    @Test
    fun `旧route的分组反查_抽屉高亮兼容`() {
        // connection 页有两个旧 route,主旧 route = 别名表首条(model/setup)
        assertEquals("model/setup", SettingsNavAliases.legacyRouteOf("model.connection"))
        assertNull(SettingsNavAliases.legacyRouteOf("nonexistent.page"))
    }
}
