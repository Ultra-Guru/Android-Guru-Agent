package com.apex.agent.ui.screen.glass

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.ui.glass.GlassShapes
import com.apex.agent.ui.glass.GlassStyle
import com.apex.agent.ui.glass.GlassSurface
import com.apex.agent.ui.glass.glassClickable
import com.apex.agent.ui.theme.ApexTheme
import com.apex.agent.ui.theme.UiStyle
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * ═══════════════════════════════════════════════════════════════
 *  Dark 栏 —— 酷安式深色 Dock v2（kyant0 实时采样 + 液态滑动预览）
 * ═══════════════════════════════════════════════════════════════
 *
 * 复刻对象（酷安 App 底部导航栏，2026-10 实机录屏取型）：
 *  - 深炭黑磨砂胶囊，约 94% 屏宽、悬浮于内容之上（非贴边通栏）；
 *  - 中央实心品牌色圆形按钮（白色 + 号，凸出栏顶、带投影 —— 酷安「发布」位）；
 *  - 选中项：浅灰药丸背景包裹图标与文字（酷安同款），全圆角。
 *
 * v2 液态玻璃可滑动预览（本版升级点）：
 *  - 8 项双页 Dock：[HorizontalPager] 承载两页 × 4 项，横向手势自由滑动，
 *    滑动中相邻页的项目随之出入视野（真「可滑动预览」）；项目从固定的
 *    中央发布按钮下滑过 —— 实心按钮悬浮如石，项目流过如水；
 *  - 液态滑动药丸：全 Dock 唯一一枚药丸高亮（替代 v1 每项自带的淡入
 *    药丸），位置 = f(选中项, pager 实时偏移)，随手势连续跟手位移；
 *    松手 / 点击后以弹簧（medium-bouncy 阻尼）落位 —— 果冻回弹；
 *    滑动进行中按偏移分数横向拉伸、纵向压缩 —— 果冻挤压形变；
 *  - 液态形变纪律：药丸全部动效量在 graphicsLayer 块内读取 State
 *    （pager 偏移 / 选中项弹簧）—— 滑动帧只失效图层属性，零重组
 *    （#262 同款纪律，GlassSurface 激活度同源做法）；
 *  - 页点指示器：双页位置一览，当前页加宽点亮。
 *
 * 相对原型的增强（本实验室的本职）：
 *  - 原型栏是不透明深色 scrim；这里整条栏是 **kyant0 backdrop 真采样**
 *    玻璃（Floating 档：blur + lens 折射 + 色散）—— 滚动彩色内容从栏后
 *    掠过时，栏内画面必须实时变化（BackdropZone 同款验收口径）；
 *  - 嵌套强制深色主题（GlassLab 双实验室之外的第三点）：无论夜间/
 *    白天实验室，dock 一律深色玻璃 —— 酷安「浅色页面上深色栏」的
 *    标志性对比正是要验证的形态。
 *
 * 诚实边界：中央按钮为实心品牌色（酷安原型即实心非玻璃）—— 刻意保留
 * 材质对比（玻璃 dock + 实心按钮），不冒充全玻璃；按钮为演示无导航。
 */
private val DarkBarZoneHeight = 340.dp
private val DockCorner = 34.dp
private val PillCorner = 18.dp
private val PublishSize = 54.dp
private val PublishLift = 18.dp

/** Dock 页容量：两页 × 4 项（固定中央发布位浮于分页流之上）。 */
private const val DockPages = 2
private const val DockItemsPerPage = 4
private val NavItemHeight = 64.dp

/** Dock 导航项 —— 8 项双页（v1 的 4 项 + 第二页 4 项）。 */
private data class DockDest(val label: String, val icon: ImageVector)

private val DockDests = listOf(
    DockDest("首页", Icons.Default.Home),
    DockDest("数码", Icons.Default.Devices),
    DockDest("发现", Icons.Default.Explore),
    DockDest("我的", Icons.Default.Person),
    DockDest("视频", Icons.Default.Videocam),
    DockDest("游戏", Icons.Default.SportsEsports),
    DockDest("音乐", Icons.Default.MusicNote),
    DockDest("设置", Icons.Default.Settings)
)

/** 采样内容卡配色 —— 8 色轮转（实验室演示色板，与 GridRowText 同类定常）。 */
private val DarkBarCardColors = listOf(
    Color(0xFFE5484D), Color(0xFFF5A524), Color(0xFF46A758), Color(0xFF00B8D9),
    Color(0xFF3E63DD), Color(0xFF8E4EC6), Color(0xFFD6409F), Color(0xFF30A46C)
)

