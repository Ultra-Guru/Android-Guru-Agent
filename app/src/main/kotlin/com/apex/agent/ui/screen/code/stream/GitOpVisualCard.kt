package com.apex.agent.ui.screen.code.stream

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.rounded.CallSplit
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Commit
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.core.code.stream.GitOpSummary
import com.apex.agent.core.code.stream.StreamEntry

/**
 * # Git Op Visual Card — 流水式输出的 Git 操作可视化卡（v1）
 *
 * 「生活中的 Git 不能是假的」：agent 调用 code_git_* 工具成功后，在胶囊
 * 时间轴上紧跟一张结构化卡——分支徽章 / ahead-behind / 暂存三分组计数 /
 * 提交哈希 / diff 增删行——而不是只有一行摘要文本。
 *
 * 布局（按 [GitOpSummary.GitOpType] 分派）：
 * - STATUS：分支行（图钉图标 + 分支名 + ↑↓ 徽标）+ 三分组计数 chip 行
 *   + 冲突警示（>0 时 error 色）+ 干净态文案；
 * - COMMIT：哈希等宽 chip + 主题 + 剩余未提交计数；
 * - DIFF：N 个文件 + +增/−删 着色统计；
 * - BRANCH：动作结果行；
 * - LOG：最近 N 条提交 + 最新主题。
 *
 * 全部只读展示（无点击行为）；配色走 MaterialTheme 动态色，语义色
 * （增绿删红）沿用时间轴 Diff 视图口径。
 */
@Composable
internal fun GitOpVisualCard(entry: StreamEntry.GitOpEntry) {
    val s = entry.summary
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            when (s.op) {
                GitOpSummary.GitOpType.STATUS -> StatusBody(s)
                GitOpSummary.GitOpType.COMMIT -> CommitBody(s)
                GitOpSummary.GitOpType.DIFF -> DiffBody(s)
                GitOpSummary.GitOpType.BRANCH -> BranchBody(s)
                GitOpSummary.GitOpType.LOG -> LogBody(s)
            }
            s.note?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ═══ 标题行（图标 + 类型标签 + 主文案）═══

@Composable
private fun CardHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    headline: String,
    headlineMono: Boolean = false
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(15.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = headline,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = if (headlineMono) FontFamily.Monospace else null,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
    }
}

// ═══ STATUS：分支 + 分组计数 ═══

@Composable
private fun StatusBody(s: GitOpSummary) {
    CardHeader(
        icon = Icons.Rounded.CallSplit,
        label = stringResource(R.string.code_git_visual_status),
        headline = s.headline,
        headlineMono = true
    )
    if (s.clean && s.staged == 0 && s.unstaged == 0 && s.untracked == 0 && s.conflicts == 0) {
        Text(
            text = stringResource(R.string.code_git_visual_clean),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        return
    }
    if (s.ahead > 0 || s.behind > 0) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (s.ahead > 0) {
                StatChip(
                    text = stringResource(R.string.code_git_visual_ahead_fmt, s.ahead),
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            if (s.behind > 0) {
                StatChip(
                    text = stringResource(R.string.code_git_visual_behind_fmt, s.behind),
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StatChip(
            text = stringResource(R.string.code_git_visual_staged_fmt, s.staged),
            color = MaterialTheme.colorScheme.primary
        )
        StatChip(
            text = stringResource(R.string.code_git_visual_unstaged_fmt, s.unstaged),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        StatChip(
            text = stringResource(R.string.code_git_visual_untracked_fmt, s.untracked),
            color = MaterialTheme.colorScheme.outline
        )
    }
    if (s.conflicts > 0) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = stringResource(R.string.code_git_visual_conflicts_fmt, s.conflicts),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

// ═══ COMMIT：哈希 + 主题 + 剩余 ═══

@Composable
private fun CommitBody(s: GitOpSummary) {
    CardHeader(
        icon = Icons.Rounded.Commit,
        label = stringResource(R.string.code_git_visual_commit),
        headline = s.headline
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        s.commitHash?.let { hash ->
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            ) {
                Text(
                    text = hash,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
        s.remainingChanges?.let { remaining ->
            Text(
                text = if (remaining == 0) {
                    stringResource(R.string.code_git_visual_remaining_zero)
                } else {
                    stringResource(R.string.code_git_visual_remaining_fmt, remaining)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ═══ DIFF：文件数 + 增删行 ═══

@Composable
private fun DiffBody(s: GitOpSummary) {
    CardHeader(
        icon = Icons.AutoMirrored.Filled.Notes,
        label = stringResource(R.string.code_git_visual_diff),
        headline = s.headline
    )
    val additions = s.additions ?: 0
    val deletions = s.deletions ?: 0
    if (additions > 0 || deletions > 0) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.code_git_visual_additions_fmt, additions),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = stringResource(R.string.code_git_visual_deletions_fmt, deletions),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

// ═══ BRANCH：动作结果 ═══

@Composable
private fun BranchBody(s: GitOpSummary) {
    CardHeader(
        icon = Icons.Rounded.CallSplit,
        label = stringResource(R.string.code_git_visual_branch),
        headline = s.headline,
        headlineMono = s.branch != null
    )
    s.branch?.takeIf { it != s.headline }?.let { name ->
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ═══ LOG：提交数 + 最新主题 ═══

@Composable
private fun LogBody(s: GitOpSummary) {
    CardHeader(
        icon = Icons.Rounded.CheckCircle,
        label = stringResource(R.string.code_git_visual_log),
        headline = s.headline
    )
    s.commitCount?.let { count ->
        Text(
            text = stringResource(R.string.code_git_visual_commits_fmt, count),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ═══ 计数 chip ═══

@Composable
private fun StatChip(text: String, color: androidx.compose.ui.graphics.Color) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.10f)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
