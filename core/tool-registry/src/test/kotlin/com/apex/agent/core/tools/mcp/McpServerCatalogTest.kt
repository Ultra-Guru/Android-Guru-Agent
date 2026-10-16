package com.apex.agent.core.tools.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #205 单测：[McpServerCatalog] 的解析 / 校验 / 条目 → 配置装配。
 *
 * 目录 JSON 是离线资产 —— 这里锁的是解析器的全部语义（schema 门禁、
 * 枚举校验、必填环境变量引导、安装装配），不依赖真实资产文件。
 */
class McpServerCatalogTest {

    private fun entryJson(
        id: String = "memory",
        transport: String = "STDIO",
        command: String? = "npx",
        args: String = """["-y", "@modelcontextprotocol/server-memory"]""",
        url: String? = null,
        envSchema: String = "[]",
        runtime: String = "node",
        tier: String = "agent",
        risk: String = "low",
        sandboxOnly: Boolean = false,
        extra: String = ""
    ): String {
        val cmdField = command?.let { """"command": "$it",""" } ?: ""
        val urlField = url?.let { """"url": "$it",""" } ?: ""
        val sandboxField = if (sandboxOnly) """"sandboxOnly": true,""" else ""
        return """
            {
              "id": "$id",
              "name": "Memory",
              "description": "knowledge graph memory",
              "descriptionZh": "知识图谱记忆",
              "transport": "$transport",
              $cmdField
              "args": $args,
              $urlField
              "envSchema": $envSchema,
              "runtime": "$runtime",
              "tier": "$tier",
              "risk": "$risk",
              $sandboxField
              "homepage": "https://example.com",
              "notes": null
              $extra
            }
        """.trimIndent()
    }

    private fun fileJson(entries: String, category: String = "official", schema: String = "apex-mcp-catalog-v1"): String =
        """
        {
          "schema": "$schema",
          "category": "$category",
          "categoryLabel": "官方",
          "entries": [$entries]
        }
        """.trimIndent()

    // ═══ 解析 ═══

    @Test
    fun `parse roundtrips entries and backfills category`() {
        val file = McpServerCatalog.parseCategoryFile(fileJson(entryJson())).getOrThrow()
        assertEquals("official", file.category)
        assertEquals(1, file.entries.size)
        val e = file.entries.single()
        assertEquals("memory", e.id)
        assertEquals("知识图谱记忆", e.descriptionZh)
        // category 由文件名回填到每条条目
        assertEquals("official", e.category)
        assertEquals(listOf("-y", "@modelcontextprotocol/server-memory"), e.args)
    }

