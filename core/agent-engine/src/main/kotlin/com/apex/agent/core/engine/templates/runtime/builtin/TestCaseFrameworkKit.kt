package com.apex.agent.core.engine.templates.runtime.builtin

/**
 * ═══ 内置模板行为 · 测试用例 —— 框架套件与骨架生成（PR 19 深化 III）═══
 *
 * 测试用例行为的第二层确定性引擎：[TestCasesAnalyzer] 管「等价类怎么
 * 推导、边界值有哪些」，本模块管「给定框架怎么落地成可运行的测试
 * 代码」——把八种主流测试框架的约定（注解/断言库/生命周期/参数化/
 * 命名/mock）全部代码化，并生成带编号的覆盖矩阵与可直接落盘的
 * 测试骨架，回包之后再对 LLM 产出的测试代码做反模式 lint。
 *
 * 能力族（全部纯函数、全部确定性）：
 * 1. **框架规范库**（framework specs）：JUnit4 / JUnit5 / kotlin.test /
 *    Kotest / MockK / pytest / Jest / go test 八套规范 —— 测试标记、
 *    断言库（含 import）、生命周期钩子、参数化形态、命名风格、
 *    mock 库建议一次给齐；
 * 2. **命名规则**（naming rules）：按框架风格生成用例名 —— Kotlin
 *    反引号自然语言 / JUnit 驼峰 / pytest 蛇形 / Jest 描述串，按
 *    正常/边界/异常三类套模板；
 * 3. **骨架生成**（skeleton generator）：函数 × 用例类 → 可运行测试
 *    骨架（含 arrange-act-assert 分段注释与断言占位）；
 * 4. **断言映射**（assertion mapper）：类型/语义 → 各框架的具体断言
 *    调用（相等/抛异常/真值/空集合/近似值）；
 * 5. **覆盖矩阵**（coverage matrix）：函数 × 用例类 → 带编号（TC-001…）
 *    的矩阵表，喂给「覆盖矩阵」章节做确定性底稿；
 * 6. **反模式 lint**（anti-pattern lint）：回包测试代码的常见病检查
 *    ——无断言测试、循环内断言、裸 catch、魔法 sleep、一测多行为。
 *
 * 防御式纪律：未知框架回落 JUnit5 规范；空函数清单退化空骨架。
 */

/** 测试框架规范（不可变快照）。 */
data class TestFrameworkSpec(
    /** 规范 id（junit4/junit5/kotlin-test/kotest/mockk/pytest/jest/go-test）。 */
    val id: String,
    val label: String,
    /** 测试标记（注解或函数形态）。 */
    val testMarker: String,
    /** 断言库（框架配套或推荐）。 */
    val assertionLibrary: String,
    /** 断言库 import 行（空 = 语言内建）。 */
    val assertionImports: List<String>,
    /** 生命周期钩子（before/after 形态说明）。 */
    val lifecycleNotes: List<String>,
    /** 参数化测试形态模板。 */
    val parameterizedPattern: String,
    /** 命名风格。 */
    val namingStyle: NamingStyle,
    /** 推荐 mock 库（无 mock 场景为空）。 */
    val mockLibrary: String,
    /** 骨架模板（占位符：{name} 用例名 / {calls} 调用与断言）。 */
    val skeletonTemplate: String
) {
    /** 命名风格。 */
    enum class NamingStyle(val label: String) {
        BACKTICK("反引号自然语言（Kotlin）"),
        CAMEL_CASE("驼峰（should…）"),
        SNAKE_CASE("蛇形（test_…）"),
        DESCRIPTIVE("describe/it 描述串")
    }
}

/** 一条测试骨架（函数 × 用例类别）。 */
data class FrameworkTestSkeleton(
    /** 用例编号（TC-001 形态）。 */
    val caseId: String,
    /** 被测函数名。 */
    val functionName: String,
    /** 用例类别（正常/边界/异常）。 */
    val category: CaseCategory,
    /** 框架风格的用例名。 */
    val testName: String,
    /** 可运行骨架代码。 */
    val code: String
) {
    enum class CaseCategory(val label: String) {
        NORMAL("正常路径"),
        BOUNDARY("边界值"),
        EXCEPTION("异常路径")
    }
}

