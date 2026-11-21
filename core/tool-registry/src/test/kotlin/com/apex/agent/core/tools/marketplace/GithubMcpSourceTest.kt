package com.apex.agent.core.tools.marketplace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GithubMcpSource 纯解析单测（「从 GitHub 安装」的 MCP 分支）。
 *
 * 喂真实仓库形态的文本夹具（不联网）：
 * - 根 server.json（publish_to_registry 规范，snake_case）→ RegistryServer
 *   字段映射（packages / remotes / 必填环境变量 / 运行时旗标）；
 * - 根 .mcp.json（mcpServers 映射）→ npx 型 npm 沙箱 / http 型远端 /
 *   非 npx command 诚实跳过；
 * - src monorepo package.json → mcpName / bin+mcp 名判定 / 普通 npm 包
 *   不误报；
 * - 树路径 → monorepo 候选（深度固定 2，防深层误报）；
 * - 恶意形态（无 packages 无 remotes 的普通 JSON / 空对象）→ null，不假安装。
 *
 * 网络路径（detectServers / fetchRepoTreePaths）由真实仓库的集成链路覆盖
 * （modelcontextprotocol/servers 与 anthropics/skills 匿名可拉取）。
 */
class GithubMcpSourceTest {

    // ═══════════════════════════════════════════════════════════
    // server.json（publish_to_registry 规范）解析
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `server json maps npm package and required env vars`() {
        val body = """
        {
          "server_id": "io.github.example/server-demo",
          "name": "server-demo",
          "description": "Demo MCP server",
          "version": "1.2.3",
          "packages": [
            {
              "registry_type": "npm",
              "identifier": "@example/server-demo",
              "version": "1.2.3",
              "transport": { "type": "stdio" },
              "environment_variables": [
                { "name": "API_KEY", "required": true },
                { "name": "REGION", "required": false }
              ],
              "run_time_arguments": [
                { "type": "string", "value": "--verbose" }
              ]
            }
          ]
        }
        """.trimIndent()
        val server = GithubMcpSource.parseServerJson(body, "https://github.com/example/repo")
        assertNotNull(server)
        assertEquals("io.github.example/server-demo", server!!.name)
        assertEquals("server-demo", server.displayName)
        assertEquals("1.2.3", server.version)
        assertEquals(RegistryServer.InstallKind.NPM, server.installKind)
        val pkg = server.npmPackage
        assertNotNull(pkg)
        assertEquals("@example/server-demo", pkg!!.identifier)
        assertEquals("npm", pkg.registryType)
        assertEquals(listOf("API_KEY", "REGION"), pkg.envVarNames)
        assertEquals(listOf("API_KEY"), pkg.requiredEnvVarNames)
        assertEquals(listOf("--verbose"), pkg.runtimeArguments)
        // 无 remotes：远端直装不可用，npx 沙箱是唯一安装路径
        assertNull(server.bestRemote)
    }

    @Test
    fun `server json maps streamable http remote with headers`() {
        val body = """
        {
          "server_id": "io.example/remote-demo",
          "title": "Remote Demo",
          "version": "0.9.0",
          "remotes": [
            {
              "transport_type": "streamable_http",
              "url": "https://mcp.example.com/api",
              "headers": [
                { "name": "Authorization", "value": "Bearer {token}" }
              ]
            },
            {
              "transport_type": "sse",
              "url": "https://legacy.example.com/sse"
            }
          ]
        }
        """.trimIndent()
        val server = GithubMcpSource.parseServerJson(body, "https://github.com/example/repo2")
        assertNotNull(server)
        assertEquals(RegistryServer.InstallKind.REMOTE, server!!.installKind)
        val remote = server.bestRemote
        assertNotNull(remote)
        assertEquals("https://mcp.example.com/api", remote!!.url)
        assertEquals("Bearer {token}", remote.headers["Authorization"])
        // streamable-http 优先于 sse
        assertTrue(!remote.isSse)
        assertEquals(2, server.remotes.size)
    }

    @Test
    fun `server json without packages and remotes is rejected`() {
        // 普通仓库的任意 JSON（package 清单等）不是 MCP server —— 不假安装
        val plain = """{ "name": "left-pad", "version": "1.0.0" }"""
        assertNull(GithubMcpSource.parseServerJson(plain, "https://github.com/x/y"))
        // 损坏 JSON 同样 null（不抛异常）
        assertNull(GithubMcpSource.parseServerJson("not json at all", "https://github.com/x/y"))
    }

