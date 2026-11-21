package com.apex.agent.core.codetools.html

/**
 * ═══ 布局语义模型：HtmlNode → LayoutNode ═══
 *
 * 把 DOM 树升级为「布局视角」的树：每个节点带 display 推断 / 盒模型
 * 估算 / 语义角色，供 [HtmlLayoutAnalyzer] 做结构与问题分析。
 *
 * display 推断优先级（生成物页面的真实世界分布）：
 * 1. 内联 style 的 display 声明（最权威）；
 * 2. class 中的布局框架类（Tailwind / Bootstrap / 常见自定义）；
 * 3. 标签语义默认值（div/p/section → block，span/a → inline …）。
 *
 * 盒模型估算来源：内联 style 优先，其次 Tailwind 工具类，再次
 * Bootstrap 工具类。所有值为「px 估算」——静态分析给不出真实渲染值，
 * 但对「这个页面大概长什么样、哪里会溢出」已经足够指导 Agent。
 */
object HtmlLayoutModel {

    // ═══ 样式视图（PR1 级联接入）═══

    /**
     * 节点样式视图：级联视图（resolvedStyle，CSS 级联解析注入）优先，
     * 缺省回退内联 style —— 未注入时与历史行为完全一致。
     */
    internal fun styleOf(node: HtmlNode): Map<String, String> = node.resolvedStyle ?: node.style

    // ═══ display ═══

    enum class DisplayKind { BLOCK, INLINE, INLINE_BLOCK, FLEX, GRID, TABLE, NONE }

    /** 标签语义默认块级元素。 */
    private val BLOCK_TAGS = setOf(
        "address", "article", "aside", "blockquote", "details", "dialog", "dd",
        "div", "dl", "dt", "fieldset", "figcaption", "figure", "footer", "form",
        "h1", "h2", "h3", "h4", "h5", "h6", "header", "hgroup", "hr", "li",
        "main", "nav", "ol", "p", "pre", "section", "table", "ul", "tbody",
        "thead", "tfoot", "tr", "td", "th", "caption", "colgroup"
    )

    private val INLINE_TAGS = setOf(
        "a", "abbr", "b", "bdi", "bdo", "cite", "code", "data", "dfn", "em",
        "i", "kbd", "mark", "q", "s", "samp", "small", "span", "strong", "sub",
        "sup", "time", "u", "var", "wbr", "ins", "del"
    )

    private val INLINE_BLOCK_TAGS = setOf("img", "input", "button", "select", "textarea", "video", "audio", "canvas", "object", "embed", "iframe")

    /** class 中的 flex 指示类（Tailwind / Bootstrap / 常见自定义）。 */
    private val FLEX_CLASSES = setOf(
        "flex", "inline-flex", "d-flex", "d-inline-flex", "flexbox",
        "flex-row", "flex-col", "flex-column", "row", "navbar", "toolbar",
        "card-deck", "btn-group", "nav-tabs", "pagination", "breadcrumb"
    )

    private val GRID_CLASSES = setOf(
        "grid", "inline-grid", "d-grid", "grid-cols-1", "grid-cols-2",
        "grid-cols-3", "grid-cols-4", "grid-cols-6", "grid-cols-12",
        "layout-grid", "auto-grid", "masonry"
    )

    private val HIDDEN_CLASSES = setOf("hidden", "invisible", "sr-only", "visually-hidden", "d-none", "hide", "gone")

    /** 判定节点 display。isHidden 已含 display:none / hidden 类。 */
    fun displayOf(node: HtmlNode): DisplayKind {
        if (node.isHidden) return DisplayKind.NONE
        val inline = styleOf(node)["display"]?.lowercase()?.trim()
        if (inline != null) {
            return when (inline.substringBefore(' ').trim()) {
                "none" -> DisplayKind.NONE
                "flex", "inline-flex" -> DisplayKind.FLEX
                "grid", "inline-grid" -> DisplayKind.GRID
                "inline" -> DisplayKind.INLINE
                "inline-block" -> DisplayKind.INLINE_BLOCK
                "table", "table-row", "table-cell", "table-caption", "inline-table" -> DisplayKind.TABLE
                "block" -> DisplayKind.BLOCK
                else -> DisplayKind.BLOCK // 未知值（如 flow-root）按 block 兜底
            }
        }
        val cls = node.classes
        if (cls.any { it in FLEX_CLASSES }) return DisplayKind.FLEX
        if (cls.any { it in GRID_CLASSES }) return DisplayKind.GRID
        if (cls.any { it in HIDDEN_CLASSES }) return DisplayKind.NONE
        return when (node.tag) {
            in BLOCK_TAGS -> DisplayKind.BLOCK
            in INLINE_TAGS -> DisplayKind.INLINE
            in INLINE_BLOCK_TAGS -> DisplayKind.INLINE_BLOCK
            "br" -> DisplayKind.INLINE
            "tr" -> DisplayKind.TABLE
            "td", "th" -> DisplayKind.TABLE
            else -> DisplayKind.BLOCK // 未知/custom 元素按块级（HTML 规范默认 display:block）
        }
    }

