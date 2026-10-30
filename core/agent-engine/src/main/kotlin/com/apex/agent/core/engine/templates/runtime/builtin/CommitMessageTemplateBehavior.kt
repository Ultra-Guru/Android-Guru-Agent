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
 *    （公开符号删除/显式标记/契约文件）；scope 推导（改动最集中目录）；
 * 3. **COMPOSE**：正文变量替换 + 「变更判读附录」（类型候选/破坏性
 *    信号/scope 建议）；
 * 4. **VALIDATE**：回包必须含「提交信息」章节，且围栏内的提交信息
 *    本身要过 [CommitMessageDiffAnalyzer.validateCommitMessage]
 *    （格式/长度/折行/语气/中文冗余）；
 * 5. **REPAIR**：公共修复 + 提交信息确定性修复（type 归一/句号剥离/
 *    超长折行/祈使语气）；
 * 6. **RENDER**：提交信息代码块 + 变更解读 + 机器摘要
 *    （type/scope/files/insertions/deletions/breaking）。
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
        behaviorVersion = 1
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

        var stats = TemplateAnalysisStats.empty()
            .with(TemplateAnalysisStats.KEY_RULE_COUNT, findings.size)
            .with(TemplateAnalysisStats.KEY_ANALYZED_LINES, changes.lines().size)
            .with(METRIC_FILES, summary.fileCount)
            .with(METRIC_INSERTIONS, summary.totalInsertions)
            .with(METRIC_DELETIONS, summary.totalDeletions)
            .with(METRIC_BREAKING, breaking.size)

        val hints = mutableListOf<String>()
        hints += "提交风格：$style"
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
        val (repaired, notes) = CommitMessageDiffAnalyzer.repairCommitMessage(message)
        if (repaired != message) {
            text = text.replace(fenceMatch.value, "```\n$repaired\n```")
            context.log("commit message repaired: ${notes.joinToString("; ")}")
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
        return base.copy(
            sections = (messageSection + base.sections).sortedBy { it.orderKey },
            summary = summaryMap
        )
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

        /** 提交信息围栏块（``` 纯围栏或 ```text）。 */
        val FENCE_BLOCK: Regex = Regex("""```[a-z]*\n(.*?)```""", RegexOption.DOT_MATCHES_ALL)
    }
}
