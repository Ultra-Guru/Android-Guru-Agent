package com.apex.agent.core.codetools.html

import com.apex.agent.core.codetools.html.HtmlLayoutModel.DisplayKind
import com.apex.agent.core.codetools.html.HtmlLayoutModel.LayoutRole

/**
 * ═══ HTML 布局分析引擎 ═══
 *
 * 输入 [HtmlDocument]（[LightweightHtmlParser] 产物），输出 [HtmlLayoutReport]：
 * 文档元信息 / 结构统计 / 布局树（缩进树 + 括号速览）/ 响应式画像 /
 * 布局与可访问性问题清单。Agent 拿到报告即可「看懂」页面的骨架、
 * 大致观感（块尺寸估算）与毛病（哪里会溢出 / 哪里不可访问）。
 *
 * 设计纪律：
 * - 纯函数、无 IO：分析器只吃树吐报告，文件读取由工具层负责（可测性）；
 * - 一切计数带上限（top-N 截断），报告体积对 16MB 病态输入也保持 KB 级；
 * - severity 三级：ERROR（功能性问题）/ WARN（布局风险）/ INFO（质量信号）。
 */
object HtmlLayoutAnalyzer {

    /** 报告体积上限（防病态输入把上下文撑爆）。 */
    private const val TREE_MAX_LINES = 160
    private const val TREE_MAX_NODES = 3_000
    private const val EVIDENCE_MAX = 30
    private const val TAG_DIST_TOP = 14
    private const val FIXED_WIDTH_TOP = 12

    // ═══ 入口 ═══

    fun analyze(doc: HtmlDocument, pageTitleHint: String = ""): HtmlLayoutReport {
        val meta = analyzeMeta(doc, pageTitleHint)
        val stats = analyzeStructure(doc)
        val responsive = analyzeResponsive(doc)
        val issues = mutableListOf<LayoutIssue>()
        collectDocumentIssues(doc, issues)
        collectLayoutIssues(doc, issues)
        collectA11yIssues(doc, issues)
        collectQualityIssues(doc, issues)
        issues.sortWith(compareBy({ it.severity.rank }, { it.category }))
        return HtmlLayoutReport(
            meta = meta,
            stats = stats,
            responsive = responsive,
            issues = issues,
            treeText = renderTree(doc, stats),
            bracketSummary = renderBracketSummary(doc),
            truncated = doc.truncatedReason
        )
    }

    // ═══ 文档元信息 ═══

    private fun analyzeMeta(doc: HtmlDocument, hint: String): DocumentMeta {
        val titleNode = doc.firstByTag("title")
        val htmlNode = doc.firstByTag("html")
        val viewportNode = doc.allByTag("meta").firstOrNull { it.attrs.containsKey("viewport") || it.attrs["name"]?.lowercase() == "viewport" }
        val charsetNode = doc.allByTag("meta").firstOrNull {
            it.attrs.containsKey("charset") || it.attrs["http-equiv"]?.lowercase() == "content-type"
        }
        val descriptionNode = doc.allByTag("meta").firstOrNull { it.attrs["name"]?.lowercase() == "description" }
        val viewportContent = viewportNode?.attrs?.get("content")?.trim() ?: ""
        return DocumentMeta(
            title = titleNode?.directText?.trim()?.takeIf { it.isNotEmpty() } ?: hint,
            titleMissing = titleNode == null || titleNode.directText.isBlank(),
            lang = htmlNode?.attrs?.get("lang")?.trim() ?: "",
            langMissing = htmlNode == null || htmlNode.attrs["lang"].isNullOrBlank(),
            viewportContent = viewportContent,
            viewportMissing = viewportNode == null || viewportContent.isBlank(),
            viewportIsResponsive = viewportContent.contains("width=device-width", ignoreCase = true),
            charset = charsetNode?.attrs?.get("charset")
                ?: charsetNode?.attrs?.get("content")?.substringAfter("charset=", "")?.trim()?.take(20)
                ?: "",
            charsetMissing = charsetNode == null,
            descriptionMissing = descriptionNode == null,
            styleBlocks = doc.allByTag("style").size,
            scriptBlocks = doc.allByTag("script").size
        )
    }

