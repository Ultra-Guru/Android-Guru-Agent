package com.apex.agent.ui.screen.code

import com.apex.agent.core.engine.AgentEvent
import com.apex.agent.ui.screen.code.fleet.AgentFleetStore
import com.apex.agent.ui.screen.code.fleet.FleetDashboardState
import com.apex.agent.ui.screen.code.fleet.FleetTab
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

// ─────────────────────────────────────────────────────────────────────────────
// Coding 工位「子 Agent / 终端命令」仪表盘 —— CodeViewModel 同包 internal 扩展
//（God-file 预算纪律：VM 行数只减不增，fleet 接线全部住在本文件）。
//
// 三条接线（Spec §3.2/§4）：
//   1. 主代理命令旁路：runEngine 的 collect 内一行 onFleetEngineEvent ——
//      只认 BASH 族 ToolCallStart/Complete；
//   2. 会话边界换号：init / clearConversationWithArchive / restoreCodeHistoryEntry
//      / bindWorkspace（切工作区）—— 铸新 UUID 弃旧桶（迟到旧 id 事件即丢弃）；
//   3. 中止收口：abort() → RUNNING 行折叠为 PARTIAL/CANCELLED（不收口则
//      永远挂着「运行中」）。
//
// 子代理实例与内部命令不经 VM：SubAgentRunner 观察者（ToolModule 注入的
// FleetSubAgentObserver）直写同一 store 单例（conversationId 在 onStart 绑定）。
// ─────────────────────────────────────────────────────────────────────────────

/** VM 冷启动铸首个会话号（无工作区时也保证非 unbound——首跑前必须已换号）。 */
internal fun CodeViewModel.initFleetConversation() {
    fleetStore.newConversation()
}

/** 主代理引擎事件旁路（collect 内一行；store 只认命令族 Start/Complete）。 */
internal fun CodeViewModel.onFleetEngineEvent(event: AgentEvent) {
    fleetStore.onEngineEvent(event)
}

/** 会话边界换号（新会话/恢复历史/切工作区）：弃旧桶 + Tab 复位，可见性保持。 */
internal fun CodeViewModel.fleetResetConversation() {
    fleetStore.newConversation()
}

/** 用户中止：在飞 RUNNING 行就地收口（与时间轴 abortLocally 同根因同口径）。 */
internal fun CodeViewModel.onFleetAbort() {
    fleetStore.cancelRunning()
}

/** 浮层可见性 toggle（工具栏行右端小按钮；状态住 uiState，随 VM 重建复位）。 */
fun CodeViewModel.toggleFleetDashboard() {
    _uiState.update { it.copy(fleetDashboardVisible = !it.fleetDashboardVisible) }
}

/** Tab 切换（子 Agent / 命令；会话内持久，会话边界随新桶复位）。 */
fun CodeViewModel.selectFleetTab(tab: FleetTab) {
    fleetStore.selectTab(tab)
}

/** 仪表盘数据流（CodeScreen 组合层 collectAsStateWithLifecycle 消费）。 */
val CodeViewModel.fleetDashboard: StateFlow<FleetDashboardState>
    get() = fleetStore.state
