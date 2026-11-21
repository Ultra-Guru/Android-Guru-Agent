package com.apex.agent.platform.terminal.state

import com.apex.agent.platform.terminal.buffer.TerminalOutputBuffer
import com.apex.agent.platform.terminal.events.TerminalEvent
import com.apex.agent.platform.terminal.events.TerminalEventLog
import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.screen.RealVirtualTerminal
import com.apex.agent.platform.terminal.screen.TerminalScreenState
import com.apex.agent.platform.terminal.screen.VirtualTerminal
import com.apex.agent.terminalemulator.TerminalRenderSnapshot
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*

/**
 * Formalized ObservationEngine. Encapsulates the 4 observe modes so the logic isn't inline
 * in TerminalRuntimeImpl.
 *
 * Spec ref: ATR 2.0 Final Spec §30 (ObservationEngine)
 *
 * Token-cost priority (Spec §30.5): SEMANTIC < EVENT < SCREEN < RAW.
 * Agent defaults to SEMANTIC + incremental EVENT.
 *
 * Modes:
 *   SEMANTIC — machine-readable state (session/job/input/cursor), NO raw output. Token-cheap.
 *   EVENT    — incremental events since afterCursor (capped at maxEvents).
 *   SCREEN   — parsed screen (renderedText + cursor + dims) for TUI (vim/top).
 *   RAW      — raw bytes since afterCursor (capped at maxBytes). Lowest priority; debug/recording.
 *
 * Cursor contract (Spec §13): previous.endCursor == next.startCursor. On overrun
 * (afterCursor < oldestCursor), return overrun=true + oldestCursor + empty bytes;
 * caller re-syncs using the returned `cursor` field.
 */
