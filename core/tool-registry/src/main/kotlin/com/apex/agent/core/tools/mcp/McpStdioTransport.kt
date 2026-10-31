package com.apex.agent.core.tools.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

// ══════════════════════════════════════════════════════════════════════
//  进程抽象层
// ══════════════════════════════════════════════════════════════════════

/**
 * 被启动起来的 MCP 服务器进程句柄（stdio 型）。
 *
 * 抽象而不是直接用 [Process]，是为了让不同宿主替换"怎么把这个命令跑起来"：
 * - JVM / Android 默认实现 [JvmProcessLauncher]：走 `ProcessBuilder`；
 * - 后续可换成「Ubuntu / PRoot 沙箱」内的 launcher（本项目 platform:terminal
 *   已具备真 rootfs），从而支持 `npx -y @modelcontextprotocol/server-x` 这类命令。
 *
 * 这也是 Operit 的做法的关键结论：**MCP 不一定要是远端 HTTP 服务器**，
 * 官方 MCP 配置里绝大多数 server 其实是 `command` + `args` + `env` 的本地进程，
 * 双方通过 stdin/stdout 上的换行分隔 JSON-RPC 通信。
 */
interface McpProcessHandle {
    val stdin: OutputStream
    val stdout: InputStream
    val stderr: InputStream
    /** #197 真实进程 pid（JVM/PRoot 子进程）；无法获取时 null（如自定义句柄）。 */
    val pid: Long? get() = null
    fun isAlive(): Boolean
    fun destroy()
}

/** 进程启动器接口：把 [command]（含参数）在 [env] 环境下运行起来。 */
fun interface McpProcessLauncher {
    /**
     * @param command 命令 + 参数（第一个元素为可执行文件）
     * @param env 环境变量
     * @param workingDir 工作目录，可为 null
     * @throws Exception 命令不存在或无法执行时抛出（调用方负责转成友好错误）
     */
    fun launch(command: List<String>, env: Map<String, String>, workingDir: File?): McpProcessHandle
}

/**
 * 默认实现：JDK `ProcessBuilder`。
 *
 * - 把子进程 stderr 合并到 stdout 会污染 JSON-RPC 流，因此这里 stderr 独立暴露；
 * - 继承 `IO` 环境变量基础上覆盖 [env]，避免 PATH 丢失导致找不到可执行文件。
 */
object JvmProcessLauncher : McpProcessLauncher {
    override fun launch(
        command: List<String>,
        env: Map<String, String>,
        workingDir: File?
    ): McpProcessHandle {
        require(command.isNotEmpty()) { "命令为空" }
        val builder = ProcessBuilder(command)
        if (env.isNotEmpty()) {
            builder.environment().putAll(env)
        }
        if (workingDir != null) builder.directory(workingDir)
        builder.redirectErrorStream(false)
        // P1 修复（不可行动报错）：Android 宿主没有 node/npx，ProcessBuilder 抛的
        // `Cannot run program "npx": error=ENOENT` 对用户与模型都无从下手。
        // 折叠成带指引的 McpException（沙箱模式才是 Android 上的正解）。
        val process = try {
            builder.start()
        } catch (e: Exception) {
            throw McpException(
                "无法启动本地 MCP 命令 '" + command.first() + "'：" + (e.message ?: e::class.simpleName) +
                    "。Android 宿主通常没有 node/npx —— 请在服务器配置里开启「沙箱运行」" +
                    "（runInSandbox=true）改用 PRoot Ubuntu 内的运行时。"
            )
        }
        return ProcessAdapter(process)
    }

    private class ProcessAdapter(private val process: Process) : McpProcessHandle {
        override val stdin: OutputStream get() = process.outputStream
        override val stdout: InputStream get() = process.inputStream
        override val stderr: InputStream get() = process.errorStream
        override val pid: Long get() = process.pid()
        override fun isAlive(): Boolean = process.isAlive
        override fun destroy() = process.destroy()
    }
}

// ══════════════════════════════════════════════════════════════════════
//  传输句柄
// ══════════════════════════════════════════════════════════════════════

