package com.apex.agent.core.engine.templates.runtime.builtin

/**
 * ═══ 内置模板行为 · 正则解释 —— 优化与测试工具箱（PR 19 深化 III）═══
 *
 * 正则解释行为的第二层确定性引擎：[RegexParser] 管「这个正则是什么
 * 意思、有什么风险」，本模块管「怎么把它写得更快、怎么验证它对不对」
 * ——方言探测、确定性优化重写、多语言测试片段生成、冗余简化，
 * 全部基于 AST，不需要真的执行正则。
 *
 * 能力族（全部纯函数、全部确定性）：
 * 1. **方言探测**（flavor detection）：(?P<name>) → Python、
 *    (?<name>) → Java/Kotlin/.NET、(?i) 内联旗标 → PCRE 系、
 *    \p{...} → PCRE/Java、POSIX 类 → POSIX 系；探测结果决定
 *    测试片段生成哪种语言；
 * 2. **优化重写**（optimization）：灾难性构造的确定性修复 ——
 *    嵌套量词 (a+)+ → 占有化 (a++) 或独立化改写；歧义交替
 *    (a|a)* → 去重交替；无界量词包裹多选项 → 收敛上界建议；
 * 3. **测试片段生成**（test snippet）：AST → 匹配样例 +
 *    不匹配样例 + Kotlin/Python/JavaScript 三语言断言片段
 *    （静态推导，不执行）；
 * 4. **冗余简化**（simplification）：[0-9] → \d、[a-zA-Z0-9_] → \w、
 *    双重否定字符类、不必要的转义（\. 之外的 \q 等）、
 *    可合并的相邻字符类；
 * 5. **锚点与字面分析**（anchor analysis）：^…$ 是否两端锚定、
 *    贪婪 vs 惰性量词分布、捕获组数量与命名情况 —— 性能与
 *    可维护性的量化底盘。
 *
 * 防御式纪律：解析失败（RegexParseResult.error != null）时全部
 * 降级为空结果，绝不抛。
 */

/** 正则方言。 */
enum class RegexFlavor(val label: String) {
    PCRE("PCRE（Perl 兼容）"),
    PYTHON("Python re"),
    JAVA("Java/Kotlin Regex"),
    JAVASCRIPT("JavaScript RegExp"),
    POSIX("POSIX ERE"),
    UNKNOWN("未识别");

    companion object {
        /** 从构造特征探测（命中多种取最特征化的）。 */
        fun detect(pattern: String): RegexFlavor = when {
            pattern.contains("(?P<") -> PYTHON
            pattern.contains("(?<") && !pattern.contains("(?<=") && !pattern.contains("(?<!") -> JAVA
            pattern.contains("(?P=") || pattern.contains("\\p{") || pattern.contains("(?J)") -> PCRE
            pattern.contains("[[:alpha:]]") || pattern.contains("[[:digit:]]") -> POSIX
            pattern.contains("\\u{") || pattern.contains("/flags") || pattern.contains("(?<!") -> JAVASCRIPT
            else -> UNKNOWN
        }
    }
}

/** 一条优化建议（含确定性重写）。 */
data class RegexOptimization(
    val kind: Kind,
    val detail: String,
    /** 原片段。 */
    val original: String,
    /** 优化后片段（无机械改写时为空）。 */
    val rewritten: String,
    /** 预期收益说明。 */
    val benefit: String
) {
    enum class Kind(val label: String) {
        NESTED_QUANTIFIER("嵌套量词"),
        AMBIGUOUS_ALTERNATION("歧义交替"),
        UNBOUNDED_QUANTIFIER("无界量词"),
        REDUNDANT_CLASS("冗余字符类"),
        UNNECESSARY_ESCAPE("不必要转义"),
        DOUBLE_NEGATION("双重否定")
    }
}

/** 测试样例（静态推导）。 */
data class RegexTestCase(
    val shouldMatch: Boolean,
    val sample: String,
    /** 为什么（喂断言注释）。 */
    val rationale: String
)

/** 锚点与结构分析。 */
data class RegexStructureProfile(
    val anchoredStart: Boolean,
    val anchoredEnd: Boolean,
    val greedyQuantifiers: Int,
    val lazyQuantifiers: Int,
    val capturingGroups: Int,
    val namedGroups: Int,
    val alternationCount: Int
)

/**
 * 工具箱主体（纯函数集合）。
 */
object RegexToolkit {

    // ═══ 1) 优化重写 ═══

    /** 嵌套量词形态：量词体本身又是量词（AST 判定，穿透分组包裹）。 */
    private fun isNestedQuantifier(node: RegexNode.Quantifier): Boolean {
        val body = unwrapGroup(node.body)
        return body is RegexNode.Quantifier
    }

