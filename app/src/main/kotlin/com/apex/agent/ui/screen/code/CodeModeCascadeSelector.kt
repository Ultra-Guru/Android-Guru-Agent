package com.apex.agent.ui.screen.code

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.core.engine.AgentMode
import com.apex.agent.core.engine.AutonomyLevel
import com.apex.agent.ui.chatkit.agentModeDescription
import com.apex.agent.ui.chatkit.agentModeIcon
import com.apex.agent.ui.theme.selectorOutlineColor

/**
 * # Coding 模式级联选择器（v4）—— Build/Plan/Goal + 三档自主级别
 *
 * 重构自 AgentModeSelector（平铺下拉）：一级菜单三项——
 * - **Build**：点击只展开/收起二级（自主级别）菜单，**不切换模式**；
 * - **Plan**：一级点击直接选中，不展开二级；
 * - **Goal**：一级点击直接进入 Goal 模式（自主级别经目标设定弹层复用同一
 *   套 [AutonomyOptionRow] 组件，见 GoalSetupSheet）。
 *
 * ## 级联布局（自适应两形态）
 *
 * - **飞出态**（屏宽 ≥ [FLYOUT_MIN_SCREEN_WIDTH]，平板/横屏/大屏折叠）：
 *   二级面板在一级菜单项**右侧**并排展开（两列 + 分隔线，与 Build 项顶部
 *   对齐）——一级保持可见、二级不遮挡一级项；
 * - **就地态**（空间不足的手机竖屏）：二级面板在 Build 项**下方**就地向下
 *   展开（AnimatedVisibility 推挤后续项，NeonOrbButton/WebSearchChannelMenu
 *   同款形态），同样不遮挡任何一级项。
 *
 * ## 关闭规则（协作式分层关闭）
 *
 * - 点击菜单外 / 返回键：关闭**当前最上层**——二级开着先收二级（一级
 *   保持可见），再触发才关一级（DropdownMenu 的 onDismissRequest 协作
 *   语义：状态不置 false 弹层不消失，据此实现分层关闭）；
 * - 点一级 Plan/Goal 或二级任一自主级别：关闭全部菜单。
 *
 * ## 前置澄清开关
 *
 * 「完全自主」被选中时，其下方出现「开始前集中澄清」Switch 行（默认关；
 * 拨动不关菜单、标签实时更新「Build · 完全自主（前置澄清：开）」）；切到
 * 其他级别时开关行隐藏但状态保留（AgentSettings.codeFullAutoPreClarify）。
 *
 * ## 无障碍
 *
 * 触发胶囊 / 一级项 / 自主级别行 / Switch 均有 contentDescription；
 * 当前模式标签由胶囊 contentDescription 全量播报。
 */
