package com.apex.agent.tools

import com.apex.agent.core.engine.AgentAnswer
import com.apex.agent.core.engine.AgentQuestion
import com.apex.agent.core.engine.UserQuestionGateway
import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.GateDecision
import com.apex.agent.core.tools.ToolAnnotations
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolRisk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S-03 回归：RiskAwareToolGate 确认面（requiresPrompt 风险判定恢复）。
 *
 * 历史：2026-10 曾把 HIGH/MEDIUM 风险弹窗全部移除（仅剩 sensitiveAction）
 * 且默认档翻成 BYPASS —— 新装机即全自动。S-03 后默认档回到 DEFAULT，
 * 本门重新承担副作用工具的默认确认面；本套件把判定矩阵钉死：
 *
 * ① sensitiveAction → 弹（付费/逐次同意类，BYPASS 下也保留）；
 * ② HIGH 风险 → 弹（删除/卸载/force-stop 族，不可逆必问）；
 * ③ MEDIUM 且非只读 → 弹（写文件/git 提交/改状态等副作用）；
 * ④ MEDIUM 只读 / LOW → 静默放行（确认预算留给真正改环境的动作）；
 * ⑤ 「本会话允许」后同会话静默（确认面恢复但不骚扰）。
 */
class RiskAwareToolGatePromptSurfaceTest {

    private class FakeTool(
        override val id: String,
        risk: ToolRisk,
        annotations: ToolAnnotations
    ) : AgentTool {
        override val name: String = id
        override val description: String = "测试用工具"
        override val parametersSchema: String = "{}"
        override val metadata: ToolMetadata = ToolMetadata(
            id = id,
            category = ToolCategory.FILE,
            risk = risk,
            annotations = annotations
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

    private fun mediumWrite(id: String = "CodeWrite") = FakeTool(
        id, ToolRisk.MEDIUM,
        ToolAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = true)
    )

    private fun mediumRead(id: String = "CodeRead") = FakeTool(
        id, ToolRisk.MEDIUM,
        ToolAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )

    private fun highRisk(id: String = "AppUninstall") = FakeTool(
        id, ToolRisk.HIGH,
        ToolAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = false)
    )

    private fun lowRisk(id: String = "FileRead") = FakeTool(
        id, ToolRisk.LOW,
        ToolAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true)
    )

    private fun sensitive(id: String = "BrowserCredentialRead") = FakeTool(
        id, ToolRisk.MEDIUM,
        ToolAnnotations(readOnlyHint = true, destructiveHint = false, sensitiveAction = true)
    )

    @Test
    fun `high risk tool prompts before execution`() = runTest {
        val gateway = FakeGateway { q -> AgentAnswer(questionId = q.id, selectedOptionId = "allow_once") }
        val gate = RiskAwareToolGate(gateway)

        val decision = gate.check(highRisk(), "{}")

        assertTrue("HIGH 风险必须进确认面（S-03 恢复）", gateway.questions.isNotEmpty())
        assertTrue("用户允许一次 → 放行", decision is GateDecision.Allow)
    }

    @Test
    fun `medium side-effect tool prompts before execution`() = runTest {
        // S-03 DoD：默认档下 CodeWrite 触发确认弹窗
        val gateway = FakeGateway { q -> AgentAnswer(questionId = q.id, selectedOptionId = "allow_once") }
        val gate = RiskAwareToolGate(gateway)

        val decision = gate.check(mediumWrite(), "{}")

        assertTrue("MEDIUM 副作用（写文件/git 提交）必须进确认面", gateway.questions.isNotEmpty())
        assertTrue(decision is GateDecision.Allow)
    }

    @Test
    fun `medium read-only and low risk tools stay silent`() = runTest {
        val gateway = FakeGateway { q -> AgentAnswer(questionId = q.id, selectedOptionId = "allow_once") }
        val gate = RiskAwareToolGate(gateway)

        assertTrue(gate.check(mediumRead(), "{}") is GateDecision.Allow)
        assertTrue(gate.check(lowRisk(), "{}") is GateDecision.Allow)
        assertEquals("只读 MEDIUM / LOW 不弹窗（确认预算留给副作用）", 0, gateway.questions.size)
    }

    @Test
    fun `sensitive action still prompts`() = runTest {
        val gateway = FakeGateway { q -> AgentAnswer(questionId = q.id, selectedOptionId = "allow_once") }
        val gate = RiskAwareToolGate(gateway)

        val decision = gate.check(sensitive(), "{}")

        assertTrue("sensitiveAction 通道不回退（BYPASS 下也保留）", gateway.questions.isNotEmpty())
        assertTrue(decision is GateDecision.Allow)
    }

    @Test
    fun `session allow silences subsequent calls of same tool`() = runTest {
        // 确认面恢复 ≠ 骚扰：同一会话内用户答过「本会话允许」后静默
        var call = 0
        val gateway = FakeGateway { q ->
            call += 1
            AgentAnswer(questionId = q.id, selectedOptionId = if (call == 1) "allow_session" else "allow_once")
        }
        val gate = RiskAwareToolGate(gateway)
        val tool = mediumWrite("GitCommit")

        assertTrue(gate.check(tool, "{}") is GateDecision.Allow)
        assertTrue(gate.check(tool, "{}") is GateDecision.Allow)
        assertEquals("第二次调用走会话记忆，不再弹窗", 1, gateway.questions.size)
    }

    @Test
    fun `user denial blocks execution with model guidance`() = runTest {
        val gateway = FakeGateway { q -> AgentAnswer(questionId = q.id, selectedOptionId = "deny") }
        val gate = RiskAwareToolGate(gateway)

        val decision = gate.check(highRisk("ForceStopApp"), "{}")

        assertTrue("拒绝后不得放行", decision is GateDecision.Deny)
        assertTrue("拒绝文案带换方案指引", (decision as GateDecision.Deny).reason.contains("do not retry"))
    }
}
