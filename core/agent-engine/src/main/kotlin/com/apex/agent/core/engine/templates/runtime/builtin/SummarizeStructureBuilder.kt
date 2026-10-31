package com.apex.agent.core.engine.templates.runtime.builtin

/**
 * ═══ 内置模板行为 · 内容总结 —— 结构构建器（PR 17 深化 I）═══
 *
 * 总结行为的第二层确定性引擎：[SummarizeAnalyzer] 管「预算怎么解析、
 * 事实有哪些」，本模块管「要点怎么排、句子怎么选、大纲怎么搭」——
 * 在请求大模型之前把原文拆成句子单元、算出显著度、聚出冗余簇、
 * 按预算分配要点字数，产出一份「照着写就行」的结构化大纲；回包
 * 之后再做要点重叠检测与超预算裁剪。
 *
 * 能力族（全部纯函数、全部确定性）：
 * 1. **句子切分**（sentence split）：中英混合安全的终止符切分
 *    （。！？；… 与 . ! ? ;），小数点/缩写/引号内句号防误切；
 * 2. **术语显著度**（term salience）：中文二元组 + 拉丁词频，
 *    标题词加权、停用词过滤，产出显著术语表；
 * 3. **句子打分**（sentence scoring）：显著度覆盖 × 位置权重 ×
 *    事实密度 × 长度惩罚的四因子线性合成，每句给出理由字符串；
 * 4. **冗余聚类**（redundancy clustering）：字符三元组 Jaccard
 *    相似度 ≥ 阈值的句子聚簇，簇内只保留最高分句——「要点互相
 *    重复」在源头就被掐掉；
 * 5. **要点预算分配**（bullet budget）：总预算按「结论 1/4 + 要点
 *    3/4」分配，要点数夹在 3..5，每条要点得到字数上限；
 * 6. **大纲组装**（outline build）：结论提示 + 有序要点草案 +
 *    冗余簇说明 + 覆盖率估算，直接喂给提示词附录；
 * 7. **回包校验**（output check）：要点间两两重叠检测（Jaccard ≥
 *    阈值报冗余）、单条要点超预算检测；
 * 8. **回包修复**（output repair）：超预算要点在句子边界安全截断。
 *
 * 防御式纪律：空文本/单句文本/超短文本全部有退化路径（单要点大纲/
 * 空簇），任何输入都不抛异常。
 */

/** 显著术语（词频加权后的排序产物）。 */
data class SalientTerm(
    val term: String,
    val weight: Double,
    val occurrences: Int,
    val fromHeading: Boolean
) {
    init {
        require(term.isNotBlank()) { "salient term must not be blank" }
    }
}

/** 句子单元（切分 + 打分的产物）。 */
data class SentenceUnit(
    /** 句序（0 起，原文顺序）。 */
    val ordinal: Int,
    val text: String,
    /** 综合得分（0.0 起步，上不封顶但通常 < 10）。 */
    val score: Double,
    /** 得分理由（人读，喂给提示词与报告）。 */
    val rationale: String,
    /** 是否原文标题行（标题句自动加权）。 */
    val isHeading: Boolean,
    /** 句内数字类事实数。 */
    val factCount: Int,
    /** 是否含否定表述（否定句不允许被裁剪掉否定词）。 */
    val hasNegation: Boolean,
    /** 段落序（0 起；用于位置权重的段落首句加成）。 */
    val paragraphOrdinal: Int
)

/** 冗余簇（相似句聚合；簇内只保留代表句）。 */
data class RedundancyCluster(
    /** 代表句序（簇内最高分句）。 */
    val representativeOrdinal: Int,
    /** 簇内全部句序（含代表句）。 */
    val memberOrdinals: List<Int>,
    /** 代表句与最相似成员的相似度（0.0..1.0）。 */
    val similarity: Double
)

