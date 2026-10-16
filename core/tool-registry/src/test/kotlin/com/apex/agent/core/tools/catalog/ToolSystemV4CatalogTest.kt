package com.apex.agent.core.tools.catalog

import com.apex.agent.core.llm.ToolDefinition
import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.ToolRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * # Tool System v4 — catalog core unit tests
 *
 * Covers the four pillars that fix "直接发送对话就报错":
 * 1. [ToolNameSanitizer] — dotted ids become provider-legal names, collision-free;
 * 2. [ToolSchemaSanitizer] — schemas always parse, unsafe keywords dropped;
 * 3. [ToolRequestBudget] — CORE-only default plan, forced plan, budget clamps;
 * 4. [ToolActivationStore] + catalog meta-tools — progressive disclosure loop.
 */
class ToolSystemV4CatalogTest {

    // ── fakes ─────────────────────────────────────────────────────

    private class FakeTool(
        override val id: String,
        override val description: String = "Test tool $id",
        override val parametersSchema: String = """{"type":"object","properties":{}}"""
    ) : AgentTool {
        override val name: String = "Fake $id"
        override suspend fun execute(arguments: String): String = "ok"
    }

    private class FakeRegistry(tools: List<AgentTool>) : ToolRegistry {
        private val map = linkedMapOf<String, AgentTool>()
        init { tools.forEach { map[it.id] = it } }
        override fun register(tool: AgentTool) { map[tool.id] = tool }
        override fun unregister(toolId: String) { map.remove(toolId) }
        override fun getTool(toolId: String): AgentTool? = map[toolId]
        override fun getAllTools(): List<AgentTool> = map.values.toList()
        override fun getToolDefinitions(): List<ToolDefinition> =
            map.values.map { ToolDefinition(it.id, it.description, it.parametersSchema) }
    }

    private fun registryOf(vararg ids: String): FakeRegistry =
        FakeRegistry(ids.map { FakeTool(it) })

    // ── ToolNameSanitizer ─────────────────────────────────────────

    @Test
    fun `dots become underscores`() {
        assertEquals("TerminalExecLegacy", ToolNameSanitizer.sanitize("TerminalExec"))
        assertEquals("terminal_linux_bootstrap", ToolNameSanitizer.sanitize("TerminalLinuxBootstrap"))
    }

    @Test
    fun `illegal characters fold and empty becomes tool`() {
        assertEquals("a_b-c", ToolNameSanitizer.sanitize("a b-c!"))
        assertEquals("tool", ToolNameSanitizer.sanitize("..."))
    }

    @Test
    fun `long names clamp to 64 chars`() {
        val name = ToolNameSanitizer.sanitize("a".repeat(100))
        assertTrue(name.length <= 64)
    }

    @Test
    fun `modern dotted id wins the slot over legacy alias`() {
        val mapping = ToolNameSanitizer.buildMapping(
            listOf("TerminalExec", "TerminalExecLegacy")
        )
        assertEquals("TerminalExecLegacy", mapping["TerminalExec"])
        // legacy alias disambiguated, not silently overwritten
        assertTrue(mapping["TerminalExecLegacy"] != "TerminalExecLegacy")
        assertNotNull(mapping["TerminalExecLegacy"])
    }

    @Test
    fun `resolveRegistryId round trips and falls back`() {
        val ids = listOf("TerminalExec", "Read")
        val mapping = ToolNameSanitizer.buildMapping(ids)
        assertEquals("TerminalExec", ToolNameSanitizer.resolveRegistryId("TerminalExecLegacy", mapping))
        assertEquals("Read", ToolNameSanitizer.resolveRegistryId("Read", mapping))
        // unknown name falls back to itself (registry id direct lookup path)
        assertEquals("whatever", ToolNameSanitizer.resolveRegistryId("whatever", mapping))
    }

    // ── ToolSchemaSanitizer ───────────────────────────────────────

    @Test
    fun `invalid json becomes object skeleton`() {
        val out = ToolSchemaSanitizer.sanitize("{{{not json")
        assertTrue(out.contains("\"type\":\"object\""))
    }

    @Test
    fun `required entries filtered to existing properties`() {
        val out = ToolSchemaSanitizer.sanitize(
            """{"type":"object","properties":{"a":{"type":"string"}},"required":["a","ghost"]}"""
        )
        assertTrue(out.contains(""""required":["a"]"""))
        assertFalse(out.contains("ghost"))
    }

