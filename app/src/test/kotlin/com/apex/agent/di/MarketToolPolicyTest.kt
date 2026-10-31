package com.apex.agent.di

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 市场检索工具超时错配修复回归（用户实测 app-20261017 日志：
 * market_search 报 "Error: timeout: tool call exceeded 30000ms budget"）。
 *
 * 根因：MarketSearch 只声明 readOnly() 注解 → DefaultToolRunPolicyResolver
 * 推断 quickRead()（30s）——而工具自身是网络目录拉取（注入的 OkHttp
 * connect 15s + read 30s，kind=all 顺序拉技能/双 MCP 目录双源，最坏
 * 2×45s=90s），执行器 30s 预算先于工具自身网络超时窗口杀掉调用。
 *
 * 本测试锁住三件事（与 TerminalToolPolicyTest 同款防漂移立场）：
 *  1. 市场检索工具**必须**在 MARKET_TOOL_RUN_POLICIES 显式覆盖表里
 *     （新增目录源/网络检索工具忘了配策略会被抓住）；
 *  2. 执行器预算 ≥ 工具自身最坏网络预算（OkHttp 超时口径）+ 余量；
 *  3. 市场检索不盲重试（引擎侧韧性守卫已带瞬时失败退避，执行器层
 *     再重试只会叠加等待窗口）。
 */
class MarketToolPolicyTest {

    /** 工具自身最坏内部预算（与注入的 OkHttp 客户端口径对齐：connect 15s + read 30s）。 */
    private val internalBudgetsMs = mapOf(
        // kind=all 顺序拉双源（skills + mcp servers），每源最坏 15+30s
        "MarketSearch" to 90_000L,
        // 单次 GitHub API 检索（最坏 45s）+ 本地模板匹配
        "SkillSearch" to 45_000L
    )

    @Test
    fun `market discovery tools are covered by explicit policies`() {
        for (id in internalBudgetsMs.keys) {
            assertNotNull(
                "工具 $id 缺少执行器超时策略覆盖（将回落 quickRead 30s 预算被截杀）",
                MARKET_TOOL_RUN_POLICIES[id]
            )
        }
    }

    @Test
    fun `executor budget covers worst-case network budget with margin`() {
        for ((id, internalMs) in internalBudgetsMs) {
            val policy = MARKET_TOOL_RUN_POLICIES[id]
            assertNotNull("工具 $id 缺少策略", policy)
            assertTrue(
                "工具 $id 执行器预算 ${policy!!.timeoutMs}ms < 最坏网络预算 $internalMs" +
                    "ms —— 执行器会先于工具自身网络超时杀掉调用",
                policy.timeoutMs >= internalMs
            )
        }
    }

    @Test
    fun `market discovery tools never blind-retry`() {
        for ((id, policy) in MARKET_TOOL_RUN_POLICIES) {
            assertEquals(
                "工具 $id 不应盲重试（引擎侧韧性守卫已带退避重试）",
                0,
                policy.maxRetries
            )
        }
    }

    @Test
    fun `resolver applies market overrides over quickRead inference`() {
        // 守护测试：readOnly 注解的默认推断确实是 quickRead 30s —— 覆盖表
        // 存在的意义（MarketSearch 无覆盖时 30s 必杀，正是用户实测的报错）。
        val noOverride = com.apex.agent.core.tools.DefaultToolRunPolicyResolver()
        val tool = FakeMarketTool("MarketSearch", com.apex.agent.core.tools.ToolAnnotations.readOnly())
        assertEquals(30_000L, noOverride.resolve(tool).timeoutMs)

        val resolver = com.apex.agent.core.tools.DefaultToolRunPolicyResolver(
            overrides = MARKET_TOOL_RUN_POLICIES
        )
        assertEquals(
            MARKET_TOOL_RUN_POLICIES["MarketSearch"]!!.timeoutMs,
            resolver.resolve(tool).timeoutMs
        )
    }

    /** 注解完全可控的假工具（readOnly 形态 = MarketSearchTool.buildMetadata 同款）。 */
    private class FakeMarketTool(
        override val id: String,
        private val annotations: com.apex.agent.core.tools.ToolAnnotations
    ) : com.apex.agent.core.tools.AgentTool {
        override val name: String = id
        override val description: String = "fake market tool"
        override val parametersSchema: String = "{}"
        override val metadata: com.apex.agent.core.tools.ToolMetadata =
            com.apex.agent.core.tools.ToolMetadata(
                id, com.apex.agent.core.tools.ToolCategory.UTILITY,
                com.apex.agent.core.tools.ToolRisk.LOW, emptyList(), annotations
            )

        override suspend fun execute(arguments: String): String = "{}"
    }
}
