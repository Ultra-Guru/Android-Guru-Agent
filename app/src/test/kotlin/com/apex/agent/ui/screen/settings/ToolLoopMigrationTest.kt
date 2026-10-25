package com.apex.agent.ui.screen.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [migrateToolLoopDefaults] 工具循环默认无上限迁移测试（用户显式要求：
 * 一个任务的工具调用次数要无上限）。
 *
 * 覆盖：存量上限翻转 / 幂等短路 / 迁移后用户重新设上限不再被翻 /
 * 新装机零行为差。
 */
class ToolLoopMigrationTest {

    @Test
    fun `legacy capped iterations flips to unlimited sentinel`() {
        // 存量用户：持久化 JSON 里 maxIterations=20（旧默认），标记未落
        val legacy = AgentSettings(maxIterations = 20, toolLoopMigrated = false)
        val migrated = migrateToolLoopDefaults(legacy)
        assertEquals("旧上限翻转为无上限哨兵 0", 0, migrated.maxIterations)
        assertTrue("迁移标记落位（不再重复执行）", migrated.toolLoopMigrated)
    }

    @Test
    fun `migrated flag short circuits repeat runs`() {
        // 迁移后用户手动设回 30：标记已置位，不再被翻为 0
        val settled = AgentSettings(maxIterations = 30, toolLoopMigrated = true)
        val rerun = migrateToolLoopDefaults(settled)
        assertEquals("用户显式上限保留", 30, rerun.maxIterations)
        assertTrue(rerun.toolLoopMigrated)
    }

    @Test
    fun `fresh install defaults are unlimited without extra churn`() {
        // 新装机：字段默认即无上限 —— 迁移只落标记（幂等语义）
        val fresh = AgentSettings()
        val migrated = migrateToolLoopDefaults(fresh)
        assertEquals(0, migrated.maxIterations)
        assertTrue(migrated.toolLoopMigrated)
        // 其余字段不受迁移波及（回归护栏：迁移只动工具循环两个字段）
        assertEquals(fresh.defaultMode, migrated.defaultMode)
        assertEquals(fresh.toolCallParallelism, migrated.toolCallParallelism)
        assertEquals(fresh.backgroundImageUri, migrated.backgroundImageUri)
        assertEquals(fresh.rateLimitFriendly, migrated.rateLimitFriendly)
    }
}
