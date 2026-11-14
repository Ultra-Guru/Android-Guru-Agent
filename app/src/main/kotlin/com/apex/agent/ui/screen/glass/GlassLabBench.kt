package com.apex.agent.ui.screen.glass

import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.ui.glass.ContrastMath
import com.apex.agent.ui.glass.GlassLadder
import com.apex.agent.ui.glass.GlassRecipe
import com.apex.agent.ui.glass.GlassRecipeBook
import com.apex.agent.ui.glass.GlassShapes
import com.apex.agent.ui.glass.GlassSurface
import com.apex.agent.ui.glass.glassPalette
import com.apex.agent.ui.glass.toGlassStyle
import com.apex.agent.ui.screen.code.CodeOutputMode
import com.apex.agent.ui.screen.code.CodeOutputStyle
import com.apex.agent.ui.screen.code.ConversationSkinSnapshot
import com.apex.agent.ui.screen.code.LocalConversationSkin
import com.apex.agent.ui.theme.PartSkinOverrides
import com.apex.agent.ui.theme.UiStyle
import com.apex.agent.ui.theme.ApexTheme
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.launch

/**
 * ═══════════════════════════════════════════════════════════════
 *  SideBySideBench —— 双态并排台（Gate-0 P-2 / G0-3 / D-10）
 * ═══════════════════════════════════════════════════════════════
 *
 * v1 的 NIGHT / DAY 是两个 tab 轮流看 —— 差异靠记忆对比。本台把两个
 * ApexTheme 并排放在同一屏：
 *
 * ```
 * ┌──────────────────────┬──────────────────────┐
 * │ NIGHT（强制深色）      │ DAY（强制浅色）        │
 * │ 同一 GlassRecipe       │ 同一 GlassRecipe       │
 * │ 纹理采样源（同步滚动）   │ 纹理采样源（同步滚动）   │
 * └──────────────────────┴──────────────────────┘
 *     参数滑杆（共用） · 导出配方 · 探针读数 · 会话快照预设
 * ```
 *
 *  - 差异只来自明暗（recipe 与滚动相位共用）—— 截图可逐像素对比；
 *  - **D-10 修复**：并排台显式 provide [LocalConversationSkin]（预置态
 *    LIQUID_GLASS / MINIMAL 双档）—— 切到 MINIMAL 时两栏同时经
 *    [com.apex.agent.ui.glass.GlassSkinGate] 短路为扁平表面，与生产
 *    GlassSurface 同一条判定路径（Gate0ParityTest 锁语义）；
 *  - 探针：ΔE（抓 NIGHT 栏采样层）、对比度（双栏 WCAG）、帧时间 P95
 *    （双栏滚动驱动）、降级档（真实 SDK / 省电 / 减少动效）。
 */
@Composable
internal fun SideBySideBenchSection(probeHub: GlassProbeHub) {
    SectionHeader(
        title = stringResource(R.string.glasslab_bench_title),
        hint = stringResource(R.string.glasslab_bench_hint)
    )
    var recipe by remember { mutableStateOf(GlassRecipeBook.Dock) }
    var presetMinimal by rememberSaveable { mutableStateOf(false) }
    var phase by rememberSaveable { mutableFloatStateOf(0.30f) }
    var showExport by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // ── D-10：会话快照预置态（生产 Coding 子树同款快照形态）──
    val conversationSkin = remember(presetMinimal) {
        ConversationSkinSnapshot(
            epoch = 0L,
            uiStyle = if (presetMinimal) UiStyle.MINIMAL else UiStyle.LIQUID_GLASS,
            outputStyle = CodeOutputStyle.TIMELINE,
            renderMode = CodeOutputMode.STREAMING,
            partSkins = PartSkinOverrides(),
            glow = 0.35f
        )
    }

    // NIGHT 栏采样层（ΔE 探针的输入）
    val nightBackdrop = rememberLayerBackdrop()
    val nightScroll = rememberScrollState()
    val dayScroll = rememberScrollState()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // ── 会话快照预设（D-10）：MINIMAL 档演示生产短路路径 ──
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !presetMinimal,
                onClick = { presetMinimal = false },
                label = { Text(stringResource(R.string.glasslab_bench_preset_liquid)) },
                modifier = Modifier.weight(1f)
            )
            FilterChip(
                selected = presetMinimal,
                onClick = { presetMinimal = true },
                label = { Text(stringResource(R.string.glasslab_bench_preset_minimal)) },
                modifier = Modifier.weight(1f)
            )
        }

        // ── 双栏并排（P-2）──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BenchPane(
                mode = GlassLabMode.NIGHT,
                recipe = recipe,
                phase = phase,
                conversationSkin = conversationSkin,
                backdrop = nightBackdrop,
                scrollState = nightScroll,
                onContrast = { probeHub.contrastNight = it },
                modifier = Modifier.weight(1f)
            )
            BenchPane(
                mode = GlassLabMode.DAY,
                recipe = recipe,
                phase = phase,
                conversationSkin = conversationSkin,
                backdrop = rememberLayerBackdrop(),
                scrollState = dayScroll,
                onContrast = { probeHub.contrastDay = it },
                modifier = Modifier.weight(1f)
            )
        }

        // ── 同步滚动相位滑杆（两栏共用一个相位，差异只来自明暗）──
        Text(
            text = stringResource(R.string.glasslab_bench_phase, (phase * 100).toInt()),
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace
        )
        Slider(
            value = phase,
            onValueChange = { phase = it },
            valueRange = 0f..1f
        )

        // ── 探针触发行 + 读数（P-4 顶部固定读数）──
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TextButton(onClick = {
                scope.launch {
                    val mean = runDeltaEProbe(nightBackdrop) { p -> phase = p }
                    probeHub.deltaE = mean?.let { deltaEReading(it) }
                        ?: ProbeReading("n/a", null, "readback unavailable")
                }
            }) {
                Text(stringResource(R.string.glasslab_bench_probe_deltae))
            }
            TextButton(onClick = {
                scope.launch {
                    val result = runFrameProbe {
                        // 双栏交替滚动驱动（活跃帧 = 滚动 + 玻璃管线）
                        repeat(3) {
                            nightScroll.animateScrollTo((nightScroll.maxValue * 0.9f).toInt())
                            dayScroll.animateScrollTo((dayScroll.maxValue * 0.9f).toInt())
                            nightScroll.animateScrollTo(0)
                            dayScroll.animateScrollTo(0)
                        }
                    }
                    probeHub.frameP95 = result?.let { (active, idle) ->
                        ProbeReading(
                            value = "%.1f/%.1fms".format(active, idle),
                            pass = framePass(active, idle),
                            note = "active/idle P95"
                        )
                    } ?: ProbeReading("n/a", null, "clock unavailable")
                }
            }) {
                Text(stringResource(R.string.glasslab_bench_probe_frame))
            }
            TextButton(onClick = { showExport = true }) {
                Text(stringResource(R.string.glasslab_bench_export))
            }
        }
        ProbeReadoutRow(hub = probeHub)

        // ── 参数滑杆（P-3：两栏共用同一 recipe 实例，实时联动）──
        RecipeParamPanel(recipe = recipe, onChange = { recipe = it })
    }

    if (showExport) {
        RecipeExportDialog(recipe = recipe, onDismiss = { showExport = false })
    }
}

