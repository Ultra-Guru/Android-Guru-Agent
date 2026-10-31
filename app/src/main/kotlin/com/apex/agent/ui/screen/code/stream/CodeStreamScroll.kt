package com.apex.agent.ui.screen.code.stream

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * # Code Stream Scroll — 时间轴智能滚动锚
 *
 * ## 语义（20261031 重构：手势锁存 + 精确贴底判定）
 *
 * 用户诉求：流式输出期间可自由上滑回看历史；点「回到底部」FAB、或手动
 * 滑回最底部后，恢复自动跟随。三个信号源各司其职：
 *
 * - **贴底判定 [isAtBottom]**：canScrollForward（测量结果直接派生，与
 *   contentPadding 约定无关）无剩余前向空间 = 精确贴底；辅以「距底剩余
 *   滚动量 ≤ 48px」的容差带（末条底边布局偏移补回 afterContentPadding
 *   即为剩余像素）。旧实现只算「末可见条底边与视口底的像素差」且不看
 *   条目 index：长条目（长结论气泡 / 终端面板）内部上翻时差值恒为负
 *   → 永远判「在底」→ 跟随永不松手（用户实测：流式期间根本滑不上去）。
 * - **阅读模式 [userScrolledUp]（手势锁存）**：DragInteraction 通道只由
 *   真实手指拖拽触发（程序化滚动不派发、底层 Channel 不合并不丢帧），
 *   拖拽结束时按落点锁存「正在阅读」；方向差分通道做兜底（滚轮 /
 *   键盘等无拖拽交互的滚动源）。回到底部（[isAtBottom] 翻真）自动
 *   解锁，恢复跟随 —— 即「滑到最底部继续跟踪」。
 * - **拖拽期停跟 [userDragging]**：手指在屏期间一律不做程序化滚动，
 *   不与手势抢滚动互斥 —— 旧实现每拍一次的 snapToLast 会把用户刚划开
 *   的列表当场拽回底部（snapshotFlow 合并把上滑位移与程序化吸底合并，
 *   方向检测形同虚设）。
 */
