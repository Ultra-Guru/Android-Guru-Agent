package com.apex.agent.core.engine.templates.runtime

/**
 * ═══ 模板行为运行时 —— 共享分析工具箱 · 编程语言探测（PR 3）═══
 *
 * 代码审查 / 测试用例 / 重构方案 / 文档等行为共用的一手能力：从代码
 * 片段（无文件名线索）推断编程语言。打分制：每门语言一组「强信号」
 * （签名关键字/独有语法）与「弱信号」（惯用形态），强信号权重高，
 * 归一化置信度输出排序候选。
 *
 * 设计取舍：
 * - **不做完美**：探测目标是「给确定性分析与提示词注入正确的语言规则表」，
 *   置信度不足时明确返回 null，行为侧回落到「通用规则 + 语言未知」分支，
 *   绝不瞎猜误导下游；
 * - **纯字符匹配**：无树解析、无依赖 —— 输入再短也只做线性扫描；
 * - **常见混淆有仲裁**：C/C++/Java/JS/TS 共享大量 C 系形态，用独有
 *   信号（template string / sealed / #include / fn / package）区分。
 */

/** 语言探测候选。 */
data class TemplateLanguageCandidate(
    val language: String,
    val score: Double,
    /** 命中的信号样例（调试与报告展示用）。 */
    val evidence: List<String>
) {
    /** 置信度（0.0..1.0：胜出分 / 次高分 的边际 + 绝对分共同约束）。 */
    val margin: Double get() = score.coerceIn(0.0, 1.0)
}

/** 探测结果。 */
data class TemplateLanguageDetection(
    val detected: String?,
    val confidence: Double,
    val candidates: List<TemplateLanguageCandidate>
) {
    /** 是否足够可信（行为侧决定是否启用语言专属规则表）。 */
    val isConfident: Boolean get() = detected != null && confidence >= CONFIDENT_THRESHOLD

    /** 展示名（null 安全）。 */
    val displayName: String get() = detected ?: "未知"

    companion object {
        const val CONFIDENT_THRESHOLD = 0.34

        /** 未识别结果。 */
        fun unknown(): TemplateLanguageDetection =
            TemplateLanguageDetection(null, 0.0, emptyList())
    }
}

/** 语言元数据（规则表键 + 展示名 + 注释形态）。 */
data class TemplateLanguageProfile(
    val id: String,
    val displayName: String,
    /** 行注释前缀（规则表「裸 print / 调试残留」类规则用）。 */
    val lineCommentPrefixes: List<String>
) {
    companion object {
        val KOTLIN = TemplateLanguageProfile("kotlin", "Kotlin", listOf("//"))
        val JAVA = TemplateLanguageProfile("java", "Java", listOf("//"))
        val PYTHON = TemplateLanguageProfile("python", "Python", listOf("#"))
        val JAVASCRIPT = TemplateLanguageProfile("javascript", "JavaScript", listOf("//"))
        val TYPESCRIPT = TemplateLanguageProfile("typescript", "TypeScript", listOf("//"))
        val GO = TemplateLanguageProfile("go", "Go", listOf("//"))
        val RUST = TemplateLanguageProfile("rust", "Rust", listOf("//"))
        val C = TemplateLanguageProfile("c", "C", listOf("//"))
        val CPP = TemplateLanguageProfile("cpp", "C++", listOf("//"))
        val CSHARP = TemplateLanguageProfile("csharp", "C#", listOf("//"))
        val SWIFT = TemplateLanguageProfile("swift", "Swift", listOf("//"))
        val PHP = TemplateLanguageProfile("php", "PHP", listOf("//", "#"))
        val RUBY = TemplateLanguageProfile("ruby", "Ruby", listOf("#"))
        val SHELL = TemplateLanguageProfile("shell", "Shell", listOf("#"))
        val SQL = TemplateLanguageProfile("sql", "SQL", listOf("--"))
        val HTML = TemplateLanguageProfile("html", "HTML", emptyList())
        val JSON = TemplateLanguageProfile("json", "JSON", emptyList())
        val YAML = TemplateLanguageProfile("yaml", "YAML", listOf("#"))
        val XML = TemplateLanguageProfile("xml", "XML", emptyList())
        val MARKDOWN = TemplateLanguageProfile("markdown", "Markdown", emptyList())

        /** 全部档案（id → profile）。 */
        val ALL: Map<String, TemplateLanguageProfile> = listOf(
            KOTLIN, JAVA, PYTHON, JAVASCRIPT, TYPESCRIPT, GO, RUST, C, CPP,
            CSHARP, SWIFT, PHP, RUBY, SHELL, SQL, HTML, JSON, YAML, XML, MARKDOWN
        ).associateBy { it.id }

        /** 展示名（未知 id 原样返回 —— 防御式）。 */
        fun displayNameOf(id: String?): String = id?.let { ALL[it]?.displayName ?: it } ?: "未知"
    }
}