    // ═══ 语义角色（结构摘要用） ═══

    enum class LayoutRole {
        LANDMARK_HEADER, LANDMARK_NAV, LANDMARK_MAIN, LANDMARK_ASIDE, LANDMARK_FOOTER,
        HEADING_1, HEADING_2, HEADING_3, HEADING_LOWER, PARAGRAPH, TEXT, LIST, LIST_ITEM,
        IMAGE, LINK, BUTTON, CONTROL, TABLE_CELL, FIGURE, VIDEO, EMBED, CONTAINER,
        SECTION, CANVAS, OTHER
    }

    fun roleOf(node: HtmlNode): LayoutRole = when (node.tag) {
        "header", "hgroup" -> LayoutRole.LANDMARK_HEADER
        "nav" -> LayoutRole.LANDMARK_NAV
        "main" -> LayoutRole.LANDMARK_MAIN
        "aside" -> LayoutRole.LANDMARK_ASIDE
        "footer" -> LayoutRole.LANDMARK_FOOTER
        "h1" -> LayoutRole.HEADING_1
        "h2" -> LayoutRole.HEADING_2
        "h3" -> LayoutRole.HEADING_3
        "h4", "h5", "h6" -> LayoutRole.HEADING_LOWER
        "p" -> LayoutRole.PARAGRAPH
        "#text" -> LayoutRole.TEXT
        "ul", "ol", "dl" -> LayoutRole.LIST
        "li" -> LayoutRole.LIST_ITEM
        "img", "picture" -> LayoutRole.IMAGE
        "a" -> LayoutRole.LINK
        "button" -> LayoutRole.BUTTON
        "input", "select", "textarea", "label", "form" -> LayoutRole.CONTROL
        "td", "th", "tr", "tbody", "thead", "tfoot", "table", "caption" -> LayoutRole.TABLE_CELL
        "figure", "figcaption" -> LayoutRole.FIGURE
        "video", "audio" -> LayoutRole.VIDEO
        "iframe", "embed", "object" -> LayoutRole.EMBED
        "canvas" -> LayoutRole.CANVAS
        "section", "article" -> LayoutRole.SECTION
        "div", "span" -> if (node.classes.any { it == "container" || it.startsWith("container-") || it == "wrapper" || it == "content" || it == "row" || it.startsWith("col") }) {
            LayoutRole.CONTAINER
        } else LayoutRole.OTHER
        else -> LayoutRole.OTHER
    }

    // ═══ 尺寸 / 盒模型估算 ═══

    /** 尺寸规格：数值 + 单位（px/%/em/rem/vw/vh/fr/auto）。 */
    data class SizeSpec(
        val raw: String?,
        val px: Int?,
        val percent: Double?,
        val isAuto: Boolean,
        val unit: String // px | % | em | rem | vw | vh | fr | auto | none
    ) {
        val isFixedPx: Boolean get() = unit == "px" && px != null
        val isPercent: Boolean get() = unit == "%"
        companion object {
            val AUTO = SizeSpec(null, null, null, true, "auto")
            val NONE = SizeSpec(null, null, null, false, "none")
            fun parse(raw: String?): SizeSpec {
                if (raw.isNullOrBlank()) return NONE
                val v = raw.trim().lowercase()
                if (v == "auto") return AUTO
                val num = v.takeWhile { it.isDigit() || it == '.' || it == '-' }
                val value = num.toDoubleOrNull() ?: return NONE
                val unit = when {
                    v.endsWith("px") -> "px"
                    v.endsWith("%") -> "%"
                    v.endsWith("rem") -> "rem"
                    v.endsWith("em") -> "em"
                    v.endsWith("vw") -> "vw"
                    v.endsWith("vh") -> "vh"
                    v.endsWith("fr") -> "fr"
                    v.endsWith("ch") -> "ch"
                    v.endsWith("pt") -> "pt"
                    num.length == v.length -> "px" // 裸数字按 px
                    else -> "none"
                }
                if (unit == "none") return NONE
                return when (unit) {
                    "%" -> SizeSpec(v, null, value, false, "%")
                    "px" -> SizeSpec(v, value.toInt(), null, false, "px")
                    "rem" -> SizeSpec(v, (value * 16).toInt(), null, false, "rem") // 16px 基准估算
                    "em" -> SizeSpec(v, (value * 16).toInt(), null, false, "em")
                    "vw" -> SizeSpec(v, (value * 3.75).toInt(), null, false, "vw") // 375px 视口基准
                    "vh" -> SizeSpec(v, (value * 6.67).toInt(), null, false, "vh") // 667px 视口基准
                    "pt" -> SizeSpec(v, (value * 1.33).toInt(), null, false, "pt")
                    else -> SizeSpec(v, null, null, false, "auto")
                }
            }
        }
    }

