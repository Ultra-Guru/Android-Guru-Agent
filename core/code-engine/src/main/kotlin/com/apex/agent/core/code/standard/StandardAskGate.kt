package com.apex.agent.core.code.standard

import com.apex.agent.core.engine.AgentEvent
import com.apex.agent.core.engine.InputType

/**
 * # Coding 屏 ASK 权限门等待（issue #340 注册先行整改）
 *
 * 自 [StandardModeEngine] 拆出的同包扩展（God-file 1200 行预算，模式同引擎侧
 * EngineAskUserFlow.kt）：[StandardModeEngine.awaitUserInput] 先注册 deferred
 * 再经 [onRegistered][awaitUserInput] 发射 UserInputRequired —— 提交方与
 * 注册方之间的 happens-before 窗口结构性消除（PR #330 实证竞态根因：
 * TaskRuntime 镜像收集使 emit 立即返回，submit 在 deferred 创建前到达则
 * complete 落空，引擎挂满 5 分钟）。
 */
internal suspend fun StandardModeEngine.awaitPermissionAsk(
    toolName: String,
    preview: String,
    emit: suspend (AgentEvent) -> Unit
): String = awaitUserInput {
    emit(
        AgentEvent.UserInputRequired(
            prompt = "工具 `$toolName` 请求执行（权限门：${toolName}）。\n" +
                "参数预览：$preview\n" +
                "回复「允许」= 本次执行；「总是」= 本会话内该工具总允许；" +
                "其他任意回复 = 拒绝（回复内容会作为指示转达给模型）；不回复 = 拒绝。",
            type = InputType.CONFIRMATION
        )
    )
}
