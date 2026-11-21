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
import com.apex.agent.core.engine.templates.runtime.TemplateOutputContract
import com.apex.agent.core.engine.templates.runtime.TemplatePhaseKind
import com.apex.agent.core.engine.templates.runtime.TemplatePreparedInput
import com.apex.agent.core.engine.templates.runtime.TemplatePromptEnrichmentMode
import com.apex.agent.core.engine.templates.runtime.TemplateRenderedReport
import com.apex.agent.core.engine.templates.runtime.TemplateReportSection
import com.apex.agent.core.engine.templates.runtime.TemplateSectionParser
import com.apex.agent.core.engine.templates.runtime.TemplateSeverity
import com.apex.agent.core.engine.templates.runtime.TemplateTurnRequest
import com.apex.agent.core.engine.templates.runtime.TemplateValidationReport

/**
 * ═══ 内置模板行为 · 提交信息生成（builtin_commit_message，PR 6）═══
 *
 * 「提交信息生成」模板的代码化行为。切换该模板 = 装配确定性变更
 * 分析管线：
 *
 * 1. **PREPARE**：主输入 changes（diff 或文字说明）+ 可选 style；
 * 2. **ANALYZE**：统一 diff 状态机解析 → 文件清单/增删统计/hunk 数；
 *    conventional 类型打分（路径与形态证据）；破坏性变更三通道扫描
 *    （公开符号删除/显式标记/契约文件）；scope 推导（改动最集中目录）
 *    + 规范体系（PR 17：四种风格解析 + 十二类型知识库 + 主题草稿合成）；
 * 3. **COMPOSE**：正文变量替换 + 「变更判读附录」（类型候选/破坏性
 *    信号/scope 建议）；
 * 4. **VALIDATE**：回包必须含「提交信息」章节，且围栏内的提交信息
 *    本身要过 [CommitMessageDiffAnalyzer.validateCommitMessage]
 *    （格式/长度/折行/语气/中文冗余）+ 风格感知规范校验（PR 17：
 *    [CommitMessageConventions.validate] 按 conventional/gitmoji/plain/
 *    kernel 各自规则逐项检查）；
 * 5. **REPAIR**：公共修复 + 提交信息确定性修复（type 归一/句号剥离/
 *    超长折行/祈使语气）+ 风格感知修复（PR 17：类型别名纠正/
 *    issue 引用入 footer/正文重折/! 与 BREAKING CHANGE 对齐）；
 * 6. **RENDER**：提交信息代码块 + 变更解读 + 机器摘要
 *    （type/scope/files/insertions/deletions/breaking/style）。
 */
open class CommitMessageTemplateBehavior : TemplateBehaviorBase() {

    override val behaviorId: String = TEMPLATE_ID
    override val primaryInputName: String = "changes"
    override val secondaryInputNames: List<String> = listOf("style")

    override fun manifest(): TemplateBehaviorManifest = TemplateBehaviorManifest(
        behaviorId = behaviorId,
        displayName = "提交信息生成",
        summary = "diff 状态机解析 + conventional 类型打分 + 破坏性变更探测，写作而不是猜",
        supportedPhases = setOf(
            TemplatePhaseKind.PREPARE,
            TemplatePhaseKind.ANALYZE,
            TemplatePhaseKind.COMPOSE,
            TemplatePhaseKind.VALIDATE,
            TemplatePhaseKind.REPAIR,
            TemplatePhaseKind.RENDER
        ),
        features = setOf(TemplateBehaviorFeature.DIFF_PARSING),
        outputContract = TemplateOutputContract(
            requiredSections = listOf(
                com.apex.agent.core.engine.templates.runtime.TemplateSectionRequirement(
                    title = "提交信息",
                    aliases = listOf("提交信息建议", "建议的提交信息"),
                    required = true
                ),
                com.apex.agent.core.engine.templates.runtime.TemplateSectionRequirement(
                    title = "变更解读",
                    aliases = listOf("变更摘要", "变更说明"),
                    required = false
                )
            )
        ),
        deterministicPreAnalysis = true,
        promptEnrichmentMode = TemplatePromptEnrichmentMode.APPEND_FINDINGS,
        behaviorVersion = 2
    )

