package com.apex.agent.core.engine.templates.runtime.builtin

/**
 * ═══ 内置模板行为 · 专业翻译 —— 质量质检器（PR 17 深化 I）═══
 *
 * 翻译行为的第二层确定性引擎：[TranslateGlossary] 管「术语对不对」，
 * 本模块管「译文本身像不像一个合格交付物」——在请求大模型之前对原文
 * 做源侧体检，在回包之后对译文做逐项质检，能机械修的（标点宽度/
 * 空白污染/引号方向）直接修掉，不能机械修的（数字丢失/漏译段/长度
 * 异常）作为 BLOCKER 级证据回灌给 LLM 复核。
 *
 * 检查族（全部纯函数、全部确定性）：
 * 1. **数字保真**（number fidelity）：原文的数字/百分比/版本号/日期/
 *    区间必须在译文中逐个出现——数字是翻译事故的重灾区（单位换算静默
 *    改值、小数点丢失、千分位吞并）；
 * 2. **标点宽度**（punctuation width）：目标语言的标点家族归一——
 *    中文目标 = 全角标点 + 弯引号，拉丁目标 = 半角标点 + 直引号；
 *    同一种标点全半角混用单列一条一致性违规；
 * 3. **括号与引号配对**（bracket pairing）：圆/方/花括号、中英引号、
 *    书名号、方头括号的开闭计数必须相等（跨全文统计，代码块豁免）；
 * 4. **段落对齐**（paragraph alignment）：非空行段落数与原文一致——
 *    多段合一/一段拆多都会破坏下游排版；
 * 5. **长度比异常**（length ratio）：按语言对的经验区间（宽容 ±40%）
 *    报告可疑的长度膨胀/坍缩——过短往往意味着漏译，过长往往意味着
 *    解释混入；
 * 6. **漏译段探测**（untranslated runs）：译文里出现「源语言书写的
 *    长连贯片段」= 漏译强信号（代码块与行内代码豁免）；
 * 7. **空白污染**（whitespace hygiene）：行尾空白/连续空行/双空格。
 *
 * 防御式纪律：任何一条检查都不抛异常——输入再离谱也折叠为空报告或
 * 单条降级注记；代码围栏与行内代码先剥除再检查，避免把代码当译文。
 */

/** 质检严重度（权重用于扣分，INFO 不扣分只留痕）。 */
enum class TranslateLintSeverity(val label: String, val penalty: Int) {
    /** 必须处理：数字丢失、漏译段、配对缺失。 */
    CRITICAL("阻断", 25),

    /** 显著影响交付质量：宽度家族错误、段落漂移、长度异常。 */
    MAJOR("重要", 10),

    /** 风格瑕疵：混用、空白污染、间距建议。 */
    MINOR("次要", 3),

    /** 观察项：不扣分，报告留痕。 */
    INFO("信息", 0);

    companion object {
        /** 从权重反查（报告聚合用）。 */
        fun fromPenalty(penalty: Int): TranslateLintSeverity =
            entries.firstOrNull { it.penalty == penalty } ?: INFO
    }
}

/** 检查族标识（报告分组与测试断言锚点）。 */
enum class TranslateLintAspect(val label: String) {
    NUMBER_FIDELITY("数字保真"),
    PUNCTUATION_WIDTH("标点宽度"),
    BRACKET_PAIRING("括号配对"),
    PARAGRAPH_ALIGNMENT("段落对齐"),
    LENGTH_RATIO("长度比"),
    UNTRANSLATED_RUNS("漏译探测"),
    WHITESPACE("空白卫生"),
    SOURCE_HEALTH("源侧体检");
}

/** 一条质检发现（规则 id 在本模块内唯一）。 */
data class TranslateLintIssue(
    val ruleId: String,
    val aspect: TranslateLintAspect,
    val severity: TranslateLintSeverity,
    val title: String,
    val detail: String,
    val evidence: String,
    val repairable: Boolean
) {
    init {
        require(ruleId.isNotBlank()) { "lint ruleId must not be blank" }
    }

    /** 单行展示形态（喂给提示词附录 / UI 列表）。 */
    fun toLine(): String = "[${severity.label}] $title｜$detail"
}

/** 质检报告（issue 列表 + 百分制得分 + 检查覆盖面）。 */
data class TranslateLintReport(
    val issues: List<TranslateLintIssue>,
    val score: Int,
    val checkedAspects: List<TranslateLintAspect>
) {
    /** 是否存在阻断级问题（译文不应直接放行）。 */
    val hasCritical: Boolean get() = issues.any { it.severity == TranslateLintSeverity.CRITICAL }

    /** 可机械修复的问题（REPAIR 阶段消费）。 */
    val repairableIssues: List<TranslateLintIssue> get() = issues.filter { it.repairable }

    /** 按检查族分组（报告面板展示）。 */
    fun byAspect(): Map<TranslateLintAspect, List<TranslateLintIssue>> =
        issues.groupBy { it.aspect }

    companion object {
        /** 干净译文（无任何发现）的规范报告。 */
        fun clean(): TranslateLintReport =
            TranslateLintReport(emptyList(), 100, emptyList())
    }
}