    /** 四边（top/right/bottom/left）值；缺省 null。 */
    data class BoxEdges(val top: Int?, val right: Int?, val bottom: Int?, val left: Int?) {
        val hasAny: Boolean get() = top != null || right != null || bottom != null || left != null
        val maxOfAny: Int? get() = listOfNotNull(top, right, bottom, left).maxOrNull()
    }

    /** 常用 Tailwind 间距/尺寸字面量表（v3，×4px 纲）。 */
    private val TAILWIND_SPACING = mapOf(
        "0" to 0, "0.5" to 2, "1" to 4, "1.5" to 6, "2" to 8, "2.5" to 10, "3" to 12,
        "3.5" to 14, "4" to 16, "5" to 20, "6" to 24, "7" to 28, "8" to 32, "9" to 36,
        "10" to 40, "11" to 44, "12" to 48, "14" to 56, "16" to 64, "20" to 80,
        "24" to 96, "28" to 112, "32" to 128, "36" to 144, "40" to 160, "44" to 176,
        "48" to 192, "52" to 208, "56" to 224, "60" to 240, "64" to 256, "72" to 288,
        "80" to 320, "96" to 384, "px" to 1
    )

    /** Tailwind 分数宽度 → 百分比。 */
    private val TAILWIND_FRACTIONS = mapOf(
        "1/2" to 50.0, "1/3" to 33.33, "2/3" to 66.67, "1/4" to 25.0, "2/4" to 50.0,
        "3/4" to 75.0, "1/5" to 20.0, "2/5" to 40.0, "3/5" to 60.0, "4/5" to 80.0,
        "1/6" to 16.67, "5/6" to 83.33, "1/12" to 8.33, "full" to 100.0, "screen" to 100.0
    )

    /** 从 class/style 提取宽度规格（Tailwind > Bootstrap > inline 顺序并不重要，首个命中胜出）。 */
    fun widthOf(node: HtmlNode): SizeSpec {
        styleOf(node)["width"]?.let { return SizeSpec.parse(it) }
        for (c in node.classes) {
            when {
                c.startsWith("w-") -> {
                    val key = c.removePrefix("w-")
                    TAILWIND_FRACTIONS[key]?.let { return SizeSpec(c, null, it, false, "%") }
                    TAILWIND_SPACING[key]?.let { return SizeSpec(c, it, null, false, "px") }
                    if (key.startsWith("[")) return SizeSpec.parse(key.trim('[', ']'))
                }
                c.startsWith("col-") -> {
                    // Bootstrap 栅格 col-md-6 / col-4 → 12 分之 N 的百分比
                    val cols = c.substringAfterLast('-').toIntOrNull()
                    if (cols != null && cols >= 1 && cols <= 12) return SizeSpec(c, null, cols * 100.0 / 12, false, "%")
                }
                c == "container" || c == "container-fluid" -> return SizeSpec(c, 1140, null, false, "px") // 假定桌面
                c == "col" -> return SizeSpec(c, null, 100.0 / 12, false, "%")
            }
        }
        return SizeSpec.NONE
    }

    fun heightOf(node: HtmlNode): SizeSpec {
        styleOf(node)["height"]?.let { return SizeSpec.parse(it) }
        for (c in node.classes) {
            when {
                c.startsWith("h-") -> {
                    val key = c.removePrefix("h-")
                    TAILWIND_FRACTIONS[key]?.let { return SizeSpec(c, null, it, false, "%") }
                    TAILWIND_SPACING[key]?.let { return SizeSpec(c, it, null, false, "px") }
                }
            }
        }
        return SizeSpec.NONE
    }

