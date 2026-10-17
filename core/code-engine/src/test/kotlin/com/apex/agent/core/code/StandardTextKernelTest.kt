package com.apex.agent.core.code

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * StandardTextKernel.PureKotlin 参考实现测试。
 *
 * 语义同时是 native 侧（apex::codenative）的镜像契约——host 侧 C++ 单测
 * 与本组用例对同一输入断言一致值。
 */
class StandardTextKernelTest {

    private val kernel: StandardTextKernel = StandardTextKernel.PureKotlin

    // ═══ estimateTokens ═══

    @Test
    fun `estimate tokens empty and pure ascii`() {
        assertEquals(0, kernel.estimateTokens(""))
        assertEquals(1, kernel.estimateTokens("abcd"))
        assertEquals(2, kernel.estimateTokens("abcdefgh"))
    }

    @Test
    fun `estimate tokens cjk weighting`() {
        // 4 个 CJK → 4*3/2 = 6
        assertEquals(6, kernel.estimateTokens("中文计数"))
    }

    @Test
    fun `estimate tokens stable across lengths`() {
        // 会话口径一致性的历史断言（原 StandardSession 估算器已随标准线
        // 提取进库）——这里改为锁公式本身：ASCII ~4 chars/token。
        val samples = listOf("hello world", "中文与 English 混排", "a".repeat(999))
        samples.forEach { s ->
            assertEquals(
                (s.count { it.code in 0x2E80..0x9FFF || it.code in 0xFF00..0xFFEF || it.code in 0x3000..0x303F } * 3) / 2 +
                    s.count { it.code !in 0x2E80..0x9FFF && it.code !in 0xFF00..0xFFEF && it.code !in 0x3000..0x303F } / 4,
                kernel.estimateTokens(s)
            )
        }
    }

    // ═══ diffStat ═══

    @Test
    fun `diff stat identical texts`() {
        val d = kernel.diffStat("a\nb\nc", "a\nb\nc")
        assertNotNull(d)
        assertEquals(0, d!!.addedLines)
        assertEquals(0, d.removedLines)
    }

    @Test
    fun `diff stat pure append`() {
        val d = kernel.diffStat("a\nb", "a\nb\nc\nd")!!
        assertEquals(2, d.addedLines)
        assertEquals(0, d.removedLines)
        assertEquals(2, d.changedLines)
    }

    @Test
    fun `diff stat pure removal`() {
        val d = kernel.diffStat("a\nb\nc", "a")!!
        assertEquals(0, d.addedLines)
        assertEquals(2, d.removedLines)
    }

    @Test
    fun `diff stat line rewrite counts one add one remove`() {
        val d = kernel.diffStat("old line\nkeep", "new line\nkeep")!!
        assertEquals(1, d.addedLines)
        assertEquals(1, d.removedLines)
    }

    @Test
    fun `diff stat empty edges`() {
        // 空串按一行空行计（Kotlin lines() 与 C++ SplitLines 同口径）
        val d = kernel.diffStat("", "a\nb")!!
        assertEquals(2, d.addedLines)
        assertEquals(1, d.removedLines)
    }

    // ═══ fuzzyLocate ═══

    @Test
    fun `fuzzy locate exact substring scores one`() {
        val m = kernel.fuzzyLocate("fun main() { println(it) }", "println")!!
        assertEquals(1.0f, m.score)
        assertEquals(13, m.index)
    }

    @Test
    fun `fuzzy locate no reasonable match returns null`() {
        assertNull(kernel.fuzzyLocate("abcdef", "zzzzzzzzzz"))
    }

    @Test
    fun `fuzzy locate whitespace drift still matches`() {
        val m = kernel.fuzzyLocate("fun  main ( ) { }", "fun main()")!!
        assertTrue(m.score >= 0.6f)
    }

    @Test
    fun `fuzzy locate empty inputs return null`() {
        assertNull(kernel.fuzzyLocate("", "abc"))
        assertNull(kernel.fuzzyLocate("abc", ""))
    }

    @Test
    fun `fuzzy match index bounds valid`() {
        val haystack = "val x = computeTotal(order) + tax"
        val needle = "computeTotal(order"
        val m = kernel.fuzzyLocate(haystack, needle)!!
        assertTrue(m.index >= 0 && m.index < haystack.length)
        assertTrue(m.score in 0.6f..1.0f)
    }
}
