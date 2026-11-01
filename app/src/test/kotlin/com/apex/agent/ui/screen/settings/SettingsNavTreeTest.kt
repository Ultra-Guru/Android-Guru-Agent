package com.apex.agent.ui.screen.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SettingsNavTree] 单元测试 —— 导航树结构契约（重排后的护栏）。
 *
 * 覆盖：全树 route 唯一性 / route 两段式命名 / v3.1 重排归属
 * （集成与扩展三叶 + 工作台瘦身）/ categoryOfLeaf·leafOf 查找行为。
 */
class SettingsNavTreeTest {

    @Test
    fun `全树 route 唯一且两段式命名`() {
        val routes = SettingsNavTree.flatMap { it.children.map { leaf -> leaf.route } }
        assertEquals("叶子 route 无重复", routes.size, routes.toSet().size)
        assertEquals("分类 route 无重复",
            SettingsNavTree.map { it.route }, SettingsNavTree.map { it.route }.toSet().toList())
        routes.forEach { route ->
            assertEquals("route 必须为「分类/叶子」两段式：$route", 2, route.split('/').size)
        }
    }

    @Test
    fun `分类数量与顺序契约`() {
        assertEquals(
            listOf("model", "workspace", "integrations", "terminal", "interface", "data"),
            SettingsNavTree.map { it.route }
        )
    }

    @Test
    fun `v3n1重排_三叶归属集成与扩展分类`() {
        val integrations = SettingsNavTree.first { it.route == "integrations" }
        assertEquals(
            listOf("integrations/github", "integrations/search", "integrations/memory"),
            integrations.children.map { it.route }
        )
    }

    @Test
    fun `v3n1重排_工作台不再含集成类叶子`() {
        val workspace = SettingsNavTree.first { it.route == "workspace" }.children.map { it.route }
        assertEquals(
            "工作台 = 纯会话行为六叶（编程工位/子代理/权限/规则/钩子/目标模式）",
            listOf(
                "workspace/coding", "workspace/subagent", "workspace/permissions",
                "workspace/rules", "workspace/hooks", "workspace/goal"
            ),
            workspace
        )
    }

    @Test
    fun `categoryOfLeaf与leafOf对新route行为正确`() {
        val category = categoryOfLeaf("integrations/search")
        assertNotNull(category)
        assertEquals("integrations", category?.route)

        val leaf = leafOf("integrations/memory")
        assertNotNull(leaf)
        assertEquals("integrations/memory", leaf?.route)

        assertNull(leafOf("workspace/github"))
        assertNull(leafOf("不存在/叶子"))
        assertNull(categoryOfLeaf("也不存在"))
    }

    @Test
    fun `首叶与查找工具链一致`() {
        assertEquals(
            "FirstSettingsLeafRoute 指向第一分类首叶",
            SettingsNavTree.first().children.first().route,
            FirstSettingsLeafRoute
        )
        assertTrue(SettingsNavTree.all { it.children.isNotEmpty() })
    }
}
