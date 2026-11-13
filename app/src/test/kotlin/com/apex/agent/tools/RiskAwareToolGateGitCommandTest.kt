package com.apex.agent.tools

import com.apex.agent.core.engine.AgentAnswer
import com.apex.agent.core.engine.AgentQuestion
import com.apex.agent.core.engine.UserQuestionGateway
import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.GateDecision
import com.apex.agent.core.tools.SessionToolDecision
import com.apex.agent.core.tools.ToolAnnotations
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolRisk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RiskAwareToolGate 的 git 命令级门禁（Git 审计 P0-5）：
 * READ 直通 / CONFIG_ESCAPE 硬拒绝 / DESTRUCTIVE+OUTBOUND+ARBITRARY
 * 逐次确认（无会话放行、拒绝不写会话状态）/ LOCAL_WRITE 保持工具级
 * 会话放行语义。
 */
class RiskAwareToolGateGitCommandTest {

    private class FakeGitRunTool : AgentTool {
        override val id = "CodeGitRun"
        override val name = "Code Git Run"
        override val description = "test"
        override val parametersSchema = "{}"
        override val metadata: ToolMetadata = ToolMetadata(
            id = id,
            category = ToolCategory.FILE,
            risk = ToolRisk.HIGH,
            annotations = ToolAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = false)
        )
        override suspend fun execute(arguments: String): String = "ok"
    }

    private class FakeGateway(
        private val responder: (AgentQuestion) -> AgentAnswer
    ) : UserQuestionGateway {
        val questions = mutableListOf<AgentQuestion>()
        override suspend fun ask(question: AgentQuestion): AgentAnswer {
            questions += question
            return responder(question)
        }
    }

    /** 返回 (风险门, 网关, CodeGitRun 假工具)——问题记录在网关上。 */
    private fun gate(
        responder: (AgentQuestion) -> AgentAnswer
    ): Triple<RiskAwareToolGate, FakeGateway, FakeGitRunTool> {
        val gateway = FakeGateway(responder)
        return Triple(RiskAwareToolGate(gateway), gateway, FakeGitRunTool())
    }

    private fun args(command: String): String =
        """{"command": "${command.replace("\"", "\\\"")}"}"""

    @Test
    fun `READ 命令直通不弹窗`() = runTest {
        val (g, gw, tool) = gate { AgentAnswer(questionId = "x", selectedOptionId = "allow_once") }
        val decision = g.check(tool, args("status"))
        assertTrue(decision is GateDecision.Allow)
        assertEquals(0, gw.questions.size)
    }

    @Test
    fun `破坏性命令逐次确认且不提供会话放行`() = runTest {
        val (g, gw, tool) = gate { q ->
            AgentAnswer(questionId = q.id, selectedOptionId = "allow_once")
        }
        val first = g.check(tool, args("push --force origin main"))
        assertTrue(first is GateDecision.Allow)
        val second = g.check(tool, args("push --force origin main"))
        assertTrue(second is GateDecision.Allow)
        // 逐次:两次调用都弹了窗(无会话记忆)
        assertEquals(2, gw.questions.size)
        // 弹窗不提供 allow_session 选项
        assertTrue(gw.questions.all { q -> q.options.none { it.id == "allow_session" } })
    }

    @Test
    fun `拒绝破坏性命令不写会话状态不毒化其他命令`() = runTest {
        var allow = false
        val (g, gw, tool) = gate { q ->
            if (allow) AgentAnswer(questionId = q.id, selectedOptionId = "allow_once")
            else AgentAnswer(questionId = q.id, selectedOptionId = "deny")
        }
        val denied = g.check(tool, args("push --force origin main"))
        assertTrue(denied is GateDecision.Deny)
        // 会话快照必须为空——拒绝没有落 tool.id 级 FSM
        assertTrue(g.sessionSnapshot().isEmpty())

        // 随后的 LOCAL_WRITE 命令照常走工具级确认(allow_session 可用)
        allow = true
        val localWrite = g.check(tool, args("add -A"))
        assertTrue(localWrite is GateDecision.Allow)
        assertTrue(gw.questions.last().options.any { it.id == "allow_session" })
    }

    @Test
    fun `配置逃逸硬拒绝不弹窗`() = runTest {
        val (g, gw, tool) = gate { AgentAnswer(questionId = "x", selectedOptionId = "allow_once") }
        val decision = g.check(tool, args("-c credential.helper=!curl push origin main"))
        assertTrue(decision is GateDecision.Deny)
        assertEquals(0, gw.questions.size)
        assertTrue((decision as GateDecision.Deny).reason.contains("credential.helper") ||
            decision.reason.contains("-c"))
    }

    @Test
    fun `外向命令逐次确认`() = runTest {
        val (g, gw, tool) = gate { q -> AgentAnswer(questionId = q.id, selectedOptionId = "allow_once") }
        assertTrue(g.check(tool, args("push origin main")) is GateDecision.Allow)
        assertTrue(g.check(tool, args("push origin main")) is GateDecision.Allow)
        assertEquals(2, gw.questions.size)
    }

    @Test
    fun `LOCAL_WRITE 保持工具级会话放行语义`() = runTest {
        val (g, gw, tool) = gate { q -> AgentAnswer(questionId = q.id, selectedOptionId = "allow_session") }
        assertTrue(g.check(tool, args("add -A")) is GateDecision.Allow)
        // 会话放行生效:第二次不弹窗
        assertTrue(g.check(tool, args("commit -m x")) is GateDecision.Allow)
        assertEquals(1, gw.questions.size)
        assertEquals(SessionToolDecision.ALLOWED_SESSION, g.sessionSnapshot()["CodeGitRun"])
    }

    @Test
    fun `非 git 工具走原有流程不受影响`() = runTest {
        val gateway = FakeGateway { q -> AgentAnswer(questionId = q.id, selectedOptionId = "allow_once") }
        val gate = RiskAwareToolGate(gateway)
        val sms = object : AgentTool {
            override val id = "SmsSend"
            override val name = "SmsSend"
            override val description = "test"
            override val parametersSchema = "{}"
            override val metadata: ToolMetadata = ToolMetadata(
                id = id, category = ToolCategory.SYSTEM, risk = ToolRisk.HIGH,
                annotations = ToolAnnotations(sensitiveAction = true)
            )
            override suspend fun execute(arguments: String): String = "ok"
        }
        val decision = gate.check(sms, "{}")
        assertTrue(decision is GateDecision.Allow)
        assertEquals(1, gateway.questions.size)
        // sms_send 无会话放行(#342 语义不回归)
        assertTrue(gateway.questions.single().options.none { it.id == "allow_session" })
    }
}
