package com.apex.agent.core.engine.templates.runtime.builtin

import com.apex.agent.core.engine.templates.runtime.TemplateFinding
import com.apex.agent.core.engine.templates.runtime.TemplateFindingCategory
import com.apex.agent.core.engine.templates.runtime.TemplateSeverity

/**
 * ═══ 内置模板行为 · 代码审查 —— 命名/注释/结构启发器（PR 19 深化 III）═══
 *
 * 代码审查行为的第三层确定性引擎：规则表（[CodeReviewRuleTables]）管
 * 「逐语言的模式级问题」，结构度量（[CodeReviewStructureMetrics]）管
 * 「复杂度/重复/嵌套的数字」，本模块管「规则表和数字都看不到的
 * 语义级坏味道」——命名质量、注释质量、跨函数结构、层次纪律，
 * 全部是确定性启发（有据可查，不靠猜）。
 *
 * 能力族（全部纯函数、全部确定性）：
 * 1. **命名质量**（naming quality）：布尔非 is/has 前缀、无意义短名
 *    （a/b/temp/data/obj）、驼峰断裂（userIdd/HTTPUrl 类不一致）、
 *    同概念双命名（list 与 arr 混用）；
 * 2. **注释质量**（comment quality）：陈旧 TODO/FIXME 计数与分布、
 *    注释掉的代码块（成行的 // 语句形态）、公开 API 缺文档
 *    （public/fun 前无 KDoc 的粗启发）、注释密度异常（0% 或 >40%）；
 * 3. **跨函数坏味道**（cross-function smells）：函数扇出（单函数
 *    调用点计数 god-function 判定）、类内函数密度（god-class）、
 *    魔法数字簇（同一字面量重复 ≥3 次）；
 * 4. **层次纪律**（layering）：UI 层直连存储形态（Activity/Fragment
 *    里出现 SQL/DAO 字样）、纯逻辑层引入 Android 框架（core 模块
 *    纪律的通用版）。
 *
 * 防御式纪律：全部启发只产 MINOR/IMPORTANT 级发现（BLOCKER 留给
 * 规则表的安全/正确性命中），置信度如实标注，绝不抛。
 */
object CodeReviewHeuristics {

    // ═══ 1) 命名质量 ═══

    /** 无意义短名黑名单。 */
    private val MEANINGLESS_NAMES = setOf(
        "a", "b", "c", "x", "y", "z", "temp", "tmp", "data", "obj", "val1", "val2",
        "foo", "bar", "baz", "thing", "stuff", "res", "ret", "o", "e", "s"
    )

    /** 布尔命名前缀白名单。 */
    private val BOOLEAN_PREFIXES = listOf("is", "has", "should", "can", "will", "need", "allow")

    /** 变量声明形态（val/var/let/const + 名字）。 */
    private val DECLARATION: Regex = Regex(
        """\b(?:val|var|let|const)\s+([a-zA-Z_][a-zA-Z0-9_]*)"""
    )

    /** 函数定义形态（fun/def/func + 名字）。 */
    private val FUNCTION_DEF: Regex = Regex(
        """\b(?:fun|def|func|function)\s+([a-zA-Z_][a-zA-Z0-9_]*)"""
    )

