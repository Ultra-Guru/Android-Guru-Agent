package com.apex.agent.plugin.host.functional

import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolExecutor
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolRegistry
import com.apex.agent.core.tools.ToolRisk
import com.apex.agent.core.tools.ToolStreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MacroToolAdapter] 宏执行引擎单测（Plugin System v2）。
 *
 * 锁死的核心契约：
 * 1. 步骤顺序执行且字面量参数原样传递（fake executor 捕获调用序列断言）；
 * 2. 变量域：`{{input}}` / `{{input.field}}` / `output` 捕获与步间插值；
 * 3. 失败短路：任一步 "Error" 前缀 → 宏立即失败，携带步号 + 工具 id；
 * 4. 未解析变量 = 诚实失败（绝不静默空替换）；
 * 5. 变量体积钳制（超长折叠，头尾保留）；
 * 6. 元数据信任钳制：风险 = max(MEDIUM, 步骤工具风险)、readOnly=false、
 *    类别 PLUGIN。
 */
class MacroToolAdapterTest {

    /** 手写 fake（仓库纪律：无 mock 框架）：工具表 + 调用捕获。 */
    private class FakeStepExecutor(
        private val behavior: Map<String, (String) -> String>
    ) : ToolExecutor {
        val calls = mutableListOf<Pair<String, String>>()

        override suspend fun execute(toolId: String, arguments: String): String {
            calls += toolId to arguments
            return behavior[toolId]?.invoke(arguments)
                ?: "Error: tool '$toolId' not found"
        }

        override fun executeStream(toolId: String, arguments: String): Flow<ToolStreamEvent> =
            kotlinx.coroutines.flow.flow { emit(ToolStreamEvent.Output(execute(toolId, arguments))) }
    }

    /** 元数据注入型注册表 fake：只为风险钳制提供 lookup。 */
    private class FakeRegistry(private val metadata: Map<String, ToolRisk>) : ToolRegistry {
        override fun register(tool: AgentTool) = Unit
        override fun register(
            tool: AgentTool,
            policy: com.apex.agent.core.tools.DuplicateToolIdPolicy
        ): AgentTool? = null

        override fun unregister(toolId: String) = Unit
        override fun getTool(toolId: String): AgentTool? = null
        override fun getAllTools(): List<AgentTool> = emptyList()
        override fun getToolDefinitions(): List<com.apex.agent.core.llm.ToolDefinition> = emptyList()
        override fun metadataOf(toolId: String): ToolMetadata? =
            metadata[toolId]?.let { risk -> ToolMetadata(id = toolId, category = ToolCategory.SHELL, risk = risk) }
    }

