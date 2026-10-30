package com.apex.agent.core.engine.templates.runtime.builtin

import com.apex.agent.core.engine.templates.runtime.TemplateAnalysis
import com.apex.agent.core.engine.templates.runtime.TemplateAnalysisStats
import com.apex.agent.core.engine.templates.runtime.TemplateBehaviorBase
import com.apex.agent.core.engine.templates.runtime.TemplateBehaviorFeature
import com.apex.agent.core.engine.templates.runtime.TemplateBehaviorManifest
import com.apex.agent.core.engine.templates.runtime.TemplateContractViolation
import com.apex.agent.core.engine.templates.runtime.TemplateContractViolationKind
import com.apex.agent.core.engine.templates.runtime.TemplateDocumentSkeleton
import com.apex.agent.core.engine.templates.runtime.TemplateExecutionContext
import com.apex.agent.core.engine.templates.runtime.TemplateFinding
import com.apex.agent.core.engine.templates.runtime.TemplateFindingCategory
import com.apex.agent.core.engine.templates.runtime.TemplateOutputContract
import com.apex.agent.core.engine.templates.runtime.TemplatePhaseKind
import com.apex.agent.core.engine.templates.runtime.TemplatePreparedInput
import com.apex.agent.core.engine.templates.runtime.TemplatePromptEnrichmentMode
import com.apex.agent.core.engine.templates.runtime.TemplateRenderedReport
import com.apex.agent.core.engine.templates.runtime.TemplateReportSection
import com.apex.agent.core.engine.templates.runtime.TemplateSectionRequirement
import com.apex.agent.core.engine.templates.runtime.TemplateSectionParser
import com.apex.agent.core.engine.templates.runtime.TemplateSeverity
import com.apex.agent.core.engine.templates.runtime.TemplateTurnRequest
import com.apex.agent.core.engine.templates.runtime.TemplateValidationReport
import com.apex.agent.core.engine.templates.runtime.TemplateLanguageDetector

/**
 * ═══ 内置模板行为 · 代码审查（builtin_code_review，PR 5）═══
 *
 * 「代码审查」模板的完整代码化行为。切换到该模板不再只是注入一段
 * 评审提示词，而是装配一条确定性分析管线：
 *
 * 1. **PREPARE**：主输入 code + 可选 language / focus；
 * 2. **ANALYZE**（确定性，先于大模型）：
 *    - 语言判定：用户显式指定 > 探测器（20 门语言打分制）；
 *    - 逐语言规则表扫描（13 张表 / 90+ 条规则，见 [CodeReviewRuleTables]）；
 *    - 结构规则：超长函数 / 嵌套过深 / 超长行 / TODO-FIXME 残留 /
 *      调试输出家族 / 硬编码凭据 / 注释率 / 魔法数；
 * 3. **COMPOSE**：模板正文 + 变量替换 + 「确定性分析附录」注入；
 * 4. **VALIDATE**：回包必须含 总体结论 + 问题清单（列表 1..20）+
 *    严重度标记规范（🔴🟡🟢）；
 * 5. **REPAIR**：公共修复 + 严重度标记归一（「严重/阻断」→🔴 等）；
 * 6. **RENDER**：结构化章节 + 严重度计数摘要 + 质量分。
 */
open class CodeReviewTemplateBehavior : TemplateBehaviorBase() {

    override val behaviorId: String = TEMPLATE_ID
    override val primaryInputName: String = "code"
    override val secondaryInputNames: List<String> = listOf("language", "focus")

