package com.apex.agent.ui.glass

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import com.apex.agent.ui.theme.LocalExtendedColors
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kyant.backdrop.Backdrop

/**
 * ═══════════════════════════════════════════════════════════════
 *  Glass Component API —— 业务层唯一入口（kyant0/backdrop 底座）
 * ═══════════════════════════════════════════════════════════════
 *
 * Spec §3：业务 UI 不允许直接散落第三方玻璃 API。
 * 本文件提供全部玻璃组件；内部细节封装在 GlassSurface / GlassStyle。
 *
 * 用法速查：
 *  - backdrop 传 null = Frosted 档——无 backdrop 采样，诚实降级；
 *  - backdrop 传 LayerBackdrop = Backdrop 档——真实采样 + blur + lens 折射；
 *  - 所有组件支持 Normal / Pressed / Focused / Selected / Disabled。
 */

/** 玻璃卡片 —— Agent 卡 / Tool 卡 / 状态卡 / Plan 卡 / 设置与诊断页卡片的统一容器。 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = null,
    style: GlassStyle = GlassStyle.Card,
    shape: Shape = GlassShapes.card,
    accent: Color = Color.Unspecified,
    selected: Boolean = false,
    enabled: Boolean = true,
    focused: Boolean = false,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    GlassSurface(
        modifier = modifier,
        backdrop = backdrop,
        style = style,
        shape = shape,
        accent = accent,
        selected = selected,
        enabled = enabled,
        focused = focused,
        interactionSource = interactionSource
    ) {
        Column { content() }
    }
}

/**
 * 玻璃图标按钮 —— 返回按钮 / 顶栏菜单 / 小型控制。
 * 小尺寸、高圆角、轻玻璃 —— Spec §5A：不得做成巨大玻璃按钮。
 */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = null,
    style: GlassStyle = GlassStyle.Control,
    size: Dp = 40.dp,
    iconSize: Dp = Dp.Unspecified,
    tint: Color = Color.Unspecified,
    enabled: Boolean = true
) {
    val interaction = remember { MutableInteractionSource() }
    val scheme = MaterialTheme.colorScheme
    val resolvedTint = if (tint.alpha > 0f) tint else scheme.onSurfaceVariant
    val resolvedIconSize = if (iconSize == Dp.Unspecified) size * 0.55f else iconSize
    GlassSurface(
        // #271：48dp 触区红线（UI-012 同款语义）—— 自绘可点击组件无 M3
        // 最小触区补偿，视觉直径低于 48 的调用整体抬到 48；icon 仍按原
        // 比例（resolvedIconSize 由调用方 size 派生，不随触区放大）。
        modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).size(size),
        backdrop = backdrop,
        style = style,
        shape = CircleShape,
        enabled = enabled,
        interactionSource = interaction
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .glassClickable(interaction, enabled, onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(resolvedIconSize),
                tint = resolvedTint
            )
        }
    }
}

/** 玻璃文字按钮 —— 胶囊形态，用于 Dialog / 空态等轻场景。 */
@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = null,
    style: GlassStyle = GlassStyle.Control,
    accent: Color = Color.Unspecified,
    leadingIcon: ImageVector? = null,
    enabled: Boolean = true
) {
    val interaction = remember { MutableInteractionSource() }
    val scheme = MaterialTheme.colorScheme
    val labelColor = if (accent.alpha > 0f) accent else scheme.onSurface
    GlassSurface(
        modifier = modifier,
        backdrop = backdrop,
        style = style,
        shape = GlassShapes.button,
        accent = accent,
        enabled = enabled,
        interactionSource = interaction
    ) {
        Row(
            modifier = Modifier
                .glassClickable(interaction, enabled, onClick)
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = labelColor
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = labelColor
            )
        }
    }
}

/**
 * 玻璃悬浮按钮 —— FAB / 快捷操作 / 滚动控制。
 * 允许比卡片更强的 blur / 边缘 / 高光 / 深度 —— Spec §8。
 */
@Composable
fun GlassFloatingButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = null,
    size: Dp = 48.dp,
    accent: Color = Color.Unspecified,
    tint: Color = Color.Unspecified,
    enabled: Boolean = true
) {
    GlassIconButton(
        icon = icon,
        contentDescription = contentDescription,
        onClick = onClick,
        modifier = modifier,
        backdrop = backdrop,
        style = GlassStyle.Floating,
        size = size,
        iconSize = size * 0.5f,
        tint = tint,
        enabled = enabled
    )
}

/**
 * 玻璃导航项 —— Drawer 每一项的专用形态。
 * Normal：非常轻；Selected：材质亮度 + 深度提升；Pressed：短暂高光。
 * 保留既有强调色左指示条与圆形图标井 —— 品牌延续而非推翻。
 */