/** 要点预算分配。 */
data class BulletBudget(
    /** 总字数预算（结论 + 要点合计）。 */
    val totalChars: Int,
    /** 结论句字数上限。 */
    val verdictChars: Int,
    /** 要点条数（3..5 夹取）。 */
    val bulletCount: Int,
    /** 单条要点字数上限（均分 3/4 预算，下限保底）。 */
    val perBulletChars: Int
) {
    init {
        require(totalChars >= 0) { "totalChars must be >= 0" }
        require(bulletCount in 1..8) { "bulletCount out of range: $bulletCount" }
    }

    companion object {
        /** 空预算（防御式：预算解析失败时的退化形态）。 */
        fun fallback(totalChars: Int): BulletBudget = BulletBudget(
            totalChars = totalChars,
            verdictChars = (totalChars / 4).coerceAtLeast(MIN_VERDICT_CHARS),
            bulletCount = 3,
            perBulletChars = ((totalChars * 3) / 4 / 3).coerceAtLeast(MIN_BULLET_CHARS)
        )

        private const val MIN_VERDICT_CHARS = 12
        private const val MIN_BULLET_CHARS = 10
    }
}

/** 大纲要点（草案形态，LLM 改写底稿）。 */
data class OutlineBullet(
    /** 来源句序（-1 = 无单一来源句）。 */
    val sourceOrdinal: Int,
    /** 草稿文本（超预算时已安全截断）。 */
    val draftText: String,
    /** 该要点的字数上限。 */
    val charBudget: Int,
    /** 携带的数字事实（必须逐字保留）。 */
    val facts: List<String>,
    /** 入选理由。 */
    val rationale: String
)

/** 组装完成的大纲（喂给提示词附录的结构化产物）。 */
data class SummaryOutline(
    /** 结论句草稿提示（最高分句的压缩形态）。 */
    val verdictHint: String,
    val bullets: List<OutlineBullet>,
    /** 冗余簇清单（报告留痕 + 提示词警告「这些句子说的是同一件事」）。 */
    val clusters: List<RedundancyCluster>,
    val budget: BulletBudget,
    /** 显著术语表（要点应尽量覆盖）。 */
    val salientTerms: List<SalientTerm>,
    /** 覆盖率估算（入选句显著度合计 / 全文显著度合计，0.0..1.0）。 */
    val coverageEstimate: Double
) {
    /** 大纲是否实质可用（至少一条要点）。 */
    val isUsable: Boolean get() = bullets.isNotEmpty()
}

/**
 * 结构构建器主体（纯函数集合）。
 */
object SummarizeStructureBuilder {

    // ═══ 1) 句子切分 ═══

    /** 中英句终止符（全角优先；省略号整体处理）。 */
    private val SENTENCE_TERMINATORS = charArrayOf('。', '！', '？', '；', '!', '?', ';')

    /** 英文缩写点防误切（句点前是这些词时不是句界）。 */
    private val ABBREVIATIONS = setOf(
        "e.g", "i.e", "etc", "vs", "Dr", "Mr", "Mrs", "Ms", "Jr", "Sr",
        "Fig", "No", "Vol", "pp", "ed", "al"
    )

    /** 数字与单位间的句点（版本号/小数/序号）防误切。 */
    private val DIGIT_NEIGHBOR: Regex = Regex("""\d\.\d""")

    /**
     * 句子切分（保留原文子串，不清洗内容）：
     * - 全角终止符直接切；
     * - 半角句点只在「非缩写、非数字邻接」时切；
     * - 换行视作软边界（段落标记同步产出）。
     *
     * @return 句子列表（含段落序，正文序即列表序）
     */
    fun splitSentences(text: String): List<SentenceUnit> {
        if (text.isBlank()) return emptyList()
        val sentences = mutableListOf<SentenceUnit>()
        var paragraph = 0
        var sawContentInParagraph = false
        val builder = StringBuilder()
        var ordinal = 0

        fun flush() {
            val sentence = builder.toString().trim()
            builder.setLength(0)
            if (sentence.isEmpty()) return
            val isHeading = sentence.length <= MAX_HEADING_CHARS &&
                (sentence.startsWith("#") || sentence.endsWith("：") || sentence.endsWith(":"))
            val facts = SummarizeAnalyzer.extractKeyFacts(sentence)
                .filter { it.kind != KeyFact.Kind.NEGATION }
            sentences += SentenceUnit(
                ordinal = ordinal++,
                text = sentence,
                score = 0.0,
                rationale = "",
                isHeading = isHeading,
                factCount = facts.size,
                hasNegation = hasNegationMarker(sentence),
                paragraphOrdinal = paragraph
            )
            sawContentInParagraph = true
        }

        val chars = text.toCharArray()
        var index = 0
        while (index < chars.size) {
            val ch = chars[index]
            if (ch == '\n') {
                flush()
                // 连续换行 = 段落边界
                if (index + 1 < chars.size && chars[index + 1] == '\n') {
                    if (sawContentInParagraph) {
                        paragraph++
                        sawContentInParagraph = false
                    }
                }
                index++
                continue
            }
            builder.append(ch)
            if (ch in SENTENCE_TERMINATORS) {
                flush()
            } else if (ch == '.' && shouldBreakAtPeriod(chars, index)) {
                flush()
            } else if (ch == '…') {
                builder.appendIfNextIsEllipsis(chars, index)
                flush()
                index++
            }
            index++
        }
        flush()
        return sentences
    }