    override fun manifest(): TemplateBehaviorManifest = TemplateBehaviorManifest(
        behaviorId = behaviorId,
        displayName = "代码审查",
        summary = "90+ 条逐语言静态规则 + 结构度量先行，评审从核对证据开始",
        supportedPhases = setOf(
            TemplatePhaseKind.PREPARE,
            TemplatePhaseKind.ANALYZE,
            TemplatePhaseKind.COMPOSE,
            TemplatePhaseKind.VALIDATE,
            TemplatePhaseKind.REPAIR,
            TemplatePhaseKind.RENDER
        ),
        features = setOf(
            TemplateBehaviorFeature.LANGUAGE_DETECTION,
            TemplateBehaviorFeature.LANGUAGE_RULES,
            TemplateBehaviorFeature.SEVERITY_MODEL
        ),
        outputContract = TemplateOutputContract(
            requiredSections = listOf(
                TemplateSectionRequirement(
                    title = "总体结论",
                    aliases = listOf("结论", "审查结论"),
                    required = true
                ),
                TemplateSectionRequirement(
                    title = "问题清单",
                    aliases = listOf("问题列表", "问题与建议"),
                    listItemCountRange = 1..MAX_ISSUE_ITEMS,
                    required = true
                ),
                TemplateSectionRequirement(
                    title = "关键修复示例",
                    aliases = listOf("修复示例"),
                    required = false
                )
            )
        ),
        deterministicPreAnalysis = true,
        promptEnrichmentMode = TemplatePromptEnrichmentMode.APPEND_FINDINGS,
        behaviorVersion = 1
    )

    // ═══ ANALYZE：确定性分析 ═══

    override suspend fun analyze(
        context: TemplateExecutionContext,
        prepared: TemplatePreparedInput
    ): TemplateAnalysis {
        val code = prepared.primaryInput
        if (code.isBlank()) {
            return TemplateAnalysis.of(
                findings = emptyList(),
                analyzerNotes = listOf("输入为空，跳过确定性分析")
            )
        }
        val declaredLanguage = context.variableOr("language", "").trim().lowercase()
        val language = resolveLanguage(declaredLanguage, code)
        val focus = context.variableOr("focus", DEFAULT_FOCUS)

        val findings = mutableListOf<TemplateFinding>()
        findings += ruleTableFindings(code, language)
        findings += structuralFindings(code, language)
        findings += secretFindings(code)

        // 第二层：结构度量（复杂度/重复/参数/嵌套 + 可维护性合成分）
        val spans = findFunctions(code, language)
        val metrics = CodeReviewStructureMetrics.report(code, language, spans)
        findings += CodeReviewStructureMetrics.findingsFrom(metrics)

        val lines = code.lines()
        var stats = TemplateAnalysisStats.empty()
            .with(TemplateAnalysisStats.KEY_RULE_COUNT, findings.size)
            .with(TemplateAnalysisStats.KEY_ANALYZED_LINES, lines.size)
            .with(METRIC_COMMENT_LINES, countCommentLines(code, language))
            .with(METRIC_LONGEST_FUNCTION, longestFunctionLines(code, language))

        // 附加情报：给 LLM 的背景事实（不带判断）
        val hints = mutableListOf<String>()
        hints += "分析覆盖 ${lines.size} 行，命中 ${findings.size} 条确定性规则（严重度：${describeSeverityMix(findings)}）"
        hints += "可维护性评分 ${metrics.maintainabilityScore}/100（${metrics.scoreRationale.joinToString("；")}）"
        if (language != null) {
            hints += "语言判定：$language（${if (declaredLanguage.isNotBlank()) "用户指定" else "自动探测"}）"
        } else {
            hints += "语言未能高置信判定，通用规则已执行；若语言明确请在输出中说明假设"
        }
        hints += "审查重点：$focus"
        val commentRatio = stats.intMetric(METRIC_COMMENT_LINES, 0).toDouble() / lines.size.coerceAtLeast(1)
        if (lines.size >= 40 && commentRatio < 0.05) {
            hints += "注释率约 ${(commentRatio * 100).toInt()}%，对超过 40 行的代码偏低"
        }

        return TemplateAnalysis.of(
            findings = findings,
            stats = stats,
            detectedLanguage = language,
            enrichedHints = hints,
            metadata = buildMap {
                put("language", language ?: "unknown")
                put("focus", focus)
                put("declaredLanguage", declaredLanguage.ifBlank { "auto" })
            }
        )
    }

