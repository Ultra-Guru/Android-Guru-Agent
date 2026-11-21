package com.apex.agent.core.codetools.html

/**
 * ═══ CSS 样式表解析 + 级联视图（HTML 布局分析第二轮 · PR1）═══
 *
 * 现状缺口：静态布局分析此前只看「内联 style 属性 + 框架工具类」，
 * 而 `<style>` 块被解析器原文捕获后**从未参与布局推断** —— 生成物
 * 页面的样式大头恰恰在 style 块里。本文件补上这条链：
 *
 * 1. [parseStylesheet]：style 块文本 → [Stylesheet]（规则序列）。
 *    支持：类 / ID / 标签 / 通配 / 复合选择器、逗号分组、@media
 *    条件内嵌；@keyframes / @font-face / @import / @supports 整块
 *    跳过；后代/兄弟组合器与伪类规则**不参与匹配**（静态分析做
 *    简单选择器匹配才可靠，复杂选择器标记后跳过并计数留痕）。
 * 2. [resolveCascadeStyles]：把规则「应用」到每个元素节点上，产出
 *    节点级联样式（inline style > style 块规则 > 框架类，规则后者
 *    覆盖前者，@media 按给定视口宽度评估），注入
 *    [HtmlNode.resolvedStyle] —— [HtmlLayoutModel] 的提取链随即
 *    全部升级为级联视图。
 *
 * 设计纪律：
 * - 纯函数、无 IO、无状态（Stylesheet 不可变）；
 * - 防御式：CSS 是病态重灾区（未闭合块 / 空 选择器 / 坏声明），
 *   一切坏段跳过 + 计数，绝不抛；
 * - 规模上限：规则 ≤ [MAX_RULES]、每节点参与匹配的声明属性数
 *   ≤ [MAX_PROPS_PER_NODE]，报告对病态 CSS 保持 KB 级。
 */
object CssStylesheetParser {

    /** 防病态 CSS：规则总数上限（超出丢弃并计数）。 */
    const val MAX_RULES = 4_000

    /** 防属性爆炸：单节点级联视图保留的属性上限。 */
    const val MAX_PROPS_PER_NODE = 64

    /** 单条声明值长度上限（超长渐变/数据 URI 截断）。 */
    private const val MAX_VALUE_LEN = 300

    /** 常见字号单位换算（静态估算基准：16px 根字号 / 375px 视口）。 */
    internal const val ESTIMATED_ROOT_FONT_PX = 16
    internal const val ESTIMATED_VIEWPORT_PX = 375

    // ═══ 数据模型 ═══

    /** 单条选择器（仅简单/复合选择器参与匹配；complex=true 的跳过匹配）。 */
    data class CssSelector(
        val raw: String,
        val tag: String?,        // null = 未指定标签（.x / #x / *）
        val classes: List<String>,
        val id: String?,
        val universal: Boolean,
        /** 含组合器（后代/子代/相邻/通用兄弟）或伪类 —— 不参与节点匹配。 */
        val complex: Boolean
    ) {
        /** 是否可能匹配任何元素（complex 与空选择器为 false）。 */
        val matchable: Boolean get() = !complex && (universal || tag != null || classes.isNotEmpty() || id != null)
    }

    /** 一条规则：选择器组 + 声明 + 可选媒体条件（"max-width:600px"）。 */
    data class CssRule(
        val selectors: List<CssSelector>,
        val declarations: Map<String, String>,
        val mediaCondition: String?
    ) {
        val matchableSelectors: List<CssSelector> get() = selectors.filter { it.matchable }
    }

    /** 样式表：规则序列 + 解析统计（坏段计数，报告/调试用）。 */
    data class Stylesheet(
        val rules: List<CssRule>,
        val skippedBlocks: Int,      // @keyframes / @font-face 等整块跳过数
        val malformedRules: Int,     // 解析失败丢弃的规则数
        val complexSelectors: Int,   // 组合器/伪类选择器数（不参与匹配）
        val truncated: Boolean       // 规则数触顶
    ) {
        val isEmpty: Boolean get() = rules.isEmpty()

        companion object {
            val EMPTY = Stylesheet(emptyList(), 0, 0, 0, false)
        }
    }

    // ═══ 解析入口 ═══