    @Test
    fun `unsafe keywords dropped but safe ones kept`() {
        val dollarSchema = "\$" + "schema"
        val input = """{"type":"object","$dollarSchema":"http://x","title":"t","examples":[1],
               "properties":{"p":{"type":"string","patternProperties":{},"x-ext":1}},
               "patternProperties":{},"additionalProperties":{"type":"string"}}"""
        val out = ToolSchemaSanitizer.sanitize(input)
        assertFalse(out.contains("\$schema"))
        assertFalse(out.contains("title"))
        assertFalse(out.contains("examples"))
        assertFalse(out.contains("patternProperties"))
        assertTrue(out.contains(""""type":"object""""))
    }

    @Test
    fun `oversized schema collapses to skeleton`() {
        val giant = """{"type":"object","properties":{"p":{"type":"string","description":"${"x".repeat(20_000)}"}}}"""
        val out = ToolSchemaSanitizer.sanitize(giant)
        assertTrue(out.length <= 100)
    }

    @Test
    fun `clamp description truncates with marker`() {
        val out = ToolSchemaSanitizer.clampDescription("x".repeat(2000), 1024)
        assertTrue(out.length < 1100)
        assertTrue(out.contains("[description truncated"))
    }

    // ── ToolActivationStore ───────────────────────────────────────

    @Test
    fun `activate is idempotent and reset clears`() {
        val store = ToolActivationStore()
        assertTrue(store.activate("a"))
        assertFalse(store.activate("a"))
        assertEquals(setOf("a"), store.snapshot())
        store.reset()
        assertTrue(store.snapshot().isEmpty())
    }

    @Test
    fun `fifo eviction caps active set`() {
        val store = ToolActivationStore(maxActive = 3)
        listOf("a", "b", "c", "d").forEach { store.activate(it) }
        assertEquals(setOf("b", "c", "d"), store.snapshot())
    }

    // ── ToolRequestBudget ─────────────────────────────────────────

    @Test
    fun `default plan exposes core only and catalog meta tools`() {
        val registry = registryOf(
            "Read", "WebSearch", "AskUser", "ToolSearch", "ToolOpen", "ToolList",
            "GithubCreateIssue", "TerminalExec", "BrowserClick", "BrowserGetCookies", "TerminalExecLegacy"
        )
        val plan = ToolRequestBudget.planDefault(registry, ToolActivationStore())
        val names = plan.tools.map { it.name }.toSet()
        assertTrue("Read" in names)
        assertTrue("WebSearch" in names)
        assertTrue("ToolSearch" in names)
        // 浏览器高频入口随 CORE 预载（v1.5.0：一级大功能的主干路径直达）
        assertTrue("BrowserClick" in names)
        // non-core capabilities stay OUT of the request payload
        assertFalse("GithubCreateIssue" in names)
        // 长尾浏览器工具（Cookie 凭据读取）仍走 ToolSearch 渐进披露
        assertFalse("BrowserGetCookies" in names)
        // legacy alias never ships (its slot is taken by the modern tool)
        assertTrue("TerminalExecLegacy" in names)
        assertEquals("TerminalExec", plan.providerNameToId["TerminalExecLegacy"])
    }

    @Test
    fun `activated tools join the default plan`() {
        val registry = registryOf("Read", "GithubCreateIssue", "ToolSearch")
        val activation = ToolActivationStore()
        activation.activate("GithubCreateIssue")
        val plan = ToolRequestBudget.planDefault(registry, activation)
        val names = plan.tools.map { it.name }
        assertTrue("GithubCreateIssue" in names)
    }

    @Test
    fun `exposeAll widens to the whole non-legacy registry`() {
        val registry = registryOf("Read", "GithubCreateIssue", "BrowserClick", "TerminalExecLegacy")
        val plan = ToolRequestBudget.planDefault(registry, ToolActivationStore(), exposeAll = true)
        val names = plan.tools.map { it.name }.toSet()
        assertTrue("GithubCreateIssue" in names)
        assertTrue("BrowserClick" in names)
        // legacy alias still excluded even in expose-all mode
        assertFalse("TerminalExecLegacy" in names)
        assertEquals(3, plan.tools.size)
    }

    @Test
    fun `forced plan carries only forced ids and reports unknown ones`() {
        val registry = registryOf("Read", "WebSearch", "GithubCreateIssue", "ToolSearch")
        val plan = ToolRequestBudget.planForced(registry, setOf("WebSearch", "no_such_tool"))
        assertEquals(listOf("WebSearch"), plan.tools.map { it.name })
        assertTrue(plan.droppedByBudget.any { it.contains("no_such_tool") })
    }

    @Test
    fun `coreOnly degradation drops activations and exposeAll`() {
        val registry = registryOf("Read", "GithubCreateIssue", "ToolSearch")
        val activation = ToolActivationStore().apply { activate("GithubCreateIssue") }
        val plan = ToolRequestBudget.planDefault(registry, activation, exposeAll = true, coreOnly = true)
        val names = plan.tools.map { it.name }.toSet()
        assertFalse("GithubCreateIssue" in names)
        assertTrue("Read" in names)
    }

    @Test
    fun `plan output is name-sorted for provider prompt-cache stability`() {
        val registry = registryOf("Read", "AskUser", "WebSearch", "ToolSearch")
        val plan = ToolRequestBudget.planDefault(registry, ToolActivationStore())
        val names = plan.tools.map { it.name }
        assertEquals(names.sorted(), names)
    }

    @Test
    fun `budget clamps tool count`() {
        val ids = (1..80).map { "tool_x$it" }
        val activation = ToolActivationStore(maxActive = 80)
        val registry = registryOf(*ids.toTypedArray())
        ids.forEach { activation.activate(it) }
        val plan = ToolRequestBudget.planDefault(registry, activation)
        assertTrue(plan.tools.size <= ToolRequestBudget.MAX_TOOLS)
        assertTrue(plan.droppedByBudget.isNotEmpty())
    }

    // ── catalog meta-tools behaviour ──────────────────────────────

    @Test
    fun `ToolSearch finds tools by keywords`() = runBlocking {
        val registry = registryOf(
            "GithubCreateIssue", "GithubReadFile", "WebSearch", "ToolSearch"
        )
        val tool = ToolSearchTool(registry)
        val result = tool.execute("""{"query":"github issue"}""")
        assertTrue(result.contains("GithubCreateIssue"))
    }

    @Test
    fun `ToolSearch empty query returns invalid argument`() = runBlocking {
        val result = ToolSearchTool(registryOf("a")).execute("""{"query":""}""")
        assertTrue(result.contains("invalid") || result.contains("query"))
    }

    @Test
    fun `ToolOpen activates tool and returns schema`() = runBlocking {
        val registry = registryOf(
            "GithubCreateIssue", "ToolSearch"
        )
        val activation = ToolActivationStore()
        val result = ToolOpenTool(registry, activation).execute(
            """{"tool_name":"GithubCreateIssue"}"""
        )
        assertTrue(result.contains("ACTIVE"))
        assertTrue(result.contains("GithubCreateIssue"))
        assertEquals(setOf("GithubCreateIssue"), activation.snapshot())
        // joins the very next plan
        val plan = ToolRequestBudget.planDefault(registry, activation)
        assertTrue(plan.tools.map { it.name }.contains("GithubCreateIssue"))
    }

    @Test
    fun `ToolOpen accepts sanitized name variant`() = runBlocking {
        val registry = registryOf("TerminalLinuxBootstrap", "ToolSearch")
        val activation = ToolActivationStore()
        val result = ToolOpenTool(registry, activation).execute(
            """{"tool_name":"terminal_linux_bootstrap"}"""
        )
        assertTrue(result.contains("ACTIVE"))
        assertEquals(setOf("TerminalLinuxBootstrap"), activation.snapshot())
    }

    @Test
    fun `ToolOpen unknown tool returns guidance not crash`() = runBlocking {
        val result = ToolOpenTool(registryOf("ToolSearch"), ToolActivationStore())
            .execute("""{"tool_name":"nope"}""")
        assertTrue(result.contains("not_found") || result.contains("Unknown tool"))
    }

    @Test
    fun `ToolList renders categories with counts`() = runBlocking {
        val registry = registryOf("Read", "GithubCreateIssue", "WebSearch")
        val result = ToolListTool(registry).execute("{}")
        assertTrue(result.contains("Registered tools: 3"))
        assertTrue(result.contains("★"))
    }

    // ── MCP naming ────────────────────────────────────────────────

    @Test
    fun `mcp tool ids are namespaced and collision free`() {
        assertEquals(
            "mcp__github__create_issue",
            McpToolNaming.toolId("GitHub", "create_issue")
        )
        assertEquals(
            "mcp__my_server__list",
            McpToolNaming.toolId("My Server", "list")
        )
        assertEquals(
            McpToolNaming.toolId("My Server", "x"),
            McpToolNaming.toolId("my-server", "x")
        )
        assertEquals("srv", McpToolNaming.slug("!!!"))
    }

    @Test
    fun `mcp ids are provider-legal without sanitization`() {
        val id = McpToolNaming.toolId("Fancy Server 2", "do.thing!")
        assertEquals(ToolNameSanitizer.sanitize(id), id)
    }
}
