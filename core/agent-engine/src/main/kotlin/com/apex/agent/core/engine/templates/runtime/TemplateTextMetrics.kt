package com.apex.agent.core.engine.templates.runtime

/**
 * ═══ 模板行为运行时 —— 共享分析工具箱 · 文本度量（PR 3）═══
 *
 * 面向自然语言输入的确定性度量：断句（CJK 感知）、分词计数、停用词
 * 过滤、词频统计与句级关键度打分。总结 / 翻译 / 文档 / BUG 报告等
 * 行为都基于这里的一手指标构建自己的分析结论。
 *
 * 纪律：
 * - 全部纯函数（无状态、无 IO、无随机）—— 同输入必同输出，可离线测试；
 * - 中文与西文混排按「CJK 字符逐字 + 拉丁按词」双口径统计，不断章取义；
 * - 任何输入（空串/超长/乱码）都给出安全的空指标，绝不抛。
 */

/** 断句产物。 */
data class TemplateSentence(
    val text: String,
    val startIndex: Int,
    val ordinal: Int
) {
    /** 字符长度（CJK 口径）。 */
    val length: Int get() = text.length
}

/** 词频条目。 */
data class TemplateTermCount(
    val term: String,
    val count: Int,
    /** 文档频率近似：出现过的句子数（关键度打分用）。 */
    val sentenceSpread: Int
)

/**
 * 文本度量结果。
 *
 * @param charCount 字符总数（不含首尾空白）
 * @param lineCount 行数
 * @param nonBlankLineCount 非空行数
 * @param sentenceCount 断句数
 * @param averageSentenceLength 平均句长（字符）
 * @param cjkCharCount CJK 字符数
 * @param latinWordCount 拉丁词数
 * @param digitCount 数字字符数
 * @param punctuationCount 标点字符数
 * @param topTerms 高频词（停用词过滤后，按词频降序，至多 [TOP_TERMS_LIMIT] 个）
 */
data class TemplateTextMetricsResult(
    val charCount: Int,
    val lineCount: Int,
    val nonBlankLineCount: Int,
    val sentenceCount: Int,
    val averageSentenceLength: Int,
    val cjkCharCount: Int,
    val latinWordCount: Int,
    val digitCount: Int,
    val punctuationCount: Int,
    val topTerms: List<TemplateTermCount>
) {
    /** 估算阅读时长（分钟，中文 300 字/分钟 + 拉丁词 200/分钟 的折中口径）。 */
    val estimatedReadingMinutes: Int
        get() {
            val units = cjkCharCount + latinWordCount
            return if (units <= 0) 0 else 1 + (units - 1) / READING_UNITS_PER_MINUTE
        }

    companion object {
        const val READING_UNITS_PER_MINUTE = 300
        const val TOP_TERMS_LIMIT = 12

        /** 空指标（防御式默认）。 */
        fun empty(): TemplateTextMetricsResult = TemplateTextMetricsResult(
            charCount = 0, lineCount = 0, nonBlankLineCount = 0, sentenceCount = 0,
            averageSentenceLength = 0, cjkCharCount = 0, latinWordCount = 0,
            digitCount = 0, punctuationCount = 0, topTerms = emptyList()
        )
    }
}

/** 句级关键度打分行（抽取式预分析的产物）。 */
data class TemplateSentenceScore(
    val sentence: TemplateSentence,
    val score: Double,
    /** 得分构成说明（报告/调试用，单行）。 */
    val rationale: String
)

/**
 * 文本度量工具箱。
 *
 * 用法：`TemplateTextMetrics.measure(text)` 一次拿全量指标；
 * 关键度打分用 `TemplateTextMetrics.rankSentences(text, metrics)`。
 */
object TemplateTextMetrics {

    /** CJK 统一表意文字区间（常用汉字主体，覆盖足够分析用）。 */
    private val CJK_RANGE = Regex("[\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF]")

    /** 拉丁词（字母数字混排，含撇号连接）。 */
    private val LATIN_WORD = Regex("[A-Za-z][A-Za-z0-9'_-]*")

    /** 断句边界：CJK 句读 + 西文句读 + 换行。 */
    private val SENTENCE_BOUNDARY = Regex("([。！？!?；;]|[.!?](\\s|$)|\\n+)")

