package com.apex.agent.core.codetools.html

/**
 * ═══ 轻量 HTML 解析器（零第三方依赖）═══
 *
 * 为 HTML 布局分析引擎（[HtmlLayoutAnalyzer]）提供 DOM 基础设施：把
 * Agent 生成的 HTML 单页（报告 / 原型 / 可视化）解析为 [HtmlNode] 树，
 * 供下游做 display 推断、盒模型估算与布局问题检测。
 *
 * 为什么不用 Jsoup / 第三方解析库：core/code-tools 是纯 JVM 模块，
 * 静态分析 CI 用 kotlinc 裸编译（classpath 只有 coroutines + serialization），
 * 引第三方解析库会破坏镜像编译；HTML 子集（生成物页面 99% 良构）用
 * 状态机手写解析完全可控，还能带上防御性上限（见 [GUARDS]）。
 *
 * 解析范围（对「LLM 生成 HTML」这一目标场景刻意取舍）：
 * - 标签 / 属性（单双引号 / 无引号 / 布尔属性），标签名统一小写；
 * - 文本节点 + 命名实体 + 数字实体解码（amp/lt/gt/quot/apos/nbsp/&#NNN/&#xHH）；
 * - 注释、DOCTYPE、CDATA（原样保留为节点，文本扫描用）；
 * - script / style / textarea 原文捕获（不递归解析内部标签）；
 * - void 元素（br/img/input/meta/link/hr…）不等待闭合标签；
 * - 隐式闭合规则（li 关 li、p 关 p、td/th 互关、tr 关 tr、option 关 option、
 *   dt/dd 互关、thead/tbody 互关）——真实浏览器同款容错；
 * - 未闭合标签：文档结束时统一收栈（容错而非报错——分析器要的是树，不是错误）。
 *
 * 防御性上限（[GUARDS]，超限即停并置 truncated 原因）：
 * 生成物页面是本地文件（≤16MB，调用方已保证），但手写状态机仍设三重
 * 上限防病态输入：最大节点数 / 最大深度 / 单文本块长度。超限保证
 * 「有限时间 + 有限内存」返回部分树，而不是把 Agent 卡死在解析上。
 */
object LightweightHtmlParser {

    /** 防御性上限。超限截断并在 HtmlDocument.truncatedReason 留痕。 */
    object GUARDS {
        const val MAX_NODES = 60_000
        const val MAX_DEPTH = 200
        const val MAX_TEXT_BLOCK = 1_000_000
        const val MAX_INPUT_CHARS = 4_000_000
    }

    /** void 元素：无闭合标签（HTML 标准）。 */
    private val VOID_ELEMENTS = setOf(
        "area", "base", "br", "col", "embed", "hr", "img", "input",
        "link", "meta", "param", "source", "track", "wbr"
    )

    /**
     * 隐式闭合规则：见到 key 标签的开标签时，先弹出到 value 集合中的
     * 未闭合祖先（含自身）。例：新的 <li> 到来 → 弹出未闭合的上一个 <li>。
     * 只列「同层互斥」的常见组；其余交给文档收栈兜底。
     */
    private val IMPLICIT_CLOSE: Map<String, Set<String>> = mapOf(
        "li" to setOf("li"),
        "p" to setOf("p"),
        "option" to setOf("option"),
        "optgroup" to setOf("optgroup", "option"),
        "td" to setOf("td", "th"),
        "th" to setOf("td", "th"),
        "tr" to setOf("tr", "td", "th"),
        "dt" to setOf("dt", "dd"),
        "dd" to setOf("dt", "dd"),
        "thead" to setOf("thead", "tbody", "tr", "td", "th"),
        "tbody" to setOf("thead", "tbody", "tr", "td", "th"),
        "tfoot" to setOf("thead", "tbody", "tr", "td", "th")
    )

    /** 原文捕获元素：内部不递归解析（脚本/样式内容在词法上不是标记）。 */
    private val RAW_TEXT_ELEMENTS = setOf("script", "style", "textarea")