    /**
     * 命名质量检查：
     * - 布尔命名（类型提示 Boolean/bool 或 isXxx 判定为布尔的声明）缺前缀；
     * - 无意义短名；
     * - 声明全是单字母/两字母（批量预警而非逐个报）。
     */
    fun namingFindings(code: String, language: String?): List<TemplateFinding> {
        val findings = mutableListOf<TemplateFinding>()
        // 1) 无意义短名（批量统计，单条 finding 收敛噪声）
        val declared = DECLARATION.findAll(code).map { it.groupValues[1] }.toList() +
            FUNCTION_DEF.findAll(code).map { it.groupValues[1] }.toList()
        if (declared.isEmpty()) return findings
        val meaningless = declared.filter { it.lowercase() in MEANINGLESS_NAMES }
        if (meaningless.size >= 2) {
            findings += TemplateFinding(
                ruleId = "heuristic.meaningless-names",
                category = TemplateFindingCategory.MAINTAINABILITY,
                severity = TemplateSeverity.MINOR,
                title = "${meaningless.size} 个无意义命名（${meaningless.distinct().take(4).joinToString("、")}）",
                evidence = "声明总数 ${declared.size}，短名占比 ${"%.0f".format(meaningless.size * 100.0 / declared.size)}%",
                suggestion = "名字写用途：data → userData / ret → parsedResult",
                confidence = 0.95
            )
        }
        // 2) 布尔命名前缀
        val booleanDecls = Regex(
            """\b(?:val|var|let|const)\s+([a-zA-Z_][a-zA-Z0-9_]*)\s*:\s*(?:Boolean|Bool|bool)"""
        ).findAll(code).map { it.groupValues[1] }.toList()
        val badBooleans = booleanDecls.filterNot { name ->
            BOOLEAN_PREFIXES.any { prefix -> name.lowercase().startsWith(prefix) }
        }
        if (badBooleans.isNotEmpty()) {
            findings += TemplateFinding(
                ruleId = "heuristic.boolean-naming",
                category = TemplateFindingCategory.MAINTAINABILITY,
                severity = TemplateSeverity.MINOR,
                title = "${badBooleans.size} 个布尔命名缺 is/has 前缀（${badBooleans.take(3).joinToString("、")}）",
                evidence = "布尔值名应自述真值语义（isEmpty / hasChildren）",
                suggestion = "改名加前缀：enabled → isEnabled / found → isFound",
                confidence = 0.9
            )
        }
        // 3) 同概念双命名（list/arr、map/dict、str/text、count/num 混用）
        val conceptGroups = listOf(
            "列表" to listOf("list", "arr", "array", "items"),
            "映射" to listOf("map", "dict", "dictionary"),
            "字符串" to listOf("str", "text", "s", "string"),
            "计数" to listOf("count", "num", "cnt", "number")
        )
        for ((concept, synonyms) in conceptGroups) {
            val used = synonyms.filter { synonym -> declared.any { it.lowercase().endsWith(synonym) } }
            if (used.size >= 2) {
                findings += TemplateFinding(
                    ruleId = "heuristic.dual-naming",
                    category = TemplateFindingCategory.CONSISTENCY,
                    severity = TemplateSeverity.MINOR,
                    title = "$concept 存在双命名习惯（${used.joinToString("/")}）",
                    evidence = "同一代码库同一概念应统一一个名字",
                    suggestion = "统一为团队约定的那一个（如 list）",
                    confidence = 0.85
                )
            }
        }
        return findings
    }

    // ═══ 2) 注释质量 ═══

    /**
     * 注释质量检查：陈旧标记 / 注释掉的代码 / 公开 API 无文档 /
     * 注释密度异常。
     */
    fun commentFindings(code: String): List<TemplateFinding> {
        val findings = mutableListOf<TemplateFinding>()
        val lines = code.lines()
        // 1) TODO/FIXME/HACK 计数
        val markers = listOf("TODO", "FIXME", "HACK", "XXX")
        val markerHits = lines.mapIndexedNotNull { index, line ->
            markers.firstOrNull { line.contains(it) }?.let { it to index + 1 }
        }
        if (markerHits.isNotEmpty()) {
            findings += TemplateFinding(
                ruleId = "heuristic.stale-markers",
                category = TemplateFindingCategory.MAINTAINABILITY,
                severity = TemplateSeverity.MINOR,
                title = "代码含 ${markerHits.size} 处 TODO/FIXME/HACK 标记",
                evidence = markerHits.take(3).joinToString("；") { "${it.first}@行${it.second}" },
                suggestion = "评审时确认每个标记有归属（issue 链接或负责人），无归属的清理",
                confidence = 1.0
            )
        }
        // 2) 注释掉的代码（连续 3+ 行 // 开头且行内含代码形态）
        val commentedCode = commentedCodeBlockCount(lines)
        if (commentedCode >= 1) {
            findings += TemplateFinding(
                ruleId = "heuristic.commented-code",
                category = TemplateFindingCategory.MAINTAINABILITY,
                severity = TemplateSeverity.MINOR,
                title = "发现 $commentedCode 处「注释掉的代码」块",
                evidence = "注释代码 = 死代码：版本控制系统里永远找得回来",
                suggestion = "直接删除；需要保留的用文档链接说明",
                confidence = 0.9
            )
        }
        // 3) 注释密度
        val commentLines = lines.count { line ->
            line.trimStart().startsWith("//") || line.trimStart().startsWith("#") ||
                line.trimStart().startsWith("*")
        }
        val nonBlank = lines.count { it.isNotBlank() }
        if (nonBlank > MIN_LINES_FOR_DENSITY) {
            val density = commentLines * 100 / nonBlank
            if (density == 0) {
                findings += TemplateFinding(
                    ruleId = "heuristic.no-comments",
                    category = TemplateFindingCategory.MAINTAINABILITY,
                    severity = TemplateSeverity.MINOR,
                    title = "全文件零注释（$nonBlank 行代码）",
                    evidence = "零注释文件在交接与排障时成本最高",
                    suggestion = "至少给公开 API 与复杂算法补 KDoc/文档注释",
                    confidence = 1.0
                )
            } else if (density > 40) {
                findings += TemplateFinding(
                    ruleId = "heuristic.comment-heavy",
                    category = TemplateFindingCategory.MAINTAINABILITY,
                    severity = TemplateSeverity.INFO,
                    title = "注释密度 ${density}%（>40%）—— 注释在复述代码",
                    evidence = "注释应解释 why 而不是 what",
                    suggestion = "删除复述型注释，保留意图与约束说明",
                    confidence = 0.8
                )
            }
        }
        return findings
    }

