package com.apex.agent.core.codetools.html

/**
 * ═══ 视觉画像分析器（HTML 布局分析第二轮 · PR2）═══
 *
 * 让静态分析从「骨架正确」升级到「观感可判」：Agent 写完页面后不仅
 * 能知道结构对不对，还能知道**这页大概长什么样** —— 配色、字体、
 * 间距节奏、圆角语言，以及最容易被忽视的**低对比度可读性问题**。
 *
 * 数据来源（按可信度）：
 * 1. 节点级联视图（[HtmlNode.resolvedStyle]，PR1 注入）—— display
 *    参与布局的同一份样式，颜色/字体/间距同源；
 * 2. CSS 规则全量统计（[CssStylesheetParser.Stylesheet]，含 complex
 *    选择器规则）—— 调色板聚合用「规则里写了什么」，不依赖匹配。
 *
 * 不做的事（诚实边界）：
 * - 不追 CSS 变量链（var(--x) 原样跳过 —— 静态分析不展开变量）；
 * - 不做外部 CSS / Tailwind 颜色类解析（页面自包含假设）；
 * - 不渲染 —— 全部是**估算与频次画像**，真实观感用内置浏览器验证。
 */
object HtmlVisualProfile {

    /** 防病态：各类榜单条数上限。 */
    private const val PALETTE_TOP = 6
    private const val FONT_TOP = 5
    private const val SPACING_TOP = 6
    private const val SIZE_TOP = 5
    private const val CONTRAST_SAMPLES = 200

    // ═══ 颜色 ═══

