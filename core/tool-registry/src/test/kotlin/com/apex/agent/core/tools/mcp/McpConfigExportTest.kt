package com.apex.agent.core.tools.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * McpConfigExport —— 桌面端 JSON 导出器的单测。
 *
 * 锁定四组不变量：
 * 1. 字段映射：STDIO/HTTP/SSE 三形态的输出形状与 [McpConfigImport] 的
 *    判定规则对偶（含空 args/env/headers 省略）；
 * 2. BUILTIN 不可导出（exportServer → null；exportServers 静默跳过）；
 * 3. 往返无损：export → Import.parse 逐字段还原（名字/传输/命令/args/env/url）；
 * 4. 敏感值语义：env/headers 原样透传（含 ${GITHUB_TOKEN} 占位符）。
 */
class McpConfigExportTest {

    @Test
    fun `stdio config exports command args env`() {
        val config = McpServerConfig(
            name = "SequentialThinking",
            transport = McpTransport.STDIO,
            command = "npx",
            args = listOf("-y", "@modelcontextprotocol/server-sequential-thinking"),
            env = mapOf("FOO" to "bar"),
            runInSandbox = true
        )
        val text = McpConfigExport.exportServer(config)
        assertNotNull(text)
        // runInSandbox 是本 App 私有字段 —— 导出形状里不得出现
        assertTrue(text!!.contains("\"command\""))
        assertTrue(!text.contains("runInSandbox"))
        // 往返：Import 解析回同构配置
        val round = McpConfigImport.parse(text).configs
        assertEquals(1, round.size)
        assertEquals("SequentialThinking", round[0].name)
        assertEquals(McpTransport.STDIO, round[0].transport)
        assertEquals("npx", round[0].command)
        assertEquals(config.args, round[0].args)
        assertEquals(config.env, round[0].env)
    }

    @Test
    fun `http config exports streamable_http type and headers`() {
        val config = McpServerConfig(
            name = "deepwiki",
            transport = McpTransport.HTTP,
            url = "https://mcp.deepwiki.com/mcp",
            headers = mapOf("Authorization" to "Bearer xxx")
        )
        val text = McpConfigExport.exportServer(config)!!
        val round = McpConfigImport.parse(text).configs
        assertEquals(1, round.size)
        assertEquals(McpTransport.HTTP, round[0].transport)
        assertEquals("https://mcp.deepwiki.com/mcp", round[0].url)
        assertEquals(config.headers, round[0].headers)
    }

    @Test
    fun `sse config exports sse type`() {
        val config = McpServerConfig(
            name = "legacy",
            transport = McpTransport.SSE,
            url = "https://example.com/sse"
        )
        val round = McpConfigImport.parse(McpConfigExport.exportServer(config)!!).configs
        assertEquals(McpTransport.SSE, round[0].transport)
        assertEquals("https://example.com/sse", round[0].url)
    }

    @Test
    fun `builtin config is not exportable`() {
        val builtin = McpServerConfig(
            name = "thinking",
            transport = McpTransport.BUILTIN
        )
        assertNull(McpConfigExport.exportServer(builtin))
        // exportServers 跳过 BUILTIN，其余照常导出
        val stdio = McpServerConfig(
            name = "fetch",
            transport = McpTransport.STDIO,
            command = "uvx",
            args = listOf("mcp-server-fetch")
        )
        val text = McpConfigExport.exportServers(listOf(builtin, stdio))
        val round = McpConfigImport.parse(text).configs
        assertEquals(listOf("fetch"), round.map { it.name })
    }

    @Test
    fun `empty args and env are omitted`() {
        val config = McpServerConfig(
            name = "bare",
            transport = McpTransport.STDIO,
            command = "uvx"
        )
        val text = McpConfigExport.exportServer(config)!!
        assertTrue(!text.contains("\"args\""))
        assertTrue(!text.contains("\"env\""))
        // 往返仍然成立（Import 对缺失字段落默认）
        val round = McpConfigImport.parse(text).configs
        assertEquals(emptyList<String>(), round[0].args)
        assertEquals(emptyMap<String, String>(), round[0].env)
    }

    @Test
    fun `placeholder token exports verbatim`() {
        val config = McpServerConfig(
            name = "github-official",
            transport = McpTransport.HTTP,
            url = "https://api.githubcopilot.com/mcp/",
            headers = mapOf("Authorization" to "Bearer \${GITHUB_TOKEN}")
        )
        val text = McpConfigExport.exportServer(config)!!
        // 占位符原样导出（真 PAT 从不落盘 —— 令牌桥承诺）；Kotlin 转义：\$ = 字面 $
        assertTrue(text.contains("Bearer \${GITHUB_TOKEN}"))
    }

    @Test
    fun `empty input yields valid skeleton json`() {
        val text = McpConfigExport.exportServers(emptyList())
        val parsed = McpConfigImport.parse(text)
        assertEquals(0, parsed.configs.size)
        assertEquals(0, parsed.errors.size)
    }
}
