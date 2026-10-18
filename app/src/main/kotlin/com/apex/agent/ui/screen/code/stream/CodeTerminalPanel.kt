package com.apex.agent.ui.screen.code.stream

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.ui.glass.TerminalGlassSurface

/**
 * # Code Terminal Panel — 终端面板（规格书：终端日志 + 独立锚定）
 *
 * Coding 工作流的 BASH 输出主舞台：
 * - 内容 = 活跃 BASH 胶囊的脉冲缓冲尾窗（[content]，已由 VM 从
 *   CodeStreamSnapshot.terminalContent 传入——整块脉冲，无打字机抖动）；
 * - **独立锚定**：横向滚动（长命令不断行）+ 纵向自动跟随输出尾行，
 *   用户上翻进入阅读模式后暂停跟随（与时间轴锚定互不干扰）；
 * - ANSI 转义清洗（CSI + OSC 标题序列 + 回车进度条，渲染层兜底）；
 * - 可折叠：头行 = 活跃命令 + 展开箭头；折叠态只留头行（时间轴优先）。
 *
 * ## 流水式折叠（与思考/工具胶囊同款生命周期）
 *
 * - [busy] = true（命令运行中）：头行脉冲点 + 「运行中」标签，面板保持
 *   展开（展开/折叠时机由 CodeScreen 按 terminalBusy 驱动，userToggled 语义）；
 * - [busy] = false（命令收束）：自动折叠成头行胶囊，[exitCode] 就地展示
 *   （exit 0 绿点 / 非零红点 + 等宽 exit N 文本），点箭头可展开回看尾窗；
 * - 正文展开/收起走 AnimatedVisibility（expand/shrinkVertically，先例见
 *   UpdateBanner / SlashCommandButton），不再硬切。
 *
 * ## v7 内联化（2026-10 用户反馈：「跟随流水式输出执行，不要悬浮在输入框上」）
 *
 * 本组件从底部悬浮栈（输入栏上方、遮挡时间轴卡片）迁入
 * [CodeStreamTimeline] 末尾成为**内联条目**：运行时随输出增长被锚定逻辑
 * 贴尾跟随，完成后折叠成胶囊留在流内。玻璃档位随之固定 Frosted：
 * 时间轴自身是采样源，内联条目不自采样（与 GlassCard Frosted 同规，
 * 极简风格则短路为扁平深色面板 —— TerminalGlassSurface 不变式）。
 */
@Composable
internal fun CodeTerminalPanel(
    content: String,
    activeCommand: String?,
    busy: Boolean,
    exitCode: Int?,
    collapsed: Boolean,
    onToggleCollapse: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 滚动状态提升到折叠开关之外：折叠（正文离开组合）不丢滚动位置
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()

    TerminalGlassSurface(
        // Frosted 档：内联条目不接 backdrop（时间轴是采样源，不自采样）
        backdrop = null,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Column {
            // ── 头行：终端图标 + 状态点 + 命令 + 运行中/exit + 折叠开关 ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1A2027))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Terminal,
                    contentDescription = null,
                    tint = Color(0xFF7C3AED),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(8.dp))
                // 状态点：运行中呼吸脉冲（与 ToolCallCapsule 状态点同款
                // infiniteTransition 模式）；完成态 exit 0 绿 / 非零红 / 未知灰
                val dotColor = when {
                    busy -> TerminalAccent
                    exitCode == null -> Color(0xFF6B7280)
                    exitCode == 0 -> Color(0xFF34D399)
                    else -> Color(0xFFF87171)
                }
                val pulse by rememberInfiniteTransition(label = "terminalPulse").animateFloat(
                    initialValue = 0.35f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(420),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "terminalDot"
                )
                val dotAlpha = if (busy) pulse else 0.85f
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(dotColor.copy(alpha = dotAlpha))
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = activeCommand ?: stringResource(R.string.code_stream_terminal_idle),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF9CA3AF),
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                // 运行中标签 / 完成态 exit 码（折叠胶囊的回看摘要）
                if (busy) {
                    Text(
                        text = stringResource(R.string.code_stream_terminal_running),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = TerminalAccent
                    )
                    Spacer(Modifier.width(8.dp))
                } else if (exitCode != null) {
                    Text(
                        text = "exit $exitCode",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (exitCode == 0) Color(0xFF34D399) else Color(0xFFF87171)
                    )
                    Spacer(Modifier.width(8.dp))
                }
                // v6 尾窗瘦身：折叠开关从 48dp IconButton 收为 36dp 紧凑
                // 可点区（终端折叠是二级操作，不占主操作触区预算）。
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button, onClick = onToggleCollapse),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (collapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                        contentDescription = stringResource(R.string.code_stream_terminal_toggle),
                        tint = Color(0xFF9CA3AF),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // 流水式折叠动画：展开/收起平滑过渡（滚动状态已提升，不受影响）
            AnimatedVisibility(
                visible = !collapsed,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                TerminalBody(
                    content = content,
                    vertical = vertical,
                    horizontal = horizontal
                )
            }
        }
    }
}

/** 终端面板强调色（图标与运行态标签）。 */
private val TerminalAccent = Color(0xFF7C3AED)

/** 终端正文：横向滚动 + 纵向尾行跟随 + ANSI 清洗（滚动状态由父级持有）。 */
@Composable
private fun TerminalBody(
    content: String,
    vertical: androidx.compose.foundation.ScrollState,
    horizontal: androidx.compose.foundation.ScrollState
) {
    val cleaned = remember(content) { stripTerminalAnsi(content) }

    // 独立锚定：输出追加时贴尾行（用户上翻后 maxValue 不追——滚动值保留即阅读态）
    LaunchedEffect(cleaned) {
        snapshotFlow { vertical.maxValue }.collect { max ->
            if (!vertical.isScrollInProgress && vertical.value >= max - SCROLL_EPSILON_PX) {
                vertical.scrollTo(max)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            // v6 尾窗瘦身：260 → 168dp（约 8 行正文，流式预览够用；
            // 全量输出走胶囊详情弹层），悬浮栈总高显著回落
            .heightIn(max = 168.dp)
            .horizontalScroll(horizontal)
    ) {
        Text(
            text = cleaned.ifBlank { stringResource(R.string.code_stream_terminal_empty) },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = Color(0xFFD1D5DB),
            modifier = Modifier
                // 不用 fillMaxSize：heightIn(max) 封顶会被 fillMaxHeight 无条件
                // 取满 260dp——哪怕只有一行输出也撑满黑块（P1）
                .verticalScroll(vertical)
                .padding(horizontal = 10.dp, vertical = 8.dp)
        )
    }
}

/**
 * ANSI 转义序列清洗（与 AgentChatOutputCards.stripAnsi 同规则 + OSC 扩展）：
 * CSI（`\u001B[...` 字母结尾）与 OSC（`\u001B]0;title\u0007` / ST 结尾），
 * 以及进度条残留的 `\r`（保留 `\n` 语义）。
 */
internal fun stripTerminalAnsi(text: String): String =
    ANSI_ESCAPE_REGEX.replace(text, "").replace("\r\n", "\n").replace("\r", "\n")

private val ANSI_ESCAPE_REGEX =
    Regex("\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\))")

/** 与时间轴侧一致的贴尾阈值（px）。 */
private const val SCROLL_EPSILON_PX = 150
