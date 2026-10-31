package com.apex.agent.ui.screen.code.stream

import androidx.compose.animation.AnimatedVisibility
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
import com.apex.agent.core.code.stream.ToolKind
import com.apex.agent.core.engine.ToolCallOrigin

/**
 * # Tool Usage Aggregate — 完整档的工具调用聚合卡（时间轴风格专用）
 *
 * ## 设计立场（用户需求：「完整档增加调用工具聚合 + 技能 / MCP 展示」）
 *
 * [com.apex.agent.ui.screen.code.CodeOutputMode.COMPLETE] 档收束后，连续
 * 的工具胶囊段不再平铺，而是折叠为一张聚合卡（规格书：「完成后折叠
 * 『已调用 N 个工具』」）：
 *
 * - **折叠态**（默认）：「已调用 N 个工具」+ 三分类 chips（内置 / 技能 /
 *   MCP，非零才显）+ 失败计数（>0 错误色）——一眼扫完本轮用了什么；
 * - **展开态**：三段分组摘要（技能 id / MCP 服务器 / 内置族，各带 ×N
 *   次数与失败数）+ 调用明细（原序胶囊列表，点击仍可进 DetailSheet 看
 *   diff / 终端全文——聚合不吞可达性）。
 *
 * ## 与流式档聚合（CodeCapsuleGroupRow）的分工
 *
 * 流式档按**同族**聚合（防刷屏 + 最新命令可见）；完整档按**调用段**聚合
 * （跨族、按时间轴连续段）——整段模式的诉求是「过程收进抽屉，结论留在
 * 台面」，族信息降级为展开态的分组摘要。
 *
 * 聚合是纯派生视图（[computeToolAggregate] 对胶囊列表的只读归约），
 * session 侧数据零改动——切回流式档即还原平铺，不丢任何条目。
 */

/** 溯源标签行（技能 id / MCP 服务器 / 内置族 → 次数 + 失败数）。 */
internal data class AggregateLabelRow(
    val label: String,
    val count: Int,
    val failed: Int
)

/** 聚合摘要：一段连续工具胶囊的只读归约（纯函数产物，可 remember）。 */
internal data class ToolAggregateSummary(
    val total: Int,
    val builtinCount: Int,
    val skillCount: Int,
    val mcpCount: Int,
    val failedCount: Int,
    val skillRows: List<AggregateLabelRow>,
    val mcpRows: List<AggregateLabelRow>,
    val kindRows: List<AggregateKindRow>
)

/** 内置族行（族 → 次数 + 失败数；标签在渲染层本地化）。 */
internal data class AggregateKindRow(
    val kind: ToolKind,
    val count: Int,
    val failed: Int
)

/**
 * 胶囊段 → 聚合摘要（纯函数）：三分类计数 + 标签去重行（首现序）+
 * 内置族行（标签在渲染层经 stringResource 本地化，不硬编码）。
 */
internal fun computeToolAggregate(calls: List<StreamToolCall>): ToolAggregateSummary {
    var builtin = 0
    var skill = 0
    var mcp = 0
    var failed = 0
    val skillCounts = LinkedHashMap<String, IntArray>()   // [count, failed]
    val mcpCounts = LinkedHashMap<String, IntArray>()
    val kindCounts = LinkedHashMap<ToolKind, IntArray>()
    calls.forEach { call ->
        val isFailed = call.status == ToolCallStatus.FAILED
        if (isFailed) failed++
        when (call.origin) {
            ToolCallOrigin.BUILTIN -> {
                builtin++
                kindCounts.getOrPut(call.kind) { IntArray(2) }
                    .let { it[0]++; if (isFailed) it[1]++ }
            }
            ToolCallOrigin.MCP -> {
                mcp++
                mcpCounts.getOrPut(call.originLabel ?: UNKNOWN_LABEL) { IntArray(2) }
                    .let { it[0]++; if (isFailed) it[1]++ }
            }
            ToolCallOrigin.SKILL -> {
                skill++
                skillCounts.getOrPut(call.originLabel ?: UNKNOWN_LABEL) { IntArray(2) }
                    .let { it[0]++; if (isFailed) it[1]++ }
            }
        }
    }
    return ToolAggregateSummary(
        total = calls.size,
        builtinCount = builtin,
        skillCount = skill,
        mcpCount = mcp,
        failedCount = failed,
        skillRows = labelRowsOf(skillCounts),
        mcpRows = labelRowsOf(mcpCounts),
        kindRows = kindCounts.map { (kind, pair) -> AggregateKindRow(kind, pair[0], pair[1]) }
    )
}

/** LinkedHashMap<label, [count, failed]> → 聚合行（首现序保持）。 */
private fun labelRowsOf(counts: LinkedHashMap<String, IntArray>): List<AggregateLabelRow> =
    counts.map { (label, pair) -> AggregateLabelRow(label, pair[0], pair[1]) }

private const val UNKNOWN_LABEL = "—"

