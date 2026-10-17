package com.apex.agent.tools

import com.apex.agent.core.engine.AgentAnswer
import com.apex.agent.core.engine.AgentQuestion
import com.apex.agent.core.engine.UserQuestionGateway
import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.GateDecision
import com.apex.agent.core.tools.SessionToolDecision
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolRisk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 确认超时循环修复回归（用户实测 tool-audit / app 日志：mcp_list 确认
 * 超时 300s 后模型 1s 内重发，连挂 4 次弹窗、纯等 20 分钟才被
 * Repetition 环路检测捕获——「recovery prompt deferred」才姗姗来迟）。
 *
 * 覆盖（#215 边界补齐，RiskAwareToolGate 确认超时短冷却）：
 *  - 超时 Deny 文案携带「勿立即重试 / 先问用户」指引（旧文案
 *    "the tool will be re-offered on the next call" 主动邀请重试）；
 *  - 冷却期内同工具快速失败，不再经 gateway 挂第二个 300s 弹窗；
 *  - 冷却 ≠ 拒绝：FSM 不写 DENIED_SESSION（#215 语义保持）；
 *  - 会话授权（allowForSession）清除冷却恢复询问；resetSession 清空冷却。
 */
class RiskAwareToolGateCooldownTest {

    /** MEDIUM 第三方动态族（mcp_ 前缀）——真实触发确认链的工具形态。 */
    private class FakeMcpTool(override val id: String) : AgentTool {
        override val name: String = id
        override val description: String = "测试用第三方工具"
        override val parametersSchema: String = "{}"
        override val metadata: ToolMetadata = ToolMetadata(
            id = id,
            category = ToolCategory.MCP,
            risk = ToolRisk.MEDIUM
        )

        override suspend fun execute(arguments: String): String = "ok"
    }

    /** 脚本化问答网关：记录全部问题，按脚本回放答案。 */
    private class FakeGateway(
        private val responder: (AgentQuestion) -> AgentAnswer
    ) : UserQuestionGateway {
        val questions = mutableListOf<AgentQuestion>()

        override suspend fun ask(question: AgentQuestion): AgentAnswer {
            questions += question
            return responder(question)
        }
    }

    @Test
    fun `确认超时的拒绝文案携带勿重试指引`() = runTest {
        val gateway = FakeGateway { q -> AgentAnswer(questionId = q.id, skipped = true, timedOut = true) }
        val gate = RiskAwareToolGate(gateway)

        val deny = gate.check(FakeMcpTool("mcp_list"), "{}") as GateDecision.Deny

        assertTrue("超时语义保留", deny.reason.contains("timed out"))
        assertTrue("不再邀请重试（旧文案 'will be re-offered on the next call' 是重试邀请）", deny.reason.contains("do NOT"))
        assertTrue("指引先问用户", deny.reason.contains("Ask the user"))
        assertEquals(1, gateway.questions.size)
    }

    @Test
    fun `冷却期内同工具快速失败不再挂第二个弹窗`() = runTest {
        val gateway = FakeGateway { q -> AgentAnswer(questionId = q.id, skipped = true, timedOut = true) }
        val gate = RiskAwareToolGate(gateway)
        val tool = FakeMcpTool("mcp_list")

        assertTrue(gate.check(tool, "{}") is GateDecision.Deny) // 第一次：弹窗超时
        val second = gate.check(tool, "{}") as GateDecision.Deny // 模型 1s 内重发

        assertTrue(second.reason.contains("do NOT call this tool again right now"))
        assertEquals("冷却期内不得再次弹窗（旧实现连挂 4 次 × 300s）", 1, gateway.questions.size)
        assertTrue("冷却≠拒绝：FSM 不写会话级决定（#215 超时未决语义保持）", gate.sessionSnapshot().isEmpty())
    }

    @Test
    fun `冷却不影响其他工具的首次询问`() = runTest {
        val gateway = FakeGateway { q -> AgentAnswer(questionId = q.id, skipped = true, timedOut = true) }
        val gate = RiskAwareToolGate(gateway)

        assertTrue(gate.check(FakeMcpTool("mcp_list"), "{}") is GateDecision.Deny)
        assertTrue(gate.check(FakeMcpTool("mcp__fs__read"), "{}") is GateDecision.Deny)

        assertEquals("冷却按工具隔离，不株连同族其他工具", 2, gateway.questions.size)
    }

    @Test
    fun `会话授权解除冷却且会话记忆优先生效`() = runTest {
        val gateway = FakeGateway { q -> AgentAnswer(questionId = q.id, skipped = true, timedOut = true) }
        val gate = RiskAwareToolGate(gateway)
        val tool = FakeMcpTool("mcp_list")

        assertTrue(gate.check(tool, "{}") is GateDecision.Deny) // 超时 → 进冷却
        assertTrue(gate.check(tool, "{}") is GateDecision.Deny) // 冷却快速失败（不弹窗）

        // 用户会话级放行 → 冷却一并解除，FSM 会话记忆优先生效（无需再问）
        gate.allowForSession("mcp_list")
        assertTrue(gate.check(tool, "{}") is GateDecision.Allow)

        assertEquals("仅超时那一次真正弹过窗", 1, gateway.questions.size)
        assertEquals(SessionToolDecision.ALLOWED_SESSION, gate.sessionSnapshot()["mcp_list"])
    }

    @Test
    fun `resetSession 清空冷却`() = runTest {
        val gateway = FakeGateway { q -> AgentAnswer(questionId = q.id, skipped = true, timedOut = true) }
        val gate = RiskAwareToolGate(gateway)
        val tool = FakeMcpTool("mcp_list")

        assertTrue(gate.check(tool, "{}") is GateDecision.Deny) // 超时 → 进冷却
        assertTrue(gate.check(tool, "{}") is GateDecision.Deny)
        assertEquals(1, gateway.questions.size)

        gate.resetSession() // 新会话：冷却与决定一并清空
        assertTrue(gate.check(tool, "{}") is GateDecision.Deny) // 重新询问（再次超时）
        assertEquals("新会话恢复询问", 2, gateway.questions.size)
    }
}
