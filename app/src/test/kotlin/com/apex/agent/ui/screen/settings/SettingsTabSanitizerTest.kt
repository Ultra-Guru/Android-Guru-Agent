package com.apex.agent.ui.screen.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BUG-08 恢复净化语义（Spec 02 · B0）：OpenTabsSaver.restore 逐项经导航树
 * 校验 —— 未知/旧 route 只丢弃该项，绝不因一个失效标签清空全部标签。
 *
 * 验收样例（Spec 02 §2.2）：注入 ["model/setup","old/gone","data/backup"]
 * 恢复后剩余 2 个有效标签。
 */
class SettingsTabSanitizerTest {

    /** 与 SettingsScreen 的 validTabRoutes 同源（导航树全部叶子 route）。 */
    private val validRoutes: Set<String> =
        SettingsNavTree.flatMap { it.children }.map { it.route }.toSet()

    @Test
    fun `未知route只丢弃自身_其余标签保留`() {
        val restored = sanitizeRestoredTabs(
            listOf("model/setup", "old/gone", "data/backup").joinToString("\u0000"),
            validRoutes
        )
        assertEquals(listOf("model/setup", "data/backup"), restored)
    }

    @Test
    fun `全部未知时返回空列表_不清空语义由调用方保持`() {
        assertTrue(sanitizeRestoredTabs("a/b\u0000c/d", validRoutes).isEmpty())
    }

    @Test
    fun `空段与空白段被过滤`() {
        val restored = sanitizeRestoredTabs("\u0000\u0000model/tools\u0000", validRoutes)
        assertEquals(listOf("model/tools"), restored)
    }

    @Test
    fun `全部23个旧route均被导航树收录_恢复不丢弃`() {
        val allRoutes = SettingsNavTree.flatMap { it.children }.map { it.route }
        assertEquals(allRoutes, sanitizeRestoredTabs(allRoutes.joinToString("\u0000"), validRoutes))
    }

    @Test
    fun `route总数与导航树叶子数一致`() {
        assertEquals(SettingsNavTree.sumOf { it.children.size }, validRoutes.size)
    }
}
