package com.apex.agent.core.codetools.html

/**
 * ═══ 组件模式识别（HTML 布局分析第二轮 · PR3）═══
 *
 * 生成物页面里最高频的结构不是 landmark，而是**重复组件** ——
 * 卡片列表、导航项、表格行、设置行、评论条。Agent 看到的是 200 个
 * 元素的分析树；它真正需要的是「这页有 12 张结构相同的卡片，改
 * 模板一处 = 12 处全变」这个**组件级抽象**。
 *
 * 算法（有意保持朴素 —— 静态分析不做 ML）：
 * 1. 每个元素的「形状指纹」= 自身 tag + 首个 class 前缀（去掉疑似
 *    序号/状态尾缀）+ 直接子元素的 tag 序列（深度 2 骨架）；
 * 2. 同一父节点的相邻子节点序列里，指纹出现 ≥ [MIN_REPEAT] 次 →
 *    判定为一个重复组件（相邻要求防止跨区块误聚）；
 * 3. 组件种类猜测（[ComponentKind]）：ul/ol 子项 → 列表项；tr →
 *    表格行；a → 导航项；含 img/h 标签骨架 → 卡片；其余 → 重复块。
 *
 * 纪律：纯结构分析（不依赖级联视图）；指纹归一化只做 class 前缀
 * 切分（连字符/下划线/数字尾缀），不做语义猜测；每报告最多
 * [MAX_PATTERNS] 个组件、每组件证据截断。
 */
object HtmlComponentPatterns {

    /** 判定重复的最小实例数（2 个可能只是巧合排版，3 个起是模式）。 */
    const val MIN_REPEAT = 3

    /** 报告内组件条数上限。 */
    const val MAX_PATTERNS = 10

    /** 指纹骨架深度（自身 + 子 + 孙，共 3 层语义但深度 2 递归）。 */
    private const val SHAPE_DEPTH = 2

    /** class 前缀归一化长度上限。 */
    private const val CLASS_PREFIX_LEN = 24

    // ═══ 数据模型 ═══

    /** 组件种类（报告文案 + Agent 心智模型）。 */
    enum class ComponentKind(val label: String) {
        LIST_ITEM("列表项"),
        TABLE_ROW("表格行"),
        NAV_ITEM("导航项"),
        CARD("卡片"),
        REPEATED_BLOCK("重复块");

        companion object {
            /** 按父容器/骨架猜测种类。 */
            fun of(parentTag: String, shape: String): ComponentKind = when {
                parentTag == "ul" || parentTag == "ol" || parentTag == "dl" -> LIST_ITEM
                parentTag == "tbody" || parentTag == "thead" || parentTag == "table" -> TABLE_ROW
                parentTag == "nav" || shape.startsWith("a|") || shape.startsWith("a(") -> NAV_ITEM
                shape.contains("img") || shape.contains("h2") || shape.contains("h3") || shape.contains("h4") -> CARD
                else -> REPEATED_BLOCK
            }
        }
    }

    /** 一个识别到的重复组件。 */
    data class ComponentPattern(
        val kind: ComponentKind,
        val count: Int,
        val shape: String,        // 归一化指纹（tag 骨架 + class 前缀）
        val parentTag: String,
        val sampleClasses: List<String>, // 首个实例的 class（改模板的定位钩子）
        val firstLine: Int        // 首个实例的近似行号（报告证据）
    )

    // ═══ 分析入口 ═══

    /** 扫描文档，产出重复组件清单（按实例数降序）。 */
    fun analyze(doc: HtmlDocument): List<ComponentPattern> {
        val patterns = mutableListOf<ComponentPattern>()
        doc.walk { node, _ ->
            if (node.tag.startsWith("#")) return@walk
            val children = node.children.filter { !it.tag.startsWith("#") }
            if (children.size < MIN_REPEAT) return@walk
            // 同容器内按指纹分组：同父 + 同形 = 组件语义（卡片夹横幅/分隔线不打散）
            children
                .mapNotNull { child -> fingerprintOf(child)?.let { child to it } }
                .groupBy({ it.second }, { it.first })
                .forEach { (fp, instances) ->
                    if (instances.size < MIN_REPEAT) return@forEach
                    val first = instances.first()
                    patterns.add(
                        ComponentPattern(
                            kind = ComponentKind.of(node.tag, fp),
                            count = instances.size,
                            shape = fp,
                            parentTag = node.tag,
                            sampleClasses = first.classes.take(3),
                            firstLine = doc.lineOf(first)
                        )
                    )
                }
        }
        // 同形状跨容器去重（保留实例最多的）+ 数量截断
        return patterns
            .groupBy { it.shape }
            .map { (_, group) -> group.maxByOrNull { it.count }!! }
            .sortedWith(compareByDescending<ComponentPattern> { it.count }.thenBy { it.firstLine })
            .take(MAX_PATTERNS)
    }

    // ═══ 指纹 ═══

    /**
     * 元素形状指纹：`tag(class前缀)|子tag(class前缀);孙tag...`。
     * 文本节点忽略；无子元素时骨架段为空。返回 null = 元素不可指纹
     * （如空标签）。
     */
    internal fun fingerprintOf(node: HtmlNode, depth: Int = SHAPE_DEPTH): String? {
        val self = shapePart(node) ?: return null
        if (depth == 0) return self
        val kidParts = node.children
            .filter { !it.tag.startsWith("#") }
            .joinToString(";") { shapePart(it) ?: "?" }
        return if (kidParts.isEmpty()) self else "$self|$kidParts"
    }

    /** 单节点形状段：`tag(class前缀)`；class 前缀去掉数字/状态尾缀。 */
    private fun shapePart(node: HtmlNode): String? {
        if (node.tag.startsWith("#")) return null
        val prefix = classPrefix(node)
        return if (prefix == null) node.tag else "${node.tag}($prefix)"
    }

    /**
     * class 前缀归一化：取首个 class，按连字符/下划线切到首个数字段或
     * 状态词（active/selected/disabled/current）为止 —— `card-item-3`
     * → `card-item`，`nav-link active` → `nav-link`。无 class 返回 null。
     */
    internal fun classPrefix(node: HtmlNode): String? {
        val first = node.classes.firstOrNull() ?: return null
        val segments = first.split('-', '_')
        val kept = mutableListOf<String>()
        for (seg in segments) {
            if (seg.isEmpty()) break
            if (seg.all { it.isDigit() }) break
            if (seg in STATE_SUFFIXES) break
            kept.add(seg)
        }
        if (kept.isEmpty()) return null
        return kept.joinToString("-").take(CLASS_PREFIX_LEN)
    }

    /** 常见状态/序号尾缀（归一化时切断）。 */
    private val STATE_SUFFIXES = setOf(
        "active", "selected", "disabled", "current", "open", "collapsed",
        "first", "last", "even", "odd", "focus", "hover", "checked"
    )
}
