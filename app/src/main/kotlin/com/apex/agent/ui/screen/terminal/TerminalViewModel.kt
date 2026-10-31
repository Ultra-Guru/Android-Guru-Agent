package com.apex.agent.ui.screen.terminal

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apex.agent.R
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.environment.EnvironmentProvisioner
import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.ubuntu.lifecycle.UbuntuLifecycleCoordinator
import com.apex.agent.ui.language.LanguageManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * M3 控制台 ViewModel（T96 —— 终端大重构）。
 *
 * 真实执行编排中枢：会话生命周期（创建/关闭/重启/恢复）× 环境联动
 * （Ubuntu PRoot 优先 + 诚实降级）× 提交管线（门禁 → 历史 → CMD 行 →
 * [RealCommandExecutor] 流式真实输出 → 真实退出码 EXIT 行）。
 *
 * 与旧交互终端（T83，已删除）的差异：
 *  - 输入不再是 PTY 字节流（readline 镜像/DECCKM/括号粘贴），而是
 *    OutlinedTextField 单源状态 [input] —— 控制台语义；
 *  - 渲染不再是 VT 快照网格（TerminalRenderOrchestrator 已随交互视图退役），
 *    而是按会话的 [ConsoleLine] 行列表；
 *  - 命令执行是真实 PTY job（runtime.run + marker 退出码协议）——
 *    与模拟实现（M3 旧模块，已删除）彻底告别。
 *
 * 保留自旧 VM 的语义（功能转移清单）：多会话簿 [SessionRegistry]、Ubuntu
 * 优先默认策略（READY 自动拉起 + 明确失败降级 LOCAL）、mksh profile 播种、
 * 崩溃恢复可见死标签、类型化通知、命令黑白名单、历史记录 + ↑/↓ 召回、
 * 依赖安装中心、设置持久化。
 */
