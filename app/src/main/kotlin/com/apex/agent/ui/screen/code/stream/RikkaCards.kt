package com.apex.agent.ui.screen.code.stream

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.apex.agent.ui.component.MarkdownText
import com.apex.agent.ui.glass.PartSurface
import com.apex.agent.ui.theme.CodingPart
import androidx.compose.foundation.layout.PaddingValues
import kotlinx.coroutines.delay

/**
 * # Rikka Cards — Rikka 风格的时间轴卡片族
 *
 * 胶囊时间轴（CodeStreamCards）的 Rikka 皮肤版：工具胶囊改走聚合块
 * （RikkaToolBlock，时间轴分段层负责聚段），其余成员按聊天 App 语言
 * 重绘——用户右对齐实色气泡 / 结论 Markdown 气泡 / 思考过程折叠卡 /
 * 验证轮次卡 / 居中状态 pill / 错误与停止卡（含「继续完成」入口）/ 文件
 * chips / 运行统计卡。Git 操作可视化卡直接复用（GitOpVisualCard 与皮肤
 * 无关）。
 *
 * @param llmRetry LLM 重试等待中（思考卡标题「思考中 · X 秒」切换为
 *   「重试中 · 第 N/T 次」；null = 正常运行）
 * @param onRetryRun 失败结尾「继续完成」回调（错误卡尾部播放入口）
 */
@Composable
internal fun RikkaStreamCard(
    entry: StreamEntry,
    onToolClick: (StreamToolCall) -> Unit,
    thinkingCollapsed: Boolean = false,
    onThinkingCollapsedChange: (Boolean) -> Unit = {},
    onShowAnswer: (() -> Unit)? = null,
    llmRetry: LlmRetryInfo? = null,
    onRetryRun: (() -> Unit)? = null
) {
    when (entry) {
        is StreamEntry.UserEntry -> RikkaUserBubble(entry.text)
        is StreamEntry.AssistantEntry -> RikkaAssistantBubble(entry.text, entry.isStreaming)
        is StreamEntry.ThinkingEntry -> RikkaThinkingCard(entry.text, entry.isStreaming, thinkingCollapsed, onThinkingCollapsedChange, onShowAnswer, llmRetry)
        // 时间轴分段层已把连续胶囊收进 RikkaToolBlock，此分支只在单胶囊
        // 直渲染（防御）时触达——单调用块本身就是一行工具卡。
        is StreamEntry.ToolCapsuleEntry -> RikkaToolBlock(listOf(entry.call), onToolClick)
        is StreamEntry.VerifyCycleEntry -> RikkaVerifyCard(entry, onToolClick)
        is StreamEntry.StatusEntry -> RikkaPillLine(
            text = entry.text,
            emphasized = false,
            part = CodingPart.STREAM_STATUS_LINE
        )
        is StreamEntry.SystemEntry -> RikkaPillLine(
            text = entry.text,
            emphasized = true,
            part = CodingPart.STREAM_SYSTEM_LINE
        )
        is StreamEntry.ErrorEntry -> RikkaErrorCard(entry, onRetryRun)
        is StreamEntry.StopEntry -> RikkaStopCard(entry.reason)
        is StreamEntry.FileChipsEntry -> RikkaFileChips(entry.files)
        is StreamEntry.GitOpEntry -> GitOpVisualCard(entry)
        is StreamEntry.RunStatsEntry -> RikkaRunStatsCard(entry)
    }
}

// ═══ 用户气泡（右对齐——RikkaHub 同款：头像 + 顶角收紧的大圆角容器色气泡）═══
// rikkahub 形态：24dp 圆角、靠头像侧顶角 6dp 收紧、primaryContainer 柔和
// 容器色（非实色 primary）、气泡不占满行；左侧 28dp 圆形头像定锚「谁说的」。

@Composable
private fun RikkaUserBubble(text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        // v1.7 部件皮肤：stream.user_bubble —— SOLID 实色气泡（primaryContainer
        // + widthIn(max=320)，与胶囊时间轴 UserBubble 共享同一 Part ID）。
        PartSurface(
            part = CodingPart.STREAM_USER_BUBBLE,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 6.dp, bottomStart = 24.dp, bottomEnd = 24.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        // 头像（rikkahub 同尺寸 28dp；人形图标 + 容器色底，用户身份锚点）
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(5.dp)
                    .size(18.dp)
            )
        }
    }
}

