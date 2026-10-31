package com.apex.agent.core.engine.templates.runtime.builtin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR 19 测试 · 正则优化与测试工具箱。
 */
class RegexToolkitTest {

    // ═══ 方言探测 ═══

    @Test
    fun `flavor detection maps syntax features`() {
        assertEquals(RegexFlavor.PYTHON, RegexFlavor.detect("(?P<name>\\w+)"))
        assertEquals(RegexFlavor.JAVA, RegexFlavor.detect("(?<name>\\w+)"))
        assertEquals(RegexFlavor.PCRE, RegexFlavor.detect("\\p{L}+"))
        assertEquals(RegexFlavor.POSIX, RegexFlavor.detect("[[:alpha:]]+"))
        assertEquals(RegexFlavor.JAVASCRIPT, RegexFlavor.detect("a(?<!b)"))
    }

    // ═══ 优化建议 ═══

    @Test
    fun `nested quantifier is flagged with possessive rewrite`() {
        val optimizations = RegexToolkit.optimize("(a+)+")
        val nested = optimizations.first { it.kind == RegexOptimization.Kind.NESTED_QUANTIFIER }
        assertTrue(nested.rewritten.contains("占有"))
        assertTrue(nested.benefit.contains("线性"))
    }

    @Test
    fun `unbounded quantifier over alternation is flagged`() {
        val optimizations = RegexToolkit.optimize("(a|b|c)*")
        assertTrue(
            optimizations.any { it.kind == RegexOptimization.Kind.UNBOUNDED_QUANTIFIER }
        )
    }

    @Test
    fun `redundant character classes are simplified`() {
        val optimizations = RegexToolkit.optimize("\\d+[0-9]*")
        assertTrue(
            optimizations.any { it.kind == RegexOptimization.Kind.REDUNDANT_CLASS && it.rewritten == "\\d" }
        )
    }

    @Test
    fun `double negation is simplified`() {
        val optimizations = RegexToolkit.optimize("\\D[^\\D]")
        assertTrue(
            optimizations.any { it.kind == RegexOptimization.Kind.DOUBLE_NEGATION }
        )
    }

    @Test
    fun `simple regex yields few optimizations`() {
        val optimizations = RegexToolkit.optimize("^\\d{4}-\\d{2}-\\d{2}$")
        // 无嵌套量词/冗余类 —— 优化建议应少
        assertTrue(optimizations.none { it.kind == RegexOptimization.Kind.NESTED_QUANTIFIER })
    }

    // ═══ 结构画像 ═══

    @Test
    fun `anchor profile detects full anchoring`() {
        val profile = RegexToolkit.profile("^abc$")
        assertTrue(profile.anchoredStart)
        assertTrue(profile.anchoredEnd)
        assertTrue(RegexToolkit.isFullyAnchored("^abc$"))
        assertFalse(RegexToolkit.isFullyAnchored("abc"))
    }

    @Test
    fun `group and quantifier counting works`() {
        val profile = RegexToolkit.profile("^(\\w+)-(\\d+)$")
        assertEquals(2, profile.capturingGroups)
        assertEquals(2, profile.greedyQuantifiers)
        assertEquals(0, profile.lazyQuantifiers)
        assertEquals(2, RegexToolkit.profile("a.*?b.*?").lazyQuantifiers)
    }

    @Test
    fun `named group is counted`() {
        val profile = RegexToolkit.profile("(?<year>\\d{4})")
        assertEquals(1, profile.capturingGroups)
        assertEquals(1, profile.namedGroups)
    }

    // ═══ 样例与测试片段 ═══

    @Test
    fun `test cases are derived from literal regex`() {
        val cases = RegexToolkit.deriveTestCases("abc")
        assertTrue(cases.isNotEmpty())
        assertTrue(cases.first { it.shouldMatch }.sample.contains("abc"))
    }

    @Test
    fun `kotlin snippet contains kotlin regex api`() {
        val snippet = RegexToolkit.generateTestSnippet("\\d+", "kotlin")
        assertTrue(snippet.contains("Regex"))
        assertTrue(snippet.contains("assertTrue"))
        assertTrue(snippet.contains("assertFalse"))
    }

    @Test
    fun `python snippet uses re module`() {
        val snippet = RegexToolkit.generateTestSnippet("\\d+", "python")
        assertTrue(snippet.contains("import re"))
        assertTrue(snippet.contains("re.search"))
    }

    @Test
    fun `javascript snippet uses regex literal`() {
        val snippet = RegexToolkit.generateTestSnippet("\\d+", "javascript")
        assertTrue(snippet.contains("pattern.test"))
    }

    @Test
    fun `recommended test language follows flavor`() {
        assertEquals("python", RegexToolkit.recommendTestLanguage(RegexFlavor.PYTHON))
        assertEquals("javascript", RegexToolkit.recommendTestLanguage(RegexFlavor.JAVASCRIPT))
        assertEquals("kotlin", RegexToolkit.recommendTestLanguage(RegexFlavor.JAVA))
    }

    // ═══ 防御式 ═══

    @Test
    fun `unparseable pattern degrades gracefully`() {
        // 括号不闭合 —— 解析器报错，工具箱不抛
        assertTrue(RegexToolkit.optimize("(unclosed").isEmpty() || RegexToolkit.optimize("(unclosed").isNotEmpty())
        assertEquals(
            RegexStructureProfile(
                anchoredStart = false, anchoredEnd = false, greedyQuantifiers = 0,
                lazyQuantifiers = 0, capturingGroups = 0, namedGroups = 0, alternationCount = 0
            ),
            RegexToolkit.profile("(unclosed")
        )
    }
}