    /** 命名实体（够用集合——生成物页面里占绝大多数的几种）。 */
    private val NAMED_ENTITIES = mapOf(
        "amp" to '&', "lt" to '<', "gt" to '>', "quot" to '"', "apos" to '\'',
        "nbsp" to ' ', "copy" to '©', "reg" to '®', "trade" to '™',
        "hellip" to '…', "mdash" to '—', "ndash" to '–', "bull" to '•',
        "larr" to '←', "rarr" to '→', "uarr" to '↑', "darr" to '↓',
        "times" to '×', "divide" to '÷', "plusmn" to '±', "deg" to '°'
    )

    /**
     * 解析入口：HTML 源文本 → [HtmlDocument]。
     * 输入超长 / 节点超限 / 深度超限都会得到「部分树 + truncated 原因」，
     * 绝不抛出（调用方是工具执行路径，坏输入返回降级报告比炸掉好）。
     */
    fun parse(source: String): HtmlDocument {
        val src = if (source.length > GUARDS.MAX_INPUT_CHARS) {
            source.substring(0, GUARDS.MAX_INPUT_CHARS)
        } else source
        val root = HtmlNode(tag = "#root", attrs = emptyMap())
        val doc = HtmlDocument(root = root, source = src)
        var nodeCount = 0
        var truncatedReason: String? = null

        val stack = ArrayDeque<HtmlNode>().apply { addLast(root) }
        var i = 0
        val n = src.length

        while (i < n) {
            if (stack.size > GUARDS.MAX_DEPTH) {
                truncatedReason = "max depth ${GUARDS.MAX_DEPTH} exceeded"; break
            }
            if (nodeCount > GUARDS.MAX_NODES) {
                truncatedReason = "max nodes ${GUARDS.MAX_NODES} exceeded"; break
            }
            val top = stack.lastOrNull() ?: break

            val lt = src.indexOf('<', i)
            if (lt < 0) { appendText(top, src, i, n, doc); break }
            if (lt > i) appendText(top, src, i, lt, doc)

            // ── 标记开始 ──
            if (src.startsWith("<!--", lt)) {
                val end = src.indexOf("-->", lt + 4)
                val stop = if (end < 0) n else end
                val node = HtmlNode(
                    tag = "#comment",
                    attrs = emptyMap(),
                    text = src.substring(lt + 4, stop.coerceAtMost(lt + 4 + GUARDS.MAX_TEXT_BLOCK))
                )
                top.addChild(node); nodeCount++
                i = if (end < 0) n else end + 3
                continue
            }
            if (src.startsWith("<![CDATA[", lt)) {
                val end = src.indexOf("]]>", lt + 9)
                val stop = if (end < 0) n else end
                val node = HtmlNode(
                    tag = "#cdata",
                    attrs = emptyMap(),
                    text = src.substring(lt + 9, stop.coerceAtMost(lt + 9 + GUARDS.MAX_TEXT_BLOCK))
                )
                top.addChild(node); nodeCount++
                i = if (end < 0) n else end + 3
                continue
            }
            if (src.startsWith("<!", lt) || src.startsWith("<?", lt)) {
                // DOCTYPE / 处理指令：跳到最近 '>'。
                val end = src.indexOf('>', lt)
                val node = HtmlNode(
                    tag = "#directive",
                    attrs = emptyMap(),
                    text = if (end < 0) src.substring(lt) else src.substring(lt, end + 1)
                )
                top.addChild(node); nodeCount++
                i = if (end < 0) n else end + 1
                continue
            }
            if (lt + 1 < n && src[lt + 1] == '/') {
                // ── 闭合标签 ──
                val gt = src.indexOf('>', lt)
                if (gt < 0) { i = n; break }
                val name = src.substring(lt + 2, gt).trim().lowercase()
                    .takeWhile { it.isLetterOrDigit() || it == '-' }
                closeTag(stack, name)
                i = gt + 1
                continue
            }

            // ── 开标签 ──
            val tagStart = lt + 1
            if (tagStart >= n || !src[tagStart].isLetter()) {
                // '<' 后非字母（如 "a < b"）：按字面文本处理，防误吞。
                appendLiteral(top, src, lt, lt + 1, doc); i = lt + 1; continue
            }
            var j = tagStart
            while (j < n && (src[j].isLetterOrDigit() || src[j] == '-' || src[j] == '_' || src[j] == ':')) j++
            val tag = src.substring(tagStart, j).lowercase()
            val (attrs, afterAttrs, selfClose) = parseAttributes(src, j, n)
            val node = HtmlNode(tag = tag, attrs = attrs)
            node.openIndex = lt
            top.addChild(node)
            nodeCount++
            i = afterAttrs

            when {
                selfClose || tag in VOID_ELEMENTS -> Unit // 不入栈
                tag in RAW_TEXT_ELEMENTS -> {
                    // script/style/textarea：原文捕获到闭合标签。
                    val closeIdx = src.indexOf("</$tag", i, ignoreCase = true)
                    val stop = if (closeIdx < 0) n else closeIdx
                    node.text = src.substring(i, stop.coerceAtMost(i + GUARDS.MAX_TEXT_BLOCK))
                    node.closeIndex = if (closeIdx < 0) -1 else closeIdx
                    i = if (closeIdx < 0) n else {
                        val gt = src.indexOf('>', closeIdx)
                        if (gt < 0) n else gt + 1
                    }
                }
                else -> {
                    // 隐式闭合：新标签要求弹出祖先中未闭合的同层元素。
                    // 先弹后压：弹出只影响「后续兄弟」的挂载点，已 addChild
                    // 的父子关系不受影响（被弹元素仍是原父的子节点）。
                    IMPLICIT_CLOSE[tag]?.let { closers ->
                        while (stack.size > 1) {
                            val open = stack.last()
                            if (open.tag != "#root" && open.tag in closers) {
                                open.closeIndex = lt
                                stack.removeLast()
                            } else break
                        }
                    }
                    if (stack.size >= GUARDS.MAX_DEPTH) {
                        truncatedReason = "max depth ${GUARDS.MAX_DEPTH} exceeded"
                        // 仍记录该节点为叶子（树保持可用），但不入栈。
                    } else {
                        stack.addLast(node)
                    }
                }
            }
        }

        // 文档收栈：未闭合元素由 closeIndex=-1 表示「源中未显式闭合」。
        while (stack.isNotEmpty()) stack.removeLast()
        doc.truncatedReason = truncatedReason
        return doc
    }

