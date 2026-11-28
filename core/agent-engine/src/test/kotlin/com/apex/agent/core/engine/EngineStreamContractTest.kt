package com.apex.agent.core.engine

import com.apex.agent.core.engine.goal.GoalCheckResult
import com.apex.agent.core.engine.goal.GoalModeCoordinator
import com.apex.agent.core.engine.goal.GoalSpec
import com.apex.agent.core.engine.goal.GoalVerifier
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #820 回归 —— 引擎事件流契约「每个 ResponseChunk 流必有 ResponseComplete 收尾」。
 *
 * BUILD/GOAL 循环的三条 continue 路径（肌肉记忆旁路 / PromiseDetector 催促 /
 * GOAL 续跑）都在已发射过 ResponseChunk 的情况下回到循环头；修复前它们不补发
 * Complete → UI 流式气泡永久悬挂 + 下一轮首个 ResponseChunk 命中 reducer 的
 * 「append to last streaming」分支，两轮文本粘连成一条消息。
 *
 * 断言口径：每条 continue 路径的上一轮 chunk 流，在**下一个 IterationStart 之前**
 * 必须出现一个 ResponseComplete，且 fullText 与该轮完整文本一致。
 */
class EngineStreamContractTest {

    // ── recording client（与 EngineToolSystemV4Test 同款骨架）─────────────

    private class RecordingLlmClient(private val script: List<String>) : LlmClient {
        val calls = mutableListOf<List<LlmMessage>>()
        private var index = 0

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
            calls += messages
            val text = script.getOrElse(index++) { "(script exhausted)" }
            emit(LlmStreamChunk(content = text, isFinish = true))
        }
    }

    private class FakeTool(override val id: String) : AgentTool {
        override val description: String = "Test tool $id"
        override val parametersSchema: String = """{"type":"object","properties":{}}"""
        override val name: String = "Fake $id"
        override suspend fun execute(arguments: String): String = "ok:$id"
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

    // ── 断言工具 ─────────────────────────────────────────────────────────

    /** 上一轮 chunk 流与下一轮 IterationStart(n+1) 之间必须有 Complete(fullText)。 */
    private fun assertCompleteBeforeNextIteration(
        events: List<AgentEvent>,
        iteration: Int,
        expectedFullText: String
    ) {
        val start = events.indexOfFirst { it is AgentEvent.IterationStart && it.iteration == iteration }
        val next = events.indexOfFirst { it is AgentEvent.IterationStart && it.iteration == iteration + 1 }
        assertTrue("IterationStart($iteration) not found", start >= 0)
        assertTrue("IterationStart(${iteration + 1}) not found", next > start)
        val completes = events.subList(start, next).filterIsInstance<AgentEvent.ResponseComplete>()
        assertTrue(
            "no ResponseComplete between iteration $iteration and ${iteration + 1} " +
                "(stream contract broken: bubble stays streaming, next round glues onto it)",
            completes.isNotEmpty()
        )
        assertEquals(expectedFullText, completes.last().fullText)
    }

    // ── 1. PromiseDetector 催促路径（BUILD）──────────────────────────────

    @Test
    fun `promise nudge emits ResponseComplete before continuing loop`() = runBlocking {
        val round1 = "I will now run the test suite to verify the fix works end to end."
        val round2 = "Tests executed successfully: 42 passed, 0 failed. The fix is verified."
        val client = RecordingLlmClient(listOf(round1, round2))
        val engine = ApexAgentEngine(
            llmClient = client,
            toolRegistry = FakeRegistry(listOf(FakeTool("t1"))),
            toolExecutor = DefaultToolExecutor(FakeRegistry(listOf(FakeTool("t1")))),
            config = AgentConfig(mode = AgentMode.BUILD, maxIterations = 5)
        )
        val events = engine.execute("fix the bug").toList()

        // 催促确实触发（两轮 LLM 调用），且两轮都以 Complete 收尾。
        assertEquals(2, client.calls.size)
        assertEquals(2, events.filterIsInstance<AgentEvent.ResponseComplete>().size)
        assertCompleteBeforeNextIteration(events, 1, round1)
    }

    // ── 2. 肌肉记忆旁路路径（BUILD）─────────────────────────────────────

    @Test
    fun `memory bypass emits ResponseComplete before continuing loop`() = runBlocking {
        val observer = object : ExecutionMemoryObserver {
            private var fired = false
            override suspend fun tryBypass(): BypassOutcome =
                if (!fired) { fired = true; BypassOutcome.Executed(actionCount = 3) }
                else BypassOutcome.NotAttempted
        }
        val final = "All replayed steps verified against the live screen state."
        val client = RecordingLlmClient(listOf(final))
        val registry = FakeRegistry(listOf(FakeTool("t1")))
        val engine = ApexAgentEngine(
            llmClient = client,
            toolRegistry = registry,
            toolExecutor = DefaultToolExecutor(registry),
            config = AgentConfig(mode = AgentMode.BUILD, maxIterations = 5),
            memoryObserver = observer
        )
        val events = engine.execute("do the thing").toList()

        // 旁路轮（无 LLM 调用）+ LLM 收尾轮。
        assertEquals(1, client.calls.size)
        val bypassChunk = events.filterIsInstance<AgentEvent.ResponseChunk>().first()
        assertTrue(bypassChunk.text.contains("肌肉记忆旁路执行完成"))
        assertCompleteBeforeNextIteration(events, 1, bypassChunk.text)
        // 最终轮照常 Complete 收尾（既有行为不回归）。
        assertEquals(final, events.filterIsInstance<AgentEvent.ResponseComplete>().last().fullText)
    }

    // ── 3. GOAL 续跑路径 ────────────────────────────────────────────────

    @Test
    fun `goal continue emits ResponseComplete before continuing loop`() = runBlocking {
        // 有状态验收器：首轮未达标 → Continue；次轮达成 → Stop（循环收敛）。
        var checks = 0
        val verifier = GoalVerifier { _, _ ->
            checks++
            if (checks == 1) GoalCheckResult(achieved = false, reason = "criteria not met")
            else GoalCheckResult(achieved = true, reason = "build green")
        }
        val coordinator = GoalModeCoordinator(verifier)
        coordinator.startGoal(
            GoalSpec(
                statement = "make the build green",
                acceptanceCriteria = "build passes",
                maxRounds = 5
            )
        )
        val round1 = "First pass: compiled the module and fixed two syntax errors in parser."
        val round2 = "Second pass: full build passes with zero warnings. Goal criteria satisfied."
        val client = RecordingLlmClient(listOf(round1, round2))
        val registry = FakeRegistry(listOf(FakeTool("t1")))
        val engine = ApexAgentEngine(
            llmClient = client,
            toolRegistry = registry,
            toolExecutor = DefaultToolExecutor(registry),
            config = AgentConfig(mode = AgentMode.GOAL, maxIterations = 5),
            goalCoordinator = coordinator
        )
        val events = engine.execute("make the build green").toList()

        // 第一轮 Continue（验收未达标），第二轮 Stop（达成）。
        assertEquals(2, client.calls.size)
        assertCompleteBeforeNextIteration(events, 1, round1)
        assertEquals(round2, events.filterIsInstance<AgentEvent.ResponseComplete>().last().fullText)
    }
}
