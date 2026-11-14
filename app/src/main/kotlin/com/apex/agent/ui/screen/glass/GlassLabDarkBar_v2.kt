package com.apex.agent.ui.screen.glass

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.toIntSize
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.ui.glass.GlassRecipe
import com.apex.agent.ui.glass.GlassRecipeBook
import com.apex.agent.ui.glass.GlassShapes
import com.apex.agent.ui.glass.GlassSurface
import com.apex.agent.ui.glass.glassClickable
import com.apex.agent.ui.glass.toGlassStyle
import com.apex.agent.ui.theme.ApexTheme
import com.apex.agent.ui.theme.UiStyle
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.launch
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.background

/**
 * ═══════════════════════════════════════════════════════════════
 *  Dark 栏 v2 —— 酷安式深色 Dock · Gate-0 D-1～D-7 修复版
 * ═══════════════════════════════════════════════════════════════
 *
 * v1（GlassLabDarkBar.kt，已随本 PR 退役）的三个硬 bug + 四个材质问题的
 * 修法全部落在这里；布局数学收敛在 [GlassDockMath]（纯函数，
 * GlassLabDarkBarLayoutTest 锁语义）：
 *
 *  - **D-1 按钮凸出**：结构改为「外层不裁剪 Box → 玻璃底
 *    GlassSurface(matchParentSize) → 内容层 → 发布按钮（外层 TopCenter
 *    − 18dp）」——按钮与阴影都在 GlassSurface 的裁剪层之外，凸出栏顶
 *    的实心圆第一次真实可见；
 *  - **D-2 五槽模型**：每页 `nav | nav | 发布(76dp) | nav | nav`——发布
 *    位独占中央槽（Spacer 留白），8 个导航项全部可点；
 *  - **D-3 页内药丸**：药丸是每页独立的一枚，只做页内槽位弹簧；跨页
 *    移动交给 Pager 原生手势（药丸随所在页整体平移）—— 滑到第 2 页
 *    药丸永远在场，不再飞出屏；
 *  - **D-4 Dock 专用配方**：GlassRecipeBook.Dock（blur 14 / tint 0.42 /
 *    lens 0 / 色散关 / 2dp 檐口 / corner 34），并排可切 Card / Floating /
 *    Pale 四档对照「为什么 Dock 不能用 FAB 档」；
 *  - **D-5 随模式换底**：[mode] 参数驱动采样源明暗（NIGHT 暗底亮纹理 /
 *    DAY 白底暗纹理），并排台两个基底长得不一样；
 *  - **D-6 纹理化采样源**：[TexturedSamplingColumn]（细网格 + 斜纹 +
 *    彩色小方块 + 等宽微字）—— blur 后纹理流动可辨，ΔE 探针可量；
 *  - **D-7 全资源化**：标签 / 标题 / 提示全走 stringResource（values 与
 *    values-zh 双份，i18n 镜像门禁可过）。
 *
 * 探针接线（P-6 可交互 + 容器完整性）：「遍历 8 项」程序化逐项点击并断言
 * 全部可达（写 [GlassProbeHub.interaction]）；「容器完整性扫描」对 Dock
 * 输出层做像素扫描（写 [GlassProbeHub.integrity]，DEF-1 双底边回归）。
 */

/** Dock 内容行高（导航行）。 */
private val NavRowHeight = 64.dp

/** v2 导航项 —— label 资源化（D-7）。 */
private data class DockDestV2(val labelRes: Int, val icon: ImageVector)

private val DockDestsV2 = listOf(
    DockDestV2(R.string.glasslab_dest_home, Icons.Default.Home),
    DockDestV2(R.string.glasslab_dest_digital, Icons.Default.Devices),
    DockDestV2(R.string.glasslab_dest_explore, Icons.Default.Explore),
    DockDestV2(R.string.glasslab_dest_mine, Icons.Default.Person),
    DockDestV2(R.string.glasslab_dest_video, Icons.Default.Videocam),
    DockDestV2(R.string.glasslab_dest_game, Icons.Default.SportsEsports),
    DockDestV2(R.string.glasslab_dest_music, Icons.Default.MusicNote),
    DockDestV2(R.string.glasslab_dest_settings, Icons.Default.Settings)
)

