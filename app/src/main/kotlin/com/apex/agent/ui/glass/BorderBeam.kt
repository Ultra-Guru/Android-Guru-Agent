package com.apex.agent.ui.glass

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * ═══════════════════════════════════════════════════════════════
 *  边界光轨 Border Beam —— 光眼沿轮廓匀速流动（v1.4.9）
 * ═══════════════════════════════════════════════════════════════
 *
 * 问题背景：旧「流光边框」用 DrawScope.rotate 整体旋转描边几何 ——
 * 圆角矩形不是旋转对称图形，一转轮廓就离开原边界，宽卡上视觉退化为
 * 「一个方框在打转」（拐角接缝错位、描边越界）。这是把「渐变相位旋转」
 * 误实现成「画布旋转」的经典错误；锥形渐变的角速度也会让长边快短边慢，
 * 与「沿边缘流动」的直觉相悖。
 *
 * 正解：几何恒定，光走路径 ——
 *  1. **常驻基环**：固定轮廓的细描边（恒定低透明 tint 细环，静态）；
 *  2. **流动光眼**：PathMeasure 沿轮廓路径取点，一枚亮光头（径向光团 +
 *     白热内核）带 N 枚渐弱拖尾，以恒定**弧长速度**绕边缘流动 —— 长边
 *     短边节奏一致，光永远贴着边缘走，这才是「沿边缘流动」。
 *
 * 性能纪律（延续 #262 / #323）：
 *  - Outline / Path / PathMeasure / 各光团 Brush 全部在 drawWithCache
 *    缓存，仅尺寸或主题色变化时重建；
 *  - 每帧只做 getPosition 取点（Offset 为 inline value class，无堆分配）
 *    + translate 绘制 —— 零分配；
 *  - 相位以 lambda 传入、draw 作用域内读取（读取其中 State 触发重绘而非
 *    重组）—— 子树零逐帧重组。
 *
 * 相位约定：phase() 返回 [0,1] 的循环进度；返回负值 = 光眼熄灭（只留
 * 常驻基环），供静态降级帧使用。fadeEnvelope = true 时（单圈播放场景）
 * 光眼首 12% 淡入、末 15% 淡出，播完自然归零不留静止亮斑。
 */
fun Modifier.borderBeam(
    shape: Shape,
    phase: () -> Float,
    tint: Color,
    baseRingAlpha: Float = 0.30f,
    beamWidth: Dp = 1.6.dp,
    headGlowRadius: Dp = 16.dp,
    coreRadius: Dp = 2.5.dp,
    tailCount: Int = 5,
    tailGap: Dp = 13.dp,
    fadeEnvelope: Boolean = false,
    reverse: Boolean = false
): Modifier = drawWithCache {
    val outline: Outline = shape.createOutline(
        size = size,
        layoutDirection = layoutDirection,
        density = this
    )
    // 轮廓 → 路径 → 弧长测量：光眼的行进轨道
    val measure = PathMeasure()
    measure.setPath(outline.toPath(), false)
    val pathLength = measure.length
    val headR = headGlowRadius.toPx()
    val coreR = coreRadius.toPx()
    val gapPx = tailGap.toPx()

    // ── 缓存笔刷（中心原点化，绘制期 translate 到光眼所在点）──
    val baseBrush = SolidColor(tint)
    val headBrush = Brush.radialGradient(
        colors = listOf(tint.copy(alpha = 1f), tint.copy(alpha = 0f)),
        center = Offset.Zero,
        radius = headR
    )
    val coreColor = lerp(tint, Color.White, 0.72f)
    // 拖尾与光头共用同一支径向笔刷：逐枚亮度/半径衰减绘制期经
    // drawCircle 的 alpha 参数施加（笔刷本体满强度缓存 —— 包络与
    // 衰减都不触发笔刷重建）
    val tailBrush = Brush.radialGradient(
        colors = listOf(tint.copy(alpha = 1f), tint.copy(alpha = 0f)),
        center = Offset.Zero,
        radius = headR
    )
    val tailAlphas = FloatArray(tailCount) { 0.34f * (0.68f).pow(it + 1) }
    val tailRadii = FloatArray(tailCount) { headR * (1f - (it + 1) * 0.14f) }
    val ringStroke = Stroke(width = beamWidth.toPx())

    onDrawWithContent {
        drawContent()
        // ① 常驻基环：几何固定，任何时候都在（轮廓锚定）
        drawOutline(
            outline = outline,
            brush = baseBrush,
            alpha = baseRingAlpha,
            style = ringStroke
        )
        // ② 流动光眼（负相位 = 熄灭态，只留基环）
        val raw = phase()
        if (raw < 0f || pathLength <= 0f) return@onDrawWithContent
        // 取模归一 [0,1)：t=1 与 t=0 同点（闭合轮廓），回绕无缝
        val t = ((if (reverse) 1f - raw else raw) % 1f)
        val env = if (fadeEnvelope) {
            (t / 0.12f).coerceAtMost(1f) * ((1f - t) / 0.15f).coerceAtMost(1f)
        } else {
            1f
        }
        if (env <= 0.01f) return@onDrawWithContent
        val d = t * pathLength
        // 拖尾先画（垫在光头下面）：头后方逐枚渐弱
        for (k in tailCount downTo 1) {
            var dk = d - k * gapPx
            if (dk < 0f) dk += pathLength
            val p = measure.getPosition(dk)
            translate(left = p.x, top = p.y) {
                drawCircle(
                    brush = tailBrush,
                    radius = tailRadii[k - 1],
                    center = Offset.Zero,
                    alpha = env * tailAlphas[k - 1]
                )
            }
        }
        // 光头：光团 + 白热内核
        val hp = measure.getPosition(d)
        translate(left = hp.x, top = hp.y) {
            drawCircle(brush = headBrush, radius = headR, center = Offset.Zero, alpha = env)
            drawCircle(color = coreColor, radius = coreR, center = Offset.Zero, alpha = 0.9f * env)
        }
    }
}

/**
 * Outline → Path：Generic 直接取内部 path；Rounded / Rectangle 现建。
 * 弧长测量（PathMeasure）与路径裁剪（clipPath）都需要 Path ——
 * Compose 未内建 Outline 转换（旧记忆里的 asPath 并不存在），
 * 本扩展是三态的等价物。路径在 drawWithCache 缓存期内复用。
 */
internal fun Outline.toPath(): Path = when (this) {
    is Outline.Generic -> path
    is Outline.Rounded -> Path().apply { addRoundRect(roundRect) }
    is Outline.Rectangle -> Path().apply { addRect(rect) }
}

/** Float 幂（kotlin.math.pow 的私有别名，避免文件顶部为一处调用引全家桶）。 */
private fun Float.pow(exp: Int): Float = Math.pow(this.toDouble(), exp.toDouble()).toFloat()
