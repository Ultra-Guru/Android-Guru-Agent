package com.apex.agent.ui.screen.terminal

import android.content.SharedPreferences
import com.apex.agent.R
import com.apex.agent.environment.DepItem
import com.apex.agent.environment.EnvironmentProvisioner
import com.apex.agent.platform.TerminalWait.WaitCondition
import com.apex.agent.platform.TerminalWait.WaitResult
import com.apex.agent.platform.terminal.events.TerminalEvent
import com.apex.agent.platform.terminal.io.InputOwner
import com.apex.agent.platform.terminal.io.UnixSignal
import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.session.SessionState
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
 * 环境依赖安装中心（T97 大重构）—— Ubuntu 开发环境安装页的执行编排器。
 *
 * T97 决策背景：终端控制台 UI 整体移除（Ubuntu 归 Agent 全权掌控，
 * Agent 侧经 TerminalUbuntuEnsure / TerminalExec / TerminalLinuxPackages
 * 等工具操作环境）—— 用户侧只剩「装什么开发环境」这一个动作面。
 *
 * 职责：
 *  - 依赖目录（[com.apex.agent.environment.DepCatalog]，版本自选）；
 *  - TUNA 镜像开关（持久化 dep_use_mirror，同时同步 Provisioner）；
 *  - 单项/批量安装（版本插值后的真实 apt / 直链下载命令）；
 *  - **批量已装探测**（一条复合命令探测全部条目 → 状态徽章）。
 *
 * 会话路由语义：DepCatalog 的命令必须跑在 linux-ubuntu 会话（AndroidShell
 * 后端已移除 —— terminal 收敛 Ubuntu 单后端）。Ubuntu 拉起失败时如实报错
 * 引导安装 rootfs，绝不伪造成功。
 *
 * 输出口径保持批式 execAndAppend（安装日志整段追加 + 真实退出码尾注）。
 */