class StreamScrollAnchor(
    val listState: LazyListState,
    private val stepChars: Int = 200
) {
    /**
     * 贴底派生态：
     * 1. canScrollForward 为假 = 无剩余前向滚动空间，精确贴底（测量结果
     *    直接派生，contentPadding 的悬浮输入栏底部补偿已在滚动极限内
     *    消化，与坐标约定无关）；
     * 2. 容差带：距底剩余滚动量 ≤ [BOTTOM_EPSILON_PX]（欠滑几像素 /
     *    流式增长瞬态）仍算贴底 ——「滑回底部恢复跟随」的容差带宽。
     *    剩余量 = 末条底边布局偏移 - viewportEndOffset + afterContentPadding
     *    （测量坐标空间里 viewportEndOffset 含 after 补偿，末条底边在极限
     *    贴底时恰好等于 viewportEndOffset - afterContentPadding）。
     */
    val isAtBottom by derivedStateOf {
        // 精确贴底（约定无关）：无剩余前向滚动空间
        if (!listState.canScrollForward) return@derivedStateOf true
        val info = listState.layoutInfo
        val lastVisible = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
        val lastIndex = info.totalItemsCount - 1
        // 末可见条至少得是真实末条（倒数第二项，1dp 尾哨兵紧随其后）
        // —— 列表中部 / 长末条内部上翻时剩余距离天然巨大，index 判据
        // 先行显式拦下，不再依赖像素差的符号（旧实现的盲区）
        if (lastVisible.index < lastIndex - 1) return@derivedStateOf false
        val remaining = (lastVisible.offset + lastVisible.size) -
            info.viewportEndOffset + info.afterContentPadding
        remaining <= BOTTOM_EPSILON_PX
    }

    /** 用户主动上翻离底后置位（阅读模式），回底复位。 */
    var userScrolledUp by mutableStateOf(false)
        private set

    /** 用户手指拖拽进行中（DragInteraction.Start..Stop/Cancel 窗口）。 */
    var userDragging by mutableStateOf(false)
        private set

    /** 脱离底部跟随（阅读模式）：底部 FAB 的可见判据。 */
    val isDetachedFromBottom: Boolean
        get() = userScrolledUp && !isAtBottom

    /** 程序化滚动抑制：snapToLast/animateToLast 期间的方向变化不算用户上翻。 */
    @Volatile
    private var programmaticScroll = false

    /**
     * 滚动意图观察（LaunchedEffect 里 collect，三通道并发）：
     *
     * 1. 手势通道（权威）：拖拽开始 → 拖拽期停跟；拖拽结束按落点定夺 ——
     *    不在底部邻域 → 锁存阅读模式；
     * 2. 方向差分（兜底）：索引或条目内偏移递减 ⇔ 上翻（滚轮等无拖拽
     *    交互的滚动源；snapshotFlow 可能合并，仅作补充不作依赖）；
     * 3. 回底复位：[isAtBottom] 翻真 → 解锁阅读模式，恢复自动跟随。
     */
    suspend fun observe() {
        coroutineScope {
            // 1) 手势通道：真实手指拖拽（程序化滚动不派发 DragInteraction）
            launch {
                listState.interactionSource.interactions.collect { interaction ->
                    when (interaction) {
                        is DragInteraction.Start -> userDragging = true
                        is DragInteraction.Stop, is DragInteraction.Cancel -> {
                            userDragging = false
                            // 拖拽结束不在底部 → 阅读模式锁存（滑到底部则由
                            // 通道 3 解锁恢复跟随）
                            if (!isAtBottom) userScrolledUp = true
                        }
                    }
                }
            }
            // 2) 方向差分兜底：索引/条目内偏移递减 ⇔ 用户上翻（8px 容差
            //    吸收 layout 期亚像素抖动）
            launch {
                var prevIndex = listState.firstVisibleItemIndex
                var prevOffset = listState.firstVisibleItemScrollOffset
                snapshotFlow {
                    listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
                }.collect { (index, offset) ->
                    if (!programmaticScroll &&
                        (index < prevIndex || (index == prevIndex && offset < prevOffset - OFFSET_TOLERANCE_PX))
                    ) {
                        userScrolledUp = true
                    }
                    prevIndex = index
                    prevOffset = offset
                }
            }
            // 3) 回底复位：滑回最底部 → 恢复自动跟随（用户诉求原文：
            //    「我滑到最底部，它会继续跟踪任务，自动往下滑」）
            launch {
                snapshotFlow { isAtBottom }.collect { atBottom ->
                    if (atBottom) userScrolledUp = false
                }
            }
        }
    }

    /** 是否应 auto-follow：非拖拽期 且（贴底 或 未进入阅读模式）。 */
    fun shouldFollow(): Boolean = !userDragging && (isAtBottom || !userScrolledUp)

    /** 即时跟随末项（流式期间；时间轴末尾有 1dp 尾哨兵条目 → 等效贴底）。 */
    suspend fun snapToLast() {
        val total = listState.layoutInfo.totalItemsCount
        if (total > 0) {
            programmaticScroll = true
            try {
                listState.scrollToItem(total - 1)
            } finally {
                programmaticScroll = false
            }
        }
    }

    /** 动画回底（FAB / 收尾）—— 点击即恢复跟随意图。 */
    suspend fun animateToLast() {
        // 意图先行：立即解除阅读模式（FAB 消隐不等人）；若动画中途被用户
        // 拖拽打断，手势通道会按新落点重新评估（不在底部 → 再次锁存）
        userScrolledUp = false
        val total = listState.layoutInfo.totalItemsCount
        if (total > 0) {
            programmaticScroll = true
            try {
                listState.animateScrollToItem(total - 1)
            } finally {
                programmaticScroll = false
            }
        }
    }

    /** 内容长度节流档位（effect key 用）。 */
    fun followTick(contentLength: Int): Int = contentLength / stepChars

    private companion object {
        /** 贴底容差带：欠滑 / 流式增长瞬态的豁免宽度（≈ 一行胶囊高度）。 */
        const val BOTTOM_EPSILON_PX = 48

        const val OFFSET_TOLERANCE_PX = 8
    }
}

/**
 * 锚定装配：挂载观察协程 + 结构变化自动滚动。
 *
 * @param itemCountKey 列表结构变化 key（条目数）
 * @param contentTickKey 内容节流档位 key（字符数/200）
 * @param isStreaming 流式中（即时跟随）与收尾（动画）的分野
 */
@Composable
internal fun rememberStreamAnchor(
    listState: LazyListState,
    itemCountKey: Int,
    contentTickKey: Int,
    isStreaming: Boolean
): StreamScrollAnchor {
    val anchor = remember(listState) { StreamScrollAnchor(listState) }

    LaunchedEffect(listState) { anchor.observe() }

    // 结构变化（新条目）→ 跟随
    LaunchedEffect(itemCountKey, isStreaming) {
        if (itemCountKey > 0 && anchor.shouldFollow()) {
            if (isStreaming) anchor.snapToLast() else anchor.animateToLast()
        }
    }
    // 内容增长（同条目变长）→ 节流跟随（key 含 isStreaming：恢复会话后
    // 流式开启而字符档位未变时也要启动跟随）
    LaunchedEffect(contentTickKey, isStreaming) {
        if (isStreaming && itemCountKey > 0 && anchor.shouldFollow()) {
            anchor.snapToLast()
        }
    }
    return anchor
}