/**
 * 质检器主体。全部方法为纯函数；行为层在 ANALYZE（源侧）与
 * VALIDATE/REPAIR（译文侧）分两次调用。
 */
object TranslateQualityLinter {

    // ═══ 语言 ↔ 标点家族 ═══

    /** 标点书写家族。 */
    enum class PunctuationFamily(val label: String) {
        /** 全角标点 + 弯引号（zh / ja）。 */
        FULL_WIDTH("全角"),

        /** 半角标点 + 直引号（en / de / fr / es / ru 等拉丁与西里尔）。 */
        HALF_WIDTH("半角");

        companion object {
            /** 宽容家族（两者皆可，不判宽度违规）。 */
            fun tolerantOf(lang: String): Boolean = lang == "ko"
        }
    }

    /** 语言 → 标点家族表（未列出的语言按半角处理）。 */
    private val PUNCTUATION_FAMILY: Map<String, PunctuationFamily> = mapOf(
        "zh" to PunctuationFamily.FULL_WIDTH,
        "ja" to PunctuationFamily.FULL_WIDTH,
        "ko" to PunctuationFamily.HALF_WIDTH,
        "en" to PunctuationFamily.HALF_WIDTH,
        "de" to PunctuationFamily.HALF_WIDTH,
        "fr" to PunctuationFamily.HALF_WIDTH,
        "es" to PunctuationFamily.HALF_WIDTH,
        "pt" to PunctuationFamily.HALF_WIDTH,
        "it" to PunctuationFamily.HALF_WIDTH,
        "ru" to PunctuationFamily.HALF_WIDTH,
        "ar" to PunctuationFamily.HALF_WIDTH
    )

    /** 语言对 → 译文长度 / 原文长度的经验区间（宽容量级，非精算）。 */
    private data class RatioBand(val min: Double, val max: Double, val note: String)

    private val LENGTH_BANDS: Map<String, RatioBand> = mapOf(
        // 中文 → 拉丁：字符数通常膨胀 1.3~2.2 倍（中文信息密度高）
        "zh>en" to RatioBand(0.9, 2.6, "中→英字符膨胀区间"),
        "zh>ja" to RatioBand(0.8, 1.8, "中→日字符接近"),
        // 拉丁 → 中文：字符数通常收缩到 0.4~0.8
        "en>zh" to RatioBand(0.35, 0.95, "英→中字符收缩区间"),
        // 拉丁互译：0.8~1.25
        "en>de" to RatioBand(0.8, 1.3, "英→德略膨胀"),
        "de>en" to RatioBand(0.75, 1.2, "德→英略收缩"),
        "en>fr" to RatioBand(0.85, 1.3, "英→法略膨胀"),
        "en>es" to RatioBand(0.85, 1.3, "英→西略膨胀"),
        "en>ru" to RatioBand(0.9, 1.4, "英→俄略膨胀"),
        "ja>zh" to RatioBand(0.7, 1.5, "日→中接近"),
        "ja>en" to RatioBand(0.9, 2.4, "日→英膨胀")
    )

    /** 默认语言对区间（未登记的组合）。 */
    private val DEFAULT_BAND = RatioBand(0.5, 2.0, "默认宽容区间")

    // ═══ 字符表 ═══

    /** 全角终止标点（中文家族的正文标点）。 */
    private const val FULL_STOPS = "，。；：？！"

    /** 对应的半角终止标点（顺序一致，供宽度互转）。 */
    private const val HALF_STOPS = ",.;:?!"

    /** 全角括号与引号对（开 → 闭）。 */
    private val FULL_BRACKETS = linkedMapOf(
        '（' to '）', '【' to '】', '《' to '》', '「' to '」', '『' to '』', '“' to '”', '‘' to '’'
    )

    /** 半角括号与引号对（开 → 闭）。 */
    private val HALF_BRACKETS = linkedMapOf(
        '(' to ')', '[' to ']', '{' to '}', '<' to '>'
    )

    /** 中日韩统一表意 + 扩展 A 的粗判定。 */
    private fun isCjk(ch: Char): Boolean {
        val block = ch.code
        return (block in 0x4E00..0x9FFF) || (block in 0x3400..0x4DBF) ||
            (block in 0xF900..0xFAFF) || (block in 0x3040..0x30FF)
    }

    /** 拉丁字母。 */
    private fun isLatin(ch: Char): Boolean =
        ch.code in 0x41..0x5A || ch.code in 0x61..0x7A

