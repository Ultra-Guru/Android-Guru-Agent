package com.apex.agent.ui.screen.glass

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

/**
 * ═══════════════════════════════════════════════════════════════
 *  Dark 栏 —— 酷安式深色 Dock（kyant0 实时采样复刻）
 * ═══════════════════════════════════════════════════════════════
 *
 * 复刻对象（酷安 App 底部导航栏，2026-10 实机录屏取型）：
 *  - 深炭黑磨砂胶囊，约 94% 屏宽、悬浮于内容之上（非贴边通栏）；
 *  - 5 项目：4 个常规项（图标 + 文字）+ 中央实心品牌色圆形按钮
 *    （白色 + 号，明显凸出栏顶、带投影 —— 酷安「发布」位）；
 *  - 选中项：浅灰药丸背景包裹图标与文字（酷安同款），全圆角。
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
 * 材质对比（4 玻璃件 + 1 实心件），不冒充全玻璃；按钮为演示无导航。
 */
private val DarkBarZoneHeight = 340.dp
private val DockCorner = 34.dp
private val PillCorner = 18.dp
private val PublishSize = 54.dp
private val PublishLift = 18.dp

/** 采样内容卡配色 —— 8 色轮转（实验室演示色板，与 GridRowText 同类定常）。 */
private val DarkBarCardColors = listOf(
    Color(0xFFE5484D), Color(0xFFF5A524), Color(0xFF46A758), Color(0xFF00B8D9),
    Color(0xFF3E63DD), Color(0xFF8E4EC6), Color(0xFFD6409F), Color(0xFF30A46C)
)

/** Dark 栏小节：滚动彩色内容 + 底部深色玻璃 Dock。两个实验室共用。 */
@Composable
internal fun DarkBarSection() {
    SectionHeader(
        title = "Dark 栏 · 酷安式深色 Dock",
        hint = "深炭玻璃胶囊浮在滚动内容上 —— 栏内画面必须随滚动实时变化（kyant0 真采样）；" +
            "白天实验室里深色栏压在浅色内容上，正是酷安标志性形态"
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
            text = "中央按钮为实心品牌色演示（原型即实心）；药丸高亮为交互演示",
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

/** 深色玻璃 Dock 本体 —— 嵌套强制深色主题（与两个实验室平行的第三形态）。 */
@Composable
private fun DarkGlassDock(
    backdrop: LayerBackdrop,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    ApexTheme(darkTheme = true, uiStyle = UiStyle.LIQUID_GLASS) {
        GlassSurface(
            modifier = modifier.fillMaxWidth(0.94f),
            backdrop = backdrop,
            style = GlassStyle.Floating,
            shape = RoundedCornerShape(DockCorner)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DarkNavItem(
                    index = 0, label = "首页", icon = Icons.Default.Home,
                    selectedIndex = selectedIndex, onSelect = onSelect,
                    modifier = Modifier.weight(1f)
                )
                DarkNavItem(
                    index = 1, label = "数码", icon = Icons.Default.Devices,
                    selectedIndex = selectedIndex, onSelect = onSelect,
                    modifier = Modifier.weight(1f)
                )
                // 中央发布位：实心品牌色圆 + 凸出栏顶（酷安「发布」形态复刻）
                Box(
                    modifier = Modifier.width(76.dp),
                    contentAlignment = Alignment.Center
                ) {
                    DarkPublishButton()
                }
                DarkNavItem(
                    index = 2, label = "发现", icon = Icons.Default.Explore,
                    selectedIndex = selectedIndex, onSelect = onSelect,
                    modifier = Modifier.weight(1f)
                )
                DarkNavItem(
                    index = 3, label = "我的", icon = Icons.Default.Person,
                    selectedIndex = selectedIndex, onSelect = onSelect,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * 常规导航项 —— 酷安选中态复刻：浅灰药丸包裹图标 + 文字，全圆角。
 * 药丸随选中态淡入/缩放（180ms）；未选中项透明背景。
 */
@Composable
private fun DarkNavItem(
    index: Int,
    label: String,
    icon: ImageVector,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val selected = index == selectedIndex
    val interaction = remember { MutableInteractionSource() }
    val pillAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "darkbar_pill_alpha"
    )
    val pillScale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.92f,
        animationSpec = tween(durationMillis = 180),
        label = "darkbar_pill_scale"
    )
    Column(
        modifier = modifier
            // scale 最外层：药丸背景与内容整体缩放
            .graphicsLayer {
                scaleX = pillScale
                scaleY = pillScale
            }
            .clip(RoundedCornerShape(PillCorner))
            .background(scheme.surfaceContainerHigh.copy(alpha = 0.95f * pillAlpha))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = { onSelect(index) }
            )
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
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
