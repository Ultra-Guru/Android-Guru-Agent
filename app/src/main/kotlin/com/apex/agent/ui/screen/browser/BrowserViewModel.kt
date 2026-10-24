package com.apex.agent.ui.screen.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.browser.chrome.BrowserEngineGateway
import com.apex.browser.chrome.InAppChromeHost
import com.apex.browser.chrome.bridge.ApexChromeWiring
import com.apex.browser.engine.BrowserEngine
import com.apex.browser.engine.BrowserTracer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// ═══════════════════════════════════════════════════════════════════════════
//  ViewModel
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 浏览器大功能页 ViewModel —— 会话状态 / 标签 / 最近动作的只读镜像 + 人工操作入口。
 *
 * v1.5.1 起本页双形态：**浏览器页签**（App 内嵌完整浏览器，[InAppChromeHost]
 * 承载引擎 WebView，[gateway] 驱动 BrowserChrome 全套外观——地址栏/标签/查找/
 * 下载/弹窗；零悬浮窗权限）+ **仪表盘页签**（观测/干预面板，本类状态镜像的
 * 消费方）。实时页面浮窗（[LiveViewOverlay]，Agent 驾驶时自动弹出的透明卡片）
 * 与悬浮球的入口不变，本类不再直接驱动它——「查看实时页面」一律切到浏览器
 * 页签（in-app 承载仲裁会让浮窗让位，页面直接在 App 内看）。
 *
 * 状态源：[BrowserEngine.addUiCallback]（引擎内部线程回调，与
 * CyberNeonBallManager 的订阅同款线程模型）——经 viewModelScope（Main.immediate）
 * 主线程化后连同标签快照 / trace 尾部一并推入 StateFlow；[onCleared] 注销回调。
 *
 * 实时动作流（v1.5 UX 增强）：AGENT_DRIVING 期间以 [DRIVING_POLL_INTERVAL_MS]
 * 周期轮询发布（tracer.recent + tabsSnapshot）——驾驶舱动作卡从「手动刷新」
 * 变「实时滚动」；非驾驶态自动暂停（状态回调与手动刷新仍在，HIDDEN 无可轮）。
 * 轮询经 uiState.state 的 distinctUntilChanged 驱动，publish 不改 state →
 * 无自激循环。
 *
 * 操作按钮走引擎 suspend API（newTab / releaseBrowser / enterHandoffMode /
 * completeHandoff）与 chrome 层同款主线程 API（switchTab / closeTab）：
 * 引擎内部以 stateMutex 串行化，非法状态迁移是幂等空操作（如 RECOVERING
 * 期间不介入、非 WAITING_HUMAN 交还无效），UI 无需本地状态机。
 *
 * 失败反馈（v1.5 UX 增强）：动作失败不再静默——[actionError] 一次性事件流
 * 暴露给 UI（LocalFeedbackController snackbar），AppLogger 照旧留痕。
 */