    /** 数字提取模式：整数/小数/百分比/版本号/日期/区间（含全角数字归一前处理）。 */
    private val NUMBER_PATTERN: Regex = Regex(
        """\d+(?:\.\d+){0,3}(?:%|％)?(?:\s*[-~至到]\s*\d+(?:\.\d+){0,3})?"""
    )

    /** 行内代码与围栏代码块剥离（先围栏后行内，防嵌套误剥）。 */
    private val CODE_FENCE: Regex = Regex("""```.*?```""", RegexOption.DOT_MATCHES_ALL)
    private val INLINE_CODE: Regex = Regex("""`[^`\n]+`""")

    /** 连续 CJK 片段（漏译探测用：长度达到阈值才算「段」）。 */
    private val CJK_RUN: Regex = Regex("""[\u4E00-\u9FFF\u3400-\u4DBF，。；：、！？]{6,}""")

    /** 连续拉丁词片段（目标为中文时的漏译信号；容忍单个专有名词）。 */
    private val LATIN_RUN: Regex = Regex("""[A-Za-z][A-Za-z'\- ]{23,}""")

    /** CJK 与拉丁字符之间应有空格的粘接点（盘古之白建议，仅 MINOR）。 */
    private val CJK_LATIN_GLUE: Regex = Regex("""[\u4E00-\u9FFF][A-Za-z0-9]|[A-Za-z0-9][\u4E00-\u9FFF]""")

    // ═══ 对外主入口 ═══

    /**
     * 译文侧全量质检（VALIDATE 阶段调用）。
     *
     * @param source 原文（规范化后）
     * @param translation 大模型回包（修复前后皆可）
     * @param sourceLang 源语言码（zh/en/…；未知传空串按宽容处理）
     * @param targetLang 目标语言码（决定标点家族与长度区间）
     */
    fun lintTranslation(
        source: String,
        translation: String,
        sourceLang: String,
        targetLang: String
    ): TranslateLintReport {
        if (translation.isBlank()) {
            return TranslateLintReport(
                issues = listOf(
                    TranslateLintIssue(
                        ruleId = "lint.empty-translation",
                        aspect = TranslateLintAspect.SOURCE_HEALTH,
                        severity = TranslateLintSeverity.CRITICAL,
                        title = "译文为空",
                        detail = "回包没有任何可交付内容",
                        evidence = "",
                        repairable = false
                    )
                ),
                score = 0,
                checkedAspects = listOf(TranslateLintAspect.SOURCE_HEALTH)
            )
        }
        val issues = mutableListOf<TranslateLintIssue>()
        val aspects = mutableListOf<TranslateLintAspect>()

        // 1) 数字保真（代码块已剥离）
        val fidelity = checkNumberFidelity(source, translation)
        if (fidelity.isNotEmpty()) {
            aspects += TranslateLintAspect.NUMBER_FIDELITY
            issues += fidelity
        }

        // 2) 标点宽度（目标家族归一；ko 等宽容语言跳过）
        val width = checkPunctuationWidth(translation, targetLang)
        if (width.isNotEmpty()) {
            aspects += TranslateLintAspect.PUNCTUATION_WIDTH
            issues += width
        }

        // 3) 括号与引号配对
        val pairing = checkBracketPairing(translation)
        if (pairing.isNotEmpty()) {
            aspects += TranslateLintAspect.BRACKET_PAIRING
            issues += pairing
        }

        // 4) 段落对齐
        val alignment = checkParagraphAlignment(source, translation)
        if (alignment.isNotEmpty()) {
            aspects += TranslateLintAspect.PARAGRAPH_ALIGNMENT
            issues += alignment
        }

        // 5) 长度比异常
        checkLengthRatio(source, translation, sourceLang, targetLang)?.let {
            aspects += TranslateLintAspect.LENGTH_RATIO
            issues += it
        }

        // 6) 漏译段
        val runs = detectUntranslatedRuns(translation, targetLang)
        if (runs.isNotEmpty()) {
            aspects += TranslateLintAspect.UNTRANSLATED_RUNS
            issues += runs
        }

        // 7) 空白卫生
        val whitespace = checkWhitespace(translation)
        if (whitespace.isNotEmpty()) {
            aspects += TranslateLintAspect.WHITESPACE
            issues += whitespace
        }

        return TranslateLintReport(
            issues = issues,
            score = scoreOf(issues),
            checkedAspects = aspects
        )
    }

