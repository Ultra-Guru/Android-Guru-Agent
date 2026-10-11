package com.apex.agent.ui.screen.code

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.core.code.thinking.CodeThinkingLevel

/**
 * # Code Thinking Selector — Coding 模式思考档位选择器（v2 重构）
 *
 * ## v1 的问题（用户反馈「调节思考深度的 UI 做的不行」）
 * - 触发器是带边框 AssistChip（32dp，与左邻 AgentModeSelector 的 28dp
 *   无边框胶囊不齐）+ label 直接拼 `💭 ULTRACODE` 裸枚举名 + 图标双重表意；
 * - 下拉是 8 个双行菜单项的纯文字墙（≈500dp 高度），无当前档指示、无
 *   深度档/AUTO 元档的层级区隔、无深度阶梯可视化。
 *
 * ## v2 设计（对齐 Agent 屏 ThinkingControlMenu 修版模式）
 * - **触发器**：28dp 无边框胶囊（`RoundedCornerShape(50)`），13dp 图标 +
 *   本地化短档名（「思考 · 深度」）；AUTO 态换 tertiary 配色示差异；
 * - **下拉结构化**（宽 300dp，分三段）：
 *   1. 深度七档单行项 —— 选中档 Check + 主色加粗 + 预算徽标（0/256/…/64k），
 *      仅选中档展开一行画像说明（避免文字墙）；
 *   2. 分隔线 + AUTO 元档 —— AutoAwesome 图标 + 预检决策回显（AUTO 时）；
 *   3. 分隔线 + 档位指南入口（[CodeThinkingGuideSheet]）。
 * - **原生参数生效提示**（用户反馈「切换是不是真实的」）：
 *   [nativeEffortActive] 为 false 时下拉底部出一行说明 —— 档位的引擎侧
 *   效果照常，但 API 原生 reasoning 参数不发（能力位门控防 400），
 *   引导去设置开能力位。切档本身真实生效（见 CodeThinkingGuideSheet
 *   联动说明）。
 *
 * 档位枚举为 coding 专属的 [CodeThinkingLevel]（与 Agent 聊天页六档分立）。
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

        // ── 结构化下拉（宽 300dp：深度七档 / AUTO 元档 / 指南）──
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(300.dp)
        ) {
            // 段 1：深度七档（NONE..APEXCODE；AUTO 元档不算深度轴）
            Text(
                text = stringResource(R.string.code_thinking_selector_title),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
            CodeThinkingLevel.entries.filter { it.isDepthTier }.forEach { level ->
                val selected = level == current
                DropdownMenuItem(
                    text = {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = codeThinkingShortName(level),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                Text(
                                    text = codeThinkingBudgetLabel(level),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            // 仅选中档展开一行画像说明（免文字墙）
                            if (selected) {
                                Text(
                                    text = codeThinkingLevelDetail(level),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    leadingIcon = {
                        if (selected) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        } else {
                            Spacer(modifier = Modifier.size(16.dp))
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(level)
                    }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

            // 段 2：AUTO 元档（发送前预检 + 运行中深水区升级，coding 自治）
            DropdownMenuItem(
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = codeThinkingShortName(CodeThinkingLevel.AUTO),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (current == CodeThinkingLevel.AUTO) FontWeight.Bold else FontWeight.Normal,
                                color = if (current == CodeThinkingLevel.AUTO) MaterialTheme.colorScheme.tertiary
                                else MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            text = stringResource(R.string.code_thinking_auto_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // AUTO 预检决策回显（发送前 resolveRuntimeThinkingLevel 产生）
                        if (current == CodeThinkingLevel.AUTO && !adaptiveDecision.isNullOrBlank()) {
                            Text(
                                text = adaptiveDecision,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = if (current == CodeThinkingLevel.AUTO) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                },
                onClick = {
                    expanded = false
                    onSelect(CodeThinkingLevel.AUTO)
                }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

            // 段 3：档位指南（阶梯总表 + 逐档卡片的 ModalBottomSheet）
            DropdownMenuItem(
                text = { Text(stringResource(R.string.code_thinking_guide_open)) },
                leadingIcon = {
                    Icon(Icons.Default.MenuBook, contentDescription = null, modifier = Modifier.size(16.dp))
                },
                onClick = {
                    expanded = false
                    onOpenGuide()
                }
            )

            // 段 4：原生参数不生效提示（能力位门控；档位引擎侧效果照常）
            if (!nativeEffortActive) {
                Text(
                    text = stringResource(R.string.code_thinking_no_native_effort),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
        }
    }
}

/** 档位 → 本地化短名（触发胶囊与下拉共用；zh：无/极简/标准/深度/极致/超频/顶点/自适应）。 */
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
