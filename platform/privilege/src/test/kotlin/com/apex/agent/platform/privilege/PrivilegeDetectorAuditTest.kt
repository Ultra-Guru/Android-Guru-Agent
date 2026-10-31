package com.apex.agent.platform.privilege

import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.core.logging.LogRecord
import com.apex.agent.core.logging.LogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T92（#255 权限链审计）—— PrivilegeDetector 逐命令审计日志契约锁。
 *
 * 审计结论「权限链真实被 Agent 利用」要可验证，前提是每一条 ShellExecute
 * 命令都留下结构化的通道痕迹。这里锁定两层契约：
 *
 * 1. **端到端契约**：executeShell 真实执行一条命令（Linux 宿主 sh/su），
 *    无论选路结果是 root / shizuku / shell，都必须产出恰好一条
 *    source=PrivilegeDetector 的 privilege-chain 日志，且消息包含
 *    via=<真实通道> 与 exit=<真实退出码>；
 * 2. **消息形态契约**：多行命令折叠为单行、超长命令截断到
 *    [PrivilegeDetector.MAX_LOG_CMD_LEN]、tag 集合含 privilege-chain 与 via。
 *
 * 环境容错（两轴）：
 * - 探测结果依环境而变（无 su → shell 通道；有 su → root 通道且可能等密码
 *   超时）—— 断言锚定「与返回值一致」而非固定通道；
 * - AppLogger.stream 带 replay=64，订阅时会回放历史记录 —— 过滤一律叠加
 *   `timestamp >= startMs` 时间戳下限，免疫跨测试回放与方法执行顺序。
 */
class PrivilegeDetectorAuditTest {

