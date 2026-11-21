package com.apex.agent.core.engine.templates.runtime.builtin

import com.apex.agent.core.engine.templates.runtime.TemplateFinding
import com.apex.agent.core.engine.templates.runtime.TemplateFindingCategory
import com.apex.agent.core.engine.templates.runtime.TemplateSeverity

/**
 * ═══ 内置模板行为 · 代码审查 —— 结构度量（PR 5 之二）═══
 *
 * 代码审查行为的第二层确定性分析：圈复杂度代理 + 重复块探测 +
 * 可维护性合成评分。与 [CodeReviewRuleTables] 的行级规则正交 ——
 * 这里的发现以「函数/块」为粒度，喂给 LLM 的是宏观质量画像。
 *
 * 全部纯函数：同输入必同输出，离线可测。
 */

/** 单个函数的结构度量。 */
data class FunctionComplexity(
    val name: String,
    val startLine: Int,
    val lineCount: Int,
    /** 分支节点计数（圈复杂度代理：每 +1 一条独立路径）。 */
    val branchCount: Int,
    /** 圈复杂度代理值（branchCount + 1）。 */
    val cyclomaticProxy: Int,
    /** 参数个数（从签名行提取）。 */
    val parameterCount: Int,
    /** 嵌套深度峰值（缩进代理）。 */
    val maxNesting: Int
) {
    /** 是否高复杂（阈值见 [CodeReviewStructureMetrics] 常量）。 */
    val isHighComplexity: Boolean get() = cyclomaticProxy >= CodeReviewStructureMetrics.HIGH_COMPLEXITY

    /** 是否参数过多。 */
    val hasTooManyParams: Boolean get() = parameterCount > CodeReviewStructureMetrics.MAX_PARAMS
}

/** 重复块。 */
data class DuplicateBlock(
    /** 规范化指纹（去空白/变量名占位后的行序列哈希）。 */
    val fingerprint: String,
    val occurrences: Int,
    /** 首次出现行号。 */
    val firstLine: Int,
    val lineCount: Int,
    /** 展示用样例（首处原文，截断）。 */
    val sample: String
)

/** 文件级合成画像。 */
data class StructureMetricsReport(
    val functions: List<FunctionComplexity>,
    val duplicates: List<DuplicateBlock>,
    val totalLines: Int,
    val commentLines: Int,
    val codeLines: Int,
    val blankLines: Int,
    /** 可维护性评分 0..100（越高越好；复杂度/重复/长度/注释四因子合成）。 */
    val maintainabilityScore: Int,
    /** 评分构成说明（人读，报告用）。 */
    val scoreRationale: List<String>
) {
    /** 最复杂函数（无函数返回 null）。 */
    val worstFunction: FunctionComplexity? get() = functions.maxByOrNull { it.cyclomaticProxy }

    /** 高复杂函数清单。 */
    val highComplexityFunctions: List<FunctionComplexity>
        get() = functions.filter { it.isHighComplexity }.sortedByDescending { it.cyclomaticProxy }

    companion object {
        fun empty(): StructureMetricsReport = StructureMetricsReport(
            functions = emptyList(),
            duplicates = emptyList(),
            totalLines = 0,
            commentLines = 0,
            codeLines = 0,
            blankLines = 0,
            maintainabilityScore = 100,
            scoreRationale = emptyList()
        )
    }
}

/**
 * 结构度量器（纯函数集合）。
 */
object CodeReviewStructureMetrics {

    const val HIGH_COMPLEXITY = 12
    const val CRITICAL_COMPLEXITY = 20
    const val MAX_PARAMS = 5
    const val MAX_NESTING = 5
    const val DUPLICATE_MIN_LINES = 3
    const val DUPLICATE_MIN_OCCURRENCES = 2
    /** 跨行签名缓冲上限（参数提取用）。 */
    const val SIGNATURE_BUFFER_MAX_LINES = 12

