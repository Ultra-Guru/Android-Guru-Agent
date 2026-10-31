package com.apex.agent.ui.screen.code.stream

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.core.code.stream.StreamToolCall
import com.apex.agent.core.code.stream.ToolCallStatus
import com.apex.agent.ui.theme.LocalExtendedColors

/**
 * # Rikka Tool Block — Rikka 风格的工具调用聚合块
 *
 * ## 设计立场（用户需求：「模仿 Rikka 的输出方式 + 调用工具聚合」）
 *
 * Rikka 风格下，时间轴里**连续的工具调用**（跨族）收进同一张卡：
 *
 * - **流式期间**（任一调用 isLive）：块自动展开——头行「正在调用工具…」
 *   呼吸指示 + 逐调用行（活跃行带实时代码窗 / 输出尾窗，写入过程可见）；
 * - **收束后**（全部终态）：自动折叠——头行「已调用 N 个工具」+ 内置 /
 *   技能 / MCP 三分类 chips（非零才显）+ 失败徽标；点击展开每次调用
 *   明细（工具名 / target / 参数摘要 / 状态 / 耗时 / 退出码）；
 * - **单次调用**：不包头行（「已调用 1 个工具」是废话），直接一行工具
 *   卡——聊天 App 的紧凑工具行形态。
 *
 * 手动干预守护与 [CodeCapsuleGroupRow] / 思考卡同款语义：用户点过
 * 展开/收起后本轮流式周期内不再自动翻转；新一轮活跃调用出现时重置。
 *
 * 聚合是纯派生视图（分段见 RikkaStreamTimeline 的 rikkaSegments），
 * session 侧数据零改动——切回时间轴风格即还原，不丢任何条目。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RikkaToolBlock(
    calls: List<StreamToolCall>,
    onToolClick: (StreamToolCall) -> Unit
) {
    val hasLive = calls.any { it.isLive }
    var expanded by remember { mutableStateOf(hasLive) }
    // 手动干预守护：用户点过展开/收起后，本轮流式周期内不再自动翻转
    var manual by remember { mutableStateOf(false) }
    LaunchedEffect(hasLive) {
        if (hasLive) {
            manual = false
            expanded = true
        } else if (!manual) {
            expanded = false
        }
    }
    val summary = remember(calls) { computeToolAggregate(calls) }
    val title = if (hasLive) stringResource(R.string.rikka_tool_block_running)
    else stringResource(R.string.code_stream_aggregate_title_fmt, calls.size)

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            // ── 头行（多调用才有；单调用直接明细行）──
            if (calls.size > 1) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) {
                            expanded = !expanded
                            manual = true
                        }
                        .semantics { contentDescription = title }
                        .padding(horizontal = 2.dp, vertical = 2.dp)
                ) {
                    if (hasLive) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(14.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Build,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.width(8.dp))
                    if (summary.failedCount > 0) {
                        Text(
                            text = stringResource(
                                R.string.code_stream_aggregate_failed_short_fmt,
                                summary.failedCount
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
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
                // ── 折叠态三分类 chips（非零才显；一眼扫完用了哪些能力生态）──
                if (!expanded && summary.builtinCount + summary.skillCount + summary.mcpCount > 0) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(start = 2.dp, top = 6.dp)
                    ) {
                        RikkaOriginChip(
                            icon = Icons.Default.Build,
                            label = stringResource(
                                R.string.code_stream_aggregate_builtin_fmt, summary.builtinCount
                            )
                        )
                        if (summary.skillCount > 0) {
                            RikkaOriginChip(
                                icon = Icons.Default.AutoAwesome,
                                label = stringResource(
                                    R.string.code_stream_aggregate_skill_fmt, summary.skillCount
                                )
                            )
                        }
                        if (summary.mcpCount > 0) {
                            RikkaOriginChip(
                                icon = Icons.Default.Extension,
                                label = stringResource(
                                    R.string.code_stream_aggregate_mcp_fmt, summary.mcpCount
                                )
                            )
                        }
                    }
                }
            }
            // ── 明细行：展开态或单调用恒显 ──
            AnimatedVisibility(
                visible = expanded || calls.size == 1,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.padding(top = if (calls.size > 1) 4.dp else 0.dp)
                ) {
                    calls.forEach { call ->
                        RikkaToolRow(call = call, onClick = onToolClick)
                    }
                }
            }
        }
    }
}

/**
 * 单次工具调用行（聊天 App 紧凑形态，验证轮次卡展开态复用）：
 * 族图标井 + 名称·target + 参数/摘要副行 + 状态徽标 + 耗时；
 * 活跃期带实时代码窗（参数流式）与输出尾窗（写入过程可见）；
 * 点击进详情弹层（diff / grep / 终端全文）。
 */
