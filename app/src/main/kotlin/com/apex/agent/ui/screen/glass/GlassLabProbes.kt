package com.apex.agent.ui.screen.glass

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apex.agent.ui.glass.ContrastMath
import com.apex.agent.ui.glass.GlassLadderReadout
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * ═══════════════════════════════════════════════════════════════
 *  Gate-0 运行时探针（P-4 可量化验收）+ 纹理化采样源（D-6）+ 读数台
 * ═══════════════════════════════════════════════════════════════
 *
 * D-8 的教训：验收清单里写死的 "PASS" 是自我介绍，不是验收。本文件提供
 * 五类**运行时探针**（全部实测、失败即红、不做静态谎言）：
 *
 *  1. 采样变化量 ΔE（[runDeltaEProbe]）：直接抓 [LayerBackdrop.graphicsLayer]
 *     —— 玻璃真正采样的那块 GraphicsLayer —— 在两个滚动相位各截一帧，
 *     逐像素算 CIE76 ΔE；均值低于阈值即判「BACKDROP NOT LIVE」。
 *     抓的就是采样的输入本身，比截图对比更强：采样源不动 = 玻璃必然死。
 *  2. 文字对比度（WCAG）：onSurface 对「霜面底色 合成 主题背景」的比值
 *     —— Frosted 档是可读性最劣情况（Backdrop 档透出的模糊内容只会更花），
 *     锁住最劣情况即锁住 NFR-6。
 *  3. 帧时间 P95（[runFrameProbe]）：驱动滚动动画期间用 Compose FrameClock
 *     实测帧间隔，取 P95 对比空闲基线（不劣于基线 = PASS）。
 *  4. 降级档（[GlassLadder.read]）：读真实 SDK / 采样源 / 短路态，显示
 *     实际命中档（PaleFlat / Frosted / Backdrop / Ambient）。
 *  5. 容器完整性（[runIntegrityProbe]）：Dock 输出层像素扫描 —— 底边
 *     硬边界计数（双底边 = DEF-1 回归）、顶缘镜面高光、侧缘受光。
 *
 * 附带 [TexturedSamplingColumn]（D-6 修复）：1dp 细网格 + 斜纹 + 彩色
 * 小方块阵列 + 等宽小字的采样源 —— 高频纹理在 blur 后仍可辨流动，
 * 「真采样」第一次变得肉眼可验、探针可量。
 */

// ═══════════════════════════════════════════════════════════════
//  探针读数枢纽 —— 各小节写入，验收清单读取（NOT RUN 也是诚实状态）
// ═══════════════════════════════════════════════════════════════

/**
 * 单项探针读数：pass 为 null 表示「未运行 / 不确定」—— 验收清单如实
 * 显示 NOT RUN，绝不折算成 PASS（D-8 纪律）。
 */
data class ProbeReading(
    val value: String,
    val pass: Boolean?,
    val note: String = ""
)

/** 探针结果中枢 —— 实验室各小节写入，[ChecklistSection] 读取显示。 */
class GlassProbeHub {
    /** 采样变化量 ΔE（均值；阈值 [DELTA_E_LIVE_THRESHOLD] 以上判活）。 */
    var deltaE by mutableStateOf<ProbeReading?>(null)

    /** 文字对比度（NIGHT / DAY 两栏各自的 WCAG 比值）。 */
    var contrastNight by mutableStateOf<ProbeReading?>(null)
    var contrastDay by mutableStateOf<ProbeReading?>(null)

    /** 帧时间 P95（滚动动画期 vs 空闲基线）。 */
    var frameP95 by mutableStateOf<ProbeReading?>(null)

    /** 降级档真实命中（API / 采样源 / 短路态 → 阶梯档）。 */
    var ladder by mutableStateOf<GlassLadderReadout?>(null)

    /** 容器完整性（双底边计数 + 顶缘镜面 + 侧缘受光）。 */
    var integrity by mutableStateOf<ProbeReading?>(null)

    /** 可交互遍历（8 个 nav 项逐个程序化点击，断言全部可达）。 */
    var interaction by mutableStateOf<ProbeReading?>(null)
}

@Composable
fun rememberGlassProbeHub(): GlassProbeHub = remember { GlassProbeHub() }

// ═══════════════════════════════════════════════════════════════
//  探针 1：采样变化量 ΔE —— 抓玻璃真正采样的 GraphicsLayer
// ═══════════════════════════════════════════════════════════════

/** ΔE 判活阈值：两个滚动相位间均值色差低于此值 → 采样源不在变化。 */
const val DELTA_E_LIVE_THRESHOLD = 3.0

