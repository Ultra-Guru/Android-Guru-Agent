package com.apex.agent.ui.screen.terminal

import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.session.SessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * 会话簿（T96 自旧终端原样转移；P0-1 拆分 + P1-1 合并语义保持）——
 * 多会话元数据的唯一权威，控制台会话标签行（多对话）的唯一数据源。
 *
 * backend 由创建方记录（runtime 快照不含该信息）。线程模型：ConcurrentHashMap
 * （DepCenter 在 Dispatchers.IO 里写 meta，主线程 refresh 同时读）。
 */
data class SessionTab(
    val id: Long,
    val backendId: String,
    val runtimeType: String,
    val state: String,
    val isAlive: Boolean,
    val title: String?
) {
    val isUbuntu: Boolean get() = backendId == SessionRegistry.BACKEND_UBUNTU
}

class SessionRegistry(private val runtime: TerminalRuntime) {

    /**
     * 合并后的会话元组（P1-1：backend 与 title 经 `compute` 原子合并互不覆盖）。
     * backend 为 null = 无创建方记录（Agent 创建 / 进程恢复）→ 展示层回落推断。
     */
    data class SessionMeta(
        val backendId: String? = null,
        val runtimeType: String? = null,
        val title: String? = null
    )

    /** 活跃会话消失后 registry 对 VM 的交接指令。 */
    sealed interface Handoff {
        data object Keep : Handoff
        data class Select(val id: Long) : Handoff
        data object Cleared : Handoff
    }

    private val _sessionTabs = MutableStateFlow<List<SessionTab>>(emptyList())
    val sessionTabs: StateFlow<List<SessionTab>> = _sessionTabs.asStateFlow()

    private val _activeSessionId = MutableStateFlow<Long?>(null)
    val activeSessionId: StateFlow<Long?> = _activeSessionId.asStateFlow()

    private val meta = ConcurrentHashMap<Long, SessionMeta>()

    /**
     * 从 runtime 拉取会话列表（状态/存活 + 记录的 backend 标签），并对 meta
     * 做存活修剪（快照里不存在的会话 → meta 即刻驱逐，杜绝僵尸条目累积）。
     */
    suspend fun refresh(): Handoff {
        val snap = runtime.snapshot(TerminalRuntime.SnapshotMode.SESSIONS)
            .getOrNull() ?: return Handoff.Keep
        val tabs = snap.sessions.map { s ->
            val m = meta[s.session.id]
            SessionTab(
                id = s.session.id,
                backendId = m?.backendId ?: BACKEND_AGENT,
                runtimeType = m?.runtimeType ?: inferRuntimeType(s.session.shell),
                state = s.session.state.name,
                isAlive = s.session.state in ALIVE_STATES,
                title = m?.title
            )
        }
        _sessionTabs.value = tabs
        val liveIds = tabs.asSequence().map { it.id }.toSet()
        meta.keys.retainAll(liveIds)
        val active = _activeSessionId.value
        if (active != null && tabs.none { it.id == active }) {
            val next = tabs.firstOrNull { it.isAlive }
            return if (next != null) {
                _activeSessionId.value = next.id
                Handoff.Select(next.id)
            } else {
                _activeSessionId.value = null
                Handoff.Cleared
            }
        }
        return Handoff.Keep
    }

    /** 会话创建成功后登记 backend 元组（保留已有标题）。 */
    fun recordBackend(id: Long, backendId: String, runtimeType: String) {
        meta.compute(id) { _, m -> SessionMeta(backendId, runtimeType, m?.title) }
    }

    /** OSC 0/1/2 标题回写（vim/tmux/ssh 设置的窗口名）。@return 标题是否变化。 */
    fun recordTitle(id: Long, title: String): Boolean {
        var changed = false
        meta.compute(id) { _, m ->
            if (m?.title != title) changed = true
            SessionMeta(m?.backendId, m?.runtimeType, title)
        }
        return changed
    }

    /** 用户切换活跃会话。@return 活跃 id 是否变化。 */
    fun select(id: Long): Boolean {
        if (_activeSessionId.value == id) return false
        _activeSessionId.value = id
        return true
    }

    /** 关闭/重启会话：meta 驱逐。 */
    fun evict(id: Long) {
        meta.remove(id)
    }

    /** 当前所有已知会话 id。 */
    fun liveIds(): Set<Long> = _sessionTabs.value.asSequence().map { it.id }.toSet()

    private fun inferRuntimeType(shell: String): String =
        if (shell.contains("bash", true) || shell.contains("proot", true)) "LINUX" else "ANDROID_LOCAL"

    companion object {
        /** 执行后端常量（T96 自旧 TerminalViewModel 顶部转移；与 platform
         *  ExecutionBackendRegistry 的注册 id 逐字一致）。 */
        const val BACKEND_LOCAL = "local"
        const val BACKEND_UBUNTU = "linux-ubuntu"

        /** 无创建方记录的会话（Agent 创建 / 进程恢复）的展示标签。 */
        const val BACKEND_AGENT = "agent"

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
