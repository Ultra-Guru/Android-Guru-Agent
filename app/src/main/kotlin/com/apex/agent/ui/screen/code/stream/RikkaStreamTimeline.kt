package com.apex.agent.ui.screen.code.stream

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.core.code.stream.CodeStreamSnapshot
import com.apex.agent.core.code.stream.StreamEntry
import com.apex.agent.core.code.stream.StreamToolCall
import com.apex.agent.ui.component.imeDismissOnTap
import com.apex.agent.ui.component.rememberImeDismisser
import com.apex.agent.ui.screen.code.CodeOutputMode
import com.apex.agent.ui.screen.code.completeModeVisibleEntries
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * # Rikka Stream Timeline — Rikka 风格聊天时间轴
 *
 * [CodeOutputStyle.RIKKA] 档的渲染主体（见 ui/screen/code/CodeOutputStyle.kt）。
 * 与胶囊时间轴（[CodeStreamTimeline]）共享：
 *
 * - 同一份 [CodeStreamSnapshot] 数据通道与滚动锚定（[rememberStreamAnchor]：
 *   流式贴尾跟随 / 阅读模式保护 / 收尾动画 / 回底 FAB）；
 * - 同一套输出方式档语义（[renderMode]：COMPLETE 生成中过程条目静默 +
 *   [CompleteModeGeneratingCard] 占位，收束后一次性揭示并滚动一次）；
 * - 同一个内联终端胶囊（[terminal]：运行时展开跟随、完成折叠）。
 *
 * 差异只在**分段与皮肤**：
 *
 * - 分段：连续工具胶囊（跨族）收进一张 [RikkaToolBlock]（聚合块）——
 *   流式「正在调用工具…」、收束「已调用 N 个工具」+ 三分类 chips，
 *   可展开每次调用明细（工具名 / 参数摘要 / 状态 / 耗时，点击进详情）；
 * - 皮肤：用户右对齐实色气泡、结论 Markdown 气泡、思考过程折叠卡、
 *   居中状态 pill（RikkaCards）——聊天 App 的对话形态。
 */
