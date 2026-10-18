package com.apex.agent.core.code.loom.permission

import com.apex.agent.core.code.loom.LoomArg
import com.apex.agent.core.code.loom.LoomInvocation
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LoomPermissionGovernor 权限治理测试：
 *
 * - 模态兜底矩阵（OBSERVE/FABRIC/UNBOUND × READ/WRITE/敏感/越界）；
 * - 显式规则优先级（命令规则优先 / 后规则覆盖 / glob 家族）；
 * - 会话批准记忆（工具级 / 命令前缀级 / 清空）；
 * - 工作区边界（宿主路径 / guest 挂载换算 / 相对路径 / 越界检测）；
 * - 风险画像册（内置 / 启发 / MCP 保守）。
 */
class LoomPermissionGovernorTest {

    private fun inv(
        name: String,
        vararg args: Pair<String, String>
    ): LoomInvocation = LoomInvocation(
        name = name,
        args = args.map { LoomArg(it.first, it.second) }
    )

    private fun governor(
        mode: LoomAccessMode = LoomAccessMode.FABRIC,
        rules: List<LoomPermissionRule> = emptyList(),
        commandRules: List<LoomPermissionRule> = emptyList()
    ): LoomPermissionGovernor {
        val snapshot = LoomPermissionSnapshot(mode = mode, rules = rules, commandRules = commandRules)
        return LoomPermissionGovernor(LoomPermissionSource { snapshot })
    }

    // ═══════════════════════════ 模态兜底矩阵 ═══════════════════════════

    @Test
    fun `read tools are allowed in all modes`() {
        for (mode in LoomAccessMode.entries) {
            val g = governor(mode = mode)
            assertEquals(
                "READ 应放行（$mode）",
                LoomPermissionDecision.Allow,
                g.decide(inv("CodeRead", "path" to "a.kt"), null)
            )
        }
    }

    @Test
    fun `observe mode asks for write tools`() {
        val g = governor(mode = LoomAccessMode.OBSERVE)
        val decision = g.decide(inv("CodeWrite", "path" to "a.kt", "content" to "x"), null)
        assertTrue(decision is LoomPermissionDecision.Ask)
    }

    @Test
    fun `fabric mode allows in-workspace write and asks for out-of-workspace`() {
        val g = governor(mode = LoomAccessMode.FABRIC)
        val root = File("/tmp/ws")
        assertEquals(
            LoomPermissionDecision.Allow,
            g.decide(inv("CodeWrite", "path" to "/tmp/ws/src/a.kt"), root)
        )
        assertEquals(
            LoomPermissionDecision.Allow,
            g.decide(inv("CodeWrite", "path" to "src/a.kt"), root) // 相对路径以根为基
        )
        assertTrue(
            g.decide(inv("CodeWrite", "path" to "/tmp/other/b.kt"), root)
                is LoomPermissionDecision.Ask
        )
    }

    @Test
    fun `unbound mode allows everything except sensitive`() {
        val g = governor(mode = LoomAccessMode.UNBOUND)
        assertEquals(
            LoomPermissionDecision.Allow,
            g.decide(inv("CodeWrite", "path" to "/anywhere/a.kt"), File("/tmp/ws"))
        )
        // CodeGitPush 在内置敏感册
        assertTrue(g.decide(inv("CodeGitPush"), null) is LoomPermissionDecision.Ask)
    }

    @Test
    fun `sensitive tools still ask under fabric`() {
        val g = governor(mode = LoomAccessMode.FABRIC)
        assertTrue(g.decide(inv("CodeGitPush"), null) is LoomPermissionDecision.Ask)
    }

    // ═══════════════════════════ 显式规则 ═══════════════════════════

    @Test
    fun `explicit allow rule overrides mode fallback`() {
        val g = governor(
            mode = LoomAccessMode.OBSERVE,
            rules = listOf(LoomPermissionRule("CodeWrite", LoomRuleEffect.ALLOW))
        )
        assertEquals(LoomPermissionDecision.Allow, g.decide(inv("CodeWrite"), null))
    }

    @Test
    fun `explicit deny rule wins over mode`() {
        val g = governor(
            mode = LoomAccessMode.UNBOUND,
            rules = listOf(LoomPermissionRule("CodeRead", LoomRuleEffect.DENY))
        )
        assertTrue(g.decide(inv("CodeRead"), null) is LoomPermissionDecision.Deny)
    }

    @Test
    fun `later rules override earlier ones`() {
        val g = governor(
            rules = listOf(
                LoomPermissionRule("CodeWrite", LoomRuleEffect.DENY),
                LoomPermissionRule("CodeWrite", LoomRuleEffect.ALLOW)
            )
        )
        assertEquals(LoomPermissionDecision.Allow, g.decide(inv("CodeWrite"), null))
    }

    @Test
    fun `glob rules match tool families`() {
        val g = governor(
            rules = listOf(LoomPermissionRule("CodeGit*", LoomRuleEffect.ASK))
        )
        assertTrue(g.decide(inv("CodeGitCommit"), null) is LoomPermissionDecision.Ask)
        assertTrue(g.decide(inv("CodeGitStatus"), null) is LoomPermissionDecision.Ask)
        assertEquals(
            LoomPermissionDecision.Allow,
            g.decide(inv("CodeRead"), null) // 不受影响
        )
    }