    private fun StringBuilder.appendIfNextIsEllipsis(chars: CharArray, index: Int) {
        if (index + 1 < chars.size && chars[index + 1] == '…') {
            append('…')
        }
    }

    /** 半角句点是否为句界（缩写/小数/序号/单字母缩写防误切）。 */
    private fun shouldBreakAtPeriod(chars: CharArray, index: Int): Boolean {
        // 数字邻接（小数/版本号）不切
        if (index > 0 && index < chars.size - 1 &&
            chars[index - 1].isDigit() && chars[index + 1].isDigit()
        ) {
            return false
        }
        // 单字母缩写点（e.g. / i.e. / U.S.A. 的每个点）不切：
        // 点前是单字母且再往前不是字母（空格/点/行首）
        if (index > 0 && chars[index - 1].isLetter() &&
            (index < 2 || !chars[index - 2].isLetter())
        ) {
            return false
        }
        // 多字母缩写（etc. / vs. / Mr.）不切：句点前的词在缩写表里
        if (index > 1) {
            val wordStart = (index - 1 downTo 0)
                .takeWhile { chars[it].isLetter() }
                .minOrNull() ?: (index - 1)
            val word = chars.concatToString(wordStart, index)
            if (word in ABBREVIATIONS) return false
        }
        // 句点后无内容（行尾）不切（换行 flush 兜底）
        if (index == chars.size - 1) return false
        // 句点后紧跟空白 → 句界
        val after = chars[index + 1]
        return after == ' ' || after == '\t'
    }

    /** 否定标记（中英）。 */
    private val NEGATION_MARKERS = listOf(
        "不", "没有", "无 ", "未", "禁止", "不得", "除外", "拒绝", "避免",
        "not ", "never", "no ", "cannot", "can't", "won't", "don't", "without"
    )

    private fun hasNegationMarker(sentence: String): Boolean {
        val padded = " $sentence "
        return NEGATION_MARKERS.any { padded.contains(it, ignoreCase = true) }
    }

    // ═══ 2) 术语显著度 ═══

    /** 中文停用词（高频功能词，不参与显著度）。 */
    private val ZH_STOPWORDS = setOf(
        "我们", "你们", "他们", "这个", "那个", "一个", "一些", "以及", "但是",
        "因此", "所以", "如果", "虽然", "然后", "还是", "已经", "可以", "进行",
        "通过", "对于", "关于", "并且", "或者", "这些", "那些", "其中", "之后",
        "之前", "同时", "此外", "另外", "目前", "现在", "时候", "方面", "情况",
        "问题", "可能", "需要", "应该", "能够", "使用", "出现", "存在", "如下"
    )

    /** 英文停用词（小写形态）。 */
    private val EN_STOPWORDS = setOf(
        "the", "a", "an", "and", "or", "but", "if", "then", "else", "when",
        "while", "for", "to", "of", "in", "on", "at", "by", "with", "from",
        "as", "is", "are", "was", "were", "be", "been", "being", "have",
        "has", "had", "do", "does", "did", "will", "would", "shall", "should",
        "can", "could", "may", "might", "must", "this", "that", "these",
        "those", "it", "its", "they", "them", "their", "we", "you", "your",
        "he", "she", "his", "her", "not", "no", "also", "very", "just",
        "about", "into", "over", "than", "then", "there", "here", "which",
        "what", "who", "whom", "how", "all", "any", "both", "each", "more",
        "most", "other", "some", "such", "only", "own", "same", "so", "too"
    )

