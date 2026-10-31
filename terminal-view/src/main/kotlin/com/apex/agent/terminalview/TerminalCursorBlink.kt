package com.apex.agent.terminalview

import android.os.Handler

/**
 * T92：光标闪烁相位调度（从 TerminalView 抽出 —— SRP 行预算；合并 main 的
 * 手势排除代码后该文件再度超限）。
 *
 * 电池纪律（原 View 内注释语义不变）：
 *  - 主线程 Handler 换相（默认 2 相/秒；`cursorBlinkMs` 下限钳 200ms）；
 *  - `invalidate()` 由 [onInvalidate] 回调（postInvalidate 语义由 View 决定）；
 *  - 不可闪（设置关/光标隐藏/失焦/未附着）时停排 + 相位回亮。
 */
internal class TerminalCursorBlink(
    private val handler: Handler,
    /** 换相/排程前查询：设置开 + 光标可见 + 窗口焦点 + 已附着。 */
    private val canBlink: () -> Boolean,
    /** 相位翻转后的重绘回调。 */
    private val onInvalidate: () -> Unit,
    /** 闪烁间隔 ms（负值钳 200）。 */
    private val intervalMs: () -> Long
) {
    /** 当前相位（渲染层帧构造读取）。 */
    var on: Boolean = true
        private set

    private var scheduled = false

    private val toggle = Runnable {
        scheduled = false
        on = !on
        onInvalidate()
        scheduleIfNeeded()
    }

    /** 快照/焦点/设置变化时调用（幂等；不可闪即停排回亮）。 */
    fun scheduleIfNeeded() {
        if (!canBlink()) {
            on = true
            if (scheduled) {
                handler.removeCallbacks(toggle)
                scheduled = false
            }
            return
        }
        if (!scheduled) {
            scheduled = true
            handler.postDelayed(toggle, intervalMs().coerceAtLeast(200L))
        }
    }
}
