package com.apex.agent.ui.screen.browser

import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apex.agent.R
import com.apex.agent.ui.component.FeedbackSeverity
import com.apex.agent.ui.component.LocalFeedbackController
import com.apex.agent.ui.glass.GlassButton
import com.apex.agent.ui.glass.GlassCard
import com.apex.agent.ui.glass.GlassIconButton
import com.apex.agent.ui.theme.statusWarning
import com.apex.browser.engine.BrowserEngine
import com.apex.browser.engine.BrowserTracer

// ═══════════════════════════════════════════════════════════════════════════
//  页面
// ═══════════════════════════════════════════════════════════════════════════

/** 页面形态：浏览器（App 内嵌完整浏览器）/ 仪表盘（观测与人工干预）。 */
private enum class BrowserScreenMode { BROWSER, DASHBOARD }

/**
 * 浏览器大功能页（v1.5.0 一级入口；v1.5.1 双页签重做）。
 *
 * **浏览器页签**（默认）：App 内嵌完整浏览器（[InAppBrowserPane] ——
 * BrowserChrome 全套外观 + 引擎 WebView，零悬浮窗权限即可用）。Agent 的
 * 34 个 browser_* 工具驱动同一引擎：Agent 驾驶时本页即「查看实时页面」
 * 的全屏形态；「接管」经 WAITING_HUMAN 显式握手（横幅 + 工具锁定）。
 *
 * **仪表盘页签**：人侧观测/干预面板 —— 会话状态卡（显式握手状态机的人话
 * 解释 + 当前页面）→ 标签列表（[BrowserEngine.tabsSnapshot]，可点切/可关闭）
 * → 最近自动化动作（[BrowserTracer.recent]，AGENT_DRIVING 时 1.5s 实时滚动）
 * → 人工操作（新建/结束/接管/交还）→ 底部说明卡；悬浮窗未授权时顶部权限
 * 引导卡（不再静默降级）。悬浮球与浮窗浏览器入口见说明卡。
 *
 * 动作失败经 [LocalFeedbackController] snackbar 反馈（不静默）。
 */
