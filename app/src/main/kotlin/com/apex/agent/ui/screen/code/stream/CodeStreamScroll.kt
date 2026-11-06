package com.apex.agent.ui.screen.code.stream

import androidx.compose.foundation.gestures.scrollBy
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
import kotlin.math.abs
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * # Code Stream Scroll — 时间轴智能滚动锚
 *
 * ## 语义（20261101 重构：时间戳抑制 + 轻触防误锁 + 双步贴底）
 *
 * 用户诉求：流式输出期间可自由上滑回看历史；点「回到底部」FAB、或手动
 * 滑回最底部后，恢复自动跟随。三个信号源各司其职：
 *
 * - **贴底判定 [isAtBottom]**：canScrollForward（测量结果直接派生，与
 *   contentPadding 约定无关）无剩余前向空间 = 精确贴底；辅以「距底剩余
 *   滚动量 ≤ 48px」的容差带。
 * - **阅读模式 [userScrolledUp]（手势锁存）**：DragInteraction 通道只由
 *   真实手指拖拽触发；拖拽结束时要求实际滚动位移超过 [TAP_SLOP_PX]
 *   才锁存「正在阅读」—— 轻触/误触不中断跟随（20261101 修复）。
 *   回到底部（[isAtBottom] 翻真）自动解锁，恢复跟随。
 * - **拖拽期停跟 [userDragging]**：手指在屏期间一律不做程序化滚动。
 *
 * ## 20261101 修复：跟随自行中断
 *
 * 旧实现用布尔 `programmaticScroll` 抑制方向差分，但 `snapshotFlow` 的
 * 发射是异步的 —— `scrollToItem` 返回后标志已复位，发射才到达，程序化
 * 滚动引起的 index 变化被误判为用户上翻。改用**时间戳窗口**（[PROGRAMMATIC_GRACE_MS]
 * 内的方向变化一律不算用户上翻），窗口覆盖 snapshotFlow 的异步延迟。
 * 另加 [isAtBottom] 守卫：贴底时 index 波动不锁存阅读模式。
 */
