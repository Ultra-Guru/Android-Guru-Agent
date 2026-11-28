package com.apex.agent.core.engine

import com.apex.agent.core.engine.compression.ContextCompressor
import com.apex.agent.core.engine.compression.CompressionReport
import com.apex.agent.core.engine.compression.CompressionStrategy
import com.apex.agent.core.engine.compression.TokenEstimator
import com.apex.agent.core.llm.ImageContent
import com.apex.agent.core.llm.LlmClient
import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.LlmResponse
import com.apex.agent.core.llm.LlmStreamChunk
import com.apex.agent.core.llm.ToolCall
import com.apex.agent.core.llm.ToolDefinition
import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.DefaultToolExecutor
import com.apex.agent.core.tools.ToolRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #826 / #827 回归 —— 上下文预算真口径：
 *
 * 1. TokenEstimator 对图片计成本（旧实现恒 0 → 压缩门与仪表盘失明）；
 * 2. 首轮已消费的图片不再逐轮重传（第 2 轮请求体不含 base64，改文本占位）；
 * 3. 压缩门按组装态口径计量（history + system prompt + tools 载荷），
 *    固定开销超阈值即触发压缩（旧实现只看 history → 永不触发 → 400 兜底）；
 * 4. PLAN 提示词工具清单收敛到 ToolRequestBudget 同口径（228 工具全量
 *    注入 ≈ 30-40KB 的旁路被关死）。
 */
class EngineContextBudgetTest {

    // ── fakes（与 EngineToolSystemV4Test 同款骨架）──────────────────────

    private class RecordedCall(val messages: List<LlmMessage>)

