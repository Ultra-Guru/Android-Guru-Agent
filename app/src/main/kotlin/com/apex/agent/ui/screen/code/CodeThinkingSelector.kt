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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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

/**
 * # Code Thinking Selector — Coding 模式思考档位选择器（v3 重设计）
 *
 * ## v3 设计（2026-10 用户需求「做得精美一点，要用圆角，也要用英文」）
 * - **全圆角**：菜单容器 24dp 圆角；档位行选中态 14dp 圆角胶囊底；预算
 *   徽标 8dp 圆角小票；触发胶囊 50% 圆角不变；
 * - **英文档名**：档位短名（Off/Light/Standard/Deep/Max/Ultra/Apex/
 *   Adaptive）恒英文（zh 资源同步英文化——档名是跨模型技术词汇，
 *   中文直译「超频/顶点」反而歧义）；
 * - **深度阶梯可视化**：每档行首 7 段递增音量条（深度仪表），选中档
 *   主色点亮 + 未选档灰阶余量 —— 不读文字也能感知档位深浅；
 * - **系统感知宽度**：`widthIn(min 300, max 屏宽-24dp)` —— 窄屏自动
 *   收缩，悬浮框永不溢出屏幕（与 Agent 屏 ThinkingControlMenu 同款
 *   修复）；
 * - 段落结构不变（v2 语义保留）：深度七档 / AUTO 元档 / 档位指南 /
 *   原生参数生效提示。
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

        // ── 结构化下拉（24dp 圆角容器；系统感知宽度）──
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
                // ── 标题行：脑图标 + 英文标题 ──
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
                Spacer(Modifier.height(4.dp))

                // 段 1：深度七档（NONE..APEXCODE；AUTO 元档不算深度轴）
                CodeThinkingLevel.entries.filter { it.isDepthTier }.forEachIndexed { tierIndex, level ->
                    val selected = level == current
                    TierRow(
                        level = level,
                        tierIndex = tierIndex,
                        tierCount = CodeThinkingLevel.entries.count { it.isDepthTier },
                        selected = selected,
                        onClick = {
                            expanded = false
                            onSelect(level)
                        }
                    )
                }

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )

                // 段 2：AUTO 元档（发送前预检 + 运行中深水区升级，coding 自治）
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

                // 段 3：档位指南（阶梯总表 + 逐档卡片的 ModalBottomSheet）
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

                // 段 4：原生参数不生效提示（能力位门控；档位引擎侧效果照常）
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

/**
 * 深度档行：递增音量条（深度仪表）+ 英文档名 + 预算徽标 + 选中态
 * （14dp 圆角胶囊底 + Check + 主色点亮）。
 */
@Composable
private fun TierRow(
    level: CodeThinkingLevel,
    tierIndex: Int,
    tierCount: Int,
    selected: Boolean,
    onClick: () -> Unit
) {
    val rowBg by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.secondaryContainer
        else Color.Transparent,
        label = "tierRowBg"
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
        // 深度仪表：tierCount 段递增音量条 —— 点亮 (tierIndex+1) 段
        DepthMeter(
            tierIndex = tierIndex,
            tierCount = tierCount,
            active = selected,
            modifier = Modifier.width(26.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = codeThinkingShortName(level),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.weight(1f))
                // 预算徽标（8dp 圆角小票）
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    else MaterialTheme.colorScheme.surfaceContainerHighest
                ) {
                    Text(
                        text = codeThinkingBudgetLabel(level),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                    )
                }
            }
            // 仅选中档展开一行画像说明（免文字墙）
            if (selected) {
                Text(
                    text = codeThinkingLevelDetail(level),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 1.dp)
                )
            }
        }
        // 选中 Check（14dp 圆角主色点）
        if (selected) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.primary
            ) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .padding(2.dp)
                        .size(12.dp)
                )
            }
        } else {
            Spacer(modifier = Modifier.size(18.dp))
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

/**
 * 深度仪表（递增音量条）：[tierCount] 段从小到大的竖条，前
 * [tierIndex] + 1 段点亮 —— 一眼读出档位在阶梯中的位置。
 */
@Composable
private fun DepthMeter(
    tierIndex: Int,
    tierCount: Int,
    active: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(1.5.dp),
        modifier = modifier
    ) {
        val litColor = if (active) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant
        val dimColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
        repeat(tierCount) { i ->
            val height = 5.dp + (i * 1.6.dp)
            Box(
                modifier = Modifier
                    .width(2.5.dp)
                    .height(height)
                    .background(
                        color = if (i <= tierIndex) litColor else dimColor,
                        shape = RoundedCornerShape(1.dp)
                    )
            )
        }
    }
}

/** 档位 → 本地化短名（触发胶囊与下拉共用；恒英文：Off/Light/Standard/Deep/Max/Ultra/Apex/Adaptive）。 */
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

/** 档位 → 预算徽标（thinking_budget 短格式：0 / 256 / … / 64k；AUTO 显示预检符号）。 */
private fun codeThinkingBudgetLabel(level: CodeThinkingLevel): String {
    val budget = level.toThinkingBudget() ?: return "⚡"
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