class ObservationEngine(
    private val eventLog: TerminalEventLog,
    private val ringBuffer: TerminalOutputBuffer,
    private val virtualTerminal: VirtualTerminal,
    private val semanticReducer: SemanticStateReducer
) {
    /**
     * Push-based screen state (Spec §41 — event-driven, NOT polling).
     * PtyOutputPump calls [refreshScreenState] after each VT feed; UI collects this Flow.
     * This replaces the old 50ms observe(SCREEN) polling loop.
     *
     * 订阅惰性 + 33ms 节流（与 [styledState] 同款契约）：refresh 仅在有订阅者时
     * 计算，且 33ms 窗口内只算一次 —— 旧实现在**每个** 8KB 读块后无条件全量
     * snapshot()（renderedText 全串 / 双 JNI），cat 大文件时每秒数百次 O(rows×cols)
     * 快照，而生产中本流无收集者（UI 走 styledState，Agent 走 observe(SCREEN)）
     * —— 纯浪费。快照流只反映「某一时刻」的屏幕，丢中间帧无害（终态由空闲兑底
     * 与订阅补发保证）。
     *
     * [onSubscription] 补发：新订阅者先收到一份**当刻新鲜**快照 —— 未订阅期间
     * 的刷新被惰性跳过，StateFlow 内部值可能落后任意多帧；`first()` 与新 UI
     * 挂载不能依赖旧值。
     */
    private val _screenState = MutableStateFlow(virtualTerminal.snapshot())
    val screenState: Flow<TerminalScreenState> = _screenState
        .onSubscription { emit(virtualTerminal.snapshot()) }

    /**
     * P83: styled render state for the UI grid renderer (colors / cursor / scrollback).
     *
     * Computed LAZILY — only while a collector is attached (subscriptionCount > 0).
     * This is the backpressure contract between the PTY feed rate and the UI: the
     * plain [screenState] stays token-cheap for Agent observation, while the styled
     * projection costs O(cells) and is skipped entirely when no terminal UI is open
     * (agent-only usage, background sessions, etc.).
     */
    private val _styledState = MutableStateFlow<TerminalRenderSnapshot?>(null)
    val styledState: StateFlow<TerminalRenderSnapshot?> = _styledState.asStateFlow()

    /**
     * Scrollback lines included in each styled snapshot (bounded for frame cost).
     *
     * 与引擎侧对齐（[com.apex.agent.vtnative.VtEngineFactory] / TerminalCore 默认
     * maxScrollback=1000）：旧值 400 使后 600 行 UI 永远滚不到（Agent 经
     * observe(SCREEN).scrollbackLines 倒是能读全量 —— 两端口径不一致）。
     * 内存量级：~1000+rows 行 × cols 个 RenderCell（每 cell 一个 String + 4 字段
     * ≈ 数十字节）≈ 数 MB 瞬时峰值 —— 仅在 styledState 有订阅者时计算（UI 打开
     * 的会话），agent-only 后台会话零成本；33ms 节流保证同一时刻至多一份在途，
     * 旧快照随 StateFlow 引用替换即刻可回收。
     */
    private val styledScrollbackLines: Int = 1000

    /**
     * OSC 52 host 落地（vim/tmux 远程复制）：pump 每次 VT feed 后 drain 出 guest
     * 的剪贴板写入请求（引擎侧已解码为 UTF-8 文本），经本流上抛给 app 层写
     * Android ClipboardManager。无消费者时丢弃（tryEmit + DROP_OLDEST，不阻塞
     * feed 热路径）—— 引擎内的待处理队列有界（8 条），必须常 drain 防止新请求
     * 顶掉旧值。快照相等去重（screen 不变时 StateFlow 不发射）不影响本通道：
     * drain 直接挂在 feed 钩子上而非快照流。
     */
    private val _clipboardRequests = MutableSharedFlow<String>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val clipboardRequests: SharedFlow<String> = _clipboardRequests.asSharedFlow()

    // ── P0（性能）：快照节流（screen 与 styled 同一套契约）──
    // styledSnapshot(1000) = 1000+rows × cols 个 RenderCell 分配。旧实现在**每个**
    // 8KB 读块后都算一次（cat 大文件 → 每秒上百次全量渲染、百万级对象/秒的分配
    // 洪峰，双核机直接拖垮整机响应）；而 UI 侧 sample(33) 每帧最多消费一次 ——
    // 绝大多数计算被白白丢弃。现在：33ms 最小间隔内跳过（标脏待重试），
    // pump 空闲轮询的 onOutput 兑底补算被节流掉的最后一段，屏幕不会停在旧帧。
    // screenState 快照（renderedText 全串）同款处理。全部仅在对应流有订阅者时
    // 生效（与旧契约一致）。
    private var styledDirty = false
    private var lastStyledAtNs = 0L
    private var screenDirty = false
    private var lastScreenAtNs = 0L

    /**
     * Called by PtyOutputPump after feeding bytes to VT — pushes new screen snapshot.
     * Also invoked from the pump's idle poll (no-data branch) to settle the throttled
     * tail: 输出停止后最后 33ms 内被跳过的内容由此补渲染。
     */
    fun refreshScreenState() {
        if (_screenState.subscriptionCount.value > 0) {
            screenDirty = true
            maybeRefreshScreen()
        }
        if (_styledState.subscriptionCount.value > 0) {
            styledDirty = true
            maybeRefreshStyled()
        }
        drainClipboardRequests()
    }

    /** 节流后的 plain screen 快照刷新（脏标记 + 33ms 最小间隔；间隔不足则留脏等下次机会）。 */
    private fun maybeRefreshScreen() {
        if (!screenDirty) return
        val now = System.nanoTime()
        if (now - lastScreenAtNs < SNAPSHOT_MIN_INTERVAL_NS) return // 留脏：下一帧/空闲兑底
        screenDirty = false
        lastScreenAtNs = now
        _screenState.value = virtualTerminal.snapshot()
    }

    /** 节流后的 styled 快照刷新（脏标记 + 33ms 最小间隔；间隔不足则留脏等下次机会）。 */
    private fun maybeRefreshStyled() {
        if (!styledDirty) return
        val now = System.nanoTime()
        if (now - lastStyledAtNs < SNAPSHOT_MIN_INTERVAL_NS) return // 留脏：下一帧/空闲兑底
        styledDirty = false
        lastStyledAtNs = now
        _styledState.value = virtualTerminal.styledSnapshot(styledScrollbackLines)
    }

    /** Drain OSC 52 请求并转发（见 [clipboardRequests]；Stub VT 无此能力，静默跳过）。 */
    private fun drainClipboardRequests() {
        val requests = (virtualTerminal as? RealVirtualTerminal)
            ?.drainClipboardRequests() ?: return
        for (text in requests) _clipboardRequests.tryEmit(text)
    }

    /** Push-based semantic state (from SemanticStateReducer, already a StateFlow). */
    val semanticState: StateFlow<com.apex.agent.platform.terminal.state.TerminalSemanticState> get() = semanticReducer.state

    /**
     * @param sessionId   target session
     * @param mode        one of SEMANTIC / EVENT / SCREEN / RAW
     * @param afterCursor for EVENT/RAW: return data after this cursor (use previous endCursor)
     * @param maxBytes    for RAW/SCREEN: max bytes to return
     * @param maxEvents   for EVENT: max events to return
     * @return ObservationResult with the mode-appropriate fields populated
     */
    suspend fun observe(
        sessionId: Long,
        mode: TerminalRuntime.ObserveMode,
        afterCursor: Long,
        maxBytes: Int,
        maxEvents: Int,
        scrollbackLines: Int = 0
    ): TerminalRuntime.ObserveResult {
        val currentCursor = ringBuffer.totalCursor
        return when (mode) {
            TerminalRuntime.ObserveMode.SEMANTIC -> {
                // PR #50: run PromptDetector (multi-signal, O(lastLine)) and enrich SemanticState
                val baseState = semanticReducer.snapshot()
                val prompt = com.apex.agent.platform.terminal.intelligence.PromptDetector.detect(
                    vt = virtualTerminal,
                    foregroundCommand = baseState.foregroundJob?.command
                )
                val enriched = baseState.copy(prompt = prompt)
                TerminalRuntime.ObserveResult(
                    mode = mode,
                    sessionId = sessionId,
                    cursor = currentCursor,
                    semantic = enriched
                )
            }

            TerminalRuntime.ObserveMode.EVENT -> {
                val events = eventLog.query(sessionId, afterCursor, maxEvents)
                val endCursor = events.lastOrNull { it.cursor >= 0 }?.cursor ?: afterCursor
                TerminalRuntime.ObserveResult(
                    mode = mode,
                    sessionId = sessionId,
                    cursor = currentCursor,
                    startCursor = afterCursor,
                    endCursor = endCursor,
                    truncated = events.size >= maxEvents,
                    overrun = false,
                    events = events
                )
            }

            TerminalRuntime.ObserveMode.SCREEN -> {
                // T82：scrollback 尾部（oldest→newest）—— 主屏保存最近 1000 行；
                // 仅 RealVirtualTerminal 支持（Stub 不带 —— null 字段保持诚实）。
                val tail = if (scrollbackLines > 0) {
                    (virtualTerminal as? RealVirtualTerminal)
                        ?.scrollbackLines(scrollbackLines)
                } else null
                TerminalRuntime.ObserveResult(
                    mode = mode,
                    sessionId = sessionId,
                    cursor = currentCursor,
                    screen = virtualTerminal.snapshot(),
                    scrollbackTail = tail
                )
            }

            TerminalRuntime.ObserveMode.RAW -> {
                val slice = ringBuffer.getSince(afterCursor, maxBytes)
                TerminalRuntime.ObserveResult(
                    mode = mode,
                    sessionId = sessionId,
                    cursor = currentCursor,
                    startCursor = slice.startCursor,
                    endCursor = slice.endCursor,
                    truncated = slice.truncated,
                    overrun = slice.overrun,
                    oldestCursor = if (slice.overrun) ringBuffer.oldestCursor else null,
                    raw = com.apex.agent.platform.terminal.buffer.Utf8Boundary.decodeWindow(slice.bytes)  // T81 (D-6)：跳过窗口头部残缺 UTF-8 序列
                )
            }
        }
    }

    private companion object {
        /** 快照最小刷新间隔（与 UI sample(33) 对齐；≈1 帧）。 */
        const val SNAPSHOT_MIN_INTERVAL_NS = 33_000_000L
    }
}
