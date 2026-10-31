package com.apex.agent.ui.screen.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.tools.ToolAuditLogger

/**
 * 诊断中心「Agent 能力区」行组件（从 DiagnosticsScreen 按职责缝拆出，
 * 参见 orchestrator/ 拆分模式）：MCP 服务器行 / 技能行 / 工具审计行，
 * 以及它们共享的展示数据类。
 *
 * 设计约束：
 * - 全部为无状态展示组件，数据由 DiagnosticsViewModel 快照注入；
 * - 连接/启用/失败等状态除颜色外均有文字徽标互补（色盲可读）；
 * - 失败行整体可点击（errorContainer 低透明度底色），由调用方弹出详情。
 */

/** MCP 服务器展示行（名称 / 传输形态 / 端点 / 启用 / 连接）。 */
data class McpServerRow(
    val name: String,
    val transport: String,
    val endpoint: String,
    val enabled: Boolean,
    val connected: Boolean
)

/** 技能展示行（id / 名称 / 版本 / 双工位启用位 / 工具数）。 */
data class SkillRow(
    val id: String,
    val name: String,
    val version: String,
    val enabledAgent: Boolean,
    val enabledCoding: Boolean,
    val toolCount: Int
)

/**
 * MCP 服务器行：名称 + 传输形态徽标 + 连接状态点（绿 = 已连接 / 灰 = 未连接）+
 * 端点（url 或命令行，等宽小字）+ 禁用态整行降透明度。
 */
@Composable
internal fun McpServerListRow(row: McpServerRow) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            // 连接状态点：主色/表面变色，与文案互补（色盲可读）
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (row.connected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant
                    )
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(
                    row.name,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (row.enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                Text(
                    buildString {
                        append(row.transport)
                        append("  ·  ")
                        append(if (row.connected) stringResource(R.string.diag_mcp_connected)
                        else stringResource(R.string.diag_mcp_disconnected))
                        if (!row.enabled) append("  ·  ").append(stringResource(R.string.diag_mcp_disabled))
                        append('\n').append(row.endpoint)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * 技能行：名称 + 版本 + 双工位启用徽标（Agent/Coding 各一枚，禁用工位划暗）+ 工具数。
 */
@Composable
internal fun SkillListRow(row: SkillRow) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Extension, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(
                    "${row.name}  v${row.version}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    buildString {
                        append(
                            if (row.enabledAgent) stringResource(R.string.diag_skill_scope_agent) + " ✓"
                            else stringResource(R.string.diag_skill_scope_agent) + " ✕"
                        )
                        append("  ·  ")
                        append(
                            if (row.enabledCoding) stringResource(R.string.diag_skill_scope_coding) + " ✓"
                            else stringResource(R.string.diag_skill_scope_coding) + " ✕"
                        )
                        append("  ·  ")
                        append(stringResource(R.string.diag_skill_tools_unit, row.toolCount))
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * 工具审计行：时间 + 工具名（等宽）+ 决策徽标 + 耗时；失败行错误色强调且可点击
 * 打开详情弹窗（[onOpenDetail]）。
 */
@Composable
internal fun ToolAuditListRow(
    entry: ToolAuditLogger.AuditEntry,
    timeLabel: String,
    isFailure: Boolean = false,
    onOpenDetail: () -> Unit = {}
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isFailure) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
        else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .let { if (isFailure) it.clickable(onClick = onOpenDetail) else it }
    ) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (isFailure) Icons.Outlined.Warning else Icons.Outlined.Terminal,
                contentDescription = stringResource(R.string.diag_view),
                tint = if (isFailure) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(
                    buildString {
                        append(entry.tool)
                        entry.tier?.let { append("  [").append(it).append(']') }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isFailure) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    buildString {
                        append(timeLabel)
                        append("  ·  ").append(entry.decision)
                        entry.durationMs?.let { append("  ·  ").append(it).append("ms") }
                        entry.command?.let { append('\n').append(it) }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
