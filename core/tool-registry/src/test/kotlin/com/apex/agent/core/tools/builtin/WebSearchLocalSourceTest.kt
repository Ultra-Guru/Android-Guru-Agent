package com.apex.agent.core.tools.builtin

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WebSearchTool] 本地搜索源插链单测（SearXNG Local Search Spec v1.0 §10）。
 *
 * 覆盖：本地命中 → 格式化输出（含 [searxng:engine] 来源标记）/ 本地 null →
 * 远程链路行为保持（构造兼容）/ 本地空结果 → 降级 / 异常 → 降级不炸链。
 */
class WebSearchLocalSourceTest {

    private val client = WebFetchTool.defaultClient()

    @Test
    fun `local hit returns formatted results with searxng marker`() = runTest {
        val tool = WebSearchTool(
            client,
            localSource = { _, limit ->
                listOf(
                    LocalSearchResult(
                        title = "Kotlin coroutines guide",
                        url = "https://example.com/kotlin",
                        snippet = "A comprehensive guide",
                        engine = "google"
                    ),
                    LocalSearchResult(
                        title = "Second hit",
                        url = "https://example.com/2",
                        snippet = "Another snippet",
                        engine = null
                    )
                ).take(limit)
            }
        )
        val out = tool.execute("""{"query": "kotlin coroutines", "max_results": 5}""")
        // 命中本地：输出为格式化结果（无 Error 前缀），含来源标记
        assertFalse(out.startsWith("Error:"))
        assertTrue(out.contains("https://example.com/kotlin"))
        assertTrue(out.contains("[searxng:google]"))
        assertTrue(out.contains("Kotlin coroutines guide"))
    }

    @Test
    fun `local empty results degrade silently to remote chain`() = runTest {
        val tool = WebSearchTool(
            client,
            localSource = { _, _ -> emptyList() }
        )
        // 远程链在无网络的测试环境会失败 —— 但必须**优雅失败**（Error 前缀文本，
        // 不抛异常、不崩溃），且本地空结果不贡献 Error。
        val out = tool.execute("""{"query": "test query"}""")
        assertTrue(out.isNotEmpty())
        if (out.startsWith("Error:")) {
            // 远程链失败时的失败清单不应包含本地空结果两次
            assertFalse(out.contains("searxng: parsed 0 results"))
        }
    }

    @Test
    fun `local exception degrades to remote chain without crashing`() = runTest {
        val tool = WebSearchTool(
            client,
            localSource = { _, _ -> throw IllegalStateException("connection refused") }
        )
        val out = tool.execute("""{"query": "test"}""")
        assertTrue(out.isNotEmpty())
    }

    @Test
    fun `null local source keeps legacy constructor compatible`() = runTest {
        // 旧调用方（单测/MCP 默认构造）不传 localSource —— 行为零变化
        val tool = WebSearchTool(client)
        val out = tool.execute("""{"query": "compat"}""")
        assertTrue(out.isNotEmpty())
        assertTrue(tool.parametersSchema.isNotEmpty())
    }
}
