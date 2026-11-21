package com.apex.agent.ui.screen.code.stream

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.core.code.stream.LlmRetryInfo
import com.apex.agent.core.code.stream.ToolUsageStats
import com.apex.agent.ui.glass.PartSurface
import com.apex.agent.ui.theme.CodingPart
import androidx.compose.foundation.layout.PaddingValues
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * # CompleteModeGeneratingCard — 整段模式的生成中加载卡
 *
 * [CodeOutputMode] COMPLETE 档在 run 进行中替代时间轴过程条目的唯一可见
 * 元素（规格书：「完整模式：生成完成后一次性展示，生成中显示加载态」）：
 *
 * - 头行：三枚呼吸圆点 + 标题（重试等待中切换为「重试中 · 第 N/T 次」）
 *   + 已用时（每秒跳动，复用胶囊耗时格式）；
 * - 进度行：本轮工具使用聚合（v1.5.1 溯源三分类）——总数（>0 时显示）
 *   + 内置 / 技能 / MCP 分类计数（非零才显，图标区分）+ 失败数（>0
 *   错误色强调）——静默不等于黑箱，长任务的推进感由真实计数提供，
 *   「用了哪些能力」一眼可辨；
 * - 骨架条：两道呼吸占位条，直观传达「内容将在完成后一次性出现」。
 *
 * 卡片高度恒定（计数/用时只改文本不改结构），不触发时间轴锚定跟随。
 */
@Composable
internal fun CompleteModeGeneratingCard(
    toolUsage: ToolUsageStats,
    llmRetry: LlmRetryInfo? = null
) {
    // 呼吸动画（三圆点相位错开 + 骨架条透明度脉冲），同一 transition 驱动。
    // perf-20261114：动画值以 State 传递、graphicsLayer 块内读取（#262 同款
    // 纪律）—— 旧实现组合期取值，整卡（PartSurface→PaleLiquid 玻璃栈）
    // 随呼吸 60fps 重组；现仅层属性失效，零重组零重测。
    val pulse = rememberInfiniteTransition(label = "complete-generating")
    val dot1 = pulse.animateFloat(
        initialValue = 0.25f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(DOT_PULSE_MS), RepeatMode.Reverse),
        label = "dot1"
    )
    val dot2 = pulse.animateFloat(
        initialValue = 0.25f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(DOT_PULSE_MS), RepeatMode.Reverse,
            initialStartOffset = StartOffset(DOT_STAGGER_MS)
        ),
        label = "dot2"
    )
    val dot3 = pulse.animateFloat(
        initialValue = 0.25f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(DOT_PULSE_MS), RepeatMode.Reverse,
            initialStartOffset = StartOffset(DOT_STAGGER_MS * 2)
        ),
        label = "dot3"
    )
    val skeletonAlpha = pulse.animateFloat(
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

    // v1.7 部件皮肤（PartSkin spec FR-6）：生成中卡默认 PALE_LIQUID 且
    // active=true（生成期间光效流动）；重试红只上标题文字（FR-11 删 accent）。
    PartSurface(
        part = CodingPart.STREAM_GENERATING,
        active = true,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
    ) {
            // ── 头行：呼吸圆点 + 标题（重试中切换）+ 已用时 ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                GeneratingDot(alphaState = dot1)
                Spacer(Modifier.width(3.dp))
                GeneratingDot(alphaState = dot2)
                Spacer(Modifier.width(3.dp))
                GeneratingDot(alphaState = dot3)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (llmRetry != null) {
                        stringResource(
                            R.string.code_stream_retrying_fmt,
                            llmRetry.attempt,
                            llmRetry.totalRetries
                        )
                    } else {
                        stringResource(R.string.code_stream_generating_title)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (llmRetry != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface
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
            // ── 进度行：工具使用聚合（推进感 + 溯源分类）+ 失败计数 ──
            if (toolUsage.total > 0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(top = 4.dp, start = 23.dp)
                ) {
                    Text(
                        text = stringResource(R.string.code_stream_generating_tools_fmt, toolUsage.total),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (toolUsage.skillCount > 0) {
                        OriginCounter(
                            icon = Icons.Default.AutoAwesome,
                            label = stringResource(R.string.code_stream_aggregate_skill_fmt, toolUsage.skillCount)
                        )
                    }
                    if (toolUsage.mcpCount > 0) {
                        OriginCounter(
                            icon = Icons.Default.Extension,
                            label = stringResource(R.string.code_stream_aggregate_mcp_fmt, toolUsage.mcpCount)
                        )
                    }
                    if (toolUsage.failedCount > 0) {
                        Text(
                            text = stringResource(R.string.code_stream_generating_failed_fmt, toolUsage.failedCount),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            // ── 骨架条：两道呼吸占位（完成后被真实内容整体替换）──
            SkeletonBar(
                fraction = 0.92f,
                alphaState = skeletonAlpha,
                modifier = Modifier.padding(top = 8.dp)
            )
            SkeletonBar(
                fraction = 0.58f,
                alphaState = skeletonAlpha,
                modifier = Modifier.padding(top = 5.dp)
            )
    }
}

/** 单枚呼吸圆点（primary 色；alpha 经 graphicsLayer 块读 State —— 零重组）。 */
@Composable
private fun GeneratingDot(alphaState: State<Float>) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .size(5.dp)
            .graphicsLayer { alpha = alphaState.value }
    ) {}
}

/** 溯源分类计数（图标 + 文案，onSurfaceVariant 静态色——推进感由计数变化提供）。 */
@Composable
private fun OriginCounter(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(11.dp)
        )
        Spacer(Modifier.width(3.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 骨架占位条：宽度按 [fraction] 裁剪 + 呼吸透明度（graphicsLayer 读 State）。 */
@Composable
private fun SkeletonBar(fraction: Float, alphaState: State<Float>, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth(fraction)
            .height(8.dp)
            .graphicsLayer { alpha = alphaState.value * SKELETON_MAX_ALPHA }
    ) {}
}

private const val DOT_PULSE_MS = 600
private const val DOT_STAGGER_MS = 180
private const val SKELETON_PULSE_MS = 900
private const val SKELETON_MAX_ALPHA = 0.28f

/** 已用时跳秒间隔。 */
private const val ELAPSED_TICK_MS = 1000L
