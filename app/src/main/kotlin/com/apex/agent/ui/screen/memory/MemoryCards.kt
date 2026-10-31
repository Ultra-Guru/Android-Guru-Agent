package com.apex.agent.ui.screen.memory

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.ui.component.ReliabilityTier
import com.apex.agent.ui.component.reliabilityTierOf
import com.apex.memkit.core.model.EpisodeStatus
import com.apex.memkit.core.model.EpisodeSummary
import com.apex.memkit.core.model.MacroSkill
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 记忆页列表视图的卡片族（记忆页 v3 从 MemoryScreen 拆出 —— 单列列表
 * 重构后本文件承载全部条目卡的可视件）：
 *
 *  - [StatCard]：概览统计卡（v3 新增 onClick 联动 —— 点击滚动到对应
 *    分区或切图谱视图）；
 *  - [EpisodeCard]：经历卡（v3 新增终态徽章 / 用时 / 应用包 / 手动
 *    整理标记）；
 *  - [MacroCard]：宏技能卡（v3 新增点击展开 —— 目标 / 动作序列 /
 *    溯源 / 沉淀时间）；
 *  - [SearchHitCard]：搜索命中卡（v3 新增命中词加粗高亮）；
 *  - [EmptyHint]：空态提示（v3 新增可选图标）。
 *
 * 纯展示组件：数据全部入参，回调节度（滚动 / 删除 / 展开）由
 * MemoryScreen 持有。
 */

// ═══ 概览统计卡 ═══════════════════════════════════════════════════

/** 统计卡（v3：onClick 非空即整卡可点 —— 分区联动 / 图谱切换）。 */
@Composable
internal fun StatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
    Surface(
        modifier = modifier
            .clickable(enabled = onClick != null, onClick = { onClick?.invoke() })
            .drawBehind {
                drawRoundRect(
                    color = borderColor,
                    style = Stroke(1.dp.toPx()),
                    cornerRadius = CornerRadius(12.dp.toPx())
                )
            },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(2.dp))
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ═══ 经历卡 ═══════════════════════════════════════════════════════

/** 终态徽章配色（语义色：成功→primary / 失败→error / 进行中→tertiary / 放弃→outline）。 */
@Composable
private fun episodeStatusColor(status: EpisodeStatus): Color = when (status) {
    EpisodeStatus.SUCCEEDED -> MaterialTheme.colorScheme.primary
    EpisodeStatus.FAILED -> MaterialTheme.colorScheme.error
    EpisodeStatus.RUNNING -> MaterialTheme.colorScheme.tertiary
    EpisodeStatus.ABANDONED -> MaterialTheme.colorScheme.outline
}

/** 经历终态的用户可读标签。 */
@Composable
internal fun episodeStatusLabel(status: EpisodeStatus): String = when (status) {
    EpisodeStatus.RUNNING -> stringResource(R.string.memory_episode_status_running)
    EpisodeStatus.SUCCEEDED -> stringResource(R.string.memory_episode_status_succeeded)
    EpisodeStatus.FAILED -> stringResource(R.string.memory_episode_status_failed)
    EpisodeStatus.ABANDONED -> stringResource(R.string.memory_episode_status_abandoned)
}

/** 经历用时（未完结返回 null；时 / 分 / 秒三档自适应）。 */
@Composable
private fun formatEpisodeDuration(episode: EpisodeSummary): String? {
    val end = episode.finishedAt ?: return null
    val totalSec = ((end - episode.startedAt).coerceAtLeast(0L) / 1000L).toInt()
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return when {
        h > 0 -> stringResource(R.string.memory_duration_hours_fmt, h, m)
        m > 0 -> stringResource(R.string.memory_duration_minutes_fmt, m, s)
        else -> stringResource(R.string.memory_duration_seconds_fmt, s)
    }
}

/**
 * 经历卡（v3 增强）：终态徽章 + 目标 + 元数据行（状态 · 时间 · 动作数
 * + 用时 / 应用包 / 手动整理 / 已蒸馏标记）。
 */
