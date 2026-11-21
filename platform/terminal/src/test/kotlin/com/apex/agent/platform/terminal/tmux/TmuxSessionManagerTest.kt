package com.apex.agent.platform.terminal.tmux

import com.apex.agent.platform.terminal.tmux.TmuxSessionManager.Companion.result
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * TmuxSessionManager 单测 —— mock shell-exec 接缝（无需 gradle/ANDROID_HOME/真 tmux）。
 *
 * 验收分层（对齐仓库「纯 JVM 单测可在 CI 跑」的约定）：
 *  - 命令构建与解析：new/list/switch/kill/send/capture 的 argv 与 `tmux ls` 解析；
 *  - 诚实存活契约：`tmux ls` 为权威，登记 ≠ 存活；
 *  - 类型化错误：Unavailable / SessionNotFound / InvalidInput / CommandFailed。
 *
 * 真 tmux 集成测试见文件末尾 @Ignore 块（需 Ubuntu rootfs，CI 不装）。
 */
class TmuxSessionManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 可编程假 exec 接缝：按 tmux 子命令分派，记录收到的命令。 */
    private class FakeExecutor : suspend (String, Long) -> TmuxSessionManager.TmuxCommandResult {
        val commands = mutableListOf<String>()
        var available = true
        var liveSessions: List<String> = emptyList()  // `tmux ls` 的原始行
        var failNext = false

        override suspend fun invoke(command: String, timeoutMs: Long): TmuxSessionManager.TmuxCommandResult {
            commands += command
            if (command.startsWith("command -v")) {
                return if (available) result(0, "/usr/bin/tmux\n", "") else result(1, "", "not found")
            }
            if (command.contains("list-sessions")) {
                if (failNext) return result(1, "", "tmux: server error")
                if (liveSessions.isEmpty()) return result(1, "", "no server running on /tmp/tmux-0/default")
                return result(0, liveSessions.joinToString("\n") + "\n", "")
            }
            if (failNext) return result(1, "", "tmux: simulated failure")
            return result(0, "", "")
        }
    }

    private fun newManager(executor: FakeExecutor): TmuxSessionManager =
        TmuxSessionManager(
            executor = executor,
            store = TmuxSessionStore(File(tmp.root, "tmux/sessions.json")),
            clock = { 1_000L }
        )

    // ── 名称校验 ──

    @Test
    fun `create rejects blank name`() = runTest {
        val ex = newManager(FakeExecutor())
        val out = ex.createSession("")
        assertFalse(out.contains("\"ok\":true"))
        assertTrue(out.contains("InvalidInput"))
    }

    @Test
    fun `create rejects tmux locator chars dot and colon`() = runTest {
        val ex = newManager(FakeExecutor())
        // tmux 目标名禁 `.` `:`（window/pane 定位符）
        assertTrue(ex.createSession("bad.name").contains("InvalidInput"))
        assertTrue(ex.createSession("bad:name").contains("InvalidInput"))
    }

    @Test
    fun `create rejects overlong name`() = runTest {
        val ex = newManager(FakeExecutor())
        val long = "a".repeat(65)
        assertTrue(ex.createSession(long).contains("InvalidInput"))
    }

    // ── tmux 可用性门 ──

    @Test
    fun `unavailable tmux surfaces TmuxUnavailable`() = runTest {
        val exec = FakeExecutor().apply { available = false }
        val ex = newManager(exec)
        val out = ex.createSession("dev")
        assertTrue(out.contains("TmuxUnavailable"))
    }

    // ── 幂等创建 ──

    @Test
    fun `create new session emits new-session with quoted name and cwd`() = runTest {
        val exec = FakeExecutor()
        val ex = newManager(exec)
        val out = ex.createSession("dev", "/workspace/proj")
        assertTrue(out.contains("\"ok\":true"))
        assertTrue(out.contains("\"alreadyExists\":false"))
        assertTrue(out.contains("\"name\":\"dev\""))
        assertTrue(out.contains("\"workingDirectory\":\"/workspace/proj\""))
        val newCmd = exec.commands.first { it.contains("new-session") }
        assertTrue(newCmd.contains("new-session -d -s 'dev'"))
        assertTrue(newCmd.contains("-c '/workspace/proj'"))
    }

    @Test
    fun `create is idempotent when session already live`() = runTest {
        val exec = FakeExecutor().apply {
            liveSessions = listOf("dev: 1 windows (created 100) (attached)")
        }
        val ex = newManager(exec)
        val out = ex.createSession("dev")
        assertTrue(out.contains("\"alreadyExists\":true"))
        // 幂等路径不应再发 new-session
        assertTrue(exec.commands.none { it.contains("new-session") })
    }

    // ── 列表（权威 = tmux ls）──

    @Test
    fun `list parses live sessions and marks active from store`() = runTest {
        val exec = FakeExecutor().apply {
            liveSessions = listOf(
                "dev: 2 windows (created 1000) (attached)",
                "build: 1 windows (created 2000)"
            )
        }
        val ex = newManager(exec)
        // 先创建 build 让 store 记 lastActive（创建走幂等，因 build 已 live）
        ex.createSession("build")
        val out = ex.listSessions()
        assertTrue(out.contains("\"count\":2"))
        assertTrue(out.contains("\"name\":\"dev\""))
        assertTrue(out.contains("\"name\":\"build\""))
        assertTrue(out.contains("\"activeSession\":\"build\""))
    }

    @Test
    fun `list returns empty on no-server without throwing`() = runTest {
        val exec = FakeExecutor().apply { liveSessions = emptyList() }
        val out = newManager(exec).listSessions()
        assertTrue(out.contains("\"ok\":true"))
        assertTrue(out.contains("\"count\":0"))
    }

    @Test
    fun `list does not resurrect registered-but-dead sessions`() = runTest {
        val exec = FakeExecutor()
        val ex = newManager(exec)
        ex.createSession("dev")                       // 登记进 store
        exec.liveSessions = listOf("other: 1 windows (created 1)")  // dev 真实已死
        val out = ex.listSessions()
        assertFalse(out.contains("\"name\":\"dev\""))   // 存活权威 ≠ 登记
        assertTrue(out.contains("\"name\":\"other\""))
    }

    // ── 切换 ──

    @Test
    fun `switch marks live session active`() = runTest {
        val exec = FakeExecutor().apply {
            liveSessions = listOf("dev: 1 windows (created 1)", "build: 1 windows (created 1)")
        }
        val ex = newManager(exec)
        val out = ex.switchSession("build")
        assertTrue(out.contains("\"ok\":true"))
        assertTrue(out.contains("\"activeSession\":\"build\""))
        assertEquals("build", ex.listSessions().let {
            Regex("\"activeSession\":\"([^\"]+)\"").find(it)!!.groupValues[1]
        })
    }

    @Test
    fun `switch unknown session surfaces SessionNotFound`() = runTest {
        val exec = FakeExecutor().apply { liveSessions = listOf("dev: 1 windows (created 1)") }
        val out = newManager(exec).switchSession("ghost")
        assertTrue(out.contains("SessionNotFound"))
    }

    // ── send / capture / kill ──

    @Test
    fun `sendKeys collapses newlines and quotes payload`() = runTest {
        val exec = FakeExecutor().apply { liveSessions = listOf("dev: 1 windows (created 1)") }
        val out = newManager(exec).sendKeys("dev", "echo 'hi'\nls -la")
        assertTrue(out.contains("\"ok\":true"))
        val sendCmd = exec.commands.first { it.contains("send-keys") }
        assertTrue(sendCmd.contains("send-keys -t 'dev' "))
        assertTrue(sendCmd.trimEnd().endsWith("Enter"))
        // 换行折叠为空格 + 单引号转义
        assertFalse(sendCmd.contains("\n"))
        assertTrue(sendCmd.contains("'\\''"))
    }

    @Test
    fun `sendKeys blank command rejected`() = runTest {
        val exec = FakeExecutor().apply { liveSessions = listOf("dev: 1 windows (created 1)") }
        val out = newManager(exec).sendKeys("dev", "   ")
        assertTrue(out.contains("InvalidInput"))
    }

    @Test
    fun `capturePane bounds lines and echoes output`() = runTest {
        val exec = FakeExecutor().apply {
            liveSessions = listOf("dev: 1 windows (created 1)")
        }
        // capture 直接返回 stdout（本假接缝对 capture 返回空 —— 校验 lines 与命令形状）
        val out = newManager(exec).capturePane("dev", lines = 999_999)
        assertTrue(out.contains("\"ok\":true"))
        val capCmd = exec.commands.first { it.contains("capture-pane") }
        assertTrue(capCmd.contains("capture-pane -t 'dev' -p -S -10000"))  // 夹到上界
    }

    @Test
    fun `killSession removes registration and clears active`() = runTest {
        val exec = FakeExecutor().apply {
            liveSessions = listOf("dev: 1 windows (created 1)")
        }
        val ex = newManager(exec)
        ex.switchSession("dev")   // 记 lastActive
        val out = ex.killSession("dev")
        assertTrue(out.contains("\"ok\":true"))
        assertTrue(out.contains("\"name\":\"dev\""))
        val killCmd = exec.commands.first { it.contains("kill-session") }
        assertTrue(killCmd.contains("kill-session -t 'dev'"))
    }

    @Test
    fun `kill unknown session surfaces SessionNotFound`() = runTest {
        val exec = FakeExecutor().apply { liveSessions = emptyList() }
        val out = newManager(exec).killSession("ghost")
        assertTrue(out.contains("SessionNotFound"))
    }

    // ── 解析器 ──

    @Test
    fun `parseListOutput skips malformed lines`() {
        val ex = newManager(FakeExecutor())
        val sessions = ex.parseListOutput(
            "dev: 2 windows (created 100) (attached)\n\nbogus-line-no-tabs\nbuild: 1 windows (created 200)"
        )
        assertEquals(listOf("dev", "build"), sessions.map { it.name })
        assertTrue(sessions.first().attached)
        assertFalse(sessions.last().attached)
    }

    // ───────────────────────── 真 tmux 集成（CI 不跑）─────────────────────────

    @Test
    @Ignore("需要 Ubuntu rootfs —— CI 纯 JVM 环境无 tmux；在已 ensure 的 guest 内手动验证")
    fun `integration real tmux new list switch kill`() = runTest {
        val realExecutor: suspend (String, Long) -> TmuxSessionManager.TmuxCommandResult =
            { cmd, _ ->
                val p = ProcessBuilder("sh", "-c", cmd).start()
                val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
                val err = p.errorStream.readBytes().toString(Charsets.UTF_8)
                p.waitFor()
                TmuxSessionManager.TmuxCommandResult(p.exitValue(), out, err)
            }
        val mgr = TmuxSessionManager(
            executor = realExecutor,
            store = TmuxSessionStore(File(tmp.root, "tmux-int/sessions.json"))
        )
        mgr.createSession("itest")
        assertTrue(mgr.listSessions().contains("\"name\":\"itest\""))
        mgr.killSession("itest")
        assertFalse(mgr.listSessions().contains("\"name\":\"itest\""))
    }
}