@Composable
fun BrowserScreen(viewModel: BrowserViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val actionError by viewModel.actionError.collectAsStateWithLifecycle()
    val feedback = LocalFeedbackController.current

    var mode by remember { mutableStateOf(BrowserScreenMode.BROWSER) }
    // 悬浮窗权限判态：进页读一次 + ON_RESUME 重检（从系统授权页返回即刷新；
    // 与 PermissionsScreen 同款时序，避免「授权后仍显示未授权」）
    val context = LocalContext.current
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        overlayGranted = Settings.canDrawOverlays(context)
    }

    // 进入页面拉一次当前快照（此后由引擎状态回调 + 驾驶态轮询驱动）
    LaunchedEffect(Unit) { viewModel.refresh() }

    // 动作失败反馈：一次性事件（snackbar 展示后消费，旋转/重组不重复弹）。
    // 文案在组合内预取（LaunchedEffect 体内非组合上下文，不调 stringResource）。
    val switchFailMsg = stringResource(R.string.browser_screen_switch_failed)
    val closeFailMsg = stringResource(R.string.browser_screen_close_failed)
    LaunchedEffect(actionError) {
        when (actionError) {
            BrowserViewModel.BrowserActionError.SWITCH_TAB ->
                feedback.show(switchFailMsg, FeedbackSeverity.ERROR)
            BrowserViewModel.BrowserActionError.CLOSE_TAB ->
                feedback.show(closeFailMsg, FeedbackSeverity.ERROR)
            null -> Unit
        }
        viewModel.consumeActionError()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── 头部：双页签切换 + 手动刷新（仪表盘数据；浏览器页签自驱动） ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ModeChip(
                label = stringResource(R.string.browser_screen_tab_browser),
                icon = Icons.Outlined.Public,
                selected = mode == BrowserScreenMode.BROWSER,
                onClick = { mode = BrowserScreenMode.BROWSER },
                modifier = Modifier.weight(1f),
            )
            ModeChip(
                label = stringResource(R.string.browser_screen_tab_dashboard),
                icon = Icons.Outlined.Dashboard,
                selected = mode == BrowserScreenMode.DASHBOARD,
                onClick = { mode = BrowserScreenMode.DASHBOARD },
                modifier = Modifier.weight(1f),
            )
            GlassIconButton(
                icon = Icons.Outlined.Refresh,
                contentDescription = stringResource(R.string.browser_screen_refresh),
                onClick = viewModel::refresh,
            )
        }

        when (mode) {
            BrowserScreenMode.BROWSER -> InAppBrowserPane(
                gateway = viewModel.gateway,
                inAppHost = viewModel.inAppHost,
                sessionState = state.state,
                tabsEmpty = state.tabs.isEmpty(),
                overlayGranted = overlayGranted,
                onOpenBrowser = viewModel::openBrowser,
                onHandBack = viewModel::handBack,
                modifier = Modifier.weight(1f),
            )

            BrowserScreenMode.DASHBOARD -> Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (!overlayGranted) OverlayPermissionCard()
                SessionCard(state)
                LiveCard(
                    state = state.state,
                    onViewLivePage = {
                        // 查看实时页面 = 切到浏览器页签（HIDDEN 顺带开会话进人工接管）；
                        // in-app 承载仲裁让实时浮窗让位，页面直接在 App 内看。
                        viewModel.openBrowser()
                        mode = BrowserScreenMode.BROWSER
                    },
                )
                TabsCard(
                    tabs = state.tabs,
                    onSwitchTab = viewModel::switchTab,
                    onCloseTab = viewModel::closeTab,
                )
                ActionsCard(state.actions)
                OperationsCard(
                    state = state.state,
                    onNewSession = viewModel::newSession,
                    onEndSession = viewModel::endSession,
                    onTakeOver = viewModel::takeOver,
                    onHandBack = viewModel::handBack,
                )
                BuiltinCard()
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
//  区块组件
// ═══════════════════════════════════════════════════════════════════════════

/** 双页签切换胶囊：选中态高亮主色（accent），未选中中性。 */
@Composable
private fun ModeChip(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassButton(
        text = label,
        onClick = onClick,
        modifier = modifier,
        accent = if (selected) MaterialTheme.colorScheme.primary else Color.Unspecified,
        leadingIcon = icon,
    )
}

/** 会话状态卡：状态名（色点同色）+ 一句人话解释 + 当前页面（标题/URL）。 */
@Composable
private fun SessionCard(state: BrowserViewModel.BrowserUiState) {
    val stateColor = sessionStateColor(state.state)
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardHeader(icon = Icons.Outlined.Public, title = stringResource(R.string.browser_screen_session_title))

            // 状态行：色点 + 状态名（颜色与霓虹球状态语义一致：驾驶=主色/人工=琥珀/恢复=错误红）
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(stateColor)
                )
                Text(
                    stringResource(sessionStateLabelRes(state.state)),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = stateColor
                )
            }

            Text(
                stringResource(sessionStateDescRes(state.state)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 当前页面：优先页面标题，缺失回退 URL，两者皆无则空态文案
            Text(
                state.title?.ifBlank { null } ?: state.url ?: stringResource(R.string.browser_screen_page_none),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (state.url != null && !state.title.isNullOrBlank()) {
                Text(
                    state.url,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * 实时页面卡（v1.4.1 引入；v1.5.1 语义升级）：一键切到浏览器页签 ——
 * Agent 驾驶时页签即实时画面（全屏、零拷贝同一 WebView）；HIDDEN 时按钮
 * 变「打开浏览器」（新建会话进人工接管）。透明悬浮窗仍随 Agent 驾驶自动
 * 弹出（供用户切去其他 App 时跟看），悬浮球点击切换。
 */
@Composable
private fun LiveCard(
    state: BrowserEngine.BrowserSessionState,
    onViewLivePage: () -> Unit,
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardHeader(icon = Icons.Outlined.Visibility, title = stringResource(R.string.browser_screen_live_title))
            Text(
                stringResource(liveCardDescRes(state)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            GlassButton(
                text = stringResource(
                    if (state == BrowserEngine.BrowserSessionState.HIDDEN) {
                        R.string.browser_screen_live_open
                    } else {
                        R.string.browser_screen_live_view
                    }
                ),
                onClick = onViewLivePage,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = Icons.Outlined.Visibility
            )
        }
    }
}

/**
 * 标签列表卡：tabsSnapshot（id/url/title），每项可操作——点击整块切换激活
 * 标签（[onSwitchTab]），尾部关闭按钮销毁该标签（[onCloseTab]）；空态「暂无标签页」。
 * 触达性：切换用 clickable 的 onClickLabel、关闭用 IconButton contentDescription。
 */
@Composable
private fun TabsCard(
    tabs: List<Triple<Int, String, String>>,
    onSwitchTab: (Int) -> Unit,
    onCloseTab: (Int) -> Unit,
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardHeader(icon = Icons.Outlined.Tab, title = stringResource(R.string.browser_screen_tabs_title))
            if (tabs.isEmpty()) {
                EmptyHint(stringResource(R.string.browser_screen_tabs_empty))
            } else {
                tabs.forEach { (id, url, title) ->
                    val switchCd = stringResource(R.string.browser_screen_cd_switch_tab, id)
                    val closeCd = stringResource(R.string.browser_screen_cd_close_tab, id)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable(onClickLabel = switchCd) { onSwitchTab(id) }
                        ) {
                            Text(
                                "#$id · " + (title.ifBlank { stringResource(R.string.browser_screen_tab_untitled) }),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (url.isNotBlank()) {
                                Text(
                                    url,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        GlassIconButton(
                            icon = Icons.Outlined.Close,
                            contentDescription = closeCd,
                            onClick = { onCloseTab(id) },
                        )
                    }
                }
            }
        }
    }
}

/** 最近自动化动作卡：tracer.recent(10) —— 工具 / 结果摘要 / 耗时 / 状态。
 *  AGENT_DRIVING 期间由 ViewModel 1.5s 轮询驱动，动作实时滚动；非驾驶态
 *  静止（状态回调仍会顺带刷新）。 */
@Composable
private fun ActionsCard(actions: List<BrowserTracer.Entry>) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardHeader(icon = Icons.Outlined.History, title = stringResource(R.string.browser_screen_actions_title))
            if (actions.isEmpty()) {
                EmptyHint(stringResource(R.string.browser_screen_actions_empty))
            } else {
                actions.forEach { entry ->
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                entry.tool,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${entry.state} · " + stringResource(R.string.browser_screen_duration_ms, entry.durationMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            entry.resultSummary,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/**
 * 人工操作卡：按会话状态切换按钮形态——
 * HIDDEN →「新建浏览器会话」；AGENT_DRIVING →「人工接管」+「结束会话」；
 * WAITING_HUMAN →「交还 Agent」+「结束会话」；RECOVERING → 引擎自愈中，仅说明。
 */
@Composable
private fun OperationsCard(
    state: BrowserEngine.BrowserSessionState,
    onNewSession: () -> Unit,
    onEndSession: () -> Unit,
    onTakeOver: () -> Unit,
    onHandBack: () -> Unit,
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (state) {
                BrowserEngine.BrowserSessionState.HIDDEN -> GlassButton(
                    text = stringResource(R.string.browser_screen_new_session),
                    onClick = onNewSession,
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = Icons.Outlined.Add
                )
                BrowserEngine.BrowserSessionState.RECOVERING ->
                    // 引擎自愈中（渲染进程重建）：状态迁移 API 均为幂等空操作，只说明不提供按钮
                    Text(
                        stringResource(R.string.browser_screen_state_recovering_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                else -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    GlassButton(
                        text = stringResource(
                            if (state == BrowserEngine.BrowserSessionState.WAITING_HUMAN) {
                                R.string.browser_screen_hand_back
                            } else {
                                R.string.browser_screen_take_over
                            }
                        ),
                        onClick = if (state == BrowserEngine.BrowserSessionState.WAITING_HUMAN) onHandBack else onTakeOver,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onEndSession) {
                        Text(
                            stringResource(R.string.browser_screen_end_session),
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

/** 底部说明卡：内置大功能 + 浏览器页签/悬浮球/浮窗入口指引。 */
@Composable
private fun BuiltinCard() {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CardHeader(icon = Icons.Outlined.Info, title = stringResource(R.string.browser_screen_builtin_title))
            Text(
                stringResource(R.string.browser_screen_builtin_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
//  小组件与映射
// ═══════════════════════════════════════════════════════════════════════════

/** 区块卡头：小图标 + 标题（UsageDashboardScreen.CardHeader 的无 hint 变体）。 */
@Composable
private fun CardHeader(icon: ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

/** 空态提示：诚实空态（只引导，不假装有数据）。 */
@Composable
private fun EmptyHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** 状态 → 主题色（与霓虹球状态语义对齐：驾驶=主色 / 人工=琥珀 / 恢复=错误红 / 空闲=中性）。 */
@Composable
private fun sessionStateColor(state: BrowserEngine.BrowserSessionState): Color = when (state) {
    BrowserEngine.BrowserSessionState.HIDDEN -> MaterialTheme.colorScheme.onSurfaceVariant
    BrowserEngine.BrowserSessionState.AGENT_DRIVING -> MaterialTheme.colorScheme.primary
    BrowserEngine.BrowserSessionState.WAITING_HUMAN -> statusWarning()
    BrowserEngine.BrowserSessionState.RECOVERING -> MaterialTheme.colorScheme.error
}

/** 状态 → 展示名资源。 */
@StringRes
private fun sessionStateLabelRes(state: BrowserEngine.BrowserSessionState): Int = when (state) {
    BrowserEngine.BrowserSessionState.HIDDEN -> R.string.browser_screen_state_hidden
    BrowserEngine.BrowserSessionState.AGENT_DRIVING -> R.string.browser_screen_state_agent_driving
    BrowserEngine.BrowserSessionState.WAITING_HUMAN -> R.string.browser_screen_state_waiting_human
    BrowserEngine.BrowserSessionState.RECOVERING -> R.string.browser_screen_state_recovering
}

/** 状态 → 一句人话解释资源。 */
@StringRes
private fun sessionStateDescRes(state: BrowserEngine.BrowserSessionState): Int = when (state) {
    BrowserEngine.BrowserSessionState.HIDDEN -> R.string.browser_screen_state_hidden_desc
    BrowserEngine.BrowserSessionState.AGENT_DRIVING -> R.string.browser_screen_state_agent_driving_desc
    BrowserEngine.BrowserSessionState.WAITING_HUMAN -> R.string.browser_screen_state_waiting_human_desc
    BrowserEngine.BrowserSessionState.RECOVERING -> R.string.browser_screen_state_recovering_desc
}

/** 实时页面卡：状态 → 引导文案资源。 */
@StringRes
private fun liveCardDescRes(state: BrowserEngine.BrowserSessionState): Int = when (state) {
    BrowserEngine.BrowserSessionState.HIDDEN -> R.string.browser_screen_live_desc_idle
    BrowserEngine.BrowserSessionState.AGENT_DRIVING -> R.string.browser_screen_live_desc_driving
    BrowserEngine.BrowserSessionState.WAITING_HUMAN -> R.string.browser_screen_live_desc_handoff
    BrowserEngine.BrowserSessionState.RECOVERING -> R.string.browser_screen_live_desc_recovering
}
