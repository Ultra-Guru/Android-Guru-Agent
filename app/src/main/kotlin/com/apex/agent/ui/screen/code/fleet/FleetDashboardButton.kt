package com.apex.agent.ui.screen.code.fleet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.apex.agent.R

/**
 * # Fleet Dashboard Button — 工具栏行右端的仪表盘小按钮（Spec §2.1）
 *
 * 落位：`CodeInputBar` 工具栏 Row 末尾（展开 ▲ 之后），与现有 chip 等高。
 * 形态对齐 [com.apex.agent.ui.screen.code.ToolbenchExpandButton] 的圆形
 * 激活态按钮：48dp 触达面 + 圆形底 + 选中/有活动泛光；**不持有数据**——
 * 只按 [state] 派生徽标并回调 [onToggle] 控制浮层显隐。
 *
 * 角标徽标（复用「激活态徽标」模式）：当前会话**运行中**子 Agent 数 +
 * 运行中（含等待）命令数，0 时不渲染；外层不裁剪，徽标不会被圆底吃掉。
 */
@Composable
fun FleetDashboardButton(
    state: FleetDashboardState,
    dashboardOpen: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val runningSubAgents = state.subAgents.count { it.status == FleetSubAgentStatus.RUNNING }
    val runningCommands = state.commands.count {
        it.status == FleetCommandStatus.RUNNING || it.status == FleetCommandStatus.WAITING
    }
    val badge = runningSubAgents + runningCommands
    val active = dashboardOpen || badge > 0
    val cd = stringResource(R.string.code_fleet_button_cd)
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                // 圆形反馈：clip 先于 background/clickable —— ripple 与圆底
                // 同被裁成圆（ToolbenchExpandButton 同款修复口径）。
                .clip(CircleShape)
                .background(
                    color = if (active) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    shape = CircleShape
                )
                .semantics { contentDescription = cd }
                .clickable { onToggle() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Dashboard,
                contentDescription = null,
                tint = if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
        if (badge > 0) {
            Text(
                text = badge.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 2.dp, end = 2.dp)
                    .background(color = MaterialTheme.colorScheme.primary, shape = CircleShape)
                    .padding(horizontal = 5.dp, vertical = 1.dp)
            )
        }
    }
}
