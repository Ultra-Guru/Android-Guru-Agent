package com.apex.agent.core.engine.templates.runtime.builtin

import com.apex.agent.core.engine.templates.runtime.TemplateAnalysis
import com.apex.agent.core.engine.templates.runtime.TemplateAnalysisStats
import com.apex.agent.core.engine.templates.runtime.TemplateBehaviorBase
import com.apex.agent.core.engine.templates.runtime.TemplateBehaviorFeature
import com.apex.agent.core.engine.templates.runtime.TemplateBehaviorManifest
import com.apex.agent.core.engine.templates.runtime.TemplateContractViolation
import com.apex.agent.core.engine.templates.runtime.TemplateContractViolationKind
import com.apex.agent.core.engine.templates.runtime.TemplateExecutionContext
import com.apex.agent.core.engine.templates.runtime.TemplateFinding
import com.apex.agent.core.engine.templates.runtime.TemplateFindingCategory
import com.apex.agent.core.engine.templates.runtime.TemplateMarkdownScanner
import com.apex.agent.core.engine.templates.runtime.TemplateOutputContract
import com.apex.agent.core.engine.templates.runtime.TemplatePhaseKind
import com.apex.agent.core.engine.templates.runtime.TemplatePreparedInput
import com.apex.agent.core.engine.templates.runtime.TemplatePromptEnrichmentMode
import com.apex.agent.core.engine.templates.runtime.TemplateRenderedReport
import com.apex.agent.core.engine.templates.runtime.TemplateReportSection
import com.apex.agent.core.engine.templates.runtime.TemplateSectionRequirement
import com.apex.agent.core.engine.templates.runtime.TemplateSectionParser
import com.apex.agent.core.engine.templates.runtime.TemplateSeverity
import com.apex.agent.core.engine.templates.runtime.TemplateTurnRequest
import com.apex.agent.core.engine.templates.runtime.TemplateValidationReport

/**
 * ═══ 内置模板行为 · 专业翻译（builtin_translate，PR 7）═══
 *
 * 「专业翻译」模板的代码化行为。切换该模板 = 装配翻译质量管线：
 *
 * 1. **ANALYZE**（先于大模型）：
 *    - 源脚本/语言探测 + 目标语言归一；
 *    - 原文结构盘点（标题/列表/代码块/占位符/表格）→ 保真要求清单；
 *    - 领域术语表命中（双向）→ 建议译法清单；
 * 2. **COMPOSE**：正文 + 术语建议 + 保真要求附录注入；
 * 3. **VALIDATE**（回包侧）：
 *    - payloadOnly 契约：译文只能是纯载荷（寒暄/解释段违规）；
 *    - 结构保真比对：丢失标题/占位符/列表条目 = 违规；
 *    - 术语一致性：同一术语多种译法 = 违规；
 * 4. **REPAIR**：剥「以下是译文」前导与「翻译说明」尾部块；
 * 5. **RENDER**：净化后译文 + 机器摘要（源/目标语言、术语命中、保真结论）。
 */
open class TranslateTemplateBehavior : TemplateBehaviorBase() {

    override val behaviorId: String = TEMPLATE_ID
    override val primaryInputName: String = "text"
    override val secondaryInputNames: List<String> = listOf("target_language", "domain")

    override fun manifest(): TemplateBehaviorManifest = TemplateBehaviorManifest(
        behaviorId = behaviorId,
        displayName = "专业翻译",
        summary = "领域术语表 + 结构保真校验 + 术语一致性检查，译文质量可判定",
        supportedPhases = setOf(
            TemplatePhaseKind.PREPARE,
            TemplatePhaseKind.ANALYZE,
            TemplatePhaseKind.COMPOSE,
            TemplatePhaseKind.VALIDATE,
            TemplatePhaseKind.REPAIR,
            TemplatePhaseKind.RENDER
        ),
        features = setOf(
            TemplateBehaviorFeature.GLOSSARY,
            TemplateBehaviorFeature.FORMAT_PRESERVATION
        ),
        outputContract = TemplateOutputContract(
            // 译文是纯载荷：无必需章节，禁止任何前后噪音
            requiredSections = emptyList(),
            payloadOnly = true
        ),
        deterministicPreAnalysis = true,
        promptEnrichmentMode = TemplatePromptEnrichmentMode.APPEND_FINDINGS,
        behaviorVersion = 1
    )