    /**
     * 源侧体检（ANALYZE 阶段调用）：原文自身的质量问题提前暴露——
     * 破括号、混宽度、粘贴带入的多余空白，避免 LLM 背锅。
     */
    fun lintSource(source: String): TranslateLintReport {
        if (source.isBlank()) return TranslateLintReport.clean()
        val issues = mutableListOf<TranslateLintIssue>()
        issues += checkBracketPairing(source)
        issues += checkWhitespace(source)
        // 源侧宽度混用（同一种标点全半角都出现）
        issues += checkWidthConsistency(stripCode(source))
        return TranslateLintReport(
            issues = issues,
            score = scoreOf(issues),
            checkedAspects = listOf(TranslateLintAspect.SOURCE_HEALTH)
        )
    }

    // ═══ 1) 数字保真 ═══

    /**
     * 原文数字清单 vs 译文：逐个比对出现性（次序不要求一致）。
     * 全角数字先归一为半角再抽取。报告最多 [MAX_NUMBER_ISSUES] 条
     * （防数字密集的表格型原文刷屏）。
     */
    fun checkNumberFidelity(source: String, translation: String): List<TranslateLintIssue> {
        val cleanSource = stripCode(source)
        val cleanTranslation = stripCode(translation)
        val sourceNumbers = extractNumbers(cleanSource)
        if (sourceNumbers.isEmpty()) return emptyList()
        val translationNumbers = extractNumbers(cleanTranslation).toMutableSet()
        // 区间形态「10-20」命中后，其端点 10 与 20 也算出现（子串包含兜底）
        val translationText = cleanTranslation.replace("％", "%")
        val missing = sourceNumbers.filter { number ->
            !translationNumbers.contains(number) && !translationText.contains(number)
        }
        if (missing.isEmpty()) return emptyList()
        return listOf(
            TranslateLintIssue(
                ruleId = "lint.number-missing",
                aspect = TranslateLintAspect.NUMBER_FIDELITY,
                severity = TranslateLintSeverity.CRITICAL,
                title = "原文 ${sourceNumbers.size} 个数字中有 ${missing.size} 个未在译文出现",
                detail = "数字必须逐字保留（含单位与小数位）；若为有意换算，需在译注说明",
                evidence = missing.take(6).joinToString("、"),
                repairable = false
            )
        )
    }

    /** 数字抽取（先归一全角数字与千分位逗号）。 */
    fun extractNumbers(text: String): List<String> {
        val normalized = text
            .replace('０', '0').replace('１', '1').replace('２', '2').replace('３', '3')
            .replace('４', '4').replace('５', '5').replace('６', '6').replace('７', '7')
            .replace('８', '8').replace('９', '9')
            .replace(",", "")
        return NUMBER_PATTERN.findAll(normalized)
            .map { it.value.replace(" ", "") }
            .filter { it.any { ch -> ch.isDigit() } }
            .distinct()
            .toList()
    }

    // ═══ 2) 标点宽度 ═══

    /** 目标语言标点家族（未知语言 → 半角缺省）。 */
    fun punctuationFamilyOf(lang: String): PunctuationFamily =
        PUNCTUATION_FAMILY[lang] ?: PunctuationFamily.HALF_WIDTH

    /**
     * 译文标点宽度体检：
     * - 家族错误（中文目标用了半角终止标点 / 拉丁目标用了全角）→ MAJOR 可修复；
     * - 同种标点混用（全半角都出现）→ MINOR 可修复；
     * - 宽容语言（ko）跳过。
     */
    fun checkPunctuationWidth(text: String, targetLang: String): List<TranslateLintIssue> {
        if (PunctuationFamily.tolerantOf(targetLang)) return emptyList()
        val clean = stripCode(text)
        val family = punctuationFamilyOf(targetLang)
        val issues = mutableListOf<TranslateLintIssue>()

        if (family == PunctuationFamily.FULL_WIDTH) {
            val halfStopsInCjk = countHalfStopsAdjacentToCjk(clean)
            if (halfStopsInCjk > 0) {
                issues += TranslateLintIssue(
                    ruleId = "lint.width-half-in-cjk",
                    aspect = TranslateLintAspect.PUNCTUATION_WIDTH,
                    severity = TranslateLintSeverity.MAJOR,
                    title = "中文译文中出现 $halfStopsInCjk 处半角标点（应全角）",
                    detail = "目标语言为中文家族，正文标点应使用全角（，。；：？！）",
                    evidence = "",
                    repairable = true
                )
            }
        } else {
            var fullStops = 0
            for (ch in clean) {
                if (FULL_STOPS.indexOf(ch) >= 0) fullStops++
            }
            if (fullStops > 0) {
                issues += TranslateLintIssue(
                    ruleId = "lint.width-full-in-latin",
                    aspect = TranslateLintAspect.PUNCTUATION_WIDTH,
                    severity = TranslateLintSeverity.MAJOR,
                    title = "拉丁译文中出现 $fullStops 处全角标点（应半角）",
                    detail = "目标语言为拉丁/西里尔家族，标点应使用半角（,.;:?!)",
                    evidence = "",
                    repairable = true
                )
            }
            val fullBrackets = FULL_BRACKETS.keys.count { clean.contains(it) } +
                FULL_BRACKETS.values.count { clean.contains(it) }
            if (fullBrackets > 0) {
                issues += TranslateLintIssue(
                    ruleId = "lint.bracket-full-in-latin",
                    aspect = TranslateLintAspect.PUNCTUATION_WIDTH,
                    severity = TranslateLintSeverity.MINOR,
                    title = "拉丁译文中出现全角括号/引号",
                    detail = "圆括号与引号应使用半角形态",
                    evidence = "",
                    repairable = true
                )
            }
        }

        issues += checkWidthConsistency(clean)
        return issues
    }