    // ═══ 结构统计 ═══

    private fun analyzeStructure(doc: HtmlDocument): StructureStats {
        var elementCount = 0
        var maxDepth = 0
        val tagDist = LinkedHashMap<String, Int>()
        var flexCount = 0
        var gridCount = 0
        var tableCount = 0
        var inlineStyled = 0
        var classAttrCount = 0
        var landmarkBits = 0
        var buttonish = 0
        var imageCount = 0
        var textChars = 0
        doc.walk { node, depth ->
            if (depth > maxDepth) maxDepth = depth
            when {
                node.tag == "#text" -> textChars += node.text.length
                node.tag.startsWith("#") -> Unit
                else -> {
                    elementCount++
                    tagDist[node.tag] = (tagDist[node.tag] ?: 0) + 1
                    if (node.attrs.containsKey("style")) inlineStyled++
                    if (node.attrs.containsKey("class")) classAttrCount++
                    val display = HtmlLayoutModel.displayOf(node)
                    when (display) {
                        DisplayKind.FLEX -> flexCount++
                        DisplayKind.GRID -> gridCount++
                        DisplayKind.TABLE -> if (node.tag == "table") tableCount++
                        DisplayKind.NONE -> Unit
                        else -> Unit
                    }
                    when (HtmlLayoutModel.roleOf(node)) {
                        LayoutRole.LANDMARK_HEADER, LayoutRole.LANDMARK_NAV, LayoutRole.LANDMARK_MAIN,
                        LayoutRole.LANDMARK_ASIDE, LayoutRole.LANDMARK_FOOTER -> landmarkBits++
                        LayoutRole.BUTTON -> buttonish++
                        LayoutRole.IMAGE -> imageCount++
                        else -> Unit
                    }
                }
            }
        }
        return StructureStats(
            elementCount = elementCount,
            textNodeCount = doc.textNodeCount,
            maxDepth = maxDepth,
            textChars = textChars,
            flexContainers = flexCount,
            gridContainers = gridCount,
            tables = tableCount,
            inlineStyledElements = inlineStyled,
            classAttrElements = classAttrCount,
            landmarkRegions = landmarkBits,
            buttons = buttonish,
            images = imageCount,
            tagDistribution = tagDist.entries.sortedByDescending { it.value }.take(TAG_DIST_TOP)
                .map { it.key to it.value }.toMap(LinkedHashMap())
        )
    }

    // ═══ 响应式画像 ═══

