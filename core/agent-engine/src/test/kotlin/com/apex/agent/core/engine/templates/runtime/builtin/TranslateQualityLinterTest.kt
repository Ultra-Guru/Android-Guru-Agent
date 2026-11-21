package com.apex.agent.core.engine.templates.runtime.builtin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR 17 测试 · 翻译质量质检器。
 */
class TranslateQualityLinterTest {

    // ═══ 数字保真 ═══

    @Test
    fun `number fidelity detects missing numbers`() {
        val source = "服务延迟从 200ms 降到 50ms，吞吐提升 3.5 倍，错误率 0.02%。"
        val good = "Latency drops from 200ms to 50ms, throughput up 3.5x, error rate 0.02%."
        val bad = "Latency drops a lot and errors are rare."
        assertTrue(TranslateQualityLinter.checkNumberFidelity(source, good).isEmpty())
        val issues = TranslateQualityLinter.checkNumberFidelity(source, bad)
        assertEquals(1, issues.size)
        assertEquals("lint.number-missing", issues.first().ruleId)
        assertTrue(issues.first().evidence.contains("200"))
    }

    @Test
    fun `number extraction handles versions dates and ranges`() {
        val numbers = TranslateQualityLinter.extractNumbers(
            "升级到 v2.10.3，日期 2026-10-31，区间 10-20，单独 7"
        )
        assertTrue(numbers.contains("2.10.3"))
        assertTrue(numbers.contains("2026-10"))
        assertTrue(numbers.contains("31"))
        assertTrue(numbers.contains("10-20"))
        assertTrue(numbers.contains("7"))
    }

    @Test
    fun `number extraction normalizes fullwidth digits`() {
        val numbers = TranslateQualityLinter.extractNumbers("温度是３５度")
        assertTrue(numbers.contains("35"))
    }

    @Test
    fun `code blocks are exempt from number fidelity`() {
        val source = "配置 `timeout=30` 秒，重试 3 次。"
        val translation = "Config `timeout=30` seconds, retry 3 times."
        assertTrue(TranslateQualityLinter.checkNumberFidelity(source, translation).isEmpty())
    }

    // ═══ 标点宽度 ═══

    @Test
    fun `fullwidth target flags halfwidth stops near cjk`() {
        val translation = "这是一个测试,标点半角了;还有冒号:以及问号?"
        val issues = TranslateQualityLinter.checkPunctuationWidth(translation, "zh")
        assertTrue(issues.any { it.ruleId == "lint.width-half-in-cjk" })
        assertTrue(issues.all { it.repairable })
    }

    @Test
    fun `halfwidth target flags fullwidth stops`() {
        val translation = "This is fine，but this is not；mixed!"
        val issues = TranslateQualityLinter.checkPunctuationWidth(translation, "en")
        assertTrue(issues.any { it.ruleId == "lint.width-full-in-latin" })
    }

    @Test
    fun `korean target is tolerant to width`() {
        val translation = "이것은, 테스트입니다. 반각과 전각，혼용."
        assertTrue(TranslateQualityLinter.checkPunctuationWidth(translation, "ko").isEmpty())
    }

    @Test
    fun `mixed width comma is reported`() {
        val text = "第一段，second part, 第三段。"
        val issues = TranslateQualityLinter.checkWidthConsistency(text)
        assertTrue(issues.any { it.ruleId == "lint.width-mixed-comma" })
    }

    // ═══ 括号配对 ═══

    @Test
    fun `unpaired brackets are critical`() {
        val issues = TranslateQualityLinter.checkBracketPairing("打开（括号没有关")
        assertEquals(1, issues.size)
        assertEquals(TranslateLintSeverity.CRITICAL, issues.first().severity)
    }

    @Test
    fun `paired brackets pass`() {
        assertTrue(
            TranslateQualityLinter.checkBracketPairing("配对（成功）且【成功】且“成功”").isEmpty()
        )
    }

    @Test
    fun `odd straight quotes are reported`() {
        val issues = TranslateQualityLinter.checkBracketPairing("he said \"hello")
        assertTrue(issues.any { it.ruleId == "lint.pair-straight-quote" })
    }

    @Test
    fun `code fences are exempt from pairing`() {
        assertTrue(
            TranslateQualityLinter.checkBracketPairing("正文（ok）\n```\nif (a { b)\n```\n结尾。").isEmpty()
        )
    }

    // ═══ 段落对齐 ═══

    @Test
    fun `paragraph drift is detected`() {
        val source = "第一段。\n\n第二段。\n\n第三段。"
        val translation = "Paragraph one.\n\nParagraph two merged with three."
        val issues = TranslateQualityLinter.checkParagraphAlignment(source, translation)
        assertEquals(1, issues.size)
        assertEquals("lint.paragraph-drift", issues.first().ruleId)
    }

    @Test
    fun `aligned paragraphs pass`() {
        val source = "第一段。\n\n第二段。"
        val translation = "Para one.\n\nPara two."
        assertTrue(TranslateQualityLinter.checkParagraphAlignment(source, translation).isEmpty())
    }

    // ═══ 长度比 ═══

    @Test
    fun `zh to en inflation band is respected`() {
        val source = "这是一段需要足够长度才能触发长度比检查的中文原文，确保超过四十个字符的门槛，这样比例检查才会真正生效并且不会因为样本太小而误报。"
        val translation = "This is a reasonably long English translation that corresponds to the Chinese source text above, " +
            "and it stays within the expected expansion ratio band."
        assertTrue(TranslateQualityLinter.checkLengthRatio(source, translation, "zh", "en") == null)
    }