    fun maxWidthOf(node: HtmlNode): SizeSpec? {
        styleOf(node)["max-width"]?.let { return SizeSpec.parse(it) }
        for (c in node.classes) {
            if (c.startsWith("max-w-")) {
                return when (c.removePrefix("max-w-")) {
                    "none" -> null
                    "xs" -> SizeSpec(c, 320, null, false, "px")
                    "sm" -> SizeSpec(c, 384, null, false, "px")
                    "md" -> SizeSpec(c, 448, null, false, "px")
                    "lg" -> SizeSpec(c, 512, null, false, "px")
                    "xl" -> SizeSpec(c, 576, null, false, "px")
                    "2xl" -> SizeSpec(c, 672, null, false, "px")
                    "3xl" -> SizeSpec(c, 768, null, false, "px")
                    "4xl" -> SizeSpec(c, 896, null, false, "px")
                    "5xl" -> SizeSpec(c, 1024, null, false, "px")
                    "6xl" -> SizeSpec(c, 1152, null, false, "px")
                    "7xl" -> SizeSpec(c, 1280, null, false, "px")
                    "full" -> SizeSpec(c, null, 100.0, false, "%")
                    "prose" -> SizeSpec(c, 65, null, false, "ch")
                    else -> null
                }
            }
            if (c == "container") return SizeSpec(c, 1140, null, false, "px")
        }
        return null
    }

    fun minWidthOf(node: HtmlNode): SizeSpec? {
        styleOf(node)["min-width"]?.let { return SizeSpec.parse(it) }
        for (c in node.classes) {
            if (c.startsWith("min-w-")) {
                TAILWIND_SPACING[c.removePrefix("min-w-")]?.let { return SizeSpec(c, it, null, false, "px") }
                if (c == "min-w-full") return SizeSpec(c, null, 100.0, false, "%")
            }
        }
        return null
    }

    /** Tailwind/Bootstrap/inline 四边距提取（p- padding / m- margin；x/y/单边组合）。 */
    private fun edgesFrom(classes: List<String>, prefixes: List<String>): BoxEdges? {
        var t: Int? = null; var r: Int? = null; var b: Int? = null; var l: Int? = null
        var any = false
        for (c in classes) {
            val hit = prefixes.firstOrNull { c.startsWith("$it-") } ?: continue
            val key = c.removePrefix("$hit-")
            val value = when {
                TAILWIND_SPACING.containsKey(key) -> TAILWIND_SPACING[key]
                key == "auto" -> 0
                key.startsWith("[") -> key.trim('[', ']').removeSuffix("px").toIntOrNull()
                else -> continue
            } ?: continue
            any = true
            when (hit.last()) {
                'x' -> { r = value; l = value } // mx-/px-
                'y' -> { t = value; b = value } // my-/py-
                't' -> t = value
                'r' -> r = value
                'b' -> b = value
                'l' -> l = value
                else -> { t = value; r = value; b = value; l = value } // m-/p- 整体
            }
        }
        return if (any) BoxEdges(t, r, b, l) else null
    }

    fun marginsOf(node: HtmlNode): BoxEdges {
        val fromCls = edgesFrom(node.classes, listOf("m", "mx", "my", "mt", "mr", "mb", "ml"))
            ?: edgesFrom(node.classes, listOf("mt", "me", "mb", "ms")) // Bootstrap 变体
        val s = styleOf(node)
        val fromStyle = BoxEdges(
            top = s["margin-top"]?.let { SizeSpec.parse(it).px },
            right = s["margin-right"]?.let { SizeSpec.parse(it).px },
            bottom = s["margin-bottom"]?.let { SizeSpec.parse(it).px },
            left = s["margin-left"]?.let { SizeSpec.parse(it).px } ?: s["margin"]?.let { SizeSpec.parse(it).px }
        )
        return mergeEdges(fromCls, fromStyle)
    }

