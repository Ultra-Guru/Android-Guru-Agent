package com.apex.agent.ui.screen.code.stream

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.core.code.stream.LlmRetryInfo
import com.apex.agent.core.code.stream.StreamEntry
import com.apex.agent.core.code.stream.StreamToolCall
import com.apex.agent.core.code.stream.UnifiedDiffParser
import androidx.compose.ui.semantics.Role
import com.apex.agent.ui.component.MarkdownText
import com.apex.agent.ui.glass.PartSurface
import com.apex.agent.ui.theme.CodingPart
import com.apex.agent.ui.theme.LocalExtendedColors
import androidx.compose.foundation.layout.PaddingValues

/**
 * # Code Stream Cards — 时间轴卡片族
 *
 * 胶囊之外的时间轴成员：思考链（独立可折叠）/ 验证轮次卡（可折叠轮次
 * 胶囊）/ 结构化错误卡（失败结尾的「继续完成」入口）/ 停止卡 / Git 操作
 * 可视化卡 / 受影响文件 chips / 流式结论气泡 / 用户气泡 / 状态与系统行。
 * 正文气泡只放结论（Markdown 渲染，容忍不完整片段），思考链与工具细节
 * 全部走独立卡片——规格书【0】的「非聊天」立场。
 *
 * @param llmRetry LLM 重试等待中（思考卡标题「思考中…」切换为
 *   「重试中 · 第 N/T 次」；null = 正常运行）
 * @param onRetryRun 失败结尾「继续完成」回调（ErrorCard 尾部的播放入口；
 *   null = 不渲染动作行）
 */
@Composable
internal fun StreamCard(
    entry: StreamEntry,
    onToolClick: (StreamToolCall) -> Unit,
    llmRetry: LlmRetryInfo? = null,
    onRetryRun: (() -> Unit)? = null
) {
    when (entry) {
        is StreamEntry.UserEntry -> UserBubble(entry.text)
        is StreamEntry.AssistantEntry -> AssistantBubble(entry.text, entry.isStreaming)
        is StreamEntry.ThinkingEntry -> ThinkingCard(entry.text, entry.isStreaming, llmRetry)
        is StreamEntry.ToolCapsuleEntry -> CodeCapsule(entry.call, onToolClick)
        is StreamEntry.VerifyCycleEntry -> VerifyCycleCard(entry, onToolClick)
        is StreamEntry.StatusEntry -> LightLine(entry.text)
        is StreamEntry.SystemEntry -> SystemLine(entry.text)
        is StreamEntry.ErrorEntry -> ErrorCard(entry, onRetryRun)
        is StreamEntry.StopEntry -> StopCard(entry.reason)
        is StreamEntry.FileChipsEntry -> FileChipsCard(entry.files)
        is StreamEntry.GitOpEntry -> GitOpVisualCard(entry)
        is StreamEntry.RunStatsEntry -> RunStatsCard(entry)
    }
}

// ═══ 用户气泡 ═══

