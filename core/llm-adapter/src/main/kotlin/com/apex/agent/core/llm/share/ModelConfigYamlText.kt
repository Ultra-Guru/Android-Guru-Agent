package com.apex.agent.core.llm.share

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// ═══════════════════════════ YAML ═══════════════════════════

/**
 * YAML 子集解析器（缩进块结构 + 简单 flow）。
 *
 * 支持：嵌套 map、`- item` 序列（含 `- key: value` 内联 map 起始）、单双引号
 * 标量、注释、空行、类型化裸标量（true/false/yes/no/null/~/数字）、简单
 * inline flow `{...}` / `[...]`。
 *
 * 明确不支持（抛带行号错误，绝不静默丢数据）：锚点 & 别名、多行块标量
 * `|` / `>`、`---` 多文档（首尾分隔行忽略）。
 */
internal object YamlTextParser {

    private class YLine(val indent: Int, val content: String, val number: Int)

    fun parse(src: String): JsonElement {
        val lines = prepare(src)
        if (lines.isEmpty()) {
            throw ModelConfigTextException("yaml input is empty (only blank or comment lines)")
        }
        val (v, next) = parseBlock(lines, 0, lines[0].indent)
        if (next < lines.size) {
            throw ModelConfigTextException(
                "yaml error at line ${lines[next].number}: unexpected content (check indentation)"
            )
        }
        return v
    }

    private fun prepare(src: String): MutableList<YLine> {
        val out = mutableListOf<YLine>()
        src.lines().forEachIndexed { idx, raw ->
            val number = idx + 1
            val noComment = stripComment(raw)
            if (noComment.isBlank()) return@forEachIndexed
            var indent = 0
            while (indent < noComment.length && noComment[indent] == ' ') indent++
            if (indent < noComment.length && noComment[indent] == '\t') {
                throw ModelConfigTextException(
                    "yaml error at line $number: tab characters are not allowed in indentation"
                )
            }
            val content = noComment.substring(indent).trimEnd()
            if (content.isEmpty()) return@forEachIndexed
            if (content == "---" || content == "...") return@forEachIndexed
            out.add(YLine(indent, content, number))
        }
        return out
    }

