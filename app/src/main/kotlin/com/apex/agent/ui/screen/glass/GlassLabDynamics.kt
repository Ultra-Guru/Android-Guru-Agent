package com.apex.agent.ui.screen.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.FlashOn
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apex.agent.ui.glass.GlassStyle
import com.apex.agent.ui.glass.GlassSurface
import com.apex.agent.ui.glass.borderBeam

/**
 * ═══════════════════════════════════════════════════════════════
 * 顶阶动态 —— v1.4.9 边界光轨重构（Pro Dynamics v2 · kyant0 迁移版）
 * ═══════════════════════════════════════════════════════════════
 *
 * 对标顶级产品动态语言的两件套（全部实时绘制，逐帧可验证）：
 *  1. **边界光轨**：光眼沿轮廓恒速流动 —— 进入卡片 / 点击触发单圈，
 *     空闲时只留常驻基环。v1.4.9 前的 rotate + sweep 实现把描边几何
 *     整体转起来（宽卡上退化为「方框打转」，拐角接缝错位）—— 现在
 *     几何恒定，光走路径（PathMeasure），共享组件见 ui.glass 的 BorderBeam；
 *  2. **呼吸光晕**：唯一保留的常驻律动 —— 浅色主题用「加深 tint 光晕 +
 *     tint 细环」双线索（细环在白底上清晰可读且不发灰；单纯拉高
 *     光晕透明度只会得到灰蒙蒙的脏边）。
 *
 * 退役记录：**镜面扫掠**（原第 2 件）依赖旧 GlassSurface 的扫掠参数 ——
 * kyant0 引擎的边缘受光（Highlight）是常驻材质语言，无需扫掠光带
 * 补光，该参数与共享扫掠时钟已随迁移一并移除（多卡同屏相位同步的
 * 问题随之不复存在）。
 *
 * 动效预算（Motion Budget）：
 *  - 同屏常驻循环动画 ≤ 1 —— 呼吸光晕是本区唯一的 ambient 律动；
 *  - 边界光轨为事件驱动（进入 / 点击触发），空闲零动画成本；
 *  - 两件套同开也不再互相轰炸：常驻的只有呼吸，其余按需播放。
 *
 * 诚实声明（延续本实验室原则）：
 *  - 边框/光晕均为单图层 Canvas 级绘制（GlassSurface 本体的 lens 折射由
 *    kyant0 在 API 33+ 提供，与本动态层无关）；
 *  - 动画值以 State<Float> 传入 draw 作用域读取 —— 子树零逐帧重组。
 */
@Composable
internal fun DynamicsSection() {
    SectionHeader(
        title = "顶阶动态 · Pro Dynamics",
        hint = "两件套动态语言 —— 动效预算内运行：光眼事件触发沿边流动，呼吸为唯一常驻律动（镜面扫掠已随 kyant0 迁移退役）"
    )

    BorderBeamCard()
    BreathingOrbsRow()
}

// ── 1. 边界光轨 ──────────────────────────────────────────────────────────────

/**
 * 光眼描边卡：进入组合 / 点击触发单圈（2.4s），空闲只留常驻基环。
 *
 * 实现要点（v1.4.9 重构）：
 *  - **几何恒定，光走路径**：旧实现 rotate(angle) 把圆角矩形描边整体
 *    转起来 —— 宽卡上视觉退化为「方框打转」，拐角接缝错位；
 *  - **恒定弧长速度**：PathMeasure 沿轮廓取点，光眼贴边流动 —— 长边
 *    短边节奏一致，与锥形渐变的角速度（长边快短边慢）不同；
 *  - **事件触发单循环**：fadeEnvelope 包络首 12% 淡入、末 15% 淡出 ——
 *    播完自然归零，不留静止亮斑；进入组合自动播一圈，点击再触发。
 */
