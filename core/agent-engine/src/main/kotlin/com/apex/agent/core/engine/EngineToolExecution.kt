package com.apex.agent.core.engine

import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.ToolCall
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.core.engine.orchestrator.RetryPolicy
import com.apex.agent.core.tools.ToolResultMeta
import com.apex.agent.core.tools.ToolStreamEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/**
 * ═══ 工具调用流式执行（自 [ApexAgentEngine] 迁出，God-file 预算拆分）═══
 *
 * 模式与 [EngineAskUserFlow] / [EngineCompressionGate] 一致：同包顶层扩展 +
 * internal 成员直调，引擎主循环调用点零改动（`executeToolCallTurn(...)`）。
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
 *
 * ═══ 工具循环 v2：并行批执行（发言权协议）═══
 *
 * [AgentConfig.toolCallParallelism] > 1 时，同一轮 Assistant 消息的多个
 * tool_calls 分块并行**执行**（IO 并发、互不等待），但保证两条不变量：
 *
 * 1. **事件时间线不撕裂**：UI 的 currentToolCall 单卡设计一次只跟踪一个
 *    callId（迟到 chunk 会被丢弃）。工作协程只把事件塞进各自的
 *    [Channel]（线程安全），由流属主协程按「发言权」顺序放行——每个工具
 *    的 Start → Output → Complete 生命周期在时间线上连续，一个讲完下一个
 *    接着讲。执行并行、呈现顺序，两全。
 * 2. **历史与检测器串行落盘**：ToolResult / 循环检测 / 韧性统计 / 终端
 *    建议器等有状态组件只由流属主协程按**原始 tool_calls 顺序**访问——
 *    无锁、无竞态、配对顺序稳定（[finalizeToolCallOutcome] 收口）。
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

/** 单个工具调用的执行产物（core 阶段输出，finalize 阶段消费）。 */
internal class ToolExecutionOutcome(
    /** 截断后的工具输出（写历史用）。 */
    val result: String,
    /** 流式信号判定成败（无 Error 事件且无异常，详见 core 注释）。 */
    val success: Boolean,
    /** 注册表 id（provider 安全名 → 注册表名的解析结果，检测器/元数据用）。 */
    val registryToolId: String
)

/**
 * ═══ 单轮工具调度核心（主循环唯一入口）═══
 *
 * 职责链：单轮上限截断 → Assistant(tool_calls) 配对锚点落盘 → AskUser
 * 保序穿插（挂起等用户不可并行）→ 其余工具按 [AgentConfig.toolCallParallelism]
 * 分块并行执行 → 冲刷延迟 System 提示（位置安全点）。
 *
 * AskUser 保序语义：模型输出 [Read, AskUser, Write] 时执行序为 Read 批 →
 * AskUser（挂起等用户）→ Write 批 —— 与历史顺序一致（问在答后的意图不被
 * 打乱）；AskUser 之后的工具不因它而失去并行机会。
 */
internal suspend fun ApexAgentEngine.executeToolCallTurn(
    contentText: String,
    toolCalls: List<ToolCall>,
    emit: suspend (AgentEvent) -> Unit
) {
    val capped = capToolCallsPerTurn(toolCalls)
    if (capped.isEmpty()) return

    // 配对锚点：Assistant 消息携带**实际执行**的 tool_calls（截断后的子集，
    // 与逐个 ToolResult 一一对应——绝不出现悬空 id）。
    addMessage(LlmMessage.Assistant(contentText, capped))

    val parallelism = config.toolCallParallelism.coerceIn(1, MAX_TOOL_PARALLELISM)
    var run = mutableListOf<ToolCall>()
    for (toolCall in capped) {
        if (toolCall.name == "AskUser") {
            executeToolRun(run, parallelism, emit)
            run = mutableListOf()
            // 挂起等待用户输入（流程拆至 EngineAskUserFlow.kt），不可并行。
            handleAskUserToolCall(toolCall, emit)
        } else {
            run.add(toolCall)
        }
    }
    executeToolRun(run, parallelism, emit)

    // diag-20261011：整批 ToolResult 落盘后冲刷延迟 System 提示
    //（位置安全点：完整 tool 块之后，下一轮请求之前）。
    flushPendingSystemNotes()
}

