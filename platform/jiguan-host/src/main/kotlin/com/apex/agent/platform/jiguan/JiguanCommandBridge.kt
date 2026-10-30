package com.apex.agent.platform.jiguan

import com.apex.agent.core.jiguan.JiguanCommandOutput
import com.apex.agent.core.jiguan.JiguanCommandRunner
import com.apex.agent.platform.TerminalExec.ExecEngine
import com.apex.agent.platform.TerminalExec.ExecRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * # 机关命令桥（ExecEngine → JiguanCommandRunner）
 *
 * 与 REA 的 ReaCommandRunnerImpl 同一条执行链（PRoot 优先 → su > Shizuku >
 * local-sh 回落），机关的全部 shell 面（dd / tcpdump / objdump / frida CLI /
 * getenforce / service）复用该桥 —— 超时 / 双流采集 / 退出码诚实性全继承。
 *
 * argv 形态拼回单字符串：参数经 [shellQuoteArg]（单引号闭合转义）防注入。
 */
class JiguanCommandBridge(
    private val engine: ExecEngine,
) : JiguanCommandRunner {

    override suspend fun run(command: List<String>, timeoutMs: Long): Result<JiguanCommandOutput> =
        withContext(Dispatchers.IO) {
            try {
                val joined = command.joinToString(" ") { shellQuoteArg(it) }
                val result = engine.execute(
                    ExecRequest(command = joined, timeoutMs = timeoutMs, maxOutputChars = 48_000),
                )
                Result.success(
                    JiguanCommandOutput(
                        command = command,
                        stdout = result.stdout,
                        stderr = result.stderr,
                        exitCode = result.exitCode ?: -1,
                        durationMs = result.durationMs,
                        channel = engine.channel,
                    ),
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(Exception("机关命令执行失败（${engine.channel}）: ${e.message ?: e.javaClass.simpleName}"))
            }
        }

    /** POSIX 单引号参数（与 CommandBlueprints.shellQuote 同语义，桥内自持防漂移）。 */
    private fun shellQuoteArg(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
