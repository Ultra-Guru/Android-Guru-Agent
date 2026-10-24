package com.apex.agent.ui.screen.code.stream

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.core.code.stream.CodeStreamSnapshot
import com.apex.agent.core.code.stream.StreamEntry
import com.apex.agent.core.code.stream.StreamEntryGroup
import com.apex.agent.core.code.stream.StreamToolCall
import com.apex.agent.core.code.stream.ToolKind
import com.apex.agent.ui.component.imeDismissOnTap
import com.apex.agent.ui.component.rememberImeDismisser
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * # Code Stream Timeline — 胶囊时间轴主列表
 *
 * ## 渲染单元（防刷屏分组）
 *
 * 快照 entries 先经 [groupConsecutive] 分段：**同族连续**胶囊超过
 * [StreamEntryGroup.GROUP_THRESHOLD] 个的一段 → 折叠为「+N 更多」
 * 分组行（展开态本地 remember 自持）；其余逐条渲染。
 *
 * ## 内联终端胶囊（2026-10 用户反馈）
 *
 * 终端尾窗不再悬浮于输入栏上方遮挡流水内容 —— 改为时间轴末尾的**内联
 * 条目**（[terminal]）：命令运行时随输出增长被锚定逻辑跟随（贴尾），
 * 完成后折叠成头行胶囊留在流内，与思考/工具胶囊同一套流水生命周期。
 *
 * ## 双锚定之一（时间轴侧）
 *
 * [rememberStreamAnchor]：流式即时跟随 + 阅读模式保护 + 收尾动画；
 * 底部 FAB（不在底部时出现）一键回底。
 *
 * ## v5 玻璃接线
 *
 * - [backdrop]：时间轴 = 玻璃采样源（悬浮输入栏 GlassCard 的 backdrop
 *   来源；null = 不接线，独立预览场景用）；
 * - [bottomInset]：底部悬浮栈（输入栏等）高度 —— 列表
 *   contentPadding 与 FAB 底部偏移同步补偿，最后一条不被遮挡。
 */
