package com.apex.agent.core.engine

import com.apex.agent.core.engine.compression.CompressionReport
import com.apex.agent.core.engine.compression.CompressionStrategy
import com.apex.agent.core.engine.compression.ContextCompressor
import com.apex.agent.core.engine.compression.TokenEstimator
import com.apex.agent.core.engine.orchestrator.FakeLlmClient
import com.apex.agent.core.engine.orchestrator.FakeToolExecutor
import com.apex.agent.core.engine.orchestrator.FakeToolRegistry
import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.tools.catalog.ToolRequestBudget
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #826 — 压缩门 headroom 测试（system prompt / 工具 schema 载荷计入门限）。
 *
 * 旧公式只比「估算历史 vs 阈值」：48KB 工具载荷 + 数 KB system prompt 从未
 * 计入，8K 窗口模型的历史可用空间实为负数，压缩门却永远不触发。锁定：
 * 1. 历史未达阈值但 history + headroom 超阈值 → 压缩触发；
 * 2. headroom 钳到阈值 60%（小窗防「恒压缩」死循环）；
 * 3. 首轮无快照 → 保守常量；上一轮快照 / 计划接管；
 * 4. execute() 真实调用链（小窗口 + 默认 headroom）触发压缩事件。
 */
class EngineCompressionGateTest {

    /** 最小假压缩器：砍平历史并回报 SLIDING_WINDOW。 */
    private class FakeCompressor : ContextCompressor {
        var calls = 0
        private set

        override fun needsCompression(
            history: List<LlmMessage>,
            maxTokens: Int,
            threshold: Float
        ): Boolean = true

        override suspend fun compress(
            history: MutableList<LlmMessage>,
            preserveRecent: Int
        ): CompressionReport {
            calls++
            history.clear()
            history.add(LlmMessage.System("[COMPRESSED]"))
            return CompressionReport(
                beforeTokens = 8000, afterTokens = 100,
                strategy = CompressionStrategy.SLIDING_WINDOW,
                summary = "test", messagesRemoved = 10, messagesTruncated = 0
            )
        }
    }

    /** threshold = 8000（10000 x 0.8；无画像直调时倍率 1.0）。 */
    private fun newEngine(compressor: ContextCompressor): ApexAgentEngine =
        ApexAgentEngine(
            llmClient = FakeLlmClient(responses = emptyList()),
            toolRegistry = FakeToolRegistry(),
            toolExecutor = FakeToolExecutor(),
            config = AgentConfig(
                mode = AgentMode.BUILD,
                maxContextTokens = 10_000,
                compressionThreshold = 0.8f
            ),
            contextCompressor = compressor
        )

    /** ASCII 文本 User 消息（chars/4 token + 每条 4 开销）。 */
    private fun userOfTokens(tokens: Int) = LlmMessage.User("a".repeat((tokens - 4) * 4))

    private suspend fun runGate(
        engine: ApexAgentEngine,
        systemPromptTokens: Long,
        toolsBudgetTokens: Long
    ): List<AgentEvent> {
        val events = mutableListOf<AgentEvent>()
        engine.maybeCompressContext(
            emit = { events.add(it) },
            systemPromptTokens = systemPromptTokens,
            toolsBudgetTokens = toolsBudgetTokens
        )
        return events
    }

    // ═══ 1. 纯函数：headroom 钳制 ═══

    @Test
    fun `clamp keeps raw headroom at or below the 60 percent cap`() {
        assertEquals(100L, clampCompressionHeadroom(100, thresholdTokens = 8000))
        assertEquals(4800L, clampCompressionHeadroom(4800, thresholdTokens = 8000))
        // 巨额 headroom（极小窗口的真实形态）钳到 60%。
        assertEquals(4800L, clampCompressionHeadroom(100_000, thresholdTokens = 8000))
        // 负输入折叠为 0；退化阈值（0）headroom 归 0。
        assertEquals(0L, clampCompressionHeadroom(-5, thresholdTokens = 8000))
        assertEquals(0L, clampCompressionHeadroom(100, thresholdTokens = 0))
    }

    // ═══ 2. 门限行为：history + headroom > threshold 触发 ═══

    @Test
    fun `history under threshold alone does not compress`() = runBlocking {
        val compressor = FakeCompressor()
        val engine = newEngine(compressor)
        engine.conversationHistory.addAll(List(5) { userOfTokens(1000) })
        // 5000 tokens < 8000 阈值（旧公式行为不变：无 headroom 不触发）。
        val events = runGate(engine, systemPromptTokens = 0, toolsBudgetTokens = 0)
        assertTrue("no compression expected, got $events", events.isEmpty())
        assertEquals(0, compressor.calls)
    }

