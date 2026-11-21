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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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

/** 圆环满度判定阈值：fraction >= 此值认为100%，避免满圆时端点缺口。 */
private const val FULL_FRACTION_THRESHOLD = 0.999f

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
 *
 * ## 20261121 修复
 * 1. **百分比真实性**：四舍五入替代直接截断，fraction>0且显示0%时修正为1%，接近满圆上卷为100%，消除「有弧显示0%、满弧显示99%」违和感；
 * 2. **显示优化**：Canvas预留描边+外晕内边距，避免圆角帽被边界裁剪；外晕宽度受可用空间约束，两端圆头完整显示；
 * 3. **满圆无缺口**：接近满圆（sweep≈360°）时改用Butt线帽，避免两个Round端点重叠产生缝隙或凸起；
 * 4. **浅色主题兜底**：增加极细深色描边，白底上白弧边界更清晰。
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

    // 百分比真实性校准（20261121 修复）：
    // 1. 四舍五入替代直接截断；
    // 2. fraction>0但round结果为0时，显示1%（与最小可见弧一致）；
    // 3. fraction接近满圆(>=99.5%)时显示100%，避免「弧满了却显示99%」；
    // 4. 无障碍播报与视觉显示一致。
    val rawPercent = Math.round(fraction * 100f)
    val percent = when {
        fraction <= 0f -> 0
        rawPercent < 1 -> 1
        fraction >= 0.995f -> 100
        else -> rawPercent
    }
    val description = stringResource(R.string.code_context_ring_cd_fmt, percent)

    // 描边和外晕宽度（20261121：预留内边距防止裁切）
    val strokeWidthPx = with(LocalDensity.current) { 2.6.dp.toPx() }
    val haloWidthPx = with(LocalDensity.current) { 3.6.dp.toPx() }
    // 内边距 = 外晕宽度的一半，确保最外层外晕的圆角帽不被Box边界裁剪
    val insetPaddingPx = haloWidthPx / 2f + 0.5f

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
            // 白弧的细外晕（浅色主题白底上白弧不可见的兜底）：适当加深不透明度提升辨识度
            val haloColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
            // 极细深色描边兜底（浅色主题白弧轮廓更清晰）
            val outlineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)

            Canvas(modifier = Modifier.fillMaxSize()) {
                val canvasSize = this.size
                // 内边距后实际绘制圆的直径和半径
                val drawDiameter = min(canvasSize.width, canvasSize.height) - 2 * insetPaddingPx
                val drawRadius = drawDiameter / 2f
                val center = Offset(canvasSize.width / 2f, canvasSize.height / 2f)
                val arcTopLeft = Offset(center.x - drawRadius, center.y - drawRadius)
                val arcSize = Size(drawDiameter, drawDiameter)

                // 轨道描边
                val trackStroke = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
                // ── 轨道（完整衬底圆）──
                drawArc(
                    color = trackColor,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = trackStroke
                )

                if (fraction > 0f) {
                    // ═══ 最小可见弧（圆环口径修复 2026-11）：22dp / 2.6dp 的弧长下
                    // 小比例（<2%）的 360°×f 几乎不可见（视觉上像 0%）——用最小
                    // 6° 弧保证「有用量」与「空转态」可区分；百分比仍按真实值校准显示。
                    val sweep = (360f * fraction).coerceAtLeast(MIN_VISIBLE_SWEEP_DEG)

                    // 满圆判定（20261121）：接近360度时用Butt线帽，避免两端Round帽重叠产生缺口/凸起
                    val isFullCircle = fraction >= FULL_FRACTION_THRESHOLD || sweep >= 359.9f
                    val progressCap = if (isFullCircle) StrokeCap.Butt else StrokeCap.Round
                    val progressStroke = Stroke(width = strokeWidthPx, cap = progressCap)
                    val haloStrokeWidth = min(haloWidthPx, drawDiameter * 0.3f).coerceAtMost(strokeWidthPx + extraHaloPaddingPx)
                    val haloStroke = Stroke(width = haloStrokeWidth, cap = progressCap)
                    // 外晕实际sweep：满圆时画完整360度，否则保持弧长一致
                    val haloSweep = if (isFullCircle) 360f else sweep

                    // ── 进度弧外晕（先画，白弧压在上面）──
                    drawArc(
                        color = haloColor,
                        startAngle = -90f,
                        sweepAngle = haloSweep,
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = arcSize,
                        style = haloStroke
                    )

                    // ── 极细深色轮廓兜底（浅色主题白弧边界清晰）──
                    val outlineStroke = Stroke(
                        width = strokeWidthPx + outlineWidthExtraPx,
                        cap = progressCap
                    )
                    drawArc(
                        color = outlineColor.copy(alpha = 0.25f),
                        startAngle = -90f,
                        sweepAngle = haloSweep,
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = arcSize,
                        style = outlineStroke
                    )

                    // ── 白色进度弧（用量百分比填充）──
                    drawArc(
                        color = Color.White,
                        startAngle = -90f,
                        sweepAngle = if (isFullCircle) 360f else sweep,
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = arcSize,
                        style = progressStroke
                    )
                }
            }
        }
    }
}
