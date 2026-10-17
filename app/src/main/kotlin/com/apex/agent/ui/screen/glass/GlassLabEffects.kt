package com.apex.agent.ui.screen.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.maxDimension
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apex.agent.ui.glass.GlassStyle
import com.apex.agent.ui.glass.GlassSurface
import com.apex.agent.ui.glass.borderBeam
import com.apex.agent.ui.glass.toPath
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlin.math.PI
import kotlin.math.sin

/**
 * ═══════════════════════════════════════════════════════════════
 *  特效工坊 —— Effects Workshop（v1.4.9 新增）
 * ═══════════════════════════════════════════════════════════════
 *
 * 边界光系的扩展陈列（延续 [DynamicsSection] 的两件套语言）：
 *  1. **轮廓光轨**：光眼沿边缘恒速流动的常驻形态（6.5s 一圈）；
 *  2. **双生光轨**：两枚光眼相向而行（phase 与 1-phase），周期性交错 ——
 *     顺带验证 borderBeam 可叠加复用（基环只画一份）；
 *  3. **镜池涟漪**：按压触发的三环交错扩散 —— 事件驱动，空闲零动画
 *     成本（动效预算纪律：非必要不常驻）；
 *  4. **星尘悬浮**：14 粒微尘在玻璃背后上升 —— 玻璃走 kyant0 backdrop
 *     真实采样，星尘透过玻璃可见折射，是「玻璃在动的东西前面」的
 *     直观证明；
 *  5. **色相流转**：宝石边光沿品牌色闭环（primary→tertiary→secondary）
 *     缓慢轮转 —— 品牌色系内流转而非全谱彩虹（避免廉价感）。
 *
 * 动效纪律（诚实声明）：
 *  - 本区常驻循环 4 路（光轨 / 双生 / 星尘 / 色相），全部集中在一支
 *    InfiniteTransition（单帧时钟派生 4 路 State）—— 本页是全应用
 *    允许无限循环动画的验证屏（豁免声明见 GlassLabScreen 文档），
 *    业务界面禁用循环动画的规则不变；
 *  - 所有动画值 State 在 draw 作用域读取 —— 零逐帧重组；
 *  - 每帧零分配：笔刷 / 路径 / 参数数组全部 drawWithCache 缓存，
 *    绘制期只 translate / 取数组（Color.copy 与 Offset 是 inline
 *    value class，无堆分配）；
 *  - 星尘上升速度取整数（1x / 2x）—— 相位 t 在 1→0 回绕时 y01 才能无缝
 *    归位（非整数速度会在循环接缝处瞬移，这是循环动画的「Solid
 *    Drawing」铁律：结束帧必须流回起始帧）。
 */
@Composable
internal fun EffectsWorkshopSection() {
    SectionHeader(
        title = "特效工坊 · Effects Workshop",
        hint = "轮廓光系扩展陈列 —— 光眼恒速沿边、双生相向、按压涟漪、星尘透射、品牌色相流转"
    )
    val scheme = MaterialTheme.colorScheme

    // 全区唯一帧时钟：一支 InfiniteTransition 派生 4 路相位（省多时钟的帧驱动开销）
    val transition = rememberInfiniteTransition(label = "effects_workshop")
    val beamPhase = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 6500, easing = LinearEasing)),
        label = "beam_loop"
    )
    val twinPhase = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 7000, easing = LinearEasing)),
        label = "twin_loop"
    )
    val dustPhase = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 8000, easing = LinearEasing)),
        label = "dust_loop"
    )
    val huePhase = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 12000, easing = LinearEasing)),
        label = "hue_loop"
    )

    DynamicsFrame(label = "轮廓光轨 · Border Beam", icon = Icons.Filled.Bolt) {
        BorderBeamDemo(phase = beamPhase, tint = scheme.primary)
    }
    DynamicsFrame(label = "双生光轨 · Twin Beam", icon = Icons.Filled.Sync) {
        TwinBeamDemo(twin = twinPhase, tintA = scheme.primary, tintB = scheme.tertiary)
    }
    DynamicsFrame(label = "镜池涟漪 · Ripple Pool", icon = Icons.Filled.Waves) {
        RippleDemo(tint = scheme.primary)
    }
    DynamicsFrame(label = "星尘悬浮 · Stardust Veil", icon = Icons.Filled.Grain) {
        StardustDemo(dust = dustPhase, tint = scheme.primary)
    }
    DynamicsFrame(label = "色相流转 · Hue Orbit", icon = Icons.Filled.Palette) {
        HueOrbitDemo(hue = huePhase)
    }
}

