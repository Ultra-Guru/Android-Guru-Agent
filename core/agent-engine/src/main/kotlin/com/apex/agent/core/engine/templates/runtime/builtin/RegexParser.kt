package com.apex.agent.core.engine.templates.runtime.builtin

/**
 * ═══ 内置模板行为 · 正则解释 —— 解析器与解释器（PR 14）═══
 *
 * 真正的正则解析器（不是字符串猜测）：手写递归下降 —— 词法（转义/字符类/
 * 量词/组）→ AST → 三个消费者：
 * 1. **逐段解释**：每个 AST 节点 → 中文语义说明；
 * 2. **样例生成**：从 AST 推导「应匹配」与「不应匹配」样本（确定性）；
 * 3. **风险分析**：灾难性回溯模式（嵌套量词/相邻无界量词/交替重叠）与
 *    简化建议（[0-9]→\d、(a|b)→[ab] 等）。
 *
 * 支持子集（常见核心）：字面量、`.`、字符类（区间/否定/转义）、预定义类
 * （\d\D\w\W\s\S）、锚点（^$\b\B\A\z\Z）、捕获/非捕获/命名组、前瞻
 * （?=、?!）、量词（*+?{m}{m,}{m,n}、惰性 `?` 后缀）、交替、分组回溯引用
 * \1..（识别但按引用语义解释）。
 */

/** 解析结果。 */
data class RegexParseResult(
    val root: RegexNode?,
    /** 源正则（原样）。 */
    val source: String,
    /** 解析错误（null = 成功）。 */
    val error: String?,
    /** 捕获组计数（不含非捕获）。 */
    val groupCount: Int
)

/** 正则 AST 节点。 */
sealed class RegexNode {
    /** 字面字符。 */
    data class Literal(val char: String) : RegexNode()

    /** 任意字符（点）。 */
    object Dot : RegexNode()

    /** 字符类：[a-z0-9_] / [^abc]。 */
    data class CharClass(
        val negated: Boolean,
        /** 展示片段（原文形态，解释用）。 */
        val raw: String,
        val sample: String
    ) : RegexNode()

    /** 预定义类：\d \D \w \W \s \S。 */
    data class Predefined(val name: String) : RegexNode()

    /** 锚点：^ $ \b \B \A \z \Z。 */
    data class Anchor(val kind: String) : RegexNode()

    /** 序列（相邻拼接）。 */
    data class Sequence(val nodes: List<RegexNode>) : RegexNode()

    /** 交替：a|b|c。 */
    data class Alternation(val options: List<RegexNode>) : RegexNode()

    /** 组：捕获 / 非捕获 (?:) / 命名 (?<name>) / 前瞻 (?=)(?!)。 */
    data class Group(
        val kind: GroupKind,
        val name: String?,
        val index: Int?,
        val body: RegexNode
    ) : RegexNode()

    /** 量词：* + ? {m} {m,} {m,n}，lazy = 惰性。 */
    data class Quantifier(
        val body: RegexNode,
        val min: Int,
        /** -1 = 无上界。 */
        val max: Int,
        val lazy: Boolean
    ) : RegexNode()

    /** 反向引用 \1。 */
    data class BackReference(val index: Int) : RegexNode()

    enum class GroupKind(val label: String) {
        CAPTURING("捕获组"),
        NON_CAPTURING("非捕获组"),
        NAMED("命名捕获组"),
        LOOKAHEAD("正向前瞻"),
        NEGATIVE_LOOKAHEAD("负向前瞻")
    }
}

/** 逐段解释条目。 */
data class RegexSegmentExplanation(
    /** 片段原文（尽量还原）。 */
    val fragment: String,
    val explanation: String
)

/** 风险条目。 */
data class RegexRisk(
    val level: RegexRiskLevel,
    val pattern: String,
    val detail: String,
    val suggestion: String
)

enum class RegexRiskLevel(val label: String) {
    CATASTROPHIC("灾难性回溯"),
    HIGH("高回溯风险"),
    MODERATE("中等风险"),
    LOW("低风险")
}

/**
 * 正则解析器 + 解释器（纯函数，防御式：任何输入不抛）。
 */
object RegexParser {

    // ═══ 解析 ═══

