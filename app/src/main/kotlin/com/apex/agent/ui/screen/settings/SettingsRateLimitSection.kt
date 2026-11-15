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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.apex.agent.R

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
internal fun RateLimitSection(agent: AgentSettings, onAgent: (AgentSettings.() -> AgentSettings) -> Unit) {
    SectionCard(
        title = stringResource(R.string.settings_rate_limit_section),
        icon = Icons.Outlined.Speed,
        initiallyExpanded = true
    ) {
        SwitchRow(
            label = stringResource(R.string.settings_rate_limit_enabled),
            checked = agent.rateLimitFriendly,
            description = stringResource(R.string.settings_rate_limit_enabled_desc)
        ) { on -> onAgent { copy(rateLimitFriendly = on) } }

        if (agent.rateLimitFriendly) {
            SliderRow(
                label = stringResource(R.string.settings_rate_limit_interval),
                value = (agent.rateLimitMinIntervalMs / 1000f),
                range = 0f..30f,
                steps = 29,
                description = stringResource(R.string.settings_rate_limit_interval_desc),
                onValueChange = {
                    onAgent { copy(rateLimitMinIntervalMs = (it * 1000).toInt()) }
                },
                fmt = { if (it <= 0f) "0" else "%.1fs".format(it) }
            )
            SwitchRow(
                label = stringResource(R.string.settings_rate_limit_rich),
                checked = agent.rateLimitRichPayload,
                description = stringResource(R.string.settings_rate_limit_rich_desc)
            ) { on -> onAgent { copy(rateLimitRichPayload = on) } }
        }

        HorizontalDivider()
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.settings_rate_limit_apply_title),
                style = MaterialTheme.typography.labelMedium
            )
            DescriptionText(stringResource(R.string.settings_rate_limit_apply_desc))
            // B2-BUG-23:跨 4 字段批量修改先弹确认(字段/旧值/新值/生效时机),
            // 应用后提供撤销 —— 旧实现一键静默改 4 个字段且不告知 toolCallParallelism
            // 属 DI 快照需重启。
            val targets = listOf(
                Triple(stringResource(R.string.settings_rate_limit_field_friendly), agent.rateLimitFriendly, true),
                Triple(
                    stringResource(R.string.settings_rate_limit_field_interval),
                    agent.rateLimitMinIntervalMs,
                    agent.rateLimitMinIntervalMs.coerceAtLeast(4000)
                ),
                Triple(stringResource(R.string.settings_rate_limit_field_payload), agent.rateLimitRichPayload, true),
                Triple(
                    stringResource(R.string.settings_rate_limit_field_parallel),
                    agent.toolCallParallelism,
                    agent.toolCallParallelism.coerceAtLeast(4)
                )
            )
            var showConfirm by rememberSaveable { mutableStateOf(false) }
            var undoSnapshot by remember { mutableStateOf(agent) }
            var undoVisible by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth()) {
                Button(onClick = { showConfirm = true }) {
                    Text(stringResource(R.string.settings_rate_limit_apply))
                }
            }
            if (undoVisible) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.settings_rate_limit_applied),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        onAgent {
                            copy(
                                rateLimitFriendly = undoSnapshot.rateLimitFriendly,
                                rateLimitMinIntervalMs = undoSnapshot.rateLimitMinIntervalMs,
                                rateLimitRichPayload = undoSnapshot.rateLimitRichPayload,
                                toolCallParallelism = undoSnapshot.toolCallParallelism
                            )
                        }
                        undoVisible = false
                    }) { Text(stringResource(R.string.settings_rate_limit_undo)) }
                }
            }
            if (showConfirm) {
                AlertDialog(
                    onDismissRequest = { showConfirm = false },
                    confirmButton = {
                        TextButton(onClick = {
                            val before = agent
                            onAgent {
                                copy(
                                    rateLimitFriendly = true,
                                    rateLimitMinIntervalMs = rateLimitMinIntervalMs.coerceAtLeast(4000),
                                    rateLimitRichPayload = true,
                                    toolCallParallelism = toolCallParallelism.coerceAtLeast(4)
                                )
                            }
                            undoSnapshot = before
                            undoVisible = true
                            showConfirm = false
                        }) { Text(stringResource(R.string.settings_rate_limit_apply)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showConfirm = false }) {
                            Text(stringResource(R.string.settings_cancel))
                        }
                    },
                    title = { Text(stringResource(R.string.settings_rate_limit_apply_title)) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            targets.forEach { (label, oldV, newV) ->
                                Text(
                                    "$label: $oldV → $newV",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Text(
                                stringResource(R.string.settings_rate_limit_restart_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                )
            }
        }
    }
}