    private class RecordingLlmClient(
        private val script: List<Script>
    ) : LlmClient {
        sealed class Script {
            data class Respond(val text: String) : Script()
            data class CallTool(val name: String, val args: String) : Script()
        }

        val calls = mutableListOf<RecordedCall>()
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
            toolChoice: com.apex.agent.core.llm.ToolChoiceSpec?
        ): Flow<LlmStreamChunk> = flow {
            calls += RecordedCall(messages)
            val step = script.getOrElse(index++) { Script.Respond("(script exhausted)") }
            when (step) {
                is Script.Respond -> emit(LlmStreamChunk(content = step.text, isFinish = true))
                is Script.CallTool -> emit(
                    LlmStreamChunk(
                        toolCalls = listOf(ToolCall("call_$index", step.name, step.args)),
                        isFinish = true
                    )
                )
            }
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

    /** 记录调用次数、不改历史的假压缩器（触发断言只看是否被调）。 */
    private class RecordingCompressor : ContextCompressor {
        var invocations = 0
        override fun needsCompression(
            history: List<LlmMessage>,
            maxTokens: Int,
            threshold: Float
        ): Boolean = false

        override suspend fun compress(
            history: MutableList<LlmMessage>,
            preserveRecent: Int
        ): CompressionReport {
            invocations++
            return CompressionReport(
                beforeTokens = 10_000, afterTokens = 1_000,
                strategy = CompressionStrategy.SLIDING_WINDOW,
                summary = "test summary",
                messagesRemoved = 1, messagesTruncated = 0
            )
        }
    }

    private fun newEngine(
        client: RecordingLlmClient,
        compressor: ContextCompressor? = null
    ): ApexAgentEngine {
        val registry = FakeRegistry(listOf(FakeTool("t1")))
        return ApexAgentEngine(
            llmClient = client,
            toolRegistry = registry,
            toolExecutor = DefaultToolExecutor(registry),
            config = AgentConfig(mode = AgentMode.BUILD, maxIterations = 5),
            contextCompressor = compressor
        )
    }

    // ── 1. 图片 token 计费（#827）───────────────────────────────────────

    @Test
    fun `token estimator counts image cost instead of zero`() {
        val bare = TokenEstimator.estimateMessage(
            LlmMessage.User(content = "what is in this image?")
        )
        val withImage = TokenEstimator.estimateMessage(
            LlmMessage.User(
                content = "what is in this image?",
                images = listOf(
                    ImageContent(base64Data = "A".repeat(40_960), mimeType = "image/png")
                )
            )
        )
        // 固定成本 1000 + 体量分量 40960/4096 = 10 → 至少 1000 的整数倍量级。
        assertTrue("image must cost tokens (was: bare=$bare with=$withImage)", withImage > bare + 900)

        val withUrl = TokenEstimator.estimateMessage(
            LlmMessage.User(
                content = "describe",
                images = listOf(
                    ImageContent(base64Data = "", url = "https://example.com/a.jpg")
                )
            )
        )
        assertTrue(withUrl >= 1000)

        // 历史整体估算也必须看到图片（压缩门数据源）。
        val history = listOf(
            LlmMessage.System("sys"),
            LlmMessage.User("look", images = listOf(ImageContent(base64Data = "B".repeat(100_000))))
        )
        assertTrue(TokenEstimator.estimateHistory(history) > 1000)
    }

    // ── 2. 图片生命周期：第 2 轮起不再重传 base64（#827）──────────────

    @Test
    fun `consumed images are stripped from second round request`() = runBlocking {
        val client = RecordingLlmClient(
            listOf(
                RecordingLlmClient.Script.CallTool("t1", "{}"),
                RecordingLlmClient.Script.Respond("The screenshot shows a login form; done.")
            )
        )
        val engine = newEngine(client)
        val events = engine.execute(
            UserInput(text = "analyze this screenshot", images = listOf(ImageContent(base64Data = "IMGDATA")))
        ).toList()

        assertEquals(2, client.calls.size)
        // 第 1 轮：原图必须发给模型（vision 消费）。
        val round1Users = client.calls[0].messages.filterIsInstance<LlmMessage.User>()
        assertTrue("round 1 must carry the image", round1Users.any { it.images.isNotEmpty() })
        // 第 2 轮：请求体不含任何 base64；带占位说明。
        val round2Users = client.calls[1].messages.filterIsInstance<LlmMessage.User>()
        assertTrue("round 2 must not re-send base64 images", round2Users.all { it.images.isEmpty() })
        assertTrue(
            "round 2 user message should carry the omission placeholder",
            round2Users.any { it.content.contains("already shown to you earlier") }
        )
        // 内存历史同步剥离（不再常驻 base64）。
        assertTrue(engine.conversationHistory.filterIsInstance<LlmMessage.User>().all { it.images.isEmpty() })
    }

    // ── 3. 压缩门组装态口径（#826）────────────────────────────────────

    @Test
    fun `compression gate counts system prompt overhead not just history`() = runBlocking {
        val client = RecordingLlmClient(
            listOf(
                RecordingLlmClient.Script.CallTool("t1", "{}"),
                RecordingLlmClient.Script.Respond("done")
            )
        )
        val compressor = RecordingCompressor()
        val registry = FakeRegistry(listOf(FakeTool("t1")))
        val engine = ApexAgentEngine(
            llmClient = client,
            toolRegistry = registry,
            toolExecutor = DefaultToolExecutor(registry),
            // 6K 窗口 + 60KB 全局规则（system prompt ≈ 15K tokens 固定开销）：
            // history 本身只有几百 token —— 旧口径永不触发，新口径必触发。
            config = AgentConfig(mode = AgentMode.BUILD, maxIterations = 5, maxContextTokens = 6_000),
            contextCompressor = compressor
        )
        engine.updateGlobalRules("Global rule batch. ".repeat(3_000).trim())

        val events = engine.execute("small task").toList()

        assertTrue(
            "assembled-state overhead must trigger compression " +
                "(invocations=${compressor.invocations}, events=${events.map { it::class.simpleName }})",
            compressor.invocations >= 1
        )
        assertTrue(events.any { it is AgentEvent.ContextCompressed })
        assertTrue(
            "overhead must be measured at assembly point",
            engine.lastRequestOverheadTokens > 5_000
        )
    }

    // ── 4. PLAN 提示词工具清单预算（#826）──────────────────────────────

    @Test
    fun `plan prompt tool list is bounded by request budget`() {
        val tools = (1..300).map { i ->
            FakeTool("tool_$i").let { t ->
                object : AgentTool by t {
                    override val description: String =
                        "Tool number $i does something reasonably descriptive for planning. " +
                            "Line ${i}x filler to simulate real catalog descriptions."
                }
            }
        }
        val prompt = EnginePrompts.buildPlanPrompt("build the app", tools)
        val listed = prompt.lineSequence().count { it.startsWith("- tool_") }
        assertTrue(
            "listed tools must respect MAX_TOOLS budget (was $listed)",
            listed <= com.apex.agent.core.tools.catalog.ToolRequestBudget.MAX_TOOLS
        )
        assertTrue("omission notice must be present", prompt.contains("more tools omitted"))
        // 任务正文与结构骨架完整保留。
        assertTrue(prompt.contains("Task: build the app"))
        assertTrue(prompt.contains("\"estimated_tool_calls\""))
    }
}