    /** 拉丁词元（小写化；纯词与内部撇号保留）。 */
    private val LATIN_TOKEN: Regex = Regex("""[A-Za-z][A-Za-z'/-]*""")

    /** 中文二元组抽取窗口。 */
    private const val ZH_BIGRAM_MIN = 2

    /** 标题词权重倍数（标题里的术语更重要）。 */
    private const val HEADING_TERM_BOOST = 2.0

    /**
     * 术语显著度：中文按字符二元组、拉丁按词元，停用词过滤后按
     * 「出现次数 × (1 + 标题加成)」加权排序。
     *
     * @param text 全文
     * @param headingLines 标题行集合（来自 Markdown 扫描；可为空）
     * @param maxTerms 返回条数上限
     */
    fun termSalience(
        text: String,
        headingLines: List<String> = emptyList(),
        maxTerms: Int = 12
    ): List<SalientTerm> {
        if (text.isBlank()) return emptyList()
        val headingText = headingLines.joinToString("\n")
        val counts = LinkedHashMap<String, Int>()
        val headingHits = HashSet<String>()

        // 中文二元组
        for (bigram in zhBigrams(text)) {
            counts.merge(bigram, 1, Int::plus)
            if (headingText.contains(bigram)) headingHits += bigram
        }
        // 拉丁词元
        for (word in LATIN_TOKEN.findAll(text).map { it.value.lowercase() }) {
            if (word.length < 2 || word in EN_STOPWORDS) continue
            counts.merge(word, 1, Int::plus)
            if (headingText.contains(word, ignoreCase = true)) headingHits += word
        }

        return counts.entries
            .map { (term, count) ->
                val fromHeading = term in headingHits
                SalientTerm(
                    term = term,
                    weight = count * (if (fromHeading) HEADING_TERM_BOOST else 1.0),
                    occurrences = count,
                    fromHeading = fromHeading
                )
            }
            .filter { it.weight >= MIN_TERM_WEIGHT }
            .sortedWith(compareByDescending<SalientTerm> { it.weight }.thenBy { it.term })
            .take(maxTerms)
    }

    /** 中文二元组（连续 CJK 字符对；过滤停用二元组）。 */
    private fun zhBigrams(text: String): List<String> {
        val bigrams = mutableListOf<String>()
        val chars = text.toCharArray()
        var run = StringBuilder()
        var index = 0
        while (index <= chars.size) {
            val inRun = index < chars.size && isCjkHan(chars[index])
            if (inRun) {
                run.append(chars[index])
            } else {
                // CJK 连续段结束 → 段内二元组
                val block = run.toString()
                run = StringBuilder()
                if (block.length >= ZH_BIGRAM_MIN) {
                    for (i in 0 until block.length - 1) {
                        val bigram = block.substring(i, i + 2)
                        if (bigram !in ZH_STOPWORDS) {
                            bigrams += bigram
                        }
                    }
                }
            }
            index++
        }
        return bigrams
    }

    /** 汉字判定（不含假名——假名走拉丁词元之外的宽容忽略）。 */
    private fun isCjkHan(ch: Char): Boolean =
        ch.code in 0x4E00..0x9FFF || ch.code in 0x3400..0x4DBF

    // ═══ 3) 句子打分 ═══

    /** 位置权重：首段首句 1.6 / 段首句 1.25 / 段中 1.0 / 末段末句 0.9。 */
    private fun positionWeight(sentence: SentenceUnit, total: Int): Double = when {
        sentence.paragraphOrdinal == 0 && sentence.ordinal <= 1 -> 1.6
        sentence.isHeading -> 1.5
        sentence.paragraphOrdinal == 0 -> 1.2
        sentence.ordinal >= total - 1 -> 0.9
        else -> 1.0
    }

