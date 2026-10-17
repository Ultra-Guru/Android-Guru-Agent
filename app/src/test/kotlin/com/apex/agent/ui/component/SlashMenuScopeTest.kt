package com.apex.agent.ui.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [SlashMenuData.forScope] per-scope 双重过滤单元测试。
 *
 * 用户需求：「在市场中开启技能后，coding 模式与 agent 模式输入框 / 菜单里
 * 显示的技能是同步的（应隔离）」—— forScope 现在做两道过滤：
 * 1. 工位作用域（manifest scope：all 两屏都保留）；
 * 2. per-scope 启停（enabledScopes：只在 Coding 市场开启的技能不进 Agent 屏）。
 *
 * 纯 JVM 数据类测试（图标用 material-icons 常量对象，无 Android 框架依赖）。
 */
class SlashMenuScopeTest {

    private fun item(
        label: String,
        scope: String = "all",
        enabledScopes: Set<String> = setOf("agent", "coding")
    ) = SlashMenuItem(label = label, command = "/skill:$label ", scope = scope, enabledScopes = enabledScopes)

    private fun category(vararg items: SlashMenuItem) =
        SlashMenuCategory(id = "skills", title = "Skills", icon = Icons.Default.Extension, items = items.toList())

    @Test
    fun `per-scope disabled skill is filtered out of the other workstation menu`() {
        // scope=all 技能，但只在 Coding 市场开启（Agent 分级里关掉了）
        val menu = SlashMenuData(
            categories = listOf(category(item("shared", enabledScopes = setOf("coding"))))
        )

        // 过滤后空类目会被整段剔除，用 flatMap 聚合断言（不依赖类目存留）
        assertEquals(
            "Agent 屏菜单不含该技能",
            emptyList<String>(),
            menu.forScope("agent").categories.flatMap { it.items }.map { it.label }
        )
        assertEquals(
            "Coding 屏菜单保留该技能",
            listOf("shared"),
            menu.forScope("coding").categories.flatMap { it.items }.map { it.label }
        )
    }

    @Test
    fun `both-scope enabled item stays in both menus`() {
        val menu = SlashMenuData(
            categories = listOf(category(item("dual")))
        )
        assertEquals(
            listOf("dual"),
            menu.forScope("agent").categories.flatMap { it.items }.map { it.label }
        )
        assertEquals(
            listOf("dual"),
            menu.forScope("coding").categories.flatMap { it.items }.map { it.label }
        )
    }

    @Test
    fun `manifest scope still gates workstation visibility`() {
        // manifest 声明 coding 的技能：即使 enabledScopes 双开也只出现在 Coding 屏
        val menu = SlashMenuData(
            categories = listOf(category(item("coding-only", scope = "coding")))
        )
        assertEquals(
            emptyList<String>(),
            menu.forScope("agent").categories.flatMap { it.items }.map { it.label }
        )
        assertEquals(
            listOf("coding-only"),
            menu.forScope("coding").categories.flatMap { it.items }.map { it.label }
        )
    }

    @Test
    fun `empty category after scope filter is dropped`() {
        val menu = SlashMenuData(
            categories = listOf(
                category(item("x", enabledScopes = setOf("coding"))),
                SlashMenuCategory(
                    id = "mcp", title = "MCP", icon = Icons.Default.Extension, items = emptyList(), hint = "hint"
                )
            )
        )
        val agent = menu.forScope("agent")
        // skills 类目被过滤空且无 hint → 整段剔除；hint 类目保留
        assertEquals(listOf("mcp"), agent.categories.map { it.id })
    }
}