/** 单轮调用数上限截断（[AgentConfig.maxToolCallsPerTurn] ≤ 0 = 不限制）。 */
private fun ApexAgentEngine.capToolCallsPerTurn(toolCalls: List<ToolCall>): List<ToolCall> {
    val cap = config.maxToolCallsPerTurn
    if (cap <= 0 || toolCalls.size <= cap) return toolCalls
    deferSystemNote(
        "This turn requested ${toolCalls.size} tool calls, but the per-turn cap is $cap. " +
            "Only the first $cap were executed this round — re-issue the remaining " +
            "calls in your next turn (they were NOT silently dropped from your intent)."
    )
    AppLogger.instance.warn(
        LogCategory.TOOL, "ApexAgentEngine",
        "tool calls capped per turn: ${toolCalls.size} → $cap (deferred to next round)"
    )
    return toolCalls.take(cap)
}

/** 一个无 AskUser 的连续工具段：并行度 1 或单个调用走顺序路径，否则分块并行。 */
private suspend fun ApexAgentEngine.executeToolRun(
    run: List<ToolCall>,
    parallelism: Int,
    emit: suspend (AgentEvent) -> Unit
) {
    if (run.isEmpty()) return
    if (parallelism <= 1 || run.size == 1) {
        for (toolCall in run) executeToolCallStreaming(toolCall, emit)
        return
    }
    run.chunked(parallelism).forEach { chunk -> executeToolCallChunk(chunk, emit) }
}

/** 并行工具并行度上限（引擎侧硬顶：再多只会压垮终端/文件 IO）。 */
private const val MAX_TOOL_PARALLELISM = 8

/**
 * 并行块执行（发言权协议，见文件头）。工作协程在 [Dispatchers.IO] 上跑
 * 工具本体，事件入各自 Channel；流属主协程按序放行完整生命周期。
 */
private suspend fun ApexAgentEngine.executeToolCallChunk(
    chunk: List<ToolCall>,
    emit: suspend (AgentEvent) -> Unit
) = coroutineScope {
    val queues = chunk.map { Channel<AgentEvent>(Channel.UNLIMITED) }
    val outcomes = arrayOfNulls<ToolExecutionOutcome>(chunk.size)
    val jobs = chunk.mapIndexed { idx, toolCall ->
        launch(Dispatchers.IO) {
            try {
                outcomes[idx] =
                    executeToolCallStreamingCore(toolCall) { event -> queues[idx].send(event) }
            } finally {
                // 哨兵闭合：core 正常收尾必发 Complete（先于 close 被 drain）；
                // 若异常路径未发 Complete，closed channel 让 receive 显式失败
                // 而非挂死。
                queues[idx].close()
            }
        }
    }
    // 发言权放行：按原始顺序，每个工具讲完（Complete）下一个才开口。
    for (i in chunk.indices) {
        while (true) {
            val event = queues[i].receive()
            emit(event)
            if (event is AgentEvent.ToolCallComplete) break
        }
    }
    // 工作协程收尾（CancellationException 在此传播为整任务 abort）。
    jobs.joinAll()
    // 历史与有状态检测器：原始顺序串行落盘（无锁正确性收口）。
    chunk.forEachIndexed { i, toolCall -> finalizeToolCallOutcome(toolCall, outcomes[i]!!) }
}

/**
 * 顺序路径的单工具完整执行（core + finalize 的组合，语义与旧
 * `executeToolCallStreaming` 逐行一致）。
 */