    /**
     * 解析正则（递归下降）。失败返回 error（不抛异常）。
     */
    fun parse(source: String): RegexParseResult {
        if (source.isEmpty()) {
            return RegexParseResult(null, source, "正则为空", 0)
        }
        return try {
            val parser = Parser(source)
            val root = parser.parseAlternation()
            if (parser.position < source.length) {
                RegexParseResult(
                    null, source,
                    "第 ${parser.position + 1} 字符附近存在无法消费的内容（疑似未闭合的括号或多余的 `)`）",
                    parser.groupCount
                )
            } else {
                RegexParseResult(root, source, null, parser.groupCount)
            }
        } catch (e: RegexParseException) {
            RegexParseResult(null, source, e.message, e.position)
        }
    }

    private class RegexParseException(message: String, val position: Int) : Exception(message)

    private class Parser(private val source: String) {
        var position = 0
        var groupCount = 0

        fun parseAlternation(): RegexNode {
            val options = mutableListOf(parseSequence())
            while (peek() == '|') {
                position++
                options.add(parseSequence())
            }
            return if (options.size == 1) options[0] else RegexNode.Alternation(options)
        }

        private fun parseSequence(): RegexNode {
            val nodes = mutableListOf<RegexNode>()
            while (position < source.length) {
                val ch = source[position]
                if (ch == '|' || ch == ')') break
                nodes.add(parseQuantified())
            }
            return if (nodes.size == 1) nodes[0] else RegexNode.Sequence(nodes)
        }

        private fun parseQuantified(): RegexNode {
            val atom = parseAtom()
            var node = atom
            while (position < source.length) {
                val ch = source[position]
                val (min, max) = when (ch) {
                    '*' -> 0 to -1
                    '+' -> 1 to -1
                    '?' -> 0 to 1
                    '{' -> parseBraces() ?: return node
                    else -> return node
                }
                if (ch != '{') position++
                var lazy = false
                if (position < source.length && source[position] == '?') {
                    lazy = true
                    position++
                }
                node = RegexNode.Quantifier(node, min, max, lazy)
                // 同一原子上的连续量词（a**）非法 → 报错
                if (position < source.length && source[position] in "*+") {
                    throw RegexParseException("量词重复叠加（`$ch` 后又跟 `${source[position]}`）", position)
                }
            }
            return node
        }

        /** {m} {m,} {m,n} 解析；非量词形态（如 {,5} 或字面 {）返回 null。 */
        private fun parseBraces(): Pair<Int, Int>? {
            val close = source.indexOf('}', position)
            if (close < 0) return null
            val body = source.substring(position + 1, close)
            val m = Regex("""^(\d+)(?:,(\d*))?$""").find(body) ?: return null
            val min = m.groupValues[1].toInt()
            val max = when {
                m.groupValues[2].isEmpty() && body.contains(',') -> -1
                m.groupValues[2].isNotEmpty() -> m.groupValues[2].toInt()
                else -> min
            }
            position = close + 1
            return min to max
        }

        private fun parseAtom(): RegexNode {
            val ch = source[position]
            return when {
                ch == '(' -> parseGroup()
                ch == '[' -> parseCharClass()
                ch == '\\' -> parseEscape()
                ch == '.' -> {
                    position++
                    RegexNode.Dot
                }
                ch == '^' -> {
                    position++
                    RegexNode.Anchor("^")
                }
                ch == '$' -> {
                    position++
                    RegexNode.Anchor("$")
                }
                ch == '*' || ch == '+' || ch == '?' ->
                    throw RegexParseException("量词 `$ch` 缺少前置表达式", position)
                else -> {
                    position++
                    RegexNode.Literal(ch.toString())
                }
            }
        }

        private fun parseGroup(): RegexNode {
            // position 指向 '('
            position++ // consume '('
            var kind = RegexNode.GroupKind.CAPTURING
            var name: String? = null
            if (position < source.length && source[position] == '?') {
                position++
                if (position >= source.length) {
                    throw RegexParseException("组标记 `?` 后没有内容", position)
                }
                when (source[position]) {
                    ':' -> {
                        position++
                        kind = RegexNode.GroupKind.NON_CAPTURING
                    }
                    '=', '!' -> {
                        kind = if (source[position] == '=') RegexNode.GroupKind.LOOKAHEAD
                        else RegexNode.GroupKind.NEGATIVE_LOOKAHEAD
                        position++
                    }
                    '<', 'P' -> {
                        // (?<name>...) 或 (?P<name>...)
                        if (source[position] == 'P') position++
                        if (position < source.length && source[position] == '<') {
                            val nameEnd = source.indexOf('>', position)
                            if (nameEnd < 0) throw RegexParseException("命名组缺少 `>` 闭合", position)
                            name = source.substring(position + 1, nameEnd)
                            position = nameEnd + 1
                            kind = RegexNode.GroupKind.NAMED
                        } else {
                            throw RegexParseException("`?<` 后应为组名", position)
                        }
                    }
                    else -> throw RegexParseException("未知的组标记 `?${source[position]}`", position)
                }
            }
            val index = if (kind == RegexNode.GroupKind.CAPTURING || kind == RegexNode.GroupKind.NAMED) {
                groupCount + 1
            } else {
                null
            }
            if (index != null) groupCount++
            val body = parseAlternation()
            if (position >= source.length || source[position] != ')') {
                throw RegexParseException("组缺少 `)` 闭合", position)
            }
            position++ // consume ')'
            return RegexNode.Group(kind, name, index, body)
        }

