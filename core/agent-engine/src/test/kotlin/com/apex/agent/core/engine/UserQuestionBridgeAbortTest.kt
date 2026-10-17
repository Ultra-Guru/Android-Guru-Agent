package com.apex.agent.core.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
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
 * ApexAgentEngine.abort() 调用链。本测试锁定：桥收口语义（skipped/弹窗清除/
 * 无挂起返回 false）与引擎 abort 调用链（abort 必调 gateway 收口）。
 */
class UserQuestionBridgeAbortTest {

    // ═══ 桥收口语义 ═══

    @Test fun `abortActiveQuestion completes pending ask with skipped answer`() = runBlocking {
        val bridge = UserQuestionBridge()
        val question = AgentQuestion(
            title = "选一个",
            options = listOf(AgentQuestionOption("a", "A"), AgentQuestionOption("b", "B"))
        )

        val askDeferred = CompletableDeferred<AgentAnswer>()
        val askJob = async {
            val answer = withTimeout(5_000) { bridge.ask(question) }
            askDeferred.complete(answer)
            answer
        }

        // 等待挂起注册（pendingQuestion 可见即注册完成）
        awaitUntil { bridge.pendingQuestion.value != null }

        assertTrue("存在挂起提问 → 收口返回 true", bridge.abortActiveQuestion())

        val answer = askJob.await()
        assertTrue("收口 = skipped 答案", answer.skipped)
        assertFalse("收口 ≠ 超时（timedOut 与显式收口可区分）", answer.timedOut)
        assertEquals(question.id, answer.questionId)
        // finally 条件回收：挂起者自己的 deferred 被清、弹窗状态复位
        assertNull("pendingQuestion 应随收口复位（UI 弹窗关闭）", bridge.pendingQuestion.value)
    }

    @Test fun `abortActiveQuestion returns false when nothing pending`() = runBlocking {
        val bridge = UserQuestionBridge()
        assertFalse("无挂起提问 → false（调用方零副作用）", bridge.abortActiveQuestion())
        Unit
    }

    @Test fun `abortActiveQuestion keeps later submissions no-op safe`() = runBlocking {
        val bridge = UserQuestionBridge()
        val question = AgentQuestion(
            title = "确认？",
            options = listOf(AgentQuestionOption("ok", "OK"))
        )
        val askJob = async { withTimeout(5_000) { bridge.ask(question) } }
        awaitUntil { bridge.pendingQuestion.value != null }

        assertTrue(bridge.abortActiveQuestion())
        val answer = askJob.await()
        assertTrue(answer.skipped)
        // 收口后再 submit（迟到的 UI 回答）→ 无 deferred 可喂，不崩不挂
        bridge.submit(AgentAnswer(questionId = question.id, selectedOptionId = "ok"))
        Unit
    }

    // ═══ 引擎 abort 调用链 ═══

    @Test fun `engine abort invokes gateway abortActiveQuestion`() = runBlocking {
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
        Unit
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

    private fun awaitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "condition not met within ${timeoutMs}ms" }
            Thread.sleep(10)
        }
    }
}
