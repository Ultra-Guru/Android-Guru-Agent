package com.apex.agent.core.llm.share

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// ═══════════════════════════ TOML ═══════════════════════════

/**
 * TOML 子集解析器。
 *
 * 支持：`[table]` / `[a.b]` 点段表头（含引号段）、`[[array]]` 表数组（模型
 * 清单的常见形态）、`key = value`（裸 / 引号 / 点段键）、基本字符串（转义）
 * 与字面字符串 '...'、整数（含 _ 分隔）、浮点、布尔、多行数组、inline
 * 表 `{ k = v }`、`#` 注释。日期及一切非标准裸值按字符串透传（模型配置里
 * 日期不是数值语义）。
 *
 * 不支持：多行字符串三引号（抛明确错误）、锚点语义（TOML 无此概念）。
 * 内部用可变 Map / List 建树（表头会回头改父表），收尾统一转 JsonElement。
 */
internal object TomlTextParser {

    fun parse(src: String): JsonElement {
        val rawLines = src.lines()
        val root = LinkedHashMap<String, Any>()
        var current: LinkedHashMap<String, Any> = root
        var i = 0
        while (i < rawLines.size) {
            val number = i + 1
            val noComment = stripComment(rawLines[i]).trim()
            i++
            if (noComment.isEmpty()) continue
            if (noComment.startsWith("[[")) {
                if (!noComment.endsWith("]]") || noComment.length <= 4) {
                    throw ModelConfigTextException("toml error at line $number: malformed array-of-tables header")
                }
                val path = splitDotted(noComment.substring(2, noComment.length - 2), number)
                current = arrayTable(root, path, number)
            } else if (noComment.startsWith("[")) {
                if (!noComment.endsWith("]") || noComment.length <= 2) {
                    throw ModelConfigTextException("toml error at line $number: malformed table header")
                }
                val path = splitDotted(noComment.substring(1, noComment.length - 1), number)
                current = navigate(root, path, number)
            } else {
                val eq = findEq(noComment)
                if (eq < 0) {
                    throw ModelConfigTextException("toml error at line $number: expected \"key = value\" or [table]")
                }
                val keyPath = splitDotted(noComment.substring(0, eq).trim(), number)
                var valuePart = noComment.substring(eq + 1).trim()
                // 多行数组：括号不平衡（引号外计数）时继续吃后续行
                while (netBrackets(valuePart) > 0 && i < rawLines.size) {
                    valuePart += " " + stripComment(rawLines[i]).trim()
                    i++
                }
                if (netBrackets(valuePart) != 0) {
                    throw ModelConfigTextException("toml error at line $number: unbalanced brackets in value")
                }
                val value = parseValue(valuePart, number)
                insert(current, keyPath, value, number)
            }
        }
        return finalize(root)
    }

