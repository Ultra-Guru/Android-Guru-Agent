package com.apex.agent.core.engine.plan

import com.apex.agent.core.engine.AgentConfig
import com.apex.agent.core.engine.AgentEvent
import com.apex.agent.core.engine.AgentMode
import com.apex.agent.core.engine.ApexAgentEngine
import com.apex.agent.core.engine.ThinkingLevel
import com.apex.agent.core.engine.UserInput
import com.apex.agent.core.engine.orchestrator.FakeLlmClient
import com.apex.agent.core.engine.orchestrator.FakeToolExecutor
import com.apex.agent.core.engine.orchestrator.FakeToolRegistry
import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.ToolCall
import com.apex.agent.core.llm.ToolDefinition
import com.apex.agent.core.engine.ToolOutputEnvelope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #828 —— PLAN 模式最终反思请求必须携带执行记录摘要。
 *
 * 旧 Phase 5 只发 System + User(goal/步骤描述)，会话历史（含全部步骤真实
 * 执行的工具结果）不随请求下发 —— 模型被要求「State the final outcome and
 * key deliverables」却拿不到任何产出，只能凭空编造。现在 Phase 4 逐步骤
 * 收集切片、[PlanExecutionDigest] 蒸煮成有界摘要注入反思 User 消息。
 *
 * 断言口径：FakeLlmClient 的 callLog 里最后一次请求（= Phase 5 反思）的
 * User 消息包含「## Execution records」段落与真实工具结果片段。
 *
 * 纪律：runTest（协程级测试构建器，#276 棘轮）+ 虚拟时间轮询（不线程睡眠）。
 */
class PlanReflectionContextTest {

    private val planJson = """
        {"goal":"g","steps":[
            {"index":0,"description":"run probe"},
            {"index":1,"description":"wrap up"}],
         "estimated_tool_calls":1,"risk_level":"low","reasoning":"r"}
    """.trimIndent()

    /**
     * 等 deferred 注册后再提交（#340 注册先行后理论上恒非空，保留防御轮询；
     * runTest 虚拟时间下 delay 瞬时推进，不占墙钟）。
     */
    private suspend fun awaitPlanConfirmationReady(engine: ApexAgentEngine) {
        val deadline = 10_000L
        val start = System.currentTimeMillis()
        while (engine.planConfirmationDeferred == null && System.currentTimeMillis() - start < deadline) {
            delay(20)
        }
    }

    @Test
    fun `reflection request carries execution records digest`() = runTest {
        val fake = FakeLlmClient(
            listOf(
                // Phase 1：计划生成
                FakeLlmClient.ScriptedResponse.Ok(content = planJson),
                // Phase 4 步骤 1：先调工具再收尾
                FakeLlmClient.ScriptedResponse.Ok(
                    toolCalls = listOf(ToolCall("call_1", "Read", "{}"))
                ),
                FakeLlmClient.ScriptedResponse.Ok(content = "probe finished ok"),
                // Phase 4 步骤 2：纯文本收尾
                FakeLlmClient.ScriptedResponse.Ok(content = "wrap up note"),
                // Phase 5：反思
                FakeLlmClient.ScriptedResponse.Ok(content = "reflection")
            )
        )
        val executor = FakeToolExecutor()
            .apply { registerSuccess("Read", "build-result-payload 42") }
        val engine = ApexAgentEngine(
            llmClient = fake,
            toolRegistry = FakeToolRegistry(
                listOf(ToolDefinition("Read", "probe tool", """{"type":"object"}"""))
            ),
            toolExecutor = executor,
            config = AgentConfig(mode = AgentMode.PLAN, thinkingLevel = ThinkingLevel.NONE)
        )
        val job = launch(Dispatchers.Unconfined) {
            engine.execute(UserInput.text("计划任务")).collect { event ->
                if (event is AgentEvent.PlanAwaitingConfirmation) {
                    launch(Dispatchers.Default) {
                        awaitPlanConfirmationReady(engine)
                        engine.submitPlanConfirmation(true)
                    }
                }
            }
        }
        job.join()

        // 最后一次 chatStream = Phase 5 反思请求（System + User，无历史）。
        val reflection = fake.callLog.last()
        assertTrue(
            "反思请求应为 System + User 两条",
            reflection.first.size == 2 &&
                reflection.first[0] is LlmMessage.System &&
                reflection.first[1] is LlmMessage.User
        )
        val userPrompt = (reflection.first[1] as LlmMessage.User).content

        // 执行记录段落存在，且携带真实工具结果片段（不再凭空编造交付结果）。
        assertTrue("应包含执行记录段落: $userPrompt", userPrompt.contains("## Execution records"))
        assertTrue(
            "工具结果 digest 行应含工具名与产出片段",
            userPrompt.contains("step 1: Read → build-result-payload 42")
        )
        assertTrue(
            "步骤 1 助手收尾文本入记录",
            userPrompt.contains("step 1: assistant → probe finished ok")
        )
        assertTrue(
            "步骤 2 助手收尾文本入记录",
            userPrompt.contains("step 2: assistant → wrap up note")
        )
    }

    // ── PlanExecutionDigest 纯函数单测（#828 的有界性契约）───────────

    @Test
    fun `stepRecords maps tool names from paired assistant and unwraps envelope`() {
        val history = listOf(
            // 工具调用轮：正文为空（引擎实际形态），toolCalls 供配对取名
            LlmMessage.Assistant("", listOf(ToolCall("c1", "Read", "{}"))),
            // #831 后历史中的工具结果带定界信封 —— digest 取正文而非信封
            LlmMessage.ToolResult("c1", ToolOutputEnvelope.wrap("Read", "c1", "file body")),
            LlmMessage.Assistant("done text"),
            // 样板消息不进 digest
            LlmMessage.User("step prompt"),
            LlmMessage.System("lock note"),
            // 孤立结果（历史回放/畸形输入）退化为 unknown-tool，不丢记录
            LlmMessage.ToolResult("c9", "orphan raw output")
        )
        val lines = PlanExecutionDigest.stepRecords(3, history)
        assertEquals(
            listOf(
                "step 3: Read → file body",
                "step 3: assistant → done text",
                "step 3: unknown-tool → orphan raw output"
            ),
            lines
        )
    }

    @Test
    fun `boundedDigest caps total size and keeps newest records`() {
        // 每行 ~250 字符（200 正文摘录 + 前缀），60 行 ≈ 15KB > 8KB 上限。
        val lines = (1..60).map { "step 1: Read → rec-$it ${"x".repeat(230)}" }
        val digest = PlanExecutionDigest.boundedDigest(lines)
        assertTrue(
            "总量必须封顶：${digest.length} > ${PlanExecutionDigest.DIGEST_CHAR_CAP}",
            digest.length <= PlanExecutionDigest.DIGEST_CHAR_CAP
        )
        // 超预算保最新记录（终局反思最关心最后几步），丢弃最旧并告示。
        assertTrue("最新记录保留", digest.contains("rec-60"))
        assertFalse("最旧记录被丢弃", digest.contains("rec-1 ") || digest.contains("rec-1\n"))
        assertTrue("丢弃有告示", digest.contains("omitted"))

        // 未超预算：逐字全量。
        val small = PlanExecutionDigest.boundedDigest(listOf("step 1: Read → ok"))
        assertEquals("step 1: Read → ok", small)
    }
}