        private fun parseCharClass(): RegexNode {
            val start = position
            position++ // consume '['
            var negated = false
            if (position < source.length && source[position] == '^') {
                negated = true
                position++
            }
            // 找闭合 ]（第一个 ] 若紧跟 [ 或 ^ 是字面量成员）
            while (position < source.length) {
                if (source[position] == '\\' && position + 1 < source.length) {
                    position += 2
                    continue
                }
                if (source[position] == ']' && position > start + (if (negated) 2 else 1)) break
                position++
            }
            if (position >= source.length) {
                throw RegexParseException("字符类缺少 `]` 闭合", start)
            }
            val raw = source.substring(start, position + 1)
            val sample = classSample(raw)
            position++ // consume ']'
            return RegexNode.CharClass(negated, raw, sample)
        }

        private fun parseEscape(): RegexNode {
            position++ // consume '\'
            if (position >= source.length) {
                throw RegexParseException("转义符 `\\` 悬空（末尾）", position - 1)
            }
            val ch = source[position]
            position++
            return when (ch) {
                'd', 'D', 'w', 'W', 's', 'S' -> RegexNode.Predefined(ch.toString())
                'b' -> RegexNode.Anchor("\\b")
                'B' -> RegexNode.Anchor("\\B")
                'A' -> RegexNode.Anchor("\\A")
                'z' -> RegexNode.Anchor("\\z")
                'Z' -> RegexNode.Anchor("\\Z")
                in '1'..'9' -> RegexNode.BackReference(ch - '0')
                'n' -> RegexNode.Literal("\n")
                't' -> RegexNode.Literal("\t")
                'r' -> RegexNode.Literal("\r")
                else -> RegexNode.Literal(ch.toString())
            }
        }

        private fun peek(): Char? = source.getOrNull(position)
    }

    /** 字符类的代表样本（解释与样例生成用）。 */
    private fun classSample(raw: String): String {
        val inner = raw.removePrefix("[").removePrefix("^").removeSuffix("]")
        val firstRange = Regex("""(.)-(.)""").find(inner)
        return when {
            inner == "\\d" -> "0"
            inner == "\\w" -> "a"
            inner == "\\s" -> " "
            firstRange != null -> firstRange.groupValues[1]
            inner.isNotEmpty() -> inner.last().toString()
            else -> "x"
        }
    }

    // ═══ 解释 ═══