    @Test
    fun `server json unsupported package type maps to UNSUPPORTED`() {
        val body = """
        {
          "server_id": "io.example/pypi-demo",
          "packages": [
            { "registry_type": "pypi", "identifier": "pypi-demo", "version": "2.0" }
          ]
        }
        """.trimIndent()
        val server = GithubMcpSource.parseServerJson(body, "https://github.com/example/repo3")
        assertNotNull(server)
        assertEquals(RegistryServer.InstallKind.UNSUPPORTED, server!!.installKind)
        assertEquals("pypi", server.unsupportedPackage?.registryType)
    }

    // ═══════════════════════════════════════════════════════════
    // .mcp.json（mcpServers 映射表）解析
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `mcp config json routes npx entries to npm sandbox`() {
        val body = """
        {
          "mcpServers": {
            "filesystem": {
              "command": "npx",
              "args": ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"],
              "env": { "FOO": "bar" }
            }
          }
        }
        """.trimIndent()
        val servers = GithubMcpSource.parseMcpConfigJson(body, "https://github.com/mcp/servers")
        assertEquals(1, servers.size)
        val server = servers.first()
        assertEquals(RegistryServer.InstallKind.NPM, server.installKind)
        assertEquals("@modelcontextprotocol/server-filesystem", server.npmPackage?.identifier)
        assertEquals("filesystem", server.displayName)
        assertEquals(listOf("FOO"), server.npmPackage?.envVarNames)
        // env 值透传：依赖 env 默认值的 server 装完即可启动（旧实现丢值）
        assertEquals(mapOf("FOO" to "bar"), server.environmentValues)
        val config = server.toMcpServerConfig()
        assertEquals(mapOf("FOO" to "bar"), config?.env)
    }

    @Test
    fun `mcp config json routes http entries to remote`() {
        val body = """
        {
          "mcpServers": {
            "docs": { "type": "http", "url": "https://docs.example.com/mcp" },
            "legacy": { "type": "sse", "url": "https://legacy.example.com/sse" }
          }
        }
        """.trimIndent()
        val servers = GithubMcpSource.parseMcpConfigJson(body, "https://github.com/x/y")
        assertEquals(2, servers.size)
        val docs = servers.first { it.displayName == "docs" }
        assertEquals(RegistryServer.InstallKind.REMOTE, docs.installKind)
        assertEquals("https://docs.example.com/mcp", docs.bestRemote?.url)
        val legacy = servers.first { it.displayName == "legacy" }
        assertTrue(legacy.bestRemote!!.isSse)
    }

    @Test
    fun `mcp config json skips non npx stdio commands`() {
        // python / docker / 自定义二进制在 Android 宿主无法执行 —— 诚实跳过，
        // 不产出装了也打不开的残配置
        val body = """
        {
          "mcpServers": {
            "py": { "command": "python", "args": ["-m", "demo_server"] },
            "dockerized": { "command": "docker", "args": ["run", "demo"] }
          }
        }
        """.trimIndent()
        val servers = GithubMcpSource.parseMcpConfigJson(body, "https://github.com/x/y")
        assertTrue(servers.isEmpty())
    }

    @Test
    fun `mcp config json strips version pin from package identifier`() {
        // npx 带 @version 固定（@scope/name@1.2.3 与 name@1.2.3 两种形态）
        val body = """
        {
          "mcpServers": {
            "a": { "command": "npx", "args": ["-y", "@scope/pkg@1.2.3"] },
            "b": { "command": "npx", "args": ["plain-pkg@4.5.6"] }
          }
        }
        """.trimIndent()
        val servers = GithubMcpSource.parseMcpConfigJson(body, "https://github.com/x/y")
        assertEquals(2, servers.size)
        assertEquals("@scope/pkg", servers.first { it.displayName == "a" }.npmPackage?.identifier)
        assertEquals("plain-pkg", servers.first { it.displayName == "b" }.npmPackage?.identifier)
    }

    @Test
    fun `mcp config json with flags before package name extracts identifier`() {
        // 复杂 npx 旗标序列：--call 值是命令（跳过），-p 显式指定包（优先），
        // --yes 合并旗标跳过 —— 按 npx 语义 -p 的值才是包
        val body = """
        {
          "mcpServers": {
            "x": { "command": "npx", "args": ["--yes", "--call", "echo hi", "-p", "explicit-pkg", "positional-cmd"] }
          }
        }
        """.trimIndent()
        val servers = GithubMcpSource.parseMcpConfigJson(body, "https://github.com/x/y")
        assertEquals(1, servers.size)
        // -p 显式指定的包优先于位置参数
        assertEquals("explicit-pkg", servers.first().npmPackage?.identifier)
    }

    @Test
    fun `mcp config json package only flag without positional arg still resolves`() {
        // npx -p pkg（无位置参数）：包即默认命令 —— 不能返回 null 整条跳过
        val body = """
        {
          "mcpServers": {
            "y": { "command": "npx", "args": ["-p", "only-pkg"] }
          }
        }
        """.trimIndent()
        val servers = GithubMcpSource.parseMcpConfigJson(body, "https://github.com/x/y")
        assertEquals(1, servers.size)
        assertEquals("only-pkg", servers.first().npmPackage?.identifier)
    }

