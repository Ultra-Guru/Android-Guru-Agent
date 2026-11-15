package com.apex.agent.ui.screen.code

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import com.apex.agent.ui.glass.PartSurface
import com.apex.agent.ui.theme.CodingPart
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
 * v1.7 部件皮肤（PartSkin spec DEF-1 修复）：
 *  - 容器从 `GlassCard(Floating)` 改 `PartSurface(input.queue_card)` ——
 *    新默认 PALE_LIQUID 零折射零色散零外阴影，彻底消灭「框中框 / 双底边
 *    / 20px 色差带」（Floating 档 10/20dp 折射 + 6dp 外阴影 + 白环 +
 *    InnerShadow 正是放大器）；统一 `GlassShapes.card` 半径，单层容器
 *    （FR-12：contentPadding 由容器贡献，业务侧不再套第二层 Column）；
 *  - 状态语义色只上图标与标题文字（FR-11 / DEF-2：淘汰 accent 整卡
 *    染色 —— 等待不再染 primary、待确认不再染 error，底色恒浅白）；
 *  - 动作行改 SpaceBetween（DEF-1 附带：开始排队 / 取消 同行两端，
 *    消除右侧 2/3 留白与标题行/动作行的双取消入口 —— 待确认态的取消
 *    收敛到动作行，等待态保留标题行叉号）。
 */
@Composable
internal fun QueuedTaskCard(
    task: QueuedTask,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    backdrop: Backdrop?
) {
    val needsConfirm = task.state == QueuedTaskState.NEEDS_CONFIRM
    PartSurface(
        part = CodingPart.INPUT_QUEUE_CARD,
        backdrop = backdrop,
        active = !needsConfirm,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        // ── 状态标题行：图标 + 标题（等待态的取消叉号收在行尾）──
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
            if (!needsConfirm) {
                IconButton(onClick = onCancel, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.code_queue_cancel),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp)
                    )
                }
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
        // ── 状态说明 +（待确认态的）动作行 ──
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
            // DEF-1 附带修复：动作行 SpaceBetween —— 「开始排队」与「取消」
            // 同行两端，消除右侧 2/3 留白与双取消入口。
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp)
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
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.code_queue_cancel))
                }
            }
        }
    }
}