/** 覆盖矩阵单元。 */
data class CoverageMatrixCell(
    val caseId: String,
    val functionName: String,
    val category: FrameworkTestSkeleton.CaseCategory,
    /** 一句话用例描述（矩阵行内容）。 */
    val description: String
)

/** 测试代码反模式发现。 */
data class TestAntiPattern(
    val kind: Kind,
    val detail: String,
    val evidence: String,
    val repairable: Boolean
) {
    enum class Kind(val label: String) {
        NO_ASSERTION("无断言"),
        ASSERT_IN_LOOP("循环内断言"),
        BARE_CATCH("裸捕获"),
        MAGIC_SLEEP("魔法 sleep"),
        MULTI_BEHAVIOR("一测多行为"),
        GENERIC_EXCEPTION("断言过宽异常")
    }
}

/**
 * 框架套件主体（纯函数集合）。
 */
object TestCaseFrameworkKit {

    // ═══ 1) 框架规范库 ═══

    /** 八套框架出厂规范。 */
    val SPECS: Map<String, TestFrameworkSpec> = listOf(
        TestFrameworkSpec(
            id = "junit4", label = "JUnit 4",
            testMarker = "@Test (org.junit.Test)",
            assertionLibrary = "org.junit.Assert",
            assertionImports = listOf("import org.junit.Assert.*"),
            lifecycleNotes = listOf(
                "@Before / @After 每个用例前后执行",
                "@BeforeClass / @AfterClass 静态、全类一次"
            ),
            parameterizedPattern = "@RunWith(Parameterized.class) + @Parameters 静态集合",
            namingStyle = TestFrameworkSpec.NamingStyle.CAMEL_CASE,
            mockLibrary = "Mockito（mockito-core）",
            skeletonTemplate = "@Test\npublic void {name}() {\n    // arrange\n\n    // act\n\n{calls}\n}"
        ),
        TestFrameworkSpec(
            id = "junit5", label = "JUnit 5",
            testMarker = "@Test (org.junit.jupiter.api.Test)",
            assertionLibrary = "org.junit.jupiter.api.Assertions",
            assertionImports = listOf("import org.junit.jupiter.api.Test", "import static org.junit.jupiter.api.Assertions.*"),
            lifecycleNotes = listOf(
                "@BeforeEach / @AfterEach 每用例",
                "@BeforeAll / @AfterAll 静态、全类一次",
                "@Nested 分组 / @Tag 过滤 / @Disabled 跳过"
            ),
            parameterizedPattern = "@ParameterizedTest + @ValueSource/@CsvSource/@MethodSource",
            namingStyle = TestFrameworkSpec.NamingStyle.CAMEL_CASE,
            mockLibrary = "MockK（Kotlin）或 Mockito（Java）",
            skeletonTemplate = "@Test\nfun `{name}`() {\n    // arrange\n\n    // act\n\n{calls}\n}"
        ),
        TestFrameworkSpec(
            id = "kotlin-test", label = "kotlin.test",
            testMarker = "@Test (kotlin.test.Test)",
            assertionLibrary = "kotlin.test",
            assertionImports = listOf("import kotlin.test.Test", "import kotlin.test.*"),
            lifecycleNotes = listOf(
                "无注解生命周期 —— 用 @BeforeTest / @AfterTest",
                "kotlin.test 兼容 JUnit 平台运行器"
            ),
            parameterizedPattern = "循环表驱动（无内建参数化）或接 JUnit5 @ParameterizedTest",
            namingStyle = TestFrameworkSpec.NamingStyle.BACKTICK,
            mockLibrary = "MockK",
            skeletonTemplate = "@Test\nfun {name-code}() {\n    // arrange\n\n    // act\n\n{calls}\n}"
        ),
        TestFrameworkSpec(
            id = "kotest", label = "Kotest",
            testMarker = "StringSpec / FunSpec { test(…) }",
            assertionLibrary = "io.kotest.matchers",
            assertionImports = listOf("import io.kotest.core.spec.style.FunSpec", "import io.kotest.matchers.shouldBe"),
            lifecycleNotes = listOf(
                "beforeTest / afterTest 回调",
                "listeners / factory 复用夹具"
            ),
            parameterizedPattern = "forAll(表驱动) / 数据类参数化",
            namingStyle = TestFrameworkSpec.NamingStyle.BACKTICK,
            mockLibrary = "MockK（io.mockk）",
            skeletonTemplate = "class {name-class} : FunSpec({\n    test(\"{name}\") {\n        // arrange\n\n        // act\n\n{calls}\n    }\n})"
        ),
        TestFrameworkSpec(
            id = "mockk", label = "MockK + JUnit5",
            testMarker = "@Test + relaxed mock",
            assertionLibrary = "kotlin.test + MockK 断言",
            assertionImports = listOf(
                "import io.mockk.every", "import io.mockk.verify",
                "import io.mockk.mockk", "import kotlin.test.Test"
            ),
            lifecycleNotes = listOf("@BeforeEach 里建 mock；@AfterEach 里 confirmVerified(mocks)"),
            parameterizedPattern = "JUnit5 参数化 + every { } 打桩表",
            namingStyle = TestFrameworkSpec.NamingStyle.BACKTICK,
            mockLibrary = "MockK 本体",
            skeletonTemplate = "@Test\nfun `{name}`() {\n    // arrange（every { } 打桩）\n\n    // act\n\n{calls}\n    verify(exactly = 1) { /* 交互断言 */ }\n}"
        ),
        TestFrameworkSpec(
            id = "pytest", label = "pytest",
            testMarker = "def test_*():",
            assertionLibrary = "assert 语句 + pytest.raises",
            assertionImports = listOf("import pytest"),
            lifecycleNotes = listOf(
                "fixture 函数（@pytest.fixture）按参数名注入",
                "yield 形态 fixture 兼顾前后置"
            ),
            parameterizedPattern = "@pytest.mark.parametrize(\"参数名\", [值列表])",
            namingStyle = TestFrameworkSpec.NamingStyle.SNAKE_CASE,
            mockLibrary = "unittest.mock（标准库 monkeypatch）",
            skeletonTemplate = "def {name}():\n    # arrange\n\n    # act\n\n{calls}\n"
        ),
        TestFrameworkSpec(
            id = "jest", label = "Jest",
            testMarker = "test('…', () => {})",
            assertionLibrary = "expect().toBe() 族",
            assertionImports = listOf("// @ts-nocheck（按需）"),
            lifecycleNotes = listOf("beforeEach / afterEach；describe 嵌套分组"),
            parameterizedPattern = "test.each([[输入, 期望], …])",
            namingStyle = TestFrameworkSpec.NamingStyle.DESCRIPTIVE,
            mockLibrary = "jest.mock() / jest.spyOn()",
            skeletonTemplate = "test('{name}', () => {\n  // arrange\n\n  // act\n\n{calls}\n});"
        ),
        TestFrameworkSpec(
            id = "go-test", label = "go test",
            testMarker = "func TestXxx(t *testing.T)",
            assertionLibrary = "if got != want { t.Errorf }",
            assertionImports = listOf('"' + "testing" + '"'),
            lifecycleNotes = listOf("t.Cleanup 注册清理；TestMain 全局前后置"),
            parameterizedPattern = "表驱动：tests := []struct{ name string; … }",
            namingStyle = TestFrameworkSpec.NamingStyle.CAMEL_CASE,
            mockLibrary = "gomock / testify",
            skeletonTemplate = "func {name}(t *testing.T) {\n    // arrange\n\n    // act\n\n{calls}\n}"
        )
    ).associateBy { it.id }