@Composable
internal fun CodeModeCascadeSelector(
    currentMode: AgentMode,
    currentAutonomy: AutonomyLevel,
    preClarify: Boolean,
    onSelectMode: (AgentMode) -> Unit,
    onSelectAutonomy: (AgentMode, AutonomyLevel) -> Unit,
    onTogglePreClarify: (Boolean) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    // 展开二级菜单的一级项（当前仅 Build；null = 全收起）。
    var submenuMode by remember { mutableStateOf<AgentMode?>(null) }

    val screenW = LocalConfiguration.current.screenWidthDp.dp
    val flyout = screenW >= FLYOUT_MIN_SCREEN_WIDTH
    val chipLabel = cascadeChipLabel(currentMode, currentAutonomy, preClarify)
    // i18n：semantics 块非组合上下文，无障碍描述在组合内预取
    val chipCd = stringResource(R.string.code_cascade_chip_cd, chipLabel)

    Box {
        // ── 触发胶囊（28dp，同选择器行既有规格；一级菜单锚点）──
        Surface(
            onClick = { expanded = true },
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            border = BorderStroke(1.dp, selectorOutlineColor()),
            modifier = Modifier
                .heightIn(min = 28.dp)
                .semantics { contentDescription = chipCd }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Icon(
                    imageVector = agentModeIcon(currentMode),
                    contentDescription = null,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = chipLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp)
                )
            }
        }

        // ── 级联菜单（一级 + 可选二级；协作式分层关闭见 KDoc）──
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                // 关闭当前最上层：二级开着 → 只收二级（一级保持）；再触发才关一级。
                if (submenuMode != null) submenuMode = null else expanded = false
            },
            modifier = Modifier.widthIn(max = screenW - 24.dp)
        ) {
            if (flyout && submenuMode != null) {
                // 飞出态：两列并排（一级列固定宽 + 分隔线 + 二级面板，顶部对齐）。
                Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                    Column(modifier = Modifier.width(LEVEL1_COLUMN_WIDTH)) {
                        Level1Items(
                            currentMode = currentMode,
                            submenuMode = submenuMode,
                            onToggleBuildSubmenu = { submenuMode = if (submenuMode == AgentMode.BUILD) null else AgentMode.BUILD },
                            onSelectDirect = { mode ->
                                submenuMode = null
                                expanded = false
                                onSelectMode(mode)
                            }
                        )
                    }
                    // 列间分隔线（二级不遮挡一级项的视觉分界）
                    Box(
                        modifier = Modifier
                            .padding(vertical = 8.dp)
                            .fillMaxHeight()
                            .width(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant)
                    )
                    AutonomyLevelPanel(
                        current = currentAutonomy,
                        preClarify = preClarify,
                        compact = true,
                        showHeader = true,
                        onSelect = { level ->
                            submenuMode = null
                            expanded = false
                            onSelectAutonomy(AgentMode.BUILD, level)
                        },
                        onTogglePreClarify = onTogglePreClarify,
                        modifier = Modifier.widthIn(min = LEVEL2_PANEL_WIDTH)
                    )
                }
            } else {
                // 就地态（手机竖屏 / 二级未展开）：单列，二级在 Build 项下方展开。
                Column {
                    Level1Items(
                        currentMode = currentMode,
                        submenuMode = submenuMode,
                        onToggleBuildSubmenu = { submenuMode = if (submenuMode == AgentMode.BUILD) null else AgentMode.BUILD },
                        onSelectDirect = { mode ->
                            submenuMode = null
                            expanded = false
                            onSelectMode(mode)
                        }
                    )
                    AnimatedVisibility(
                        visible = submenuMode == AgentMode.BUILD,
                        enter = expandVertically(),
                        exit = shrinkVertically()
                    ) {
                        AutonomyLevelPanel(
                            current = currentAutonomy,
                            preClarify = preClarify,
                            compact = false,
                            showHeader = true,
                            onSelect = { level ->
                                submenuMode = null
                                expanded = false
                                onSelectAutonomy(AgentMode.BUILD, level)
                            },
                            onTogglePreClarify = onTogglePreClarify,
                            modifier = Modifier.padding(start = 28.dp, end = 4.dp, bottom = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

/** 一级菜单三项（Build 可展开二级 / Plan 与 Goal 直接选中）。 */
@Composable
private fun Level1Items(
    currentMode: AgentMode,
    submenuMode: AgentMode?,
    onToggleBuildSubmenu: () -> Unit,
    onSelectDirect: (AgentMode) -> Unit
) {
    CascadeModeItem(
        mode = AgentMode.BUILD,
        selected = currentMode == AgentMode.BUILD,
        submenuOpen = submenuMode == AgentMode.BUILD,
        onClick = onToggleBuildSubmenu
    )
    CascadeModeItem(
        mode = AgentMode.PLAN,
        selected = currentMode == AgentMode.PLAN,
        submenuOpen = false,
        onClick = { onSelectDirect(AgentMode.PLAN) }
    )
    CascadeModeItem(
        mode = AgentMode.GOAL,
        selected = currentMode == AgentMode.GOAL,
        submenuOpen = false,
        onClick = { onSelectDirect(AgentMode.GOAL) }
    )
}

/** 单个一级菜单项（名称 + 一句话说明 + 选中态；Build 尾部为展开箭头）。 */
@Composable
private fun CascadeModeItem(
    mode: AgentMode,
    selected: Boolean,
    submenuOpen: Boolean,
    onClick: () -> Unit
) {
    val hasSubmenu = mode == AgentMode.BUILD
    // i18n：组合内预取（Build = 展开/收起二级；其余 = 直接选中）
    val cd = if (hasSubmenu) {
        stringResource(
            if (submenuOpen) R.string.code_cascade_build_collapse_cd
            else R.string.code_cascade_build_expand_cd
        )
    } else {
        stringResource(R.string.code_cascade_mode_select_cd, mode.displayName)
    }
    DropdownMenuItem(
        text = {
            Column {
                Text(
                    text = mode.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = agentModeDescription(mode),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        leadingIcon = {
            Icon(
                imageVector = agentModeIcon(mode),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingIcon = {
            when {
                hasSubmenu -> Icon(
                    imageVector = if (submenuOpen) Icons.Default.KeyboardArrowDown
                    else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                selected -> Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        },
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = cd }
    )
}

/**
 * 二级菜单面板：三档自主级别 + 前置澄清开关行（仅当前生效级别为完全
 * 自主时显示；拨动不关菜单）。级联菜单与目标设定弹层（GoalSetupSheet）
 * 复用同一套 [AutonomyOptionRow] / [PreClarifyRow] 组件。
 */
@Composable
internal fun AutonomyLevelPanel(
    current: AutonomyLevel,
    preClarify: Boolean,
    compact: Boolean,
    showHeader: Boolean,
    onSelect: (AutonomyLevel) -> Unit,
    onTogglePreClarify: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        if (showHeader) {
            Text(
                text = stringResource(R.string.autonomy_level_label),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
        AutonomyLevel.entries.forEach { level ->
            AutonomyOptionRow(
                level = level,
                selected = level == current,
                compact = compact,
                onClick = { onSelect(level) }
            )
        }
        // 前置澄清开关：仅完全自主为当前生效级别时显示（状态保留，不随隐藏重置）
        AnimatedVisibility(
            visible = current == AutonomyLevel.FULLY,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            PreClarifyRow(
                checked = preClarify,
                onToggle = onTogglePreClarify,
                compact = compact
            )
        }
    }
}

/**
 * 自主级别单选行（级联菜单二级 + 目标设定弹层共用）。
 * compact = 飞出态（描述单行截断，省横向空间）。
 */
@Composable
internal fun AutonomyOptionRow(
    level: AutonomyLevel,
    selected: Boolean,
    compact: Boolean,
    onClick: () -> Unit
) {
    val selectedSuffix = stringResource(R.string.autonomy_selected_suffix)
    val cd = stringResource(
        R.string.autonomy_option_cd,
        autonomyShortName(level),
        if (selected) selectedSuffix else ""
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics { contentDescription = cd }
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
        } else {
            Spacer(modifier = Modifier.size(16.dp))
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = autonomyShortName(level),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = autonomyDesc(level),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (compact) 1 else 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 「开始前集中澄清」开关行（NeonToggleRow 同款：整行可点切换，Switch 为
 * 纯展示 onCheckedChange=null；拨动不关闭所在菜单/弹层）。
 */
@Composable
internal fun PreClarifyRow(
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    compact: Boolean
) {
    val cd = stringResource(R.string.autonomy_pre_clarify_cd)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onToggle(!checked) }
            .padding(start = 36.dp, end = 12.dp, top = 2.dp, bottom = 2.dp)
            .semantics(mergeDescendants = true) { contentDescription = cd }
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.autonomy_pre_clarify_label),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (!compact) {
                Text(
                    text = stringResource(R.string.autonomy_pre_clarify_desc),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            modifier = Modifier.semantics { contentDescription = cd }
        )
    }
}

/** 触发胶囊标签：Plan 裸名；Build/Goal = 「模式 · 级别」（FULLY+开关开 追加后缀）。 */
@Composable
internal fun cascadeChipLabel(
    mode: AgentMode,
    autonomy: AutonomyLevel,
    preClarify: Boolean
): String = when (mode) {
    AgentMode.PLAN -> mode.displayName
    else -> buildString {
        append(mode.displayName)
        append(" · ")
        append(autonomyShortName(autonomy))
        if (autonomy == AutonomyLevel.FULLY && preClarify) {
            append(stringResource(R.string.autonomy_pre_clarify_on_suffix))
        }
    }
}

/** 自主级别本地化短名（触发胶囊 / 菜单 / 弹层共用）。 */
@Composable
internal fun autonomyShortName(level: AutonomyLevel): String = stringResource(
    when (level) {
        AutonomyLevel.INTERACTIVE -> R.string.autonomy_name_interactive
        AutonomyLevel.SEMI -> R.string.autonomy_name_semi
        AutonomyLevel.FULLY -> R.string.autonomy_name_fully
    }
)

/** 自主级别一行描述（提问策略口径）。 */
@Composable
internal fun autonomyDesc(level: AutonomyLevel): String = stringResource(
    when (level) {
        AutonomyLevel.INTERACTIVE -> R.string.autonomy_desc_interactive
        AutonomyLevel.SEMI -> R.string.autonomy_desc_semi
        AutonomyLevel.FULLY -> R.string.autonomy_desc_fully
    }
)

/** 飞出态最小屏宽（一级列 + 分隔线 + 二级面板 + 弹层内边距 + 屏幕安全边距）。 */
private val FLYOUT_MIN_SCREEN_WIDTH = 460.dp

/** 飞出态一级列固定宽（名称 + 一行说明 + 图标）。 */
private val LEVEL1_COLUMN_WIDTH = 188.dp

/** 飞出态二级面板最小宽（compact 描述单行截断）。 */
private val LEVEL2_PANEL_WIDTH = 212.dp
