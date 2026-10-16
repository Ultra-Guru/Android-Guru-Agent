package com.apex.agent.core.llm.share

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// ═══════════════════════════ Python ═══════════════════════════

/** Python 词法 token（类型 + 原文 + 行号；行号供错误消息）。 */
private class PyTok(val type: Int, val text: String, val line: Int) {
    companion object {
        const val LBRACE = 1
        const val RBRACE = 2
        const val LBRACKET = 3
        const val RBRACKET = 4
        const val LPAREN = 5
        const val RPAREN = 6
        const val COLON = 7
        const val COMMA = 8
        const val EQUALS = 9
        const val STRING = 10
        const val NUMBER = 11
        const val IDENT = 12
        const val NEWLINE = 13
    }
}

/**
 * Python 子集解析器。
 *
 * 两条路径（先字面量后代码，见 [parse]）：
 * 1. **字面量**：dict / list / 字符串（单双引号 + r"" 原始串）/ 数字 /
 *    True / False / None / 裸标识符（键和值都按字符串兜底）/ 尾逗号 / # 注释；
 * 2. **代码形状**：字面量失败（文本里有 import、赋值、调用）→ 解析顶层
 *    `name = <字面量或调用>` 赋值语句与 `Ident(kwargs)` 调用，识别不了的行
 *    （import / from / 装饰器等）整行跳过 —— 用户粘贴的是能跑的代码，配置
 *    语义就藏在赋值和构造调用里。
 */
internal object PythonTextParser {

    fun parse(src: String): JsonElement {
        val toks = PyLexer(src).tokenize()
        val literal = try {
            PyLiteralParser(toks).parseDocument()
        } catch (e: ModelConfigTextException) {
            null
        }
        if (literal != null) return literal
        return PyCodeParser(toks).parse()
    }

    /** 扫描带引号字符串（支持单双引号、常见转义；raw 时不处理转义）。 */
    private fun scanString(src: String, start: Int, raw: Boolean, lineAtStart: Int): Pair<PyTok, Int> {
        var i = start
        if (raw) i++ // 吃掉 r/R 前缀
        val quote = src[i]
        i++
        val sb = StringBuilder()
        while (i < src.length) {
            val ch = src[i]
            when {
                ch == '\\' && !raw && i + 1 < src.length -> {
                    sb.append(when (val e = src[i + 1]) {
                        'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'
                        '\\' -> '\\'; '\'' -> '\''; '"' -> '"'
                        else -> e
                    })
                    i += 2
                }
                ch == quote -> return PyTok(PyTok.STRING, sb.toString(), lineAtStart) to (i + 1)
                ch == '\n' -> throw ModelConfigTextException(
                    "unterminated string at line $lineAtStart (multi-line strings are not supported)"
                )
                else -> { sb.append(ch); i++ }
            }
        }
        throw ModelConfigTextException("unterminated string at line $lineAtStart")
    }

    private class PyLexer(private val src: String) {
        val tokens = ArrayList<PyTok>()
        private var i = 0
        private var line = 1

        fun tokenize(): List<PyTok> {
            while (i < src.length) {
                val c = src[i]
                when {
                    c == '\n' -> { tokens.add(PyTok(PyTok.NEWLINE, "\n", line)); line++; i++ }
                    c == '\r' || c == ' ' || c == '\t' -> i++
                    c == '#' -> while (i < src.length && src[i] != '\n') i++
                    c == '{' -> { add(PyTok.LBRACE, "{"); i++ }
                    c == '}' -> { add(PyTok.RBRACE, "}"); i++ }
                    c == '[' -> { add(PyTok.LBRACKET, "["); i++ }
                    c == ']' -> { add(PyTok.RBRACKET, "]"); i++ }
                    c == '(' -> { add(PyTok.LPAREN, "("); i++ }
                    c == ')' -> { add(PyTok.RPAREN, ")"); i++ }
                    c == ':' -> { add(PyTok.COLON, ":"); i++ }
                    c == ',' -> { add(PyTok.COMMA, ","); i++ }
                    c == '=' -> { add(PyTok.EQUALS, "="); i++ }
                    c == '"' || c == '\'' -> {
                        val (tok, next) = scanString(src, i, raw = false, line)
                        tokens.add(tok); i = next
                    }
                    (c == 'r' || c == 'R') && i + 1 < src.length &&
                        (src[i + 1] == '"' || src[i + 1] == '\'') -> {
                        val (tok, next) = scanString(src, i, raw = true, line)
                        tokens.add(tok); i = next
                    }
                    c.isDigit() || ((c == '-' || c == '+') && i + 1 < src.length && src[i + 1].isDigit()) -> {
                        val start = i
                        if (src[i] == '-' || src[i] == '+') i++
                        while (i < src.length && src[i].isDigit()) i++
                        if (i < src.length && src[i] == '.') {
                            i++
                            while (i < src.length && src[i].isDigit()) i++
                        }
                        if (i < src.length && (src[i] == 'e' || src[i] == 'E')) {
                            i++
                            if (i < src.length && (src[i] == '+' || src[i] == '-')) i++
                            while (i < src.length && src[i].isDigit()) i++
                        }
                        tokens.add(PyTok(PyTok.NUMBER, src.substring(start, i), line))
                    }
                    c.isLetter() || c == '_' -> {
                        val start = i
                        while (i < src.length &&
                            (src[i].isLetterOrDigit() || src[i] == '_' || src[i] == '.')
                        ) i++
                        tokens.add(PyTok(PyTok.IDENT, src.substring(start, i), line))
                    }
                    else -> throw ModelConfigTextException(
                        "unexpected character \"$c\" at line $line"
                    )
                }
            }
            tokens.add(PyTok(PyTok.NEWLINE, "\n", line))
            return tokens
        }