/** 实验室四档对照（D-4）：配方 id → 显示名资源。 */
private val BenchTierIds = listOf("dock", "card", "floating", "pale")
private val BenchTierLabels = listOf(
    R.string.glasslab_darkbar_chip_dock,
    R.string.glasslab_darkbar_chip_card,
    R.string.glasslab_darkbar_chip_floating,
    R.string.glasslab_darkbar_chip_pale
)

/**
 * Dark 栏小节 v2 —— 两个实验室共用；[mode] 决定采样源明暗（D-5）。
 */
@Composable
internal fun DarkBarSectionV2(mode: GlassLabMode, probeHub: GlassProbeHub) {
    SectionHeader(
        title = stringResource(R.string.glasslab_darkbar_title),
        hint = stringResource(R.string.glasslab_darkbar_hint)
    )
    var sel0 by rememberSaveable { mutableIntStateOf(0) }
    var sel1 by rememberSaveable { mutableIntStateOf(0) }
    var recipeId by rememberSaveable { mutableStateOf(GlassRecipeBook.Dock.id) }
    val recipe = GlassRecipeBook.byId(recipeId) ?: GlassRecipeBook.Dock
    val backdrop = rememberLayerBackdrop()
    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { GlassDockMath.PAGES }
    )
    val scope = rememberCoroutineScope()
    val dockLayer = rememberGraphicsLayer()
    // 探针注记资源在组合期解析（协程内不可调 stringResource）
    val traverseNote = stringResource(R.string.glasslab_darkbar_probe_traverse)
    val scanDoubleNote = stringResource(R.string.glasslab_darkbar_scan_double)
    val scanSingleNote = stringResource(R.string.glasslab_darkbar_scan_single)
    val scanUnavailableNote = stringResource(R.string.glasslab_darkbar_scan_unavailable)

    val handleSelect: (Int) -> Unit = { global ->
        val page = GlassDockMath.pageOf(global)
        val inPage = GlassDockMath.indexInPage(global)
        if (page == 0) sel0 = inPage else sel1 = inPage
        if (pagerState.currentPage != page) {
            scope.launch { pagerState.animateScrollToPage(page) }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // ── D-4 配方对照 chips：Dock 专用配方 vs 三档既有配方 ──
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BenchTierIds.forEachIndexed { i, id ->
                FilterChip(
                    selected = recipeId == id,
                    onClick = { recipeId = id },
                    label = { Text(stringResource(BenchTierLabels[i])) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // ── 采样区：纹理化滚动源 + 深色 Dock ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(340.dp)
                .clip(GlassShapes.card)
        ) {
            TexturedSamplingColumn(
                backdrop = backdrop,
                mode = mode,
                modifier = Modifier.fillMaxSize()
            )
            // D-1 外层不裁剪 Box：记录层包住 Dock（探针 5 的像素输入）
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp)
                    .drawWithContent {
                        dockLayer.record(size.toIntSize()) { this@drawWithContent.drawContent() }
                        drawContent()
                    }
            ) {
                DarkGlassDockV2(
                    backdrop = backdrop,
                    recipe = recipe,
                    pagerState = pagerState,
                    selectedInPage = listOf(sel0, sel1),
                    onSelect = handleSelect,
                    modifier = Modifier.fillMaxWidth(0.94f)
                )
            }
            // 诚实标注（D-7 资源化）
            Text(
                text = stringResource(R.string.glasslab_darkbar_caption),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }

        // ── 探针行：遍历可达（G0-1）+ 容器完整性（G0-2 / DEF-1 回归）──
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TextButton(onClick = {
                scope.launch {
                    var reached = 0
                    val total = GlassDockMath.PAGES * GlassDockMath.NAV_PER_PAGE
                    for (g in 0 until total) {
                        pagerState.scrollToPage(GlassDockMath.pageOf(g))
                        handleSelect(g)
                        repeat(2) { awaitFrame() }
                        val projected = GlassDockMath.selectionAfterSettle(
                            pagerState.currentPage, listOf(sel0, sel1)
                        )
                        if (projected == g) reached++
                    }
                    probeHub.interaction = ProbeReading(
                        value = "$reached/$total",
                        pass = reached == total,
                        note = traverseNote
                    )
                }
            }) {
                Text(stringResource(R.string.glasslab_darkbar_probe_traverse))
            }
            TextButton(onClick = {
                scope.launch {
                    repeat(2) { awaitFrame() }
                    val readout = runIntegrityScan(dockLayer)
                    probeHub.integrity = readout?.let {
                        ProbeReading(
                            value = "edges=${it.bottomTransitions} sheen=%.2f".format(it.topSheenDelta),
                            pass = !it.doubleEdge,
                            note = if (it.doubleEdge) scanDoubleNote else scanSingleNote
                        )
                    } ?: ProbeReading(
                        value = "n/a",
                        pass = null,
                        note = scanUnavailableNote
                    )
                }
            }) {
                Text(stringResource(R.string.glasslab_darkbar_probe_scan))
            }
            // 读数（紧凑行内显示，避免重复占位）
            probeHub.interaction?.let {
                Text(
                    text = "TOUCH ${it.value}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (it.pass == false) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            probeHub.integrity?.let {
                Text(
                    text = "EDGE ${it.value}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (it.pass == false) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 深色玻璃 Dock v2 —— 嵌套强制深色主题（与实验室模式正交）。
 *
 * 结构（自下而上，D-1 修复的核心）：
 *  1. 玻璃底：GlassSurface(matchParentSize) —— 只承载材质，内容为空；
 *  2. 内容层：导航行（HorizontalPager）+ 页点，画在玻璃之上；
 *  3. 发布按钮：外层 Box 的 TopCenter − 18dp —— **在 GlassSurface 的
 *     graphicsLayer 裁剪之外**，凸出与阴影完整可见。
 */
@Composable
private fun DarkGlassDockV2(
    backdrop: LayerBackdrop,
    recipe: GlassRecipe,
    pagerState: PagerState,
    selectedInPage: List<Int>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    ApexTheme(darkTheme = true, uiStyle = UiStyle.LIQUID_GLASS) {
        Box(modifier = modifier) {
            // 1) 玻璃底（D-4：配方驱动，实验室可切四档对照）
            GlassSurface(
                modifier = Modifier.matchParentSize(),
                backdrop = backdrop,
                style = recipe.toGlassStyle(),
                shape = RoundedCornerShape(recipe.cornerDp.dp)
            ) {}
            // 2) 内容层
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                var rowWidthPx by remember { mutableIntStateOf(0) }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(NavRowHeight)
                        .onSizeChanged { rowWidthPx = it.width }
                ) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize()
                    ) { page ->
                        DockPageV2(
                            page = page,
                            pageLocalSelected = selectedInPage[page]
                                .coerceIn(0, GlassDockMath.NAV_PER_PAGE - 1),
                            rowWidthPx = rowWidthPx,
                            onSelect = onSelect
                        )
                    }
                }
                DockPageDotsV2(pagerState = pagerState)
            }
            // 3) 发布按钮（D-1：在玻璃裁剪层之外，凸出栏顶）
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = -GlassDockMath.PUBLISH_LIFT_DP.dp)
            ) {
                DarkPublishButtonV2()
            }
        }
    }
}

