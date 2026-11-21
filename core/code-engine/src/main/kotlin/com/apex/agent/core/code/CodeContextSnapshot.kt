package com.apex.agent.core.code

/**
 * # Code Context Snapshot — Coding 会话上下文快照（上下文查看工具的数据源）
 *
 * 由 [CodeEngineFacade.contextSnapshot] 产出：当前激活逻辑线的会话消息
 * 序列 + 提示注入段 + token 用量，一次性拉给 UI 展示（不进 Compose 状态流
 * —— 查看是即时动作，点开时拉一次即可，避免会话推进带来的高频重建）。
 *
 * @param entries 会话消息序列（角色 + 文本内容；助手消息的工具调用以括注
 *   形式并入 content，不单列结构化字段 —— 查看工具面向人读）。
 *   三条 kit 线（标准/织造/信使）的引擎未开放消息导出 → 空列表（UI 显示
 *   降级说明，诚实告知而非假装有内容）。
 * @param globalRules 全局规则注入段（仅深潜线携带；null = 该线不暴露）。
 * @param sessionExtras 「小圆环」会话上下文附加段（仅深潜线携带）。
 * @param tokenUsed 当前上下文 token 估算。
 * @param tokenMax 上下文窗口预算。
 */
data class CodeContextSnapshot(
    val entries: List<CodeContextEntry>,
    val globalRules: String?,
    val sessionExtras: String?,
    val tokenUsed: Int,
    val tokenMax: Int
)

/** 消息角色（查看工具的徽标着色用；与 LlmMessage 四型一一对应）。 */
enum class CodeContextRole { SYSTEM, USER, ASSISTANT, TOOL }

/** 单条会话消息（人读文本形态）。 */
data class CodeContextEntry(
    val role: CodeContextRole,
    val content: String
)
