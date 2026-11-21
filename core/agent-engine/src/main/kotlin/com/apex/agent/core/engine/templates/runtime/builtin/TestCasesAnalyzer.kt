package com.apex.agent.core.engine.templates.runtime.builtin

import com.apex.agent.core.engine.templates.runtime.TemplateFinding
import com.apex.agent.core.engine.templates.runtime.TemplateFindingCategory
import com.apex.agent.core.engine.templates.runtime.TemplateSeverity

/**
 * ═══ 内置模板行为 · 测试用例 —— 等价类与边界推导器（PR 12）═══
 *
 * 测试用例行为的分析层：从被测代码提取「参数签名 → 等价类 → 边界值
 * → 测试矩阵」的确定性推导链：
 * 1. **函数签名提取**：参数名/类型/默认值（复用 CodeReview 的函数探测）；
 * 2. **类型 → 等价类表**：Int/String/List/Boolean/可空类型等各自的
 *    合法/非法/边界类；
 * 3. **边界值推导**：数字 0/1/-1/最大值、字符串空/单字符/超长、
 *    集合空/单元素/满容量；
 * 4. **测试矩阵**：等价类 × 函数 → 用例骨架（含命名建议）；
 * 5. **框架约定库**：JUnit4/5、Kotest、pytest、Jest、go test 的
 *    命名/断言/结构约定。
 */

/** 提取出的函数参数。 */
data class TestCaseParam(
    val name: String,
    /** 推断的类型（kotlin 类型名或 unknown）。 */
    val type: String,
    /** 是否可空。 */
    val nullable: Boolean,
    /** 是否有默认值（缺省参数）。 */
    val hasDefault: Boolean
)

/** 提取出的被测函数。 */
data class TestCaseFunction(
    val name: String,
    val startLine: Int,
    val params: List<TestCaseParam>,
    /** 是否 suspend 函数。 */
    val isSuspend: Boolean
)

/** 等价类。 */
data class EquivalenceClass(
    /** 类别名（正常值/边界值/异常值…）。 */
    val category: String,
    /** 该类的取值描述。 */
    val values: List<String>,
    /** 关联参数名。 */
    val paramName: String
)

/** 测试用例骨架。 */
data class TestCaseSkeleton(
    /** 建议的测试名（框架无关语义名）。 */
    val name: String,
    val category: String,
    /** 涉及参数与取值。 */
    val setup: List<Pair<String, String>>,
    /** 断言焦点。 */
    val assertionFocus: String
)

/** 分析产物。 */
data class TestCaseAnalysis(
    val functions: List<TestCaseFunction>,
    val equivalenceClasses: List<EquivalenceClass>,
    val skeletons: List<TestCaseSkeleton>,
    val notes: List<String>
)

/**
 * 测试用例推导器（纯函数）。
 */
object TestCasesAnalyzer {

    // ═══ 签名提取 ═══

    /**
     * 函数签名提取（复用 CodeReview 的函数探测 + 参数计数通道）。
     */
    fun extractFunctions(code: String, language: String?): List<TestCaseFunction> {
        if (code.isBlank()) return emptyList()
        val behavior = CodeReviewTemplateBehavior()
        val spans = behavior.findFunctions(code, language)
        return spans.map { span ->
            val signature = extractSignature(code, span.startLine)
            TestCaseFunction(
                name = span.name,
                startLine = span.startLine,
                params = extractParams(signature),
                isSuspend = signature.contains("suspend")
            )
        }
    }

    /** 签名文本：从头行起向上缓冲到参数列表闭合（最多 8 行）。 */
    private fun extractSignature(code: String, startLine: Int): String {
        val lines = code.lines()
        val buffer = StringBuilder()
        var index = startLine - 1
        while (index < lines.size && index < startLine - 1 + 8) {
            buffer.append(lines[index]).append('\n')
            if (lines[index].count { it == ')' } > 0) break
            index++
        }
        return buffer.toString()
    }

