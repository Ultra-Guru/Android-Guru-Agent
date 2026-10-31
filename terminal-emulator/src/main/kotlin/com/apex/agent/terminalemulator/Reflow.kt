package com.apex.agent.terminalemulator

/**
 * ═══ Resize reflow（软换行重排）—— native vt_reflow.cpp 的 Kotlin 移植（v0.3）═══
 *
 * 问题：缩放窗口宽度时，ScreenBuffer.resize 的「保左上角裁剪」会把右缘文字
 * **裁掉**（历史即失）。本引擎按 native apex-vt-reflow 的契约重排：
 *
 *  * scrollback + 可见屏的视觉行先按接续标志（ScreenBuffer.rowWrapped）拼回
 *    **逻辑行**（软换行链），再按新宽度重新流式布局 —— 内容零丢失；
 *  * 宽字符绝不跨行：放不下的宽字符把行尾孤儿列用其样式补空白、整对下移
 *    （newCols==1 时降级为窄格 —— 有界且全序，见 [MAX_COLS]/[MAX_ROWS_PER_LINE]）；
 *  * 尾部空白按 rowText 语义裁剪（全空白行保留 1 格，携带样式渲染一行）；
 *  * 组合标记内嵌在 [TerminalCell.combining]，随基元格整体流动（native 需要
 *    独立 comb 表 —— Kotlin 模型天然免重写）；
 *  * 光标按「逻辑行内基元格序数」重放：同一序数 → 同一 cell → 它的新 lead
 *    列；序数越过行尾（含 wrapPending 的 +1 列）→ 落在行尾之后。
 *
 * [rewrap] 是纯函数（可直接单测）；[applyResize] 是引擎接线（重建主屏 +
 * 光标映射 + 溢出行回灌 scrollback），由 TerminalCore.resize 在**宽度变化**
 * 时调用。高度变化仍走裁剪/补空路径；备用屏从不 reflow（vim/less 依赖该语义）。
 *
 * 契约（与 vt_reflow.h 逐条对齐）：
 *  - 每条输入逻辑行产出 ≥ 1 行（空行也给 1 行）；两条逻辑行绝不共享输出行；
 *  - 输出行恰好 newCols 宽（含继承末格样式的右侧填充）；
 *  - 单逻辑行输出行数 ≤ [MAX_ROWS_PER_LINE]（保头弃尾）。
 */
internal object Reflow {

    /** newCols 上界（契约 [1,512]）。 */
    const val MAX_COLS = 512

    /** 单逻辑行输出行数上界（保头弃尾 —— 灾难性单行不失控）。 */
    const val MAX_ROWS_PER_LINE = 65535

    /** rewrap 结果：输出行（恰好 newCols 宽）+ 行→逻辑行映射 + 光标映射。 */
    class ReflowResult(
        val rows: List<Array<TerminalCell>>,
        val lineOfRow: IntArray,
        /** 输出全局行（0 基；cursorRow 输入无归属时为 0 —— 调用方再钳制）。 */
        val cursorRow: Int,
        val cursorCol: Int
    )