class StreamScrollAnchor(
    val listState: LazyListState,
    private val stepChars: Int = STEP_CHARS
) {
    /**
     * 贴底派生态：
     * 1. canScrollForward 为假 = 无剩余前向滚动空间，精确贴底；
     * 2. 容差带：距底剩余滚动量 ≤ [BOTTOM_EPSILON_PX] 仍算贴底。
     */
    val isAtBottom by derivedStateOf {
        if (!listState.canScrollForward) return@derivedStateOf true
        val info = listState.layoutInfo
        val lastVisible = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
        val lastIndex = info.totalItemsCount - 1
        // 末可见条至少得是真实末条（倒数第二项，1dp 尾哨兵紧随其后）
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

    /**
     * 程序化滚动抑制窗口：此时间戳之前的方向变化不算用户上翻。
     * 时间戳方案替代旧布尔标志 —— snapshotFlow 发射是异步的，布尔在
     * scrollToItem 返回后立即复位，发射才到达时抑制已失效（竞态根因）。
     */
    @Volatile
    private var programmaticScrollUntil = 0L

    private fun isProgrammatic(): Boolean =
        System.currentTimeMillis() < programmaticScrollUntil

    /** 拖拽起点（Stop 时计算位移量，区分轻触与真实拖拽）。 */
    private var dragStartIndex = 0
    private var dragStartOffset = 0

    /**
     * 滚动意图观察（LaunchedEffect 里 collect，三通道并发）：
     *
     * 1. 手势通道（权威）：拖拽开始 → 拖拽期停跟；拖拽结束要求位移超过
     *    [TAP_SLOP_PX] 且不在底部 → 锁存阅读模式（轻触不中断跟随）；
     * 2. 方向差分（兜底）：非程序化窗口 且 非贴底 时，索引/偏移递减 ⇔ 上翻；
     * 3. 回底复位：[isAtBottom] 翻真 → 解锁阅读模式，恢复自动跟随。
     */
    suspend fun observe() {
        coroutineScope {
            // 1) 手势通道：真实手指拖拽（程序化滚动不派发 DragInteraction）
            launch {
                listState.interactionSource.interactions.collect { interaction ->
                    when (interaction) {
                        is DragInteraction.Start -> {
                            userDragging = true
                            dragStartIndex = listState.firstVisibleItemIndex
                            dragStartOffset = listState.firstVisibleItemScrollOffset
                        }
                        is DragInteraction.Stop, is DragInteraction.Cancel -> {
                            userDragging = false
                            // 轻触防误锁：位移超过 TAP_SLOP_PX 才算拖拽（20261101 修复）
                            val moved = listState.firstVisibleItemIndex != dragStartIndex ||
                                abs(listState.firstVisibleItemScrollOffset - dragStartOffset) > TAP_SLOP_PX
                            if (moved && !isAtBottom) userScrolledUp = true
                        }
                    }
                }
            }
            // 2) 方向差分兜底：非程序化窗口 且 非贴底时，索引/条目内偏移递减 ⇔ 上翻
            launch {
                var prevIndex = listState.firstVisibleItemIndex
                var prevOffset = listState.firstVisibleItemScrollOffset
                snapshotFlow {
                    listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
                }.collect { (index, offset) ->
                    if (!isProgrammatic() && !isAtBottom &&
                        (index < prevIndex || (index == prevIndex && offset < prevOffset - OFFSET_TOLERANCE_PX))
                    ) {
                        userScrolledUp = true
                    }
                    prevIndex = index
                    prevOffset = offset
                }
            }
            // 3) 回底复位：滑回最底部 → 恢复自动跟随
            launch {
                snapshotFlow { isAtBottom }.collect { atBottom ->
                    if (atBottom) userScrolledUp = false
                }
            }
        }
    }

    /** 是否应 auto-follow：非拖拽期 且（贴底 或 未进入阅读模式）。 */
    fun shouldFollow(): Boolean = !userDragging && (isAtBottom || !userScrolledUp)

    /**
     * 即时跟随末项（流式期间）。
     *
     * 双步贴底（20261101 修复）：`scrollToItem(total-1)` 可能把 1dp 哨兵
     * 定在视口顶（不 clamp 到 max scroll），可见内容被推出视口上方；
     * 追加正向 `scrollBy` 强制滑到 max scroll —— 内容底对齐视口底减
     * afterContentPadding（即工具栏上方），不再跑到输入栏遮挡区。
     */
    suspend fun snapToLast() {
        val total = listState.layoutInfo.totalItemsCount
        if (total > 0) {
            programmaticScrollUntil = System.currentTimeMillis() + PROGRAMMATIC_GRACE_MS
            try {
                listState.scrollToItem(total - 1)
                // 双步贴底：正向大步长 scrollBy，LazyList 内部 clamp 到 max scroll
                listState.scrollBy(listState.layoutInfo.viewportEndOffset.toFloat())
            } finally {
                // 时间戳自动过期，无需手动复位
            }
        }
    }

    /** 动画回底（FAB / 收尾）—— 点击即恢复跟随意图。 */
    suspend fun animateToLast() {
        userScrolledUp = false
        val total = listState.layoutInfo.totalItemsCount
        if (total > 0) {
            programmaticScrollUntil = System.currentTimeMillis() + PROGRAMMATIC_GRACE_MS
            try {
                listState.animateScrollToItem(total - 1)
            } finally {
                // 时间戳自动过期
            }
        }
    }

    /** 内容长度节流档位（effect key 用）。 */
    fun followTick(contentLength: Int): Int = contentLength / stepChars

    private companion object {
        /** 贴底容差带：欠滑 / 流式增长瞬态的豁免宽度（≈ 一行胶囊高度）。 */
        const val BOTTOM_EPSILON_PX = 48

        /** 方向差分容差（吸收 layout 期亚像素抖动）。 */
        const val OFFSET_TOLERANCE_PX = 8

        /** 轻触判定阈值：拖拽位移低于此值不算上翻（防止误触中断跟随）。 */
        const val TAP_SLOP_PX = 12

        /** 程序化滚动抑制窗口（覆盖 snapshotFlow 异步发射延迟）。 */
        const val PROGRAMMATIC_GRACE_MS = 150L

        /** 内容节流档位字符数（200 → 50：终端输出快速增长时追赶更紧）。 */
        const val STEP_CHARS = 50
    }
}

/**
 * 锚定装配：挂载观察协程 + 结构变化自动滚动。
 *
 * @param itemCountKey 列表结构变化 key（条目数）
 * @param contentTickKey 内容节流档位 key（字符数/50）
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
    // 内容增长（同条目变长）→ 节流跟随
    LaunchedEffect(contentTickKey, isStreaming) {
        if (isStreaming && itemCountKey > 0 && anchor.shouldFollow()) {
            anchor.snapToLast()
        }
    }
    return anchor
}
