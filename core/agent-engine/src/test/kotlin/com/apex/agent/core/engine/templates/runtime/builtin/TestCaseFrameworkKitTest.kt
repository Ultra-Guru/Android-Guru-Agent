package com.apex.agent.core.engine.templates.runtime.builtin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR 19 测试 · 测试框架套件。
 */
class TestCaseFrameworkKitTest {

    private val function = TestCaseFunction(
        name = "parseOrder", startLine = 1,
        params = listOf(TestCaseParam("input", "String", nullable = false, hasDefault = false)),
        isSuspend = false
    )

    // ═══ 框架解析 ═══

    @Test
    fun `framework resolution maps aliases`() {
        assertEquals("junit4", TestCaseFrameworkKit.resolveFramework("JUnit4").id)
        assertEquals("junit5", TestCaseFrameworkKit.resolveFramework("JUnit").id)
        assertEquals("kotest", TestCaseFrameworkKit.resolveFramework("Kotest").id)
        assertEquals("pytest", TestCaseFrameworkKit.resolveFramework("pytest").id)
        assertEquals("jest", TestCaseFrameworkKit.resolveFramework("jest").id)
        assertEquals("go-test", TestCaseFrameworkKit.resolveFramework("go test").id)
        // 未知回落 junit5
        assertEquals("junit5", TestCaseFrameworkKit.resolveFramework("火星框架").id)
    }

    @Test
    fun `eight framework specs are complete`() {
        assertEquals(8, TestCaseFrameworkKit.SPECS.size)
        for (spec in TestCaseFrameworkKit.SPECS.values) {
            assertTrue("${spec.id} 缺测试标记", spec.testMarker.isNotBlank())
            assertTrue("${spec.id} 缺断言库", spec.assertionLibrary.isNotBlank())
            assertTrue("${spec.id} 缺骨架模板", spec.skeletonTemplate.contains("{calls}"))
        }
    }

    // ═══ 命名规则 ═══

    @Test
    fun `naming adapts to framework style`() {
        val junit = TestCaseFrameworkKit.resolveFramework("junit5")
        val pytest = TestCaseFrameworkKit.resolveFramework("pytest")
        val kotlinStyle = TestCaseFrameworkKit.resolveFramework("kotlin.test")

        val junitName = TestCaseFrameworkKit.testNamingOf(
            junit, "parseOrder", TestCaseSkeleton.CaseCategory.BOUNDARY
        )
        assertTrue(junitName.startsWith("handles") || junitName.startsWith("returns"))

        val pytestName = TestCaseFrameworkKit.testNamingOf(
            pytest, "parseOrder", TestCaseSkeleton.CaseCategory.EXCEPTION
        )
        assertTrue(pytestName.startsWith("test_"))

        val kotlinName = TestCaseFrameworkKit.testNamingOf(
            kotlinStyle, "parseOrder", TestCaseSkeleton.CaseCategory.NORMAL
        )
        assertTrue(kotlinName.contains("parseOrder"))
    }

    // ═══ 断言映射 ═══

    @Test
    fun `assertion mapping covers frameworks`() {
        val junit5 = TestCaseFrameworkKit.resolveFramework("junit5")
        assertEquals(
            "assertEquals(want, result)",
            TestCaseFrameworkKit.assertionOf(junit5, TestCaseFrameworkKit.AssertionKind.EQUALS, "result", "want")
        )
        val pytest = TestCaseFrameworkKit.resolveFramework("pytest")
        assertTrue(
            TestCaseFrameworkKit.assertionOf(pytest, TestCaseFrameworkKit.AssertionKind.THROWS, "run()", "ValueError")
                .contains("pytest.raises")
        )
        val jest = TestCaseFrameworkKit.resolveFramework("jest")
        assertTrue(
            TestCaseFrameworkKit.assertionOf(jest, TestCaseFrameworkKit.AssertionKind.EQUALS, "result", "want")
                .contains("toBe")
        )
    }

    // ═══ 骨架与覆盖矩阵 ═══

