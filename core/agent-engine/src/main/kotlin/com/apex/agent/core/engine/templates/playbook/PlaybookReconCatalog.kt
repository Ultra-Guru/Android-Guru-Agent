package com.apex.agent.core.engine.templates.playbook

import com.apex.agent.core.engine.templates.runtime.TemplateSeverity
import com.apex.agent.core.engine.templates.runtime.builtin.BugReportTriageEngine
import com.apex.agent.core.engine.templates.runtime.builtin.CodeReviewHeuristics
import com.apex.agent.core.engine.templates.runtime.builtin.CodeReviewStructureMetrics
import com.apex.agent.core.engine.templates.runtime.builtin.CodeReviewTemplateBehavior
import com.apex.agent.core.engine.templates.runtime.builtin.RefactorPlanPlanner
import com.apex.agent.core.engine.templates.runtime.builtin.TestCasesAnalyzer

/** 侦察产物（确定性分析结果的纯文本附录行；供指令构建器拼接）。 */
data class ReconReport(
    val reconId: String,
    val title: String,
    val lines: List<String>
) {
    val isEmpty: Boolean get() = lines.isEmpty()
}

/**
 * ═══ 幕侦察绑定表 ═══
 *
 * 剧本幕的确定性侦察单元：reconId → 纯函数分析。复用 runtime/builtin
 * 里现存的确定性分析机械（启发式规则表 / 结构度量 / 分诊引擎 / 重构
 * 规划器 / 用例分析器），在幕激活时产出「确定性发现附录」—— agent 拿
 * 到的是带本地事实的子提示词，不是空口指令。
 *
 * 侦察全部**只读纯函数**（无 IO、无状态、同输入同输出）；输入缺失
 * （如无代码）返回空报告——侦察是增益不是门槛，绝不让幕挂不起来。
 */
object PlaybookReconCatalog {

    /** 输入键约定（幕子提示词变量取值透传）。 */
    const val KEY_CODE = "code"
    const val KEY_LANGUAGE = "language"
    const val KEY_FRAMEWORK = "framework"
    const val KEY_TASK = "task"

    /** 全部已注册侦察 id（工坊能力预览 + 校验用）。 */
    val ALL_IDS: Set<String> = setOf(
        RECON_CODE_REVIEW,
        RECON_TEST_CASES,
        RECON_BUG_TRIAGE,
        RECON_REFACTOR_PLAN
    )

    const val RECON_CODE_REVIEW = "code_review_heuristics"
    const val RECON_TEST_CASES = "test_cases_analyzer"
    const val RECON_BUG_TRIAGE = "bug_triage"
    const val RECON_REFACTOR_PLAN = "refactor_plan"

    /** 单幕附录行数上限（防长代码把指令撑爆）。 */
    private const val MAX_LINES = 12

    /**
     * 运行侦察（未知 id / 输入缺失 → 空报告，防御式不抛）。
     *
     * @param reconId 绑定 id（[ALL_IDS] 之一；null = 无侦察）
     * @param inputs 幕变量取值表（按 [KEY_CODE] 等键读取）
     */
    fun run(reconId: String?, inputs: Map<String, String>): ReconReport {
        if (reconId == null) return ReconReport("", "", emptyList())
        return try {
            when (reconId) {
                RECON_CODE_REVIEW -> codeReviewRecon(inputs)
                RECON_TEST_CASES -> testCasesRecon(inputs)
                RECON_BUG_TRIAGE -> bugTriageRecon(inputs)
                RECON_REFACTOR_PLAN -> refactorPlanRecon(inputs)
                else -> ReconReport(reconId, "", emptyList())
            }
        } catch (t: Throwable) {
            // 侦察崩溃绝不上抛：附录缺席只损失增益，不损失可用性。
            ReconReport(reconId, "侦察异常", listOf("(侦察器异常：${t::class.simpleName})"))
        }
    }

    // ═══ 各侦察单元 ═══

