package com.apex.agent.ui.screen.playground

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apex.agent.ui.glass.GlassButton
import kotlin.math.sin

/**
 * ═══════════════════════════════════════════════════════════════
 *  GlyphStormGame —— 《字符风暴》街机柜（Canvas + 引擎 + HUD + 遮罩）
 * ═══════════════════════════════════════════════════════════════
 *
 * 自包含的游戏整机：HUD 读数条 / 竞技场 Canvas / 阶段遮罩 / 控制排。
 * 帧循环：LaunchedEffect + withFrameNanos —— 离开组合（切抽屉页）即
 * 停帧，引擎自然冻结 = 免费自动暂停；回到页面恢复时 dt 已钳制，物理
 * 不会跳变。本屏与玻璃实验室 / 关于页 Hero 同属全应用少数允许连续
 * 动画的屏幕（游戏本体，其余业务界面仍禁循环动画）。
 *
 * pretext 实测通道：[rememberTextMeasurer] 对每个字符预排版一次，
 * 实测宽度表喂给引擎当物理半径 —— 与 chenglou/pretext 在 Web 上
 * measureNaturalWidth 的角色完全对位。等宽近似模式下宽度表换成
 * 0.6em 常量（老式终端思维），物理手感立即变笨 —— 彩蛋即论点。
 */
@Composable
internal fun GlyphStormGame(
    /** true = 等宽近似；false = pretext 实测。切换即重开一局。 */
    monoMode: Boolean,
    onGameOver: (score: Int, bestCombo: Int, wordsCleared: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val pxPerDp = density.density
    val haptic = LocalHapticFeedback.current
    val textMeasurer = rememberTextMeasurer()
    val scheme = MaterialTheme.colorScheme

    // ═══ pretext 测量：字符集逐个预排版（测量一次，绘制期零分配）═══
    val glyphStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold
    )
    val charset = remember {
        (GlyphStormEngine.WORDS.flatMap { it.toList() } +
            GlyphStormEngine.DEFAULT_DISTRACTORS).distinct()
    }
    val layouts: Map<Char, TextLayoutResult> = remember(glyphStyle, charset, textMeasurer) {
        charset.associateWith { textMeasurer.measure(text = it.toString(), style = glyphStyle) }
    }
    val glyphHeight = remember(layouts, pxPerDp) {
        layouts.values.firstOrNull()?.size?.height?.toFloat() ?: 28f * pxPerDp
    }
    // 等宽近似：0.6em 假宽度（拉丁等宽终端的平均口径）—— sp→px 含 fontScale
    val monoWidth = remember(density) { with(density) { 22.sp.toPx() } * 0.60f }

    // ═══ 引擎：测量模式切换即重建（宽度表是构造参数）═══
    val engine = remember(monoMode, pxPerDp) {
        val widthProvider: (Char) -> Float = if (monoMode) {
            { _ -> monoWidth }
        } else {
            { c -> layouts[c]?.size?.width?.toFloat() ?: monoWidth }
        }
        GlyphStormEngine(
            words = GlyphStormEngine.WORDS,
            glyphWidth = widthProvider,
            pxPerDp = pxPerDp
        )
    }

    // ═══ 帧循环 + HUD 镜像 ═══
    var frameTick by remember { mutableStateOf(0) }
    var hud by remember { mutableStateOf(StormHud()) }
    var overReported by remember { mutableStateOf(false) }

    LaunchedEffect(engine) {
        overReported = false // 新引擎 = 新一局，回报闸门重置
        var last = -1L
        while (true) {
            withFrameNanos { now ->
                if (last < 0L) last = now
                val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, 0.05f)
                last = now
                engine.tick(dt)
                frameTick++
                hud = StormHud(
                    phase = engine.phase,
                    score = engine.score,
                    combo = engine.combo,
                    lives = engine.lives,
                    wordsCleared = engine.wordsCleared
                )
                // 触觉反馈即「音效」：无音频权限负担，震动即反馈
                if (engine.events.isNotEmpty()) {
                    repeat(engine.events.size) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                    engine.events.clear()
                }
                if (engine.phase == GlyphStormEngine.Phase.OVER && !overReported) {
                    overReported = true
                    onGameOver(engine.score, engine.bestCombo, engine.wordsCleared)
                }
            }
        }
    }

    // ═══ 竞技场布局（尺寸就绪 / 模式切换后重铺）═══
    var arenaSize by remember { mutableStateOf(Size.Zero) }
    LaunchedEffect(engine, arenaSize) {
        if (arenaSize != Size.Zero) {
            engine.layout(arenaSize)
            engine.warmUp()
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        StormHudBar(hud = hud)
        Box(modifier = Modifier.fillMaxWidth().height(ArenaHeight)) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { arenaSize = Size(it.width.toFloat(), it.height.toFloat()) }
                    .pointerInput(engine) {
                        detectDragGestures(
                            onDragStart = { engine.attractor = it },
                            onDragEnd = { engine.attractor = null },
                            onDragCancel = { engine.attractor = null },
                            onDrag = { change, _ -> engine.attractor = change.position }
                        )
                    }
                    .pointerInput(engine) {
                        detectTapGestures { tap -> engine.squashAt(tap) }
                    }
            ) {
                frameTick // 读一帧状态 → 每帧失效重绘
                drawStorm(engine, layouts, glyphHeight, monoMode, scheme)
            }
            StormOverlay(
                hud = hud,
                onStart = { engine.start() },
                onResume = { engine.resume() },
                onRestart = {
                    overReported = false
                    engine.start()
                }
            )
        }
        StormControls(hud = hud, onStart = { engine.start() }, onPause = { engine.pause() })
    }
}

