package com.apex.agent.ui.screen.code

import com.apex.agent.core.tools.mcp.McpTransport
import com.apex.agent.marketplace.SearchMcpPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WebSearchChannelLogic] / [SearchMcpPreset] 单元测试 ——
 * 网络搜索三通道单选互斥语义与三台本地搜索 MCP 预设配置形状。
 */
class WebSearchChannelTest {

    @Test
    fun `未选通道时点选即启用`() {
        val plan = WebSearchChannelLogic.planToggle(null, WebSearchChannel.BUILTIN)
        assertEquals(WebSearchChannel.BUILTIN, plan.activate)
        assertNull(plan.deactivate)
    }

    @Test
    fun `切换通道时停旧开新`() {
        val plan = WebSearchChannelLogic.planToggle(
            WebSearchChannel.BUILTIN, WebSearchChannel.MCP_SEARCHLIGHT
        )
        assertEquals(WebSearchChannel.MCP_SEARCHLIGHT, plan.activate)
        assertEquals(WebSearchChannel.BUILTIN, plan.deactivate)
    }

    @Test
    fun `再点当前通道即关闭`() {
        val plan = WebSearchChannelLogic.planToggle(
            WebSearchChannel.MCP_AGENT_SEARCH, WebSearchChannel.MCP_AGENT_SEARCH
        )
        assertNull("再点 = 关闭（activate=null）", plan.activate)
        assertEquals(WebSearchChannel.MCP_AGENT_SEARCH, plan.deactivate)
    }

    @Test
    fun `MCP 组内切换同样互斥`() {
        val plan = WebSearchChannelLogic.planToggle(
            WebSearchChannel.MCP_FREE_SEARCH, WebSearchChannel.MCP_AGENT_SEARCH
        )
        assertEquals(WebSearchChannel.MCP_AGENT_SEARCH, plan.activate)
        assertEquals(WebSearchChannel.MCP_FREE_SEARCH, plan.deactivate)
    }

    @Test
    fun `三台预设与通道一一对应`() {
        SearchMcpPreset.entries.forEach { preset ->
            assertEquals(preset, WebSearchChannel.byPreset(preset).preset)
        }
    }

    @Test
    fun `预设 stdio 沙箱配置形状`() {
        val fs = SearchMcpPreset.FREE_SEARCH.toConfig(enabled = true)
        assertEquals("free-search-mcp", fs.name)
        assertEquals(McpTransport.STDIO, fs.transport)
        assertTrue("三台都必须 runInSandbox（rootfs 内解析命令）", fs.runInSandbox)
        assertEquals("all", fs.scope)
        assertEquals("free-search-mcp", fs.command)
        assertTrue(fs.args.isEmpty())

        val sl = SearchMcpPreset.SEARCHLIGHT.toConfig(enabled = false)
        assertFalse(sl.enabled)
        assertEquals("python3", sl.command)
        assertEquals(listOf("-m", "searchlight"), sl.args)

        val asp = SearchMcpPreset.AGENT_SEARCH.toConfig(enabled = true)
        assertEquals("npx", asp.command)
        assertEquals(listOf("-y", "agent-search-mcp"), asp.args)
    }

    @Test
    fun `安装与回滚命令按 kind 区分`() {
        assertTrue(
            SearchMcpPreset.FREE_SEARCH.installCommand().joinToString(" ")
                .startsWith("pip install --break-system-packages")
        )
        assertTrue(
            SearchMcpPreset.SEARCHLIGHT.uninstallCommand().joinToString(" ")
                .startsWith("pip uninstall --break-system-packages -y")
        )
        assertEquals(
            "npm install -g agent-search-mcp",
            SearchMcpPreset.AGENT_SEARCH.installCommand().joinToString(" ")
        )
        assertEquals(
            "npm uninstall -g agent-search-mcp",
            SearchMcpPreset.AGENT_SEARCH.uninstallCommand().joinToString(" ")
        )
    }
}
