package com.apex.agent.platform.TerminalExec

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * # PersistentFrameChannel —— 长驻进程的长度前缀 JSON 帧通道（DSH Worker 底座）
 *
 * [ExecEngine] 是一次性语义（spawn → waitFor → 销毁）；DSH Native Plugin Host
 * 的持久 Worker 需要长驻进程的**双向帧 IO**（P0 平台底座，2026-11）。本通道：
 *
 * - **帧格式**：4 字节大端长度前缀 + UTF-8 JSON 载荷（LSP 同款）—— 无转义歧义、
 *   无 newline 污染，JSON 里出现任何控制字符都安全；
 * - **单写者同步发送**：length+payload 原子写出（同一把锁），并发 send 顺序化；
 * - **读协程持续排空 stdout**：完整帧回调 [Listener.onFrame]；EOF/半帧/超限/
 *   IO 错误 = **通道破损（fail-fast）**——停读、去重上报 [Listener.onExit]，
 *   绝不假装还能继续收帧；
 * - **stderr 约束**：调试日志必须走分离的 stderr。若通道把 stderr 并进 stdout
 *   （[SpawnedCommand.stderr] == null），日志会污染帧流 —— 本通道在 start 时
 *   如实判破（构造即 exit(cause="stderr not separated")），不提供"尽力解析"；
 * - **不管理代次/请求关联**：generation 与 requestId 关联是协议层（DshWorker
 *   Protocol，P2）职责；本通道只做诚实的字节管道。
 *
 * 纯 JVM（0 android.* import，能力矩阵 13.1 约束）；单测用管道 fake 或真实
 * ProcessBuilder（CI 有 node 时跑端到端 fixture）。
 *
 * @param process 已 spawn 的长驻进程句柄（**不**对其调用 waitFor——持久语义）
 * @param generation 代次标识（诊断日志用；Worker 崩溃重建时递增）
 */