    /** 中文停用词（高频功能词，打分时剔除）。 */
    private val STOPWORDS_ZH = setOf(
        "的", "了", "和", "是", "就", "都", "而", "及", "与", "或", "也", "在",
        "有", "对", "从", "到", "为", "以", "被", "把", "让", "向", "但", "不",
        "这", "那", "你", "我", "他", "她", "它", "们", "个", "上", "下", "中",
        "会", "能", "要", "可以", "我们", "你们", "他们", "以及", "因此", "所以",
        "但是", "如果", "因为", "还有", "以及", "之类", "什么", "怎么", "这个",
        "一个", "一些", "非常", "进行", "通过", "对于", "已经", "可能", "需要"
    )

    /** 英文停用词（小写形态）。 */
    private val STOPWORDS_EN = setOf(
        "the", "a", "an", "and", "or", "but", "if", "then", "else", "when", "at",
        "by", "for", "with", "about", "into", "through", "during", "to", "from",
        "in", "on", "of", "is", "are", "was", "were", "be", "been", "being",
        "have", "has", "had", "do", "does", "did", "will", "would", "shall",
        "should", "can", "could", "may", "might", "must", "this", "that", "these",
        "those", "it", "its", "as", "not", "no", "yes", "we", "you", "they",
        "he", "she", "them", "his", "her", "their", "there", "here", "what",
        "which", "who", "whom", "how", "why", "when", "where", "all", "any",
        "both", "each", "few", "more", "most", "other", "some", "such", "than",
        "too", "very", "just", "also", "only", "own", "same", "so", "up", "down",
        "out", "off", "over", "under", "again", "further", "once"
    )

    /** 是否为停用词（中英双语判定）。 */
    fun isStopword(term: String): Boolean {
        if (term.isEmpty()) return true
        if (CJK_RANGE.containsMatchIn(term)) return term in STOPWORDS_ZH
        return term.lowercase() in STOPWORDS_EN
    }

    /** 断句：CJK 句读 / 西文句读（含句点+空白）/ 换行三种边界。 */
    fun splitSentences(text: String): List<TemplateSentence> {
        if (text.isBlank()) return emptyList()
        val sentences = mutableListOf<TemplateSentence>()
        var cursor = 0
        var ordinal = 0
        for (match in SENTENCE_BOUNDARY.findAll(text)) {
            val end = match.range.first + 1
            val chunk = text.substring(cursor, end).trim()
            if (chunk.isNotEmpty()) {
                sentences.add(TemplateSentence(chunk, cursor, ordinal++))
            }
            cursor = match.range.last + 1
        }
        val tail = text.substring(cursor).trim()
        if (tail.isNotEmpty()) {
            sentences.add(TemplateSentence(tail, cursor, ordinal))
        }
        return sentences
    }

    /** CJK 字符计数。 */
    fun countCjk(text: String): Int = CJK_RANGE.findAll(text).count()

    /** 拉丁词列表（原文大小写保留）。 */
    fun latinWords(text: String): List<String> = LATIN_WORD.findAll(text).map { it.value }.toList()

    /** 标点计数（中西文常用标点；曲引号用 Unicode 转义防串内引号截断）。 */
    fun countPunctuation(text: String): Int =
        text.count { it in "，。！？；：、\u2018\u2019\u201C\u201D（）,.!?;:()[]{}<>\"'-—…·/\\|@#$%^&*+=~`" }

    /**
     * 全量指标测量。
     */
    fun measure(text: String): TemplateTextMetricsResult {
        if (text.isBlank()) return TemplateTextMetricsResult.empty()
        val trimmed = text.trim()
        val lines = trimmed.lines()
        val sentences = splitSentences(trimmed)
        val cjk = countCjk(trimmed)
        val latin = latinWords(trimmed)
        val digits = trimmed.count { it.isDigit() }
        val punctuation = countPunctuation(trimmed)
        val nonBlank = lines.count { it.isNotBlank() }
        return TemplateTextMetricsResult(
            charCount = trimmed.length,
            lineCount = lines.size,
            nonBlankLineCount = nonBlank,
            sentenceCount = sentences.size,
            averageSentenceLength = if (sentences.isEmpty()) 0 else trimmed.length / sentences.size,
            cjkCharCount = cjk,
            latinWordCount = latin.size,
            digitCount = digits,
            punctuationCount = punctuation,
            topTerms = topTerms(sentences)
        )
    }