    /**
     * 逐段解释（深度优先，片段 + 中文语义）。
     */
    fun explain(node: RegexNode): List<RegexSegmentExplanation> {
        val out = mutableListOf<RegexSegmentExplanation>()
        fun walk(current: RegexNode, depth: Int) {
            when (current) {
                is RegexNode.Literal -> out.add(
                    RegexSegmentExplanation(current.char, "字面字符「${current.char}」：精确匹配该字符")
                )
                is RegexNode.Dot -> out.add(
                    RegexSegmentExplanation(".", "任意单个字符（默认不含换行，除非 DOTALL 模式）")
                )
                is RegexNode.CharClass -> out.add(
                    RegexSegmentExplanation(
                        current.raw,
                        if (current.negated) {
                            "否定字符类：匹配 ${current.raw.removePrefix("[^").removeSuffix("]")} 之外的任意单字符"
                        } else {
                            "字符类：匹配 ${current.raw.removePrefix("[").removeSuffix("]")} 中的任意单字符"
                        }
                    )
                )
                is RegexNode.Predefined -> out.add(
                    RegexSegmentExplanation("\\${current.name}", predefinedExplanation(current.name))
                )
                is RegexNode.Anchor -> out.add(
                    RegexSegmentExplanation(current.kind, anchorExplanation(current.kind))
                )
                is RegexNode.BackReference -> out.add(
                    RegexSegmentExplanation("\\${current.index}", "反向引用：匹配第 ${current.index} 个捕获组已捕获的同样内容")
                )
                is RegexNode.Sequence -> current.nodes.forEach { walk(it, depth) }
                is RegexNode.Alternation -> {
                    out.add(
                        RegexSegmentExplanation("(", "交替分支开始：以下任一分支匹配即成立")
                    )
                    current.options.forEachIndexed { index, option ->
                        out.add(RegexSegmentExplanation(if (index == 0) "" else "|", "第 ${index + 1} 分支："))
                        walk(option, depth + 1)
                    }
                    out.add(RegexSegmentExplanation(")", "交替分支结束"))
                }
                is RegexNode.Group -> {
                    val kindText = when (current.kind) {
                        RegexNode.GroupKind.CAPTURING -> "捕获组 #${current.index}（内容可被 \\${current.index} 引用或供替换提取）"
                        RegexNode.GroupKind.NON_CAPTURING -> "非捕获组（只分组不记忆内容）"
                        RegexNode.GroupKind.NAMED -> "命名捕获组「${current.name}」（按名提取）"
                        RegexNode.GroupKind.LOOKAHEAD -> "正向前瞻（此处后面必须匹配，但不消耗字符）"
                        RegexNode.GroupKind.NEGATIVE_LOOKAHEAD -> "负向前瞻（此处后面不能匹配，不消耗字符）"
                    }
                    out.add(RegexSegmentExplanation("(", kindText))
                    walk(current.body, depth + 1)
                    out.add(RegexSegmentExplanation(")", "组结束"))
                }
                is RegexNode.Quantifier -> {
                    walk(current.body, depth)
                    val range = when {
                        current.max == -1 && current.min == 0 -> "零次或多次"
                        current.max == -1 && current.min == 1 -> "一次或多次"
                        current.max == 1 && current.min == 0 -> "零次或一次（可选）"
                        current.min == current.max -> "恰好 ${current.min} 次"
                        current.max == -1 -> "至少 ${current.min} 次（无上限）"
                        else -> "${current.min} 到 ${current.max} 次"
                    }
                    val lazy = if (current.lazy) "（惰性：尽可能少匹配）" else "（贪婪：尽可能多匹配）"
                    out.add(
                        RegexSegmentExplanation(
                            if (current.max == -1 && current.min == 0) "*$lazy"
                            else if (current.max == -1 && current.min == 1) "+$lazy"
                            else if (current.max == 1 && current.min == 0) "?$lazy"
                            else "{${current.min},${if (current.max == -1) "" else current.max}}$lazy",
                            "量词：上述内容重复 $range$lazy"
                        )
                    )
                }
            }
        }
        walk(node, 0)
        return out
    }

    private fun predefinedExplanation(name: String): String = when (name) {
        "d" -> "任意数字（等价 [0-9]）"
        "D" -> "任意非数字（等价 [^0-9]）"
        "w" -> "任意单词字符（等价 [A-Za-z0-9_]）"
        "W" -> "任意非单词字符"
        "s" -> "任意空白（空格/制表/换行等）"
        "S" -> "任意非空白"
        else -> "预定义类 \\$name"
    }

    private fun anchorExplanation(kind: String): String = when (kind) {
        "^" -> "行首锚点（MULTILINE 模式下匹配每一行的行首）"
        "$" -> "行尾锚点（换行符之前）"
        "\\b" -> "单词边界（单词字符与非单词字符的交界，零宽）"
        "\\B" -> "非单词边界"
        "\\A" -> "绝对串首（不受 MULTILINE 影响）"
        "\\z" -> "绝对串尾"
        "\\Z" -> "串尾（允许末尾换行符）"
        else -> "锚点 $kind"
    }

    // ═══ 样例生成 ═══

    /**
     * 样例生成（确定性）：匹配样本取「每交替的首分支、量词取下限、
     * 类取代表字符」；不匹配样本对首个可破坏节点取反。
     * 返回 (matchingSample, nonMatchingSample)；无法构造返回 null。
     */
    fun generateSamples(node: RegexNode): Pair<String, String>? {
        val matching = buildMatching(node) ?: return null
        val nonMatching = buildNonMatching(node) ?: (matching + "x")
        return matching to nonMatching
    }

