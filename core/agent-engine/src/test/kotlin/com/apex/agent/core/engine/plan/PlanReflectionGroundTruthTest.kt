package com.apex.agent.core.engine.plan

import com.apex.agent.core.engine.AgentConfig
import com.apex.agent.core.engine.AgentEvent
import com.apex.agent.core.engine.AgentMode
import com.apex.agent.core.engine.ApexAgentEngine
import com.apex.agent.core.engine.EngineResilienceGuard
import com.apex.agent.core.engine.EngineResiliencePolicy
import com.apex.agent.core.engine.ThinkingLevel
import com.apex.agent.core.engine.UserInput
import com.apex.agent.core.engine.orchestrator.FakeLlmClient
import com.apex.agent.core.engine.orchestrator.FakeToolExecutor
import com.apex.agent.core.engine.orchestrator.FakeToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #828 回归 — PLAN 模式反思请求必须携带执行 ground truth。
 *
 * 旧实现 Phase 5 反思只发送 System + 计划文本（反思 prompt），执行阶段
 * 的工具结果 / 错误 / 部分完成状态一个都看不到 —— 模型被要求「汇报交付物
 * 与问题」却只能凭计划编造。本套件钉死：
 *
 * 1. 反思请求（最后一条 LLM 调用的 User 消息）含「步骤结果清单」段：
 *    成功步骤的收尾摘要 + 失败步骤的 ERROR 行都在；
 * 2. 反思指令要求以实际结果为准（ground truth 措辞）；
 * 3. 提示词纯函数（renderStepOutcome / buildReflectionPrompt）的边界语义。
 */
class PlanReflectionGroundTruthTest {

    private val planJson = """
        {"goal":"g","steps":[
            {"index":0,"description":"build the module"},
            {"index":1,"description":"run the test suite"}],
         "estimated_tool_calls":2,"risk_level":"low","reasoning":"r"}
    """.trimIndent()

    private suspend fun awaitPlanConfirmationReady(
        engine: ApexAgentEngine,
        timeoutMs: Long = 10_000
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (engine.planConfirmationDeferred == null && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(20)
        }
    }

    @Test
    fun `reflection request carries per-step ground truth outcomes`() = runBlocking<Unit> {
        val fake = FakeLlmClient(
            listOf(
                // Phase 1：计划生成
                FakeLlmClient.ScriptedResponse.Ok(content = planJson),
                // Phase 4 步骤 1：正常收尾（摘要入 ground truth）
                FakeLlmClient.ScriptedResponse.Ok(content = "module compiled, 3 sources emitted"),
                // Phase 4 步骤 2：空响应（DISABLED 韧性 → 立即 Error 事件）
                FakeLlmClient.ScriptedResponse.Ok(content = ""),
                // Phase 5：反思
                FakeLlmClient.ScriptedResponse.Ok(content = "reflection summary")
            )
        )
        val engine = ApexAgentEngine(
            llmClient = fake,
            toolRegistry = FakeToolRegistry(emptyList()),
            toolExecutor = FakeToolExecutor(),
            config = AgentConfig(mode = AgentMode.PLAN, thinkingLevel = ThinkingLevel.NONE),
            resilience = EngineResilienceGuard(EngineResiliencePolicy.DISABLED)
        )
        val events = mutableListOf<AgentEvent>()
        val job = launch(Dispatchers.Unconfined) {
            engine.execute(UserInput.text("计划任务")).collect { event ->
                events.add(event)
                if (event is AgentEvent.PlanAwaitingConfirmation) {
                    launch(Dispatchers.Default) {
                        awaitPlanConfirmationReady(engine)
                        engine.submitPlanConfirmation(true)
                    }
                }
            }
        }
        val deadline = System.currentTimeMillis() + 30_000
        while (!events.any { it is AgentEvent.Complete } && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(50)
        }
        job.cancel()

        // 反思请求 = 最后一条 LLM 调用（计划生成 / 步骤 1 / 步骤 2 之后）。
        val reflectionCall = fake.callLog.lastOrNull()
            ?: throw AssertionError("no LLM call was recorded")
        val reflectionUser = reflectionCall.first
            .filterIsInstance<com.apex.agent.core.llm.LlmMessage.User>()
            .single()
        val prompt = reflectionUser.content

        // ground truth 段存在且双态齐全：成功摘要 + 失败行。
        assertTrue(
            "reflection prompt must carry actual step outcomes:\n$prompt",
            prompt.contains("Actual step outcomes")
        )
        assertTrue(
            "successful step summary must be visible to reflection:\n$prompt",
            prompt.contains("final model summary — module compiled")
        )
        assertTrue(
            "failed step must be reported as ERROR, not glossed over:\n$prompt",
            prompt.contains("ERROR")
        )
        // 指令要求以实际结果为准。
        assertTrue(prompt.contains("report THESE, not the plan"))
        // 反思输出流照常收尾（既有行为不回归）。
        assertTrue(events.any { it is AgentEvent.ResponseComplete })
    }

    @Test
    fun `renderStepOutcome priority and truncation semantics`() {
        val notRun = com.apex.agent.core.engine.EnginePrompts.renderStepOutcome(
            2, "later step", null, null, notRun = true
        )
        assertTrue(notRun.contains("NOT RUN"))
        val errored = com.apex.agent.core.engine.EnginePrompts.renderStepOutcome(
            0, "step", null, error = "connection reset\nafter retry", notRun = false
        )
        assertTrue(errored.contains("ERROR"))
        assertTrue("newline folded", errored.contains("connection reset after retry"))
        val summary = com.apex.agent.core.engine.EnginePrompts.renderStepOutcome(
            1, "step", finalText = "x".repeat(500), error = null, notRun = false
        )
        assertTrue("summary truncated to 200", summary.length < 320)
        assertTrue(summary.contains("final model summary"))
        val noText = com.apex.agent.core.engine.EnginePrompts.renderStepOutcome(
            1, "step", finalText = null, error = null, notRun = false
        )
        assertTrue(noText.contains("ended without a final text summary"))
    }

    @Test
    fun `reflection prompt omits outcome section when list empty`() {
        val plan = com.apex.agent.core.engine.ExecutionPlan(
            goal = "g", steps = emptyList(),
            estimatedToolCalls = 0,
            riskLevel = com.apex.agent.core.engine.RiskLevel.LOW,
            reasoning = "r"
        )
        val prompt = com.apex.agent.core.engine.EnginePrompts.buildReflectionPrompt(plan)
        assertTrue("empty outcome list → legacy prompt shape", !prompt.contains("Actual step outcomes"))
        assertTrue(prompt.contains("State the final outcome"))
    }
}