    /** 引号外的 `[`/`]` 净计数（多行数组探测）。 */
    private fun netBrackets(s: String): Int {
        var quote: Char? = null
        var depth = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (quote != null) {
                if (c == quote) quote = null
            } else when (c) {
                '\'' , '"' -> quote = c
                '[' -> depth++
                ']' -> depth--
            }
            i++
        }
        return depth
    }

    private fun stripComment(raw: String): String {
        var quote: Char? = null
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (quote != null) {
                if (c == quote) quote = null
            } else when (c) {
                '\'', '"' -> quote = c
                '#' -> return raw.substring(0, i)
            }
            i++
        }
        return raw
    }

    /** 引号外第一个 `=`（表头行已在调用前分流）。 */
    private fun findEq(s: String): Int {
        var quote: Char? = null
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (quote != null) {
                if (c == quote) quote = null
            } else when (c) {
                '\'', '"' -> quote = c
                '=' -> return i
            }
            i++
        }
        return -1
    }

    /** 点段路径（`a."b.c".d` → [a, b.c, d]）。 */
    private fun splitDotted(s: String, lineNo: Int): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < s.length) {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t')) i++
            if (i >= s.length) break
            if (s[i] == '"' || s[i] == '\'') {
                val sb = StringBuilder()
                val quote = s[i]
                i++
                while (i < s.length && s[i] != quote) { sb.append(s[i]); i++ }
                if (i >= s.length) {
                    throw ModelConfigTextException("toml error at line $lineNo: unterminated quoted key")
                }
                i++
                out.add(sb.toString())
            } else {
                val start = i
                while (i < s.length && s[i] != '.') i++
                val seg = s.substring(start, i).trim()
                if (seg.isEmpty()) {
                    throw ModelConfigTextException("toml error at line $lineNo: empty key segment")
                }
                out.add(seg)
            }
            while (i < s.length && (s[i] == ' ' || s[i] == '\t')) i++
            if (i < s.length) {
                if (s[i] != '.') {
                    throw ModelConfigTextException("toml error at line $lineNo: unexpected character in key")
                }
                i++
            }
        }
        if (out.isEmpty()) throw ModelConfigTextException("toml error at line $lineNo: empty key")
        return out
    }

    /** 导航到 path 指向的表（缺则建；类型冲突则重建）。 */
    private fun navigate(
        root: LinkedHashMap<String, Any>,
        path: List<String>,
        lineNo: Int
    ): LinkedHashMap<String, Any> {
        var cur: LinkedHashMap<String, Any> = root
        for (seg in path) {
            val next = cur[seg]
            cur = when (next) {
                is LinkedHashMap<*, *> -> @Suppress("UNCHECKED_CAST") (next as LinkedHashMap<String, Any>)
                else -> {
                    // 键被标量占位 / 表头重复定义：以表语义重建（后者覆盖前者）
                    val m = LinkedHashMap<String, Any>()
                    cur[seg] = m
                    m
                }
            }
        }
        return cur
    }

    /** `[[x]]`：取 / 建数组，追加空表并返回之。 */
    private fun arrayTable(
        root: LinkedHashMap<String, Any>,
        path: List<String>,
        lineNo: Int
    ): LinkedHashMap<String, Any> {
        val parent = navigate(root, path.dropLast(1), lineNo)
        val last = path.last()
        val arr = when (val existing = parent[last]) {
            is ArrayList<*> -> @Suppress("UNCHECKED_CAST") (existing as ArrayList<Any>)
            // 同键先前落过数组值（JsonArray 已 finalize 的场景不存在，防御 List 即可）
            is List<*> -> ArrayList<Any>(existing.filterIsInstance<Any>())
            else -> ArrayList<Any>()
        }
        val table = LinkedHashMap<String, Any>()
        arr.add(table)
        parent[last] = arr
        return table
    }

    private fun insert(
        table: LinkedHashMap<String, Any>,
        path: List<String>,
        value: JsonElement,
        lineNo: Int
    ) {
        var cur = table
        for (seg in path.dropLast(1)) {
            cur = when (val next = cur[seg]) {
                is LinkedHashMap<*, *> -> @Suppress("UNCHECKED_CAST") (next as LinkedHashMap<String, Any>)
                else -> {
                    val m = LinkedHashMap<String, Any>()
                    cur[seg] = m
                    m
                }
            }
        }
        cur[path.last()] = value
    }

    private fun parseValue(s: String, lineNo: Int): JsonElement {
        if (s.isEmpty()) throw ModelConfigTextException("toml error at line $lineNo: missing value")
        return when {
            s.startsWith("\"\"\"") -> throw ModelConfigTextException(
                "toml error at line $lineNo: multi-line strings are not supported"
            )
            s.startsWith("\"") -> JsonPrimitive(basicString(s, lineNo))
            s.startsWith("'") -> JsonPrimitive(literalString(s, lineNo))
            s.startsWith("[") -> parseArray(s, lineNo)
            s.startsWith("{") -> parseInlineTable(s, lineNo)
            s == "true" -> JsonPrimitive(true)
            s == "false" -> JsonPrimitive(false)
            else -> parseBare(s, lineNo)
        }
    }

    /** 基本字符串：`"..."` + 转义（返回内容与消费长度由调用方保证单值）。 */
    private fun basicString(s: String, lineNo: Int): String {
        val sb = StringBuilder()
        var i = 1
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val e = s[i + 1]) {
                    'n' -> sb.append('\n'); 't' -> sb.append('\t')
                    'r' -> sb.append('\r'); 'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                    '"' -> sb.append('"'); '\\' -> sb.append('\\')
                    'u' -> {
                        if (i + 6 > s.length) {
                            throw ModelConfigTextException("toml error at line $lineNo: bad \\u escape")
                        }
                        val hex = s.substring(i + 2, i + 6)
                        sb.append(hex.toIntOrNull(16)?.toChar() ?: ' ')
                        i += 4
                    }
                    else -> sb.append(e)
                }
                i += 2
            } else if (c == '"') {
                val rest = s.substring(i + 1).trim()
                if (rest.isNotEmpty()) {
                    throw ModelConfigTextException(
                        "toml error at line $lineNo: unexpected content after closing quote"
                    )
                }
                return sb.toString()
            } else {
                sb.append(c); i++
            }
        }
        throw ModelConfigTextException("toml error at line $lineNo: unterminated basic string")
    }

    private fun literalString(s: String, lineNo: Int): String {
        val end = s.indexOf('\'', 1)
        if (end < 0) throw ModelConfigTextException("toml error at line $lineNo: unterminated literal string")
        if (s.substring(end + 1).trim().isNotEmpty()) {
            throw ModelConfigTextException(
                "toml error at line $lineNo: unexpected content after closing quote"
            )
        }
        return s.substring(1, end)
    }

    /** 数组：顶层逗号切分（引号 / 嵌套括号感知）后逐项递归。 */
    private fun parseArray(s: String, lineNo: Int): JsonElement {
        if (!s.endsWith("]")) {
            throw ModelConfigTextException("toml error at line $lineNo: unterminated array")
        }
        val body = s.substring(1, s.length - 1).trim()
        val items = mutableListOf<JsonElement>()
        if (body.isEmpty()) return JsonArray(items)
        for (piece in splitTopLevel(body)) {
            val t = piece.trim()
            if (t.isNotEmpty()) items.add(parseValue(t, lineNo))
        }
        return JsonArray(items)
    }

    /** inline 表：`{ k = v, ... }`。 */
    private fun parseInlineTable(s: String, lineNo: Int): JsonElement {
        if (!s.endsWith("}")) {
            throw ModelConfigTextException("toml error at line $lineNo: unterminated inline table")
        }
        val body = s.substring(1, s.length - 1).trim()
        val map = LinkedHashMap<String, JsonElement>()
        if (body.isEmpty()) return JsonObject(map)
        for (piece in splitTopLevel(body)) {
            val t = piece.trim()
            val eq = findEq(t)
            if (eq < 0) {
                throw ModelConfigTextException("toml error at line $lineNo: expected \"k = v\" in inline table")
            }
            val keyPath = splitDotted(t.substring(0, eq).trim(), lineNo)
            val value = parseValue(t.substring(eq + 1).trim(), lineNo)
            // inline 表里的点段键：按嵌套落位（同父既有键深合并）
            putNested(map, keyPath, value)
        }
        return JsonObject(map)
    }

    /** 把 value 按 path 嵌套并入 map（path 长 1 直落；更长则建中间 JsonObject 链）。 */
    private fun putNested(map: LinkedHashMap<String, JsonElement>, path: List<String>, value: JsonElement) {
        if (path.size == 1) {
            map[path[0]] = value
            return
        }
        val child = LinkedHashMap<String, JsonElement>()
        (map[path[0]] as? JsonObject)?.forEach { (k, v) -> child[k] = v }
        putNested(child, path.drop(1), value)
        map[path[0]] = JsonObject(child)
    }

    /** 顶层逗号切分（引号与嵌套括号内的逗号不切）。 */
    private fun splitTopLevel(s: String): List<String> {
        val out = mutableListOf<String>()
        var quote: Char? = null
        var depth = 0
        var start = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (quote != null) {
                if (c == quote) quote = null
            } else when (c) {
                '\'', '"' -> quote = c
                '[', '{' -> depth++
                ']', '}' -> depth--
                ',' -> if (depth == 0) { out.add(s.substring(start, i)); start = i + 1 }
            }
            i++
        }
        out.add(s.substring(start))
        return out
    }

    /** 裸值：int（含 _）/ float / 其余（日期等）一律字符串。 */
    private fun parseBare(s: String, lineNo: Int): JsonElement {
        val noSep = s.replace("_", "")
        if (Regex("""^[+-]?\d+$""").matches(noSep)) {
            return noSep.toLongOrNull()?.let { JsonPrimitive(it) }
                ?: throw ModelConfigTextException("toml error at line $lineNo: integer out of range")
        }
        if (Regex("""^[+-]?(\d+\.\d*|\.\d+)([eE][+-]?\d+)?$|^[+-]?\d+[eE][+-]?\d+$""").matches(noSep)) {
            return noSep.toDoubleOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(s)
        }
        return JsonPrimitive(s)
    }

    /** 可变树 → JsonElement（表头改写父表，必须建完再冻结）。 */
    private fun finalize(node: Any): JsonElement = when (node) {
        is LinkedHashMap<*, *> -> {
            val m = LinkedHashMap<String, JsonElement>()
            for ((k, v) in node) m[k.toString()] = finalize(v)
            JsonObject(m)
        }
        is ArrayList<*> -> JsonArray(node.map { finalize(it) })
        is JsonElement -> node
        else -> JsonPrimitive(node.toString())
    }
}