    private fun buildMatching(node: RegexNode): String? = when (node) {
        is RegexNode.Literal -> node.char
        is RegexNode.Dot -> "a"
        is RegexNode.CharClass -> node.sample
        is RegexNode.Predefined -> when (node.name) {
            "d" -> "0"; "D" -> "a"; "w" -> "a"; "W" -> " "; "s" -> " "; "S" -> "a"; else -> "a"
        }
        is RegexNode.Anchor -> "" // 零宽
        is RegexNode.BackReference -> null
        is RegexNode.Sequence -> node.nodes.map { buildMatching(it) }.joinAll()
        is RegexNode.Alternation -> node.options.firstOrNull()?.let { buildMatching(it) }
        is RegexNode.Group -> buildMatching(node.body)
        is RegexNode.Quantifier -> buildMatching(node.body)?.let { sample ->
            sample.repeat(node.min.coerceAtMost(3))
        }
    }

    private fun List<String?>.joinAll(): String? =
        if (any { it == null }) null else joinToString("")

    private fun buildNonMatching(node: RegexNode): String? = when (node) {
        is RegexNode.Literal -> if (node.char == "a") "b" else "a"
        is RegexNode.Dot -> "\n" // 默认 DOTALL 关闭时不匹配换行
        is RegexNode.CharClass -> if (node.negated) node.sample else "✗"
        is RegexNode.Predefined -> when (node.name) {
            "d" -> "a"; "w" -> " "; "s" -> "x"; else -> "✗"
        }
        is RegexNode.Anchor -> null
        is RegexNode.BackReference -> null
        is RegexNode.Sequence -> {
            // 找第一个可破坏节点破坏之
            val firstBreakable = node.nodes.indexOfFirst { it !is RegexNode.Anchor }
            if (firstBreakable < 0) null
            else {
                val parts = node.nodes.mapIndexed { index, child ->
                    if (index == firstBreakable) buildNonMatching(child) else buildMatching(child)
                }
                parts.joinAll()
            }
        }
        is RegexNode.Alternation -> {
            // 构造一个所有分支都不匹配的样本：每分支取非匹配且拼接首分支之外的匹配前缀
            // 简化：把所有分支的非匹配样本逐个尝试，取第一个非空
            node.options.firstNotNullOfOrNull { buildNonMatching(it) }
        }
        is RegexNode.Group -> buildNonMatching(node.body)
        is RegexNode.Quantifier -> if (node.min == 0) "" else buildNonMatching(node.body)
    }

    // ═══ 风险分析 ═══

    /**
     * 回溯风险分析：嵌套量词 / 相邻无界量词 / 交替前缀重叠。
     */
    fun analyzeRisks(node: RegexNode): List<RegexRisk> {
        val risks = mutableListOf<RegexRisk>()

        fun walk(current: RegexNode) {
            when (current) {
                is RegexNode.Quantifier -> {
                    val body = current.body
                    if (current.max == -1) {
                        // 嵌套量词：量词体本身也是无界量词
                        if (body is RegexNode.Quantifier && body.max == -1) {
                            risks.add(
                                RegexRisk(
                                    RegexRiskLevel.CATASTROPHIC,
                                    "嵌套无界量词",
                                    "量词 ${describeQuantifier(current)} 作用在另一个无界量词 ${describeQuantifier(body)} 上：回溯路径随输入长度指数增长",
                                    "改占有量词（*+）或固化组，或重写为明确的单层重复"
                                )
                            )
                        }
                        // 相邻无界量词（Sequence 中相邻两个都无界且可匹配同一字符）
                        if (body is RegexNode.Group) {
                            val inner = stripGroups(body)
                            if (inner is RegexNode.Quantifier && inner.max == -1) {
                                risks.add(
                                    RegexRisk(
                                        RegexRiskLevel.CATASTROPHIC,
                                        "组内嵌套无界量词",
                                        "无界量词包住含无界量词的组：经典灾难性回溯形态",
                                        "明确上界（{1,50}）或改占有量词"
                                    )
                                )
                            }
                        }
                    }
                    walk(body)
                }
                is RegexNode.Sequence -> {
                    for (index in 0 until current.nodes.size - 1) {
                        val left = current.nodes[index]
                        val right = current.nodes[index + 1]
                        if (left is RegexNode.Quantifier && left.max == -1 &&
                            right is RegexNode.Quantifier && right.max == -1
                        ) {
                            val shareClass = canOverlap(left.body, right.body)
                            if (shareClass) {
                                risks.add(
                                    RegexRisk(
                                        RegexRiskLevel.HIGH,
                                        "相邻无界量词字符域重叠",
                                        "两个无界量词 ${describeQuantifier(left)} ${describeQuantifier(right)} 相邻且可匹配相同字符：分割方式随长度线性增长",
                                        "合并为一个量词或收紧其中一方的字符域"
                                    )
                                )
                            }
                        }
                    }
                    current.nodes.forEach { walk(it) }
                }
                is RegexNode.Alternation -> {
                    val firstStrings = current.options.map { literalPrefix(it) }
                    for (i in current.options.indices) {
                        for (j in current.options.indices) {
                            if (i >= j) continue
                            val a = firstStrings[i]
                            val b = firstStrings[j]
                            if (a != null && b != null && a.isNotEmpty() && b.isNotEmpty() &&
                                (a.startsWith(b) || b.startsWith(a))
                            ) {
                                risks.add(
                                    RegexRisk(
                                        RegexRiskLevel.MODERATE,
                                        "交替分支前缀重叠",
                                        "分支「$a」与「$b」存在前缀包含：回溯引擎会重复尝试，且短分支会截胡长分支的匹配意图",
                                        "长分支放前 / 用断言收紧 / 重构为字符类"
                                    )
                                )
                                break
                            }
                        }
                    }
                    current.options.forEach { walk(it) }
                }
                is RegexNode.Group -> walk(current.body)
                else -> Unit
            }
        }
        walk(node)
        return risks.distinctBy { it.level to it.pattern }.take(6)
    }