    /** 框架解析（宽容匹配别名；未识别回落 junit5）。 */
    fun resolveFramework(input: String): TestFrameworkSpec {
        val normalized = input.trim().lowercase()
        return when {
            normalized.contains("junit4") || normalized.contains("junit 4") || normalized.contains("junit-4") ->
                SPECS.getValue("junit4")
            normalized.contains("kotest") -> SPECS.getValue("kotest")
            normalized.contains("mockk") -> SPECS.getValue("mockk")
            normalized.contains("kotlin.test") || normalized.contains("kotlin-test") ->
                SPECS.getValue("kotlin-test")
            normalized.contains("junit") || normalized.contains("junit5") || normalized.isEmpty() ->
                SPECS.getValue("junit5")
            normalized.contains("pytest") || normalized.contains("python") -> SPECS.getValue("pytest")
            normalized.contains("jest") || normalized.contains("js") || normalized.contains("ts") ->
                SPECS.getValue("jest")
            normalized.contains("go") -> SPECS.getValue("go-test")
            else -> SPECS.getValue("junit5")
        }
    }

    // ═══ 2) 命名规则 ═══

    /**
     * 按框架风格生成用例名。
     *
     * @param functionName 被测函数
     * @param category 用例类别
     * @param scenario 场景短语（如「空列表入参」；空则按类别默认）
     */
    fun testNamingOf(
        spec: TestFrameworkSpec,
        functionName: String,
        category: FrameworkTestSkeleton.CaseCategory,
        scenario: String = ""
    ): String {
        val scene = scenario.ifBlank {
            when (category) {
                FrameworkTestSkeleton.CaseCategory.NORMAL -> "常规输入"
                FrameworkTestSkeleton.CaseCategory.BOUNDARY -> "边界输入"
                FrameworkTestSkeleton.CaseCategory.EXCEPTION -> "非法输入"
            }
        }
        return when (spec.namingStyle) {
            TestFrameworkSpec.NamingStyle.BACKTICK ->
                "$functionName $scene 时返回预期结果"
            TestFrameworkSpec.NamingStyle.CAMEL_CASE -> {
                val verb = if (functionName.startsWith("is") || functionName.startsWith("has")) "returns" else "handles"
                "${verb}${functionName.replaceFirstChar { it.uppercaseChar() }}With${scenePascal(scene)}"
            }
            TestFrameworkSpec.NamingStyle.SNAKE_CASE ->
                "test_${snakeOf(functionName)}_${snakeOf(category.label)}"
            TestFrameworkSpec.NamingStyle.DESCRIPTIVE ->
                "$functionName $scene"
        }
    }