    // ═══ ANALYZE ═══

    override suspend fun analyze(
        context: TemplateExecutionContext,
        prepared: TemplatePreparedInput
    ): TemplateAnalysis {
        val text = prepared.primaryInput
        if (text.isBlank()) {
            return TemplateAnalysis.of(findings = emptyList(), analyzerNotes = listOf("原文为空，跳过确定性分析"))
        }
        val targetInput = context.variableOr("target_language", "")
        val domainInput = context.variableOr("domain", "通用")
        val domain = GlossaryDomain.normalize(domainInput)
        val findings = mutableListOf<TemplateFinding>()
        var fidelityHint: String? = null

        // 1) 源语言与目标语言判定
        val sourceGuess = TranslateGlossary.guessSourceLanguage(text)
        val target = TranslateGlossary.normalizeTargetLanguage(targetInput)
        if (target == null) {
            findings.add(
                TemplateFinding(
                    ruleId = "translate.unknown-target",
                    category = TemplateFindingCategory.COMPLETENESS,
                    severity = TemplateSeverity.MINOR,
                    title = "目标语言无法归一（输入：$targetInput）",
                    suggestion = "按英文（en）假设执行，并在译文首行外注明假设",
                    confidence = 1.0
                )
            )
        }
        val effectiveTarget = target ?: "en"
        if (sourceGuess != null && sourceGuess.first == effectiveTarget) {
            findings.add(
                TemplateFinding(
                    ruleId = "translate.same-language",
                    category = TemplateFindingCategory.CONSISTENCY,
                    severity = TemplateSeverity.IMPORTANT,
                    title = "源语言与目标语言相同（${sourceGuess.first}）",
                    evidence = sourceGuess.second,
                    suggestion = "确认是否输入了错的目标语言；若为润色需求请改用写作模板",
                    confidence = 0.9
                )
            )
        }

        // 2) 结构盘点 → 保真要求
        val sourceScan = TemplateMarkdownScanner.scan(text)
        if (sourceScan.hasStructure || sourceScan.placeholders.isNotEmpty()) {
            val requirements = buildList {
                if (sourceScan.headings.isNotEmpty()) {
                    add("保留全部 ${sourceScan.headings.size} 个标题（含层级）")
                }
                if (sourceScan.listItems.isNotEmpty()) {
                    add("保留列表结构（原文 ${sourceScan.listItems.size} 项，条目数不得增减）")
                }
                if (sourceScan.codeFences.isNotEmpty()) {
                    add("代码块原样保留（${sourceScan.codeFences.size} 个，含语言标注，不翻译内容）")
                }
                if (sourceScan.placeholders.isNotEmpty()) {
                    add("占位符逐字保留：" + sourceScan.placeholders.joinToString("、") { it.raw })
                }
                if (sourceScan.tableRowCount > 0) {
                    add("保留表格形态（原文 ${sourceScan.tableRowCount} 行）")
                }
            }
            fidelityHint = requirements.joinToString("；")
            findings.add(
                TemplateFinding(
                    ruleId = "translate.fidelity-requirements",
                    category = TemplateFindingCategory.STRUCTURE,
                    severity = TemplateSeverity.INFO,
                    title = "结构保真要求（${requirements.size} 项）",
                    evidence = requirements.joinToString("；"),
                    suggestion = "译文必须在满足上述要求的前提下重排语言",
                    confidence = 1.0
                )
            )
        }

        // 3) 术语表命中
        val hits = TranslateGlossary.matchTerms(text, domain, effectiveTarget)
        for (hit in hits.take(MAX_TERM_HINTS)) {
            findings.add(
                TemplateFinding(
                    ruleId = "translate.term-hit",
                    category = TemplateFindingCategory.CONSISTENCY,
                    severity = TemplateSeverity.INFO,
                    title = "术语「${hit.entry.term}」→ 建议译法「${hit.suggested}」",
                    evidence = "出现 ${hit.count} 次${hit.entry.note.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: ""}",
                    suggestion = "全文统一使用该译法",
                    confidence = 1.0
                )
            )
        }

        // 4) RTL 提示
        if (TranslateGlossary.isRightToLeft(effectiveTarget)) {
            findings.add(
                TemplateFinding(
                    ruleId = "translate.rtl-target",
                    category = TemplateFindingCategory.STRUCTURE,
                    severity = TemplateSeverity.INFO,
                    title = "目标语言为从右到左书写（${TranslateGlossary.displayNameOf(effectiveTarget)}）",
                    suggestion = "标点与数字方向按 RTL 习惯处理",
                    confidence = 1.0
                )
            )
        }

        var stats = TemplateAnalysisStats.empty()
            .with(TemplateAnalysisStats.KEY_RULE_COUNT, findings.size)
            .with(TemplateAnalysisStats.KEY_ANALYZED_LINES, text.lines().size)
            .with(METRIC_TERM_HITS, hits.size)
            .with(METRIC_SOURCE_LANG, sourceGuess?.first ?: "unknown")
            .with(METRIC_TARGET_LANG, effectiveTarget)

        val hints = mutableListOf<String>()
        hints += "领域：${domain.label}" + (if (domain != GlossaryDomain.GENERAL) "（已加载专属术语表）" else "（通用术语表）")
        fidelityHint?.let { hints += "结构保真要求：$it" }
        hints += "源语言判定：${sourceGuess?.let { TranslateGlossary.displayNameOf(it.first) + "（${it.second}）" } ?: "未能判定"}"
        hints += "目标语言：${TranslateGlossary.displayNameOf(effectiveTarget)}"
        if (hits.isNotEmpty()) {
            hints += "术语对照（必须全文统一）：" + hits.take(8).joinToString("；") { "${it.entry.term}→${it.suggested}" }
        }

        return TemplateAnalysis.of(
            findings = findings,
            stats = stats,
            enrichedHints = hints,
            metadata = buildMap {
                put("domain", domain.name)
                put("targetLanguage", effectiveTarget)
                put("sourceLanguage", sourceGuess?.first ?: "")
                put("termHits", hits.size.toString())
                put("hasStructure", sourceScan.hasStructure.toString())
            }
        )
    }