    /**
     * 纯重排。
     *
     * @param inputRows 输入视觉行（scrollback 旧→新 + 可见屏上→下；每行宽可不等）
     * @param inputWrapped inputRows[i] 的内容软换行延续到下一行（最后一行恒 false）
     * @param cursorRow 光标所在的输入全局行（-1 = 无归属 → 输出光标 (0,0)）
     * @param cursorCol 光标列（wrapPending 语义由调用方 +1 传入）
     * @param toCols 目标宽度（clamp [1,512]）
     */
    fun rewrap(
        inputRows: List<Array<TerminalCell>>,
        inputWrapped: BooleanArray,
        cursorRow: Int,
        cursorCol: Int,
        toCols: Int
    ): ReflowResult {
        val newCols = toCols.coerceIn(1, MAX_COLS)
        val n = inputRows.size
        if (n == 0) return ReflowResult(emptyList(), IntArray(0), 0, 0)

        // ── 逻辑行分组：wrapped[i] == true → 行 i 延续到行 i+1 ──
        val lineStarts = ArrayList<Int>()           // 每条逻辑行的首行
        var i = 0
        while (i < n) {
            lineStarts.add(i)
            var j = i
            while (j < n - 1 && inputWrapped[j]) j++
            i = j + 1
        }

        // ── Pass 1：光标所在逻辑行 + 行内基元格序数 ──
        // 序数 = 光标位置之前（流序）的基元格数；光标落在宽字符 trail 上按
        //「lead 之后」计（视觉正确位）。
        var cursorLine = -1
        var cursorOrdinal = 0L
        if (cursorRow in 0 until n) {
            for (li in lineStarts.indices) {
                val start = lineStarts[li]
                var end = start
                while (end < n - 1 && inputWrapped[end]) end++
                if (cursorRow in start..end) {
                    cursorLine = li
                    for (r in start until cursorRow) {
                        val rr = inputRows[r]
                        for (c in rr.indices) if (!rr[c].isWideTrail) cursorOrdinal++
                    }
                    val row = inputRows[cursorRow]
                    val cCol = cursorCol.coerceIn(0, row.size)
                    for (c in 0 until cCol) if (!row[c].isWideTrail) cursorOrdinal++
                    break
                }
            }
        }

        // ── Pass 2：逐逻辑行流式布局 ──
        val outRows = ArrayList<Array<TerminalCell>>()
        val lineOfRow = ArrayList<Int>()
        var outCursorRow = 0
        var outCursorCol = 0

        for (li in lineStarts.indices) {
            val start = lineStarts[li]
            var end = start
            while (end < n - 1 && inputWrapped[end]) end++
            val lineFirstRow = outRows.size

            // 基元格流（trail 随 lead 再生，不复制）+ 尾部空白裁剪（全空白保 1 格）
            val stream = ArrayList<TerminalCell>(rowCellCount(inputRows, start, end))
            for (r in start..end) for (c in inputRows[r].indices) {
                if (!inputRows[r][c].isWideTrail) stream.add(inputRows[r][c])
            }
            trimTrailingBlanks(stream)

            var w = 0
            var padStyle = TerminalStyle.DEFAULT
            var ordinal = 0L
            var cursorFound = false
            var endRow = -1
            var endCol = 0
            var lineRows = 0
            var row = Array(newCols) { TerminalCell.BLANK }
            var si = 0
            while (true) {
                while (si < stream.size) {
                    var cell = stream[si]
                    var cw = if (cell.width == 2 || cell.isWideLead) 2 else 1
                    if (cw == 2 && newCols < 2) {
                        // 单列行放不下宽字符 —— 降级窄格（保码点，保持全序有界）
                        cell = cell.copy(width = 1, flags = cell.flags and TerminalCell.FLAG_WIDE_LEAD.inv())
                        cw = 1
                    }
                    if (w + cw > newCols) {
                        if (cw == 2 && w + 1 == newCols) {
                            // 宽字符会跨行界：孤儿列补同样式空白，整对下移
                            row[w] = TerminalCell(codePoint = ' '.code, width = 1, style = cell.style)
                            w = newCols
                        }
                        break  // 行满 → 下方 flush
                    }
                    // 光标重放：本格正是光标序数目标
                    if (cursorLine == li && !cursorFound && ordinal == cursorOrdinal) {
                        cursorFound = true
                        outCursorRow = outRows.size
                        outCursorCol = w
                    }
                    row[w] = cell
                    if (cw == 2) {
                        row[w + 1] = TerminalCell.CONTINUATION.copy(style = cell.style)
                    }
                    w += cw
                    endRow = outRows.size
                    endCol = w
                    padStyle = cell.style  // 右侧填充继承末格样式（xterm bg 继承）
                    ordinal++
                    si++
                }
                // flush（空行仅在逻辑行尚无输出时 —— 每行 ≥ 1）
                if (w > 0 || lineRows == 0) {
                    for (c in w until newCols) {
                        row[c] = TerminalCell(codePoint = ' '.code, width = 1, style = padStyle)
                    }
                    outRows.add(row)
                    lineOfRow.add(li)
                    lineRows++
                    row = Array(newCols) { TerminalCell.BLANK }
                    w = 0
                }
                if (si >= stream.size) break      // 逻辑行完成（硬行尾）
                if (lineRows >= MAX_ROWS_PER_LINE) break  // 有界：保头弃尾
            }

            // 光标回退：序数在行尾之后 / 裁剪掉 —— 落到本逻辑行末格之后
            if (cursorLine == li && !cursorFound) {
                outCursorRow = if (endRow < 0) lineFirstRow else endRow
                outCursorCol = if (endRow < 0) 0 else endCol
            }
        }

        return ReflowResult(outRows, lineOfRow.toIntArray(), outCursorRow, outCursorCol)
    }

    /** 行区间基元格总数（容量预估用）。 */
    private fun rowCellCount(rows: List<Array<TerminalCell>>, from: Int, to: Int): Int {
        var t = 0
        for (r in from..to) t += rows[r].size
        return t
    }

    /**
     * 尾部空白裁剪（rowText 语义：cp ' ' 或 0，样式无关）。
     * 全空白流保留最后一格 —— 全行背景色等样式得以渲染。
     */
    private fun trimTrailingBlanks(stream: ArrayList<TerminalCell>) {
        if (stream.isEmpty()) return
        val kept = stream.last()
        while (stream.isNotEmpty() && isBlankCp(stream.last().codePoint)) {
            stream.removeAt(stream.size - 1)
        }
        if (stream.isEmpty()) stream.add(kept)
    }

    private fun isBlankCp(cp: Int): Boolean = cp == ' '.code || cp == 0

