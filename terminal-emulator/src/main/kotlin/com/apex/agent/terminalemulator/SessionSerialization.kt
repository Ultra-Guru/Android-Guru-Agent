package com.apex.agent.terminalemulator

/**
 * ═══ 会话序列化（saveSession / restoreSession）—— Kotlin 与 native 对齐（v0.3）═══
 *
 * 进程死亡恢复：TerminalCore.saveSession() 把**主屏 + scrollback（有界）+
 * 光标/样式/模式/制表位/边距/动态色/字符集**压成紧凑二进制；恢复端
 * `TerminalCore.restoreSession(bytes)` 重建等价引擎。native 引擎（apex-native-vt
 * v0.2）已有同一能力 —— 本文件补齐纯 Kotlin 回退引擎（libvt_native.so 加载
 * 失败的设备同样能恢复会话）。
 *
 * 编码（自定义 varint 流，无第三方依赖）：
 * ```
 * "AGVT" magic + 版本字节(3)
 * rows/cols + 光标(活跃/保存) + 样式(去重表) + 模式位 + 滚区 + 边距 + 字符集
 * + 动态色(3 槽) + cwd + 标题 + 光标形状 + 制表位(位图)
 * + 可见行(k 单元格/行) + scrollback 行(≤ [MAX_SCROLLBACK_LINES])
 * ```
 * 单元格：trail 不序列化（恢复时按 lead 再生 —— 与 reflow 同手法）；
 * 行尾默认空白裁剪；样式去重表让同屏满色 TUI 保持紧凑。
 *
 * **防崩纪律**（corrupt/truncated 永不抛）：解码全程在 runCatching 内，
 * 外加结构性护栏（rows/cols 上下界、样式表/行数/单元格数上限、码点合法性、
 * 消费量 == 总长）。任何违规 → null。
 *
 * 备用屏是瞬态 TUI 状态（程序会整屏重绘），内容不持久化 —— 模式位照存，
 * 恢复时备用屏为空白（与进程死亡后真实状态一致）。
 */
internal class SessionState(
    val rows: Int,
    val cols: Int,
    val cursorRow: Int, val cursorCol: Int, val cursorWrapPending: Boolean,
    val savedCursorRow: Int, val savedCursorCol: Int, val savedCursorWrap: Boolean,
    val currentStyle: TerminalStyle, val savedStyle: TerminalStyle,
    val modes: TerminalModes,
    val mouseTracking: Int, val mouseEncoding: Int, val mouseUnit: Int,
    val mouseExtendedSgr: Boolean, val mouseAltScroll: Boolean, val focusReporting: Boolean,
    val scrollTop: Int, val scrollBottom: Int,
    val margins: IntArray,
    val charsets: IntArray,
    val dynamicForeground: TerminalColor?, val dynamicBackground: TerminalColor?,
    val dynamicCursor: TerminalColor?,
    val guestCwd: String?, val title: String?,
    val cursorStyleOrdinal: Int,
    val tabStops: BooleanArray,
    val visibleRows: List<Pair<Array<TerminalCell>, Boolean>>,
    val scrollbackRows: List<Pair<Array<TerminalCell>, Boolean>>
)

internal object SessionSerialization {

    /** 格式版本（不兼容的布局变更必须递增）。 */
    const val VERSION = 3

    /** 序列化携带的 scrollback 行上限（任务约束；防超大 blob）。 */
    const val MAX_SCROLLBACK_LINES = 500

    private val MAGIC = byteArrayOf('A'.code.toByte(), 'G'.code.toByte(), 'V'.code.toByte(), 'T'.code.toByte())

    // ── 编码 ─────────────────────────────────────────────────────────────

