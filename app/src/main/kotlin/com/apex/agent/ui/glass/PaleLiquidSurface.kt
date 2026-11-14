package com.apex.agent.ui.glass

import android.os.Build
import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline          // ★ H-1：顶层扩展函数，非 DrawScope 成员 —— 漏 import 即 compileDebugKotlin 挂
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.apex.agent.ui.theme.LocalUiStyle
import com.apex.agent.ui.theme.UiStyle
import com.kyant.backdrop.Backdrop
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * ═══════════════════════════════════════════════════════════════
 *  PaleLiquidSurface — 浅白色液态玻璃（PartSkin spec §6 唯一渲染实现）
 * ═══════════════════════════════════════════════════════════════
 *
 * 目标观感（§6.1）：乳白霜面为底，一层极淡的白色垂直渐变；主题色只以
 * **低饱和光晕**形式在玻璃内部呼吸，边缘一圈白热流光贴轮廓缓慢行走；
 * 文字永远是纯 `onSurface`，任何状态都不把整卡染成粉红/暗红（FR-11：
 * 调 [GlassSurface] 时**恒传 `accent = Color.Unspecified`** ——
 * `glassPalette()` 只在 accent.alpha > 0 时染色，这正是粉红卡的根因）。
 *
 * 渲染层级（§6.5，自下而上仅 4 层，禁止再加容器）：
 * ```
 * ① Box(modifier) 内：GlassSurface(matchParentSize, accent=Unspecified,
 *                    style=PaleLiquid, shape=S) {}      ← 唯一材质容器，不贡献尺寸
 * ② Box.matchParentSize().clip(S).drawWithCache { 白霜 + 光晕 + 扫掠 }
 * ③ Box.matchParentSize().borderBeam(shape=S, phase=...)   ← MINIMAL 下省略
 * ④ Column.padding(contentPadding) { content() }         ← 业务内容，且**唯一贡献尺寸**
 * ```
 * 尺寸约定（易踩坑）：①②③ 全用 `matchParentSize`（BoxScope 内不参与
 * 测量），外层 `Box` 的尺寸由 ④ 的 `Column` 决定 —— 若 ④ 也写
 * `matchParentSize`，整卡高度会塌成 0。声明顺序同时保证内容绘制在
 * 最上层，彩光永不遮字。
 *
 * 光效层（§6.4，复用既有零件不新造轮子）：
 *  - 白霜垂直渐变：frostTop → frostBottom（[palePalette]）；
 *  - 三枚漂移彩光：AboutScreen.AuroraCanvas 的 Lissajous 轨迹
 *    （半径 0.55/0.48/0.42 × w，周期 9s）；
 *  - 白色镜面扫掠：固定 `linearGradient` + `translate`（3.6s）；
 *  - 轮廓流光：[borderBeam]（beamWidth 1.2dp / headGlow 14dp /
 *    baseRing 0.22 / 4.2s 一圈）；
 *  - 光晕强度 [glow] 0..1 乘到三枚光晕 alpha（draw 阶段读值，0 = 纯白霜面）。
 *
 * 降级矩阵（§10 / NFR-4 / NFR-5 / NFR-7）：
 *  - `UiStyle.MINIMAL` 且无豁免 → PaleFlat：GlassSurface 单点短路为
 *    FlatSurface（实色 + 发丝描边），overlay 只留**静态白霜渐变**，
 *    零光晕零流光零扫掠 —— 与 FlatSurface 同语言；
 *  - API < 31 → 光效停静态帧（Frosted 档：静态白霜 + 静态光晕中位构图 +
 *    常驻基环，零管线诚实降级）；
 *  - 减少动效（系统 `ANIMATOR_DURATION_SCALE == 0`）→ 全部静态帧；
 *  - `!active`（idle 卡片）→ 静态帧（光晕停中位、流光负相位熄灭只留基环、
 *    扫掠停中位），仍保留白霜渐变保证「有渐变」。
 *
 * 性能纪律（NFR-2，沿用 #262 / #323）：全部 Brush / Outline 在
 * `drawWithCache` 缓存；动画相位在 draw 阶段读 `State`（Lissajous 中心
 * 每帧仅 translate，径向渐变以原点缓存）—— 零逐帧重组、零逐帧 Brush 分配。
 */
