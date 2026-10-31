package com.apex.agent.platform.terminal.searxng

import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.platform.TerminalWait.WaitCondition
import com.apex.agent.platform.TerminalWait.WaitResult
import com.apex.agent.platform.terminal.events.TerminalEvent
import com.apex.agent.platform.terminal.io.InputOwner
import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.Socket

/**
 * ═══ M3：SearXNG 生命周期编排器（SearXNG Local Search Spec v1.0 §5/§6/§9）═══
 *
 * 状态机（Spec §5 转移表 + 不变量）：
 *
 * ```
 * Off ──enable──▶ Installing ──▶ Starting ──▶ Ready ⇄ Restarting
 *                    │              │           │        │
 *                    └──▶ Failed(retryable) ◀──┴────────┘
 * 任意 ──disable──▶ Stopping ──▶ Off
 * ```
 *
 * 不变量（Spec §5 INV1-5）：
 *  - INV1 单飞：[opMutex] 串行决策 + **单一 [startJob] 生命线**（任何 start/重启
 *    先 cancelAndJoin 旧线再开新线 —— 旧线的最后一次 setState 必然先于新线，
 *    消灭「取消中的序列回写 Ready」竞态）；
 *  - INV2 watchdog 存活：Ready 态必有 [watchdogJob] 在跑（与 Ready 同点启动）；
 *  - INV3 Off 无进程组：Off/Stopping 路径必然 close 会话（close = ProcessController
 *    注销，AC12 的「searxng 组」即以 headless 会话存在性表达 —— workspaceId=
 *    "searxng" 充当语义 tag，Spec §6）；
 *  - INV4 UI 只读 [state]；
 *  - INV5 意图与运行态最终一致：[reconcile] 在 Service 启动时按意图对账。
 *
 * 守护进程形态：TerminalRuntime headless 会话（linux-ubuntu 后端）内的**前台**
 * job —— bash 父进程 + python 子进程（形态决策见 [SearxngScripts.daemonCommand]）。
 * 健康监测双通道互补（WaitEngine 为锚点后新事件匹配，不回放历史 —— 两通道的
 * 覆盖窗口互补成闭环）：
 *  - 退出观察者（[startExitWatcher]）：wait(ProcessExited(jobId)) —— 真实退出码
 *    （Shell Marker Protocol），AC6 自愈的主信号；
 *  - 健康看门狗（[startWatchdog]）：15s 周期 /healthz，连续 3 失败 → 重启
 *    （Spec §9 表）；锚点空窗（healthz 成功→订阅完成之间的退出事件）由此兜底。
 *
 * 重启纪律（Spec §9「不无限重试」）：[consecutiveRestarts] 上限
 * [MAX_CONSECUTIVE_RESTARTS]，达上限 → Failed(retryable=true)；守护进程稳定运行
 * [RESTART_RESET_MS] 后计数清零。
 *
 * 取消语义：CancellationException 全线透传（不吞协程取消纪律）。Stopping/Off 为
 * 「粘性」状态 —— 只有后续 enable/retry/reconcile（stop=false 先行）可离开，防
 * 被取消中的 start 序列回写终态的竞态。
 */
