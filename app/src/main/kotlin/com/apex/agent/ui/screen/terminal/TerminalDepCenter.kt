package com.apex.agent.ui.screen.terminal

import android.content.SharedPreferences
import com.apex.agent.R
import com.apex.agent.environment.EnvironmentProvisioner
import com.apex.agent.platform.terminal.io.InputOwner
import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.ui.language.LanguageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 环境依赖安装中心（P0-1 拆分）—— [EnvironmentProvisioner] 的 UI 侧编排：
 * 依赖目录、镜像开关、安装运行态与 apt 命令的会话路由。
 *
 * 旧实现整段挤在 `TerminalViewModel` 里（连同 [EnvironmentProvisioner] 构造、
 * depSessionId 缓存、run/wait/observe 全链），是 VM 九类职责中最大的一块。
 * 本类收口后 VM 只保留「入口委托」。
 *
 * 会话路由（T82 断点修复语义保留）：DepCatalog 的 apt 命令必须跑在
 * linux-ubuntu 会话（Android shell 里只有 command not found）。Ubuntu 拉起
 * 失败时诚实降级到 local session（输出真实报错，绝不伪造成功）。
 *
 * public：[TerminalViewModel] 的公开 API（depItems/install/installDep…）委托
 * 到本类嵌套类型，internal 类会触发「public exposes internal type」。
 */