    /** 半角终止标点紧邻 CJK 字符的计数（中文目标的宽度违规核心证据）。 */
    private fun countHalfStopsAdjacentToCjk(text: String): Int {
        var count = 0
        val chars = text.toCharArray()
        for (index in chars.indices) {
            if (HALF_STOPS.indexOf(chars[index]) < 0) continue
            val before = if (index > 0) chars[index - 1] else ' '
            val after = if (index < chars.size - 1) chars[index + 1] else ' '
            if (isCjk(before) || isCjk(after)) count++
        }
        return count
    }

    /** 同种标点全半角混用检测（源侧与译文侧共用；internal 供测试直接验证）。 */
    internal fun checkWidthConsistency(cleanText: String): List<TranslateLintIssue> {
        val issues = mutableListOf<TranslateLintIssue>()
        // 逗号：, 与 ， 同时出现
        if (cleanText.contains(',') && cleanText.contains('，')) {
            issues += mixedWidthIssue("comma", "逗号", ", / ，")
        }
        // 冒号
        if (cleanText.contains(':') && cleanText.contains('：')) {
            issues += mixedWidthIssue("colon", "冒号", ": / ：")
        }
        // 分号
        if (cleanText.contains(';') && cleanText.contains('；')) {
            issues += mixedWidthIssue("semicolon", "分号", "; / ；")
        }
        // 句号（. 与 。同现且 . 紧邻 CJK 才算混用 —— 拉丁缩写如 e.g. 会误报）
        val chars = cleanText.toCharArray()
        val periodNearCjk = chars.withIndex().any { (i, ch) ->
            ch == '.' && (
                (i > 0 && isCjk(chars[i - 1])) ||
                    (i < chars.size - 1 && isCjk(chars[i + 1]))
                )
        }
        if (periodNearCjk && cleanText.contains('。')) {
            issues += mixedWidthIssue("period", "句号", ". / 。")
        }
        return issues
    }

    private fun mixedWidthIssue(kind: String, label: String, forms: String): TranslateLintIssue =
        TranslateLintIssue(
            ruleId = "lint.width-mixed-$kind",
            aspect = TranslateLintAspect.PUNCTUATION_WIDTH,
            severity = TranslateLintSeverity.MINOR,
            title = "$label 全半角混用（$forms）",
            detail = "同一种标点在全文内应统一宽度形态",
            evidence = "",
            repairable = true
        )

    // ═══ 3) 括号与引号配对 ═══

    /**
     * 开闭计数配对（全文聚合）。跨行不配对、方向颠倒都只报计数差——
     * 精确定位交给 LLM 复核。直引号 " 与 ' 因无法区分开闭，改用
     * 奇偶计数（奇数 = 不配对）。
     */
    fun checkBracketPairing(text: String): List<TranslateLintIssue> {
        val clean = stripCode(text)
        val issues = mutableListOf<TranslateLintIssue>()
        for ((open, close) in FULL_BRACKETS) {
            val opens = clean.count { it == open }
            val closes = clean.count { it == close }
            if (opens != closes) {
                issues += pairingIssue(open, close, opens, closes)
            }
        }
        for ((open, close) in HALF_BRACKETS) {
            if (open == '<') continue // 尖括号在译文里多为泛型/比较符号，跳过
            val opens = clean.count { it == open }
            val closes = clean.count { it == close }
            if (opens != closes) {
                issues += pairingIssue(open, close, opens, closes)
            }
        }
        val straightDouble = clean.count { it == '"' }
        if (straightDouble % 2 == 1) {
            issues += TranslateLintIssue(
                ruleId = "lint.pair-straight-quote",
                aspect = TranslateLintAspect.BRACKET_PAIRING,
                severity = TranslateLintSeverity.MINOR,
                title = "直引号 \" 出现奇数次（${straightDouble}），存在未闭合引号",
                detail = "补齐闭合引号或改用成对弯引号",
                evidence = "",
                repairable = false
            )
        }
        return issues
    }