/** 等一帧（Compose FrameClock —— 与运行帧同步，不占额外线程；跨小节共用）。 */
internal suspend fun awaitFrame() {
    withFrameNanos { }
}

/**
 * 截取采样源 GraphicsLayer 两帧（相位 A / 相位 B），算逐点 CIE76 ΔE 均值。
 * 主线程调用（GraphicsLayer 读回要求 UI 线程）；任何失败折叠为 null。
 */
suspend fun runDeltaEProbe(
    backdrop: LayerBackdrop,
    setScrollPhase: suspend (Float) -> Unit
): Double? = runCatching {
    setScrollPhase(0.12f)
    repeat(2) { awaitFrame() }
    val frameA = backdrop.graphicsLayer.toImageBitmap()
    setScrollPhase(0.68f)
    repeat(2) { awaitFrame() }
    val frameB = backdrop.graphicsLayer.toImageBitmap()
    meanDeltaE(frameA, frameB)
}.getOrNull()

/** 两帧位图的抽样 ΔE 均值（步进抽样，兼顾速度与覆盖）。 */
internal fun meanDeltaE(a: ImageBitmap, b: ImageBitmap, stride: Int = 32): Double {
    if (a.width != b.width || a.height != b.height) return 0.0
    val pa = a.toPixelMap()
    val pb = b.toPixelMap()
    var total = 0.0
    var count = 0
    var y = 0
    while (y < a.height) {
        var x = 0
        while (x < a.width) {
            total += ContrastMath.deltaE76(pa[x, y].toArgbLong(), pb[x, y].toArgbLong())
            count++
            x += stride
        }
        y += stride
    }
    return if (count == 0) 0.0 else total / count
}

/** Compose Color → 无符号 ARGB Long（ContrastMath 的入参形态）。 */
internal fun Color.toArgbLong(): Long = toArgb().toLong() and 0xFFFFFFFFL

// ═══════════════════════════════════════════════════════════════
//  探针 3：帧时间 P95 —— Compose FrameClock 实测
// ═══════════════════════════════════════════════════════════════

/** 帧预算上限（60fps × 2 的宽容档；P95 超过即判 FAIL）。 */
const val FRAME_BUDGET_MS = 33.0

/**
 * 帧时间采样：先测空闲基线（[idleMillis]），再驱动滚动动画测活跃帧，
 * 返回 (活跃P95, 空闲P95)。PASS 判据 = 活跃 P95 ≤ max(预算, 基线×1.4)。
 */
suspend fun runFrameProbe(
    idleMillis: Long = 400,
    activeMillis: Long = 1200,
    drive: suspend () -> Unit
): Pair<Double, Double>? = runCatching {
    suspend fun sample(durationMillis: Long): Double {
        val deltas = mutableListOf<Double>()
        val start = withFrameNanos { it }
        var last = -1L
        while (true) {
            val now = withFrameNanos { it }
            if (last > 0) deltas += (now - last) / 1_000_000.0
            last = now
            if (now - start >= durationMillis * 1_000_000L) break
        }
        return percentile95(deltas)
    }

    val idle = sample(idleMillis)
    coroutineScope {
        val driver = launch { drive() }
        val active = sample(activeMillis)
        driver.cancel()
        active to idle
    }
}.getOrNull()

/** P95（线性插值；样本不足时取最大值 —— 保守）。 */
internal fun percentile95(values: List<Double>): Double {
    if (values.isEmpty()) return 0.0
    val sorted = values.sorted()
    if (sorted.size < 20) return sorted.last()
    val idx = 0.95 * (sorted.size - 1)
    val lo = idx.toInt()
    val hi = minOf(lo + 1, sorted.size - 1)
    val frac = idx - lo
    return sorted[lo] * (1 - frac) + sorted[hi] * frac
}

// ═══════════════════════════════════════════════════════════════
//  探针 5：容器完整性 —— Dock 输出层像素扫描（DEF-1 双底边回归）
// ═══════════════════════════════════════════════════════════════

/** 容器完整性读数：底边硬边界数 / 顶缘镜面 Δ / 侧缘受光 Δ。 */
data class IntegrityReadout(
    val bottomTransitions: Int,
    val topSheenDelta: Double,
    val edgeLightDelta: Double
) {
    /** 双底边判定：底边 24px 带内硬边界 ≤ 2（材质渐变 + 玻璃边）为正常。 */
    val doubleEdge: Boolean get() = bottomTransitions > 2
}

