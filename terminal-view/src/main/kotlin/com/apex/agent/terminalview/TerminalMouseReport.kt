package com.apex.agent.terminalview

import com.apex.agent.terminalemulator.MouseTrackingMode
import com.apex.agent.terminalemulator.TerminalMouseEventType
import com.apex.agent.terminalemulator.TerminalRenderSnapshot

/**
 * 鼠标报告编码出口 —— 从 `TerminalView` 缝拆（守 1200 行文件预算）。
 *
 * 只依赖快照 + client 回调两个显式参数（View 侧薄封装取字段）：
 *  - [dispatch]：点击/拖动/MOTION 派发，1-based 屏内坐标；
 *  - [wheel]：滚轮报告，位置取最近触点/指点位置（tmux 分屏路由正确 pane；
 *    解析不到 → 屏幕中心兜底）。
 *
 * 坐标一律钳到快照边界 —— resize 窗口期 grid 与快照短暂不一致时，
 * 防止把越界坐标发给 tmux/vim。
 */
internal object TerminalMouseReport {

    /** 派发一个鼠标事件（mergedRow = 合并网格行；滚回区行 < 1 → 丢弃）。 */
    fun dispatch(
        snap: TerminalRenderSnapshot,
        client: TerminalViewClient?,
        mergedRow: Int,
        col: Int,
        type: TerminalMouseEventType,
        button: Int,
        released: Boolean
    ) {
        val mode = snap.mouseMode.tracking
        if (mode == MouseTrackingMode.OFF) return
        // 1-based 屏内坐标（旧渲染器 onMouse 同款）
        val screenRow = mergedRow - snap.scrollback.size + 1
        if (screenRow < 1) return
        client?.onTerminalMouse(
            col = (col + 1).coerceIn(1, snap.cols),
            row = screenRow.coerceIn(1, snap.rows),
            type = type,
            mods = 0,
            mouseMode = mode,
            released = released,
            button = button
        )
    }

    /** 滚轮报告（up = WHEEL_UP）。[at] = 已解析的 (合并行, VT 列)，null → 中心。 */
    fun wheel(
        snap: TerminalRenderSnapshot,
        client: TerminalViewClient?,
        at: Pair<Int, Int>?,
        up: Boolean
    ) {
        val mode = snap.mouseMode.tracking
        if (mode == MouseTrackingMode.OFF) return
        val type = if (up) TerminalMouseEventType.WHEEL_UP else TerminalMouseEventType.WHEEL_DOWN
        val row = if (at != null) (at.first - snap.scrollback.size + 1).coerceAtLeast(1)
        else (snap.rows / 2 + 1).coerceAtLeast(1)
        val col = if (at != null) (at.second + 1).coerceAtLeast(1)
        else (snap.cols / 2 + 1).coerceAtLeast(1)
        client?.onTerminalMouse(
            col = col.coerceIn(1, snap.cols),
            row = row.coerceIn(1, snap.rows),
            type = type,
            mods = 0,
            mouseMode = mode,
            released = false,
            button = 0
        )
    }
}