    private fun scenePascal(scene: String): String =
        scene.replace(Regex("""[^\w]"""), "_").split('_', ' ')
            .filter { it.isNotBlank() }
            .joinToString("") { it.replaceFirstChar { inner -> inner.uppercaseChar() } }
            .ifBlank { "Default" }

    private fun snakeOf(text: String): String =
        text.replace(Regex("""([a-z])([A-Z])"""), "$1_$2")
            .lowercase()
            .replace(' ', '_')
            .replace(Regex("""[^\w]"""), "")

    // ═══ 3) 断言映射 ═══

    /** 断言语义。 */
    enum class AssertionKind { EQUALS, THROWS, TRUE, FALSE, NULL, EMPTY_COLLECTION, APPROXIMATE }

    /**
     * 语义 → 框架断言调用（骨架 {calls} 段的确定性内容）。
     *
     * @param actual 实际值表达式（骨架里是占位变量）
     * @param expected 期望值表达式
     */
    fun assertionOf(
        spec: TestFrameworkSpec,
        kind: AssertionKind,
        actual: String,
        expected: String
    ): String = when (spec.id) {
        "junit4" -> when (kind) {
            AssertionKind.EQUALS -> "assertEquals($expected, $actual);"
            AssertionKind.THROWS -> "try { $actual; fail(\"应抛异常\"); } catch (Exception expected) { /* 断言异常已到达 */ }"
            AssertionKind.TRUE -> "assertTrue($actual);"
            AssertionKind.FALSE -> "assertFalse($actual);"
            AssertionKind.NULL -> "assertNull($actual);"
            AssertionKind.EMPTY_COLLECTION -> "assertTrue(($actual).isEmpty());"
            AssertionKind.APPROXIMATE -> "assertEquals($expected, $actual, 1e-9);"
        }
        "junit5", "kotlin-test", "mockk" -> when (kind) {
            AssertionKind.EQUALS -> "assertEquals($expected, $actual)"
            AssertionKind.THROWS -> "assertThrows<Exception> { $actual }"
            AssertionKind.TRUE -> "assertTrue($actual)"
            AssertionKind.FALSE -> "assertFalse($actual)"
            AssertionKind.NULL -> "assertNull($actual)"
            AssertionKind.EMPTY_COLLECTION -> "assertTrue($actual.isEmpty())"
            AssertionKind.APPROXIMATE -> "assertEquals($expected, $actual, 1e-9)"
        }
        "kotest" -> when (kind) {
            AssertionKind.EQUALS -> "$actual shouldBe $expected"
            AssertionKind.THROWS -> "shouldThrow<Exception> { $actual }"
            AssertionKind.TRUE -> "$actual shouldHaveSize 1 // 或 shouldBe(true)"
            AssertionKind.FALSE -> "$actual shouldBe false"
            AssertionKind.NULL -> "$actual shouldNotBeNull() shouldBe null // 或 shouldBe(null)"
            AssertionKind.EMPTY_COLLECTION -> "$actual shouldHaveSize 0"
            AssertionKind.APPROXIMATE -> "$actual shouldBeExactly $expected // 近似用 shouldHavePrecision"
        }
        "pytest" -> when (kind) {
            AssertionKind.EQUALS -> "assert $actual == $expected"
            AssertionKind.THROWS -> "with pytest.raises(Exception):\n        $actual"
            AssertionKind.TRUE -> "assert $actual is True"
            AssertionKind.FALSE -> "assert $actual is False"
            AssertionKind.NULL -> "assert $actual is None"
            AssertionKind.EMPTY_COLLECTION -> "assert $actual == []"
            AssertionKind.APPROXIMATE -> "assert abs($actual - $expected) < 1e-9"
        }
        "jest" -> when (kind) {
            AssertionKind.EQUALS -> "expect($actual).toBe($expected);"
            AssertionKind.THROWS -> "expect(() => $actual).toThrow();"
            AssertionKind.TRUE -> "expect($actual).toBe(true);"
            AssertionKind.FALSE -> "expect($actual).toBe(false);"
            AssertionKind.NULL -> "expect($actual).toBeNull();"
            AssertionKind.EMPTY_COLLECTION -> "expect($actual).toHaveLength(0);"
            AssertionKind.APPROXIMATE -> "expect($actual).toBeCloseTo($expected, 9);"
        }
        "go-test" -> when (kind) {
            AssertionKind.EQUALS -> "if got := $actual; got != $expected {\n        t.Errorf(\"got %v, want %v\", got, $expected)\n    }"
            AssertionKind.THROWS -> "func() {\n        defer func() { if recover() == nil { t.Error(\"应 panic\") } }()\n        $actual\n    }()"
            AssertionKind.TRUE -> "if !($actual) { t.Error(\"应为真\") }"
            AssertionKind.FALSE -> "if $actual { t.Error(\"应为假\") }"
            AssertionKind.NULL -> "if $actual != nil { t.Error(\"应为 nil\") }"
            AssertionKind.EMPTY_COLLECTION -> "if len($actual) != 0 { t.Error(\"应为空\") }"
            AssertionKind.APPROXIMATE -> "if math.Abs(float64($actual)-float64($expected)) > 1e-9 { t.Error(\"近似失败\") }"
        }
        else -> "assertEquals($expected, $actual)"
    }

