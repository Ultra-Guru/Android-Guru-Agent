package com.apex.agent.core.engine.templates.runtime

/**
 * ═══ 模板行为运行时 —— 共享分析工具箱 · Markdown 结构扫描（PR 3）═══
 *
 * 对「原文」与「回包」两侧都可用的结构盘点：标题层级、列表、围栏代码块、
 * 表格、引用块、链接、内联代码与占位符清单。翻译 / 总结 / 文档行为用它
 * 做格式保真校验（原文结构 vs 译文结构差集），其余行为用它识别回包形态。
 *
 * 纪律：只做行级/词级扫描（无 AST），任何行都归入且只归入一个主结构类。
 */

/** 单个 Markdown 标题。 */
data class TemplateMarkdownHeading(
    val level: Int,
    val text: String,
    val lineIndex: Int
)

/** 单个列表条目（无序/有序统一形态）。 */
data class TemplateMarkdownListItem(
    val marker: String,
    val ordered: Boolean,
    val text: String,
    val lineIndex: Int
)

/** 围栏代码块。 */
data class TemplateMarkdownCodeFence(
    val language: String,
    val lineCount: Int,
    val startLineIndex: Int
)

/** 占位符形态（保真校验的对象）。 */
data class TemplatePlaceholderMark(
    val raw: String,
    val kind: Kind,
    val count: Int
) {
    enum class Kind(val label: String) {
        MUSTACHE("双花括号"),
        PERCENT("百分号"),
        BRACE_INDEX("花括号序号"),
        DOLLAR_BRACE("美元花括号"),
        ENV_STYLE("环境变量")
    }
}

/**
 * Markdown 结构盘点结果。
 *
 * @param headings 标题（文档顺序）
 * @param listItems 列表条目（文档顺序）
 * @param codeFences 围栏代码块
 * @param tableRowCount 表格行（含分隔行）计数
 * @param blockquoteLines 引用块行计数
 * @param linkCount 链接计数
 * @param inlineCodeSpans 内联代码计数
 * @param boldSpans 加粗段计数
 * @param placeholders 占位符清单（去重 + 出现次数）
 * @param blankLineCount 空行数
 */
data class TemplateMarkdownScan(
    val headings: List<TemplateMarkdownHeading>,
    val listItems: List<TemplateMarkdownListItem>,
    val codeFences: List<TemplateMarkdownCodeFence>,
    val tableRowCount: Int,
    val blockquoteLines: Int,
    val linkCount: Int,
    val inlineCodeSpans: Int,
    val boldSpans: Int,
    val placeholders: List<TemplatePlaceholderMark>,
    val blankLineCount: Int
) {
    /** 是否含任何 Markdown 结构信号。 */
    val hasStructure: Boolean
        get() = headings.isNotEmpty() || listItems.isNotEmpty() ||
            codeFences.isNotEmpty() || tableRowCount > 0

    /** 结构签名（保真比对：两侧签名的差集 = 丢失/多出的结构）。 */
    fun structureSignature(): String = buildString {
        headings.forEach { append("H${it.level};") }
        append("L${listItems.size};")
        append("F${codeFences.size};")
        append("T$tableRowCount;")
        append("Q$blockquoteLines;")
    }

    companion object {
        fun empty(): TemplateMarkdownScan = TemplateMarkdownScan(
            headings = emptyList(), listItems = emptyList(), codeFences = emptyList(),
            tableRowCount = 0, blockquoteLines = 0, linkCount = 0, inlineCodeSpans = 0,
            boldSpans = 0, placeholders = emptyList(), blankLineCount = 0
        )
    }
}