    private fun describeQuantifier(q: RegexNode.Quantifier): String = when {
        q.max == -1 && q.min == 0 -> "*"
        q.max == -1 && q.min == 1 -> "+"
        q.max == 1 && q.min == 0 -> "?"
        else -> "{${q.min},${if (q.max == -1) "" else q.max}}"
    }

    private fun stripGroups(node: RegexNode): RegexNode = when (node) {
        is RegexNode.Group -> stripGroups(node.body)
        else -> node
    }

    private fun canOverlap(a: RegexNode, b: RegexNode): Boolean {
        val aClass = charDomain(a)
        val bClass = charDomain(b)
        return aClass.isNotEmpty() && bClass.isNotEmpty() && aClass.any { it in bClass }
    }

    private fun charDomain(node: RegexNode): Set<Char> = when (node) {
        is RegexNode.Literal -> setOf(node.char.first())
        is RegexNode.Dot -> setOf('a', '0', ' ')
        is RegexNode.CharClass -> setOf(node.sample.first())
        is RegexNode.Predefined -> when (node.name) {
            "d" -> setOf('0'); "w" -> setOf('a', '0'); "s" -> setOf(' '); else -> setOf('x')
        }
        is RegexNode.Group -> charDomain(node.body)
        is RegexNode.Quantifier -> charDomain(node.body)
        else -> emptySet()
    }

    private fun literalPrefix(node: RegexNode): String? = when (node) {
        is RegexNode.Literal -> node.char
        is RegexNode.Sequence -> {
            val parts = node.nodes.map { literalPrefix(it) }
            if (parts.any { it == null }) null else parts.joinToString("")
        }
        is RegexNode.Group -> literalPrefix(node.body)
        else -> null
    }

    // ═══ 简化建议 ═══

    /**
     * 简化建议（确定性规则表）。
     */
    fun simplifications(source: String): List<String> {
        val suggestions = mutableListOf<String>()
        if (source.contains("[0-9]")) suggestions.add("[0-9] → \\d（更短且语义等价）")
        if (source.contains("[^0-9]")) suggestions.add("[^0-9] → \\D")
        if (source.contains("[A-Za-z0-9_]")) suggestions.add("[A-Za-z0-9_] → \\w")
        if (Regex("""\((\w)\|(\w)\)""").containsMatchIn(source)) {
            suggestions.add("单字符交替 (a|b) → [ab]（字符类比交替回溯开销低）")
        }
        if (source.contains(".*.*") || source.contains(".+.+")) {
            suggestions.add("连续的 .* / .+ 只保留一个（多余的会加剧回溯）")
        }
        if (Regex("""^[\w.+-]+@""").containsMatchIn(source).not() &&
            source.contains("@") && source.contains("+")
        ) {
            // 邮箱类正则的宽松提醒
            suggestions.add("若为邮箱校验：全量 RFC 校验不现实，建议明确业务口径（长度/域名规则）并加边界锚点")
        }
        if (!source.startsWith("^") && !source.endsWith("$") && source.length > 8) {
            suggestions.add("无首尾锚点：find 语义下会匹配子串；若要全串校验补 ^…$")
        }
        return suggestions
    }
}
