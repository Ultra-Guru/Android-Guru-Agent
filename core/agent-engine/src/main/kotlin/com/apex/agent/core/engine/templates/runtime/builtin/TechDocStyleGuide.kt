package com.apex.agent.core.engine.templates.runtime.builtin

/**
 * ═══ 内置模板行为 · 技术文档 —— 风格指南与文档体检（PR 19 深化 III）═══
 *
 * 技术文档行为的第二层确定性引擎：[TechDocPlanner] 管「大纲长什么样、
 * 受众是谁」，本模块管「文字本身写得好不好」——把技术写作的风格
 * 规则（句长预算/术语纪律/示例规范/引用完整性）全部代码化，回包
 * 之后对文档全文做体检，能机械修的修，不能修的给量化证据。
 *
 * 能力族（全部纯函数、全部确定性）：
 * 1. **文体规则**（style rules）：句长预算（中文 40 字/句硬限）、
 *    段落长度（连排 8 行预警）、被动语态密度、口语化虚词
 *    （其实/ basically / 也就是说）计数；
 * 2. **文档类型模板**（doc type templates）：概念型/操作指南/API
 *    参考/排障手册四型的必备章节与内容纪律（喂给大纲做校验基准）；
 * 3. **示例校验**（example validation）：围栏代码块必须有语言标注、
 *    禁伪代码标记（TODO/xxx/…占位）、示例长度预算、
 *    可运行形态粗判（含赋值/调用/输出的启发）；
 * 4. **术语表构建**（glossary builder）：候选术语抽取（中英对照、
 *    首字母缩写）+ 首次使用先定义检测（术语先出现后解释 = 违规）；
 * 5. **可读性评分**（readability score）：句长方差、示例占比、
 *    章节均衡度、标题层级规范四因子 → 0-100；
 * 6. **文档 lint**（doc lint）：标题层级跳级（h1→h3）、重复标题、
 *    空章节、Markdown 引用残缺（[](...) 缺半边）。
 *
 * 防御式纪律：空文档/纯代码输入全部有退化路径，绝不抛。
 */

/** 文档类型（决定必备章节基准）。 */
enum class DocType(val label: String) {
    CONCEPT("概念型"),
    HOW_TO("操作指南"),
    API_REF("API 参考"),
    TROUBLESHOOT("排障手册");

    companion object {
        /** 从主题与内容猜类型（关键词启发）。 */
        fun inferFrom(text: String): DocType {
            val lowered = text.lowercase()
            return when {
                lowered.contains("排障") || lowered.contains("故障") ||
                    lowered.contains("troubleshoot") || lowered.contains("报错") -> TROUBLESHOOT
                lowered.contains("api") || lowered.contains("接口") ||
                    lowered.contains("sdk") || lowered.contains("端点") -> API_REF
                lowered.contains("如何") || lowered.contains("步骤") ||
                    lowered.contains("how to") || lowered.contains("指南") ||
                    lowered.contains("安装") || lowered.contains("配置") -> HOW_TO
                else -> CONCEPT
            }
        }
    }
}

/** 一条风格/体检发现。 */
data class DocStyleIssue(
    val ruleId: String,
    val kind: Kind,
    val detail: String,
    val evidence: String,
    val repairable: Boolean
) {
    enum class Kind(val label: String) {
        SENTENCE_TOO_LONG("句长超限"),
        PARAGRAPH_TOO_LONG("段落过长"),
        FILLER_WORDS("口语虚词"),
        EXAMPLE_NO_LANG("示例缺语言标注"),
        EXAMPLE_PLACEHOLDER("示例含占位符"),
        EXAMPLE_TOO_LONG("示例过长"),
        TERM_UNDEFINED_FIRST("术语先用后定义"),
        HEADING_LEVEL_JUMP("标题层级跳级"),
        DUPLICATE_HEADING("重复标题"),
        EMPTY_SECTION("空章节"),
        BROKEN_LINK("引用残缺")
    }
}