    /** 语言判定：用户指定优先（常见别名归一），否则探测器。 */
    internal fun resolveLanguage(declared: String, code: String): String? {
        if (declared.isNotBlank()) {
            normalizeLanguageName(declared)?.let { return it }
        }
        val detection = TemplateLanguageDetector.detect(code)
        return if (detection.isConfident) detection.detected else null
    }

    /** 用户语言字段 → 规则表 id（别名归一）。 */
    internal fun normalizeLanguageName(name: String): String? = when (name.trim().lowercase()) {
        "kotlin", "kt" -> "kotlin"
        "java" -> "java"
        "python", "py", "python3" -> "python"
        "javascript", "js", "node", "nodejs" -> "javascript"
        "typescript", "ts" -> "typescript"
        "go", "golang" -> "go"
        "rust", "rs" -> "rust"
        "c" -> "c"
        "c++", "cpp", "cplusplus" -> "cpp"
        "c#", "csharp", "cs" -> "csharp"
        "swift" -> "swift"
        "php" -> "php"
        "shell", "bash", "sh", "zsh" -> "shell"
        "sql" -> "sql"
        else -> null
    }

    /** 逐语言规则表扫描（行级定位 + 证据截取 + 上限防刷屏）。 */
    internal fun ruleTableFindings(code: String, language: String?): List<TemplateFinding> {
        val rules = CodeReviewRuleTables.rulesFor(language)
        if (rules.isEmpty() || code.isBlank()) return emptyList()
        val lines = code.lines()
        val findings = mutableListOf<TemplateFinding>()
        for (rule in rules) {
            var reports = 0
            for ((index, line) in lines.withIndex()) {
                if (reports >= rule.maxReports) break
                if (!rule.matchesInComment && isCommentLine(line, language)) continue
                val match = rule.pattern.find(line) ?: continue
                findings.add(
                    TemplateFinding(
                        ruleId = rule.ruleId,
                        category = rule.category,
                        severity = rule.severity,
                        title = rule.title,
                        location = "第 ${index + 1} 行",
                        evidence = line.trim().take(EVIDENCE_MAX_CHARS),
                        suggestion = rule.suggestion,
                        confidence = if (rule.severity == TemplateSeverity.BLOCKER) 0.95 else 0.9
                    )
                )
                reports++
            }
        }
        return findings
    }

