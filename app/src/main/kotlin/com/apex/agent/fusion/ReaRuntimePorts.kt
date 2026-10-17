package com.apex.agent.fusion

import com.apex.agent.core.tools.fusion.rea.ReaCommandOutput
import com.apex.agent.core.tools.fusion.rea.ReaCommandRunner
import com.apex.agent.core.tools.fusion.rea.ReaPageContent
import com.apex.agent.core.tools.fusion.rea.ReaPageFetcher
import com.apex.agent.platform.TerminalExec.ExecEngine
import com.apex.agent.platform.TerminalExec.ExecRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest

/**
 * # REA 运行时端口的 app 层实现
 *
 * - [ReaCommandRunnerImpl]：桥接到**既有**终端执行链（[ExecEngine] +
 *   ProotCommandSpawner —— 与 ShellExecute/TerminalExec 同一条链）：
 *   PRoot Ubuntu rootfs 就绪 → 沙箱内执行（apt 装的 objdump/rizin/jadx
 *   天然可见）；否则回落 su > Shizuku > local-sh（设备本地 am/pm/dumpsys
 *   直接可用）。超时/双流采集/退出码语义全部复用。
 * - [ReaPageFetcherImpl]：OkHttp 静态 GET（与 WebFetchTool 同栈）。
 */
class ReaCommandRunnerImpl(
    private val engine: ExecEngine
) : ReaCommandRunner {

    override suspend fun run(command: List<String>, timeoutMs: Long): Result<ReaCommandOutput> =
        withContext(Dispatchers.IO) {
            try {
                // ExecRequest.command 为单字符串（shell 形态）—— argv 形态拼回
                val joined = command.joinToString(" ") { "'${it.replace("'", "")}'" }
                val result = engine.execute(
                    ExecRequest(command = joined, timeoutMs = timeoutMs, maxOutputChars = 24_000)
                )
                Result.success(
                    ReaCommandOutput(
                        command = command,
                        stdout = result.stdout,
                        stderr = result.stderr,
                        exitCode = result.exitCode ?: -1,
                        durationMs = result.durationMs,
                        channel = engine.channel
                    )
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(Exception("REA 命令执行失败（${engine.channel}）: ${e.message}"))
            }
        }
}

class ReaPageFetcherImpl(
    private val httpClient: OkHttpClient
) : ReaPageFetcher {

    override suspend fun fetch(url: String, timeoutMs: Long): Result<ReaPageContent> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 ReaFusion/1.0")
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    val digest = MessageDigest.getInstance("SHA-256")
                        .digest(body.toByteArray(Charsets.UTF_8))
                        .joinToString("") { "%02x".format(it) }
                    Result.success(
                        ReaPageContent(
                            url = url,
                            status = response.code,
                            contentType = response.header("Content-Type") ?: "",
                            body = body,
                            bodySha256 = digest
                        )
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(Exception("页面抓取失败: ${e.message}"))
            }
        }
}