    fun encode(state: SessionState): ByteArray {
        val w = Writer()

        // 样式去重表：先预扫当前/保存样式 + 所有单元格样式建表（先表后数据 → 单遍解码）。
        val styleIndex = HashMap<TerminalStyle, Int>()
        fun styleId(s: TerminalStyle): Int = styleIndex.getOrPut(s) { styleIndex.size }
        for ((row, _) in state.visibleRows) for (c in row) styleId(c.style)
        for ((row, _) in state.scrollbackRows) for (c in row) styleId(c.style)
        styleId(state.currentStyle)
        styleId(state.savedStyle)

        w.bytes(MAGIC)
        w.u8(VERSION)
        w.vint(state.rows); w.vint(state.cols)
        w.vint(state.cursorRow); w.vint(state.cursorCol); w.u8(if (state.cursorWrapPending) 1 else 0)
        w.vint(state.savedCursorRow); w.vint(state.savedCursorCol); w.u8(if (state.savedCursorWrap) 1 else 0)

        // T88 修复：样式表必须按 **id 序** 写入。旧实现 `ArrayList(styleIndex.keys)`
        // 按 HashMap 迭代序写表，而 id 是插入序 —— 两者错位时解码端把 id 映射到
        // 别的样式（round-trip 颜色/下划线描色静默丢失的根因；条目少时碰巧一致
        // 才侥幸通过）。按 id 反转建表，位置 == id，与解码端约定对齐。
        val styleTable = arrayOfNulls<TerminalStyle>(styleIndex.size)
        for ((s, id) in styleIndex) styleTable[id] = s
        w.vint(styleTable.size)
        for (s in styleTable) writeStyle(w, s!!)
        w.vint(styleId(state.currentStyle))
        w.vint(styleId(state.savedStyle))

        // 模式位（TerminalModes 全字段 + 鼠标/焦点）
        val m = state.modes
        w.vint(
            boolBits(
                m.autoWrap, m.cursorVisible, m.applicationCursor, m.originMode, m.insertMode,
                m.bracketedPaste, m.reverseVideo, m.alternateScreen, m.applicationKeypad, m.newlineMode
            )
        )
        w.vint(state.mouseTracking); w.vint(state.mouseEncoding); w.vint(state.mouseUnit)
        w.vint(boolBits(state.mouseExtendedSgr, state.mouseAltScroll, state.focusReporting))

        w.vint(state.scrollTop); w.vint(state.scrollBottom)
        w.vint(state.margins.size)
        for (v in state.margins) w.vint(v)
        w.vint(state.charsets.size)
        for (v in state.charsets) w.vint(v)

        writeColor(w, state.dynamicForeground)
        writeColor(w, state.dynamicBackground)
        writeColor(w, state.dynamicCursor)

        w.string(state.guestCwd)
        w.string(state.title)
        w.vint(state.cursorStyleOrdinal)

        writeTabStops(w, state.tabStops, state.cols)

        w.vint(state.visibleRows.size)
        for ((row, wrapped) in state.visibleRows) writeRow(w, row, wrapped, state.cols, styleIndex)
        w.vint(state.scrollbackRows.size)
        for ((row, wrapped) in state.scrollbackRows) writeRow(w, row, wrapped, state.cols, styleIndex)

        return w.toByteArray()
    }

    // ── 解码（任何违规 → null）───────────────────────────────────────────

    fun decode(data: ByteArray): SessionState? {
        return runCatching {
            val r = Reader(data)
            if (data.size < 8) return null
            if (!r.takeMagic()) return null
            if (r.u8() != VERSION) return null
            val rows = r.vint()
            val cols = r.vint()
            if (rows !in 1..2048 || cols !in 1..1024) return null

            val cursorRow = r.vint(); val cursorCol = r.vint()
            val cursorWrap = r.u8() == 1
            val savedRow = r.vint(); val savedCol = r.vint()
            val savedWrap = r.u8() == 1
            if (cursorRow !in 0 until rows || cursorCol !in 0 until cols) return null
            if (savedRow !in 0 until rows || savedCol !in 0 until cols) return null

            val styleCount = r.vint()
            if (styleCount !in 1..65536) return null
            val styles = ArrayList<TerminalStyle>(styleCount)
            repeat(styleCount) { styles.add(readStyle(r)) }
            val currentStyle = styles[r.vint().coerceIn(0, styles.size - 1)]
            val savedStyle = styles[r.vint().coerceIn(0, styles.size - 1)]

            val modeBits = r.vint()
            val modes = TerminalModes(
                autoWrap = modeBits.bit(0), cursorVisible = modeBits.bit(1),
                applicationCursor = modeBits.bit(2), originMode = modeBits.bit(3),
                insertMode = modeBits.bit(4), bracketedPaste = modeBits.bit(5),
                reverseVideo = modeBits.bit(6), alternateScreen = modeBits.bit(7),
                applicationKeypad = modeBits.bit(8), newlineMode = modeBits.bit(9)
            )
            val mouseTracking = r.vint(); val mouseEncoding = r.vint(); val mouseUnit = r.vint()
            val mouseBits = r.vint()

            val scrollTop = r.vint(); val scrollBottom = r.vint()
            if (scrollTop !in 0 until rows || scrollBottom !in scrollTop until rows) return null
            val margins = IntArray(r.vint().coerceIn(0, 8)) { r.vint() }
            val charsets = IntArray(r.vint().coerceIn(0, 16)) { r.vint() }

            val dynFg = readColor(r); val dynBg = readColor(r); val dynCursor = readColor(r)
            val guestCwd = r.string(MAX_PATH)
            val title = r.string(4096)
            val cursorStyleOrdinal = r.vint().coerceIn(0, CursorStyle.entries.size - 1)

            val tabStops = readTabStops(r, cols) ?: return null

            val nVisible = r.vint()
            if (nVisible != rows) return null
            val visible = ArrayList<Pair<Array<TerminalCell>, Boolean>>(nVisible)
            repeat(nVisible) { visible.add(readRow(r, cols, styles) ?: return null) }
            val nSb = r.vint()
            if (nSb !in 0..MAX_SCROLLBACK_LINES) return null
            val sbRows = ArrayList<Pair<Array<TerminalCell>, Boolean>>(nSb)
            repeat(nSb) { sbRows.add(readRow(r, cols, styles) ?: return null) }

            if (!r.fullyConsumed()) return null   // 尾部多字节 = 篡改/拼接 → 拒绝

            SessionState(
                rows, cols, cursorRow, cursorCol, cursorWrap, savedRow, savedCol, savedWrap,
                currentStyle, savedStyle, modes,
                mouseTracking, mouseEncoding, mouseUnit,
                mouseBits.bit(0), mouseBits.bit(1), mouseBits.bit(2),
                scrollTop, scrollBottom, margins, charsets,
                dynFg, dynBg, dynCursor, guestCwd, title, cursorStyleOrdinal,
                tabStops, visible, sbRows
            )
        }.getOrNull()
    }