/**
 * 扫描 Dock 输出层：x = 1/4 宽（避开中央发布位）的亮度剖面。
 *  - 底边带 [h−24px, h]：平滑后数硬跳变（> 0.06 luma）—— 双底边即红；
 *  - 顶缘带：y=6% 行与中心的亮度差（镜面高光存在的像素证据）；
 *  - 侧缘：x=3px 列与 x=30% 的亮度差（Highlight 受光的像素证据）。
 */
fun runIntegrityScan(layer: GraphicsLayer): IntegrityReadout? = runCatching {
    val bmp = layer.toImageBitmap()
    if (bmp.width < 40 || bmp.height < 40) return null
    val map = bmp.toPixelMap()
    fun lumaAt(x: Int, y: Int): Double =
        ContrastMath.relativeLuminance(map[x, y.coerceIn(0, bmp.height - 1)].toArgbLong())

    // 1) 底边带亮度剖面（3px 滑动平滑 + 硬跳变计数）
    val scanX = (bmp.width * 0.25f).toInt()
    val bandTop = maxOf(0, bmp.height - 24)
    val raw = (bandTop until bmp.height).map { lumaAt(scanX, it) }
    val smooth = raw.indices.map { i ->
        val a = raw[maxOf(0, i - 1)]
        val b = raw[i]
        val c = raw[minOf(raw.size - 1, i + 1)]
        (a + b + c) / 3.0
    }
    var transitions = 0
    for (i in 0 until smooth.size - 1) {
        if (abs(smooth[i + 1] - smooth[i]) > 0.06) transitions++
    }

    // 2) 顶缘镜面高光（y=6% vs 中心）
    val sheenY = (bmp.height * 0.06f).toInt().coerceAtLeast(1)
    val topSheen = lumaAt(scanX, sheenY) - lumaAt(scanX, bmp.height / 2)

    // 3) 侧缘受光（x=3px vs x=30%，y=中高）
    val midY = (bmp.height * 0.62f).toInt()
    val edgeLight = lumaAt(3, midY) - lumaAt((bmp.width * 0.30f).toInt(), midY)

    IntegrityReadout(transitions, topSheen, edgeLight)
}.getOrNull()

// ═══════════════════════════════════════════════════════════════
//  纹理化采样源（D-6 修复）—— 高频纹理让「真采样」肉眼可验
// ═══════════════════════════════════════════════════════════════

/** 采样行演示色板（8 色轮转 —— NIGHT 亮色 / DAY 加深变体在行内派生）。 */
private val LinerPalette = listOf(
    0xFFE5484D, 0xFFF5A524, 0xFF46A758, 0xFF00B8D9,
    0xFF3E63DD, 0xFF8E4EC6, 0xFFD6409F, 0xFF30A46C
)

/**
 * 纹理化采样列：NIGHT 暗底亮纹理 / DAY 白底暗纹理（D-5 —— 两个基底
 * 长得不一样，并排台差异可辨）。每行 = 细网格 + 45° 斜纹 + 彩色小方块
 * 阵列 + 等宽小字 —— blur 14dp 之后网格与斜纹仍以「纹理流动」可辨。
 */
@Composable
internal fun TexturedSamplingColumn(
    backdrop: LayerBackdrop,
    mode: GlassLabMode,
    modifier: Modifier = Modifier,
    scrollState: ScrollState? = null,
    rows: Int = 16
) {
    val night = mode == GlassLabMode.NIGHT
    Column(
        modifier = modifier
            .layerBackdrop(backdrop)
            .verticalScroll(scrollState ?: rememberScrollState())
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        repeat(rows) { i -> TexturedLinerRow(index = i, night = night) }
        // 底部留白：末行也能滚到玻璃下
        Spacer(modifier = Modifier.height(120.dp))
    }
}