    /** 解析一段 style 块文本（容错：坏段跳过计数，不抛）。 */
    fun parse(text: String): Stylesheet {
        if (text.isBlank()) return Stylesheet.EMPTY
        val src = stripComments(text)
        val rules = mutableListOf<CssRule>()
        var skipped = 0
        var malformed = 0
        var complex = 0
        var truncated = false
        var i = 0
        val n = src.length
        while (i < n) {
            // 跳过空白与分号
            while (i < n && (src[i].isWhitespace() || src[i] == ';')) i++
            if (i >= n) break
            // 读取 at-rule 或选择器段（到首个 { 或 ;）
            val headStart = i
            var headEnd = i
            while (headEnd < n && src[headEnd] != '{' && src[headEnd] != ';') headEnd++
            if (headEnd >= n) break
            val head = src.substring(headStart, headEnd).trim()
            if (src[headEnd] == ';') { i = headEnd + 1; continue } // @import 等 —— 语句级跳过
            // 找配对的右括号（含嵌套 @media）
            val (bodyEnd, next) = findBlockEnd(src, headEnd)
            val body = src.substring(headEnd + 1, bodyEnd)
            i = next
            if (head.startsWith("@")) {
                val at = head.substringBefore(' ').lowercase()
                when {
                    at == "@media" -> {
                        val cond = head.substringAfter(' ', "").trim().take(120)
                        val inner = parse(body)
                        skipped += inner.skippedBlocks
                        malformed += inner.malformedRules
                        complex += inner.complexSelectors
                        for (r in inner.rules) {
                            if (rules.size >= MAX_RULES) { truncated = true; break }
                            rules.add(r.copy(mediaCondition = r.mediaCondition?.let { c -> "$c && $cond" } ?: cond))
                        }
                    }
                    // keyframes / font-face / supports / layer / property —— 整块跳过
                    else -> skipped++
                }
                continue
            }
            // 普通规则：head 是逗号分隔的选择器组，body 是声明块。
            // 全 complex 的规则也保留（视觉画像/统计用），级联解析自动跳过其匹配。
            val selectors = head.split(',').mapNotNull { sel -> parseSelector(sel) }
            complex += selectors.count { it.complex }
            if (selectors.isEmpty()) { malformed++; continue }
            val declarations = parseDeclarations(body)
            if (declarations.isEmpty()) { malformed++; continue }
            if (rules.size >= MAX_RULES) { truncated = true; continue }
            rules.add(CssRule(selectors, declarations, null))
        }
        return Stylesheet(rules, skipped, malformed, complex, truncated)
    }