        private fun add(type: Int, s: String) {
            tokens.add(PyTok(type, s, line))
        }
    }

    /** Python 字面量递归下降解析（忽略换行 token —— 括号内可自由换行）。 */
    private class PyLiteralParser(private val toks: List<PyTok>, private val start: Int = 0) {
        private var p = start

        fun index(): Int = p

        fun parseDocument(): JsonElement {
            val v = parseValue()
            skipNl()
            if (p < toks.size) fail("unexpected trailing token \"${toks[p].text}\"", toks[p])
            return v
        }

        fun parseValue(): JsonElement {
            skipNl()
            val t = peek() ?: throw ModelConfigTextException("unexpected end of input")
            return when (t.type) {
                PyTok.LBRACE -> parseCollection(isDict = true)
                PyTok.LBRACKET -> parseCollection(isDict = false)
                PyTok.STRING -> { p++; JsonPrimitive(t.text) }
                PyTok.NUMBER -> { p++; ModelConfigText.numberJson(t.text) }
                PyTok.IDENT -> when (t.text) {
                    "True" -> { p++; JsonPrimitive(true) }
                    "False" -> { p++; JsonPrimitive(false) }
                    "None" -> { p++; JsonNull }
                    else -> { p++; JsonPrimitive(t.text) }
                }
                else -> fail("unexpected token \"${t.text}\"", t)
            }
        }

        /** dict / list 共用骨架（开闭符与分隔符不同而已）。 */
        private fun parseCollection(isDict: Boolean): JsonElement {
            p++ // 开符
            val open = if (isDict) "{" else "["
            val close = if (isDict) "}" else "]"
            val map = LinkedHashMap<String, JsonElement>()
            val list = mutableListOf<JsonElement>()
            skipNl()
            var t = peek() ?: throw ModelConfigTextException("unterminated $open")
            if (t.type == (if (isDict) PyTok.RBRACE else PyTok.RBRACKET)) {
                p++; return finish(map, list, isDict)
            }
            while (true) {
                if (isDict) {
                    skipNl()
                    t = peek() ?: throw ModelConfigTextException("unterminated $open")
                    val key = when (t.type) {
                        PyTok.STRING, PyTok.NUMBER, PyTok.IDENT -> { p++; t.text }
                        else -> fail("invalid dict key \"${t.text}\"", t)
                    }
                    skipNl()
                    t = peek() ?: throw ModelConfigTextException("missing \":\" after key \"$key\"")
                    if (t.type != PyTok.COLON) fail("expected \":\" after key \"$key\"", t)
                    p++
                    map[key] = parseValue()
                } else {
                    list.add(parseValue())
                }
                skipNl()
                t = peek() ?: throw ModelConfigTextException("unterminated $open")
                when (t.type) {
                    PyTok.COMMA -> {
                        p++
                        skipNl()
                        val nxt = peek()
                        // 尾逗号：`{...,}` / `[...,]` 合法收尾
                        if (nxt != null && nxt.type == (if (isDict) PyTok.RBRACE else PyTok.RBRACKET)) {
                            p++; return finish(map, list, isDict)
                        }
                    }
                    if (isDict) PyTok.RBRACE else PyTok.RBRACKET -> {
                        p++; return finish(map, list, isDict)
                    }
                    else -> fail("expected \",\" or \"$close\"", t)
                }
            }
        }

        private fun finish(
            map: LinkedHashMap<String, JsonElement>,
            list: MutableList<JsonElement>,
            isDict: Boolean
        ): JsonElement = if (isDict) JsonObject(map) else JsonArray(list)

        private fun skipNl() {
            while (p < toks.size && toks[p].type == PyTok.NEWLINE) p++
        }

        private fun peek(): PyTok? = if (p < toks.size) toks[p] else null

        private fun fail(msg: String, tok: PyTok?): Nothing =
            throw ModelConfigTextException(
                if (tok != null) "python literal error at line ${tok.line}: $msg" else "python literal error: $msg"
            )
    }

