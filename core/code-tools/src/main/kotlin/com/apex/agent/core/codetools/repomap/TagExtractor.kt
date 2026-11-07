package com.apex.agent.core.codetools.repomap

/**
 * # 标签提取器 —— Aider tree-sitter 查询的正则移植（MVP）
 *
 * Aider 对每种语言持有一份 `.scm` 查询（tree-sitter captures），抽出
 * `name.definition.*` / `name.reference.*` 两类节点。Android JVM 侧没有
 * 可用的 tree-sitter 原生绑定，这里用「语言轮廓 = 定义正则 + 关键字表」
 * 近似：
 *
 * - **def**：按语言列出的定义形态（fun/class/def/func/fn…），捕获组 1 =
 *   标识符；正则全部逐行匹配（`Regex` 显式 MULTILINE），行号 = 命中行；
 * - **ref**：全文件标识符 token 计数（排除该语言关键字）。tree-sitter 的
 *   ref 是作用域感知的，token 计数会把定义名也计入引用 —— 这与现代
 *   tree-sitter 查询（def 名同时被 def/ref 双捕获）行为一致，且 Aider
 *   对「只有 def 没有 ref 的语言」正是用 pygments token 兜底 ref 的，
 *   同款近似。
 *
 * 防御式约定（仓库架构原则）：任何输入形状都要吞得下 —— 不可读/超限的
 * 文件折成空 [FileTags]，绝不向上抛。
 */
class TagExtractor {

    /** 一个语言轮廓：扩展名集合 + 定义正则 + 关键字表。 */
    private class Profile(
        val extensions: Set<String>,
        val definitionPatterns: List<Regex>,
        val keywords: Set<String>,
        /** 行注释前缀（任意位置起效；剔除后防文档注释污染计数）。 */
        val lineComments: List<String> = listOf("//")
    )

    fun extract(relPath: String, content: String): FileTags {
        val profile = profileFor(relPath) ?: return FileTags(relPath, emptyList(), emptyMap())
        if (content.isEmpty()) return FileTags(relPath, emptyList(), emptyMap())

        val lines = content.split('\n')
        val defs = mutableListOf<RepoTag>()
        for ((index, line) in lines.withIndex()) {
            val code = stripComment(line, profile) ?: continue
            for (pattern in profile.definitionPatterns) {
                val m = pattern.find(code) ?: continue
                val ident = m.groupValues.getOrNull(1) ?: continue
                if (ident.isBlank() || ident in profile.keywords) continue
                defs += RepoTag(ident, RepoTagKind.DEF, relPath, index + 1)
                break // 每行至多记一个 def（与 tree-sitter 单查询主捕获对齐）
            }
        }

        val refCounts = HashMap<String, Int>()
        for (line in lines) {
            val code = stripComment(line, profile) ?: continue
            for (m in IDENTIFIER.findAll(code)) {
                val token = m.value
                if (token !in profile.keywords && token !in COMMON_NOISE) {
                    refCounts[token] = (refCounts[token] ?: 0) + 1
                }
            }
        }
        return FileTags(relPath, defs, refCounts)
    }

    /** 该路径是否是可抽取的源码文件（按扩展名路由）。 */
    fun supports(relPath: String): Boolean = profileFor(relPath) != null

    private fun profileFor(relPath: String): Profile? {
        val ext = relPath.substringAfterLast('.', "").lowercase()
        return PROFILES.firstOrNull { ext in it.extensions }
    }

    /**
     * 剔除注释后返回代码段；整行皆注释/空白 → null（跳过）。
     *
     * 防误伤规则：
     * - `//` / `#` 出现在字符串字面量内（位置前引号为奇数个）不算注释；
     * - 行首 `*`（块注释续行）整行跳过 —— `a * b` 乘法不受影响
     *   （乘法不往行首写星号）。
     */
    private fun stripComment(line: String, profile: Profile): String? {
        var cut = -1
        for (prefix in profile.lineComments) {
            var idx = line.indexOf(prefix)
            while (idx >= 0) {
                if (!insideStringLiteral(line, idx)) {
                    cut = if (cut == -1) idx else minOf(cut, idx)
                    break
                }
                idx = line.indexOf(prefix, idx + 1)
            }
        }
        var code = if (cut >= 0) line.substring(0, cut) else line
        if (code.trimStart().startsWith("*")) return null // 块注释续行整行跳过
        return code.trimEnd().ifEmpty { null }
    }

