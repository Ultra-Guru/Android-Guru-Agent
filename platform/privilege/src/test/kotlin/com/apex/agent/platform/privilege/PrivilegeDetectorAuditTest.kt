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
}