/** 术语表条目。 */
data class DocGlossaryEntry(
    val term: String,
    /** 首次出现位置（字符序）。 */
    val firstOccurrence: Int,
    /** 是否在首次出现前有定义（术语表章节或「（…）」括注）。 */
    val definedBeforeFirstUse: Boolean
)

/** 可读性评分明细。 */
data class ReadabilityReport(
    val score: Int,
    /** 平均句长（字符）。 */
    val averageSentenceLength: Int,
    /** 最长句长度。 */
    val longestSentenceLength: Int,
    /** 示例代码占比（0-100）。 */
    val codeRatioPercent: Int,
    /** 各因子得分（喂报告解释）。 */
    val factorScores: Map<String, Int>
) {
    companion object {
        fun empty(): ReadabilityReport = ReadabilityReport(
            score = 0, averageSentenceLength = 0, longestSentenceLength = 0,
            codeRatioPercent = 0, factorScores = emptyMap()
        )
    }
}

/**
 * 风格指南主体（纯函数集合）。
 */
object TechDocStyleGuide {

    // ═══ 1) 文体规则 ═══

    /** 中文句长硬限（字符；超限 = 违规）。 */
    const val SENTENCE_HARD_LIMIT = 60

    /** 段落连排预警行数。 */
    const val PARAGRAPH_WARN_LINES = 10

    /** 口语虚词表（中英）。 */
    private val FILLER_WORDS = listOf(
        "其实", "一般来说", "基本上", "简单来说", "也就是说", "大家都知道",
        "废话不多说", "bascially" /* 常见拼写 */, "actually", "in fact", "you know"
    )

    /**
     * 文体规则检查（正文段落，代码围栏剥除后再量）。
     */
    fun checkStyle(text: String): List<DocStyleIssue> {
        val issues = mutableListOf<DocStyleIssue>()
        if (text.isBlank()) return issues
        val prose = stripFences(text)
        // 句长（按 。！？；.!?; 切）
        for (sentence in splitSentences(prose)) {
            if (sentence.length > SENTENCE_HARD_LIMIT) {
                issues += DocStyleIssue(
                    ruleId = "doc.sentence-too-long",
                    kind = DocStyleIssue.Kind.SENTENCE_TOO_LONG,
                    detail = "句子 ${sentence.length} 字超过 $SENTENCE_HARD_LIMIT 上限 —— 长句拆短句（一意一句）",
                    evidence = sentence.take(40) + "…",
                    repairable = false
                )
            }
        }
        // 段落连排
        var runLength = 0
        for (line in prose.lines()) {
            runLength = if (line.isBlank()) 0 else runLength + 1
            if (runLength == PARAGRAPH_WARN_LINES) {
                issues += DocStyleIssue(
                    ruleId = "doc.paragraph-too-long",
                    kind = DocStyleIssue.Kind.PARAGRAPH_TOO_LONG,
                    detail = "段落连排 $PARAGRAPH_WARN_LINES 行以上 —— 按一屏一主题切分",
                    evidence = "",
                    repairable = true
                )
            }
        }
        // 虚词
        val lowerProse = prose.lowercase()
        for (filler in FILLER_WORDS) {
            val count = countOccurrences(lowerProse, filler.lowercase())
            if (count > 0) {
                issues += DocStyleIssue(
                    ruleId = "doc.filler-word",
                    kind = DocStyleIssue.Kind.FILLER_WORDS,
                    detail = "口语虚词「$filler」出现 $count 次 —— 技术文档删虚词直述",
                    evidence = filler,
                    repairable = true
                )
            }
        }
        return issues
    }

    private fun countOccurrences(text: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var index = text.indexOf(needle)
        while (index >= 0) {
            count++
            index = text.indexOf(needle, index + needle.length)
        }
        return count
    }

    private fun splitSentences(prose: String): List<String> =
        prose.split(Regex("""(?<=[。！？；!?;])\s*"""))
            .map { it.trim() }
            .filter { it.length > SENTENCE_HARD_LIMIT }

    private fun stripFences(text: String): String =
        Regex("""```.*?```""", RegexOption.DOT_MATCHES_ALL).replace(text, "\n")