    /** 行内注释剥离（引号内的 # 不算注释）。 */
    private fun stripComment(raw: String): String {
        var quote: Char? = null
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (quote != null) {
                if (c == quote) quote = null
            } else if (c == '\'' || c == '"') {
                quote = c
            } else if (c == '#' && (i == 0 || raw[i - 1] == ' ' || raw[i - 1] == '\t')) {
                return raw.substring(0, i)
            }
            i++
        }
        return raw
    }

    /** 解析一个块（map 或 seq），返回 (值, 下一行下标)。 */
    private fun parseBlock(lines: MutableList<YLine>, idx: Int, indent: Int): Pair<JsonElement, Int> {
        val first = lines[idx]
        return if (first.content == "-" || first.content.startsWith("- ")) {
            parseSeq(lines, idx, indent)
        } else {
            parseMap(lines, idx, indent)
        }
    }

    private fun parseSeq(lines: MutableList<YLine>, idx: Int, indent: Int): Pair<JsonElement, Int> {
        val items = mutableListOf<JsonElement>()
        var i = idx
        while (i < lines.size) {
            val l = lines[i]
            if (l.indent != indent) break
            if (l.content != "-" && !l.content.startsWith("- ")) break
            val after = l.content.substring(1)
            val rest = after.trimStart()
            when {
                rest.isEmpty() -> {
                    val child = childIndent(lines, i + 1, indent)
                    if (child == null) {
                        items.add(JsonNull); i++
                    } else {
                        val (v, ni) = parseBlock(lines, i + 1, child)
                        items.add(v); i = ni
                    }
                }
                rest.startsWith("- ") || rest == "-" -> throw ModelConfigTextException(
                    "yaml error at line ${l.number}: nested sequence items (- - x) are not supported"
                )
                findKeyColon(rest) >= 0 -> {
                    // `- key: value`：把本行改写成 (key 起始列, rest) 后按映射继续，
                    // 后续同缩进行自然并入该映射
                    val newIndent = l.indent + 1 + (after.length - rest.length)
                    lines[i] = YLine(newIndent, rest, l.number)
                    val (v, ni) = parseMap(lines, i, newIndent)
                    items.add(v); i = ni
                }
                else -> {
                    items.add(scalarOrFlow(rest, l.number)); i++
                }
            }
        }
        return JsonArray(items) to i
    }

    private fun parseMap(lines: MutableList<YLine>, idx: Int, indent: Int): Pair<JsonElement, Int> {
        val map = LinkedHashMap<String, JsonElement>()
        var i = idx
        while (i < lines.size) {
            val l = lines[i]
            if (l.indent != indent) break
            val colon = findKeyColon(l.content)
            if (colon < 0) {
                throw ModelConfigTextException(
                    "yaml error at line ${l.number}: expected \"key: value\" or \"key:\""
                )
            }
            val rawKey = l.content.substring(0, colon).trim()
            val rest = l.content.substring(colon + 1).trim()
            val key = unquoteKey(rawKey, l.number)
            if (rest.isEmpty()) {
                val child = childIndent(lines, i + 1, indent)
                if (child == null) {
                    map[key] = JsonNull; i++
                } else {
                    val (v, ni) = parseBlock(lines, i + 1, child)
                    map[key] = v; i = ni
                }
            } else if (BLOCK_SCALAR.matches(rest)) {
                throw ModelConfigTextException(
                    "yaml error at line ${l.number}: block scalars (| or >) are not supported — inline the value instead"
                )
            } else {
                map[key] = scalarOrFlow(rest, l.number); i++
            }
        }
        return JsonObject(map) to i
    }

    private val BLOCK_SCALAR = Regex("^[|>][+-]?\\d*$")

    /** 下一行是否存在更深缩进（子块）；无则 null。 */
    private fun childIndent(lines: List<YLine>, idx: Int, parentIndent: Int): Int? =
        if (idx < lines.size && lines[idx].indent > parentIndent) lines[idx].indent else null

    /**
     * 键值分隔冒号的位置：必须后跟空格或行尾（YAML 规则）—— 这样
     * `url: https://x.com` 里 https 后的冒号不会被误判。
     */
    private fun findKeyColon(s: String): Int {
        var quote: Char? = null
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (quote != null) {
                if (c == quote) quote = null
            } else when {
                c == '\'' || c == '"' -> quote = c
                c == ':' && (i + 1 >= s.length || s[i + 1] == ' ') -> return i
            }
            i++
        }
        return -1
    }

    private fun unquoteKey(raw: String, lineNo: Int): String {
        if (raw.isEmpty()) return raw
        if (raw.startsWith("\"") || raw.startsWith("'")) {
            val (s, next) = scanQuoted(raw, 0, lineNo)
            if (next < raw.length) {
                throw ModelConfigTextException("yaml error at line $lineNo: unexpected content after quoted key")
            }
            return s
        }
        return raw
    }

    /** 标量或简单 flow 值。 */
    private fun scalarOrFlow(rest: String, lineNo: Int): JsonElement {
        if (rest.startsWith("{") || rest.startsWith("[")) {
            return YamlFlowParser(rest, lineNo).parseWhole()
        }
        if (rest.startsWith("\"") || rest.startsWith("'")) {
            val (s, next) = scanQuoted(rest, 0, lineNo)
            if (next < rest.length) {
                throw ModelConfigTextException(
                    "yaml error at line $lineNo: unexpected content after quoted scalar"
                )
            }
            return JsonPrimitive(s)
        }
        return ModelConfigText.typedScalar(rest)
    }

    /** 从 [start] 扫描带引号字符串，返回 (内容, 结束下标)。 */
    private fun scanQuoted(s: String, start: Int, lineNo: Int): Pair<String, Int> {
        val quote = s[start]
        val sb = StringBuilder()
        var i = start + 1
        while (i < s.length) {
            val c = s[i]
            when {
                quote == '\'' && c == '\'' && i + 1 < s.length && s[i + 1] == '\'' -> {
                    sb.append('\''); i += 2 // YAML 单引号转义：'' → '
                }
                quote == '"' && c == '\\' && i + 1 < s.length -> {
                    sb.append(when (val e = s[i + 1]) {
                        'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'
                        '"' -> '"'; '\\' -> '\\'
                        else -> e
                    })
                    i += 2
                }
                c == quote -> return sb.toString() to (i + 1)
                else -> { sb.append(c); i++ }
            }
        }
        throw ModelConfigTextException("yaml error at line $lineNo: unterminated quoted string")
    }

    /** 简单 flow 解析（`{a: 1, b: [x, y]}` 一行内）。 */
    private class YamlFlowParser(private val s: String, private val lineNo: Int) {
        private var i = 0

        fun parseWhole(): JsonElement {
            val v = value()
            skipWs()
            if (i < s.length) fail("unexpected \"${s[i]}\" after flow collection")
            return v
        }

        private fun value(): JsonElement {
            skipWs()
            if (i >= s.length) fail("unexpected end of flow collection")
            return when {
                s[i] == '{' -> map()
                s[i] == '[' -> list()
                s[i] == '\'' || s[i] == '"' -> {
                    val q = scanQuotedPublic()
                    JsonPrimitive(q)
                }
                else -> ModelConfigText.typedScalar(rawUntil { c -> c == ',' || c == '}' || c == ']' })
            }
        }

        private fun map(): JsonElement {
            i++
            val m = LinkedHashMap<String, JsonElement>()
            skipWs()
            if (i < s.length && s[i] == '}') { i++; return JsonObject(m) }
            while (true) {
                skipWs()
                if (i >= s.length) fail("unterminated flow map")
                val key = if (s[i] == '\'' || s[i] == '"') scanQuotedPublic() else rawUntil { c -> c == ':' }
                skipWs()
                if (i >= s.length || s[i] != ':') fail("expected \":\" in flow map")
                i++
                m[key] = value()
                skipWs()
                if (i >= s.length) fail("unterminated flow map")
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return JsonObject(m) }
                    else -> fail("expected \",\" or \"}\" in flow map")
                }
            }
        }

        private fun list(): JsonElement {
            i++
            val l = mutableListOf<JsonElement>()
            skipWs()
            if (i < s.length && s[i] == ']') { i++; return JsonArray(l) }
            while (true) {
                l.add(value())
                skipWs()
                if (i >= s.length) fail("unterminated flow list")
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return JsonArray(l) }
                    else -> fail("expected \",\" or \"]\" in flow list")
                }
            }
        }

        private fun scanQuotedPublic(): String {
            val sb = StringBuilder()
            val quote = s[i]
            i++
            while (i < s.length) {
                val c = s[i]
                when {
                    quote == '\'' && c == '\'' && i + 1 < s.length && s[i + 1] == '\'' -> {
                        sb.append('\''); i += 2
                    }
                    quote == '"' && c == '\\' && i + 1 < s.length -> {
                        sb.append(when (val e = s[i + 1]) {
                            'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'
                            '"' -> '"'; '\\' -> '\\'
                            else -> e
                        })
                        i += 2
                    }
                    c == quote -> { i++; return sb.toString() }
                    else -> { sb.append(c); i++ }
                }
            }
            fail("unterminated quoted string in flow collection")
        }

        private fun rawUntil(stop: (Char) -> Boolean): String {
            val start = i
            while (i < s.length && !stop(s[i])) i++
            return s.substring(start, i).trim()
        }

        private fun skipWs() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t')) i++
        }

        private fun fail(msg: String): Nothing =
            throw ModelConfigTextException("yaml flow error at line $lineNo: $msg")
    }
}