/**
 * 编程语言探测器（打分制）。
 */
object TemplateLanguageDetector {

    /** 强信号（独有语法，权重 3.0）。 */
    private val STRONG: Map<String, List<Regex>> = mapOf(
        "kotlin" to listOf(
            Regex("""\bfun\s+[a-zA-Z_]\w*\s*\("""),
            Regex("""\bval\s+\w+\s*[:=]"""),
            Regex("""\bvar\s+\w+\s*[:=]"""),
            Regex("""\bdata class\b"""),
            Regex("""\bwhen\s*\("""),
            Regex("""\?\.(?!\()"""),
            Regex("""\bsuspend fun\b"""),
            Regex("""\bobject\s+\w+\s*\{"""),
            Regex("""\bcompanion object\b"""),
            Regex("""!!\s*[.\s)]""")
        ),
        "java" to listOf(
            Regex("""\bpublic\s+(static\s+)?(final\s+)?class\b"""),
            Regex("""\bSystem\.out\.print"""),
            Regex("""\bprivate\s+(static\s+)?\w+(<[^>]+>)?\s+\w+\s*="""),
            Regex("""@Override\b"""),
            Regex("""\bimport\s+java\.(util|io|lang)\."""),
            Regex("""\bnew\s+[A-Z]\w*\s*\("""),
            Regex("""\bextends\b|\bimplements\b""")
        ),
        "python" to listOf(
            Regex("""\bdef\s+\w+\s*\(.*\)\s*:"""),
            Regex("""\bimport\s+\w+(\s+as\s+\w+)?\s*$""", RegexOption.MULTILINE),
            Regex("""\bfrom\s+\w+(\.\w+)*\s+import\b"""),
            Regex("""\bself\.\w+"""),
            Regex("""\bprint\s*\("""),
            Regex("""__\w+__""")
        ),
        "javascript" to listOf(
            Regex("""\b(function\s+\w+\s*\(|const\s+\w+\s*=\s*(async\s*)?\(|=>\s*\{)"""),
            Regex("""\bconsole\.(log|error|warn)\s*\("""),
            Regex("""\brequire\s*\(\s*['"]"""),
            Regex("""\bmodule\.exports\b"""),
            Regex("""\bdocument\.(querySelector|getElementById)"""),
            // 普通转义串：`\$` 在 raw 串里会触发 `\${` 模板插值，必须用普通串写美元符
            Regex("`[^`]*\\$\\{")
        ),
        "typescript" to listOf(
            Regex("""\binterface\s+\w+\s*\{"""),
            Regex("""\btype\s+\w+\s*=\s*(\{|\w+\s*\||'[^']+'\s*\|)"""),
            Regex("""\bimport\s+.*\bfrom\s+['"]"""),
            Regex("""\w+\s*:\s*(string|number|boolean|void|any|unknown)\b"""),
            Regex("""<[A-Z]\w*(,\s*\w+)*>\s*\(""")
        ),
        "go" to listOf(
            Regex("""\bfunc\s+(\(\s*\w+\s+\*?\w+\s*\)\s+)?\w+\s*\(.*\)\s*(\w+)?\s*\{"""),
            Regex("""\bpackage\s+\w+\s*$""", RegexOption.MULTILINE),
            Regex("""\bfmt\.(Print|Sprint|Errorf)"""),
            Regex(""":=\s*"""),
            Regex("""\bgo\s+func\b|\bdefer\s+\w+\.""")
        ),
        "rust" to listOf(
            Regex("""\bfn\s+\w+\s*\(.*\)\s*(->\s*\w+)?\s*\{"""),
            Regex("""\blet\s+(mut\s+)?\w+\s*(:\s*\w+)?\s*="""),
            Regex("""\bmatch\s+\w+\s*\{"""),
            Regex("""\bimpl\s+\w+"""),
            Regex("""\bprintln!\s*\(""")
        ),
        "c" to listOf(
            Regex("""#include\s*<"""),
            Regex("""\bprintf\s*\("""),
            Regex("""\bmalloc\s*\(|\bfree\s*\("""),
            Regex("""\bstruct\s+\w+\s*\w*;"""),
            Regex("""\bint\s+main\s*\(""")
        ),
        "cpp" to listOf(
            Regex("""#include\s*(<\w+>|"\w+")"""),
            Regex("""\bstd::\w+"""),
            Regex("""\bcout\s*<<|\bcin\s*>>"""),
            Regex("""\btemplate\s*<"""),
            Regex("""\bnew\s+\w+(\[|;)"""),
            Regex("""\bnamespace\s+\w+""")
        ),
        "csharp" to listOf(
            Regex("""\busing\s+[\w.]+;"""),
            Regex("""\bnamespace\s+[\w.]+"""),
            Regex("""\bConsole\.(Write|Read)"""),
            Regex("""\bpublic\s+(partial\s+)?class"""),
            Regex("""\bstring\s+\w+\s*=|List<""")
        ),
        "swift" to listOf(
            Regex("""\bfunc\s+\w+\s*\(.*\)\s*(->\s*[\w?\[\]]+)?\s*\{"""),
            Regex("""\blet\s+\w+\s*(:|=)"""),
            Regex("""\bvar\s+\w+\s*:\s*\w+"""),
            Regex("""\bguard\s+\w+\s+else\b"""),
            Regex("""@State\s+var|@Published\b""")
        ),
        "php" to listOf(
            Regex("""<\?php"""),
            Regex("""\$\w+\s*="""),
            Regex("""\bfunction\s+\w+\s*\(.*\)\s*\{"""),
            Regex("""->\w+\(""")
        ),
        "ruby" to listOf(
            Regex("""\bdef\s+\w+(\s*\(.*\))?$""", RegexOption.MULTILINE),
            Regex("""\bend\b"""),
            Regex("""\bputs\s+|\brequire\s+['"]"""),
            Regex("""#\{\w+\}""")
        ),
        "shell" to listOf(
            Regex("""^#!/(bin/)?(ba)?sh""", RegexOption.MULTILINE),
            Regex("""\becho\s+"""),
            // 同上：`\$\{` 用普通转义串表达（raw 串会把 `\${` 当模板插值）
            Regex("\\$\\{?\\w+\\}?"),
            Regex("""\bfi\b|\besac\b|\bdone\b""")
        ),
        "sql" to listOf(
            Regex("""\bSELECT\b.+\bFROM\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(INSERT\s+INTO|UPDATE\s+\w+\s+SET|DELETE\s+FROM|CREATE\s+TABLE)\b""", RegexOption.IGNORE_CASE),
            Regex("""\bWHERE\b.+=\s*""", RegexOption.IGNORE_CASE),
            Regex("""\b(ORDER\s+BY|GROUP\s+BY|LEFT\s+JOIN|INNER\s+JOIN|LIMIT)\b""", RegexOption.IGNORE_CASE)
        ),
        "html" to listOf(
            Regex("""<!DOCTYPE\s+html""", RegexOption.IGNORE_CASE),
            Regex("""</(div|span|p|html|body)>"""),
            Regex("""<div[\s>]"""),
            Regex("""\s(href|src)=["']""")
        ),
        "json" to listOf(
            Regex("""^\s*\{""", RegexOption.MULTILINE),
            Regex(""""\w+"\s*:\s*("|\d|\{|\[|true|false|null)"""),
            Regex("""\[\s*\{""")
        ),
        "yaml" to listOf(
            Regex("""^---\s*$""", RegexOption.MULTILINE),
            Regex("""^\w[\w-]*:\s*(\S.*)?$""", RegexOption.MULTILINE),
            Regex("""^\s+-\s+\w+:""", RegexOption.MULTILINE)
        ),
        "xml" to listOf(
            Regex("""<\?xml\s+version"""),
            Regex("""</\w+>\s*$""", RegexOption.MULTILINE),
            Regex("""<(?:\w+:)?\w+(\s+\w+="[^"]*")*\s*/?>""")
        ),
        "markdown" to listOf(
            Regex("""^#{1,6}\s+\S""", RegexOption.MULTILINE),
            Regex("""^```""", RegexOption.MULTILINE),
            Regex("""\*\*[^*]+\*\*""")
        )
    )