@Composable
internal fun EpisodeCard(
    episode: EpisodeSummary,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EpisodeStatusBadge(episode.status)
                    if (episode.manual) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.memory_episode_manual),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(episode.goal, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                val duration = formatEpisodeDuration(episode)
                val meta = stringResource(
                    R.string.memory_episode_meta_time_actions_fmt,
                    formatTime(episode.startedAt),
                    episode.actionCount,
                )
                Text(
                    if (duration != null) "$meta · $duration" else meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (episode.appPackage != null) {
                    Text(
                        episode.appPackage,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                if (episode.macroIds.isNotEmpty()) {
                    Text(stringResource(R.string.memory_macro_distilled_fmt, episode.macroIds.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.memory_delete),
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f), modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** 终态徽章（配色见 [episodeStatusColor]）。 */
@Composable
private fun EpisodeStatusBadge(status: EpisodeStatus) {
    val color = episodeStatusColor(status)
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.14f)
    ) {
        Text(
            episodeStatusLabel(status),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

// ═══ 宏技能卡（v3 可展开）════════════════════════════════════════

/** 宏步骤类型的用户可读标签。 */
@Composable
private fun macroStepKindLabel(kind: MacroStepKind): String = when (kind) {
    MacroStepKind.TAP -> stringResource(R.string.memory_macro_step_tap)
    MacroStepKind.SWIPE -> stringResource(R.string.memory_macro_step_swipe)
    MacroStepKind.INPUT -> stringResource(R.string.memory_macro_step_input)
    MacroStepKind.BACK -> stringResource(R.string.memory_macro_step_back)
    MacroStepKind.HOME -> stringResource(R.string.memory_macro_step_home)
}

/**
 * 宏技能卡（v3 增强）：折叠形 = 名称 + 统计 + 可靠性档位 + 步数；
 * 展开形追加 —— 目标 / 动作序列（类型标签 + notation 详形）/ 溯源
 * 情景 / 沉淀时间。展开动画走 animateContentSize（宿主传入的
 * [modifier] 上由调用点链入）。
 *
 * 可靠性定性徽标逻辑不变（#365：计数保留、档位定性）。
 */
@Composable
internal fun MacroCard(
    macro: MacroSkill,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
    ) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(macro.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(
                            R.string.memory_macro_stats,
                            macro.appPackage ?: stringResource(R.string.memory_generic),
                            macro.stats.successCount,
                            macro.stats.failureCount
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // 可靠性定性徽标（基于成功/失败计数的档位投影）
                val tier = reliabilityTierOf(macro.stats.successRate(), macro.stats.totalRuns() > 0)
                val tierColor = when (tier) {
                    ReliabilityTier.STABLE -> MaterialTheme.colorScheme.primary
                    ReliabilityTier.FAIR -> MaterialTheme.colorScheme.tertiary
                    ReliabilityTier.UNSTABLE -> MaterialTheme.colorScheme.error
                    ReliabilityTier.UNUSED -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Surface(
                    color = tierColor.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        tier.label(),
                        style = MaterialTheme.typography.labelSmall,
                        color = tierColor,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(
                        if (expanded) R.string.memory_collapse else R.string.memory_expand_hint,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (macro.stats.crystallized) {
                Text(stringResource(R.string.memory_crystallized), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary)
            }

            if (expanded) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.memory_macro_goal_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    macro.goal,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.memory_macro_steps_detail_header),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                visibleMacroSteps(macro).forEachIndexed { index, step ->
                    Text(
                        stringResource(
                            R.string.memory_macro_step_fmt,
                            index + 1,
                            macroStepKindLabel(step.action.stepKind()),
                        ) + " · " + step.action.notation(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                if (macro.steps.size > MACRO_EXPANDED_STEP_LIMIT) {
                    Text(
                        stringResource(
                            R.string.memory_macro_more_steps_fmt,
                            macro.steps.size - MACRO_EXPANDED_STEP_LIMIT
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                Spacer(Modifier.height(8.dp))
                val meta = buildList {
                    add(stringResource(R.string.memory_macro_created_fmt, formatTime(macro.createdAt)))
                    if (macro.sourceEpisodeId != null) {
                        add(stringResource(R.string.memory_macro_source_fmt, macro.sourceEpisodeId))
                    }
                    if (macro.stats.lastExecutedAt > 0) {
                        add(stringResource(R.string.memory_macro_last_used_fmt, formatTime(macro.stats.lastExecutedAt)))
                    }
                }
                Text(
                    meta.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ═══ 搜索命中卡（v3 高亮）════════════════════════════════════════

/** 单条命中卡：类型徽章 + 高亮主文案 + 元数据 + 图谱跳转。 */
@Composable
internal fun SearchHitCard(
    hit: MemorySearchHit,
    query: String,
    onJumpToGraph: (nodeId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SearchTypeBadge(hit.type)
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                QueryHighlightedText(
                    text = hit.title.ifBlank { stringResource(R.string.memory_no_text) },
                    query = query,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                )
                if (hit.subtitle != null) {
                    Text(
                        hit.subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (hit.jumpId != null) {
                Text(
                    stringResource(R.string.memory_knowledge_open_graph),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { onJumpToGraph(hit.jumpId) }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

/** 命中词加粗高亮文本（区间来自 [queryMatchRanges] 纯函数）。 */
@Composable
private fun QueryHighlightedText(
    text: String,
    query: String,
    style: TextStyle,
    color: Color,
    maxLines: Int,
) {
    val annotated = remember(text, query) {
        val ranges = queryMatchRanges(text, query)
        if (ranges.isEmpty()) {
            AnnotatedString(text)
        } else {
            buildAnnotatedString {
                append(text)
                ranges.forEach { range ->
                    addStyle(
                        SpanStyle(fontWeight = FontWeight.Bold),
                        range.first,
                        range.last + 1,
                    )
                }
            }
        }
    }
    Text(annotated, style = style, color = color, maxLines = maxLines,
        overflow = TextOverflow.Ellipsis)
}

/** 命中类型徽章。 */
@Composable
internal fun SearchTypeBadge(type: MemorySearchHitType) {
    val (label, color) = when (type) {
        MemorySearchHitType.FACT ->
            stringResource(R.string.memory_search_type_fact) to MaterialTheme.colorScheme.primary
        MemorySearchHitType.ENTITY ->
            stringResource(R.string.memory_search_type_entity) to MaterialTheme.colorScheme.secondary
        MemorySearchHitType.NODE ->
            stringResource(R.string.memory_search_type_node) to MaterialTheme.colorScheme.tertiary
    }
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.14f)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

// ═══ 公共小件 ═════════════════════════════════════════════════════

/** 空态提示（v3：可选图标 —— 无图标时退化为纯文本，兼容全部旧调用点）。 */
@Composable
fun EmptyHint(text: String, icon: ImageVector? = null) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.size(40.dp)
                )
                Spacer(Modifier.height(8.dp))
            }
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

// ═══ 记忆健康卡（梦境巩固 + 免疫隔离）════════════════════════════

/**
 * cs-mem 卖点的用户侧入口（T76 审计补齐；v3 从 MemoryScreen 迁入卡片族）：
 *  - 「记忆整理」→ MemKit.runMaintenanceNow()（衰减/修剪/晶化/别名发现，
 *    周期任务亦可手动触发）；
 *  - 「免疫隔离区」→ MemoryImmuneSystem 可疑 UI 指纹计数/清除（0 = 未
 *    触发隔离，正常态）。
 */
@Composable
internal fun MemoryHealthCard(
    quarantinedCount: Int,
    dreamRunning: Boolean,
    onDreamNow: () -> Unit,
    onClearQuarantine: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 梦境整理
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Bedtime, contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.memory_dream_title), style = MaterialTheme.typography.labelMedium)
                    }
                    Text(
                        if (dreamRunning) stringResource(R.string.memory_dream_running)
                        else stringResource(R.string.memory_dream_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(onClick = onDreamNow, enabled = !dreamRunning) {
                    if (dreamRunning) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.memory_dream_now))
                    }
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 14.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 免疫隔离区
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.HealthAndSafety, contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (quarantinedCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.memory_quarantine_title), style = MaterialTheme.typography.labelMedium)
                    }
                    Text(
                        if (quarantinedCount > 0) stringResource(R.string.memory_quarantined, quarantinedCount)
                        else stringResource(R.string.memory_quarantine_clear),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (quarantinedCount > 0) {
                    OutlinedButton(onClick = onClearQuarantine) {
                        Text(stringResource(R.string.memory_clear), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

/** 时间格式化（MM-dd HH:mm；非正时间显示占位破折号）。 */
internal fun formatTime(ts: Long): String {
    if (ts <= 0L) return "—"
    return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
}