    private fun pairingIssue(open: Char, close: Char, opens: Int, closes: Int): TranslateLintIssue =
        TranslateLintIssue(
            ruleId = "lint.pair-${open.code}-${close.code}",
            aspect = TranslateLintAspect.BRACKET_PAIRING,
            severity = TranslateLintSeverity.CRITICAL,
            title = "括号/引号「$open…$close」不配对（开 $opens / 闭 $closes）",
            detail = "开闭数量必须相等——缺失一侧意味着译文截断或吞字符",
            evidence = "",
            repairable = false
        )

    // ═══ 4) 段落对齐 ═══

    /**
     * 非空行块（段落）数对齐。差 1 段以内算 MINOR（段内合并常见且无害），
     * 差 2 段以上算 MAJOR（排版破坏强信号）。
     */
    fun checkParagraphAlignment(source: String, translation: String): List<TranslateLintIssue> {
        val sourceParagraphs = countParagraphs(source)
        val translationParagraphs = countParagraphs(translation)
        if (sourceParagraphs <= 0 || translationParagraphs <= 0) return emptyList()
        val delta = kotlin.math.abs(sourceParagraphs - translationParagraphs)
        if (delta == 0) return emptyList()
        val severity = if (delta >= 2) TranslateLintSeverity.MAJOR else TranslateLintSeverity.MINOR
        return listOf(
            TranslateLintIssue(
                ruleId = "lint.paragraph-drift",
                aspect = TranslateLintAspect.PARAGRAPH_ALIGNMENT,
                severity = severity,
                title = "段落数漂移（原文 $sourceParagraphs 段 → 译文 $translationParagraphs 段）",
                detail = if (translationParagraphs < sourceParagraphs) {
                    "译文段落少于原文——检查是否有整段漏译"
                } else {
                    "译文段落多于原文——检查是否混入了解释性内容"
                },
                evidence = "",
                repairable = false
            )
        )
    }

    /** 非空行块计数（连续非空行 = 一段）。 */
    private fun countParagraphs(text: String): Int {
        var paragraphs = 0
        var inParagraph = false
        for (line in text.lines()) {
            val blank = line.isBlank()
            if (!blank && !inParagraph) paragraphs++
            inParagraph = !blank
        }
        return paragraphs
    }

    // ═══ 5) 长度比异常 ═══

    /**
     * 语言对长度比体检（剥代码后按字符数比）。短原文（<40 字符）不做——
     * 样本太小比例噪声大；译文长度不设门槛（长原文→短译文正是漏译信号）。
     * 命中区间外 → 单条 MAJOR（膨胀）/ MINOR（收缩）。
     */
    fun checkLengthRatio(
        source: String,
        translation: String,
        sourceLang: String,
        targetLang: String
    ): TranslateLintIssue? {
        val cleanSource = stripCode(source)
        val cleanTranslation = stripCode(translation)
        if (cleanSource.length < MIN_RATIO_SOURCE_CHARS) return null
        if (cleanTranslation.isBlank()) return null
        if (sourceLang.isBlank() || targetLang.isBlank()) return null
        if (sourceLang == targetLang) return null
        val band = LENGTH_BANDS["$sourceLang>$targetLang"] ?: DEFAULT_BAND
        val ratio = cleanTranslation.length.toDouble() / cleanSource.length.toDouble()
        if (ratio in band.min..band.max) return null
        val inflated = ratio > band.max
        return TranslateLintIssue(
            ruleId = if (inflated) "lint.length-inflated" else "lint.length-collapsed",
            aspect = TranslateLintAspect.LENGTH_RATIO,
            severity = if (inflated) TranslateLintSeverity.MAJOR else TranslateLintSeverity.MINOR,
            title = "译文长度${if (inflated) "异常膨胀" else "明显偏短"}（比值 %.2f，期望 %.2f~%.2f）"
                .format(ratio, band.min, band.max),
            detail = if (inflated) {
                "膨胀常意味着解释混入——译文应只含对应原文的内容"
            } else {
                "偏短常意味着漏译——核对原文每个语义段是否都已覆盖"
            },
            evidence = band.note,
            repairable = false
        )
    }

    // ═══ 6) 漏译段探测 ═══

