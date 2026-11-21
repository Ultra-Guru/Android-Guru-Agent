package com.apex.agent.core.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #395 — abort 语义收口回归。
 *
 * 真机故障链（logcat 2026-10-17 17:50 实证）：AskUserChoice / 权限门在工具
 * execute() 内挂起于 UserQuestionBridge 自有 deferred → 引擎 abort() 只收口
 * 引擎自有三通道（userInput/plan/spec）→ 桥接层挂起无人唤醒 → 引擎流吃满
 * 5 分钟不收尾 → TaskRuntime pause/cancel 连续打印 "stream finalize did not
 * complete within 10s"（5 连）→ UI「取消卡死」。
 *
 * 修复：UserQuestionGateway.abortActiveQuestion() 默认收口接口 + 桥实现 +
 * abortConfirmationChannels 统一调用链。本测试锁定：桥收口语义
 * （skipped/弹窗清除/无挂起返回 false/迟到 submit 安全）与引擎 abort 调用链。
 *
 * 测试纪律（AGENTS.md / #276 棘轮）：runTest 虚拟钟 + runCurrent/advanceUntilIdle
 * 推进 —— 不用墙钟睡眠、不用阻塞等待原语。ask 的注册段（synchronized 内
 * deferred 赋值 + pendingQuestion 置值）先于 await 挂起点同步执行，
 * runCurrent 一拍即可见。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UserQuestionBridgeAbortTest {

    // ═══ 桥收口语义 ═══

    @Test fun `abortActiveQuestion completes pending ask with skipped answer`() = runTest {
        val bridge = UserQuestionBridge()
        val question = AgentQuestion(
            title = "选一个",
            options = listOf(AgentQuestionOption("a", "A"), AgentQuestionOption("b", "B"))
        )
        val answered = CompletableDeferred<AgentAnswer>()
        launch { answered.complete(bridge.ask(question)) }

        runCurrent() // ask 注册段同步执行完（挂起在 deferred.await()）
        assertEquals("提问已注册（UI 可见弹窗）", question.id, bridge.pendingQuestion.value?.id)

        assertTrue("存在挂起提问 → 收口返回 true", bridge.abortActiveQuestion())

        val answer = withTimeout(5_000) { answered.await() }
        assertTrue("收口 = skipped 答案", answer.skipped)
        assertFalse("收口 ≠ 超时（timedOut 与显式收口可区分）", answer.timedOut)
        assertEquals(question.id, answer.questionId)
        advanceUntilIdle() // ask 的 finally 条件回收执行
        assertNull("pendingQuestion 应随收口复位（UI 弹窗关闭）", bridge.pendingQuestion.value)
    }

    @Test fun `abortActiveQuestion returns false when nothing pending`() = runTest {
        val bridge = UserQuestionBridge()
        assertFalse("无挂起提问 → false（调用方零副作用）", bridge.abortActiveQuestion())
    }

    @Test fun `abortActiveQuestion keeps later submissions no-op safe`() = runTest {
        val bridge = UserQuestionBridge()
        val question = AgentQuestion(
            title = "确认？",
            options = listOf(AgentQuestionOption("ok", "OK"))
        )
        val answered = CompletableDeferred<AgentAnswer>()
        launch { answered.complete(bridge.ask(question)) }
        runCurrent()

        assertTrue(bridge.abortActiveQuestion())
        val answer = withTimeout(5_000) { answered.await() }
        assertTrue(answer.skipped)
        // 收口后再 submit（迟到的 UI 回答）→ 无 deferred 可喂，不崩不挂
        bridge.submit(AgentAnswer(questionId = question.id, selectedOptionId = "ok"))
        assertNull(bridge.pendingQuestion.value)
    }

    // ═══ 引擎 abort 调用链 ═══

    @Test fun `engine abort invokes gateway abortActiveQuestion`() = runTest {
        val recording = RecordingGateway()
        val engine = ApexAgentEngine(
            llmClient = com.apex.agent.core.engine.orchestrator.FakeLlmClient(
                responses = listOf(
                    com.apex.agent.core.engine.orchestrator.FakeLlmClient.ScriptedResponse.Ok(content = "done")
                )
            ),
            toolRegistry = com.apex.agent.core.engine.orchestrator.FakeToolRegistry(emptyList()),
            toolExecutor = com.apex.agent.core.engine.orchestrator.FakeToolExecutor(),
            config = AgentConfig(mode = AgentMode.BUILD, thinkingLevel = ThinkingLevel.NONE),
            userQuestionGateway = recording
        )
        engine.abort()
        assertTrue("abort() 必须调用 gateway.abortActiveQuestion()", recording.abortCalled)
    }

    // ═══ helpers ═══

    /** 记录 abort 收口调用的最小 gateway（默认接口实现兼容验证同体覆盖）。 */
    private class RecordingGateway : UserQuestionGateway {
        var abortCalled = false
        override suspend fun ask(question: AgentQuestion): AgentAnswer =
            AgentAnswer(questionId = question.id, skipped = true)
        override fun abortActiveQuestion(): Boolean {
            abortCalled = true
            return false
        }
    }
}