    /**
     * Python 代码形状解析：顶层语句序列。
     *
     * - `name = <字面量>` → 赋值；值是 `Ident(...)` 调用时展开成 kwargs 对象；
     * - `Ident(kwargs...)` 独立调用 → 记录 (被调名, kwargs)；
     * - 其余语句（import / from / 装饰器…）整行跳过 —— 用户贴的是真实代码。
     *
     * 组装优先级：唯一对象赋值直接取其对象（"take the dict"）；多赋值合成
     * `{变量名: 值}` 外层对象（导入器会进 config / client / settings 这类
     * 容器键里找）；没有赋值时挑调用（优先 /openai|client|llm|model|config/i）
     * 返回其 kwargs。
     */
    private class PyCodeParser(private val toks: List<PyTok>) {
        private var p = 0

        fun parse(): JsonElement {
            val assignments = mutableListOf<Pair<String, JsonElement>>()
            val calls = mutableListOf<Pair<String, JsonObject>>()
            skipNl()
            while (p < toks.size) {
                val t = toks[p]
                if (t.type == PyTok.NEWLINE) { p++; continue }
                if (t.type == PyTok.IDENT && p + 1 < toks.size && toks[p + 1].type == PyTok.EQUALS) {
                    val name = t.text
                    p += 2
                    val value = parseStatementValue()
                    if (value != null) assignments.add(name to value) else skipToEol()
                } else if (t.type == PyTok.IDENT && p + 1 < toks.size && toks[p + 1].type == PyTok.LPAREN) {
                    val call = parseCall()
                    if (call != null) calls.add(call) else skipToEol()
                } else {
                    skipToEol()
                }
                skipNl()
            }
            if (assignments.size == 1 && assignments[0].second is JsonObject) {
                return assignments[0].second
            }
            if (assignments.isNotEmpty()) {
                val m = LinkedHashMap<String, JsonElement>()
                assignments.forEach { (k, v) -> m[k] = v }
                return JsonObject(m)
            }
            val preferred = Regex("(?i)openai|client|llm|model|config")
            val best = calls.firstOrNull { it.first.contains(preferred) }
                ?: calls.firstOrNull { it.second.isNotEmpty() }
            if (best != null) return best.second
            throw ModelConfigTextException(
                "no recognizable python structure (dict literal, assignment or call) found"
            )
        }

        /** 语句右值：调用 → kwargs 对象；否则字面量（失败返回 null，上层跳行）。 */
        private fun parseStatementValue(): JsonElement? {
            val t = toks.getOrNull(p) ?: return null
            if (t.type == PyTok.IDENT && toks.getOrNull(p + 1)?.type == PyTok.LPAREN) {
                return parseCall()?.second
            }
            return try {
                val lp = PyLiteralParser(toks, p)
                val v = lp.parseValue()
                p = lp.index()
                v
            } catch (e: ModelConfigTextException) {
                // f-string / 复杂表达式等不支持的字面量：整行放弃（容忍真实代码）
                null
            }
        }

        /** 解析 `Ident(...)`：kwargs 收集 + 位置参数容忍跳过；括号未闭合 → null。 */
        private fun parseCall(): Pair<String, JsonObject>? {
            val callee = toks[p].text
            p += 2 // IDENT + (
            val kwargs = LinkedHashMap<String, JsonElement>()
            skipNl()
            while (p < toks.size) {
                val t = toks[p]
                if (t.type == PyTok.NEWLINE) { p++; continue }
                if (t.type == PyTok.RPAREN) { p++; return callee to JsonObject(kwargs) }
                if (t.type == PyTok.COMMA) { p++; skipNl(); continue }
                if (t.type == PyTok.IDENT && toks.getOrNull(p + 1)?.type == PyTok.EQUALS) {
                    val name = t.text
                    p += 2
                    skipNl()
                    try {
                        val lp = PyLiteralParser(toks, p)
                        val v = lp.parseValue()
                        p = lp.index()
                        kwargs[name] = v
                    } catch (e: ModelConfigTextException) {
                        // 复杂实参（f-string / 嵌套调用）：丢弃该实参，跳到边界
                        skipArgBoundary()
                    }
                } else {
                    try {
                        val lp = PyLiteralParser(toks, p)
                        lp.parseValue()
                        p = lp.index()
                    } catch (e: ModelConfigTextException) {
                        skipArgBoundary()
                    }
                }
                skipNl()
            }
            return null
        }

        /** 跳到本层 `,` 或 `)`（嵌套括号整段跨过）。 */
        private fun skipArgBoundary() {
            var depth = 0
            while (p < toks.size) {
                val t = toks[p]
                when (t.type) {
                    PyTok.LPAREN, PyTok.LBRACE, PyTok.LBRACKET -> depth++
                    PyTok.RPAREN -> if (depth == 0) return else depth--
                    PyTok.RBRACE, PyTok.RBRACKET -> if (depth > 0) depth--
                    PyTok.COMMA -> if (depth == 0) { p++; return }
                    PyTok.NEWLINE -> if (depth == 0) return
                }
                p++
            }
        }

        private fun skipToEol() {
            while (p < toks.size && toks[p].type != PyTok.NEWLINE) p++
        }

        private fun skipNl() {
            while (p < toks.size && toks[p].type == PyTok.NEWLINE) p++
        }
    }
}

