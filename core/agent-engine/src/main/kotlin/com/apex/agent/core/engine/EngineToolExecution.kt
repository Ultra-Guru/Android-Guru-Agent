package com.apex.agent.core.engine

import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.ToolCall
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.core.engine.orchestrator.RetryPolicy
import com.apex.agent.core.tools.ToolStreamEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * ═══ 工具调用流式执行（自 [ApexAgentEngine] 迁出，God-file 预算拆分）═══
 *
 * 模式与 [EngineAskUserFlow] / [EngineCompressionGate] 一致：同包顶层扩展 +
 * internal 成员直调，引擎主循环调用点零改动（`executeToolCallStreaming(...)`）。
 *
 * 本版新增 **长任务韧性**（详见 [EngineResilienceGuard]）：
 * - 工具瞬时失败（网络断连/超时/限流 —— orchestrator FailureClassifier 判
 *   TRANSIENT/TIMEOUT）指数退避自动重试（任务级预算）；权限/致命失败不重试，
 *   立刻回灌 LLM 自行换路（旧语义）；
 * - 连续 N 个工具调用失败 → 注入系统级换路提示（换工具/换参数/换分解）。
 *
 * diag-20261011 修复（配对类 400 会话级报废的成因）：本文件内三处
 * System 提示（韧性换路 / 思考自检 / 循环恢复）曾直接 `addMessage` ——
 * 注入点落在 Assistant(tool_calls) 与其 ToolResult 之间或并行批中间，
 * 违反「tool 消息必须紧随 tool_calls 消息」→ Provider 400 且旧修复器
 * 无法归位（隔块 ToolResult 不参与配对）→ 损坏持久化、会话报废。现在统一
 * **延迟注入**（[deferSystemNote]）：先入引擎缓冲队列，整批工具结果写完后
 * 由主循环统一 [flushPendingSystemNotes]（位置永远在完整 tool 块之后）。
 */

/**
 * 延迟注入的系统提示入队（位置安全性见文件头）。
 *
 * 任何在工具执行期产生的 System 提示都走此队列，绝不直接写历史 ——
 * OpenAI 兼容端点要求 tool 消息紧随 tool_calls 消息，批中插队即 400。
 */
internal fun ApexAgentEngine.deferSystemNote(note: String) {
    pendingSystemNotes.addLast(note)
}

/**
 * 冲刷延迟注入的系统提示：整批 ToolResult 落盘后调用（迭代尾/迭代头两个
 * 安全点），保证提示落在完整 tool 块之后、下一轮请求之前。
 */
internal fun ApexAgentEngine.flushPendingSystemNotes() {
    while (pendingSystemNotes.isNotEmpty()) {
        addMessage(LlmMessage.System(pendingSystemNotes.removeFirst()))
    }
}
/**
 * 流式执行单个工具调用。
 *
 * 取代旧的 `toolExecutor.execute(...)` 一次性调用。收集
 * [ToolExecutor.executeStream] 的事件流：
 * - [ToolStreamEvent.Output] → 追加到 [outputBuilder] 并即时发射
 *   [AgentEvent.ToolOutputChunk]，让 UI 在工具执行期间就能看到实时输出
 *   （如 shell 的逐行输出）。
 * - [ToolStreamEvent.Progress] → 发射 [AgentEvent.ToolProgress]，UI 显示进度条。
 * - [ToolStreamEvent.Complete] → 仅当此前没有任何 Output（非典型）时才把
 *   `output` 补发一次，保证 UI 不空；否则忽略（以累积值为准）。
 * - [ToolStreamEvent.Error] → 追加到 [outputBuilder] 并发射一条 ToolOutputChunk，
 *   使失败信息也实时可见。
 *
 * 收集结束后（或捕获到异常），[outputBuilder] 即为 `rawOutput`，沿用原有的
 * P7 截断 + ToolCallComplete + 写入 LlmMessage.ToolResult 流程 —— 因此成功
 * 判定（`!result.startsWith("Error")`）与历史持久化行为与旧实现完全一致。
 *
 * [CancellationException] 重抛，使 `abort()` 能沿 `collect` → 工具 Flow →
 * 底层进程（如 `Process.destroy()`）传播。
 */