    @Test
    fun `skeletons are generated with case ids`() {
        val spec = TestCaseFrameworkKit.resolveFramework("junit5")
        val skeletons = TestCaseFrameworkKit.generateSkeletons(spec, listOf(function))
        assertEquals(3, skeletons.size)
        assertEquals("TC-001", skeletons[0].caseId)
        assertEquals("TC-002", skeletons[1].caseId)
        assertTrue(skeletons.all { it.code.contains("arrange") })
        // 异常用例用 THROWS 断言
        assertTrue(skeletons[2].code.contains("assertThrows"))
    }

    @Test
    fun `coverage matrix matches skeleton ids`() {
        val spec = TestCaseFrameworkKit.resolveFramework("junit5")
        val matrix = TestCaseFrameworkKit.buildCoverageMatrix(spec, listOf(function))
        assertEquals(3, matrix.size)
        assertEquals("TC-001", matrix[0].caseId)
        assertTrue(TestCaseFrameworkKit.matrixLineOf(matrix[0]).contains("TC-001"))
    }

    @Test
    fun `empty functions yield empty skeletons`() {
        val spec = TestCaseFrameworkKit.resolveFramework("junit5")
        assertTrue(TestCaseFrameworkKit.generateSkeletons(spec, emptyList()).isEmpty())
    }

    // ═══ 测试片段生成 ═══

    @Test
    fun `test snippets are language specific`() {
        val kotlinSnippet = TestCaseFrameworkKit.generateTestSnippet("\\d+", "kotlin")
        assertTrue(kotlinSnippet.contains("Regex"))
        val pythonSnippet = TestCaseFrameworkKit.generateTestSnippet("\\d+", "python")
        assertTrue(pythonSnippet.contains("re.search"))
        val jsSnippet = TestCaseFrameworkKit.generateTestSnippet("\\d+", "javascript")
        assertTrue(jsSnippet.contains("pattern.test"))
    }

    // ═══ 反模式 lint ═══

    @Test
    fun `test without assertion is flagged`() {
        val issues = TestCaseFrameworkKit.lintAntiPatterns(
            """
            @Test
            fun `just runs`() {
                doSomething()
            }
            """.trimIndent()
        )
        assertTrue(issues.any { it.kind == TestAntiPattern.Kind.NO_ASSERTION })
    }

    @Test
    fun `magic sleep is flagged as repairable`() {
        val issues = TestCaseFrameworkKit.lintAntiPatterns(
            """
            @Test
            fun `waits`() {
                Thread.sleep(3000)
                assertEquals(1, 1)
            }
            """.trimIndent()
        )
        val sleep = issues.first { it.kind == TestAntiPattern.Kind.MAGIC_SLEEP }
        assertTrue(sleep.repairable)
        assertTrue(sleep.evidence.contains("3000"))
    }

    @Test
    fun `bare catch is flagged`() {
        val issues = TestCaseFrameworkKit.lintAntiPatterns(
            """
            @Test
            fun `swallows`() {
                try { risky() } catch (e: Exception) { }
                assertTrue(true)
            }
            """.trimIndent()
        )
        assertTrue(issues.any { it.kind == TestAntiPattern.Kind.BARE_CATCH })
    }

    @Test
    fun `generic exception assertion is flagged`() {
        val issues = TestCaseFrameworkKit.lintAntiPatterns(
            """
            @Test
            fun `too wide`() {
                assertThrows<Exception> { parse(null) }
            }
            """.trimIndent()
        )
        assertTrue(issues.any { it.kind == TestAntiPattern.Kind.GENERIC_EXCEPTION })
    }

    @Test
    fun `clean test passes lint`() {
        val issues = TestCaseFrameworkKit.lintAntiPatterns(
            """
            @Test
            fun `parses valid order`() {
                val result = parseOrder("A1")
                assertEquals(1, result.id)
            }
            """.trimIndent()
        )
        assertTrue(issues.isEmpty())
    }

    @Test
    fun `assert in loop is flagged`() {
        val issues = TestCaseFrameworkKit.lintAntiPatterns(
            """
            @Test
            fun `loop asserts`() {
                for (item in listOf(1, 2, 3)) {
                    assertEquals(item, item)
                }
            }
            """.trimIndent()
        )
        assertTrue(issues.any { it.kind == TestAntiPattern.Kind.ASSERT_IN_LOOP })
    }
}
