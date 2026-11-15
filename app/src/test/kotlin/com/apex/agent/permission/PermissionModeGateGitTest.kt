package com.apex.agent.permission

import com.apex.agent.core.engine.AgentAnswer
import com.apex.agent.core.engine.AgentQuestion
import com.apex.agent.core.engine.UserQuestionGateway
import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.ToolAnnotations
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolRisk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PermissionModeGate 的 git 命令级 carve-out（Git 审计 P0-5）：
 * BYPASS 的模式承诺不覆盖破坏性/外向/未识别 git 命令（仍逐次确认）、
 * CONFIG_ESCAPE 硬拒绝、PLAN 拒绝、用户 ALLOW 规则是显式意志仍放行、
 * READ 命令各模式直通（DENY 规则仍优先）。
 */
class PermissionModeGateGitTest {

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

    private fun gate(
        mode: PermissionMode,
        rules: List<PermissionRule> = emptyList(),
        responder: (AgentQuestion) -> AgentAnswer = { AgentAnswer(questionId = it.id, selectedOptionId = "allow_once") },
    ): Pair<PermissionModeGate, FakeGateway> {
        val gateway = FakeGateway(responder)
        return PermissionModeGate(gateway, settingsProvider = { PermissionSnapshot(mode, rules) }) to gateway
    }

    private fun args(command: String): String =
        """{"command": "${command.replace("\"", "\\\"")}"}"""

    @Test
    fun `BYPASS 下破坏性命令仍逐次确认`() = runTest {
        val (g, gw) = gate(PermissionMode.BYPASS)
        val decision = g.checkDetailed(FakeGitRunTool(), args("push --force origin main"))
        assertTrue(decision is PermissionModeGate.DetailedDecision.ExplicitAllow)
        assertEquals(1, gw.questions.size)
        // 无「本会话允许」——逐次确认语义
        assertTrue(gw.questions.single().options.none { it.id == "allow_session" })
    }

    @Test
    fun `BYPASS 下外向命令逐次确认`() = runTest {
        val (g, gw) = gate(PermissionMode.BYPASS)
        g.checkDetailed(FakeGitRunTool(), args("push origin main"))
        g.checkDetailed(FakeGitRunTool(), args("pull"))
        assertEquals(2, gw.questions.size)
    }

    @Test
    fun `配置逃逸在任何模式硬拒绝不弹窗`() = runTest {
        for (mode in PermissionMode.entries) {
            val (g, gw) = gate(mode)
            val decision = g.checkDetailed(FakeGitRunTool(), args("config --global user.name x"))
            assertTrue("模式 $mode 应硬拒绝", decision is PermissionModeGate.DetailedDecision.Denied)
            assertEquals("模式 $mode 不应弹窗", 0, gw.questions.size)
        }
    }

    @Test
    fun `PLAN 下外向命令直接拒绝不弹窗`() = runTest {
        val (g, gw) = gate(PermissionMode.PLAN)
        val decision = g.checkDetailed(FakeGitRunTool(), args("push origin main"))
        assertTrue(decision is PermissionModeGate.DetailedDecision.Denied)
        assertEquals(0, gw.questions.size)
    }

    @Test
    fun `PLAN 下 READ 命令直通`() = runTest {
        val (g, gw) = gate(PermissionMode.PLAN)
        val decision = g.checkDetailed(FakeGitRunTool(), args("status"))
        assertTrue(decision is PermissionModeGate.DetailedDecision.ExplicitAllow)
        assertEquals(0, gw.questions.size)
    }

    @Test
    fun `用户 ALLOW 规则显式放行外向命令`() = runTest {
        val (g, gw) = gate(
            PermissionMode.BYPASS,
            rules = listOf(PermissionRule(pattern = "CodeGitRun", effect = PermissionEffect.ALLOW)),
        )
        val decision = g.checkDetailed(FakeGitRunTool(), args("push --force origin main"))
        assertTrue(decision is PermissionModeGate.DetailedDecision.ExplicitAllow)
        assertEquals(0, gw.questions.size)
    }

    @Test
    fun `READ 命令尊重 DENY 规则`() = runTest {
        val (g, gw) = gate(
            PermissionMode.BYPASS,
            rules = listOf(PermissionRule(pattern = "CodeGitRun", effect = PermissionEffect.DENY)),
        )
        val decision = g.checkDetailed(FakeGitRunTool(), args("status"))
        assertTrue(decision is PermissionModeGate.DetailedDecision.Denied)
    }

    @Test
    fun `DEFAULT 下 LOCAL_WRITE 走原有会话记忆流程`() = runTest {
        // 用户点「本会话允许」→ 会话记忆生效(与破坏性命令的逐次语义对比)
        val (g, gw) = gate(PermissionMode.DEFAULT) { q ->
            AgentAnswer(questionId = q.id, selectedOptionId = "allow_session")
        }
        val first = g.checkDetailed(FakeGitRunTool(), args("add -A"))
        assertTrue(first is PermissionModeGate.DetailedDecision.ExplicitAllow)
        val second = g.checkDetailed(FakeGitRunTool(), args("commit -m x"))
        assertTrue(second is PermissionModeGate.DetailedDecision.ExplicitAllow)
        // 第一次弹窗 + 会话记忆第二次免问(与破坏性命令的逐次语义形成对比)
        assertEquals(1, gw.questions.size)
        assertEquals(1, g.sessionAllowedSnapshot().size)
    }

    @Test
    fun `DEFAULT 下 LOCAL_WRITE 仅允许一次则逐次再问`() = runTest {
        val (g, gw) = gate(PermissionMode.DEFAULT) { q ->
            AgentAnswer(questionId = q.id, selectedOptionId = "allow_once")
        }
        assertTrue(g.checkDetailed(FakeGitRunTool(), args("add -A")) is PermissionModeGate.DetailedDecision.ExplicitAllow)
        assertTrue(g.checkDetailed(FakeGitRunTool(), args("commit -m x")) is PermissionModeGate.DetailedDecision.ExplicitAllow)
        assertEquals(2, gw.questions.size)
        assertTrue("allow_once 不写会话记忆", g.sessionAllowedSnapshot().isEmpty())
    }

    @Test
    fun `用户拒绝逐次确认的命令不写会话记忆`() = runTest {
        val (g, gw) = gate(PermissionMode.BYPASS) { q ->
            AgentAnswer(questionId = q.id, selectedOptionId = "deny")
        }
        val decision = g.checkDetailed(FakeGitRunTool(), args("push --force origin main"))
        assertTrue(decision is PermissionModeGate.DetailedDecision.Denied)
        assertTrue("逐次确认的拒绝不落会话记忆", g.sessionAllowedSnapshot().isEmpty())
    }
}
