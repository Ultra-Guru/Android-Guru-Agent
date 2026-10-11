package com.apex.agent.ui.screen.browser

import androidx.annotation.StringRes
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apex.agent.R
import com.apex.agent.ui.glass.GlassButton
import com.apex.agent.ui.glass.GlassCard
import com.apex.agent.ui.glass.GlassIconButton
import com.apex.agent.ui.theme.statusWarning
import com.apex.browser.engine.BrowserEngine
import com.apex.browser.engine.BrowserTracer

// ═══════════════════════════════════════════════════════════════════════════
//  页面
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 浏览器大功能页（v1.5.0 一级入口）—— 浏览器自动化的驾驶舱。
 *
 * 浏览器自动化已内置（24 个 browser_* 工具，Agent 对话中直接指挥）；本页是
 * 人侧的观测/干预面板：会话状态卡（显式握手状态机的人话解释 + 当前页面）→
 * 标签列表（[BrowserEngine.tabsSnapshot]）→ 最近自动化动作（[BrowserTracer.recent]）
 * → 人工操作（新建会话 / 结束会话 / 人工接管 / 交还 Agent，均为引擎 suspend API）
 * → 底部内置说明卡。悬浮球与浮窗浏览器入口见说明卡。
 */
@Composable
fun BrowserScreen(viewModel: BrowserViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // 进入页面拉一次当前快照（此后由引擎状态回调驱动；trace 动作列表随状态变更顺带刷新）
    LaunchedEffect(Unit) { viewModel.refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ── 头部：标题 + 副标题 + 手动刷新 ──
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.browser_screen_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    stringResource(R.string.browser_screen_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            GlassIconButton(
                icon = Icons.Outlined.Refresh,
                contentDescription = stringResource(R.string.browser_screen_refresh),
                onClick = viewModel::refresh
            )
        }

        SessionCard(state)
        TabsCard(state.tabs)
        ActionsCard(state.actions)
        OperationsCard(
            state = state.state,
            onNewSession = viewModel::newSession,
            onEndSession = viewModel::endSession,
            onTakeOver = viewModel::takeOver,
            onHandBack = viewModel::handBack
        )
        BuiltinCard()
    }
}

// ═══════════════════════════════════════════════════════════════════════════
//  区块组件
// ═══════════════════════════════════════════════════════════════════════════

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

/** 标签列表卡：tabsSnapshot（index/url/title），空态「暂无标签页」。 */
@Composable
private fun TabsCard(tabs: List<Triple<Int, String, String>>) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardHeader(icon = Icons.Outlined.Tab, title = stringResource(R.string.browser_screen_tabs_title))
            if (tabs.isEmpty()) {
                EmptyHint(stringResource(R.string.browser_screen_tabs_empty))
            } else {
                tabs.forEach { (id, url, title) ->
                    Column {
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
                }
            }
        }
    }
}

/** 最近自动化动作卡：tracer.recent(10) —— 工具 / 结果摘要 / 耗时 / 状态。 */
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

/** 底部说明卡：内置大功能 + 悬浮球/浮窗入口指引（旧插件用户也可在此得到「已内置」的解释）。 */
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
