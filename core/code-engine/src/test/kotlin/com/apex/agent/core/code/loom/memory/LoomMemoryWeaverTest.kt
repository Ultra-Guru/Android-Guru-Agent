package com.apex.agent.core.code.loom.memory

import com.apex.agent.core.code.loom.LoomEngineConfig
import com.apex.agent.core.code.loom.loop.LoomFailureClassifier
import com.apex.agent.core.code.loom.loop.LoomFailureKind
import com.apex.agent.core.code.loom.loop.LoomHeuristicEstimator
import com.apex.agent.core.code.loom.loop.LoomLogger
import com.apex.agent.core.code.loom.loop.LoomModelClient
import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.LlmResponse
import com.apex.agent.core.llm.ToolDefinition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LoomMemoryWeaver 布面压缩测试：
 *
 * - 阈值内不压缩 / 历史不足无处折叠；
 * - LOOM_DIGEST 主路径（摘要轮替换旧轮头、事件数据完整）；
 * - SLIDING_WINDOW 兜底（digest 失败时保留既有摘要轮与近期轮）；
 * - digest 源渲染限幅。
 */
class LoomMemoryWeaverTest {

    /** 剧本式假模型客户端（chat 按 FIFO 出队；chatStream 不会被用到）。 */
    private class ScriptedClient(
        private val chatScript: MutableList<suspend () -> LlmResponse>
    ) : LoomModelClient {
        override fun chatStream(
            contextTag: String,
            messages: List<LlmMessage>,
            tools: List<ToolDefinition>,
            temperature: Float,
            maxTokens: Int
        ): Flow<com.apex.agent.core.llm.LlmStreamChunk> = flow {
            error("chatStream not expected in weaver tests")
        }

        override suspend fun chat(
            contextTag: String,
            messages: List<LlmMessage>,
            temperature: Float,
            maxTokens: Int
        ): LlmResponse = chatScript.removeFirst().let { it() }
    }

    private val logger = object : LoomLogger {
        override fun info(tag: String, message: String) = Unit
        override fun warn(tag: String, message: String) = Unit
        override fun error(tag: String, message: String, throwable: Throwable?) = Unit
    }

    /** 估算器：1 字符 = 1 token（方便精确造阈值）。 */
    private val estimator = LoomHeuristicEstimator

    private fun weaver(client: LoomModelClient, config: LoomEngineConfig) =
        LoomMemoryWeaver(config, client, estimator, logger)

    /** 造一段「N 轮 × 每轮 M 个 CJK 字符」的历史（CJK 1 字符 = 1 token——方便精确造阈值）。 */
    private fun history(turns: Int, charsPerTurn: Int): List<LlmMessage> =
        (0 until turns).map { i ->
            if (i % 2 == 0) {
                LlmMessage.User("中".repeat(charsPerTurn))
            } else {
                LlmMessage.Assistant("文".repeat(charsPerTurn))
            }
        }

    @Test
    fun `below threshold is a no-op`() = runTest {
        val config = LoomEngineConfig(maxContextTokens = 100_000)
        val w = weaver(ScriptedClient(mutableListOf()), config)
        assertNull(w.maybeCompact(history(turns = 4, charsPerTurn = 100)))
    }

    @Test
    fun `short history has nothing to fold`() = runTest {
        // 阈值极低（token 必超）但轮数 ≤ keepRecentTurns → 无处折叠
        val config = LoomEngineConfig(
            maxContextTokens = 1_000,
            compactAtRatio = 0.5f,
            keepRecentTurns = 8
        ).sanitized()
        val w = weaver(ScriptedClient(mutableListOf()), config)
        assertNull(w.maybeCompact(history(turns = 6, charsPerTurn = 10_000)))
    }

