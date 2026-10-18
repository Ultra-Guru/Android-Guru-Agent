package com.apex.agent.core.code.loom.swarm

import com.apex.agent.core.code.loom.LoomEngineConfig
import com.apex.agent.core.code.loom.LoomThreadPhase
import com.apex.agent.core.code.loom.loop.LoomEnvironmentInfo
import com.apex.agent.core.code.loom.loop.LoomFailureClassifier
import com.apex.agent.core.code.loom.loop.LoomFailureKind
import com.apex.agent.core.code.loom.loop.LoomLogger
import com.apex.agent.core.code.loom.loop.LoomModelClient
import com.apex.agent.core.code.loom.loop.LoomToolRunner
import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.LlmResponse
import com.apex.agent.core.llm.LlmStreamChunk
import com.apex.agent.core.llm.ToolDefinition
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LoomSwarmHub 子线程群测试（剧本式模型客户端）：
 *
 * - 起飞 + 首飞（send 收口返回最后回复 + 状态推进）；
 * - 二次推进（连续 send 会话延续）；
 * - 并发上限（IDLE/WEAVING 占额）与退役释放；
 * - observe 摘录 / status 快照 / stop；
 * - 模型异常 → FAILED 收场（不击穿调用方）。
 */
class LoomSwarmHubTest {

    /** 剧本客户端：每条子线程每飞出队一段文本。 */
    private class ScriptedClient : LoomModelClient {
        val rounds = ArrayDeque<String>()

        override fun chatStream(
            contextTag: String,
            messages: List<LlmMessage>,
            tools: List<ToolDefinition>,
            temperature: Float,
            maxTokens: Int
        ): Flow<LlmStreamChunk> = flow {
            val text = rounds.removeFirst()
            emit(LlmStreamChunk(content = text))
            emit(LlmStreamChunk(isFinish = true, finishReason = "stop"))
        }

        override suspend fun chat(
            contextTag: String,
            messages: List<LlmMessage>,
            temperature: Float,
            maxTokens: Int
        ): LlmResponse = LlmResponse(content = "摘要")
    }

    private class NoopRunner : LoomToolRunner {
        override fun executeStream(toolId: String, arguments: String) = flow {
            emit(com.apex.agent.core.tools.ToolStreamEvent.Complete("noop"))
        }
    }

    private val logger = object : LoomLogger {
        override fun info(tag: String, message: String) = Unit
        override fun warn(tag: String, message: String) = Unit
        override fun error(tag: String, message: String, throwable: Throwable?) = Unit
    }

    private fun hub(
        client: ScriptedClient,
        config: LoomEngineConfig = LoomEngineConfig(threadConcurrencyMax = 2)
    ): LoomSwarmHub = LoomSwarmHub(
        config = config,
        modelClient = client,
        parentToolSource = object : com.apex.agent.core.code.loom.loop.LoomToolSource {
            override fun definitions(): List<ToolDefinition> = listOf(
                ToolDefinition("CodeRead", "读", "{}"),
                ToolDefinition("loom_thread_start", "子线程", "{}")
            )
        },
        toolRunner = NoopRunner(),
        permissionSource = { com.apex.agent.core.code.loom.permission.LoomPermissionSnapshot() },
        failureClassifier = LoomFailureClassifier { LoomFailureKind.UNCLASSIFIED },
        logger = logger,
        environmentSource = { LoomEnvironmentInfo(platformText = "测试机") },
        workspaceRootProvider = { null }
    )

    @Test
    fun `spawn sends first task and settles with reply`() = runTest {
        val client = ScriptedClient()
        client.rounds.addLast("调研结论：方案A 最优。")
        val h = hub(client)
        val reply = h.send(h.spawn("调研"), "帮我调研方案")
        assertTrue(reply.contains("方案A"))
        val snap = h.status(null).single()
        assertEquals(LoomThreadPhase.SETTLED, snap.phase)
        assertEquals(1, snap.rounds)
        assertTrue(snap.lastReply.contains("方案A"))
    }

    @Test
    fun `second send continues the same session`() = runTest {
        val client = ScriptedClient()
        client.rounds.addLast("第一飞完成。")
        client.rounds.addLast("第二飞：基于上文继续。")
        val h = hub(client)
        val id = h.spawn("连飞")
        h.send(id, "第一段")
        val reply = h.send(id, "继续")
        assertTrue(reply.contains("第二飞"))
        assertEquals(LoomThreadPhase.SETTLED, h.status(id).single().phase)
    }

