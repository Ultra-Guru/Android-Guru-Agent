package com.apex.agent.ui.screen.code

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.core.code.thinking.CodeThinkingLevel
import kotlin.math.roundToInt

/**
 * # Code Thinking Selector — Coding 模式思考档位选择器（v4 滑块版）
 *
 * ## v4 设计（2026-10 用户需求「思考程度改为滑块」）
 * - **深度七档 = 一根滑块**：NONE..APEXCODE 七档等距停靠（5 个中间步进），
 *   拖动中实时预览目标档（档名 + 预算徽标 + 画像说明跟随手指切换），松手
 *   即选中并发 [onSelect] —— 取代 v3 的七行档位列（下拉高度减半，档间
 *   深浅由滑块位置直接可感，不再依赖音量条仪表）；
 * - **AUTO 独立行保留**：自动档不在深度轴上（发送前预检选档 + 深水区
 *   升级），单独一行可选中，决策回显照旧；AUTO 选中时滑块仍可拖动，
 *   拖动 = 明确选定某个固定档（自然退出自动档）；
 * - **系统感知宽度**：`widthIn(min 300, max 屏宽-24dp)` —— 窄屏自动收缩；
 * - 档名/预算徽标/画像说明复用 v3 语义（[codeThinkingShortName] /
 *   [codeThinkingBudgetLabel] / [codeThinkingLevelDetail]）。
 *
 * 档位枚举为 coding 专属的 [CodeThinkingLevel]（与 Agent 聊天页分立）。
 */