/**
 * 单页内容：页内药丸（D-3）+ 五槽导航行（D-2）。
 *
 * 药丸画在**本页**内部 —— Pager 滑动时整页（含药丸）随手指平移，
 * 药丸与它高亮的项永远同程；松手后页内弹簧落位（果冻回弹）。
 */
@Composable
private fun DockPageV2(
    page: Int,
    pageLocalSelected: Int,
    rowWidthPx: Int,
    onSelect: (Int) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        InPagePillV2(pageLocalSelected = pageLocalSelected, rowWidthPx = rowWidthPx)
        Row(modifier = Modifier.fillMaxSize()) {
            repeat(GlassDockMath.NAV_PER_PAGE) { inPage ->
                // D-2：第 2 项之后插入 76dp 发布槽 —— nav|nav|发布|nav|nav
                if (inPage == GlassDockMath.PUBLISH_SLOT) {
                    Spacer(modifier = Modifier.width(GlassDockMath.PUBLISH_WIDTH_DP.dp))
                }
                val global = GlassDockMath.globalIndex(page, inPage)
                DockNavCellV2(
                    dest = DockDestsV2[global],
                    selected = inPage == pageLocalSelected,
                    onSelect = { onSelect(global) },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }
        }
    }
}

/**
 * 页内液态药丸（D-3 修复本体）：位置 = GlassDockMath.pillOffsetXpx（页内
 * 槽位），宽度 = 导航槽宽；位移动画 medium-bouncy 弹簧。
 */
