package com.apex.agent.ui.screen.terminal

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apex.agent.R
import com.apex.agent.environment.EnvironmentProvisioner
import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.ubuntu.lifecycle.UbuntuLifecycleCoordinator
import com.apex.agent.ui.language.LanguageManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * T97（Ubuntu 大重构）—— Ubuntu 开发环境安装页 ViewModel。
 *
 * 取代旧 [TerminalViewModel]（控制台编排：输入管线/多会话/历史/黑白名单门禁
 * —— 全部随「Ubuntu 归 Agent 全权掌控」决策退役）。本 VM 只保留**环境面**：
 *  - Ubuntu rootfs 生命周期（安装/修复/删除/占用）；
 *  - 依赖安装中心（[TerminalDepCenter]：版本自选 + 批量已装探测）。
 *
 * 会话/命令执行不在 UI 层 —— Agent 侧经 TerminalUbuntuEnsure / TerminalExec /
 * TerminalLinuxPackages 等工具全权操作（ToolModule 注册）。
 */
@HiltViewModel
class UbuntuEnvironmentViewModel @Inject constructor(
    @ApplicationContext context: Context,
    terminalRuntime: TerminalRuntime,
    private val ubuntuLifecycle: UbuntuLifecycleCoordinator,
    private val lang: LanguageManager
) : ViewModel() {

    internal val depCenter = TerminalDepCenter(
        scope = viewModelScope,
        prefs = context.getSharedPreferences("apex_terminal", Context.MODE_PRIVATE),
        runtime = terminalRuntime,
        provisioner = EnvironmentProvisioner(terminalRuntime, ubuntuLifecycle),
        lang = lang
    )

    /** Ubuntu rootfs 磁盘占用（卡片数据源；null = 未知/未安装）。 */
    internal var rootfsSize by mutableStateOf<Long?>(null)
        private set

    /** Ubuntu 生命周期状态（rootfs 卡数据源）。 */
    val ubuntuLifecycleState: StateFlow<UbuntuLifecycleCoordinator.LifecycleState> =
        ubuntuLifecycle.stateFlow

    /** Ubuntu 安装/引导进度流。 */
    val ubuntuProgress: Flow<UbuntuLifecycleCoordinator.LifecycleProgress>
        get() = ubuntuLifecycle.progressFlow()

    // ═══════════════ 通知（类型化，短横幅）═══════════════

    data class TerminalNotice(
        val text: String,
        val kind: Kind
    ) {
        enum class Kind { INFO, ERROR }
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

    init {
        // READY 到达 → 刷新占用 + 批量探测已装状态（状态徽章）。
        viewModelScope.launch {
            ubuntuLifecycle.stateFlow.collect { st ->
                if (st.phase == UbuntuLifecycleCoordinator.Phase.READY) {
                    refreshRootfsSize()
                    depCenter.refreshStatuses()
                }
            }
        }
    }

    // ═══════════════ 状态探测入口（守卫：仅 READY 时探测）═══════════════

    /**
     * 手动刷新已装状态（顶栏刷新钮）—— 仅 READY 时放行：
     * 未就绪时 ensureDepInstallSession 会尝试拉起 Ubuntu（ensureReady），
     * 「查状态」不该隐式触发重型安装。
     */
    fun refreshDepStatuses() {
        if (ubuntuLifecycle.stateFlow.value.phase == UbuntuLifecycleCoordinator.Phase.READY) {
            depCenter.refreshStatuses()
        }
    }

    // ═══════════════ 环境生命周期动作（rootfs 卡委托）═══════════════

    /** 触发 Ubuntu 安装/引导（进度经 ubuntuLifecycleState 流回）。 */
    fun installUbuntu() {
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { ubuntuLifecycle.ensureReady() }.getOrNull() }
            if (r is UbuntuLifecycleCoordinator.EnsureResult.Failed) {
                notifyError(R.string.term_notice_ubuntu_unavailable, r.message?.take(200) ?: "")
            } else if (r == null) {
                notifyError(R.string.term_notice_ubuntu_unavailable, "…")
            }
        }
    }

    /** 取消进行中的安装。 */
    fun cancelInstallUbuntu() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { ubuntuLifecycle.cancelInstall() }
        }
    }

    /** 刷新 rootfs 磁盘占用（IO；卡片显示）。 */
    fun refreshRootfsSize() {
        viewModelScope.launch {
            rootfsSize = withContext(Dispatchers.IO) {
                runCatching { ubuntuLifecycle.rootfsSizeBytes() }.getOrNull()
            }
        }
    }

    /** 删除 Ubuntu rootfs（删除确认后的动作；拒绝场景如实通知）。 */
    fun removeUbuntu() {
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { ubuntuLifecycle.removeRootfs() }.getOrNull()
            }
            if (outcome == null) {
                notifyError(R.string.term_notice_ubuntu_unavailable, "removeRootfs failed")
                return@launch
            }
            if (outcome.removed) {
                rootfsSize = null
                notifyInfo(R.string.term_env_removed)
            } else {
                // 拒绝：安装进行中 / 会话占用 / 锁忙 —— 人可读原因直接上横幅。
                notifyError(R.string.term_notice_ubuntu_unavailable, outcome.message.take(160))
            }
        }
    }
}
