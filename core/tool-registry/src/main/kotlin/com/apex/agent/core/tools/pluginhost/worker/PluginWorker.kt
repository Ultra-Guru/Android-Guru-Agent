package com.apex.agent.core.tools.pluginhost.worker

import com.apex.agent.core.tools.pluginhost.worker.WorkerProtocol.Type
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * # PluginWorker —— 持久化插件 Worker（spec §6.2 / §6.3）
 *
 * 一个 Worker = 一个长驻沙箱进程（node/bun）+ 换行 JSON IPC。对比
 * 「每次调用起一个 node 进程」：热调用只走 IPC 往返，模块加载与依赖
 * 初始化只发生一次 —— 手机端 Agent 响应速度的关键（spec 性能目标：
 * 热调用复用 Worker）。
 *
 * 生命周期与线程模型：
 * - **读循环**：独立协程逐行读 STDOUT，按 requestId 关联分发（请求/
 *   响应/事件/心跳），异常永不逃逸到宿主主循环；
 * - **STDERR 观测**：独立协程收干（防管道写满阻塞插件），进事件流 +
 *   环形缓冲（诊断用）；
 * - **写互斥**：stdin 写入经 Mutex 串行化；
 * - **进程退出看护**：退出即终结全部在途请求（失败而非悬挂）并发事件。
 *
 * 协程取消语义：在途请求的协程被取消时，向 Worker 发 cancel 消息
 * （尽力而为）并从关联表移除 —— Worker 侧对已派发的 JS 调用无法真抢占，
 * 这在协议与文档中如实声明。
 */
