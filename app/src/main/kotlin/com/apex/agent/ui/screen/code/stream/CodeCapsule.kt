package com.apex.agent.ui.screen.code.stream

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Timelapse
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.core.code.stream.StreamEntryGroup
import com.apex.agent.core.code.stream.StreamToolCall
import com.apex.agent.core.code.stream.ToolCallStatus
import com.apex.agent.core.code.stream.ToolKind
import com.apex.agent.ui.theme.LocalExtendedColors

/**
 * # Code Capsule — 工具调用胶囊（规格书【2】胶囊层）
 *
 * 结构：左（ToolKind 着色图标）+ 中（名称 + target 可点击）+
 * 右（状态徽标 + 耗时 + hunk 进度）+ 副标题（动态文案 / 完成摘要 ≤24 字）。
 *
 * - 点击区 ≥48dp（内容垂直 padding 8dp + 双行主体保证可触面积）；
 * - 点击行为按族路由（onOpen 回调携带意图：BASH→终端面板、
 *   EDIT/WRITE→文件 Diff、GREP→结果列表、TEST→失败用例）；
 * - 着色语义：读灰 / 写蓝 / 命令紫 / lint 黄 / 测试绿 / 错误红
 *   （深浅主题取 MaterialTheme 动态色打底，族色用固定色相保证跨主题一致）；
 * - 运行态内嵌实时输出尾窗（「使用中详细展示、完成后折叠成胶囊」）：
 *   RUNNING 时在胶囊行下方流式展示 [StreamToolCall.logTail]，终态自动收起。
 */
