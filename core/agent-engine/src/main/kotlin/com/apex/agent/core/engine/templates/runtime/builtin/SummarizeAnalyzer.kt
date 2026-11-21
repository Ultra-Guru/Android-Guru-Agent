package com.apex.agent.core.engine.templates.runtime.builtin

import com.apex.agent.core.engine.templates.runtime.TemplateTextMetrics

/**
 * ═══ 内置模板行为 · 内容总结 —— 确定性分析器（PR 8）═══
 *
 * 总结行为的分析层：长度预算解析、内容画像（体裁判定）、关键事实
 * 抽取、抽取式草稿与输出校验。LLM 拿到的是「已抽取的事实清单 +
 * 已排好的草稿句序」，总结从校对开始而不是从阅读开始。
 *
 * 全部纯函数；断言口径：中文按字符、拉丁按词（与 TemplateTextMetrics 一致）。
 */

/** 长度预算（字符口径，中文友好）。 */
data class LengthBudget(
    val minChars: Int,
    val maxChars: Int,
    /** 解析注记（原样保留长度要求字符串）。 */
    val source: String = "直接给定",
    /** 是否成功解析（失败回落默认）。 */
    val parsed: Boolean = false
) {
    /** 判定文本是否在预算内。 */
    fun contains(chars: Int): Boolean = chars in minChars..maxChars

    companion object {
        /** 缺省预算：200 字以内。 */
        val DEFAULT = LengthBudget(0, 200, "默认", false)
    }
}

/** 内容画像。 */
data class ContentProfile(
    /** 体裁判定。 */
    val genre: Genre,
    /** 段落数（非空行块）。 */
    val paragraphs: Int,
    /** 平均句长（字符）。 */
    val averageSentenceLength: Int,
    /** 事实密度（每百字符的事实条数）。 */
    val factDensityPer100: Double,
    /** 是否以列表为主体。 */
    val listDominated: Boolean
) {
    enum class Genre(val label: String) {
        NARRATIVE("叙事"),
        LIST_BASED("列表型"),
        STRUCTURED_DOC("结构化文档"),
        DIALOGUE("对话"),
        MIXED("混合")
    }
}

/** 关键事实（数字/日期/否定）。 */
data class KeyFact(
    val kind: Kind,
    val text: String,
    /** 所在句序号（-1 = 未定位）。 */
    val sentenceOrdinal: Int
) {
    enum class Kind(val label: String) {
        NUMBER("数字"),
        PERCENT("百分比"),
        DATE("日期"),
        RANGE("区间"),
        RATIO("倍数"),
        NEGATION("否定")
    }
}

/** 抽取式草稿句（原文顺序 + 打分理由）。 */
data class DraftSentence(
    val ordinal: Int,
    val text: String,
    val score: Double,
    val rationale: String
)

/**
 * 总结分析器（纯函数集合）。
 */
object SummarizeAnalyzer {

    // ═══ 长度预算 ═══

    private val CHINESE_DIGITS = mapOf(
        '一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5,
        '六' to 6, '七' to 7, '八' to 8, '九' to 9, '十' to 10,
        '百' to 100, '两' to 2
    )

    /**
     * 解析长度要求：
     * - 「200 字以内 / 不超过 300 字 / ≤300字」→ (0, N)；
     * - 「50-80 字 / 50~80 字 / 50至80字」→ (50, 80)；
     * - 「300 字左右」→ (270, 330)（±10%）；
     * - 中文数字（两百以内）支持；
     * - 解析失败 → DEFAULT。
     */
    fun parseLengthBudget(requirement: String): LengthBudget {
        val text = requirement.replace(" ", "").replace("字数", "")
        if (text.isBlank()) return LengthBudget.DEFAULT

        // 区间形态：50-80 / 50~80 / 50至80
        val range = Regex("""(\d{1,4})(?:-|~|至|到)(\d{1,4})""").find(text)
        if (range != null) {
            val min = range.groupValues[1].toIntOrNull() ?: 0
            val max = range.groupValues[2].toIntOrNull() ?: 0
            if (max > min && max <= 100_000) {
                return LengthBudget(min, max, requirement, true)
            }
        }
        // 上限形态：N以内 / 不超过N / ≤N / N字以内
        val upper = Regex("""(?:不超过|以内|≤|最多)(\d{1,4})|(\d{1,4})(?:字)?以内""").find(text)
            ?: Regex("""(\d{1,4})字?以内""").find(text)
        if (upper != null) {
            val value = (upper.groupValues.filter { it.isNotBlank() }.getOrNull(1))?.toIntOrNull()
            if (value != null && value in 1..100_000) {
                return LengthBudget(0, value, requirement, true)
            }
        }
        // 中文数字 + 以内
        val cnUpper = Regex("""不超过(十|两|一|二|三|四|五|六|七|八|九)百|([一二两三四五六七八九十百]+)以内""").find(text)
        if (cnUpper != null) {
            val raw = cnUpper.groupValues.filter { it.isNotBlank() }.getOrNull(1)
                ?: cnUpper.groupValues.filter { it.isNotBlank() }.getOrNull(2)
            val value = parseChineseNumber(raw)
            if (value != null) {
                return LengthBudget(0, value, requirement, true)
            }
        }
        // 左右形态
        val approx = Regex("""(\d{1,4})字?左右""").find(text)
        if (approx != null) {
            val value = approx.groupValues[1].toIntOrNull()
            if (value != null && value > 0) {
                val slack = (value * 0.1).toInt().coerceAtLeast(1)
                return LengthBudget(value - slack, value + slack, requirement, true)
            }
        }
        return LengthBudget.DEFAULT
    }