    /** 结构规则：与语言无关（或按语言族微调）的形态级发现。 */
    internal fun structuralFindings(code: String, language: String?): List<TemplateFinding> {
        val findings = mutableListOf<TemplateFinding>()
        val lines = code.lines()
        if (lines.isEmpty()) return findings

        // 1) TODO / FIXME / HACK / XXX 残留（注释行也算——标记本身在注释里）
        for ((index, line) in lines.withIndex()) {
            val marker = WORK_ITEM_MARKERS.firstOrNull { line.contains(it, ignoreCase = true) }
            if (marker != null) {
                findings.add(
                    TemplateFinding(
                        ruleId = "common.work-item-marker",
                        category = TemplateFindingCategory.MAINTAINABILITY,
                        severity = TemplateSeverity.MINOR,
                        title = "工作项标记 $marker 残留",
                        location = "第 ${index + 1} 行",
                        evidence = line.trim().take(EVIDENCE_MAX_CHARS),
                        suggestion = "转为 issue 跟踪或完成即删；代码里不该长期挂着待办"
                    )
                )
                if (findings.count { it.ruleId == "common.work-item-marker" } >= 3) break
            }
        }

        // 2) 超长行
        for ((index, line) in lines.withIndex()) {
            if (line.length > LONG_LINE_THRESHOLD) {
                findings.add(
                    TemplateFinding(
                        ruleId = "common.long-line",
                        category = TemplateFindingCategory.STYLE,
                        severity = TemplateSeverity.MINOR,
                        title = "超长行（${line.length} 字符 > $LONG_LINE_THRESHOLD）",
                        location = "第 ${index + 1} 行",
                        evidence = line.take(60) + "…",
                        suggestion = "按语义断行（函数链/参数表/长条件各自成行）",
                        confidence = 1.0
                    )
                )
                if (findings.count { it.ruleId == "common.long-line" } >= 2) break
            }
        }

        // 3) 超长函数（按语言族的函数头识别 + 花括号/缩进计界）
        val longFunctions = findLongFunctions(code, language)
        for (function in longFunctions.take(3)) {
            findings.add(
                TemplateFinding(
                    ruleId = "common.long-function",
                    category = TemplateFindingCategory.MAINTAINABILITY,
                    severity = TemplateSeverity.IMPORTANT,
                    title = "函数过长（${function.lineCount} 行 > $LONG_FUNCTION_THRESHOLD）",
                    location = "函数 ${function.name}（自第 ${function.startLine} 行）",
                    evidence = "${function.name} 起始于第 ${function.startLine} 行，跨度 ${function.lineCount} 行",
                    suggestion = "按职责拆分（每个函数只做一件事）；先抽副作用边界再抽纯逻辑",
                    confidence = 0.85
                )
            )
        }

        // 4) 嵌套过深（缩进层级代理：以 4 空格为一层，Python 族更严格）
        val indentUnit = if (language == "python") 4 else 4
        var deepest = 0
        var deepestLine = 0
        for ((index, line) in lines.withIndex()) {
            val indent = line.takeWhile { it == ' ' }.length / indentUnit
            if (indent > deepest) {
                deepest = indent
                deepestLine = index + 1
            }
        }
        if (deepest >= DEEP_NESTING_THRESHOLD) {
            findings.add(
                TemplateFinding(
                    ruleId = "common.deep-nesting",
                    category = TemplateFindingCategory.MAINTAINABILITY,
                    severity = TemplateSeverity.IMPORTANT,
                    title = "嵌套过深（第 $deepest 层 ≥ $DEEP_NESTING_THRESHOLD）",
                    location = "第 $deepestLine 行附近",
                    evidence = "最深处缩进 ${deepest * indentUnit} 空格",
                    suggestion = "卫语句提前返回 / 抽内部函数 / 用集合操作替代多层循环",
                    confidence = 0.8
                )
            )
        }

        // 5) Shell 家族专项：缺 set -euo pipefail（结构性判定：脚本前部无防护）
        if (language == "shell" && lines.size >= 4) {
            val head = lines.take(10).joinToString("\n")
            if (!Regex("""\bset\s+-[euoEx]""").containsMatchIn(head)) {
                findings.add(
                    TemplateFinding(
                        ruleId = "common.shell-no-strict-mode",
                        category = TemplateFindingCategory.CORRECTNESS,
                        severity = TemplateSeverity.IMPORTANT,
                        title = "脚本缺少严格模式",
                        location = "脚本头部",
                        evidence = "前 10 行未见 set -euo pipefail",
                        suggestion = "头部加 set -euo pipefail；外部输入不可信时配合 shellcheck 修复",
                        confidence = 0.9
                    )
                )
            }
        }
        return findings
    }

    /** 硬编码凭据扫描（安全红线，独立通道避免与语言表耦合）。 */
    internal fun secretFindings(code: String): List<TemplateFinding> {
        val findings = mutableListOf<TemplateFinding>()
        val lines = code.lines()
        for ((index, line) in lines.withIndex()) {
            for ((name, pattern) in SECRET_PATTERNS) {
                val match = pattern.find(line) ?: continue
                findings.add(
                    TemplateFinding(
                        ruleId = "security.hardcoded-credential",
                        category = TemplateFindingCategory.SECURITY,
                        severity = TemplateSeverity.BLOCKER,
                        title = "疑似硬编码凭据（$name）",
                        location = "第 ${index + 1} 行",
                        evidence = match.value.take(40) + "…",
                        suggestion = "移到环境变量/密钥管理（KeyStore/Vault）；该行应立即从代码与历史中清除",
                        confidence = 0.85
                    )
                )
            }
            if (findings.size >= 3) break
        }
        return findings
    }