@Composable
internal fun RikkaStreamTimeline(
    snapshot: CodeStreamSnapshot,
    isStreaming: Boolean,
    onToolClick: (StreamToolCall) -> Unit,
    bottomInset: Dp = 0.dp,
    backdrop: LayerBackdrop? = null,
    modifier: Modifier = Modifier,
    terminal: TerminalPanelState? = null,
    onToggleTerminalCollapse: () -> Unit = {},
    renderMode: CodeOutputMode = CodeOutputMode.STREAMING
) {
    val listState = rememberLazyListState()
    // ═══ 完整档切片（与胶囊时间轴同款）：生成中只留用户输入及更早的
    // 终态内容，本轮过程条目静默——切片是纯派生视图，session 数据不变 ═══
    val completeActive = isStreaming && renderMode == CodeOutputMode.COMPLETE
    val visibleEntries = remember(snapshot.entries, completeActive) {
        if (completeActive) completeModeVisibleEntries(snapshot.entries) else snapshot.entries
    }
    // Rikka 分段：连续工具胶囊（跨族）聚合为一块——聊天形态下工具过程
    // 整体收进抽屉，台面只留结论气泡与聚合块头行。
    val segments = remember(visibleEntries) { rikkaSegments(visibleEntries) }
    // 终端输出计入锚定素材：尾窗在末条目内增长也要触发贴尾跟随
    val terminalLength = terminal?.content?.length ?: 0
    val contentLength = remember(visibleEntries, terminalLength) {
        visibleEntries.sumOf { entryContentLength(it) } + terminalLength
    }
    val anchor = rememberStreamAnchor(
        listState = listState,
        itemCountKey = visibleEntries.size * 3 + terminalStructureKey(terminal),
        contentTickKey = anchorFollowTick(contentLength),
        isStreaming = isStreaming
    )
    val scope = rememberCoroutineScope()
    // 点时间轴空白收起输入法（与胶囊时间轴同款 IME 纪律）
    val imeDismisser = rememberImeDismisser()
    val showFab by remember { derivedStateOf { anchor.isDetachedFromBottom } }

    // 展开动画补锚（终端尾窗 busy 翻转的弹簧收敛期再补一次贴尾）
    LaunchedEffect(terminal?.busy, terminal?.collapsed) {
        val term = terminal
        if (term != null && !term.collapsed) {
            delay(EXPAND_SETTLE_MS)
            if (anchor.shouldFollow()) anchor.snapToLast()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // ═══ 空态占位（与胶囊时间轴同判据：只看渲染素材）═══
        if (segments.isEmpty() && terminal == null && !completeActive) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 32.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Chat,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.code_stream_empty_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .imeDismissOnTap(imeDismisser)
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier),
            contentPadding = PaddingValues(bottom = 8.dp + bottomInset)
        ) {
            segments.forEach { segment ->
                when (segment) {
                    is RikkaSegment.Single ->
                        item(key = segment.entry.id) {
                            RikkaStreamCard(entry = segment.entry, onToolClick = onToolClick)
                        }

                    is RikkaSegment.ToolBlock ->
                        item(key = segment.blockKey) {
                            RikkaToolBlock(
                                calls = segment.calls,
                                onToolClick = onToolClick
                            )
                        }
                }
            }
            // ═══ 内联终端胶囊：时间轴末条（与胶囊时间轴同款接线）═══
            terminal?.let { term ->
                item(key = KEY_RIKKA_TERMINAL_INLINE) {
                    CodeTerminalPanel(
                        content = term.content,
                        activeCommand = term.activeCommand,
                        busy = term.busy,
                        exitCode = term.exitCode,
                        collapsed = term.collapsed,
                        onToggleCollapse = onToggleTerminalCollapse
                    )
                }
            }
            // ═══ 完整档生成中：加载进度卡（本轮过程的唯一可见占位）═══
            if (completeActive) {
                item(key = KEY_RIKKA_COMPLETE_GENERATING) {
                    CompleteModeGeneratingCard(
                        toolUsage = snapshot.toolUsage
                    )
                }
            }
            // 1dp 尾哨兵：贴它即贴底（与胶囊时间轴同款锚定约定）
            item(key = "rikka-tail-sentinel") {
                Spacer(modifier = Modifier.height(1.dp))
            }
        }

        AnimatedVisibility(
            visible = showFab && snapshot.entries.isNotEmpty(),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            SmallFloatingActionButton(
                onClick = { scope.launch { anchor.animateToLast() } },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(bottom = 8.dp + bottomInset)
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.code_stream_fab_bottom),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// ═══ 分段逻辑（纯函数）═══

/** Rikka 渲染分段：单条 / 工具聚合块（连续胶囊跨族连片，≥1 即成块）。 */
internal sealed interface RikkaSegment {
    data class Single(val entry: StreamEntry) : RikkaSegment

    /** 连续工具胶囊聚合段（跨族不拆段——聊天形态把整段过程收进一块）。 */
    data class ToolBlock(val blockKey: String, val calls: List<StreamToolCall>) : RikkaSegment
}

/**
 * 连续 [StreamEntry.ToolCapsuleEntry]（跨族）聚合为 [RikkaSegment.ToolBlock]；
 * 其余条目逐条渲染。验证轮次卡（VerifyCycleEntry）在 session 侧已把轮内
 * 胶囊折叠替换，天然是 Single 段——展开态由卡片自持。
 */
internal fun rikkaSegments(entries: List<StreamEntry>): List<RikkaSegment> {
    val out = mutableListOf<RikkaSegment>()
    val buffer = mutableListOf<StreamEntry.ToolCapsuleEntry>()

    fun flush() {
        if (buffer.isNotEmpty()) {
            out += RikkaSegment.ToolBlock(
                blockKey = "rikka-block-${buffer.first().id}",
                calls = buffer.map { it.call }
            )
            buffer.clear()
        }
    }

    entries.forEach { entry ->
        if (entry is StreamEntry.ToolCapsuleEntry) {
            buffer += entry
        } else {
            flush()
            out += RikkaSegment.Single(entry)
        }
    }
    flush()
    return out
}

/** 内联终端胶囊的稳定 item key。 */
private const val KEY_RIKKA_TERMINAL_INLINE = "rikka-terminal-inline"

/** 完整档加载进度卡的稳定 item key。 */
private const val KEY_RIKKA_COMPLETE_GENERATING = "rikka-complete-generating"

/** 展开弹簧收敛时长（补锚延时；与胶囊时间轴同值）。 */
private const val EXPAND_SETTLE_MS = 350L