    // ═══ 4) 骨架生成 ═══

    /**
     * 测试骨架生成：函数 × 三类用例 → 框架风格骨架。
     * 每函数最多 3 条（正常/边界/异常各一），总量夹在 [maxCases]。
     */
    fun generateSkeletons(
        spec: TestFrameworkSpec,
        functions: List<TestCaseFunction>,
        maxCases: Int = 12
    ): List<FrameworkTestSkeleton> {
        val skeletons = mutableListOf<FrameworkTestSkeleton>()
        var sequence = 0
        outer@ for (function in functions) {
            for (category in FrameworkTestSkeleton.CaseCategory.entries) {
                if (skeletons.size >= maxCases) break@outer
                sequence++
                val caseId = "TC-%03d".format(sequence)
                val testName = testNamingOf(spec, function.name, category)
                val assertionKind = when (category) {
                    FrameworkTestSkeleton.CaseCategory.NORMAL -> AssertionKind.EQUALS
                    FrameworkTestSkeleton.CaseCategory.BOUNDARY -> AssertionKind.EQUALS
                    FrameworkTestSkeleton.CaseCategory.EXCEPTION -> AssertionKind.THROWS
                }
                val callExpression = callExpressionOf(function, category)
                val assertion = assertionOf(
                    spec, assertionKind,
                    actual = "result",
                    expected = expectedPlaceholderOf(category)
                )
                val code = spec.skeletonTemplate
                    .replace("{name-code}", snakeOf(testName))
                    .replace("{name-class}", scenePascal(testName))
                    .replace("{name}", testName)
                    .replace("{calls}", indentAssertion(assertion, spec))
                skeletons += FrameworkTestSkeleton(
                    caseId = caseId,
                    functionName = function.name,
                    category = category,
                    testName = testName,
                    code = "// $caseId（${category.label}）\n" + code
                )
            }
        }
        return skeletons
    }

