package com.apex.agent.core.engine

/**
 * # 三档自主级别（v4 Coding 模式级联选择器）
 *
 * Build / Goal 两档执行模式的**提问策略**维度（与 AgentMode 正交）：
 * - [INTERACTIVE]（交互式，默认）：任务过程中只要存在影响正确性的歧义
 *   就向用户提问；同类问题合并一次问清，不重复问；
 * - [SEMI]（半自主）：只在「重要且影响验收 / 架构 / 数据 / 安全 / 外部
 *   依赖 / 成本 / 不可逆」时提问，其余自行决策并记录假设；
 * - [FULLY]（完全自主）：执行全程零提问，自行决策并记录假设与风险；
 *   不可逆 / 安全 / 权限 / 付费等高风险操作不得提问，应停止该操作并在
 *   结果中报告阻塞。
 *
 * ## 注入通道（AGENTS.md「薄包装不重写」纪律）
 *
 * - **深潜线**：[AgentConfig.autonomyLevel] + [AgentConfig.fullAutoPreClarify]
 *   经 patchConfig 拍平，[EnginePrompts] 在 Mode 段后追加 [promptInstruction]
 *   的 "## Autonomy" 段（null = 段落省略，PLAN 模式与既有调用方零变化）；
 * - **kit 三线**（standard / loom / courier）：kit 库无自由 system context
 *   通道，由各 Adapter 把 [promptInstruction] 追加进 updateSessionExtras
 *   透传文本（见 StandardKitAdapter / LoomKitAdapter / CourierKitAdapter）；
 * - 四线同步入口：[com.apex.agent.core.code.standard.DualLogicCodeEngine]
 *   的 updateAutonomy（与 updateMode 同口径）。
 *
 * ## 前置澄清（仅 FULLY 生效）
 *
 * 「开始前集中澄清」开关（[AgentConfig.fullAutoPreClarify]，默认关）：
 * - 开：任务开始前 agent 必须一次性列出所有需要确认的问题并集中询问；
 *   用户回答或跳过后，执行过程中绝不再提问；跳过的按合理默认假设执行
 *   并记录；
 * - 关：任务开始前也不提问，执行中也不提问，全程自行决策并记录
 *   「假设 / 风险」；高风险操作停止并报告阻塞（见 [promptInstruction]）。
 */
enum class AutonomyLevel(val persistenceName: String) {

    /** 交互式（默认）：影响正确性的歧义必问，同类合并。 */
    INTERACTIVE("interactive"),

    /** 半自主：仅重要且影响验收的事项提问，其余自行决策并记录假设。 */
    SEMI("semi"),

    /** 完全自主：执行全程零提问，记录假设与风险；高风险停手报告。 */
    FULLY("fully");

    /**
     * 提问策略指令（system prompt "## Autonomy" 段正文，中英与 EnginePrompts
     * 各 Mode 段同款惯例：英文硬约束、面向主模型）。深潜线经 EnginePrompts
     * 拼接，kit 三线经各 Adapter 的 sessionExtras 透传，两处共用本单一事实源。
     */
    fun promptInstruction(preClarify: Boolean): String = buildString {
        appendLine("## Autonomy: ${sectionLabel()}")
        when (this@AutonomyLevel) {
            INTERACTIVE -> {
                appendLine("- Ask the user whenever a correctness-affecting ambiguity exists during the task.")
                appendLine("- Merge same-topic questions into a single batched ask; never re-ask an answered question.")
            }
            SEMI -> {
                appendLine("- Ask ONLY when a decision materially affects acceptance, architecture, data safety,")
                appendLine("  security, external dependencies, cost, or is irreversible. Decide everything else")
                appendLine("  yourself and record the assumptions you made in the final report.")
            }
            FULLY -> {
                if (preClarify) {
                    appendLine("- BEFORE starting the task, list ALL questions that need confirming in ONE batch")
                    appendLine("  and ask them together exactly once. After the user answers or skips, NEVER ask")
                    appendLine("  again; execute skipped items on reasonable default assumptions and record them.")
                } else {
                    appendLine("- Do NOT ask the user anything, neither before nor during execution.")
                }
                appendLine("- Decide everything yourself and record assumptions and risks in the final report.")
                appendLine("- High-risk operations (irreversible changes, security, permissions, paid services):")
                appendLine("  do NOT ask — stop that operation and report the blocker with options in the result.")
                appendLine("- Never surface any question or confirmation prompt to the user while executing.")
            }
        }
    }

    /** Autonomy 段标题里的级别标签（英文短标签 + 一句话口径）。 */
    private fun sectionLabel(): String = when (this) {
        INTERACTIVE -> "INTERACTIVE (ask on ambiguity)"
        SEMI -> "SEMI_AUTONOMOUS (ask only what is material)"
        FULLY -> "FULLY_AUTONOMOUS (zero questions)"
    }

    companion object {
        /**
         * 持久化名解析（大小写容错）：interactive / semi / fully。
         * 未知或空白返回 null，调用方兜底 [INTERACTIVE]（Build 默认交互式）。
         */
        fun fromName(name: String?): AutonomyLevel? =
            name?.trim()?.lowercase()?.let { raw ->
                entries.firstOrNull { it.persistenceName == raw }
            }
    }
}