/** 保真校验差异报告。 */
data class TemplateFormatFidelityReport(
    val missingHeadings: List<String>,
    val extraHeadings: List<String>,
    val listItemCountDelta: Int,
    val missingPlaceholders: List<String>,
    val extraPlaceholders: List<String>,
    val codeFenceDelta: Int
) {
    /** 是否完全保真（翻译类契约的目标态）。 */
    val isFaithful: Boolean
        get() = missingHeadings.isEmpty() && extraHeadings.isEmpty() &&
            listItemCountDelta == 0 && missingPlaceholders.isEmpty() &&
            extraPlaceholders.isEmpty() && codeFenceDelta == 0

    /** 人读差异说明（喂给修复/报告）。 */
    fun describe(): List<String> = buildList {
        if (missingHeadings.isNotEmpty()) add("丢失标题：${missingHeadings.joinToString("、")}")
        if (extraHeadings.isNotEmpty()) add("多出标题：${extraHeadings.joinToString("、")}")
        if (listItemCountDelta != 0) {
            add(if (listItemCountDelta > 0) "列表条目多出 ${abs(listItemCountDelta)} 项" else "列表条目丢失 ${abs(listItemCountDelta)} 项")
        }
        if (missingPlaceholders.isNotEmpty()) add("占位符丢失：${missingPlaceholders.joinToString("、")}")
        if (extraPlaceholders.isNotEmpty()) add("占位符多出：${extraPlaceholders.joinToString("、")}")
        if (codeFenceDelta != 0) {
            add(if (codeFenceDelta > 0) "代码块多出 $codeFenceDelta 个" else "代码块丢失 ${abs(codeFenceDelta)} 个")
        }
    }

    private fun abs(v: Int): Int = if (v < 0) -v else v
}

/**
 * Markdown 结构扫描器。
 */
object TemplateMarkdownScanner {

    private val HEADING = Regex("""^(#{1,6})\s+(.+?)\s*#*\s*$""")
    private val LIST_ITEM = Regex("""^(\s*)([-*+]|\d{1,3}[.)]|[①②③④⑤⑥⑦⑧⑨⑩])\s+(.+)$""")
    private val CODE_FENCE = Regex("""^```(\w*)\s*$""")
    private val TABLE_ROW = Regex("""^\|.*\|\s*$""")
    private val TABLE_SEPARATOR = Regex("""^\|[\s:|-]+\|\s*$""")
    private val BLOCKQUOTE = Regex("""^>\s?.*$""")
    private val LINK = Regex("""\[[^\]]+\]\([^)]+\)""")
    private val INLINE_CODE = Regex("""`[^`\n]+`""")
    private val BOLD = Regex("""\*\*[^*\n]+\*\*""")

    private val PLACEHOLDER_PATTERNS = listOf(
        """\{\{\s*[a-zA-Z][a-zA-Z0-9_]*\s*(\|\s*[^{}]+\s*)?\}\}""" to TemplatePlaceholderMark.Kind.MUSTACHE,
        """%[sdfox@]""" to TemplatePlaceholderMark.Kind.PERCENT,
        """\{\d+\}""" to TemplatePlaceholderMark.Kind.BRACE_INDEX,
        // 美元占位符用普通转义串：raw 串里 `\${` 会触发模板插值
        "\\$\\{[a-zA-Z][a-zA-Z0-9_]*\\}" to TemplatePlaceholderMark.Kind.DOLLAR_BRACE,
        "\\$\\d+" to TemplatePlaceholderMark.Kind.ENV_STYLE
    )