    /**
     * 译文中的「源语言书写残留」：
     * - 目标为拉丁语系：CJK 连贯片段 ≥ [MIN_CJK_RUN] 字 → 漏译；
     * - 目标为中文：拉丁连贯片段 ≥ [MIN_LATIN_RUN] 字符 → 漏译
     *   （专有名词/短术语容忍——阈值即容忍带）；
     * - 目标为中文时保留原文的标识符（驼峰/下划线形态）不算漏译。
     */
    fun detectUntranslatedRuns(translation: String, targetLang: String): List<TranslateLintIssue> {
        val clean = stripCode(translation)
        val issues = mutableListOf<TranslateLintIssue>()
        val targetIsCjkFamily = punctuationFamilyOf(targetLang) == PunctuationFamily.FULL_WIDTH
        if (!targetIsCjkFamily) {
            val runs = CJK_RUN.findAll(clean).map { it.value }.toList()
            if (runs.isNotEmpty()) {
                issues += untranslatedIssue(
                    runs = runs,
                    label = "中文",
                    direction = "zh-in-latin"
                )
            }
        } else {
            val runs = LATIN_RUN.findAll(clean)
                .map { it.value.trim() }
                .filter { run ->
                    // 全大写缩写（API/HTTP）与单个驼峰词不算漏译
                    run.length >= MIN_LATIN_RUN && !run.all { it.isUpperCase() } &&
                        run.count { it == ' ' } >= 3
                }
                .toList()
            if (runs.isNotEmpty()) {
                issues += untranslatedIssue(
                    runs = runs,
                    label = "拉丁",
                    direction = "latin-in-cjk"
                )
            }
        }
        return issues
    }

    private fun untranslatedIssue(runs: List<String>, label: String, direction: String): TranslateLintIssue =
        TranslateLintIssue(
            ruleId = "lint.untranslated-$direction",
            aspect = TranslateLintAspect.UNTRANSLATED_RUNS,
            severity = TranslateLintSeverity.CRITICAL,
            title = "检测到 ${runs.size} 处${label}长片段疑似漏译",
            detail = "目标语言译文中不应残留源语言书写的连续片段（代码与专有名词除外）",
            evidence = runs.take(3).joinToString("；") { it.take(24) },
            repairable = false
        )

    // ═══ 7) 空白卫生 ═══

    /** 行尾空白 / 三连空行 / 中文文本中的双空格。 */
    fun checkWhitespace(text: String): List<TranslateLintIssue> {
        val issues = mutableListOf<TranslateLintIssue>()
        val trailingLines = text.lines().count { it != it.trimEnd() && it.isNotBlank() }
        if (trailingLines > 0) {
            issues += TranslateLintIssue(
                ruleId = "lint.whitespace-trailing",
                aspect = TranslateLintAspect.WHITESPACE,
                severity = TranslateLintSeverity.MINOR,
                title = "$trailingLines 行行尾带空白",
                detail = "行尾空白会在下游 diff 与排版中制造噪声",
                evidence = "",
                repairable = true
            )
        }
        if (text.contains("\n\n\n\n")) {
            issues += TranslateLintIssue(
                ruleId = "lint.whitespace-blank-run",
                aspect = TranslateLintAspect.WHITESPACE,
                severity = TranslateLintSeverity.MINOR,
                title = "存在三连以上空行",
                detail = "连续空行应压缩为单个（段落分隔）",
                evidence = "",
                repairable = true
            )
        }
        return issues
    }

    // ═══ CJK-拉丁间距建议（盘古之白）═══

    /**
     * 中英混排粘接点计数（建议性质，MINOR/INFO）：「中文abc中文」
     * 之间加空格更易读。只在粘接点密度高时报告，避免碎嘴。
     */
    fun countCjkLatinGlue(text: String): Int {
        val clean = stripCode(text)
        return CJK_LATIN_GLUE.findAll(clean).count()
    }

    // ═══ 修复 ═══

    /**
     * 确定性修复（REPAIR 阶段调用）——只动「机械可修」的部分：
     * 1. 标点宽度归一（按目标家族；宽容语言只做混用归一不换形）；
     * 2. 行尾空白剥离 + 连续空行压缩 + 首尾空行剥离；
     * 3. 中文家族内直引号 → 弯引号成对替换。
     *
     * 代码块（``` 围栏与 ` 行内）内容逐字保留——先切出代码段再替换。
     */
    fun repair(translation: String, targetLang: String): Pair<String, List<String>> {
        if (translation.isBlank()) return translation to emptyList()
        val notes = mutableListOf<String>()
        val family = punctuationFamilyOf(targetLang)
        val tolerant = PunctuationFamily.tolerantOf(targetLang)

        // 切分代码段与文本段（围栏优先）
        val segments = splitByCode(translation)
        val repaired = StringBuilder()
        for (segment in segments) {
            if (segment.isCode) {
                repaired.append(segment.text)
                continue
            }
            var text = segment.text
            if (family == PunctuationFamily.FULL_WIDTH && !tolerant) {
                val before = text
                text = widenPunctuation(text)
                if (text != before) notes += "半角标点已归一为全角"
            } else if (family == PunctuationFamily.HALF_WIDTH && !tolerant) {
                val before = text
                text = narrowPunctuation(text)
                if (text != before) notes += "全角标点已归一为半角"
            }
            // 空白卫生（全家族通用）
            val beforeWhitespace = text
            text = text.lines().joinToString("\n") { it.trimEnd() }
            while (text.contains("\n\n\n")) text = text.replace("\n\n\n", "\n\n")
            text = text.trim()
            if (text != beforeWhitespace) notes += "空白已清理"
            repaired.append(text)
        }
        var result = repaired.toString()
        if (result != translation) {
            result = result.trim()
        }
        return result to notes.distinct()
    }