    /** 长度惩罚：超长句（>120 字符）按比例降权（信息密度稀释）。 */
    private fun lengthPenalty(sentence: String): Double {
        if (sentence.length <= 120) return 1.0
        return 120.0 / sentence.length
    }

    /**
     * 句子打分（四因子线性合成）：
     * 显著术语覆盖 × 位置权重 × (1 + 0.15 × 事实数) × 长度惩罚。
     * 每句产出理由字符串（喂报告与提示词）。
     */
    fun scoreSentences(
        sentences: List<SentenceUnit>,
        terms: List<SalientTerm>
    ): List<SentenceUnit> {
        if (sentences.isEmpty()) return emptyList()
        val termSet = terms.map { it.term to it.weight }.toMap()
        return sentences.map { sentence ->
            val covered = termSet.entries.filter { sentence.text.contains(it.key, ignoreCase = true) }
            val salienceScore = covered.sumOf { it.value }
            val position = positionWeight(sentence, sentences.size)
            val factBoost = 1.0 + 0.15 * sentence.factCount
            val lengthFactor = lengthPenalty(sentence.text)
            val score = salienceScore * position * factBoost * lengthFactor
            val reasons = mutableListOf<String>()
            if (covered.isNotEmpty()) {
                reasons += "显著词 ${covered.size} 个（" + covered.take(3).joinToString("/") { it.key } + "）"
            }
            if (position > 1.0) reasons += "位置权重 %.2f".format(position)
            if (sentence.factCount > 0) reasons += "数字事实 ${sentence.factCount} 处"
            if (lengthFactor < 1.0) reasons += "超长句降权"
            if (sentence.hasNegation) reasons += "含否定（语义必须保留）"
            sentence.copy(
                score = score,
                rationale = if (reasons.isEmpty()) "常规信息句" else reasons.joinToString("；")
            )
        }
    }

    // ═══ 4) 冗余聚类 ═══

    /** 字符三元组集合（相似度计算的底座）。 */
    fun trigrams(text: String): Set<String> {
        if (text.length < 3) return setOf(text)
        val normalized = text.filter { !it.isWhitespace() }
        if (normalized.length < 3) return setOf(normalized)
        return (0..normalized.length - 3).map { normalized.substring(it, it + 3) }.toSet()
    }

    /** Jaccard 相似度（三元组集合；空集合对返回 0）。 */
    fun similarity(a: String, b: String): Double {
        val setA = trigrams(a)
        val setB = trigrams(b)
        if (setA.isEmpty() || setB.isEmpty()) return 0.0
        val intersection = setA.intersect(setB).size
        val union = setA.size + setB.size - intersection
        return if (union == 0) 0.0 else intersection.toDouble() / union
    }

    /** 聚类相似度阈值（≥ 视为同一件事的两种说法）。 */
    const val REDUNDANCY_THRESHOLD = 0.55

    /**
     * 冗余聚类：贪心单链接——按句序扫描，与已入簇句子相似度达标即入簇；
     * 簇代表 = 簇内最高分句。只返回规模 ≥ 2 的簇。
     */
    fun clusterRedundancy(sentences: List<SentenceUnit>): List<RedundancyCluster> {
        if (sentences.size < 2) return emptyList()
        val assigned = HashMap<Int, Int>() // 句序 → 簇号
        val clusters = mutableListOf<MutableList<SentenceUnit>>()
        for (sentence in sentences) {
            var targetCluster: Int? = null
            var bestSimilarity = 0.0
            for ((clusterIndex, cluster) in clusters.withIndex()) {
                val clusterMax = cluster.maxOf { similarity(it.text, sentence.text) }
                if (clusterMax >= REDUNDANCY_THRESHOLD && clusterMax > bestSimilarity) {
                    bestSimilarity = clusterMax
                    targetCluster = clusterIndex
                }
            }
            if (targetCluster != null) {
                clusters[targetCluster] += sentence
                assigned[sentence.ordinal] = targetCluster
            } else {
                clusters += mutableListOf(sentence)
                assigned[sentence.ordinal] = clusters.size - 1
            }
        }
        return clusters
            .filter { it.size >= 2 }
            .map { cluster ->
                val representative = cluster.maxByOrNull { it.score } ?: cluster.first()
                val memberSimilarity = cluster
                    .filter { it.ordinal != representative.ordinal }
                    .maxOfOrNull { similarity(it.text, representative.text) } ?: 0.0
                RedundancyCluster(
                    representativeOrdinal = representative.ordinal,
                    memberOrdinals = cluster.map { it.ordinal }.sorted(),
                    similarity = memberSimilarity
                )
            }
            .sortedBy { it.representativeOrdinal }
    }