@Composable
internal fun CodeThinkingSelector(
    current: CodeThinkingLevel,
    adaptiveDecision: String?,
    onSelect: (CodeThinkingLevel) -> Unit,
    onOpenGuide: () -> Unit,
    // 原生 reasoning 参数是否随请求发送（当前默认模型能力位门控；
    // false 时下拉底部出提示行，见类 KDoc）
    nativeEffortActive: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        // ── 触发胶囊（28dp 无边框，对齐 AgentModeSelector 规格）──
        Surface(
            onClick = { expanded = true },
            shape = RoundedCornerShape(50),
            color = if (current == CodeThinkingLevel.AUTO) {
                MaterialTheme.colorScheme.tertiaryContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
            contentColor = if (current == CodeThinkingLevel.AUTO) {
                MaterialTheme.colorScheme.onTertiaryContainer
            } else {
                MaterialTheme.colorScheme.onSecondaryContainer
            },
            modifier = Modifier.heightIn(min = 28.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Psychology,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = stringResource(R.string.code_thinking_chip, codeThinkingShortName(current)),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }

        // ── 结构化下拉（滑块面板；系统感知宽度）──
        val maxMenuWidth = LocalConfiguration.current.screenWidthDp.dp - 24.dp
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 3.dp,
            modifier = Modifier.widthIn(min = 300.dp, max = maxMenuWidth)
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .widthIn(min = 280.dp, max = maxMenuWidth - 20.dp)
            ) {
                // ── 标题行：脑图标 + 标题 ──
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Psychology,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = stringResource(R.string.code_thinking_selector_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                // ── 深度滑块区：当前/预览档信息 + 七档滑块 + 两端锚点 ──
                val depthTiers = remember {
                    CodeThinkingLevel.entries.filter { it.isDepthTier }
                }
                // 拖动中的目标档序（null = 未在拖动）——预览跟随手指
                var dragIndex by remember { mutableStateOf<Int?>(null) }
                // 基准滑块位置：深度档 = 自身档序；AUTO = 标准档占位（拖动即退出自动档）
                val baseIndex = if (current.isDepthTier) {
                    depthTiers.indexOf(current)
                } else {
                    depthTiers.indexOf(CodeThinkingLevel.STANDARD)
                }
                val shownLevel = dragIndex?.let { depthTiers.getOrNull(it) }
                    ?: current.takeIf { it.isDepthTier }
                if (shownLevel != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(horizontal = 8.dp)
                    ) {
                        Text(
                            text = codeThinkingShortName(shownLevel),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        // 预算徽标（8dp 圆角小票）
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        ) {
                            Text(
                                text = codeThinkingBudgetLabel(shownLevel),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                            )
                        }
                    }
                    Text(
                        text = codeThinkingLevelDetail(shownLevel),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp)
                    )
                } else {
                    // AUTO 选中且未拖动：信息行回显自动档语义
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(horizontal = 8.dp)
                    ) {
                        Text(
                            text = codeThinkingShortName(CodeThinkingLevel.AUTO),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                    Text(
                        text = stringResource(R.string.code_thinking_auto_desc),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp)
                    )
                }
                Slider(
                    value = (dragIndex ?: baseIndex).toFloat(),
                    onValueChange = { fraction ->
                        dragIndex = fraction.roundToInt().coerceIn(0, depthTiers.lastIndex)
                    },
                    onValueChangeFinished = {
                        dragIndex?.let { idx -> onSelect(depthTiers[idx]) }
                        dragIndex = null
                    },
                    valueRange = 0f..depthTiers.lastIndex.toFloat(),
                    // 7 档 = 两端 + 5 个中间停靠点
                    steps = depthTiers.size - 2,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = codeThinkingShortName(CodeThinkingLevel.NONE),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = codeThinkingShortName(CodeThinkingLevel.APEXCODE),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )

                // ── AUTO 元档（发送前预检 + 运行中深水区升级，coding 自治）──
                AutoTierRow(
                    selected = current == CodeThinkingLevel.AUTO,
                    decisionEcho = adaptiveDecision,
                    onClick = {
                        expanded = false
                        onSelect(CodeThinkingLevel.AUTO)
                    }
                )

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )

                // ── 档位指南（阶梯总表 + 逐档卡片的 ModalBottomSheet）──
                ActionRow(
                    label = stringResource(R.string.code_thinking_guide_open),
                    icon = { tint ->
                        Icon(
                            Icons.Default.MenuBook,
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = {
                        expanded = false
                        onOpenGuide()
                    }
                )

                // ── 原生参数不生效提示（能力位门控；档位引擎侧效果照常）──
                if (!nativeEffortActive) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.code_thinking_no_native_effort),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

/** AUTO 元档行：AutoAwesome 图标井（tertiary 调）+ 预检决策回显。 */
@Composable
private fun AutoTierRow(
    selected: Boolean,
    decisionEcho: String?,
    onClick: () -> Unit
) {
    val rowBg by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f)
        else Color.Transparent,
        label = "autoRowBg"
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .background(color = rowBg, shape = RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp)
    ) {
        // 图标井（16dp 圆角 tertiary 调）
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f)
        ) {
            Icon(
                Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier
                    .padding(4.dp)
                    .size(14.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = codeThinkingShortName(CodeThinkingLevel.AUTO),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.code_thinking_auto_desc),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // AUTO 预检决策回显（发送前 resolveRuntimeThinkingLevel 产生）
            if (selected && !decisionEcho.isNullOrBlank()) {
                Text(
                    text = decisionEcho,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
    }
}

/** 通用动作行（指南入口等）：图标 + 标签，14dp 圆角点按反馈。 */
@Composable
private fun ActionRow(
    label: String,
    icon: @Composable (Color) -> Unit,
    iconTint: Color,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .background(Color.Transparent, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp)
    ) {
        Box(modifier = Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            icon(iconTint)
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** 档位 → 本地化短名（触发胶囊与下拉共用；恒英文：Off/Light/Standard/Deep/Max/Ultra/Apex/Auto）。 */
@Composable
internal fun codeThinkingShortName(level: CodeThinkingLevel): String = stringResource(
    when (level) {
        CodeThinkingLevel.NONE -> R.string.code_thinking_short_none
        CodeThinkingLevel.LIGHT -> R.string.code_thinking_short_light
        CodeThinkingLevel.STANDARD -> R.string.code_thinking_short_standard
        CodeThinkingLevel.DEEP -> R.string.code_thinking_short_deep
        CodeThinkingLevel.MAXIMUM -> R.string.code_thinking_short_maximum
        CodeThinkingLevel.ULTRACODE -> R.string.code_thinking_short_ultracode
        CodeThinkingLevel.APEXCODE -> R.string.code_thinking_short_apexcode
        CodeThinkingLevel.AUTO -> R.string.code_thinking_short_auto
    }
)

/** 档位 → 预算徽标（thinking_budget 短格式：0 / 256 / … / 64k；AUTO 无固定预算显示 auto）。 */
private fun codeThinkingBudgetLabel(level: CodeThinkingLevel): String {
    val budget = level.toThinkingBudget() ?: return "auto"
    return if (budget >= 1024) "${budget / 1024}k" else "$budget"
}

/** 档位 → 画像级一句话说明（coding 语境，全部使用 strings_code 自有键）。 */
@Composable
private fun codeThinkingLevelDetail(level: CodeThinkingLevel): String = when (level) {
    CodeThinkingLevel.NONE -> stringResource(R.string.code_thinking_none_desc)
    CodeThinkingLevel.LIGHT -> stringResource(R.string.code_thinking_light_desc)
    CodeThinkingLevel.STANDARD -> stringResource(R.string.code_thinking_standard_desc)
    CodeThinkingLevel.DEEP -> stringResource(R.string.code_thinking_deep_desc)
    CodeThinkingLevel.MAXIMUM -> stringResource(R.string.code_thinking_maximum_desc)
    CodeThinkingLevel.ULTRACODE -> stringResource(R.string.code_thinking_ultracode_desc)
    CodeThinkingLevel.APEXCODE -> stringResource(R.string.code_thinking_apexcode_desc)
    CodeThinkingLevel.AUTO -> stringResource(R.string.code_thinking_auto_desc)
}