    /** 半角 → 全角（只动紧邻 CJK 的终止标点，防止误伤 URL 与代码残留）。 */
    private fun widenPunctuation(text: String): String {
        val chars = text.toCharArray()
        val out = StringBuilder(chars.size + 8)
        for (index in chars.indices) {
            val ch = chars[index]
            val halfIndex = HALF_STOPS.indexOf(ch)
            val before = if (index > 0) chars[index - 1] else ' '
            val after = if (index < chars.size - 1) chars[index + 1] else ' '
            val adjacentCjk = isCjk(before) || isCjk(after)
            if (halfIndex >= 0 && adjacentCjk) {
                out.append(FULL_STOPS[halfIndex])
            } else if (ch == '"' && (isCjk(before) || isCjk(after))) {
                // 直引号 → 弯引号（按累计奇偶定方向）
                val straightCount = chars.count { it == '"' }
                val seenSoFar = chars.take(index + 1).count { it == '"' }
                val isOpen = straightCount % 2 == 0 && seenSoFar % 2 == 1
                out.append(if (isOpen) '“' else '”')
            } else {
                out.append(ch)
            }
        }
        return out.toString()
    }

    /** 全角 → 半角（拉丁家族：全角标点全部替换）。 */
    private fun narrowPunctuation(text: String): String {
        var result = text
        val pairs = listOf(
            '，' to ',', '。' to '.', '；' to ';', '：' to ':',
            '？' to '?', '！' to '!', '（' to '(', '）' to ')',
            '【' to '[', '】' to ']', '《' to '<', '》' to '>'
        )
        for ((full, half) in pairs) {
            if (result.contains(full)) {
                result = result.replace(full, half)
            }
        }
        return result
    }

    // ═══ 工具 ═══

    /** 剥离代码（围栏块 → 占位空行；行内代码 → 空串），返回纯自然语言。 */
    fun stripCode(text: String): String {
        var result = CODE_FENCE.replace(text) { "\n\n" }
        result = INLINE_CODE.replace(result) { "" }
        return result
    }

    /** 按代码段切分（repair 用：代码逐字保留，文本段做归一）。 */
    private data class TextSegment(val text: String, val isCode: Boolean)

    private fun splitByCode(text: String): List<TextSegment> {
        val segments = mutableListOf<TextSegment>()
        var cursor = 0
        for (match in CODE_FENCE.findAll(text)) {
            if (match.range.first > cursor) {
                segments += TextSegment(text.substring(cursor, match.range.first), false)
            }
            segments += TextSegment(match.value, true)
            cursor = match.range.last + 1
        }
        if (cursor < text.length) {
            segments += TextSegment(text.substring(cursor), false)
        }
        return segments.ifEmpty { listOf(TextSegment(text, false)) }
    }

    /** 百分制得分（100 起步，按严重度扣分，下限 0）。 */
    fun scoreOf(issues: List<TranslateLintIssue>): Int {
        var score = 100
        for (issue in issues) {
            score -= issue.severity.penalty
        }
        return score.coerceIn(0, 100)
    }

    /** 交给提示词附录的精简形态（每条一行，最多 [MAX_PROMPT_LINES] 条）。 */
    fun toPromptLines(report: TranslateLintReport): List<String> =
        report.issues.take(MAX_PROMPT_LINES).map { it.toLine() }

    // ═══ 常量 ═══

    private const val MIN_RATIO_SOURCE_CHARS = 40
    private const val MIN_CJK_RUN = 6
    private const val MIN_LATIN_RUN = 24
    private const val MAX_NUMBER_ISSUES = 6
    private const val MAX_PROMPT_LINES = 10

    /** 面向行为的规则清单（工坊能力预览 & 目录完整性测试用）。 */
    val RULE_IDS: List<String> = listOf(
        "lint.empty-translation", "lint.number-missing", "lint.width-half-in-cjk",
        "lint.width-full-in-latin", "lint.bracket-full-in-latin", "lint.width-mixed-comma",
        "lint.width-mixed-colon", "lint.width-mixed-semicolon", "lint.width-mixed-period",
        "lint.pair-straight-quote", "lint.paragraph-drift", "lint.length-inflated",
        "lint.length-collapsed", "lint.untranslated-zh-in-latin", "lint.untranslated-latin-in-cjk",
        "lint.whitespace-trailing", "lint.whitespace-blank-run"
    )
}