    /** 代码审查侦察：启发式规则发现 + 复杂度 Top。 */
    private fun codeReviewRecon(inputs: Map<String, String>): ReconReport {
        val code = inputs[KEY_CODE] ?: return empty(RECON_CODE_REVIEW)
        val language = inputs[KEY_LANGUAGE]?.takeIf { it.isNotBlank() && it != "未指定" }
        val spans = CodeReviewTemplateBehavior().findFunctions(code, language)
        val lines = mutableListOf<String>()
        for (finding in CodeReviewHeuristics.allFindings(code, language, spans)) {
            lines.add(
                "${finding.severity.marker} [${finding.ruleId}] ${finding.title}" +
                    (finding.location.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: "")
            )
            if (lines.size >= MAX_LINES) break
        }
        for (complexity in CodeReviewStructureMetrics.measureComplexity(code, spans, language).take(3)) {
            lines.add(
                "🔵 复杂度代理 ${complexity.cyclomaticProxy}：${complexity.name} " +
                    "（分支 ${complexity.branchCount} / 参数 ${complexity.parameterCount} / 跨度 ${complexity.lineCount} 行）"
            )
            if (lines.size >= MAX_LINES + 3) break
        }
        return ReconReport(RECON_CODE_REVIEW, "代码审查启发式侦察", lines)
    }

    /** 测试用例侦察：被测函数 + 等价类骨架。 */
    private fun testCasesRecon(inputs: Map<String, String>): ReconReport {
        val code = inputs[KEY_CODE] ?: return empty(RECON_TEST_CASES)
        val language = inputs[KEY_LANGUAGE]?.takeIf { it.isNotBlank() && it != "未指定" }
        val framework = inputs[KEY_FRAMEWORK]?.takeIf { it.isNotBlank() } ?: "JUnit"
        val analysis = TestCasesAnalyzer.analyze(code, language, framework)
        val lines = mutableListOf<String>()
        for (function in analysis.functions.take(4)) {
            lines.add("🔵 被测函数 ${function.name}（参数 ${function.params.size} 个，suspend=${function.isSuspend}）")
        }
        for (ec in analysis.equivalenceClasses.take(MAX_LINES - lines.size)) {
            lines.add("🔵 等价类[${ec.category}] ${ec.paramName}: ${ec.values.take(4).joinToString(" / ")}")
        }
        return ReconReport(RECON_TEST_CASES, "测试用例结构侦察", lines)
    }

    /** Bug 分诊侦察：优先级分级 + 排查路径指引。 */
    private fun bugTriageRecon(inputs: Map<String, String>): ReconReport {
        val task = inputs[KEY_TASK] ?: return empty(RECON_BUG_TRIAGE)
        // 输入里没有结构化 severity：默认 IMPORTANT，交给 agent 核对。
        val verdict = BugReportTriageEngine.triage(
            severity = TemplateSeverity.IMPORTANT,
            symptom = task.take(400),
            actual = "",
            reproduce = ""
        )
        val lines = mutableListOf(
            "🔵 分诊优先级 ${verdict.priority.label}（${verdict.priority.sla}）——默认 IMPORTANT 档，请核对",
            "🔵 判定依据：${verdict.rationale}"
        )
        // 排查路径取第一个类目的决策树（确定性给出，供幕1引用）。
        val category = BugReportTriageEngine.investigationCategories().firstOrNull()
        for (step in category?.let { BugReportTriageEngine.investigationTreeFor(it) }.orEmpty().take(MAX_LINES - lines.size)) {
            lines.add("🔵 排查建议：${step.action}（预期观察：${step.expectedObservation}）")
        }
        return ReconReport(RECON_BUG_TRIAGE, "Bug 分诊侦察", lines)
    }

    /** 重构规划侦察：诊断 + 步骤 + 风险（RefactorPlanPlanner 全量）。 */
    private fun refactorPlanRecon(inputs: Map<String, String>): ReconReport {
        val code = inputs[KEY_CODE] ?: return empty(RECON_REFACTOR_PLAN)
        val language = inputs[KEY_LANGUAGE]?.takeIf { it.isNotBlank() && it != "未指定" }
        val goal = inputs[KEY_TASK]?.takeIf { it.isNotBlank() } ?: "改善结构与可维护性"
        val plan = RefactorPlanPlanner.plan(code, language, goal, constraints = "")
        val lines = mutableListOf<String>()
        for (diagnosis in plan.diagnoses.take(6)) {
            lines.add("🔵 诊断[${diagnosis.kind.label}] ${diagnosis.title}")
            if (lines.size >= MAX_LINES) break
        }
        for (step in plan.steps.take(4)) {
            if (lines.size >= MAX_LINES) break
            lines.add("🔵 规划步骤：${step.title}（${step.kind.label}，风险 ${step.risk.level.label}）")
        }
        return ReconReport(RECON_REFACTOR_PLAN, "重构规划侦察", lines)
    }

    private fun empty(reconId: String): ReconReport =
        ReconReport(reconId, "", emptyList())
}
