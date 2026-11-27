package com.apex.agent.tools

import com.apex.agent.platform.privilege.PrivilegeDetector
import com.apex.agent.platform.privilege.shizuku.DeviceChannel
import com.apex.agent.platform.privilege.shizuku.DeviceExecRequest
import com.apex.agent.platform.privilege.shizuku.DeviceExecResult
import com.apex.agent.platform.privilege.shizuku.ShizukuCommandExecutor
import com.apex.agent.platform.privilege.shizuku.ShizukuProcessChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.Reader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ═══ DCP Shizuku argv 直调通道（S08）═══
 *
 * 结构化意图的唯一执行通道：argv **逐元素**经 `IShizukuService.newProcess`
 * 直调，绝不包装 `sh -c` —— 拼串注入面就此关闭（S02 manifest 的白名单
 * 校验 + 本通道的 argv 语义共同构成纵深防御）。
 *
 * 诚实纪律（对齐 ShizukuCommandExecutor 的既有口径）：
 * - 未启动 / 未授权 → 结构化 `DEVICE_PERMISSION_REQUIRED` + 开通步骤，
 *   **绝不**回退本地 app-shell 冒充 Shizuku；
 * - binder 死亡 / SecurityException → 主动 [PrivilegeDetector.invalidateCache]
 *   （防 30s 缓存误报），再结构化失败；
 * - 超时：分片轮询（100ms slice）+ 强杀 + 2s 排空宽限，`timedOut=true`
 *   + 部分输出如实标注；
 * - 取消：[DeviceExecRequest] 的 isCancelled 回调命中 → destroy 收尾，
 *   `cancelled=true` 结构化返回；
 * - 输出：首 N + 尾 M 有界采集（对齐 ProotExecutor.BoundedOutputCapture），
 *   超预算 `truncated=true`；
 * - `supportsSignal=false` 恒如实（IRemoteProcess 无进程组信号语义）。
 *
 * [spawner] 注入点是测试缝：单测用手写 fake Process 验证 argv 逐元素
 * 相等（没有 sh / -c）、超时、取消与截断分支；生产接线指向
 * [ShizukuProcessChannel.start]。
 */