    // ═══ VALIDATE / REPAIR / RENDER ═══

    override fun validateOutput(
        raw: String,
        context: TemplateExecutionContext,
        request: TemplateTurnRequest
    ): TemplateValidationReport {
        val base = super.validateOutput(raw, context, request)
        // 专项：问题清单里的严重度标记规范性（🔴🟡🟢 或文字级）
        val contract = manifest().outputContract
        val skeleton = TemplateSectionParser.parse(raw, contract)
        val issuesSection = skeleton.sections.firstOrNull {
            contract.requiredSections[1].matchesHeader(it.headerText)
        }
        val violations = base.violations.toMutableList()
        if (issuesSection != null && issuesSection.listItems.isNotEmpty()) {
            val unmarked = issuesSection.listItems.filter { TemplateSeverity.fromMarker(it) == null }
            if (unmarked.isNotEmpty() && unmarked.size == issuesSection.listItems.size) {
                violations.add(
                    TemplateContractViolation(
                        kind = TemplateContractViolationKind.BAD_SEVERITY_MARK,
                        section = "问题清单",
                        detail = "问题清单 ${unmarked.size} 条均缺严重度标记（期望 🔴/🟡/🟢 前缀）",
                        evidence = unmarked.first().take(40)
                    )
                )
            }
        }
        // 专项：结论必须落在三档之一（通过/有条件通过/需修改）
        val verdictSection = skeleton.sections.firstOrNull {
            contract.requiredSections[0].matchesHeader(it.headerText)
        }
        if (verdictSection != null) {
            val verdictText = verdictSection.bodyLines.joinToString(" ") + " " +
                verdictSection.listItems.joinToString(" ")
            if (VERDICT_KEYWORDS.none { verdictText.contains(it) }) {
                violations.add(
                    TemplateContractViolation(
                        kind = TemplateContractViolationKind.MISSING_VERDICT,
                        section = "总体结论",
                        detail = "总体结论未落在三档（通过/有条件通过/需修改）",
                        evidence = verdictText.trim().take(40)
                    )
                )
            }
        }
        return TemplateValidationReport(
            violations = violations,
            parsedSections = base.parsedSections,
            orphans = base.orphans
        )
    }

    override fun repairOutput(
        raw: String,
        report: TemplateValidationReport,
        context: TemplateExecutionContext
    ): String {
        var text = super.repairOutput(raw, report, context)
        // 专项修复：文字级严重度 → emoji 标记归一
        for ((word, severity) in SEVERITY_WORD_MAP) {
            text = text.replace(word, "${severity.marker}${word.removePrefix("【").removeSuffix("】")}")
        }
        return text
    }

    override fun renderReport(
        raw: String,
        context: TemplateExecutionContext,
        request: TemplateTurnRequest
    ): TemplateRenderedReport {
        val base = super.renderReport(raw, context, request)
        val contract = manifest().outputContract
        val skeleton = TemplateSectionParser.parse(raw, contract)
        val severityCounts = countSeverityMarks(skeleton)
        val topSeverity = severityCounts.filter { it.value > 0 }
            .maxByOrNull { it.key.weight }?.key?.label ?: "无"
        val summary = mapOf(
            TemplateRenderedReport.KEY_BLOCKER_COUNT to (severityCounts[TemplateSeverity.BLOCKER] ?: 0).toString(),
            TemplateRenderedReport.KEY_FINDING_COUNT to severityCounts.values.sum().toString(),
            TemplateRenderedReport.KEY_SEVERITY_TOP to topSeverity,
            TemplateRenderedReport.KEY_REPAIRED_COUNT to "0"
        )
        // 顶部追加「评审摘要」章节（严重度分布一眼可见）
        val summarySection = TemplateReportSection(
            title = "评审摘要",
            kind = TemplateReportSection.Kind.VERDICT,
            lines = listOf(
                "🔴 阻断 ${severityCounts[TemplateSeverity.BLOCKER] ?: 0} ｜ " +
                    "🟡 重要 ${severityCounts[TemplateSeverity.IMPORTANT] ?: 0} ｜ " +
                    "🟢 次要 ${severityCounts[TemplateSeverity.MINOR] ?: 0} ｜ " +
                    "🔵 信息 ${severityCounts[TemplateSeverity.INFO] ?: 0}"
            ),
            orderKey = -1
        )
        return base.copy(
            sections = (listOf(summarySection) + base.sections).sortedBy { it.orderKey },
            summary = summary
        )
    }