    private fun analyzeResponsive(doc: HtmlDocument): ResponsiveProfile {
        val styleText = buildString {
            doc.allByTag("style").forEach { append(it.text).append('\n') }
        }
        val mediaQueries = Regex("@media[^{]*\\{").findAll(styleText).count()
        val responsiveClassCount = doc.root.let { root ->
            var count = 0
            doc.walk { n, _ ->
                if (n.tag.startsWith("#")) return@walk
                n.classes.forEach { c ->
                    val isResp = c.startsWith("sm:") || c.startsWith("md:") || c.startsWith("lg:") ||
                        c.startsWith("xl:") || c.startsWith("2xl:") ||
                        Regex("^col-(xs|sm|md|lg|xl)-").containsMatchIn(c) ||
                        c.startsWith("d-") && c != "d-flex" && c != "d-none" && c != "d-block" && c != "d-inline"
                    if (isResp) count++
                }
            }
            count
        }
        val fixedWidths = mutableListOf<FixedWidth>()
        doc.walk { n, depth ->
            if (n.tag.startsWith("#") || depth > 4) return@walk
            // Bootstrap container 家族由断点媒体查询控宽，不属固定宽度风险
            val isFrameworkContainer = n.classes.any { it == "container" || it.startsWith("container-") }
            if (isFrameworkContainer) return@walk
            val w = HtmlLayoutModel.widthOf(n)
            if (w.isFixedPx && (w.px ?: 0) > 375) {
                val maxW = HtmlLayoutModel.maxWidthOf(n)
                if (maxW == null || !maxW.isPercent) {
                    fixedWidths.add(FixedWidth(n.tag, w.px ?: 0, doc.lineOf(n), n.classes.take(3)))
                }
            }
            if (n.style["min-width"] != null) {
                val mw = HtmlLayoutModel.minWidthOf(n)
                if (mw != null && mw.isFixedPx && (mw.px ?: 0) > 375) {
                    fixedWidths.add(FixedWidth(n.tag, mw.px ?: 0, doc.lineOf(n), n.classes.take(3) + listOf("min-w")))
                }
            }
        }
        val percentWidths = doc.root.let {
            var c = 0
            doc.walk { n, _ ->
                if (n.tag.startsWith("#")) return@walk
                val w = HtmlLayoutModel.widthOf(n)
                if (w.isPercent) c++
            }
            c
        }
        val viewport = doc.allByTag("meta")
            .firstOrNull { it.attrs["name"]?.lowercase() == "viewport" }?.attrs?.get("content") ?: ""
        return ResponsiveProfile(
            viewportContent = viewport,
            hasResponsiveViewport = viewport.contains("width=device-width", true),
            mediaQueries = mediaQueries,
            responsiveUtilityClasses = responsiveClassCount,
            fixedWidthElements = fixedWidths.sortedByDescending { it.widthPx }.take(FIXED_WIDTH_TOP),
            percentWidthElements = percentWidths,
            mobileOverflowRisk = fixedWidths.any { it.widthPx > 420 }
        )
    }

    // ═══ 问题收集：文档级 ═══

    private fun collectDocumentIssues(doc: HtmlDocument, out: MutableList<LayoutIssue>) {
        val titleNode = doc.firstByTag("title")
        if (titleNode == null || titleNode.directText.isBlank()) {
            out.add(LayoutIssue.error("document", "缺少 <title>（浏览器标签页 / 收藏夹 / 搜索结果都依赖它）"))
        }
        val htmlNode = doc.firstByTag("html")
        if (htmlNode == null || htmlNode.attrs["lang"].isNullOrBlank()) {
            out.add(LayoutIssue.error("document", "缺少 lang 属性（屏幕阅读器发音 / 搜索引擎语言判定依赖它）"))
        }
        val charsetOk = doc.allByTag("meta").any {
            it.attrs.containsKey("charset") || it.attrs["http-equiv"]?.lowercase() == "content-type"
        }
        if (!charsetOk) {
            out.add(LayoutIssue.error("document", "缺少 charset 声明（中文页面易乱码）"))
        }
        val viewport = doc.allByTag("meta").firstOrNull { it.attrs["name"]?.lowercase() == "viewport" }
        if (viewport == null) {
            out.add(LayoutIssue.error("responsive", "缺少 viewport meta —— 手机上会按 980px 桌面宽度渲染再缩小，几乎不可用"))
        } else {
            val content = viewport.attrs["content"] ?: ""
            if (!content.contains("width=device-width", true)) {
                out.add(LayoutIssue.warn("responsive", "viewport 未设 width=device-width（当前: $content）—— 手机宽度自适应失效", content.take(EVIDENCE_MAX)))
            }
        }
    }

    // ═══ 问题收集：布局级 ═══

