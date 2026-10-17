package com.apex.agent.permission

import com.apex.agent.core.engine.AgentAnswer
import com.apex.agent.core.engine.AgentQuestion
import com.apex.agent.core.engine.UserQuestionBridge
import com.apex.agent.core.engine.UserQuestionGateway
import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.GateDecision
import com.apex.agent.core.tools.ToolAnnotations
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolExecutionGate
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolRisk
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #155 — 业界标准式权限模式单测（纯 JVM，JUnit4 + runTest）。
 *
 * 覆盖（任务书 §测试）：
 *  - 四模式决策矩阵（BYPASS / DEFAULT / ACCEPT_EDITS / PLAN）；
 *  - 规则语义：首匹配优先于模式默认、越级放行 / 收紧、尾缀通配、
 *    单星通配、空模式、大小写敏感；
 *  - 门交互闭环：会话记忆（allow_session 记忆 / allow_once 不记忆）、
 *    deny 映射、gateway 异常、真实 UserQuestionBridge 的超时与取消
 *    （skipped 折叠形态）、resetSession、check 与 checkDetailed 一致性、
 *    授权问题构造（选项 / 截断 / 禁自定义）；
 *  - 组合器三路分派：显式放行不问 fallback、默认放行转发 fallback、
 *    拒绝直接拦截。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PermissionDeciderTest {

    // ══════════════════════ 决策矩阵 ══════════════════════

    @Test
    fun `BYPASS 模式全放行且为显式放行`() {
        val decision = PermissionDecider.decide(
            PermissionMode.BYPASS, emptyList(), writeCtx("ShellExecute")
        )
        assertTrue(decision is PermissionDecision.AllowExplicit)
    }

    @Test
    fun `BYPASS 模式下 DENY 规则不生效（全放行是模式硬承诺）`() {
        val decision = PermissionDecider.decide(
            PermissionMode.BYPASS,
            listOf(PermissionRule("*", PermissionEffect.DENY)),
            writeCtx()
        )
        assertTrue(decision is PermissionDecision.AllowExplicit)
    }

    @Test
    fun `DEFAULT 模式基础工具自动放行（只读、普通写、幂等覆写）`() {
        val readOnly = PermissionDecider.decide(
            PermissionMode.DEFAULT, emptyList(), readCtx("CodeRead")
        )
        // 普通编辑写（非破坏非幂等）：可恢复操作，静默放行
        val plainWrite = PermissionDecider.decide(
            PermissionMode.DEFAULT, emptyList(), writeCtx("CodeEditFile")
        )
        // 幂等覆写（破坏但可重放恢复，如 Write）：不算删除类，放行
        val idempotentOverwrite = PermissionDecider.decide(
            PermissionMode.DEFAULT, emptyList(),
            PermissionContext(
                "Write", readOnlyHint = false,
                destructiveHint = true, sensitiveAction = false, idempotentHint = true
            )
        )
        assertTrue(readOnly is PermissionDecision.AllowDefault)
        assertTrue(plainWrite is PermissionDecision.AllowDefault)
        assertTrue(idempotentOverwrite is PermissionDecision.AllowDefault)
    }

    @Test
    fun `DEFAULT 模式删除类与敏感动作仍询问`() {
        // 删除类 = 破坏且非幂等（delete / uninstall / force-stop 族）
        val deleteLike = PermissionDecider.decide(
            PermissionMode.DEFAULT, emptyList(),
            PermissionContext("DeleteFile", readOnlyHint = false, destructiveHint = true, sensitiveAction = false)
        )
        assertTrue(deleteLike is PermissionDecision.Ask)
        // 敏感动作（如读取通知）即使只读也值得一次询问——但只读判定在前，
        // 敏感读（readOnlyHint=true）走 AllowDefault 交后续风险门把关
        val sensitiveWrite = PermissionDecider.decide(
            PermissionMode.DEFAULT, emptyList(),
            PermissionContext("sensitive_write", readOnlyHint = false, destructiveHint = false, sensitiveAction = true)
        )
        assertTrue(sensitiveWrite is PermissionDecision.Ask)
        val sensitiveRead = PermissionDecider.decide(
            PermissionMode.DEFAULT, emptyList(),
            PermissionContext("NotificationRead", readOnlyHint = true, destructiveHint = false, sensitiveAction = true)
        )
        assertTrue(sensitiveRead is PermissionDecision.AllowDefault)
    }

    @Test
    fun `ACCEPT_EDITS 模式自动放行编辑写类工具`() {
        val decision = PermissionDecider.decide(
            PermissionMode.ACCEPT_EDITS, emptyList(), writeCtx("CodeEditFile")
        )
        assertTrue(decision is PermissionDecision.AllowExplicit)
    }

    @Test
    fun `ACCEPT_EDITS 模式只读放行而非编辑写类仍询问`() {
        val readOnly = PermissionDecider.decide(
            PermissionMode.ACCEPT_EDITS, emptyList(), readCtx("CodeRead")
        )
        val notEditLike = PermissionDecider.decide(
            PermissionMode.ACCEPT_EDITS, emptyList(), writeCtx("ShellExecute")
        )
        assertTrue(readOnly is PermissionDecision.AllowExplicit)
        assertTrue(notEditLike is PermissionDecision.Ask)
    }

    @Test
    fun `技能与MCP常规能力工具在ACCEPT_EDITS下不再询问`() {
        // 2026-10 用户反馈：skill 与 mcp 全部不再确认使用 —— 使用技能/MCP
        // 是 Agent 的常规动作，ACCEPT_EDITS 下与编辑写类同口径自动放行。
        val skillRun = PermissionDecider.decide(
            PermissionMode.ACCEPT_EDITS, emptyList(), writeCtx("skill_execute")
        )
        val skillActivate = PermissionDecider.decide(
            PermissionMode.ACCEPT_EDITS, emptyList(), writeCtx("skill_activate")
        )
        val mcpCall = PermissionDecider.decide(
            PermissionMode.ACCEPT_EDITS, emptyList(), writeCtx("mcp__github__create_issue")
        )
        assertTrue(skillRun is PermissionDecision.AllowExplicit)
        assertTrue(skillActivate is PermissionDecision.AllowExplicit)
        assertTrue(mcpCall is PermissionDecision.AllowExplicit)
        // 规则仍可越级收紧：显式 ASK 规则首匹配优先于豁免
        val ruleTightened = PermissionDecider.decide(
            PermissionMode.ACCEPT_EDITS,
            listOf(PermissionRule("skill_execute", PermissionEffect.ASK)),
            writeCtx("skill_execute")
        )
        assertTrue(ruleTightened is PermissionDecision.Ask)
        // PLAN 模式只读承诺不豁免：非只读技能调用照旧拒绝
        val planDenied = PermissionDecider.decide(
            PermissionMode.PLAN, emptyList(), writeCtx("skill_execute")
        )
        assertTrue(planDenied is PermissionDecision.Deny)
        // DEFAULT 模式本就默认放行（mutating 非删除类）——口径不变
        val defaultAllowed = PermissionDecider.decide(
            PermissionMode.DEFAULT, emptyList(), writeCtx("mcp__srv__tool")
        )
        assertTrue(defaultAllowed is PermissionDecision.AllowDefault)
    }

    @Test
    fun `PLAN 模式只读工具默认放行`() {
        val decision = PermissionDecider.decide(
            PermissionMode.PLAN, emptyList(), readCtx("CodeRead")
        )
        assertTrue(decision is PermissionDecision.AllowDefault)
    }

    @Test
    fun `PLAN 模式拒绝非只读并给出中文原因`() {
        val decision = PermissionDecider.decide(
            PermissionMode.PLAN, emptyList(), writeCtx("CodeEditFile")
        )
        val deny = decision as PermissionDecision.Deny
        assertTrue(deny.reason.contains("只读"))
        assertTrue(deny.reason.contains("CodeEditFile"))
    }

    @Test
    fun `PLAN 模式下 ALLOW 规则不可越级放行写操作`() {
        val decision = PermissionDecider.decide(
            PermissionMode.PLAN,
            listOf(PermissionRule("*", PermissionEffect.ALLOW)),
            writeCtx()
        )
        assertTrue(decision is PermissionDecision.Deny)
    }

    // ══════════════════════ 规则语义 ══════════════════════

    @Test
    fun `ALLOW 规则越级放行 DEFAULT 模式下的非只读工具`() {
        val decision = PermissionDecider.decide(
            PermissionMode.DEFAULT,
            listOf(PermissionRule("ShellExecute", PermissionEffect.ALLOW)),
            writeCtx("ShellExecute")
        )
        assertTrue(decision is PermissionDecision.AllowExplicit)
    }

    @Test
    fun `规则按序首条生效（精确 DENY 先于通配 ALLOW）`() {
        val decision = PermissionDecider.decide(
            PermissionMode.ACCEPT_EDITS,
            listOf(
                PermissionRule("CodeEditFile", PermissionEffect.DENY),
                PermissionRule("CodeEdit*", PermissionEffect.ALLOW)
            ),
            writeCtx("CodeEditFile")
        )
        assertTrue(decision is PermissionDecision.Deny)
    }

    @Test
    fun `尾缀通配匹配 MCP 动态注册工具 id`() {
        // v5：MCP 一等工具 id 形如 Mcp{Server}{Tool}，前缀通配照样命中运行期才知道的 id
        assertTrue(PermissionRuleMatcher.matches("McpGithub*", "McpGithubCreateIssue"))
        assertTrue(PermissionRuleMatcher.matches("CodeGit*", "CodeGitCommit"))
        // PascalCase 无分隔符：通配是纯前缀语义，跨族名（CodeGrep）才是反例
        assertFalse(PermissionRuleMatcher.matches("CodeGit*", "CodeGrep"))
        // 决策层集成：通配规则放行运行期才知道 id 的 MCP 写工具
        val decision = PermissionDecider.decide(
            PermissionMode.DEFAULT,
            listOf(PermissionRule("McpGithub*", PermissionEffect.ALLOW)),
            writeCtx("McpGithubCreateIssue")
        )
        assertTrue(decision is PermissionDecision.AllowExplicit)
    }

    @Test
    fun `单独星号规则匹配一切工具`() {
        assertTrue(PermissionRuleMatcher.matches("*", "ShellExecute"))
        assertTrue(PermissionRuleMatcher.matches("*", "McpAnythingGoes"))
        val decision = PermissionDecider.decide(
            PermissionMode.DEFAULT,
            listOf(PermissionRule("*", PermissionEffect.ALLOW)),
            writeCtx("ShellExecute")
        )
        assertTrue(decision is PermissionDecision.AllowExplicit)
    }

    @Test
    fun `空模式规则不匹配任何工具`() {
        assertFalse(PermissionRuleMatcher.matches("", "CodeEdit"))
        assertFalse(PermissionRuleMatcher.matches("   ", "CodeEdit"))
        // 规则不生效 → 落到模式默认；删除类上下文（destructive 且非幂等）
        // 在 DEFAULT 下按模式默认询问，断言意图不变：空规则既不放行也不拒绝
        val decision = PermissionDecider.decide(
            PermissionMode.DEFAULT,
            listOf(PermissionRule("", PermissionEffect.ALLOW)),
            PermissionContext("DeleteFile", readOnlyHint = false, destructiveHint = true, sensitiveAction = false)
        )
        assertTrue(decision is PermissionDecision.Ask)
    }

    @Test
    fun `精确匹配大小写敏感`() {
        assertTrue(PermissionRuleMatcher.matches("CodeEdit", "CodeEdit"))
        assertFalse(PermissionRuleMatcher.matches("Code_Edit", "CodeEdit"))
    }

    @Test
    fun `ASK 规则对只读工具同样越级收紧触发询问`() {
        val decision = PermissionDecider.decide(
            PermissionMode.ACCEPT_EDITS,
            listOf(PermissionRule("CodeRead", PermissionEffect.ASK)),
            readCtx("CodeRead")
        )
        assertTrue(decision is PermissionDecision.Ask)
    }

    @Test
    fun `编辑写类前缀判定覆盖正反例`() {
        val editLike = listOf(
            "CodeEditFile", "CodeWriteBlock", "CodeGitCommit",
            "CodeGitBranchCreate", "Write", "Edit",
            "DeleteFile", "CopyMoveFile"
        )
        editLike.forEach { id ->
            assertTrue("应判定为编辑类：$id", PermissionDecider.isEditLike(id))
        }
        val notEditLike = listOf(
            "CodeRead", "CodeSearch", "CodeTask",
            "CodeGitStatus", "CodeGitDiff", "CodeGitLog",
            "ShellExecute", "WebFetch"
        )
        notEditLike.forEach { id ->
            assertFalse("不应判定为编辑类：$id", PermissionDecider.isEditLike(id))
        }
        assertEquals(8, PermissionDecider.EDIT_LIKE_PREFIXES.size)
    }

    // ══════════════════════ 门：询问闭环与会话记忆 ══════════════════════

    @Test
    fun `allow_session 授权被记忆第二次静默放行`() = runTest {
        val gateway = FakeGateway { q -> AgentAnswer(questionId = q.id, selectedOptionId = "allow_session") }
        val gate = PermissionModeGate(
            gateway,
            { PermissionSnapshot(PermissionMode.DEFAULT) },
            clock = { 42L }
        )
        val tool = deleteLikeTool()

        assertTrue(gate.check(tool, "{}") is GateDecision.Allow)
        assertTrue(gate.check(tool, "{}") is GateDecision.Allow)

        assertEquals(1, gateway.questions.size)
        assertEquals(mapOf("DeleteFile" to 42L), gate.sessionAllowedSnapshot())
    }

    @Test
    fun `allow_once 不记忆下次再问`() = runTest {
        val gateway = FakeGateway { q -> AgentAnswer(questionId = q.id, selectedOptionId = "allow_once") }
        val gate = PermissionModeGate(gateway, { PermissionSnapshot(PermissionMode.DEFAULT) })
        val tool = deleteLikeTool()

        assertTrue(gate.check(tool, "{}") is GateDecision.Allow)
        assertTrue(gate.check(tool, "{}") is GateDecision.Allow)

        assertEquals(2, gateway.questions.size)
        assertTrue(gate.sessionAllowedSnapshot().isEmpty())
    }

    @Test
    fun `拒绝回答映射为拒绝并带模型指引`() = runTest {
        val gateway = FakeGateway { q -> AgentAnswer(questionId = q.id, selectedOptionId = "deny") }
        val gate = PermissionModeGate(gateway, { PermissionSnapshot(PermissionMode.DEFAULT) })

        val decision = gate.check(deleteLikeTool(), "{}")

        val deny = decision as GateDecision.Deny
        assertTrue(deny.reason.contains("用户拒绝"))
        assertTrue(deny.reason.contains("DeleteFile"))
    }

    @Test
    fun `询问通道异常按拒绝处理`() = runTest {
        val gateway = FakeGateway { _ -> throw IllegalStateException("boom") }
        val gate = PermissionModeGate(gateway, { PermissionSnapshot(PermissionMode.DEFAULT) })

        val decision = gate.check(deleteLikeTool(), "{}")

        val deny = decision as GateDecision.Deny
        assertTrue(deny.reason.contains("异常"))
    }

    @Test
    fun `真实 bridge 超时折叠为 skipped 并按拒绝处理`() = runTest {
        val bridge = UserQuestionBridge()
        val gate = PermissionModeGate(bridge, { PermissionSnapshot(PermissionMode.DEFAULT) })
        var result: GateDecision? = null

        val job = launch { result = gate.check(deleteLikeTool(), "{}") }
        advanceUntilIdle()                       // ask 挂起等待回答
        advanceTimeBy(5 * 60 * 1000L + 1_000L)   // 虚拟时间越过 5 分钟超时线
        advanceUntilIdle()
        job.join()

        val deny = result as GateDecision.Deny
        assertTrue(deny.reason.contains("超时"))
    }

    @Test
    fun `bridge 主动取消当前问题同样按拒绝处理`() = runTest {
        val bridge = UserQuestionBridge()
        val gate = PermissionModeGate(bridge, { PermissionSnapshot(PermissionMode.DEFAULT) })
        var result: GateDecision? = null

        val job = launch { result = gate.check(deleteLikeTool(), "{}") }
        advanceUntilIdle()
        bridge.cancelCurrentQuestion()            // skipped 形态回答
        advanceUntilIdle()
        job.join()

        val deny = result as GateDecision.Deny
        assertTrue(deny.reason.contains("超时"))
    }

    @Test
    fun `resetSession 清空会话授权记忆`() = runTest {
        val gateway = FakeGateway { q -> AgentAnswer(questionId = q.id, selectedOptionId = "allow_session") }
        val gate = PermissionModeGate(gateway, { PermissionSnapshot(PermissionMode.DEFAULT) })
        val tool = deleteLikeTool()

        assertTrue(gate.check(tool, "{}") is GateDecision.Allow)
        assertEquals(1, gateway.questions.size)

        gate.resetSession()
        assertTrue(gate.sessionAllowedSnapshot().isEmpty())

        // 记忆已清：同一工具再次触发询问
        assertTrue(gate.check(tool, "{}") is GateDecision.Allow)
        assertEquals(2, gateway.questions.size)
    }

    @Test
    fun `check 与 checkDetailed 两入口语义一致`() = runTest {
        val gateway = FakeGateway { q -> AgentAnswer(questionId = q.id, selectedOptionId = "deny") }
        val gate = PermissionModeGate(gateway, { PermissionSnapshot(PermissionMode.DEFAULT) })
        val tool = deleteLikeTool()

        val viaCheck = gate.check(tool, "{}")
        val viaDetailed = gate.checkDetailed(tool, "{}")

        val denyA = viaCheck as GateDecision.Deny
        val denyB = viaDetailed as PermissionModeGate.DetailedDecision.Denied
        assertEquals(denyA.reason, denyB.reason)
    }

    @Test
    fun `授权问题构造含工具 id 模式提示与参数摘要截断`() = runTest {
        val gateway = FakeGateway { q -> AgentAnswer(questionId = q.id, selectedOptionId = "allow_once") }
        val gate = PermissionModeGate(gateway, { PermissionSnapshot(PermissionMode.ACCEPT_EDITS) })

        gate.checkDetailed(
            FakeTool("ShellExecute", ToolAnnotations.mutating()),
            "x".repeat(500)
        )

        val question = gateway.questions.single()
        assertTrue(question.title.contains("工具执行授权"))
        assertTrue(question.title.contains("ShellExecute"))
        // 模式提示 + 参数摘要截 300 字符
        val description = question.description.orEmpty()
        assertTrue(description.contains("ACCEPT_EDITS"))
        assertTrue(description.contains("x".repeat(300)))
        assertFalse(description.contains("x".repeat(301)))
        // 选项三元组与 RiskAwareToolGate 对齐；禁自定义 / 禁跳过
        assertEquals(listOf("allow_session", "allow_once", "deny"), question.options.map { it.id })
        assertFalse(question.allowCustom)
        assertFalse(question.allowSkip)
    }

    @Test
    fun `PermissionSnapshot 默认值为 DEFAULT 模式空规则`() {
        val snapshot = PermissionSnapshot()
        assertEquals(PermissionMode.DEFAULT, snapshot.mode)
        assertTrue(snapshot.rules.isEmpty())
    }

    // ══════════════════════ 组合器：三路分派 ══════════════════════

    @Test
    fun `显式放行不经过 fallback 风险门`() = runTest {
        val fallback = RecordingFallback()
        val gate = PermissionAwareToolGate(
            PermissionModeGate(
                FakeGateway { q -> AgentAnswer(questionId = q.id, selectedOptionId = "deny") },
                {
                    PermissionSnapshot(
                        PermissionMode.DEFAULT,
                        listOf(PermissionRule("DeleteFile", PermissionEffect.ALLOW))
                    )
                }
            ),
            fallback
        )

        val decision = gate.check(deleteLikeTool(), "{}")

        assertTrue(decision is GateDecision.Allow)
        assertTrue(fallback.checkedToolIds.isEmpty())
    }

    @Test
    fun `默认放行转发 fallback 并透传其决定`() = runTest {
        val fallback = RecordingFallback(GateDecision.Deny("风险门拒绝"))
        val gate = PermissionAwareToolGate(
            PermissionModeGate(
                FakeGateway { q -> AgentAnswer(questionId = q.id, selectedOptionId = "allow_session") },
                { PermissionSnapshot(PermissionMode.DEFAULT) }
            ),
            fallback
        )

        val decision = gate.check(readonlyTool(), "{}")

        val deny = decision as GateDecision.Deny
        assertEquals("风险门拒绝", deny.reason)
        assertEquals(listOf("CodeRead"), fallback.checkedToolIds)
    }

    @Test
    fun `权限拒绝直接拦截不消耗 fallback`() = runTest {
        val fallback = RecordingFallback()
        val gate = PermissionAwareToolGate(
            PermissionModeGate(
                FakeGateway { q -> AgentAnswer(questionId = q.id, selectedOptionId = "allow_session") },
                { PermissionSnapshot(PermissionMode.PLAN) }
            ),
            fallback
        )

        val decision = gate.check(deleteLikeTool(), "{}")

        assertTrue(decision is GateDecision.Deny)
        assertTrue(fallback.checkedToolIds.isEmpty())
    }

    // ══════════════════════ 测试基建 ══════════════════════

    /** 只读上下文（readOnlyHint = true）。 */
    private fun readCtx(toolId: String) =
        PermissionContext(toolId, readOnlyHint = true, destructiveHint = false, sensitiveAction = false)

    /** 普通写上下文（非只读、非破坏、非敏感、非幂等——如 Edit）。 */
    private fun writeCtx(toolId: String = "CodeEditFile") =
        PermissionContext(toolId, readOnlyHint = false, destructiveHint = false, sensitiveAction = false)

    /** 元数据完全可控的假工具（annotations 决策三注解的载体）。 */
    private class FakeTool(
        override val id: String,
        annotations: ToolAnnotations
    ) : AgentTool {
        override val name: String = id
        override val description: String = "测试工具"
        override val parametersSchema: String = "{}"
        override val metadata: ToolMetadata = ToolMetadata(
            id = id,
            category = ToolCategory.UTILITY,
            risk = ToolRisk.LOW,
            annotations = annotations
        )

        override suspend fun execute(arguments: String): String = "ok"
    }

    private fun readonlyTool(id: String = "CodeRead") =
        FakeTool(id, ToolAnnotations.readOnly())

    /**
     * 删除类假工具（破坏且非幂等）：DEFAULT 模式下唯一会触发询问的
     * 非敏感类别 —— 门交互闭环测试的问答载体。
     */
    private fun deleteLikeTool(id: String = "DeleteFile") =
        FakeTool(id, ToolAnnotations.destructive())

    /** 脚本化问答网关：记录全部问题，按脚本回放答案（可抛异常模拟通道故障）。 */
    private class FakeGateway(
        private val responder: (AgentQuestion) -> AgentAnswer
    ) : UserQuestionGateway {
        val questions = mutableListOf<AgentQuestion>()

        override suspend fun ask(question: AgentQuestion): AgentAnswer {
            questions += question
            return responder(question)
        }
    }

    /** 记录型后续门：验证组合器是否转发、转发给了谁。 */
    private class RecordingFallback(
        private val decision: GateDecision = GateDecision.Allow
    ) : ToolExecutionGate {
        val checkedToolIds = mutableListOf<String>()

        override suspend fun check(tool: AgentTool, arguments: String): GateDecision {
            checkedToolIds += tool.id
            return decision
        }
    }
}