    /** 中文数字解析（支持 一~十/两/百 组合的常见形态）。 */
    internal fun parseChineseNumber(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        var total = 0
        var current = 0
        for (ch in raw) {
            when (ch) {
                in CHINESE_DIGITS.keys -> {
                    val value = CHINESE_DIGITS[ch] ?: continue
                    if (value == 100) {
                        current = (if (current == 0) 1 else current) * 100
                        total += current
                        current = 0
                    } else {
                        current = value
                    }
                }
                else -> return null
            }
        }
        return total + current
    }

    // ═══ 内容画像 ═══

    /** 内容画像：体裁判定（结构化文档 > 列表型 > 对话 > 叙事 > 混合）。 */
    fun profile(text: String): ContentProfile {
        if (text.isBlank()) {
            return ContentProfile(ContentProfile.Genre.NARRATIVE, 0, 0, 0.0, false)
        }
        val metrics = TemplateTextMetrics.measure(text)
        val lines = text.lines().filter { it.isNotBlank() }
        val listLines = lines.count { BULLET_LINE.containsMatchIn(it.trim()) }
        val headingLines = lines.count { it.trim().startsWith("#") }
        val speakerTurns = lines.count { DIALOGUE_MARK.containsMatchIn(it) }
        val facts = extractKeyFacts(text)
        val paragraphs = countParagraphs(text)

        val genre = when {
            headingLines >= 2 && metrics.lineCount >= 8 -> ContentProfile.Genre.STRUCTURED_DOC
            listLines >= 3 && listLines >= lines.size / 2 -> ContentProfile.Genre.LIST_BASED
            speakerTurns >= 3 -> ContentProfile.Genre.DIALOGUE
            paragraphs >= 3 && listLines == 0 -> ContentProfile.Genre.MIXED
            else -> ContentProfile.Genre.NARRATIVE
        }
        return ContentProfile(
            genre = genre,
            paragraphs = paragraphs,
            averageSentenceLength = metrics.averageSentenceLength,
            factDensityPer100 = if (metrics.charCount == 0) 0.0 else
                facts.size * 100.0 / metrics.charCount,
            listDominated = listLines >= 3 && listLines >= lines.size / 2
        )
    }

    /** 段落计数（非空行块：空行分隔的非空行连续段）。 */
    internal fun countParagraphs(text: String): Int {
        var count = 0
        var inParagraph = false
        for (line in text.lines()) {
            if (line.isBlank()) {
                inParagraph = false
            } else if (!inParagraph) {
                count++
                inParagraph = true
            }
        }
        return count
    }

    private val DIALOGUE_MARK = Regex("""^(甲|乙|A|B|Q|问|答|:|：)\s*""")

    /** 列表行形态（结论先行检查与要点计数共用）。 */
    private val BULLET_LINE = Regex("""^(?:[-*+]|\d{1,3}[.)])\s+""")

    // ═══ 关键事实抽取 ═══

    /** 事实模式表。 */
    private val NUMBER_FACT = Regex("""\d+(?:\.\d+)?(?:万|亿|千万)?(?:个|人|次|条|台|件|篇|款|项|行|字|分钟|小时|天|周|月|年|元|美元|km|ms|s|MB|GB|%)?""")
    private val PERCENT_FACT = Regex("""\d+(?:\.\d+)?\s*%|百分之[一二三四五六七八九十\d]+""")
    private val DATE_FACT = Regex("""\d{4}\s*年|\d{1,2}\s*月\s*\d{1,2}\s*[日号]|昨天|今天|明天|上周|下周|本月|上月|Q[1-4]|季度""")
    private val RANGE_FACT = Regex("""\d+(?:\.\d+)?\s*(?:-|~|至|到)\s*\d+(?:\.\d+)?""")
    private val RATIO_FACT = Regex("""(?:翻了|增长|提高|下降|减少|降低)\s*[一二两三四五六七八九十\d]+\s*倍|x\s*\d+(?:\.\d+)?""")
    private val NEGATION_FACT = Regex("""不(?:会|能|可|得|要|宜|再|支持|允许|提供|包含|兼容|建议)|未(?:能|经|必)|禁止|无法|不得|严禁|切勿|拒不""")