    // ═══ ANALYZE ═══

    override suspend fun analyze(
        context: TemplateExecutionContext,
        prepared: TemplatePreparedInput
    ): TemplateAnalysis {
        val changes = prepared.primaryInput
        if (changes.isBlank()) {
            return TemplateAnalysis.of(
                findings = emptyList(),
                analyzerNotes = listOf("变更说明为空，跳过确定性分析")
            )
        }
        val style = context.variableOr("style", DEFAULT_STYLE)
        val styleKind = CommitStyleKind.parse(style) ?: CommitStyleKind.CONVENTIONAL
        val styleSpec = CommitStyleSpec.of(styleKind)

        val findings = mutableListOf<TemplateFinding>()
        val summary = CommitMessageDiffAnalyzer.parseUnifiedDiff(changes)
        val breaking = CommitMessageDiffAnalyzer.detectBreakingChanges(changes, summary)
        val typeCandidates = CommitMessageDiffAnalyzer.classifyChanges(summary)
        val scope = CommitMessageDiffAnalyzer.inferScope(summary, changes)

        // 1) 通道说明 finding（INFO：让 LLM 知道走的是哪条判读通道）
        if (summary.isDiffLike) {
            findings.add(
                TemplateFinding(
                    ruleId = "commit.diff-parsed",
                    category = TemplateFindingCategory.STRUCTURE,
                    severity = TemplateSeverity.INFO,
                    title = "已解析统一 diff：${summary.fileCount} 个文件 / +${summary.totalInsertions} -${summary.totalDeletions}",
                    evidence = summary.files.take(4).joinToString("；") {
                        "${it.displayPath}（+${it.insertions} -${it.deletions}）"
                    },
                    suggestion = "提交信息应基于上述文件级事实展开",
                    confidence = 1.0
                )
            )
        } else {
            findings.add(
                TemplateFinding(
                    ruleId = "commit.prose-input",
                    category = TemplateFindingCategory.STRUCTURE,
                    severity = TemplateSeverity.INFO,
                    title = "输入为文字说明（无 diff 形态），走语义通道",
                    evidence = changes.take(80),
                    suggestion = "从描述中提取意图动词与影响对象作类型判断",
                    confidence = 1.0
                )
            )
        }
        summary.notes.forEach { note ->
            findings.add(
                TemplateFinding(
                    ruleId = "commit.diff-degraded",
                    category = TemplateFindingCategory.COMPLETENESS,
                    severity = TemplateSeverity.MINOR,
                    title = "diff 解析降级",
                    evidence = note,
                    suggestion = "如需精确统计请提供完整 diff 输出",
                    confidence = 1.0
                )
            )
        }

        // 2) 类型候选（首位 INFO，其余留 hints）
        typeCandidates.firstOrNull()?.let { top ->
            findings.add(
                TemplateFinding(
                    ruleId = "commit.type-candidate",
                    category = TemplateFindingCategory.STRUCTURE,
                    severity = TemplateSeverity.INFO,
                    title = "类型判定首选：${top.type}（${CommitMessageDiffAnalyzer.TYPE_LABELS[top.type] ?: top.type}）",
                    evidence = top.evidence.joinToString("；"),
                    suggestion = "首行 type 建议 ${top.type}" + (scope?.let { "，scope 建议 $it" } ?: ""),
                    confidence = top.confidence
                )
            )
        }

        // 3) 破坏性变更（BLOCKER 级——必须进提交信息 footer）
        for (signal in breaking) {
            findings.add(
                TemplateFinding(
                    ruleId = "commit.breaking-change",
                    category = TemplateFindingCategory.RISK,
                    severity = TemplateSeverity.BLOCKER,
                    title = "破坏性变更信号（${signal.kind.label}）",
                    location = signal.location,
                    evidence = signal.evidence,
                    suggestion = "提交信息需带 ! 标记与 BREAKING CHANGE footer，说明迁移路径",
                    confidence = 0.9
                )
            )
        }

        // 4) 大体量变更（提示正文要点）
        if (summary.totalInsertions + summary.totalDeletions > LARGE_CHANGE_LINES) {
            findings.add(
                TemplateFinding(
                    ruleId = "commit.large-change",
                    category = TemplateFindingCategory.MAINTAINABILITY,
                    severity = TemplateSeverity.MINOR,
                    title = "大体量变更（+${summary.totalInsertions} -${summary.totalDeletions}）",
                    suggestion = "正文分点说明动机与影响面，不要罗列文件清单",
                    confidence = 1.0
                )
            )
        }

        // 5) 规范体系（PR 17）：风格解析 + 主题草稿合成
        if (CommitStyleKind.parse(style) == null && style.isNotBlank()) {
            findings.add(
                TemplateFinding(
                    ruleId = "commit.style-unresolved",
                    category = TemplateFindingCategory.COMPLETENESS,
                    severity = TemplateSeverity.MINOR,
                    title = "提交风格无法识别（$style），按 conventional 执行",
                    suggestion = "支持：conventional commits / gitmoji / 内核风格 / 自由格式",
                    confidence = 1.0
                )
            )
        }
        findings.add(
            TemplateFinding(
                ruleId = "commit.style-spec",
                category = TemplateFindingCategory.STRUCTURE,
                severity = TemplateSeverity.INFO,
                title = "风格规范：${styleSpec.kind.label}（主题软限 ${styleSpec.subjectSoftMax}/硬限 ${styleSpec.subjectHardMax}，正文 ${styleSpec.bodyWrapColumns} 列折行）",
                evidence = styleSpec.guidance,
                suggestion = "按该风格规范书写提交信息",
                confidence = 1.0
            )
        )
        val topType = typeCandidates.firstOrNull()?.type ?: "chore"
        val draftSubject = CommitMessageConventions.draftSubject(
            type = topType,
            scope = scope,
            objectText = draftObjectOf(summary, breaking.isNotEmpty()),
            spec = styleSpec,
            preferChinese = true
        )
        if (draftSubject.isNotBlank()) {
            findings.add(
                TemplateFinding(
                    ruleId = "commit.subject-draft",
                    category = TemplateFindingCategory.STRUCTURE,
                    severity = TemplateSeverity.INFO,
                    title = "主题草稿（$styleKind）：$draftSubject",
                    suggestion = "核对事实后润色，保持风格形态",
                    confidence = 0.9
                )
            )
        }

        var stats = TemplateAnalysisStats.empty()
            .with(TemplateAnalysisStats.KEY_RULE_COUNT, findings.size)
            .with(TemplateAnalysisStats.KEY_ANALYZED_LINES, changes.lines().size)
            .with(METRIC_FILES, summary.fileCount)
            .with(METRIC_INSERTIONS, summary.totalInsertions)
            .with(METRIC_DELETIONS, summary.totalDeletions)
            .with(METRIC_BREAKING, breaking.size)

        val hints = mutableListOf<String>()
        hints += "提交风格：$style（${styleSpec.kind.label}）"
        hints += "风格规范：${styleSpec.guidance}"
        if (typeCandidates.size > 1) {
            hints += "类型候选（按置信度）：" + typeCandidates.joinToString(" > ") { "${it.type}(${it.confidence})" }
        }
        scope?.let { hints += "scope 建议：$it" }
        if (breaking.isNotEmpty()) {
            hints += "破坏性变更 ${breaking.size} 处，必须带 BREAKING CHANGE footer"
        }
        if (summary.isDiffLike) {
            hints += "文件级事实（节选）：" + summary.files.take(6).joinToString("；") { "${it.displayPath} +${it.insertions} -${it.deletions}" }
        }
        // 确定性草稿（LLM 核对润色，不是从零造句）
        val draft = CommitDraftGenerator.generate(
            summary = summary,
            topType = typeCandidates.firstOrNull()?.type ?: "chore",
            scope = scope,
            breaking = breaking,
            style = style
        )
        hints += "代码侧草稿（请核对事实后润色）：\n" + draft.toMessage()

        return TemplateAnalysis.of(
            findings = findings,
            stats = stats,
            enrichedHints = hints,
            metadata = buildMap {
                put("diffLike", summary.isDiffLike.toString())
                put("fileCount", summary.fileCount.toString())
                put("topType", typeCandidates.firstOrNull()?.type ?: "chore")
                put("scope", scope ?: "")
                put("breaking", breaking.size.toString())
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
        val contract = manifest().outputContract
        val skeleton = TemplateSectionParser.parse(raw, contract)

        // 专项：提取提交信息（围栏优先，其次「提交信息」章节体）
        val extracted = extractCommitMessage(skeleton)
        val violations = base.violations.toMutableList()
        if (extracted != null) {
            val issues = CommitMessageDiffAnalyzer.validateCommitMessage(extracted)
            for (issue in issues) {
                violations.add(
                    TemplateContractViolation(
                        kind = if (issue.repairable) {
                            TemplateContractViolationKind.BAD_SEVERITY_MARK
                        } else {
                            TemplateContractViolationKind.SECTION_EMPTY
                        },
                        section = "提交信息",
                        detail = "[${issue.kind.label}] ${issue.detail}",
                        evidence = extracted.lines().firstOrNull()?.take(50).orEmpty()
                    )
                )
            }
            // 专项（PR 17）：风格感知规范校验
            val styleKind = CommitStyleKind.parse(
                context.variableOr("style", DEFAULT_STYLE)
            ) ?: CommitStyleKind.CONVENTIONAL
            val styleSpec = CommitStyleSpec.of(styleKind)
            for (issue in CommitMessageConventions.validate(extracted, styleSpec)) {
                violations.add(
                    TemplateContractViolation(
                        kind = if (issue.repairable) {
                            TemplateContractViolationKind.BAD_SEVERITY_MARK
                        } else {
                            TemplateContractViolationKind.SECTION_EMPTY
                        },
                        section = "提交信息",
                        detail = "[${issue.kind.label}] ${issue.detail}",
                        evidence = issue.evidence
                    )
                )
            }
        }
        return TemplateValidationReport(
            violations = violations,
            parsedSections = base.parsedSections,
            orphans = base.orphans
        )
    }

    /** 从回包提取提交信息：优先 ``` 围栏块，回落到「提交信息」章节文本。 */
    internal fun extractCommitMessage(
        skeleton: com.apex.agent.core.engine.templates.runtime.TemplateDocumentSkeleton
    ): String? {
        val fenced = FENCE_BLOCK.findAll(skeleton.sections.joinToString("\n") { it.toText() })
            .firstOrNull()?.groupValues?.get(1)
        if (fenced != null && fenced.isNotBlank()) return fenced.trim()
        val section = skeleton.sections.firstOrNull {
            manifest().outputContract.requiredSections[0].matchesHeader(it.headerText)
        } ?: return null
        val text = (section.bodyLines + section.listItems).joinToString("\n").trim()
        return text.ifBlank { null }
    }

    // ═══ REPAIR ═══

    override fun repairOutput(
        raw: String,
        report: TemplateValidationReport,
        context: TemplateExecutionContext
    ): String {
        var text = super.repairOutput(raw, report, context)
        // 专项：围栏内提交信息修复（type 归一/句号/折行/祈使语气）
        val fenceMatch = FENCE_BLOCK.find(text) ?: return text
        val message = fenceMatch.groupValues[1].trim()
        val (baseRepaired, baseNotes) = CommitMessageDiffAnalyzer.repairCommitMessage(message)
        // 专项（PR 17）：风格感知修复（别名纠正/issue 引用入 footer/重折）
        val styleKind = CommitStyleKind.parse(
            context.variableOr("style", DEFAULT_STYLE)
        ) ?: CommitStyleKind.CONVENTIONAL
        val (repaired, styleNotes) = CommitMessageConventions.repair(
            baseRepaired,
            CommitStyleSpec.of(styleKind)
        )
        if (repaired != message) {
            text = text.replace(fenceMatch.value, "```\n$repaired\n```")
            context.log("commit message repaired: ${(baseNotes + styleNotes).joinToString("; ")}")
        }
        return text
    }

    // ═══ RENDER ═══

    override fun renderReport(
        raw: String,
        context: TemplateExecutionContext,
        request: TemplateTurnRequest
    ): TemplateRenderedReport {
        val base = super.renderReport(raw, context, request)
        val contract = manifest().outputContract
        val skeleton = TemplateSectionParser.parse(raw, contract)
        val extracted = extractCommitMessage(skeleton)
        val message = extracted?.let {
            CommitMessageDiffAnalyzer.repairCommitMessage(it).first
        }
        val summaryMap = buildMap {
            put(META_TYPE, extractType(message))
            put(META_SCOPE, extractScope(message) ?: "")
            put(META_SUBJECT_OK, (message != null && CommitMessageDiffAnalyzer
                .validateCommitMessage(message).none {
                    it.kind == CommitMessageIssue.Kind.BAD_HEADER_FORMAT ||
                        it.kind == CommitMessageIssue.Kind.SUBJECT_TOO_LONG
                }).toString())
        }
        val messageSection = if (message != null) {
            listOf(
                TemplateReportSection(
                    title = "规范化提交信息",
                    kind = TemplateReportSection.Kind.BODY,
                    lines = listOf("```", message, "```"),
                    orderKey = -1
                )
            )
        } else {
            emptyList()
        }
        val styleKind = CommitStyleKind.parse(
            context.variableOr("style", DEFAULT_STYLE)
        ) ?: CommitStyleKind.CONVENTIONAL
        val styleSpec = CommitStyleSpec.of(styleKind)
        val styleConformant = message != null &&
            CommitMessageConventions.validate(message, styleSpec).isEmpty()
        val summaryWithStyle = summaryMap + (META_STYLE to styleSpec.kind.label) +
            (META_STYLE_CONFORMANT to styleConformant.toString())
        return base.copy(
            sections = (messageSection + base.sections).sortedBy { it.orderKey },
            summary = summaryWithStyle
        )
    }

    /** 草稿对象描述（从 diff 事实推导主题里的「做了什么」）。 */
    private fun draftObjectOf(summary: DiffSummary, breaking: Boolean): String {
        val newCount = summary.newFiles.size
        val deletedCount = summary.deletedFiles.size
        val testOnly = summary.files.isNotEmpty() && summary.files.all { it in summary.testFiles }
        return when {
            breaking -> "破坏性变更（含迁移说明）"
            newCount > 0 && deletedCount == 0 ->
                "${newCount} 个新文件（${summary.newFiles.first().displayPath.substringAfterLast('/')})"
            deletedCount > 0 && newCount == 0 ->
                "移除 ${deletedCount} 个文件（${summary.deletedFiles.first().displayPath.substringAfterLast('/')})"
            testOnly -> "测试覆盖（${summary.fileCount} 个文件）"
            summary.docFiles.isNotEmpty() && summary.files.size == summary.docFiles.size ->
                "文档更新（${summary.fileCount} 个文件）"
            else ->
                "${summary.fileCount} 个文件的行为变更"
        }
    }

    private fun extractType(message: String?): String {
        if (message == null) return ""
        return Regex("""^([a-z]+)(\(|:|$)""").find(message.trim())?.groupValues?.get(1) ?: ""
    }

    private fun extractScope(message: String?): String? {
        if (message == null) return null
        return Regex("""^[a-z]+\(([\w./-]+)\)""").find(message.trim())?.groupValues?.get(1)
    }

    companion object {
        const val TEMPLATE_ID = "builtin_commit_message"
        const val DEFAULT_STYLE = "conventional commits"
        const val LARGE_CHANGE_LINES = 400
        const val METRIC_FILES = "files"
        const val METRIC_INSERTIONS = "insertions"
        const val METRIC_DELETIONS = "deletions"
        const val METRIC_BREAKING = "breakingSignals"
        const val META_TYPE = "commitType"
        const val META_SCOPE = "commitScope"
        const val META_SUBJECT_OK = "subjectOk"
        const val META_STYLE = "commitStyle"
        const val META_STYLE_CONFORMANT = "styleConformant"

        /** 提交信息围栏块（``` 纯围栏或 ```text）。 */
        val FENCE_BLOCK: Regex = Regex("""```[a-z]*\n(.*?)```""", RegexOption.DOT_MATCHES_ALL)
    }
}