class SearxngLifecycleCoordinator(
    private val runtime: TerminalRuntime,
    private val installer: SearxngInstaller,
    private val settingsWriter: SearxngSettingsWriter,
    private val intent: SearxngIntentPort,
    /** healthz 探测端口（app 侧 OkHttp 适配；true = 200）。 */
    private val healthProbe: suspend () -> Boolean,
    /** Ubuntu 就绪门禁（app 侧适配 UbuntuLifecycleCoordinator.phase == READY）。 */
    private val ubuntuReady: suspend () -> Boolean,
    /** 生命周期宿主作用域（app 侧注入 service 级 SupervisorScope；测试注入 TestScope）。 */
    private val scope: CoroutineScope,
    /** 端口占用探测（host 侧 TCP connect；测试注入假实现）。 */
    private val portBusyProbe: suspend (Int) -> Boolean = ::tcpPortBusy,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    // ─────────────────────────── 状态（INV4 单一流）───────────────────────────

    private val _state = MutableStateFlow<SearxngState>(SearxngState.Off)
    val state: StateFlow<SearxngState> = _state.asStateFlow()

    /** Provider 同步判定（Spec §6 isReady —— 仅 Ready 才放行本地搜索）。 */
    fun isReady(): Boolean = _state.value is SearxngState.Ready

    /** 本地实例地址（UI 副标题/排查展示，Spec F12）。 */
    fun address(): String =
        "http://${SearxngContract.BIND_ADDRESS}:${intent.port()}"

    // ─────────────────────────── 并发骨架 ────────────────────────────

    /** enable/disable/retry/setEngines 的互斥（INV1 单飞的决策半；长序列在 scope 内跑）。 */
    private val opMutex = Mutex()

    /** 当前 bring-up（install→start / restart）生命线 —— 单飞的工作半。 */
    private var startJob: Job? = null
    private var watchdogJob: Job? = null
    private var exitWatcherJob: Job? = null

    /** 守护会话持有（INV1：非 null 即有会话）。@Volatile：startJob 协程写、锁外读。 */
    @Volatile
    private var held: HeldSession? = null

    /** disable 收尾流标记 —— Stopping/Off 粘性状态的合法出口钥匙。 */
    @Volatile
    private var stopping: Boolean = false

    /** 宿主（service）显式收尾 —— 会话被外部关闭时抑制自动重启（F6/F7 语义）。 */
    @Volatile
    private var hostShutdown: Boolean = false

    private var consecutiveRestarts = 0
    private var readySinceMs: Long = 0L

    private data class HeldSession(
        val sessionId: Long,
        val jobId: Long,
        /** run() 返回的 startCursor —— healthz 超时时从这里截取尾部输出归因。 */
        val startCursor: Long
    )

    // ─────────────────────────── 公共 API（Spec §6 契约）───────────────────────────

    /**
     * 开启（幂等）：已 Ready/进行中直接成功；意图立即落盘（Spec §8「先写 Vault
     * 再发 action」的事务语义 —— 本方法即 action 侧终点）。
     */
    suspend fun enable(): Result<Unit> = opMutex.withLock {
        when (_state.value) {
            is SearxngState.Ready, is SearxngState.Installing,
            is SearxngState.Starting, is SearxngState.Restarting ->
                return@withLock Result.success(Unit)
            is SearxngState.Stopping ->
                return@withLock Result.failure(RuntimeException("searxng is stopping — try again shortly"))
            is SearxngState.Off, is SearxngState.Failed -> {
                intent.setEnabled(true)
                hostShutdown = false
                stopping = false
                launchStart(fromRestart = false)
                Result.success(Unit)
            }
        }
    }

    /**
     * 关闭（幂等；Spec F10 优雅停止 + N3 ≤3s）：写意图=false → 取消全部后台流 →
     * close 会话（force 三级杀）→ Off。安装中的产物保留（F11 断点续装）。
     */
    suspend fun disable(): Result<Unit> = opMutex.withLock {
        if (_state.value is SearxngState.Off) return@withLock Result.success(Unit)
        stopping = true
        intent.setEnabled(false)
        try {
            _state.value = SearxngState.Stopping
            startJob?.cancelAndJoin()
            teardownAsyncWorkers()
            closeHeldSession(force = true)
        } finally {
            stopping = false
            _state.value = SearxngState.Off
            log("disabled")
        }
        Result.success(Unit)
    }

    /** 重试（仅 Failed 有效，Spec §6）。 */
    suspend fun retry(): Result<Unit> = opMutex.withLock {
        when (_state.value) {
            is SearxngState.Failed -> {
                hostShutdown = false
                stopping = false
                launchStart(fromRestart = false)
                Result.success(Unit)
            }
            else -> Result.failure(RuntimeException("retry only valid from Failed (current=${_state.value})"))
        }
    }

    /**
     * 引擎变更（Spec §6：写入后若 Ready 触发重启；UI 走 Starting 态）。
     * 空名单 → 回落默认引擎（与 [SearxngSettingsWriter] 归一规则一致）。
     */
    suspend fun setEngines(engines: Set<String>): Result<Unit> = opMutex.withLock {
        intent.setEngines(engines)
        when (_state.value) {
            is SearxngState.Ready, is SearxngState.Starting, is SearxngState.Restarting -> {
                log("engines changed (${engines.sorted().joinToString(",")}) — restarting daemon")
                launchStart(fromRestart = true)
                Result.success(Unit)
            }
            is SearxngState.Installing, is SearxngState.Stopping, is SearxngState.Off,
            is SearxngState.Failed -> Result.success(Unit) // 配置已落盘，下次启动生效
        }
    }

    /**
     * 对账（INV5）：App/Service 启动时按意图恢复。意图开而运行态死 → 拉起
     * （rootfs 重装丢失场景自动进入 Installing，Spec §15 风险表）；意图关而
     * 残留运行 → 停止。
     */
    fun reconcile() {
        scope.launch {
            opMutex.withLock {
                val s = _state.value
                if (intent.isEnabled() && (s is SearxngState.Off || s is SearxngState.Failed)) {
                    log("reconcile: intent=on, runtime=${s::class.simpleName} → start")
                    hostShutdown = false
                    stopping = false
                    launchStart(fromRestart = false)
                }
            }
            // 意图关而残留运行的对账（锁外调用 —— disable 自身会抢锁，锁内只判定）
            if (!intent.isEnabled() && _state.value !is SearxngState.Off &&
                _state.value !is SearxngState.Stopping
            ) {
                disable()
            }
        }
    }

    /**
     * 宿主收尾（ApexCoreService.onDestroy / TerminalRuntime.shutdown 兜底路径）：
     * 抑制自动重启、状态归 Off —— **不写意图**（F6/F7：意图保持，下次 Service/
     * 开机 reconcile 恢复）。会话本体由既有 TerminalRuntime.shutdown() 关闭。
     */
    fun onHostShutdown() {
        hostShutdown = true
        startJob?.cancel()
        teardownAsyncWorkers()
        if (_state.value !is SearxngState.Off) {
            _state.value = SearxngState.Off
        }
        log("host shutdown — daemon left to runtime teardown, intent preserved")
    }

    // ─────────────────────────── bring-up 生命线（INV1 单飞工作半）───────────────────────────

    /**
     * 开新 bring-up 线：cancelAndJoin 旧线（其最后一次 setState 必先于本线任何
     * 状态写入 —— 顺序由 join 建立 happens-before）→ 清场 → 全新序列。
     *
     * @param fromRestart true = 跳过安装/配置（引擎切换/重启路径，安装态与配置
     *   已就绪，settings.yml 由调用方先行落盘）；false = 完整序列。
     */
    private suspend fun launchStart(fromRestart: Boolean) {
        startJob?.cancelAndJoin()
        teardownAsyncWorkers()
        closeHeldSession(force = true)
        startJob = scope.launch {
            runCatching { startSequence(fromRestart) }
                .onFailure { e ->
                    if (e !is CancellationException) {
                        log("start sequence crashed: ${e.message ?: e::class.simpleName}")
                        setState(SearxngState.Failed("internal error — ${e::class.simpleName}", retryable = true))
                    }
                }
        }
    }

    private suspend fun startSequence(fromRestart: Boolean) {
        // 1. Ubuntu 门禁（Spec §12 失败矩阵首行）
        if (!ubuntuReady()) {
            setState(SearxngState.Failed("ubuntu not ready", retryable = true))
            return
        }
        // 2. 快速失败端口探测（Spec §5：port in use 提前暴露，不让用户白等安装）
        val port = intent.port()
        if (portBusyProbe(port)) {
            setState(SearxngState.Failed("port in use", retryable = true))
            return
        }
        if (!fromRestart) {
            // 3. 安装（幂等；已装时近零耗时 —— AC2 ≤10s 进 Ready）
            if (!installer.isInstalled()) {
                installer.install(onStage = { stage, progress ->
                    setState(SearxngState.Installing(progress, stage.label))
                }).fold(
                    onSuccess = { /* fallthrough */ },
                    onFailure = { e ->
                        setState(SearxngState.Failed(e.message ?: "install failed", retryable = true))
                        return
                    }
                )
            }
            // 4. 配置（幂等写入 —— 引擎名单/secret_key 变化时刷新）
            settingsWriter.ensureConfig().fold(
                onSuccess = { /* fallthrough */ },
                onFailure = { e ->
                    setState(SearxngState.Failed(e.message ?: "settings failed", retryable = true))
                    return
                }
            )
            // 5. 端口二次确认（安装耗时窗口内可能被占）
            if (portBusyProbe(port)) {
                setState(SearxngState.Failed("port in use", retryable = true))
                return
            }
        }
        // 6. 拉起守护进程 + 等待 healthz
        startDaemon(port)
    }

    /** 拉起 headless 会话 + 前台 job + healthz 等待（Spec §5 Starting 语义）。 */
    private suspend fun startDaemon(port: Int) {
        setState(SearxngState.Starting)
        val created = runtime.create(
            backendId = LINUX_BACKEND_ID,
            workspaceId = SearxngContract.SESSION_WORKSPACE_ID
        ).getOrElse { e ->
            setState(
                SearxngState.Failed(
                    "ubuntu not ready — ${e.message ?: "session create failed"}",
                    retryable = true
                )
            )
            return
        }
        val sessionId = created.sessionId
        // 立即持有（占位 jobId）—— create 到 Ready 之间的任何取消/失败路径都由
        // finally 统一收尾，消灭「取消落在 create 与 held 赋值之间」的会话泄漏
        held = HeldSession(sessionId, PENDING_JOB_ID, 0L)
        try {
            // bash 就绪（宽限 10s；超时继续 —— prompt 探测是尽力而为的前置）
            runCatching { runtime.wait(sessionId, WaitCondition.PromptDetected, PROMPT_GRACE_MS) }

            val runResult = runtime.run(
                sessionId = sessionId,
                command = SearxngScripts.daemonCommand(port),
                owner = InputOwner.SYSTEM,
                background = false
            ).getOrElse { e ->
                setState(
                    SearxngState.Failed(
                        "startup failed — ${e.message ?: "daemon spawn failed"}",
                        retryable = true
                    )
                )
                return
            }

            // healthz 轮询窗口（N2：30s；1s 步进 —— 步进内并发探测进程早退，不等满窗口）
            val deadline = clock() + SearxngContract.STARTUP_TIMEOUT_MS
            while (clock() < deadline) {
                if (healthProbe()) {
                    held = HeldSession(sessionId, runResult.jobId, runResult.startCursor)
                    readySinceMs = clock()
                    setState(SearxngState.Ready(address()))
                    log("ready at ${address()} (job=${runResult.jobId})")
                    startWatchdog()
                    startExitWatcher(sessionId, runResult.jobId)
                    return
                }
                val early = runtime.wait(
                    sessionId, WaitCondition.ProcessExited(runResult.jobId), HEALTH_POLL_STEP_MS
                ).getOrNull()
                if (early is WaitResult.Matched || early is WaitResult.SessionGone) {
                    val tail = collectSessionTail(sessionId, runResult.startCursor)
                    setState(
                        SearxngState.Failed(
                            "startup timeout — daemon exited early${tail?.let { ": $it" } ?: ""}",
                            retryable = true
                        )
                    )
                    return
                }
                delay(HEALTH_POLL_STEP_MS)
            }
            // 超时（Spec §5「Starting 超时 30s → Failed；关闭会话」）
            val tail = collectSessionTail(sessionId, runResult.startCursor)
            setState(
                SearxngState.Failed(
                    "startup timeout — healthz not reachable in " +
                        "${SearxngContract.STARTUP_TIMEOUT_MS / 1000}s${tail?.let { ": $it" } ?: ""}",
                    retryable = true
                )
            )
        } finally {
            // 非 Ready 出口（失败/取消/超时）统一关会话；Ready 出口保留（finally 只对账）
            if (held?.sessionId == sessionId && _state.value !is SearxngState.Ready) {
                held = null
                runCatching { runtime.close(sessionId, force = true) }
            }
        }
    }

    // ─────────────────────────── watchdog / 退出观察者（Spec §9）───────────────────────────

    /**
     * 周期健康检查：15s 间隔 / 单次 3s 超时 / 连续 3 失败 → 重启。
     * INV2：与 Ready 同点启动，disable/重启时取消。
     */
    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            var failures = 0
            while (isActive && _state.value is SearxngState.Ready) {
                delay(SearxngContract.WATCHDOG_INTERVAL_MS)
                if (_state.value !is SearxngState.Ready) break
                val healthy = withTimeoutOrNull(SearxngContract.HEALTH_CHECK_TIMEOUT_MS) {
                    healthProbe()
                } == true
                if (healthy) {
                    failures = 0
                } else {
                    failures++
                    log("healthz miss ${failures}/${SearxngContract.WATCHDOG_FAILURE_THRESHOLD}")
                    if (failures >= SearxngContract.WATCHDOG_FAILURE_THRESHOLD) {
                        requestRestart("healthz failed ${SearxngContract.WATCHDOG_FAILURE_THRESHOLD}x")
                        break
                    }
                }
            }
        }
    }

    /** 守护进程退出观察：真实退出码（Shell Marker）→ 自愈；SessionGone 同义。 */
    private fun startExitWatcher(sessionId: Long, jobId: Long) {
        exitWatcherJob?.cancel()
        exitWatcherJob = scope.launch {
            while (isActive) {
                val r = runtime.wait(sessionId, WaitCondition.ProcessExited(jobId), WAITER_SLICE_MS)
                    .getOrNull() ?: return@launch
                when (r) {
                    is WaitResult.Matched -> {
                        val code = (r.event as? TerminalEvent.ProcessExited)?.exitCode
                        if (!stopping && !hostShutdown && _state.value is SearxngState.Ready) {
                            requestRestart("daemon exited (code=$code)")
                        }
                        return@launch
                    }
                    is WaitResult.SessionGone -> {
                        if (!stopping && !hostShutdown && _state.value is SearxngState.Ready) {
                            // 会话外部消失（非我方 stop 流）—— 归 Off 等下次 reconcile，
                            // 不在此自动重拉（宿主可能正在收尾，重拉会与 shutdown 打架）
                            setState(SearxngState.Off)
                            log("session gone outside stop flow — awaiting reconcile")
                        }
                        return@launch
                    }
                    is WaitResult.Timeout -> continue // 分片等待：可被 cancel 打断
                }
            }
        }
    }

    /**
     * 重启请求（watchdog/exitWatcher 发起）：全量走 [opMutex] + [launchStart]
     * （fromRestart=true —— 跳过安装，配置已就绪）。预算封顶防崩溃循环。
     */
    private fun requestRestart(reason: String) {
        scope.launch {
            opMutex.withLock {
                if (hostShutdown || stopping) return@withLock
                if (_state.value !is SearxngState.Ready) return@withLock
                // 稳定期满 → 重启计数清零（Spec §9「不无限重试」的宽松面）
                if (readySinceMs > 0 && clock() - readySinceMs >= RESTART_RESET_MS) {
                    consecutiveRestarts = 0
                }
                if (consecutiveRestarts >= MAX_CONSECUTIVE_RESTARTS) {
                    setState(SearxngState.Failed("restart budget exhausted ($reason)", retryable = true))
                    return@withLock
                }
                consecutiveRestarts++
                setState(SearxngState.Restarting(consecutiveRestarts))
                log("restarting ($consecutiveRestarts/$MAX_CONSECUTIVE_RESTARTS): $reason")
                launchStart(fromRestart = true)
            }
        }
    }

    // ─────────────────────────── 内部工具 ────────────────────────────

    /** 状态写入（粘性守卫：Stopping 只出向 Off；Off 仅在非 stop 流可离开）。 */
    private fun setState(next: SearxngState) {
        synchronized(this) {
            val cur = _state.value
            if (cur is SearxngState.Stopping && next !is SearxngState.Off) return
            if (cur is SearxngState.Off && next !is SearxngState.Off && stopping) return
            _state.value = next
        }
    }

    /** 取消监测双流（bring-up 生命线由调用方决定）。 */
    private fun teardownAsyncWorkers() {
        watchdogJob?.cancel()
        exitWatcherJob?.cancel()
        watchdogJob = null
        exitWatcherJob = null
    }

    /** 关闭守护会话（幂等；force 三级杀满足 N3 ≤3s）。 */
    private suspend fun closeHeldSession(force: Boolean) {
        val session = held ?: return
        held = null
        runCatching { runtime.close(session.sessionId, force = force) }
            .onFailure { log("session close failed: ${it.message}") }
    }

    /** 会话尾部输出（healthz 超时归因；RAW 模式截尾 2KB）。 */
    private suspend fun collectSessionTail(sessionId: Long, startCursor: Long): String? =
        runtime.observe(
            sessionId = sessionId,
            mode = TerminalRuntime.ObserveMode.RAW,
            afterCursor = startCursor,
            maxBytes = 2048
        ).getOrNull()?.raw
            ?.takeLast(TAIL_LOG_CHARS)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun log(message: String) {
        runCatching {
            AppLogger.instance.info(LogCategory.TOOL, TAG, "searxng: $message")
        }
    }

    companion object {
        private const val TAG = "SearxngLifecycle"

        /** linux-ubuntu 后端 id（TerminalRuntime 默认；显式传可读性优先）。 */
        private const val LINUX_BACKEND_ID = "linux-ubuntu"

        /** prompt 就绪宽限。 */
        private const val PROMPT_GRACE_MS = 10_000L

        /** healthz 轮询步进。 */
        private const val HEALTH_POLL_STEP_MS = 1_000L

        /** 退出观察分片（wait 可中断粒度）。 */
        private const val WAITER_SLICE_MS = 60_000L

        /** 连续重启上限（Spec §9 不无限重试）。 */
        private const val MAX_CONSECUTIVE_RESTARTS = 3

        /** 稳定期：Ready 持续此时长后重启计数清零。 */
        private const val RESTART_RESET_MS = 120_000L

        /** 失败归因尾部日志上限。 */
        private const val TAIL_LOG_CHARS = 400

        /** create 后 Ready 前的占位 jobId（finally 收尾对账用）。 */
        private const val PENDING_JOB_ID = -1L

        /**
         * host 侧端口占用探测：TCP connect 成功 = 有监听者 = 被占（Spec §7
         * 端口冲突 —— 不自动换端口，避免配置漂移）。纯 java.net —— Android/JVM
         * 双侧可用，测试经 [portBusyProbe] 端口注入假实现。
         */
        private suspend fun tcpPortBusy(port: Int): Boolean = try {
            Socket().use { s ->
                s.connect(InetSocketAddress(SearxngContract.BIND_ADDRESS, port), 500)
                true
            }
        } catch (e: Exception) {
            false
        }
    }
}
