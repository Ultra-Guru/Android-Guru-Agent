package com.apex.agent.core.engine

import com.apex.agent.core.llm.LlmClient
import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.LlmResponse
import com.apex.agent.core.llm.LlmStreamChunk
import com.apex.agent.core.llm.ToolCall
import com.apex.agent.core.llm.ToolChoiceSpec
import com.apex.agent.core.llm.ToolDefinition
import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.DefaultToolExecutor
import com.apex.agent.core.tools.ToolRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ═══ 工具循环 v2 — 引擎级行为测试 ═══
 *
 * 1. **并行批执行**：toolCallParallelism=3 时同轮 3 个工具并发执行
 *    （并发峰值观测），事件按发言权协议顺序放行（每个工具的
 *    Start→Complete 生命周期在时间线上连续），历史 ToolResult 按原始
 *    tool_calls 顺序落盘（配对不变量：Assistant 的每个 id 恰有一个紧跟的
 *    ToolResult，绝不悬空 —— 400 根因防线）；
 * 2. **顺序路径回归**：parallelism=1（默认）时逐个执行（历史行为）；
 * 3. **单轮调用数上限**：超出截断到下一轮 + 延迟 System 说明；
 * 4. **任务轮次无上限**：maxIterations=0（哨兵）时 30 轮工具循环照常
 *    完成，无「已达最大迭代」错误。
 */
class EngineToolLoopTest {

    // ── fakes ─────────────────────────────────────────────────────

    /** 逐轮脚本化 client：每轮要么返回一批 tool_calls，要么（脚本耗尽）回文本。 */
    private class ScriptedClient(
        private val rounds: List<List<ToolCall>>,
        private val finalText: String = "done"
    ) : LlmClient {
        var calls = 0

        override suspend fun chat(
            messages: List<LlmMessage>,
            tools: List<ToolDefinition>,
            temperature: Float,
            maxTokens: Int
        ): LlmResponse = LlmResponse(content = "")

        override fun chatStream(
            messages: List<LlmMessage>,
            tools: List<ToolDefinition>,
            temperature: Float,
            maxTokens: Int
        ): Flow<LlmStreamChunk> = chatStream(messages, tools, temperature, maxTokens, null)

        override fun chatStream(
            messages: List<LlmMessage>,
            tools: List<ToolDefinition>,
            temperature: Float,
            maxTokens: Int,
            toolChoice: ToolChoiceSpec?
        ): Flow<LlmStreamChunk> = flow {
            val i = calls++
            val batch = rounds.getOrElse(i) { emptyList() }
            if (batch.isEmpty()) {
                emit(LlmStreamChunk(content = finalText, isFinish = true))
            } else {
                emit(LlmStreamChunk(toolCalls = batch, isFinish = true))
            }
        }
    }

    /** 并发峰值观测器（@Synchronized：IO 工作协程并发进出自增）。 */
    private class ConcurrencyProbe {
        private var active = 0
        var maxObserved = 0
            private set

        @Synchronized
        fun enter() {
            active++
            if (active > maxObserved) maxObserved = active
        }

        @Synchronized
        fun exit() {
            active--
        }
    }

