package com.apex.agent.core.engine

import com.apex.agent.core.engine.goal.GoalCheckResult
import com.apex.agent.core.engine.goal.GoalModeCoordinator
import com.apex.agent.core.engine.goal.GoalSpec
import com.apex.agent.core.engine.orchestrator.FakeLlmClient
import com.apex.agent.core.engine.orchestrator.FakeToolExecutor
import com.apex.agent.core.engine.orchestrator.FakeToolRegistry
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #820 — BUILD/GOAL 循环 continue 路径的 ResponseComplete 契约测试。
 *
 * UI 契约（CodeEventReducer）：流式气泡只认 [AgentEvent.ResponseComplete] 落定；
 * continue 路径只发 ResponseChunk 时气泡永不收尾，下一轮回复粘进同一气泡。
 * 三条历史漏发路径逐一锁定：
 * 1. 肌肉记忆旁路（BypassOutcome.Executed → continue）；
 * 2. BUILD 承诺催促（PromiseDetector 命中 → System 催促 → continue）；
 * 3. GOAL 验收续跑（GoalDecision.Continue → System 差距说明 → continue）。
 */
class EngineContinuePathResponseCompleteTest {

    /** 肌肉记忆旁路 fake：每轮固定返回 Executed（LLM 恒被跳过）。 */
    private class BypassAlwaysExecuted : ExecutionMemoryObserver {
        override suspend fun tryBypass(): BypassOutcome = BypassOutcome.Executed(actionCount = 2)
    }

    /** 恒不达标的验收器（每轮 Continue，直到轮次耗尽语义接管）。 */
    private val neverAchieved = { _: GoalSpec, _: String ->
        GoalCheckResult(achieved = false, reason = "仍有失败测试")
    }

    private fun newEngine(
        responses: List<FakeLlmClient.ScriptedResponse>,
        mode: AgentMode,
        memoryObserver: ExecutionMemoryObserver? = null,
        goalCoordinator: GoalModeCoordinator? = null,
        maxIterations: Int = 3
    ): ApexAgentEngine = ApexAgentEngine(
        llmClient = FakeLlmClient(responses = responses),
        toolRegistry = FakeToolRegistry(),
        toolExecutor = FakeToolExecutor(),
        config = AgentConfig(mode = mode, maxIterations = maxIterations),
        memoryObserver = memoryObserver,
        goalCoordinator = goalCoordinator
    )

    @Test
    fun `muscle-memory bypass continue path emits ResponseComplete`() = runTest {
        val engine = newEngine(
            responses = emptyList(),
            mode = AgentMode.BUILD,
            memoryObserver = BypassAlwaysExecuted(),
            maxIterations = 2
        )
        val events = engine.execute(UserInput.text("打开设置")).toList()
        val completes = events.filterIsInstance<AgentEvent.ResponseComplete>()
        assertTrue(
            "bypass continue path must finalize the streaming bubble, events=$events",
            completes.isNotEmpty()
        )
        assertTrue(
            "bypass finalize must carry the bypass notice, got ${completes.map { it.fullText }}",
            completes.any { it.fullText.contains("肌肉记忆旁路执行完成") }
        )
    }

    @Test
    fun `promise nudge continue path emits ResponseComplete for the promised turn`() = runTest {
        val promiseText = "我现在去执行构建命令并运行全部测试，预计几分钟内完成。"
        val engine = newEngine(
            responses = listOf(
                FakeLlmClient.ScriptedResponse.Ok(content = promiseText),
                FakeLlmClient.ScriptedResponse.Ok(content = "构建与测试全部通过。")
            ),
            mode = AgentMode.BUILD,
            maxIterations = 3
        )
        val events = engine.execute(UserInput.text("跑一遍构建")).toList()
        val completes = events.filterIsInstance<AgentEvent.ResponseComplete>()
        assertTrue(
            "nudged turn + final turn must both finalize, got ${completes.map { it.fullText }}",
            completes.size >= 2
        )
        assertTrue(
            "first finalize must close the promised (nudged) turn, got ${completes.first().fullText}",
            completes.first().fullText.contains("我现在去执行构建命令")
        )
    }

    @Test
    fun `goal continue path emits ResponseComplete before continuing`() = runTest {
        val coordinator = GoalModeCoordinator(verifier = neverAchieved)
        coordinator.startGoal(
            GoalSpec(statement = "测试全绿", acceptanceCriteria = "全部测试通过", maxRounds = 2)
        )
        val engine = newEngine(
            responses = listOf(
                FakeLlmClient.ScriptedResponse.Ok(content = "第一轮工作汇报：修好了 1 个失败测试。"),
                FakeLlmClient.ScriptedResponse.Ok(content = "第二轮工作汇报：全部测试通过。")
            ),
            mode = AgentMode.GOAL,
            goalCoordinator = coordinator,
            maxIterations = 5
        )
        val events = engine.execute(UserInput.text("把测试修到全绿")).toList()
        val completes = events.filterIsInstance<AgentEvent.ResponseComplete>()
        assertTrue(
            "goal continue turn + final turn must both finalize, got ${completes.map { it.fullText }}",
            completes.size >= 2
        )
        assertTrue(
            "first finalize must close the continued (goal) turn, got ${completes.first().fullText}",
            completes.first().fullText.contains("第一轮工作汇报")
        )
    }
}
