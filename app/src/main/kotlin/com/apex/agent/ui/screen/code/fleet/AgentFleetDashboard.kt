package com.apex.agent.ui.screen.code.fleet

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.ui.glass.GlassShapes
import com.apex.agent.ui.glass.GlassStyle
import com.apex.agent.ui.glass.PartSurface
import com.apex.agent.ui.theme.CodingPart

/**
 * # Agent Fleet Dashboard — 子 Agent / 终端命令轻量浮层（Spec §2.2–2.4）
 *
 * ## 挂载与形态
 * App 内 Compose 浮层：叠在 CodeScreen 根 Box、内容 Column 之后（经
 * [modifier] 传入 matchParentSize + zIndex）。卡片 = PartSurface(OVERLAY_SHEET)
 * + 当前 conversationSkin 材质（MINIMAL 皮肤经 PartSurface 材质短路自动降级
 * 扁平面板——与 CodeTerminalPanel 的既有降级口径一致）；backdrop 为 null =
 * Frosted 诚实降级档（浮层挂根 Box，不在时间轴 backdrop 采样链内）。
 * **不申请 SYSTEM_ALERT_WINDOW、不出 App、随对话销毁**（附录 B 路径不走）。
 *
 * ## 关闭路径（三条）
 * 右上角关闭按钮 / [BackHandler] / 点击浮层外轻 scrim。scrim 半透明无 blur
 * （复用 GlassModalScrim 的点击拦截形态，但不做模态模糊）；浮层关闭后即
 * 全量消失，不常驻不遮挡。
 *
 * ## 内容 = P0 只读状态列表（双 Tab + 空态）
 * 上方 M3 TabRow 切「子 Agent / 命令」（计数随行）；列表每行 ≤2 行文字 +
 * 状态点（颜色口径对齐 CodeTerminalPanel：绿 34D399 / 红 F87171 / 灰
 * 6B7280）。**无 stdout 全文、无复制/搜索/过滤、无终止/重试/跳转**——
 * 显示「运行中」不意味着可以终止（只读观察层红线）。
 */
@Composable
fun AgentFleetDashboard(
    state: FleetDashboardState,
    onDismiss: () -> Unit,
    onTabSelect: (FleetTab) -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onDismiss)
    Box(modifier = modifier) {
        // 轻量 scrim：半透明、无模态模糊；点击浮层外 = 关闭（Spec §2.2 第三路）。
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.14f))
                .pointerInput(Unit) { detectTapGestures { onDismiss() } }
        )
        PartSurface(
            part = CodingPart.OVERLAY_SHEET,
            classicStyle = GlassStyle.Dialog,
            shape = GlassShapes.dialog,
            backdrop = null,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = (-36).dp)
                .fillMaxWidth(0.86f)
        ) {
            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                FleetHeader(onDismiss = onDismiss)
                FleetTabs(
                    tab = state.tab,
                    subAgentCount = state.subAgents.size,
                    commandCount = state.commands.size,
                    onSelect = onTabSelect
                )
                if (state.tab == FleetTab.SUB_AGENT) {
                    SubAgentList(state.subAgents)
                } else {
                    CommandList(state.commands, state.subAgents)
                }
            }
        }
    }
}

/** 头行：标题 + 右上角关闭（Spec §2.2 关闭路径一）。 */
@Composable
private fun FleetHeader(onDismiss: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 6.dp, end = 4.dp)
    ) {
        Text(
            text = stringResource(R.string.code_fleet_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.code_fleet_close_cd),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 双 Tab（M3 TabRow；计数随行，默认「子 Agent」——Spec §2.3）。 */
@Composable
private fun FleetTabs(
    tab: FleetTab,
    subAgentCount: Int,
    commandCount: Int,
    onSelect: (FleetTab) -> Unit
) {
    TabRow(selectedTabIndex = if (tab == FleetTab.SUB_AGENT) 0 else 1) {
        Tab(
            selected = tab == FleetTab.SUB_AGENT,
            onClick = { onSelect(FleetTab.SUB_AGENT) },
            text = {
                Text(stringResource(R.string.code_fleet_tab_subagents, subAgentCount))
            }
        )
        Tab(
            selected = tab == FleetTab.COMMAND,
            onClick = { onSelect(FleetTab.COMMAND) },
            text = {
                Text(stringResource(R.string.code_fleet_tab_commands, commandCount))
            }
        )
    }
}

/** 列表容器：高度上限 260dp + 纵向滚动（Spec §2.2）。 */
@Composable
private fun FleetListColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .heightIn(max = 260.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        content()
    }
}

/** 子 Agent 只读行（Spec §2.4：类型 · 描述 · 状态 · N 轮/M 工具/Xs）。 */
@Composable
private fun SubAgentList(subAgents: List<FleetSubAgentState>) {
    FleetListColumn {
        if (subAgents.isEmpty()) {
            FleetEmpty(text = stringResource(R.string.code_fleet_empty_subagents))
            return@FleetListColumn
        }
        subAgents.forEach { subAgent ->
            FleetRow(
                dotColor = statusColorOf(subAgent.status),
                title = subAgent.typeKey,
                titleWeight = FontWeight.Medium,
                body = subAgent.description,
                meta = subAgentMeta(subAgent)
            )
            Spacer(Modifier.height(6.dp))
        }
    }
}

/** 命令只读行（Spec §2.4：工具 · 摘要 · 状态 · exit · 通道 · 耗时 · 归属）。 */
@Composable
private fun CommandList(commands: List<FleetCommandState>, subAgents: List<FleetSubAgentState>) {
    FleetListColumn {
        if (commands.isEmpty()) {
            FleetEmpty(text = stringResource(R.string.code_fleet_empty_commands))
            return@FleetListColumn
        }
        commands.forEach { command ->
            FleetRow(
                dotColor = statusColorOf(command.status),
                title = command.toolName,
                titleWeight = FontWeight.Medium,
                body = command.target,
                bodyMonospace = true,
                meta = commandMeta(command, subAgents)
            )
            Spacer(Modifier.height(6.dp))
        }
    }
}

/**
 * 通用行布局：状态点 + 标题/摘要/meta 三段紧凑排布，无点选联动
 *（P0 只读：不做跳转/筛选/终止）。
 */
@Composable
private fun FleetRow(
    dotColor: Color,
    title: String,
    titleWeight: FontWeight,
    body: String,
    bodyMonospace: Boolean = false,
    meta: String
) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .size(8.dp)
                .background(color = dotColor, shape = CircleShape)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = titleWeight,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = if (bodyMonospace) FontFamily.Monospace else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (meta.isNotEmpty()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** 空态（Spec §2.4：「本对话暂无子 Agent / 暂无终端命令」）。 */
@Composable
private fun FleetEmpty(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp)
    )
}