    // ═══ 2) 文档类型模板 ═══

    /** 各类型的必备章节基准（标题匹配用别名集）。 */
    fun requiredSectionsOf(type: DocType): List<String> = when (type) {
        DocType.CONCEPT -> listOf("概述", "背景", "核心概念", "示例", "常见问题")
        DocType.HOW_TO -> listOf("概述", "前置条件", "操作步骤", "验证", "回退/故障处理")
        DocType.API_REF -> listOf("概述", "认证与版本", "端点参考", "错误码", "调用示例")
        DocType.TROUBLESHOOT -> listOf("概述", "症状定位", "常见故障与处置", "日志与诊断", "求助渠道")
    }

    /** 各类型的内容纪律（喂提示词的确定性要求）。 */
    fun contentDisciplineOf(type: DocType): List<String> = when (type) {
        DocType.CONCEPT -> listOf(
            "每个核心概念单独小节：一句话定义 + 为什么存在 + 与相邻概念的边界",
            "示例先给最小可运行版，再给生产形态"
        )
        DocType.HOW_TO -> listOf(
            "步骤可复制：每步一条命令或一个动作 + 预期输出",
            "每步有验证点（怎么知道这步成功了）"
        )
        DocType.API_REF -> listOf(
            "每个端点：方法/路径/参数表/响应结构/错误码五件套",
            "示例含请求与响应成对出现"
        )
        DocType.TROUBLESHOOT -> listOf(
            "症状 → 检查点 → 处置三列表格化",
            "每个故障给「如何确认是这个问题」的判定证据"
        )
    }

    // ═══ 3) 示例校验 ═══

    /** 围栏块抽取（带语言标注信息）。 */
    private data class FenceBlock(val language: String, val content: String, val raw: String)

    private fun extractFences(text: String): List<FenceBlock> =
        Regex("""```(\w*)\n(.*?)```""", RegexOption.DOT_MATCHES_ALL)
            .findAll(text)
            .map { FenceBlock(it.groupValues[1], it.groupValues[2], it.value) }
            .toList()

    /** 伪代码占位标记（示例里不应出现）。 */
    private val PLACEHOLDER_MARKERS = listOf("TODO", "FIXME", "xxx", "…", "…...", "your_code", "此处省略")

    /** 示例长度预算（行）。 */
    const val EXAMPLE_MAX_LINES = 40

    /**
     * 示例三查：语言标注 / 占位符 / 长度。
     */
    fun validateExamples(text: String): List<DocStyleIssue> {
        val issues = mutableListOf<DocStyleIssue>()
        for ((index, fence) in extractFences(text).withIndex()) {
            if (fence.language.isBlank()) {
                issues += DocStyleIssue(
                    ruleId = "doc.example-no-lang",
                    kind = DocStyleIssue.Kind.EXAMPLE_NO_LANG,
                    detail = "第 ${index + 1} 个示例缺语言标注（``` 后注明语言）",
                    evidence = fence.content.lines().firstOrNull().orEmpty().take(30),
                    repairable = true
                )
            }
            val placeholder = PLACEHOLDER_MARKERS.firstOrNull { fence.content.contains(it) }
            if (placeholder != null) {
                issues += DocStyleIssue(
                    ruleId = "doc.example-placeholder",
                    kind = DocStyleIssue.Kind.EXAMPLE_PLACEHOLDER,
                    detail = "第 ${index + 1} 个示例含占位标记「$placeholder」—— 示例必须完整可复制",
                    evidence = placeholder,
                    repairable = false
                )
            }
            val lineCount = fence.content.lines().size
            if (lineCount > EXAMPLE_MAX_LINES) {
                issues += DocStyleIssue(
                    ruleId = "doc.example-too-long",
                    kind = DocStyleIssue.Kind.EXAMPLE_TOO_LONG,
                    detail = "第 ${index + 1} 个示例 $lineCount 行超过 $EXAMPLE_MAX_LINES 预算 —— 拆分或收敛到关键段",
                    evidence = "",
                    repairable = true
                )
            }
        }
        return issues
    }