    /**
     * 关键事实抽取（数字/百分比/日期/区间/倍数/否定句）。
     * 否定句抽取整句（否定语义不能在总结中丢失）。
     */
    fun extractKeyFacts(text: String): List<KeyFact> {
        if (text.isBlank()) return emptyList()
        val facts = mutableListOf<KeyFact>()
        val sentences = TemplateTextMetrics.splitSentences(text)

        for (match in PERCENT_FACT.findAll(text)) {
            facts.add(KeyFact(KeyFact.Kind.PERCENT, match.value, locateSentence(sentences, match.value)))
        }
        for (match in RANGE_FACT.findAll(text)) {
            facts.add(KeyFact(KeyFact.Kind.RANGE, match.value, locateSentence(sentences, match.value)))
        }
        for (match in RATIO_FACT.findAll(text)) {
            facts.add(KeyFact(KeyFact.Kind.RATIO, match.value, locateSentence(sentences, match.value)))
        }
        for (match in DATE_FACT.findAll(text)) {
            facts.add(KeyFact(KeyFact.Kind.DATE, match.value, locateSentence(sentences, match.value)))
        }
        for (match in NUMBER_FACT.findAll(text)) {
            // 去重：已被百分比/区间捕获的数字不再计
            val overlapped = facts.any { fact ->
                fact.kind != KeyFact.Kind.NEGATION && text.indexOf(fact.text).let { idx ->
                    idx >= 0 && idx < text.indexOf(match.value) + match.value.length &&
                        idx + fact.text.length > text.indexOf(match.value)
                }
            }
            if (!overlapped) {
                facts.add(KeyFact(KeyFact.Kind.NUMBER, match.value, locateSentence(sentences, match.value)))
            }
        }
        for ((index, sentence) in sentences.withIndex()) {
            if (NEGATION_FACT.containsMatchIn(sentence.text)) {
                facts.add(KeyFact(KeyFact.Kind.NEGATION, sentence.text, index))
            }
        }
        return facts.take(MAX_FACTS)
    }

    private fun locateSentence(sentences: List<com.apex.agent.core.engine.templates.runtime.TemplateSentence>, needle: String): Int {
        val index = sentences.indexOfFirst { it.text.contains(needle) }
        return if (index >= 0) index else -1
    }

    // ═══ 抽取式草稿 ═══

    /**
     * 抽取式草稿：句级打分（复用 PR3 的 rankSentences）取 top-K，
     * 按原文顺序输出；相邻句去冗余（bigram 重叠 > 60% 留高分句）。
     */
    fun draftSummary(text: String, budget: LengthBudget): List<DraftSentence> {
        if (text.isBlank()) return emptyList()
        val sentences = TemplateTextMetrics.splitSentences(text)
        if (sentences.isEmpty()) return emptyList()
        val scored = TemplateTextMetrics.rankSentences(text)
        val k = deriveDraftCount(budget, sentences.size)
        val top = scored.sortedByDescending { it.score }
            .take(k)
            .sortedBy { it.sentence.ordinal }
        // 相邻去冗余
        val kept = mutableListOf<DraftSentence>()
        for (candidate in top) {
            val overlapWithNeighbor = kept.any { existing ->
                bigramOverlap(existing.text, candidate.sentence.text) > REDUNDANCY_THRESHOLD
            }
            if (!overlapWithNeighbor) {
                kept.add(
                    DraftSentence(
                        ordinal = candidate.sentence.ordinal,
                        text = candidate.sentence.text,
                        score = candidate.score,
                        rationale = candidate.rationale
                    )
                )
            }
        }
        return kept
    }

    /** 草稿句数推导：预算越大句越多（每 60 字一句，2..6 句夹紧）。 */
    internal fun deriveDraftCount(budget: LengthBudget, sentenceCount: Int): Int {
        val byBudget = (budget.maxChars / 60).coerceIn(2, 6)
        return byBudget.coerceAtMost(sentenceCount.coerceAtLeast(1))
    }