    private fun collectLayoutIssues(doc: HtmlDocument, out: MutableList<LayoutIssue>) {
        // 深度 / 体量
        var maxDepth = 0
        val deepNodes = mutableListOf<String>()
        doc.walk { n, d ->
            if (d > maxDepth) maxDepth = d
            if (d > 12 && deepNodes.size < 5) deepNodes.add("${n.tag} ${n.classes.take(2)}")
        }
        if (maxDepth > 12) {
            out.add(LayoutIssue.warn("structure", "嵌套过深（最大 $maxDepth 层，最深处: ${deepNodes.joinToString(" / ")})—— 建议扁平化或抽组件"))
        }

        // 绝对定位滥用 / 无定位祖先
        var absoluteCount = 0
        var fixedCount = 0
        doc.walk { n, _ ->
            if (n.tag.startsWith("#")) return@walk
            when (HtmlLayoutModel.positionOf(n)) {
                "absolute" -> {
                    absoluteCount++
                    var anc = n.parent
                    var positioned = false
                    while (anc != null && anc.tag != "#root") {
                        if (anc.tag == "body" || anc.tag == "html") break
                        val p = HtmlLayoutModel.positionOf(anc)
                        if (p != "static") { positioned = true; break }
                        anc = anc.parent
                    }
                    if (!positioned && out.size < 200) {
                        out.add(LayoutIssue.warn(
                            "positioning",
                            "absolute 元素没有已定位祖先（会以 body 为基准，常出现意外漂移）",
                            doc.lineOf(n).toString()
                        ))
                    }
                }
                "fixed" -> fixedCount++
                else -> Unit
            }
            // z-index 无定位（被浏览器忽略的典型误解）
            if (HtmlLayoutModel.zIndexOf(n) != null && HtmlLayoutModel.positionOf(n) == "static") {
                out.add(LayoutIssue.info("positioning", "z-index 配 static 定位不生效（浏览器忽略）—— 需要 relative/absolute", doc.lineOf(n).toString()))
            }
        }
        if (absoluteCount > 8) {
            out.add(LayoutIssue.info("positioning", "absolute 定位较多（$absoluteCount 处）—— 若非刻意画布类布局，建议回归文档流"))
        }
        if (fixedCount > 4) {
            out.add(LayoutIssue.info("positioning", "fixed 定位 $fixedCount 处——固定元素过多会互相遮挡，注意 z 序管理"))
        }

        // 固定宽度溢出
        doc.walk { n, _ ->
            if (n.tag.startsWith("#")) return@walk
            val w = HtmlLayoutModel.widthOf(n)
            if (w.isFixedPx && (w.px ?: 0) > 420) {
                val maxW = HtmlLayoutModel.maxWidthOf(n)
                val guarded = maxW != null && maxW.px != null && maxW.px!! <= 420
                if (!guarded && out.count { it.category == "overflow" } < 8) {
                    out.add(LayoutIssue.warn("overflow", "固定宽度 ${w.px}px 在 375px 手机屏上必然横向溢出", "${n.tag} line ${doc.lineOf(n)}"))
                }
            }
            // 负 margin
            nodeStyle(n, "margin-left")?.let { v ->
                if (v.startsWith("-")) out.add(LayoutIssue.info("spacing", "负 margin（$v）—— 重叠布局的信号，确认是有意为之", doc.lineOf(n).toString()))
            }
            nodeStyle(n, "margin-top")?.let { v ->
                if (v.startsWith("-")) out.add(LayoutIssue.info("spacing", "负 margin（$v）", doc.lineOf(n).toString()))
            }
        }

        // 表格布局（单行双列的「菜单+内容」式布局桌也常见，行数 ≥1 即判）
        doc.allByTag("table").forEach { table ->
            val hasSemantic = table.attrs["role"] != null
            val cellsContainBlockish = table.allByTag("div").size + table.allByTag("p").size +
                table.allByTag("h1").size + table.allByTag("h2").size + table.allByTag("ul").size
            if (!hasSemantic && cellsContainBlockish >= 3 && table.allByTag("tr").size >= 1) {
                out.add(LayoutIssue.warn("structure", "疑似用 <table> 做页面布局（单元格内大量 div/p/ul）—— 语义与响应式都受损", doc.lineOf(table).toString()))
            }
        }

        // 多个 main / 多个 h1
        val mains = doc.allByTag("main")
        if (mains.size > 1) {
            out.add(LayoutIssue.error("semantics", "存在 ${mains.size} 个 <main>（每个页面只允许一个）"))
        }
        var h1 = 0
        doc.walk { n, _ -> if (n.tag == "h1") h1++ }
        if (h1 > 1) {
            out.add(LayoutIssue.warn("semantics", "存在 $h1 个 <h1>（页面主标题应唯一；其余降为 h2）"))
        }
    }