    /** 参数解析：name: Type = default（Kotlin 风格为主，兼容 python 的 name=default）。 */
    internal fun extractParams(signature: String): List<TestCaseParam> {
        val open = signature.indexOf('(')
        if (open < 0) return emptyList()
        // 截到配对右括号
        var depth = 0
        var end = -1
        for (index in open until signature.length) {
            when (signature[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) {
                        end = index
                        break
                    }
                }
            }
        }
        if (end < 0) end = signature.length
        val body = signature.substring(open + 1, end)
        if (body.isBlank()) return emptyList()
        val params = mutableListOf<TestCaseParam>()
        var genericDepth = 0
        val current = StringBuilder()
        for (ch in body) {
            when (ch) {
                '<' -> {
                    genericDepth++
                    current.append(ch)
                }
                '>' -> {
                    genericDepth--
                    current.append(ch)
                }
                ',' -> if (genericDepth == 0) {
                    current.toString().trim().takeIf { it.isNotBlank() }?.let { params.add(parseParam(it)) }
                    current.clear()
                } else {
                    current.append(ch)
                }
                else -> current.append(ch)
            }
        }
        current.toString().trim().takeIf { it.isNotBlank() }?.let { params.add(parseParam(it)) }
        return params
    }

    private fun parseParam(raw: String): TestCaseParam {
        val hasDefault = raw.contains('=') && !raw.contains("==")
        val left = raw.substringBefore('=').trim()
        val nullable = left.contains('?')
        val name = left.substringBefore(':').trim().removeSuffix("?").replace(Regex("""^\w+\s+"""), "")
        val type = if (left.contains(':')) {
            left.substringAfter(':').trim().removeSuffix("?")
        } else {
            inferPythonishType(raw.substringAfter('=').trim())
        }
        return TestCaseParam(name = name.ifBlank { "arg" }, type = type, nullable = nullable, hasDefault = hasDefault)
    }

    /** 无类型标注（Python 风格）时从默认值猜类型。 */
    private fun inferPythonishType(defaultValue: String): String = when {
        defaultValue.isBlank() -> "unknown"
        defaultValue.matches(Regex("""-?\d+""")) -> "Int"
        defaultValue.matches(Regex("""-?\d+\.\d+""")) -> "Double"
        defaultValue == "True" || defaultValue == "False" || defaultValue == "true" || defaultValue == "false" -> "Boolean"
        defaultValue.startsWith("[") || defaultValue.startsWith("list(") -> "List"
        defaultValue.startsWith("{") || defaultValue.startsWith("dict(") -> "Map"
        defaultValue.startsWith("\"") || defaultValue.startsWith("'") -> "String"
        defaultValue == "None" || defaultValue == "null" -> "unknown?"
        else -> "unknown"
    }

    // ═══ 等价类与边界 ═══

    /** 类型 → 等价类表。 */
    fun equivalenceClassesFor(param: TestCaseParam): List<EquivalenceClass> {
        val base = when {
            param.type in INT_TYPES -> listOf(
                EquivalenceClass("正常值", listOf("42", "7"), param.name),
                EquivalenceClass("边界值", listOf("0", "1", "-1", "Int.MAX_VALUE", "Int.MIN_VALUE"), param.name),
                EquivalenceClass("异常值", listOf("超上限（MAX+1 的 Long 截断场景）", "非数字输入（校验层）"), param.name)
            )
            param.type == "String" -> listOf(
                EquivalenceClass("正常值", listOf("\"abc\"", "\"中文样本\""), param.name),
                EquivalenceClass("边界值", listOf("\"\"（空串）", "\" \"（空白）", "\"a\"（单字符）", "超长串（>长度约束）"), param.name),
                EquivalenceClass("异常值", if (param.nullable) listOf("null") else listOf("含控制字符", "非法编码"), param.name)
            )
            param.type.startsWith("List") || param.type.startsWith("Array") || param.type.startsWith("Set") -> listOf(
                EquivalenceClass("正常值", listOf("[a, b, c]"), param.name),
                EquivalenceClass("边界值", listOf("[]（空集合）", "[a]（单元素）", "满容量（如有上限）"), param.name),
                EquivalenceClass("异常值", listOf("null（如可空）", "含 null 元素（如元素非空）"), param.name)
            )
            param.type == "Boolean" -> listOf(
                EquivalenceClass("正常值", listOf("true", "false"), param.name)
            )
            param.type.startsWith("Map") || param.type.startsWith("Dict") -> listOf(
                EquivalenceClass("正常值", listOf("{k1: v1, k2: v2}"), param.name),
                EquivalenceClass("边界值", listOf("{}（空映射）", "键重复（合并语义）"), param.name)
            )
            param.type == "Double" || param.type == "Float" -> listOf(
                EquivalenceClass("正常值", listOf("3.14"), param.name),
                EquivalenceClass("边界值", listOf("0.0", "-0.0", "NaN", "Infinity", "最小精度 epsilon"), param.name)
            )
            param.nullable -> listOf(
                EquivalenceClass("正常值", listOf("合法对象"), param.name),
                EquivalenceClass("边界值", listOf("null（空安全路径）"), param.name)
            )
            else -> listOf(
                EquivalenceClass("正常值", listOf("代表性取值"), param.name),
                EquivalenceClass("边界值", listOf("最小/最大约束取值"), param.name),
                EquivalenceClass("异常值", listOf("非法取值（如契约外）"), param.name)
            )
        }
        return if (param.nullable && base.none { it.values.contains("null（空安全路径）") }) {
            base + EquivalenceClass("边界值", listOf("null（空安全路径）"), param.name)
        } else {
            base
        }
    }

    private val INT_TYPES = setOf("Int", "Long", "Short", "Byte", "int", "long", "Integer")

    // ═══ 测试矩阵 ═══

    /**
     * 测试矩阵：每个函数 × 等价类（正常/边界/异常各取代表）→ 用例骨架。
     * 命名规范：`函数名 + 条件 + 期望`。
     */
    fun buildSkeletons(analysis: TestCaseAnalysis): List<TestCaseSkeleton> {
        val skeletons = mutableListOf<TestCaseSkeleton>()
        for (function in analysis.functions) {
            // 正常路径
            skeletons.add(
                TestCaseSkeleton(
                    name = "${function.name} 正常输入 返回预期结果",
                    category = "正常路径",
                    setup = function.params.map { param ->
                        param.name to equivalenceClassesFor(param)
                            .firstOrNull { it.category == "正常值" }?.values?.firstOrNull().orEmpty()
                    },
                    assertionFocus = "主路径输出与契约一致"
                )
            )
            // 边界路径（每参数边界代表）
            for (param in function.params) {
                val boundary = equivalenceClassesFor(param).firstOrNull { it.category == "边界值" } ?: continue
                for (value in boundary.values.take(3)) {
                    skeletons.add(
                        TestCaseSkeleton(
                            name = "${function.name} ${param.name}=$value 稳定处理",
                            category = "边界值",
                            setup = listOf(param.name to value),
                            assertionFocus = if (value.contains("null") || value == "null") "空安全（不抛 NPE）" else "边界输入不越界/不溢出"
                        )
                    )
                }
            }
            // 异常路径（每参数异常代表）
            for (param in function.params) {
                val invalid = equivalenceClassesFor(param).firstOrNull { it.category == "异常值" } ?: continue
                skeletons.add(
                    TestCaseSkeleton(
                        name = "${function.name} ${param.name} 非法输入 被拒绝或兜底",
                        category = "异常路径",
                        setup = listOf(param.name to invalid.values.first()),
                        assertionFocus = "显式失败（异常/错误码），不静默吞"
                    )
                )
            }
        }
        return skeletons.take(MAX_SKELETONS)
    }

    // ═══ 框架约定库 ═══

    /** 框架约定（提示词注入）。 */
    fun frameworkConventions(framework: String): List<String> = when {
        framework.contains("kotest", true) -> listOf(
            "Kotest：FunSpec + should 风格（\"output\" should beSorted）",
            "属性测试可用 Arb（数据生成器）覆盖等价类",
            "命名用 backtick 自由文本：`sum of empty list is zero`"
        )
        framework.contains("junit5", true) || framework.contains("jupiter", true) -> listOf(
            "JUnit5：@Test + @DisplayName（中文描述测试意图）",
            "参数化用 @ParameterizedTest + @ValueSource/@CsvSource 覆盖等价类",
            "断言用 Assertions.assertEquals / assertThrows"
        )
        framework.contains("pytest", true) -> listOf(
            "pytest：test_ 前缀函数 + parametrize 装饰器覆盖等价类",
            "夹具用 @pytest.fixture；异常断言用 pytest.raises",
            "命名：test_<函数>_<条件>_<期望>"
        )
        framework.contains("jest", true) || framework.contains("vitest", true) -> listOf(
            "Jest/Vitest：describe(函数) + it(条件与期望)",
            "参数化用 it.each 覆盖等价类；异常断言 expect().toThrow"
        )
        framework.contains("go", true) -> listOf(
            "go test：Test 函数 + 表驱动（cases := []struct{...}）",
            "断言用 t.Errorf / testify；命名 Test<函数><条件>"
        )
        else -> listOf(
            "JUnit4：@Test + 有意义命名（方法名说明意图：testSum_emptyList_returnsZero）",
            "一个测试只验证一个行为；不依赖网络与真实文件系统（手写 fake）",
            "断言值明确（不用 assertTrue(x > 0) 这种弱断言，用 assertEquals(42, x)）"
        )
    }

    /**
     * 分析聚合。
     */
    fun analyze(code: String, language: String?, framework: String): TestCaseAnalysis {
        val functions = extractFunctions(code, language)
        val notes = mutableListOf<String>()
        if (functions.isEmpty()) {
            notes.add("未探测到函数签名：等价类推导需要签名（参数名与类型）；请确认输入是被测代码本体")
            return TestCaseAnalysis(emptyList(), emptyList(), emptyList(), notes)
        }
        val classes = functions.flatMap { function ->
            function.params.flatMap { equivalenceClassesFor(it) }
        }
        val draft = TestCaseAnalysis(functions, classes, emptyList(), notes)
        val skeletons = buildSkeletons(draft)
        val suspendFunctions = functions.count { it.isSuspend }
        if (suspendFunctions > 0) {
            notes.add("检测到 $suspendFunctions 个 suspend 函数：测试用 runTest 驱动，不阻塞真实线程")
        }
        val defaultParams = functions.sumOf { it.params.count { p -> p.hasDefault } }
        if (defaultParams > 0) {
            notes.add("检测到 $defaultParams 个默认值参数：每个默认参数至少一条「省略入参走默认值」用例")
        }
        return draft.copy(skeletons = skeletons)
    }

    /** 分析产物 → 发现清单。 */
    fun findingsFrom(analysis: TestCaseAnalysis): List<TemplateFinding> = buildList {
        if (analysis.functions.isNotEmpty()) {
            add(
                TemplateFinding(
                    ruleId = "test.functions",
                    category = TemplateFindingCategory.STRUCTURE,
                    severity = TemplateSeverity.INFO,
                    title = "被测函数 ${analysis.functions.size} 个（参数 ${analysis.functions.sumOf { it.params.size }} 个）",
                    evidence = analysis.functions.joinToString("；") { fn ->
                        "${fn.name}(${fn.params.joinToString(",") { "${it.name}:${it.type}${if (it.nullable) "?" else ""}" }})"
                    },
                    suggestion = "用例清单按函数分组，逐函数覆盖三类路径",
                    confidence = 1.0
                )
            )
        }
        for (skeleton in analysis.skeletons.take(8)) {
            add(
                TemplateFinding(
                    ruleId = "test.skeleton",
                    category = TemplateFindingCategory.STRUCTURE,
                    severity = TemplateSeverity.INFO,
                    title = "用例骨架（${skeleton.category}）：${skeleton.name}",
                    evidence = "入参：" + skeleton.setup.joinToString(",") { "${it.first}=${it.second}" },
                    suggestion = "断言焦点：${skeleton.assertionFocus}",
                    confidence = 0.85
                )
            )
        }
        for (note in analysis.notes) {
            add(
                TemplateFinding(
                    ruleId = "test.note",
                    category = TemplateFindingCategory.COMPLETENESS,
                    severity = TemplateSeverity.MINOR,
                    title = note,
                    suggestion = "在测试代码中显式落实",
                    confidence = 1.0
                )
            )
        }
    }

    const val MAX_SKELETONS = 18
}
