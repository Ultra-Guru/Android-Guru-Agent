package com.apex.agent.core.engine.templates.runtime.builtin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR 17 测试 · 总结结构构建器。
 */
class SummarizeStructureBuilderTest {

    // ═══ 句子切分 ═══

    @Test
    fun `sentence split handles chinese terminators`() {
        val sentences = SummarizeStructureBuilder.splitSentences(
            "第一句话。第二句话！第三句？第四句；"
        )
        assertEquals(4, sentences.size)
        assertEquals("第一句话。", sentences[0].text)
        assertTrue(sentences[3].text.endsWith("；"))
    }

    @Test
    fun `sentence split keeps decimals and abbreviations intact`() {
        val sentences = SummarizeStructureBuilder.splitSentences(
            "Version 2.10.3 was released. See e.g. the notes. It works."
        )
        // 2.10.3 不切开，e.g. 不切开
        assertTrue(sentences.first().text.contains("2.10.3"))
        assertEquals(3, sentences.size)
    }

    @Test
    fun `paragraph ordinals advance on blank lines`() {
        val sentences = SummarizeStructureBuilder.splitSentences(
            "第一段句一。第一段句二。\n\n第二段句一。"
        )
        assertEquals(0, sentences[0].paragraphOrdinal)
        assertEquals(0, sentences[1].paragraphOrdinal)
        assertEquals(1, sentences[2].paragraphOrdinal)
    }

    @Test
    fun `negation detection marks sentences`() {
        val sentences = SummarizeStructureBuilder.splitSentences(
            "此方案不支持离线模式。支持在线模式。"
        )
        assertTrue(sentences[0].hasNegation)
        assertFalse(sentences[1].hasNegation)
    }

    @Test
    fun `blank input yields no sentences`() {
        assertTrue(SummarizeStructureBuilder.splitSentences("   ").isEmpty())
    }

    // ═══ 术语显著度 ═══

    @Test
    fun `term salience ranks frequent terms first`() {
        val terms = SummarizeStructureBuilder.termSalience(
            "内存优化提升了性能，内存占用下降。性能与内存再次提升。"
        )
        assertTrue(terms.isNotEmpty())
        assertTrue(terms.first().occurrences >= terms.last().occurrences)
        assertTrue(terms.take(3).any { it.term.contains("内存") })
    }

    @Test
    fun `heading terms get boosted`() {
        val terms = SummarizeStructureBuilder.termSalience(
            "正文提到内存一次，又提到内存。",
            headingLines = listOf("内存优化方案")
        )
        val memoryTerm = terms.firstOrNull { it.term.contains("内存") }
        assertTrue(memoryTerm != null && memoryTerm.fromHeading)
    }

    @Test
    fun `stopwords are excluded`() {
        val terms = SummarizeStructureBuilder.termSalience("我们 我们 我们 可以 可以")
        // 「我们」是停用词，不应出现在显著术语里
        assertTrue(terms.none { it.term == "我们" })
    }

    @Test
    fun `english stopwords are excluded`() {
        val terms = SummarizeStructureBuilder.termSalience("the the the and and system system")
        assertTrue(terms.none { it.term == "the" })
        assertTrue(terms.any { it.term == "system" })
    }

    // ═══ 句子打分 ═══

    @Test
    fun `scoring favors early sentences with salient terms`() {
        val content = "内存优化是本次更新的核心。内存占用下降了百分之四十。" +
            "还有一些无关紧要的琐碎细节可以忽略不谈的废话内容填充。"
        val terms = SummarizeStructureBuilder.termSalience(content)
        val scored = SummarizeStructureBuilder.scoreSentences(
            SummarizeStructureBuilder.splitSentences(content), terms
        )
        assertTrue(scored.first().score > 0)
        assertTrue(scored.all { it.rationale.isNotBlank() })
    }

    // ═══ 冗余聚类 ═══

    @Test
    fun `similar sentences are clustered`() {
        val sentences = SummarizeStructureBuilder.splitSentences(
            "本次更新优化了内存占用。本次更新优化了内存占用量。完全不同的另一句话讲别的事情。"
        )
        val clusters = SummarizeStructureBuilder.clusterRedundancy(sentences)
        assertEquals(1, clusters.size)
        assertEquals(2, clusters.first().memberOrdinals.size)
        assertTrue(clusters.first().similarity >= SummarizeStructureBuilder.REDUNDANCY_THRESHOLD)
    }

    @Test
    fun `distinct sentences are not clustered`() {
        val sentences = SummarizeStructureBuilder.splitSentences(
            "今天天气很好。服务器负载升高了。用户提交了新的工单。"
        )
        assertTrue(SummarizeStructureBuilder.clusterRedundancy(sentences).isEmpty())
    }

    @Test
    fun `similarity of identical text is one`() {
        assertTrue(
            SummarizeStructureBuilder.similarity("完全相同的句子", "完全相同的句子") > 0.99
        )
        assertEquals(0.0, SummarizeStructureBuilder.similarity("甲乙丙丁", "子丑寅卯"), 0.01)
    }

    // ═══ 预算分配 ═══