    // ═══ 问题收集：可访问性 ═══

    private fun collectA11yIssues(doc: HtmlDocument, out: MutableList<LayoutIssue>) {
        var imgNoAlt = 0
        var linkNoText = 0
        var buttonNoText = 0
        var inputNoLabel = 0
        var iframeNoTitle = 0
        var headingSkip = 0
        var prevHeading = 0
        val labels = doc.allByTag("label").mapNotNull { it.attrs["for"] }.toSet()

        // 标题层级遍历需要文档序
        doc.walk { n, _ ->
            when (n.tag) {
                "img" -> if (n.attrs["alt"] == null) imgNoAlt++
                "a" -> {
                    val text = n.allText.ifBlank { n.attrs["aria-label"] ?: n.attrs["title"] ?: "" }
                    if (text.isBlank() && n.allByTag("img").none { it.attrs["alt"]?.isNotBlank() == true }) linkNoText++
                }
                "button" -> {
                    val text = n.allText.ifBlank { n.attrs["aria-label"] ?: n.attrs["title"] ?: "" }
                    if (text.isBlank()) buttonNoText++
                }
                "input" -> {
                    val type = n.attrs["type"]?.lowercase()
                    if (type != null && type != "hidden" && type != "submit" && type != "button") {
                        val id = n.attrs["id"]
                        val labeled = n.attrs["aria-label"] != null || (id != null && id in labels)
                        if (!labeled) inputNoLabel++
                    }
                }
                "iframe" -> if (n.attrs["title"].isNullOrBlank()) iframeNoTitle++
                "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    val level = n.tag[1].digitToInt()
                    if (prevHeading != 0 && level > prevHeading + 1) headingSkip++
                    prevHeading = level
                }
                else -> Unit
            }
            // div/span 伪按钮（无键盘可达性）
            if ((n.tag == "div" || n.tag == "span") && (n.attrs.containsKey("onclick") || n.classes.any { it.startsWith("btn") })) {
                if (n.attrs["role"] != "button" && n.attrs["tabindex"] == null && out.count { it.category == "a11y" } < 12) {
                    out.add(LayoutIssue.warn("a11y", "div/span 充当按钮（无键盘可达性）—— 换 <button> 或补 role=button+tabindex", doc.lineOf(n).toString()))
                }
            }
        }
        if (imgNoAlt > 0) out.add(LayoutIssue.error("a11y", "$imgNoAlt 个 <img> 缺少 alt（屏幕阅读器无法朗读）"))
        if (linkNoText > 0) out.add(LayoutIssue.error("a11y", "$linkNoText 个链接无可读文本（空链接不可操作）"))
        if (buttonNoText > 0) out.add(LayoutIssue.error("a11y", "$buttonNoText 个按钮无可读文本"))
        if (inputNoLabel > 0) out.add(LayoutIssue.error("a11y", "$inputNoLabel 个输入框无关联 label（aria-label 或 <label for>）"))
        if (iframeNoTitle > 0) out.add(LayoutIssue.warn("a11y", "$iframeNoTitle 个 iframe 无 title"))
        if (headingSkip > 0) out.add(LayoutIssue.warn("a11y", "标题层级跳跃 $headingSkip 处（如 h2 直接到 h4）"))
    }

    // ═══ 问题收集：代码质量（布局相关） ═══

    private fun collectQualityIssues(doc: HtmlDocument, out: MutableList<LayoutIssue>) {
        // 重复 id
        val seen = HashMap<String, Int>()
        val dup = LinkedHashMap<String, Int>()
        doc.walk { n, _ ->
            if (n.tag.startsWith("#")) return@walk
            val id = n.attrs["id"] ?: return@walk
            val c = seen.getOrDefault(id, 0) + 1
            seen[id] = c
            if (c == 2) dup[id] = 2
        }
        if (dup.isNotEmpty()) {
            out.add(LayoutIssue.error("quality", "重复 id：${dup.keys.take(6).joinToString(", ")}（CSS/JS 定位会命中第一个，行为漂移源）"))
        }
        // !important / 内联样式密度
        val styleText = buildString {
            doc.allByTag("style").forEach { append(it.text).append('\n') }
        }
        val importantInStyle = styleText.split("!important").size - 1
        var inlineImportant = 0
        var inlineStyleHeavy = 0
        doc.walk { n, _ ->
            if (n.tag.startsWith("#")) return@walk
            if (n.attrs.containsKey("style") && (n.attrs["style"]?.length ?: 0) > 120) inlineStyleHeavy++
            n.style.values.forEach { v -> if (v.contains("!important")) inlineImportant++ }
        }
        val totalImportant = importantInStyle + inlineImportant
        if (totalImportant > 3) {
            out.add(LayoutIssue.info("quality", "!important 出现 $totalImportant 次（内联 ${inlineImportant} 次 / 样式块 ${importantInStyle} 次 —— 特异性战争信号）"))
        }
        if (inlineStyleHeavy > 0) {
            out.add(LayoutIssue.info("quality", "$inlineStyleHeavy 个元素内联样式超长（>120 字符）—— 建议移入 <style> 或类"))
        }
        // <center> / align 属性 / br 间隔
        var legacyCenter = 0
        var brCount = 0
        doc.walk { n, _ ->
            when (n.tag) {
                "center" -> legacyCenter++
                "br" -> brCount++
                "font" -> legacyCenter++
                else -> Unit
            }
            if (n.attrs.containsKey("align") || n.attrs.containsKey("bgcolor")) legacyCenter++
        }
        if (legacyCenter > 0) out.add(LayoutIssue.warn("quality", "使用过时排版设施（center/font/align 属性）$legacyCenter 处 —— 换 CSS"))
        if (brCount > 6) out.add(LayoutIssue.info("quality", "<br> 用了 $brCount 次 —— 连续 br 多为用换行凑间距，应该用 margin"))
        // script 阻塞
        doc.allByTag("script").forEach { s ->
            val src = s.attrs["src"]
            val inHead = s.parent?.tag == "head"
            val isModule = s.attrs["type"]?.lowercase() == "module"
            val async = s.attrs.containsKey("async") || s.attrs.containsKey("defer")
            if (src != null && inHead && !async && !isModule && out.count { it.category == "perf" } < 4) {
                out.add(LayoutIssue.info("perf", "head 中同步外链脚本会阻塞首屏渲染 —— 加 defer/async 或 type=module", (s.attrs["src"] ?: "").take(EVIDENCE_MAX)))
            }
        }
        // css 出现在 body
        val bodyStyles = doc.allByTag("body").firstOrNull()?.allByTag("style")?.size ?: 0
        if (bodyStyles > 0) out.add(LayoutIssue.info("perf", "$bodyStyles 个 <style> 位于 body 内（FOUC 风险，规范位置是 head）"))
    }

    private fun nodeStyle(n: HtmlNode, key: String): String? = n.style[key]?.take(EVIDENCE_MAX)

    // ═══ 布局树渲染 ═══

    private fun renderTree(doc: HtmlDocument, stats: StructureStats): String {
        val sb = StringBuilder()
        var lines = 0
        var nodes = 0
        fun rec(node: HtmlNode, depth: Int) {
            if (lines >= TREE_MAX_LINES || nodes >= TREE_MAX_NODES) return
            if (node.tag == "#comment" || node.tag == "#directive" || node.tag == "#cdata") return
            nodes++
            val display = HtmlLayoutModel.displayOf(node)
            val w = HtmlLayoutModel.widthOf(node)
            val role = HtmlLayoutModel.roleOf(node)
            sb.append("  ".repeat(depth.coerceAtMost(30)))
            // 形如: div[flex] .card w:96px
            sb.append('<').append(node.tag)
            if (display != DisplayKind.BLOCK && display != DisplayKind.INLINE) {
                sb.append('/').append(display.name.lowercase())
            }
            val cls = node.attrs["class"]?.trim()
            if (!cls.isNullOrBlank()) sb.append(" .").append(cls.split(Regex("\\s+")).take(2).joinToString("."))
            if (w.isFixedPx && w.px != null) sb.append(" w:").append(w.px).append("px")
            if (w.isPercent && w.percent != null) sb.append(" w:").append(w.percent.toInt()).append('%')
            when (role) {
                LayoutRole.LANDMARK_HEADER, LayoutRole.LANDMARK_NAV, LayoutRole.LANDMARK_MAIN,
                LayoutRole.LANDMARK_ASIDE, LayoutRole.LANDMARK_FOOTER -> sb.append(" [landmark]")
                LayoutRole.HEADING_1, LayoutRole.HEADING_2, LayoutRole.HEADING_3 -> {
                    sb.append(" \"").append(node.directText.take(40)).append('"')
                }
                else -> Unit
            }
            if (node.tag == "#text") {
                sb.append(" \"").append(node.text.take(40)).append('"')
            }
            sb.append('\n')
            lines++
            node.children.forEach { rec(it, depth + 1) }
        }
        doc.root.children.forEach { rec(it, 0) }
        if (nodes >= TREE_MAX_NODES || lines >= TREE_MAX_LINES) {
            sb.append("…（布局树已按上限截断：最多 $TREE_MAX_LINES 行 / $TREE_MAX_NODES 节点，全量共 ${stats.elementCount} 元素）\n")
        }
        return sb.toString()
    }

    /**
     * 括号速览：一行看懂页面骨架。
     * 形如: body > header + nav + main[flex] > (aside[20%] + article[75%]) + footer
     */
    private fun renderBracketSummary(doc: HtmlDocument): String {
        val body = doc.firstByTag("body") ?: doc.root
        fun summarize(node: HtmlNode, depth: Int): String {
            if (depth > 4) return "${node.tag}(…)"
            val display = HtmlLayoutModel.displayOf(node)
            val role = HtmlLayoutModel.roleOf(node)
            val name = when (role) {
                LayoutRole.LANDMARK_HEADER -> "header"
                LayoutRole.LANDMARK_NAV -> "nav"
                LayoutRole.LANDMARK_MAIN -> "main"
                LayoutRole.LANDMARK_ASIDE -> "sidebar"
                LayoutRole.LANDMARK_FOOTER -> "footer"
                else -> node.tag
            }
            val tag = buildString {
                append(name)
                when (display) {
                    DisplayKind.FLEX -> append("[flex]")
                    DisplayKind.GRID -> append("[grid]")
                    DisplayKind.TABLE -> append("[table]")
                    DisplayKind.NONE -> append("[hidden]")
                    else -> Unit
                }
                val w = HtmlLayoutModel.widthOf(node)
                if (w.isFixedPx && w.px != null) append("[w${w.px}px]")
                if (w.isPercent && w.percent != null) append("[w${w.percent.toInt()}%]")
            }
            val kids = node.children.filter {
                !it.tag.startsWith("#") || it.tag == "#text"
            }.filter { it.tag != "#comment" && it.tag != "#directive" }
                .filter { it.tag != "#text" || it.text.isNotBlank() }
            if (kids.isEmpty()) return tag
            val kidSummary = kids.take(12).joinToString(" + ") { summarize(it, depth + 1) }
            val more = if (kids.size > 12) " +…(${kids.size - 12})" else ""
            return "$tag > ($kidSummary$more)" // 展开两层即止
        }
        val top = body.children.filter { it.tag != "#comment" && it.tag != "#directive" }
        if (top.isEmpty()) return "(空文档)"
        val parts = top.take(10).joinToString(" + ") { summarize(it, 1) }
        val more = if (top.size > 10) " +…(${top.size - 10})" else ""
        return "body > ($parts$more)"
    }
}