@Composable
internal fun RikkaToolRow(
    call: StreamToolCall,
    onClick: (StreamToolCall) -> Unit
) {
    val style = capsuleStyle(call.kind)
    val statusColor by animateColorAsState(
        targetValue = statusColor(call.status),
        label = "rikka-row-status"
    )
    // 终态语义色（成对槽位）：exit 0 的亮绿对白底对比度不足，走明暗成对色
    val successColor = LocalExtendedColors.current.success
    // 状态的无障碍描述：成败不能只靠色/形传达（TalkBack 不可感知）
    val statusDesc = stringResource(
        when (call.status) {
            ToolCallStatus.WAITING -> R.string.code_stream_status_waiting
            ToolCallStatus.RUNNING -> R.string.code_stream_status_running
            ToolCallStatus.SUCCESS -> R.string.code_stream_status_success
            ToolCallStatus.FAILED -> R.string.code_stream_status_failed
            ToolCallStatus.APPLIED -> R.string.code_stream_status_applied
            ToolCallStatus.PARTIAL -> R.string.code_stream_status_partial
        }
    )
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button) { onClick(call) }
            .semantics { contentDescription = "${call.displayName} ${call.target}, $statusDesc" }
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                // 左：族图标井（技能星芒 teal / MCP 拼图 tertiary 与胶囊同源）
                Surface(
                    shape = RoundedCornerShape(7.dp),
                    color = style.color.copy(alpha = 0.14f)
                ) {
                    Icon(
                        imageVector = style.icon,
                        contentDescription = null,
                        tint = style.color,
                        modifier = Modifier
                            .padding(3.dp)
                            .size(14.dp)
                    )
                }
                Spacer(Modifier.width(7.dp))
                // ── 中：名称（含技能·id / MCP·服务器 溯源）+ target + 副行 ──
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = call.displayName,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (call.target.isNotBlank()) {
                            Spacer(Modifier.width(5.dp))
                            Text(
                                text = call.target,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                        }
                    }
                    // 副行：运行中 = 进度文案/参数摘要；终态 = 完成摘要
                    val subtitle = call.summary
                        ?: call.progressMessage
                        ?: call.argsSummary
                    if (subtitle.isNotBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                // ── 右：exit 码 + 耗时 + 状态徽标 ──
                call.exitCode?.let { code ->
                    Text(
                        text = stringResource(R.string.code_stream_exit_code_fmt, code),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (code == 0) successColor
                        else MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.width(6.dp))
                }
                if (call.isLive) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(13.dp))
                } else {
                    val shownDuration = call.displayDuration(System.currentTimeMillis())
                    if (shownDuration > 0) {
                        Text(
                            text = formatCapsuleDuration(shownDuration),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Icon(
                        imageVector = statusIcon(call.status),
                        contentDescription = statusDesc,
                        tint = statusColor,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            // ═══ 参数流式期实时代码窗（绿增红删，与胶囊同款；终态收回）═══
            var lastPreview by remember(call.id) { mutableStateOf(call.streamingPreview) }
            call.streamingPreview?.let { lastPreview = it }
            AnimatedVisibility(
                visible = call.isLive && lastPreview?.hasCode == true,
                enter = expandVertically(),
                exit = shrinkVertically(),
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
            ) {
                lastPreview?.let { preview ->
                    StreamingCodeWindow(
                        preview = preview,
                        kind = call.kind,
                        status = call.status
                    )
                }
            }
            // ── 运行中输出尾窗（使用中详细、完成即折叠回单行摘要）──
            if (call.status == ToolCallStatus.RUNNING && call.logTail.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.45f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
                ) {
                    Text(
                        text = call.logTail,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

/** 聚合块折叠态的三分类计数 chip（非交互展示，与 StatChip 同语言）。 */
@Composable
private fun RikkaOriginChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String
) {
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
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
