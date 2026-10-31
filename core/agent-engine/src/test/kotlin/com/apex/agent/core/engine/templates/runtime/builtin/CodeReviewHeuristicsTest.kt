package com.apex.agent.core.engine.templates.runtime.builtin

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR 19 测试 · 代码审查启发器（命名/注释/结构/层次）。
 */
class CodeReviewHeuristicsTest {

    // ═══ 命名质量 ═══

    @Test
    fun `meaningless names are flagged in batch`() {
        val code = """
            fun process() {
                val a = 1
                val b = 2
                val temp = a + b
                return temp
            }
        """.trimIndent()
        val findings = CodeReviewHeuristics.namingFindings(code, "kotlin")
        assertTrue(findings.any { it.ruleId == "heuristic.meaningless-names" })
    }

    @Test
    fun `boolean naming without prefix is flagged`() {
        val code = """
            val enabled: Boolean = true
            val found: Boolean = false
        """.trimIndent()
        val findings = CodeReviewHeuristics.namingFindings(code, "kotlin")
        val naming = findings.first { it.ruleId == "heuristic.boolean-naming" }
        assertTrue(naming.evidence.contains("isEmpty"))
    }

    @Test
    fun `good names pass`() {
        val code = """
            fun parseOrder(input: String): Int {
                val orderId = input.length
                val isEnabled: Boolean = orderId > 0
                return if (isEnabled) orderId else 0
            }
        """.trimIndent()
        assertTrue(CodeReviewHeuristics.namingFindings(code, "kotlin").isEmpty())
    }

    // ═══ 注释质量 ═══

    @Test
    fun `stale todo markers are counted`() {
        val code = """
            fun run() {
                // TODO: 后续优化
                // FIXME: 临时方案
                stepOne()
            }
        """.trimIndent()
        val findings = CodeReviewHeuristics.commentFindings(code)
        assertTrue(findings.any { it.ruleId == "heuristic.stale-markers" })
    }

    @Test
    fun `commented out code blocks are flagged`() {
        val code = """
            fun run() {
                // val old = compute()
                // if (old > 0) {
                //     print(old)
                // }
                stepOne()
            }
        """.trimIndent()
        val findings = CodeReviewHeuristics.commentFindings(code)
        assertTrue(findings.any { it.ruleId == "heuristic.commented-code" })
    }

    @Test
    fun `zero comment file is flagged`() {
        val code = (1..25).joinToString("\n") { "val line$it = $it" }
        val findings = CodeReviewHeuristics.commentFindings(code)
        assertTrue(findings.any { it.ruleId == "heuristic.no-comments" })
    }

    // ═══ 跨函数坏味道 ═══

    @Test
    fun `magic numbers repeated are flagged`() {
        val code = """
            fun a() = 3600 + 1
            fun b() = 3600 + 2
            fun c() = 3600 + 3
        """.trimIndent()
        val spans = CodeReviewTemplateBehavior().findFunctions(code, "kotlin")
        val findings = CodeReviewHeuristics.structuralSmellFindings(code, spans)
        assertTrue(findings.any { it.ruleId == "heuristic.magic-number" })
    }

    @Test
    fun `god file tendency is flagged`() {
        val code = (1..22).joinToString("\n") { "fun helper$it(x: Int) = x + $it" }
        val spans = CodeReviewTemplateBehavior().findFunctions(code, "kotlin")
        val findings = CodeReviewHeuristics.structuralSmellFindings(code, spans)
        assertTrue(findings.any { it.ruleId == "heuristic.god-file" })
    }

    @Test
    fun `small clean file has no structural smells`() {
        val code = """
            fun add(a: Int, b: Int): Int {
                return a + b
            }
        """.trimIndent()
        val spans = CodeReviewTemplateBehavior().findFunctions(code, "kotlin")
        assertTrue(CodeReviewHeuristics.structuralSmellFindings(code, spans).isEmpty())
    }

    // ═══ 层次纪律 ═══

    @Test
    fun `ui class with dao access is flagged`() {
        val code = """
            class OrderActivity {
                fun load() {
                    val cursor = dao.rawQuery("SELECT * FROM orders")
                }
            }
        """.trimIndent()
        val findings = CodeReviewHeuristics.layeringFindings(code)
        assertTrue(findings.any { it.ruleId == "heuristic.ui-storage-coupling" })
    }

    @Test
    fun `pure logic with android import is flagged`() {
        val code = """
            import android.util.Log

            fun compute(x: Int): Int {
                Log.d("tag", "compute")
                return x * 2
            }
        """.trimIndent()
        val findings = CodeReviewHeuristics.layeringFindings(code)
        assertTrue(findings.any { it.ruleId == "heuristic.framework-in-logic" })
    }

    @Test
    fun `pure kotlin logic passes layering check`() {
        val code = """
            fun compute(x: Int): Int {
                return x * 2
            }
        """.trimIndent()
        assertTrue(CodeReviewHeuristics.layeringFindings(code).isEmpty())
    }

    // ═══ 聚合入口 ═══

    @Test
    fun `all findings are capped and categorized`() {
        val code = buildString {
            appendLine("class OrderActivity {")
            appendLine("    fun load() { dao.rawQuery(\"SELECT * FROM orders\") }")
            appendLine("}")
            repeat(22) { appendLine("fun helper$it(a: Int) = 3600 + $it") }
        }
        val spans = CodeReviewTemplateBehavior().findFunctions(code, "kotlin")
        val findings = CodeReviewHeuristics.allFindings(code, "kotlin", spans)
        assertTrue(findings.isNotEmpty())
        assertTrue(findings.size <= 10)
        assertTrue(findings.all { it.severity.weight < 3 }) // 启发器不产 BLOCKER
    }
}
