package com.apex.agent.core.engine.templates.runtime.builtin

import com.apex.agent.core.engine.templates.BuiltinPromptTemplates
import com.apex.agent.core.engine.templates.runtime.AgentTemplateBehavior

/**
 * ═══ 内置模板行为目录（PR 15）═══
 *
 * 十个内置模板 ↔ 十个代码行为的 1:1 装订表（模板运行时系列的收口）。
 * 激活内置模板时，[com.apex.agent.core.engine.templates.runtime.TemplateRuntime]
 * 经此目录绑定行为；目录外的模板（用户自定义）回落兜底行为。
 *
 * 完整性由 `BuiltinBehaviorCatalogTest` 锁死：
 * - 目录键集 == 内置模板 id 集（多一个少一个都红）；
 * - 每个行为清单自洽（behaviorId / 契约 / 徽标）；
 * - 每个行为确定性冒烟（同输入两次分析结果一致）。
 */
object BuiltinBehaviorCatalog {

    /** 全部内置行为（id → 行为实例；行为无状态，进程内共享安全）。 */
    val ALL: Map<String, AgentTemplateBehavior> = listOf(
        CodeReviewTemplateBehavior(),
        CommitMessageTemplateBehavior(),
        TranslateTemplateBehavior(),
        SummarizeTemplateBehavior(),
        RefactorPlanTemplateBehavior(),
        BugReportTemplateBehavior(),
        ApiDesignTemplateBehavior(),
        TestCasesTemplateBehavior(),
        TechDocTemplateBehavior(),
        RegexExplainTemplateBehavior()
    ).associateBy { it.behaviorId }

    /** 行为查询（未知 id 返回 null —— 调用方回落兜底行为）。 */
    fun behaviorFor(templateId: String): AgentTemplateBehavior? = ALL[templateId]

    /** 是否有对应行为。 */
    fun hasBehaviorFor(templateId: String): Boolean = templateId in ALL
}
