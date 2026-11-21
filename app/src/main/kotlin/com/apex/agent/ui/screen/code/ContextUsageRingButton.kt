package com.apex.agent.ui.screen.code

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import kotlin.math.min

/** 小比例用量的最小可见弧度数（22dp 圆环上 <2% ≈ 一个点，不可辨）。 */
private const val MIN_VISIBLE_SWEEP_DEG = 6f

/**
 * # Context Usage Ring — 会话上下文用量圆环（替代旧的眼睛图标入口）
 *
 * 顶栏常驻的上下文水位指示器：一个小圆环，**白色弧**按上下文占用百分比
 * 填充（used / max），用量涨多少弧就走多少 —— 一眼读出「这个会话离
 * 压缩还有多远」，无需点开任何面板。
 *
 * - 数据源：[CodeUiState.contextUsedTokens] / [CodeUiState.contextMaxTokens]
 *   （UsageUpdated 事件实时推进，run 收尾 / 切工作区时全量校准）；
 * - 视觉：22dp 圆环（48dp 触区，UI-012 红线）；轨道为低对比衬底色，
 *   进度弧为白色（用户指定），外圈细描边保证浅色主题下白弧依然可见；
 * - 交互：点击打开会话上下文详情（CodeContextSheet：具体用量 / token
 *   输入输出 / 缓存命中率 / 消息序列）。
 */
@Composable
internal fun ContextUsageRingButton(
    usedTokens: Int,
    maxTokens: Int,
    onClick: () -> Unit
) {
    // 占用比例（分母非法时视为 0 —— 空转态只画轨道，不画进度弧）
    val fraction = if (maxTokens > 0) {
        (usedTokens.coerceAtLeast(0).toFloat() / maxTokens).coerceIn(0f, 1f)
    } else 0f
    // 无障碍描述含实时百分比（读屏可播报「上下文用量 42%」）
    val percent = (fraction * 100).toInt()
    val description = stringResource(R.string.code_context_ring_cd_fmt, percent)
    val haloWidth = with(LocalDensity.current) { 3.6.dp.toPx() }

    IconButton(
        onClick = onClick,
        modifier = Modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .semantics { contentDescription = description }
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(22.dp)
        ) {
            val trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f)
            // 白弧的细外晕（浅色主题白底上白弧不可见的兜底）
            val haloColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
            Canvas(modifier = Modifier.fillMaxSize()) {
                val stroke = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round)
                // ── 轨道（完整衬底圆）──
                drawArc(
                    color = trackColor,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = stroke
                )
                if (fraction > 0f) {
                    // ═══ 最小可见弧（圆环口径修复 2026-11）：22dp / 2.6dp 的弧长下
                    // 小比例（<2%）的 360°×f 几乎不可见（视觉上像 0%）——用最小
                    // 6° 弧保证「有用量」与「空转态」可区分；百分比仍按真实值。
                    val sweep = (360f * fraction).coerceAtLeast(MIN_VISIBLE_SWEEP_DEG)
                    // ── 进度弧外晕（先画，白弧压在上面）──
                    drawArc(
                        color = haloColor,
                        startAngle = -90f,
                        sweepAngle = sweep,
                        useCenter = false,
                        style = Stroke(
                            width = min(haloWidth, size.minDimension * 0.35f),
                            cap = StrokeCap.Round
                        )
                    )
                    // ── 白色进度弧（用量百分比填充）──
                    drawArc(
                        color = Color.White,
                        startAngle = -90f,
                        sweepAngle = sweep,
                        useCenter = false,
                        style = stroke
                    )
                }
            }
        }
    }
}