@Composable
internal fun CodeCapsule(
    call: StreamToolCall,
    onClick: (StreamToolCall) -> Unit
) {
    val style = capsuleStyle(call.kind)
    val statusColor by animateColorAsState(
        targetValue = statusColor(call.status),
        label = "capsule-status"
    )
    // 终态语义色（成对槽位）：浅色主题下 exit 0 的亮绿 4ADE80 对白底仅
    // ~2:1 对比度（UI-016 同源问题），改走 LocalExtendedColors 明暗成对色
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
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(horizontal = 12.dp, vertical = 1.dp)
            .clickable(role = Role.Button) { onClick(call) }
            .semantics { contentDescription = "${call.displayName} ${call.target}, $statusDesc" }
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 5.dp)
            ) {
                // 左：族图标（着色底，紧凑井 20dp）
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
                Spacer(Modifier.width(6.dp))

                // ── 中：名称 + target（可点击主体）──
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = call.displayName,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = call.target,
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                    // 副标题：运行中 = 进度文案/参数摘要；终态 = 完成摘要
                    val subtitle = call.summary
                        ?: call.progressMessage
                        ?: call.argsSummary.ifBlank { call.target }
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // ── 右：hunk 进度 + exit code + 耗时 + 状态徽标 ──
                if (call.hunksTotal > 0) {
                    Text(
                        text = "${call.hunksApplied}/${call.hunksTotal}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (call.status == ToolCallStatus.FAILED)
                            MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(Modifier.width(6.dp))
                }
                call.exitCode?.let { code ->
                    Text(
                        text = stringResource(R.string.code_stream_exit_code_fmt, code),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (code == 0) successColor else MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.width(6.dp))
                }
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

            // ═══ 实时进度迷你条（编辑类 hunk 应用进度 / ToolProgress 百分比）═══
            // 文本徽标（3/7 / percent 文案）之外的可视化补充：一道 2dp 细条
            // 随进度填充，长任务（复制/下载/批量编辑）的推进感不再只靠数字。
            val progressFraction = liveProgressFraction(call)
            if (call.isLive && progressFraction > 0f) {
                CapsuleProgressBar(fraction = progressFraction, color = style.color)
            }

            // ═══ 参数流式期实时代码窗（用户需求：写/编辑时实时看到代码写入）═══
            // WAITING（参数生成中）/RUNNING（执行中）展开展示绿增红删代码流，
            // 终态自动收回（AnimatedVisibility 收束）；点击胶囊可回看详情。
            // lastPreview 缓存：终态时会话已把 preview 清空，收束动画的最后一帧
            // 仍需旧内容可渲染（写入 state 属收敛性缓存，不会无限重组）。
            var lastPreview by remember(call.id) { mutableStateOf(call.streamingPreview) }
            call.streamingPreview?.let { lastPreview = it }
            AnimatedVisibility(
                visible = call.isLive && lastPreview?.hasCode == true,
                enter = expandVertically(),
                exit = shrinkVertically(),
                modifier = Modifier.padding(start = 34.dp, end = 10.dp, bottom = 8.dp)
            ) {
                lastPreview?.let { preview ->
                    StreamingCodeWindow(
                        preview = preview,
                        kind = call.kind,
                        status = call.status
                    )
                }
            }

            // ── 运行中内嵌实时输出尾窗（与 Agent 聊天屏 RunningToolCallCard 同口径）──
            // RUNNING 态在胶囊行下方流式展示 logTail（6 行限高防长输出撑爆时间轴），
            // 完成后自动收起 —— 胶囊回到单行摘要形态（使用中详细、完成即折叠）。
            if (call.status == ToolCallStatus.RUNNING && call.logTail.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.45f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 34.dp, end = 10.dp, bottom = 8.dp)
                ) {
                    Text(
                        text = call.logTail,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = LIVE_TAIL_MAX_LINES,
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

/**
 * 胶囊聚合行（同族聚合）：同族连续胶囊 ≥2 收成一颗——
 *
 * - **折叠态**：一颗胶囊形态，始终展示**最新调用**（名称/命令/状态/
 *   耗时与单颗胶囊同款），右侧 ×N 计数徽标 + 展开提示；点击展开；
 * - **展开态**：头行（族图标 + ×N + 「收起」）+ 平铺全部历史胶囊，
 *   每颗可点击进详情（[onClick]），头行点击再收起——展开/收起不丢状态。
 */
@Composable
internal fun CodeCapsuleGroupRow(
    calls: List<StreamToolCall>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onClick: (StreamToolCall) -> Unit
) {
    val style = capsuleStyle(calls.firstOrNull()?.kind ?: ToolKind.MCP_CUSTOM)
    // ═══ 无障碍/文案（组合上下文求值，分支内直接使用）═══
    val groupCountDesc = stringResource(R.string.code_stream_group_count_fmt, calls.size)
    val collapseDesc = stringResource(R.string.code_stream_group_collapse)
    val expandDesc = stringResource(R.string.code_stream_group_expand)
    if (!expanded && calls.size > StreamEntryGroup.GROUP_THRESHOLD) {
        // ── 折叠聚合胶囊：仅一颗、内容 = 最新调用 ──
        val latest = calls.last()
        val statusColor by animateColorAsState(
            targetValue = statusColor(latest.status),
            label = "group-status"
        )
        val statusDesc = stringResource(
            when (latest.status) {
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
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .padding(horizontal = 12.dp, vertical = 1.dp)
                .clickable(role = Role.Button) { onToggle() }
                .semantics {
                    contentDescription = latest.displayName + " " + latest.target +
                        ", " + groupCountDesc + ", " + expandDesc
                }
        ) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 5.dp)
                ) {
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
                    Spacer(Modifier.width(6.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = latest.displayName,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = latest.target,
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                        }
                        // 副标题与单颗胶囊同口径：终态摘要 / 运行文案 / 目标
                        Text(
                            text = latest.summary
                                ?: latest.progressMessage
                                ?: latest.argsSummary.ifBlank { latest.target },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    // ×N 聚合计数徽标（同族调用总数——用户一眼可见折叠了几颗）
                    Text(
                        text = "×${calls.size}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(Modifier.width(6.dp))
                    val shownDuration = latest.displayDuration(System.currentTimeMillis())
                    if (shownDuration > 0) {
                        Text(
                            text = formatCapsuleDuration(shownDuration),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Icon(
                        imageVector = statusIcon(latest.status),
                        contentDescription = statusDesc,
                        tint = statusColor,
                        modifier = Modifier.size(14.dp)
                    )
                }
                // ═══ 调用历史状态条（可扫读的组内全景）═══
                // 折叠态只显最新调用，历史不可见——一枚色块 tick 代表一次
                // 调用（终态按红绿着色 / 运行中族色呼吸脉冲），不展开也能
                // 一眼看清整组几十次调用的成败分布。
                GroupHistoryStrip(calls = calls, kindColor = style.color)
            }
        }
    } else {
        // ── 展开态：收起头行 + 全部历史胶囊平铺 ──
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 36.dp)
                    .padding(horizontal = 12.dp, vertical = 1.dp)
                    .clickable(role = Role.Button) { onToggle() }
                    .semantics { contentDescription = groupCountDesc + ", " + collapseDesc }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = style.icon,
                        contentDescription = null,
                        tint = style.color.copy(alpha = 0.7f),
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "×${calls.size}",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = collapseDesc,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowUp,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            calls.forEach { call ->
                CodeCapsule(call = call, onClick = onClick)
            }
        }
    }
}

// ═══ 内部：样式与文案 ═══

internal data class CapsuleStyle(val icon: ImageVector, val color: Color)

/** 运行中实时尾窗的最大行数（限高防长输出撑爆时间轴）。 */
private const val LIVE_TAIL_MAX_LINES = 6

/** 聚合历史状态条的上限 tick 数（超出显 +N 文本，不无限拉宽）。 */
private const val GROUP_STRIP_MAX_TICKS = 12

/** 族样式：读灰 / 写改蓝 / 命令紫 / lint 黄 / 测试绿 / git 品牌灰 /
 *  MCP 拼图（tertiary）/ 技能星芒（teal）。 */
@Composable
internal fun capsuleStyle(kind: ToolKind): CapsuleStyle = when (kind) {
    ToolKind.READ_FILE -> CapsuleStyle(
        Icons.AutoMirrored.Filled.InsertDriveFile,
        MaterialTheme.colorScheme.onSurfaceVariant
    )
    ToolKind.WRITE_FILE -> CapsuleStyle(Icons.Default.Edit, Color(0xFF2563EB))
    ToolKind.EDIT_FILE -> CapsuleStyle(Icons.Default.Code, Color(0xFF2563EB))
    ToolKind.GREP_SEARCH -> CapsuleStyle(
        Icons.Default.Search,
        MaterialTheme.colorScheme.secondary
    )
    ToolKind.BASH -> CapsuleStyle(Icons.Default.Terminal, Color(0xFF7C3AED))
    ToolKind.GIT -> CapsuleStyle(Icons.Default.FolderOpen, Color(0xFF6E7681))
    ToolKind.LINT -> CapsuleStyle(Icons.Default.Bolt, Color(0xFFD97706))
    ToolKind.TEST -> CapsuleStyle(Icons.Default.CheckCircle, Color(0xFF059669))
    ToolKind.PLAN -> CapsuleStyle(
        Icons.Default.Psychology,
        MaterialTheme.colorScheme.primary
    )
    // MCP 拼图语义（v1.5.1：远程能力插件；旧 Bolt 与 lint 同形易混）
    ToolKind.MCP_CUSTOM -> CapsuleStyle(
        Icons.Default.Extension,
        MaterialTheme.colorScheme.tertiary
    )
    // 技能星芒（teal——与测试绿/命令紫拉开色相，能力生态的第三色）
    ToolKind.SKILL -> CapsuleStyle(Icons.Default.AutoAwesome, Color(0xFF0D9488))
}

@Composable
internal fun statusColor(status: ToolCallStatus): Color {
    // v6 白天模式修复：成功/部分 走成对语义色（浅色下不再对比度不足）
    val extended = LocalExtendedColors.current
    return when (status) {
        ToolCallStatus.WAITING -> MaterialTheme.colorScheme.outline
        ToolCallStatus.RUNNING -> MaterialTheme.colorScheme.primary
        ToolCallStatus.SUCCESS -> extended.success
        ToolCallStatus.FAILED -> MaterialTheme.colorScheme.error
        ToolCallStatus.APPLIED -> Color(0xFF2563EB)
        ToolCallStatus.PARTIAL -> extended.warning
    }
}

internal fun statusIcon(status: ToolCallStatus): ImageVector = when (status) {
    ToolCallStatus.WAITING -> Icons.Default.Timelapse
    ToolCallStatus.RUNNING -> Icons.Default.Timelapse
    ToolCallStatus.SUCCESS -> Icons.Default.CheckCircle
    ToolCallStatus.FAILED -> Icons.Default.Cancel
    ToolCallStatus.APPLIED -> Icons.Default.CheckCircle
    ToolCallStatus.PARTIAL -> Icons.Default.ErrorOutline
}

/** 毫秒 → 紧凑时长（850ms / 3.4s / 2m05s）。 */
internal fun formatCapsuleDuration(ms: Long): String = when {
    ms < 0 -> "0s"
    ms < 1_000 -> "${ms}ms"
    ms < 60_000 -> String.format(java.util.Locale.US, "%.1fs", ms / 1000.0)
    else -> {
        val m = ms / 60_000
        val s = (ms % 60_000) / 1000
        String.format(java.util.Locale.US, "%dm%02ds", m, s)
    }
}

// ═══ 聚合胶囊：调用历史状态条 + 实时进度迷你条 ═══

/**
 * 同族聚合的调用历史状态条：每次调用一枚色块 tick——
 *
 * - 终态着色与胶囊状态徽标同源（成对语义色）：SUCCESS/APPLIED 绿 /
 *   FAILED 红 / PARTIAL 琥珀；
 * - WAITING/RUNNING 用族色做**呼吸脉冲**（无限过渡动画）——活跃调用
 *   在条上“活着”，一眼可辨组内还有活没干完；
 * - tick 上限 [GROUP_STRIP_MAX_TICKS]（几十条命令也不拉宽时间轴），
 *   超出部分以「+还有 N 个」文本收尾（复用受影响文件 chips 的省略键）；
 * - 纯装饰（无点击语义）：组的可点击区域在胶囊行，TalkBack 已有
 *   「×N 次调用」计数播报，不重复。
 */
@Composable
private fun GroupHistoryStrip(calls: List<StreamToolCall>, kindColor: Color) {
    val hasLive = calls.any { it.isLive }
    // 无限过渡无条件组合（条件调用 composable 会破坏槽表稳定性）——
    // 无活跃调用时动画值不被读取，开销趋近于零。
    val transition = rememberInfiniteTransition(label = "strip-live")
    val liveAlpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "strip-live-alpha"
    )
    val pulse = if (hasLive) liveAlpha else 1f
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.padding(start = 31.dp, end = 10.dp, bottom = 6.dp)
    ) {
        val shown = calls.takeLast(GROUP_STRIP_MAX_TICKS)
        shown.forEach { call ->
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(8.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        when (call.status) {
                            ToolCallStatus.WAITING, ToolCallStatus.RUNNING ->
                                kindColor.copy(alpha = pulse)
                            else -> statusColor(call.status).copy(alpha = 0.75f)
                        }
                    )
            )
        }
        if (calls.size > GROUP_STRIP_MAX_TICKS) {
            Text(
                text = stringResource(R.string.code_stream_files_more_fmt, calls.size - GROUP_STRIP_MAX_TICKS),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.align(Alignment.CenterVertically)
            )
        }
    }
}

/**
 * 实时进度迷你条：编辑类 hunk 应用进度（hunksApplied/Total）优先，
 * 其后回退 ToolProgress 的 percent——事件源两套量纲（0..1 小数与
 * 0..100 整数）都兼容（≤1 按 0..1，≤100 按 0..100 折算）。
 */
private fun liveProgressFraction(call: StreamToolCall): Float {
    if (call.hunksTotal > 0) {
        return (call.hunksApplied.toFloat() / call.hunksTotal).coerceIn(0f, 1f)
    }
    val percent = call.progressPercent ?: return 0f
    return when {
        percent <= 1f -> percent
        percent <= 100f -> percent / 100f
        else -> 1f
    }.coerceIn(0f, 1f)
}

/** 2dp 细进度条（轨道 surfaceContainerHighest，填充色 = 族色；非交互装饰）。 */
@Composable
private fun CapsuleProgressBar(fraction: Float, color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 34.dp, end = 10.dp, bottom = 6.dp)
            .height(2.dp)
            .clip(RoundedCornerShape(1.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0.03f, 1f))
                .fillMaxHeight()
                .background(color)
        )
    }
}
