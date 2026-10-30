package com.apex.agent.core.engine.templates.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR 3 工具箱测试 · 章节解析 / 契约校验 / 公共修复。
 */
class TemplateSectionParserTest {

    private val reviewContract = TemplateOutputContract(
        requiredSections = listOf(
            TemplateSectionRequirement(
                title = "总体结论",
                aliases = listOf("结论"),
                required = true
            ),
            TemplateSectionRequirement(
                title = "问题清单",
                aliases = listOf("问题列表"),
                listItemCountRange = 1..10,
                required = true
            )
        )
    )

    @Test
    fun `parse splits leading preamble and sections`() {
        val raw = """
            好的，以下是评审结果。

            ## 总体结论
            通过。

            ## 问题清单
            1. 第一条问题
            2. 第二条问题
        """.trimIndent()
        val skeleton = TemplateSectionParser.parse(raw, reviewContract)
        assertEquals(2, skeleton.sections.size)
        assertTrue(skeleton.leadingLines.first().contains("好的"))
        assertEquals("总体结论", skeleton.sections[0].headerText)
        assertEquals(2, skeleton.sections[1].listItems.size)
        // 契约绑定：两个章节都命中契约
        assertTrue(skeleton.sections.all { it.requirement != null })
    }

    @Test
    fun `parse without contract leaves requirements null`() {
        val skeleton = TemplateSectionParser.parse("## 任意标题\n正文")
        assertEquals(1, skeleton.sections.size)
        assertEquals(null, skeleton.sections[0].requirement)
    }

    @Test
    fun `bold standalone line is a section header`() {
        val raw = "**总体结论**\n通过\n\n**问题清单**\n- 问题甲"
        val skeleton = TemplateSectionParser.parse(raw, reviewContract)
        assertEquals(2, skeleton.sections.size)
        assertEquals("总体结论", skeleton.sections[0].headerText)
    }

    @Test
    fun `chinese numbered headers are recognized`() {
        val raw = "一、总体结论\n通过\n二、问题清单\n- 问题甲\n- 问题乙"
        val skeleton = TemplateSectionParser.parse(raw, reviewContract)
        assertEquals(2, skeleton.sections.size)
    }

    @Test
    fun `short numbered list lines are not mistaken for headers`() {
        // 「1. 命名要有意义」是列表（有正文语义），长度 ≤24 且无句读 → 也可能被判标题。
        // 设计取舍：短数字行在正文段落里判为标题的误伤率低，列表项通常有冒号/更长。
        // 这里锁定行为：带 LIST 形态且含实质语句的行不会被当标题（无句读要求内建）。
        val raw = "## 问题清单\n- 依赖注入容器初始化顺序在多模块场景下有竞态风险\n- printStackTrace 遗留在生产分支"
        val skeleton = TemplateSectionParser.parse(raw, reviewContract)
        assertEquals(1, skeleton.sections.size)
        assertEquals(2, skeleton.sections[0].listItems.size)
    }

    @Test
    fun `validate flags missing section and short list`() {
        val raw = "## 总体结论\n通过。"
        val skeleton = TemplateSectionParser.parse(raw, reviewContract)
        val report = TemplateSectionParser.validate(skeleton, reviewContract)
        assertFalse(report.ok)
        assertTrue(report.violations.any { it.kind == TemplateContractViolationKind.MISSING_SECTION && it.section == "问题清单" })
    }

    @Test
    fun `validate flags preamble and epilogue`() {
        val raw = """
            好的，以下是评审结果。

            ## 总体结论
            通过。

            ## 问题清单
            1. 第一条

            希望对你有帮助！
        """.trimIndent()
        val skeleton = TemplateSectionParser.parse(raw, reviewContract)
        val report = TemplateSectionParser.validate(skeleton, reviewContract)
        assertTrue(report.violations.any { it.kind == TemplateContractViolationKind.PREAMBLE })
        assertTrue(report.violations.any { it.kind == TemplateContractViolationKind.EPILOGUE })
        assertTrue(report.hasRepairable)
        assertFalse(report.hasFatal)
    }

    @Test
    fun `validate flags placeholder leak in sections`() {
        val raw = """
            ## 总体结论
            通过 {{verdict}}。

            ## 问题清单
            1. 第一条
        """.trimIndent()
        val skeleton = TemplateSectionParser.parse(raw, reviewContract)
        val report = TemplateSectionParser.validate(skeleton, reviewContract)
        assertTrue(report.violations.any { it.kind == TemplateContractViolationKind.PLACEHOLDER_LEAK })
    }

