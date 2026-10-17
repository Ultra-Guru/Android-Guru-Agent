package com.apex.agent.terminalemulator

/**
 * ═══ CSI 修正类操作的纯函数体 —— v0.3 从 TerminalCore 拆出 ═══
 *
 * 拆分动机（Task 2-b 文件预算 + 可读性）：TerminalCore 的 CSI 分发 switch
 * 保留在原处（序列语义总表一目了然），但 IL/DL/ICH/DCH/ECH/EL/ED 的**函数体**
 * 全部是「对 (buffer, cursor, 参数) 的纯过程」—— 抽到这里后：
 *  - 函数签名显式声明边距窗口（[left]/[right]，v0.3 DECLRMM 全语义）；
 *  - 可脱离引擎直接单测（构造 ScreenBuffer + CursorState 即可驱动）。
 *
 * 所有函数保持 TerminalCore 迁移前的既有语义（含 P2 宽字符整对保护、
 * T85 审计修正），只是把 `cols` 换成了可传入的边距窗口右界。
 * 纯 JVM、无 Android 依赖。
 */
internal object CsiOps {

    // ─── ED（erase display，§16）───
    // 说明：ED 不受左右边距裁剪（xterm/DEC STD 070 —— 整屏语义保留）。

    /**
     * ED：0=光标→屏尾，1=屏头→光标，2=全屏，3=只清主屏 scrollback。
     * 返回应登记的 mutation。
     */
    fun eraseDisplay(
        buffer: ScreenBuffer,
        cursor: CursorState,
        rows: Int,
        cols: Int,
        mode: Int,
        style: TerminalStyle,
        mainBuffer: ScreenBuffer
    ): ScreenMutation {
        when (mode) {
            0 -> {
                buffer.eraseRow(cursor.row, cursor.column, cols - 1, style)
                buffer.eraseRows(cursor.row + 1, rows - 1, style)
            }
            1 -> {
                buffer.eraseRows(0, cursor.row - 1, style)
                buffer.eraseRow(cursor.row, 0, cursor.column, style)
            }
            2 -> buffer.eraseRows(0, rows - 1, style)
            // ED 3（xterm "erase saved lines"）：只清主屏 scrollback —— 可见屏与
            // 备用屏均不动（备用屏本无 scrollback；`clear` 命令依赖此语义不闪屏）。
            3 -> mainBuffer.clearScrollback()
        }
        return ScreenMutation(ScreenMutation.MutationType.ERASE, 0 until rows)
    }

    // ─── EL（erase line）───

    /**
     * EL：0=光标→行尾，1=行首→光标，2=整行。
     * v0.3：作用域被左右边距 [left]..[right] 裁剪（DECLRMM）。
     */
    fun eraseLine(
        buffer: ScreenBuffer,
        cursor: CursorState,
        mode: Int,
        style: TerminalStyle,
        left: Int,
        right: Int
    ): ScreenMutation {
        when (mode) {
            0 -> buffer.eraseRow(cursor.row, cursor.column, right, style)
            1 -> buffer.eraseRow(cursor.row, left, cursor.column, style)
            2 -> buffer.eraseRow(cursor.row, left, right, style)
        }
        return ScreenMutation.rows(cursor.row, cursor.row)
    }

    // ─── ICH（insert chars，§5）───

    /**
     * ICH：在光标处插入 [n] 个空白 cell，行右移；移动/空白填充区间被
     * 边距 [left]..[right] 裁剪（右边距外内容不动）。
     * P2：起点宽字符配对感知（整对一起移，防拆对孤儿）。
     */
    fun insertChars(buffer: ScreenBuffer, cursor: CursorState, n: Int, left: Int, right: Int) {
        val r = cursor.row
        val start = wideAwareStart(buffer, r, cursor.column).coerceAtLeast(left)
        // diag-20261017 崩溃热修（P0）：大参数饱和钳位到剩余行宽。旧实现只有
        // 下限钳位 —— CSI 大参数（ESC[2147483647@）使 start+count 整型溢出为负，
        // 下方 downTo 循环在 engineLock 内空转 ~2^31 次（ANR/卡死，泵线程与
        // 快照渲染全部阻塞）。xterm 语义本就是「插入数不超过行尾」。
        val count = n.coerceAtLeast(1).coerceAtMost((right - start + 1).coerceAtLeast(1))
        for (c in right downTo (start + count)) {
            buffer.setCell(r, c, buffer.get(r, c - count))
        }
        for (c in start until (start + count).coerceAtMost(right + 1)) {
            buffer.setCell(r, c, TerminalCell.BLANK)
        }
        buffer.repairRow(r)
    }

    // ─── DCH（delete chars）───

    /**
     * DCH：在光标处删除 [n] 个 cell，行左移；区间同样被边距裁剪
     * （补尾空白落在 [right]）。
     * P2：宽字符整对删除 —— 起点在 trail → 左扩到 lead；起点在 lead →
     * count+1 吸收 trail。repairRow 兜底。
     */
    fun deleteChars(buffer: ScreenBuffer, cursor: CursorState, n: Int, cols: Int, left: Int, right: Int) {
        val count = n.coerceAtLeast(1)
        val r = cursor.row
        var start = cursor.column
        var effective = count
        when {
            cursor.column > left && buffer.get(r, cursor.column).isWideTrail &&
                buffer.get(r, cursor.column - 1).isWideLead -> {
                start = cursor.column - 1
                effective = count + 1
            }
            buffer.get(r, cursor.column).isWideLead &&
                cursor.column + 1 <= right && buffer.get(r, cursor.column + 1).isWideTrail -> {
                effective = count + 1
            }
        }
        if (start < left) start = left
        // diag-20261017 崩溃热修（P0）：effective 饱和到剩余行宽（宽字符配对
        // count+1 与大参数 n 在此可能溢出为负 → 下方 src = c + effective 越界
        // 方向翻转，语义错乱；饱和后 get/set 越界保护不再被触发）。
        effective = effective.coerceAtLeast(1).coerceAtMost(right - start + 1)
        for (c in start..right) {
            val src = c + effective
            buffer.setCell(r, c, if (src <= right) buffer.get(r, src) else TerminalCell.BLANK)
        }
        buffer.repairRow(r)
    }

    // ─── IRM 打印路径的行内右移 ───

    /**
     * IRM（插入模式打印）：把光标处 [width] 列腾出来（右侧右移），
     * 区间被边距裁剪。P2：起点配对感知。
     */
    fun insertCharsAtCursor(buffer: ScreenBuffer, cursor: CursorState, width: Int, left: Int, right: Int) {
        val r = cursor.row
        val start = wideAwareStart(buffer, r, cursor.column).coerceAtLeast(left)
        for (c in right downTo (start + width)) {
            buffer.setCell(r, c, buffer.get(r, c - width))
        }
        for (c in start until (start + width).coerceAtMost(right + 1)) {
            buffer.setCell(r, c, TerminalCell.BLANK)
        }
        buffer.repairRow(r)
    }

    // ─── 共用 ───

    /**
     * P2：插入类操作的宽字符感知起点 —— [col] 是某宽字符的 trail
     *（lead 在 col-1）时返回 col-1，使插入位不拆散既有宽字符对。
     */
    fun wideAwareStart(buffer: ScreenBuffer, row: Int, col: Int): Int =
        if (col > 0 && buffer.get(row, col).isWideTrail &&
            buffer.get(row, col - 1).isWideLead
        ) col - 1 else col
}