// ═══════════════════════════════════════════════════════════════
//  1. 轮廓光轨（常驻形态）
// ═══════════════════════════════════════════════════════════════

/**
 * 光眼沿边缘恒速流动的常驻演示：与 [BorderBeamCard]（事件触发单圈）
 * 同一组件的另一形态 —— 常驻循环 6.5s 一圈，头亮尾散。
 */
@Composable
private fun BorderBeamDemo(phase: State<Float>, tint: Color) {
    val scheme = MaterialTheme.colorScheme
    val cardShape = RoundedCornerShape(18.dp)

    Box(
        Modifier
            .fillMaxWidth()
            .height(96.dp)
            .borderBeam(shape = cardShape, phase = { phase.value }, tint = tint)
    ) {
        GlassSurface(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp),
            style = GlassStyle.Card,
            shape = cardShape
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(22.dp)
                )
                Column {
                    Text(
                        "光眼沿边缘恒速流动",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "常驻循环 6.5s 一圈 —— 几何恒定光走路径，长边短边同速",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  2. 双生光轨（相向而行）
// ═══════════════════════════════════════════════════════════════

/**
 * 药丸形双光眼：primary 顺行、tertiary 逆行（phase 与 1 - phase），
 * 每圈交错两次 —— 顺带验证 borderBeam 的叠加复用（基环只画一份，
 * 第二路 baseRingAlpha = 0，避免双描边叠深）。
 */
@Composable
private fun TwinBeamDemo(twin: State<Float>, tintA: Color, tintB: Color) {
    val scheme = MaterialTheme.colorScheme
    val pillShape = RoundedCornerShape(percent = 50)

    Box(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .borderBeam(shape = pillShape, phase = { twin.value }, tint = tintA)
            .borderBeam(
                shape = pillShape,
                phase = { 1f - twin.value },
                tint = tintB,
                baseRingAlpha = 0f
            )
    ) {
        GlassSurface(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            style = GlassStyle.Floating,
            shape = pillShape
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    "双生光轨 · 相向而行",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  3. 镜池涟漪（按压触发）
// ═══════════════════════════════════════════════════════════════

/**
 * 按压玻璃任意位置 → 三环交错涟漪从按点扩散（1.1s，FastOutSlowIn）。
 *
 * 事件驱动纪律：Animatable 播完停在 1f，draw 直接短路返回 —— 空闲帧
 * 零绘制成本；三环以 0.16 相位差交错起跑（Follow Through：主环先行，
 * 从环跟上），透明度随扩散二次衰减。
 */
@Composable
private fun RippleDemo(tint: Color) {
    val scheme = MaterialTheme.colorScheme
    val cardShape = RoundedCornerShape(16.dp)

    // ripples 自增触发重放；progress 1f = 空闲（初值不打涟漪）
    var ripples by remember { mutableStateOf(0) }
    val progress = remember { Animatable(1f) }
    var tapPoint by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(ripples) {
        if (ripples > 0) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(durationMillis = 1100, easing = FastOutSlowInEasing))
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .height(104.dp)
    ) {
        GlassSurface(
            modifier = Modifier
                .fillMaxWidth()
                .height(104.dp),
            style = GlassStyle.Card,
            shape = cardShape
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    Icons.Filled.Waves,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(22.dp)
                )
                Column {
                    Text(
                        "按压任意位置",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "三环交错扩散 · 事件驱动 —— 空闲零动画成本",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant
                    )
                }
            }
        }
        // 涟漪覆盖层：置顶收点 + 绘制；裁剪在卡形内，不越界
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        tapPoint = offset
                        ripples += 1
                    }
                }
                .drawWithCache {
                    val outline = cardShape.createOutline(
                        size = size,
                        layoutDirection = layoutDirection,
                        density = this
                    )
                    val clip = outline.toPath()
                    val span = size.maxDimension * 0.85f
                    val startR = 10.dp.toPx()
                    onDrawWithContent {
                        drawContent()
                        val p = progress.value
                        if (p <= 0f || p >= 1f) return@onDrawWithContent
                        clipPath(clip) {
                            for (i in 0..2) {
                                // 第 i 环延迟 0.16 起跑，全程 0.84
                                val local = (p - i * 0.16f) / 0.84f
                                if (local !in 0f..1f) continue
                                val fade = 1f - local
                                drawCircle(
                                    color = tint,
                                    alpha = fade * fade * (0.50f - i * 0.08f),
                                    radius = startR + local * span,
                                    center = tapPoint,
                                    style = Stroke(width = (2.2f - 1.2f * local).dp.toPx())
                                )
                            }
                        }
                    }
                }
        )
    }
}