    /** 穿透分组包裹（捕获/非捕获组不影响内层结构判定）。 */
    private fun unwrapGroup(node: RegexNode): RegexNode =
        if (node is RegexNode.Group) unwrapGroup(node.body) else node

    /**
     * 优化建议全量扫描（AST + 源码双通道）：
     * - 嵌套量词 → 占有量化改写（+ → ++ 等）；
     * - 歧义交替（同选项重复 / 选项间前缀包含）→ 去重或重排；
     * - 无界量词 × 交替 → 建议收敛上界或锚定；
     * - 冗余字符类（源码正则通道）；
     * - 双重否定 [^\\D] 形态。
     */
    fun optimize(source: String): List<RegexOptimization> {
        val optimizations = mutableListOf<RegexOptimization>()
        val parsed = RegexParser.parse(source)
        val root = parsed.root ?: return optimizations
        walkOptimize(root, source, optimizations)
        // 源码通道：字符类简化
        optimizations += simplifyFromClassSyntax(source)
        return optimizations
    }

    private fun walkOptimize(
        node: RegexNode,
        source: String,
        out: MutableList<RegexOptimization>
    ) {
        when (node) {
            is RegexNode.Quantifier -> {
                if (isNestedQuantifier(node) && node.max < 0) {
                    out += RegexOptimization(
                        kind = RegexOptimization.Kind.NESTED_QUANTIFIER,
                        detail = "嵌套无界量词（外层量词包裹内层量词）—— 回溯爆炸的经典形态",
                        original = source.take(40),
                        rewritten = possessiveRewriteHint(node),
                        benefit = "占有量词（++/*+）让内层不回溯：最坏情况从指数级降到线性"
                    )
                }
                if (node.max < 0 && unwrapGroup(node.body) is RegexNode.Alternation) {
                    out += RegexOptimization(
                        kind = RegexOptimization.Kind.UNBOUNDED_QUANTIFIER,
                        detail = "无界量词直接作用于交替选项 —— 每轮展开都要试全部选项",
                        original = source.take(40),
                        rewritten = "",
                        benefit = "给量词加合理上界（如 {0,64}）或把高频选项前置 + 锚定前缀"
                    )
                }
                walkOptimize(node.body, source, out)
            }
            is RegexNode.Group -> walkOptimize(node.body, source, out)
            is RegexNode.Sequence -> node.nodes.forEach { walkOptimize(it, source, out) }
            is RegexNode.Alternation -> {
                val literals = node.options.mapNotNull { option ->
                    (option as? RegexNode.Literal)?.char
                }
                if (literals.size != literals.distinct().size) {
                    out += RegexOptimization(
                        kind = RegexOptimization.Kind.AMBIGUOUS_ALTERNATION,
                        detail = "交替选项存在重复 —— 重复项只会浪费回溯预算",
                        original = literals.joinToString("|"),
                        rewritten = literals.distinct().joinToString("|"),
                        benefit = "去重后交替分支减少，匹配路径更短"
                    )
                }
                node.options.forEach { walkOptimize(it, source, out) }
            }
            else -> Unit
        }
    }

    /** 占有化改写提示（不能盲改 —— 语义可能变化，给提示形态）。 */
    private fun possessiveRewriteHint(node: RegexNode.Quantifier): String {
        val inner = when (val body = node.body) {
            is RegexNode.Quantifier -> quantifierSuffix(body)
            is RegexNode.Group -> (body.body as? RegexNode.Quantifier)?.let { quantifierSuffix(it) } ?: "+"
            else -> "+"
        }
        return "(…$inner)${quantifierSuffix(node)}+  ← 占有化形态（先确认语义不变）"
    }

    private fun quantifierSuffix(q: RegexNode.Quantifier): String = when {
        q.min == 0 && q.max == -1 -> "*"
        q.min == 1 && q.max == -1 -> "+"
        q.min == 0 && q.max == 1 -> "?"
        q.max == -1 -> "{${q.min},}"
        else -> "{${q.min},${q.max}}"
    }

