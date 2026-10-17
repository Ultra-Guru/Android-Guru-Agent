package com.apex.agent.core.engine

import com.apex.agent.core.engine.plan.PLAN_CONFIRMATION_TIMEOUT_MS
import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.ToolCall
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * AskUser 工具交互流（自 [ApexAgentEngine] 迁出，God-file 预算拆分；
 * 模式与 AgentChatEventApplier 相同：同包扩展 + internal 成员直调）。
 *
 * 语义：模型调用 `AskUser` 时，引擎暂停循环 → 发 [AgentEvent.UserInputRequired]
 * → 挂起等待 UI 回传（[ApexAgentEngine.awaitUserInput]）→ 以 ToolResult
 * 形式回填"User answered: …" → 发 ToolCallComplete（success=true）→ 循环继续。
 *
 * #214 超时语义：等待超时 → awaitUserInput 返回 null（并已发射
 * [AgentEvent.UserInputExpired] 让 UI 关闭挂起的输入框）→ 以
 * 「用户输入超时」显式收场（ToolCallComplete success=false + ToolResult
 * 说明），绝不拿空答案继续跑 —— 模型不会把用户的沉默误读为默许。
 */
internal suspend fun ApexAgentEngine.handleAskUserToolCall(
    toolCall: ToolCall,
    emit: suspend (AgentEvent) -> Unit
): Boolean {
    if (toolCall.name != "AskUser") return false

    val args = try {
        kotlinx.serialization.json.Json.parseToJsonElement(toolCall.arguments).jsonObject
    } catch (_: Exception) {
        emptyMap<String, String>()
    }
    // P3-d 修复：旧实现 `?.toString()?.trim('"')` 依赖 JsonPrimitive.toString()
    // 的带引号表示，非字符串 JSON 值（数字/嵌套对象）会变成 JSON 文本，
    // 且 trim 会误伤字符串末尾本身含引号的内容。改用 jsonPrimitive.content。
    val question = (args["question"] as? JsonPrimitive)?.contentOrNull ?: "Please provide input:"
    val inputType = (args["type"] as? JsonPrimitive)?.contentOrNull?.lowercase() ?: "text"
    val eventType = when (inputType) {
        "confirmation" -> InputType.CONFIRMATION
        "choice" -> InputType.CHOICE
        else -> InputType.TEXT
    }
    // #340 注册先行：UserInputRequired 的 emit 移入 beforeWait —— 注册（与
    // abort 同锁互斥）先于 emit，submit 不再可能落在注册前（旧窗口：emit 经
    // 镜像 tap 快速消费返回 → 用户极快提交/自动化提交在 deferred 赋值前到达
    // → complete 落空 → 引擎挂满 5 分钟，chat 模式卡死观感的直接机制）。
    // 等待时长回填（旧实现硬编码 0）：用户等 5 分钟与 1 秒在统计上无差别。
    // core 纯 JVM —— 用 System.nanoTime 差值（不受墙钟回拨影响）。
    val waitStartNs = System.nanoTime()
    val answer = awaitUserInput(
        onExpired = { emit(AgentEvent.UserInputExpired) },
        beforeWait = { emit(AgentEvent.UserInputRequired(question, eventType)) }
    )
    val waitedMs = (System.nanoTime() - waitStartNs) / 1_000_000L
    if (answer == null) {
        // #214 超时未决：显式失败收场（UserInputExpired 已由 onExpired 发射）。
        val timeoutNote = "User input timed out after ${PLAN_CONFIRMATION_TIMEOUT_MS / 1000}s; " +
            "the question was closed unanswered. Do not treat silence as consent — " +
            "proceed with the safest default or re-ask the user later."
        addMessage(LlmMessage.ToolResult(toolCall.id, timeoutNote))
        emit(
            AgentEvent.ToolCallComplete(
                callId = toolCall.id,
                toolName = toolCall.name,
                arguments = toolCall.arguments,
                output = timeoutNote,
                success = false,
                // 超时等待同样是用户可感知的等待时长，与正常回答同口径计时。
                durationMs = waitedMs
            )
        )
        return true
    }
    addMessage(LlmMessage.ToolResult(toolCall.id, "User answered: $answer"))
    emit(
        AgentEvent.ToolCallComplete(
            callId = toolCall.id,
            toolName = toolCall.name,
            arguments = toolCall.arguments,
            output = "User answered: $answer",
            success = true,
            durationMs = waitedMs
        )
    )
    return true
}
