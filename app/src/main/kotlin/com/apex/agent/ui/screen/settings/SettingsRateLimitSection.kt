package com.apex.agent.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment

/**
 * ═══ 模型限速友好（model/rate-limit 叶子页）═══
 *
 * **只针对某些用户**：所用厂商有 RPM / TPM / 日请求次数配额（免费档、
 * 按次计费网关、严格限流端点）时才需要打开；配额充裕的用户保持默认关
 * （零行为变化）。
 *
 * 三根支柱（全部引擎/运行时真接线，非数据预埋）：
 * 1. **请求节流**（[AgentSettings.rateLimitMinIntervalMs]）：所有 LLM 请求
 *    发出前在 RateLimitGovernor 排队——含 ReAct 轮 / 压缩摘要 / 子代理 /
 *    目标验收的每一次尝试，无旁路；
 * 2. **每请求信息密度**（[AgentSettings.rateLimitRichPayload]）：工具输出
 *    预算 ×2 / 保留近轮 +3 / 压缩更晚——单请求携带更多上下文，同样的任务
 *    用更少的请求完成；
 * 3. **一键推荐组合**：并行工具执行 ×4（一轮多工具 = 少几轮请求）+
 *    上述两项——把「减请求」策略一次拉满。
 *
 * 生效时机：节流参数即时生效（策略热读，下一个请求起算）；引擎旋钮
 * （信息密度 / 并行度）重启应用生效（DI 快照语义）。
 */
@Composable
internal fun RateLimitSection(agent: AgentSettings, onAgent: (AgentSettings) -> Unit) {
    // BUG-23（设置 spec 02）：一键组合的待确认状态（确认前不写盘）
    var pendingApply by rememberSaveable { mutableStateOf(false) }

    SectionCard(
        title = stringResource(R.string.settings_rate_limit_section),
        icon = Icons.Outlined.Speed,
        initiallyExpanded = true
    ) {
        SwitchRow(
            label = stringResource(R.string.settings_rate_limit_enabled),
            checked = agent.rateLimitFriendly,
            description = stringResource(R.string.settings_rate_limit_enabled_desc)
        ) { on -> onAgent(agent.copy(rateLimitFriendly = on)) }

        if (agent.rateLimitFriendly) {
            SliderRow(
                label = stringResource(R.string.settings_rate_limit_interval),
                value = (agent.rateLimitMinIntervalMs / 1000f),
                range = 0f..30f,
                steps = 29,
                description = stringResource(R.string.settings_rate_limit_interval_desc),
                onValueChange = {
                    onAgent(agent.copy(rateLimitMinIntervalMs = (it * 1000).toInt()))
                },
                fmt = { if (it <= 0f) "0" else "%.1fs".format(it) }
            )
            SwitchRow(
                label = stringResource(R.string.settings_rate_limit_rich),
                checked = agent.rateLimitRichPayload,
                description = stringResource(R.string.settings_rate_limit_rich_desc)
            ) { on -> onAgent(agent.copy(rateLimitRichPayload = on)) }
        }

        HorizontalDivider()
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.settings_rate_limit_apply_title),
                style = MaterialTheme.typography.labelMedium
            )
            DescriptionText(stringResource(R.string.settings_rate_limit_apply_desc))
            Row(Modifier.fillMaxWidth()) {
                Button(onClick = { pendingApply = true }) {
                    Text(stringResource(R.string.settings_rate_limit_apply))
                }
            }
        }
        // BUG-23（设置 spec 02）：跨四字段批量修改先弹确认表（字段/旧值→新值/
        // 生效时机），确认前不写盘 —— 此前一键组合静默覆盖，且
        // toolCallParallelism 属 DI 快照字段（需重启生效）完全不告知。
        if (pendingApply) {
            val targetFriendly = true
            val targetInterval = maxOf(agent.rateLimitMinIntervalMs, 4000L)
            val targetRich = true
            val targetParallel = maxOf(agent.toolCallParallelism, 4)
            AlertDialog(
                onDismissRequest = { pendingApply = false },
                title = { Text(stringResource(R.string.settings_rate_limit_confirm_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ConfirmRow(
                            stringResource(R.string.settings_rate_limit_field_friendly),
                            agent.rateLimitFriendly, targetFriendly
                        )
                        ConfirmRow(
                            stringResource(R.string.settings_rate_limit_field_interval),
                            agent.rateLimitMinIntervalMs, targetInterval
                        )
                        ConfirmRow(
                            stringResource(R.string.settings_rate_limit_field_rich),
                            agent.rateLimitRichPayload, targetRich
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ConfirmRow(
                                stringResource(R.string.settings_rate_limit_field_parallel),
                                agent.toolCallParallelism, targetParallel
                            )
                            Spacer(Modifier.width(6.dp))
                            // DI 快照字段：需重启生效（spec 明示必须告知）
                            Surface(
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                shape = MaterialTheme.shapes.small,
                            ) {
                                Text(
                                    stringResource(R.string.settings_effect_restart),
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        onAgent(
                            agent.copy(
                                rateLimitFriendly = targetFriendly,
                                rateLimitMinIntervalMs = targetInterval,
                                rateLimitRichPayload = targetRich,
                                toolCallParallelism = targetParallel
                            )
                        )
                        pendingApply = false
                    }) { Text(stringResource(R.string.settings_rate_limit_confirm_apply)) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingApply = false }) { Text(stringResource(R.string.settings_cancel)) }
                },
            )
        }
    }
}

/** BUG-23：确认表单行「字段： 旧值 → 新值」（相同则显示「不变」）。 */
@Composable
private fun ConfirmRow(label: String, old: Any?, new: Any?) {
    val unchanged = old == new
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        Text(
            if (unchanged) {
                stringResource(R.string.settings_confirm_unchanged)
            } else {
                "$old → $new"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (unchanged) {
                MaterialTheme.colorScheme.outline
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
    }
}