    // ═══ VALIDATE ═══

    override fun validateOutput(
        raw: String,
        context: TemplateExecutionContext,
        request: TemplateTurnRequest
    ): TemplateValidationReport {
        val base = super.validateOutput(raw, context, request)
        val violations = base.violations.toMutableList()

        // 1) 纯载荷校验：解释段 / 前导噪音
        val skeleton = TemplateSectionParser.parse(raw)
        if (skeleton.sections.isNotEmpty()) {
            // 译文不应有任何章节结构（章节 = 解释性输出的强信号）
            violations.add(
                TemplateContractViolation(
                    kind = TemplateContractViolationKind.NOISE_AROUND_PAYLOAD,
                    detail = "译文不应包含章节结构（发现 ${skeleton.sections.size} 个标题）",
                    evidence = skeleton.allHeaders.firstOrNull().orEmpty().take(40)
                )
            )
        }
        for (line in skeleton.leadingLines) {
            if (NOISE_MARKERS.any { line.trim().startsWith(it) }) {
                violations.add(
                    TemplateContractViolation(
                        kind = TemplateContractViolationKind.NOISE_AROUND_PAYLOAD,
                        detail = "译文前部有解释性内容",
                        evidence = line.trim().take(50)
                    )
                )
                break
            }
        }

        // 2) 结构保真比对
        val text = context.variable("text").orEmpty()
        val sourceScan = TemplateMarkdownScanner.scan(text)
        val outputScan = TemplateMarkdownScanner.scan(raw)
        val fidelity = TemplateMarkdownScanner.compareFidelity(sourceScan, outputScan)
        if (!fidelity.isFaithful) {
            for (line in fidelity.describe().take(4)) {
                violations.add(
                    TemplateContractViolation(
                        kind = TemplateContractViolationKind.PLACEHOLDER_LEAK,
                        detail = "保真缺口：$line"
                    )
                )
            }
        }

        // 3) 术语一致性
        val domain = GlossaryDomain.normalize(context.variableOr("domain", "通用"))
        val target = TranslateGlossary.normalizeTargetLanguage(
            context.variableOr("target_language", "")
        ) ?: "en"
        val consistency = TranslateGlossary.checkConsistency(text, raw, domain, target)
        for (issue in consistency.take(3)) {
            violations.add(
                TemplateContractViolation(
                    kind = TemplateContractViolationKind.BAD_SEVERITY_MARK,
                    detail = "术语「${issue.sourceTerm}」出现多种译法：${issue.variants.joinToString(" / ")}",
                    evidence = issue.variants.firstOrNull().orEmpty()
                )
            )
        }
        return TemplateValidationReport(
            violations = violations,
            parsedSections = base.parsedSections,
            orphans = base.orphans
        )
    }

