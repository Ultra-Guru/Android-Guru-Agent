package com.apex.agent.core.engine

/**
 * 执行记忆观察者 —— 解耦 agent-engine（纯 JVM）与 platform:cs-mem（Android）。
 *
 * CS-Mem 的记忆采集应当是"隐式"的：Agent 执行任务时自动记录 UI 轨迹，
 * 无需 LLM 主动调用记忆工具。为此 agent-engine 在任务生命周期的关键节点
 * 回调本接口，由 app 层用 [com.apex.agent.platform.csmem.session.CsMemSessionManager]
 * 实现并注入。
 *
 * 设计要点：
 * - 所有方法均为 suspend 且有默认空实现，便于 engine 无条件调用（观察者未注入时静默跳过）。
 * - 观察者实现内部必须自行处理异常（如无障碍未开启），绝不能阻断 Agent 主流程。
 * - 这是"隐式记忆"闭环（报告 P2）的核心接口；Bypass/Recall 工具（P3）是另一路径。
 */
interface ExecutionMemoryObserver {

    /**
     * Agent 任务开始时调用。对应 CsMemSessionManager.startSession()。
     *
     * @param goal 任务目标文本
     * @param appPackage 当前前台 App 包名（可能为空）
     */
    suspend fun onTaskStart(goal: String, appPackage: String?) {
        // 默认空实现
    }

    /**
     * Agent 每执行完一个工具/动作后调用。对应 CsMemSessionManager.afterAction()。
     *
     * @param actionDescription 刚执行的动作描述（如 "UiTap(540,1200)"）
     * @param success 该动作是否执行成功；用于 CS-Mem 蒸馏时过滤失败动作，避免把
     *  "鼠标连点失败"也压进 FSM 宏技能。默认 true 以兼容未更新的调用方。
     */
    suspend fun onActionExecuted(actionDescription: String, success: Boolean = true) {
        // 默认空实现
    }

    /**
     * Agent 任务结束时调用。对应 CsMemSessionManager.finishSession()。
     *
     * @param success 任务是否成功完成
     */
    suspend fun onTaskFinish(success: Boolean) {
        // 默认空实现
    }

    /**
     * 在每轮 LLM 推理前尝试"肌肉记忆"旁路执行（报告 P3/P4 闭环）。
     *
     * 若记忆中存在匹配当前 UI 的 FSM 宏技能且验证通过，直接执行并跳过 LLM；
     * 若不匹配或执行失败，返回对应状态由引擎照常走 LLM 决策。
     *
     * 默认返回 [BypassOutcome.NotAttempted]（观察者未注入时静默跳过）。
     */
    suspend fun tryBypass(): BypassOutcome = BypassOutcome.NotAttempted

    /**
     * 聊天记忆召回（对话自动记忆的读取面）。
     *
     * 引擎在 execute() 入口、构建本轮消息前调用：实现方基于用户文本
     * 检索长期记忆（用户偏好 / 背景事实 / 历史任务结论），返回一段
     * 已格式化的上下文文本（注入系统提示词 "## Remembered About You" 段）；
     * 无可召回内容返回 null（段落省略，既有调用方零变化）。
     *
     * 实现约定：快（内存索引 / 本地文件读取级别），失败必须吞掉返回 null
     * ——绝不因记忆子系统异常阻断主对话。
     */
    suspend fun recallChatMemory(userText: String): String? = null

    /**
     * 聊天记忆自动沉淀（对话自动记忆的写入面）。
     *
     * 引擎在每轮 execute() 收尾（finally）调用一次：传入本轮用户原文与
     * 最终助手回复。实现方异步提取值得长期记住的事实（用户自述、偏好、
     * 进行中的事项），写入长期存储。轻量启发式提取可零成本同步完成；
     * LLM 蒸馏类重活应转后台协程，本方法不应阻塞超过几十毫秒。
     *
     * 默认空实现（观察者未注入 / 未实现聊天记忆时静默跳过）。
     */
    suspend fun onConversationTurn(userText: String, assistantText: String) {
        // 默认空实现
    }
}

/**
 * 旁路执行结果 —— 引擎据此决定是否跳过本轮 LLM。
 */
sealed class BypassOutcome {
    /** 未尝试（观察者未注入/不可用），引擎照常走 LLM */
    object NotAttempted : BypassOutcome()

    /** 无匹配的宏技能，引擎照常走 LLM */
    object NotMatched : BypassOutcome()

    /** 旁路执行成功，已跳过 LLM；[actionCount] 为执行的动作数 */
    data class Executed(val actionCount: Int) : BypassOutcome()

    /** 旁路执行失败（偏离/异常），引擎应回退到 LLM 接管 */
    data class Failed(val reason: String) : BypassOutcome()
}