    // ═══ 4) 术语表构建 ═══

    /** 候选术语模式：首字母缩写（≥2 大写）。 */
    private val ACRONYM: Regex = Regex("""\b[A-Z]{2,6}\b""")

    /**
     * 术语表构建 + 先用后定义检测：
     * 「定义」判定 = 术语首次出现位置之前，文中存在「X（英文全称）」
     * 括注形态或存在术语表章节（## 术语表 之后的行）。
     */
    fun buildGlossary(text: String): List<DocGlossaryEntry> {
        if (text.isBlank()) return emptyList()
        // 术语表章节起点（字符位置；无术语表 = 无限大）
        val glossaryAnchor = Regex("""^#{1,3}\s*(?:术语表|词汇表|术语与缩写)""", RegexOption.MULTILINE)
            .find(text)?.range?.first ?: Int.MAX_VALUE

        val entries = mutableListOf<DocGlossaryEntry>()
        for (match in ACRONYM.findAll(text)) {
            val term = match.value
            if (term in KNOWN_NON_TERMS) continue
            val first = match.range.first
            // 定义形态1：首次出现紧随开括号（全称（ACRONYM）括注形态）
            val charBefore = if (first > 0) text[first - 1] else ' '
            val definedByParen = charBefore == '(' || charBefore == '（'
            // 定义形态2：术语表章节在首现之前
            val definedByGlossary = glossaryAnchor < first
            entries += DocGlossaryEntry(
                term = term,
                firstOccurrence = first,
                definedBeforeFirstUse = definedByParen || definedByGlossary
            )
        }
        // 去重（同术语保留首条）
        return entries.distinctBy { it.term }
    }

    /** 非术语缩写白名单（常见普通缩写）。 */
    private val KNOWN_NON_TERMS = setOf("OK", "NO", "YES", "THE", "AND", "FOR", "TODO", "NOTE")

    /** 先用后定义违规抽取（喂 VALIDATE）。 */
    fun undefinedTermIssues(text: String): List<DocStyleIssue> =
        buildGlossary(text)
            .filter { !it.definedBeforeFirstUse }
            .take(MAX_TERM_ISSUES)
            .map { entry ->
                DocStyleIssue(
                    ruleId = "doc.term-undefined-first",
                    kind = DocStyleIssue.Kind.TERM_UNDEFINED_FIRST,
                    detail = "术语「${entry.term}」首次出现前未定义 —— 首次出现处括注全称或文档头加术语表",
                    evidence = entry.term,
                    repairable = true
                )
            }

    // ═══ 5) 可读性评分 ═══

    /**
     * 可读性四因子评分（0-100）：
     * 句长因子 35 分（均句长 ≤30 满分，>50 零分）+
     * 示例占比 25 分（5-30% 满分，无示例 8 分）+
     * 章节均衡 20 分（必需章节全有满分）+
     * 层级规范 20 分（无跳级满分）。
     */
    fun scoreReadability(text: String, type: DocType): ReadabilityReport {
        if (text.isBlank()) return ReadabilityReport.empty()
        val prose = stripFences(text)
        val sentences = prose.split(Regex("""[。！？.!?\n]""")).map { it.trim() }.filter { it.isNotEmpty() }
        val average = if (sentences.isEmpty()) 0 else prose.length / sentences.size
        val longest = sentences.maxOfOrNull { it.length } ?: 0

        val sentenceFactor = when {
            average <= 30 -> 35
            average <= 40 -> 28
            average <= 50 -> 18
            else -> 8
        }
        val totalChars = (text.length).coerceAtLeast(1)
        val codeChars = extractFences(text).sumOf { it.content.length }
        val codeRatio = codeChars * 100 / totalChars
        val codeFactor = when {
            codeRatio in 5..30 -> 25
            codeRatio in 1..4 -> 18
            codeRatio in 31..60 -> 15
            else -> 8
        }
        val headings = text.lines().filter { it.startsWith("#") }.map { it.trimStart('#', ' ') }
        val required = requiredSectionsOf(type)
        val matchedRequired = required.count { need ->
            headings.any { heading -> heading.contains(need) || need.contains(heading) }
        }
        val sectionFactor = matchedRequired * 20 / required.size.coerceAtLeast(1)
        val levelJumps = headingLevelJumps(text)
        val levelFactor = when (levelJumps.size) {
            0 -> 20
            1 -> 12
            else -> 5
        }
        val score = (sentenceFactor + codeFactor + sectionFactor + levelFactor).coerceIn(0, 100)
        return ReadabilityReport(
            score = score,
            averageSentenceLength = average,
            longestSentenceLength = longest,
            codeRatioPercent = codeRatio,
            factorScores = mapOf(
                "sentenceLength" to sentenceFactor,
                "exampleRatio" to codeFactor,
                "sectionBalance" to sectionFactor,
                "headingLevels" to levelFactor
            )
        )
    }

