package com.apex.agent.core.engine.templates.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR 3 工具箱测试 · 文本度量。
 */
class TemplateTextMetricsTest {

    @Test
    fun `blank text yields empty metrics`() {
        val result = TemplateTextMetrics.measure("   \n  ")
        assertEquals(0, result.charCount)
        assertEquals(0, result.sentenceCount)
        assertEquals(0, result.topTerms.size)
        assertEquals(0, result.estimatedReadingMinutes)
    }

    @Test
    fun `chinese sentence splitting respects cjk punctuation`() {
        val sentences = TemplateTextMetrics.splitSentences("第一句话。第二句话！第三句？")
        assertEquals(3, sentences.size)
        assertEquals("第一句话。", sentences[0].text)
        assertEquals("第二句话！", sentences[1].text)
        assertEquals("第三句？", sentences[2].text)
        assertEquals(0, sentences[0].ordinal)
        assertEquals(2, sentences[2].ordinal)
    }

    @Test
    fun `newline is a sentence boundary for latin text`() {
        val sentences = TemplateTextMetrics.splitSentences("First item\nSecond item\nThird item")
        assertEquals(3, sentences.size)
    }

    @Test
    fun `mixed script counts both census`() {
        // CJK：协程(2) + 是基类(3) = 5；拉丁词：Kotlin + CoroutineScope = 2
        val metrics = TemplateTextMetrics.measure("Kotlin 协程 CoroutineScope 是基类")
        assertTrue(metrics.cjkCharCount >= 5)
        assertTrue(metrics.latinWordCount >= 2)
        assertTrue(metrics.charCount > 10)
    }

    @Test
    fun `top terms filter stopwords and rank by frequency`() {
        val text = """
            系统架构分为三层。数据层负责存储与检索。
            业务层承担编排。业务层还负责重试。数据层支持冷热分离。
            架构评审关注数据层与业务层边界。
        """.trimIndent()
        val metrics = TemplateTextMetrics.measure(text)
        val top = metrics.topTerms
        assertTrue(top.isNotEmpty())
        // 高频双字词（数据层/业务层）必须进 top3，且次数递减
        val topTermsText = top.take(3).joinToString(",") { it.term }
        assertTrue("数据层" in topTermsText || "业务层" in topTermsText)
        assertTrue(top[0].count >= top[1].count)
        // 停用词（的/了/是）不出现
        assertTrue(top.none { it.term == "的" || it.term == "是" })
    }

    @Test
    fun `stopword detection covers zh and en`() {
        assertTrue(TemplateTextMetrics.isStopword("的"))
        assertTrue(TemplateTextMetrics.isStopword("The"))
        assertFalse(TemplateTextMetrics.isStopword("协程"))
        assertFalse(TemplateTextMetrics.isStopword("coroutine"))
        assertTrue(TemplateTextMetrics.isStopword(""))
    }

    @Test
    fun `rank sentences boosts first sentence and numeric facts`() {
        // 设计让「缓存层/重构」成为仅有的重复词条（各 3 次），确保
        // 首句（位置分）与数值句（位置 + 事实分）稳定压过稀释句。
        val text = """
            核心结论：缓存层重构收益最大。
            缓存层重构后命中率显著提升。
            中间是一些普通描述内容，用来稀释关键词密度，让打分差异更明显。
            缓存层重构使接口延迟从 320ms 降到 45ms。
        """.trimIndent()
        val scores = TemplateTextMetrics.rankSentences(text)
        assertTrue(scores.size >= 4)
        val first = scores.first { it.sentence.ordinal == 0 }
        val numeric = scores.first { it.sentence.text.contains("320ms") }
        val plain = scores.first { it.sentence.text.contains("稀释") }
        assertTrue(first.score > plain.score)
        assertTrue(numeric.score > plain.score)
        assertTrue(numeric.rationale.contains("含数值"))
    }

    @Test
    fun `reading minutes grow with units`() {
        val short = TemplateTextMetrics.measure("短文本")
        assertEquals(1, short.estimatedReadingMinutes)
        val long = TemplateTextMetrics.measure("字".repeat(900))
        assertEquals(3, long.estimatedReadingMinutes)
    }
}
