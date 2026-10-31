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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Send
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
import com.apex.agent.core.code.standard.StandardLogicMode

/**
 * # Code Logic Mode Selector — Coding 屏右上角「思考逻辑」切换器
 *
 * Coding 模式的完成任务逻辑四引擎切换：
 *
 * - **深潜**（[StandardLogicMode.DEEP_DIVE]）：项目自研思考逻辑——七档
 *   思考阶梯 + AUTO 预检选档 + 深水区升级（默认）；
 * - **标准**（[StandardLogicMode.STANDARD]）：业界标准 Agent 任务循环
 *   ——Agent 画像、权限三态门、隔离上下文子代理、会话压缩、task 委派；
 * - **织造**（[StandardLogicMode.LOOM]）：经纬交织范式——in-band XML
 *   工具协议 + 梭回合机 + 权限模态门 + 子线程群 + 织造包 + 运行中转向；
 * - **信使**（[StandardLogicMode.COURIER]）：驿传调度范式——收发匣投递 +
 *   趟次步进机 + 勘合通行证 + 档房压缩 + 下站子驿委派。
 *
 * ## UI 规格（对齐 CodeThinkingSelector v2 的设计语言）
 *
 * - 触发胶囊：28dp 无边框，13dp 图标 + 本地化短名；四线配色区分
 *   （深潜 secondary / 标准 tertiary / 织造 primary / 信使 surfaceVariant 容器）；
 * - 下拉（宽 300dp）：四逻辑项（选中 Check + 主色加粗 + 一行画像说明）
 *   + 分隔线 + 当前引擎能力徽标行；
 * - 运行中切换被 VM 拒绝（返回 false）→ 选中项正下方行内告警（2 秒自清）。
 */
@Composable
internal fun CodeLogicModeSelector(
    current: StandardLogicMode,
    onSwitchBlocked: Boolean,
    onSelect: (StandardLogicMode) -> Boolean
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        // ── 触发胶囊（28dp 无边框；2026-10 用户需求「圆角小一些」——
        //    10dp 圆角矩形，区别于思考档位胶囊的全圆）──
        Surface(
            onClick = { expanded = true },
            shape = RoundedCornerShape(10.dp),
            color = when (current) {
                StandardLogicMode.STANDARD -> MaterialTheme.colorScheme.tertiaryContainer
                StandardLogicMode.LOOM -> MaterialTheme.colorScheme.primaryContainer
                StandardLogicMode.COURIER -> MaterialTheme.colorScheme.surfaceVariant
                StandardLogicMode.DEEP_DIVE -> MaterialTheme.colorScheme.secondaryContainer
            },
            contentColor = when (current) {
                StandardLogicMode.STANDARD -> MaterialTheme.colorScheme.onTertiaryContainer
                StandardLogicMode.LOOM -> MaterialTheme.colorScheme.onPrimaryContainer
                StandardLogicMode.COURIER -> MaterialTheme.colorScheme.onSurfaceVariant
                StandardLogicMode.DEEP_DIVE -> MaterialTheme.colorScheme.onSecondaryContainer
            },
            modifier = Modifier.heightIn(min = 28.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Icon(
                    imageVector = when (current) {
                        StandardLogicMode.STANDARD -> Icons.Default.Route
                        StandardLogicMode.LOOM -> Icons.Default.Layers
                        StandardLogicMode.COURIER -> Icons.Default.Send
                        StandardLogicMode.DEEP_DIVE -> Icons.Default.Psychology
                    },
                    contentDescription = null,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = stringResource(
                        R.string.code_logic_chip,
                        logicModeShortName(current)
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }

        // ── 结构化下拉（宽 300dp：双逻辑项 / 引擎能力徽标）──
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(300.dp)
        ) {
            Text(
                text = stringResource(R.string.code_logic_selector_title),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
            StandardLogicMode.entries.forEach { mode ->
                val selected = mode == current
                DropdownMenuItem(
                    text = {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = logicModeShortName(mode),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    color = when {
                                        selected && mode == StandardLogicMode.STANDARD ->
                                            MaterialTheme.colorScheme.tertiary
                                        selected && mode == StandardLogicMode.LOOM ->
                                            MaterialTheme.colorScheme.primary
                                        selected && mode == StandardLogicMode.COURIER ->
                                            MaterialTheme.colorScheme.secondary
                                        selected -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.onSurface
                                    }
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                Text(
                                    text = stringResource(
                                        when (mode) {
                                            StandardLogicMode.STANDARD ->
                                                R.string.code_logic_badge_standard
                                            StandardLogicMode.LOOM ->
                                                R.string.code_logic_badge_loom
                                            StandardLogicMode.COURIER ->
                                                R.string.code_logic_badge_courier
                                            StandardLogicMode.DEEP_DIVE ->
                                                R.string.code_logic_badge_deep_dive
                                        }
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = stringResource(
                                    when (mode) {
                                        StandardLogicMode.STANDARD ->
                                            R.string.code_logic_standard_desc
                                        StandardLogicMode.LOOM ->
                                            R.string.code_logic_loom_desc
                                        StandardLogicMode.COURIER ->
                                            R.string.code_logic_courier_desc
                                        StandardLogicMode.DEEP_DIVE ->
                                            R.string.code_logic_deep_dive_desc
                                    }
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            // 切换被拒（运行中）提示——选中项正下方的行内告警
                            if (selected && onSwitchBlocked) {
                                Text(
                                    text = stringResource(R.string.code_logic_switch_blocked),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    },
                    leadingIcon = {
                        if (selected) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = when (mode) {
                                    StandardLogicMode.STANDARD ->
                                        MaterialTheme.colorScheme.tertiary
                                    StandardLogicMode.LOOM ->
                                        MaterialTheme.colorScheme.primary
                                    StandardLogicMode.COURIER ->
                                        MaterialTheme.colorScheme.secondary
                                    StandardLogicMode.DEEP_DIVE ->
                                        MaterialTheme.colorScheme.primary
                                },
                                modifier = Modifier.size(16.dp)
                            )
                        } else {
                            Spacer(modifier = Modifier.size(16.dp))
                        }
                    },
                    onClick = {
                        val accepted = onSelect(mode)
                        if (accepted) expanded = false
                        // 拒绝时保持下拉展开（onSwitchBlocked 提示行内展示）
                    }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

            // 引擎能力徽标行（当前引擎的关键能力点，静态展示）
            Text(
                text = stringResource(
                    when (current) {
                        StandardLogicMode.STANDARD -> R.string.code_logic_caps_standard
                        StandardLogicMode.LOOM -> R.string.code_logic_caps_loom
                        StandardLogicMode.COURIER -> R.string.code_logic_caps_courier
                        StandardLogicMode.DEEP_DIVE -> R.string.code_logic_caps_deep_dive
                    }
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }
    }
}

/** 逻辑 → 本地化短名（触发胶囊与下拉共用）。 */
@Composable
internal fun logicModeShortName(mode: StandardLogicMode): String = stringResource(
    when (mode) {
        StandardLogicMode.DEEP_DIVE -> R.string.code_logic_mode_deep_dive
        StandardLogicMode.STANDARD -> R.string.code_logic_mode_standard
        StandardLogicMode.LOOM -> R.string.code_logic_mode_loom
        StandardLogicMode.COURIER -> R.string.code_logic_mode_courier
    }
)