    @Test
    fun `executeShell 每次执行产出恰好一条审计日志`() = runBlocking {
        val sink = mutableListOf<LogRecord>()
        val collector = launch(Dispatchers.Unconfined) {
            AppLogger.instance.stream.collect { sink.add(it) }
        }
        try {
            val startMs = System.currentTimeMillis()
            val result = PrivilegeDetector.executeShell(
                "echo t92-audit-probe",
                timeoutMs = 3000
            )
            // Unconfined 收集器在 emit 线程同步收到记录；小睡眠兜底调度边界
            Thread.sleep(150)
            val audits = sink.filter {
                it.source == PrivilegeDetector.AUDIT_SOURCE && it.timestamp >= startMs
            }
            assertEquals("executeShell 应产出恰好一条审计日志", 1, audits.size)
            val audit = audits.first()
            assertTrue(audit.message.contains("via=${result.via}"))
            assertTrue(audit.message.contains("exit=${result.exitCode}"))
            assertTrue(audit.message.contains("cmd=echo t92-audit-probe"))
            assertTrue("tag 应含 privilege-chain", "privilege-chain" in audit.tags)
            assertTrue("tag 应含通道名", result.via in audit.tags)
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun `审计消息 多行命令折叠为单行`() = runBlocking {
        val sink = mutableListOf<LogRecord>()
        // 注意：collector 必须在本 runBlocking 块内 cancel —— runBlocking 会等
        // 全部子协程完成，而 SharedFlow.collect 永不完成（曾致死锁，jstack 定位）。
        val collector = launch(Dispatchers.Unconfined) {
            AppLogger.instance.stream.collect { sink.add(it) }
        }
        try {
            val startMs = System.currentTimeMillis()
            PrivilegeDetector.audit(
                ShellExecResult(success = false, output = "x", exitCode = 1, via = "shizuku"),
                durationMs = 42,
                command = "echo line1\n  echo   line2\n"
            )
            Thread.sleep(150)
            val audit = sink.last {
                it.source == PrivilegeDetector.AUDIT_SOURCE && it.timestamp >= startMs
            }
            assertEquals(
                "privilege-chain via=shizuku exit=1 42ms cmd=echo line1 echo line2",
                audit.message
            )
            assertEquals(LogLevel.INFO, audit.level)
            assertEquals(LogCategory.TOOL, audit.category)
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun `审计消息 超长命令截断到上限并带省略号标记`() = runBlocking {
        val sink = mutableListOf<LogRecord>()
        val collector = launch(Dispatchers.Unconfined) {
            AppLogger.instance.stream.collect { sink.add(it) }
        }
        try {
            val startMs = System.currentTimeMillis()
            val longCommand = "a".repeat(500)
            PrivilegeDetector.audit(
                ShellExecResult(success = true, output = "ok", exitCode = 0, via = "root"),
                durationMs = 7,
                command = longCommand
            )
            Thread.sleep(150)
            val audit = sink.last {
                it.source == PrivilegeDetector.AUDIT_SOURCE && it.timestamp >= startMs
            }
            val expectedCmd = "a".repeat(PrivilegeDetector.MAX_LOG_CMD_LEN) + "..."
            assertEquals(
                "privilege-chain via=root exit=0 7ms cmd=$expectedCmd",
                audit.message
            )
        } finally {
            collector.cancel()
        }
    }

    // ── 2026-10 高错误率修复：stderr 保留 + cd 引号 + 跨通道 cwd 守卫 ──

    @Test
    fun `executeShell stdout 非空时 stderr 以标记追加不再丢失`() = runBlocking {
        // 旧实现 `stdout.ifBlank { stderr }`：stdout 非空 → stderr 被静默丢弃，
        // 模型看不到失败原因只能盲重试。现在双非空时以 [stderr] 标记拼接。
        // 命令同时写两流；宿主 sh（测试环境无 root/shizuku）真实执行。
        val result = PrivilegeDetector.executeShell(
            "echo merge-out-probe; echo merge-err-probe 1>&2",
            timeoutMs = 5000
        )
        assertTrue("stdout 应保留", result.output.contains("merge-out-probe"))
        assertTrue(
            "stderr 应以 [stderr] 标记追加（实际: ${result.output}）",
            result.output.contains("[stderr]") && result.output.contains("merge-err-probe")
        )
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `executeShell stderr 独占时行为与旧版一致`() = runBlocking {
        // 只有 stderr 非空 → 输出即 stderr（旧语义不变，git clone 进度类命令）
        val result = PrivilegeDetector.executeShell(
            "echo err-only-probe 1>&2",
            timeoutMs = 5000
        )
        assertTrue(result.output.contains("err-only-probe"))
        assertTrue("[stderr] 标记不应出现（无 stdout）", !result.output.contains("[stderr]"))
    }

    @Test
    fun `跨通道 cwd 守卫 宿主不存在的目录被丢弃不污染命令`() = runBlocking {
        // workDir 指向 Ubuntu guest 路径（如 /workspace）—— 宿主上不存在。
        // 守卫后：不做 cd（命令在默认目录执行），echo 依旧成功；
        // 旧实现 root 通道会拼 `cd /workspace && echo ok` → 语法报错失败。
        val result = PrivilegeDetector.executeShell(
            "echo cwd-guard-probe",
            timeoutMs = 5000,
            workDir = "/workspace"
        )
        assertTrue(
            "守卫应丢弃宿主不存在的 workDir（实际: ${result.output}）",
            result.output.contains("cwd-guard-probe")
        )
    }

    @Test
    fun `shellQuote 单引号安全包裹 POSIX 转义闭环`() {
        // 纯函数：路径含空格/单引号时 cd 前缀不再语法错误
        assertEquals("'/plain/path'", PrivilegeDetector.shellQuote("/plain/path"))
        assertEquals("'path with space'", PrivilegeDetector.shellQuote("path with space"))
        // 内嵌单引号 → close-quote/escaped-quote/reopen-quote 闭环（POSIX 标准转义）
        assertEquals("'it'\\''s'", PrivilegeDetector.shellQuote("it's"))
    }
}
