package com.apex.agent.core.engine.templates.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR 1 骨架测试 —— 契约/上下文/分析/契约/轮次五组模型的行为锁定。
 *
 * 骨架期没有编排器，测试聚焦：默认值防御、归一化逻辑、排序语义、
 * 违规可修复性分流、报告兜底 —— 这些是十个行为实现共同依赖的地基。
 */
class TemplateRuntimeSkeletonTest {

    // ═══ 清单与阶段 ═══

    @Test
    fun `phase execution order matches declaration order`() {
        assertEquals(
            listOf(
                TemplatePhaseKind.PREPARE,
                TemplatePhaseKind.ANALYZE,
                TemplatePhaseKind.COMPOSE,
                TemplatePhaseKind.VALIDATE,
                TemplatePhaseKind.REPAIR,
                TemplatePhaseKind.RENDER
            ),
            TemplatePhaseKind.EXECUTION_ORDER
        )
    }

    @Test
    fun `manifest supports only declared phases`() {
        val manifest = TemplateBehaviorManifest(
            behaviorId = "builtin_code_review",
            displayName = "代码审查",
            summary = "静态规则 + 结构化评审",
            supportedPhases = setOf(TemplatePhaseKind.PREPARE, TemplatePhaseKind.ANALYZE)
        )
        assertTrue(manifest.supports(TemplatePhaseKind.PREPARE))
        assertTrue(manifest.supports(TemplatePhaseKind.ANALYZE))
        assertFalse(manifest.supports(TemplatePhaseKind.RENDER))
        assertFalse(manifest.deterministicPreAnalysis)
        assertEquals(TemplatePromptEnrichmentMode.APPEND_FINDINGS, manifest.promptEnrichmentMode)
        assertEquals(1, manifest.behaviorVersion)
    }