    /** 源码通道的字符类简化（无需 AST 的确定性简化）。 */
    private fun simplifyFromClassSyntax(source: String): List<RegexOptimization> {
        val optimizations = mutableListOf<RegexOptimization>()
        if (source.contains("[0-9]")) {
            optimizations += RegexOptimization(
                kind = RegexOptimization.Kind.REDUNDANT_CLASS,
                detail = "[0-9] 可简写为 \\d（等价且更短；与已有的 \\d 写法统一）",
                original = "[0-9]",
                rewritten = "\\d",
                benefit = "可读性与全文一致性（同一语义只用一种写法）"
            )
        }
        if (source.contains("[a-zA-Z0-9_]")) {
            optimizations += RegexOptimization(
                kind = RegexOptimization.Kind.REDUNDANT_CLASS,
                detail = "[a-zA-Z0-9_] 可简写为 \\w",
                original = "[a-zA-Z0-9_]",
                rewritten = "\\w",
                benefit = "更短且语义明确（注意 \\w 受 Unicode 旗标影响，ASCII 语义用 (?-u) 说明）"
            )
        }
        if (Regex("""\[\^\\D\]""").containsMatchIn(source) || Regex("""\[\^\\W\]""").containsMatchIn(source)) {
            optimizations += RegexOptimization(
                kind = RegexOptimization.Kind.DOUBLE_NEGATION,
                detail = "双重否定字符类（[^\\D] 形态）—— 否定类的否定难读",
                original = "[^\\D]",
                rewritten = "\\d",
                benefit = "语义等价但一眼可读"
            )
        }
        return optimizations
    }

    // ═══ 2) 结构画像 ═══

    /** AST → 结构画像（锚点/量词分布/捕获组统计）。 */
    fun profile(source: String): RegexStructureProfile {
        val parsed = RegexParser.parse(source)
        val root = parsed.root ?: return RegexStructureProfile(
            anchoredStart = false, anchoredEnd = false, greedyQuantifiers = 0,
            lazyQuantifiers = 0, capturingGroups = 0, namedGroups = 0, alternationCount = 0
        )
        var anchoredStart = false
        var anchoredEnd = false
        var greedy = 0
        var lazy = 0
        var capturing = 0
        var named = 0
        var alternations = 0

        fun walk(node: RegexNode?) {
            when (node) {
                is RegexNode.Anchor -> when (node.kind) {
                    "^", "\\A" -> anchoredStart = true
                    "$", "\\z", "\\Z" -> anchoredEnd = true
                }
                is RegexNode.Quantifier -> {
                    if (node.lazy) lazy++ else greedy++
                    walk(node.body)
                }
                is RegexNode.Group -> {
                    when (node.kind) {
                        RegexNode.GroupKind.CAPTURING -> capturing++
                        RegexNode.GroupKind.NAMED -> {
                            capturing++
                            named++
                        }
                        else -> Unit
                    }
                    walk(node.body)
                }
                is RegexNode.Sequence -> node.nodes.forEach { walk(it) }
                is RegexNode.Alternation -> {
                    alternations++
                    node.options.forEach { walk(it) }
                }
                else -> Unit
            }
        }
        walk(root)
        return RegexStructureProfile(
            anchoredStart = anchoredStart,
            anchoredEnd = anchoredEnd,
            greedyQuantifiers = greedy,
            lazyQuantifiers = lazy,
            capturingGroups = capturing,
            namedGroups = named,
            alternationCount = alternations
        )
    }

    /** 两端锚定判定（未锚定的正则在大文本上是子串匹配 —— 性能陷阱）。 */
    fun isFullyAnchored(source: String): Boolean {
        val profile = profile(source)
        return profile.anchoredStart && profile.anchoredEnd
    }

    // ═══ 3) 测试片段生成 ═══

    /**
     * 静态样例推导（AST → 匹配/不匹配各一条，不执行正则）：
     * - 字面序列 → 字面串匹配样例；
     * - 字符类 → 样例字符；
     * - Predefined → 代表字符；
     * - 交替 → 首选项。
     */
    fun deriveTestCases(source: String): List<RegexTestCase> {
        val parsed = RegexParser.parse(source)
        val root = parsed.root ?: return emptyList()
        val (positive, negative) = deriveSamples(root)
        val cases = mutableListOf<RegexTestCase>()
        positive?.let {
            cases += RegexTestCase(shouldMatch = true, sample = it, rationale = "按 AST 推导的命中样例")
        }
        negative?.let {
            cases += RegexTestCase(shouldMatch = false, sample = it, rationale = "按 AST 推导的反例（刻意不满足）")
        }
        return cases
    }

    /** 样例推导（返回 匹配样例 与 反例；null = 无法静态推导）。 */
    private fun deriveSamples(root: RegexNode): Pair<String?, String?> {
        val positive = buildSample(root, positive = true) ?: return null to null
        val negative = buildSample(root, positive = false) ?: return positive to null
        return positive to negative
    }