    @Test
    fun `collapsed translation is flagged`() {
        val source = "这是一段非常长的中文原文内容，讲述了一个复杂的故事：从前有一座山，山里有一座庙，庙里有一个老和尚和一个小和尚，" +
            "老和尚给小和尚讲故事，故事讲的是从前有一座山，如此循环往复，周而复始，永无止境，这是一个很长的故事。"
        val translation = "A story."
        val issue = TranslateQualityLinter.checkLengthRatio(source, translation, "zh", "en")
        assertNotNull(issue)
        assertEquals("lint.length-collapsed", issue!!.ruleId)
    }

    @Test
    fun `short inputs skip ratio check`() {
        val issue = TranslateQualityLinter.checkLengthRatio("短", "A very different long text here", "zh", "en")
        assertEquals(null, issue)
    }

    // ═══ 漏译探测 ═══

    @Test
    fun `cjk runs in latin output are flagged`() {
        val translation = "The service is stable. 但这一段中文没有被翻译，直接漏译了。 Overall fine."
        val issues = TranslateQualityLinter.detectUntranslatedRuns(translation, "en")
        assertTrue(issues.isNotEmpty())
        assertEquals(TranslateLintSeverity.CRITICAL, issues.first().severity)
    }

    @Test
    fun `proper nouns in zh output are tolerated`() {
        val translation = "我们支持 Android 15 与 Kotlin 2.0 的组合，性能提升明显。"
        assertTrue(TranslateQualityLinter.detectUntranslatedRuns(translation, "zh").isEmpty())
    }

    @Test
    fun `latin sentence runs in zh output are flagged`() {
        val translation = "本段已翻译。But this whole sentence is left untranslated and it is long enough to trigger."
        val issues = TranslateQualityLinter.detectUntranslatedRuns(translation, "zh")
        assertTrue(issues.isNotEmpty())
    }

    // ═══ 空白卫生 ═══

    @Test
    fun `trailing whitespace and blank runs are repairable`() {
        val issues = TranslateQualityLinter.checkWhitespace("行尾空白   \n\n\n\n正文")
        assertTrue(issues.any { it.ruleId == "lint.whitespace-trailing" })
        assertTrue(issues.any { it.ruleId == "lint.whitespace-blank-run" })
        assertTrue(issues.all { it.repairable })
    }

    // ═══ 修复 ═══

    @Test
    fun `repair widens punctuation for zh target`() {
        val (repaired, notes) = TranslateQualityLinter.repair(
            "这是译文,标点该全角;确认无误。代码 `keep(1)` 保留。",
            "zh"
        )
        assertTrue(repaired.contains("译文，"))
        assertTrue(repaired.contains("全角；"))
        // 代码内容逐字保留
        assertTrue(repaired.contains("keep(1)"))
        assertTrue(notes.isNotEmpty())
    }

    @Test
    fun `repair narrows punctuation for en target`() {
        val (repaired, _) = TranslateQualityLinter.repair(
            "This is fine，but this，is not；ok？",
            "en"
        )
        assertFalse(repaired.contains("，"))
        assertFalse(repaired.contains("；"))
        assertTrue(repaired.contains("?"))
    }

    @Test
    fun `repair preserves fenced code verbatim`() {
        val input = "正文，说明。\n\n```\nx = 1,y = 2\n```\n"
        val (repaired, _) = TranslateQualityLinter.repair(input, "zh")
        assertTrue(repaired.contains("x = 1,y = 2"))
    }

    @Test
    fun `repair collapses blank runs and trims`() {
        val (repaired, _) = TranslateQualityLinter.repair("  开头\n\n\n\n结尾  \n", "zh")
        assertEquals("开头\n\n结尾", repaired)
    }

    // ═══ 报告聚合 ═══

    @Test
    fun `lint report aggregates score and aspects`() {
        val report = TranslateQualityLinter.lintTranslation(
            source = "原文（配对）有 3 个数字。",
            translation = "译文中没有数字。",
            sourceLang = "zh",
            targetLang = "zh"
        )
        assertTrue(report.issues.isNotEmpty())
        assertTrue(report.score < 100)
        assertTrue(report.checkedAspects.contains(TranslateLintAspect.NUMBER_FIDELITY))
    }

    @Test
    fun `empty translation is critical with zero score`() {
        val report = TranslateQualityLinter.lintTranslation("原文", "", "zh", "en")
        assertTrue(report.hasCritical)
        assertEquals(0, report.score)
    }

    @Test
    fun `clean translation scores full marks`() {
        val source = "今天天气不错，我们去公园散步，顺便买了两杯咖啡，花费 50 元，心情很好，这是一个完美的下午，大家都非常开心，最后步行回家休息。"
        val translation = "今天天气不错，我们去公园散步，顺便买了两杯咖啡，花费 50 元，心情很好，这是一个完美的下午，大家都非常开心，最后步行回家休息。"
        val report = TranslateQualityLinter.lintTranslation(source, translation, "zh", "zh")
        assertEquals(100, report.score)
    }

    @Test
    fun `source lint reports source side issues`() {
        val report = TranslateQualityLinter.lintSource("原文（有括号没关")
        assertTrue(report.issues.any { it.aspect == TranslateLintAspect.BRACKET_PAIRING })
    }

    @Test
    fun `rule id catalog is stable`() {
        assertTrue(TranslateQualityLinter.RULE_IDS.contains("lint.number-missing"))
        assertTrue(TranslateQualityLinter.RULE_IDS.contains("lint.untranslated-zh-in-latin"))
        assertEquals(17, TranslateQualityLinter.RULE_IDS.size)
    }
}
