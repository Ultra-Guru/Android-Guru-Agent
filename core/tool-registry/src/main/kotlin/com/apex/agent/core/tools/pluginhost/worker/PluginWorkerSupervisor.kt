package com.apex.agent.core.tools.pluginhost.worker

import com.apex.agent.core.tools.pluginhost.PluginRuntimeKind
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * # PluginWorkerSupervisor —— Worker 看护与崩溃恢复（spec §7.4）
 *
 * 职责（对齐 spec 的崩溃恢复清单）：
 * 1. 检测 Worker 异常退出（非用户主动 stop）；
 * 2. 记录错误信息（exit code + fatal 原因 + stderr 尾部）；
 * 3. 依据重启策略恢复（窗口内计数 + 上限，防无限重启拖垮宿主）；
 * 4. 恢复即重放 handshake + load（重启后工具重新可用）；
 * 5. 超过上限 → 状态 GaveUp 并发事件 —— **永不影响 Agent 主循环**
 *    （spec 禁止事项 #9：全部观测经事件流，异常不外抛）。
 *
 * 假钟注入（仓库测试纪律）：[clock] 参数驱动重启窗口判定，不依赖墙钟。
 */
class PluginWorkerSupervisor(
    private val spawnPort: WorkerProcessPort,
    private val provider: RuntimeProvider,
    /** 引导脚本落盘目录（每次 start 前写入，幂等）。 */
    private val bootstrapDir: File,
    parentScope: CoroutineScope,
    private val policy: RestartPolicy = RestartPolicy(),
    private val clock: () -> Long = System::currentTimeMillis
) {
    /** 重启策略：窗口内最多 [maxRestarts] 次；存活超 [stableAfterMs] 计数清零。 */
    data class RestartPolicy(
        val maxRestarts: Int = 3,
        val windowMs: Long = 60_000,
        val stableAfterMs: Long = 30_000,
        val handshakeTimeoutMs: Long = 20_000
    )

    /** Supervisor 对外事件。 */
    sealed interface Event {
        val pluginId: String

        data class Started(override val pluginId: String, val pid: Long?) : Event
        data class Restarted(override val pluginId: String, val attempt: Int) : Event
        data class Crashed(
            override val pluginId: String,
            val exitCode: Int?,
            val fatalReason: String?,
            val stderrTail: List<String>
        ) : Event

        data class GaveUp(override val pluginId: String, val reason: String) : Event
        data class Stopped(override val pluginId: String) : Event
    }

    /** 受看护插件的重放规格（重启后 load 参数）。 */
    private data class LoadSpec(
        val pluginId: String,
        val workingDir: File,
        val entryPath: String,
        val format: String?
    )

    val events: SharedFlow<Event> get() = eventsFlow.asSharedFlow()

    private val eventsFlow = MutableSharedFlow<Event>(extraBufferCapacity = 128)
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob())
    private val mutex = Mutex()
    private val workers = ConcurrentHashMap<String, PluginWorker>()
    private val specs = ConcurrentHashMap<String, LoadSpec>()
    private val restarts = ConcurrentHashMap<String, MutableList<Long>>()
    private val startedAt = ConcurrentHashMap<String, Long>()
    private val gaveUp = ConcurrentHashMap<String, String>()
    private val watchJobs = ConcurrentHashMap<String, Job>()

    /** 启动并加载插件（handshake + load 全链路）。 */
    suspend fun start(
        pluginId: String,
        workingDir: File,
        entryPath: String,
        format: String? = null
    ): Result<PluginWorker> = mutex.withLock {
        gaveUp.remove(pluginId)
        val bootstrap = PluginWorkerBootstrap.writeTo(bootstrapDir)
        val argv = provider.buildCommand(bootstrap, workingDir)
        val spec = WorkerProcessPort.SpawnSpec.of(argv, workingDir)
        val process = spawnPort.spawn(spec).getOrElse { return@withLock Result.failure(it) }
        val worker = PluginWorker(
            pluginId = pluginId,
            process = process,
            parentScope = scope,
            config = PluginWorker.Config(handshakeTimeoutMs = policy.handshakeTimeoutMs)
        )
        val ready = worker.handshake().getOrElse {
            worker.kill()
            return@withLock Result.failure(IllegalStateException("握手失败: ${it.message}"))
        }
        val loaded = worker.load(entryPath, format).getOrElse {
            worker.kill()
            return@withLock Result.failure(IllegalStateException("插件模块加载失败: ${it.message}"))
        }
        if (!loaded.setupOk) {
            worker.kill()
            return@withLock Result.failure(IllegalStateException("插件 setup 失败: ${loaded.error}"))
        }
        workers[pluginId] = worker
        specs[pluginId] = LoadSpec(pluginId, workingDir, entryPath, format)
        startedAt[pluginId] = clock()
        watchJobs[pluginId]?.cancel()
        watchJobs[pluginId] = scope.launch { watchWorker(worker) }
        eventsFlow.tryEmit(Event.Started(pluginId, process.pidOrNull()))
        Result.success(worker)
    }

    /** 当前 Worker 实例（重启后实例会更换，调用方每次现取）。 */
    fun worker(pluginId: String): PluginWorker? = workers[pluginId]

    /** 看护中的插件 id 快照。 */
    fun supervisedIds(): Set<String> = workers.keys.toSet()

    /** 经 Supervisor 路由的工具调用（始终打到当前实例）。 */
    suspend fun callTool(pluginId: String, toolName: String, argsJson: String): Result<String> {
        val worker = workers[pluginId]
            ?: return Result.failure(IllegalStateException("插件 '$pluginId' 无运行中的 Worker"))
        return worker.callTool(toolName, argsJson)
    }

    /** 健康检查（ping 往返）。 */
    suspend fun healthCheck(pluginId: String): Result<Long> {
        val worker = workers[pluginId]
            ?: return Result.failure(IllegalStateException("插件 '$pluginId' 无运行中的 Worker"))
        if (!worker.alive) return Result.failure(IllegalStateException("worker 进程已死亡"))
        return worker.ping()
    }

    /** 用户主动停止（不算崩溃，不触发重启）。 */
    suspend fun stop(pluginId: String) {
        watchJobs.remove(pluginId)?.cancel()
        val worker = workers.remove(pluginId)
        specs.remove(pluginId)
        restarts.remove(pluginId)
        startedAt.remove(pluginId)
        gaveUp.remove(pluginId)
        worker?.shutdown()
        eventsFlow.tryEmit(Event.Stopped(pluginId))
    }

    /** 停止全部（宿主关停路径）。 */
    suspend fun stopAll() {
        supervisedIds().forEach { stop(it) }
        scope.cancel()
    }

    /** 放弃原因（诊断；null = 未放弃）。 */
    fun gaveUpReason(pluginId: String): String? = gaveUp[pluginId]

    // ── 崩溃看护 ──────────────────────────────────────────────────────────

    private suspend fun watchWorker(worker: PluginWorker) {
        var exited: WorkerEvent.Exited? = null
        val stderrTail = ArrayDeque<String>()
        val watcher = scope.launch {
            worker.events.collect { event ->
                when (event) {
                    is WorkerEvent.StderrLine -> {
                        synchronized(stderrTail) {
                            stderrTail.addLast(event.line)
                            while (stderrTail.size > 20) stderrTail.removeFirst()
                        }
                    }
                    is WorkerEvent.Exited -> exited = event
                    is WorkerEvent.PluginEventFired -> Unit
                }
            }
        }
        // 轮询等待退出（事件流的 Exited 即崩溃信号；worker.shutdown 会先置 STOPPING）
        while (worker.alive) {
            kotlinx.coroutines.delay(CRASH_POLL_MS)
        }
        watcher.cancel()
        val exitCode = exited?.exitCode
        val pluginId = worker.pluginId
        // 用户主动 stop 已从 workers 移除 → 不在此列
        if (workers.remove(pluginId) == null) return

        val fatal = worker.lastFatalReason()
        eventsFlow.tryEmit(
            Event.Crashed(pluginId, exitCode, fatal, synchronized(stderrTail) { stderrTail.toList() })
        )
        maybeRestart(pluginId)
    }

    private suspend fun maybeRestart(pluginId: String) {
        val spec = specs[pluginId] ?: return
        val now = clock()
        val history = restarts.getOrPut(pluginId) { mutableListOf() }
        // 存活期超过稳定阈值 → 计数清零（长稳运行后的崩溃重新获得全额预算）
        val started = startedAt[pluginId]
        if (started != null && now - started > policy.stableAfterMs) {
            history.clear()
        }
        history.removeAll { now - it > policy.windowMs }
        if (history.size >= policy.maxRestarts) {
            val reason = "窗口 ${policy.windowMs}ms 内重启已达上限 ${policy.maxRestarts} 次"
            gaveUp[pluginId] = reason
            eventsFlow.tryEmit(Event.GaveUp(pluginId, reason))
            return
        }
        history.add(now)
        val attempt = history.size
        val restarted = start(spec.pluginId, spec.workingDir, spec.entryPath, spec.format)
        if (restarted.isSuccess) {
            eventsFlow.tryEmit(Event.Restarted(pluginId, attempt))
        } else {
            // 重启失败继续按策略计次（历史已记录本次）
            val reason = "重启失败: ${restarted.exceptionOrNull()?.message}"
            gaveUp[pluginId] = reason
            eventsFlow.tryEmit(Event.GaveUp(pluginId, reason))
        }
    }

    private fun Process.pidOrNull(): Long? =
        runCatching { this.pid() }.getOrNull()

    private companion object {
        const val CRASH_POLL_MS = 100L
    }
}
