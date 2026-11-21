package com.apex.agent.github.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GithubMcpToolsets] 工具集 URL 编解码测试。
 *
 * 覆盖：全选=无参数（服务器默认语义）、部分选择的参数形态（官方声明序）、
 * roundtrip（encode → decode 恒等）、空集回退、未知 key 防御过滤、
 * 非官方 URL/无参数/坏参数的解析回退。
 *
 * 纯 JVM（JUnit4），与 app 模块既有 MCP 单测同构。
 */
class GithubMcpToolsetsTest {

    private val base = OfficialGithubMcp.REMOTE_URL

    @Test
    fun `full selection maps to bare endpoint`() {
        assertEquals(base, GithubMcpToolsets.urlWithToolsets(GithubMcpToolsets.DEFAULT_SELECTED))
        // 全集的显式集合形态也折叠为无参数（同语义同形态）。
        assertEquals(
            base,
            GithubMcpToolsets.urlWithToolsets(GithubMcpToolsets.ALL.map { it.key }.toSet())
        )
    }

    @Test
    fun `partial selection maps to explicit toolsets param`() {
        val url = GithubMcpToolsets.urlWithToolsets(setOf("issues", "pull_requests"))
        assertEquals("$base?toolsets=issues,pull_requests", url)
    }

    @Test
    fun `partial selection preserves catalog order regardless of input order`() {
        val url = GithubMcpToolsets.urlWithToolsets(setOf("pull_requests", "repos"))
        assertEquals("$base?toolsets=repos,pull_requests", url)
    }

    @Test
    fun `empty selection falls back to bare endpoint`() {
        assertEquals(base, GithubMcpToolsets.urlWithToolsets(emptySet()))
    }

    @Test
    fun `unknown keys are sanitized away`() {
        // 未知 key 过滤后只剩已知 key；全未知 → 空集回退裸端点。
        assertEquals(
            "$base?toolsets=issues",
            GithubMcpToolsets.urlWithToolsets(setOf("issues", "future_toolset"))
        )
        assertEquals(base, GithubMcpToolsets.urlWithToolsets(setOf("no_such", "ghost")))
    }

    @Test
    fun `parse roundtrip is identity for partial sets`() {
        val selected = setOf("repos", "actions", "gists")
        val url = GithubMcpToolsets.urlWithToolsets(selected)
        assertEquals(selected, GithubMcpToolsets.parseSelected(url))
    }

    @Test
    fun `parse bare or missing param yields full set`() {
        assertEquals(GithubMcpToolsets.DEFAULT_SELECTED, GithubMcpToolsets.parseSelected(base))
        assertEquals(
            GithubMcpToolsets.DEFAULT_SELECTED,
            GithubMcpToolsets.parseSelected("$base?other=x")
        )
    }

    @Test
    fun `parse filters unknown values defensively`() {
        val parsed = GithubMcpToolsets.parseSelected("$base?toolsets=issues,future_toolset")
        assertEquals(setOf("issues"), parsed)
    }

    @Test
    fun `parse empty or broken toolsets param falls back to full set`() {
        // 「读不懂的配置 = 全开」口径（见 parseSelected KDoc）。
        assertEquals(
            GithubMcpToolsets.DEFAULT_SELECTED,
            GithubMcpToolsets.parseSelected("$base?toolsets=")
        )
        assertEquals(
            GithubMcpToolsets.DEFAULT_SELECTED,
            GithubMcpToolsets.parseSelected("$base?toolsets=,,")
        )
    }

    @Test
    fun `parse non official url yields full set`() {
        assertEquals(
            GithubMcpToolsets.DEFAULT_SELECTED,
            GithubMcpToolsets.parseSelected("https://example.com/mcp?toolsets=issues")
        )
    }

    @Test
    fun `catalog has all ten official toolsets with labels`() {
        assertEquals(10, GithubMcpToolsets.ALL.size)
        GithubMcpToolsets.ALL.forEach { ts ->
            assertTrue("label blank for ${ts.key}", ts.zhLabel.isNotBlank())
            assertTrue("summary blank for ${ts.key}", ts.summary.isNotBlank())
        }
        assertTrue(GithubMcpToolsets.byKey("repos") != null)
        assertTrue(GithubMcpToolsets.byKey("no-such") == null)
    }
}