@HiltViewModel
class TerminalViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val terminalRuntime: TerminalRuntime,
    // T82: Ubuntu 产品级生命周期 —— 环境配置面板与依赖安装路由的底座。
    private val ubuntuLifecycle: UbuntuLifecycleCoordinator,
    // i18n：通知/安装日志/EXIT 行文案（非 Compose 场景，LanguageManager 取词）
    private val lang: LanguageManager,
    // 两个 store 由 TerminalModule 提供 Hilt 单例：设置中心「终端」分类与
    // 本 VM 共享同一内存态（任意一侧改动经同一 StateFlow 即时广播）。
    // 配色方案单例随旧交互终端退役（scheme 子包已删除），不再注入。
    private val settingsStore: TerminalSettingsStore,
    private val policyStore: CommandPolicyStore
) : ViewModel() {

    // ═══════════════ 协作组件 ═══════════════

    private val prefs = context.getSharedPreferences("apex_terminal", Context.MODE_PRIVATE)

    /** 多会话元数据簿（会话标签行的唯一数据源）。 */
    internal val registry = SessionRegistry(terminalRuntime)

    /** 命令历史（记录 + ↑/↓ 召回）。 */
    internal val commandHistory = TerminalCommandHistory(context)

    /** 环境依赖安装中心（apt 路由；EnvironmentProvisioner 编排）。 */
    internal val depCenter = TerminalDepCenter(
        scope = viewModelScope,
        prefs = prefs,
        runtime = terminalRuntime,
        provisioner = EnvironmentProvisioner(terminalRuntime, ubuntuLifecycle),
        registry = registry,
        lang = lang,
        onSessionsChanged = ::refreshSessions
    )

    /** 真实命令执行器（流式输出 + 真实退出码）。 */
    internal val executor = RealCommandExecutor(terminalRuntime)

    // ═══════════════ 控制台状态（Compose 直读）═══════════════

    /**
     * 输入框唯一状态源【输入同步】。
     *
     * OutlinedTextField 的 value 与控制台卡片内的当前输入行共用同一状态 ——
     * 两处渲染零漂移；提交后清空亦只经过本字段，无第二数据源、无额外回调。
     */
    var input by mutableStateOf("")
        private set

    /** 每会话的输出行列表（多对话：会话切换时各自独立保留）。 */
    internal val consoleLines = mutableStateMapOf<Long, SnapshotStateList<ConsoleLine>>()

    /** 每会话的运行中命令（null = 空闲；提交双守卫 + 停止按钮数据源）。 */
    internal val runningCommands = mutableStateMapOf<Long, String>()

    /** Ubuntu rootfs 磁盘占用（环境中心卡片数据源；null = 未知/未安装）。 */
    internal var rootfsSize by mutableStateOf<Long?>(null)
        private set

    /** 历史召回游标（-1 = 实时输入位；↑ = 向更旧移动）。 */
    private var historyIndex = -1

    /** 离开实时位时暂存的半成品输入（↓ 回到实时位时恢复）。 */
    private var savedLiveInput = ""

    // ═══════════════ 会话状态流（委托 registry）═══════════════

    val sessions: StateFlow<List<SessionTab>> = registry.sessionTabs
    val activeSessionId: StateFlow<Long?> = registry.activeSessionId

    /** Ubuntu 生命周期状态（环境配置面板/横幅数据源）。 */
    val ubuntuLifecycleState: StateFlow<UbuntuLifecycleCoordinator.LifecycleState> =
        ubuntuLifecycle.stateFlow

    /** Ubuntu 安装/引导进度流（环境面板步骤条数据源）。 */
    val ubuntuProgress: kotlinx.coroutines.flow.Flow<UbuntuLifecycleCoordinator.LifecycleProgress>
        get() = ubuntuLifecycle.progressFlow()

    /** 控制台设置。 */
    internal val settings: StateFlow<TerminalSettings> = settingsStore.settings

    /** 命令历史（最新在前）。 */
    val history: StateFlow<List<String>> = commandHistory.entries

    // ═══════════════ 通知（类型化）═══════════════

    data class TerminalNotice(
        val text: String,
        val kind: Kind
    ) {
        enum class Kind { INFO, ERROR, FALLBACK }
    }

    private val _notice = MutableStateFlow<TerminalNotice?>(null)
    val notice: StateFlow<TerminalNotice?> = _notice.asStateFlow()

    fun consumeNotice() { _notice.value = null }

    private fun notifyInfo(resId: Int, vararg args: Any) {
        _notice.value = TerminalNotice(lang.getString(resId, *args), TerminalNotice.Kind.INFO)
    }

    private fun notifyError(resId: Int, vararg args: Any) {
        _notice.value = TerminalNotice(lang.getString(resId, *args), TerminalNotice.Kind.ERROR)
    }

    private fun notifyFallback(resId: Int, vararg args: Any) {
        _notice.value = TerminalNotice(lang.getString(resId, *args), TerminalNotice.Kind.FALLBACK)
    }

    // ═══════════════ 会话生命周期 ═══════════════

    /** T85：Ubuntu 优先默认会话策略挂起标记（语义与旧版一致）。 */
    @Volatile
    private var autoUbuntuSessionPending = false

    private val createMutex = Mutex()
    private var pollJob: Job? = null
    private var creating = false

    init {
        viewModelScope.launch {
            // Crash recovery (Spec §39)：恢复的会话以真实死亡态可见 ——
            // 标签列表可选中，覆盖层给「重启会话」，绝不伪造存活。
            val recovered = withContext(Dispatchers.IO) { terminalRuntime.recover() }
            if (recovered.isNotEmpty()) {
                notifyFallback(R.string.term_notice_recovered, recovered.size)
            }
            refreshSessionsInternal()
            if (registry.sessionTabs.value.none { it.isAlive }) {
                if (ubuntuLifecycle.stateFlow.value.phase == UbuntuLifecycleCoordinator.Phase.READY) {
                    createMutex.withLock { createSessionInternal(SessionRegistry.BACKEND_UBUNTU) }
                } else {
                    autoUbuntuSessionPending = true
                    val r = withContext(Dispatchers.IO) {
                        runCatching { ubuntuLifecycle.ensureReady() }.getOrNull()
                    }
                    val failed = r == null || r is UbuntuLifecycleCoordinator.EnsureResult.Failed
                    if (failed && autoUbuntuSessionPending &&
                        registry.sessionTabs.value.none { it.isAlive }
                    ) {
                        autoUbuntuSessionPending = false
                        val reason = (r as? UbuntuLifecycleCoordinator.EnsureResult.Failed)
                            ?.message?.take(80)
                        // AndroidShell 后端已移除：不再降级本地会话 —— 用户经空态
                        // 覆盖层的「重试 / 安装 Ubuntu」引导（terminal Ubuntu-only）。
                        notifyError(R.string.term_notice_ubuntu_unavailable, reason ?: "…")
                    }
                }
            } else {
                registry.sessionTabs.value.firstOrNull { it.isAlive }?.let { selectSession(it.id) }
            }
            startSessionPolling()
        }

        // 环境 READY 到达 → ①用户仍未手工建过会话时自动拉起 Ubuntu 会话；②刷新 rootfs 占用。
        viewModelScope.launch {
            ubuntuLifecycle.stateFlow.collect { st ->
                if (st.phase == UbuntuLifecycleCoordinator.Phase.READY) {
                    refreshRootfsSize()
                    if (autoUbuntuSessionPending &&
                        registry.sessionTabs.value.none { it.isAlive }
                    ) {
                        autoUbuntuSessionPending = false
                        createMutex.withLock { createSessionInternal(SessionRegistry.BACKEND_UBUNTU) }
                    }
                }
            }
        }
    }

    /** 用户主动新建（会话标签行 + 号菜单；取消 READY 自动拉起）。 */
    fun createSession(backendId: String) {
        if (creating) return
        creating = true
        autoUbuntuSessionPending = false
        viewModelScope.launch {
            try {
                createMutex.withLock { createSessionInternal(backendId) }
            } finally {
                creating = false
            }
        }
    }

    private suspend fun createSessionInternal(backendId: String) {
        if (backendId == SessionRegistry.BACKEND_UBUNTU) {
            val r = withContext(Dispatchers.IO) { ubuntuLifecycle.ensureReady() }
            if (r is UbuntuLifecycleCoordinator.EnsureResult.Failed) {
                notifyError(R.string.term_notice_ubuntu_unavailable, r.message?.take(200) ?: "")
                return
            }
        }
        val created = withContext(Dispatchers.IO) {
            terminalRuntime.create(backendId = backendId)
        }
        val result = created.getOrElse { e ->
            notifyError(R.string.term_notice_create_failed, e.message?.take(200) ?: "")
            return
        }
        registry.recordBackend(result.sessionId, result.backendId, result.runtimeType)
        // 新会话的落点横幅（INFO 行，进入输出流首行）
        linesOf(result.sessionId).add(
            ConsoleLine(ConsoleLine.LineKind.INFO, lang.getString(R.string.term_session_opened, labelOf(backendId)))
        )
        refreshSessionsInternal()
        selectSession(result.sessionId)
    }

    fun closeSession(id: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { terminalRuntime.close(id, force = true) }
            registry.evict(id)
            consoleLines.remove(id)
            runningCommands.remove(id)
            refreshSessionsInternal()
            if (registry.activeSessionId.value == id) {
                registry.sessionTabs.value.firstOrNull { it.isAlive }?.let { selectSession(it.id) }
            }
        }
    }

    /** 重启当前会话（保留 backend；死会话覆盖层的动作）。 */
    fun restartActiveSession() {
        val active = registry.activeSessionId.value ?: return
        val tab = registry.sessionTabs.value.firstOrNull { it.id == active } ?: return
        // Ubuntu-only：重启一律回 linux-ubuntu（旧 local 会话一并收敛）。
        val backend = SessionRegistry.BACKEND_UBUNTU
        viewModelScope.launch {
            withContext(Dispatchers.IO) { terminalRuntime.close(active, force = true) }
            registry.evict(active)
            consoleLines.remove(active)
            runningCommands.remove(active)
            refreshSessionsInternal()
            createMutex.withLock { createSessionInternal(backend) }
        }
    }

    fun selectSession(id: Long) {
        if (registry.select(id)) {
            // 控制台行随会话独立保留 —— 切换无需搬运；清召回游标防串台
            historyIndex = -1
            savedLiveInput = ""
        }
    }

    // ═══════════════ 会话簿轮询 ═══════════════

    fun refreshSessions() {
        viewModelScope.launch { refreshSessionsInternal() }
    }

    private suspend fun refreshSessionsInternal() {
        registry.refresh()
    }

    private fun startSessionPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            val events = terminalRuntime.sessionLifecycleEvents()
            if (events != null) {
                events.collect { refreshSessionsInternal() }
            } else {
                // 兜底轮询（与旧版同口径：2s）
                while (true) {
                    delay(2_000)
                    refreshSessionsInternal()
                }
            }
        }
    }

    // ═══════════════ 输入 / 提交管线 ═══════════════

    /**
     * 输入框变更入口【输入同步】。
     *
     * 唯一写入口（TextField onValueChange 与任何程序化赋值都走这里）——
     * 同时复位历史召回游标（手动编辑即离开召回态）。
     */
    fun onInputChange(value: String) {
        input = value
        historyIndex = -1
        savedLiveInput = ""
    }

    /** ↑/↓ 历史召回（entries 最新在前；↑=更旧，↓=更新，越过末位回实时位）。 */
    fun moveHistory(delta: Int) {
        val entries = commandHistory.entries.value
        if (entries.isEmpty()) return
        when {
            historyIndex == -1 && delta < 0 -> {
                savedLiveInput = input
                historyIndex = 0
                input = entries[0]
            }
            historyIndex == -1 -> return
            else -> {
                val older = delta < 0
                val next = if (older) historyIndex + 1 else historyIndex - 1
                when {
                    next < 0 -> {
                        historyIndex = -1
                        input = savedLiveInput
                    }
                    next >= entries.size -> return
                    else -> {
                        historyIndex = next
                        input = entries[next]
                    }
                }
            }
        }
    }

    /**
     * 提交当前输入（发送按钮 / IME Send / 回车）。
     *
     * 管线：双守卫（会话存活 + 空闲）→ 空命令丢弃 → 黑白名单 → 历史记录 →
     * 控制台级内建（clear/help）→ CMD 行 → 真实执行 → 流式 OUT → EXIT 行。
     */
    fun submit() {
        val sid = registry.activeSessionId.value
        if (sid == null) {
            notifyInfo(R.string.term_notice_no_session_input)
            return
        }
        if (runningCommands[sid] != null) return
        val tab = registry.sessionTabs.value.firstOrNull { it.id == sid }
        if (tab == null || !tab.isAlive) {
            notifyError(R.string.term_notice_session_dead)
            return
        }
        val cmd = input.trim()
        if (cmd.isEmpty()) return
        if (!policyStore.isCommandAllowed(cmd)) {
            notifyError(R.string.term_notice_blocked, cmd.take(40))
            return
        }
        commandHistory.record(cmd)
        logUserCommand(cmd)
        historyIndex = -1
        savedLiveInput = ""

        // 控制台级内建（语义属于视图操作，不走 PTY）
        when (cmd) {
            "clear", "cls" -> {
                linesOf(sid).clear()
                input = "" // 【输入同步】提交侧清空
                return
            }
            "help" -> {
                lang.getString(R.string.term_help_body).lines()
                    .filter { it.isNotBlank() }
                    .forEach { linesOf(sid).add(ConsoleLine(ConsoleLine.LineKind.INFO, it)) }
                input = ""
                return
            }
        }

        linesOf(sid).add(ConsoleLine(ConsoleLine.LineKind.CMD, cmd))
        input = "" // 【输入同步】命令进入输出流后清空输入框
        runningCommands[sid] = cmd

        viewModelScope.launch {
            val result = executor.execute(sid, cmd) { lines ->
                val bucket = linesOf(sid)
                lines.forEach { bucket.add(ConsoleLine(ConsoleLine.LineKind.OUT, it)) }
            }
            runningCommands.remove(sid)
            when (result) {
                is RealCommandExecutor.ExecResult.Completed -> {
                    val timedOut = result.timedOut
                    val code = result.exitCode
                    val kind = when {
                        timedOut -> ConsoleLine.LineKind.EXIT_BAD
                        code == null || code == 0 -> ConsoleLine.LineKind.EXIT_OK
                        else -> ConsoleLine.LineKind.EXIT_BAD
                    }
                    val text = when {
                        timedOut -> lang.getString(R.string.term_exit_timeout)
                        code == null -> lang.getString(R.string.term_exit_unknown)
                        code == 0 -> lang.getString(R.string.term_exit_ok)
                        else -> lang.getString(R.string.term_exit_code, code)
                    }
                    linesOf(sid).add(ConsoleLine(kind, text))
                }
                is RealCommandExecutor.ExecResult.RunFailed -> {
                    linesOf(sid).add(ConsoleLine(ConsoleLine.LineKind.ERROR, lang.getString(R.string.term_notice_run_failed, result.message.take(80))))
                    notifyError(R.string.term_notice_run_failed, result.message.take(80))
                }
                is RealCommandExecutor.ExecResult.WaitFailed -> {
                    linesOf(sid).add(ConsoleLine(ConsoleLine.LineKind.ERROR, lang.getString(R.string.term_notice_wait_failed, result.message.take(80))))
                }
                is RealCommandExecutor.ExecResult.SessionGone -> {
                    linesOf(sid).add(ConsoleLine(ConsoleLine.LineKind.EXIT_BAD, lang.getString(R.string.term_exit_session_gone)))
                    refreshSessions()
                }
            }
        }
    }

    /** 停止当前会话的前台 job（SIGINT —— 与真实终端 Ctrl-C 同义）。 */
    fun stopActive() {
        val sid = registry.activeSessionId.value ?: return
        if (runningCommands[sid] == null) return
        viewModelScope.launch {
            executor.interrupt(sid)
        }
    }

    // ═══════════════ 环境配置动作（面板/中心 Sheet 委托）═══════════════

    /** 触发 Ubuntu 安装/引导（进度经 ubuntuLifecycleState 流回）。 */
    fun installUbuntu() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { ubuntuLifecycle.ensureReady() }
        }
    }

    /** 取消进行中的安装。 */
    fun cancelInstallUbuntu() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { ubuntuLifecycle.cancelInstall() }
        }
    }

    /** 刷新 rootfs 磁盘占用（IO；环境中心卡片显示）。 */
    fun refreshRootfsSize() {
        viewModelScope.launch {
            rootfsSize = withContext(Dispatchers.IO) {
                runCatching { ubuntuLifecycle.rootfsSizeBytes() }.getOrNull()
            }
        }
    }

    /** 删除 Ubuntu rootfs（环境中心删除确认后的动作）。 */
    fun removeUbuntu() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { ubuntuLifecycle.removeRootfs() }
            rootfsSize = null
        }
    }

    // ═══════════════ 设置 / 名单 / 历史 委托 ═══════════════

    internal fun updateSettings(block: TerminalSettings.() -> TerminalSettings) = settingsStore.update(block)

    // ═══ 黑名单 / 白名单命令（P0-1：持久化与门禁在 CommandPolicyStore）═══

    val blacklist: StateFlow<Set<String>> get() = policyStore.blacklist
    val whitelist: StateFlow<Set<String>> get() = policyStore.whitelist

    fun addBlacklist(cmd: String) = policyStore.addBlacklist(cmd)
    fun removeBlacklist(cmd: String) = policyStore.removeBlacklist(cmd)
    fun addWhitelist(cmd: String) = policyStore.addWhitelist(cmd)
    fun removeWhitelist(cmd: String) = policyStore.removeWhitelist(cmd)

    fun clearHistory() = commandHistory.clear()

    /**
     * 日志增强：用户命令提交进日志中枢（TERMINAL 分类）。
     *
     * 只记通过门禁的提交（与 commandHistory 入史同口径）；Agent 侧终端
     * 命令不在此记（引擎事件 ToolCallStart/Complete 已带输出摘录，双计
     * 会重复）。交互 PTY 的输出流无法按命令归因（readline/全屏程序混流），
     * 故不记输出 —— Agent 侧结构化输出在 EngineLogExtensions 有 2KB 摘录。
     * 失败静默（观测不干扰输入链路）。
     */
    private fun logUserCommand(cmd: String) {
        runCatching {
            AppLogger.instance.info(
                LogCategory.TERMINAL, "UserInput",
                "提交命令: $cmd",
                tags = arrayOf("terminal", "user", "submit")
            )
        }
    }

    /** 复制成功反馈（历史 Sheet 复制按钮 → 通知条）。 */
    fun onHistoryCopied(chars: Int) {
        notifyInfo(R.string.term_copy_done, chars)
    }

    // ═══════════════ 内部工具 ═══════════════

    /** 取会话行桶（懒建）。 */
    internal fun linesOf(sid: Long): SnapshotStateList<ConsoleLine> =
        consoleLines.getOrPut(sid) { mutableStateListOf() }

    private fun labelOf(backendId: String): String = when (backendId) {
        SessionRegistry.BACKEND_UBUNTU -> lang.getString(R.string.term_tab_ubuntu)
        else -> backendId
    }

    // onCleared 不关闭会话：runtime 是单例（与 Agent 工具共享），会话存活
    // 跨屏保留（多对话语义）；退出 App 由 runtime 层统一收割。
}
