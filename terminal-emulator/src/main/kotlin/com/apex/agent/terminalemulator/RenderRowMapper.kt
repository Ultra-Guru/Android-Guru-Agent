package com.apex.agent.terminalemulator

/**
 * ═══ TerminalCell → RenderCell 行渲染器 —— v0.3 从 TerminalCore 拆出 ═══
 *
 * 拆分动机（Task 2-b 文件预算）：renderRow / cellToRender / colorArgb /
 * linkTable 汇总是「模型映射」而非引擎语义 —— 抽成纯函数对象后可独立单测
 * （含 SGR 58 下划线描色的 RenderCell 映射），TerminalCore.renderSnapshot
 * 只做行收集。
 *
 * 纯 JVM、无 Android 依赖。
 */
internal object RenderRowMapper {

    /**
     * Render one row of cells, trimming trailing default-blank cells
     * (they are pure background). Wide trails fold into their lead.
     */
    fun renderRow(cells: Array<TerminalCell>, reverseVideo: Boolean): List<RenderCell> {
        var last = cells.size - 1
        while (last >= 0) {
            val c = cells[last]
            if (c.isWideTrail) break  // a wide lead precedes — non-blank content
            if (c.codePoint == ' '.code && c.style == TerminalStyle.DEFAULT && c.combining.isEmpty()) {
                last--
                continue
            }
            break
        }
        if (last < 0) return emptyList()
        val out = ArrayList<RenderCell>(last + 1)
        var i = 0
        while (i <= last) {
            val c = cells[i]
            if (c.isWideTrail) { i++; continue }  // rendered as part of its wide lead
            out.add(cellToRender(c, reverseVideo))
            i++
        }
        return out
    }

    /** Single cell → [RenderCell]（v0.3：+SGR 58 下划线描色）。 */
    fun cellToRender(c: TerminalCell, reverseVideo: Boolean): RenderCell {
        val sb = StringBuilder()
        sb.appendCodePoint(if (c.codePoint == 0) ' '.code else c.codePoint)
        for (m in c.combining) sb.appendCodePoint(m)
        var flags = 0
        if (c.style.bold) flags = flags or RenderCell.FLAG_BOLD
        if (c.style.dim) flags = flags or RenderCell.FLAG_DIM
        if (c.style.italic) flags = flags or RenderCell.FLAG_ITALIC
        if (c.style.underline != UnderlineStyle.NONE) flags = flags or RenderCell.FLAG_UNDERLINE
        if (c.style.blink) flags = flags or RenderCell.FLAG_BLINK
        if (c.style.hidden) flags = flags or RenderCell.FLAG_HIDDEN
        if (c.style.strikethrough) flags = flags or RenderCell.FLAG_STRIKE
        // Inverse video: cell-level SGR 7 XOR global DECSCNM (5) — resolved at render time
        // by the UI (keeps default-vs-explicit color semantics in one place).
        if (c.style.inverse || reverseVideo) flags = flags or RenderCell.FLAG_INVERSE
        if (c.width == 2) flags = flags or RenderCell.FLAG_WIDE
        if (c.style.linkIndex != 0) flags = flags or RenderCell.FLAG_LINK
        return RenderCell(
            text = sb.toString(),
            fg = colorArgb(c.style.foreground),
            bg = colorArgb(c.style.background),
            flags = flags,
            link = c.style.linkIndex,
            underlineColorLong = colorArgb(c.style.underlineColor)
        )
    }

    /** Map a [TerminalColor] to an opaque 0xAARRGGBB long; 0 = theme default. */
    fun colorArgb(c: TerminalColor): Long = when (c) {
        is TerminalColor.Default -> 0L
        else -> 0xFF000000L or TerminalColor.toRgb(c).toLong().and(0xFFFFFFL)
    }

    /** 收集屏内/scrollback 渲染行里出现的链接 id → URI 映射（悬空 id 跳过）。 */
    fun buildLinkTable(
        visible: List<List<RenderCell>>,
        scrollback: List<List<RenderCell>>,
        registry: HyperlinkRegistry
    ): Map<Int, String> {
        val ids = HashSet<Int>()
        for (row in visible) for (cell in row) if (cell.link != 0) ids.add(cell.link)
        for (row in scrollback) for (cell in row) if (cell.link != 0) ids.add(cell.link)
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<Int, String>(ids.size)
        for (id in ids) registry.uriOf(id)?.let { out[id] = it }
        return out
    }
}