// ═══════════════════════════════════════════════════════════════
//  4. 星尘悬浮（真实 backdrop 采样）
// ═══════════════════════════════════════════════════════════════

/**
 * 玻璃卡压在上升星尘场上：星尘经 layerBackdrop 录制为采样源，
 * 玻璃内部画面随星尘实时变化 ——「玻璃在动的东西前面」的直观证明。
 *
 * 星尘参数（坐标 / 初相 / 速度 / 半径 / 基础透明度 / 摆动相位）全部
 * 以固定种子随机数预生成，缓存在 drawWithCache —— 尺寸变化才重建，
 * 每帧只做取模 / 正弦 / drawCircle。速度限 1x / 2x 两档（整数）：
 * 相位回绕无缝（见文件头 Solid Drawing 注记）。
 */
@Composable
private fun StardustDemo(dust: State<Float>, tint: Color) {
    val scheme = MaterialTheme.colorScheme
    val cardShape = RoundedCornerShape(16.dp)
    val backdrop = rememberLayerBackdrop()

    Box(
        Modifier
            .fillMaxWidth()
            .height(150.dp)
    ) {
        // 采样源：渐变基底 + 星尘层（录制进 GraphicsLayer）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .drawWithCache {
                        val baseBrush = Brush.verticalGradient(
                            colors = listOf(
                                scheme.surfaceContainerHighest.copy(alpha = 0.45f),
                                scheme.background
                            )
                        )
                        // 固定种子：重建（尺寸变化）也不换一批星尘
                        val rnd = kotlin.random.Random(20260417)
                        val n = 14
                        val xs = FloatArray(n) { 0.06f + 0.88f * rnd.nextFloat() }
                        val y0 = FloatArray(n) { rnd.nextFloat() }
                        val speed = FloatArray(n) { if (rnd.nextFloat() < 0.35f) 2f else 1f }
                        val radii = FloatArray(n) { (0.8f + 1.4f * rnd.nextFloat()).dp.toPx() }
                        val alphaBase = FloatArray(n) { 0.20f + 0.36f * rnd.nextFloat() }
                        val swayPhase = FloatArray(n) { rnd.nextFloat() * 2f * PI.toFloat() }
                        val swayAmp = 5.dp.toPx()
                        val tau = (2f * PI).toFloat()
                        onDrawBehind {
                            val t = dust.value
                            drawRect(brush = baseBrush)
                            for (i in 0 until n) {
                                // 上升：y01 循环取模，两端 sin 包络淡入淡出（无缝）
                                val y01 = (y0[i] + t * speed[i]) % 1f
                                val y = size.height * (1f - y01)
                                val x = size.width * xs[i] +
                                    sin(swayPhase[i] + t * tau * speed[i]) * swayAmp
                                val edgeFade = sin(PI.toFloat() * y01)
                                drawCircle(
                                    color = tint.copy(alpha = alphaBase[i] * edgeFade),
                                    radius = radii[i],
                                    center = Offset(x, y)
                                )
                            }
                        }
                    }
            )
        }
        // 玻璃卡：实时采样身后的星尘（折射 / 模糊随引擎档位走）
        GlassSurface(
            modifier = Modifier.fillMaxSize(),
            backdrop = backdrop,
            style = GlassStyle.Card,
            shape = cardShape
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "星尘在玻璃后面上升",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "玻璃实时采样身后的微尘 —— 透过霜面看星轨流动",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  5. 色相流转（品牌色闭环）
// ═══════════════════════════════════════════════════════════════

/**
 * 玻璃宝石的边光色沿品牌色闭环（primary → tertiary → secondary →
 * 回到 primary）以 12s 一圈缓慢轮转：光晕（三同心圆近似柔光）+ 边线
 * （细描边）双线索同色联动。色环为 6 个预生成色标（相邻主色 50% 插值
 * 接驳），绘制期按相位取相邻两色 lerp —— Color 为 inline value class，
 * 每帧零分配；环闭环保证 t 回绕 1→0 时颜色连续。
 */
@Composable
private fun HueOrbitDemo(hue: State<Float>) {
    val scheme = MaterialTheme.colorScheme
    // 品牌色闭环色环：相邻主色 50% 插值接驳（主题色变化才重建）
    val ramp = remember(scheme.primary, scheme.tertiary, scheme.secondary) {
        listOf(
            scheme.primary,
            lerp(scheme.primary, scheme.tertiary, 0.5f),
            scheme.tertiary,
            lerp(scheme.tertiary, scheme.secondary, 0.5f),
            scheme.secondary,
            lerp(scheme.secondary, scheme.primary, 0.5f)
        )
    }
    val gemShape = RoundedCornerShape(24.dp)

    Row(
        Modifier
            .fillMaxWidth()
            .height(104.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            Modifier
                .size(84.dp)
                .drawWithCache {
                    val outline = gemShape.createOutline(
                        size = size,
                        layoutDirection = layoutDirection,
                        density = this
                    )
                    val glowR = size.minDimension * 0.72f
                    onDrawWithContent {
                        drawContent()
                        val t = hue.value
                        val f = (t * ramp.size) % ramp.size
                        val i = f.toInt()
                        val c = lerp(ramp[i], ramp[(i + 1) % ramp.size], f - i)
                        // 柔光晕：三同心圆递进透明度（近似径向渐变，免逐帧重建笔刷）
                        drawCircle(color = c, alpha = 0.10f, radius = glowR, center = center)
                        drawCircle(color = c, alpha = 0.10f, radius = glowR * 0.66f, center = center)
                        drawCircle(color = c, alpha = 0.12f, radius = glowR * 0.38f, center = center)
                        // 锐边线：色相流转的主线索
                        drawOutline(
                            outline = outline,
                            color = c.copy(alpha = 0.90f),
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }
                }
        ) {
            GlassSurface(
                modifier = Modifier.size(72.dp),
                style = GlassStyle.Floating,
                shape = gemShape,
                accent = scheme.primary
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.Palette,
                        contentDescription = null,
                        tint = scheme.primary,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }
        Column {
            Text(
                "色相流转 · 品牌色闭环",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "边光沿 primary→tertiary→secondary 缓慢轮转 —— 品牌色系内流转，不走全谱彩虹",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant
            )
        }
    }
}
