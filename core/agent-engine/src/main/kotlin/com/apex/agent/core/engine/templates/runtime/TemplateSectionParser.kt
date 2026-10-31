package com.apex.agent.core.engine.templates.runtime

/**
 * ═══ 模板行为运行时 —— 共享分析工具箱 · 章节解析与归一（PR 3）═══
 *
 * 回包解析的公共机械：把大模型回包切成「前导寒暄 / 章节（标题+正文+列表）/
 * 尾随废话」三段，并按 [TemplateOutputContract] 逐条比对产出违规清单。
 * REPAIR 阶段的确定性修补（剥寒暄 / 补章节壳 / 列表编号归一）也住在这里
 * —— 十个行为共享同一份实现，行为侧只声明契约。
 */

/** 解析出的文档骨架（VALIDATE 阶段的中间产物）。 */
data class TemplateDocumentSkeleton(
    /** 首个标题之前的正文行（寒暄候选区）。 */
    val leadingLines: List<String>,
    /** 章节（文档顺序；契约外章节 requirement = null）。 */
    val sections: List<TemplateParsedSection>
) {
    /** 全部标题行（含原始文本）。 */
    val allHeaders: List<String> get() = sections.map { it.headerText }
}

/**
 * 章节解析器 + 契约校验器（公共机械）。
 */
object TemplateSectionParser {

    /** Markdown 标题行（# 或 **加粗独立行** 或 「一、」「1.」编号标题）。 */
    private val HEADER_LINE = Regex("""^(#{1,6})\s+(.+?)\s*#*\s*$""")
    private val BOLD_HEADER = Regex("""^\*\*(.+?)\*\*[：:]?\s*$""")
    private val CN_NUMBERED_HEADER = Regex("""^[一二三四五六七八九十]+[、.]\s*(.+)$""")
    private val DIGIT_HEADER = Regex("""^(\d{1,2})[、.]\s*([^.。]{2,24})$""")

    /** 列表条目（与 MarkdownScanner 同源形态，外加全角破折号）。 */
    private val LIST_LINE = Regex("""^(\s*)([-*+•]|\d{1,3}[.)]|[①②③④⑤⑥⑦⑧⑨⑩])\s+(.+)$""")

    /** 寒暄前导词（回包常见的口水话开头）。 */
    private val PREAMBLE_MARKERS = listOf(
        "好的", "明白了", "收到", "以下是", "下面是", "这是", "当然", "没问题",
        "Sure", "Here is", "Here's", "OK", "Okay", "Certainly", "As requested"
    )

    /** 尾随废话标记。 */
    private val EPILOGUE_MARKERS = listOf(
        "希望", "祝你", "如有问题", "如果还有", "欢迎", "需要我", "请随时",
        "Hope this helps", "Let me know", "Feel free", "如果你还有"
    )

    // ═══ 解析 ═══

    /**
     * 把回包切成骨架：前导区 / 章节。尾随废话并入末章节正文，由
     * [isEpilogueLine] 在校验时识别（不单独切区 —— 多段落尾注切区会误伤
     * 正常正文）。
     *
     * 标题识别四通道（任一命中即视为章节边界）：
     * 1. Markdown `## 标题`；
     * 2. `**加粗独立行**`（LLM 爱用的章节形态）；
     * 3. 「一、二、」中文序号标题；
     * 4. `1.` 数字编号标题（限短标题，防把列表当标题 —— 长度 ≤24 且无句读）。
     *
     * @param contract 传入时章节按标题绑定契约要求（requirement 非空 =
     *   契约内章节）；null = 纯骨架（全部章节 requirement = null）
     */
    fun parse(
        raw: String,
        contract: TemplateOutputContract? = null
    ): TemplateDocumentSkeleton {
        if (raw.isBlank()) return TemplateDocumentSkeleton(emptyList(), emptyList())
        val lines = raw.lines()
        val leading = mutableListOf<String>()
        val sections = mutableListOf<TemplateParsedSection>()
        var currentHeader: String? = null
        var currentRequirement: TemplateSectionRequirement? = null
        var currentBody = mutableListOf<String>()

        fun flush() {
            val header = currentHeader ?: return
            sections.add(
                TemplateParsedSection(
                    requirement = currentRequirement,
                    headerText = header,
                    bodyLines = currentBody.filter { it.isNotBlank() },
                    listItems = currentBody.filter { LIST_LINE.matches(it.trim()) }
                        .map { LIST_LINE.find(it.trim())!!.groupValues[3].trim() }
                )
            )
            currentBody = mutableListOf()
        }

        for (rawLine in lines) {
            val line = rawLine.trimEnd()
            val headerCandidate = extractHeader(line)
            if (headerCandidate != null) {
                flush()
                currentHeader = headerCandidate
                currentRequirement = contract?.requiredSections
                    ?.firstOrNull { it.matchesHeader(headerCandidate) }
                continue
            }
            if (currentHeader == null) {
                leading.add(line)
            } else {
                currentBody.add(line)
            }
        }
        flush()
        return TemplateDocumentSkeleton(leading, sections)
    }

