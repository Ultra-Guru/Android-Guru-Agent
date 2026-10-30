package com.apex.agent.platform.terminal.screen

import com.apex.agent.terminalemulator.RenderCell
import com.apex.agent.terminalemulator.TerminalRenderSnapshot

/**
 * Parsed virtual terminal screen state (plain-text projection for Agent observation).
 *
 * Spec ref: ATR 2.0 Final Spec §25
 *
 * Produced by [VirtualTerminal] (TerminalCore 2.0) from the PTY byte stream.
 * For the styled UI projection (colors / scrollback / DEC modes) use
 * [VirtualTerminal.styledSnapshot] → `TerminalRenderSnapshot`.
 */
data class TerminalScreenState(
    val rows: Int,
    val cols: Int,
    val cursorRow: Int,
    val cursorCol: Int,
    val alternateScreen: Boolean,
    /** DECTCEM cursor visibility (CSI ?25 h/l) — UI draws the caret only when true. */
    val cursorVisible: Boolean = true,
    val title: String?,
    /** Plain-text rendering of the visible screen, row-joined with \n. For Agent SCREEN observation. */
    val renderedText: String?,
    /** When returning an incremental update, the set of row indices that changed (null = full screen). */
    val changedRows: Set<Int>?,
    /**
     * T82: saved scrollback depth (main screen only; 0/null on alt screen).
     * The SCROLL observation layer can additionally request the scrollback tail.
     */
    val scrollbackLineCount: Int? = null,
    /**
     * v3（OSC 7 / 9;9）：guest shell 上报的当前工作目录（TerminalObserve SCREEN
     * 输出给 Agent，供后续 TerminalExec 的 cwd 续接）。
     *
     * 引擎覆盖诚实声明：JVM 回退引擎（TerminalCore，CI/单测）已解析 OSC 7
     * 并填充；设备端 native 引擎（NativeVtCore）的 C++ 侧尚未解析 OSC 7，
     * 此处返回 null —— 待 native 桥接就绪后自动生效，无需改本投影。
     */
    val guestCwd: String? = null
) {
    companion object {
        fun empty(rows: Int, cols: Int): TerminalScreenState = TerminalScreenState(
            rows = rows, cols = cols,
            cursorRow = 0, cursorCol = 0,
            alternateScreen = false, title = null,
            renderedText = "", changedRows = null
        )
    }
}