// ═══ 结论区（RikkaHub 默认无助手气泡：Markdown 直铺背景 + 身份头行）═══
// rikkahub 的 showAssistantBubble 默认 false——助手正文直接铺在聊天背景
// 上渲染，靠顶部的头像/模型名行定锚。此处对齐：身份头行（终端图标井 +
// Guru）+ Markdown 直铺 + 流式光标；无容器色、零阴影。

@Composable
private fun RikkaAssistantBubble(text: String, isStreaming: Boolean) {
    // v1.7 部件皮肤：结论区默认 PALE_LIQUID 浅白玻璃（§4.1 —— 与胶囊
    // 时间轴 AssistantBubble 共享 stream.assistant_bubble）；身份头行 +
    // Markdown + 光标零改动，只换容器。切 FLAT 可回到 rikkahub 直铺形态。
    PartSurface(
        part = CodingPart.STREAM_ASSISTANT_BUBBLE,
        active = isStreaming,
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.size(20.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Terminal,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier
                        .padding(3.dp)
                        .size(14.dp)
                )
            }
            Text(
                text = stringResource(R.string.rikka_agent_label),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (isStreaming) {
                // 流式呼吸点（rikkahub DotLoading 同语义：轻量不抢戏）
                RikkaStreamingDot()
            }
        }
        MarkdownText(
            markdown = text,
            modifier = Modifier.padding(top = 4.dp)
        )
        if (isStreaming) {
            Text(
                text = "▍",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/** 流式呼吸圆点（10dp；与 rikkahub DotLoading 同语义的轻量加载指示）。 */
@Composable
private fun RikkaStreamingDot() {
    val pulse by rememberInfiniteTransition(label = "rikka-dot")
        .animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 700, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "rikka-dot-alpha"
        )
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary.copy(alpha = pulse),
        modifier = Modifier.size(10.dp)
    ) {}
}

// ═══ 思考过程折叠卡（RikkaHub 同款：实时秒表标题 + 流式展开 / 收束折叠）═══
// rikkahub 形态：「思考了 %1$.1f 秒」实时计时（50ms 级刷新的秒表语言），
// 流式中标题呼吸；完成后定格总时长。UI 层测时（ThinkingEntry 无耗时
// 字段，不侵入 core）：startedAt 首帧记录 + rememberSaveable 防列表滚动
// 重建重置；收束瞬间定格 finalElapsed。

@Composable
private fun RikkaThinkingCard(
    text: String,
    isStreaming: Boolean,
    defaultCollapsed: Boolean,
    onCollapsedChange: (Boolean) -> Unit,
    onShowAnswer: (() -> Unit)?,
    llmRetry: LlmRetryInfo? = null
) {
    // key 语义与胶囊思考卡同款：不能拿派生值当 key（空卡点开后首 token
    // 到达会重置展开态）；手动干预优先于自动展开/折叠。
    var expanded by rememberSaveable { mutableStateOf(isStreaming && !defaultCollapsed) }
    LaunchedEffect(isStreaming) {
        if (!isStreaming) expanded = false
    }
    val latestText by rememberUpdatedState(text)
    var displayed by remember { mutableStateOf(if (isStreaming) "" else text) }
    val reveal = remember { RikkaThinkingReveal() }
    LaunchedEffect(isStreaming) {
        if (!isStreaming) {
            displayed = reveal.advance(latestText, isStreaming = false)
        } else {
            while (true) {
                displayed = reveal.advance(latestText, isStreaming = true)
                delay(25L)
            }
        }
    }
    // ── 实时秒表（rikkahub「思考了 X 秒」）：首帧起表，100ms 步进；
    // 收束瞬间定格，滚动重建不重置（rememberSaveable）──
    var startedAt by rememberSaveable { mutableLongStateOf(0L) }
    var finalElapsedMs by rememberSaveable { mutableLongStateOf(0L) }
    var tickMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(isStreaming) {
        if (isStreaming) {
            if (startedAt == 0L) startedAt = System.currentTimeMillis()
            while (true) {
                tickMs = System.currentTimeMillis()
                delay(THINKING_TICK_MS)
            }
        } else if (startedAt > 0L && finalElapsedMs == 0L) {
            finalElapsedMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
        }
    }
    val elapsedSeconds = when {
        isStreaming && startedAt > 0L ->
            (((tickMs.takeIf { it > startedAt } ?: startedAt) - startedAt) / 1000f)
        finalElapsedMs > 0L -> finalElapsedMs / 1000f
        else -> 0f
    }
    val titleText = if (llmRetry != null) {
        // 重试等待中（用户规格 2026-11）：标题从「思考中 · X 秒」切换为
        // 「重试中 · 第 N/T 次」——重试成功后回到秒表口径。
        stringResource(R.string.code_stream_retrying_fmt, llmRetry.attempt, llmRetry.totalRetries)
    } else if (isStreaming) {
        stringResource(R.string.rikka_thinking_running_fmt, elapsedSeconds)
    } else {
        stringResource(R.string.rikka_thinking_elapsed_fmt, elapsedSeconds)
    }
    // 流式标题呼吸（rikkahub shimmer 同语义的轻量替代）
    val titlePulse by rememberInfiniteTransition(label = "rikka-think-pulse")
        .animateFloat(
            initialValue = 0.6f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 800, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "rikka-think-pulse-alpha"
        )
    // v1.7 部件皮肤（PartSkin spec FR-5）：思考卡默认浅白液态玻璃 ——
    // 秒表/折叠/打字机 reveal 逻辑零改动，只换容器（Surface → PartSurface）；
    // 重试红只上图标/标题（FR-11）。FR-9：展开态 key 与 skin 无关。
    PartSurface(
        part = CodingPart.STREAM_THINKING,
        active = isStreaming,
        onClick = {
            expanded = !expanded
            onCollapsedChange(!expanded)
        },
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
    ) {
        // v1.2 G2 内部件（P0-E）：标题行/正文可独立换肤；默认 EMBEDDED
        //（零容器，contentPadding=0 保持原视觉），H-4 嵌套退化生效。
        PartSurface(
            part = CodingPart.STREAM_THINKING_HEADER,
            contentPadding = PaddingValues(0.dp)
        ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (llmRetry != null) Icons.Default.Refresh else Icons.Default.Psychology,
                contentDescription = null,
                tint = if (llmRetry != null) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = titleText,
                style = MaterialTheme.typography.labelMedium,
                color = if (llmRetry != null) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant
                    .copy(alpha = if (isStreaming) titlePulse else 1f)
            )
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
                text = if (isStreaming) displayed else text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        if (onShowAnswer != null) {
            TextButton(onClick = { expanded = false; onShowAnswer() }) {
                Text(stringResource(R.string.rikka_thinking_show_answer))
            }
        }
        if (!expanded && text.isNotBlank()) {
            Text(
                text = stringResource(R.string.rikka_thinking_preview_fmt, rikkaThinkingFirstSentence(text)),
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

// ═══ 验证轮次卡（可折叠；展开态复用工具行）═══

@Composable
private fun RikkaVerifyCard(entry: StreamEntry.VerifyCycleEntry, onToolClick: (StreamToolCall) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val passed = entry.cycle.passed
    val tint = when (passed) {
        true -> MaterialTheme.colorScheme.primary
        false -> MaterialTheme.colorScheme.error
        null -> MaterialTheme.colorScheme.outline
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
            Spacer(Modifier.weight(1f))
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
                    RikkaToolRow(call = call, onClick = onToolClick)
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

// ═══ 运行统计收尾卡（头行 + stat chips；技能 / MCP 非零才显）═══

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RikkaRunStatsCard(entry: StreamEntry.RunStatsEntry) {
    val failed = entry.failedCalls > 0
    // v1.7 部件皮肤：统计卡默认 PALE_LIQUID；失败语义色只上图标/chip 文字
    val statusTint = if (failed) MaterialTheme.colorScheme.error
    else MaterialTheme.colorScheme.primary
    PartSurface(
        part = CodingPart.STREAM_RUN_STATS,
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
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
            RikkaStatChip(
                icon = Icons.Default.Loop,
                label = stringResource(R.string.code_stream_run_iterations_fmt, entry.iterations)
            )
            RikkaStatChip(
                icon = Icons.Default.Build,
                label = stringResource(R.string.code_stream_run_tools_fmt, entry.toolCalls)
            )
            if (entry.skillCalls > 0) {
                RikkaStatChip(
                    icon = Icons.Default.AutoAwesome,
                    label = stringResource(R.string.code_stream_run_skills_fmt, entry.skillCalls)
                )
            }
            if (entry.mcpCalls > 0) {
                RikkaStatChip(
                    icon = Icons.Default.Extension,
                    label = stringResource(R.string.code_stream_run_mcp_fmt, entry.mcpCalls)
                )
            }
            if (entry.filesTouched > 0) {
                RikkaStatChip(
                    icon = Icons.AutoMirrored.Filled.Notes,
                    label = stringResource(R.string.code_stream_run_files_fmt, entry.filesTouched)
                )
            }
            if (failed) {
                RikkaStatChip(
                    icon = Icons.Default.BugReport,
                    label = stringResource(R.string.code_stream_run_failed_fmt, entry.failedCalls),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/** 单枚统计 chip（非交互展示；与胶囊时间轴 StatChip 同语言）。 */
@Composable
private fun RikkaStatChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: androidx.compose.ui.graphics.Color? = null
) {
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

// ═══ 结构化错误卡 ═══

/**
 * 错误卡（Rikka 皮肤）：与胶囊时间轴 ErrorCard 同语义——失败结尾带
 * 「继续完成」动作行（用户规格 2026-11），播放入口重放本任务输入。
 *
 * v1.7 部件皮肤：默认 PALE_LIQUID 浅白玻璃 —— 红色仅上图标/标题文字
 * （FR-11 淘汰 errorContainer@0.5f 整卡染红）；正文/提示改 onSurface
 * （NFR-6 对比度）。
 */
@Composable
private fun RikkaErrorCard(entry: StreamEntry.ErrorEntry, onRetryRun: (() -> Unit)? = null) {
    PartSurface(
        part = CodingPart.STREAM_ERROR,
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
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
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.10f),
                    modifier = Modifier
                        // 圆角反馈修复：clip 先于 clickable —— 涟漪跟随 10dp 圆角
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(
                            role = androidx.compose.ui.semantics.Role.Button
                        ) { onRetryRun() }
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

// ═══ 停止卡（v1.7 部件皮肤：stream.stop 默认 PALE_LIQUID 卡片形态）═══

@Composable
private fun RikkaStopCard(reason: String) {
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

// ═══ 受影响文件 chips ═══

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RikkaFileChips(files: List<String>) {
    // v1.7 部件皮肤：文件 chips 默认 FLAT 裸渲染（与胶囊时间轴同款 bareInFlat）。
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
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(top = 4.dp)
        ) {
            files.take(FILE_CHIPS_MAX).forEach { file ->
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

// ═══ 状态 / 系统 pill（居中小胶囊——聊天 App 的时间戳/事件语言）═══
// v1.7 部件皮肤：pill 容器收编 PartSurface（FLAT 形态 ≈ 既有 surfaceContainer
// 胶囊）；part 由调用方按 StatusEntry / SystemEntry 传入（共享 ID 两套
// 风格同源）。

@Composable
private fun RikkaPillLine(
    text: String,
    emphasized: Boolean,
    part: CodingPart = CodingPart.STREAM_STATUS_LINE,
    leading: androidx.compose.ui.graphics.vector.ImageVector? = null
) {
    Row(
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
    ) {
        PartSurface(
            part = part,
            shape = RoundedCornerShape(50),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                leading?.let {
                    Icon(
                        imageVector = it,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(12.dp)
                    )
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (emphasized) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private const val FILE_CHIPS_MAX = 6

/** 思考卡秒表步进（rikkahub 同量级；过低无感知，过高浪费重组）。 */
private const val THINKING_TICK_MS = 100L