class TerminalDepCenter(
    private val scope: CoroutineScope,
    private val prefs: SharedPreferences,
    private val runtime: TerminalRuntime,
    private val provisioner: EnvironmentProvisioner,
    private val registry: SessionRegistry,
    private val lang: LanguageManager,
    private val onSessionsChanged: () -> Unit
) {

    data class DepItem(
        val id: String,
        val name: String,
        val group: DepGroup,
        val installOfficial: String,
        val installMirror: String,
        val checkCommand: String
    )

    enum class DepGroup { GENERAL, ANDROID }

    val depItems: List<DepItem> =
        com.apex.agent.environment.DepCatalog.ALL.map {
            DepItem(it.id, it.name, DepGroup.valueOf(it.group.name), it.installOfficial, it.installMirror, it.checkCommand)
        }

    private val _useMirror = MutableStateFlow(prefs.getBoolean(KEY_USE_MIRROR, true))
    val useMirror: StateFlow<Boolean> = _useMirror.asStateFlow()

    fun setUseMirror(on: Boolean) {
        prefs.edit().putBoolean(KEY_USE_MIRROR, on).apply()
        _useMirror.value = on
        provisioner.setUseMirror(on)
    }

    /** 依赖安装运行态（runningId = 正在安装的条目 id；log = 安装输出滚动窗）。 */
    data class InstallState(
        val runningId: String? = null,
        val log: String = ""
    )

    private val _install = MutableStateFlow(InstallState())
    val install: StateFlow<InstallState> = _install.asStateFlow()

    /** M2：ensureDepInstallSession 在 IO 线程写、主线程读（sendInput 门禁）——
     *  跨线程可见性用 @Volatile 保证（旧版普通 var 可能读到陈旧值）。 */
    @Volatile
    private var depSessionId: Long? = null

    fun installDep(item: DepItem) {
        val useMirror = _useMirror.value
        val cmd = if (useMirror) item.installMirror else item.installOfficial
        runCommand(item.id, cmd)
    }

    fun installAll(onProgress: (Int, Int) -> Unit = { _, _ -> }) {
        scope.launch {
            _install.update { it.copy(runningId = "__all__", log = it.log + lang.getString(R.string.term_notice_install_all_start, _useMirror.value.toString())) }
            // T92：try/finally 兑底 —— 旧行为循环中任一异常（ensureReady 抛出等）
            // 杀死协程后 runningId 永不复位 → 环境中心全部安装按钮灰死到 VM 销毁。
            try {
                depItems.forEachIndexed { index, item ->
                    onProgress(index, depItems.size)
                    val cmd = if (_useMirror.value) item.installMirror else item.installOfficial
                    execAndAppend(item.id, cmd)
                }
                _install.update { it.copy(log = it.log + lang.getString(R.string.term_notice_install_all_done)) }
            } finally {
                _install.update { it.copy(runningId = null) }
            }
        }
    }

    fun installAndroidOnly(onProgress: (Int, Int) -> Unit = { _, _ -> }) {
        scope.launch {
            val items = depItems.filter { it.group == DepGroup.ANDROID }
            _install.update { it.copy(runningId = "__android__", log = it.log + lang.getString(R.string.term_notice_install_android_start, _useMirror.value.toString())) }
            try {
                items.forEachIndexed { index, item ->
                    onProgress(index, items.size)
                    val cmd = if (_useMirror.value) item.installMirror else item.installOfficial
                    execAndAppend(item.id, cmd)
                }
                _install.update { it.copy(log = it.log + lang.getString(R.string.term_notice_install_android_done)) }
            } finally {
                _install.update { it.copy(runningId = null) }
            }
        }
    }

    private fun runCommand(id: String, cmd: String) {
        scope.launch {
            _install.update { it.copy(runningId = id, log = it.log + "\n▶ [$id] $cmd\n") }
            try {
                execAndAppend(id, cmd)
            } finally {
                _install.update { it.copy(runningId = null) }
            }
        }
    }

    private suspend fun execAndAppend(id: String, cmd: String) {
        // T92：会话拉起（含 proot 探测/forkpty）全部在 IO 线程 —— 旧行为
        // ensureDepInstallSession 在 withContext(IO) **之外**，主线程 fork +
        // StrictMode 违例（createSessionInternal 已修同类问题，此路径漏修）。
        val sid = withContext(Dispatchers.IO) {
            ensureDepInstallSession()
        } ?: run {
            _install.update { it.copy(log = it.log + lang.getString(R.string.term_notice_no_pty)) }
            return
        }
        val output = withContext(Dispatchers.IO) {
            val runResult = runtime.run(sid, cmd, InputOwner.SYSTEM, background = false)
            val run = runResult.getOrElse { return@withContext lang.getString(R.string.term_notice_run_failed, it.message ?: "") }
            // T92：300s + SIGTERM 温和终止 —— 旧行为 120s 后直接 SIGKILL：
            // openjdk/SDK 类 apt 在慢网普遍 >120s，被硬杀留下半安装状态，日志
            // 还误报「等待超时」（实际是被自己杀的）。与 Provisioner 300s 对齐。
            val waitResult = runtime.wait(sid, com.apex.agent.platform.TerminalWait.WaitCondition.ProcessExited(jobId = run.jobId), 300_000)
            val wait = waitResult.getOrElse { return@withContext lang.getString(R.string.term_notice_wait_failed, it.message ?: "") }
            val exitCode = when (wait) {
                is com.apex.agent.platform.TerminalWait.WaitResult.Matched -> {
                    val ev = wait.event
                    if (ev is com.apex.agent.platform.terminal.events.TerminalEvent.ProcessExited) ev.exitCode ?: -1 else 0
                }
                is com.apex.agent.platform.TerminalWait.WaitResult.Timeout -> {
                    runtime.signal(sid, com.apex.agent.platform.terminal.io.UnixSignal.SIGTERM, InputOwner.SYSTEM, run.jobId)
                    return@withContext lang.getString(R.string.term_notice_wait_timeout)
                }
                is com.apex.agent.platform.TerminalWait.WaitResult.SessionGone -> return@withContext lang.getString(R.string.term_notice_session_gone)
            }
            val obs = runtime.observe(sid, TerminalRuntime.ObserveMode.RAW, run.startCursor, 65536)
                .getOrNull()?.raw ?: ""
            val tail = if (obs.length > 4000) lang.getString(R.string.term_notice_truncated) + obs.takeLast(4000) else obs
            tail + if (exitCode != 0) "\n[exit=$exitCode]\n" else "\n"
        }
        _install.update { it.copy(log = it.log + output) }
    }

    /**
     * T92：实时存活探测（安装链专用）—— 会话列表来自 2s 轮询，可能滞后；
     * 写入死 PTY 会让 job 挂到超时。安装前直查 runtime（快照 SESSIONS 模式，
     * 低频调用成本可忽略）。
     */
    private suspend fun isSessionAliveRealtime(sid: Long): Boolean {
        val snap = runtime.snapshot(TerminalRuntime.SnapshotMode.SESSIONS, sessionId = sid)
            .getOrNull() ?: return false
        return snap.sessions.any { it.session.id == sid && it.session.state in SessionRegistry.ALIVE_STATES }
    }

    /**
     * T82 断点修复：依赖安装的会话路由。T92：depSessionId 存活校验改为
     * **实时**（旧用 2s 轮询快照，会话死亡后最长 2s 内误判存活 → 写死 PTY 假超时）。
     */
    private suspend fun ensureDepInstallSession(): Long? {
        val cached = depSessionId
        if (cached != null && isSessionAliveRealtime(cached)) return cached
        if (cached != null) depSessionId = null  // 死亡 → 清缓存重建
        provisioner.ensureUbuntuSession()?.let {
            depSessionId = it
            return it
        }
        // 降级：复用当前活跃的 local 会话（无则新建）
        val active = registry.activeSessionId.value
        if (active != null && registry.sessionTabs.value.any { it.id == active && it.isAlive }) {
            depSessionId = active
            return active
        }
        _install.update { it.copy(log = it.log + lang.getString(R.string.term_notice_fallback_android)) }
        val r = runtime.create(backendId = TerminalViewModel.BACKEND_LOCAL)
        return if (r.isSuccess) {
            val sid = r.getOrThrow().sessionId
            registry.recordBackend(sid, TerminalViewModel.BACKEND_LOCAL, "ANDROID_LOCAL")
            depSessionId = sid
            onSessionsChanged()
            sid
        } else null
    }

    companion object {
        /** dep 开关的持久化键（SharedPreferences "apex_terminal"）。 */
        internal const val KEY_USE_MIRROR = "dep_use_mirror"
    }
}
