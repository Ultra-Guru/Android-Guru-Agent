package com.apex.agent.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.1.0 浏览器角色策略 + RoleGate 意图测试（评审 B1/B3/B8）：
 * 两级角色分派、infer fallback 与显式覆盖、RoleGate 允许/拒绝与文案可执行性。
 */
class BrowserRolePolicyTest {

    @Test
    fun `常规browser工具要求browser-automation`() {
        assertEquals(
            setOf(BrowserRolePolicy.ROLE_BROWSER_AUTOMATION),
            BrowserRolePolicy.requiredRolesFor("BrowserNavigate"),
        )
        assertEquals(
            setOf(BrowserRolePolicy.ROLE_BROWSER_AUTOMATION),
            BrowserRolePolicy.requiredRolesFor("BrowserSnapshot"),
        )
        // 非 Browser 工具零漂移（111 存量工具不受影响）
        assertTrue(BrowserRolePolicy.requiredRolesFor("ShellExecute").isEmpty())
        assertTrue(BrowserRolePolicy.requiredRolesFor("WebFetch").isEmpty())
    }

    @Test
    fun `敏感四件套要求两级角色`() {
        for (id in listOf("BrowserExecuteJs", "BrowserGetCookies", "BrowserFileUpload", "BrowserClearData")) {
            assertEquals(
                "敏感工具 $id 应同时要求两级角色",
                setOf(BrowserRolePolicy.ROLE_BROWSER_AUTOMATION, BrowserRolePolicy.ROLE_BROWSER_SENSITIVE),
                BrowserRolePolicy.requiredRolesFor(id),
            )
        }
    }

    @Test
    fun `显式requiredRoles恒覆盖infer`() {
        val explicit = ToolMetadata.meta("BrowserNavigate") {
            category(ToolCategory.BROWSER)
            requiredRoles() // 显式空 = 不限制（覆盖 infer 的 browser-automation）
        }
        assertTrue("显式空集必须生效（infer 仅 fallback）", explicit.requiredRoles.isEmpty())
        val inferred = ToolMetadata.infer("BrowserNavigate")
        assertFalse(inferred.requiredRoles.isEmpty())
    }

    @Test
    fun `来源推断_mcp与plugin与skill`() {
        assertEquals(ToolSource.MCP, BrowserRolePolicy.sourceFor("mcp__search__web_search"))
        assertEquals(ToolSource.PLUGIN, BrowserRolePolicy.sourceFor("PluginWorkflowRun"))
        assertEquals(ToolSource.SKILL, BrowserRolePolicy.sourceFor("SkillInstall"))
        assertEquals(ToolSource.CORE, BrowserRolePolicy.sourceFor("BrowserNavigate"))
    }

    @Test
    fun `默认授予_严格开=空集_关=仅automation`() {
        assertTrue(BrowserRolePolicy.defaultGrants(restrictionEnabled = true).isEmpty())
        assertEquals(
            setOf(BrowserRolePolicy.ROLE_BROWSER_AUTOMATION),
            BrowserRolePolicy.defaultGrants(restrictionEnabled = false),
        )
        // 关闭限制也不授敏感 tier（独立用户授权，评审最小权限）
        assertFalse(BrowserRolePolicy.defaultGrants(false).contains(BrowserRolePolicy.ROLE_BROWSER_SENSITIVE))
    }

    @Test
    fun `RoleGate_常规工具零开销放行_缺失角色拒绝且文案可执行`() {
        val gate = RoleGate { setOf(BrowserRolePolicy.ROLE_BROWSER_AUTOMATION) }
        val tool = object : AgentTool {
            override val id = "BrowserExecuteJs"
            override val name = "BrowserExecuteJs"
            override val description = ""
            override val parametersSchema = "{}"
            override val metadata = ToolMetadata.infer(id)
            override suspend fun execute(arguments: String): String = ""
        }
        // 敏感四件套缺 browser-sensitive → 拒绝
        val denied = kotlinx.coroutines.runBlocking { gate.check(tool, "{}") }
        assertTrue(denied is GateDecision.Deny)
        assertTrue((denied as GateDecision.Deny).reason.contains("browser-sensitive"))
        // 授予全部 → 放行
        val gate2 = RoleGate {
            setOf(BrowserRolePolicy.ROLE_BROWSER_AUTOMATION, BrowserRolePolicy.ROLE_BROWSER_SENSITIVE)
        }
        val allowed = kotlinx.coroutines.runBlocking { gate2.check(tool, "{}") }
        assertTrue(allowed is GateDecision.Allow)
        // 非 Browser 工具不受影响
        val plain = object : AgentTool {
            override val id = "WebFetch"
            override val name = "WebFetch"
            override val description = ""
            override val parametersSchema = "{}"
            override val metadata = ToolMetadata.infer(id)
            override suspend fun execute(arguments: String): String = ""
        }
        assertTrue(kotlinx.coroutines.runBlocking { gate.check(plain, "{}") } is GateDecision.Allow)
    }
}
