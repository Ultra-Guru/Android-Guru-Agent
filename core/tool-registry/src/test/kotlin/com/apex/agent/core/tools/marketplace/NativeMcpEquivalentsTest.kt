package com.apex.agent.core.tools.marketplace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * NativeMcpEquivalents —— 目录条目 ↔ 内置原生 MCP 等价映射的单测。
 *
 * 锁定四个不变量：
 * 1. 名字路径：归一化口径（大小写/分隔符变体折叠）命中内置 ID；
 * 2. 包名路径：npm 包名（含 @scope/、去 @version）命中内置 ID；
 * 3. 命令行路径：`npx -y pkg@ver --flag` 提取包名后命中；非 npm 家族
 *    命令（python/uvx）首参不是包名，必须返回 null；
 * 4. 非等价条目（fetch/filesystem 等）不误伤 —— fs 内置是工作区子集，
 *    不是等价物，必须保留 null。
 */
class NativeMcpEquivalentsTest {

    // ── 名字路径 ──

    @Test
    fun `hub name variants resolve to builtin thinking`() {
        assertEquals("thinking", NativeMcpEquivalents.builtinForName("SequentialThinking"))
        assertEquals("thinking", NativeMcpEquivalents.builtinForName("sequential-thinking"))
        assertEquals("thinking", NativeMcpEquivalents.builtinForName("Sequential Thinking"))
        assertEquals("thinking", NativeMcpEquivalents.builtinForName("  SequentialThinking  "))
    }

    @Test
    fun `memory and github name variants resolve`() {
        assertEquals("memory", NativeMcpEquivalents.builtinForName("ServerMemory"))
        assertEquals("memory", NativeMcpEquivalents.builtinForName("server-memory"))
        assertEquals("github", NativeMcpEquivalents.builtinForName("ServerGithub"))
        assertEquals("github", NativeMcpEquivalents.builtinForName("server_github"))
    }

    @Test
    fun `non equivalent names return null`() {
        assertNull(NativeMcpEquivalents.builtinForName("Fetch"))
        assertNull(NativeMcpEquivalents.builtinForName("ServerFilesystem"))
        assertNull(NativeMcpEquivalents.builtinForName("BraveSearch"))
        assertNull(NativeMcpEquivalents.builtinForName(""))
        assertNull(NativeMcpEquivalents.builtinForName("thinking-extra"))
    }

    // ── 包名路径 ──

    @Test
    fun `npm package identifiers resolve`() {
        assertEquals(
            "thinking",
            NativeMcpEquivalents.builtinForPackage("@modelcontextprotocol/server-sequential-thinking")
        )
        assertEquals(
            "memory",
            NativeMcpEquivalents.builtinForPackage("@modelcontextprotocol/server-memory")
        )
        assertEquals(
            "github",
            NativeMcpEquivalents.builtinForPackage("@modelcontextprotocol/server-github")
        )
    }

    @Test
    fun `unrelated packages return null`() {
        assertNull(NativeMcpEquivalents.builtinForPackage("@modelcontextprotocol/server-filesystem"))
        assertNull(NativeMcpEquivalents.builtinForPackage("@modelcontextprotocol/server-everything"))
        assertNull(NativeMcpEquivalents.builtinForPackage("mcp-server-fetch"))
        assertNull(NativeMcpEquivalents.builtinForPackage(""))
    }

    // ── 命令行路径 ──

    @Test
    fun `npx command line extracts package and resolves`() {
        assertEquals(
            "thinking",
            NativeMcpEquivalents.builtinForCommand(
                "npx", listOf("-y", "@modelcontextprotocol/server-sequential-thinking")
            )
        )
        // 带 @version 尾缀：先提取去版本包名再查
        assertEquals(
            "github",
            NativeMcpEquivalents.builtinForCommand(
                "npx", listOf("-y", "@modelcontextprotocol/server-github@0.5.0", "--flag")
            )
        )
    }

    @Test
    fun `non npm family commands never resolve via first arg`() {
        // python/uvx 的首参是脚本路径/包名之外的东西 —— 即便碰巧同名也不该命中
        assertNull(NativeMcpEquivalents.builtinForCommand("uvx", listOf("mcp-server-fetch")))
        assertNull(NativeMcpEquivalents.builtinForCommand("python", listOf("-m", "server")))
        assertNull(NativeMcpEquivalents.builtinForCommand(null, listOf("npx", "-y", "pkg")))
        assertNull(NativeMcpEquivalents.builtinForCommand("npx", emptyList()))
    }

    @Test
    fun `npx package without scope resolves scoped registry entry`() {
        // 无 scope 包名不在收录清单 —— null（当前无此形态的等价条目）
        assertNull(NativeMcpEquivalents.builtinForCommand("npx", listOf("-y", "sequential-thinking")))
    }

    // ── 统一入口 ──

    @Test
    fun `builtinFor combines name and command paths`() {
        assertEquals(
            "thinking",
            NativeMcpEquivalents.builtinFor(
                rawName = "SequentialThinking",
                command = "npx",
                args = listOf("-y", "@modelcontextprotocol/server-sequential-thinking")
            )
        )
        // 名字没命中但命令行命中
        assertEquals(
            "memory",
            NativeMcpEquivalents.builtinFor(
                rawName = "知识图谱",
                command = "npx",
                args = listOf("-y", "@modelcontextprotocol/server-memory")
            )
        )
        assertNull(NativeMcpEquivalents.builtinFor("Fetch", "uvx", listOf("mcp-server-fetch")))
    }

    // ── Registry 三路查询 ──

    @Test
    fun `builtinForRegistry resolves via display name and npm identifier`() {
        // 展示名命中（Registry name 是 reverse-DNS，不撞收录名）
        assertEquals(
            "thinking",
            NativeMcpEquivalents.builtinForRegistry(
                name = "io.modelcontextprotocol/servers",
                displayName = "Sequential Thinking"
            )
        )
        // 展示名空 → 包名兜底命中
        assertEquals(
            "memory",
            NativeMcpEquivalents.builtinForRegistry(
                name = "io.modelcontextprotocol/servers",
                displayName = "",
                npmIdentifier = "@modelcontextprotocol/server-memory"
            )
        )
        // 三路全空/全不命中
        assertNull(
            NativeMcpEquivalents.builtinForRegistry(
                name = "io.github.bytedance/mcp-server-filesystem",
                displayName = "Filesystem",
                npmIdentifier = "@modelcontextprotocol/server-filesystem"
            )
        )
        assertNull(NativeMcpEquivalents.builtinForRegistry("", "", null))
    }
}