    /** 解析后的颜色（ARGB 不透明通道只有 RGB；alpha 独立保留）。 */
    data class Color(
        val r: Int, val g: Int, val b: Int,
        val alpha: Double = 1.0,      // 0..1
        val raw: String               // 原始值（证据展示）
    ) {
        /** 十六进制显示（#rrggbb）。 */
        val hex: String get() = "#%02x%02x%02x".format(r, g, b)

        /** 相对亮度（WCAG 定义：0 黑 → 1 白）。 */
        val luminance: Double get() {
            fun ch(v: Int): Double {
                val c = v / 255.0
                return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * ch(r) + 0.7152 * ch(g) + 0.0722 * ch(b)
        }

        /** 饱和度估算（HSB 的 S，0 = 灰 → 1 = 纯色）。 */
        val saturation: Double get() {
            val mx = maxOf(r, g, b) / 255.0
            val mn = minOf(r, g, b) / 255.0
            return if (mx == 0.0) 0.0 else (mx - mn) / mx
        }

        /** 是否灰色系（低饱和 —— 主色/强调色判定用）。 */
        val isGrayish: Boolean get() = saturation < 0.18

        override fun equals(other: Any?): Boolean = other is Color && other.r == r && other.g == g && other.b == b
        override fun hashCode(): Int = r * 65536 + g * 256 + b
    }

    /** 颜色字面量 → [Color]；解析不出返回 null（var()/currentColor/inherit 等）。 */
    fun parseColor(raw: String?): Color? {
        if (raw.isNullOrBlank()) return null
        val v = raw.trim().lowercase()
        // var() / calc() / inherit / currentColor —— 静态分析不追
        if (v.startsWith("var(") || v.startsWith("calc(") || v in setOf("inherit", "initial", "unset", "currentcolor", "transparent", "none")) return null
        // #rgb / #rgba / #rrggbb / #rrggbbaa
        if (v.startsWith("#")) {
            val hex = v.removePrefix("#")
            return when (hex.length) {
                3 -> Color(
                    Integer.parseInt(hex.substring(0, 1) + hex.substring(0, 1), 16),
                    Integer.parseInt(hex.substring(1, 2) + hex.substring(1, 2), 16),
                    Integer.parseInt(hex.substring(2, 3) + hex.substring(2, 3), 16),
                    raw = raw
                )
                4 -> Color(
                    Integer.parseInt(hex.substring(0, 1) + hex.substring(0, 1), 16),
                    Integer.parseInt(hex.substring(1, 2) + hex.substring(1, 2), 16),
                    Integer.parseInt(hex.substring(2, 3) + hex.substring(2, 3), 16),
                    alpha = Integer.parseInt(hex.substring(3, 4) + hex.substring(3, 4), 16) / 255.0,
                    raw = raw
                )
                6 -> Color(
                    Integer.parseInt(hex.substring(0, 2), 16),
                    Integer.parseInt(hex.substring(2, 4), 16),
                    Integer.parseInt(hex.substring(4, 6), 16),
                    raw = raw
                )
                8 -> Color(
                    Integer.parseInt(hex.substring(0, 2), 16),
                    Integer.parseInt(hex.substring(2, 4), 16),
                    Integer.parseInt(hex.substring(4, 6), 16),
                    alpha = Integer.parseInt(hex.substring(6, 8), 16) / 255.0,
                    raw = raw
                )
                else -> null
            }
        }
        // rgb() / rgba()（逗号与空格两种现代语法均容错）
        if (v.startsWith("rgb")) {
            val inner = v.substringAfter('(').substringBefore(')')
            val parts = inner.split(',', '/').flatMap { tok -> tok.trim().split(Regex("\\s+")) }
                .mapNotNull { it.trim().toDoubleOrNull() }
            if (parts.size >= 3) {
                val a = if (parts.size >= 4) (parts[3] / 255.0).coerceIn(0.0, 1.0) else 1.0
                return Color(parts[0].toInt().coerceIn(0, 255), parts[1].toInt().coerceIn(0, 255), parts[2].toInt().coerceIn(0, 255), a, raw)
            }
            return null
        }
        // hsl() / hsla()
        if (v.startsWith("hsl")) {
            val inner = v.substringAfter('(').substringBefore(')')
            val nums = Regex("-?\\d+(\\.\\d+)?").findAll(inner).map { it.value.toDouble() }.toList()
            if (nums.size >= 3) {
                val (r, g, b) = hslToRgb(nums[0], (nums[1] / 100.0).coerceIn(0.0, 1.0), (nums[2] / 100.0).coerceIn(0.0, 1.0))
                val a = if (nums.size >= 4) (nums[3] / 100.0).coerceIn(0.0, 1.0) else 1.0
                return Color(r, g, b, a, raw)
            }
            return null
        }
        // 常见命名色（覆盖高频 38 个 —— 不求全，求「页面里真会用的」）
        return NAMED_COLORS[v]
    }

    private fun hslToRgb(h: Double, s: Double, l: Double): Triple<Int, Int, Int> {
        val hh = ((h % 360.0) + 360.0) % 360.0 / 360.0
        val q = if (l < 0.5) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        fun channel(t0: Double): Int {
            var t = t0
            if (t < 0) t += 1
            if (t > 1) t -= 1
            val c = when {
                t < 1.0 / 6.0 -> p + (q - p) * 6 * t
                t < 1.0 / 2.0 -> q
                t < 2.0 / 3.0 -> p + (q - p) * (2.0 / 3.0 - t) * 6
                else -> p
            }
            return (c * 255).toInt().coerceIn(0, 255)
        }
        return Triple(channel(hh + 1.0 / 3.0), channel(hh), channel(hh - 1.0 / 3.0))
    }

    /** 高频命名色表（小写）。 */
    private val NAMED_COLORS: Map<String, Color> = mapOf(
        "black" to Color(0, 0, 0, raw = "black"), "white" to Color(255, 255, 255, raw = "white"),
        "red" to Color(255, 0, 0, raw = "red"), "green" to Color(0, 128, 0, raw = "green"),
        "blue" to Color(0, 0, 255, raw = "blue"), "yellow" to Color(255, 255, 0, raw = "yellow"),
        "orange" to Color(255, 165, 0, raw = "orange"), "purple" to Color(128, 0, 128, raw = "purple"),
        "pink" to Color(255, 192, 203, raw = "pink"), "gray" to Color(128, 128, 128, raw = "gray"),
        "grey" to Color(128, 128, 128, raw = "grey"), "silver" to Color(192, 192, 192, raw = "silver"),
        "maroon" to Color(128, 0, 0, raw = "maroon"), "navy" to Color(0, 0, 128, raw = "navy"),
        "teal" to Color(0, 128, 128, raw = "teal"), "aqua" to Color(0, 255, 255, raw = "aqua"),
        "cyan" to Color(0, 255, 255, raw = "cyan"), "magenta" to Color(255, 0, 255, raw = "magenta"),
        "fuchsia" to Color(255, 0, 255, raw = "fuchsia"), "lime" to Color(0, 255, 0, raw = "lime"),
        "olive" to Color(128, 128, 0, raw = "olive"), "brown" to Color(165, 42, 42, raw = "brown"),
        "coral" to Color(255, 127, 80, raw = "coral"), "crimson" to Color(220, 20, 60, raw = "crimson"),
        "indigo" to Color(75, 0, 130, raw = "indigo"), "ivory" to Color(255, 255, 240, raw = "ivory"),
        "khaki" to Color(240, 230, 140, raw = "khaki"), "lavender" to Color(230, 230, 250, raw = "lavender"),
        "salmon" to Color(250, 128, 114, raw = "salmon"), "tan" to Color(210, 180, 140, raw = "tan"),
        "tomato" to Color(255, 99, 71, raw = "tomato"), "violet" to Color(238, 130, 238, raw = "violet"),
        "wheat" to Color(245, 222, 179, raw = "wheat"), "beige" to Color(245, 245, 220, raw = "beige"),
        "gold" to Color(255, 215, 0, raw = "gold"), "orchid" to Color(218, 112, 214, raw = "orchid"),
        "plum" to Color(221, 160, 221, raw = "plum"), "slategray" to Color(112, 128, 144, raw = "slategray")
    )

    /** WCAG 对比度（1..21，越大越可读）。 */
    fun contrastRatio(a: Color, b: Color): Double {
        val la = a.luminance
        val lb = b.luminance
        val lighter = maxOf(la, lb)
        val darker = minOf(la, lb)
        return (lighter + 0.05) / (darker + 0.05)
    }

    /** WCAG 判级：AA 正文 4.5 / AA 大字 3.0 / AAA 7.0。 */
    fun wcagLevel(ratio: Double): String = when {
        ratio >= 7.0 -> "AAA"
        ratio >= 4.5 -> "AA"
        ratio >= 3.0 -> "AA-large"
        else -> "FAIL"
    }

    // ═══ 画像数据模型 ═══

    /** 画像产出（null 字段 = 页面未声明，报告跳过）。 */
    data class VisualProfile(
        val backgroundColor: Color?,       // body/html 背景（缺省按白估算）
        val textColor: Color?,             // body color（缺省按黑估算）
        val palette: List<PaletteEntry>,   // 全部声明颜色频次榜
        val accentColors: List<Color>,     // 非灰高频色（主色/强调色候选）
        val fontFamilies: List<FontEntry>, // 字体栈频次
        val baseFontSizePx: Int?,          // html/body font-size
        val fontSizes: List<SizeEntry>,    // 字号分布
        val spacingScale: List<SizeEntry>, // 间距（margin/padding 值）分布
        val spacingRhythm: String?,        // "4px 节奏" / "8px 节奏" / "混合"
        val radii: List<SizeEntry>,        // 圆角分布
        val shadowCount: Int,              // box-shadow 声明数
        val contrastIssues: List<ContrastIssue>,
        val darkThemeCandidate: Boolean    // 深色背景 + 浅色文字 → 暗色主题画像
    ) {
        val hasAnySignal: Boolean
            get() = palette.isNotEmpty() || fontFamilies.isNotEmpty() || spacingScale.isNotEmpty()
    }

    data class PaletteEntry(val color: Color, val count: Int, val usage: String) // usage: bg/text/border/other

    data class FontEntry(val family: String, val count: Int)

    data class SizeEntry(val raw: String, val px: Int, val count: Int)

    data class ContrastIssue(
        val fg: Color,
        val bg: Color,
        val ratio: Double,
        val where: String,   // 选择器/标签证据
        val isLarge: Boolean // 大字（≥18px 或 ≥14px bold 按 AA-large）
    )

    // ═══ 分析入口 ═══

    /**
     * 产出视觉画像。规则全量统计（调色板/字体/间距）+ 节点级联视图
     * （对比度配对、主题判定）。样式全空返回 hasAnySignal=false 的空画像。
     */
    fun analyze(doc: HtmlDocument, sheet: CssStylesheetParser.Stylesheet): VisualProfile {
        // 1. 规则全量统计（不依赖匹配 —— 「写了什么」就是设计意图）
        val bgColors = LinkedHashMap<Color, Int>()
        val textColors = LinkedHashMap<Color, Int>()
        val otherColors = LinkedHashMap<Color, Int>()
        val fonts = LinkedHashMap<String, Int>()
        val fontSizes = LinkedHashMap<String, Int>()
        val spacings = LinkedHashMap<String, Int>()
        val radii = LinkedHashMap<String, Int>()
        var shadowCount = 0
        for (rule in sheet.rules) {
            for ((key, value) in rule.declarations) {
                when {
                    key == "background-color" || key == "background" -> classifyColor(value, bgColors, otherColors)
                    key == "color" -> classifyColor(value, textColors, otherColors)
                    key == "border-color" || key == "border" || key == "border-top-color" || key == "border-right-color" ||
                        key == "border-bottom-color" || key == "border-left-color" -> classifyColor(value, otherColors, null)
                    key == "font-family" -> {
                        val primary = value.split(',').firstOrNull()?.trim()?.trim('"', '\'')?.take(40)
                        if (!primary.isNullOrBlank()) fonts[primary] = (fonts[primary] ?: 0) + 1
                    }
                    key == "font-size" -> recordSize(value, fontSizes)
                    key == "box-shadow" -> if (value.isNotBlank() && value != "none") shadowCount++
                    key == "border-radius" -> if (value != "0") recordSize(value, radii)
                    key.endsWith("margin") || key.endsWith("padding") || key == "gap" -> recordSize(value, spacings)
                }
            }
        }
        // 2. 节点级联视图：body 背景/文字 + 对比度采样
        val body = doc.firstByTag("body")
        val bgColor = firstColorOf(body?.resolvedStyle, "background-color")
            ?: firstColorOf(body?.resolvedStyle, "background")
        val fgColor = firstColorOf(body?.resolvedStyle, "color")
        val htmlStyle = doc.firstByTag("html")?.resolvedStyle
        val effectiveBg = bgColor ?: firstColorOf(htmlStyle, "background-color")
        val effectiveFg = fgColor ?: firstColorOf(htmlStyle, "color")
        val contrastIssues = collectContrastIssues(doc, effectiveBg ?: Color(255, 255, 255, raw = "default-white"))
        // 暗色判定：背景明显深 + 文字明显浅（静态估算三色平均 < 96 视为深）
        val darkCandidate = effectiveBg != null && effectiveFg != null &&
            (effectiveBg.r + effectiveBg.g + effectiveBg.b) / 3 < 96 &&
            (effectiveFg.r + effectiveFg.g + effectiveFg.b) / 3 > 159
        val baseFont = firstSizeOf(htmlStyle, "font-size") ?: firstSizeOf(body?.resolvedStyle, "font-size")
        // 3. 主色/强调色：非灰系高频色（饱和度 ≥ 0.18）
        val accent = (bgColors.entries + textColors.entries + otherColors.entries)
            .groupBy({ it.key }, { it.value })
            .map { (k, vs) -> k to vs.sum() }
            .filter { !it.first.isGrayish && it.first.alpha > 0.5 }
            .sortedByDescending { it.second }
            .take(3)
            .map { it.first }
        return VisualProfile(
            backgroundColor = effectiveBg,
            textColor = effectiveFg,
            palette = buildPalette(bgColors, textColors, otherColors),
            accentColors = accent,
            fontFamilies = fonts.entries.sortedByDescending { it.value }.take(FONT_TOP).map { FontEntry(it.key, it.value) },
            baseFontSizePx = baseFont,
            fontSizes = fontSizes.entries.sortedByDescending { it.value }.take(SIZE_TOP).map { (raw, c) -> SizeEntry(raw, sizeToPx(raw) ?: 0, c) },
            spacingScale = spacings.entries.sortedByDescending { it.value }.take(SPACING_TOP).map { (raw, c) -> SizeEntry(raw, sizeToPx(raw) ?: 0, c) },
            spacingRhythm = rhythmOf(spacings),
            radii = radii.entries.sortedByDescending { it.value }.take(SIZE_TOP).map { (raw, c) -> SizeEntry(raw, sizeToPx(raw) ?: 0, c) },
            shadowCount = shadowCount,
            contrastIssues = contrastIssues,
            darkThemeCandidate = darkCandidate
        )
    }

    private fun classifyColor(value: String, primary: LinkedHashMap<Color, Int>?, fallback: LinkedHashMap<Color, Int>?) {
        // 多值（如 background: #fff url(...)）取第一个颜色段
        val color = parseColor(value.trim()) ?: run {
            val m = Regex("#[0-9a-f]{3,8}|rgba?\\([^)]+\\)|hsla?\\([^)]+\\)").find(value)
            m?.let { parseColor(it.value) }
        } ?: return
        if (color.alpha < 0.35) return // 近全透明不进画像
        if (primary != null) primary[color] = (primary[color] ?: 0) + 1
        else fallback?.let { it[color] = (it[color] ?: 0) + 1 }
    }

    private fun buildPalette(
        bg: Map<Color, Int>, text: Map<Color, Int>, other: Map<Color, Int>
    ): List<PaletteEntry> {
        val all = LinkedHashMap<Color, Pair<Int, String>>()
        fun merge(colors: Map<Color, Int>, usage: String) {
            for ((c, n) in colors) {
                val prev = all[c]
                all[c] = (prev?.first ?: 0) + n to if (prev?.second == usage || prev == null) usage else "mixed"
            }
        }
        merge(bg, "bg"); merge(text, "text"); merge(other, "border/other")
        return all.entries.sortedByDescending { it.value.first }.take(PALETTE_TOP)
            .map { PaletteEntry(it.key, it.value.first, it.value.second) }
    }

    private fun firstColorOf(style: Map<String, String>?, vararg keys: String): Color? {
        if (style == null) return null
        for (k in keys) parseColor(style[k])?.let { return it }
        return null
    }

    private fun firstSizeOf(style: Map<String, String>?, key: String): Int? =
        style?.get(key)?.let { sizeToPx(it) }

    /** 尺寸字面量 → px（px/rem/em/%/pt；其余 null）。 */
    internal fun sizeToPx(raw: String): Int? {
        val v = raw.trim().lowercase()
        val num = v.takeWhile { it.isDigit() || it == '.' }.toDoubleOrNull() ?: return null
        return when {
            v.endsWith("px") -> num.toInt()
            v.endsWith("rem") || v.endsWith("em") -> (num * CssStylesheetParser.ESTIMATED_ROOT_FONT_PX).toInt()
            v.endsWith("pt") -> (num * 1.33).toInt()
            else -> null
        }
    }

    private fun recordSize(value: String, into: LinkedHashMap<String, Int>) {
        val v = value.trim().lowercase()
        if (v.isEmpty() || v == "0" || v == "auto" || v.startsWith("var(") || v.startsWith("calc(")) return
        // 多值取首段（margin: 8px 16px → 记 8px 一票 + 16px 由四属性变体各自记录）
        val first = v.split(Regex("\\s+")).first()
        into[first] = (into[first] ?: 0) + 1
    }

    /** 间距节奏判定：过半值贴近 4 的倍数 → 4px 节奏；8 同理；否则混合。 */
    private fun rhythmOf(spacings: Map<String, Int>): String? {
        if (spacings.isEmpty()) return null
        val entries = spacings.entries.mapNotNull { e -> sizeToPx(e.key)?.let { px -> px to e.value } }
        if (entries.isEmpty()) return null
        val total = entries.sumOf { it.second }.toDouble()
        val near4 = entries.filter { it.first % 4 == 0 }.sumOf { it.second }
        val near8 = entries.filter { it.first % 8 == 0 }.sumOf { it.second }
        return when {
            near8 / total >= 0.6 -> "8px 节奏"
            near4 / total >= 0.6 -> "4px 节奏"
            else -> "混合"
        }
    }

    // ═══ 对比度采样 ═══

    /**
     * 节点级对比度采样：自身 color + 自身/最近祖先 background（级联视图
     * 已合并规则，但背景继承需沿树向上找 —— 浏览器语义同构的保守实现）。
     * 只报告「能确定配对」的节点（都取不到就跳过，不臆测）。
     */
    private fun collectContrastIssues(doc: HtmlDocument, defaultBg: Color): List<ContrastIssue> {
        val out = mutableListOf<ContrastIssue>()
        var sampled = 0
        var inheritedBg: Color = defaultBg
        fun visit(node: HtmlNode, bg: Color) {
            if (sampled >= CONTRAST_SAMPLES || out.size >= 12) return
            val style = node.resolvedStyle ?: node.style
            var currentBg = bg
            style["background-color"]?.let { parseColor(it) }?.let { if (it.alpha > 0.6) currentBg = it }
            val fg = style["color"]?.let { parseColor(it) }
            if (fg != null && node.directText.isNotBlank()) {
                sampled++
                val ratio = contrastRatio(fg, currentBg)
                val fontSizePx = style["font-size"]?.let { sizeToPx(it) }
                val isLarge = (fontSizePx ?: 16) >= 18
                if (ratio < if (isLarge) 3.0 else 4.5) {
                    val where = buildString {
                        append('<').append(node.tag)
                        node.attrs["class"]?.let { append(" .").append(it.trim().split(Regex("\\s+")).firstOrNull()?.take(24)) }
                        append(">")
                    }
                    out.add(ContrastIssue(fg, currentBg, ratio, where, isLarge))
                }
            }
            node.children.forEach { visit(it, currentBg) }
        }
        doc.firstByTag("body")?.let { body ->
            body.resolvedStyle?.get("background-color")?.let { parseColor(it) }?.let { inheritedBg = it }
            visit(body, inheritedBg)
        }
        return out.sortedBy { it.ratio }
    }
}