/**
 * MCP 传输句柄 —— 屏蔽"对面到底是一个远端 HTTP 服务还是本地子进程"。
 *
 * 之前 [McpClient] 把所有字节搬运硬编成 HTTP POST，导致 `transport=STDIO`
 * 只是一个枚举值（`/* 暂未实现 */`）：选了 STDIO 仍然去 POST 那根 URL，
 * 于是"支持三种传输"是假的。现在 HTTP/SSE 与 STDIO 各自实现同一接口。
 */
interface McpTransportHandle {
    /**
     * 发送一条 JSON-RPC 报文。
     *
     * 注意：正文里不能出现斜杠+星号的注释起始序列，Kotlin 块注释是可嵌套的，
     * 它会在 KDoc 内再开一层注释、吞掉本应闭合文档的星号+斜杠（本文件曾因此
     * 把后半整个类吃进注释里、编译器报 Unresolved reference）。
     *
     * @param id 请求 id；为 null 表示**通知**（notifications 系事件，如
     *   notifications/initialized），不等待响应
     * @param payload 序列化后的报文全文
     * @return 响应对象；通知返回 null
     */
    suspend fun send(id: Int?, payload: String): JsonObject?

    /** 判断底层通道是否仍然可用（进程存活 / 连接未断）。 */
    fun isHealthy(): Boolean

    fun close()
}

/**
 * stdio 传输：本地进程 + 换行分隔 JSON-RPC（NDJSON）。
 *
 * 实现要点：
 * - 单一读线程把子进程 stdout 里的每一行按 `id` 投放到 [pending]，请求线程在
 *   监视器上等待自己那条 —— 标准 MCP stdio 允许**乱序/穿插通知**，必须按 id 匹配；
 * - 不是 JSON 的行（很多 server 会往 stdout 打日志）只跳过、不抛错，
 *   这也是官方 SDK 的兼容做法；
 * - 进程死掉时等待中的请求会立刻返回 null，上层折叠成明确错误而不是挂死；
 * - #205：stderr 由专职线程常流式清空 —— 子进程往 stderr 写满 64KB 管道
 *   缓冲区会**阻塞自身**（写不进去 = 进程卡死 = 超时假死）；行回调
 *   [onStderrLine] 把真实 stderr 行透传给启动监听器（时间线可见）。
 */
class McpStdioTransport internal constructor(
    private val handle: McpProcessHandle,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val requestTimeoutMs: Long = 60_000L,
    private val onStderrLine: ((String) -> Unit)? = null
) : McpTransportHandle {

    private val writer: BufferedWriter =
        BufferedWriter(OutputStreamWriter(handle.stdin, StandardCharsets.UTF_8))
    private val reader: BufferedReader =
        BufferedReader(InputStreamReader(handle.stdout, StandardCharsets.UTF_8))

    /** P1：专用写线程 —— 阻塞写既不占协程 IO 线程，也能被 Future 超时兜住。 */
    private val writeExecutor: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "mcp-stdio-writer").apply { isDaemon = true }
        }

    /** 写路径是否已判死（写入超时/失败）—— [isHealthy] 据此交给 Supervisor 收尸。 */
    @Volatile
    private var writeBroken = false

    private val pending = ConcurrentHashMap<Int, String>()
    private val pendingAt = ConcurrentHashMap<Int, Long>()
    private val monitor = Object()

    /**
     * 写侧锁：registrar 的异步 tools/list 与 Agent 的 tools/call 会并发写同一
     * writer —— BufferedWriter 无锁时 NDJSON 帧字符级交错撕裂（报文损坏 /
     * id 不匹配 / 莫名超时）。write+flush 必须整体持锁。
     */
    private val writeLock = Object()

    @Volatile
    private var closed = false

    private val pump: Thread = thread(name = "mcp-stdio-pump", isDaemon = true) { pumpLoop() }

    /**
     * #205 stderr 泄放线程：常驻读到 EOF。两个作用：
     * 1. 防管道写满死锁（不清空 → 子进程 stderr 写满 64KB 后自身阻塞）；
     * 2. 行回调把真实 stderr 透传给调用方（启动时间线 / 诊断日志）。
     */
    private val stderrPump: Thread = thread(name = "mcp-stderr-drain", isDaemon = true) {
        val reader = BufferedReader(InputStreamReader(handle.stderr, StandardCharsets.UTF_8))
        try {
            while (!closed) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                runCatching { onStderrLine?.invoke(line) }
            }
        } catch (_: Exception) {
            // 进程销毁时流关闭属正常路径
        }
    }

    constructor(
        command: List<String>,
        env: Map<String, String> = emptyMap(),
        launcher: McpProcessLauncher = JvmProcessLauncher,
        workingDir: File? = null,
        requestTimeoutMs: Long = 60_000L,
        onSpawned: ((pid: Long?, argv: List<String>) -> Unit)? = null,
        onStderrLine: ((String) -> Unit)? = null
    ) : this(
        handle = launcher.launch(command, env, workingDir).also { h ->
            // #197 真实 spawn 事件：进程已 fork，pid/argv 来自真实子进程。
            onSpawned?.invoke(h.pid, command)
        },
        requestTimeoutMs = requestTimeoutMs,
        onStderrLine = onStderrLine
    )

    override suspend fun send(id: Int?, payload: String): JsonObject? = withContext(Dispatchers.IO) {
        if (closed) throw McpException("MCP 本地进程已关闭")
        if (!handle.isAlive()) throw McpException("MCP 本地进程已退出")

        // 报文必须是单行：内容里的换行会撕裂 NDJSON 帧
        val line = payload.replace('\n', ' ').replace('\r', ' ')
        // P1 修复（写路径无超时）：旧实现在 IO 协程里同步 write+flush —— 对端
        // 不读 stdin（npx 卡在下载、被 SIGSTOP）时 flush 永久阻塞、writeLock 被
        // 长期持有，后续所有请求（含 tools/list）排队，上层取消也无效。改为
        // 专用写线程 + 有界 Future.get，超时即判传输不健康（交给 Supervisor 收尸）。
        val writeTask = try {
            writeExecutor.submit {
                synchronized(writeLock) {
                    writer.write(line)
                    writer.write('\n'.code)
                    writer.flush()
                }
            }
        } catch (e: Exception) {
            throw McpException("MCP 本地进程写线程不可用：${e.message ?: e::class.simpleName}")
        }
        try {
            withContext(Dispatchers.IO) {
                writeTask.get(requestTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        } catch (e: java.util.concurrent.TimeoutException) {
            writeTask.cancel(true)
            writeBroken = true
            throw McpException("MCP 本地进程写入超时（${requestTimeoutMs}ms）：对端没有读取 stdin")
        } catch (e: java.util.concurrent.ExecutionException) {
            writeBroken = true
            throw McpException("MCP 本地进程写入失败：${e.cause?.message ?: e.message}")
        }

        if (id == null) return@withContext null

        val raw = awaitResponse(id) ?: throw McpException(
            if (handle.isAlive()) "等待 MCP 进程响应超时（${requestTimeoutMs}ms）"
            else "MCP 进程在响应前退出"
        )
        return@withContext try {
            json.parseToJsonElement(raw) as? JsonObject
                ?: throw McpException("MCP 进程返回了非对象响应")
        } catch (e: SerializationException) {
            throw McpException("MCP 进程返回了非法 JSON：${raw.take(120)}")
        }
    }

    override fun isHealthy(): Boolean = !closed && !writeBroken && handle.isAlive()

    override fun close() {
        closed = true
        synchronized(monitor) {
            pending.clear()
            pendingAt.clear()
            monitor.notifyAll()
        }
        runCatching { writeExecutor.shutdownNow() }
        runCatching { writer.close() }
        // #206 说明：两个泵线程阻塞在不可中断的管道 readLine 上，interrupt()
        // 对它们是无效操作（已删）—— 真正的退出机制是 destroy() 关闭进程管道
        // → readLine 返回 EOF → 泵线程自然退出；daemon 标记兜底进程退出。
        runCatching { handle.destroy() }
        // #453：reader 显式关闭 —— destroy 只关底层进程管道，BufferedReader /
        // InputStreamReader 的字符缓冲不释放；多次重连会话下逐步累积。
        // 关闭时仍在 readLine 的泵线程会收到 StreamClosed → 既有 catch 收尾。
        runCatching { reader.close() }
        runCatching { pump.interrupt() }
    }

    /**
     * 可取消的响应等待（P1 资源耗尽修复）：
     *
     * 旧实现 `monitor.wait` 是不可取消的线程阻塞 —— 上层 ToolRunPolicy 的 90s
     * withTimeout 取消后，IO 线程仍被钉死到 180s STDIO 死限；沙箱服务器挂死 +
     * 模型重试可耗尽 Dispatchers.IO 线程池拖垮全 App IO。改为 delay 轮询：
     * 协程取消立即返回（线程归池），代价仅是最多 50ms 唤醒延迟（相对网络 RTT
     * 可忽略）。
     */
    private suspend fun awaitResponse(id: Int): String? {
        val deadline = System.currentTimeMillis() + requestTimeoutMs
        while (true) {
            synchronized(monitor) { pending.remove(id) }?.let { return it }
            if (closed || !handle.isAlive()) return null
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) return null
            kotlinx.coroutines.delay(minOf(remaining, 50L))
        }
    }

    private fun pumpLoop() {
        while (!closed) {
            val line = try {
                reader.readLine() ?: break
            } catch (_: Exception) {
                break
            }
            if (line.isBlank()) continue
            val id = extractId(line) ?: continue   // 通知 / 非 JSON 行：直接丢弃
            synchronized(monitor) {
                pending[id] = line
                pendingAt[id] = System.currentTimeMillis()
                // 超时未被消费的迟到响应：一直留在 map 里就是慢性泄漏（每个
                // 超时请求泄漏一条），攒到 32 条时清理掉明显过期的条目。
                if (pending.size > 32) {
                    val horizon = System.currentTimeMillis() - requestTimeoutMs * 4
                    pending.entries.removeIf { (k, _) ->
                        (pendingAt[k] ?: 0L) < horizon
                    }
                    pendingAt.entries.removeIf { it.value < horizon }
                }
                monitor.notifyAll()
            }
        }
        // 进程 EOF：唤醒所有等待者，让它们尽快拿到失败结论而不是干等超时
        synchronized(monitor) { monitor.notifyAll() }
    }

    private fun extractId(line: String): Int? = runCatching {
        val obj = json.parseToJsonElement(line) as? JsonObject ?: return null
        obj["id"]?.jsonPrimitive?.intOrNull
    }.getOrNull()
}