    /** 分支关键字（语言通联表：按语言取子集）。 */
    private val BRANCH_KEYWORDS: Map<String, List<Regex>> = mapOf(
        "common" to listOf(
            Regex("""\bif\b"""), Regex("""\belse\b"""), Regex("""\bfor\b"""),
            Regex("""\bwhile\b"""), Regex("""\bcase\b"""), Regex("""\bcatch\b""")
        ),
        "kotlin" to listOf(Regex("""\bwhen\b"""), Regex("""\?\?"""), Regex("""&&"""), Regex("""\|\|""")),
        "java" to listOf(Regex("""\?"""), Regex("""&&"""), Regex("""\|\|""")),
        "python" to listOf(Regex("""\belif\b"""), Regex("""\band\b"""), Regex("""\bor\b""")),
        "javascript" to listOf(Regex("""\?\?"""), Regex("""&&"""), Regex("""\|\|"""), Regex("""\?\.""")),
        "typescript" to listOf(Regex("""\?\?"""), Regex("""&&"""), Regex("""\|\|"""), Regex("""\?\."""")),
        "go" to listOf(Regex("""\bselect\b"""), Regex("""&&"""), Regex("""\|\|""")),
        "rust" to listOf(Regex("""\bmatch\b"""), Regex("""&&"""), Regex("""\|\|"""), Regex("""\?""")),
        "c" to listOf(Regex("""\?\?"""), Regex("""&&"""), Regex("""\|\|""")),
        "cpp" to listOf(Regex("""&&"""), Regex("""\|\|""")),
        "csharp" to listOf(Regex("""\bswitch\b"""), Regex("""&&"""), Regex("""\|\|"""), Regex("""\?\?""")),
        "swift" to listOf(Regex("""\bguard\b"""), Regex("""&&"""), Regex("""\|\|""")),
        "php" to listOf(Regex("""\belseif\b"""), Regex("""&&"""), Regex("""\|\|""")),
        "shell" to listOf(Regex("""\bcase\b"""), Regex("""&&"""), Regex("""\|\|"""))
    )

    // ═══ 圈复杂度代理 ═══

    /**
     * 逐函数度量复杂度（复用行为侧的函数边界探测结果）。
     *
     * @param spans 函数边界（由 [CodeReviewTemplateBehavior.findLongFunctions] 提供）
     */
    fun measureComplexity(
        code: String,
        spans: List<CodeReviewTemplateBehavior.FunctionSpan>,
        language: String?
    ): List<FunctionComplexity> {
        val lines = code.lines()
        return spans.map { span ->
            val body = lines.drop(span.startLine - 1).take(span.lineCount)
            val keywords = branchKeywordsFor(language)
            var branchCount = 0
            var maxNesting = 0
            for (line in body) {
                for (keyword in keywords) {
                    branchCount += keyword.findAll(line).count()
                }
                val indent = line.takeWhile { it == ' ' }.length / 4
                if (indent > maxNesting) maxNesting = indent
            }
            FunctionComplexity(
                name = span.name,
                startLine = span.startLine,
                lineCount = span.lineCount,
                branchCount = branchCount,
                cyclomaticProxy = branchCount + 1,
                parameterCount = countParameters(span, lines),
                maxNesting = maxNesting
            )
        }
    }

    private fun branchKeywordsFor(language: String?): List<Regex> =
        (BRANCH_KEYWORDS["common"].orEmpty()) + (BRANCH_KEYWORDS[language].orEmpty())

    /** 从函数头行提取参数个数（跨行签名：缓冲到首个深度归零的右括号，上限 12 行）。 */
    private fun countParameters(
        span: CodeReviewTemplateBehavior.FunctionSpan,
        lines: List<String>
    ): Int {
        val headIndex = span.startLine - 1
        val buffer = StringBuilder()
        var index = headIndex
        while (index < lines.size && index < headIndex + SIGNATURE_BUFFER_MAX_LINES) {
            buffer.append(lines[index]).append('\n')
            if (lines[index].contains(')')) break
            index++
        }
        val head = buffer.toString()
        val open = head.indexOf('(')
        if (open < 0) return 0
        var depth = 0
        var genericDepth = 0
        var commas = 0
        var sawContent = false
        for (charPosition in open until head.length) {
            val ch = head[charPosition]
            when (ch) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0 && sawContent) return commas + 1
                    if (depth == 0) return 0
                }
                '<' -> genericDepth++
                '>' -> if (genericDepth > 0) genericDepth--
                // 泛型尖括号内的逗号不是参数分隔符（Map<String, Int> 只算一个参数）
                ',' -> if (depth == 1 && genericDepth == 0) commas++
                '\n' -> Unit
                else -> if (!ch.isWhitespace()) sawContent = true
            }
        }
        return 0
    }

    // ═══ 重复块探测 ═══

    /**
     * 重复块探测：把行序列规范化（去空白、字面量与标识符占位化）后
     * 滑窗哈希，出现 ≥2 次的 ≥3 行块报告为重复。
     *
     * 规范化两级：
     * 1. trim + 压缩内部空白；
     * 2. 数字/字符串字面量 → 占位符（`0xN` / `0xS`），标识符保留
     *    （同名才更可能是真复制粘贴）。
     */
    fun detectDuplicates(code: String): List<DuplicateBlock> {
        val lines = code.lines().filter { it.isNotBlank() }
        if (lines.size < DUPLICATE_MIN_LINES * 2) return emptyList()
        val normalized = lines.map { normalizeLine(it) }
        val windowFingerprints = LinkedHashMap<String, MutableList<Int>>()
        var position = 0
        while (position + DUPLICATE_MIN_LINES <= normalized.size) {
            val window = normalized.subList(position, position + DUPLICATE_MIN_LINES)
            if (window.any { it.isEmpty() }) {
                position++
                continue
            }
            val fingerprint = window.joinToString("|")
            windowFingerprints.getOrPut(fingerprint) { mutableListOf() }.add(position)
            position++
        }
        val duplicates = mutableListOf<DuplicateBlock>()
        for ((fingerprint, positions) in windowFingerprints) {
            if (positions.size < DUPLICATE_MIN_OCCURRENCES) continue
            // 连续窗口只保留首窗（滑动会造成重叠计数）
            val distinct = collapseConsecutive(positions)
            if (distinct.size < DUPLICATE_MIN_OCCURRENCES) continue
            duplicates.add(
                DuplicateBlock(
                    fingerprint = fingerprint.take(60),
                    occurrences = distinct.size,
                    firstLine = indexInOriginal(lines, distinct.first()),
                    lineCount = DUPLICATE_MIN_LINES,
                    sample = lines.subList(distinct.first(), distinct.first() + DUPLICATE_MIN_LINES)
                        .joinToString(" ⏎ ").take(100)
                )
            )
        }
        return duplicates.sortedByDescending { it.occurrences }.take(3)
    }

    /** 连续起始位去重（i 与 i+1 是同一块的相邻窗口）。 */
    private fun collapseConsecutive(positions: List<Int>): List<Int> {
        val distinct = mutableListOf<Int>()
        for (value in positions.sorted()) {
            if (distinct.isEmpty() || value - distinct.last() >= DUPLICATE_MIN_LINES) {
                distinct.add(value)
            }
        }
        return distinct
    }

    /** 窗口序号 → 原始行号（过滤空行前的序号映射）。 */
    private fun indexInOriginal(nonBlankLines: List<String>, windowIndex: Int): Int {
        return windowIndex + 1  // 简化：报告用非空行序数（样例自证位置）
    }

    /** 行规范化：trim + 空白压缩 + 字面量占位。 */
    internal fun normalizeLine(line: String): String {
        var text = line.trim().replace(Regex("""\s+"""), " ")
        if (text.isEmpty()) return ""
        text = Regex(""""[^"]*"""").replace(text, "0xS")
        text = Regex("""\b\d+(\.\d+)?\b""").replace(text, "0xN")
        text = Regex(""""[^"]*"""").replace(text, "0xS")
        return text
    }

    // ═══ 合成画像 ═══

    /**
     * 文件级合成画像 + 质量发现（复杂度/重复/超参/深嵌套四类）。
     */
    fun report(
        code: String,
        language: String?,
        spans: List<CodeReviewTemplateBehavior.FunctionSpan>
    ): StructureMetricsReport {
        if (code.isBlank()) return StructureMetricsReport.empty()
        val lines = code.lines()
        val commentPrefixes = when (language) {
            "python", "shell", "ruby", "yaml" -> listOf("#")
            "sql" -> listOf("--")
            else -> listOf("//", "*", "/*")
        }
        val commentLines = lines.count { line ->
            val trimmed = line.trim()
            trimmed.isNotEmpty() && commentPrefixes.any { trimmed.startsWith(it) }
        }
        val blankLines = lines.count { it.isBlank() }
        val functions = measureComplexity(code, spans, language)
        val duplicates = detectDuplicates(code)

        // 质量分合成（四因子扣分制，下限 0）
        var score = 100.0
        val rationale = mutableListOf<String>()
        val highComplexity = functions.filter { it.cyclomaticProxy >= HIGH_COMPLEXITY }
        if (highComplexity.isNotEmpty()) {
            val penalty = highComplexity.sumOf { (it.cyclomaticProxy - HIGH_COMPLEXITY).coerceAtMost(10) * 1.5 }
            score -= penalty
            rationale.add("复杂度：${highComplexity.size} 个函数超过阈值 $HIGH_COMPLEXITY（扣 ${penalty.toInt()} 分）")
        }
        if (duplicates.isNotEmpty()) {
            val penalty = duplicates.sumOf { it.occurrences * 4.0 }
            score -= penalty
            rationale.add("重复：${duplicates.size} 处重复块（扣 ${penalty.toInt()} 分）")
        }
        val tooManyParams = functions.filter { it.hasTooManyParams }
        if (tooManyParams.isNotEmpty()) {
            score -= tooManyParams.size * 3.0
            rationale.add("参数：${tooManyParams.size} 个函数参数超过 $MAX_PARAMS（扣 ${tooManyParams.size * 3} 分）")
        }
        val deepNesting = functions.filter { it.maxNesting > MAX_NESTING }
        if (deepNesting.isNotEmpty()) {
            score -= deepNesting.size * 4.0
            rationale.add("嵌套：${deepNesting.size} 个函数嵌套超过 $MAX_NESTING 层（扣 ${deepNesting.size * 4} 分）")
        }
        if (rationale.isEmpty()) {
            rationale.add("结构健康：无超阈值复杂度/重复/参数/嵌套项")
        }
        return StructureMetricsReport(
            functions = functions,
            duplicates = duplicates,
            totalLines = lines.size,
            commentLines = commentLines,
            codeLines = lines.size - commentLines - blankLines,
            blankLines = blankLines,
            maintainabilityScore = score.toInt().coerceIn(0, 100),
            scoreRationale = rationale
        )
    }

    /** 画像 → 发现清单（复杂度族）。 */
    fun findingsFrom(metrics: StructureMetricsReport): List<TemplateFinding> {
        val findings = mutableListOf<TemplateFinding>()
        for (function in metrics.highComplexityFunctions.take(3)) {
            findings.add(
                TemplateFinding(
                    ruleId = "metrics.high-complexity",
                    category = TemplateFindingCategory.MAINTAINABILITY,
                    severity = if (function.cyclomaticProxy >= CRITICAL_COMPLEXITY) {
                        TemplateSeverity.IMPORTANT
                    } else {
                        TemplateSeverity.MINOR
                    },
                    title = "函数复杂度过高（${function.name} 圈复杂度代理 ${function.cyclomaticProxy}）",
                    location = "第 ${function.startLine} 行起 ${function.lineCount} 行",
                    evidence = "分支节点 ${function.branchCount} / 参数 ${function.parameterCount} / 最深嵌套 ${function.maxNesting}",
                    suggestion = "拆分职责（每函数一条主路径）；策略表替代多重 when/if 链",
                    confidence = 0.9
                )
            )
        }
        for (duplicate in metrics.duplicates) {
            findings.add(
                TemplateFinding(
                    ruleId = "metrics.duplicate-block",
                    category = TemplateFindingCategory.MAINTAINABILITY,
                    severity = TemplateSeverity.IMPORTANT,
                    title = "重复代码块（出现 ${duplicate.occurrences} 次）",
                    location = "首个重复自第 ${duplicate.firstLine} 行（非空行序）",
                    evidence = duplicate.sample,
                    suggestion = "提取公共函数/工具；参数化差异点",
                    confidence = 0.85
                )
            )
        }
        for (function in metrics.functions.filter { it.hasTooManyParams }.take(2)) {
            findings.add(
                TemplateFinding(
                    ruleId = "metrics.too-many-params",
                    category = TemplateFindingCategory.MAINTAINABILITY,
                    severity = TemplateSeverity.MINOR,
                    title = "参数过多（${function.name} 有 ${function.parameterCount} 个参数）",
                    location = "第 ${function.startLine} 行",
                    evidence = "参数 ${function.parameterCount} 个 > $MAX_PARAMS",
                    suggestion = "收敛为参数对象（data class）或拆分函数",
                    confidence = 0.95
                )
            )
        }
        return findings
    }
}
