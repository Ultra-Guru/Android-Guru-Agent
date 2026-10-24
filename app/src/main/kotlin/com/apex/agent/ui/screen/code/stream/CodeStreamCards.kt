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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
import com.apex.agent.core.code.stream.UnifiedDiffParser
import com.apex.agent.ui.component.MarkdownText
import com.apex.agent.ui.glass.GlassCard
import com.apex.agent.ui.glass.GlassStyle
import com.apex.agent.ui.theme.LocalExtendedColors

/**
 * # Code Stream Cards — 时间轴卡片族
 *
 * 胶囊之外的时间轴成员：思考链（独立可折叠）/ 验证轮次卡（可折叠轮次
 * 胶囊）/ 结构化错误卡 / 停止卡 / Git 操作可视化卡 / 受影响文件 chips /
 * 流式结论气泡 / 用户气泡 / 状态与系统行。正文气泡只放结论（Markdown
 * 渲染，容忍不完整片段），思考链与工具细节全部走独立卡片——规格书【0】
 * 的「非聊天」立场。
 */
@Composable
internal fun StreamCard(entry: StreamEntry, onToolClick: (StreamToolCall) -> Unit) {
    when (entry) {
        is StreamEntry.UserEntry -> UserBubble(entry.text)
        is StreamEntry.AssistantEntry -> AssistantBubble(entry.text, entry.isStreaming)
        is StreamEntry.ThinkingEntry -> ThinkingCard(entry.text, entry.isStreaming)
        is StreamEntry.ToolCapsuleEntry -> CodeCapsule(entry.call, onToolClick)
        is StreamEntry.VerifyCycleEntry -> VerifyCycleCard(entry, onToolClick)
        is StreamEntry.StatusEntry -> LightLine(entry.text)
        is StreamEntry.SystemEntry -> SystemLine(entry.text)
        is StreamEntry.ErrorEntry -> ErrorCard(entry)
        is StreamEntry.StopEntry -> StopCard(entry.reason)
        is StreamEntry.FileChipsEntry -> FileChipsCard(entry.files)
        is StreamEntry.GitOpEntry -> GitOpVisualCard(entry)
        is StreamEntry.RunStatsEntry -> RunStatsCard(entry)
    }
}

// ═══ 用户气泡 ═══

