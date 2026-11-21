package com.apex.agent.core.engine.templates.runtime

import com.apex.agent.core.engine.templates.PromptTemplate
import com.apex.agent.core.engine.templates.TemplateScope

/**
 * ═══ 模板行为运行时 —— 基座行为（PR 4）═══
 *
 * [AgentTemplateBehavior] 的抽象基类：六阶段的「默认机械」全部住在
 * 这里，十个内置模板行为只覆写自己有真实逻辑的阶段。内置模板之外的
 * 自定义模板由 [fallbackFor] 生成的兜底行为驱动（纯变量替换 + 通用
 * 契约 —— 用户模板保持「文本模板」语义，不强加结构）。
 *
 * 默认实现要点（每个都是可独立测试的确定性机械）：
 * - **PREPARE**：输入规范化（trim / 换行统一 / 超长截断留痕）+ 形态探测；
 * - **COMPOSE**：变量替换 + 「确定性分析附录」注入（行为清单决定模式）；
 * - **VALIDATE/REPAIR**：复用 PR 3 章节解析器的契约校验与修复机械；
 * - **RENDER**：骨架 → 结构化章节 + 机器摘要 + 质量分。
 */
abstract class TemplateBehaviorBase : AgentTemplateBehavior {

    /** 主输入变量名（子类声明；PREPARE 以它为主输入做形态探测）。
     *  公开只读：目录完整性测试与工坊「主输入」提示消费。 */
    abstract val primaryInputName: String

    /** 可选输入变量名集合（PREPARE 一并规范化）。 */
    protected open val secondaryInputNames: List<String> = emptyList()

    // ═══ PREPARE ═══

    override suspend fun prepare(context: TemplateExecutionContext): TemplatePreparedInput {
        val options = context.options
        val normalized = LinkedHashMap<String, String>()
        val allFacets = LinkedHashMap<String, TemplateInputFacets>()
        val truncations = LinkedHashMap<String, Int>()
        val names = (listOf(primaryInputName) + secondaryInputNames +
            context.template.declaredVariableNames).distinct()
        for (name in names) {
            val raw = context.variable(name) ?: continue
            val normalizedValue = normalizeInput(raw)
            val (trimmedValue, cut) = truncate(normalizedValue, options.maxInputChars)
            if (cut > 0) {
                truncations[name] = cut
                context.log("input '$name' truncated $cut chars (limit=${options.maxInputChars})")
            }
            normalized[name] = trimmedValue
            if (trimmedValue.isNotBlank()) {
                allFacets[name] = inspectFacets(trimmedValue)
            }
        }
        val facets = allFacets[primaryInputName] ?: TemplateInputFacets.empty()
        return TemplatePreparedInput(
            normalizedInputs = normalized,
            primaryInputName = primaryInputName,
            facets = facets,
            allFacets = allFacets,
            truncations = truncations
        )
    }

    /** 输入规范化：统一换行为 \n、去行尾空白；保留内部空行结构。 */
    protected fun normalizeInput(raw: String): String =
        raw.replace("\r\n", "\n").replace('\r', '\n').lines()
            .joinToString("\n") { it.trimEnd() }
            .trim()

    /** 超长截断（0 或负数上限 = 不截断）。返回 (截断后, 截断字符数)。 */
    protected fun truncate(text: String, maxChars: Int): Pair<String, Int> {
        if (maxChars <= 0 || text.length <= maxChars) return text to 0
        return text.take(maxChars) to (text.length - maxChars)
    }