// ═══════════════════════════════════════════════════════════════
//  HUD
// ═══════════════════════════════════════════════════════════════

private val ArenaHeight = 460.dp

/** HUD 快照（结构性相等：数值未变不触发重组）。 */
private data class StormHud(
    val phase: GlyphStormEngine.Phase = GlyphStormEngine.Phase.READY,
    val score: Int = 0,
    val combo: Int = 0,
    val lives: Int = GlyphStormEngine.START_LIVES,
    val wordsCleared: Int = 0
)

@Composable
private fun StormHudBar(hud: StormHud) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HudChip(label = "SCORE", value = hud.score.toString(), accent = true)
        HudChip(label = "COMBO", value = "×${hud.combo}")
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            repeat(GlyphStormEngine.START_LIVES) { i ->
                Icon(
                    imageVector = if (i < hud.lives) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = null,
                    tint = if (i < hud.lives) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        HudChip(label = "WORDS", value = hud.wordsCleared.toString())
    }
}

@Composable
private fun HudChip(label: String, value: String, accent: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                value,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (accent) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  遮罩（READY / PAUSED / OVER）
// ═══════════════════════════════════════════════════════════════

@Composable
private fun StormOverlay(
    hud: StormHud,
    onStart: () -> Unit,
    onResume: () -> Unit,
    onRestart: () -> Unit
) {
    when (hud.phase) {
        GlyphStormEngine.Phase.READY -> OverlayPanel {
            Text(
                "字符风暴 · GLYPH STORM",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "按住屏幕拖动 = 引力井，把发光的目标字符吸进中央环；\n" +
                    "点按虫子可以拍死；虫子咬到环会掉一颗心。拼满整词大额加分。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            GlassButton(text = "开始游戏", onClick = onStart, leadingIcon = Icons.Default.PlayArrow)
        }
        GlyphStormEngine.Phase.PAUSED -> OverlayPanel {
            Text(
                "已暂停",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassButton(text = "继续", onClick = onResume, leadingIcon = Icons.Default.PlayArrow)
                GlassButton(text = "重新开始", onClick = onRestart, leadingIcon = Icons.Default.Refresh)
            }
        }
        GlyphStormEngine.Phase.OVER -> OverlayPanel {
            Text(
                "游戏结束",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error
            )
            Text(
                "本局 ${hud.score} 分 · 清词 ${hud.wordsCleared} 个",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            GlassButton(text = "再来一局", onClick = onRestart, leadingIcon = Icons.Default.Refresh)
        }
        GlyphStormEngine.Phase.RUNNING -> Unit
    }
}

@Composable
private fun OverlayPanel(content: @Composable ColumnScope.() -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                content = content
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  控制排
// ═══════════════════════════════════════════════════════════════

@Composable
private fun StormControls(hud: StormHud, onStart: () -> Unit, onPause: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (hud.phase) {
            GlyphStormEngine.Phase.READY -> GlassButton(
                text = "开始", onClick = onStart, leadingIcon = Icons.Default.PlayArrow
            )
            GlyphStormEngine.Phase.RUNNING -> GlassButton(
                text = "暂停", onClick = onPause, leadingIcon = Icons.Default.Pause
            )
            GlyphStormEngine.Phase.PAUSED -> Unit
            GlyphStormEngine.Phase.OVER -> Unit
        }
        Text(
            text = when (hud.phase) {
                GlyphStormEngine.Phase.RUNNING -> "引力井随手指移动 —— 拖住不放"
                GlyphStormEngine.Phase.PAUSED -> "已暂停 —— 环与字符停在原地"
                else -> "PRETEXT 实测宽度驱动物理 —— 每个字符都是真实排版尺寸"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ═══════════════════════════════════════════════════════════════
//  绘制
// ═══════════════════════════════════════════════════════════════

private fun DrawScope.drawStorm(
    engine: GlyphStormEngine,
    layouts: Map<Char, TextLayoutResult>,
    glyphHeight: Float,
    monoMode: Boolean,
    scheme: ColorScheme
) {
    val w = size.width
    val h = size.height

    // 1. 背景三段渐变 + 双光晕 + 细网格（与抽屉氛围背景同一语言）
    drawRect(
        Brush.verticalGradient(
            0f to scheme.surfaceContainerLowest,
            0.55f to scheme.background,
            1f to scheme.primaryContainer.copy(alpha = 0.30f)
        )
    )
    drawCircle(
        Brush.radialGradient(
            colors = listOf(scheme.primary.copy(alpha = 0.10f), Color.Transparent),
            center = Offset(w * 0.85f, h * 0.12f), radius = w * 0.9f
        ),
        radius = w * 0.9f, center = Offset(w * 0.85f, h * 0.12f)
    )
    drawCircle(
        Brush.radialGradient(
            colors = listOf(scheme.tertiary.copy(alpha = 0.07f), Color.Transparent),
            center = Offset(w * 0.10f, h * 0.92f), radius = w * 0.8f
        ),
        radius = w * 0.8f, center = Offset(w * 0.10f, h * 0.92f)
    )
    val gridColor = scheme.onSurfaceVariant.copy(alpha = 0.06f)
    val cell = 24.dp.toPx()
    var gx = 0f
    while (gx < w) { drawLine(gridColor, Offset(gx, 0f), Offset(gx, h), 1f); gx += cell }
    var gy = 0f
    while (gy < h) { drawLine(gridColor, Offset(0f, gy), Offset(w, gy), 1f); gy += cell }

    // 2. 咬环震屏（衰减正弦横移）+ 全部实体
    val shake = if (engine.shakeT > 0f) {
        sin(engine.shakeT * 42f) * 10.dp.toPx() * engine.shakeT
    } else 0f
    translate(left = shake) {
        drawRing(engine, layouts, glyphHeight, scheme)
        drawGlyphs(engine, layouts, monoMode, scheme)
        drawAttractor(engine, scheme)
        drawBugs(engine, scheme)
        drawParticles(engine, scheme)
    }
}

private fun DrawScope.drawRing(
    engine: GlyphStormEngine,
    layouts: Map<Char, TextLayoutResult>,
    glyphHeight: Float,
    scheme: ColorScheme
) {
    val c = engine.ringCenter
    val r = engine.ringRadius
    if (r <= 0f) return
    // 光晕
    drawCircle(
        Brush.radialGradient(
            colors = listOf(scheme.primary.copy(alpha = 0.16f), Color.Transparent),
            center = c, radius = r * 1.2f
        ),
        radius = r * 1.2f, center = c
    )
    // 旋转虚线环（悠闲时钟驱动 —— 画面活着）
    drawCircle(
        color = scheme.primary.copy(alpha = 0.55f),
        radius = r,
        center = c,
        style = Stroke(
            width = 2.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(
                floatArrayOf(14f, 10f), phase = engine.clock * 26f
            )
        )
    )
    // 卡槽排：pretext 宽度直出 —— 每槽宽 = 该字符实测宽 + 内边距
    val slotH = glyphHeight + 14.dp.toPx()
    val word = GlyphStormEngine.WORDS[engine.wordIndex % GlyphStormEngine.WORDS.size]
    for (i in engine.slotXs.indices) {
        val x = engine.slotXs[i]
        val sw = engine.slotWidths[i]
        val top = c.y - slotH * 0.5f
        if (engine.slots.getOrNull(i) != null) {
            drawRoundRect(
                color = scheme.primary.copy(alpha = 0.85f),
                topLeft = Offset(x, top),
                size = Size(sw, slotH),
                cornerRadius = CornerRadius(8.dp.toPx())
            )
            layouts[word[i]]?.let { layout ->
                drawText(
                    layout,
                    color = scheme.onPrimary,
                    topLeft = Offset(
                        x + sw * 0.5f - layout.size.width * 0.5f,
                        c.y - layout.size.height * 0.5f
                    )
                )
            }
        } else {
            drawRoundRect(
                color = scheme.primary.copy(alpha = 0.07f),
                topLeft = Offset(x, top),
                size = Size(sw, slotH),
                cornerRadius = CornerRadius(8.dp.toPx())
            )
            drawRoundRect(
                color = scheme.primary.copy(alpha = 0.40f),
                topLeft = Offset(x, top),
                size = Size(sw, slotH),
                cornerRadius = CornerRadius(8.dp.toPx()),
                style = Stroke(width = 1.5.dp.toPx())
            )
            // 空槽幽灵字 —— 告诉玩家还缺什么
            layouts[word[i]]?.let { layout ->
                drawText(
                    layout,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.30f),
                    topLeft = Offset(
                        x + sw * 0.5f - layout.size.width * 0.5f,
                        c.y - layout.size.height * 0.5f
                    )
                )
            }
        }
    }
}

private fun DrawScope.drawGlyphs(
    engine: GlyphStormEngine,
    layouts: Map<Char, TextLayoutResult>,
    monoMode: Boolean,
    scheme: ColorScheme
) {
    for (g in engine.glyphs) {
        val layout = layouts[g.char] ?: continue
        // 目标字符（还有空槽）→ 主色 + 光斑；其余暗淡漂浮
        val isTarget = hasEmptySlotFor(engine, g.char)
        if (isTarget) {
            drawCircle(
                color = scheme.primary.copy(alpha = 0.22f),
                radius = layout.size.width * 0.85f,
                center = Offset(g.x, g.y)
            )
        }
        drawText(
            layout,
            color = if (isTarget) scheme.primary else scheme.onSurfaceVariant.copy(alpha = 0.75f),
            topLeft = Offset(g.x - layout.size.width * 0.5f, g.y - layout.size.height * 0.5f)
        )
        if (monoMode && isTarget) {
            // 等宽模式：虚框 = 假宽度 —— 直观暴露等宽思维与实测的差距
            val monoHalf = 6.6.dp.toPx()
            drawRect(
                color = scheme.tertiary.copy(alpha = 0.35f),
                topLeft = Offset(g.x - monoHalf, g.y - layout.size.height * 0.5f),
                size = Size(monoHalf * 2f, layout.size.height.toFloat()),
                style = Stroke(width = 1.dp.toPx())
            )
        }
    }
}

/** 该字符在当前词里是否还有空槽可填（目标字符判定）。 */
private fun hasEmptySlotFor(engine: GlyphStormEngine, ch: Char): Boolean {
    val word = GlyphStormEngine.WORDS[engine.wordIndex % GlyphStormEngine.WORDS.size]
    for (i in engine.slots.indices) {
        if (engine.slots[i] == null && word[i] == ch) return true
    }
    return false
}

private fun DrawScope.drawAttractor(engine: GlyphStormEngine, scheme: ColorScheme) {
    val a = engine.attractor ?: return
    val pulse = 1f + sin(engine.clock * 5f) * 0.12f
    drawCircle(
        color = scheme.primary.copy(alpha = 0.35f),
        radius = 30.dp.toPx() * pulse,
        center = a,
        style = Stroke(width = 2.dp.toPx())
    )
    drawCircle(
        color = scheme.primary.copy(alpha = 0.55f),
        radius = 5.dp.toPx(),
        center = a
    )
}

/** 虫子：矢量绘制（身体两节 + 四腿 + 触角），无 emoji 代理对测量问题。 */
private fun DrawScope.drawBugs(engine: GlyphStormEngine, scheme: ColorScheme) {
    for (bug in engine.bugs) {
        val r = 9.dp.toPx()
        val bodyColor = scheme.error
        if (bug.dead) {
            val t = bug.squashT
            scale(
                scaleX = 1f + t * 0.5f,
                scaleY = (1f - t).coerceAtLeast(0.08f),
                pivot = Offset(bug.x, bug.y)
            ) {
                drawBugBody(bug.x, bug.y, r, bodyColor.copy(alpha = 1f - t), scheme)
            }
        } else {
            val wobble = sin(engine.clock * 9f + bug.x * 0.05f)
            drawBugBody(bug.x, bug.y, r, bodyColor, scheme, wobble)
        }
    }
}

private fun DrawScope.drawBugBody(
    x: Float,
    y: Float,
    r: Float,
    bodyColor: Color,
    scheme: ColorScheme,
    wobble: Float = 0f
) {
    val legColor = scheme.onError.copy(alpha = 0.85f)
    val legLen = r * 1.6f
    // 四条腿：左右各两条，摆动相位交错
    for (side in intArrayOf(-1, 1)) {
        for (k in 0 until 2) {
            val ay = y + (k - 0.5f) * r
            val swing = wobble * r * 0.25f * (if (k == 0) 1f else -1f)
            drawLine(
                legColor,
                Offset(x + side * r * 0.8f, ay),
                Offset(x + side * legLen, ay + r * 0.5f + swing),
                strokeWidth = 2f
            )
        }
    }
    // 腹部 + 头（两节身体）
    drawCircle(bodyColor, r, Offset(x, y))
    drawCircle(bodyColor, r * 0.55f, Offset(x + r * 0.85f, y - r * 0.15f))
    // 触角
    drawLine(
        legColor,
        Offset(x + r * 1.0f, y - r * 0.4f),
        Offset(x + r * 1.5f, y - r * 0.9f),
        strokeWidth = 2f
    )
}

private fun DrawScope.drawParticles(engine: GlyphStormEngine, scheme: ColorScheme) {
    for (p in engine.particles) {
        val alpha = (p.life / p.maxLife).coerceIn(0f, 1f)
        val color = when (p.hue) {
            0 -> scheme.primary
            1 -> scheme.tertiary
            else -> scheme.error
        }
        drawCircle(
            color = color.copy(alpha = alpha * 0.85f),
            radius = 3.dp.toPx(),
            center = Offset(p.x, p.y)
        )
    }
}
