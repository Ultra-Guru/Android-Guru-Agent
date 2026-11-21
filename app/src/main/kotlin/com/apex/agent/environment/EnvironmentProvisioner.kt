package com.apex.agent.environment

import com.apex.agent.platform.terminal.io.InputOwner
import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.ubuntu.lifecycle.UbuntuLifecycleCoordinator
import com.apex.agent.platform.TerminalWait.WaitCondition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Developer-environment provisioner. Installs JDK / Gradle / SDK / NDK / etc.
 *
 * Spec ref: ATR 2.0 Final Spec §38 / §43
 *
 * EXTRACTED from the old `TerminalViewModel.kt` (which mixed terminal settings + blacklist +
 * dependency installation + session lifecycle). The Runtime itself does NOT know about JDK /
 * Gradle / NDK / SDK mirrors — those belong here, in the `DeveloperEnvironment` module.
 *
 * Install flow (per dep):
 *   1. Run checkCommand via TerminalRun + wait(PROCESS_EXITED)
 *      → if exit 0, already installed, skip
 *   2. Otherwise run installCommand(useMirror) via TerminalRun
 *   3. Wait PROCESS_EXITED (reliable, no settle-time)
 *   4. Stream output to installLog StateFlow
 *   5. Re-run checkCommand to verify
 *
 * Uses the new Runtime API (TerminalRun + TerminalWait + TerminalObserve), NOT the old
 * TerminalManager.execute with settle-time.
 *
 * T82 断点修复：[DepCatalog] 的命令全部是 Ubuntu（proot）内的 apt 命令 ——
 * 必须跑在 linux-ubuntu backend 会话里。此前产品层把 apt 命令投进 Android
 * shell（local session）→ `apt-get: command not found`，依赖下载中心 100% 失效。
 * [ensureUbuntuSession] 先幂等拉起 Ubuntu 生命周期（install→bootstrap→READY），
 * 再创建/复用 linux-ubuntu 会话；[ubuntuLifecycle]=null 时（兼容旧构造）诚实降级。
 */
