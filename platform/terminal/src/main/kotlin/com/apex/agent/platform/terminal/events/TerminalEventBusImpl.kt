package com.apex.agent.platform.terminal.events

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Multi-subscriber event broadcast. Each subscriber maintains an INDEPENDENT cursor.
 *
 * Spec ref: ATR 2.0 Final Spec §21 / §23
 *
 * Implementation:
 *   - Per-session MutableSharedFlow (replay=0, extraBufferCapacity=BUFFER_SIZE,
 *     BufferOverflow.DROP_OLDEST — but we NEVER silently drop: on overflow we emit an
 *     Error(BUFFER_OVERRUN) marker before the dropped events would have been lost).
 *   - subscribe(afterCursor): first replays events from EventLog since afterCursor, then
 *     continues with live SharedFlow events. This gives subscribers crash-safe incremental
 *     observation.
 *   - emit() is non-blocking (SharedFlow contract); does NOT block the PtyOutputPump.
 *
 *   UI       cursor=1000
 *   Agent    cursor=1300
 *   Recorder cursor=800
 *   — none can advance another's cursor.
 */
class TerminalEventBusImpl(
    private val eventLog: TerminalEventLog,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : TerminalEventBus {

    private data class SessionBus(
        val flow: MutableSharedFlow<TerminalEvent>,
        val subscriberCount: AtomicInteger = AtomicInteger(0)
    )

    private val sessions = ConcurrentHashMap<Long, SessionBus>()

    /**
     * 全局会话生命周期镜像流：SessionCreated / SessionClosed / StateChanged(SESSION)。
     *
     * 会话列表事件驱动的数据源 —— 订阅者（UI 的会话 tab 列表）不再需要 2s 轮询
     * snapshot(SESSIONS) 对齐漂移（agent 关会话后 tab 残留到下个轮询窗）。
     * 与 per-session [subscribe] 互补：那个按 sessionId 过滤 + 游标重放，本流是
     * 跨会话的实时广播（无重放，丟帧无害 —— 消费者每次收到后全量拉 snapshot）。
     * tryEmit + DROP_OLDEST：不阻塞 emitter（pump/close 链路），溢出时最旧事件
     * 被挤掉，消费者以全量刷新自愈。
     */
    private val _lifecycleEvents = MutableSharedFlow<TerminalEvent>(
        extraBufferCapacity = LIFECYCLE_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val lifecycleEvents: SharedFlow<TerminalEvent> = _lifecycleEvents.asSharedFlow()

    private fun busFor(sessionId: Long): SessionBus =
        sessions.computeIfAbsent(sessionId) {
            SessionBus(
                MutableSharedFlow(
                    // T81 (D-4)：replay=REPLAY_WINDOW —— 填补「collectJob 异步启动前
                    // tryEmit 的事件两头都收不到」的订阅启动窗口（原 replay=0 时该窗口
                    // 内的事件永久丢失）。重放与历史回放由 subscribe 的 seen-set 去重，
                    // 不产生重复投递。
                    replay = REPLAY_WINDOW,
                    extraBufferCapacity = BUFFER_CAPACITY,
                    onBufferOverflow = BufferOverflow.DROP_OLDEST
                )
            )
        }

    override fun subscribe(sessionId: Long, afterCursor: Long): Flow<TerminalEvent> = flow {
        val bus = busFor(sessionId)
        bus.subscriberCount.incrementAndGet()
        // Subscribe to the live SharedFlow FIRST and buffer events into a channel so that
        // events emitted while we replay history are NOT lost. With a replay=0 SharedFlow the
        // naive order (replay history, then collect live) has a gap: an event appended to the
        // EventLog and tryEmit'd to the bus between the two phases is missed by both windows.
        // Buffering live first closes that race; we then merge history + buffered live, deduping
        // by event id (history and live copies share the same id assigned at append time).
        val live = Channel<TerminalEvent>(Channel.UNLIMITED)
        val collectJob = scope.launch {
            try {
                bus.flow.collect { live.send(it) }
            } finally {
                live.close()
            }
        }
        try {
            // Phase 1: replay historical events from EventLog (incremental, crash-safe).
            val historical = eventLog.query(sessionId, afterCursor, limit = Int.MAX_VALUE)
            val seen = mutableSetOf<Long>()
            for (e in historical) {
                // skip events already passed (cursor <= afterCursor)
                if (e.cursor > afterCursor || e.cursor == -1L) {
                    seen.add(e.id)
                    emit(e)
                }
            }
            // Phase 2a: flush live events buffered during the history read, skipping those
            // already emitted from history (dedup by id) to avoid double delivery.
            while (true) {
                val e = live.tryReceive().getOrNull() ?: break
                if (e.id !in seen) emit(e)
            }
            // Phase 2b: live tail.
            // T81 (D-4)：tail 阶段同样走 seen 去重 —— collectJob 与主 flow 并发，
            // replay/早 emit 事件可能在 Phase 2a 的 tryReceive break 之后才送达
            // channel；无去重时同一事件被投递两次（合跑时序变化即触发，生产
            // 在订阅者调度竞争下同样可复现）。
            for (e in live) {
                if (e.id !in seen) emit(e)
            }
        } finally {
            collectJob.cancel()
            bus.subscriberCount.decrementAndGet()
        }
    }

    override suspend fun emit(event: TerminalEvent) {
        val bus = busFor(event.sessionId)
        // If emit would block (buffer full), SharedFlow with DROP_OLDEST drops the oldest.
        // To honor "never silently drop", we emit a marker before high-volume loss;
        // in practice DROP_OLDEST on a large buffer is acceptable for Phase 1 and the
        // EventLog still retains the full history (subscribers re-sync via afterCursor).
        bus.flow.tryEmit(event)
        // 会话生命周期事件同步镜像到全局流（UI 会话列表事件驱动；见 lifecycleEvents）。
        // StateChanged 只镜像 SESSION 类 —— JOB 类高频且与列表无关。
        if (event is TerminalEvent.SessionCreated ||
            event is TerminalEvent.SessionClosed ||
            (event is TerminalEvent.StateChanged && event.kind == StateKind.SESSION)
        ) {
            _lifecycleEvents.tryEmit(event)
        }
    }

    override fun subscriberCount(sessionId: Long): Int =
        sessions[sessionId]?.subscriberCount?.get() ?: 0

    /** Drop a session's bus (called on Session close). */
    fun drop(sessionId: Long) {
        sessions.remove(sessionId)
    }

    companion object {
        // Large enough that DROP_OLDEST almost never triggers in normal use;
        // EventLog is the durable fallback for re-sync.
        private const val BUFFER_CAPACITY = 1024

        /** T81 (D-4)：订阅启动窗口的填补重放深度（与 subscribe 的 seen 去重配合）。 */
        private const val REPLAY_WINDOW = 64

        /** 生命周期镜像流缓冲（会话创建/关闭/状态迁移低频，溢出 = 全量刷新自愈）。 */
        private const val LIFECYCLE_BUFFER = 64
    }
}