internal class TerminalDepCenter(
    private val scope: CoroutineScope,
    private val prefs: SharedPreferences,
    private val runtime: TerminalRuntime,
    private val provisioner: EnvironmentProvisioner,
    private val lang: LanguageManager
) {

    /** 依赖条目（T97：版本自选模型）。 */
    val depItems: List<DepItem> = com.apex.agent.environment.DepCatalog.ALL

    private val _useMirror = MutableStateFlow(prefs.getBoolean(KEY_USE_MIRROR, true))
    val useMirror: StateFlow<Boolean> = _useMirror.asStateFlow()

    fun setUseMirror(on: Boolean) {
        prefs.edit().putBoolean(KEY_USE_MIRROR, on).apply()
        _useMirror.value = on
        provisioner.setUseMirror(on)
    }

    /** 安装运行态（runningId = 正在安装的条目 id；log = 安装输出滚动窗）。 */
    data class InstallState(
        val runningId: String? = null,
        val log: String = ""
    )

    private val _install = MutableStateFlow(InstallState())
    val install: StateFlow<InstallState> = _install.asStateFlow()

    /** 已装探测运行态（installed: depId → true 已装 / false 未装）。 */
    data class StatusState(
        val checking: Boolean = false,
        val installed: Map<String, Boolean> = emptyMap()
    )

    private val _status = MutableStateFlow(StatusState())
    val status: StateFlow<StatusState> = _status.asStateFlow()

    /** 安装会话缓存（IO 线程写、主线程读 —— @Volatile 保证跨线程可见）。 */
    @Volatile
    private var depSessionId: Long? = null

    /** 批量探测互斥（防抖：上一轮未完成时忽略新请求）。 */
    @Volatile
    private var statusChecking = false

    // ═══════════════ 安装动作（版本感知）═══════════════

    /** 安装单个条目（[version] = 所选版本；无版本条目忽略之）。 */
    fun installDep(item: DepItem, version: String? = null) {
        val cmd = item.installCommand(_useMirror.value, version)
        val label = if (item.versioned && version != null) "${item.id}@$version" else item.id
        runCommand(label, cmd)
    }

    /** 全部安装（逐条顺序执行；版本取各条目默认/首选）。 */
    fun installAll() {
        scope.launch {
            _install.update {
                it.copy(
                    runningId = "__all__",
                    log = it.log + lang.getString(R.string.term_notice_install_all_start, _useMirror.value.toString())
                )
            }
            // try/finally 兜底 —— 循环中任一异常杀死协程后 runningId 永不复位
            // → 安装按钮灰死到 VM 销毁（T92 修复语义保持）。
            try {
                depItems.forEach { item ->
                    val cmd = item.installCommand(_useMirror.value, item.preferredVersion)
                    execAndAppend(item.id, cmd)
                }
                _install.update { it.copy(log = it.log + lang.getString(R.string.term_notice_install_all_done)) }
            } finally {
                _install.update { it.copy(runningId = null) }
            }
        }
    }

    /** 仅安装 Android 组（cmdline-tools → platform-tools → build-tools → NDK）。 */
    fun installAndroidOnly() {
        scope.launch {
            val items = depItems.filter { it.group == com.apex.agent.environment.DepGroup.ANDROID }
            _install.update {
                it.copy(
                    runningId = "__android__",
                    log = it.log + lang.getString(R.string.term_notice_install_android_start, _useMirror.value.toString())
                )
            }
            try {
                items.forEach { item ->
                    val cmd = item.installCommand(_useMirror.value, item.preferredVersion)
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
                refreshStatuses()
            }
        }
    }

    // ═══════════════ 批量已装探测 ════════════════

    /**
     * 一条复合命令探测全部条目（`check && echo "D <id> OK" || echo "D <id> MISSING"`）。
     *
     * 输出解析取**每 id 最后一次匹配** —— PTY 回显先于真实输出（回显行是复合
     * 长行，行首锚定 `D <id> …` 不匹配；即便换行折射出碎片，真实输出在后，
     * last-match-wins 仍指向真值）。
     */
    fun refreshStatuses() {
        if (statusChecking) return
        statusChecking = true
        scope.launch {
            _status.update { it.copy(checking = true) }
            try {
                val sid = withContext(Dispatchers.IO) { ensureDepInstallSession() } ?: run {
                    _status.update { it.copy(checking = false) }
                    return@launch
                }
                val raw = withContext(Dispatchers.IO) {
                    runCollect(sid, buildStatusCommand(), timeoutMs = 90_000)
                }
                val parsed = parseStatuses(raw)
                if (parsed.isNotEmpty()) _status.update { it.copy(installed = parsed) }
            } finally {
                statusChecking = false
                _status.update { it.copy(checking = false) }
            }
        }
    }

    private fun buildStatusCommand(): String =
        depItems.joinToString("; ") { item ->
            "${item.checkCommand} >/dev/null 2>&1 && echo \"D ${item.id} OK\" || echo \"D ${item.id} MISSING\""
        }

    private fun parseStatuses(raw: String): Map<String, Boolean> {
        val known = depItems.map { it.id }.toSet()
        val out = LinkedHashMap<String, Boolean>()
        raw.replace("\r\n", "\n").lines().forEach { line ->
            STATUS_LINE.find(line.trim())?.let { m ->
                val id = m.groupValues[1]
                if (id in known) out[id] = m.groupValues[2] == "OK"
            }
        }
        return out
    }

    // ═══════════════ 执行底座（run + wait + observe）═══════════════

    private suspend fun execAndAppend(id: String, cmd: String) {
        // 会话拉起（含 proot 探测/forkpty）全部在 IO 线程（T92 语义保持）。
        val sid = withContext(Dispatchers.IO) {
            ensureDepInstallSession()
        } ?: run {
            _install.update { it.copy(log = it.log + lang.getString(R.string.term_notice_no_pty)) }
            return
        }
        val output = withContext(Dispatchers.IO) {
            runCollect(sid, cmd, timeoutMs = 300_000)
        }
        _install.update { it.copy(log = it.log + output) }
    }

    /**
     * run → wait(ProcessExited) → observe(RAW)：返回输出尾窗 + 退出码尾注。
     *
     * @param timeoutMs 等待预算（安装类 300s —— openjdk/SDK 类慢网普遍超 120s；
     * 探测类 90s）。超时发 SIGTERM 温和终止（与 Provisioner 对齐）。
     */
    private suspend fun runCollect(sid: Long, cmd: String, timeoutMs: Long): String {
        val runResult = runtime.run(sid, cmd, InputOwner.SYSTEM, background = false)
        val run = runResult.getOrElse { return lang.getString(R.string.term_notice_run_failed, it.message ?: "") }
        val waitResult = runtime.wait(sid, WaitCondition.ProcessExited(jobId = run.jobId), timeoutMs)
        val wait = waitResult.getOrElse { return lang.getString(R.string.term_notice_wait_failed, it.message ?: "") }
        val exitCode = when (wait) {
            is WaitResult.Matched -> {
                val ev = wait.event
                if (ev is TerminalEvent.ProcessExited) ev.exitCode ?: -1 else 0
            }
            is WaitResult.Timeout -> {
                runtime.signal(sid, UnixSignal.SIGTERM, InputOwner.SYSTEM, run.jobId)
                return lang.getString(R.string.term_notice_wait_timeout)
            }
            is WaitResult.SessionGone -> return lang.getString(R.string.term_notice_session_gone)
        }
        val obs = runtime.observe(sid, TerminalRuntime.ObserveMode.RAW, run.startCursor, 65536)
            .getOrNull()?.raw ?: ""
        val tail = if (obs.length > 4000) lang.getString(R.string.term_notice_truncated) + obs.takeLast(4000) else obs
        return tail + if (exitCode != 0) "\n[exit=$exitCode]\n" else "\n"
    }

    // ═══════════════ 会话路由 ════════════════

    /** 实时存活探测（安装链专用）—— 快照 SESSIONS 模式直查 runtime。 */
    private suspend fun isSessionAliveRealtime(sid: Long): Boolean {
        val snap = runtime.snapshot(TerminalRuntime.SnapshotMode.SESSIONS, sessionId = sid)
            .getOrNull() ?: return false
        return snap.sessions.any { it.session.id == sid && it.session.state in ALIVE_STATES }
    }

    /**
     * 依赖安装的会话路由（T82 断点修复语义 + T92 实时存活校验 + T97 去
     * SessionRegistry 依赖）。
     */
    private suspend fun ensureDepInstallSession(): Long? {
        val cached = depSessionId
        if (cached != null && isSessionAliveRealtime(cached)) return cached
        if (cached != null) depSessionId = null
        provisioner.ensureUbuntuSession()?.let {
            depSessionId = it
            return it
        }
        // AndroidShell 后端已移除：无降级 —— apt 只在 Ubuntu 会话可用，
        // 引导用户先装 Ubuntu（rootfs 卡）。
        _install.update { it.copy(log = it.log + lang.getString(R.string.term_notice_ubuntu_unavailable) + "\n") }
        return null
    }

    private companion object {
        /** dep 开关的持久化键（SharedPreferences "apex_terminal"）。 */
        const val KEY_USE_MIRROR = "dep_use_mirror"

        /** 状态行锚定（`D <id> OK|MISSING`）。 */
        val STATUS_LINE = Regex("""^D ([a-zA-Z0-9_-]+) (OK|MISSING)$""")

        /** 会话存活态（T96 SessionRegistry 语义迁入 —— 控制台退役后自持）。 */
        val ALIVE_STATES: Set<SessionState> = setOf(
            SessionState.CREATED,
            SessionState.STARTING,
            SessionState.READY,
            SessionState.RUNNING,
            SessionState.WAITING_INPUT,
            SessionState.INTERRUPTED
        )
    }
}