class EnvironmentProvisioner(
    private val runtime: TerminalRuntime,
    private val ubuntuLifecycle: UbuntuLifecycleCoordinator? = null
) {
    data class InstallState(
        val depId: String? = null,
        val running: Boolean = false,
        val log: String = "",
        val done: Boolean = false,
        val exitCode: Int? = null
    )

    private val _install = MutableStateFlow(InstallState())
    val install: StateFlow<InstallState> = _install.asStateFlow()

    private val _useMirror = MutableStateFlow(false)
    val useMirror: StateFlow<Boolean> = _useMirror.asStateFlow()

    fun setUseMirror(v: Boolean) { _useMirror.value = v }

    val depItems: List<DepItem> get() = DepCatalog.ALL

    // ═══ T82: Ubuntu 路由（DepCatalog apt 命令的正确执行环境）═══

    private var ubuntuSessionId: Long? = null

    /**
     * T82 断点修复：确保存在一个 linux-ubuntu backend 会话（DepCatalog 的 apt 命令
     * 只在 Ubuntu 环境里真实可执行）。幂等：已创建则复用。
     *
     * 流程：ensureReady（install→bootstrap→capability，幂等且进度不丢）→
     * create(backend="linux-ubuntu")。任一环节失败返回 null（调用方诚实降级到
     * local session —— 输出里会出现真实的 command not found，而非伪造成功）。
     *
     * T92：缓存会话**存活校验** —— 旧实现 `ubuntuSessionId?.let { return it }`
     * 永不过期：会话死亡（EXITED）后每次安装都把命令写进死 PTY → job 挂到
     * 120s 超时 → SIGKILL → 日志报「等待超时」（与真实根因不符），直到重启
     * App。死亡即清缓存重建。
     */
    suspend fun ensureUbuntuSession(): Long? {
        if (ubuntuSessionId != null && !isSessionAlive(ubuntuSessionId!!)) {
            appendLog("⚠️ 缓存的 Ubuntu 会话已退出 —— 重建会话\n")
            ubuntuSessionId = null
        }
        ubuntuSessionId?.let { return it }
        val lc = ubuntuLifecycle ?: return null
        when (val r = lc.ensureReady()) {
            is UbuntuLifecycleCoordinator.EnsureResult.Ready,
            is UbuntuLifecycleCoordinator.EnsureResult.AlreadyReady -> Unit
            is UbuntuLifecycleCoordinator.EnsureResult.InProgress -> {
                appendLog("⚠️ Ubuntu 环境仍在准备中（${r.message}）— 稍后重试\n")
                return null
            }
            is UbuntuLifecycleCoordinator.EnsureResult.Failed -> {
                appendLog("❌ Ubuntu 环境准备失败（${r.stage.name}: ${r.message}）" +
                    (if (r.retryable) " — 可重试" else "") + "\n")
                return null
            }
            is UbuntuLifecycleCoordinator.EnsureResult.Cancelled -> {
                appendLog("⚠️ Ubuntu 环境准备被取消\n")
                return null
            }
        }
        val created = runtime.create(backendId = "linux-ubuntu")
        val sessionId = created.getOrNull()?.sessionId
        if (sessionId == null) {
            appendLog("❌ Ubuntu 会话创建失败：${created.exceptionOrNull()?.message}\n")
            return null
        }
        ubuntuSessionId = sessionId
        return sessionId
    }

    /** T92：会话实时存活探测（终态黑名单：EXITED/LOST/FAILED/BROKEN/CLOSED）。 */
    private suspend fun isSessionAlive(sessionId: Long): Boolean {
        val snap = runtime.snapshot(
            com.apex.agent.platform.terminal.runtime.TerminalRuntime.SnapshotMode.SESSIONS,
            sessionId = sessionId
        ).getOrNull() ?: return false
        return snap.sessions.any { s ->
            s.session.id == sessionId && s.session.state !in DEAD_SESSION_STATES
        }
    }

    /** Ubuntu 生命周期状态（UI 订阅展示安装/引导进度）。 */
    val ubuntuLifecycleState: StateFlow<UbuntuLifecycleCoordinator.LifecycleState>? =
        ubuntuLifecycle?.stateFlow

    /** 依赖安装的正确 session：Ubuntu 可用走 linux-ubuntu，否则 -1（无降级 ——
     *  AndroidShell 后端已移除，apt 只在 Ubuntu 会话可用）。 */
    suspend fun sessionForApt(): Long = ensureUbuntuSession() ?: -1L

    /** Install a single dependency. Returns the final exit code (0 = success). */
    suspend fun installDep(sessionId: Long, item: DepItem): Int {
        val cmd = item.installCommand(_useMirror.value)
        _install.value = InstallState(depId = item.id, running = true, log = "$ ${cmd}\n", done = false)

        val exitCode = runCommand(sessionId, cmd, append = true)
        _install.value = _install.value.copy(running = false, done = true, exitCode = exitCode)
        return exitCode
    }

    /** Install all dependencies in GENERAL then ANDROID group. Calls onProgress after each. */
    suspend fun installAll(sessionId: Long, onProgress: (DepItem, Int) -> Unit) {
        for ((i, dep) in depItems.withIndex()) {
            val code = installDep(sessionId, dep)
            onProgress(dep, code)
        }
    }

    /** Install only ANDROID-group deps. */
    suspend fun installAndroidOnly(sessionId: Long, onProgress: (DepItem, Int) -> Unit) {
        for (dep in depItems.filter { it.group == DepGroup.ANDROID }) {
            val code = installDep(sessionId, dep)
            onProgress(dep, code)
        }
    }

    /** Check if a dep is already installed (runs checkCommand, returns true if exit 0). */
    suspend fun isInstalled(sessionId: Long, item: DepItem): Boolean {
        val code = runCommand(sessionId, item.checkCommand, append = false)
        return code == 0
    }

    /** Run a command via the new Runtime API (run + wait + observe). Returns exit code. */
    private suspend fun runCommand(sessionId: Long, command: String, append: Boolean): Int {
        val runResult = runtime.run(sessionId, command, owner = InputOwner.SYSTEM, background = false)
        val run = runResult.getOrElse {
            appendLog("ERROR: ${it.message}\n")
            return -1
        }
        val waitResult = runtime.wait(
            sessionId = sessionId,
            condition = WaitCondition.ProcessExited(jobId = run.jobId),
            timeoutMs = 300_000L   // 5 min per command
        )
        val wait = waitResult.getOrElse {
            appendLog("ERROR: ${it.message}\n")
            return -1
        }
        val exitCode = when (wait) {
            is com.apex.agent.platform.TerminalWait.WaitResult.Matched -> {
                val ev = wait.event
                if (ev is com.apex.agent.platform.terminal.events.TerminalEvent.ProcessExited)
                    ev.exitCode ?: -1
                else 0
            }
            is com.apex.agent.platform.TerminalWait.WaitResult.Timeout -> {
                appendLog("TIMEOUT\n")
                runtime.signal(sessionId, com.apex.agent.platform.terminal.io.UnixSignal.SIGKILL, InputOwner.SYSTEM, run.jobId)
                -1
            }
            is com.apex.agent.platform.TerminalWait.WaitResult.SessionGone -> -1
        }
        // observe the command's output (since its startCursor)
        val obs = runtime.observe(
            sessionId = sessionId,
            mode = TerminalRuntime.ObserveMode.RAW,
            afterCursor = run.startCursor,
            maxBytes = 65536
        ).getOrNull()
        if (obs != null && append) {
            appendLog(obs.raw ?: "")
        }
        return exitCode
    }

    private fun appendLog(text: String) {
        _install.value = _install.value.copy(log = _install.value.log + text)
    }

    private companion object {
        /** T92：终态黑名单（EXITED/LOST/FAILED/BROKEN/CLOSED —— 与
         *  TerminalViewModel.ALIVE_STATES 互补：STOPPING 仍在收尾，写空操作无害）。 */
        val DEAD_SESSION_STATES = setOf(
            com.apex.agent.platform.terminal.session.SessionState.EXITED,
            com.apex.agent.platform.terminal.session.SessionState.LOST,
            com.apex.agent.platform.terminal.session.SessionState.FAILED,
            com.apex.agent.platform.terminal.session.SessionState.BROKEN,
            com.apex.agent.platform.terminal.session.SessionState.CLOSED,
        )
    }
}