    /** 形态探测（PREPARE 的公共事实层，行为按需消费）。 */
    protected fun inspectFacets(text: String): TemplateInputFacets {
        val metrics = TemplateTextMetrics.measure(text)
        val markdown = TemplateMarkdownScanner.scan(text)
        val lines = text.lines()
        val diffLines = lines.count { it.startsWith("+") || it.startsWith("-") || it.startsWith("@@") }
        return TemplateInputFacets(
            charCount = metrics.charCount,
            lineCount = metrics.lineCount,
            nonBlankLineCount = metrics.nonBlankLineCount,
            averageLineLength = if (metrics.lineCount == 0) 0 else metrics.charCount / metrics.lineCount,
            longestLineLength = lines.maxOfOrNull { it.length } ?: 0,
            looksLikeMarkdown = markdown.hasStructure,
            looksLikeDiff = diffLines >= 3 && lines.any { it.startsWith("@@") },
            looksLikeCode = looksLikeCode(text),
            looksLikeUrl = text.contains("http://") || text.contains("https://"),
            cjkCharCount = metrics.cjkCharCount,
            latinWordCount = metrics.latinWordCount
        )
    }

    private fun looksLikeCode(text: String): Boolean {
        var signals = 0
        if (text.count { it == '{' } >= 1 && text.count { it == '}' } >= 1) signals++
        if (text.count { it == ';' } >= 2) signals++
        if (Regex("""\b(fun|def|class|func|void|let|var|const|return|if|for|while)\b""").containsMatchIn(text)) signals++
        if (text.count { it == '=' } >= 2) signals++
        return signals >= 2
    }

    // ═══ ANALYZE ═══

    /** 默认空分析（子类覆写注入真实规则引擎）。 */
    override suspend fun analyze(
        context: TemplateExecutionContext,
        prepared: TemplatePreparedInput
    ): TemplateAnalysis = TemplateAnalysis.empty()

    // ═══ COMPOSE ═══

    override fun composePrompt(
        context: TemplateExecutionContext,
        prepared: TemplatePreparedInput,
        analysis: TemplateAnalysis
    ): TemplateTurnRequest {
        val manifest = manifest()
        val base = substituteVariables(context)
        val enrichment = when {
            !context.options.injectAnalysisIntoPrompt -> ""
            manifest.promptEnrichmentMode == TemplatePromptEnrichmentMode.NONE -> ""
            analysis.findings.isEmpty() && analysis.enrichedHints.isEmpty() -> ""
            manifest.promptEnrichmentMode == TemplatePromptEnrichmentMode.INLINE_HINTS -> inlineHints(analysis)
            else -> appendFindingsBlock(analysis, manifest)
        }
        val prompt = if (enrichment.isEmpty()) base else "$base\n\n$enrichment"
        return TemplateTurnRequest(
            prompt = prompt,
            behaviorId = behaviorId,
            declaredPhases = manifest.supportedPhases,
            enrichmentNotes = enrichmentNotes(analysis, enrichment),
            metadata = mapOf(
                "promptChars" to prompt.length.toString(),
                "behaviorVersion" to manifest.behaviorVersion.toString()
            )
        )
    }

    /**
     * 变量替换：`{{var}}` 与 `{{var|default}}` 都替换为合并后的值
     * （默认值已在激活时合并），未知变量保留原占位符（引擎变量系统
     * 可能后续解析 —— 与模板库渲染管线的语义一致）。
     */
    protected fun substituteVariables(context: TemplateExecutionContext): String {
        var text = context.template.content
        for (name in context.template.declaredVariableNames) {
            val value = context.variable(name)
            if (value != null) {
                text = text.replace("{{$name}}", value)
                text = Regex("""\{\{\s*$name\s*\|[^{}]*\}\}""").replace(text, value)
            }
        }
        return text
    }

