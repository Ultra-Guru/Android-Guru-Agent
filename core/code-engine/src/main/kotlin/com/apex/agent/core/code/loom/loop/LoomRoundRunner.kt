package com.apex.agent.core.code.loom.loop

import com.apex.agent.core.code.loom.LoomEngineConfig
import com.apex.agent.core.code.loom.LoomInvocation
import com.apex.agent.core.code.loom.LoomOutcomeBlock
import com.apex.agent.core.code.loom.LoomSegment
import com.apex.agent.core.code.loom.LoomWovenPlan
import com.apex.agent.core.code.loom.markup.LoomMarkupParser
import com.apex.agent.core.code.loom.markup.LoomNativeBridge
import com.apex.agent.core.code.loom.markup.LoomOutcomeFormatter
import com.apex.agent.core.code.loom.memory.LoomMemoryWeaver
import com.apex.agent.core.code.loom.permission.LoomApprovalBox
import com.apex.agent.core.code.loom.permission.LoomPermissionDecision
import com.apex.agent.core.code.loom.permission.LoomPermissionGovernor
import com.apex.agent.core.code.loom.steer.LoomSteering
import com.apex.agent.core.code.loom.thinking.LoomThinkingPolicy
import com.apex.agent.core.engine.AgentEvent
import com.apex.agent.core.engine.InputType
import com.apex.agent.core.engine.UserInput
import com.apex.agent.core.llm.ToolDefinition
import com.apex.agent.core.tools.ToolStreamEvent
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow

/**
 * # Loom Round Runner — 织造梭回合机
 *
 * 织造线的主循环：一次用户输入 → 织到完成。每回合（一梭）：
 *
 * ```
 * 织入转向 → 组装（系统轮 JIT + 历史） → 流式模型调用（重试阶梯）
 *   → 标记解析（思考/正文/调用/布样增量）
 *   → 分流：
 *       布样（PLAN 首轮）→ 人控门 → 裁决轮 → 确认续织 / 驳回重织
 *       无调用 → 终局（ResponseComplete + Complete）
 *       纯思考 → 催促轮（预算内）/ 强制收尾
 *       空回合 → 超限终止
 *       调用批次 → 防环裁决 → 串行执行（权限门 → 流式执行 → 回执）
 *                → 回执轮落账 → 压缩检查 → 下一梭
 * ```
 *
 * ## 事件纪律
 *
 * 全部产出走宿主 [AgentEvent]（胶囊时间轴 / 长任务追踪 / 权限问答
 * UI 零改动消费）。中止经 [LoomAbortSignal] 协作检查（回合边界 + 流帧
 * 边界）——捕获取消异常后织出 Aborted 正常收场。
 *
 * ## 线程契约
 *
 * 冷流单收集者；引擎把本类持为长寿命对象（abort / 布样裁决 / 权限
 * 问答从引擎门面转进来）。
 */
