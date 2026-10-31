package com.apex.agent.ui.screen.code.stream

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.ui.glass.GlassCard
import com.apex.agent.ui.glass.GlassStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * # CompleteModeGeneratingCard — 整段模式的生成中加载卡
 *
 * [CodeOutputMode.COMPLETE] 档在 run 进行中替代时间轴过程条目的唯一可见
 * 元素（规格书：「完整模式：生成完成后一次性展示，生成中显示加载态」）：
 *
 * - 头行：三枚呼吸圆点 + 标题 + 已用时（每秒跳动，复用胶囊耗时格式）；
 * - 进度行：本轮已调用工具数（>0 时显示）+ 失败数（>0 时错误色强调）
 *   ——静默不等于黑箱，长任务的推进感由真实计数提供；
 * - 骨架条：两道呼吸占位条，直观传达「内容将在完成后一次性出现」。
 *
 * 卡片高度恒定（计数/用时只改文本不改结构），不触发时间轴锚定跟随。
 */
@Composable
internal fun CompleteModeGeneratingCard(
    toolCalls: Int,
    failedCalls: Int
) {
    // 呼吸动画（三圆点相位错开 + 骨架条透明度脉冲），同一 transition 驱动
    val pulse = rememberInfiniteTransition(label = "complete-generating")
    val dot1 by pulse.animateFloat(
        initialValue = 0.25f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(DOT_PULSE_MS), RepeatMode.Reverse),
        label = "dot1"
    )
    val dot2 by pulse.animateFloat(
        initialValue = 0.25f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(DOT_PULSE_MS), RepeatMode.Reverse,
            initialStartOffset = StartOffset(DOT_STAGGER_MS)
        ),
        label = "dot2"
    )
    val dot3 by pulse.animateFloat(
        initialValue = 0.25f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(DOT_PULSE_MS), RepeatMode.Reverse,
            initialStartOffset = StartOffset(DOT_STAGGER_MS * 2)
        ),
        label = "dot3"
    )
    val skeletonAlpha by pulse.animateFloat(
        initialValue = 0.35f, targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(SKELETON_PULSE_MS), RepeatMode.Reverse),
        label = "skeleton"
    )

    // 已用时：卡片组合时刻起算的本地时钟（≈ run 起点；每秒一跳）
    val startAt = remember { System.currentTimeMillis() }
    var elapsedMs by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (isActive) {
            elapsedMs = System.currentTimeMillis() - startAt
            delay(ELAPSED_TICK_MS)
        }
    }

    GlassCard(
        style = GlassStyle.Card,
        shape = RoundedCornerShape(12.dp),
        accent = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
            // ── 头行：呼吸圆点 + 标题 + 已用时 ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                GeneratingDot(alpha = dot1)
                Spacer(Modifier.width(3.dp))
                GeneratingDot(alpha = dot2)
                Spacer(Modifier.width(3.dp))
                GeneratingDot(alpha = dot3)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.code_stream_generating_title),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.weight(1f))
                if (elapsedMs > 0) {
                    Text(
                        text = formatCapsuleDuration(elapsedMs),
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // ── 进度行：工具计数（推进感）+ 失败计数（错误色）──
            if (toolCalls > 0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(top = 4.dp, start = 23.dp)
                ) {
                    Text(
                        text = stringResource(R.string.code_stream_generating_tools_fmt, toolCalls),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (failedCalls > 0) {
                        Text(
                            text = stringResource(R.string.code_stream_generating_failed_fmt, failedCalls),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            // ── 骨架条：两道呼吸占位（完成后被真实内容整体替换）──
            SkeletonBar(
                fraction = 0.92f,
                alpha = skeletonAlpha,
                modifier = Modifier.padding(top = 8.dp)
            )
            SkeletonBar(
                fraction = 0.58f,
                alpha = skeletonAlpha,
                modifier = Modifier.padding(top = 5.dp)
            )
        }
    }
}

/** 单枚呼吸圆点（primary 色，alpha 由外层动画驱动）。 */
@Composable
private fun GeneratingDot(alpha: Float) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .size(5.dp)
            .alpha(alpha)
    ) {}
}

/** 骨架占位条：宽度按 [fraction] 裁剪 + 呼吸透明度。 */
@Composable
private fun SkeletonBar(fraction: Float, alpha: Float, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth(fraction)
            .height(8.dp)
            .alpha(alpha * SKELETON_MAX_ALPHA)
    ) {}
}

private const val DOT_PULSE_MS = 600
private const val DOT_STAGGER_MS = 180
private const val SKELETON_PULSE_MS = 900
private const val SKELETON_MAX_ALPHA = 0.28f

/** 已用时跳秒间隔。 */
private const val ELAPSED_TICK_MS = 1000L
