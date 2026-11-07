package com.apex.agent.core.engine

import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.DefaultToolRegistry
import com.apex.agent.core.tools.ToolActivationStore
import com.apex.agent.core.tools.ToolMetadata
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.1.0 计划层角色过滤测试（评审 B3/B8 并发测试 A 的计划面）：
 * 默认严格会话（grantedRoles 空）计划里看不到 browser_*；授予后恢复；
 * forced/allowed 收窄路径同样被角色硬边界拦下。
 */
class EngineToolPlannerRoleTest {

    private class FakeTool(override val id: String) : AgentTool {
        override val name = id
        override val description = "test"
        override val parametersSchema = "{}"
        override val metadata: ToolMetadata = ToolMetadata.infer(id)
        override suspend fun execute(arguments: String): String = ""
    }

    private fun registryWith(ids: List<String>): DefaultToolRegistry {
        val r = DefaultToolRegistry()
        ids.forEach { r.register(FakeTool(it)) }
        return r
    }

    @Test
    fun `默认严格会话计划不含browser工具_授予后恢复`() {
        val registry = registryWith(listOf("WebFetch", "BrowserNavigate", "BrowserExecuteJs", "ShellExecute"))
        val activation = ToolActivationStore()

        // 严格默认（AgentConfig.grantedRoles 默认空集）
        val strictPlan = EngineToolPlanner.buildToolPlan(
            config = AgentConfig.STANDARD.copy(exposeAllTools = true),
            registry = registry,
            activation = activation,
            degradationLevel = 0,
        )
        val strictIds = strictPlan.providerNameToId.values.toSet()
        assertTrue("WebFetch 应在计划内", "WebFetch" in strictIds)
        assertFalse("严格会话不得看到 BrowserNavigate", "BrowserNavigate" in strictIds)
        assertFalse("严格会话不得看到 BrowserExecuteJs", "BrowserExecuteJs" in strictIds)

        // 授予 browser-automation：常规 browser 工具恢复可见
        val grantedPlan = EngineToolPlanner.buildToolPlan(
            config = AgentConfig.STANDARD.copy(
                grantedRoles = setOf("browser-automation"),
                exposeAllTools = true,
            ),
            registry = registry,
            activation = activation,
            degradationLevel = 0,
        )
        val grantedIds = grantedPlan.providerNameToId.values.toSet()
        assertTrue("BrowserNavigate 授予后可见", "BrowserNavigate" in grantedIds)
        // 敏感四件套仍不可见（缺 browser-sensitive）
        assertFalse("敏感四件套需敏感 tier", "BrowserExecuteJs" in grantedIds)
    }

    @Test
    fun `allowed收窄路径同样被角色边界拦截`() {
        val registry = registryWith(listOf("BrowserNavigate", "ShellExecute"))
        val plan = EngineToolPlanner.buildToolPlan(
            config = AgentConfig.STANDARD.copy(
                allowedToolIds = setOf("BrowserNavigate", "ShellExecute"),
                // 子代理未授予 browser 角色（默认不继承）
                grantedRoles = emptySet(),
            ),
            registry = registry,
            activation = ToolActivationStore(),
            degradationLevel = 0,
        )
        val ids = plan.providerNameToId.values.toSet()
        assertTrue("ShellExecute 保留", "ShellExecute" in ids)
        assertFalse("子代理收窄路径也拦 browser_*", "BrowserNavigate" in ids)
    }
}