class ShizukuDeviceChannel(
    private val grantCheck: () -> Boolean =
        { ShizukuCommandExecutor.isAvailable() && ShizukuCommandExecutor.hasPermission() },
    private val invalidateCache: () -> Unit = PrivilegeDetector::invalidateCache,
    private val spawner: (Array<String>, Array<String>?, String?) -> Process =
        { argv, env, dir -> ShizukuProcessChannel.start(argv, env, dir) }
) : DeviceChannel {

    override suspend fun execute(
        req: DeviceExecRequest,
        isCancelled: () -> Boolean
    ): DeviceExecResult = withContext(Dispatchers.IO) {
        if (!grantCheck()) {
            return@withContext unavailable()
        }
        var process: Process? = null
        var stdinWriter: Thread? = null
        try {
            val proc = try {
                spawner(req.argv, req.env, req.dir)
            } catch (e: SecurityException) {
                invalidateCache()
                return@withContext denied()
            } catch (e: IllegalStateException) {
                // ShizukuProcessChannel.start 在 binder 死亡 / 服务不可用时抛出。
                invalidateCache()
                return@withContext unavailable(cause = e.message)
            }
            process = proc

            val stdoutCapture = BoundedTextCapture(req.maxOutputBytes)
            val stderrCapture = BoundedTextCapture(req.maxOutputBytes)
            val drain = listOf(
                Thread { streamCopy(proc.inputStream.bufferedReader(), stdoutCapture) },
                Thread { streamCopy(proc.errorStream.bufferedReader(), stderrCapture) }
            )
            drain.forEach { it.isDaemon = true; it.start() }

            if (req.stdinUtf8 != null) {
                stdinWriter = Thread {
                    runCatching {
                        proc.outputStream.use { out ->
                            out.write(req.stdinUtf8.toByteArray(Charsets.UTF_8))
                            out.flush()
                        }
                    }
                }.also { it.isDaemon = true; it.start() }
            }

            // 分片轮询：每片检查取消与超时；超时强杀 + 排空宽限后返回部分输出。
            val deadline = System.currentTimeMillis() + req.timeoutMs
            var completed = false
            while (!completed) {
                if (isCancelled()) {
                    proc.destroy()
                    joinQuietly(drain, DRAIN_GRACE_MS)
                    return@withContext result(
                        ok = false, proc, stdoutCapture, stderrCapture,
                        cancelled = true
                    )
                }
                completed = proc.waitFor(WAIT_SLICE_MS, TimeUnit.MILLISECONDS)
                if (!completed && System.currentTimeMillis() >= deadline) {
                    proc.destroy()
                    joinQuietly(drain, DRAIN_GRACE_MS)
                    return@withContext result(
                        ok = false, proc, stdoutCapture, stderrCapture,
                        timedOut = true
                    )
                }
            }

            joinQuietly(drain, DRAIN_GRACE_MS)
            return@withContext result(ok = proc.exitValue() == 0, proc, stdoutCapture, stderrCapture)
        } catch (e: CancellationException) {
            // 协程取消：destroy 收尾（不折叠成文本结果，保持取消语义传播）。
            process?.destroy()
            throw e
        } catch (e: Exception) {
            return@withContext DeviceExecResult(
                ok = false,
                stdout = "",
                stderr = "",
                error = "EXEC_FAILED",
                hint = "Shizuku 通道执行异常：${e::class.simpleName}: ${e.message}"
            )
        } finally {
            runCatching { stdinWriter?.join(1_000) }
            process?.let { if (it.isAlive) runCatching { it.destroyForcibly() } }
        }
    }

    private fun result(
        ok: Boolean,
        proc: Process,
        stdout: BoundedTextCapture,
        stderr: BoundedTextCapture,
        timedOut: Boolean = false,
        cancelled: Boolean = false
    ): DeviceExecResult = DeviceExecResult(
        ok = ok,
        pid = null,
        exitCode = if (timedOut || cancelled) null else runCatching { proc.exitValue() }.getOrNull(),
        stdout = stdout.snapshot(),
        stderr = stderr.snapshot(),
        truncated = stdout.truncated || stderr.truncated,
        timedOut = timedOut,
        cancelled = cancelled,
        channel = CHANNEL
    )

    private fun unavailable(cause: String? = null): DeviceExecResult = DeviceExecResult(
        ok = false,
        error = "DEVICE_PERMISSION_REQUIRED",
        hint = buildString {
            append("Shizuku 未运行或未授权")
            cause?.let { append("（$it）") }
            append("。开通步骤：启动 Shizuku 应用 → 在 Shizuku 内授权本应用 → 重新调用。")
        }
    )

    private fun denied(): DeviceExecResult = DeviceExecResult(
        ok = false,
        error = "DEVICE_PERMISSION_REQUIRED",
        hint = "Shizuku 权限被拒绝（授权可能已被收回）。请在 Shizuku 应用中重新授权本应用后重试。"
    )

    private fun streamCopy(reader: Reader, capture: BoundedTextCapture) {
        runCatching {
            val br = reader as? BufferedReader ?: BufferedReader(reader)
            val buf = CharArray(4096)
            while (true) {
                val n = br.read(buf)
                if (n < 0) break
                capture.append(buf, n)
            }
        }
        runCatching { reader.close() }
    }

    private fun joinQuietly(threads: List<Thread>, graceMs: Long) {
        for (t in threads) {
            runCatching { t.join(graceMs) }
        }
    }

    /** 首 N + 尾 M 有界文本采集（对齐 ProotExecutor.BoundedOutputCapture 语义）。 */
    internal class BoundedTextCapture(budget: Long) {
        private val head = StringBuilder()
        private val tail = StringBuilder()
        private val halfBudget: Int =
            (budget / 2).coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)

        @Synchronized
        fun append(buf: CharArray, len: Int) {
            if (len <= 0) return
            if (!truncated) {
                val remaining = halfBudget - head.length
                if (len <= remaining) {
                    head.append(buf, 0, len)
                } else {
                    head.append(buf, 0, remaining)
                    tail.append(buf, remaining, len - remaining)
                    truncated = true
                }
            } else {
                tail.append(buf, 0, len)
                if (tail.length > halfBudget) tail.delete(0, tail.length - halfBudget)
            }
        }

        var truncated: Boolean = false
            private set

        @Synchronized
        fun snapshot(): String =
            if (truncated) "$head\n...[输出超预算，中间部分已截断]...\n$tail" else head.toString()
    }

    internal companion object {
        const val CHANNEL = "shizuku"
        private const val WAIT_SLICE_MS = 100L
        private const val DRAIN_GRACE_MS = 2_000L
    }
}