    /** 位置前出现奇数个引号 → 该位置在字符串字面量内（单/双引号同口径）。 */
    private fun insideStringLiteral(line: String, pos: Int): Boolean {
        var quotes = 0
        for (i in 0 until pos) {
            val c = line[i]
            if (c == '"' || c == '\'') quotes++
        }
        return quotes % 2 == 1
    }

    private companion object {
        val IDENTIFIER = Regex("[A-Za-z_$][A-Za-z0-9_$]*")

        /** 跨语言通用噪音 token：全大写常量风格之外的单字符/超通用词。 */
        val COMMON_NOISE = setOf(
            "it", "if", "in", "is", "as", "at", "by", "or", "to", "of", "on",
            "do", "no", "id", "ok", "up", "me", "my", "we", "an", "be"
        )

        /** Kotlin / Java 共用的驼峰定义正则片段。 */
        private val KT_DEFS = listOf(
            // fun（含泛型与接收者）：fun foo / fun <T> foo / fun Type.foo
            Regex("\\bfun\\s+(?:<[^>]*>\\s+)?(?:[A-Za-z_]\\w*\\.)*`?([A-Za-z_]\\w*)`?"),
            // class / object / interface（含 data/sealed/enum/abstract 等前缀由 \\b 边界天然兼容）
            Regex("\\b(?:class|object|interface)\\s+`?([A-Za-z_]\\w*)`?"),
            // typealias / 顶层属性
            Regex("\\b(?:typealias|val|var)\\s+`?([A-Za-z_]\\w*)`?")
        )
        private val KT_KEYWORDS = setOf(
            "package", "import", "class", "object", "interface", "fun", "val", "var",
            "typealias", "when", "while", "for", "if", "else", "return", "is", "in",
            "as", "by", "constructor", "companion", "init", "this", "super", "null",
            "true", "false", "suspend", "override", "open", "abstract", "sealed",
            "data", "enum", "annotation", "internal", "private", "protected", "public",
            "expect", "actual", "operator", "infix", "inline", "external", "lateinit",
            "reified", "out", "where", "break", "continue", "try", "catch", "finally",
            "throw", "typealias", "dynamic", "field", "get", "set", "param", "property",
            "setparam", "const", "vararg", "noinline", "crossinline", "it", "Unit",
            "Int", "Long", "Short", "Byte", "Double", "Float", "Boolean", "Char",
            "String", "Any", "Nothing", "Void"
        )

        private val JAVA_DEFS = listOf(
            Regex("\\b(?:class|interface|enum|record)\\s+([A-Za-z_]\\w*)"),
            // 方法/构造器：可见性或 static/final 等修饰词开头 + 返回类型 + 名字 + (
            Regex("\\b(?:public|protected|private|static|final|abstract|synchronized|native|default)\\s+[\\w<>\\[\\],.?\\s]+?\\s+([A-Za-z_]\\w*)\\s*\\(")
        )
        private val JAVA_KEYWORDS = setOf(
            "package", "import", "class", "interface", "enum", "record", "extends",
            "implements", "public", "protected", "private", "static", "final",
            "abstract", "synchronized", "native", "transient", "volatile", "strictfp",
            "void", "int", "long", "short", "byte", "double", "float", "boolean",
            "char", "String", "var", "new", "return", "if", "else", "for", "while",
            "do", "switch", "case", "default", "break", "continue", "try", "catch",
            "finally", "throw", "throws", "this", "super", "null", "true", "false",
            "instanceof", "assert", "sealed", "permits", "yield"
        )

        private val PY_DEFS = listOf(
            Regex("\\bdef\\s+([A-Za-z_]\\w*)"),
            Regex("\\bclass\\s+([A-Za-z_]\\w*)"),
            Regex("\\b([A-Za-z_]\\w*)\\s*=(?!=)") // 模块级赋值（近似）
        )
        private val PY_KEYWORDS = setOf(
            "def", "class", "return", "if", "elif", "else", "for", "while", "import",
            "from", "as", "with", "try", "except", "finally", "raise", "pass", "break",
            "continue", "lambda", "global", "nonlocal", "del", "assert", "yield",
            "async", "await", "in", "is", "not", "and", "or", "None", "True", "False",
            "self", "cls", "print", "len", "range", "str", "int", "float", "bool",
            "list", "dict", "set", "tuple", "type", "isinstance", "super"
        )

        private val JS_DEFS = listOf(
            Regex("\\bfunction\\s+([A-Za-z_$][\\w$]*)"),
            Regex("\\bclass\\s+([A-Za-z_$][\\w$]*)"),
            Regex("\\b(?:const|let|var)\\s+([A-Za-z_$][\\w$]*)"),
            Regex("\\b(?:type|interface|enum)\\s+([A-Za-z_$][\\w$]*)")
        )
        private val JS_KEYWORDS = setOf(
            "function", "class", "const", "let", "var", "type", "interface", "enum",
            "return", "if", "else", "for", "while", "do", "switch", "case", "default",
            "break", "continue", "new", "delete", "typeof", "instanceof", "void",
            "this", "super", "extends", "implements", "import", "export", "from",
            "as", "async", "await", "yield", "try", "catch", "finally", "throw",
            "null", "undefined", "true", "false", "static", "get", "set", "of", "in",
            "declare", "namespace", "module", "readonly", "public", "private",
            "protected", "abstract", "override", "string", "number", "boolean", "any",
            "unknown", "never", "object", "symbol", "bigint"
        )

        private val GO_DEFS = listOf(
            Regex("\\bfunc\\s+(?:\\([^)]*\\)\\s*)?([A-Za-z_]\\w*)"),
            Regex("\\btype\\s+([A-Za-z_]\\w*)"),
            Regex("\\b(?:var|const)\\s+([A-Za-z_]\\w*)")
        )
        private val GO_KEYWORDS = setOf(
            "func", "type", "var", "const", "package", "import", "return", "if",
            "else", "for", "range", "switch", "case", "default", "break", "continue",
            "go", "defer", "select", "chan", "map", "struct", "interface", "fallthrough",
            "goto", "nil", "true", "false", "iota", "len", "cap", "make", "new",
            "append", "copy", "delete", "panic", "recover", "print", "println"
        )

        private val RS_DEFS = listOf(
            Regex("\\bfn\\s+([A-Za-z_]\\w*)"),
            Regex("\\b(?:struct|enum|trait|mod|type)\\s+([A-Za-z_]\\w*)"),
            Regex("\\bimpl(?:<[^>]*>)?\\s+([A-Za-z_]\\w*)")
        )
        private val RS_KEYWORDS = setOf(
            "fn", "struct", "enum", "trait", "mod", "type", "impl", "let", "mut",
            "const", "static", "use", "pub", "crate", "self", "super", "as", "in",
            "for", "while", "loop", "if", "else", "match", "return", "break",
            "continue", "unsafe", "where", "dyn", "ref", "move", "async", "await",
            "true", "false", "Some", "None", "Ok", "Err", "Box", "Vec", "String",
            "Option", "Result"
        )

        private val C_DEFS = listOf(
            Regex("\\b(?:class|struct|enum|union)\\s+([A-Za-z_]\\w*)"),
            Regex("#define\\s+([A-Za-z_]\\w*)")
        )
        private val C_KEYWORDS = setOf(
            "class", "struct", "enum", "union", "define", "include", "ifdef", "ifndef",
            "endif", "pragma", "typedef", "static", "const", "extern", "inline",
            "virtual", "override", "template", "typename", "namespace", "using",
            "public", "protected", "private", "return", "if", "else", "for", "while",
            "do", "switch", "case", "default", "break", "continue", "new", "delete",
            "this", "throw", "try", "catch", "sizeof", "null", "nullptr", "true",
            "false", "auto", "int", "long", "short", "char", "float", "double",
            "bool", "void", "unsigned", "signed", "std", "size_t", "uint32_t",
            "uint64_t", "int32_t", "int64_t"
        )

        val PROFILES = listOf(
            Profile(setOf("kt", "kts"), KT_DEFS, KT_KEYWORDS),
            Profile(setOf("java"), JAVA_DEFS, JAVA_KEYWORDS),
            Profile(setOf("py", "pyw"), PY_DEFS, PY_KEYWORDS, lineComments = listOf("#")),
            Profile(setOf("js", "jsx", "ts", "tsx", "mjs", "cjs"), JS_DEFS, JS_KEYWORDS),
            Profile(setOf("go"), GO_DEFS, GO_KEYWORDS, lineComments = listOf("//")),
            Profile(setOf("rs"), RS_DEFS, RS_KEYWORDS, lineComments = listOf("//")),
            Profile(
                setOf("c", "h", "cpp", "hpp", "cc", "cxx", "cs"), C_DEFS, C_KEYWORDS,
                lineComments = listOf("//")
            )
        )
    }
}