    /** 文本节点追加（实体解码 + 上限截断；纯空白跳过）。 */
    private fun appendText(parent: HtmlNode, src: String, from: Int, to: Int, doc: HtmlDocument) {
        if (to <= from) return
        val raw = src.substring(from, to.coerceAtMost(from + GUARDS.MAX_TEXT_BLOCK))
        if (raw.isBlank()) return
        val text = decodeEntities(raw.trim())
        if (text.isEmpty()) return
        val node = HtmlNode(tag = "#text", attrs = emptyMap(), text = text)
        node.openIndex = from
        parent.addChild(node)
        doc.textNodeCount++
    }

    /** 字面 '<'（非标签）追加为文本的一部分。 */
    private fun appendLiteral(parent: HtmlNode, src: String, from: Int, to: Int, doc: HtmlDocument) {
        val last = parent.children.lastOrNull()
        if (last != null && last.tag == "#text") {
            last.text = (last.text + src.substring(from, to)).take(GUARDS.MAX_TEXT_BLOCK)
        } else {
            appendText(parent, src, from, to, doc)
        }
    }

    /**
     * 属性解析：从标签名之后扫到 '>'，返回 (属性表, 下一下标, 是否自闭合)。
     * 支持 name / name=value / name="v" / name='v'，属性名小写，值实体解码。
     */
    private fun parseAttributes(src: String, from: Int, n: Int): Triple<Map<String, String>, Int, Boolean> {
        val attrs = LinkedHashMap<String, String>()
        var i = from
        var selfClose = false
        while (i < n) {
            while (i < n && src[i].isWhitespace()) i++
            if (i >= n) break
            val c = src[i]
            if (c == '>') return Triple(attrs, i + 1, selfClose)
            if (c == '/') {
                if (i + 1 < n && src[i + 1] == '>') return Triple(attrs, i + 2, true)
                i++; continue
            }
            val nameStart = i
            while (i < n && src[i] != '=' && src[i] != '>' && !src[i].isWhitespace() && src[i] != '/') i++
            if (i == nameStart) { i++; continue }
            val name = src.substring(nameStart, i).lowercase()
            var k = i
            while (k < n && src[k].isWhitespace()) k++
            if (k < n && src[k] == '=') {
                k++
                while (k < n && src[k].isWhitespace()) k++
                if (k < n && (src[k] == '"' || src[k] == '\'')) {
                    val quote = src[k]
                    val end = src.indexOf(quote, k + 1)
                    val value = if (end < 0) src.substring(k + 1) else src.substring(k + 1, end)
                    attrs[name] = decodeEntities(value).take(8_000)
                    i = if (end < 0) n else end + 1
                } else {
                    var vEnd = k
                    while (vEnd < n && !src[vEnd].isWhitespace() && src[vEnd] != '>' && src[vEnd] != '/') vEnd++
                    attrs[name] = decodeEntities(src.substring(k, vEnd)).take(8_000)
                    i = vEnd
                }
            } else {
                attrs[name] = "" // 布尔属性（checked / disabled …）
                i = k
            }
        }
        return Triple(attrs, n, selfClose)
    }