    @Test
    fun `unknown schema is rejected`() {
        val result = McpServerCatalog.parseCategoryFile(fileJson(entryJson(), schema = "apex-mcp-catalog-v2"))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("schema"))
    }

    @Test
    fun `empty entries list is rejected`() {
        val result = McpServerCatalog.parseCategoryFile(fileJson(""))
        assertTrue(result.isFailure)
    }

    @Test
    fun `blank category falls back to filename category`() {
        val file = McpServerCatalog.parseCategoryFile(fileJson(entryJson(), category = ""), fallbackCategory = "docs")
            .getOrThrow()
        assertEquals("docs", file.category)
        assertEquals("docs", file.entries.single().category)
    }

    @Test
    fun `unknown fields are forward compatible`() {
        // 明天给条目加新字段（如 stars），旧解析器必须不炸（ignoreUnknownKeys）。
        val withExtra = entryJson(extra = """, "stars": 123, "future": {"x": 1}""")
        val file = McpServerCatalog.parseCategoryFile(fileJson(withExtra)).getOrThrow()
        assertEquals("memory", file.entries.single().id)
    }

    // ═══ 校验 ═══

    @Test
    fun `validateEntries accepts a clean entry`() {
        val issues = McpServerCatalog.validateEntries(
            listOf(
                McpServerCatalog.McpCatalogEntry(
                    id = "memory", name = "Memory", description = "d",
                    transport = McpTransport.STDIO, command = "npx",
                    args = listOf("-y", "pkg"), runtime = "node", tier = "agent",
                    risk = "low", sandboxOnly = true
                )
            )
        )
        assertTrue(issues.isEmpty())
    }

    @Test
    fun `duplicate and illegal ids are flagged`() {
        // v5：大写已是合法命名（PascalCase）；非法样例改为下划线写法
        val entries = listOf(
            McpServerCatalog.McpCatalogEntry(id = "Bad_Id", name = "x", description = "d", transport = McpTransport.STDIO, command = "npx"),
            McpServerCatalog.McpCatalogEntry(id = "Bad_Id", name = "x", description = "d", transport = McpTransport.STDIO, command = "npx")
        )
        val issues = McpServerCatalog.validateEntries(entries)
        assertTrue(issues.any { it.reason.contains("非法") })
        assertTrue(issues.any { it.reason.contains("重复") })
    }

    @Test
    fun `stdio without command and remote without url are flagged`() {
        val stdioNoCmd = McpServerCatalog.McpCatalogEntry(
            id = "a", name = "x", description = "d", transport = McpTransport.STDIO, command = null
        )
        val remoteNoUrl = McpServerCatalog.McpCatalogEntry(
            id = "b", name = "x", description = "d", transport = McpTransport.HTTP
        )
        val issues = McpServerCatalog.validateEntries(listOf(stdioNoCmd, remoteNoUrl))
        assertTrue(issues.any { it.entryId == "a" && it.reason.contains("command") })
        assertTrue(issues.any { it.entryId == "b" && it.reason.contains("url") })
    }

    @Test
    fun `python runtime must be sandbox only`() {
        val py = McpServerCatalog.McpCatalogEntry(
            id = "fetch", name = "x", description = "d",
            transport = McpTransport.STDIO, command = "uvx",
            runtime = "python", sandboxOnly = false
        )
        val issues = McpServerCatalog.validateEntries(listOf(py))
        assertTrue(issues.any { it.reason.contains("sandboxOnly") })
    }

    @Test
    fun `enum violations are flagged`() {
        val bad = McpServerCatalog.McpCatalogEntry(
            id = "x", name = "x", description = "d",
            transport = McpTransport.STDIO, command = "npx",
            runtime = "wasm", tier = "both", risk = "extreme"
        )
        val issues = McpServerCatalog.validateEntries(listOf(bad))
        assertTrue(issues.count { it.reason.contains("runtime") || it.reason.contains("tier") || it.reason.contains("risk") } >= 3)
    }

    @Test
    fun `builtin transport is rejected by the catalog`() {
        val builtin = McpServerCatalog.McpCatalogEntry(
            id = "github", name = "x", description = "d", transport = McpTransport.BUILTIN
        )
        val issues = McpServerCatalog.validateEntries(listOf(builtin))
        assertTrue(issues.any { it.reason.contains("BUILTIN") })
    }

    @Test
    fun `duplicate env keys are flagged`() {
        val dupEnv = McpServerCatalog.McpCatalogEntry(
            id = "k", name = "x", description = "d", transport = McpTransport.STDIO, command = "npx",
            envSchema = listOf(
                McpServerCatalog.McpCatalogEnvVar("K", required = true),
                McpServerCatalog.McpCatalogEnvVar("K", required = false)
            )
        )
        val issues = McpServerCatalog.validateEntries(listOf(dupEnv))
        assertTrue(issues.any { it.reason.contains("envSchema") })
    }

    // ═══ 条目 → 配置 ═══

    @Test
    fun `toServerConfig fills env only with non-blank values`() {
        val entry = McpServerCatalog.McpCatalogEntry(
            id = "brave", name = "Brave", description = "d",
            transport = McpTransport.STDIO, command = "npx",
            args = listOf("-y", "server-brave"),
            envSchema = listOf(McpServerCatalog.McpCatalogEnvVar("K", required = true)),
            runtime = "node", tier = "agent", sandboxOnly = true
        )
        val config = McpServerCatalog.toServerConfig(
            entry, envValues = mapOf("K" to "secret", "OPT" to "  ")
        )
        assertEquals(mapOf("K" to "secret"), config.env)
        assertEquals("brave", config.name)
        assertEquals("agent", config.scope)
        assertTrue(config.runInSandbox) // sandboxOnly=true 强制
    }

    @Test
    fun `toServerConfig non-sandbox entry honors caller sandbox choice`() {
        val entry = McpServerCatalog.McpCatalogEntry(
            id = "x", name = "X", description = "d",
            transport = McpTransport.STDIO, command = "docker"
        )
        assertFalse(McpServerCatalog.toServerConfig(entry, runInSandbox = false).runInSandbox)
        assertTrue(McpServerCatalog.toServerConfig(entry, runInSandbox = true).runInSandbox)
    }

    @Test
    fun `toServerConfig maps remote entry to url transport`() {
        val entry = McpServerCatalog.McpCatalogEntry(
            id = "deepwiki", name = "DeepWiki", description = "d",
            transport = McpTransport.HTTP, url = "https://mcp.deepwiki.com/mcp",
            runtime = "remote", tier = "all", sandboxOnly = false
        )
        val config = McpServerCatalog.toServerConfig(entry)
        assertEquals(McpTransport.HTTP, config.transport)
        assertEquals("https://mcp.deepwiki.com/mcp", config.url)
        assertEquals("all", config.scope)
    }

    @Test
    fun `toServerConfig rejects builtin entry`() {
        val builtin = McpServerCatalog.McpCatalogEntry(
            id = "github", name = "G", description = "d", transport = McpTransport.BUILTIN
        )
        val result = runCatching { McpServerCatalog.toServerConfig(builtin) }
        assertTrue(result.isFailure)
    }

    // ═══ 必填环境变量引导 ═══

    @Test
    fun `missingRequiredEnv names only required blank keys`() {
        val entry = McpServerCatalog.McpCatalogEntry(
            id = "s", name = "S", description = "d", transport = McpTransport.STDIO, command = "npx",
            envSchema = listOf(
                McpServerCatalog.McpCatalogEnvVar("REQ", required = true),
                McpServerCatalog.McpCatalogEnvVar("OPT", required = false)
            )
        )
        assertEquals(listOf("REQ"), McpServerCatalog.missingRequiredEnv(entry, mapOf("OPT" to "v")))
        assertEquals(emptyList<String>(), McpServerCatalog.missingRequiredEnv(entry, mapOf("REQ" to "k")))
    }

    // ═══ 分级可见性 ═══

    @Test
    fun `tier visibility filters correctly`() {
        fun t(tier: String) = McpServerCatalog.McpCatalogEntry(
            id = "t-$tier", name = "t", description = "d",
            transport = McpTransport.STDIO, command = "n", tier = tier
        )
        val all = listOf(t("agent"), t("coding"), t("all"))
        // agent 分级：agent + all 可见（2）；coding 条目不可见。
        assertEquals(2, all.count { it.visibleToTier("agent") })
        assertEquals(2, all.count { it.visibleToTier("coding") })
        assertTrue(t("coding").visibleToTier("coding"))
        assertFalse(t("coding").visibleToTier("agent"))
        // 空分级（未选择）= 全可见
        assertEquals(3, all.count { it.visibleToTier("") })
    }

    @Test
    fun `descriptionFor picks zh only when zh text exists`() {
        val entry = McpServerCatalog.McpCatalogEntry(
            id = "d", name = "D", description = "english", descriptionZh = "中文",
            transport = McpTransport.STDIO, command = "npx"
        )
        assertEquals("中文", entry.descriptionFor(langZh = true))
        assertEquals("english", entry.descriptionFor(langZh = false))
        val noZh = entry.copy(descriptionZh = "")
        assertEquals("english", noZh.descriptionFor(langZh = true))
    }
}
