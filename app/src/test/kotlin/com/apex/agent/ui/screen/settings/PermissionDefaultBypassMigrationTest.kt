package com.apex.agent.ui.screen.settings

import com.apex.agent.permission.PermissionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [migratePermissionDefaultBypass] 迁移语义回归（S-03 反转后：不再自动提权）。
 *
 * 历史：2026-10 曾按用户反馈把默认档 DEFAULT → BYPASS，并对停在旧默认
 * DEFAULT 的存量用户做一次性翻转 —— 等于无感知自动提权（S-03 修复对象）。
 * 本套件把反转后的语义钉死：
 *
 * - 存量任何档位都不被迁移改变（含「尚未跑过旧迁移」的 DEFAULT 用户）；
 * - 新装机默认 DEFAULT（AgentSettings 缺省值，非 BYPASS）；
 * - 迁移标记幂等短路（flag=true 不再重复执行）；
 * - 已被旧迁移翻到 BYPASS 的用户保持不动（反向翻转同样是无感知变更）。
 */
class PermissionDefaultBypassMigrationTest {

    @Test
    fun `legacy default mode is never escalated to bypass`() {
        // 存量用户：持久化 JSON 是旧默认 DEFAULT 且未跑过旧迁移 ——
        // S-03 后不得被翻转（自动提权路径关闭）
        val legacy = AgentSettings(permissionMode = PermissionMode.DEFAULT, permissionBypassMigrated = false)
        val migrated = migratePermissionDefaultBypass(legacy)
        assertEquals("存量 DEFAULT 保持 DEFAULT（不自动提权）", PermissionMode.DEFAULT, migrated.permissionMode)
        assertTrue("迁移标记仍落位（幂等短路用）", migrated.permissionBypassMigrated)
    }

    @Test
    fun `all persisted modes are preserved`() {
        // 任何已持久化档位（含 BYPASS / ACCEPT_EDITS / PLAN）迁移后不动
        for (mode in PermissionMode.entries) {
            val explicit = AgentSettings(permissionMode = mode, permissionBypassMigrated = false)
            val migrated = migratePermissionDefaultBypass(explicit)
            assertEquals("档位 $mode 迁移后保持不动", mode, migrated.permissionMode)
            assertTrue(migrated.permissionBypassMigrated)
        }
    }

    @Test
    fun `migrated flag short circuits repeat runs`() {
        // 旧迁移已运行（flag=true）：幂等短路，值与档位零变化
        val settled = AgentSettings(permissionMode = PermissionMode.BYPASS, permissionBypassMigrated = true)
        val again = migratePermissionDefaultBypass(settled)
        assertEquals("幂等：已迁移不再变化", settled, again)

        val settledDefault = AgentSettings(permissionMode = PermissionMode.DEFAULT, permissionBypassMigrated = true)
        assertEquals(settledDefault, migratePermissionDefaultBypass(settledDefault))
    }

    @Test
    fun `fresh install default is DEFAULT not bypass`() {
        // S-03 DoD：全新安装 permissionMode == DEFAULT（新装机不再全自动）
        val fresh = AgentSettings()
        assertEquals("开箱默认 DEFAULT（副作用工具走确认面）", PermissionMode.DEFAULT, fresh.permissionMode)
        assertFalse(fresh.permissionBypassMigrated)
        val migrated = migratePermissionDefaultBypass(fresh)
        assertEquals("迁移不改变新装机档位", PermissionMode.DEFAULT, migrated.permissionMode)
        assertTrue(migrated.permissionBypassMigrated)
    }

    @Test
    fun `legacy bypass users stay on bypass no reverse flip`() {
        // 已被旧迁移翻到 BYPASS 的存量用户：无法区分「被翻上去」与「显式
        // 选择」，反向翻转同样是无感知变更 —— 保持不动，想收紧的用户在
        // 设置页显式切回（切出 BYPASS 不需要确认）。
        val legacyBypass = AgentSettings(permissionMode = PermissionMode.BYPASS, permissionBypassMigrated = false)
        val migrated = migratePermissionDefaultBypass(legacyBypass)
        assertEquals("存量 BYPASS 保持 BYPASS（档位不变）", PermissionMode.BYPASS, migrated.permissionMode)
        assertTrue(migrated.permissionBypassMigrated)
    }
}