    @Test
    fun `manifest rejects blank identity`() {
        val error = runCatching {
            TemplateBehaviorManifest(behaviorId = " ", displayName = "x", summary = "", supportedPhases = emptySet())
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        val error2 = runCatching {
            TemplateBehaviorManifest(behaviorId = "x", displayName = "", summary = "", supportedPhases = emptySet())
        }.exceptionOrNull()
        assertTrue(error2 is IllegalArgumentException)
    }

    // ═══ 变量报告 ═══

    @Test
    fun `variable report resolves value source and missing`() {
        val report = TemplateVariableReport(
            listOf(
                TemplateVariableAssignment("code", "val a = 1", TemplateVariableSource.USER),
                TemplateVariableAssignment("language", "未指定", TemplateVariableSource.DEFAULT),
                TemplateVariableAssignment("focus", "", TemplateVariableSource.MISSING)
            )
        )
        assertEquals("val a = 1", report.value("code"))
        assertEquals("未指定", report.value("language"))
        assertNull(report.value("unknown"))
        assertEquals("未指定", report.valueOr("language", "兜底"))
        assertEquals("兜底", report.valueOr("focus", "兜底"))       // 空白值回落
        assertEquals("兜底", report.valueOr("unknown", "兜底"))     // 未声明回落
        assertEquals(listOf("focus"), report.missingNames())
        assertEquals(listOf("code"), report.userProvidedNames())
        assertEquals(TemplateVariableSource.USER, report.source("code"))
        assertNull(report.source("nope"))
        assertTrue(TemplateVariableReport.empty().assignments.isEmpty())
    }

    // ═══ 输入形态 ═══

    @Test
    fun `empty facets are substantial-free and cjk-free`() {
        val facets = TemplateInputFacets.empty()
        assertFalse(facets.hasSubstantialContent)
        assertFalse(facets.dominantScriptIsCjk)
        assertEquals(0, facets.charCount)
    }

    @Test
    fun `cjk dominance follows character census`() {
        val cjk = TemplateInputFacets(
            charCount = 100, lineCount = 4, nonBlankLineCount = 4,
            averageLineLength = 25, longestLineLength = 40,
            looksLikeMarkdown = false, looksLikeDiff = false, looksLikeCode = false,
            looksLikeUrl = false, cjkCharCount = 80, latinWordCount = 5
        )
        val latin = TemplateInputFacets(
            charCount = 100, lineCount = 4, nonBlankLineCount = 4,
            averageLineLength = 25, longestLineLength = 40,
            looksLikeMarkdown = false, looksLikeDiff = false, looksLikeCode = false,
            looksLikeUrl = false, cjkCharCount = 5, latinWordCount = 80
        )
        assertTrue(cjk.dominantScriptIsCjk)
        assertFalse(latin.dominantScriptIsCjk)
        assertTrue(cjk.hasSubstantialContent)
    }

    // ═══ 上下文 ═══

    @Test
    fun `context bridges variables options and logger`() {
        val logs = mutableListOf<String>()
        val context = TemplateExecutionContext(
            template = TemplateTemplateSnapshot(
                id = "builtin_translate",
                name = "专业翻译",
                content = "翻译 {{text}} 到 {{target_language}}",
                declaredVariableNames = listOf("text", "target_language"),
                categoryLabel = "TRANSLATION",
                isBuiltIn = true
            ),
            variables = TemplateVariableReport(
                listOf(TemplateVariableAssignment("text", "你好世界", TemplateVariableSource.USER))
            ),
            options = TemplateRuntimeOptions(maxInputChars = 5, autoRepair = false),
            clock = { 42L },
            logger = { logs.add(it) }
        )
        assertEquals("你好世界", context.variable("text"))
        assertEquals("英文", context.variableOr("target_language", "英文"))
        assertFalse(context.options.autoRepair)
        assertEquals(5, context.options.maxInputChars)
        context.log("hello")
        assertEquals(listOf("TemplateRuntime[builtin_translate]: hello"), logs)
    }

    @Test
    fun `prepared input falls back to empty primary`() {
        val prepared = TemplatePreparedInput.empty(primaryInputName = "code")
        assertEquals("", prepared.primaryInput)
        assertEquals("", prepared.input("code"))
        assertEquals("code", prepared.primaryInputName)
        assertTrue(prepared.truncations.isEmpty())
    }

    // ═══ 发现与严重度 ═══

    @Test
    fun `severity from marker recognizes emoji and words`() {
        assertEquals(TemplateSeverity.BLOCKER, TemplateSeverity.fromMarker("🔴 阻断"))
        assertEquals(TemplateSeverity.BLOCKER, TemplateSeverity.fromMarker("严重阻断"))
        assertEquals(TemplateSeverity.IMPORTANT, TemplateSeverity.fromMarker("🟡"))
        assertEquals(TemplateSeverity.MINOR, TemplateSeverity.fromMarker("次要问题"))
        assertEquals(TemplateSeverity.INFO, TemplateSeverity.fromMarker("🔵 信息"))
        assertNull(TemplateSeverity.fromMarker("无关文本"))
    }

    @Test
    fun `severity at least compares by weight`() {
        assertTrue(TemplateSeverity.BLOCKER.atLeast(TemplateSeverity.IMPORTANT))
        assertTrue(TemplateSeverity.IMPORTANT.atLeast(TemplateSeverity.IMPORTANT))
        assertFalse(TemplateSeverity.MINOR.atLeast(TemplateSeverity.IMPORTANT))
    }

    @Test
    fun `finding prompt line compresses multiline evidence`() {
        val finding = TemplateFinding(
            ruleId = "kotlin.double-bang",
            category = TemplateFindingCategory.CORRECTNESS,
            severity = TemplateSeverity.IMPORTANT,
            title = "非空断言脆弱",
            location = "第 3 行",
            evidence = "val name = user!!.name\n    .trim()",
            suggestion = "改用安全调用"
        )
        val line = finding.toPromptLine()
        assertTrue(line.contains("[🟡重要]"))
        assertTrue(line.contains("（第 3 行）"))
        assertTrue(line.contains("⏎"))
        assertFalse(line.contains('\n'))
    }

    @Test
    fun `finding validates confidence bounds`() {
        val error = runCatching {
            TemplateFinding(
                ruleId = "x", category = TemplateFindingCategory.OTHER,
                severity = TemplateSeverity.INFO, title = "t", confidence = 1.5
            )
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    // ═══ 分析聚合 ═══

    @Test
    fun `analysis of sorts findings by severity descending`() {
        val findings = listOf(
            TemplateFinding("a.minor", TemplateFindingCategory.STYLE, TemplateSeverity.MINOR, "m"),
            TemplateFinding("b.blocker", TemplateFindingCategory.SECURITY, TemplateSeverity.BLOCKER, "b"),
            TemplateFinding("c.important", TemplateFindingCategory.PERFORMANCE, TemplateSeverity.IMPORTANT, "i"),
            TemplateFinding("d.info", TemplateFindingCategory.OTHER, TemplateSeverity.INFO, "n")
        )
        val analysis = TemplateAnalysis.of(findings)
        assertEquals(
            listOf("b.blocker", "c.important", "a.minor", "d.info"),
            analysis.findings.map { it.ruleId }
        )
        assertEquals(TemplateSeverity.BLOCKER, analysis.highestSeverity())
        assertTrue(analysis.hasFindingsAtLeast(TemplateSeverity.BLOCKER))
        assertFalse(analysis.hasFindingsAtLeast(TemplateSeverity.BLOCKER).not())
    }

    @Test
    fun `analysis severity counts bucket all findings`() {
        val analysis = TemplateAnalysis.of(
            listOf(
                TemplateFinding("a", TemplateFindingCategory.STYLE, TemplateSeverity.MINOR, "1"),
                TemplateFinding("b", TemplateFindingCategory.STYLE, TemplateSeverity.MINOR, "2"),
                TemplateFinding("c", TemplateFindingCategory.STYLE, TemplateSeverity.BLOCKER, "3")
            )
        )
        val counts = analysis.severityCounts()
        assertEquals(1, counts[TemplateSeverity.BLOCKER])
        assertEquals(2, counts[TemplateSeverity.MINOR])
        assertEquals(0, counts[TemplateSeverity.INFO])
    }

    @Test
    fun `stats int and double metrics tolerate garbage`() {
        val stats = TemplateAnalysisStats(
            mapOf("ruleCount" to "4", "ratio" to "0.5", "bad" to "xyz")
        ).with("extra", 7)
        assertEquals(4, stats.ruleCount)
        assertEquals(0.5, stats.doubleMetric("ratio", 0.0), 1e-9)
        assertEquals(0, stats.intMetric("bad", 0))
        assertEquals(7, stats.intMetric("extra", 0))
        assertEquals(0, TemplateAnalysisStats.empty().ruleCount)
        assertEquals(0, TemplateAnalysisStats.empty().analyzedLineCount)
    }

    @Test
    fun `empty analysis has no findings and no severity`() {
        val empty = TemplateAnalysis.empty()
        assertNull(empty.highestSeverity())
        assertFalse(empty.hasFindingsAtLeast(TemplateSeverity.INFO))
        assertTrue(empty.findings.isEmpty())
        assertNull(empty.detectedLanguage)
    }

    // ═══ 输出契约 ═══

    @Test
    fun `normalize header strips markdown numbering and punctuation`() {
        assertEquals("问题清单", TemplateOutputContract.normalizeHeader("## 2. 问题清单："))
        assertEquals("问题清单", TemplateOutputContract.normalizeHeader("问题清单"))
        assertEquals("问题列表", TemplateOutputContract.normalizeHeader("1）问题列表　"))
        assertEquals("", TemplateOutputContract.normalizeHeader("   "))
    }

    @Test
    fun `section requirement matches headers via aliases`() {
        val requirement = TemplateSectionRequirement(
            title = "问题清单",
            aliases = listOf("问题列表", "Issues"),
            listItemCountRange = 1..20
        )
        assertTrue(requirement.matchesHeader("### 1. 问题清单："))
        assertTrue(requirement.matchesHeader("2、问题列表"))
        assertTrue(requirement.matchesHeader("Issues:"))
        assertFalse(requirement.matchesHeader("问题"))
    }

    @Test
    fun `violation kinds declare repairability split`() {
        val repairable = TemplateContractViolationKind.entries.filter { it.repairable }
        val fatal = TemplateContractViolationKind.entries.filter { !it.repairable }
        assertTrue(repairable.isNotEmpty())
        assertEquals(listOf(TemplateContractViolationKind.EMPTY_OUTPUT, TemplateContractViolationKind.OFF_TOPIC), fatal)
    }

    @Test
    fun `validation report aggregates repairability and counts`() {
        val report = TemplateValidationReport(
            violations = listOf(
                TemplateContractViolation(TemplateContractViolationKind.PREAMBLE, detail = "好的，下面是"),
                TemplateContractViolation(TemplateContractViolationKind.EMPTY_OUTPUT, detail = "空")
            )
        )
        assertFalse(report.ok)
        assertTrue(report.hasRepairable)
        assertTrue(report.hasFatal)
        assertEquals(1, report.violationCounts()["PREAMBLE"])
        assertNull(report.section("问题清单"))
    }

    @Test
    fun `parsed section exposes emptiness and text`() {
        val filled = TemplateParsedSection(
            requirement = null, headerText = "结论", bodyLines = listOf("通过"), listItems = emptyList()
        )
        val hollow = TemplateParsedSection(
            requirement = null, headerText = "结论", bodyLines = listOf(" "), listItems = emptyList()
        )
        assertFalse(filled.isEmptyBody)
        assertTrue(hollow.isEmptyBody)
        assertTrue(filled.toText().contains("通过"))
    }

    // ═══ 轮次与报告 ═══

    @Test
    fun `turn request exposes prompt chars and validates blank`() {
        val request = TemplateTurnRequest(prompt = "你好", behaviorId = "b")
        assertEquals(2, request.promptChars)
        val error = runCatching { TemplateTurnRequest(prompt = "  ", behaviorId = "b") }
            .exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `trace accumulates phases with wall-clock accounting`() {
        var trace = TemplateExecutionTrace(templateId = "t")
        assertTrue(trace.phases.isEmpty())
        trace = trace.append(TemplatePhaseTrace(TemplatePhaseKind.PREPARE, true, 3, "ok"), at = 10)
        trace = trace.append(TemplatePhaseTrace(TemplatePhaseKind.ANALYZE, false, 5, "崩了"), at = 15)
        assertEquals(2, trace.phases.size)
        assertFalse(trace.allOk)
        assertEquals(15, trace.finishedAt)
        assertEquals(5, trace.totalDurationMs)
        assertEquals(10, trace.startedAt)
    }

    @Test
    fun `rendered report fallback keeps raw text with zero score`() {
        val fallback = TemplateRenderedReport.fallback(
            raw = "  原样回包\n",
            violations = listOf(TemplateContractViolation(TemplateContractViolationKind.EMPTY_OUTPUT, detail = "空"))
        )
        assertEquals("原样回包", fallback.finalText)
        assertEquals(0.0, fallback.qualityScore, 1e-9)
        assertEquals(1, fallback.residualViolations.size)
        assertFalse(fallback.wasRepaired)
        assertEquals(0, fallback.blockerCount)
        assertEquals(1, fallback.sections.size)
    }

    @Test
    fun `report section kinds cover verdict body list table supplement`() {
        val kinds = TemplateReportSection.Kind.entries.map { it.label }
        assertEquals(listOf("结论", "正文", "列表", "表格", "补充"), kinds)
    }
}