    /** 标题行抽取（返回归一化前的原文标题文本；非标题返回 null）。 */
    private fun extractHeader(line: String): String? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        HEADER_LINE.find(trimmed)?.let { return it.groupValues[2].trim() }
        BOLD_HEADER.find(trimmed)?.let { return it.groupValues[1].trim() }
        CN_NUMBERED_HEADER.find(trimmed)?.let { return it.groupValues[1].trim() }
        val digitMatch = DIGIT_HEADER.find(trimmed)
        if (digitMatch != null && LIST_LINE.find(trimmed) == null) {
            return digitMatch.groupValues[2].trim()
        }
        return null
    }

    // ═══ 契约校验 ═══

    /**
     * 按契约校验骨架（VALIDATE 阶段核心机械）。
     *
     * @param skeleton 解析骨架
     * @param contract 输出契约
     * @param strict true = 全部违规都计（默认）；false = 只计不可修复级
     */
    fun validate(
        skeleton: TemplateDocumentSkeleton,
        contract: TemplateOutputContract,
        strict: Boolean = true
    ): TemplateValidationReport {
        val violations = mutableListOf<TemplateContractViolation>()
        val matched = mutableSetOf<String>()

        // 1) 必需章节存在性 + 列表长度区间（骨架未绑定时现查契约标题）
        for (requirement in contract.requiredSections) {
            val section = skeleton.sections.firstOrNull { requirement.matchesHeader(it.headerText) }
                ?: skeleton.sections.firstOrNull { it.requirement == requirement }
            if (section == null) {
                if (requirement.required) {
                    violations.add(
                        TemplateContractViolation(
                            kind = TemplateContractViolationKind.MISSING_SECTION,
                            section = requirement.title,
                            detail = "缺少必需章节「${requirement.title}」"
                        )
                    )
                }
                continue
            }
            matched.add(requirement.title)
            if (section.isEmptyBody) {
                violations.add(
                    TemplateContractViolation(
                        kind = TemplateContractViolationKind.SECTION_EMPTY,
                        section = requirement.title,
                        detail = "章节「${requirement.title}」为空壳"
                    )
                )
            }
            val range = requirement.listItemCountRange
            if (range != null) {
                val count = section.listItems.size
                if (count < range.first) {
                    violations.add(
                        TemplateContractViolation(
                            kind = TemplateContractViolationKind.LIST_TOO_SHORT,
                            section = requirement.title,
                            detail = "章节「${requirement.title}」列表仅 $count 条（契约 ≥${range.first}）",
                            evidence = section.listItems.joinToString(" / ").take(80)
                        )
                    )
                } else if (count > range.last) {
                    violations.add(
                        TemplateContractViolation(
                            kind = TemplateContractViolationKind.LIST_TOO_LONG,
                            section = requirement.title,
                            detail = "章节「${requirement.title}」列表 $count 条（契约 ≤${range.last}）"
                        )
                    )
                }
            }
        }

        // 2) 契约外章节 → 归入补充区（不违规，渲染排序用）
        val orphans = skeleton.sections
            .filter { section -> contract.requiredSections.none { it.matchesHeader(section.headerText) } }
            .map { it.toText() }

        // 3) 寒暄前导 / 尾随废话
        val preambleLines = skeleton.leadingLines.filter { isPreambleLine(it) }
        if (preambleLines.isNotEmpty() && contract.allowedPreambles.isEmpty()) {
            violations.add(
                TemplateContractViolation(
                    kind = TemplateContractViolationKind.PREAMBLE,
                    detail = "回包前 ${preambleLines.size} 行为寒暄前导",
                    evidence = preambleLines.joinToString(" ⏎ ").take(80)
                )
            )
        }
        val epilogueCandidate = skeleton.sections.lastOrNull()?.bodyLines?.takeLast(contract.maxNoiseLines)
        if (epilogueCandidate != null) {
            val epilogue = epilogueCandidate.filter { isEpilogueLine(it) }
            if (epilogue.isNotEmpty()) {
                violations.add(
                    TemplateContractViolation(
                        kind = TemplateContractViolationKind.EPILOGUE,
                        detail = "回包尾部有 ${epilogue.size} 行废话",
                        evidence = epilogue.joinToString(" ⏎ ").take(80)
                    )
                )
            }
        }

        // 4) 占位符泄漏（正文里出现 {{var}} / %s 等）
        val leak = TemplateMarkdownScanner.inventoryPlaceholders(skeleton.sections.joinToString("\n") { it.toText() })
        if (leak.isNotEmpty() && !contract.payloadOnly) {
            violations.add(
                TemplateContractViolation(
                    kind = TemplateContractViolationKind.PLACEHOLDER_LEAK,
                    detail = "回包泄漏占位符 ${leak.size} 处",
                    evidence = leak.joinToString("、") { it.raw }.take(80)
                )
            )
        }

        // 5) 空输出（无章节无正文）
        if (skeleton.sections.isEmpty() && skeleton.leadingLines.none { it.isNotBlank() }) {
            violations.add(
                TemplateContractViolation(
                    kind = TemplateContractViolationKind.EMPTY_OUTPUT,
                    detail = "回包没有任何可展示内容"
                )
            )
        }

        // 6) 结论先行（总结类契约）
        if (contract.requiresVerdictStyle && matched.isNotEmpty()) {
            val firstSection = skeleton.sections.firstOrNull()
            val firstIsVerdict = contract.requiredSections.firstOrNull()
                ?.let { firstSection != null && it.matchesHeader(firstSection.headerText) } == true
            if (!firstIsVerdict) {
                violations.add(
                    TemplateContractViolation(
                        kind = TemplateContractViolationKind.MISSING_VERDICT,
                        detail = "首个章节不是结论段（契约要求结论先行）",
                        evidence = firstSection?.headerText.orEmpty().take(40)
                    )
                )
            }
        }

        val effective = if (strict) violations else violations.filter { !it.repairable }
        return TemplateValidationReport(
            violations = effective,
            parsedSections = skeleton.sections,
            orphans = orphans
        )
    }

    /** 寒暄前导判定。 */
    fun isPreambleLine(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return false
        // 只有寒暄才算：像「以下是 XXX」整行寒暄；带实质内容的行不算
        if (trimmed.length > PREAMBLE_MAX_LENGTH) return false
        return PREAMBLE_MARKERS.any { trimmed.startsWith(it) } && !trimmed.contains("：")
    }

    /** 尾随废话判定。 */
    fun isEpilogueLine(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.length > EPILOGUE_MAX_LENGTH) return false
        return EPILOGUE_MARKERS.any { trimmed.startsWith(it) }
    }

    // ═══ 确定性修复 ═══

    /**
     * 公共修复机械（REPAIR 阶段）：按违规清单做确定性修补。
     * 返回修复后的文本 + 修复说明（行为侧可叠加自己的专项修复）。
     *
     * 纪律：**只删/只补，绝不重排原文** —— 剥寒暄按行精确移除（保住
     * 标题行原样，`##` 等标记不丢），补章节壳只追加尾部。
     */
    fun repair(raw: String, report: TemplateValidationReport): Pair<String, List<String>> {
        var text = raw
        val notes = mutableListOf<String>()

        if (report.violations.any { it.kind == TemplateContractViolationKind.PREAMBLE }) {
            val lines = text.lines()
            val firstHeaderIndex = lines.indexOfFirst { extractHeader(it) != null }
                .let { if (it < 0) lines.size else it }
            // 只在「首个标题之前」的区域内剥寒暄行（标题之后的正文段落
            // 里以「以下是」开头的行可能是合法内容，不动）
            val toRemove = (0 until firstHeaderIndex).filter { isPreambleLine(lines[it]) }
            if (toRemove.isNotEmpty()) {
                val kept = lines.filterIndexed { index, _ -> index !in toRemove }
                text = kept.joinToString("\n").trimStart('\n')
                notes.add("剥离寒暄前导 ${toRemove.size} 行")
            }
        }

        if (report.violations.any { it.kind == TemplateContractViolationKind.EPILOGUE }) {
            val lines = text.lines().toMutableList()
            var removed = 0
            while (lines.isNotEmpty() && isEpilogueLine(lines.last())) {
                lines.removeAt(lines.size - 1)
                removed++
            }
            if (removed > 0) {
                // 尾部空行一并清理
                while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.size - 1)
                text = lines.joinToString("\n")
                notes.add("剥离尾随废话 $removed 行")
            }
        }

        if (report.violations.any { it.kind == TemplateContractViolationKind.MISSING_SECTION }) {
            val missing = report.violations
                .filter { it.kind == TemplateContractViolationKind.MISSING_SECTION }
                .mapNotNull { it.section }
            if (missing.isNotEmpty()) {
                val appendix = missing.joinToString("\n\n") { "## $it\n\n（本章节内容缺失，请基于上文追问补全。）" }
                text = text.trimEnd() + "\n\n" + appendix
                notes.add("补齐缺失章节壳 ${missing.size} 个（${missing.joinToString("、")}）")
            }
        }

        if (report.violations.any { it.kind == TemplateContractViolationKind.LIST_TOO_LONG }) {
            // 超长列表截断由行为侧处理（知道契约上限）；公共层不做盲截
        }
        return text to notes
    }

    /** 列表编号归一（渲染阶段用：统一为「1. 2. 3.」有序或「-」无序）。 */
    fun normalizeListNumbering(lines: List<String>, ordered: Boolean): List<String> {
        var counter = 0
        return lines.map { line ->
            val match = LIST_LINE.find(line.trim())
            if (match == null) {
                line
            } else {
                counter++
                val text = match.groupValues[3].trim()
                if (ordered) "$counter. $text" else "- $text"
            }
        }
    }

    const val PREAMBLE_MAX_LENGTH = 60
    const val EPILOGUE_MAX_LENGTH = 80
}