@HiltViewModel
class BrowserViewModel @Inject constructor(
    private val engine: BrowserEngine,
    private val tracer: BrowserTracer,
    // v1.5.1：App 内嵌浏览器（浏览器页签）——chrome 数据源与承载控制器
    private val chromeWiring: ApexChromeWiring,
    val inAppHost: InAppChromeHost,
) : ViewModel() {

    data class BrowserUiState(
        val state: BrowserEngine.BrowserSessionState = BrowserEngine.BrowserSessionState.HIDDEN,
        val url: String? = null,
        val title: String? = null,
        /** 标签快照：Triple(index, url, title)。 */
        val tabs: List<Triple<Int, String, String>> = emptyList(),
        /** 最近自动化动作（tool/结果摘要/耗时/状态，最多 10 条）。 */
        val actions: List<BrowserTracer.Entry> = emptyList(),
    )

    /**
     * 动作失败语义（UI 据此映射本地化文案；ViewModel 不持有资源上下文，
     * i18n 留在 Compose 层取词）。
     */
    enum class BrowserActionError(val action: String) {
        SWITCH_TAB("switchTab"),
        CLOSE_TAB("closeTab"),
    }

    private val _uiState = MutableStateFlow(BrowserUiState())
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    /** 一次性动作失败事件（UI 收集 → snackbar；展示后调 [consumeActionError] 清空）。 */
    private val _actionError = MutableStateFlow<BrowserActionError?>(null)
    val actionError: StateFlow<BrowserActionError?> = _actionError.asStateFlow()

    /** 浏览器页签：BrowserChrome 的命令/数据边界（地址栏/标签/查找/弹窗全走它）。 */
    val gateway: BrowserEngineGateway get() = chromeWiring.gateway

    /** 消费当前失败事件（防止旋转/重组重复弹同一条反馈）。 */
    fun consumeActionError() {
        _actionError.value = null
    }

    /** 引擎状态订阅者：回调线程不确定（Agent 工具线程 / 主线程混杂）→ 主线程统一快照。 */
    private val callback = object : BrowserEngine.BrowserUiCallback {
        override fun onStateChanged(
            state: BrowserEngine.BrowserSessionState,
            url: String?,
            title: String?,
        ) {
            viewModelScope.launch { publish(state, url, title) }
        }
    }

    /** AGENT_DRIVING 期间的实时轮询任务（状态离开驾驶态即被取消）。 */
    private var drivingPollJob: Job? = null

    init {
        engine.addUiCallback(callback)
        // v1.8.0 实时动作流（事件驱动，替代纯轮询的动作卡数据源）：tracer 每条
        // record 即时推来时立刻刷新镜像——动作卡从「最慢 1.5s 一拍」变「落记录即滚」。
        // 驾驶态轮询（下方）保留，继续兜底 tabs 快照等非 trace 数据的刷新。
        viewModelScope.launch {
            tracer.events.collect { publishSnapshot() }
        }
        // 实时动作流驱动：state 变化（回调/刷新发布）→ 按需启停轮询。
        viewModelScope.launch {
            uiState.map { it.state }.distinctUntilChanged().collect { state ->
                drivingPollJob?.cancel()
                if (state == BrowserEngine.BrowserSessionState.AGENT_DRIVING) {
                    drivingPollJob = launch {
                        while (isActive) {
                            delay(DRIVING_POLL_INTERVAL_MS)
                            refresh()
                        }
                    }
                }
            }
        }
    }

    override fun onCleared() {
        engine.removeUiCallback(callback)
        super.onCleared()
    }

    /**
     * 手动刷新（页面进入 / 操作按钮执行后调用）：从引擎读当前状态快照。
     * viewModelScope 默认 Main.immediate —— tabsSnapshot/activeTab 的标签读
     * 与 chrome 层同线程，无交错。
     */
    fun refresh() {
        val tab = engine.activeTab()
        publish(engine.currentState, tab?.url, tab?.title)
    }

    /**
     * 打开浏览器（v1.5.1，浏览器页签空态与仪表盘「打开浏览器」共用）：
     * HIDDEN → 复用首个标签（无则新建空白页）。**正常浏览器语义**——用户
     * 输入网址自由浏览，不改变会话状态、不锁 Agent 工具（v1.5.2 修正：旧实现
     * 一打开就 enterHandoffMode 进 WAITING_HUMAN，用户看到的全是「人工接管/
     * 工具已锁定」信息，而不是浏览器）。人工接管是显式动作：仪表盘「人工接管」
     * 按钮或 Agent 的 BrowserShow 工具才进入 WAITING_HUMAN。
     * 非 HIDDEN 幂等空操作（页面已在，浏览器页签直接展示当前实况）。
     */
    fun openBrowser() = launchAction("openBrowser") {
        if (engine.currentState == BrowserEngine.BrowserSessionState.HIDDEN) {
            if (engine.tabsSnapshot().isEmpty()) engine.newTab()
        }
    }

    /** 新建浏览器会话（空白页起步；页面加载后状态回调自然驱动 UI）。 */
    fun newSession() = launchAction("newTab") {
        engine.newTab()
    }

    /** 结束会话：状态回落 HIDDEN，浮窗/悬浮球随之收起。 */
    fun endSession() = launchAction("releaseBrowser") {
        engine.releaseBrowser()
    }

    /** 人工接管：仅 AGENT_DRIVING 时展示该按钮，进入 WAITING_HUMAN 锁定自动化工具。 */
    fun takeOver() = launchAction("enterHandoffMode") {
        engine.enterHandoffMode()
    }

    /** 交还 Agent：仅 WAITING_HUMAN 时展示该按钮（引擎对非该状态调用幂等空操作）。 */
    fun handBack() = launchAction("completeHandoff") {
        engine.completeHandoff()
    }

    /**
     * 切换激活标签页（chrome 层 TabStrip 同款主线程路径）。
     * 引擎返回 false = 标签已不存在（并发关闭竞态）→ 失败事件，不静默。
     */
    fun switchTab(id: Int) = launchAction("switchTab", BrowserActionError.SWITCH_TAB) {
        check(engine.switchTab(id)) { "tab #$id not found" }
    }

    /**
     * 关闭标签页并销毁其 WebView（关掉激活页时引擎自动回落首个标签）。
     * 引擎返回 false = 标签已不存在 → 失败事件，不静默。
     */
    fun closeTab(id: Int) = launchAction("closeTab", BrowserActionError.CLOSE_TAB) {
        check(engine.closeTab(id)) { "tab #$id not found" }
    }

    /**
     * 统一动作出口：runCatching 兜底（异常不杀进程）+ 失败事件（[failure]
     * 非空时暴露给 UI snackbar，AppLogger 照旧留痕）+ 动作后即时刷新镜像。
     */
    private inline fun launchAction(
        action: String,
        failure: BrowserActionError? = null,
        crossinline block: suspend () -> Unit,
    ) {
        viewModelScope.launch {
            runCatching { block() }
                .onFailure {
                    AppLogger.instance.warn(
                        LogCategory.UI, TAG, "browser screen action '$action' failed: ${it.message}"
                    )
                    if (failure != null) _actionError.value = failure
                }
            refresh()
        }
    }

    /**
     * 发布一次完整镜像（状态回调 / 手动刷新共用；Main 线程上读标签与 trace 尾部）。
     * [publishSnapshot] 为「保持当前状态、仅刷新数据面」的变体（v1.8.0 事件驱动路径）。
     */
    private fun publish(
        state: BrowserEngine.BrowserSessionState,
        url: String?,
        title: String?,
    ) {
        _uiState.update {
            it.copy(
                state = state,
                url = url,
                title = title,
                tabs = engine.tabsSnapshot(),
                actions = tracer.recent(10),
            )
        }
    }

    private fun publishSnapshot() {
        _uiState.update {
            it.copy(
                tabs = engine.tabsSnapshot(),
                actions = tracer.recent(10),
            )
        }
    }

    private companion object {
        const val TAG = "BrowserScreen"

        /**
         * AGENT_DRIVING 实时轮询周期：2.5s（v1.8.0 起 action 卡走 tracer 事件流
         * 即时驱动，轮询只兜底标签快照等非 trace 数据，放缓以省 trace 读开销）。
         */
        const val DRIVING_POLL_INTERVAL_MS = 2_500L
    }
}