    // ═══ 5) 要点预算分配 ═══

    /**
     * 预算分配：结论占 1/4（下限 12 字），要点合计占 3/4，要点条数
     * 取 min(5, max(3, 候选句数))，单条上限 = 要点总预算 / 条数（下限 10）。
     */
    fun allocateBudget(budget: LengthBudget, candidateCount: Int): BulletBudget {
        val total = budget.maxChars.coerceAtLeast(0)
        if (total <= 0) return BulletBudget.fallback(LengthBudget.DEFAULT.maxChars)
        val verdictChars = (total / 4).coerceAtLeast(12).coerceAtMost(total)
        val bulletTotal = (total - verdictChars).coerceAtLeast(0)
        val bulletCount = candidateCount.coerceIn(3, 5)
        val perBullet = if (bulletCount == 0) 0 else (bulletTotal / bulletCount).coerceAtLeast(10)
        return BulletBudget(
            totalChars = total,
            verdictChars = verdictChars,
            bulletCount = bulletCount,
            perBulletChars = perBullet
        )
    }

    // ═══ 6) 大纲组装 ═══

    /** 单要点草案的最大句数（超长句群截断到该值）。 */
    private const val MAX_BULLET_SENTENCES = 2

    /**
     * 大纲组装主入口（ANALYZE 阶段调用）：
     * 切句 → 显著度 → 打分 → 聚类 → 预算 → 按分选点 → 安全截断。
     *
     * @param content 原文（规范化后）
     * @param budget 长度预算（来自 [SummarizeAnalyzer.parseLengthBudget]）
     * @param headingLines Markdown 标题行（显著度加成用；可为空）
     */
    fun buildOutline(
        content: String,
        budget: LengthBudget,
        headingLines: List<String> = emptyList()
    ): SummaryOutline {
        if (content.isBlank()) {
            return SummaryOutline(
                verdictHint = "",
                bullets = emptyList(),
                clusters = emptyList(),
                budget = BulletBudget.fallback(budget.maxChars),
                salientTerms = emptyList(),
                coverageEstimate = 0.0
            )
        }
        val terms = termSalience(content, headingLines)
        val scored = scoreSentences(splitSentences(content), terms)
        val clusters = clusterRedundancy(scored)

        // 冗余簇代表句优先（簇成员除代表外不再入点）
        val redundantMembers = clusters.flatMap { it.memberOrdinals }.toSet()
        val representatives = clusters.map { it.representativeOrdinal }.toSet()
        val eligible = scored.filter { sentence ->
            sentence.ordinal !in redundantMembers || sentence.ordinal in representatives
        }

        val allocation = allocateBudget(budget, eligible.size)
        val selected = eligible
            .sortedWith(compareByDescending<SentenceUnit> { it.score }.thenBy { it.ordinal })
            .take(allocation.bulletCount)
            .sortedBy { it.ordinal }

        val totalScore = scored.sumOf { it.score }.takeIf { it > 0 } ?: 1.0
        val selectedScore = selected.sumOf { it.score }

        val bullets = selected.map { sentence ->
            val facts = SummarizeAnalyzer.extractKeyFacts(sentence.text)
                .filter { it.kind != KeyFact.Kind.NEGATION }
                .map { it.text }
            OutlineBullet(
                sourceOrdinal = sentence.ordinal,
                draftText = trimBullet(sentence.text, allocation.perBulletChars),
                charBudget = allocation.perBulletChars,
                facts = facts,
                rationale = sentence.rationale
            )
        }

        val verdictSource = selected.maxByOrNull { it.score } ?: scored.first()
        return SummaryOutline(
            verdictHint = trimBullet(verdictSource.text, allocation.verdictChars),
            bullets = bullets,
            clusters = clusters,
            budget = allocation,
            salientTerms = terms,
            coverageEstimate = (selectedScore / totalScore).coerceIn(0.0, 1.0)
        )
    }