    @Test
    fun `budget allocation splits verdict and bullets`() {
        val budget = SummarizeStructureBuilder.allocateBudget(
            LengthBudget(0, 200, "test", true), candidateCount = 10
        )
        assertEquals(5, budget.bulletCount)
        assertTrue(budget.verdictChars >= 12)
        assertTrue(budget.perBulletChars >= 10)
        assertTrue(budget.verdictChars + budget.bulletCount * budget.perBulletChars <= 200 + 5)
    }

    @Test
    fun `budget allocation clamps to three bullets minimum`() {
        val budget = SummarizeStructureBuilder.allocateBudget(
            LengthBudget(0, 200, "test", true), candidateCount = 1
        )
        assertEquals(3, budget.bulletCount)
    }

    @Test
    fun `zero budget falls back to default`() {
        val budget = SummarizeStructureBuilder.allocateBudget(
            LengthBudget(0, 0, "test", true), candidateCount = 4
        )
        assertEquals(200, budget.totalChars)
    }

    // ═══ 大纲组装 ═══

    @Test
    fun `outline builds usable bullets from content`() {
        val content = """
            智能手机市场在 2026 年第三季度增长 12%。
            其中折叠屏机型贡献了 35% 的增量。
            供应链方面，芯片交期缩短到 8 周。
            软件生态同步扩张，应用数量突破 300 万。
            不过整体利润率下降了 2 个百分点。
            市场竞争进一步加剧，头部厂商集中度提升。
            新兴市场成为主要增长引擎，占比 40%。
        """.trimIndent()
        val outline = SummarizeStructureBuilder.buildOutline(
            content, LengthBudget(0, 200, "test", true)
        )
        assertTrue(outline.isUsable)
        assertTrue(outline.bullets.size in 3..5)
        assertTrue(outline.bullets.all { it.draftText.length <= it.charBudget + 1 })
        assertTrue(outline.coverageEstimate > 0.0)
        assertTrue(outline.salientTerms.isNotEmpty())
    }

    @Test
    fun `outline on blank content degrades gracefully`() {
        val outline = SummarizeStructureBuilder.buildOutline(
            "", LengthBudget(0, 200, "test", true)
        )
        assertFalse(outline.isUsable)
        assertTrue(outline.bullets.isEmpty())
    }

    @Test
    fun `single sentence content still produces outline`() {
        val outline = SummarizeStructureBuilder.buildOutline(
            "只有一句话的内容讲了增长 20% 的事情。",
            LengthBudget(0, 200, "test", true)
        )
        assertTrue(outline.bullets.isNotEmpty())
    }

    // ═══ 回包校验 ═══

    @Test
    fun `overlapping bullets are detected`() {
        val overlaps = SummarizeStructureBuilder.checkBulletOverlap(
            listOf(
                "内存占用下降了 40%，性能显著提升",
                "内存占用下降了 40%，性能有显著提升",
                "服务器负载保持稳定"
            )
        )
        assertEquals(1, overlaps.size)
        assertEquals(0, overlaps.first().firstIndex)
        assertEquals(1, overlaps.first().secondIndex)
        assertTrue(overlaps.first().similarity >= SummarizeStructureBuilder.BULLET_OVERLAP_THRESHOLD)
    }

    @Test
    fun `distinct bullets pass overlap check`() {
        val overlaps = SummarizeStructureBuilder.checkBulletOverlap(
            listOf("内存占用下降", "芯片交期缩短", "应用数量增长")
        )
        assertTrue(overlaps.isEmpty())
    }

    @Test
    fun `bullet budget check reports overruns`() {
        val budget = BulletBudget(totalChars = 100, verdictChars = 25, bulletCount = 3, perBulletChars = 25)
        val overruns = SummarizeStructureBuilder.checkBulletBudget(
            listOf("短句", "这一条要点写得特别长特别长特别长特别长特别长特别长特别长特别长特别长"),
            budget
        )
        assertEquals(1, overruns.size)
        assertEquals(1, overruns.first().first)
    }

    // ═══ 回包修复 ═══

    @Test
    fun `trim bullet cuts at sentence boundary`() {
        val trimmed = SummarizeStructureBuilder.trimBullet(
            "第一句讲内存。第二句讲性能。第三句讲稳定性。",
            maxChars = 12
        )
        assertTrue(trimmed.length <= 14)
        assertTrue(trimmed.contains("内存"))
        assertTrue(trimmed.endsWith("…"))
    }

    @Test
    fun `trim bullet keeps short text intact`() {
        assertEquals(
            "短要点",
            SummarizeStructureBuilder.trimBullet("短要点", maxChars = 20)
        )
    }

    @Test
    fun `trim bullet protects negation semantics`() {
        val trimmed = SummarizeStructureBuilder.trimBullet(
            "该功能不支持离线模式，且没有回退方案，用户必须联网使用，另外还有更多细节展开说明。",
            maxChars = 16
        )
        assertTrue(trimmed.contains("不"))
    }

    @Test
    fun `repair bullets trims each entry`() {
        val budget = BulletBudget(totalChars = 100, verdictChars = 25, bulletCount = 3, perBulletChars = 20)
        val repaired = SummarizeStructureBuilder.repairBullets(
            listOf("一条正好长度的要点", "一条特别特别特别特别特别特别特别特别长的要点需要被裁剪掉尾部"),
            budget
        )
        assertEquals(2, repaired.size)
        assertTrue(repaired[1].length <= 22)
    }
}