/** Dark 栏小节：滚动彩色内容 + 底部深色玻璃可滑动 Dock。两个实验室共用。 */
@Composable
internal fun DarkBarSection() {
    SectionHeader(
        title = "Dark 栏 · 酷安式深色 Dock",
        hint = "深炭玻璃胶囊浮在滚动内容上 —— Dock 内 8 项双页横向滑动，唯一药丸跟手液态滑动、" +
            "果冻挤压落位；栏内画面随滚动实时变化（kyant0 真采样）"
    )
    var selected by rememberSaveable { mutableStateOf(0) }
    val backdrop = rememberLayerBackdrop()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(DarkBarZoneHeight)
            .clip(GlassShapes.card)
    ) {
        // ── 采样源：彩色卡片流（.layerBackdrop 录制进 GraphicsLayer，
        //    滚动即重录 —— dock 里的玻璃画面随之实时变化）──
        Column(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            repeat(14) { i -> DarkBarContentRow(index = i) }
            // 底部留白：让最后一张卡也能完整滚到 dock 上方
            Box(modifier = Modifier.height(120.dp))
        }

        DarkGlassDock(
            backdrop = backdrop,
            selectedIndex = selected,
            onSelect = { selected = it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp)
        )

        // 诚实标注（左上角，不遮挡采样区中心）
        Text(
            text = "8 项双页 Dock：横向滑动切换页面，药丸跟手液态滑动；中央按钮为实心品牌色演示",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

/** 采样内容行：色卡 + 等宽文本 —— 高对比内容让采样变化一目了然。 */
@Composable
private fun DarkBarContentRow(index: Int) {
    val color = DarkBarCardColors[index % DarkBarCardColors.size]
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(color)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "ROW " + index.toString().padStart(2, '0'),
            style = MaterialTheme.typography.labelLarge,
            fontFamily = FontFamily.Monospace,
            color = Color.White
        )
        Text(
            text = "DARK BAR SAMPLING ${'A' + index % 26}",
            style = MaterialTheme.typography.labelLarge,
            fontFamily = FontFamily.Monospace,
            color = Color.White.copy(alpha = 0.85f)
        )
    }
}

/**
 * 深色玻璃可滑动 Dock v2 —— 嵌套强制深色主题（与两个实验室平行的第三形态）。
 *
 * 结构（GlassSurface 内容层自下而上）：
 *  1. [LiquidSlidingPill] —— 唯一药丸高亮，底层（不遮挡项目内容）；
 *  2. [HorizontalPager] —— 两页 × 4 项，横向手势滑动（可滑动预览主体）；
 *  3. 中央发布位 —— 固定悬浮实心按钮，分页项目从其下滑过；
 *  4. 页点指示器 —— 双页位置一览。
 */
@Composable
private fun DarkGlassDock(
    backdrop: LayerBackdrop,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    ApexTheme(darkTheme = true, uiStyle = UiStyle.LIQUID_GLASS) {
        // 初始页跟随恢复的选中项（rememberSaveable 跨进程重建还原不脱页）
        val pagerState = rememberPagerState(
            initialPage = selectedIndex / DockItemsPerPage,
            pageCount = { DockPages }
        )
        val scope = rememberCoroutineScope()

        // 选中处理：跨页项目先弹簧滑到目标页（药丸与项目同程液态滑入）
        val handleSelect: (Int) -> Unit = { index ->
            onSelect(index)
            val targetPage = index / DockItemsPerPage
            if (pagerState.currentPage != targetPage) {
                scope.launch { pagerState.animateScrollToPage(targetPage) }
            }
        }

        GlassSurface(
            modifier = modifier.fillMaxWidth(0.94f),
            backdrop = backdrop,
            style = GlassStyle.Floating,
            shape = RoundedCornerShape(DockCorner)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                var contentWidthPx by remember { mutableIntStateOf(0) }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(NavItemHeight)
                        .onSizeChanged { contentWidthPx = it.width }
                ) {
                    // 1) 唯一液态药丸（底层高亮，随手势连续滑动）
                    LiquidSlidingPill(
                        selectedIndex = selectedIndex,
                        pagerState = pagerState,
                        contentWidthPx = contentWidthPx
                    )
                    // 2) 可滑动分页流（两页 × 4 项）
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize()
                    ) { page ->
                        Row(modifier = Modifier.fillMaxSize()) {
                            repeat(DockItemsPerPage) { slot ->
                                val index = page * DockItemsPerPage + slot
                                val dest = DockDests[index]
                                DockNavCell(
                                    label = dest.label,
                                    icon = dest.icon,
                                    selected = index == selectedIndex,
                                    onSelect = { handleSelect(index) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                )
                            }
                        }
                    }
                    // 3) 中央发布位：固定悬浮，分页项目从按钮下滑过
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .width(76.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        DarkPublishButton()
                    }
                }
                // 4) 页点指示器
                DockPageDots(pagerState = pagerState)
            }
        }
    }
}