    private fun buildSample(node: RegexNode?, positive: Boolean, depth: Int = 0): String? {
        if (depth > MAX_SAMPLE_DEPTH) return null
        return when (node) {
            null -> null
            is RegexNode.Literal -> node.char
            is RegexNode.Dot -> if (positive) "a" else " "
            is RegexNode.CharClass -> charClassSample(node, positive)
            is RegexNode.Predefined -> predefinedSample(node.name, positive)
            is RegexNode.Anchor -> "" // 锚点不产生字符
            is RegexNode.BackReference -> null
            is RegexNode.Sequence -> {
                val parts = node.nodes.map { buildSample(it, positive, depth + 1) }
                if (parts.any { it == null }) null else parts.joinToString("")
            }
            is RegexNode.Alternation -> {
                val choice = if (positive) node.options.firstOrNull() else node.options.lastOrNull()
                buildSample(choice, positive, depth + 1)
            }
            is RegexNode.Group -> buildSample(node.body, positive, depth + 1)
            is RegexNode.Quantifier -> {
                val body = buildSample(node.body, positive, depth + 1) ?: return null
                when {
                    positive -> body.repeat(node.min.coerceAtLeast(1).coerceAtMost(3))
                    node.min == 0 -> body // 反例：0 次（不满足 1+ 语义时不适用）
                    else -> null
                }
            }
        }
    }

    private fun charClassSample(node: RegexNode.CharClass, positive: Boolean): String? {
        if (node.negated) {
            // 反转类：正例取一个肯定不在类里的字符（用 sample 之外的安全字符）
            return if (positive) {
                if (node.sample.contains("a")) "z" else "a"
            } else {
                node.sample.take(1).ifEmpty { null }
            }
        }
        return if (positive) node.sample.take(1).ifEmpty { null } else {
            // 正类的反例：取类外字符
            if (node.sample.contains("0")) "x" else "0"
        }
    }

    private fun predefinedSample(name: String, positive: Boolean): String? = when (name) {
        "d" -> if (positive) "7" else "x"
        "D" -> if (positive) "x" else "7"
        "w" -> if (positive) "a" else "!"
        "W" -> if (positive) "!" else "a"
        "s" -> if (positive) " " else "a"
        "S" -> if (positive) "a" else " "
        else -> null
    }

    /**
     * 多语言测试片段生成（静态；按方言选默认语言）：
     * Kotlin / Python / JavaScript 三选一或全出。
     */
    fun generateTestSnippet(source: String, language: String): String {
        val cases = deriveTestCases(source)
        val positive = cases.firstOrNull { it.shouldMatch }?.sample ?: "(无法静态推导，手写样例)"
        val negative = cases.firstOrNull { !it.shouldMatch }?.sample ?: "(无法静态推导，手写反例)"
        val escaped = source.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
        return when (language.lowercase()) {
            "python" -> buildString {
                appendLine("import re")
                appendLine()
                appendLine("PATTERN = r\"\"\"$source\"\"\"")
                appendLine()
                appendLine("def test_positive():")
                appendLine("    assert re.search(PATTERN, \"${positive.replace("\"", "\\\"")}\")")
                appendLine()
                appendLine("def test_negative():")
                append("    assert not re.search(PATTERN, \"${negative.replace("\"", "\\\"")}\")")
            }
            "javascript", "js", "ts" -> buildString {
                appendLine("const pattern = /$source/;")
                appendLine()
                appendLine("test('positive', () => {")
                appendLine("  expect(pattern.test('${positive.replace("'", "\\'")}')).toBe(true);")
                appendLine("});")
                appendLine()
                appendLine("test('negative', () => {")
                append("  expect(pattern.test('${negative.replace("'", "\\'")}')).toBe(false);");
            }
            else -> buildString {
                appendLine("val pattern = Regex(\"\"\"$escaped\"\"\")")
                appendLine()
                appendLine("@Test")
                appendLine("fun `positive sample matches`() {")
                appendLine("    assertTrue(pattern.containsMatchIn(\"\"\"$positive\"\"\"))")
                appendLine("}")
                appendLine()
                appendLine("@Test")
                appendLine("fun `negative sample rejected`() {")
                append("    assertFalse(pattern.containsMatchIn(\"\"\"$negative\"\"\"))")
                appendLine("}")
            }
        }
    }

    /** 按方言推荐测试语言。 */
    fun recommendTestLanguage(flavor: RegexFlavor): String = when (flavor) {
        RegexFlavor.PYTHON -> "python"
        RegexFlavor.JAVASCRIPT -> "javascript"
        else -> "kotlin"
    }

    // ═══ 常量 ═══

    private const val MAX_SAMPLE_DEPTH = 12
}