    /** 附录形态的确定性分析注入（重模式，默认）。 */
    protected open fun appendFindingsBlock(
        analysis: TemplateAnalysis,
        manifest: TemplateBehaviorManifest
    ): String = buildString {
        appendLine("──────────")
        appendLine("【确定性分析附录 · 由模板行为运行时自动注入】")
        for (finding in analysis.findings.take(MAX_FINDINGS_IN_PROMPT)) {
            appendLine("- ${finding.toPromptLine()}")
        }
        if (analysis.enrichedHints.isNotEmpty()) {
            appendLine()
            appendLine("附加情报：")
            analysis.enrichedHints.take(MAX_HINTS_IN_PROMPT).forEach { appendLine("- $it") }
        }
        val statsLine = buildString {
            append("统计：${analysis.findings.size} 条规则命中")
            analysis.detectedLanguage?.let { append(" / 语言：$it") }
            if (analysis.stats.analyzedLineCount > 0) append(" / 覆盖 ${analysis.stats.analyzedLineCount} 行")
        }
        appendLine(statsLine)
        appendLine("要求：请逐条核对上述证据——认同的展开位置与修复，不认同的给出理由。")
        append("──────────")
    }

    /** 内联提示形态（轻模式）。 */
    protected open fun inlineHints(analysis: TemplateAnalysis): String = buildString {
        appendLine("【运行时提示】")
        analysis.findings.take(4).forEach { appendLine("- ${findingSummary(it)}") }
        analysis.enrichedHints.take(3).forEach { appendLine("- $it") }
        append("（以上为代码侧确定性结论，请核实后采纳）")
    }

    private fun findingSummary(finding: TemplateFinding): String {
        val where = if (finding.location.isBlank()) "" else "（${finding.location}）"
        return "[${finding.severity.marker}] ${finding.title}$where"
    }

    private fun enrichmentNotes(analysis: TemplateAnalysis, enrichment: String): List<String> {
        if (enrichment.isEmpty()) return emptyList()
        val notes = mutableListOf<String>()
        if (analysis.findings.isNotEmpty()) {
            notes.add("注入 ${analysis.findings.size} 条确定性发现")
        }
        if (analysis.enrichedHints.isNotEmpty()) {
            notes.add("注入 ${analysis.enrichedHints.size} 条附加情报")
        }
        analysis.detectedLanguage?.let { notes.add("语言探测：$it") }
        return notes
    }

    // ═══ VALIDATE / REPAIR ═══

    override fun validateOutput(
        raw: String,
        context: TemplateExecutionContext,
        request: TemplateTurnRequest
    ): TemplateValidationReport {
        val contract = manifest().outputContract
        val skeleton = TemplateSectionParser.parse(raw, contract)
        return TemplateSectionParser.validate(
            skeleton,
            contract,
            strict = context.options.strictValidation
        )
    }

    override fun repairOutput(
        raw: String,
        report: TemplateValidationReport,
        context: TemplateExecutionContext
    ): String {
        val (repaired, notes) = TemplateSectionParser.repair(raw, report)
        if (notes.isNotEmpty()) {
            context.log("repaired: ${notes.joinToString("; ")}")
        }
        return repaired
    }

    // ═══ RENDER ═══

    override fun renderReport(
        raw: String,
        context: TemplateExecutionContext,
        request: TemplateTurnRequest
    ): TemplateRenderedReport {
        val contract = manifest().outputContract
        val skeleton = TemplateSectionParser.parse(raw, contract)
        val sections = buildReportSections(skeleton, contract)
        val repairable = skeletonLeadingViolationCount(skeleton, contract)
        val summary = mapOf(
            TemplateRenderedReport.KEY_BLOCKER_COUNT to "0",
            TemplateRenderedReport.KEY_FINDING_COUNT to "0",
            TemplateRenderedReport.KEY_SEVERITY_TOP to "NONE",
            TemplateRenderedReport.KEY_REPAIRED_COUNT to "0"
        )
        val quality = qualityScore(sections.size, repairable)
        return TemplateRenderedReport(
            finalText = raw.trim(),
            sections = sections,
            summary = summary,
            repairs = emptyList(),
            residualViolations = emptyList(),
            qualityScore = quality
        )
    }