    // ── 样式 ──

    private fun writeStyle(w: Writer, s: TerminalStyle) {
        writeColor(w, s.foreground)
        writeColor(w, s.background)
        writeColor(w, s.underlineColor)
        w.vint(
            boolBits(s.bold, s.dim, s.italic, s.blink, s.inverse, s.hidden, s.strikethrough)
        )
        w.vint(s.underline.ordinal)
        w.vint(s.linkIndex)
    }

    private fun readStyle(r: Reader): TerminalStyle {
        // 颜色槽 null（未设置）在样式语义里不存在 —— 归 Default。
        val fg = readColor(r) ?: TerminalColor.Default
        val bg = readColor(r) ?: TerminalColor.Default
        val ul = readColor(r) ?: TerminalColor.Default
        val bits = r.vint()
        return TerminalStyle(
            foreground = fg, background = bg, underlineColor = ul,
            bold = bits.bit(0), dim = bits.bit(1), italic = bits.bit(2), blink = bits.bit(3),
            inverse = bits.bit(4), hidden = bits.bit(5), strikethrough = bits.bit(6),
            underline = UnderlineStyle.entries[r.vint().coerceIn(0, UnderlineStyle.entries.size - 1)],
            linkIndex = r.vint()
        )
    }

    // ── 颜色（null = 未设置槽位）──

    private fun writeColor(w: Writer, c: TerminalColor?) {
        when (c) {
            null -> w.u8(0)
            is TerminalColor.Default -> w.u8(1)
            is TerminalColor.Indexed -> { w.u8(2); w.vint(c.index) }
            is TerminalColor.RGB -> { w.u8(3); w.vint(c.r); w.vint(c.g); w.vint(c.b) }
        }
    }

    private fun readColor(r: Reader): TerminalColor? = when (val tag = r.u8()) {
        0 -> null
        1 -> TerminalColor.Default
        2 -> TerminalColor.Indexed(r.vint().coerceIn(0, 255))
        3 -> TerminalColor.RGB(r.vint().coerceIn(0, 255), r.vint().coerceIn(0, 255), r.vint().coerceIn(0, 255))
        else -> null
    }

    // ── 行（trail 不存、行尾默认空白裁剪；恢复端再生）──

    private fun writeRow(w: Writer, row: Array<TerminalCell>, wrapped: Boolean, cols: Int, styles: HashMap<TerminalStyle, Int>) {
        // 序列化基元格序列（跳过 trail），尾部裁掉「默认空白」格。
        val out = ArrayList<TerminalCell>(row.size)
        for (c in row) if (!c.isWideTrail) out.add(c)
        var k = out.size
        while (k > 0 && out[k - 1].isDefaultBlank) k--
        w.u8(if (wrapped) 1 else 0)
        w.vint(k)
        for (i in 0 until k) {
            val c = out[i]
            w.vint(c.codePoint)
            w.u8(if (c.isWideLead) 1 else 0)
            w.vint(styles[c.style] ?: 0)
            w.vint(c.combining.size)
            for (m in c.combining) w.vint(m)
        }
    }