class PluginWorker(
    val pluginId: String,
    private val process: Process,
    parentScope: CoroutineScope,
    private val config: Config = Config()
) {
    /** Worker 配置（超时与观测容量）。 */
    data class Config(
        val handshakeTimeoutMs: Long = 20_000,
        val requestTimeoutMs: Long = 120_000,
        val shutdownGraceMs: Long = 5_000,
        val stderrRingLines: Int = 200
    )

    /** Worker 运行状态（Supervisor 消费）。 */
    enum class State { STARTING, READY, LOADED, STOPPING, STOPPED }

    @Volatile
    var state: State = State.STARTING
        private set

    /** 进程是否仍存活。 */
    val alive: Boolean get() = process.isAlive

    /** Worker 生命周期事件（stderr 行 / 插件事件 / 退出）。 */
    val events: SharedFlow<WorkerEvent> get() = eventsFlow.asSharedFlow()

    private val eventsFlow = MutableSharedFlow<WorkerEvent>(extraBufferCapacity = 64)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<WorkerProtocol.Message>>()
    private val writeMutex = Mutex()
    private val requestCounter = AtomicLong(0)
    private val stderrRing = ArrayDeque<String>()
    private val stderrLock = Any()

    @Volatile
    private var lastFatalReason: String? = null

    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob())

    init {
        scope.launch(Dispatchers.IO) { readLoop() }
        scope.launch(Dispatchers.IO) { stderrLoop() }
        scope.launch(Dispatchers.IO) { exitWatchLoop() }
    }

    // ── 公开操作 ──────────────────────────────────────────────────────────

    /** 握手（hello→ready）。 */
    suspend fun handshake(): Result<WorkerProtocol.Ready> =
        roundTrip(
            WorkerProtocol.hello(nextRequestId(), pluginId),
            Type.READY,
            timeoutMs = config.handshakeTimeoutMs
        ).mapC { msg ->
            WorkerProtocol.Ready.from(msg)
                ?: error("ready 消息负载损坏")
        }

    /**
     * 加载插件模块（load→loaded）。OpenCode 兼容层载荷（ecosystem/
     * pluginId/pluginDir/options/storageFile）由各生态加载器提供，
     * 通用插件只需 entryPath/format。
     */
    suspend fun load(
        entryPath: String,
        format: String? = null,
        ecosystem: String = "",
        pluginId: String = "",
        pluginDir: String = "",
        optionsJson: String = "{}",
        storageFile: String = ""
    ): Result<WorkerProtocol.Loaded> =
        roundTrip(
            WorkerProtocol.load(
                nextRequestId(), entryPath, format ?: "",
                ecosystem, pluginId, pluginDir, optionsJson, storageFile
            ),
            Type.LOADED
        ).mapC { msg ->
            WorkerProtocol.Loaded.from(msg) ?: error("loaded 消息负载损坏")
        }.onSuccess { lastLoadedResult = it }

    /** 调用插件工具（tool.execute→tool.result），返回 output 文本。 */
    suspend fun callTool(toolName: String, argsJson: String): Result<String> {
        val id = nextRequestId()
        val response = roundTrip(WorkerProtocol.toolExecute(id, toolName, argsJson), Type.TOOL_RESULT)
        return response.mapC { msg ->
            val result = WorkerProtocol.ToolResult.from(msg)
                ?: error("tool.result 消息负载损坏")
            if (result.ok) result.output
            else error(result.error ?: "插件工具执行失败")
        }
    }

    /** 向插件下发事件（Phase 4 起有订阅方；当前如实回执）。 */
    suspend fun emitEvent(eventName: String, payloadJson: String): Result<String> {
        val id = nextRequestId()
        val response = roundTrip(WorkerProtocol.eventEmit(id, eventName, payloadJson), Type.TOOL_RESULT)
        return response.mapC { msg ->
            WorkerProtocol.ToolResult.from(msg)?.output ?: error("event 回执负载损坏")
        }
    }

    /** 心跳探测（ping→pong），返回往返毫秒。 */
    suspend fun ping(): Result<Long> {
        val start = System.currentTimeMillis()
        return roundTrip(WorkerProtocol.ping(nextRequestId()), Type.PONG).mapC {
            System.currentTimeMillis() - start
        }
    }

    /** 优雅停机：发 shutdown → 等宽限 → 强杀兜底。 */
    suspend fun shutdown(): Unit = withContext(Dispatchers.IO) {
        if (state == State.STOPPING || state == State.STOPPED) return@withContext
        state = State.STOPPING
        runCatching {
            withTimeout(config.shutdownGraceMs) {
                writeRaw(WorkerProtocol.shutdown(nextRequestId()))
            }
        }
        val deadline = System.currentTimeMillis() + config.shutdownGraceMs
        while (process.isAlive && System.currentTimeMillis() < deadline) {
            delay(50)
        }
        if (process.isAlive) {
            process.destroy()
            val killDeadline = System.currentTimeMillis() + 1_000
            while (process.isAlive && System.currentTimeMillis() < killDeadline) delay(50)
            if (process.isAlive) process.destroyForcibly()
        }
        state = State.STOPPED
        // 同步终结在途请求：看护协程被取消时收尾块可能被跳过（竞态），
        // 这里主动兜底（幂等 —— 已完成的不受影响）
        failAllPending(-1)
        scope.cancel()
    }

    /** 强制终止（崩溃处理路径用）：同步等死亡落定后再返回。 */
    fun kill() {
        if (state != State.STOPPED) {
            process.destroyForcibly()
            // destroyForcibly 后 isAlive 翻转是异步的 —— 不同步等死亡落定，
            // 调用方（含测试与 Supervisor 重启判定）会在「垂死实例」窗口里
            // 把已杀进程当活实例，后续逻辑全部错拍。
            runCatching { process.waitFor(2, TimeUnit.SECONDS) }
            state = State.STOPPED
            // 同步终结在途请求（不能依赖被取消的看护协程：scope.cancel 后
            // 看护协程的 NonCancellable 收尾块存在被跳过的竞态 —— CI 实证）
            failAllPending(-1)
        }
        scope.cancel()
    }

    /** 终结全部在途请求（失败而非悬挂；幂等，线程安全）。 */
    private fun failAllPending(code: Int) {
        val failure = IllegalStateException("worker 进程已退出（code=$code）")
        pending.values.forEach { it.completeExceptionally(failure) }
        pending.clear()
    }

    /** 最近一次 fatal 原因（诊断）。 */
    fun lastFatalReason(): String? = lastFatalReason

    /**
     * 最近一次成功 load 的 Loaded 快照（Supervisor 重启重放后刷新）。
     * 加载器用它取工具/钩子清单，避免二次 load 重复触发插件 setup。
     */
    @Volatile
    private var lastLoadedResult: WorkerProtocol.Loaded? = null

    fun lastLoaded(): WorkerProtocol.Loaded? = lastLoadedResult

    /** STDERR 环形缓冲快照（诊断）。 */
    fun stderrSnapshot(): List<String> = synchronized(stderrLock) { stderrRing.toList() }

    // ── 内部机制 ──────────────────────────────────────────────────────────

    private suspend fun roundTrip(
        line: String,
        expectType: String,
        timeoutMs: Long = config.requestTimeoutMs
    ): Result<WorkerProtocol.Message> {
        val id = WorkerProtocol.Message.parse(line)?.requestId
            ?: return Result.failure(IllegalStateException("消息构造失败"))
        val deferred = CompletableDeferred<WorkerProtocol.Message>()
        pending[id] = deferred
        try {
            writeRaw(line)
            val message = withTimeout(timeoutMs) { deferred.await() }
            return if (message.type == expectType) {
                Result.success(message)
            } else {
                Result.failure(IllegalStateException("期望 $expectType 却收到 ${message.type}"))
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            // 超时的请求不再关心结果（防迟到响应错配）
            pending.remove(id)
            return Result.failure(IllegalStateException("请求超时（${timeoutMs}ms）"))
        } catch (e: Throwable) {
            return Result.failure(e)
        } finally {
            // 协程取消时尽力通知 Worker 丢弃结果（NonCancellable：取消路径
            // 里的挂起写必须绕过取消检查，否则 cancel 消息永远发不出去）
            if (!deferred.isCompleted) {
                pending.remove(id)
                runCatching {
                    withContext(NonCancellable) {
                        writeRaw(WorkerProtocol.cancel(nextRequestId(), id))
                    }
                }
            }
        }
    }

    private suspend fun writeRaw(line: String) {
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                val stream = process.outputStream
                stream.write((line + "\n").toByteArray(Charsets.UTF_8))
                stream.flush()
            }
        }
    }

    private fun nextRequestId(): String = "req-${requestCounter.incrementAndGet()}"

    private suspend fun readLoop() {
        val reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
        try {
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val message = WorkerProtocol.Message.parse(line)
                if (message == null) {
                    eventsFlow.tryEmit(WorkerEvent.StderrLine(pluginId, "[ipc] 无法解析: ${line.take(120)}"))
                    continue
                }
                dispatch(message)
            }
        } catch (_: Throwable) {
            // 流关闭/进程死亡：退出看护负责收尾
        }
    }

    private fun dispatch(message: WorkerProtocol.Message) {
        when (message.type) {
            Type.FATAL -> {
                lastFatalReason = WorkerProtocol.fatalReason(message)
                eventsFlow.tryEmit(WorkerEvent.StderrLine(pluginId, "[fatal] ${lastFatalReason}"))
            }
            Type.PLUGIN_EVENT -> {
                WorkerProtocol.PluginEvent.from(message)?.let {
                    eventsFlow.tryEmit(WorkerEvent.PluginEventFired(pluginId, it))
                }
            }
            else -> {
                // 请求响应类：按 requestId 关联
                val id = message.requestId
                if (id != null) {
                    pending.remove(id)?.complete(message)
                }
            }
        }
    }

    private suspend fun stderrLoop() {
        val reader = BufferedReader(InputStreamReader(process.errorStream, Charsets.UTF_8))
        try {
            while (true) {
                val line = reader.readLine() ?: break
                synchronized(stderrLock) {
                    stderrRing.addLast(line)
                    while (stderrRing.size > config.stderrRingLines) stderrRing.removeFirst()
                }
                eventsFlow.tryEmit(WorkerEvent.StderrLine(pluginId, line))
            }
        } catch (_: Throwable) {
            // 同上：退出看护收尾
        }
    }

    private suspend fun exitWatchLoop() {
        val code = withContext(Dispatchers.IO) {
            runCatching { process.waitFor() }.getOrDefault(-1)
        }
        // NonCancellable：尽力而为的收尾（事件流发射 + 状态落定）；在途请求
        // 的终结不依赖这里 —— kill/shutdown 已同步兜底（本块存在被取消跳过的
        // 竞态，仅作为自然死亡路径的事件源）。
        withContext(NonCancellable) {
            failAllPending(code)
            if (state != State.STOPPING && state != State.STOPPED) {
                state = State.STOPPED
            }
            eventsFlow.tryEmit(WorkerEvent.Exited(pluginId, code))
        }
    }
}

/**
 * Result 变换：transform 抛出的业务异常**折叠回 Result.failure** 而不是
 * 穿透（调用方拿到的是失败结果，不是异常）；协程取消照常传播
 * （CancellationException 不吞 —— 吞掉会让取消语义失真）。
 */
private inline fun <T, R> Result<T>.mapC(transform: (T) -> R): Result<R> = fold(
    onSuccess = { value ->
        try {
            Result.success(transform(value))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    },
    onFailure = { Result.failure(it) }
)

/** 供 Supervisor 落盘引导脚本的便捷入口。 */
fun writeBootstrapTo(dir: File): File = PluginWorkerBootstrap.writeTo(dir)
