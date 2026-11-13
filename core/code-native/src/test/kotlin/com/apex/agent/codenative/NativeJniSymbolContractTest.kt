package com.apex.agent.codenative

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * # JNI 符号绑定契约 —— Kotlin external 声明 ↔ .cpp 导出符号双向核对
 *
 * 事故背景（v1.5.39 真机 logcat E 级刷屏）：libcode_native.so 导出符号
 * 曾以 CodeNativeBridge 结尾，而 Kotlin 声明类是 NativeTextKernel ——
 * 编译期完全无感（符号名不进链接校验），运行期每次调用抛
 * UnsatisfiedLinkError（"No implementation found for … nativeEstimateTokens"）
 * 后被 runCatching 吞掉、静默回退纯 Kotlin：加速核从未生效，还倒贴
 * JNI 查找 + 异常构造成本。
 *
 * 本测试在 JVM 源级锁死该契约（无需 NDK / 真机 / 模拟器）：
 *  * 正向 —— Kotlin 每个 external fun 必须有 mangle(类全名 + 方法名) 的
 *    导出符号（JNI 静态绑定规则：Java_ + 类全名 + _ + 方法名）；
 *  * 反向 —— .cpp 每个 Java_ 前缀导出必须对应某个 external fun
 *    （防陈旧符号残留——正是 CodeNativeBridge 事故的残留形态）；
 *  * 形参 —— external fun 为实例方法（桥接类类体直属，非 companion），
 *    .cpp 第二参数必须是 jobject（jclass 是静态方法绑定的形参类型，
 *    写错属类型混淆）。
 *
 * 契约边界（超出即显式失败，绝不静默放宽）：
 *  * external fun 不允许重载（重载需要双下划线签名后缀 mangle，本测试
 *    不覆盖——需要时先扩展本测试再加重载）；
 *  * external fun 必须声明在桥接类类体（companion 内会绑定到 Companion
 *    类、mangle 规则不同——需要时先扩展本测试）。
 *
 * 解析器是源级轻量扫描（剔注释/字符串后按括号深度归属声明类），不依赖
 * kotlinc —— 面向本模块单文件族足够；结构漂移时宁可报错也不猜。
 */
class NativeJniSymbolContractTest {

    // ── 被测源定位 ──────────────────────────────────────────────────────

    /**
     * 模块目录定位：Gradle 单测工作目录 = 模块目录；从 user.dir 逐级上溯
     * 兼容仓库根运行（IDE / 聚合任务）两种布局，命中模块标记文件即停。
     */
    private val moduleDir: File by lazy {
        val direct = "src/main/cpp/src/code_native_jni.cpp"
        val nested = "core/code-native/src/main/cpp/src/code_native_jni.cpp"
        var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
        while (dir != null) {
            if (File(dir, direct).isFile) return@lazy dir
            if (File(dir, nested).isFile) return@lazy File(dir, "core/code-native")
            dir = dir.parentFile
        }
        error(
            "无法定位 code-native 模块目录（user.dir=" +
                System.getProperty("user.dir").orEmpty() + "）——契约测试需在模块目录或仓库根下运行"
        )
    }