    // ═══ 7) 回包校验 ═══

    /** 要点间重叠阈值（两要点 Jaccard ≥ 此值 = 内容重复）。 */
    const val BULLET_OVERLAP_THRESHOLD = 0.5

    /** 一对重叠的要点（回包校验产物）。 */
    data class BulletOverlap(val firstIndex: Int, val secondIndex: Int, val similarity: Double)

    /**
     * 要点重叠检测：LLM 常犯的「三条要点一个意思」在代码侧直接量化。
     *
     * @param bullets 回包要点列表（纯文本，不含项目符号）
     * @return 重叠对（索引对 + 相似度），空 = 无重叠
     */
    fun checkBulletOverlap(bullets: List<String>): List<BulletOverlap> {
        if (bullets.size < 2) return emptyList()
        val overlaps = mutableListOf<BulletOverlap>()
        for (first in bullets.indices) {
            for (second in first + 1 until bullets.size) {
                val score = similarity(bullets[first], bullets[second])
                if (score >= BULLET_OVERLAP_THRESHOLD) {
                    overlaps += BulletOverlap(first, second, score)
                }
            }
        }
        return overlaps.sortedByDescending { it.similarity }
    }

    /**
     * 超预算要点检测（逐条比对字数上限；超限即报告）。
     *
     * @return (要点索引, 实际字数, 上限) 三元组列表
     */
    fun checkBulletBudget(bullets: List<String>, budget: BulletBudget): List<Triple<Int, Int, Int>> =
        bullets.withIndex()
            .filter { it.value.length > budget.perBulletChars }
            .map { Triple(it.index, it.value.length, budget.perBulletChars) }

    // ═══ 8) 回包修复 ═══

    /**
     * 要点安全截断：在预算内最后一个句子边界截断；无句界时在最后一个
     * 逗号/分号/空格边界截断；极端情况硬截并补省略号。否定句永不截断
     * 否定词之前（简单保证：截断结果若丢否定标记则整体保留前半 + 省略）。
     */
    fun trimBullet(text: String, maxChars: Int): String {
        val clean = text.trim()
        if (maxChars <= 0 || clean.length <= maxChars) return clean
        val window = clean.take(maxChars)
        val cutCandidates = listOf('。', '；', '，', ',', ';', ' ')
        var cutAt = -1
        for (ch in cutCandidates) {
            val found = window.lastIndexOf(ch)
            if (found > MIN_KEEP_CHARS) {
                cutAt = maxOf(cutAt, found)
            }
        }
        val kept = if (cutAt > MIN_KEEP_CHARS) {
            window.take(cutAt)
        } else {
            window
        }
        // 否定保护：截断丢了否定语义 → 退回硬截（保留否定词所在前段）
        if (hasNegationMarker(clean) && !hasNegationMarker(kept)) {
            val negationIndex = NEGATION_MARKERS.firstNotNullOfOrNull { marker ->
                clean.indexOf(marker, ignoreCase = true).takeIf { it >= 0 }
            } ?: -1
            if (negationIndex in 0 until maxChars) {
                return clean.take(negationIndex + 4).trimEnd('，', '。', '；', ' ') + "…"
            }
        }
        return kept.trimEnd('，', '。', '；', '，', ' ', ',') + if (kept.length < clean.length) "…" else ""
    }

    /** 回包要点清单整体修复（超预算逐条截断；保持顺序与条数）。 */
    fun repairBullets(bullets: List<String>, budget: BulletBudget): List<String> =
        bullets.map { trimBullet(it, budget.perBulletChars) }

    // ═══ 常量 ═══

    private const val MAX_HEADING_CHARS = 60
    private const val MIN_TERM_WEIGHT = 2.0
    private const val MIN_KEEP_CHARS = 8
}