    /** 弱信号（惯用形态，权重 1.0）。 */
    private val WEAK: Map<String, List<Regex>> = mapOf(
        "kotlin" to listOf(Regex(""":\s*(String|Int|Boolean|List<)"""), Regex("""\bif\s*\(.*\)\s*\{""")),
        "python" to listOf(Regex("""^\s{4}\S""", RegexOption.MULTILINE), Regex("""\bNone\b|\bTrue\b|\bFalse\b""")),
        "java" to listOf(Regex(""";\s*$""", RegexOption.MULTILINE), Regex("""\bnew\s+[A-Z]""")),
        "javascript" to listOf(Regex("""\bvar\s+\w+\s*="""), Regex("""===|!==""")),
        "go" to listOf(Regex("""\bnil\b"""), Regex("""\berror\b""")),
        "rust" to listOf(Regex("""&\w+"""), Regex("""\bmut\b""")),
        "sql" to listOf(Regex("""\bJOIN\b""", RegexOption.IGNORE_CASE), Regex("""\bGROUP\s+BY\b""", RegexOption.IGNORE_CASE))
    )

    /**
     * 探测主入口。
     *
     * @param code 代码片段（可以是片段；过短会因信号不足回落 unknown）
     */
    fun detect(code: String): TemplateLanguageDetection {
        if (code.isBlank()) return TemplateLanguageDetection.unknown()
        val text = code.trim()
        if (text.length < MIN_CODE_CHARS) return TemplateLanguageDetection.unknown()

        val scored = mutableListOf<TemplateLanguageCandidate>()
        for ((language, strongSignals) in STRONG) {
            val evidence = mutableListOf<String>()
            var strongHits = 0
            for (signal in strongSignals) {
                val matches = signal.findAll(text).count()
                if (matches > 0) {
                    strongHits += matches.coerceAtMost(3)
                    evidence.add(signal.pattern.take(24))
                }
            }
            var weakHits = 0
            for (signal in WEAK[language].orEmpty()) {
                if (signal.containsMatchIn(text)) {
                    weakHits++
                    evidence.add(signal.pattern.take(24))
                }
            }
            if (strongHits + weakHits > 0) {
                val score = (STRONG_WEIGHT * strongHits + WEAK_WEIGHT * weakHits) /
                    (STRONG_WEIGHT * strongHits + WEAK_WEIGHT * weakHits + NEUTRALIZER)
                scored.add(TemplateLanguageCandidate(language, score, evidence.take(4)))
            }
        }
        if (scored.isEmpty()) return TemplateLanguageDetection.unknown()
        val ranked = scored.sortedWith(
            compareByDescending<TemplateLanguageCandidate> { it.score }.thenBy { it.language }
        )
        val top = ranked[0]
        val runnerUp = ranked.getOrNull(1)
        // 置信判定双通道：无对手时看绝对分；有对手时看「领先幅度 + 绝对分」
        // 双门槛 —— 防止两个低分候选互相贴着把噪声推成正判。
        val confident = if (runnerUp == null) {
            top.score >= SINGLE_CANDIDATE_MIN_SCORE
        } else {
            top.score >= MULTI_CANDIDATE_TOP_MIN &&
                (top.score - runnerUp.score) >= LEAD_MARGIN
        }
        return TemplateLanguageDetection(
            detected = if (confident) top.language else null,
            confidence = top.score.coerceIn(0.0, 1.0),
            candidates = ranked
        )
    }

    /** 家族归属（规则表选择用：同族共享大量规则）。 */
    fun familyOf(language: String?): String? = when (language) {
        "kotlin", "java", "csharp", "swift" -> "jvm-c-family"
        "c", "cpp" -> "native-c-family"
        "javascript", "typescript" -> "web-family"
        else -> language
    }

    const val MIN_CODE_CHARS = 12
    const val STRONG_WEIGHT = 1.0
    const val WEAK_WEIGHT = 0.4
    const val NEUTRALIZER = 2.0

    /** 无对手候选时，胜出者需要的最低绝对分。 */
    const val SINGLE_CANDIDATE_MIN_SCORE = 0.30

    /** 有对手候选时，胜出者需要的最低绝对分。 */
    const val MULTI_CANDIDATE_TOP_MIN = 0.40

    /** 有对手候选时，胜出者需要领先亚军的最低分差。 */
    const val LEAD_MARGIN = 0.12
}