    @Test
    fun `command rules take precedence for shell tools`() {
        val g = governor(
            mode = LoomAccessMode.FABRIC,
            commandRules = listOf(LoomPermissionRule("git push*", LoomRuleEffect.ASK)),
            rules = listOf(LoomPermissionRule("ShellExecute", LoomRuleEffect.ALLOW))
        )
        val push = inv("ShellExecute", "command" to "git push origin main")
        assertTrue(g.decide(push, null) is LoomPermissionDecision.Ask) // 命令规则更具体，优先
        val ls = inv("ShellExecute", "command" to "ls -la")
        assertEquals(LoomPermissionDecision.Allow, g.decide(ls, null)) // 工具规则放行
    }

    @Test
    fun `exact command rule matches whole line prefix with word boundary`() {
        val g = governor(
            commandRules = listOf(LoomPermissionRule("git push", LoomRuleEffect.DENY))
        )
        assertTrue(
            g.decide(inv("ShellExecute", "command" to "git push origin"), null)
                is LoomPermissionDecision.Deny
        )
        // 词边界：git-push 不算
        assertEquals(
            LoomPermissionDecision.Allow,
            g.decide(inv("ShellExecute", "command" to "git-push --help"), null)
        )
    }

    // ═══════════════════════════ 会话批准记忆 ═══════════════════════════

    @Test
    fun `session approval is remembered per tool`() {
        val g = governor(mode = LoomAccessMode.OBSERVE)
        val call = inv("CodeWrite", "path" to "a.kt")
        assertTrue(g.decide(call, null) is LoomPermissionDecision.Ask)
        g.rememberApproval(call)
        assertEquals(LoomPermissionDecision.Allow, g.decide(call, null))
    }

    @Test
    fun `session approval is remembered per command prefix`() {
        val g = governor(mode = LoomAccessMode.OBSERVE)
        val push = inv("ShellExecute", "command" to "git push origin main")
        g.rememberApproval(push)
        // 同前缀（git push）后续直通
        assertEquals(
            LoomPermissionDecision.Allow,
            g.decide(inv("ShellExecute", "command" to "git push other"), null)
        )
        // 不同前缀仍要问
        assertTrue(
            g.decide(inv("ShellExecute", "command" to "git commit -m x"), null)
                is LoomPermissionDecision.Ask
        )
    }

    @Test
    fun `approvals are cleared for new session`() {
        val g = governor(mode = LoomAccessMode.OBSERVE)
        val call = inv("CodeWrite")
        g.rememberApproval(call)
        g.clearApprovals()
        assertTrue(g.decide(call, null) is LoomPermissionDecision.Ask)
    }

    // ═══════════════════════════ 工作区边界 ═══════════════════════════

    @Test
    fun `guest mount paths map back to workspace root`() {
        val g = governor(mode = LoomAccessMode.FABRIC)
        val root = File("/tmp/ws")
        assertEquals(
            LoomPermissionDecision.Allow,
            g.decide(inv("CodeWrite", "path" to "/workspace/src/a.kt"), root)
        )
        assertTrue(
            g.decide(inv("CodeWrite", "path" to "/etc/passwd"), root)
                is LoomPermissionDecision.Ask
        )
    }

    @Test
    fun `any out-of-bound path in batch makes it ask`() {
        val g = governor(mode = LoomAccessMode.FABRIC)
        val root = File("/tmp/ws")
        assertTrue(
            g.decide(
                inv("CodeWrite", "path" to "/tmp/ws/a.kt", "dest" to "/tmp/outside"),
                root
            ) is LoomPermissionDecision.Ask
        )
    }

    @Test
    fun `null workspace root treats everything as within`() {
        val g = governor(mode = LoomAccessMode.FABRIC)
        assertEquals(
            LoomPermissionDecision.Allow,
            g.decide(inv("CodeWrite", "path" to "/anywhere/a.kt"), null)
        )
    }

    // ═══════════════════════════ 风险画像册 ═══════════════════════════

    @Test
    fun `risk book heuristics classify write-ish names`() {
        assertEquals(LoomToolEffect.WRITE, LoomToolRiskBook.effectByHeuristic("MyDeleteThing"))
        assertEquals(LoomToolEffect.WRITE, LoomToolRiskBook.effectByHeuristic("mcp__x__do_thing"))
        assertEquals(LoomToolEffect.READ, LoomToolRiskBook.effectByHeuristic("QueryIndex"))
    }

    @Test
    fun `risk book describes risk tiers`() {
        val g = governor()
        assertEquals("敏感操作", g.describeRisk(inv("CodeGitPush")))
        assertEquals("写操作", g.describeRisk(inv("CodeWrite")))
        assertEquals("只读操作", g.describeRisk(inv("CodeRead")))
    }

    // ═══════════════════════════ 模式匹配族 ═══════════════════════════

    @Test
    fun `pattern matcher covers glob family`() {
        val m = LoomPermissionGovernor.Companion::matchesPattern
        assertTrue(m("CodeRead", "CodeRead"))
        assertFalse(m("CodeRead", "CodeReadX"))
        assertTrue(m("Code*", "CodeRead"))
        assertTrue(m("*Read", "CodeRead"))
        assertTrue(m("Co*ad", "CodeRead"))
        assertTrue(m("*deRea*", "CodeRead"))
        assertFalse(m("Co*ad", "CodeRea"))
        assertTrue(m("git push", "git push origin main"))
        assertFalse(m("git push", "git-push"))
        assertFalse(m("", "x"))
    }
}