    // ═══ 分析辅助 ═══

    /** 函数边界探测结果。 */
    data class FunctionSpan(
        val name: String,
        val startLine: Int,
        val lineCount: Int
    )

    /** 按语言族识别函数头并度量跨度（花括号配对或缩进块）—— 全量函数。 */
    internal fun findFunctions(code: String, language: String?): List<FunctionSpan> {
        val lines = code.lines()
        val spans = mutableListOf<FunctionSpan>()
        val functionHeads = when (language) {
            "kotlin", "java", "csharp", "swift", "javascript", "typescript" ->
                Regex("""\b(fun|def|function|func|fn)\s+(\w+)\s*\(""")
            "python" -> Regex("""^\s*def\s+(\w+)\s*\(""")
            "go" -> Regex("""\bfunc\s+(?:\([^)]*\)\s+)?(\w+)\s*\(""")
            "rust" -> Regex("""\bfn\s+(\w+)\s*\(""")
            else -> Regex("""\b(fun|def|func|fn|function)\s+(\w+)\s*\(""")
        }
        var index = 0
        while (index < lines.size) {
            val match = functionHeads.find(lines[index])
            if (match != null) {
                val name = match.groupValues.last { it.isNotBlank() }
                val startLine = index + 1
                val lineCount = measureSpan(lines, index, language)
                spans.add(FunctionSpan(name, startLine, lineCount))
                index += lineCount.coerceAtLeast(1)
            } else {
                index++
            }
        }
        return spans
    }

    /** 超长函数 = 全量函数中的超阈值子集（行为对外 API 语义不变）。 */
    internal fun findLongFunctions(code: String, language: String?): List<FunctionSpan> =
        findFunctions(code, language).filter { it.lineCount > LONG_FUNCTION_THRESHOLD }

    /** 度量函数跨度：花括号配对（C 系）或缩进回退（Python）。 */
    private fun measureSpan(lines: List<String>, headIndex: Int, language: String?): Int {
        if (language == "python") {
            val baseIndent = lines[headIndex].takeWhile { it == ' ' }.length
            var end = headIndex + 1
            while (end < lines.size) {
                val line = lines[end]
                if (line.isBlank()) {
                    end++
                    continue
                }
                val indent = line.takeWhile { it == ' ' }.length
                if (indent <= baseIndent) break
                end++
            }
            return end - headIndex
        }
        // 花括号计界（含签名跨行场景的容错）
        var depth = 0
        var seen = false
        var end = headIndex
        while (end < lines.size) {
            for (ch in lines[end]) {
                if (ch == '{') {
                    depth++
                    seen = true
                } else if (ch == '}') {
                    depth--
                }
            }
            if (seen && depth <= 0) return end - headIndex + 1
            if (!seen && end - headIndex > SIGNATURE_MAX_LINES) return end - headIndex + 1
            end++
        }
        return lines.size - headIndex
    }

