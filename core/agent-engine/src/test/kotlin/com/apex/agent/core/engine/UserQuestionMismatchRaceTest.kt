package com.apex.agent.core.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S-11（docs/chaos-crash-audit.md L-11）—— UserQuestionBridge.submit 的
 * questionId 校验回归。
 *
 * 竞态窗口（修复前）：A 超时收场（或被 abort）与新问题 B 注册之间，UI 迟到
 * 的 A 答案直接 complete 到 B 的 deferred —— B 的调用方（AskUserChoice /
 * CommandPermissionGate / RiskAwareToolGate）拿到 A 的选择继续执行 B 的
 * 动作，破坏性操作带着错配的确认跑。同批 isRunning 已补 @Volatile，本项
 * 是 L-11 的另一半。
 *
 * 修复语义（UserQuestionBridge.submit）：answer.questionId ≠ 当前挂起问题 id
 * → 丢弃 + 审计日志（两个 id + 时间戳），不 complete；B 保持挂起等待真实
 * 回答。
 *
 * 测试纪律（AGENTS.md / #276 棘轮）：runTest 虚拟钟 + runCurrent /
 * advanceTimeBy 推进 —— 不用墙钟睡眠、不用阻塞等待原语。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UserQuestionMismatchRaceTest {

    private fun options(vararg ids: String) =
        ids.map { AgentQuestionOption(it, it.uppercase()) }

    // ═══ spec 场景：A 超时 → B 挂起 → 迟到的 A 答案 → B 仍挂起 ═══

    @Test fun `late answer for timed-out question must not satisfy the next question`() = runTest {
        val bridge = UserQuestionBridge()
        val questionA = AgentQuestion(
            title = "A：删除哪个分支？", options = options("a1", "a2"), timeoutMs = 1_000
        )
        val answeredA = CompletableDeferred<AgentAnswer>()
        launch { answeredA.complete(bridge.ask(questionA)) }
        runCurrent() // A 注册段同步执行（挂起在 await）
        assertEquals(questionA.id, bridge.pendingQuestion.value?.id)

        // A 超时收场（虚拟钟推 1s）：timedOut 答案返回 + finally 条件回收
        advanceTimeBy(1_000)
        runCurrent()
        val a = withTimeout(5_000) { answeredA.await() }
        assertTrue("A 按 #215 超时语义收场", a.timedOut)
        runCurrent() // finally 清理段执行
        assertNull("A 收场后 pendingQuestion 复位", bridge.pendingQuestion.value)

        // B 注册（新一轮提问，长超时防干扰）
        val questionB = AgentQuestion(
            title = "B：确认执行 rm -rf？", options = options("b1", "b2"), timeoutMs = 60_000
        )
        val answeredB = CompletableDeferred<AgentAnswer>()
        launch { answeredB.complete(bridge.ask(questionB)) }
        runCurrent()
        assertEquals(questionB.id, bridge.pendingQuestion.value?.id)

        // 迟到的 A 答案到达（竞态窗口核心）—— 必须被丢弃，B 不得被收场
        bridge.submit(AgentAnswer(questionId = questionA.id, selectedOptionId = "a1"))
        assertFalse("迟到的 A 答案不得 complete B 的 deferred", answeredB.isCompleted)
        assertEquals("B 仍是当前挂起提问", questionB.id, bridge.pendingQuestion.value?.id)

        // 真实的 B 答案仍然有效（校验不引入新阻塞）
        bridge.submit(AgentAnswer(questionId = questionB.id, selectedOptionId = "b2"))
        val b = withTimeout(5_000) { answeredB.await() }
        assertEquals(questionB.id, b.questionId)
        assertEquals("b2", b.selectedOptionId)
    }

    // ═══ 同族窗口：A 被 abort 收场后 B 注册，迟到的 A 答案同样丢弃 ═══

    @Test fun `late answer for aborted question must not satisfy the next question`() = runTest {
        val bridge = UserQuestionBridge()
        val questionA = AgentQuestion(title = "A", options = options("a1"))
        val answeredA = CompletableDeferred<AgentAnswer>()
        launch { answeredA.complete(bridge.ask(questionA)) }
        runCurrent()
        assertTrue(bridge.abortActiveQuestion())
        val a = withTimeout(5_000) { answeredA.await() }
        assertTrue(a.skipped)
        runCurrent() // finally 条件回收

        val questionB = AgentQuestion(title = "B", options = options("b1"))
        val answeredB = CompletableDeferred<AgentAnswer>()
        launch { answeredB.complete(bridge.ask(questionB)) }
        runCurrent()

        bridge.submit(AgentAnswer(questionId = questionA.id, selectedOptionId = "a1"))
        assertFalse("abort 窗口的迟到 A 答案同样不得注入 B", answeredB.isCompleted)

        bridge.submit(AgentAnswer(questionId = questionB.id, selectedOptionId = "b1"))
        val b = withTimeout(5_000) { answeredB.await() }
        assertEquals("b1", b.selectedOptionId)
    }

    // ═══ 直接错配（UI 侧 bug / 伪造）：A 挂起期间提交异物 id ═══

    @Test fun `answer with foreign questionId is dropped while original stays pending`() = runTest {
        val bridge = UserQuestionBridge()
        val question = AgentQuestion(title = "确认？", options = options("ok"))
        val answered = CompletableDeferred<AgentAnswer>()
        launch { answered.complete(bridge.ask(question)) }
        runCurrent()

        // 完全陌生的 questionId —— 丢弃，原问题继续等待
        bridge.submit(AgentAnswer(questionId = "not-a-real-question-id", selectedOptionId = "ok"))
        assertFalse(answered.isCompleted)
        assertEquals(question.id, bridge.pendingQuestion.value?.id)

        // 正确答案照常生效
        bridge.submit(AgentAnswer(questionId = question.id, selectedOptionId = "ok"))
        val answer = withTimeout(5_000) { answered.await() }
        assertEquals("ok", answer.selectedOptionId)
        assertFalse(answer.skipped)
    }

    // ═══ 既有通道不回退：cancelCurrentQuestion / abortActiveQuestion 照常收场 ═══

    @Test fun `cancel and abort channels unaffected by id validation`() = runTest {
        val bridge = UserQuestionBridge()

        // cancelCurrentQuestion 用当前挂起 id 构造答案 → 必须照常收场
        val q1 = AgentQuestion(title = "Q1", options = options("x"))
        val a1 = CompletableDeferred<AgentAnswer>()
        launch { a1.complete(bridge.ask(q1)) }
        runCurrent()
        bridge.cancelCurrentQuestion()
        val cancelled = withTimeout(5_000) { a1.await() }
        assertTrue("cancelCurrentQuestion 语义不变（skipped 收场）", cancelled.skipped)
        runCurrent()

        // abortActiveQuestion 直连 deferred，不经 submit → 语义不变
        val q2 = AgentQuestion(title = "Q2", options = options("y"))
        val a2 = CompletableDeferred<AgentAnswer>()
        launch { a2.complete(bridge.ask(q2)) }
        runCurrent()
        assertTrue(bridge.abortActiveQuestion())
        val aborted = withTimeout(5_000) { a2.await() }
        assertTrue("abortActiveQuestion 语义不变", aborted.skipped)
    }
}