/**
 * 液态滑动药丸 —— 全 Dock 唯一选中高亮。
 *
 * 位置公式（graphicsLayer 块内求值，draw 通道零重组）：
 *  `translationX = (选中项浮点槽位 − pager 偏移 × 页容量) × 槽宽`
 *  - 滑动中：pager 偏移连续变化 → 药丸与其所属项目同程跟手位移
 *    （项目流到哪，药丸流到哪 —— 「液态」的语义本体）；
 *  - 点击换选：选中项浮点槽位经弹簧动画趋近目标 → 药丸果冻回弹落位，
 *    跨页选择时与 pager 滚动同程（两项动效共享同一条连续轨迹，无跳变）；
 *  - 挤压形变：滑动偏移分数越大，横向拉伸越多、纵向等比压缩
 *    （体积近似守恒的果冻挤压），回中即复原。
 */
@Composable
private fun LiquidSlidingPill(
    selectedIndex: Int,
    pagerState: PagerState,
    contentWidthPx: Int
) {
    val scheme = MaterialTheme.colorScheme
    // 选中项浮点槽位：弹簧趋近目标（果冻阻尼 —— 落位带回弹）
    val slotFloat by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "darkbar_liquid_pill_slot"
    )
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .fillMaxWidth(1f / DockItemsPerPage)
            .graphicsLayer {
                if (contentWidthPx > 0) {
                    val slotWidth = contentWidthPx / DockItemsPerPage.toFloat()
                    // pager 实时偏移 = 整页 + 页内分数（State：draw 通道读取）
                    val pagerOffset = pagerState.currentPage +
                        pagerState.currentPageOffsetFraction
                    // 跟手平移：药丸与其所属项目同程滑动
                    translationX = (slotFloat - pagerOffset * DockItemsPerPage) * slotWidth
                    // 果冻挤压：滑动越快拉伸越明显（纵向等比压缩守体积）
                    val stretch = 1f + 0.22f * abs(pagerState.currentPageOffsetFraction)
                    scaleX = stretch
                    scaleY = 1f - (stretch - 1f) * 0.5f
                }
            }
            .clip(RoundedCornerShape(PillCorner))
            .background(scheme.surfaceContainerHigh.copy(alpha = 0.95f))
    )
}

/**
 * 分页导航单元 —— 透明底（选中高亮由唯一液态药丸承担），
 * 选中项内容提亮 + 微放大（1.0 vs 0.96 —— 药丸之外的轻量响应），
 * 按压缩放 0.92（v1 同款手感）。
 */
@Composable
private fun DockNavCell(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = when {
            pressed -> 0.92f
            selected -> 1f
            else -> 0.96f
        },
        animationSpec = tween(durationMillis = 140),
        label = "darkbar_cell_scale"
    )
    Column(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onSelect
            )
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
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

/** 页点指示器 —— 双页位置一览：当前页加宽点亮，180ms 过渡。 */
@Composable
private fun DockPageDots(pagerState: PagerState) {
    val scheme = MaterialTheme.colorScheme
    val page = pagerState.currentPage
    Row(
        modifier = Modifier.padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        repeat(DockPages) { i ->
            val active = i == page
            val dotWidth by animateDpAsState(
                targetValue = if (active) 14.dp else 5.dp,
                animationSpec = tween(durationMillis = 180),
                label = "darkbar_dot_width"
            )
            Box(
                modifier = Modifier
                    .width(dotWidth)
                    .height(5.dp)
                    .clip(CircleShape)
                    .background(scheme.onSurfaceVariant.copy(alpha = if (active) 0.6f else 0.25f))
            )
        }
    }
}

/** 中央发布按钮 —— 实心品牌色圆，凸出栏顶 + 投影 + 按压缩放 + 触觉反馈。 */
@Composable
private fun DarkPublishButton() {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "darkbar_publish_scale"
    )
    val haptic = LocalHapticFeedback.current
    Box(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .offset(y = -PublishLift)
            .size(PublishSize)
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
