package com.apex.agent.ui.screen.code.stream

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.core.code.stream.StreamEntry
import com.apex.agent.core.code.stream.StreamToolCall
import com.apex.agent.ui.component.MarkdownText

/**
 * # Rikka Cards — Rikka 风格的时间轴卡片族
 *
 * 胶囊时间轴（CodeStreamCards）的 Rikka 皮肤版：工具胶囊改走聚合块
 * （RikkaToolBlock，时间轴分段层负责聚段），其余成员按聊天 App 语言
 * 重绘——用户右对齐实色气泡 / 结论 Markdown 气泡 / 思考过程折叠卡 /
 * 验证轮次卡 / 居中状态 pill / 错误与停止卡 / 文件 chips / 运行统计卡。
 * Git 操作可视化卡直接复用（GitOpVisualCard 与皮肤无关）。
 */
@Composable
internal fun RikkaStreamCard(entry: StreamEntry, onToolClick: (StreamToolCall) -> Unit) {
    when (entry) {
        is StreamEntry.UserEntry -> RikkaUserBubble(entry.text)
        is StreamEntry.AssistantEntry -> RikkaAssistantBubble(entry.text, entry.isStreaming)
        is StreamEntry.ThinkingEntry -> RikkaThinkingCard(entry.text, entry.isStreaming)
        // 时间轴分段层已把连续胶囊收进 RikkaToolBlock，此分支只在单胶囊
        // 直渲染（防御）时触达——单调用块本身就是一行工具卡。
        is StreamEntry.ToolCapsuleEntry -> RikkaToolBlock(listOf(entry.call), onToolClick)
        is StreamEntry.VerifyCycleEntry -> RikkaVerifyCard(entry, onToolClick)
        is StreamEntry.StatusEntry -> RikkaPillLine(text = entry.text, emphasized = false)
        is StreamEntry.SystemEntry -> RikkaPillLine(text = entry.text, emphasized = true)
        is StreamEntry.ErrorEntry -> RikkaErrorCard(entry)
        is StreamEntry.StopEntry -> RikkaStopCard(entry.reason)
        is StreamEntry.FileChipsEntry -> RikkaFileChips(entry.files)
        is StreamEntry.GitOpEntry -> GitOpVisualCard(entry)
        is StreamEntry.RunStatsEntry -> RikkaRunStatsCard(entry)
    }
}

// ═══ 用户气泡（右对齐实色——「谁说的」一眼可辨的聊天形态）═══

@Composable
private fun RikkaUserBubble(text: String) {
    Row(
        horizontalArrangement = Arrangement.End,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }
    }
}

// ═══ 结论气泡（Markdown，流式容忍；左置表面容器实底）═══