    /** 被测函数调用表达式（骨架 act 段占位）。 */
    private fun callExpressionOf(function: TestCaseFunction, category: FrameworkTestSkeleton.CaseCategory): String {
        val args = function.params.joinToString(", ") { param ->
            when (category) {
                FrameworkTestSkeleton.CaseCategory.NORMAL -> "${param.name} = 合法值"
                FrameworkTestSkeleton.CaseCategory.BOUNDARY -> "${param.name} = 边界值"
                FrameworkTestSkeleton.CaseCategory.EXCEPTION -> "${param.name} = 非法值"
            }
        }
        return "val result = ${function.name}($args)"
    }

    private fun expectedPlaceholderOf(category: FrameworkTestSkeleton.CaseCategory): String = when (category) {
        FrameworkTestSkeleton.CaseCategory.NORMAL -> "期望值"
        FrameworkTestSkeleton.CaseCategory.BOUNDARY -> "边界期望值"
        FrameworkTestSkeleton.CaseCategory.EXCEPTION -> "异常类型"
    }

    /** 断言块缩进适配（Python/JS/Golang 的缩进风格差异）。 */
    private fun indentAssertion(assertion: String, spec: TestFrameworkSpec): String {
        val indent = when (spec.id) {
            "pytest" -> "    "
            "jest" -> "  "
            "go-test" -> "    "
            else -> "    "
        }
        return assertion.lines().joinToString("\n") { line -> if (line.isBlank()) line else indent + line }
    }

    // ═══ 5) 覆盖矩阵 ═══

    /** 函数 × 用例类 → 矩阵单元清单（「覆盖矩阵」章节的确定性底稿）。 */
    fun buildCoverageMatrix(
        spec: TestFrameworkSpec,
        functions: List<TestCaseFunction>
    ): List<CoverageMatrixCell> =
        generateSkeletons(spec, functions, maxCases = MAX_MATRIX_CELLS).map { skeleton ->
            CoverageMatrixCell(
                caseId = skeleton.caseId,
                functionName = skeleton.functionName,
                category = skeleton.category,
                description = "${skeleton.category.label}：${skeleton.testName}"
            )
        }

    /** 矩阵单行（喂提示词附录 / 报告表格）。 */
    fun matrixLineOf(cell: CoverageMatrixCell): String =
        "${cell.caseId}｜${cell.functionName}｜${cell.category.label}｜${cell.description.substringAfter("：")}"

    // ═══ 6) 反模式 lint ═══