    /** 注释行判定（按语言取行注释前缀；未知语言只认 // 与 #）。 */
    private fun isCommentLine(line: String, language: String?): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return false
        val prefixes = when (language) {
            "python", "shell", "ruby", "yaml" -> listOf("#")
            "sql" -> listOf("--")
            else -> listOf("//", "*", "/*")
        }
        return prefixes.any { trimmed.startsWith(it) }
    }

    private fun countCommentLines(code: String, language: String?): Int =
        code.lines().count { isCommentLine(it, language) }

    private fun longestFunctionLines(code: String, language: String?): Int =
        findFunctions(code, language).maxOfOrNull { it.lineCount } ?: 0

    private fun describeSeverityMix(findings: List<TemplateFinding>): String {
        val counts = findings.groupingBy { it.severity }.eachCount()
        return TemplateSeverity.entries.joinToString(" / ") { severity ->
            "${severity.marker}${counts[severity] ?: 0}"
        }
    }

    /** 从回包骨架统计严重度标记（列表项 + 非列表正文行；列表行在
     *  bodyLines 与 listItems 里重复出现，只计一次）。 */
    private fun countSeverityMarks(skeleton: TemplateDocumentSkeleton): Map<TemplateSeverity, Int> {
        val counts = TemplateSeverity.entries.associateWith { 0 }.toMutableMap()
        for (section in skeleton.sections) {
            for (item in section.listItems) {
                TemplateSeverity.fromMarker(item)?.let { counts[it] = counts[it]!! + 1 }
            }
            for (line in section.bodyLines) {
                if (LIST_LINE_SHAPE.containsMatchIn(line)) continue  // 已在 listItems 计过
                TemplateSeverity.fromMarker(line)?.let { counts[it] = counts[it]!! + 1 }
            }
        }
        return counts
    }

    companion object {
        const val TEMPLATE_ID = "builtin_code_review"
        const val DEFAULT_FOCUS = "正确性、安全性、性能与可维护性"
        const val MAX_ISSUE_ITEMS = 20
        const val EVIDENCE_MAX_CHARS = 80
        const val LONG_LINE_THRESHOLD = 140
        const val LONG_FUNCTION_THRESHOLD = 60
        const val DEEP_NESTING_THRESHOLD = 5
        const val SIGNATURE_MAX_LINES = 3
        const val METRIC_COMMENT_LINES = "commentLines"
        const val METRIC_LONGEST_FUNCTION = "longestFunctionLines"

        /** 工作项标记（注释行也算命中）。 */
        val WORK_ITEM_MARKERS = listOf("TODO", "FIXME", "HACK", "XXX")

        /** 结论三档关键词。 */
        val VERDICT_KEYWORDS = listOf("通过", "需修改", "不通过", "reject", "approve", "有条件")

        /** 硬编码凭据形态（值域宽松，宁可误报给 LLM 核对；分隔符兼容
         *  `key: value` / `key = value` / Kotlin `"key" to value` 三种）。 */
        val SECRET_PATTERNS: List<Pair<String, Regex>> = listOf(
            "密码" to Regex("""(password|passwd|pwd)["']?\s*(to\b|[:=])\s*["'][^"'{}\s]{6,}["']""", RegexOption.IGNORE_CASE),
            "API Key" to Regex("""(api[_-]?key|apikey)["']?\s*(to\b|[:=])\s*["'][A-Za-z0-9_\-]{16,}["']""", RegexOption.IGNORE_CASE),
            "Token" to Regex("""(token|secret)["']?\s*(to\b|[:=])\s*["'][A-Za-z0-9_\-]{16,}["']""", RegexOption.IGNORE_CASE),
            "私钥" to Regex("""-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----""")
        )

        /** 列表行形态（countSeverityMarks 去重用，与章节解析器同源形态）。 */
        val LIST_LINE_SHAPE: Regex = Regex("""^\s*([-*+•]|\d{1,3}[.)]|[①②③④⑤⑥⑦⑧⑨⑩])\s+""")

        /** 文字级严重度 → emoji 标记归一表（修复阶段用）。 */
        val SEVERITY_WORD_MAP: List<Pair<String, TemplateSeverity>> = listOf(
            "【阻断】" to TemplateSeverity.BLOCKER,
            "【严重】" to TemplateSeverity.BLOCKER,
            "【重要】" to TemplateSeverity.IMPORTANT,
            "【次要】" to TemplateSeverity.MINOR,
            "【轻微】" to TemplateSeverity.MINOR,
            "【信息】" to TemplateSeverity.INFO
        )
    }
}
