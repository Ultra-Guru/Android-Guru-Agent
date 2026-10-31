package com.apex.agent.ui.screen.code.stream

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.core.code.stream.UnifiedDiffParser

/**
 * # Code Diff View — hunk 级文件 Diff 渲染（规格书：Diff 按 hunk 级拼装）
 *
 * - **hunk 级 + 懒渲染**：每个 hunk 一张卡片（头行 + 行列表），
 *   LazyColumn 逐块组合——千行 diff 不再一次性构建上万 Text 节点
 *   （急切组合在低端机上掉帧/ANR）；
 * - **骨架态**：流式期间 diff 原文不完整（半截 hunk），照样渲染已到的行；
 *   完全无 hunk 时显示占位骨架（呼吸脉冲）等流式补齐；
 * - **行号**：REMOVE 挂旧行号、ADD 挂新行号、CONTEXT 双侧（git 格式真实
 *   行号；mini 折叠格式无行号时列占位「·」）；
 * - 配色：ADD 绿底 / REMOVE 红底（深浅主题各自可读的柔和 alpha）。
 *
 * @param maxHeight 懒列高度封顶（嵌入详情弹层时与外层滚动解耦）
 */
@Composable
internal fun CodeDiffView(
    diffText: String?,
    modifier: Modifier = Modifier,
    maxHeight: androidx.compose.ui.unit.Dp = 480.dp
) {
    val parsed = remember(diffText) { UnifiedDiffParser.parse(diffText) }

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
    ) {
        // ── 文件头行：路径 + 总统计 ──
        item(key = "diff-file-header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(
                    text = parsed.newPath ?: parsed.oldPath ?: stringResource(R.string.code_stream_diff_unnamed),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(
                        R.string.code_stream_diff_stat_fmt,
                        parsed.totalAdded,
                        parsed.totalRemoved
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (parsed.hunks.isEmpty()) {
            item(key = "diff-skeleton") { DiffSkeleton() }
        } else {
            items(
                items = parsed.hunks,
                key = { "${it.oldStart}-${it.newStart}-${it.lines.size}-${it.header}" }
            ) { hunk ->
                DiffHunkCard(hunk = hunk)
            }
        }

        if (parsed.truncated) {
            item(key = "diff-truncated") {
                Text(
                    text = stringResource(R.string.code_stream_diff_truncated),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        }
    }
}

/** 单个 hunk 卡片：头行（@@ 行号范围 @@）+ 行级着色渲染。 */
@Composable
private fun DiffHunkCard(hunk: UnifiedDiffParser.DiffHunk) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
    ) {
        Column {
            // hunk 头：@@ -10,4 +10,5 @@
            Text(
                text = hunk.header,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
            hunk.lines.forEach { line ->
                DiffLineRow(line)
            }
        }
    }
}

@Composable
private fun DiffLineRow(line: UnifiedDiffParser.DiffLine) {
    val (bg, sign, noColor) = when (line.kind) {
        UnifiedDiffParser.LineKind.ADD ->
            Triple(Color(0xFF059669).copy(alpha = 0.10f), "+", Color(0xFF059669))
        UnifiedDiffParser.LineKind.REMOVE ->
            Triple(MaterialTheme.colorScheme.error.copy(alpha = 0.10f), "-", MaterialTheme.colorScheme.error)
        UnifiedDiffParser.LineKind.CONTEXT ->
            Triple(Color.Transparent, " ", MaterialTheme.colorScheme.outline)
    }
    Row(modifier = Modifier
        .fillMaxWidth()
        .background(bg)
        .padding(horizontal = 8.dp, vertical = 0.dp)
    ) {
        Text(
            text = (line.oldLineNo?.toString() ?: "·").padStart(4),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.width(38.dp)
        )
        Text(
            text = (line.newLineNo?.toString() ?: "·").padStart(4),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.width(38.dp)
        )
        Text(
            text = sign,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = noColor
        )
        Text(
            text = line.text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 2.dp)
        )
    }
}

/** 骨架态：diff 流式中/不可解析时的占位（真·呼吸脉冲，0.3↔0.6 往返）。 */
@Composable
private fun DiffSkeleton() {
    val transition = rememberInfiniteTransition(label = "diff-skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "diff-skeleton-alpha"
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        repeat(3) { i ->
            Box(
                modifier = Modifier
                    .fillMaxWidth(fractionOf(i))
                    .height(12.dp)
                    .padding(vertical = 3.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = alpha),
                        RoundedCornerShape(4.dp)
                    )
            )
        }
    }
}

private fun fractionOf(index: Int): Float = when (index) {
    0 -> 0.9f
    1 -> 0.7f
    else -> 0.8f
}