@Composable
internal fun CodeStreamTimeline(
    snapshot: CodeStreamSnapshot,
    isStreaming: Boolean,
    onToolClick: (StreamToolCall) -> Unit,
    bottomInset: Dp = 0.dp,
    backdrop: LayerBackdrop? = null,
    // 玻璃幕帘（GlassModalVeil）：提问卡挂起时由调用方传入 blur 修饰 ——
    // 根 Box 接收，LazyColumn 的 layerBackdrop 采样源保持原样不失真。
    modifier: Modifier = Modifier,
    // ═══ 内联终端胶囊（跟随流水式输出；null = 无活跃/历史终端会话）═══
    terminal: TerminalPanelState? = null,
    onToggleTerminalCollapse: () -> Unit = {}
) {
    val listState = rememberLazyListState()
    val grouped = remember(snapshot.entries) { groupConsecutive(snapshot.entries) }
    // 终端输出计入锚定素材：尾窗在末条目内增长也要触发贴尾跟随
    val terminalLength = terminal?.content?.length ?: 0
    val contentLength = remember(snapshot.entries, terminalLength) {
        snapshot.entries.sumOf { entryContentLength(it) } + terminalLength
    }
    val anchor = rememberStreamAnchor(
        listState = listState,
        // 终端胶囊的结构位（无=0 / 静默=1 / 运行中=2）并入结构 key ——
        // 尾窗出现/开跑（展开）时视同结构变化触发跟随，收束折叠不追问。
        // ×3 展开编码保证 (entries.size, 结构位) 二元组无碰撞。
        itemCountKey = snapshot.entries.size * 3 + terminalStructureKey(terminal),
        contentTickKey = anchorFollowTick(contentLength),
        isStreaming = isStreaming
    )
    val scope = rememberCoroutineScope()
    // 点时间轴空白/非交互区域收起输入法（与 Agent 聊天屏同款 IME 纪律，
    // 2026-10 用户反馈三连修；见 ImeDismiss.kt）。
    val imeDismisser = rememberImeDismisser()
    // FAB 可见性只依赖可追踪的 derived state（anchor.isAtBottom）；
    // entries 判空放到组合处——原实现把首次组合时的空 snapshot 闭包进
    // derivedStateOf，普通属性读取不被追踪 → FAB 永远不出现（P0）
    val showFab by remember { derivedStateOf { !anchor.isAtBottom } }

    // ═══ 展开动画补锚（内联终端胶囊）═══
    // busy 翻转触发的结构 snap 落在 AnimatedVisibility 高度动画早期 ——
    // maxScroll 随动画逐步增长，单次 snap 会落在半途，展开态尾窗下半截
    // 可能悬在视口外直到下个内容档位。延时补一次贴尾（覆盖弹簧收敛）；
    // 阅读模式守卫照常生效（用户上翻过则不追）。
    LaunchedEffect(terminal?.busy, terminal?.collapsed) {
        val term = terminal
        if (term != null && !term.collapsed) {
            delay(EXPAND_SETTLE_MS)
            if (anchor.shouldFollow()) anchor.snapToLast()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                // 点时间轴空白收起输入法（在采样源接线之前 = 覆盖全域；
                // 卡片/链接等可点击子项与滚动手势不会误触）。
                .imeDismissOnTap(imeDismisser)
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier),
            // v5：底部悬浮栈（输入栏/选择器行等）动态补偿 —— 最后一条不被遮挡
            // v6 紧凑化：12 → 8dp（悬浮栈自身已瘦身）
            contentPadding = PaddingValues(bottom = 8.dp + bottomInset)
        ) {
            grouped.forEach { segment ->
                when (segment) {
                    is Segment.Single ->
                        item(key = segment.entry.id) {
                            StreamCard(entry = segment.entry, onToolClick = onToolClick)
                        }

                    is Segment.Group -> {
                        item(key = segment.groupKey) {
                            GroupExpandable(
                                calls = segment.calls,
                                onToolClick = onToolClick
                            )
                        }
                    }
                }
            }
            // ═══ 内联终端胶囊：时间轴末条（尾哨兵之前）═══
            // 跟随流水式输出执行（用户反馈「不要悬浮在输入框上」）：尾窗
            // 作为普通列表条目参与布局 —— 运行时随输出增长贴尾，完成后
            // 折叠成胶囊留在流内；不遮任何卡片，不再吃悬浮栈高度。
            // backdrop 不接线：时间轴自身就是玻璃采样源，内联条目不自采样
            // （与 AssistantBubble/ThinkingCard 的 GlassCard Frosted 档同规）。
            terminal?.let { term ->
                item(key = KEY_TERMINAL_INLINE) {
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
            // 尾哨兵：snapToLast 锚到末条顶部而非尾部——长气泡流式时新内容
            // 在视口外不可见；1dp 哨兵恒为末条，贴它即贴底（P1）
            item(key = "stream-tail-sentinel") {
                Spacer(modifier = Modifier.height(1.dp))
            }
        }

        AnimatedVisibility(
            visible = showFab && snapshot.entries.isNotEmpty(),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            // v6 紧凑化：ExtendedFAB(56dp+文字) → SmallFAB(40dp 纯图标) ——
            // 回底是时间轴高频小操作，缩小视觉质量但不减可达性（CD 保留语义）
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

/** 分组行 + 展开态（remember 自持）。 */
@Composable
private fun GroupExpandable(
    calls: List<StreamToolCall>,
    onToolClick: (StreamToolCall) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    CodeCapsuleGroupRow(
        calls = calls,
        expanded = expanded,
        onToggle = { expanded = !expanded },
        onClick = onToolClick
    )
}

/** 内联终端胶囊的稳定 item key。 */
private const val KEY_TERMINAL_INLINE = "stream-terminal-inline"

/** 展开弹簧收敛时长（补锚延时）： AnimatedVisibility 默认 spring ≈ 300ms。 */
private const val EXPAND_SETTLE_MS = 350L

/**
 * 内联终端胶囊状态 —— 由 CodeScreen 从 CodeStreamSnapshot 装配，
 * 经 [CodeStreamTimeline] 的 `terminal` 参数在时间轴末尾渲染。
 * null = 无活跃且无历史终端输出（不渲染条目）。
 */
internal data class TerminalPanelState(
    val content: String,
    val activeCommand: String?,
    val busy: Boolean,
    val exitCode: Int?,
    val collapsed: Boolean
)

/**
 * 终端胶囊结构位（并入锚定 itemCountKey）：无 = 0 / 静默 = 1 / 运行中 = 2。
 * 出现与开跑（展开）都算结构变化 → 触发一次贴尾跟随。
 */
private fun terminalStructureKey(terminal: TerminalPanelState?): Int = when {
    terminal == null -> 0
    terminal.busy -> 2
    else -> 1
}

// ═══ 分段逻辑（纯函数）═══

/** 渲染分段：单条 or 同族连续胶囊组。 */
internal sealed interface Segment {
    data class Single(val entry: StreamEntry) : Segment
    data class Group(val groupKey: String, val kind: ToolKind, val calls: List<StreamToolCall>) : Segment
}

/**
 * 同族**连续**胶囊聚合：仅当相邻且同 [ToolKind] 的胶囊数超过阈值时
 * 成组（分组键 = 族名 + 首胶囊 id，保稳定）。
 */
internal fun groupConsecutive(entries: List<StreamEntry>): List<Segment> {
    val out = mutableListOf<Segment>()
    var bufferKind: ToolKind? = null
    val buffer = mutableListOf<StreamEntry.ToolCapsuleEntry>()

    fun flush() {
        val kind = bufferKind
        if (kind != null && buffer.size > StreamEntryGroup.GROUP_THRESHOLD) {
            out += Segment.Group(
                groupKey = "group-${kind.toString().lowercase()}-${buffer.first().id}",
                kind = kind,
                calls = buffer.map { it.call }
            )
        } else {
            buffer.forEach { out += Segment.Single(it) }
        }
        buffer.clear()
        bufferKind = null
    }

    entries.forEach { entry ->
        if (entry is StreamEntry.ToolCapsuleEntry) {
            if (bufferKind != null && bufferKind != entry.call.kind) flush()
            bufferKind = entry.call.kind
            buffer += entry
        } else {
            flush()
            out += Segment.Single(entry)
        }
    }
    flush()
    return out
}

/** 条目内容长度（节流档位 key 的素材）。 */
private fun entryContentLength(entry: StreamEntry): Int = when (entry) {
    is StreamEntry.UserEntry -> entry.text.length
    is StreamEntry.AssistantEntry -> entry.text.length
    is StreamEntry.ThinkingEntry -> entry.text.length
    is StreamEntry.ToolCapsuleEntry -> entry.call.logTail.length + 40
    is StreamEntry.VerifyCycleEntry -> entry.calls.size * 40
    is StreamEntry.StatusEntry -> entry.text.length
    is StreamEntry.SystemEntry -> entry.text.length
    is StreamEntry.ErrorEntry -> entry.message.length
    is StreamEntry.StopEntry -> entry.reason.length
    is StreamEntry.FileChipsEntry -> entry.files.size * 20
    is StreamEntry.GitOpEntry -> 120
}

private fun anchorFollowTick(contentLength: Int): Int = contentLength / 200