@Composable
private fun UserBubble(text: String) {
    // PartSkin spec §4.1：stream.user_bubble —— SOLID 实色气泡（新默认
    // primaryContainer + onPrimaryContainer，与 Rikka 用户气泡同语言；
    // 「谁说的」与浅白玻璃的结论气泡一眼可辨）。
    PartSurface(
        part = CodingPart.STREAM_USER_BUBBLE,
        shape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

// ═══ 结论气泡（Markdown，流式容忍）═══

@Composable
private fun AssistantBubble(text: String, isStreaming: Boolean) {
    // v5 流式玻璃 → v1.7 部件皮肤：结论气泡从 GlassCard(accent=primary)
    // 改走 PartSurface(stream.assistant_bubble) —— 新默认 PALE_LIQUID
    // 浅白液态玻璃（FR-11：accent 移除，语义色只上图标/文字）；流式光标
    // 保留。换皮只换容器，Markdown 容忍渲染与光标逻辑零改动。
    PartSurface(
        part = CodingPart.STREAM_ASSISTANT_BUBBLE,
        shape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
        active = isStreaming,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
    ) {
        MarkdownText(markdown = text)
        if (isStreaming && text.isEmpty()) {
            Text(
                text = stringResource(R.string.code_stream_status_waiting),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (isStreaming) {
            Text(
                text = "▍",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

// ═══ 思考链卡（单独、可折叠）═══

@Composable
private fun ThinkingCard(
    text: String,
    isStreaming: Boolean,
    llmRetry: LlmRetryInfo? = null
) {
    // 注意 key：不能用 text.isEmpty() 这类派生值——空思考卡点开后首个
    // token 到达（isEmpty 翻转）会重置展开态，吞掉用户的点击
    //
    // 胶囊化展开态（用户需求：「思考时展示全部思考内容，思考完自动折叠
    // 成胶囊」）：流式期间默认展开跟随全文；流式收束（isStreaming 翻转）
    // 自动折叠 —— 用户手动点过则尊重手动状态，不再自动干预。
    // FR-9：展开态 key 与 skin 无关（remember 不带 skin —— 换皮不重置）。
    var expanded by remember { mutableStateOf(isStreaming) }
    var userToggled by remember { mutableStateOf(false) }
    LaunchedEffect(isStreaming) {
        if (!userToggled) expanded = isStreaming
    }
    // v1.7 部件皮肤（PartSkin spec FR-5）：思考卡默认浅白液态玻璃 ——
    // PALE_LIQUID 乳白霜面 + 微彩光晕；重试态的红色只上图标/标题文字
    // （FR-11 淘汰 accent=error 整卡染色，消灭粉红卡）；展开/折叠点击收
    // 在容器层（onClick），涟漪经 clip 跟随卡面圆角。
    PartSurface(
        part = CodingPart.STREAM_THINKING,
        active = isStreaming,
        onClick = {
            userToggled = true
            expanded = !expanded
        },
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
    ) {
        // v1.2 G2 内部件（P0-E）：标题行/正文可独立换肤；默认 EMBEDDED
        //（零容器，contentPadding=0 保持原视觉），H-4 由父卡 PartSurface
        // 下发 LocalParentSkin 参与嵌套退化。
        PartSurface(
            part = CodingPart.STREAM_THINKING_HEADER,
            contentPadding = PaddingValues(0.dp)
        ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // ═══ 标题：重试等待中 = 「重试中 · 第 N/T 次」（用户规格
            // 2026-11：重试期间「思考中/正在工作」切换为「重试中」）═══
            if (llmRetry != null) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(
                        R.string.code_stream_retrying_fmt,
                        llmRetry.attempt,
                        llmRetry.totalRetries
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error
                )
                // 阶梯延迟副语（3s 后再试 / 1m 后再试）——重试节奏可见
                if (llmRetry.delayMs > 0) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(
                            R.string.code_stream_retry_delay_fmt,
                            formatRetryDelayLabel(llmRetry.delayMs)
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.75f)
                    )
                }
            } else {
                Icon(
                    imageVector = Icons.Default.Psychology,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (isStreaming) stringResource(R.string.code_stream_thinking_running)
                    else stringResource(R.string.code_stream_thinking_done),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.weight(1f))
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(14.dp)
            )
        }
        }
        PartSurface(
            part = CodingPart.STREAM_THINKING_BODY,
            contentPadding = PaddingValues(0.dp)
        ) {
        AnimatedVisibility(visible = expanded) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        if (!expanded && text.isNotBlank()) {
            Text(
                text = text.take(80),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
            }
        }
    }
}

// ═══ 验证轮次卡（可折叠「轮次胶囊」）═══

@Composable
private fun VerifyCycleCard(entry: StreamEntry.VerifyCycleEntry, onToolClick: (StreamToolCall) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val passed = entry.cycle.passed
    val tint = when (passed) {
        true -> MaterialTheme.colorScheme.primary
        false -> MaterialTheme.colorScheme.error
        null -> MaterialTheme.colorScheme.outline
    }
    // 轮内改动量可视化：编辑胶囊 diff 原文现场解析求和（绿增红删徽标）。
    // 恢复回放的轮次 calls 为空 → null（不显徽标，轮次红绿态仍保留）。
    val delta = remember(entry) {
        val parsed = entry.calls
            .filter { it.isEditFamily }
            .mapNotNull { it.diffText }
            .map { UnifiedDiffParser.parse(it) }
            .filter { it.hunks.isNotEmpty() }
        if (parsed.isEmpty()) null
        else parsed.sumOf { it.totalAdded } to parsed.sumOf { it.totalRemoved }
    }
    // v1.7 部件皮肤：验证轮次卡默认 PALE_LIQUID；通过/失败语义色只上
    // 图标与文字（FR-11 淘汰 tint.copy(alpha=0.06f) 容器染色）。
    PartSurface(
        part = CodingPart.STREAM_VERIFY_CYCLE,
        onClick = { expanded = !expanded },
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.code_stream_verify_round_fmt, entry.cycle.round),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = when (passed) {
                    true -> stringResource(R.string.code_stream_verify_passed)
                    false -> stringResource(R.string.code_stream_verify_failed)
                    null -> stringResource(R.string.code_stream_verify_unknown)
                },
                style = MaterialTheme.typography.labelSmall,
                color = tint
            )
            delta?.let { (added, removed) ->
                if (added + removed > 0) {
                    Spacer(Modifier.width(6.dp))
                    VerifyDeltaBadge(added = added, removed = removed)
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(
                    R.string.code_stream_verify_counts_fmt,
                    entry.cycle.editCallIds.size,
                    entry.cycle.verifyCallIds.size
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(14.dp)
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.padding(top = 6.dp)
            ) {
                entry.calls.forEach { call ->
                    CodeCapsule(call = call, onClick = onToolClick)
                }
                if (entry.calls.isEmpty()) {
                    Text(
                        text = stringResource(R.string.code_stream_verify_details_gone),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

// ═══ 运行统计收尾卡（Complete 事件的结构化可视化）═══

/**
 * 一轮运行收尾的统计卡：头行（完成图标 + 总耗时）+ FlowRow 象征 chips
 * （迭代轮数 / 工具调用 / 触碰文件 / 失败数——失败 >0 红色强调）。
 * 取代旧纯文本「完成 · N 轮 · N 次工具 · Ns」状态行——同样的数字，
 * 可扫读的版式（图标 + 数值 + 标签三段式）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RunStatsCard(entry: StreamEntry.RunStatsEntry) {
    // 失败 >0：头行用错误色转述（成功轮次一律 primary，不告警）—— v1.7
    // 部件皮肤：语义色只上图标/chip 文字（FR-11），容器不再 accent 染色。
    val failed = entry.failedCalls > 0
    val statusTint = if (failed) MaterialTheme.colorScheme.error
    else MaterialTheme.colorScheme.primary
    PartSurface(
        part = CodingPart.STREAM_RUN_STATS,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (failed) Icons.Default.BugReport else Icons.Default.CheckCircle,
                contentDescription = null,
                tint = statusTint,
                modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.code_stream_run_stats_title),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = formatCapsuleDuration(entry.durationMs),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(top = 6.dp)
        ) {
            StatChip(
                icon = Icons.Default.Loop,
                label = stringResource(R.string.code_stream_run_iterations_fmt, entry.iterations)
            )
            StatChip(
                icon = Icons.Default.Build,
                label = stringResource(R.string.code_stream_run_tools_fmt, entry.toolCalls)
            )
            // 溯源分解（v1.5.1）：技能 / MCP 调用数（非零才显）——本轮
            // 用了哪些能力生态一眼可辨（与聚合卡同源会计）
            if (entry.skillCalls > 0) {
                StatChip(
                    icon = Icons.Default.AutoAwesome,
                    label = stringResource(R.string.code_stream_run_skills_fmt, entry.skillCalls)
                )
            }
            if (entry.mcpCalls > 0) {
                StatChip(
                    icon = Icons.Default.Extension,
                    label = stringResource(R.string.code_stream_run_mcp_fmt, entry.mcpCalls)
                )
            }
            if (entry.filesTouched > 0) {
                StatChip(
                    icon = Icons.AutoMirrored.Filled.Notes,
                    label = stringResource(R.string.code_stream_run_files_fmt, entry.filesTouched)
                )
            }
            if (failed) {
                StatChip(
                    icon = Icons.Default.BugReport,
                    label = stringResource(R.string.code_stream_run_failed_fmt, entry.failedCalls),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/** 重试延迟的人读形态（1s / 30s / 1m / 3m，与 core 侧状态行同口径）。 */
private fun formatRetryDelayLabel(ms: Long): String = when {
    ms < 1_000 -> "${ms}ms"
    ms < 60_000 -> "${ms / 1000}s"
    else -> "${ms / 60_000}m"
}

/** 单枚统计 chip：图标 + 文案（surfaceContainer 薄底，非交互展示）。 */
@Composable
private fun StatChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: androidx.compose.ui.graphics.Color? = null) {
    val color = tint ?: MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = color
            )
        }
    }
}

/** 轮次头行的增删行徽标（与流式代码窗 / 终态 diff 统计同款 +N −M）。 */
@Composable
private fun VerifyDeltaBadge(added: Int, removed: Int) {
    Text(
        text = stringResource(R.string.code_stream_diff_stat_fmt, added, removed),
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = LocalExtendedColors.current.success
    )
}

// ═══ 结构化错误卡 ═══

/**
 * 错误卡：原因 + 简短文案 + **「继续完成」动作行**（用户规格 2026-11：
 * 任务失败结尾必须有继续推进的入口——播放入口重放本任务输入，与底部
 * ErrorBar 的「重试」同语义，时间轴内自含闭环）。
 *
 * v1.7 部件皮肤：默认 PALE_LIQUID 浅白玻璃 —— 红色仅上图标/标题文字
 * （FR-11 淘汰 errorContainer@0.5f 整卡染红，消灭粉红卡）；正文/提示
 * 改 onSurface 保证浅白底对比度（NFR-6 ≥ 4.5:1）。
 */
@Composable
private fun ErrorCard(entry: StreamEntry.ErrorEntry, onRetryRun: (() -> Unit)? = null) {
    PartSurface(
        part = CodingPart.STREAM_ERROR,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.BugReport,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (entry.recoverable) stringResource(R.string.code_stream_error_recoverable)
                else stringResource(R.string.code_stream_error_fatal),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error
            )
        }
        Text(
            text = entry.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 4.dp)
        )
        entry.hint?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        // ═══ 继续完成（失败结尾的动作行）═══
        if (onRetryRun != null && entry.recoverable) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.10f),
                    modifier = Modifier
                        // 圆角反馈修复：clip 先于 clickable —— 涟漪跟随 8dp 圆角
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button) { onRetryRun() }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = stringResource(R.string.code_stream_error_continue),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = stringResource(R.string.code_stream_error_continue),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

// ═══ 停止卡 ═══

@Composable
private fun StopCard(reason: String) {
    PartSurface(
        part = CodingPart.STREAM_STOP,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.StopCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = reason,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ═══ 受影响文件 chips（FlowRow 换行 + 超 6 个显式提示）═══

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FileChipsCard(files: List<String>) {
    // v1.7 部件皮肤：文件 chips 默认 FLAT 裸渲染（与状态行同款 bareInFlat）。
    PartSurface(
        part = CodingPart.STREAM_FILE_CHIPS,
        bareInFlat = true,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Notes,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(13.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.code_stream_files_touched, files.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // FlowRow：文件名普遍 110-150dp，普通 Row 三个即溢出屏宽且不可达
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(top = 4.dp)
        ) {
            files.take(FILE_CHIPS_MAX).forEach { file ->
                // 非交互展示 chip（原 AssistChip + 空 onClick 是欺骗性可供性：
                // TalkBack 播报可激活但无行为）
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Text(
                        text = file.substringAfterLast('/'),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
            if (files.size > FILE_CHIPS_MAX) {
                Text(
                    text = stringResource(R.string.code_stream_files_more_fmt, files.size - FILE_CHIPS_MAX),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp)
                )
            }
        }
    }
}

// ═══ 状态 / 系统行 ═══

@Composable
private fun LightLine(text: String) {
    // v1.7 部件皮肤：状态行默认 FLAT **裸渲染**（§4「细状态行不套玻璃」
    // —— bareInFlat）；用户切 PALE/CLASSIC 时获得容器（可切换性，FR-7）。
    PartSurface(
        part = CodingPart.STREAM_STATUS_LINE,
        bareInFlat = true,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

@Composable
private fun SystemLine(text: String) {
    // 同 LightLine：系统行默认 FLAT 裸渲染（tertiary 强调色保留）。
    PartSurface(
        part = CodingPart.STREAM_SYSTEM_LINE,
        bareInFlat = true,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary
        )
    }
}

private const val FILE_CHIPS_MAX = 6