class PersistentFrameChannel(
    private val process: SpawnedCommand,
    val generation: Long,
    private val frameMaxBytes: Int = DEFAULT_FRAME_MAX_BYTES,
    private val listener: Listener,
    private val readerDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    interface Listener {
        /** 完整帧到达（UTF-8 解码后）。仅在通道存活期间回调。 */
        fun onFrame(payload: String)

        /** 通道终态（恰好一次）：进程退出 / 破损 / destroy。 */
        fun onExit(exitCode: Int?, cause: String?)
    }

    /** stdin 写入口（null = 底层通道不支持 —— send 将诚实失败）。 */
    val stdin: OutputStream? = process.stdin

    private val exited = AtomicBoolean(false)
    private val sendLock = Any()
    @Volatile private var destroyed = false

    fun isAlive(): Boolean = !exited.get() && !destroyed

    /** 启动读协程。spawn 的 stderr 必须分离，否则构造即判破（诚实约束，见类 KDoc）。 */
    fun start(readerScope: kotlinx.coroutines.CoroutineScope) {
        if (process.stderr == null) {
            failClosed(exitCode = null, cause = "stderr not separated — frame stream cannot be trusted")
            return
        }
        readerScope.launch(readerDispatcher) { readLoop() }
    }

    /**
     * 发送一帧（UTF-8 JSON 载荷）。length+payload 原子写出；超限/破损/无 stdin
     * 通道 = Result.failure（不静默截断、不假装写入）。
     */
    suspend fun send(payload: String): Result<Unit> = withContext(Dispatchers.IO) {
        val stream = stdin
            ?: return@withContext Result.failure(
                IllegalStateException("channel has no stdin (generation=$generation)")
            )
        val payloadBytes = payload.toByteArray(Charsets.UTF_8)
        if (payloadBytes.size > frameMaxBytes) {
            return@withContext Result.failure(
                IllegalArgumentException(
                    "frame ${payloadBytes.size} bytes exceeds limit $frameMaxBytes (generation=$generation)"
                )
            )
        }
        synchronized(sendLock) {
            if (!isAlive()) {
                return@withContext Result.failure(
                    IllegalStateException("channel closed (generation=$generation)")
                )
            }
            runCatching {
                writeIntBE(stream, payloadBytes.size)
                stream.write(payloadBytes)
                stream.flush()
            }.onFailure {
                // 管道破裂：进程已死 —— 判破并上报，不假装发送成功
                failClosed(exitCode = safeExitCode(), cause = "send failed: ${it.message}")
                return@withContext Result.failure(it)
            }
            Result.success(Unit)
        }
    }

    /** 进程层强杀 + 流关闭（幂等）。exit 事件经读协程 EOF 或此处去重上报。 */
    fun destroy() {
        if (destroyed) return
        destroyed = true
        runCatching { process.destroy() }
        failClosed(exitCode = safeExitCode(), cause = "destroyed")
    }

    // ── 内部 ──

    private suspend fun readLoop() {
        val stdout = process.stdout
        try {
            while (true) {
                val length = readIntBE(stdout) ?: break // EOF = 正常终态
                if (length < 0 || length > frameMaxBytes) {
                    failClosed(safeExitCode(), "frame length $length out of range (0..$frameMaxBytes)")
                    return
                }
                val payload = ByteArray(length)
                if (!readFully(stdout, payload)) {
                    failClosed(safeExitCode(), "EOF mid-frame (expected $length bytes)")
                    return
                }
                // listener 异常不能静默杀死读协程（协程挂了 = 通道假活）—— 判破如实上报
                runCatching { listener.onFrame(String(payload, Charsets.UTF_8)) }
                    .onFailure { failClosed(safeExitCode(), "listener threw: ${it.message}"); return }
            }
            failClosed(safeExitCode(), "stdout EOF")
        } catch (e: java.io.IOException) {
            // 进程被杀/管道破裂 —— 已收内容即终态
            failClosed(safeExitCode(), "stdout IO error: ${e.message}")
        }
    }

    /** 去重上报终态（恰好一次）。 */
    private fun failClosed(exitCode: Int?, cause: String) {
        if (exited.compareAndSet(false, true)) {
            runCatching { process.stdout.close() }
            process.stderr?.let { runCatching { it.close() } }
            process.stdin?.let { runCatching { it.close() } }
            listener.onExit(exitCode, cause)
        }
    }

    /** 进程若已退出拿真实退出码；仍活着（孙进程持有 stdout 等）= null 诚实上报。 */
    private fun safeExitCode(): Int? = runCatching { process.exitValue() }.getOrNull()

    private fun writeIntBE(out: OutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    /** 读 4 字节大端整数；EOF 返回 null（不足 4 字节 = 破损，交调用方判）。 */
    private fun readIntBE(input: InputStream): Int? {
        val buf = ByteArray(4)
        if (!readFully(input, buf)) return null
        return ((buf[0].toInt() and 0xFF) shl 24) or
            ((buf[1].toInt() and 0xFF) shl 16) or
            ((buf[2].toInt() and 0xFF) shl 8) or
            (buf[3].toInt() and 0xFF)
    }

    /** 读满 [dst]；EOF 返回 false（部分读也视为不完整）。 */
    private fun readFully(input: InputStream, dst: ByteArray): Boolean {
        var off = 0
        while (off < dst.size) {
            val n = input.read(dst, off, dst.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    companion object {
        /** 单帧上限（默认 1MB）：工具输出 JSON 是 KB 级；超限帧判破而非截断。 */
        const val DEFAULT_FRAME_MAX_BYTES: Int = 1_000_000

        /** 帧头字节数（诊断/测试用）。 */
        const val HEADER_BYTES: Int = 4

        /** 代次分配器（Worker 管理层共用；进程级唯一即可）。 */
        val GENERATION = AtomicLong(1)
    }
}