    @Test
    fun `busy thread rejects concurrent send`() = runTest {
        // 长飞的子线程：模型输出前挂起——制造 WEAVING 窗口（真实 Default 线程 + 虚拟时钟不交叉：
        // 用真实挂起依赖外部 dispose 收口）
        val client = object : LoomModelClient {
            override fun chatStream(
                contextTag: String,
                messages: List<LlmMessage>,
                tools: List<ToolDefinition>,
                temperature: Float,
                maxTokens: Int
            ): Flow<LlmStreamChunk> = flow {
                kotlinx.coroutines.delay(60_000)
                emit(LlmStreamChunk(content = "慢工"))
                emit(LlmStreamChunk(isFinish = true, finishReason = "stop"))
            }

            override suspend fun chat(
                contextTag: String,
                messages: List<LlmMessage>,
                temperature: Float,
                maxTokens: Int
            ): LlmResponse = LlmResponse(content = "摘要")
        }
        val hub2 = LoomSwarmHub(
            config = LoomEngineConfig(),
            modelClient = client,
            parentToolSource = object : com.apex.agent.core.code.loom.loop.LoomToolSource {
                override fun definitions() = emptyList<ToolDefinition>()
            },
            toolRunner = NoopRunner(),
            permissionSource = { com.apex.agent.core.code.loom.permission.LoomPermissionSnapshot() },
            failureClassifier = LoomFailureClassifier { LoomFailureKind.UNCLASSIFIED },
            logger = logger,
            environmentSource = { LoomEnvironmentInfo(platformText = "测试机") },
            workspaceRootProvider = { null }
        )
        val id = hub2.spawn("慢")
        val first = async { hub2.send(id, "慢活") }
        runCurrent() // 推进 async 到 send 同步段（WEAVING 置位 + await 挂起）
        try {
            hub2.send(id, "插队")
            org.junit.Assert.fail("应抛忙碌异常")
        } catch (e: LoomSwarmHub.SwarmBusyException) {
            // 预期：WEAVING 状态同步置位，第二 send 被拒
        } finally {
            first.cancel()
            hub2.dispose()
        }
    }

    @Test
    fun `stop retires thread and frees slot`() = runTest {
        val client = ScriptedClient()
        val h = hub(client, LoomEngineConfig(threadConcurrencyMax = 1))
        val id = h.spawn("独占")
        assertEquals(0, h.remainingSlots())
        assertTrue(h.stop(id))
        assertEquals(1, h.remainingSlots())
        assertTrue(h.status(id).isEmpty()) // 已退役
    }

    @Test
    fun `observe returns transcript excerpt`() = runTest {
        val client = ScriptedClient()
        client.rounds.addLast("子线程第一答。")
        val h = hub(client)
        val id = h.spawn("观测")
        h.send(id, "任务")
        val excerpt = h.observe(id)
        assertTrue(excerpt.contains("[用户]"))
        assertTrue(excerpt.contains("[助手]"))
    }

    @Test
    fun `model failure marks thread failed without throwing`() = runTest {
        val client = object : LoomModelClient {
            override fun chatStream(
                contextTag: String,
                messages: List<LlmMessage>,
                tools: List<ToolDefinition>,
                temperature: Float,
                maxTokens: Int
            ): Flow<LlmStreamChunk> = flow { error("模型炸了") }

            override suspend fun chat(
                contextTag: String,
                messages: List<LlmMessage>,
                temperature: Float,
                maxTokens: Int
            ): LlmResponse = LlmResponse(content = "摘要")
        }
        val h = LoomSwarmHub(
            config = LoomEngineConfig(),
            modelClient = client,
            parentToolSource = object : com.apex.agent.core.code.loom.loop.LoomToolSource {
                override fun definitions(): List<ToolDefinition> = emptyList()
            },
            toolRunner = NoopRunner(),
            permissionSource = { com.apex.agent.core.code.loom.permission.LoomPermissionSnapshot() },
            failureClassifier = LoomFailureClassifier { LoomFailureKind.UNCLASSIFIED },
            logger = logger,
            environmentSource = { LoomEnvironmentInfo(platformText = "测试机") },
            workspaceRootProvider = { null }
        )
        val reply = h.send(h.spawn("炸机"), "任务")
        assertTrue(reply.contains("失败"))
        assertEquals(LoomThreadPhase.FAILED, h.status(null).single().phase)
    }