    /** 骨架 → 报告章节（契约内章节按期望顺序排 key，契约外归补充区）。 */
    protected open fun buildReportSections(
        skeleton: TemplateDocumentSkeleton,
        contract: TemplateOutputContract
    ): List<TemplateReportSection> {
        val sections = mutableListOf<TemplateReportSection>()
        for ((index, requirement) in contract.requiredSections.withIndex()) {
            val parsed = skeleton.sections.firstOrNull { requirement.matchesHeader(it.headerText) }
                ?: skeleton.sections.firstOrNull { it.requirement == requirement }
                ?: continue
            sections.add(
                TemplateReportSection(
                    title = requirement.title,
                    kind = kindOf(parsed),
                    lines = parsed.bodyLines + parsed.listItems.map { "· $it" },
                    orderKey = index
                )
            )
        }
        for ((extraIndex, orphan) in skeleton.sections.withIndex()) {
            if (contract.requiredSections.none { it.matchesHeader(orphan.headerText) }) {
                sections.add(
                    TemplateReportSection(
                        title = orphan.headerText,
                        kind = TemplateReportSection.Kind.SUPPLEMENT,
                        lines = orphan.bodyLines,
                        orderKey = CONTRACT_SECTION_ORDER_BASE + extraIndex
                    )
                )
            }
        }
        return sections.sortedBy { it.orderKey }
    }

    private fun kindOf(parsed: TemplateParsedSection): TemplateReportSection.Kind =
        if (parsed.listItems.isNotEmpty()) TemplateReportSection.Kind.LIST else TemplateReportSection.Kind.BODY

    private fun skeletonLeadingViolationCount(
        skeleton: TemplateDocumentSkeleton,
        contract: TemplateOutputContract
    ): Int {
        var violations = 0
        for (requirement in contract.requiredSections.filter { it.required }) {
            if (skeleton.sections.none { requirement.matchesHeader(it.headerText) }) violations++
        }
        return violations
    }

    /** 质量分：契约覆盖缺 1 扣 0.25，未识别结构扣 0.1（下限 0）。 */
    protected fun qualityScore(sectionCount: Int, missingRequired: Int): Double =
        (1.0 - 0.25 * missingRequired - if (sectionCount == 0) 0.1 else 0.0).coerceIn(0.0, 1.0)

    // ═══ 兜底行为（自定义模板驱动）═══

    companion object {
        const val MAX_FINDINGS_IN_PROMPT = 24
        const val MAX_HINTS_IN_PROMPT = 8
        const val CONTRACT_SECTION_ORDER_BASE = 100

        /**
         * 兜底行为：为没有代码行为的模板（用户自定义模板）提供基线管线
         * —— 纯变量替换 + 宽松契约（无必需章节，只剥寒暄/废话）。
         * 模板 id 动态派生，behaviorId = 对应模板 id。
         */
        fun fallbackFor(template: PromptTemplate): TemplateBehaviorBase {
            val target = template
            return object : TemplateBehaviorBase() {
                override val behaviorId: String = target.id
                override val primaryInputName: String =
                    target.variables.firstOrNull()?.name ?: "input"

                override fun manifest(): TemplateBehaviorManifest = TemplateBehaviorManifest(
                    behaviorId = target.id,
                    displayName = target.name,
                    summary = "自定义模板（变量替换 + 通用净化）",
                    supportedPhases = setOf(
                        TemplatePhaseKind.PREPARE,
                        TemplatePhaseKind.COMPOSE,
                        TemplatePhaseKind.VALIDATE,
                        TemplatePhaseKind.RENDER
                    ),
                    outputContract = TemplateOutputContract(payloadOnly = false),
                    deterministicPreAnalysis = false,
                    promptEnrichmentMode = TemplatePromptEnrichmentMode.NONE,
                    behaviorVersion = 0
                )
            }
        }

        /** 分类展示标签（快照构建用）。 */
        fun categoryLabelOf(template: PromptTemplate): String =
            template.category.name + if (template.scope == TemplateScope.CODING) "·CODING" else ""
    }
}