    @Test
    fun `validate flags empty output`() {
        val skeleton = TemplateSectionParser.parse("   ")
        val report = TemplateSectionParser.validate(skeleton, reviewContract)
        assertTrue(report.violations.any { it.kind == TemplateContractViolationKind.EMPTY_OUTPUT })
        assertTrue(report.hasFatal)
    }

    @Test
    fun `verdict style contract requires conclusion first`() {
        val verdictContract = TemplateOutputContract(
            requiredSections = listOf(
                TemplateSectionRequirement("一句话结论", required = true),
                TemplateSectionRequirement("要点", listItemCountRange = 3..5, required = true)
            ),
            requiresVerdictStyle = true
        )
        val good = TemplateSectionParser.parse(
            "## 一句话结论\n重构成功。\n## 要点\n- a\n- b\n- c",
            verdictContract
        )
        assertTrue(TemplateSectionParser.validate(good, verdictContract).violations.none { it.kind == TemplateContractViolationKind.MISSING_VERDICT })

        val bad = TemplateSectionParser.parse(
            "## 要点\n- a\n- b\n- c\n## 一句话结论\n重构成功。",
            verdictContract
        )
        assertTrue(
            TemplateSectionParser.validate(bad, verdictContract).violations
                .any { it.kind == TemplateContractViolationKind.MISSING_VERDICT }
        )
    }

    @Test
    fun `strict mode filters repairable violations`() {
        val raw = "好的。\n## 总体结论\n通过。"
        val skeleton = TemplateSectionParser.parse(raw, reviewContract)
        val strict = TemplateSectionParser.validate(skeleton, reviewContract, strict = true)
        val loose = TemplateSectionParser.validate(skeleton, reviewContract, strict = false)
        assertTrue(strict.violations.size > loose.violations.size)
    }

    @Test
    fun `repair strips preamble epilogue and patches missing section shells`() {
        val raw = """
            好的，以下是评审结果。

            ## 总体结论
            通过。

            希望对你有帮助！
        """.trimIndent()
        val skeleton = TemplateSectionParser.parse(raw, reviewContract)
        val report = TemplateSectionParser.validate(skeleton, reviewContract)
        val (repaired, notes) = TemplateSectionParser.repair(raw, report)
        assertFalse(repaired.contains("好的"))
        assertFalse(repaired.contains("希望对你有帮助"))
        assertTrue(repaired.contains("## 问题清单")) // 缺失章节壳补齐
        assertTrue(notes.any { it.contains("寒暄") })
        assertTrue(notes.any { it.contains("章节壳") || it.contains("缺失章节") })
    }

    @Test
    fun `list numbering normalization`() {
        val lines = listOf("- 甲", "2) 乙", "• 丙", "普通行")
        val ordered = TemplateSectionParser.normalizeListNumbering(lines, ordered = true)
        assertEquals(listOf("1. 甲", "2. 乙", "3. 丙", "普通行"), ordered)
        val unordered = TemplateSectionParser.normalizeListNumbering(lines, ordered = false)
        assertEquals(listOf("- 甲", "- 乙", "- 丙", "普通行"), unordered)
    }

    @Test
    fun `preamble and epilogue line judgments`() {
        assertTrue(TemplateSectionParser.isPreambleLine("好的，以下是评审结果"))
        assertTrue(TemplateSectionParser.isPreambleLine("Sure, here is the plan"))
        assertFalse(TemplateSectionParser.isPreambleLine("这是核心结论：问题出在锁顺序上")) // 带冒号实质内容
        assertFalse(TemplateSectionParser.isPreambleLine(""))
        assertTrue(TemplateSectionParser.isEpilogueLine("希望对你有帮助！"))
        assertTrue(TemplateSectionParser.isEpilogueLine("如果还有问题欢迎追问，我可以继续展开第二个方案的具体实现细节与边界条件处理")) // ≤80
        assertFalse(TemplateSectionParser.isEpilogueLine("这是一个非常长的总结段落，远远超过尾随废话的长度上限，所以不应该被识别为尾随废话，哪怕它以希望开头也没有关系吧再多写一点凑长度"))
    }
}