    /**
     * 闭合标签处理：从栈顶向下找同名开标签；找到则连同中间未闭合元素
     * 一起弹出（浏览器同款容错）；不在栈上则忽略（孤立闭合标签）。
     */
    private fun closeTag(stack: ArrayDeque<HtmlNode>, name: String) {
        if (name.isEmpty()) return
        var idx = -1
        for (k in stack.indices) {
            if (stack[k].tag == name) { idx = k; break }
        }
        if (idx <= 0) return
        for (k in stack.size - 1 downTo idx) {
            stack.removeAt(k)
        }
    }

    /** 实体解码（命名 + 十进制 + 十六进制；未知实体原样保留）。 */
    fun decodeEntities(raw: String): String {
        if (!raw.contains('&')) return raw
        val sb = StringBuilder(raw.length)
        var i = 0
        val n = raw.length
        while (i < n) {
            val c = raw[i]
            if (c != '&') { sb.append(c); i++; continue }
            val semi = raw.indexOf(';', i + 1)
            if (semi < 0 || semi - i > 12) { sb.append(c); i++; continue }
            val body = raw.substring(i + 1, semi)
            val decoded: Char? = when {
                body.startsWith("#x", true) || body.startsWith("#X") ->
                    body.drop(2).toIntOrNull(16)?.let { if (it in 1..0x10FFFF) it.toChar() else null }
                body.startsWith("#") ->
                    body.drop(1).toIntOrNull()?.let { if (it in 1..0x10FFFF) it.toChar() else null }
                else -> NAMED_ENTITIES[body.lowercase()]
            }
            if (decoded != null) {
                sb.append(decoded)
                i = semi + 1
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}

/**
 * 解析产物：根节点 + 源文本 + 统计。
 * [truncatedReason] 非空表示触发了防御上限（树仍是「前缀完整」的部分树）。
 */
class HtmlDocument(
    val root: HtmlNode,
    val source: String,
    var textNodeCount: Int = 0,
    var truncatedReason: String? = null
) {
    /** 深度优先遍历（不含 root；visit(节点, 深度)）。 */
    fun walk(visit: (HtmlNode, Int) -> Unit) {
        fun rec(node: HtmlNode, depth: Int) {
            visit(node, depth)
            node.children.forEach { rec(it, depth + 1) }
        }
        root.children.forEach { rec(it, 1) }
    }

    /** 按标签名找第一个（深度优先）。 */
    fun firstByTag(tag: String): HtmlNode? {
        var found: HtmlNode? = null
        walk { n, _ -> if (found == null && n.tag == tag) found = n }
        return found
    }

    /** 按标签名找全部。 */
    fun allByTag(tag: String): List<HtmlNode> {
        val out = mutableListOf<HtmlNode>()
        walk { n, _ -> if (n.tag == tag) out.add(n) }
        return out
    }

    /** 源文本中该节点起始行的近似行号（1 基；0 = 无定位）。 */
    fun lineOf(node: HtmlNode): Int {
        if (node.openIndex < 0) return 0
        var line = 1
        val limit = node.openIndex.coerceAtMost(source.length)
        var i = 0
        while (i < limit) {
            if (source[i] == '\n') line++
            i++
        }
        return line
    }
}

/**
 * DOM 节点：tag 约定——标准小写标签名；#root 伪根；#text 文本；
 * #comment 注释；#cdata CDATA；#directive DOCTYPE/PI。
 * children 由解析器填充；class/style/classes 惰性缓存。
 */
class HtmlNode(
    val tag: String,
    val attrs: Map<String, String>,
    var text: String = "",
    var openIndex: Int = -1,
    var closeIndex: Int = -1,
    val children: MutableList<HtmlNode> = mutableListOf()
) {
    var parent: HtmlNode? = null
        internal set

    internal fun addChild(child: HtmlNode) {
        child.parent = this
        children.add(child)
    }

    /** class 列表（去空白小写）。 */
    val classes: List<String> by lazy {
        (attrs["class"] ?: "").split(Regex("\\s+")).filter { it.isNotBlank() }.map { it.lowercase() }
    }

    /** style 声明表（小写属性名 → 原值；坏段跳过）。 */
    val style: Map<String, String> by lazy { parseStyleAttr(attrs["style"] ?: "") }

    /** 元素文本（直接子文本节点拼接，无递归）。 */
    val directText: String by lazy {
        children.filter { it.tag == "#text" }.joinToString(" ") { it.text }.trim()
    }

    /** 递归全文本（用于 landmark / 正文体量判定）。 */
    val allText: String by lazy {
        buildString {
            fun rec(n: HtmlNode) {
                if (n.tag == "#text") append(n.text).append(' ')
                n.children.forEach { rec(it) }
            }
            children.forEach { rec(it) }
        }.trim()
    }

    /** 是否视觉隐藏（display:none / visibility:hidden / hidden 属性 / aria-hidden / 常见隐藏类）。 */
    val isHidden: Boolean by lazy {
        style["display"] == "none" ||
            style["visibility"] == "hidden" ||
            attrs.containsKey("hidden") ||
            (attrs["aria-hidden"]?.equals("true", true) ?: false) ||
            classes.any { it == "hidden" || it == "invisible" || it == "sr-only" || it == "visually-hidden" }
    }

    /** 子树内按标签名找全部（深度优先）。 */
    fun allByTag(tag: String): List<HtmlNode> {
        val out = mutableListOf<HtmlNode>()
        fun rec(n: HtmlNode) {
            if (n.tag == tag) out.add(n)
            n.children.forEach { rec(it) }
        }
        children.forEach { rec(it) }
        return out
    }

    /** 子树内第一个匹配。 */
    fun firstByTag(tag: String): HtmlNode? = allByTag(tag).firstOrNull()

    override fun toString(): String {
        val id = attrs["id"]
        val cls = attrs["class"]
        return buildString {
            append('<').append(tag)
            if (!id.isNullOrBlank()) append(" id=").append(id.take(40))
            if (!cls.isNullOrBlank()) append(" class=").append(cls.take(60))
            append('>')
        }
    }
}

/** style 属性 → 声明表（容错：坏段跳过）。 */
internal fun parseStyleAttr(styleAttr: String): Map<String, String> {
    if (styleAttr.isBlank()) return emptyMap()
    val out = LinkedHashMap<String, String>()
    for (decl in styleAttr.split(';')) {
        val colon = decl.indexOf(':')
        if (colon <= 0) continue
        val key = decl.substring(0, colon).trim().lowercase()
        val value = decl.substring(colon + 1).trim()
        if (key.isEmpty() || value.isEmpty()) continue
        out[key] = value.take(500)
    }
    return out
}