    // ═══ REPAIR ═══

    override fun repairOutput(
        raw: String,
        report: TemplateValidationReport,
        context: TemplateExecutionContext
    ): String {
        var text = super.repairOutput(raw, report, context)
        // 专项：剥「以下是译文：」前导行
        val lines = text.lines().toMutableList()
        while (lines.isNotEmpty() && NOISE_MARKERS.any { lines.first().trim().startsWith(it) }) {
            lines.removeAt(0)
        }
        // 专项：剥尾部「翻译说明」块（从首个翻译说明标记行到结尾）
        val noteIndex = lines.indexOfFirst { line ->
            TRAILING_NOTE_MARKERS.any { line.trim().startsWith(it) }
        }
        if (noteIndex >= 0 && noteIndex > lines.size / 2) {
            while (lines.size > noteIndex) lines.removeAt(lines.size - 1)
            while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.size - 1)
            context.log("translation trailing notes stripped")
        }
        while (lines.isNotEmpty() && lines.first().isBlank()) lines.removeAt(0)
        return lines.joinToString("\n")
    }

    // ═══ RENDER ═══

    override fun renderReport(
        raw: String,
        context: TemplateExecutionContext,
        request: TemplateTurnRequest
    ): TemplateRenderedReport {
        val base = super.renderReport(raw, context, request)
        val text = context.variable("text").orEmpty()
        val domain = GlossaryDomain.normalize(context.variableOr("domain", "通用"))
        val target = TranslateGlossary.normalizeTargetLanguage(
            context.variableOr("target_language", "")
        ) ?: "en"
        val hits = TranslateGlossary.matchTerms(text, domain, target)
        val fidelity = TemplateMarkdownScanner.compareFidelity(
            TemplateMarkdownScanner.scan(text),
            TemplateMarkdownScanner.scan(raw)
        )
        val summary = mapOf(
            META_SOURCE_LANG to (TranslateGlossary.guessSourceLanguage(text)?.first ?: "unknown"),
            META_TARGET_LANG to target,
            META_DOMAIN to domain.label,
            META_TERM_HITS to hits.size.toString(),
            META_FIDELITY_OK to fidelity.isFaithful.toString()
        )
        return base.copy(
            sections = listOf(
                TemplateReportSection(
                    title = "译文",
                    kind = TemplateReportSection.Kind.BODY,
                    lines = raw.lines(),
                    orderKey = 0
                )
            ),
            summary = summary
        )
    }

    companion object {
        const val TEMPLATE_ID = "builtin_translate"
        const val MAX_TERM_HINTS = 12
        const val METRIC_TERM_HITS = "termHits"
        const val METRIC_SOURCE_LANG = "sourceLang"
        const val METRIC_TARGET_LANG = "targetLang"
        const val META_SOURCE_LANG = "sourceLanguage"
        const val META_TARGET_LANG = "targetLanguage"
        const val META_DOMAIN = "domain"
        const val META_TERM_HITS = "termHitCount"
        const val META_FIDELITY_OK = "fidelityOk"

        /** 前导噪音标记（纯载荷不允许）。 */
        val NOISE_MARKERS = listOf(
            "以下是", "翻译如下", "译文如下", "好的", "Translation:", "Here is", "译文："
        )

        /** 尾部翻译说明块标记。 */
        val TRAILING_NOTE_MARKERS = listOf(
            "翻译说明", "译注", "Translator", "翻译要点", "说明："
        )
    }
}