    // ═══ 6) 文档 lint ═══

    /** 标题层级跳级检测（h1 → h3 形态）。 */
    fun headingLevelJumps(text: String): List<Pair<String, String>> {
        val jumps = mutableListOf<Pair<String, String>>()
        var previousLevel = 0
        for (line in text.lines()) {
            if (!line.startsWith("#")) continue
            val level = line.takeWhile { it == '#' }.length
            if (previousLevel in 1 until level && level - previousLevel > 1) {
                jumps += "h$previousLevel" to "h$level"
            }
            previousLevel = level
        }
        return jumps
    }

    /** 文档全量 lint（跳级 + 重复标题 + 空章节 + 引用残缺）。 */
    fun lintDocument(text: String): List<DocStyleIssue> {
        val issues = mutableListOf<DocStyleIssue>()
        if (text.isBlank()) return issues
        // 标题跳级
        for ((from, to) in headingLevelJumps(text)) {
            issues += DocStyleIssue(
                ruleId = "doc.heading-level-jump",
                kind = DocStyleIssue.Kind.HEADING_LEVEL_JUMP,
                detail = "标题层级跳级（$from → $to）—— 层级逐级递进，跳级破坏大纲树",
                evidence = "$from→$to",
                repairable = true
            )
        }
        // 重复标题
        val headings = text.lines()
            .filter { it.startsWith("#") }
            .map { it.trimStart('#', ' ').trim() }
            .filter { it.isNotEmpty() }
        val duplicates = headings.groupBy { it }.filter { it.value.size > 1 }
        for ((title, occurrences) in duplicates) {
            issues += DocStyleIssue(
                ruleId = "doc.duplicate-heading",
                kind = DocStyleIssue.Kind.DUPLICATE_HEADING,
                detail = "标题「$title」出现 ${occurrences.size} 次 —— 同名章节合并或改名区分",
                evidence = title,
                repairable = true
            )
        }
        // 空章节（标题后直接跟下一个标题）
        val lines = text.lines()
        for (index in lines.indices) {
            if (!lines[index].startsWith("#")) continue
            val nextNonBlank = lines.drop(index + 1).firstOrNull { it.isNotBlank() }
            if (nextNonBlank != null && nextNonBlank.startsWith("#")) {
                issues += DocStyleIssue(
                    ruleId = "doc.empty-section",
                    kind = DocStyleIssue.Kind.EMPTY_SECTION,
                    detail = "章节「${lines[index].trimStart('#', ' ')}」无内容 —— 补内容或删除标题",
                    evidence = lines[index].take(40),
                    repairable = true
                )
            }
        }
        // 引用残缺：]（ 有起无终 / ](  后面没跟 url
        for (line in lines) {
            if (line.contains("](") && !Regex("""\]\([^)]+\)""").containsMatchIn(line)) {
                issues += DocStyleIssue(
                    ruleId = "doc.broken-link",
                    kind = DocStyleIssue.Kind.BROKEN_LINK,
                    detail = "Markdown 引用残缺（] 与 () 不配对或缺 URL）",
                    evidence = line.take(40),
                    repairable = true
                )
            }
        }
        return issues
    }

    // ═══ 常量 ═══

    private const val MAX_TERM_ISSUES = 5
}