// ── 行文案组装（状态标签 / 统计行 / 命令 meta）──────────────────────

@Composable
private fun subAgentMeta(subAgent: FleetSubAgentState): String {
    val label = stringResource(statusLabelOf(subAgent.status))
    return if (subAgent.durationMs > 0 || subAgent.iterations > 0 || subAgent.toolCalls > 0) {
        label + " · " + stringResource(
            R.string.code_fleet_stats_fmt,
            subAgent.iterations,
            subAgent.toolCalls,
            (subAgent.durationMs + 999L) / 1000L
        )
    } else {
        label
    }
}

@Composable
private fun commandMeta(command: FleetCommandState, subAgents: List<FleetSubAgentState>): String {
    val parts = mutableListOf(stringResource(statusLabelOf(command.status)))
    command.exitCode?.let { parts += stringResource(R.string.code_fleet_exit_fmt, it) }
    command.channel?.let { parts += it }
    if (command.durationMs > 0) parts += stringResource(R.string.code_fleet_duration_ms_fmt, command.durationMs)
    parts += command.ownerSubAgentId
        ?.let { owner -> subAgents.firstOrNull { it.id == owner }?.typeKey ?: owner.take(24) }
        ?: stringResource(R.string.code_fleet_owner_main)
    return parts.joinToString(" · ")
}

// ── 状态 → 标签 / 颜色（颜色口径对齐 CodeTerminalPanel 终端面板）────

private fun statusLabelOf(status: FleetSubAgentStatus): Int = when (status) {
    FleetSubAgentStatus.QUEUED -> R.string.code_fleet_status_queued
    FleetSubAgentStatus.RUNNING -> R.string.code_fleet_status_running
    FleetSubAgentStatus.SUCCESS -> R.string.code_fleet_status_success
    FleetSubAgentStatus.FAILED -> R.string.code_fleet_status_failed
    FleetSubAgentStatus.TIMEOUT -> R.string.code_fleet_status_timeout
    FleetSubAgentStatus.CANCELLED -> R.string.code_fleet_status_cancelled
}

private fun statusLabelOf(status: FleetCommandStatus): Int = when (status) {
    FleetCommandStatus.WAITING -> R.string.code_fleet_status_waiting
    FleetCommandStatus.RUNNING -> R.string.code_fleet_status_running
    FleetCommandStatus.SUCCESS -> R.string.code_fleet_status_success
    FleetCommandStatus.FAILED -> R.string.code_fleet_status_failed
    FleetCommandStatus.PARTIAL -> R.string.code_fleet_status_partial
}

private fun statusColorOf(status: FleetSubAgentStatus): Color = when (status) {
    FleetSubAgentStatus.QUEUED -> Color(0xFF6B7280)
    FleetSubAgentStatus.RUNNING -> Color(0xFF7C3AED)
    FleetSubAgentStatus.SUCCESS -> Color(0xFF34D399)
    FleetSubAgentStatus.FAILED -> Color(0xFFF87171)
    FleetSubAgentStatus.TIMEOUT -> Color(0xFFF59E0B)
    FleetSubAgentStatus.CANCELLED -> Color(0xFF9CA3AF)
}

private fun statusColorOf(status: FleetCommandStatus): Color = when (status) {
    FleetCommandStatus.WAITING -> Color(0xFF6B7280)
    FleetCommandStatus.RUNNING -> Color(0xFF7C3AED)
    FleetCommandStatus.SUCCESS -> Color(0xFF34D399)
    FleetCommandStatus.FAILED -> Color(0xFFF87171)
    FleetCommandStatus.PARTIAL -> Color(0xFF9CA3AF)
}