    private fun contribution(
        stepsJson: String,
        id: String = "kit/run",
        schema: String? = null
    ): ToolContribution = ToolContribution(
        id = id,
        description = "测试宏",
        parametersSchema = schema,
        steps = Json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(MacroStep.serializer()), stepsJson)
    )

    private fun macro(
        stepsJson: String,
        executor: FakeStepExecutor,
        registry: ToolRegistry = FakeRegistry(emptyMap()),
        scope: String = "all"
    ) = MacroToolAdapter(
        pluginId = "functional.kit",
        contribution = contribution(stepsJson),
        scope = scope,
        executor = executor,
        registry = registry
    )

    // ═══════════════ 步骤顺序与参数传递 ═══════════════

    @Test
    fun `步骤按序执行且字面量参数原样传递`() = runTest {
        val executor = FakeStepExecutor(
            mapOf(
                "tool_a" to { "result-a" },
                "tool_b" to { "result-b" }
            )
        )
        val steps = """[{"tool":"tool_a","args":{"x":"1"}},
                       {"tool":"tool_b","args":{"y":"2"}}]"""

        val result = macro(steps, executor).execute("{}")

        assertTrue(result.startsWith("OK:"))
        // 步骤顺序 + 参数原样（无插值时 args 原封不动）。
        assertEquals(listOf("tool_a" to """{"x":"1"}""", "tool_b" to """{"y":"2"}"""), executor.calls)
        // 尾步输出作为宏结果主体。
        assertTrue(result.contains("result-b"))
        assertTrue(result.contains("2 步"))
        assertTrue(result.contains("宏执行轨迹"))
    }

    @Test
    fun `缺 args 的步骤传空对象`() = runTest {
        val executor = FakeStepExecutor(mapOf("t" to { "ok" }))

        macro("""[{"tool":"t"}]""", executor).execute("{}")

        assertEquals("t" to "{}", executor.calls.single())
    }

    // ═══════════════ 变量域（input / output / 步间插值） ═══════════════

    @Test
    fun `input 顶层字段插值`() = runTest {
        val executor = FakeStepExecutor(mapOf("nav" to { "navigated" }))
        val steps = """[{"tool":"nav","args":{"url":"{{input.site}}","mode":"fixed"}}]"""

        macro(steps, executor).execute("""{"site":"https://example.com"}""")

        val args = executor.calls.single().second
        val obj = Json.parseToJsonElement(args) as JsonObject
        assertEquals("https://example.com", obj["url"]!!.toString().trim('"'))
        assertEquals("fixed", obj["mode"]!!.toString().trim('"'))
    }

    @Test
    fun `output 捕获与后续步骤插值`() = runTest {
        val executor = FakeStepExecutor(
            mapOf(
                "extract" to { "这是正文内容" },
                "notify" to { "notified" }
            )
        )
        val steps = """[{"tool":"extract","output":"body"},
                       {"tool":"notify","args":{"message":"内容：{{body}}"}}]"""

        macro(steps, executor).execute("{}")

        val notifyArgs = executor.calls[1].second
        assertTrue(notifyArgs.contains("内容：这是正文内容"))
    }

    @Test
    fun `input 整体 JSON 文本插值`() = runTest {
        val executor = FakeStepExecutor(mapOf("mirror" to { "ok" }))
        val steps = """[{"tool":"mirror","args":{"raw":"{{input}}"}}]"""

        macro(steps, executor).execute("""{"a":1}""")

        val args = executor.calls.single().second
        // 插值后值作为 JSON 字符串内嵌，断言解析后的字段值而非转义前的字面量。
        val raw = (Json.parseToJsonElement(args) as JsonObject)["raw"]!!.jsonPrimitive.content
        assertEquals("{\"a\":1}", raw)
    }

    @Test
    fun `未解析变量诚实失败`() = runTest {
        val executor = FakeStepExecutor(mapOf("t" to { "ok" }))
        val steps = """[{"tool":"t","args":{"x":"{{missing_var}}"}}]"""

        val result = macro(steps, executor).execute("{}")

        assertTrue(result.startsWith("Error"))
        assertTrue(result.contains("第 1 步"))
        assertTrue(result.contains("'t'"))
        assertTrue(result.contains("变量"))
    }

    @Test
    fun `占位符周围文本保留且可多变量同串`() = runTest {
        val executor = FakeStepExecutor(
            mapOf(
                "a" to { "AAA" },
                "b" to { "prefix <AAA> suffix" }
            )
        )
        val steps = """[{"tool":"a","output":"x"},
                       {"tool":"b","args":{"m":"头{{x}}尾"}}]"""

        macro(steps, executor).execute("{}")

        val args = executor.calls[1].second
        assertTrue(args.contains("头AAA尾"))
    }

    // ═══════════════ 失败短路（v1 字符串协议） ═══════════════

    @Test
    fun `任一步 Error 前缀即短路且携带步号工具 id`() = runTest {
        val executor = FakeStepExecutor(
            mapOf(
                "ok_tool" to { "fine" },
                "bad_tool" to { "Error: 目标页面加载失败" },
                "never_tool" to { "should-not-run" }
            )
        )
        val steps = """[{"tool":"ok_tool"},
                       {"tool":"bad_tool"},
                       {"tool":"never_tool"}]"""

        val result = macro(steps, executor).execute("{}")

        assertTrue(result.startsWith("Error"))
        assertTrue(result.contains("第 2 步"))
        assertTrue(result.contains("'bad_tool'"))
        assertTrue(result.contains("目标页面加载失败"))
        // 短路：第三步绝不执行。
        assertEquals(2, executor.calls.size)
    }

    @Test
    fun `未知工具在执行期诚实失败`() = runTest {
        val executor = FakeStepExecutor(emptyMap())

        val result = macro("""[{"tool":"ghost"}]""", executor).execute("{}")

        assertTrue(result.startsWith("Error"))
        assertTrue(result.contains("ghost"))
    }

    @Test
    fun `非对象入参诚实失败`() = runTest {
        val executor = FakeStepExecutor(mapOf("t" to { "ok" }))

        val result = macro("""[{"tool":"t"}]""", executor).execute("plain-text")

        assertTrue(result.startsWith("Error"))
        assertTrue(result.contains("JSON object"))
    }

    @Test
    fun `空入参按空对象处理`() = runTest {
        val executor = FakeStepExecutor(mapOf("t" to { "ok" }))

        val result = macro("""[{"tool":"t"}]""", executor).execute("")

        assertTrue(result.startsWith("OK:"))
    }

    // ═══════════════ 体积钳制 ═══════════════

    @Test
    fun `超长变量折叠保头尾`() = runTest {
        val bigBody = "A".repeat(10_000) + "TAIL_MARKER"
        val executor = FakeStepExecutor(
            mapOf(
                "extract" to { bigBody },
                "notify" to { "done" }
            )
        )
        val steps = """[{"tool":"extract","output":"body"},
                       {"tool":"notify","args":{"m":"{{body}}"}}]"""

        macro(steps, executor).execute("{}")

        val interpolated = executor.calls[1].second
        // 头部保留（前缀 A 串）+ 折叠标记 + 尾部标记保留。
        assertTrue(interpolated.length < bigBody.length)
        assertTrue(interpolated.contains("AAAA"))
        assertTrue(interpolated.contains("TAIL_MARKER"))
        assertTrue(interpolated.contains("折叠"))
    }

    // ═══════════════ 元数据信任钳制 ═══════════════

    @Test
    fun `风险钳制取步骤工具最大值且不低于 MEDIUM`() {
        val executor = FakeStepExecutor(emptyMap())
        val registry = FakeRegistry(
            mapOf(
                "safe_read" to ToolRisk.LOW,
                "danger_write" to ToolRisk.HIGH
            )
        )
        val steps = """[{"tool":"safe_read"},{"tool":"danger_write"}]"""

        val metadata = macro(steps, executor, registry).metadata

        assertEquals(ToolRisk.HIGH, metadata.risk)
        assertEquals(ToolCategory.PLUGIN, metadata.category)
        assertFalse(metadata.annotations.readOnlyHint)
        assertTrue(metadata.annotations.openWorldHint)
        assertTrue(metadata.tags.contains("plugin"))
        assertTrue(metadata.tags.contains("functional"))
    }

    @Test
    fun `未知步骤工具风险按 MEDIUM 下限`() {
        val executor = FakeStepExecutor(emptyMap())
        val metadata = macro("""[{"tool":"ghost_tool"}]""", executor).metadata

        assertEquals(ToolRisk.MEDIUM, metadata.risk)
    }

    @Test
    fun `scope 归一并透传`() {
        val executor = FakeStepExecutor(emptyMap())
        val metadata = macro("""[{"tool":"t"}]""", executor, scope = "coding").metadata

        assertEquals("coding", metadata.scope)
        // 非法 scope 折叠为 all（fail-open：拼写错误不把工具藏死）。
        val normalized = macro("""[{"tool":"t"}]""", executor, scope = "typo").metadata
        assertEquals("all", normalized.scope)
    }

    @Test
    fun `schema 缺省占位与描述回退`() {
        val executor = FakeStepExecutor(emptyMap())
        val adapter = macro("""[{"tool":"t"}]""", executor)

        assertEquals("""{"type":"object","properties":{}}""", adapter.parametersSchema)
        assertEquals("测试宏", adapter.description)
    }

    // ═══════════════ 防线（安装期校验的运行期兜底） ═══════════════

    @Test
    fun `步数超限在执行期也拒绝`() = runTest {
        val executor = FakeStepExecutor(mapOf("t" to { "ok" }))
        val manySteps = (1..20).joinToString(",") { """{"tool":"t"}""" }
        val adapter = MacroToolAdapter(
            pluginId = "functional.kit",
            contribution = ToolContribution(
                id = "kit/run",
                description = "超限宏",
                steps = Json.decodeFromString(
                    kotlinx.serialization.builtins.ListSerializer(MacroStep.serializer()),
                    "[$manySteps]"
                )
            ),
            scope = "all",
            executor = executor,
            registry = FakeRegistry(emptyMap())
        )

        val result = adapter.execute("{}")

        assertTrue(result.startsWith("Error"))
        assertTrue(result.contains("16"))
        assertEquals("执行前短路，零调用", 0, executor.calls.size)
    }
}