    /** 去掉块注释与行注释（字符串字面量内的注释符不特殊处理 —— 保守跳过）。 */
    private fun stripComments(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            if (c == '/' && i + 1 < n && text[i + 1] == '*') {
                val end = text.indexOf("*/", i + 2)
                i = if (end >= 0) end + 2 else n
                sb.append(' ')
            } else if (c == '/' && i + 1 < n && text[i + 1] == '/') {
                val end = text.indexOf('\n', i)
                i = if (end >= 0) end else n
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    /** 找与 start 处左括号配对的右括号（处理嵌套；未闭合返回文本末尾）。 */
    private fun findBlockEnd(src: String, start: Int): Pair<Int, Int> {
        var depth = 0
        var i = start
        val n = src.length
        while (i < n) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i to i + 1
                }
            }
            i++
        }
        return n to n // 未闭合：吞到末尾（防御，不抛）
    }

    /** 单个选择器 → [CssSelector]；无法解析返回 null。 */
    internal fun parseSelector(raw: String): CssSelector? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        // 伪类/伪元素/属性选择器（含 :root / :hover / [data-x]）—— complex 不参与匹配
        val hasPseudo = s.contains(':') || s.contains('[')
        // 组合器（后代空格 / > + ~）—— complex 不参与匹配
        val hasCombinator = s.any { it == '>' || it == '+' || it == '~' } || s.contains(' ')
        if (hasCombinator || hasPseudo) {
            return CssSelector(s.take(80), null, emptyList(), null, false, complex = true)
        }
        var tag: String? = null
        val classes = mutableListOf<String>()
        var id: String? = null
        var universal = false
        var idx = 0
        while (idx < s.length) {
            when (s[idx]) {
                '.' -> {
                    val start = idx + 1
                    var end = start
                    while (end < s.length && s[end] !in ".#") end++
                    val cls = s.substring(start, end).lowercase()
                    if (cls.isNotEmpty()) classes.add(cls)
                    idx = end
                }
                '#' -> {
                    val start = idx + 1
                    var end = start
                    while (end < s.length && s[end] !in ".#") end++
                    val ident = s.substring(start, end).lowercase()
                    if (ident.isNotEmpty()) id = ident
                    idx = end
                }
                '*' -> { universal = true; idx++ }
                else -> {
                    val start = idx
                    var end = start
                    while (end < s.length && s[end] !in ".#*") end++
                    val name = s.substring(start, end).lowercase()
                    if (name.isNotEmpty()) tag = name
                    idx = end
                }
            }
        }
        return CssSelector(s.take(80), tag, classes, id, universal, complex = false)
    }

    /** 声明块 → 属性表（复用 style 属性的容错切分；值截断）。 */
    private fun parseDeclarations(body: String): Map<String, String> {
        if (body.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (decl in body.split(';')) {
            val colon = decl.indexOf(':')
            if (colon <= 0) continue
            val key = decl.substring(0, colon).trim().lowercase()
            val value = decl.substring(colon + 1).trim()
            if (key.isEmpty() || value.isEmpty()) continue
            if (key.startsWith("--")) continue // CSS 变量本身不是布局属性
            out[key] = value.take(MAX_VALUE_LEN)
        }
        return out
    }

    // ═══ 匹配 ═══

    /** 选择器是否匹配节点（大小写不敏感；类/ID/tag 全命中才匹配）。 */
    fun matches(sel: CssSelector, node: HtmlNode): Boolean {
        if (!sel.matchable) return false
        if (sel.tag != null && node.tag != sel.tag) return false
        if (sel.id != null && (node.attrs["id"] ?: "").lowercase() != sel.id) return false
        if (sel.classes.isNotEmpty()) {
            val nodeClasses = node.classes
            if (!sel.classes.all { it in nodeClasses }) return false
        }
        return true
    }

    // ═══ 媒体条件评估 ═══

    /**
     * 媒体条件静态评估（默认按 375px 移动视口 —— 与盒模型估算基准一致）：
     * - `(max-width: 600px)` → 375 ≤ 600 → true；
     * - `(min-width: 768px)` → false；
     * - `screen` / `all` / 仅 and 连接 → 恒真；
     * - `print` → false（打印样式不进屏幕布局视图）；
     * - 解析不出的条件 → false（保守：宁缺毋滥）。
     */
    fun mediaApplies(condition: String?, viewportWidthPx: Int = ESTIMATED_VIEWPORT_PX): Boolean {
        if (condition.isNullOrBlank()) return true
        val c = condition.lowercase()
        if (c.contains("print")) return false
        if (!c.contains("(")) return true // screen / all / only screen —— 无宽度特征，恒真
        // 逐括号段评估，AND 语义（or 出现按保守 false）
        if (c.contains(" or ") || c.contains(",or")) return false
        val featureRegex = Regex("\\(([^)]+)\\)")
        for (m in featureRegex.findAll(c)) {
            val feature = m.groupValues[1].trim()
            when {
                feature.startsWith("min-width") -> {
                    val px = parseCssLengthToPx(feature.substringAfter(':', "").trim()) ?: return false
                    if (viewportWidthPx < px) return false
                }
                feature.startsWith("max-width") -> {
                    val px = parseCssLengthToPx(feature.substringAfter(':', "").trim()) ?: return false
                    if (viewportWidthPx > px) return false
                }
                feature.startsWith("min-device-width") || feature.startsWith("max-device-width") -> {
                    // 视作 min-width / max-width 同义（deprecated 但生成物常见）
                    val px = parseCssLengthToPx(feature.substringAfter(':', "").trim()) ?: return false
                    if (feature.startsWith("min") && viewportWidthPx < px) return false
                    if (feature.startsWith("max") && viewportWidthPx > px) return false
                }
                // orientation / hover / resolution 等非宽度特征 → 不约束宽度，放行
                else -> Unit
            }
        }
        return true // 无宽度特征命中（纯 screen 等）或全部特征通过
    }

    /** CSS 长度 → px 估算（px 原值 / em·rem ×16 / 百分比与 auto 返回 null）。 */
    internal fun parseCssLengthToPx(raw: String): Int? {
        val v = raw.trim().lowercase()
        if (v.isEmpty()) return null
        val num = v.takeWhile { it.isDigit() || it == '.' || it == '-' }
        val value = num.toDoubleOrNull() ?: return null
        return when {
            v.endsWith("px") -> value.toInt()
            v.endsWith("rem") || v.endsWith("em") -> (value * ESTIMATED_ROOT_FONT_PX).toInt()
            else -> null // %/vw/auto/负值宽度场景少 —— 保守 null
        }
    }

    // ═══ 级联解析 ═══

    /**
     * 把样式表「应用」到文档每个元素节点：按规则顺序，匹配且媒体条件
     * 通过的声明依序覆盖，最后内联 style 覆盖一切，写入
     * [HtmlNode.resolvedStyle]。幂等：重复调用重算重写。
     *
     * 性能：规则 × 节点双上限截断（病态输入直接跳过匹配阶段），
     * 注入的视图只保留 [MAX_PROPS_PER_NODE] 个属性。
     */
    fun resolveCascadeStyles(doc: HtmlDocument, sheet: Stylesheet, viewportWidthPx: Int = ESTIMATED_VIEWPORT_PX) {
        if (sheet.isEmpty) return
        val activeRules = sheet.rules.filter { mediaApplies(it.mediaCondition, viewportWidthPx) }
        if (activeRules.isEmpty()) return
        // 按选择器预分组：class 索引 / id 索引 / tag 索引 / 裸通配（免全规则 × 全节点扫描）
        val byClass = HashMap<String, MutableList<Pair<CssSelector, Map<String, String>>>>()
        val byId = HashMap<String, MutableList<Pair<CssSelector, Map<String, String>>>>()
        val byTag = HashMap<String, MutableList<Pair<CssSelector, Map<String, String>>>>()
        val bare = mutableListOf<Pair<CssSelector, Map<String, String>>>() // 无 class/id/tag 的（.x 无类的不会出现——matchable 已保证至少一个条件）
        for (rule in activeRules) {
            for (sel in rule.matchableSelectors) {
                val entry = sel to rule.declarations
                when {
                    sel.id != null -> byId.getOrPut(sel.id!!) { mutableListOf() }.add(entry)
                    sel.classes.isNotEmpty() -> byClass.getOrPut(sel.classes.first()) { mutableListOf() }.add(entry)
                    sel.tag != null -> byTag.getOrPut(sel.tag!!) { mutableListOf() }.add(entry)
                    else -> bare.add(entry)
                }
            }
        }
        doc.walk { node, _ ->
            if (node.tag.startsWith("#")) return@walk
            val merged = LinkedHashMap<String, String>()
            fun apply(entries: List<Pair<CssSelector, Map<String, String>>>) {
                for ((sel, decls) in entries) {
                    if (matches(sel, node)) {
                        for ((k, v) in decls) {
                            if (merged.size >= MAX_PROPS_PER_NODE && k !in merged) continue
                            merged[k] = v
                        }
                    }
                }
            }
            apply(bare)
            node.classes.forEach { cls -> byClass[cls]?.let(::apply) }
            (node.attrs["id"] ?: "").lowercase().takeIf { it.isNotEmpty() }?.let { byId[it]?.let(::apply) }
            byTag[node.tag]?.let(::apply)
            if (merged.isEmpty()) return@walk
            // 内联 style 最后覆盖（最高优先级）
            for ((k, v) in node.style) merged[k] = v
            node.resolvedStyle = LinkedHashMap(merged)
        }
    }

    /** 聚合文档全部 style 块 → 单一样式表（懒，聚合一次）。 */
    fun stylesheetOf(doc: HtmlDocument): Stylesheet {
        val styleNodes = doc.allByTag("style")
        if (styleNodes.isEmpty()) return Stylesheet.EMPTY
        var rules = mutableListOf<CssRule>()
        var skipped = 0; var malformed = 0; var complex = 0; var truncated = false
        for (node in styleNodes) {
            val sheet = parse(node.text)
            if (rules.size + sheet.rules.size > MAX_RULES) {
                truncated = true
                val room = MAX_RULES - rules.size
                if (room > 0) rules.addAll(sheet.rules.take(room))
                break
            }
            rules.addAll(sheet.rules)
            skipped += sheet.skippedBlocks
            malformed += sheet.malformedRules
            complex += sheet.complexSelectors
        }
        return Stylesheet(rules, skipped, malformed, complex, truncated)
    }
}
