package com.apex.agent.ui.screen.market

import com.apex.agent.core.tools.marketplace.HubSource
import com.apex.agent.core.tools.mcp.McpTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Hub 生态 —— MCP 目录条目 → 注册表配置 的纯函数单测。
 *
 * [MarketHubController.toServerConfig] 是市场「官方 MCP 仓库 → 安装」的
 * 唯一映射点，锁定三个不变量：
 * 1. **安装 ≠ 启动**：无论目录条目怎么写，落盘 enabled 恒 false
 *    （启动走市场「启动」按钮的真实连接管线——产品要求）；
 * 2. transport 映射：STDIO/HTTP/SSE 三态、未知值兜底 HTTP；
 * 3. 命令/参数/环境变量/沙箱开关/作用域逐字段透传（防错位）。
 */
class MarketHubMcpConfigTest {

    @Test
    fun `sandbox stdio entry maps to disabled sandbox config with full passthrough`() {
        val entry = HubSource.HubMcpEntry(
            name = "fs-sandbox",
            transport = "STDIO",
            command = "npx",
            args = listOf("-y", "@modelcontextprotocol/server-filesystem", "/workspace"),
            env = mapOf("FOO" to "bar"),
            runInSandbox = true,
            enabled = true, // 目录即使写了 true 也要被压成 false（安装 ≠ 启动）
            scope = "coding"
        )

        val config = MarketHubController.toServerConfig(entry)

        assertEquals("fs-sandbox", config.name)
        assertEquals(McpTransport.STDIO, config.transport)
        assertEquals("npx", config.command)
        assertEquals(entry.args, config.args)
        assertEquals(mapOf("FOO" to "bar"), config.env)
        assertEquals(true, config.runInSandbox)
        assertEquals("coding", config.scope)
        // 核心门控：安装 ≠ 启动
        assertFalse(config.enabled)
    }

    @Test
    fun `remote http entry maps transport and url`() {
        val entry = HubSource.HubMcpEntry(
            name = "deepwiki",
            transport = "HTTP",
            url = "https://mcp.deepwiki.com/mcp",
            scope = "all"
        )

        val config = MarketHubController.toServerConfig(entry)

        assertEquals(McpTransport.HTTP, config.transport)
        assertEquals("https://mcp.deepwiki.com/mcp", config.url)
        assertFalse(config.runInSandbox)
        assertEquals("all", config.scope)
        assertFalse(config.enabled)
    }

    @Test
    fun `sse transport maps explicitly and unknown transport falls back to http`() {
        val sse = MarketHubController.toServerConfig(
            HubSource.HubMcpEntry(name = "s", transport = "sse", url = "https://x/sse")
        )
        assertEquals(McpTransport.SSE, sse.transport)

        val unknown = MarketHubController.toServerConfig(
            HubSource.HubMcpEntry(name = "u", transport = "websocket", url = "https://x/ws")
        )
        assertEquals(McpTransport.HTTP, unknown.transport)
    }
}