    /** 全量扫描。 */
    fun scan(text: String): TemplateMarkdownScan {
        if (text.isBlank()) return TemplateMarkdownScan.empty()
        val lines = text.lines()
        val headings = mutableListOf<TemplateMarkdownHeading>()
        val listItems = mutableListOf<TemplateMarkdownListItem>()
        val codeFences = mutableListOf<TemplateMarkdownCodeFence>()
        var tableRows = 0
        var blockquotes = 0
        var blankLines = 0

        var insideFence = false
        var fenceLanguage = ""
        var fenceStart = 0
        var fenceLineCount = 0
        for ((index, raw) in lines.withIndex()) {
            val line = raw.trimEnd()
            val fenceMatch = CODE_FENCE.find(line.trim())
            if (fenceMatch != null) {
                if (!insideFence) {
                    insideFence = true
                    fenceLanguage = fenceMatch.groupValues[1].lowercase()
                    fenceStart = index
                    fenceLineCount = 0
                } else {
                    codeFences.add(TemplateMarkdownCodeFence(fenceLanguage, fenceLineCount, fenceStart))
                    insideFence = false
                }
                continue
            }
            if (insideFence) {
                fenceLineCount++
                continue
            }
            if (line.isBlank()) {
                blankLines++
                continue
            }
            val headingMatch = HEADING.find(line)
            if (headingMatch != null) {
                headings.add(
                    TemplateMarkdownHeading(
                        headingMatch.groupValues[1].length,
                        headingMatch.groupValues[2].trim(),
                        index
                    )
                )
                continue
            }
            val listMatch = LIST_ITEM.find(line)
            if (listMatch != null) {
                val marker = listMatch.groupValues[2]
                listItems.add(
                    TemplateMarkdownListItem(
                        marker = marker,
                        ordered = marker.first().isDigit() ||
                            marker in setOf("①", "②", "③", "④", "⑤", "⑥", "⑦", "⑧", "⑨", "⑩"),
                        text = listMatch.groupValues[3].trim(),
                        lineIndex = index
                    )
                )
                continue
            }
            if (TABLE_ROW.containsMatchIn(line)) {
                tableRows++
                continue
            }
            if (BLOCKQUOTE.matches(line)) {
                blockquotes++
            }
        }
        // 未闭合围栏兜底（防御式：按已有行数收尾）
        if (insideFence) {
            codeFences.add(TemplateMarkdownCodeFence(fenceLanguage, fenceLineCount, fenceStart))
        }

        return TemplateMarkdownScan(
            headings = headings,
            listItems = listItems,
            codeFences = codeFences,
            tableRowCount = tableRows,
            blockquoteLines = blockquotes,
            linkCount = LINK.findAll(text).count(),
            inlineCodeSpans = INLINE_CODE.findAll(text).count(),
            boldSpans = BOLD.findAll(text).count(),
            placeholders = inventoryPlaceholders(text),
            blankLineCount = blankLines
        )
    }

    /** 占位符清单（去重保序 + 次数）。 */
    fun inventoryPlaceholders(text: String): List<TemplatePlaceholderMark> {
        if (text.isBlank()) return emptyList()
        val counts = LinkedHashMap<String, Int>()
        val kinds = HashMap<String, TemplatePlaceholderMark.Kind>()
        for ((pattern, kind) in PLACEHOLDER_PATTERNS) {
            for (match in Regex(pattern).findAll(text)) {
                val raw = match.value
                counts[raw] = (counts[raw] ?: 0) + 1
                kinds[raw] = kind
            }
        }
        return counts.entries.map { (raw, count) ->
            TemplatePlaceholderMark(raw, kinds[raw] ?: TemplatePlaceholderMark.Kind.MUSTACHE, count)
        }
    }

    /**
     * 结构保真比对（翻译行为主消费）：原文结构 vs 输出结构。
     *
     * @param source 原文扫描
     * @param output 译文/输出扫描
     */
    fun compareFidelity(
        source: TemplateMarkdownScan,
        output: TemplateMarkdownScan
    ): TemplateFormatFidelityReport {
        val sourceHeadings = source.headings.map { TemplateOutputContract.normalizeHeader(it.text) }.filter { it.isNotEmpty() }
        val outputHeadings = output.headings.map { TemplateOutputContract.normalizeHeader(it.text) }.filter { it.isNotEmpty() }
        val missing = sourceHeadings.filter { it !in outputHeadings }
        val extra = outputHeadings.filter { it !in sourceHeadings }
        val sourcePlaceholders = source.placeholders.map { it.raw }
        val outputPlaceholders = output.placeholders.map { it.raw }
        return TemplateFormatFidelityReport(
            missingHeadings = missing,
            extraHeadings = extra,
            listItemCountDelta = output.listItems.size - source.listItems.size,
            missingPlaceholders = sourcePlaceholders.filter { it !in outputPlaceholders },
            extraPlaceholders = outputPlaceholders.filter { it !in sourcePlaceholders },
            codeFenceDelta = output.codeFences.size - source.codeFences.size
        )
    }

    /** 表格分隔行判定（渲染对齐用）。 */
    fun isTableSeparator(line: String): Boolean = TABLE_SEPARATOR.matches(line.trim())
}