    private fun readRow(r: Reader, cols: Int, styles: List<TerminalStyle>): Pair<Array<TerminalCell>, Boolean>? {
        val wrapped = r.u8() == 1
        val k = r.vint()
        if (k !in 0..cols) return null
        val cells = Array(cols) { TerminalCell.BLANK }
        var out = 0
        for (i in 0 until k) {
            val cp = r.vint()
            if (cp !in 0..0x10FFFF || (cp in 0xD800..0xDFFF)) return null  // 非法码点 → 拒收
            val wide = r.u8() == 1
            val styleIdx = r.vint()
            if (styleIdx !in styles.indices) return null
            val nComb = r.vint()
            if (nComb !in 0..8) return null
            val comb = IntArray(nComb) {
                val m = r.vint()
                if (m !in 0..0x10FFFF || (m in 0xD800..0xDFFF)) return null
                m
            }
            if (out >= cols) return null  // 声明的单元格数超出列宽（再生 trail 溢出）
            if (wide) {
                if (out + 1 >= cols) return null  // lead 贴末列 → 越界
                cells[out] = TerminalCell(
                    codePoint = cp, width = 2, style = styles[styleIdx],
                    combining = comb, flags = TerminalCell.FLAG_WIDE_LEAD
                )
                cells[out + 1] = TerminalCell.CONTINUATION.copy(style = styles[styleIdx])
                out += 2
            } else {
                cells[out] = TerminalCell(codePoint = cp, width = 1, style = styles[styleIdx], combining = comb)
                out++
            }
        }
        return cells to wrapped
    }

    // ── 制表位（run-length，稀疏友好）──

    private fun writeTabStops(w: Writer, stops: BooleanArray, cols: Int) {
        var i = 0
        while (i < stops.size) {
            val v = stops[i]
            var run = 1
            while (i + run < stops.size && stops[i + run] == v) run++
            w.vint(if (v) run else -run)
            i += run
        }
    }

    private fun readTabStops(r: Reader, cols: Int): BooleanArray? {
        val stops = BooleanArray(cols)
        var i = 0
        while (i < cols) {
            val run = r.vint()
            val len = Math.abs(run)
            if (len <= 0 || i + len > cols) return null
            if (run > 0) stops.fill(true, i, i + len)
            i += len
        }
        return stops
    }

    // ── 小工具 ──

    private fun boolBits(vararg values: Boolean): Int {
        var v = 0
        for (i in values.indices) if (values[i]) v = v or (1 shl i)
        return v
    }

    private fun Int.bit(i: Int): Boolean = (this shr i) and 1 == 1

    private const val MAX_PATH = 4096

    // ── varint 流（带符号 LEB128：7bit 组，支持负值 ≤5 字节）──

    private class Writer {
        private val buf = ArrayList<Byte>(1024)

        fun u8(v: Int) { buf.add((v and 0xFF).toByte()) }

        /** 带符号 LEB128：正负整数统一编码（负数 ≤5 字节）。 */
        fun vint(v: Int) {
            var x = v
            while (true) {
                val b = x and 0x7F
                x = x shr 7   // 算术右移 —— 符号扩展，负数自然收敛到 -1
                if (x == 0 && (b and 0x40) == 0) { u8(b); return }
                if (x == -1 && (b and 0x40) != 0) { u8(b); return }
                u8(b or 0x80)
            }
        }

        fun bytes(b: ByteArray) { for (x in b) buf.add(x) }

        fun string(s: String?) {
            if (s == null) { vint(-1); return }
            val b = s.toByteArray(Charsets.UTF_8)
            vint(b.size)
            for (x in b) buf.add(x)
        }

        fun toByteArray(): ByteArray = buf.toByteArray()
    }

    private class Reader(private val data: ByteArray) {
        private var pos = 0

        fun u8(): Int {
            if (pos >= data.size) throw IllegalStateException("EOF")
            return data[pos++].toInt() and 0xFF
        }

        fun vint(): Int {
            var result = 0
            var shift = 0
            var b = 0
            while (true) {
                b = u8()
                result = result or ((b and 0x7F) shl shift)
                shift += 7
                if (b and 0x80 == 0) break
                if (shift > 35) throw IllegalStateException("varint too long")
            }
            if (shift < 32 && (b and 0x40) != 0) {
                result = result or (0xFFFFFFFF.toInt() shl shift)
            }
            return result
        }

        fun takeMagic(): Boolean {
            if (pos + MAGIC.size > data.size) return false
            for (i in MAGIC.indices) if (data[pos + i] != MAGIC[i]) return false
            pos += MAGIC.size
            return true
        }

        fun string(maxBytes: Int): String? {
            val n = vint()
            if (n == -1) return null
            if (n < 0 || n > maxBytes || pos + n > data.size) throw IllegalStateException("bad string")
            val s = String(data, pos, n, Charsets.UTF_8)
            pos += n
            return s
        }

        fun fullyConsumed(): Boolean = pos == data.size
    }
}

/** 单元格是否「默认空白」（行尾裁剪用 —— 与 BLANK 语义一致）。 */
private val TerminalCell.isDefaultBlank: Boolean
    get() = (codePoint == ' '.code || codePoint == 0) &&
        style == TerminalStyle.DEFAULT && combining.isEmpty() && !isWideLead && !isWideTrail