@Composable
fun PaleLiquidSurface(
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = null,
    shape: Shape = GlassShapes.card,
    glow: Float = 0.35f,
    active: Boolean = true,
    forceGlass: Boolean = false,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val palette = palePalette()
    // ═══ NFR-5：MINIMAL 短路豁免判定 ═══
    // 无豁免时 GlassSurface 单点短路为 FlatSurface（实色 + 发丝描边），
    // 本组件的 overlay 层同步收敛为「静态白霜」——PaleFlat 降级形态。
    val minimal = LocalUiStyle.current == UiStyle.MINIMAL && !forceGlass
    // ═══ 静态帧判定：MINIMAL / API < 31（Frosted 档）/ 减少动效 / !active ═══
    val animated = active && !minimal &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        !rememberReducedMotion()

    // ── H-2（硬规则）：transition 与三个 animateFloat **恒定创建**，禁止在
    // if/else 分支里条件调用 remember*（animated 翻转会改变 slot table 调用
    // 位置，引发动画状态错位）；静态帧只在 draw 阶段读固定值：
    // drift 0.5（中位构图）/ beam -1（光眼熄灭只留基环）/ sheen 0.5（中位）。──
    val transition = rememberInfiniteTransition(label = "pale-liquid")
    val drift = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = DRIFT_PERIOD_MS, easing = LinearEasing)
        ),
        label = "pale-drift-phase"
    )
    val beamPhase = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = BEAM_PERIOD_MS, easing = LinearEasing)
        ),
        label = "pale-beam-phase"
    )
    val sheen = transition.animateFloat(
        initialValue = -0.4f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = SHEEN_PERIOD_MS),
            repeatMode = RepeatMode.Restart
        ),
        label = "pale-sheen-phase"
    )

    // glow 读入即 clamp（持久化容错的第二道钳，§11.1）
    val glowLevel = glow.coerceIn(0f, 1f)

    Box(modifier = modifier) {
        // ① 唯一材质容器（不贡献尺寸）—— accent 恒 Unspecified（FR-11 硬规则：
        // 语义状态色只作用于图标与文字，绝不染整块玻璃）
        GlassSurface(
            modifier = Modifier.matchParentSize(),
            backdrop = backdrop,
            style = GlassStyle.PaleLiquid,
            shape = shape,
            accent = Color.Unspecified,
            scaleOnPress = false,
            forceGlass = forceGlass
        ) {}
        // ② 白霜 + 光晕 + 扫掠 overlay（不贡献尺寸）
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(shape)
                .drawWithCache {
                    val w = size.width
                    val h = size.height
                    val frostBrush = Brush.verticalGradient(
                        colors = listOf(palette.frostTop, palette.frostBottom),
                        startY = 0f,
                        endY = h
                    )
                    // 光晕渐变原点化（AuroraCanvas 同款纪律）：半径只依赖
                    // 宽度，中心随 translate 移动 —— Brush 完全静态可缓存
                    val orbABrush = Brush.radialGradient(
                        colors = listOf(palette.glowA, Color.Transparent),
                        center = Offset.Zero,
                        radius = w * 0.55f
                    )
                    val orbBBrush = Brush.radialGradient(
                        colors = listOf(palette.glowB, Color.Transparent),
                        center = Offset.Zero,
                        radius = w * 0.48f
                    )
                    val orbCBrush = Brush.radialGradient(
                        colors = listOf(palette.glowC, Color.Transparent),
                        center = Offset.Zero,
                        radius = w * 0.42f
                    )
                    // 规范位（bandX=0）的扫掠光带渐变；动画只平移不重建
                    val sheenBrush = Brush.linearGradient(
                        colors = listOf(Color.Transparent, palette.sheen, Color.Transparent),
                        start = Offset(-w * 0.24f, 0f),
                        end = Offset(w * 0.24f, h)
                    )
                    val outline = shape.createOutline(
                        size = size,
                        layoutDirection = layoutDirection,
                        density = this
                    )
                    onDrawBehind {
                        // ① 白霜垂直渐变 —— 恒在（降级帧也「有渐变」）
                        drawRect(brush = frostBrush)
                        if (!minimal) {
                            // ② 三枚漂移彩光（glow 0..1 乘 alpha，draw 阶段读值）
                            if (glowLevel > 0f) {
                                val t = (if (animated) drift.value else 0.5f) * 2.0 * PI
                                drawOrb(
                                    brush = orbABrush,
                                    cx = w * (0.80f + 0.09f * sin(t).toFloat()),
                                    cy = h * (0.20f + 0.10f * cos(t * 0.7).toFloat()),
                                    radius = w * 0.55f,
                                    alpha = glowLevel
                                )
                                drawOrb(
                                    brush = orbBBrush,
                                    cx = w * (0.14f + 0.08f * sin(t * 1.3 + 1.1).toFloat()),
                                    cy = h * (0.74f + 0.12f * cos(t * 0.9).toFloat()),
                                    radius = w * 0.48f,
                                    alpha = glowLevel
                                )
                                drawOrb(
                                    brush = orbCBrush,
                                    cx = w * (0.50f + 0.15f * sin(t * 0.8 + 2.4).toFloat()),
                                    cy = h * (0.45f + 0.16f * cos(t * 1.1).toFloat()),
                                    radius = w * 0.42f,
                                    alpha = glowLevel
                                )
                            }
                            // ③ 镜面扫掠：固定 Brush + 每帧 translate（零分配）
                            translate(left = (if (animated) sheen.value else 0.5f) * w) {
                                drawOutline(outline = outline, brush = sheenBrush)
                            }
                        }
                    }
                }
        )
        // ③ 轮廓流光（MINIMAL 下省略 —— PaleFlat 的发丝描边由 FlatSurface 提供）
        if (!minimal) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .borderBeam(
                        shape = shape,
                        phase = { if (animated) beamPhase.value else -1f },
                        tint = palette.eye,
                        baseRingAlpha = 0.22f,
                        beamWidth = 1.2.dp,
                        headGlowRadius = 14.dp
                    )
            )
        }
        // ④ 业务内容 —— 唯一贡献尺寸（外层 Box 的高度由本 Column 决定）
        Column(
            modifier = Modifier
                .then(
                    if (onClick != null) {
                        Modifier.clip(shape).clickable { onClick() }
                    } else Modifier
                )
                .padding(contentPadding)
        ) {
            content()
        }
    }
}

/** 光晕绘制：translate 到动点 + drawCircle(alpha)（Brush 缓存零重建）。 */
private fun DrawScope.drawOrb(
    brush: Brush,
    cx: Float,
    cy: Float,
    radius: Float,
    alpha: Float
) {
    translate(left = cx, top = cy) {
        drawCircle(brush = brush, radius = radius, center = Offset.Zero, alpha = alpha)
    }
}

/**
 * 减少动效检测（NFR-7）：系统「动画时长缩放」为 0（开发者选项「关闭动画」
 * / 无障碍「移除动画」）时返回 true —— 光晕、镜面扫掠、borderBeam 全部
 * 转静态帧。Settings.Global 读取经 remember 缓存，零重复查询。
 */
@Composable
private fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) == 0f
        }.getOrDefault(false)
    }
}

/** Lissajous 漂移周期（§6.4：9s —— 比 AboutScreen 极光 26s 更缓，阅读优先）。 */
private const val DRIFT_PERIOD_MS = 9_000

/** 轮廓流光周期（§6.4：4.2s 一圈）。 */
private const val BEAM_PERIOD_MS = 4_200

/** 镜面扫掠周期（§6.4：3.6s）。 */
private const val SHEEN_PERIOD_MS = 3_600
