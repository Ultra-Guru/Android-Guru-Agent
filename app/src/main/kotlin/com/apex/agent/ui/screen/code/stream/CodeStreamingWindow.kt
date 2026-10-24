package com.apex.agent.ui.screen.code.stream

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.core.code.stream.StreamArgsPreview
import com.apex.agent.core.code.stream.ToolKind
import com.apex.agent.core.code.stream.ToolCallStatus

/**
 * # Streaming Code Window — 参数流式期实时代码窗
 *
 * 用户需求：「调用写文件/编辑文件工具时，正在写时能实时显示不断写入
 * 的代码，编辑时绿色代表写入、红色代表删除」——LLM 流式生成 tool_calls
 * 参数期间，[StreamArgsPreview] 从半截 JSON 里增量提取 content /
 * old_string / new_string，本组件把「正在写入」的过程渲染成嵌入式代码窗：
 *
 * - **写文件**（CodeWrite）：全文绿色（正在落笔的字符流）+ 尾部光标块；
 * - **编辑文件**（CodeEdit）：旧串红色（将删除）在前、新串绿色（将写入）
 *   在后，与 hunk Diff 的增删语义同色（浅绿/红在深底上取亮色变体）；
 * - 恒深底（与终端详情体同款 0xFF101418）：跨主题观感一致，写码场景的
 *   语义色不受浅色主题冲淡；
 * - 只展示**尾窗**（[STREAM_WINDOW_MAX_LINES]）：流式写入的增量永远在
 *   尾部，头部超窗由 [StreamArgsPreview] 的 truncated 标记提示已省略；
 * - `generating=true`（WAITING，参数生成中）时光标常亮提示进行中，
 *   RUNNING（执行中）时窗口保留（写入结果随执行持续可见），终态由
 *   胶囊层整体收回（AnimatedVisibility 收束动画）。
 */
@Composable
internal fun StreamingCodeWindow(
    preview: StreamArgsPreview,
    kind: ToolKind,
    status: ToolCallStatus,
    modifier: Modifier = Modifier,
    maxLines: Int = STREAM_WINDOW_MAX_LINES
) {
    val generating = status == ToolCallStatus.WAITING
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = STREAM_BG,
        modifier = modifier.fillMaxWidth()
    ) {
        Column {
            // ── 头行：状态文案 + 规模（写入行数随分片增长）──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = stringResource(
                        when {
                            !generating -> R.string.code_stream_streaming_title_live
                            kind == ToolKind.EDIT_FILE -> R.string.code_stream_streaming_editing
                            else -> R.string.code_stream_streaming_writing
                        }
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = STREAM_MUTED
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = streamSizeText(preview),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = STREAM_MUTED
                )
            }
            // ── 代码体（尾窗）：写 = 绿色流入；编辑 = 红删 + 绿增 ──
            val cursor = if (generating) STREAM_CURSOR else ""
            val writeContent = preview.writeContent
            if (writeContent != null) {
                StreamOmittedHint(preview.writeContentTruncated)
                StreamCodeBlock(
                    text = tailLines(writeContent, maxLines) + cursor,
                    color = STREAM_ADD,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            } else {
                val old = preview.editOld
                val new = preview.editNew
                StreamOmittedHint(preview.editTruncated)
                if (old != null) {
                    StreamCodeBlock(
                        text = tailLines(old, maxLines).prependMinus(),
                        color = STREAM_REMOVE,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
                if (new != null) {
                    StreamCodeBlock(
                        text = (tailLines(new, maxLines) + cursor).prependPlus(),
                        color = STREAM_ADD,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
            Spacer(Modifier.padding(bottom = 4.dp))
        }
    }
}

/** 截头提示（超长内容 [StreamArgsPreview] 已丢头部；尾窗才是展示主体）。 */
@Composable
private fun StreamOmittedHint(truncated: Boolean) {
    if (!truncated) return
    Text(
        text = stringResource(R.string.code_stream_streaming_omitted),
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = STREAM_DIM,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp)
    )
}

/** 等宽代码块（单 Text 节点承载整段，行数由 [tailLines] 钉在尾窗）。 */
@Composable
private fun StreamCodeBlock(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = color,
        modifier = modifier.fillMaxWidth()
    )
}

/** 头行右侧的规模文案：写 = 行数；编辑 = −旧行/+新行。 */
@Composable
private fun streamSizeText(preview: StreamArgsPreview): String {
    val write = preview.writeContent
    if (write != null) {
        return stringResource(R.string.code_stream_streaming_lines_fmt, write.lineSequence().count())
    }
    val old = preview.editOld
    val new = preview.editNew
    if (old != null || new != null) {
        val removed = old?.lineSequence()?.count() ?: 0
        val added = new?.lineSequence()?.count() ?: 0
        // 与终态 diff 统计同款键（+added −removed）：流式期与完成态口径一致
        return stringResource(R.string.code_stream_diff_stat_fmt, added, removed)
    }
    return ""
}

/** 尾窗截取：只展示最后 [maxLines] 行（流式增量永远在尾部）。 */
private fun tailLines(content: String, maxLines: Int): String =
    content.lineSequence().toList().takeLast(maxLines).joinToString("\n")

/** diff 语义前缀：删除行「− 」/新增行「+ 」（与 CodeDiffView 行首同口径）。 */
private fun String.prependMinus(): String = lineSequence().joinToString("\n") { "− $it" }
private fun String.prependPlus(): String = lineSequence().joinToString("\n") { "+ $it" }

// ═══ 恒定色（深底亮变体；跨主题一致，与终端详情体同规）═══

/** 深底（恒定，同 TerminalDetailBody）。 */
internal val STREAM_BG = Color(0xFF101418)

/** 写入/新增：亮绿（深底可读变体，浅底 Diff 用 0xFF059669）。 */
internal val STREAM_ADD = Color(0xFF4ADE80)

/** 删除/旧串：亮红。 */
internal val STREAM_REMOVE = Color(0xFFF87171)

/** 头行/说明文字：暗灰。 */
private val STREAM_MUTED = Color(0xFF8B949E)

/** 截头提示：更暗的灰。 */
private val STREAM_DIM = Color(0xFF6E7681)

/** 生成中光标块（尾部常亮，写入进行时）。 */
private const val STREAM_CURSOR = "▍"

/** 嵌入式窗口的尾窗行数上限（防止长文件撑爆时间轴）。 */
internal const val STREAM_WINDOW_MAX_LINES = 14

/** 详情弹层等大视口场景的尾窗行数上限。 */
internal const val STREAM_WINDOW_MAX_LINES_DETAIL = 400
