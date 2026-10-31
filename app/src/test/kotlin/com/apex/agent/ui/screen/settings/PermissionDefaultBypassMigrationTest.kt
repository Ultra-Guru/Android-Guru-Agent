package com.apex.agent.ui.screen.settings

import com.apex.agent.permission.PermissionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [migratePermissionDefaultBypass] 存量默认档迁移测试（2026-10 用户反馈
 * 「默认就是开启所有工具，agent 可以直接使用」—— 默认档 DEFAULT → BYPASS）。
 *
 * 覆盖：旧默认翻转 / 显式档位保留 / 幂等短路 / 新装机零行为差。
 */
class PermissionDefaultBypassMigrationTest {

    @Test
    fun `legacy default mode flips to bypass with flag set`() {
        // 存量用户：持久化 JSON 里是旧默认 DEFAULT（无法区分显式选择与从未动过）
        val legacy = AgentSettings(permissionMode = PermissionMode.DEFAULT, permissionBypassMigrated = false)
        val migrated = migratePermissionDefaultBypass(legacy)
        assertEquals("旧默认翻转为全自动", PermissionMode.BYPASS, migrated.permissionMode)
        assertTrue("迁移标记落位（不再重复执行）", migrated.permissionBypassMigrated)
    }

    @Test
    fun `explicit non default modes are preserved`() {
        // 显式选过 ACCEPT_EDITS / PLAN 的用户：模式不动，仅落标记
        for (mode in listOf(PermissionMode.ACCEPT_EDITS, PermissionMode.PLAN, PermissionMode.BYPASS)) {
            val explicit = AgentSettings(permissionMode = mode, permissionBypassMigrated = false)
            val migrated = migratePermissionDefaultBypass(explicit)
            assertEquals("显式档位 $mode 保留", mode, migrated.permissionMode)
            assertTrue(migrated.permissionBypassMigrated)
        }
    }

    @Test
    fun `migrated flag short circuits repeat runs`() {
        // 迁移后用户手动切回 DEFAULT：标记已置位，不再被翻回 BYPASS
        val settled = AgentSettings(permissionMode = PermissionMode.DEFAULT, permissionBypassMigrated = true)
        val again = migratePermissionDefaultBypass(settled)
        assertEquals("幂等：已迁移不再翻转", PermissionMode.DEFAULT, again.permissionMode)
        assertTrue(again.permissionBypassMigrated)
    }

    @Test
    fun `fresh install default is already bypass`() {
        // 新装机：AgentSettings() 默认即 BYPASS，迁移只补标记（零行为差）
        val fresh = AgentSettings()
        assertEquals("开箱即全自动（工具/MCP/技能/shell 免确认）", PermissionMode.BYPASS, fresh.permissionMode)
        assertFalse(fresh.permissionBypassMigrated)
        val migrated = migratePermissionDefaultBypass(fresh)
        assertEquals(PermissionMode.BYPASS, migrated.permissionMode)
        assertTrue(migrated.permissionBypassMigrated)
    }
}