    /**
     * 回包测试代码反模式检查（VALIDATE 阶段调用）：
     * - 无断言测试（标记了 test 但无 assert/expect/t.Error）；
     * - 循环内断言（for + assert 同块 —— 失败只报最后一轮）；
     * - 裸 catch（捕获后空体）；
     * - 魔法 sleep（Thread.sleep / time.sleep 直接出现在非等待测试里）；
     * - 一测多行为（一个测试体内多个不相干 act 调用 —— 粗启发：
     *   同一测试块内断言数 > 3）。
     */
    fun lintAntiPatterns(testCode: String): List<TestAntiPattern> {
        val issues = mutableListOf<TestAntiPattern>()
        if (testCode.isBlank()) return issues
        val blocks = testBlocks(testCode)
        val assertionPattern = Regex(
            """assert|expect\(|should[A-Z]|t\.Error|t\.Fatal|fail\(|raise |\.toBe"""
        )
        for ((name, body) in blocks) {
            if (!assertionPattern.containsMatchIn(body)) {
                issues += TestAntiPattern(
                    kind = TestAntiPattern.Kind.NO_ASSERTION,
                    detail = "测试「$name」没有任何断言 —— 跑过 ≠ 正确",
                    evidence = name.take(40),
                    repairable = false
                )
            }
            val assertionCount = assertionPattern.findAll(body).count()
            if (assertionCount > MAX_ASSERTS_PER_TEST) {
                issues += TestAntiPattern(
                    kind = TestAntiPattern.Kind.MULTI_BEHAVIOR,
                    detail = "测试「$name」含 $assertionCount 处断言，疑似一测多行为 —— 拆分为独立用例",
                    evidence = name.take(40),
                    repairable = true
                )
            }
        }
        if (Regex("""for\s*\([^)]*\)[^{]*\{[^}]*assert""").containsMatchIn(testCode) ||
            Regex("""for\s+\w+\s+in\s+[\w:]+\s*\{[^}]*assert""").containsMatchIn(testCode)
        ) {
            issues += TestAntiPattern(
                kind = TestAntiPattern.Kind.ASSERT_IN_LOOP,
                detail = "循环体内断言：失败只报最后一轮，丢失前面失败的现场 —— 改表驱动逐例报告",
                evidence = "",
                repairable = true
            )
        }
        if (Regex("""catch\s*\([^)]*\)\s*\{\s*\}""").containsMatchIn(testCode)) {
            issues += TestAntiPattern(
                kind = TestAntiPattern.Kind.BARE_CATCH,
                detail = "裸 catch（捕获后空体）吞掉失败 —— 至少断言异常类型",
                evidence = "",
                repairable = true
            )
        }
        if (Regex("""(Thread\.sleep|time\.sleep|time\.Sleep)\s*\(\s*\d+""").containsMatchIn(testCode)) {
            issues += TestAntiPattern(
                kind = TestAntiPattern.Kind.MAGIC_SLEEP,
                detail = "魔法 sleep 让测试变慢且不稳定 —— 用假时钟注入或显式等待条件",
                evidence = Regex("""(Thread\.sleep|time\.sleep|time\.Sleep)\s*\(\s*\d+""").find(testCode)?.value ?: "",
                repairable = true
            )
        }
        if (Regex("""assertThrows\s*<\s*(Exception|Throwable)\s*>|pytest\.raises\(Exception\)|toThrow\(\)""").containsMatchIn(testCode)) {
            issues += TestAntiPattern(
                kind = TestAntiPattern.Kind.GENERIC_EXCEPTION,
                detail = "断言过宽异常类型（Exception/Throwable）—— 收窄到具体异常类型",
                evidence = "",
                repairable = true
            )
        }
        return issues
    }

    /** 测试块切分（名称 → 函数体；语言通用的粗启发）。 */
    private fun testBlocks(testCode: String): List<Pair<String, String>> {
        val blocks = mutableListOf<Pair<String, String>>()
        var pendingMarker = false
        var currentName: String? = null
        val currentBody = StringBuilder()

        fun flush() {
            val name = currentName ?: return
            blocks += name to currentBody.toString()
            currentName = null
            currentBody.setLength(0)
        }

        for (line in testCode.lines()) {
            val trimmed = line.trim()
            // 测试标记行：不算块起点，置位等函数定义行（JUnit/pytest 装饰器形态）
            if (trimmed.startsWith("@Test") || trimmed.startsWith("@ParameterizedTest") ||
                trimmed.startsWith("@pytest") || trimmed.startsWith("@pytest.mark")
            ) {
                if (currentName == null) {
                    pendingMarker = true
                    continue
                }
            }
            val isDefinition = trimmed.startsWith("def test_") ||
                trimmed.startsWith("test(") || trimmed.startsWith("test.each(") ||
                trimmed.startsWith("it(") || trimmed.startsWith("it.each(") ||
                trimmed.startsWith("fun ") || trimmed.startsWith("func Test") ||
                (pendingMarker && (trimmed.startsWith("void ") || trimmed.startsWith("func ") ||
                    trimmed.startsWith("def ")))
            if (isDefinition) {
                flush()
                currentName = extractTestName(trimmed)
                pendingMarker = false
            } else if (currentName != null) {
                currentBody.appendLine(line)
            }
        }
        flush()
        return blocks
    }

    private fun extractTestName(definitionLine: String): String {
        val header = Regex("""(?:fun|def|test|it|void|func)\s+[`"']?([\w\s`-]+)[`"']?\s*\(""")
            .find(definitionLine)
        return header?.groupValues?.get(1)?.trim()?.ifBlank { definitionLine.take(30) }
            ?: definitionLine.take(30)
    }

    // ═══ 常量 ═══

    private const val MAX_ASSERTS_PER_TEST = 3
    private const val MAX_MATRIX_CELLS = 16
}