    /** Kotlin 声明侧：模块内全部 .kt 主源（路径无关，防重命名后漏检）。 */
    private val kotlinSources: List<String> by lazy {
        val root = File(moduleDir, "src/main/kotlin")
        val files = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.readText() }
            .toList()
        assertTrue(
            "src/main/kotlin 下未找到任何 .kt 源——契约测试解析器需跟进模块布局",
            files.isNotEmpty()
        )
        files
    }

    /** C++ 导出侧：模块内全部 .cpp（code_kernel.cpp 纯算法层无 Java_ 导出，天然不参与）。 */
    private val cppSources: List<String> by lazy {
        val root = File(moduleDir, "src/main/cpp")
        val files = root.walkTopDown()
            .filter { it.isFile && it.extension == "cpp" }
            .map { it.readText() }
            .toList()
        assertTrue(
            "src/main/cpp 下未找到任何 .cpp 源——契约测试解析器需跟进模块布局",
            files.isNotEmpty()
        )
        files
    }

    /** Kotlin external 声明（类全名 + 方法名）。 */
    private val externals: List<ExternalDecl> by lazy {
        val decls = kotlinSources.flatMap { parseKotlinExternals(skeleton(it)) }
        assertTrue(
            "未解析到任何 external fun——声明丢失或解析器结构漂移，契约测试需跟进",
            decls.isNotEmpty()
        )
        decls
    }

    /** .cpp 全部 Java_ 前缀导出（符号名 + 签名文本）。 */
    private val exports: List<CppExport> by lazy {
        val found = cppSources.flatMap { parseCppExports(it) }
        assertTrue(
            "未解析到任何 Java_ 前缀导出——符号导出走 RegisterNatives 了？契约测试需跟进",
            found.isNotEmpty()
        )
        found
    }

    // ── 契约断言 ────────────────────────────────────────────────────────

    @Test
    fun `kotlin external funs have matching jni export symbols`() {
        val expected = expectedSymbols()
        val exported = exports.map { it.symbol }.toSet()
        val missing = expected - exported
        assertTrue(
            "Kotlin 声明的 external fun 缺少对应 JNI 导出符号" +
                "（JNI 静态绑定 = Java_ + 类全名 + _ + 方法名）：\n" +
                missing.sorted().joinToString("\n") { "  缺失: $it" } +
                "\n.cpp 实际导出：\n" +
                exported.sorted().joinToString("\n") { "  $it" },
            missing.isEmpty()
        )
    }

    @Test
    fun `cpp jni exports have no orphan symbols`() {
        val expected = expectedSymbols()
        val orphans = exports.map { it.symbol }.toSet() - expected
        assertTrue(
            ".cpp 存在无 Kotlin 声明对应的孤儿导出符号" +
                "（陈旧符号残留——CodeNativeBridge 事故形态，改名后旧符号必须同步删除）：\n" +
                orphans.sorted().joinToString("\n") { "  孤儿: $it" },
            orphans.isEmpty()
        )
    }

    @Test
    fun `instance method exports bind jobject not jclass`() {
        val jobjectRe = Regex("""\bjobject\b""")
        val jclassRe = Regex("""\bjclass\b""")
        val offenders = exports.filter {
            !jobjectRe.containsMatchIn(it.signature) || jclassRe.containsMatchIn(it.signature)
        }
        assertTrue(
            "JNI 导出第二参数必须是 jobject（external fun 为实例方法；" +
                "jclass 是静态方法绑定的形参类型）：\n" +
                offenders.joinToString("\n") { "  违规: ${it.symbol}" },
            offenders.isEmpty()
        )
    }

    // ── Kotlin 侧解析 ──────────────────────────────────────────────────

    /** external 声明：声明类全名 + 方法名。 */
    private data class ExternalDecl(val fqcn: String, val method: String)

    /**
     * 从剔注释/字符串后的 Kotlin 骨架提取 external 声明。括号深度归属：
     * 深度 0 的类型声明开启当前桥接类；external fun 必须落在深度 1
     * （类体直属）——companion / 嵌套类型（深度 ≥ 2）会改变 mangle 规则，
     * 直接报错引导保持实例成员形态。
     */
    private fun parseKotlinExternals(sk: String): List<ExternalDecl> {
        val packageRe = Regex("""(?m)^\s*package\s+([A-Za-z_][\w.]*)""")
        val typeRe = Regex(
            """^\s*(?:(?:public|private|internal|protected|final|open|abstract|sealed|data|value|enum)\s+)*""" +
                """(?:class|object|interface)\s+([A-Za-z_]\w*)"""
        )
        val externalRe = Regex("""\bexternal\s+fun\s+([A-Za-z_]\w*)""")
        val decls = mutableListOf<ExternalDecl>()
        val seen = mutableSetOf<Pair<String, String>>()
        val pkg = packageRe.find(sk)?.groupValues?.getOrNull(1)
            ?: error("模块 .kt 源缺 package 声明——契约测试无法 mangle 类全名")
        var depth = 0
        var currentType: String? = null
        for (line in sk.lines()) {
            if (depth == 0) {
                typeRe.find(line)?.let { currentType = it.groupValues[1] }
            }
            for (match in externalRe.findAll(line)) {
                val method = match.groupValues[1]
                val owner = currentType
                    ?: error("external fun $method 出现在任何顶层类型之外——契约测试无法归属声明类")
                if (depth != 1) {
                    error(
                        "external fun $method 声明深度异常（当前=$depth）——" +
                            "契约要求 external fun 直属桥接类类体；companion/嵌套类型的" +
                            "mangle 规则不同，需要时先扩展本测试"
                    )
                }
                if (!seen.add(owner to method)) {
                    error(
                        "external fun $owner.$method 存在重载——JNI 重载 mangle 需要" +
                            "双下划线签名后缀，本契约测试不支持，请保持 external fun 无重载"
                    )
                }
                decls.add(ExternalDecl("$pkg.$owner", method))
            }
            for (ch in line) {
                when (ch) {
                    '{' -> depth++
                    '}' -> depth--
                }
            }
        }
        return decls
    }

    // ── C++ 侧解析 ─────────────────────────────────────────────────────

    /** .cpp 导出：符号名 + 从符号起至函数体开括号的签名文本。 */
    private data class CppExport(val symbol: String, val signature: String)

    /**
     * 提取行首锚定的 Java_ 前缀导出函数。先剔除行注释（本模块 .cpp 无
     * 内嵌双斜线的字符串字面量），符号必须独占逻辑行首——注释里的提及
     * 不会误报。
     */
    private fun parseCppExports(cpp: String): List<CppExport> {
        val code = cpp.lines().joinToString("\n") { it.substringBefore("//") }
        val symbolRe = Regex("""(?m)^Java_[A-Za-z0-9_]+(?=\s*\()""")
        return symbolRe.findAll(code).map { match ->
            val symbol = match.value
            val bodyOpen = code.indexOf('{', match.range.last)
            val end = if (bodyOpen >= 0) bodyOpen else code.length
            CppExport(symbol, code.substring(match.range.first, end))
        }.toList()
    }

    // ── mangle ─────────────────────────────────────────────────────────

    /** JNI 名字 mangle：点 → 下划线、下划线 → _1、美元 → _00024（单遍无顺序陷阱）。 */
    private fun mangle(name: String): String = name.map { c ->
        when (c) {
            '.' -> "_"
            '_' -> "_1"
            '$' -> "_00024"
            else -> c.toString()
        }
    }.joinToString("")

    private fun expectedSymbols(): Set<String> =
        externals.map { "Java_" + mangle(it.fqcn) + "_" + mangle(it.method) }.toSet()

    // ── Kotlin 词法骨架 ────────────────────────────────────────────────

    /**
     * 剔除注释与字符串/字符字面量（保留换行与原文长度）——括号深度扫描
     * 不再被 KDoc、注释或字符串里的花括号与关键字干扰。Kotlin 块注释可
     * 嵌套，按开闭计数配对；普通字符串支持反斜杠转义与双引号转义，
     * 原始字符串按三引号进出（无转义）。
     */
    private fun skeleton(source: String): String {
        val out = StringBuilder(source)
        var i = 0
        var blockDepth = 0
        var lineComment = false
        var normalString = false
        var rawString = false
        var charLiteral = false
        while (i < source.length) {
            val c = source[i]
            val next = if (i + 1 < source.length) source[i + 1] else '\u0000'
            when {
                lineComment -> {
                    if (c == '\n') lineComment = false else out[i] = ' '
                }
                blockDepth > 0 -> {
                    if (c == '/' && next == '*') {
                        blockDepth++
                        out[i] = ' '; out[i + 1] = ' '; i++
                    } else if (c == '*' && next == '/') {
                        blockDepth--
                        out[i] = ' '; out[i + 1] = ' '; i++
                    } else if (c != '\n') {
                        out[i] = ' '
                    }
                }
                normalString -> {
                    when {
                        c == '\\' -> {
                            out[i] = ' '
                            if (i + 1 < source.length && source[i + 1] != '\n') out[i + 1] = ' '
                            i++
                        }
                        c == '"' && next == '"' -> {
                            out[i] = ' '; out[i + 1] = ' '; i++
                        }
                        c == '"' -> normalString = false
                        else -> if (c != '\n') out[i] = ' '
                    }
                }
                rawString -> {
                    if (c == '"' && next == '"' && i + 2 < source.length && source[i + 2] == '"') {
                        out[i] = ' '; out[i + 1] = ' '; out[i + 2] = ' '
                        i += 2
                        rawString = false
                    } else if (c != '\n') {
                        out[i] = ' '
                    }
                }
                charLiteral -> {
                    when {
                        c == '\\' -> {
                            out[i] = ' '
                            if (i + 1 < source.length && source[i + 1] != '\n') out[i + 1] = ' '
                            i++
                        }
                        c == '\'' -> charLiteral = false
                        else -> if (c != '\n') out[i] = ' '
                    }
                }
                else -> when {
                    c == '/' && next == '/' -> {
                        lineComment = true
                        out[i] = ' '; out[i + 1] = ' '; i++
                    }
                    c == '/' && next == '*' -> {
                        blockDepth = 1
                        out[i] = ' '; out[i + 1] = ' '; i++
                    }
                    c == '"' && next == '"' && i + 2 < source.length && source[i + 2] == '"' -> {
                        rawString = true
                        out[i] = ' '; out[i + 1] = ' '; out[i + 2] = ' '
                        i += 2
                    }
                    c == '"' -> normalString = true
                    c == '\'' -> charLiteral = true
                }
            }
            i++
        }
        return out.toString()
    }
}