/**
 * 聚合卡本体。展开态自持（remember by 调用方 key——时间轴 item key 已按
 * 段首胶囊 id 稳定），点击头行切换；明细里的胶囊有自己的点击语义
 * （DetailSheet），嵌套 clickable 由内层先行消费，不冲突。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ToolAggregateCard(
    calls: List<StreamToolCall>,
    onToolClick: (StreamToolCall) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val summary = remember(calls) { computeToolAggregate(calls) }
    val title = stringResource(R.string.code_stream_aggregate_title_fmt, summary.total)
    val originTint = MaterialTheme.colorScheme.primary

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clickable(role = Role.Button) { expanded = !expanded }
            .semantics { contentDescription = title }
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
            // ── 头行：工具图标 + 聚合标题 + 失败徽标 + 展开箭头 ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Build,
                    contentDescription = null,
                    tint = originTint,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(6.dp))
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
            // ── 三分类 chips（非零才显；单类全零不出行）──
            if (summary.builtinCount + summary.skillCount + summary.mcpCount > 0) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 6.dp)
                ) {
                    AggregateChip(
                        icon = Icons.Default.Build,
                        label = stringResource(R.string.code_stream_aggregate_builtin_fmt, summary.builtinCount)
                    )
                    if (summary.skillCount > 0) {
                        AggregateChip(
                            icon = Icons.Default.AutoAwesome,
                            label = stringResource(R.string.code_stream_aggregate_skill_fmt, summary.skillCount)
                        )
                    }
                    if (summary.mcpCount > 0) {
                        AggregateChip(
                            icon = Icons.Default.Extension,
                            label = stringResource(R.string.code_stream_aggregate_mcp_fmt, summary.mcpCount)
                        )
                    }
                }
            }
            // ── 展开态：分组摘要 + 调用明细 ──
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(modifier = Modifier.padding(top = 6.dp)) {
                    if (summary.skillRows.isNotEmpty()) {
                        AggregateSectionHeader(
                            icon = Icons.Default.AutoAwesome,
                            title = stringResource(R.string.code_stream_aggregate_section_skills),
                            count = summary.skillCount
                        )
                        summary.skillRows.forEach { row ->
                            AggregateLabelRow(
                                label = row.label,
                                count = row.count,
                                failed = row.failed,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    if (summary.mcpRows.isNotEmpty()) {
                        AggregateSectionHeader(
                            icon = Icons.Default.Extension,
                            title = stringResource(R.string.code_stream_aggregate_section_mcp),
                            count = summary.mcpCount
                        )
                        summary.mcpRows.forEach { row ->
                            AggregateLabelRow(
                                label = row.label,
                                count = row.count,
                                failed = row.failed,
                                tint = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                    if (summary.kindRows.isNotEmpty()) {
                        AggregateSectionHeader(
                            icon = Icons.Default.Build,
                            title = stringResource(R.string.code_stream_aggregate_section_builtin),
                            count = summary.builtinCount
                        )
                        summary.kindRows.forEach { row ->
                            AggregateLabelRow(
                                label = kindLabelOf(row.kind),
                                count = row.count,
                                failed = row.failed,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    // 调用明细：原序平铺胶囊（点击 → DetailSheet：diff / 终端全文）
                    Text(
                        text = stringResource(R.string.code_stream_aggregate_details),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        calls.forEach { call ->
                            CodeCapsule(call = call, onClick = onToolClick)
                        }
                    }
                }
            }
        }
    }
}

/** 三分类计数 chip（非交互展示；与 RunStatsCard 的 StatChip 同款语言）。 */
@Composable
private fun AggregateChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
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

/** 分组小节头：图标 + 标题 + 计数。 */
@Composable
private fun AggregateSectionHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    count: Int
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(13.dp)
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "· $count",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

/** 标签行：label（等宽）+ ×N 次数 + 失败徽标（>0 错误色）。 */
@Composable
private fun AggregateLabelRow(
    label: String,
    count: Int,
    failed: Int,
    tint: androidx.compose.ui.graphics.Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 18.dp, top = 1.dp, bottom = 1.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "×" + count,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (failed > 0) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.code_stream_aggregate_failed_short_fmt, failed),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

/** 内置族的本地化标签（胶囊 displayName 在 core 层硬编码中文——聚合卡
 *  的分组摘要走 stringResource，双语一致）。 */
@Composable
private fun kindLabelOf(kind: ToolKind): String = stringResource(
    when (kind) {
        ToolKind.READ_FILE -> R.string.code_stream_kind_read
        ToolKind.WRITE_FILE -> R.string.code_stream_kind_write
        ToolKind.EDIT_FILE -> R.string.code_stream_kind_edit
        ToolKind.GREP_SEARCH -> R.string.code_stream_kind_grep
        ToolKind.BASH -> R.string.code_stream_kind_bash
        ToolKind.GIT -> R.string.code_stream_kind_git
        ToolKind.LINT -> R.string.code_stream_kind_lint
        ToolKind.TEST -> R.string.code_stream_kind_test
        ToolKind.PLAN -> R.string.code_stream_kind_plan
        ToolKind.MCP_CUSTOM -> R.string.code_stream_kind_mcp
        ToolKind.SKILL -> R.string.code_stream_kind_skill
    }
)