/**
 * 单栏：独立 ApexTheme（强制明 / 暗）+ 纹理采样源（相位同步）+ 配方驱动的
 * 玻璃样本。对比度探针在本栏组合期计算（Frosted 最劣情况 WCAG）。
 */
@Composable
private fun BenchPane(
    mode: GlassLabMode,
    recipe: GlassRecipe,
    phase: Float,
    conversationSkin: ConversationSkinSnapshot,
    backdrop: LayerBackdrop,
    scrollState: ScrollState,
    onContrast: (ProbeReading) -> Unit,
    modifier: Modifier = Modifier
) {
    val night = mode == GlassLabMode.NIGHT
    // 相位同步：两栏共享 phase，LaunchedEffect 按 maxValue 换算为像素偏移
    LaunchedEffect(phase, scrollState.maxValue) {
        if (scrollState.maxValue > 0) {
            scrollState.scrollTo((scrollState.maxValue * phase).toInt())
        }
    }
    ApexTheme(darkTheme = night, uiStyle = UiStyle.LIQUID_GLASS) {
        // D-10：显式 provide 会话快照 —— 与生产 Coding 子树同路径
        CompositionLocalProvider(LocalConversationSkin provides conversationSkin) {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .clip(GlassShapes.card)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        shape = GlassShapes.card
                    )
            ) {
                TexturedSamplingColumn(
                    backdrop = backdrop,
                    mode = mode,
                    modifier = Modifier.fillMaxSize(),
                    scrollState = scrollState,
                    rows = 12
                )
                // 配方样本：滑杆实时驱动（corner 也随配方走）
                GlassSurface(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 14.dp)
                        .fillMaxWidth()
                        .height(88.dp),
                    backdrop = backdrop,
                    style = recipe.toGlassStyle(),
                    shape = RoundedCornerShape(recipe.cornerDp.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = recipe.id,
                            style = MaterialTheme.typography.labelLarge,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "blur ${recipe.blurRadiusDp.toInt()}dp · tint ${"%.2f".format(recipe.tintAlpha)}" +
                                " · lens ${recipe.lensHeightDp.toInt()}/${recipe.lensAmountDp.toInt()}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    text = stringResource(
                        if (night) R.string.glasslab_bench_pane_night
                        else R.string.glasslab_bench_pane_day
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                )
            }
        }
        // 对比度探针（Frosted 最劣情况）：本栏主题色实时合成
        val palette = glassPalette(style = recipe.toGlassStyle(), accent = Color.Unspecified)
        val scheme = MaterialTheme.colorScheme
        val composite = ContrastMath.compositeOver(
            palette.frostBase.toArgbLong(),
            scheme.background.toArgbLong()
        )
        val ratio = ContrastMath.contrastRatio(scheme.onSurface.toArgbLong(), composite)
        LaunchedEffect(ratio) { onContrast(contrastReading(ratio)) }
    }
}

/**
 * 降级档探针（P-4）：真实采集 SDK / 省电 / 减少动效（一次性，NFR-14
 * 采样纪律），写入 hub 供读数台与验收清单显示。
 */
@Composable
internal fun LadderProbeEffect(probeHub: GlassProbeHub) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        val powerSave = runCatching {
            (context.getSystemService(android.content.Context.POWER_SERVICE) as? PowerManager)
                ?.isPowerSaveMode
        }.getOrNull() ?: false
        val reducedMotion = runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f
            ) == 0f
        }.getOrNull() ?: false
        probeHub.ladder = GlassLadder.read(
            apiLevel = Build.VERSION.SDK_INT,
            hasBackdrop = true,
            conversationUiStyleKey = null,
            globalUiStyleKey = UiStyle.LIQUID_GLASS.key,
            powerSave = powerSave,
            reducedMotion = reducedMotion
        )
    }
}
