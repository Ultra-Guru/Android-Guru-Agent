package com.apex.agent.ui.screen.code

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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.ui.glass.GlassCard
import com.apex.agent.ui.glass.GlassStyle
import com.kyant.backdrop.Backdrop

/**
 * # Queued Task Card — 输入框上方的任务排队卡
 *
 * 运行中发出的第二条任务不再被静默丢弃：文本入队后以排队卡形态悬浮在
 * 输入栏上方，两种状态（[QueuedTaskState]）：
 *
 * - **WAITING_AUTO（等待发送）**：当前任务运行中——完成后自动发送；
 *   卡片展示任务文本预览 + 取消按钮；
 * - **NEEDS_CONFIRM（等待排队）**：上一任务失败/中止——不自动接力，
 *   亮出「开始排队」按钮，用户确认后才继续执行（取消按钮同在）。
 *
 * 玻璃语言与悬浮栈其余成员一致（GlassCard Floating + 状态 accent：
 * 等待=primary，待确认=error）。
 */
@Composable
internal fun QueuedTaskCard(
    task: QueuedTask,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    backdrop: Backdrop?
) {
    val needsConfirm = task.state == QueuedTaskState.NEEDS_CONFIRM
    GlassCard(
        backdrop = backdrop,
        style = GlassStyle.Floating,
        shape = RoundedCornerShape(12.dp),
        accent = if (needsConfirm) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            // ── 状态标题行：图标 + 标题 + 取消叉号 ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = if (needsConfirm) Icons.Default.WarningAmber else Icons.Default.HourglassTop,
                    contentDescription = null,
                    tint = if (needsConfirm) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = stringResource(
                        if (needsConfirm) R.string.code_queue_needs_confirm_title
                        else R.string.code_queue_waiting_title
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (needsConfirm) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onCancel, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.code_queue_cancel),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
            // ── 任务文本预览（等宽，两行封顶）──
            if (task.text.isNotBlank()) {
                Text(
                    text = task.text,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 2.dp, end = 4.dp)
                )
            }
            // ── 技能 chip 快照提示（入队时挂了胶囊的展示数量）──
            if (task.commands.isNotEmpty()) {
                Text(
                    text = "◈ ${task.commands.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 2.dp, top = 2.dp)
                )
            }
            // ── 状态说明 +（待确认态的）动作按钮 ──
            Text(
                text = stringResource(
                    if (needsConfirm) R.string.code_queue_needs_confirm_hint
                    else R.string.code_queue_waiting_hint
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 2.dp, top = 2.dp)
            )
            if (needsConfirm) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 2.dp)
                ) {
                    TextButton(onClick = onStart) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.code_queue_start))
                    }
                }
            }
        }
    }
}