    @Test
    fun `child tool surface excludes loom domain tools`() = runTest {
        // 经由 spawn 间接验证：子线程的模型请求收到的 tools 面不含 loom_*
        val seenTools = mutableListOf<List<ToolDefinition>>()
        val client = object : LoomModelClient {
            override fun chatStream(
                contextTag: String,
                messages: List<LlmMessage>,
                tools: List<ToolDefinition>,
                temperature: Float,
                maxTokens: Int
            ): Flow<LlmStreamChunk> = flow {
                seenTools.add(tools)
                emit(LlmStreamChunk(content = "收到"))
                emit(LlmStreamChunk(isFinish = true, finishReason = "stop"))
            }

            override suspend fun chat(
                contextTag: String,
                messages: List<LlmMessage>,
                temperature: Float,
                maxTokens: Int
            ): LlmResponse = LlmResponse(content = "摘要")
        }
        val h = LoomSwarmHub(
            config = LoomEngineConfig(),
            modelClient = client,
            parentToolSource = object : com.apex.agent.core.code.loom.loop.LoomToolSource {
                override fun definitions(): List<ToolDefinition> = listOf(
                    ToolDefinition("CodeRead", "读", "{}"),
                    ToolDefinition("loom_thread_start", "子线程", "{}")
                )
            },
            toolRunner = NoopRunner(),
            permissionSource = { com.apex.agent.core.code.loom.permission.LoomPermissionSnapshot() },
            failureClassifier = LoomFailureClassifier { LoomFailureKind.UNCLASSIFIED },
            logger = logger,
            environmentSource = { LoomEnvironmentInfo(platformText = "测试机") },
            workspaceRootProvider = { null }
        )
        h.send(h.spawn("过滤"), "任务")
        assertTrue(seenTools.isNotEmpty())
        assertTrue(seenTools.first().none { it.name.startsWith("loom_") })
        assertTrue(seenTools.first().any { it.name == "CodeRead" })
    }
}

/** 条件求值器测试。 */
class LoomConditionTest {

    @Test
    fun `equality and inequality are case-insensitive`() {
        assertTrue(LoomCondition.evaluate("status == ok", mapOf("status" to "OK")))
        assertTrue(LoomCondition.evaluate("status != error", mapOf("status" to "ok")))
        assertFalse(LoomCondition.evaluate("status == ok", mapOf("status" to "error")))
    }

    @Test
    fun `contains checks substring`() {
        assertTrue(LoomCondition.evaluate("reply contains ERROR", mapOf("reply" to "build ERROR here")))
        assertFalse(LoomCondition.evaluate("reply contains FATAL", mapOf("reply" to "only errors")))
    }

    @Test
    fun `numeric comparison when both sides parse`() {
        assertTrue(LoomCondition.evaluate("rounds >= 3", mapOf("rounds" to "5")))
        assertFalse(LoomCondition.evaluate("rounds < 3", mapOf("rounds" to "5")))
        assertTrue(LoomCondition.evaluate("rounds > 2.5", mapOf("rounds" to "3")))
    }

    @Test
    fun `lexicographic fallback when not numeric`() {
        // 非数字 → 字典序（"b" > "a"）
        assertTrue(LoomCondition.evaluate("name > aaa", mapOf("name" to "b")))
    }

    @Test
    fun `exists checks presence and non-blank`() {
        assertTrue(LoomCondition.evaluate("path exists", mapOf("path" to "/a/b")))
        assertFalse(LoomCondition.evaluate("path exists", mapOf("path" to "")))
        assertFalse(LoomCondition.evaluate("path exists", emptyMap()))
    }

    @Test
    fun `missing field evaluates false even for ne`() {
        // 字段缺席 → false（含 != 语义：宁误判不满足）
        assertFalse(LoomCondition.evaluate("missing != x", mapOf("other" to "y")))
    }

    @Test
    fun `malformed expressions evaluate false and fail validity`() {
        val bad = listOf("", "   ", "onlyone", "a == ", "a weirdop b", "a == b c")
        for (expr in bad) {
            assertFalse("坏表达式应判 false：$expr", LoomCondition.evaluate(expr, mapOf("a" to "b")))
            assertFalse("坏表达式应预检不过：$expr", LoomCondition.isValid(expr))
        }
    }

    @Test
    fun `valid expressions pass validity`() {
        assertTrue(LoomCondition.isValid("status == ok"))
        assertTrue(LoomCondition.isValid("path exists"))
        assertTrue(LoomCondition.isValid("rounds >= 3"))
    }
}