    @Test
    fun `mcp config json rejects broken and non server shapes`() {
        assertTrue(GithubMcpSource.parseMcpConfigJson("{", "url").isEmpty())
        assertTrue(GithubMcpSource.parseMcpConfigJson("""{"foo": 1}""", "url").isEmpty())
        // 空对象 / 无 command 无 url 的 entry 跳过
        val body = """{ "mcpServers": { "empty": {}, "nothing": { "type": "stdio" } } }"""
        assertTrue(GithubMcpSource.parseMcpConfigJson(body, "url").isEmpty())
    }

    // ═══════════════════════════════════════════════════════════
    // src monorepo package.json 解析（modelcontextprotocol/servers 血统）
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `monorepo package json with mcpName maps to npm server`() {
        // 真实形态：src/filesystem/package.json（截取关键字段）
        val body = """
        {
          "name": "@modelcontextprotocol/server-filesystem",
          "version": "1.0.0",
          "description": "MCP server for filesystem access",
          "mcpName": "io.github.modelcontextprotocol/server-filesystem",
          "bin": { "mcp-server-filesystem": "dist/index.js" }
        }
        """.trimIndent()
        val server = GithubMcpSource.parseMonorepoPackageJson(body, "https://github.com/modelcontextprotocol/servers")
        assertNotNull(server)
        assertEquals("io.github.modelcontextprotocol/server-filesystem", server!!.name)
        assertEquals("server-filesystem", server.displayName)
        assertEquals(RegistryServer.InstallKind.NPM, server.installKind)
        assertEquals("@modelcontextprotocol/server-filesystem", server.npmPackage?.identifier)
        assertEquals("1.0.0", server.npmPackage?.version)
    }

    @Test
    fun `monorepo package json with mcp in name and bin qualifies`() {
        // 无 mcpName 但名称含 mcp 且带 bin —— 宽容判定（社区仓库常见）
        val body = """
        {
          "name": "my-mcp-server",
          "version": "0.3.1",
          "bin": { "my-mcp-server": "dist/main.js" }
        }
        """.trimIndent()
        val server = GithubMcpSource.parseMonorepoPackageJson(body, "https://github.com/x/y")
        assertNotNull(server)
        assertEquals(RegistryServer.InstallKind.NPM, server!!.installKind)
        assertEquals("my-mcp-server", server.npmPackage?.identifier)
    }

    @Test
    fun `monorepo package json plain npm package is not mcp`() {
        // 普通 npm 包（无 mcpName、名称不含 mcp）不误报 —— 宁缺勿滥
        val body = """
        {
          "name": "left-pad",
          "version": "1.0.0",
          "bin": { "left-pad": "index.js" }
        }
        """.trimIndent()
        assertNull(GithubMcpSource.parseMonorepoPackageJson(body, "https://github.com/x/y"))
    }

    @Test
    fun `monorepo package json without bin and mcp name is not mcp`() {
        val body = """{ "name": "some-mcp-thing", "version": "1.0.0" }"""
        assertNull(GithubMcpSource.parseMonorepoPackageJson(body, "https://github.com/x/y"))
    }

    // ═══════════════════════════════════════════════════════════
    // 树路径 → monorepo 候选过滤
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `tree paths filter src level one package json only`() {
        val paths = listOf(
            "src/filesystem/package.json",
            "src/git/package.json",
            "src/everything/package.json",
            "package.json",                       // 根包不算 monorepo 候选
            "src/nested/deep/package.json",       // 深层不算
            "docs/package.json",                  // 非 src 不算
            "src/filesystem/index.ts",
            "src/filesystem/lib/package.json"     // src 二级子目录不算
        )
        val candidates = GithubMcpSource.monorepoCandidates(paths, "owner", "repo", "main")
        assertEquals(listOf("src/filesystem/package.json", "src/git/package.json", "src/everything/package.json"), candidates)
    }

    @Test
    fun `real servers repo tree shape produces seven candidates`() {
        // modelcontextprotocol/servers 真实树切片（src 一级七台 reference server）
        val paths = listOf(
            ".mcp.json", "README.md", "package.json", "tsconfig.json",
            "src/everything/package.json", "src/fetch/package.json",
            "src/filesystem/package.json", "src/git/package.json",
            "src/memory/package.json", "src/sequentialthinking/package.json",
            "src/time/package.json", "src/filesystem/server.ts"
        )
        val candidates = GithubMcpSource.monorepoCandidates(paths, "modelcontextprotocol", "servers", "main")
        assertEquals(7, candidates.size)
    }
}