/** 单行纹理采样卡：Canvas 三层纹理 + 两个等宽微字（drawWithCache 缓存）。 */
@Composable
private fun TexturedLinerRow(index: Int, night: Boolean) {
    val bg = if (night) Color(0xFF0D1117) else Color(0xFFFFFFFF)
    val line = if (night) Color(0x66FFFFFF) else Color(0x66000000)
    val hatch = if (night) Color(0x40FFFFFF) else Color(0x33000000)
    val text = MaterialTheme.colorScheme.onSurface.copy(alpha = if (night) 0.9f else 0.75f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .drawWithCache {
                    // drawWithCache：网格线段与斜纹端点按尺寸缓存，
                    // 滚动 / 重组帧零几何计算（D-6 的性能注脚）
                    val gridStep = 4.dp.toPx()
                    val hatchStep = 12.dp.toPx()
                    val square = 8.dp.toPx()
                    val palette = LinerPalette.map { c ->
                        val base = Color(c)
                        if (night) base else base.copy(alpha = 0.85f)
                    }
                    onDrawBehind {
                        // 1) 1dp 细网格（水平 + 垂直）
                        var gx = 0f
                        while (gx < size.width) {
                            drawLine(line, Offset(gx, 0f), Offset(gx, size.height), 1f)
                            gx += gridStep
                        }
                        var gy = 0f
                        while (gy < size.height) {
                            drawLine(line, Offset(0f, gy), Offset(size.width, gy), 1f)
                            gy += gridStep
                        }
                        // 2) 45° 斜纹（步进 12dp，穿过整行）
                        var hx = -size.height
                        while (hx < size.width) {
                            drawLine(
                                hatch,
                                Offset(hx, 0f),
                                Offset(hx + size.height, size.height),
                                2.5f
                            )
                            hx += hatchStep
                        }
                        // 3) 彩色小方块阵列（行索引轮转起始相位）
                        val cols = 7
                        val gapX = (size.width - square) / (cols - 1)
                        val phase = index % LinerPalette.size
                        for (c in 0 until cols) {
                            drawRect(
                                color = palette[(phase + c) % palette.size],
                                topLeft = Offset(c * gapX + square * 0.5f, size.height - square * 2f),
                                size = Size(square, square)
                            )
                        }
                    }
                }
        ) {}
        // 4) 等宽微字（真实 Text —— blur 后仍在玻璃内可辨流动）
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "ΔE ${index.toString().padStart(2, '0')}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = text
            )
            Text(
                text = "LIVE SAMPLING ${'A' + index % 26}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = text
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  读数台 —— 探针结果实时一行（P-4：顶部固定 ΔE / 对比度 / 帧时间）
// ═══════════════════════════════════════════════════════════════

/** 读数行：每项「名称 值 [PASS/FAIL/NOT RUN]」，等宽小字实验室风格。 */
@Composable
internal fun ProbeReadoutRow(hub: GlassProbeHub) {
    val scheme = MaterialTheme.colorScheme
    val mono = FontFamily.Monospace
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ReadoutLine("ΔE", hub.deltaE, mono)
        ReadoutLine("WCAG n", hub.contrastNight, mono)
        ReadoutLine("WCAG d", hub.contrastDay, mono)
        ReadoutLine("P95", hub.frameP95, mono)
        ReadoutLine("EDGE", hub.integrity, mono)
        ReadoutLine("TOUCH", hub.interaction, mono)
        hub.ladder?.let { ladder ->
            Text(
                text = "LADDER ${ladder.renderTier} · blur=${ladder.blurOn} lens=${ladder.lensOn}" +
                    (if (ladder.powerSave) " · POWER_SAVE" else "") +
                    (if (ladder.reducedMotion) " · REDUCED_MOTION" else ""),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = mono,
                color = scheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ReadoutLine(name: String, reading: ProbeReading?, mono: FontFamily) {
    val scheme = MaterialTheme.colorScheme
    val statusColor = when (reading?.pass) {
        true -> Color(0xFF22C55E)
        false -> scheme.error
        null -> scheme.onSurfaceVariant
    }
    Text(
        text = buildString {
            append(name.padEnd(6))
            append(reading?.value ?: "--")
            append("  ")
            append(
                when (reading?.pass) {
                    true -> "PASS"
                    false -> "FAIL"
                    null -> "NOT RUN"
                }
            )
            val note = reading?.note
            if (!note.isNullOrEmpty()) {
                append("  · ").append(note)
            }
        },
        style = MaterialTheme.typography.labelSmall,
        fontFamily = mono,
        color = if (reading?.pass == false) statusColor else scheme.onSurfaceVariant
    )
}

/** 帧时间 PASS 判据：活跃 P95 ≤ max(预算, 空闲基线 × 1.4)。 */
fun framePass(activeP95: Double, idleP95: Double): Boolean =
    activeP95 <= max(FRAME_BUDGET_MS, idleP95 * 1.4)

/** 对比度读数：比值 + PASS（≥ 4.5，NFR-6）。 */
fun contrastReading(ratio: Double): ProbeReading = ProbeReading(
    value = "%.2f:1".format(ratio),
    pass = ratio >= 4.5,
    note = "NFR-6 ≥ 4.5:1"
)

/** ΔE 读数：均值 + 判活（≥ [DELTA_E_LIVE_THRESHOLD]）。 */
fun deltaEReading(mean: Double): ProbeReading = ProbeReading(
    value = "%.1f".format(mean),
    pass = mean >= DELTA_E_LIVE_THRESHOLD,
    note = "≥ ${DELTA_E_LIVE_THRESHOLD.roundToInt()} live"
)