@Composable
private fun InPagePillV2(pageLocalSelected: Int, rowWidthPx: Int) {
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    if (rowWidthPx <= 0) return // 首帧测量前不显示（避免 0 宽闪变）
    val publishPx = with(density) { GlassDockMath.PUBLISH_WIDTH_DP.dp.toPx() }.toInt()
    val slotPx = GlassDockMath.navSlotWidthPx(rowWidthPx, publishPx)
    val targetPx = GlassDockMath.pillOffsetXpx(pageLocalSelected, rowWidthPx, publishPx)
    val x by animateDpAsState(
        targetValue = with(density) { targetPx.toDp() },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "darkbar2_pill_x"
    )
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(with(density) { slotPx.toDp() })
            .offset(x = x)
            .clip(RoundedCornerShape(18.dp))
            .background(scheme.surfaceContainerHigh.copy(alpha = 0.95f))
    )
}

/** 导航单元（v1 手感保留：透明底 + 选中提亮 + 按压缩放 0.92）。 */
@Composable
private fun DockNavCellV2(
    dest: DockDestV2,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val label = stringResource(dest.labelRes)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = when {
            pressed -> 0.92f
            selected -> 1f
            else -> 0.96f
        },
        animationSpec = tween(durationMillis = 140),
        label = "darkbar2_cell_scale"
    )
    Column(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .glassClickable(interaction) { onSelect() }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = dest.icon,
            contentDescription = stringResource(R.string.glasslab_cd_darkbar_nav, label),
            modifier = Modifier.size(22.dp),
            tint = if (selected) scheme.onSurface else scheme.onSurfaceVariant
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) scheme.onSurface else scheme.onSurfaceVariant
        )
    }
}

/** 页点指示器（当前页加宽点亮）。 */
@Composable
private fun DockPageDotsV2(pagerState: PagerState) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        repeat(GlassDockMath.PAGES) { i ->
            val active = i == pagerState.currentPage
            Box(
                modifier = Modifier
                    .width(if (active) 14.dp else 5.dp)
                    .height(5.dp)
                    .clip(CircleShape)
                    .background(
                        scheme.onSurfaceVariant.copy(alpha = if (active) 0.6f else 0.25f)
                    )
            )
        }
    }
}

/** 中央发布按钮 —— 实心品牌色圆 + 投影 + 按压缩放 + 触觉（v1 手感保留）。 */
@Composable
private fun DarkPublishButtonV2() {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "darkbar2_publish_scale"
    )
    val haptic = LocalHapticFeedback.current
    Box(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .size(54.dp)
            .shadow(elevation = 8.dp, shape = CircleShape, clip = false)
            .clip(CircleShape)
            .background(scheme.primary)
            .glassClickable(interaction) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = stringResource(R.string.glasslab_cd_darkbar_publish),
            modifier = Modifier.size(26.dp),
            tint = scheme.onPrimary
        )
    }
}
