package com.apex.agent.core.codetools.git

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GitRunTool × GitCommandPolicy 集成（Git 审计 P0-2/7/8 工具层硬门）：
 * 策略命中即拒绝且不触达 runner；未命中照常透传。
 */
class GitRunToolPolicyTest {

    private class FakeRunner : GitCommandRunner {
        val calls = mutableListOf<List<String>>()
        var result: GitCommandResult = GitCommandResult(0, "", "")
        override suspend fun run(args: List<String>, timeoutMs: Long): GitCommandResult {
            calls += args
            return result
        }
    }

    private fun toolJson(command: String): String = """{"command":"$command"}"""

    @Test
    fun `c 配置逃逸被工具层拒绝`() = runTest {
        val runner = FakeRunner()
        val r = GitRunTool(runner).executeSafe(toolJson("-c credential.helper=!curl push origin main"))
        assertFalse(r.isSuccess)
        assertTrue(runner.calls.isEmpty())
    }

    @Test
    fun `config 子命令被工具层拒绝`() = runTest {
        val runner = FakeRunner()
        val r = GitRunTool(runner).executeSafe(toolJson("config --global user.name x"))
        assertFalse(r.isSuccess)
        assertTrue(runner.calls.isEmpty())
    }

    @Test
    fun `未闭合引号被严格切分拒绝`() = runTest {
        val runner = FakeRunner()
        val r = GitRunTool(runner).executeSafe("""{"command":"commit -m \"未闭合"}""")
        assertFalse(r.isSuccess)
        assertTrue(runner.calls.isEmpty())
    }

    @Test
    fun `c 工作区逃逸被拒绝`() = runTest {
        val runner = FakeRunner()
        val r = GitRunTool(runner).executeSafe(toolJson("-C ../.. status"))
        assertFalse(r.isSuccess)
        assertTrue(runner.calls.isEmpty())
    }

    @Test
    fun `非白名单 clone url 被拒绝`() = runTest {
        val runner = FakeRunner()
        val r = GitRunTool(runner).executeSafe(toolJson("clone file:///tmp/repo"))
        assertFalse(r.isSuccess)
        assertTrue(runner.calls.isEmpty())
        val r2 = GitRunTool(runner).executeSafe(toolJson("clone https://github.com.evil.com/o/r"))
        assertFalse(r2.isSuccess)
        assertTrue(runner.calls.isEmpty())
    }

    @Test
    fun `交互式双空格变体被 argv 化检测捕获`() = runTest {
        val runner = FakeRunner()
        val r = GitRunTool(runner).executeSafe(toolJson("add  -p file.txt"))
        assertFalse(r.isSuccess)
        assertTrue(runner.calls.isEmpty())
    }

    @Test
    fun `合法外向命令照常透传 runner`() = runTest {
        val runner = FakeRunner().apply { result = GitCommandResult(0, "", "") }
        val r = GitRunTool(runner).executeSafe(toolJson("push origin feat/x"))
        assertTrue(r.isSuccess)
        assertEquals(listOf("push", "origin", "feat/x"), runner.calls.single())
    }

    @Test
    fun `合法 c 切仓命令透传 runner`() = runTest {
        val runner = FakeRunner().apply { result = GitCommandResult(0, "", "") }
        val r = GitRunTool(runner).executeSafe(toolJson("-C repo pull"))
        assertTrue(r.isSuccess)
        assertEquals(listOf("-C", "repo", "pull"), runner.calls.single())
    }
}