    @Test
    fun `digest path replaces head with digest turn`() = runTest {
        val config = LoomEngineConfig(
            maxContextTokens = 20_000,
            compactAtRatio = 0.5f, // 阈值 10000 token
            keepRecentTurns = 4
        ).sanitized()
        val client = ScriptedClient(
            mutableListOf({ LlmResponse(content = "已折叠：任务是修登录。") })
        )
        val w = weaver(client, config)
        val history = history(turns = 12, charsPerTurn = 2_000) // 约 24k token，远超阈值
        val outcome = w.maybeCompact(history)
        assertNotNull(outcome)
        outcome!!
        assertEquals("LOOM_DIGEST", outcome.result.strategy)
        assertEquals(5, outcome.newHistory.size) // 1 摘要轮 + 4 近期轮
        val digestTurn = outcome.newHistory.first() as LlmMessage.User
        assertTrue(digestTurn.content.contains("<loom-digest"))
        assertTrue(digestTurn.content.contains("已折叠：任务是修登录。"))
        assertEquals(12 - 5, outcome.result.turnsRemoved)
        assertTrue(outcome.result.afterTokens < outcome.result.beforeTokens)
    }

    @Test
    fun `sliding window fallback preserves prior digest`() = runTest {
        val config = LoomEngineConfig(
            maxContextTokens = 20_000,
            compactAtRatio = 0.5f,
            keepRecentTurns = 2
        ).sanitized()
        val client = ScriptedClient(
            mutableListOf({ error("digest 网络故障") })
        )
        val w = weaver(client, config)
        val priorDigest = LlmMessage.User(
            "<loom-digest rounds=\"3\">旧摘要正文</loom-digest>"
        )
        val history = listOf(priorDigest) + history(turns = 8, charsPerTurn = 2_000)
        val outcome = w.maybeCompact(history)
        assertNotNull(outcome)
        outcome!!
        assertEquals("SLIDING_WINDOW", outcome.result.strategy)
        // 保留：既有摘要轮 + 2 近期轮
        assertEquals(3, outcome.newHistory.size)
        assertTrue(w.isDigestTurn(outcome.newHistory.first()))
        assertEquals(
            "<loom-digest rounds=\"3\">旧摘要正文</loom-digest>",
            (outcome.newHistory.first() as LlmMessage.User).content
        )
    }

    @Test
    fun `blank digest response falls back to sliding window`() = runTest {
        val config = LoomEngineConfig(
            maxContextTokens = 20_000,
            compactAtRatio = 0.5f,
            keepRecentTurns = 2
        ).sanitized()
        val client = ScriptedClient(mutableListOf({ LlmResponse(content = "   ") }))
        val w = weaver(client, config)
        val outcome = w.maybeCompact(history(turns = 6, charsPerTurn = 2_000))
        assertNotNull(outcome)
        assertEquals("SLIDING_WINDOW", outcome!!.result.strategy)
    }

    @Test
    fun `digest source rendering caps each turn`() {
        val config = LoomEngineConfig()
        val client = ScriptedClient(mutableListOf())
        val w = weaver(client, config)
        val head = listOf(
            LlmMessage.User("x".repeat(5_000)),
            LlmMessage.Assistant("y".repeat(5_000))
        )
        val source = w.renderForDigest(head)
        // 每轮限幅 600 + 「…（后略）」标记
        assertTrue(source.contains("…（后略）"))
        assertTrue(source.length < 2_000)
        assertTrue(source.contains("第1轮·用户"))
        assertTrue(source.contains("第2轮·助手"))
    }

    @Test
    fun `digest turn detection only matches digest-wrapped user turns`() {
        val config = LoomEngineConfig()
        val w = weaver(ScriptedClient(mutableListOf()), config)
        assertTrue(w.isDigestTurn(LlmMessage.User("<loom-digest rounds=\"1\">x</loom-digest>")))
        assertFalse(w.isDigestTurn(LlmMessage.User("普通轮")))
        assertFalse(w.isDigestTurn(LlmMessage.Assistant("<loom-digest>x</loom-digest>")))
    }

    @Test
    fun `failure classifier port maps kinds`() {
        val c = LoomFailureClassifier { LoomFailureKind.TRANSIENT }
        assertEquals(LoomFailureKind.TRANSIENT, c.classify(IllegalStateException()))
    }
}