@Composable
private fun RikkaAssistantBubble(text: String, isStreaming: Boolean) {
    Surface(
        shape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            MarkdownText(markdown = text)
            if (isStreaming) {
                Text(
                    text = "▍",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

// ═══ 思考过程折叠卡（流式展开跟随全文 / 收束自动折叠）═══

@Composable
private fun RikkaThinkingCard(text: String, isStreaming: Boolean) {
    // key 语义与胶囊思考卡同款：不能拿派生值当 key（空卡点开后首 token
    // 到达会重置展开态）；手动干预优先于自动展开/折叠。
    var expanded by remember { mutableStateOf(isStreaming) }
    var userToggled by remember { mutableStateOf(false) }
    LaunchedEffect(isStreaming) {
        if (!userToggled) expanded = isStreaming
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .clickable {
                    userToggled = true
                    expanded = !expanded
                }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Psychology,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (isStreaming) stringResource(R.string.code_stream_thinking_running)
                    else stringResource(R.string.rikka_thinking_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(14.dp)
                )
            }
            AnimatedVisibility(visible = expanded) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            if (!expanded && text.isNotBlank()) {
                Text(
                    text = text.take(80),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

// ═══ 验证轮次卡（可折叠；展开态复用工具行）═══

@Composable
private fun RikkaVerifyCard(entry: StreamEntry.VerifyCycleEntry, onToolClick: (StreamToolCall) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val passed = entry.cycle.passed
    val tint = when (passed) {
        true -> MaterialTheme.colorScheme.primary
        false -> MaterialTheme.colorScheme.error
        null -> MaterialTheme.colorScheme.outline
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = tint.copy(alpha = 0.06f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.code_stream_verify_round_fmt, entry.cycle.round),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = when (passed) {
                        true -> stringResource(R.string.code_stream_verify_passed)
                        false -> stringResource(R.string.code_stream_verify_failed)
                        null -> stringResource(R.string.code_stream_verify_unknown)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = tint
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(14.dp)
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.padding(top = 6.dp)
                ) {
                    entry.calls.forEach { call ->
                        RikkaToolRow(call = call, onClick = onToolClick)
                    }
                    if (entry.calls.isEmpty()) {
                        Text(
                            text = stringResource(R.string.code_stream_verify_details_gone),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        }
    }
}

// ═══ 运行统计收尾卡（头行 + stat chips；技能 / MCP 非零才显）═══

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RikkaRunStatsCard(entry: StreamEntry.RunStatsEntry) {
    val failed = entry.failedCalls > 0
    val accent = if (failed) MaterialTheme.colorScheme.error
    else MaterialTheme.colorScheme.primary
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (failed) Icons.Default.BugReport else Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.code_stream_run_stats_title),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = formatCapsuleDuration(entry.durationMs),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(top = 6.dp)
            ) {
                RikkaStatChip(
                    icon = Icons.Default.Loop,
                    label = stringResource(R.string.code_stream_run_iterations_fmt, entry.iterations)
                )
                RikkaStatChip(
                    icon = Icons.Default.Build,
                    label = stringResource(R.string.code_stream_run_tools_fmt, entry.toolCalls)
                )
                if (entry.skillCalls > 0) {
                    RikkaStatChip(
                        icon = Icons.Default.AutoAwesome,
                        label = stringResource(R.string.code_stream_run_skills_fmt, entry.skillCalls)
                    )
                }
                if (entry.mcpCalls > 0) {
                    RikkaStatChip(
                        icon = Icons.Default.Extension,
                        label = stringResource(R.string.code_stream_run_mcp_fmt, entry.mcpCalls)
                    )
                }
                if (entry.filesTouched > 0) {
                    RikkaStatChip(
                        icon = Icons.AutoMirrored.Filled.Notes,
                        label = stringResource(R.string.code_stream_run_files_fmt, entry.filesTouched)
                    )
                }
                if (failed) {
                    RikkaStatChip(
                        icon = Icons.Default.BugReport,
                        label = stringResource(R.string.code_stream_run_failed_fmt, entry.failedCalls),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

/** 单枚统计 chip（非交互展示；与胶囊时间轴 StatChip 同语言）。 */
@Composable
private fun RikkaStatChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: androidx.compose.ui.graphics.Color? = null
) {
    val color = tint ?: MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = color
            )
        }
    }
}

// ═══ 结构化错误卡 ═══

@Composable
private fun RikkaErrorCard(entry: StreamEntry.ErrorEntry) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.BugReport,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (entry.recoverable) stringResource(R.string.code_stream_error_recoverable)
                    else stringResource(R.string.code_stream_error_fatal),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Text(
                text = entry.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(top = 4.dp)
            )
            entry.hint?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

// ═══ 停止卡（居中 pill）═══

@Composable
private fun RikkaStopCard(reason: String) {
    RikkaPillLine(text = reason, emphasized = false, leading = Icons.Default.StopCircle)
}

// ═══ 受影响文件 chips ═══

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RikkaFileChips(files: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Notes,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(13.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.code_stream_files_touched, files.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(top = 4.dp)
        ) {
            files.take(FILE_CHIPS_MAX).forEach { file ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Text(
                        text = file.substringAfterLast('/'),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
            if (files.size > FILE_CHIPS_MAX) {
                Text(
                    text = stringResource(R.string.code_stream_files_more_fmt, files.size - FILE_CHIPS_MAX),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp)
                )
            }
        }
    }
}

// ═══ 状态 / 系统 pill（居中小胶囊——聊天 App 的时间戳/事件语言）═══

@Composable
private fun RikkaPillLine(
    text: String,
    emphasized: Boolean,
    leading: androidx.compose.ui.graphics.vector.ImageVector? = null
) {
    Row(
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                leading?.let {
                    Icon(
                        imageVector = it,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(12.dp)
                    )
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (emphasized) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private const val FILE_CHIPS_MAX = 6