internal suspend fun ApexAgentEngine.executeToolCallStreaming(
    toolCall: ToolCall,
    emit: suspend (AgentEvent) -> Unit
) {
    val outcome = executeToolCallStreamingCore(toolCall, emit)
    finalizeToolCallOutcome(toolCall, outcome)
}

/**
 * 流式执行单个工具调用（core：事件与流，不碰历史）。
 *
 * 收集 [ToolExecutor.executeStream] 的事件流：
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
 * P7 截断 + ToolCallComplete 流程 —— 因此成功判定（流式 Error 信号）与
 * 历史持久化行为与旧实现完全一致（历史写入移至 [finalizeToolCallOutcome]）。
 *
 * [CancellationException] 重抛，使 `abort()` 能沿 `collect` → 工具 Flow →
 * 底层进程（如 `Process.destroy()`）传播。
 */
internal suspend fun ApexAgentEngine.executeToolCallStreamingCore(
    toolCall: ToolCall,
    emit: suspend (AgentEvent) -> Unit
): ToolExecutionOutcome {
    // v4：模型回显的是 provider 安全名（TerminalExecLegacy）；执行器/截断策略
    // 需要注册表 id（TerminalExec）——经当前计划的反向映射解析。
    // 无映射时（旧会话回放/模型直呼 registry id）原样直查，两条路都通。
    val registryToolId = EngineToolPlanner.registryIdOf(currentToolPlan, toolCall.name)
    // 溯源打标（能力生态三分类）：注册表元数据 → MCP/技能/内置，随事件下发。
    // 元数据缺失/未打标 → BUILTIN（kit 适配器与旁路注册同款缺省，不误标）。
    val originInfo = resolveToolOrigin(registryToolId)

    emit(
        AgentEvent.ToolCallStart(
            callId = toolCall.id,
            toolName = toolCall.name,
            arguments = toolCall.arguments,
            origin = originInfo.origin,
            originLabel = originInfo.label
        )
    )

    val toolStart = System.currentTimeMillis()
    val outputBuilder = StringBuilder()

    // v3：结构化执行元数据（ToolStreamEvent.Complete.meta）—— 终端类工具
    // 在 Complete 事件上携带真实退出码 / 会话标注 / 通道。非终端工具默认实例
    // （全 null）→ 成败判定回退旧的文本前缀逻辑，行为不变。重试轮次间
    // 重置（失败尝试的 meta 不泄漏到最终结果）。
    var completeMeta: ToolResultMeta = ToolResultMeta()

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
        completeMeta = ToolResultMeta()
        var streamException: Throwable? = null
        var streamErrorMessage: String? = null
        try {
            toolExecutor.executeStream(registryToolId, toolCall.arguments).collect { event ->
                when (event) {
                    is ToolStreamEvent.Output -> {
                        // 预览片段（preview=true）：只推 UI（终端胶囊实时流动），
                        // 不进累积输出 —— 结构化工具（TerminalExec）的权威输出
                        // 是 Complete 携带的最终 JSON。修复：长命令执行期间
                        // UI 面板空白（旧 executeStream 假流式，invoke 完成前
                        // 零事件）。
                        if (event.preview) {
                            emit(
                                AgentEvent.ToolOutputChunk(
                                    callId = toolCall.id,
                                    chunk = event.chunk,
                                    preview = true
                                )
                            )
                        } else {
                            outputBuilder.append(event.chunk)
                            emit(
                                AgentEvent.ToolOutputChunk(
                                    callId = toolCall.id,
                                    chunk = event.chunk
                                )
                            )
                        }
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
                        // v3：捕获结构化元数据（退出码 / 会话标注 / 通道）——
                        // 发射 ToolCallComplete 时透传给 UI。
                        completeMeta = event.meta
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

    // ═══ P0（截断预算链路打通）：按调用解析工具输出预算 ═══
    // 旧链路断层：模型按工具 schema 显式请求 max_output_chars=12000，
    // TerminalExec 忠实执行返回 12K JSON；但引擎用配置预算（Agent 默认 2000 /
    // Coding 8000~16000）在回灌前再次截断 —— 模型请求的超预算被静默作废，
    // stdout 实际只见 ~1.9K（扣掉 JSON 键开销），“输出不完整”成常态。
    // 现在从本次调用的参数里解析模型显式声明的预算（max_output_chars 等通用
    // 命名约定），以 max(配置预算, 声明预算) 为本次生效预算（硬顶内）。
    // P2 补齐（输出完整性第二轮）：模型**未显式声明**时，工具 schema 的
    // default 同样计入声明预算 —— 模型读 schema 即有合理预期「不传 =
    // 默认 12000」，引擎只给配置预算（如 Coding 8000）是对同一契约的
    // 静默违背：诚实截断但每次默认调用都亏一段、还赔一整轮「收窄重跑」。
    // schema 无 default 的工具零变化（null → 沿用构造预算）。
    val perCallBudget = resolvePerCallToolBudget(toolCall, registryToolId)

    // P7 Layer 1: 工具输出截断（始终生效）
    val rawOutput = outputBuilder.toString()
    val truncationResult = toolTruncator.smartTruncate(rawOutput, registryToolId, perCallBudget)
    val result = truncationResult.text

    // 成败判定（v3 三级优先）：
    // 1. 工具上报了结构化退出码（completeMeta.exitCode != null，如 TerminalExec
    //    的 waitpid 结果）→ 以真实退出码为准 —— JSON 输出不以 "Error" 开头的
    //    exit_code:127 / 门禁拒绝 126 / 超时强杀 -1 不再误判为成功；
    // 2. 流式 Error 事件 / 捕获异常 → 失败；
    // 3. 回退文本前缀检测（兼容只返回 "Error: ..." 文本而不发 Error 事件的
    //    旧工具）。
    // （anyActionFailed / 韧性换路提示已随 main 的 core/finalize 拆分迁至
    //  finalizeToolCallOutcome —— outcome.success 承载本判定结果。）
    val actionSuccess = when {
        completeMeta.exitCode != null -> completeMeta.exitCode == 0 && !hadStreamError
        else -> !hadStreamError && !result.startsWith("Error")
    }

    emit(
        AgentEvent.ToolCallComplete(
            callId = toolCall.id,
            toolName = toolCall.name,
            arguments = toolCall.arguments,
            // P0（预算打通）：UI 切片与截断同预算 —— 旧实现在 per-call 放大后
            // 仍用思考档位预算二次切，UI 显示与回灌 LLM 的内容不一致。
            output = result.take(
                perCallBudget ?: thinkingController.resolveToolOutputBudget(config.maxToolOutputLength)
            ),
            fullOutput = rawOutput.take(100_000),
            success = actionSuccess,
            durationMs = duration,
            // v3：结构化执行元数据透传（UI 的 exit 徽标 / 会话标注 / 挂起提示）。
            exitCode = completeMeta.exitCode,
            sessionId = completeMeta.sessionId,
            channel = completeMeta.channel,
            cwd = completeMeta.cwd,
            errorKind = completeMeta.errorKind,
            waitingInput = completeMeta.waitingInput,
            // 溯源同款透传（Complete 单独携带：重放/恢复场景 Start 丢失时
            // 消费端补建胶囊仍能拿到权威分类）。
            origin = originInfo.origin,
            originLabel = originInfo.label
        )
    )
    return ToolExecutionOutcome(result, actionSuccess, registryToolId)
}

/**
 * 工具调用收尾（finalize：历史与检测器，只由流属主协程顺序调用）。
 *
 * 状态收口清单（全部按原始 tool_calls 顺序串行执行，并行批与顺序路径
 * 共用同一段代码 —— 行为一致性由结构保证，不靠测试对齐）：
 * - ToolResult 写历史（配对安全：Assistant 消息先落，ToolResult 紧随其后）；
 * - 失败统计 + 换路提示（延迟注入，见 [deferSystemNote]）；
 * - 深思考档自检提示（延迟注入）；
 * - 终端主动性连击统计；
 * - 循环检测与恢复提示（延迟注入）；
 * - 隐式记忆采集（报告 P2）。
 */
internal suspend fun ApexAgentEngine.finalizeToolCallOutcome(
    toolCall: ToolCall,
    outcome: ToolExecutionOutcome
) {
    if (!outcome.success) anyActionFailed = true

    // ═══ 长任务韧性：连续失败 → 延迟注入换路提示 ═══
    // 连续 N 个工具调用失败时，向历史注入一条系统级 recovery 提示，
    // 明确要求模型换工具/换参数/换分解方式 ——「不会用其他方式」的闭环；
    // 预算用尽/未达阈值返回 null 零开销。
    // diag-20261011：经 [deferSystemNote] 延迟到整批工具结果之后落盘 ——
    // 此处直接 addMessage 会插在 ToolResult 之前，破坏配对（400 根因）。
    resilience.onToolCallOutcome(toolCall.name, outcome.success)?.let {
        deferSystemNote(it)
    }

    // 截断后的结果存入历史（节省后续 token）
    addMessage(LlmMessage.ToolResult(toolCall.id, outcome.result))
    // #168：工具计数 + DEEP/MAXIMUM 档在失败/HIGH 风险后注入自检提示（下一轮
    // LLM 可见）。diag-20261011：经 [deferSystemNote] 延迟落盘 —— 并行工具批
    // 中直接 addMessage 会插在本批后续 ToolResult 之前，破坏配对（400 根因）。
    thinkingController.postToolCheckPrompt(
        outcome.registryToolId, outcome.success, toolRegistry.metadataOf(outcome.registryToolId)?.isHighRisk == true
    )?.let { deferSystemNote(it) }

    // #170：终端主动性 —— 一次性 shell 连击/失败连击滑窗（下一迭代判定建议）。
    terminalAdvisor.onToolCallCompleted(outcome.registryToolId, outcome.success, toolCall.arguments)

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
    // 传入 success 供 CS-Mem 蒸馏时过滤失败动作（避免"鼠标连点失败"
    // 也被压进 FSM 宏技能，使学到的宏技能必然无法回放）。
    memoryObserver?.onActionExecuted(
        "${toolCall.name}(${toolCall.arguments.take(120)})",
        success = outcome.success
    )
}

// ══════════════════════════════════════════════════════════════════════════
//  P0（截断预算链路打通）：按调用解析工具输出预算
// ══════════════════════════════════════════════════════════════════════════

/**
 * 解析本次工具调用的输出预算上限（null = 未声明且无 schema default，沿用构造预算）。
 *
 * 生效预算 = max(思考档位解析的配置预算, 模型声明预算)，并钳制在
 * [HARD_TOOL_OUTPUT_CEILING] 硬顶内 —— 声明优先级：模型参数显式声明 >
 * 工具 schema 的 default（模型不传参数时 schema default 即其合理预期）。
 * 模型在工具参数里显式要的输出量
 * （如 TerminalExec 的 max_output_chars=12000）不再被引擎配置预算
 * 静默作废；同时硬顶保证任何单次工具结果都不会撑爆上下文。
 */
internal fun ApexAgentEngine.resolvePerCallToolBudget(toolCall: ToolCall, registryToolId: String): Int? {
    val declared = declaredToolOutputBudget(toolCall.arguments)
        ?: schemaDeclaredToolBudget(registryToolId)
        ?: return null
    val base = thinkingController.resolveToolOutputBudget(config.maxToolOutputLength)
    return maxOf(base, declared).coerceAtMost(HARD_TOOL_OUTPUT_CEILING)
}

/**
 * 从注册表工具的 parametersSchema 里解析 default 预算（模型未显式声明时的
 * 兜底声明）：properties.max_output_chars.default + max_error_chars.default
 * + JSON 信封开销。与 [declaredToolOutputBudget] 同一公式 —— 仅声明源不同。
 *
 * 防御式：schema 缺失/非 JSON/无 default 字段 → null，永不因解析抛错影响执行；
 * 注册表查询失败（未知工具/并发即栽）同样 null。
 */
internal fun ApexAgentEngine.schemaDeclaredToolBudget(registryToolId: String): Int? {
    val schema = runCatching { toolRegistry.getTool(registryToolId)?.parametersSchema }.getOrNull()
        ?: return null
    if (schema.isBlank() || schema.length > MAX_BUDGET_ARG_SCAN_CHARS) return null
    return runCatching {
        val props = Json.parseToJsonElement(schema).jsonObject["properties"] as? JsonObject
            ?: return@runCatching null
        // JSON Schema 语义：properties.<name> 是 JsonObject，default 藏在内层
        // （{"type":"integer","default":12000}）—— 不能用 intFieldOf（只匹配
        // 顶层 JsonPrimitive，对本形态恒 null）。
        fun defaultInt(vararg keys: String): Int? =
            keys.firstNotNullOfOrNull { k ->
                (props[k] as? JsonObject)?.get("default")?.let { d ->
                    (d as? JsonPrimitive)?.content?.toIntOrNull()?.takeIf { it > 0 }
                }
            }
        val outputChars = defaultInt("max_output_chars", "maxOutputChars", "max_chars")
            ?: return@runCatching null
        val errorChars = defaultInt("max_error_chars", "maxErrorChars") ?: 0
        (outputChars + errorChars + JSON_ENVELOPE_OVERHEAD).coerceAtLeast(1)
    }.getOrNull()
}

/**
 * 从工具调用参数 JSON 里解析模型显式声明的输出预算（字符数）。
 *
 * 通用命名约定（snake_case 优先，camelCase 兜底）：
 *  - `max_output_chars` / `maxOutputChars` / `max_chars` —— stdout 预算；
 *  - `max_error_chars` / `maxErrorChars` —— stderr 预算（计入总预算）。
 *
 * 加上 [JSON_ENVELOPE_OVERHEAD] 信封开销（结构化工具的键名/元数据字段/
 * 截断告示）。防御式：参数非 JSON / 字段缺失 / 超长参数串 → null，
 * 永不因解析抛错影响执行。
 */
internal fun declaredToolOutputBudget(arguments: String): Int? {
    if (arguments.isBlank() || arguments.length > MAX_BUDGET_ARG_SCAN_CHARS) return null
    return runCatching {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val outputChars = intFieldOf(json, "max_output_chars", "maxOutputChars", "max_chars")
            ?: return@runCatching null
        val errorChars = intFieldOf(json, "max_error_chars", "maxErrorChars") ?: 0
        (outputChars + errorChars + JSON_ENVELOPE_OVERHEAD).coerceAtLeast(1)
    }.getOrNull()
}

/** JSON 对象里的第一个命中的整数字段（容错：非数字/缺失 → null）。 */
private fun intFieldOf(json: JsonObject, vararg keys: String): Int? =
    keys.firstNotNullOfOrNull { k ->
        (json[k] as? JsonPrimitive)?.content?.toIntOrNull()?.takeIf { it > 0 }
    }

/** 按调用预算的硬顶（单次工具结果进上下文的绝对上限）。 */
internal const val HARD_TOOL_OUTPUT_CEILING = 150_000

/** 结构化工具 JSON 信封开销预算（键名 + 元数据字段 + 截断告示）。 */
private const val JSON_ENVELOPE_OVERHEAD = 2_500

/** 预算解析的参数串长度上限（超大参数串直接放弃解析，防性能倒退）。 */
private const val MAX_BUDGET_ARG_SCAN_CHARS = 8_192