class LoomRoundRunner(
    private val config: LoomEngineConfig,
    internal val session: LoomSession,
    private val modelClient: LoomModelClient,
    private val toolSource: LoomToolSource,
    private val toolRunner: LoomToolRunner,
    private val governor: LoomPermissionGovernor,
    private val weaver: LoomMemoryWeaver,
    private val steering: LoomSteering,
    private val approvalBox: LoomApprovalBox,
    internal val planGate: LoomPlanGate,
    private val formatter: LoomOutcomeFormatter,
    private val thinkingPolicy: LoomThinkingPolicy,
    private val failureClassifier: com.apex.agent.core.code.loom.loop.LoomFailureClassifier,
    private val logger: LoomLogger,
    private val abortSignal: LoomAbortSignal,
    private val modelInfoSource: LoomModelInfoSource? = null,
    /** 假钟注入（测试）。 */
    private val clock: () -> Long = System::currentTimeMillis,
    /** 重试睡眠（测试注入）。 */
    private val retrySleeper: suspend (Long) -> Unit = { delay(it) }
) {

    /** 单回合流式产物。 */
    private data class RoundProduct(
        val text: String,
        val deliberation: String,
        val invocations: List<LoomInvocation>,
        val plan: LoomWovenPlan?,
        val finishReason: String?
    )

    /** 工具执行中的柔性失败（转为 error 回执，不击穿任务）。 */
    private class ToolRunException(message: String) : Exception(message)

    private val bridge = LoomNativeBridge()
    private val parser = LoomMarkupParser()
    private val guards = LoomGuards(config)
    private val callCounter = AtomicInteger(0)

    /** 本任务布样门已消费（确认过一次即不再二次门控——后续按步执行）。 */
    private var planGateConsumed = false

    // ═══════════════════════════ 对外 API ═══════════════════════════

    /** 布样裁决投递（引擎门面转进；无挂起等待返回 false）。 */
    fun submitPlanConfirmation(confirmed: Boolean): Boolean = planGate.submit(confirmed)

    /** 权限问答回答投递。 */
    fun submitApprovalAnswer(answer: String): Boolean = approvalBox.submit(answer)

    /** 任务中止请求（引擎 abort 转进）。 */
    fun requestAbort() {
        abortSignal.request()
        approvalBox.cancelPending()
        planGate.cancel()
    }

    /** 运行中转向（引擎转进；非运行态返回 false）。 */
    fun offerSteering(text: String): Boolean = steering.offer(text)

    /**
     * 主循环（冷流）。
     */
    fun run(input: UserInput): Flow<AgentEvent> = flow {
        val startedAt = clock()
        var totalToolCalls = 0
        var round = 0
        var finalText = ""
        var terminated = false
        var truncResumes = 0
        guards.resetForTask()
        abortSignal.reset()
        planGateConsumed = false
        steering.setRunning(true)
        try {
            // 用户轮落账（含任务开始前排队的转向）
            session.appendUserInput(input, steeringNotes = steering.drain())

            val maxRounds = effectiveMaxRounds()
            while (!terminated && round < maxRounds) {
                round++
                abortSignal.ensureNotRequested()
                emit(AgentEvent.IterationStart(round))
                emit(
                    AgentEvent.ThinkingStart(
                        round,
                        session.thinkingLevel().toAgentLevel()
                    )
                )

                // 转向织入（回合边界优先注入）
                val steerNotes = steering.drain()
                for (note in steerNotes) {
                    session.appendUserTurn(formatter.renderSteeringTurn(note))
                    emit(AgentEvent.ResponseChunk(steeringNote(note)))
                }

                // 思考取向刷新（档位 × 模型家族——提示词 JIT 消费）
                session.updateThinkingDirective(
                    thinkingPolicy.deliberationDirective(
                        session.thinkingLevel(),
                        modelInfoSource?.current()?.modelId
                    )
                )

                val toolDefs = visibleTools()
                val messages = session.assembleMessages(toolDefs)
                val product = streamRound(messages, toolDefs, round)

                // 回合思考收尾（思考卡闭合）
                if (product.deliberation.isNotBlank()) {
                    emit(AgentEvent.ThinkingComplete(product.deliberation))
                }

                // 空转守卫（无文本无思考无调用）
                if (guards.noteRoundShape(
                        product.text.isNotBlank(),
                        product.deliberation.isNotBlank(),
                        product.invocations.isNotEmpty()
                    )
                ) {
                    emit(
                        AgentEvent.Error(
                            "模型连续返回空回合，任务已终止（可重试或换模型）",
                            recoverable = true
                        )
                    )
                    terminated = true
                    break
                }

                // 布样人控门（PLAN 模式 + 可出示布样 + 本任务尚未确认过）
                if (product.plan != null && product.plan.isPresentable() && !planGateConsumed) {
                    val plan = product.plan
                    session.appendAssistantRound(
                        deliberation = product.deliberation,
                        text = product.text,
                        invocations = product.invocations
                    )
                    emit(AgentEvent.PlanGenerated(plan.toExecutionPlan()))
                    emit(AgentEvent.PlanAwaitingConfirmation(plan.toExecutionPlan()))
                    val verdict = planGate.await(config.approvalTimeoutMs)
                    abortSignal.ensureNotRequested()
                    emit(AgentEvent.PlanConfirmed(plan.toExecutionPlan()))
                    session.appendUserTurn(
                        formatter.renderPlanVerdictTurn(verdict.confirmed, verdict.note)
                    )
                    if (verdict.confirmed) {
                        planGateConsumed = true
                        session.updatePlanSettled(true)
                    }
                    // 确认 → 按步执行；驳回/超时 → 模型重织（门保持可用）
                    continue
                }

                session.appendAssistantRound(
                    deliberation = product.deliberation,
                    text = product.text,
                    invocations = product.invocations
                )

                if (product.invocations.isEmpty()) {
                    // 纯思考回合：催促（预算内）/ 强制收尾
                    if (product.text.isBlank() && product.deliberation.isNotBlank()) {
                        if (guards.tryConsumeNudge()) {
                            session.appendUserTurn(formatter.renderNudgeTurn())
                            continue
                        }
                        emit(
                            AgentEvent.Error(
                                "模型持续只思考不行动，任务已终止（可提高思考档位或换模型）",
                                recoverable = true
                            )
                        )
                        terminated = true
                        break
                    }
                    // 输出截断续跑（finishReason = length）：断点继续
                    if (product.finishReason == REASON_LENGTH &&
                        product.text.isNotBlank() && truncResumes < TRUNC_RESUME_LIMIT
                    ) {
                        truncResumes++
                        session.appendUserTurn(TRUNC_RESUME_NOTE)
                        continue
                    }
                    // 终局
                    finalText = product.text
                    emit(AgentEvent.ResponseComplete(product.text))
                    terminated = true
                    break
                }

                // 调用批次：防环裁决
                val verdict = guards.observeBatch(product.invocations)
                val outcomes = mutableListOf<LoomOutcomeBlock>()
                when (verdict) {
                    is LoomGuards.Verdict.ForceConverge -> {
                        session.appendUserTurn(verdict.message)
                        emit(AgentEvent.ResponseChunk(verdict.message + "\n"))
                        continue // 模型应给出总结
                    }
                    is LoomGuards.Verdict.Caution -> {
                        outcomes.add(formatter.formatWarn(GUARD_TOOL, verdict.message))
                    }
                    LoomGuards.Verdict.Proceed -> Unit
                }

                // 串行执行
                for (invocation in product.invocations) {
                    totalToolCalls++
                    val outcome = executeInvocation(invocation)
                    outcomes.add(outcome)
                }

                // 回执轮落账
                session.appendUserTurn(formatter.renderOutcomeTurn(outcomes))

                // 压缩检查（回合边界）
                compactIfNeeded()
            }

            if (!terminated) {
                emit(
                    AgentEvent.Error(
                        "已达本任务最大梭回合数（$maxRounds）——请开新任务续跑或提高思考档位",
                        recoverable = true
                    )
                )
            }
            emit(
                AgentEvent.Complete(
                    summary = finalText.take(COMPLETE_SUMMARY_CAP),
                    totalIterations = round,
                    totalToolCalls = totalToolCalls,
                    totalDurationMs = clock() - startedAt
                )
            )
        } catch (c: CancellationException) {
            if (abortSignal.isRequested()) {
                emit(AgentEvent.Aborted)
                emit(
                    AgentEvent.Complete(
                        summary = finalText.take(COMPLETE_SUMMARY_CAP),
                        totalIterations = round,
                        totalToolCalls = totalToolCalls,
                        totalDurationMs = clock() - startedAt
                    )
                )
            } else {
                throw c // 外部作用域取消——自然传播
            }
        } catch (t: Throwable) {
            logger.error(TAG, "loom run faulted: ${t.message}", t)
            emit(AgentEvent.Error(t.message ?: "织造引擎内部错误", recoverable = false))
            emit(
                AgentEvent.Complete(
                    summary = finalText.take(COMPLETE_SUMMARY_CAP),
                    totalIterations = round,
                    totalToolCalls = totalToolCalls,
                    totalDurationMs = clock() - startedAt
                )
            )
        } finally {
            steering.setRunning(false)
            abortSignal.reset()
        }
    }

    // ═══════════════════════════ 流式回合（重试阶梯）═══════════════════════════

    private suspend fun FlowCollector<AgentEvent>.streamRound(
        messages: List<com.apex.agent.core.llm.LlmMessage>,
        toolDefs: List<ToolDefinition>,
        round: Int
    ): RoundProduct {
        var attempt = 0
        var overflowRetried = false
        while (true) {
            try {
                return streamOnce(messages, toolDefs, round)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                when (failureClassifier.classify(t)) {
                    LoomFailureKind.TRANSIENT -> {
                        attempt++
                        if (attempt > config.transientRetryMax) throw t
                        val delayMs = config.transientRetryBaseDelayMs * (1L shl (attempt - 1))
                        emit(
                            AgentEvent.LlmRetryScheduled(
                                attempt = attempt,
                                delayMs = delayMs,
                                reason = "瞬时故障，${delayMs / 1000}秒后重试：${t.message?.take(60) ?: t::class.simpleName}"
                            )
                        )
                        logger.warn(TAG, "transient #$attempt: ${t.message}")
                        retrySleeper(delayMs)
                    }
                    LoomFailureKind.CONTEXT_OVERFLOW -> {
                        if (overflowRetried) throw t
                        overflowRetried = true
                        val compacted = compactIfNeeded()
                        if (!compacted) throw t // 无处可压——上抛终止
                        emit(
                            AgentEvent.LlmRetryScheduled(
                                attempt = 0,
                                delayMs = 0,
                                reason = "上下文超窗，已压缩历史后重试"
                            )
                        )
                    }
                    else -> throw t
                }
            }
        }
    }

    /** 单次流式调用 + 标记解析（桥接 → 解析器 → 事件流）。 */
    private suspend fun FlowCollector<AgentEvent>.streamOnce(
        messages: List<com.apex.agent.core.llm.LlmMessage>,
        toolDefs: List<ToolDefinition>,
        round: Int
    ): RoundProduct {
        bridge.reset()
        parser.reset()
        val text = StringBuilder()
        val deliberation = StringBuilder()
        val invocations = mutableListOf<LoomInvocation>()
        var plan: LoomWovenPlan? = null
        var finishReason: String? = null

        // 原生调用开关：关闭时不下发 tools 面（纯提示词协议）
        val apiTools = if (config.nativeToolCalling) toolDefs else emptyList()

        modelClient.chatStream(
            contextTag = config.contextTag,
            messages = messages,
            tools = apiTools,
            temperature = -1f,
            maxTokens = -1
        ).collect { chunk ->
            abortSignal.ensureNotRequested()
            for (frame in bridge.feed(chunk)) {
                when (frame) {
                    is LoomNativeBridge.LoomWireFrame.Text -> {
                        for (segment in parser.feed(frame.delta)) {
                            when (segment) {
                                is LoomSegment.Text -> {
                                    text.append(segment.delta)
                                    emit(AgentEvent.ResponseChunk(segment.delta))
                                }
                                is LoomSegment.Deliberation -> {
                                    deliberation.append(segment.delta)
                                    emit(AgentEvent.ThinkingChunk(segment.delta))
                                }
                                is LoomSegment.Invocation ->
                                    invocations.add(segment.invocation)
                                is LoomSegment.Plan -> plan = segment.plan
                                is LoomSegment.Outcome, is LoomSegment.Digest -> {
                                    // 历史回显块：不重复织事件（提示词已声明只读回执）
                                }
                            }
                        }
                    }
                    is LoomNativeBridge.LoomWireFrame.Usage -> emit(
                        AgentEvent.UsageUpdated(
                            promptTokens = frame.promptTokens,
                            completionTokens = frame.completionTokens,
                            totalTokens = frame.totalTokens
                        )
                    )
                    is LoomNativeBridge.LoomWireFrame.Finish -> finishReason = frame.reason
                }
            }
        }
        for (frame in bridge.finish()) {
            if (frame is LoomNativeBridge.LoomWireFrame.Text) {
                for (segment in parser.feed(frame.delta)) {
                    when (segment) {
                        is LoomSegment.Text -> {
                            text.append(segment.delta)
                            emit(AgentEvent.ResponseChunk(segment.delta))
                        }
                        is LoomSegment.Deliberation -> {
                            deliberation.append(segment.delta)
                            emit(AgentEvent.ThinkingChunk(segment.delta))
                        }
                        is LoomSegment.Invocation -> invocations.add(segment.invocation)
                        is LoomSegment.Plan -> plan = segment.plan
                        is LoomSegment.Outcome, is LoomSegment.Digest -> Unit
                    }
                }
            }
        }
        for (segment in parser.finish()) {
            when (segment) {
                is LoomSegment.Text -> {
                    text.append(segment.delta)
                    emit(AgentEvent.ResponseChunk(segment.delta))
                }
                is LoomSegment.Deliberation -> {
                    deliberation.append(segment.delta)
                    emit(AgentEvent.ThinkingChunk(segment.delta))
                }
                is LoomSegment.Invocation -> invocations.add(segment.invocation)
                is LoomSegment.Plan -> plan = segment.plan
                is LoomSegment.Outcome, is LoomSegment.Digest -> Unit
            }
        }
        logger.info(
            TAG,
            "round $round settled: text=${text.length}ch delib=${deliberation.length}ch " +
                "invocations=${invocations.size} finish=$finishReason"
        )
        return RoundProduct(
            text = text.toString(),
            deliberation = deliberation.toString(),
            invocations = invocations,
            plan = plan,
            finishReason = finishReason
        )
    }

    // ═══════════════════════════ 调用执行 ═══════════════════════════

    /** 一次调用的全链路：权限门 → 流式执行 → 回执。 */
    private suspend fun FlowCollector<AgentEvent>.executeInvocation(
        invocation: LoomInvocation
    ): LoomOutcomeBlock {
        val callId = "loom-${callCounter.incrementAndGet()}"
        val argsJson = invocation.argumentsOrJson()
        emit(AgentEvent.ToolCallStart(callId, invocation.name, argsJson))
        val decision = governor.decide(invocation, session.currentWorkspaceRoot())
        return when (decision) {
            is LoomPermissionDecision.Deny -> {
                val outcome = formatter.formatDenied(invocation.name, decision.reason)
                emitSettled(callId, invocation, outcome, success = false, durationMs = 0)
                outcome
            }
            is LoomPermissionDecision.Ask -> {
                val question = formatter.renderApprovalQuestion(
                    invocation, governor.describeRisk(invocation)
                )
                emit(AgentEvent.UserInputRequired(question, InputType.CONFIRMATION))
                val answer = approvalBox.await(question, config.approvalTimeoutMs)
                abortSignal.ensureNotRequested()
                when {
                    answer == null -> {
                        emit(AgentEvent.UserInputExpired)
                        val outcome = formatter.formatApprovalLapse(
                            invocation.name, config.approvalTimeoutMs
                        )
                        emitSettled(callId, invocation, outcome, success = false, durationMs = 0)
                        outcome
                    }
                    isApprovalYes(answer) -> {
                        governor.rememberApproval(invocation)
                        runTool(callId, invocation)
                    }
                    else -> {
                        val outcome = formatter.formatDenied(invocation.name, "用户选择拒绝")
                        emitSettled(callId, invocation, outcome, success = false, durationMs = 0)
                        outcome
                    }
                }
            }
            LoomPermissionDecision.Allow -> runTool(callId, invocation)
        }
    }

    /** 流式执行工具（Output/Progress 全程可见；柔性失败转回执）。 */
    private suspend fun FlowCollector<AgentEvent>.runTool(
        callId: String,
        invocation: LoomInvocation
    ): LoomOutcomeBlock {
        val startedAt = clock()
        val output = StringBuilder()
        var completedOutput: String? = null
        try {
            toolRunner.executeStream(invocation.name, invocation.argumentsOrJson())
                .collect { event ->
                    abortSignal.ensureNotRequested()
                    when (event) {
                        is ToolStreamEvent.Output -> {
                            output.append(event.chunk)
                            emit(AgentEvent.ToolOutputChunk(callId, event.chunk))
                        }
                        is ToolStreamEvent.Progress -> emit(
                            AgentEvent.ToolProgress(callId, event.percent, event.message)
                        )
                        is ToolStreamEvent.Complete -> completedOutput = event.output
                        is ToolStreamEvent.Error -> throw ToolRunException(event.message)
                    }
                }
            val finalOutput = if (output.isNotEmpty()) output.toString()
            else completedOutput ?: ""
            val outcome = formatter.formatResult(
                invocation.name, finalOutput, success = true, durationMs = clock() - startedAt
            )
            emitSettled(
                callId, invocation, outcome,
                success = true, durationMs = clock() - startedAt, fullOutput = finalOutput
            )
            return outcome
        } catch (e: ToolRunException) {
            val outcome = formatter.formatError(invocation.name, e.message ?: "工具执行失败")
            emitSettled(callId, invocation, outcome, success = false, durationMs = clock() - startedAt)
            return outcome
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            logger.warn(TAG, "tool ${invocation.name} faulted: ${t.message}")
            val outcome = formatter.formatError(invocation.name, t.message ?: t::class.simpleName ?: "工具异常")
            emitSettled(callId, invocation, outcome, success = false, durationMs = clock() - startedAt)
            return outcome
        }
    }

    /** 回执落定事件（ToolCallComplete 统一口径）。 */
    private suspend fun FlowCollector<AgentEvent>.emitSettled(
        callId: String,
        invocation: LoomInvocation,
        outcome: LoomOutcomeBlock,
        success: Boolean,
        durationMs: Long,
        fullOutput: String = outcome.payload
    ) {
        emit(
            AgentEvent.ToolCallComplete(
                callId = callId,
                toolName = invocation.name,
                arguments = invocation.argumentsOrJson(),
                output = outcome.payload,
                fullOutput = fullOutput,
                success = success,
                durationMs = durationMs
            )
        )
    }

    // ═══════════════════════════ 辅助 ═══════════════════════════

    /** 有效最大回合数（配置 × 档位倍率）。 */
    private fun effectiveMaxRounds(): Int = (
        config.shuttleRoundsMax *
            thinkingPolicy.roundBudgetMultiplier(session.thinkingLevel())
        ).toInt().coerceAtLeast(4)

    /** 可见面（工具目录源——引擎层已按 forcedTools 过滤）。 */
    private fun visibleTools(): List<ToolDefinition> = toolSource.definitions()

    /** 压缩检查（返回 true = 发生了压缩）。 */
    private suspend fun FlowCollector<AgentEvent>.compactIfNeeded(): Boolean {
        val outcome = weaver.maybeCompact(session.historySnapshot()) ?: return false
        session.replaceHistory(outcome.newHistory)
        val r = outcome.result
        emit(
            AgentEvent.ContextCompressed(
                beforeTokens = r.beforeTokens,
                afterTokens = r.afterTokens,
                strategy = r.strategy,
                summary = r.summary,
                messagesRemoved = r.turnsRemoved,
                messagesTruncated = 0
            )
        )
        return true
    }

    /** 问答肯定口径（中英常见肯定词）。 */
    private fun isApprovalYes(answer: String): Boolean {
        val a = answer.trim().lowercase()
        return a in APPROVAL_YES || (a.isNotEmpty() && a.first() == 'y')
    }

    private fun LoomWovenPlan.toExecutionPlan(): com.apex.agent.core.engine.ExecutionPlan =
        com.apex.agent.core.engine.ExecutionPlan(
            goal = goal,
            steps = steps.mapIndexed { i, s ->
                com.apex.agent.core.engine.PlanStep(
                    index = i,
                    description = s,
                    toolName = null,
                    estimatedArgs = null
                )
            },
            estimatedToolCalls = steps.size,
            riskLevel = com.apex.agent.core.engine.RiskLevel.MEDIUM,
            reasoning = note
        )

    companion object {
        private const val TAG = "LoomRoundRunner"
        private const val COMPLETE_SUMMARY_CAP = 200
        private const val GUARD_TOOL = "__system__"
        private const val REASON_LENGTH = "length"
        private const val TRUNC_RESUME_LIMIT = 2
        private const val TRUNC_RESUME_NOTE =
            "（系统提示）上一段输出因长度限制被截断——请从断点继续，不要重复已输出内容。"

        private val APPROVAL_YES = setOf(
            "是", "好", "允许", "同意", "确认", "准了", "继续", "yes", "y", "ok", "allow", "approve"
        )

        /** 转向受理回执文案（事件流可见性）。 */
        private fun steeringNote(text: String): String =
            "[steering] 已入队补充指令：" + text + "\n"
    }
}