    /** bigram 重叠率（Jaccard，CJK 双字 + 拉丁词统一口径）。 */
    internal fun bigramOverlap(a: String, b: String): Double {
        val gramsA = TemplateTextMetrics.extractTerms(a).toSet()
        val gramsB = TemplateTextMetrics.extractTerms(b).toSet()
        if (gramsA.isEmpty() || gramsB.isEmpty()) return 0.0
        val intersection = gramsA.intersect(gramsB).size.toDouble()
        val union = gramsA.union(gramsB).size.toDouble()
        return intersection / union
    }

    // ═══ 输出校验 ═══

    /** 总结输出问题（结构化）。 */
    data class SummaryIssue(
        val kind: Kind,
        val detail: String
    ) {
        enum class Kind(val label: String) {
            MISSING_VERDICT("缺结论"),
            VERDICT_TOO_LONG("结论超预算"),
            TOO_FEW_POINTS("要点过少"),
            TOO_MANY_POINTS("要点过多"),
            FACT_LOST("关键事实丢失"),
            NEGATION_LOST("否定语义丢失")
        }
    }

    /**
     * 总结输出校验：
     * - 首段/首章节是结论句形态（非列表起头）；
     * - 要点条数 3..5；
     * - 结论 + 要点总字符在预算内；
     * - 关键数字事实覆盖率（丢失 > 30% 违规）；
     * - 否定语义保留（原文否定句的核心词在输出中无否定形态 → 警告）。
     */
    fun validateSummary(
        output: String,
        source: String,
        budget: LengthBudget
    ): List<SummaryIssue> {
        val issues = mutableListOf<SummaryIssue>()
        if (output.isBlank()) {
            issues.add(SummaryIssue(SummaryIssue.Kind.MISSING_VERDICT, "输出为空"))
            return issues
        }
        val lines = output.lines().filter { it.isNotBlank() }
        val firstLine = lines.firstOrNull().orEmpty()

        // 1) 结论先行：首行不应是列表
        if (BULLET_LINE.containsMatchIn(firstLine.trim())) {
            issues.add(SummaryIssue(SummaryIssue.Kind.MISSING_VERDICT, "首行是列表，缺一句话结论"))
        }

        // 2) 要点条数
        val bulletLines = lines.drop(1).count {
            BULLET_LINE.containsMatchIn(it.trim())
        }
        if (bulletLines in 1..2) {
            issues.add(SummaryIssue(SummaryIssue.Kind.TOO_FEW_POINTS, "要点仅 $bulletLines 条（要求 3-5 条）"))
        } else if (bulletLines > 5) {
            issues.add(SummaryIssue(SummaryIssue.Kind.TOO_MANY_POINTS, "要点 $bulletLines 条（要求 ≤5 条）"))
        }

        // 3) 长度预算
        val totalChars = output.replace(Regex("""\s"""), "").length
        if (!budget.contains(totalChars)) {
            issues.add(
                SummaryIssue(
                    SummaryIssue.Kind.VERDICT_TOO_LONG,
                    "总结 $totalChars 字（预算 ${budget.minChars}-${budget.maxChars}）"
                )
            )
        }

        // 4) 事实覆盖
        val sourceFacts = extractKeyFacts(source)
            .filter { it.kind != KeyFact.Kind.NEGATION }
        if (sourceFacts.isNotEmpty()) {
            val covered = sourceFacts.count { fact -> output.contains(fact.text) }
            val coverage = covered.toDouble() / sourceFacts.size
            if (coverage < FACT_COVERAGE_THRESHOLD) {
                issues.add(
                    SummaryIssue(
                        SummaryIssue.Kind.FACT_LOST,
                        "关键事实覆盖 ${(coverage * 100).toInt()}%（丢失 " +
                            sourceFacts.filter { !output.contains(it.text) }.take(3)
                                .joinToString("、") { it.text } + "）"
                    )
                )
            }
        }

        // 5) 否定语义
        val negations = extractKeyFacts(source).filter { it.kind == KeyFact.Kind.NEGATION }
        for (negation in negations.take(3)) {
            val coreWord = NEGATION_CORE.find(negation.text)?.value
            if (coreWord != null && !output.contains(coreWord) && !output.contains("不") && !output.contains("未")) {
                issues.add(
                    SummaryIssue(SummaryIssue.Kind.NEGATION_LOST, "否定语义丢失：「${negation.text.take(30)}」")
                )
            }
        }
        return issues
    }

    private val NEGATION_CORE = Regex("""(不能|不会|不可|不得|不宜|不该|不支持|不允许|不提供|不包含|不兼容|不建议|未能|未经|禁止|无法|严禁|切勿)""")

    const val MAX_FACTS = 24
    const val REDUNDANCY_THRESHOLD = 0.6
    const val FACT_COVERAGE_THRESHOLD = 0.7
}