    /** 带延迟的假工具（CORE id 保证进工具计划）；执行期占 probe 一个并发位。 */
    private class SlowTool(
        override val id: String,
        private val probe: ConcurrencyProbe,
        private val delayMs: Long
    ) : AgentTool {
        override val name = "Slow $id"
        override val description = "Loop test tool $id"
        override val parametersSchema = """{"type":"object","properties":{}}"""

        override suspend fun execute(arguments: String): String {
            probe.enter()
            try {
                delay(delayMs)
            } finally {
                probe.exit()
            }
            return "ok:$id"
        }
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

    private fun engineOf(
        registry: ToolRegistry,
        client: ScriptedClient,
        config: AgentConfig
    ): ApexAgentEngine = ApexAgentEngine(
        llmClient = client,
        toolRegistry = registry,
        toolExecutor = DefaultToolExecutor(registry),
        config = config
    )

    private val baseConfig = AgentConfig(
        mode = AgentMode.BUILD,
        thinkingLevel = ThinkingLevel.NONE,
        maxIterations = 8
    )

    private fun call(id: String, name: String) = ToolCall(id, name, "{}")

    private fun tripleRegistry(probe: ConcurrencyProbe, delayMs: Long) = FakeRegistry(
        listOf(
            SlowTool("Read", probe, delayMs),
            SlowTool("Write", probe, delayMs),
            SlowTool("Edit", probe, delayMs)
        )
    )

    private val tripleRound = listOf(
        listOf(call("c1", "Read"), call("c2", "Write"), call("c3", "Edit"))
    )

    // ── 1. 并行批：并发峰值 + 发言权事件顺序 + 配对不变量 ──────────

    @Test
    fun `parallelism 3 runs tools concurrently with ordered results`() = runTest {
        val probe = ConcurrencyProbe()
        val client = ScriptedClient(tripleRound)
        val engine = engineOf(tripleRegistry(probe, 250), client, baseConfig.copy(toolCallParallelism = 3))
        val events = engine.execute("go").toList()

        // 并发峰值 = 3（三个工具同时在场；250ms 余量对 IO 池足够宽容）。
        assertEquals("all three tools should overlap", 3, probe.maxObserved)

        // 发言权协议：每个工具的 Complete 按原始顺序放行。
        val completes = events.filterIsInstance<AgentEvent.ToolCallComplete>()
        assertEquals(listOf("c1", "c2", "c3"), completes.map { it.callId })

        // 配对不变量：Assistant.tool_calls 的每个 id 在其后紧跟恰一个 ToolResult，
        // 且 ToolResult 顺序 = tool_calls 顺序（400 根因防线）。
        val history = engine.conversationHistory
        val assistant = history.filterIsInstance<LlmMessage.Assistant>().first { it.toolCalls.isNotEmpty() }
        assertEquals(listOf("c1", "c2", "c3"), assistant.toolCalls.map { it.id })
        val toolResults = history.filterIsInstance<LlmMessage.ToolResult>()
        assertEquals(listOf("c1", "c2", "c3"), toolResults.map { it.toolCallId })
        assertEquals(3, completes.size)
        assertTrue(completes.all { it.success })
    }

    // ── 2. 顺序路径回归：parallelism=1（默认）逐个执行 ──────────────

    @Test
    fun `parallelism 1 keeps sequential execution as before`() = runTest {
        val probe = ConcurrencyProbe()
        val client = ScriptedClient(tripleRound)
        val engine = engineOf(tripleRegistry(probe, 60), client, baseConfig)

        val events = engine.execute("go").toList()

        assertEquals("sequential path must never overlap", 1, probe.maxObserved)
        val completes = events.filterIsInstance<AgentEvent.ToolCallComplete>()
        assertEquals(listOf("c1", "c2", "c3"), completes.map { it.callId })
        val toolResults = engine.conversationHistory.filterIsInstance<LlmMessage.ToolResult>()
        assertEquals(listOf("c1", "c2", "c3"), toolResults.map { it.toolCallId })
    }

    // ── 3. 单轮调用数上限：截断 + 延迟 System 说明 ─────────────────

    @Test
    fun `per-turn cap truncates calls and defers explanation note`() = runTest {
        val probe = ConcurrencyProbe()
        val client = ScriptedClient(tripleRound)
        val engine = engineOf(
            tripleRegistry(probe, 30), client,
            baseConfig.copy(maxToolCallsPerTurn = 2)
        )

        val events = engine.execute("go").toList()

        // 只有前 2 个调用被执行（第 3 个延迟到下一轮——脚本耗尽直接收尾，
        // 模型可自行重发；这里断言引擎侧截断行为本身）。
        val completes = events.filterIsInstance<AgentEvent.ToolCallComplete>()
        assertEquals(listOf("c1", "c2"), completes.map { it.callId })

        val history = engine.conversationHistory
        val assistant = history.filterIsInstance<LlmMessage.Assistant>().first { it.toolCalls.isNotEmpty() }
        assertEquals("Assistant 消息只携带实际执行的调用（配对锚点）", 2, assistant.toolCalls.size)
        val toolResults = history.filterIsInstance<LlmMessage.ToolResult>()
        assertEquals(2, toolResults.size)

        // 截断说明：延迟 System 提示落在完整 tool 块之后（位置安全点）。
        val lastResultIdx = history.indexOfLast { it is LlmMessage.ToolResult }
        val noteIdx = history.indexOfLast {
            it is LlmMessage.System && it.content.contains("per-turn cap")
        }
        assertTrue("cap note must exist", noteIdx >= 0)
        assertTrue("cap note must come after the tool results", noteIdx > lastResultIdx)
    }

    // ── 4. 任务轮次无上限：maxIterations=0 哨兵 ────────────────────

    @Test
    fun `unlimited iterations runs past the legacy cap without error`() = runTest {
        val probe = ConcurrencyProbe()
        val registry = FakeRegistry(listOf(SlowTool("Read", probe, 1)))
        // 30 轮单工具调用（旧默认上限 25 / 深档放大也远低于此的量级）。
        val rounds = (1..30).map { listOf(call("c$it", "Read")) }
        val client = ScriptedClient(rounds)
        val engine = engineOf(registry, client, baseConfig.copy(maxIterations = 0))

        val events = engine.execute("go").toList()

        val completes = events.filterIsInstance<AgentEvent.ToolCallComplete>()
        assertEquals("all 30 rounds should execute", 30, completes.size)
        assertEquals(
            "no iteration-cap error should surface for the unlimited sentinel",
            0,
            events.count { it is AgentEvent.Error }
        )
        assertEquals(30, engine.conversationHistory.count { it is LlmMessage.ToolResult })
    }
}