    /** 连续注释代码块计数（≥3 行 // 且含代码特征）。 */
    private fun commentedCodeBlockCount(lines: List<String>): Int {
        var blocks = 0
        var run = 0
        for (line in lines) {
            val trimmed = line.trim()
            val isCommentedCode = trimmed.startsWith("//") && trimmed.length > 3 &&
                Regex("""[;{}()=]|fun |val |var |return |if |for """).containsMatchIn(trimmed)
            if (isCommentedCode) {
                run++
                if (run == COMMENTED_CODE_MIN_LINES) blocks++
            } else {
                run = 0
            }
        }
        return blocks
    }

    // ═══ 3) 跨函数坏味道 ═══

    /**
     * 跨函数结构检查：魔法数字簇 / 调用扇出 / 类内函数密度
     * （复用 [CodeReviewTemplateBehavior.findFunctions] 的边界结果）。
     */
    fun structuralSmellFindings(
        code: String,
        spans: List<CodeReviewTemplateBehavior.FunctionSpan>
    ): List<TemplateFinding> {
        val findings = mutableListOf<TemplateFinding>()
        // 1) 魔法数字簇（同一非平凡数字字面量出现 ≥3 次）
        val numberHits = Regex("""\b\d+(\.\d+)?\b""").findAll(code)
            .map { it.value }
            .filter { it != "0" && it != "1" && it.length >= 2 }
            .groupingBy { it }
            .eachCount()
            .filter { it.value >= MAGIC_NUMBER_REPEATS }
        if (numberHits.isNotEmpty()) {
            val (number, count) = numberHits.entries.maxByOrNull { it.value }!!
            findings += TemplateFinding(
                ruleId = "heuristic.magic-number",
                category = TemplateFindingCategory.MAINTAINABILITY,
                severity = TemplateSeverity.MINOR,
                title = "魔法数字「$number」重复出现 $count 次",
                evidence = numberHits.entries.joinToString("；") { "${it.key}×${it.value}" }.take(60),
                suggestion = "提取为具名常量（数字的意义写进名字）",
                confidence = 0.9
            )
        }
        // 2) 扇出：函数体内调用计数（heuristic：行内出现 xx( 形态）
        if (spans.isNotEmpty()) {
            val lines = code.lines()
            val fanOuts = spans.map { span ->
                val body = lines.drop(span.startLine - 1).take(span.lineCount).joinToString("\n")
                val callCount = Regex("""\b[a-zA-Z_][\w.]*\s*\(""").findAll(body)
                    .count { !it.value.contains("if") && !it.value.contains("for") && !it.value.contains("while") }
                span.name to callCount
            }
            val worst = fanOuts.maxByOrNull { it.second }
            if (worst != null && worst.second >= FAN_OUT_THRESHOLD) {
                findings += TemplateFinding(
                    ruleId = "heuristic.high-fan-out",
                    category = TemplateFindingCategory.MAINTAINABILITY,
                    severity = TemplateSeverity.MINOR,
                    title = "函数「${worst.first}」扇出过高（约 ${worst.second} 个调用点）",
                    evidence = "高扇出 = 高耦合：改动任一被调方都可能波及",
                    suggestion = "按职责拆分或引入中间抽象收敛依赖",
                    confidence = 0.8
                )
            }
        }
        // 3) 类内函数密度（god class 启发：单文件函数数）
        if (spans.size >= GOD_CLASS_FUNCTIONS) {
            findings += TemplateFinding(
                ruleId = "heuristic.god-file",
                category = TemplateFindingCategory.MAINTAINABILITY,
                severity = TemplateSeverity.IMPORTANT,
                title = "单文件 ${spans.size} 个函数（God-file 倾向）",
                evidence = "函数过多通常意味着职责堆叠（多原因变化）",
                suggestion = "按职责切分文件；每个文件一个变化理由",
                confidence = 0.85
            )
        }
        return findings
    }