    /**
     * 高频词统计（停用词过滤；CJK 按双字滑窗，拉丁按整词）。
     *
     * CJK 用 bigram 而非单字：中文单字信息量太低，双字词才是语义单元，
     * 这是轻量分析的标准折中（不上分词器依赖）。
     */
    fun topTerms(
        sentences: List<TemplateSentence>,
        limit: Int = TemplateTextMetricsResult.TOP_TERMS_LIMIT
    ): List<TemplateTermCount> {
        if (sentences.isEmpty()) return emptyList()
        val counts = LinkedHashMap<String, Int>()
        val spread = HashMap<String, MutableSet<Int>>()
        for (sentence in sentences) {
            for (term in extractTerms(sentence.text)) {
                counts[term] = (counts[term] ?: 0) + 1
                // sentenceSpread 按句子序号去重（同句重复出现不重复计数）
                spread.getOrPut(term) { mutableSetOf() }.add(sentence.ordinal)
            }
        }
        return counts.entries
            .map { (term, count) ->
                TemplateTermCount(term, count, spread[term]?.size ?: 0)
            }
            .filter { !isStopword(it.term) && it.term.length > 1 }
            .sortedWith(
                compareByDescending<TemplateTermCount> { it.count }.thenBy { it.term }
            )
            .take(limit)
    }

    /** 术语抽取：拉丁整词（小写化）+ CJK 二字与三字滑窗（三字词如「数据层」「业务层」
     *  只有靠 trigram 才能整词命中，单靠 bigram 会碎成「数据/据层」）。 */
    fun extractTerms(text: String): List<String> {
        val terms = mutableListOf<String>()
        for (word in latinWords(text)) {
            terms.add(word.lowercase())
        }
        val cjkChars = StringBuilder()
        for (ch in text) {
            if (CJK_RANGE.matches(ch.toString())) cjkChars.append(ch) else {
                appendCjkNgrams(cjkChars, terms)
                cjkChars.clear()
            }
        }
        appendCjkNgrams(cjkChars, terms)
        return terms
    }

    private fun appendCjkNgrams(buffer: StringBuilder, out: MutableList<String>) {
        if (buffer.length < 2) return
        for (i in 0 until buffer.length - 1) {
            out.add(buffer.substring(i, i + 2))
        }
        for (i in 0 until buffer.length - 2) {
            out.add(buffer.substring(i, i + 3))
        }
    }

    /**
     * 句级关键度打分（抽取式预分析）。
     *
     * 得分构成（可解释，权重见常量）：
     * - 词频得分：句中高频词命中数（归一）；
     * - 位置得分：首句与末句天然更重要（新闻式结构）；
     * - 数值得分：含具体数字/比例的句子更可保留（事实密度）；
     * - 长度惩罚：过短（<6 字）或过长（>120 字）的句子扣分。
     */
    fun rankSentences(
        text: String,
        metrics: TemplateTextMetricsResult = measure(text)
    ): List<TemplateSentenceScore> {
        val sentences = splitSentences(text)
        if (sentences.isEmpty()) return emptyList()
        val termWeight = metrics.topTerms.associate { it.term to it.count.toDouble() }
        val maxWeight = termWeight.values.maxOrNull() ?: 0.0
        val lastOrdinal = sentences.maxOf { it.ordinal }
        return sentences.map { sentence ->
            val terms = extractTerms(sentence.text).filter { it in termWeight }
            // 词频得分：命中词条的总权重，按「饱和词数」归一 —— 覆盖 3 个满权
            // 词条即打满，避免「每个句子都拿到归一满分」的退化形态。
            val matchedWeight = terms.sumOf { termWeight[it] ?: 0.0 }
            val termScore = if (maxWeight <= 0.0) 0.0 else {
                (matchedWeight / (maxWeight * TERM_SATURATION)).coerceAtMost(1.0)
            }
            val positionScore = when (sentence.ordinal) {
                0 -> 1.0
                lastOrdinal -> 0.6
                else -> 0.0
            }
            val numericScore = if (sentence.text.any { it.isDigit() }) 0.5 else 0.0
            val lengthPenalty = when {
                sentence.length < 6 -> -0.5
                sentence.length > 120 -> -0.3
                else -> 0.0
            }
            val score = WEIGHT_TERM * termScore + WEIGHT_POSITION * positionScore +
                WEIGHT_NUMERIC * numericScore + lengthPenalty
            val rationale = buildString {
                append("词频${"%.2f".format(termScore)}")
                if (positionScore > 0) append("|位置${"%.1f".format(positionScore)}")
                if (numericScore > 0) append("|含数值")
                if (lengthPenalty < 0) append("|长度惩罚")
            }
            TemplateSentenceScore(sentence, score.coerceIn(0.0, 10.0), rationale)
        }
    }

    /** 打分权重（常量集中，调参只动这里）。 */
    const val WEIGHT_TERM = 1.0
    const val WEIGHT_POSITION = 0.4
    const val WEIGHT_NUMERIC = 0.3

    /** 词频得分饱和词数：命中这么多个满权词条即打满 1.0。 */
    const val TERM_SATURATION = 3.0
}