    /** T92：整行是否「默认样式空白」（尾部空行裁剪判定 —— 带样式空白行保留）。 */
    private fun isDefaultBlankRow(row: Array<TerminalCell>): Boolean {
        for (c in row) {
            if (c.codePoint != ' '.code && c.codePoint != 0) return false
            if (c.style != TerminalStyle.DEFAULT) return false
            if (c.combining.isNotEmpty()) return false
        }
        return true
    }

    // ═══ 引擎接线：TerminalCore.resize（宽度变化）调用 ═══

    /**
     * 主屏重排重建（native vt_engine.reflowResize 的 Kotlin 等价）：
     *
     * 1. 主屏视觉行（scrollback 旧→新 + 可见屏上→下，末行不延续）拼逻辑行；
     * 2. [rewrap] 到新宽度；
     * 3. 可见窗口 = 重排输出的**最新 newRows 行**（底部锚定 —— Termux resize
     *    同款零丢弃），窗口之前的输出行回灌 scrollback（保持旧→新顺序）；
     *    光标行映射到屏内（越出窗口顶 → 钳屏顶）；
     * 4. 光标映射到屏内坐标（备用屏激活时改写的是 savedCursor —— 主屏光标的
     *    最近已知近似，native 同语义）。
     *
     * 备用屏不在此处理（TerminalCore 对其走裁剪/补空 resize）。
     */
    fun applyResize(
        main: ScreenBuffer,
        oldRows: Int,
        cursor: CursorState,
        altActive: Boolean,
        savedCursor: CursorState,
        newRows: Int,
        newCols: Int
    ) {
        val sb = main.scrollbackLineCount
        val input = ArrayList<Array<TerminalCell>>(sb + oldRows)
        val wrapped = BooleanArray(sb + oldRows)
        for (i in 0 until sb) {
            input.add(main.scrollbackLine(i))
            wrapped[i] = main.scrollbackRowWrapped(i)
        }
        for (r in 0 until oldRows) {
            input.add(main.row(r))
            wrapped[sb + r] = if (r < oldRows - 1) main.rowWrapped(r) else false
        }

        // 主屏光标：备用屏激活时活跃光标属于备用屏 —— savedCursor 近似主屏位。
        val mc = if (altActive) savedCursor else cursor
        val cursorGlobal = if (mc.row in 0 until oldRows) sb + mc.row else -1
        // wrapPending ⇒ 光标逻辑上位于最后一格之后一格 —— 编码为 +1 列
        val cursorCol = (mc.column + if (mc.wrapPending) 1 else 0).coerceAtMost(main.cols)

        val out = rewrap(input, wrapped, cursorGlobal, cursorCol, newCols)

        // 重建：**底部锚定**（Termux resize 同款 —— 屏恒为重排输出的最新
        // newRows 行，之前的行回灌 scrollback；**零丢弃**）。旧实现「光标行
        // 留屏」锚定把光标窗口以下的所有输出行静默丢弃（列宽收缩 + 光标在
        // 屏中部 → 光标下方整段内容消失，不可从 scrollback 找回）。光标行
        // 落在窗口上方时钳到屏顶（Termux 同款 newCursorRow<0 → 0）。
        //
        // 尾部空行先裁剪再锚定：末尾若干行全部是「默认样式空白格」（来自空
        // 屏尾行的 rewrap 产物）不是内容 —— 计入锚定会把真内容推出屏
        //（Termux resize 的 skippedBlankLines 语义：空白行仅在有后续非空行时
        // 才回插）。带样式的空白行（bg 块尾行）保留。
        main.resetTo(newRows, newCols)
        val total = out.rows.size
        var contentTotal = total
        while (contentTotal > 0 && isDefaultBlankRow(out.rows[contentTotal - 1])) contentTotal--
        val visibleCount = minOf(total, newRows)
        val first = (contentTotal - visibleCount).coerceAtLeast(0)
        for (idx in 0 until first) {
            val lineEnd = idx + 1 < total && out.lineOfRow[idx] == out.lineOfRow[idx + 1]
            main.pushScrollbackRow(out.rows[idx], lineEnd)
        }
        for (idx in first until first + visibleCount) {
            val r = idx - first
            val lineEnd = idx + 1 < total && out.lineOfRow[idx] == out.lineOfRow[idx + 1]
            main.loadRow(r, out.rows[idx], lineEnd)
            main.repairRow(r)
        }

        // 光标映射（rewrap 的行是输出全局 → 屏内坐标；窗口保证在屏内，仍钳制）
        val nRow = (out.cursorRow - first).coerceIn(0, newRows - 1)
        val nCol = out.cursorCol.coerceIn(0, newCols - 1)
        if (altActive) {
            savedCursor.row = nRow
            savedCursor.column = nCol
        } else {
            cursor.row = nRow
            cursor.column = nCol
            cursor.wrapPending = false
        }
    }
}