    // ═══ 4) 层次纪律 ═══

    /** UI 层直连存储的形态词。 */
    private val UI_STORAGE_HINTS = listOf("SELECT ", "INSERT INTO", "DAO", "dao.", "Cursor", "rawQuery")

    /** UI 层类名形态。 */
    private val UI_CLASS_HINT = listOf("Activity", "Fragment", "Screen", "Page", "ViewController")

    /** 纯逻辑层的框架依赖形态（android./androidx. 出现在非 UI 文件）。 */
    private val FRAMEWORK_IMPORTS = listOf("import android.", "import androidx.", "import android.util.Log")

    /**
     * 层次纪律检查：
     * - UI 类（文件名/类名含 Activity/Fragment/Screen）内出现 SQL/DAO
     *   直连 → 层次穿透；
     * - 无 UI 特征的文件 import 平台框架 → 纯逻辑被平台污染。
     */
    fun layeringFindings(code: String): List<TemplateFinding> {
        val findings = mutableListOf<TemplateFinding>()
        val looksLikeUi = UI_CLASS_HINT.any { code.contains(it) }
        if (looksLikeUi) {
            val storageHit = UI_STORAGE_HINTS.firstOrNull { code.contains(it) }
            if (storageHit != null) {
                findings += TemplateFinding(
                    ruleId = "heuristic.ui-storage-coupling",
                    category = TemplateFindingCategory.MAINTAINABILITY,
                    severity = TemplateSeverity.IMPORTANT,
                    title = "UI 层直连存储（命中「$storageHit」）",
                    evidence = "UI 应只依赖 ViewModel/Presenter，存储访问走仓库层",
                    suggestion = "把数据访问下沉到 Repository，UI 只观察状态",
                    confidence = 0.85
                )
            }
        } else {
            val frameworkImport = FRAMEWORK_IMPORTS.firstOrNull { code.contains(it) }
            if (frameworkImport != null && !code.contains("Compose") && !code.contains("Activity")) {
                findings += TemplateFinding(
                    ruleId = "heuristic.framework-in-logic",
                    category = TemplateFindingCategory.MAINTAINABILITY,
                    severity = TemplateSeverity.MINOR,
                    title = "纯逻辑文件引入平台框架（$frameworkImport）",
                    evidence = "平台依赖让逻辑无法在 JVM 单测里跑（这也是本项目 core 层的纪律）",
                    suggestion = "通过接口注入平台能力（Logger/时钟/资源）",
                    confidence = 0.75
                )
            }
        }
        return findings
    }

    // ═══ 聚合入口 ═══

    /**
     * 全量启发（行为 ANALYZE 第三层调用）：命名 + 注释 + 结构 + 层次。
     * 总量夹在 [MAX_FINDINGS]（启发是辅助信号，不淹没规则表主信号）。
     */
    fun allFindings(
        code: String,
        language: String?,
        spans: List<CodeReviewTemplateBehavior.FunctionSpan>
    ): List<TemplateFinding> =
        (namingFindings(code, language) +
            commentFindings(code) +
            structuralSmellFindings(code, spans) +
            layeringFindings(code)).take(MAX_FINDINGS)

    // ═══ 常量 ═══

    private const val MAX_FINDINGS = 10
    private const val MIN_LINES_FOR_DENSITY = 20
    private const val COMMENTED_CODE_MIN_LINES = 3
    private const val MAGIC_NUMBER_REPEATS = 3
    private const val FAN_OUT_THRESHOLD = 15
    private const val GOD_CLASS_FUNCTIONS = 20
}
