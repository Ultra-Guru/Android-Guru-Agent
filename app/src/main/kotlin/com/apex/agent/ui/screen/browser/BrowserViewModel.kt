package com.apex.agent.ui.screen.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.browser.engine.BrowserEngine
import com.apex.browser.engine.BrowserTracer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// ═══════════════════════════════════════════════════════════════════════════
//  ViewModel
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 浏览器大功能页 ViewModel —— 会话状态 / 标签 / 最近动作的只读镜像 + 人工操作入口。
 *
 * 状态源：[BrowserEngine.addUiCallback]（引擎内部线程回调，与
 * CyberNeonBallManager 的订阅同款线程模型）——经 viewModelScope（Main.immediate）
 * 主线程化后连同标签快照 / trace 尾部一并推入 StateFlow；[onCleared] 注销回调。
 *
 * 操作按钮走引擎 suspend API（newTab / releaseBrowser / enterHandoffMode /
 * completeHandoff）：引擎内部以 stateMutex 串行化，非法状态迁移是幂等空操作
 * （如 RECOVERING 期间不介入、非 WAITING_HUMAN 交还无效），UI 无需本地状态机。
 */
@HiltViewModel
class BrowserViewModel @Inject constructor(
    private val engine: BrowserEngine,
    private val tracer: BrowserTracer,
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

    private val _uiState = MutableStateFlow(BrowserUiState())
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

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

    init {
        engine.addUiCallback(callback)
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

    /** 统一动作出口：runCatching 兜底（异常不杀进程）+ 动作后即时刷新镜像。 */
    private inline fun launchAction(action: String, crossinline block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }
                .onFailure {
                    AppLogger.instance.warn(
                        LogCategory.UI, TAG, "browser screen action '$action' failed: ${it.message}"
                    )
                }
            refresh()
        }
    }

    /** 发布一次完整镜像（状态回调 / 手动刷新共用；Main 线程上读标签与 trace 尾部）。 */
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

    private companion object {
        const val TAG = "BrowserScreen"
    }
}