@Composable
private fun BorderBeamCard() {
    val scheme = MaterialTheme.colorScheme
    val cardShape = RoundedCornerShape(16.dp)

    // pulses ≥ 1 即播放一圈：初值 1 = 进入组合自动播一圈；点击 +1 重启
    var pulses by remember { mutableStateOf(1) }
    // 相位动画 0→1：一路就是一圈（borderBeam 内按弧长展开）
    val phase = remember { Animatable(0f) }

    LaunchedEffect(pulses) {
        phase.snapTo(0f)
        phase.animateTo(1f, tween(durationMillis = 2400, easing = LinearEasing))
    }

    DynamicsFrame(label = "边界光轨 · Border Beam", icon = Icons.Filled.FlashOn) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(96.dp)
                // 点击再触发一圈（触屏设备上按压即 hover 语义的等价交互）
                .pointerInput(Unit) {
                    detectTapGestures { pulses += 1 }
                }
                // 光眼沿边缘恒速流动：几何零旋转，头亮尾散（共享组件
                // BorderBeam：PathMeasure 取点 + 缓存笔刷，每帧零分配）
                .borderBeam(
                    shape = cardShape,
                    phase = { phase.value },
                    tint = scheme.primary,
                    fadeEnvelope = true
                )
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
                        tint = scheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Column {
                        Text(
                            "光眼沿边缘流动",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "进入 / 点击触发单圈 —— 几何恒定光走路径，恒定弧长速度零接缝",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

// ── 2. 呼吸光晕 ──────────────────────────────────────────────────────────────

/**
 * 三枚相位错开 1/3 周期的玻璃球：光环半径与亮度同步脉动。
 * 本组是两件套中唯一的常驻律动（动效预算：同屏常驻循环 ≤ 1）。
 */
@Composable
private fun BreathingOrbsRow() {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f

    // 浅色主题：光晕色向 onSurface 加深 25% —— 提高与白底的明度差。
    // 只拉 alpha 不加深色，浅 tint 在白底上依旧不可见，拉过头就是灰蒙脏边；
    // 加深后的 tint 让低 alpha 也能被读到，同时保持「色」而非「灰」。
    val deepPrimary = if (dark) scheme.primary else lerp(scheme.primary, scheme.onSurface, 0.25f)
    val deepTertiary = if (dark) scheme.tertiary else lerp(scheme.tertiary, scheme.onSurface, 0.25f)
    val deepSecondary = if (dark) scheme.secondary else lerp(scheme.secondary, scheme.onSurface, 0.25f)

    val transition = rememberInfiniteTransition(label = "breathing_orbs")
    // 三路相位：0 / 1/3 / 2/3 周期错开 —— 呼吸此起彼伏
    val breathA = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breath_a"
    )
    val breathB = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
            initialStartOffset = StartOffset(866)
        ),
        label = "breath_b"
    )
    val breathC = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
            initialStartOffset = StartOffset(1733)
        ),
        label = "breath_c"
    )

    DynamicsFrame(label = "呼吸光晕 · Breathing Glow", icon = Icons.Filled.AutoAwesome) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            BreathingOrb(breath = breathA, orbSize = 56.dp, tint = scheme.primary, deepTint = deepPrimary, dark = dark)
            BreathingOrb(breath = breathB, orbSize = 72.dp, tint = scheme.tertiary, deepTint = deepTertiary, dark = dark)
            BreathingOrb(breath = breathC, orbSize = 56.dp, tint = scheme.secondary, deepTint = deepSecondary, dark = dark)
        }
    }
}

/**
 * 单枚呼吸玻璃球：外圈脉动光晕 + tint 细环 + 玻璃本体（参数名避开 DrawScope.size）。
 *
 * 浅色模式双线索：加深 tint 的径向光晕（可读且不灰）+ 纯 tint 细环
 *（1.5dp 描边环在白底上轮廓清晰，环随呼吸收张）—— 取代旧版
 * 「浅色几乎不可见 / 拉高强度则边缘发灰」的单光晕配方。
 */
@Composable
private fun BreathingOrb(
    breath: State<Float>,
    orbSize: androidx.compose.ui.unit.Dp,
    tint: Color,
    deepTint: Color,
    dark: Boolean
) {
    // 光晕 / 细环强度按明暗双态调参：深色维持原发光语言，
    // 浅色靠「加深色 + 细环」而非拉 alpha —— 不产生灰脏边
    val haloPeak = if (dark) 0.30f else 0.26f
    val haloAmp = if (dark) 0.18f else 0.16f
    val ringBase = if (dark) 0.10f else 0.26f
    val ringAmp = if (dark) 0.10f else 0.18f
    val coreBase = if (dark) 0.45f else 0.55f

    Box(contentAlignment = Alignment.Center) {
        // 脉动光晕 + 细环（draw 作用域读 State —— 零重组）
        Box(
            Modifier
                .size(orbSize * 1.65f)
                .drawBehind {
                    val b = breath.value
                    val r = size.minDimension * (0.30f + 0.16f * b)
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                deepTint.copy(alpha = haloPeak + haloAmp * b),
                                Color.Transparent
                            ),
                            center = center,
                            radius = r
                        ),
                        radius = r,
                        center = center
                    )
                    drawCircle(
                        color = tint.copy(alpha = ringBase + ringAmp * b),
                        radius = r,
                        center = center,
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                }
        )
        GlassSurface(
            modifier = Modifier.size(orbSize),
            style = GlassStyle.Floating,
            shape = CircleShape,
            accent = tint
        ) {
            Box(contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(orbSize * 0.34f)
                        .drawBehind {
                            val b = breath.value
                            drawCircle(
                                color = tint.copy(alpha = coreBase + 0.35f * b),
                                radius = size.minDimension / 2f
                            )
                        }
                )
            }
        }
    }
}

// ── 展示框 ──────────────────────────────────────────────────────────────────

/** 动态演示的标准外框：等宽小标签 + 演示主体（特效工坊同款复用）。 */
@Composable
internal fun DynamicsFrame(
    label: String,
    icon: ImageVector,
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary
            )
        }
        content()
    }
}