    fun paddingsOf(node: HtmlNode): BoxEdges {
        val fromCls = edgesFrom(node.classes, listOf("p", "px", "py", "pt", "pr", "pb", "pl"))
        val s = styleOf(node)
        val fromStyle = BoxEdges(
            top = s["padding-top"]?.let { SizeSpec.parse(it).px },
            right = s["padding-right"]?.let { SizeSpec.parse(it).px },
            bottom = s["padding-bottom"]?.let { SizeSpec.parse(it).px },
            left = s["padding-left"]?.let { SizeSpec.parse(it).px } ?: s["padding"]?.let { SizeSpec.parse(it).px }
        )
        return mergeEdges(fromCls, fromStyle)
    }

    private fun mergeEdges(a: BoxEdges?, b: BoxEdges): BoxEdges =
        BoxEdges(
            top = a?.top ?: b.top,
            right = a?.right ?: b.right,
            bottom = a?.bottom ?: b.bottom,
            left = a?.left ?: b.left
        )

    // ═══ 定位 / 弹性 / 网格属性 ═══

    fun positionOf(node: HtmlNode): String {
        styleOf(node)["position"]?.let { return it.trim().lowercase() }
        for (c in node.classes) {
            when (c) {
                "absolute", "position-absolute" -> return "absolute"
                "relative", "position-relative" -> return "relative"
                "fixed", "position-fixed", "fixed-top", "fixed-bottom" -> return "fixed"
                "sticky", "position-sticky", "sticky-top" -> return "sticky"
            }
        }
        return "static"
    }

    fun zIndexOf(node: HtmlNode): Int? {
        styleOf(node)["z-index"]?.toIntOrNull()?.let { return it }
        for (c in node.classes) {
            if (c.startsWith("z-")) c.removePrefix("z-").toIntOrNull()?.let { return it }
        }
        return null
    }

    /** flex 容器属性（direction/wrap/justify/align/gap），非 flex 返回 null。 */
    fun flexPropsOf(node: HtmlNode): FlexProps? {
        val s = styleOf(node)
        val cls = node.classes
        var direction = s["flex-direction"]?.lowercase()?.trim()
            ?: when {
                cls.any { it == "flex-row" || it == "row" } -> "row"
                cls.any { it == "flex-col" || it == "flex-column" || it == "column" || it == "flex-col-reverse" } -> "column"
                cls.any { it.startsWith("row-reverse") } -> "row-reverse"
                else -> null
            }
        val justify = s["justify-content"]?.lowercase()?.trim()
            ?: when {
                cls.any { it == "justify-center" || it == "justify-content-center" } -> "center"
                cls.any { it == "justify-between" || it == "justify-content-between" || it == "space-between" } -> "space-between"
                cls.any { it == "justify-end" || it == "justify-content-end" } -> "flex-end"
                cls.any { it == "justify-start" || it == "justify-content-start" } -> "flex-start"
                else -> null
            }
        val align = s["align-items"]?.lowercase()?.trim()
            ?: when {
                cls.any { it == "items-center" || it == "align-items-center" } -> "center"
                cls.any { it == "items-start" || it == "align-items-start" } -> "flex-start"
                cls.any { it == "items-end" || it == "align-items-end" } -> "flex-end"
                cls.any { it == "items-stretch" || it == "align-items-stretch" } -> "stretch"
                else -> null
            }
        val wrap = s["flex-wrap"]?.lowercase()?.trim()
            ?: if (cls.any { it == "flex-wrap" || it == "wrap" }) "wrap" else null
        val gap = s["gap"]?.let { SizeSpec.parse(it).px }
            ?: cls.firstOrNull { it.startsWith("gap-") }?.let { SizeSpec.parse(it.removePrefix("gap-")).px }
        if (direction == null && justify == null && align == null && wrap == null && gap == null) return null
        return FlexProps(direction ?: "row", wrap, justify, align, gap)
    }

    data class FlexProps(
        val direction: String,
        val wrap: String?,
        val justify: String?,
        val alignItems: String?,
        val gapPx: Int?
    )

    /** grid 容器属性（columns/rows/gap），非 grid 返回 null。 */
    fun gridPropsOf(node: HtmlNode): GridProps? {
        val s = styleOf(node)
        val cls = node.classes
        val cols = s["grid-template-columns"]?.trim()
            ?: cls.firstOrNull { it.startsWith("grid-cols-") }?.removePrefix("grid-cols-")
            ?: return null
        return GridProps(cols, s["grid-template-rows"]?.trim(), s["gap"]?.let { SizeSpec.parse(it).px })
    }

    data class GridProps(
        val templateColumns: String,
        val templateRows: String?,
        val gapPx: Int?
    )
}