    @Test
    fun `history under threshold but history plus headroom over threshold compresses`() = runBlocking {
        val compressor = FakeCompressor()
        val engine = newEngine(compressor)
        engine.conversationHistory.addAll(List(5) { userOfTokens(1000) })
        // 5000 + 4000 headroom = 9000 > 8000 → 触发（旧公式永不触发的盲区）。
        val events = runGate(engine, systemPromptTokens = 2_000, toolsBudgetTokens = 2_000)
        assertTrue(
            "compression must fire with headroom accounted, got $events",
            events.any { it is AgentEvent.ContextCompressed }
        )
        assertEquals(1, compressor.calls)
    }

    @Test
    fun `huge headroom clamped so 30 percent history does not compress`() = runBlocking {
        val compressor = FakeCompressor()
        val engine = newEngine(compressor)
        engine.conversationHistory.addAll(List(2) { userOfTokens(1200) })
        // 2400（30% 窗口）+ 钳后 4800 = 7200 ≤ 8000 → 不触发（不钳制则恒触发死循环）。
        val events = runGate(engine, systemPromptTokens = 50_000, toolsBudgetTokens = 50_000)
        assertTrue("clamped headroom must not loop-compress, got $events", events.isEmpty())
        assertEquals(0, compressor.calls)
    }

    @Test
    fun `clamped headroom still fires above the 40 percent boundary`() = runBlocking {
        val compressor = FakeCompressor()
        val engine = newEngine(compressor)
        engine.conversationHistory.addAll(List(4) { userOfTokens(1200) })
        // 4800 + 4800 = 9600 > 8000 → 触发（钳制只防死循环，不吞真实超限）。
        val events = runGate(engine, systemPromptTokens = 50_000, toolsBudgetTokens = 50_000)
        assertTrue(events.any { it is AgentEvent.ContextCompressed })
        assertEquals(1, compressor.calls)
    }

    // ═══ 3. headroom 输入：首轮保守常量 → 上一轮快照接管 ═══

    @Test
    fun `gate inputs fall back to conservative constants then track last round`() = runBlocking {
        val engine = newEngine(FakeCompressor())
        // 首轮（无快照 / 无计划）：保守常量。
        assertEquals(
            DEFAULT_SYSTEM_PROMPT_TOKENS to DEFAULT_TOOLS_PAYLOAD_TOKENS,
            engine.compressionGateInputs()
        )
        // 上一轮系统提示词快照 + 上一轮工具计划接管（门在本轮构建前运行）。
        engine.buildMessages()
        engine.currentToolPlan = ToolRequestBudget.RequestToolPlan(
            tools = emptyList(),
            providerNameToId = emptyMap(),
            visibleRegistryIds = emptySet(),
            totalBytes = 4_800,
            droppedByBudget = emptyList()
        )
        val snapshot = engine.promptPipeline.lastSnapshot()
        assertTrue("buildMessages must record a prompt snapshot", snapshot != null)
        val (sysTokens, toolsTokens) = engine.compressionGateInputs()
        assertEquals(
            TokenEstimator.estimateText(snapshot!!.systemPrompt).toLong(),
            sysTokens
        )
        assertTrue(sysTokens > 0)
        assertEquals(1_200L, toolsTokens)
    }

    // ═══ 4. execute() 真实调用链（小窗口 + 默认 headroom）═══

    @Test
    fun `small window model triggers compression through execute`() = runBlocking {
        val compressor = FakeCompressor()
        val engine = ApexAgentEngine(
            llmClient = FakeLlmClient(
                responses = listOf(FakeLlmClient.ScriptedResponse.Ok(content = "done"))
            ),
            toolRegistry = FakeToolRegistry(),
            toolExecutor = FakeToolExecutor(),
            config = AgentConfig(mode = AgentMode.BUILD, maxContextTokens = 1_000),
            contextCompressor = compressor
        )
        // 历史 ~379 token，阈值 800；headroom 默认常量（4096+12000）钳到 480
        // → 859 > 800 触发（旧公式 379 ≤ 800 永不触发 —— 8K 窗口模型盲区复现）。
        val events = engine.execute(UserInput.text("a".repeat(1500))).toList()
        assertTrue(
            "execute must emit ContextCompressed on small windows, got ${
                events.map { it::class.simpleName }
            }",
            events.any { it is AgentEvent.ContextCompressed }
        )
        assertEquals(1, compressor.calls)
    }
}
