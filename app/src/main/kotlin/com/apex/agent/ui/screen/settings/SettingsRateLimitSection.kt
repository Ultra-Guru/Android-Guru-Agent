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
 *    任务轮次无上限 + 上述两项——把「减请求」策略一次拉满。
 *
 * 生效时机：节流参数即时生效（策略热读，下一个请求起算）；引擎旋钮
 * （信息密度 / 并行度）重启应用生效（与 maxIterations 同款 DI 快照语义）。
 */
@Composable
internal fun RateLimitSection(agent: AgentSettings, onAgent: (AgentSettings) -> Unit) {
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
                Button(
                    onClick = {
                        onAgent(
                            agent.copy(
                                rateLimitFriendly = true,
                                rateLimitMinIntervalMs = agent.rateLimitMinIntervalMs
                                    .coerceAtLeast(4000),
                                rateLimitRichPayload = true,
                                toolCallParallelism = agent.toolCallParallelism.coerceAtLeast(4),
                                maxIterations = 0
                            )
                        )
                    }
                ) {
                    Text(stringResource(R.string.settings_rate_limit_apply))
                }
            }
        }
    }
}