@Composable
private fun UserBubble(text: String) {
    Surface(
        shape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
        // 用户/结论气泡区分（用户反馈「两者颜色一样」）：结论气泡是 primary
        // 玻璃薄霜（≈16% 染色），用户侧旧值 primary@10% 与之同色相仅差 6 个
        // 百分点透明度——肉眼不可分。改实色 primary + onPrimary：实色 vs 玻璃、
        // 饱和 vs 淡染，「谁说的」一眼可辨（与 Agent 屏 UserBubble 同口径）。
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

// ═══ 结论气泡（Markdown，流式容忍）═══

@Composable
private fun AssistantBubble(text: String, isStreaming: Boolean) {
    // v5 流式玻璃（用户反馈「coding 屏流式输出无玻璃」）：结论气泡从裸铺
    // 背景改为 GlassCard Frosted 档 —— 与 Agent 屏 StreamingResponseBubble
    // 同一套玻璃语言（薄霜渐变 + 边缘光）；流式光标保留。
    GlassCard(
        style = GlassStyle.Card,
        shape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
        accent = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
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

// ═══ 思考链卡（单独、可折叠）═══

@Composable
private fun ThinkingCard(text: String, isStreaming: Boolean) {
    // 注意 key：不能用 text.isEmpty() 这类派生值——空思考卡点开后首个
    // token 到达（isEmpty 翻转）会重置展开态，吞掉用户的点击
    //
    // 胶囊化展开态（用户需求：「思考时展示全部思考内容，思考完自动折叠
    // 成胶囊」）：流式期间默认展开跟随全文；流式收束（isStreaming 翻转）
    // 自动折叠 —— 用户手动点过则尊重手动状态，不再自动干预。
    var expanded by remember { mutableStateOf(isStreaming) }
    var userToggled by remember { mutableStateOf(false) }
    LaunchedEffect(isStreaming) {
        if (!userToggled) expanded = isStreaming
    }
    // v5 流式玻璃：思考卡 Surface → GlassCard Frosted（accent=secondary
    // 与思考色系一致）；折叠/展开点击收在内容层，涟漪经玻璃层裁剪。
    GlassCard(
        style = GlassStyle.Card,
        shape = RoundedCornerShape(12.dp),
        accent = MaterialTheme.colorScheme.secondary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 10.dp, vertical = 7.dp)
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
                    else stringResource(R.string.code_stream_thinking_done),
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

// ═══ 验证轮次卡（可折叠「轮次胶囊」）═══

@Composable
private fun VerifyCycleCard(entry: StreamEntry.VerifyCycleEntry, onToolClick: (StreamToolCall) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val passed = entry.cycle.passed
    val tint = when (passed) {
        true -> MaterialTheme.colorScheme.primary
        false -> MaterialTheme.colorScheme.error
        null -> MaterialTheme.colorScheme.outline
    }
    // 轮内改动量可视化：编辑胶囊 diff 原文现场解析求和（绿增红删徽标）。
    // 恢复回放的轮次 calls 为空 → null（不显徽标，轮次红绿态仍保留）。
    val delta = remember(entry) {
        val parsed = entry.calls
            .filter { it.isEditFamily }
            .mapNotNull { it.diffText }
            .map { UnifiedDiffParser.parse(it) }
            .filter { it.hunks.isNotEmpty() }
        if (parsed.isEmpty()) null
        else parsed.sumOf { it.totalAdded } to parsed.sumOf { it.totalRemoved }
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
                delta?.let { (added, removed) ->
                    if (added + removed > 0) {
                        Spacer(Modifier.width(6.dp))
                        VerifyDeltaBadge(added = added, removed = removed)
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = stringResource(
                        R.string.code_stream_verify_counts_fmt,
                        entry.cycle.editCallIds.size,
                        entry.cycle.verifyCallIds.size
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(4.dp))
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
                        CodeCapsule(call = call, onClick = onToolClick)
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

// ═══ 运行统计收尾卡（Complete 事件的结构化可视化）═══

/**
 * 一轮运行收尾的统计卡：头行（完成图标 + 总耗时）+ FlowRow 象征 chips
 * （迭代轮数 / 工具调用 / 触碰文件 / 失败数——失败 >0 红色强调）。
 * 取代旧纯文本「完成 · N 轮 · N 次工具 · Ns」状态行——同样的数字，
 * 可扫读的版式（图标 + 数值 + 标签三段式）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RunStatsCard(entry: StreamEntry.RunStatsEntry) {
    // 失败 >0：头行用错误色转述（成功轮次一律 primary，不告警）
    val failed = entry.failedCalls > 0
    val accent = if (failed) MaterialTheme.colorScheme.error
    else MaterialTheme.colorScheme.primary
    GlassCard(
        style = GlassStyle.Card,
        shape = RoundedCornerShape(12.dp),
        accent = accent,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
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
                StatChip(
                    icon = Icons.Default.Loop,
                    label = stringResource(R.string.code_stream_run_iterations_fmt, entry.iterations)
                )
                StatChip(
                    icon = Icons.Default.Build,
                    label = stringResource(R.string.code_stream_run_tools_fmt, entry.toolCalls)
                )
                if (entry.filesTouched > 0) {
                    StatChip(
                        icon = Icons.AutoMirrored.Filled.Notes,
                        label = stringResource(R.string.code_stream_run_files_fmt, entry.filesTouched)
                    )
                }
                if (failed) {
                    StatChip(
                        icon = Icons.Default.BugReport,
                        label = stringResource(R.string.code_stream_run_failed_fmt, entry.failedCalls),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

/** 单枚统计 chip：图标 + 文案（surfaceContainer 薄底，非交互展示）。 */
@Composable
private fun StatChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: androidx.compose.ui.graphics.Color? = null) {
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

/** 轮次头行的增删行徽标（与流式代码窗 / 终态 diff 统计同款 +N −M）。 */
@Composable
private fun VerifyDeltaBadge(added: Int, removed: Int) {
    Text(
        text = stringResource(R.string.code_stream_diff_stat_fmt, added, removed),
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = LocalExtendedColors.current.success
    )
}

// ═══ 结构化错误卡 ═══

@Composable
private fun ErrorCard(entry: StreamEntry.ErrorEntry) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
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

// ═══ 停止卡 ═══

@Composable
private fun StopCard(reason: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
        ) {
            Icon(
                imageVector = Icons.Default.StopCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = reason,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ═══ 受影响文件 chips（FlowRow 换行 + 超 6 个显式提示）═══

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FileChipsCard(files: List<String>) {
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
        // FlowRow：文件名普遍 110-150dp，普通 Row 三个即溢出屏宽且不可达
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(top = 4.dp)
        ) {
            files.take(FILE_CHIPS_MAX).forEach { file ->
                // 非交互展示 chip（原 AssistChip + 空 onClick 是欺骗性可供性：
                // TalkBack 播报可激活但无行为）
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

// ═══ 状态 / 系统行 ═══

@Composable
private fun LightLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
    )
}

@Composable
private fun SystemLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.tertiary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
    )
}

private const val FILE_CHIPS_MAX = 6