internal suspend fun ApexAgentEngine.executeToolCallStreaming(
    toolCall: ToolCall,
    emit: suspend (AgentEvent) -> Unit
) {
    // v4：模型回显的是 provider 安全名（TerminalExecLegacy）；执行器/截断策略
    // 需要注册表 id（TerminalExec）——经当前计划的反向映射解析。
    // 无映射时（旧会话回放/模型直呼 registry id）原样直查，两条路都通。
    val registryToolId = EngineToolPlanner.registryIdOf(currentToolPlan, toolCall.name)

    emit(
        AgentEvent.ToolCallStart(
            callId = toolCall.id,
            toolName = toolCall.name,
            arguments = toolCall.arguments
        )
    )

    val toolStart = System.currentTimeMillis()
    val outputBuilder = StringBuilder()

    // 以流式事件信号为主判定成败：收到 ToolStreamEvent.Error 或捕获异常
    // 即视为失败。这样工具合法输出以 "Error" 开头（如 "Error: foo not found" 这类
    // 真实数据）也不会被误判为执行失败。
    var hadStreamError = false
    // ═══ 长任务韧性：工具瞬时失败自动重试 ═══
    // 网络断连/超时/限流类失败（orchestrator FailureClassifier 判 TRANSIENT/TIMEOUT）
    // 指数退避后重试同一调用（任务级预算内）；权限/致命失败不重试 —— 立刻把
    // 错误回灌给 LLM 让其自行换路（与旧语义一致）。重试前清空失败尝试的部分
    // 输出，保证写入历史的只有最终一次尝试的结果。
    var retryAttempt = 0
    while (true) {
        hadStreamError = false
        var streamException: Throwable? = null
        var streamErrorMessage: String? = null
        try {
            toolExecutor.executeStream(registryToolId, toolCall.arguments).collect { event ->
                when (event) {
                    is ToolStreamEvent.Output -> {
                        outputBuilder.append(event.chunk)
                        emit(
                            AgentEvent.ToolOutputChunk(
                                callId = toolCall.id,
                                chunk = event.chunk
                            )
                        )
                    }
                    is ToolStreamEvent.Progress -> {
                        emit(
                            AgentEvent.ToolProgress(
                                callId = toolCall.id,
                                percent = event.percent,
                                message = event.message
                            )
                        )
                    }
                    is ToolStreamEvent.Complete -> {
                        // 防御：仅当工具只发 Complete 没发 Output（非典型）时补发。
                        if (outputBuilder.isEmpty() && event.output.isNotEmpty()) {
                            outputBuilder.append(event.output)
                            emit(
                                AgentEvent.ToolOutputChunk(
                                    callId = toolCall.id,
                                    chunk = event.output
                                )
                            )
                        }
                    }
                    is ToolStreamEvent.Error -> {
                        hadStreamError = true
                        streamErrorMessage = event.message
                        outputBuilder.append(event.message)
                        emit(
                            AgentEvent.ToolOutputChunk(
                                callId = toolCall.id,
                                chunk = event.message
                            )
                        )
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            hadStreamError = true
            streamException = e
            streamErrorMessage = e.message ?: "tool execution failed"
        }

        if (!hadStreamError) break
        retryAttempt++
        val retryDecision = resilience.onToolFailure(
            toolCall.name, toolCall.id, streamErrorMessage ?: "", retryAttempt
        )
        if (retryDecision is RetryPolicy.RetryDecision.Retry) {
            AppLogger.instance.warn(
                LogCategory.TOOL, "ApexAgentEngine",
                "工具瞬时失败（${toolCall.name}，第 ${retryAttempt} 次），退避 ${retryDecision.delayMs}ms 后重试: ${streamErrorMessage?.take(120)}"
            )
            emit(
                AgentEvent.ToolOutputChunk(
                    callId = toolCall.id,
                    chunk = "\n[engine] 瞬时失败（${streamErrorMessage?.take(80)}）— " +
                        "${retryDecision.delayMs / 1000.0}s 后自动重试（第 $retryAttempt 次）\n"
                )
            )
            outputBuilder.setLength(0)
            delay(retryDecision.delayMs)
            continue
        }
        // 最终失败：异常路径补 "Error: " 前缀（Error 事件路径已在流内追加原文）。
        if (streamException != null) {
            outputBuilder.append("Error: $streamErrorMessage")
        }
        break
    }

    val duration = System.currentTimeMillis() - toolStart

    // P7 Layer 1: 工具输出截断（始终生效）
    val rawOutput = outputBuilder.toString()
    val truncationResult = toolTruncator.smartTruncate(rawOutput, registryToolId)
    val result = truncationResult.text

    // 成功判定：优先采用流式事件信号；仅当工具未发任何 Error 事件且
    // 异常分支未触发时，才回退到文本前缀检测（兼容只返回 "Error: ..." 文本
    // 而不发 Error 事件的旧工具）。
    val actionSuccess = !hadStreamError && !result.startsWith("Error")
    if (!actionSuccess) anyActionFailed = true

    // ═══ 长任务韧性：连续失败 → 延迟注入换路提示 ═══
    // 连续 N 个工具调用失败时，向历史注入一条系统级 recovery 提示，
    // 明确要求模型换工具/换参数/换分解方式 ——「不会用其他方式」的闭环；
    // 预算用尽/未达阈值返回 null 零开销。
    // diag-20261011：经 [deferSystemNote] 延迟到整批工具结果之后落盘 ——
    // 此处直接 addMessage 会插在 ToolResult 之前，破坏配对（400 根因）。
    resilience.onToolCallOutcome(toolCall.name, actionSuccess)?.let {
        deferSystemNote(it)
    }

    emit(
        AgentEvent.ToolCallComplete(
            callId = toolCall.id,
            toolName = toolCall.name,
            arguments = toolCall.arguments,
            output = result.take(thinkingController.resolveToolOutputBudget(config.maxToolOutputLength)),
            fullOutput = rawOutput.take(100_000),
            success = actionSuccess,
            durationMs = duration
        )
    )

    // 截断后的结果存入历史（节省后续 token）
    addMessage(LlmMessage.ToolResult(toolCall.id, result))
    // #168：工具计数 + DEEP/MAXIMUM 档在失败/HIGH 风险后注入自检提示（下一轮
    // LLM 可见）。diag-20261011：经 [deferSystemNote] 延迟落盘 —— 并行工具批
    // 中直接 addMessage 会插在本批后续 ToolResult 之前，破坏配对（400 根因）。
    thinkingController.postToolCheckPrompt(
        registryToolId, actionSuccess, toolRegistry.metadataOf(registryToolId)?.isHighRisk == true
    )?.let { deferSystemNote(it) }

    // #170：终端主动性 —— 一次性 shell 连击/失败连击滑窗（下一迭代判定建议）。
    terminalAdvisor.onToolCallCompleted(registryToolId, actionSuccess, toolCall.arguments)

    // ═══ 循环检测（编排器路径同款下沉，3-C/C8）═══
    // 引擎路径此前只有失败连击统计（resilience.onToolCallOutcome），模型反复
    // 调用同一「成功」工具的空转检测不到。每个逻辑调用（含内部重试收敛后）
    // record 一次；检出重复/振荡 → 注入换路提示并冷却窗口（一次循环只提示
    // 一次）。恢复预算用尽后不再注入 —— 终止兜底交给 maxIterations。
    loopDetector.record(toolCall.name, toolCall.arguments)
    loopDetector.detect()?.let { signal ->
        loopDetector.acknowledge()
        if (loopRecovery.canRecover()) {
            // diag-20261011：同上，延迟落盘防插队。
            deferSystemNote(loopRecovery.buildLoopRecoveryPrompt(signal))
            AppLogger.instance.warn(
                LogCategory.TOOL, "ApexAgentEngine",
                "loop detected (${signal::class.simpleName}) after '${toolCall.name}' — " +
                    "recovery prompt deferred"
            )
        }
    }

    // 隐式记忆采集（报告 P2）：记录每个已执行动作及其成败。
    // 传入 actionSuccess 供 CS-Mem 蒸馏时过滤失败动作（避免"鼠标连点失败"
    // 也被压进 FSM 宏技能，使学到的宏技能必然无法回放）。
    memoryObserver?.onActionExecuted(
        "${toolCall.name}(${toolCall.arguments.take(120)})",
        success = actionSuccess
    )
}