@Composable
fun GlassNavigationItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = null,
    enabled: Boolean = true,
    accent: Color = Color.Unspecified,
    trailing: (@Composable () -> Unit)? = null
) {
    val scheme = MaterialTheme.colorScheme
    val itemAccent = if (accent.alpha > 0f) accent else scheme.primary
    val interaction = remember { MutableInteractionSource() }
    // diag-20261011：指示条动画改为纯 draw 通道 —— 旧实现 animateDpAsState 喂
    // Modifier.width，选中态翻转时每帧 remeasure 整列导航项（抽屉 verticalScroll
    // Column）；与目的地切换（旧屏子树 dispose，SaveableStateProvider 换页）同帧
    // 竞争时，draw 派发中的 measureAndLayout 会撞上已脱离 owner 的节点 ——
    // 「LayoutNode should be attached to an owner」崩溃（crash-1791678670852 栈
    // 形态：Scroll→Column→Row→Padding→Box/width）。固定 3dp 槽位 + drawBehind
    // 内读动画分数：只失效 draw，零测量失效，视效保留（宽度生长）。
    val indicatorFraction by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "glass_nav_indicator"
    )

    GlassSurface(
        modifier = modifier.fillMaxWidth(),
        backdrop = backdrop,
        style = GlassStyle.Navigation,
        shape = GlassShapes.navItem,
        accent = itemAccent,
        selected = selected,
        enabled = enabled,
        interactionSource = interaction
    ) {
        Row(
            modifier = Modifier
                // ★ 选中项保持可点击（用户反馈：在 Agent 模式页打开抽屉后
                // 再点「Agent 模式」抽屉收不回 —— 旧实现 enabled && !selected
                // 把已选中项的点击禁用，onDestinationSelected 不触发、抽屉
                // 悬停不关）。选中项点击走同一路径：navigateToTopLevel 同页
                // 无副作用 + drawerState.close() 收起 —— 标准 drawer 惯例。
                .glassClickable(interaction, enabled, onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 强调色指示条 —— 既有品牌元素；draw 通道动画（宽度分数），零测量失效
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(24.dp)
                    .drawBehind {
                        if (indicatorFraction > 0.01f) {
                            drawRoundRect(
                                color = itemAccent,
                                topLeft = Offset.Zero,
                                size = Size(size.width * indicatorFraction, size.height),
                                cornerRadius = CornerRadius(2.dp.toPx())
                            )
                        }
                    }
            )
            // 圆形图标井：选中态主色浸染，常态中性
            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) itemAccent.copy(alpha = 0.20f)
                            else scheme.surfaceContainerHighest.copy(alpha = 0.75f)
                        )
                )
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (selected) itemAccent else scheme.onSurfaceVariant
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) itemAccent else scheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (trailing != null) trailing()
        }
    }
}

/** 工具卡状态 —— Spec §7：状态改变 tint / 亮度 / 高光，不用强色制造廉价效果。 */
enum class GlassToolStatus { RUNNING, COMPLETED, FAILED, WAITING }

/** 玻璃工具卡 —— Tool 执行 UI 专用；状态驱动着色，可用 accent 覆盖。 */
@Composable
fun GlassToolCard(
    status: GlassToolStatus,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = null,
    shape: Shape = GlassShapes.card,
    expanded: Boolean = false,
    accent: Color = Color.Unspecified,
    content: @Composable ColumnScope.() -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val statusAccent = when (status) {
        GlassToolStatus.RUNNING -> scheme.primary
        // UI-016：完成绿收编 success token（原硬编码 22C55E 浅色 2.28:1）
        GlassToolStatus.COMPLETED -> LocalExtendedColors.current.success
        GlassToolStatus.FAILED -> scheme.error
        GlassToolStatus.WAITING -> scheme.secondary
    }
    GlassCard(
        modifier = modifier,
        backdrop = backdrop,
        style = GlassStyle.Card,
        shape = shape,
        accent = if (accent.alpha > 0f) accent else statusAccent,
        selected = expanded,
        content = content
    )
}

/**
 * 玻璃对话框 —— Spec §9。
 * backdrop != null 时面板真实采样源内容（kyant0 GraphicsLayer 采样，
 * 跨 Dialog 窗口的采样仍受窗口合成限制 —— 主窗口源采不到时自动表现为
 * Frosted 档语义，不冒充）；backdrop == null 时 Frosted 面板。
 * 对话框内容保持清晰 —— 玻璃只作用于面板材质本身。
 */
@Composable
fun GlassDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = null,
    style: GlassStyle = GlassStyle.Dialog,
    shape: Shape = GlassShapes.dialog,
    properties: DialogProperties = DialogProperties(),
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = properties
    ) {
        GlassSurface(
            modifier = modifier,
            backdrop = backdrop,
            style = style,
            shape = shape
        ) {
            Column(modifier = Modifier.padding(24.dp)) { content() }
        }
    }
}

/** 玻璃徽标 —— 小型状态组件的极轻玻璃。 */
@Composable
fun GlassBadge(
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = null,
    accent: Color = Color.Unspecified,
    content: @Composable RowScope.() -> Unit
) {
    GlassSurface(
        modifier = modifier,
        backdrop = backdrop,
        style = GlassStyle.Subtle,
        shape = RoundedCornerShape(8.dp),
        accent = accent
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            content = content
        )
    }
}

/**
 * 聊天气泡玻璃 —— Agent 回复 / 流式 / 思考三气泡专用。
 *
 * kyant0 时代的能力跃迁（对比 Cloudy 时代）：气泡可接收页面的氛围背景
 * [backdrop]（屏幕背景源，与气泡不同子树 —— Haze 1.4 的嵌套限制不存在），
 * 经 GPU blur + 轻折射得到真·液态玻璃材质；backdrop == null 或 API < 31
 * 时退回 Frosted 档（霜面渐变 + 边缘光，零冒充）。
 * 流式高频重排场景：GPU RenderEffect 每帧毫秒级，替代 Cloudy 的 CPU
 * 位图回读 + NEON 迭代模糊 —— 长会话滚动/流式不再有掉帧尖峰。
 */
@Composable
fun AgentBubbleGlass(
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = null,
    shape: Shape = RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp),
    accent: Color = Color.Unspecified,
    content: @Composable ColumnScope.() -> Unit
) {
    GlassSurface(
        modifier = modifier,
        backdrop = backdrop,
        style = GlassStyle.Bubble,
        shape = shape,
        accent = accent
    ) {
        Column { content() }
    }
}
