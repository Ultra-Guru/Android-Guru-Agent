package com.apex.agent.core.code.stream

import com.apex.agent.core.engine.AgentEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive

/**
 * # Code Stream Session — 胶囊时间轴事件状态机（渲染通道的数据源）
 *
 * ## 定位（VM 双通道架构的「渲染半边」）
 *
 * CodeViewModel 的 collect 循环把每个 AgentEvent 喂给本会话
 * （[onEvent]，纯归约 + 置脏），**副作用通道**（长任务追踪 / 深水区
 * 升级观察器 / 会话落盘）留在 VM 原有 reduce——渲染与副作用彻底分流，
 * 引擎与既有功能零改动。
 *
 * ## 25ms ≤40Hz 攒批
 *
 * 事件到来只置脏，**不立刻重组 UI**；VM 的渲染 ticker 每拍调 [tick]：
 * 脏了才重建一次不可变 [CodeStreamSnapshot]（时间轴 + 终端尾窗 + 派生
 * 统计）。流式风暴下 UI 重组频率被钉死在 25ms 一档。
 *
 * ## 幂等（规格书【1】）
 *
 * callId 即幂等键：重复的 ToolCallStart/ToolOutputChunk/ToolCallComplete
 * 对同一 callId 天然合并（首次创建、后续更新），重放安全——检查点恢复
 * （Last-Event-ID 续传）语义建立在这一点上。
 *
 * ## 验证闭环聚合
 *
 * edit/write 落盘后紧跟的 lint/test 归入同一 [VerifyCycle]；下一轮 edit
 * 开始时上一轮闭环收口为可折叠 [StreamEntry.VerifyCycleEntry]（轮内胶囊
 * 从时间轴折叠进卡片，展开态由 UI 自持）——闭环自延长时时间轴不刷屏。
 *
 * 线程模型：与 VM collect 协程同线程串行调用；快照只读共享。
 */
class CodeStreamSession {

    // ═══ 内部状态（全部只在 onEvent/inject 变更）═══

    private val entries = mutableListOf<StreamEntry>()
    private val toolCalls = LinkedHashMap<String, StreamToolCall>()
    private val terminalBuffers = LinkedHashMap<String, TerminalPulseBuffer>()

    /** 时间轴条目 id 稳定自增（会话生命周期内唯一）。 */
    private var entrySeq = 0L


    /** 当前流式思考条目（ThinkingStart..ThinkingComplete 窗口内非空）。 */
    private var streamingThinking: StreamEntry.ThinkingEntry? = null

    /** 当前流式助手条目。 */
    private var streamingAssistant: StreamEntry.AssistantEntry? = null

    /** 活跃 BASH 胶囊（终端面板跟随；run 结束或下个 BASH 到来时切换）。 */
    private var activeTerminalCallId: String? = null

    // ── 终端面板流水式折叠信号（用户需求：「终端命令运行时展开跟随输出，
    //    完成后自动折叠成胶囊」；与思考/工具胶囊同款生命周期）──
    /** 活跃 BASH 正在执行（面板保持展开）；Complete/Error/Aborted 收束。 */
    private var terminalBusy = false

    /** 头行展示的命令摘要（活跃/最近 BASH 的首行命令）。 */
    private var terminalCommandSummary = ""

    /** 最近一次终端命令退出码（完成态头行展示；null = 未知）。 */
    private var terminalExitCode: Int? = null

    private val affectedFiles = LinkedHashSet<String>()
    private var lastError: CodeStreamSnapshot.ErrorInfo? = null
    private var toolCallCount = 0
    private var failedToolCallCount = 0

    // ═══ 验证闭环追踪 ═══
    private var cycleRound = 0
    private var openCycleEdits = mutableListOf<String>()
    private var openCycleVerifies = mutableListOf<String>()

    private var dirty = false

    // ═══ 公开 API ═══

    /** 新 run 开始：用户气泡入轴 + 运行态归零（时间轴跨 run 累积）。 */
    fun beginRun(userText: String) {
        // 收口上一轮残留的流式条目：中止路径没有 Complete 事件，引用
        // 悬挂会导致下一轮首个 Chunk 把新文本拼进旧气泡（跨轮串台）
        streamingThinking?.let { closeStreamingEntry(it) }
        streamingThinking = null
        streamingAssistant?.let { closeStreamingEntry(it) }
        streamingAssistant = null
        // 上一轮的在飞胶囊同样静默收口（防御：中止事件未达且本地收尾
        // 遗漏的路径，RUNNING/WAITING 胶囊带进新 run 会无限计时转圈）
        finalizeInFlightCapsules(System.currentTimeMillis())
        // 文档语义：「本轮运行」触碰的文件 —— 每 run 归零重新累计
        affectedFiles.clear()
        append(StreamEntry.UserEntry(nextId(), userText))
        activeTerminalCallId = null
        // 新 run 归零终端面板三信号（上一轮命令的尾窗不再展示）
        terminalBusy = false
        terminalCommandSummary = ""
        terminalExitCode = null
    }

    /**
     * VM 侧本地防御式收尾（用户点「停止」时调用，对齐 Agent 屏
     * AgentChatViewModel.abort 的防御语义）。
     *
     * 收集协程取消后，引擎 catch(CancellationException) 里的
     * emit(AgentEvent.Aborted) 会在已取消协程上再次抛出取消异常——
     * 收尾事件永远到不了本会话；不本地收口，时间轴将冻结在流式态
     * （结论气泡永远挂「▍」光标、RUNNING 胶囊无限计时）。
     *
     * 幂等安全：无任何在飞条目（正常完成后误调）时 no-op，
     * 不落多余的「已中止」卡。
     */
    fun abortLocally(nowMs: Long = System.currentTimeMillis()) {
        val hadInFlight = streamingThinking != null || streamingAssistant != null ||
            toolCalls.values.any { !it.isTerminal }
        if (!hadInFlight) return
        finalizeAbortedState(nowMs)
    }

    /** VM 注入系统说明行（AUTO 预检决策 / 深水区升级等）。 */
    fun injectSystem(text: String) {
        append(StreamEntry.SystemEntry(nextId(), text))
    }

    /** 事件归约入口（纯状态机，无 IO 无回调）。 */
    fun onEvent(event: AgentEvent) {
        when (event) {
            // 迭代声明不进时间轴（用户反馈：「每次调用工具都显示迭代，这个词
            // 没有意义」）——「第 N 轮迭代」状态行对流程理解零增益，工具胶囊
            // / 思考 / 输出本身已经构成完整时序；轮次统计仍由 Complete 收尾行
            // 汇总展示。
            is AgentEvent.IterationStart -> Unit

            is AgentEvent.ThinkingStart -> Unit // 思考条目由首个 Chunk 创建

            is AgentEvent.ThinkingChunk -> {
                // 空思考分片不建卡（2026-10 用户反馈）：部分网关对不思考的
                // 模型也每帧发 reasoning_content:"" —— 旧实现建出一张永远
                // 「思考中」的空思考卡（reasoningBuilder 恒空 → 引擎不发
                // ThinkingComplete → 永不收尾）。首块空白直接忽略；已有条目
                // 时空白拼接无害但也无意义，一并跳过。
                if (event.text.isEmpty()) return
                val cur = streamingThinking
                if (cur == null) {
                    if (event.text.isBlank()) return
                    streamingThinking = StreamEntry.ThinkingEntry(nextId(), event.text, true)
                    append(streamingThinking!!)
                } else {
                    // 引用同步回写：不回写则 cur 永远停在首块快照，第三个
                    // Chunk 起条目被「首块+最新块」覆盖（中间块丢失）。
                    val merged = cur.copy(text = cur.text + event.text, isStreaming = true)
                    streamingThinking = merged
                    replaceEntry(merged.id, merged)
                }
            }

            is AgentEvent.ThinkingComplete -> {
                val cur = streamingThinking
                if (cur == null) {
                    // 无先导 Chunk 的完整思考（非流式/降级 provider）不得静默丢弃
                    if (event.fullThought.isNotBlank()) {
                        append(StreamEntry.ThinkingEntry(nextId(), event.fullThought, false))
                    }
                } else if (event.fullThought.isBlank() && cur.text.isBlank()) {
                    // 全程空白思考（模型未思考/网关空串）：移除占位卡而非留白
                    removeEntry(cur.id)
                } else {
                    // 引用同步回写后 cur.text 即全量累积；ThinkingComplete
                    // 的 fullThought 与本轮窗口同源（引擎 reasoningBuilder
                    // 每轮局部重建、UI 引用同轮首块起累积），保持 ifBlank 兑底。
                    replaceEntry(cur.id, cur.copy(text = event.fullThought.ifBlank { cur.text }, isStreaming = false))
                }
                streamingThinking = null
            }

            is AgentEvent.ToolCallStart -> onToolStart(event)

            is AgentEvent.ToolOutputChunk -> {
                toolCalls[event.callId]?.let { call ->
                    terminalBuffers[event.callId]?.append(event.chunk)
                    // 通用 logTail（非 BASH 族的输出尾窗，DetailSheet 用）
                    val merged = call.logTail + event.chunk
                    updateCall(call.copy(logTail = merged.takeLast(NON_BASH_LOG_TAIL_CHARS)))
                    if (call.kind == ToolKind.BASH) activeTerminalCallId = event.callId
                }
            }

            is AgentEvent.ToolProgress -> {
                toolCalls[event.callId]?.let { call ->
                    updateCall(call.copy(progressPercent = event.percent, progressMessage = event.message))
                }
            }

            is AgentEvent.ToolCallComplete -> onToolComplete(event)

            is AgentEvent.ResponseChunk -> {
                // 空回复分片不建条目（与空思考卡同源：部分 provider 发
                // content:"" 的 tool_calls 前导帧）——空气泡流式占位无意义
                if (event.text.isEmpty()) return
                val cur = streamingAssistant
                if (cur == null) {
                    if (event.text.isBlank()) return
                    streamingAssistant = StreamEntry.AssistantEntry(nextId(), event.text, true)
                    append(streamingAssistant!!)
                } else {
                    // 引用同步回写（同 ThinkingChunk）：不回写则流式条目
                    // 从第三个 Chunk 起丢失中间块——旧代码靠 Complete 的
                    // fullText 整体覆写掩盖，本次改为累积优先后必须修。
                    val merged = cur.copy(text = cur.text + event.text, isStreaming = true)
                    streamingAssistant = merged
                    replaceEntry(merged.id, merged)
                }
            }

            is AgentEvent.ResponseComplete -> {
                val cur = streamingAssistant
                if (cur == null) {
                    // 同上：非流式路径的整段结论直接建条目
                    if (event.fullText.isNotBlank()) {
                        append(StreamEntry.AssistantEntry(nextId(), event.fullText, false))
                    }
                } else if (event.fullText.isBlank() && cur.text.isBlank()) {
                    // 全程空白回复（纯工具调用轮）：移除空气泡而非留白
                    removeEntry(cur.id)
                } else {
                    // ═══ 一致性修复（对齐 Agent 屏 AgentChatEventApplier 同款）═══
                    // 引擎 contentBuilder 每轮迭代局部重建（ReAct 多轮只带
                    // 末轮文本），完成事件用 fullText 整体覆写会把跨轮累积
                    // 的气泡截成末轮残句（用户实测：多轮工具调用后结论
                    // 只剩末轮两三个字）。UI 已流式累积的文本才是用户看到
                    // 的全文；fullText 仅在 UI 侧为空时兑底（反思修正轮等
                    // 文本可能从未流经 UI，避免落空气泡）。
                    val finalText = cur.text.ifBlank { event.fullText }
                    replaceEntry(cur.id, cur.copy(text = finalText, isStreaming = false))
                }
                streamingAssistant = null
            }

            is AgentEvent.ContextCompressed -> {
                append(
                    StreamEntry.StatusEntry(
                        nextId(),
                        "上下文压缩 ${event.beforeTokens} → ${event.afterTokens}（${event.strategy}）"
                    )
                )
            }

            is AgentEvent.Error -> {
                lastError = CodeStreamSnapshot.ErrorInfo(event.message, event.recoverable)
                val related = activeTerminalCallId
                // 引擎级错误收束终端「运行中」信号（否则面板永远脉冲）
                terminalBusy = false
                append(
                    StreamEntry.ErrorEntry(
                        id = nextId(),
                        message = event.message,
                        recoverable = event.recoverable,
                        hint = if (event.recoverable) "可重试：检查参数或换一个改法" else null,
                        relatedCallId = related
                    )
                )
            }

            is AgentEvent.Complete -> {
                closeOpenCycle()
                append(StreamEntry.StatusEntry(nextId(), "完成 · ${event.totalIterations} 轮 · ${event.totalToolCalls} 次工具 · ${event.totalDurationMs / 1000}s"))
                if (affectedFiles.isNotEmpty()) {
                    append(StreamEntry.FileChipsEntry(nextId(), affectedFiles.toList()))
                }
            }

            is AgentEvent.Aborted -> {
                closeOpenCycle()
                // 中止同样收口流式条目与在飞胶囊（与 beginRun 双保险：
                // Aborted 后可能没有下一轮 beginRun，气泡不能永远挂
                // 「生成中」，RUNNING 胶囊不能无限计时）
                finalizeAbortedState()
            }

            is AgentEvent.UserInputRequired -> {
                append(StreamEntry.StatusEntry(nextId(), "等待输入：${event.prompt.take(60)}"))
            }

            is AgentEvent.PlanGenerated ->
                append(StreamEntry.StatusEntry(nextId(), "计划已生成（${event.plan.steps.size} 步）"))

            is AgentEvent.PlanAwaitingConfirmation ->
                append(StreamEntry.StatusEntry(nextId(), "等待计划确认"))

            is AgentEvent.SpecGenerated ->
                append(StreamEntry.StatusEntry(nextId(), "规格已生成（${event.spec.requirements.size} 项需求）"))

            is AgentEvent.ReflectionReview ->
                append(StreamEntry.StatusEntry(nextId(), "评审：${event.reviewText.take(60)}"))

            is AgentEvent.StepStart ->
                append(StreamEntry.StatusEntry(nextId(), event.description.take(80)))

            is AgentEvent.PlanConfirmed, is AgentEvent.SpecConfirmed,
            is AgentEvent.SpecAwaitingConfirmation -> Unit

            // 真实用量更新（服务端 token 统计）—— 归 VM/仪表盘通道消费，
            // 胶囊时间轴不展示用量条目（低频元数据，非工作流事件）。
            is AgentEvent.UsageUpdated -> Unit

            // #214 用户输入等待超时：引擎已按「未决」继续 —— 时间轴留痕说明，
            // 挂起的提问输入框由 VM 侧收到同一事件后关闭。
            is AgentEvent.UserInputExpired -> {
                append(StreamEntry.StatusEntry(nextId(), "输入等待超时，已按未决继续"))
            }

            // LLM 请求容错（重试/降级续跑）：过程可见性状态行，不弹错误横幅。
            is AgentEvent.LlmRetryScheduled -> {
                val text = if (event.delayMs > 0) {
                    "请求失败（${event.reason}），${event.delayMs / 1000}s 后第 ${event.attempt} 次重试"
                } else {
                    event.reason
                }
                append(StreamEntry.StatusEntry(nextId(), text))
            }
        }
    }

    /**
     * 渲染 ticker 每拍调用：脉冲缓冲过时间窗 + 脏则重建快照。
     *
     * @return 新快照（无变化 → null，VM 跳过 StateFlow 更新）
     */
    fun tick(nowMs: Long = System.currentTimeMillis()): CodeStreamSnapshot? {
        var pulsed = false
        terminalBuffers.values.forEach { buf ->
            if (buf.tick(nowMs) != null) pulsed = true
        }
        if (!dirty && !pulsed) return null
        dirty = false
        return buildSnapshot()
    }

    /** 强制重建（恢复/测试用）。 */
    fun snapshot(): CodeStreamSnapshot {
        dirty = false
        return buildSnapshot()
    }

    /** 时间轴条目数（测试与落盘裁剪依据）。 */
    fun entryCount(): Int = entries.size

    /**
     * 整轴替换（检查点/旧档恢复用）：清空内部状态后灌入既有条目。
     *
     * 恢复的条目全部视为终态（isStreaming 强制 false 由调用方保证——
     * mapper 产出即终态）；工具表与终端缓冲不重建（历史 BASH 尾窗在
     * DetailSheet 不可回放是已声明的降级）。
     */
    fun replaceAll(restored: List<StreamEntry>) {
        entries.clear()
        entries.addAll(restored)
        // 序号回拨到恢复轴的最大 e-N：不回拨则 nextId() 从 1 重发，与恢复
        // 条目撞号 → LazyColumn 重复 key 直接崩溃（P0）
        entrySeq = restored.maxOfOrNull { entrySeqOf(it.id) } ?: 0L
        toolCalls.clear()
        terminalBuffers.clear()
        affectedFiles.clear()
        // committedFiles 语义恢复：从最后一个 FileChipsEntry 回填（重启后
        // 会话快照的 committedFiles 不再清零）
        (restored.lastOrNull { it is StreamEntry.FileChipsEntry } as? StreamEntry.FileChipsEntry)
            ?.let { affectedFiles.addAll(it.files) }
        streamingThinking = null
        streamingAssistant = null
        activeTerminalCallId = null
        lastError = null
        terminalBusy = false
        terminalCommandSummary = ""
        terminalExitCode = null
        toolCallCount = restored.count { it is StreamEntry.ToolCapsuleEntry }
        failedToolCallCount = restored.count {
            (it as? StreamEntry.ToolCapsuleEntry)?.call?.status == ToolCallStatus.FAILED
        }
        restored.forEach { entry ->
            if (entry is StreamEntry.ToolCapsuleEntry) {
                toolCalls[entry.call.id] = entry.call
            }
        }
        dirty = true
    }

    /** 清空时间轴（新会话）。 */
    fun clear() {
        replaceAll(emptyList())
    }

    /** 只读条目访问（检查点序列化用）。 */
    fun entriesSnapshot(): List<StreamEntry> = entries.toList()

    /** 终端胶囊的尾窗内容（DetailSheet 查看历史 BASH 输出）。 */
    fun terminalContentOf(callId: String): String? =
        terminalBuffers[callId]?.content()?.takeIf { it.isNotEmpty() }

    // ═══ 内部：工具生命周期 ═══

    private fun onToolStart(event: AgentEvent.ToolCallStart) {
        // 幂等：同一 callId 的 Start 重放直接跳过（检查点恢复/重放安全）
        if (toolCalls.containsKey(event.callId)) return
        val kind = ToolKind.fromToolName(event.toolName)
        val args = parseArgs(event.arguments)
        val target = targetOf(kind, args, event.toolName)
        val call = StreamToolCall(
            id = event.callId,
            kind = kind,
            displayName = displayNameOf(kind, event.toolName),
            target = target,
            argsSummary = args.values.firstOrNull()?.toString()?.take(ARGS_SUMMARY_MAX) ?: "",
            status = ToolCallStatus.RUNNING,
            startAt = System.currentTimeMillis()
        )
        toolCalls[event.callId] = call
        toolCallCount++
        if (kind == ToolKind.BASH) {
            terminalBuffers[event.callId] = TerminalPulseBuffer()
            activeTerminalCallId = event.callId
            // 新命令开跑：面板展开跟随（busy=true），头行换成新命令摘要
            terminalBusy = true
            terminalCommandSummary = target
            terminalExitCode = null
        }
        trackVerifyCycleStart(kind, event.callId)
        append(StreamEntry.ToolCapsuleEntry(toolEntryId(event.callId), call))
    }

    private fun onToolComplete(event: AgentEvent.ToolCallComplete) {
        val existing = toolCalls[event.callId] ?: run {
            // 恢复/重放场景：Start 丢失时补建（幂等）
            onToolStart(
                AgentEvent.ToolCallStart(event.callId, event.toolName, event.arguments)
            )
            toolCalls[event.callId] ?: return
        }
        // 幂等闸：已终态的调用重放 Complete 直接跳过——否则
        // failedToolCallCount 重复自增、diff/summary 被旧事件覆盖
        if (existing.status != ToolCallStatus.RUNNING && existing.status != ToolCallStatus.WAITING) {
            return
        }
        val buffer = terminalBuffers[event.callId]
        buffer?.flush()
        val logTail = buffer?.content()?.takeIf { it.isNotEmpty() }
            ?: safeTakeLast(existing.logTail + event.output, NON_BASH_LOG_TAIL_CHARS)

        val diff = if (existing.isEditFamily) UnifiedDiffParser.parse(event.fullOutput.ifBlank { event.output }) else null
        val hunksTotal = diff?.hunks?.size ?: 0
        val status = when {
            !event.success -> ToolCallStatus.FAILED
            existing.isEditFamily -> if (hunksTotal > 0) ToolCallStatus.APPLIED else ToolCallStatus.SUCCESS
            else -> ToolCallStatus.SUCCESS
        }
        val exitCode = exitCodeOf(event.output, existing.kind)

        val updated = existing.copy(
            status = status,
            endAt = System.currentTimeMillis(),
            durationMs = event.durationMs,
            hunksApplied = if (event.success) hunksTotal else 0,
            hunksTotal = hunksTotal,
            exitCode = exitCode,
            logTail = logTail,
            diffText = diff?.takeIf { it.hunks.isNotEmpty() }?.let { event.fullOutput.ifBlank { event.output } },
            progressPercent = null,
            summary = summaryOf(status, diff, exitCode, event.output)
        )
        updateCall(updated)
        if (status == ToolCallStatus.FAILED) failedToolCallCount++
        if (existing.isEditFamily) {
            existing.target.takeIf { it.isNotBlank() && it != UNKNOWN_TARGET }?.let { affectedFiles.add(it) }
        }
        if (activeTerminalCallId == event.callId) {
            // 面板保留最后一次 BASH 的内容（可翻看），直到下一个 BASH 开始；
            // 流水式折叠：命令收束 → busy=false（UI 自动折叠成头行胶囊），
            // 头行保留命令摘要 + 退出码供回看
            terminalBusy = false
            terminalExitCode = exitCode
        }
        trackVerifyCycleEnd(existing.kind, event.callId, event.success)
    }

    // ═══ 内部：中止收尾 ═══

    /** 中止态统一收口：流式思考/结论落终态（已累积文本保留）+ 在飞胶囊
     * 置 PARTIAL + 「已中止」停止卡。事件路径（[AgentEvent.Aborted]）与
     * VM 本地收尾（[abortLocally]）共用。 */
    private fun finalizeAbortedState(nowMs: Long = System.currentTimeMillis()) {
        streamingThinking?.let { closeStreamingEntry(it) }
        streamingThinking = null
        streamingAssistant?.let { closeStreamingEntry(it) }
        streamingAssistant = null
        // 中止时终端命令不会再来 Complete —— 运行中信号就地收束
        terminalBusy = false
        finalizeInFlightCapsules(nowMs)
        append(StreamEntry.StopEntry(nextId(), ABORTED_TEXT))
    }

    /**
     * 在飞（RUNNING/WAITING）胶囊置 PARTIAL：没有结局的调用按中断折叠
     * 计时 —— 与检查点恢复同语义（CodeStreamCheckpoint.toEntries：正在跑
     * 的调用没有结局，恢复后由用户决定单工具重试或整轮重跑）。
     */
    private fun finalizeInFlightCapsules(nowMs: Long) {
        terminalBuffers.values.forEach { it.flush() }
        toolCalls.values
            .filter { it.status == ToolCallStatus.RUNNING || it.status == ToolCallStatus.WAITING }
            .forEach { call ->
                updateCall(
                    call.copy(
                        status = ToolCallStatus.PARTIAL,
                        endAt = if (call.startAt > 0) nowMs else call.endAt,
                        summary = ABORTED_TEXT
                    )
                )
            }
    }

    // ═══ 内部：验证闭环 ═══

    private fun trackVerifyCycleStart(kind: ToolKind, callId: String) {
        when {
            kind == ToolKind.EDIT_FILE || kind == ToolKind.WRITE_FILE -> {
                if (openCycleVerifies.isNotEmpty()) closeOpenCycle()
                openCycleEdits.add(callId)
            }
            kind == ToolKind.LINT || kind == ToolKind.TEST -> {
                if (openCycleEdits.isNotEmpty()) openCycleVerifies.add(callId)
            }
            else -> Unit
        }
    }

    private fun trackVerifyCycleEnd(kind: ToolKind, callId: String, success: Boolean) {
        if ((kind == ToolKind.LINT || kind == ToolKind.TEST) && callId in openCycleVerifies) {
            // 记录该验证是否通过（轮次卡的红绿态）；轮次在下一轮 edit 或收尾时收口
            pendingVerifyPassed = pendingVerifyPassed && success
        }
    }

    private var pendingVerifyPassed = true

    /** 收口当前开着的验证轮次：把轮内胶囊折叠为 VerifyCycleEntry。 */
    private fun closeOpenCycle() {
        if (openCycleEdits.isEmpty() || openCycleVerifies.isEmpty()) {
            openCycleEdits.clear(); openCycleVerifies.clear(); pendingVerifyPassed = true
            return
        }
        cycleRound++
        val ids = openCycleEdits + openCycleVerifies
        val calls = ids.mapNotNull { toolCalls[it] }
        // 从时间轴移除轮内胶囊，原位插入轮次卡（保持时序）
        val firstIdx = entries.indexOfFirst { it.id == toolEntryId(openCycleEdits.first()) }
        val lastIdx = entries.indexOfLast { it.id == toolEntryId(openCycleVerifies.last()) }
        if (firstIdx >= 0 && lastIdx >= firstIdx) {
            val cycleEntry = StreamEntry.VerifyCycleEntry(
                id = "cycle-$cycleRound-${nextId()}",
                cycle = VerifyCycle(cycleRound, openCycleEdits.toList(), openCycleVerifies.toList(), pendingVerifyPassed),
                calls = calls
            )
            // 只折叠轮内工具胶囊；思考/迭代状态等非工具条目原位保留
            // （整段 subList 清空会把它们一并吞掉——时间轴数据丢失）
            val range = entries.subList(firstIdx, lastIdx + 1)
            val nonTool = range.filterNot { it is StreamEntry.ToolCapsuleEntry }
            range.clear()
            entries.add(firstIdx, cycleEntry)
            nonTool.forEachIndexed { i, entry -> entries.add(firstIdx + 1 + i, entry) }
            dirty = true
        }
        openCycleEdits = mutableListOf()
        openCycleVerifies = mutableListOf()
        pendingVerifyPassed = true
    }

    // ═══ 内部：参数与文案 ═══

    private fun append(entry: StreamEntry) {
        entries.add(entry)
        dirty = true
    }

    private fun replaceEntry(id: String, entry: StreamEntry) {
        val idx = entries.indexOfFirst { it.id == id }
        if (idx >= 0) entries[idx] = entry
        dirty = true
    }

    private fun removeEntry(id: String) {
        entries.removeAll { it.id == id }
        dirty = true
    }

    /**
     * 收口流式条目（beginRun/Aborted 路径）：全程空白（模型未思考/
     * 网关空串帧）直接移除占位卡，非空白才定格为终态。
     */
    private fun closeStreamingEntry(entry: StreamEntry) {
        when (entry) {
            is StreamEntry.ThinkingEntry ->
                if (entry.text.isBlank()) removeEntry(entry.id)
                else replaceEntry(entry.id, entry.copy(isStreaming = false))
            is StreamEntry.AssistantEntry ->
                if (entry.text.isBlank()) removeEntry(entry.id)
                else replaceEntry(entry.id, entry.copy(isStreaming = false))
            else -> Unit
        }
    }

    private fun updateCall(call: StreamToolCall) {
        toolCalls[call.id] = call
        val idx = entries.indexOfFirst { it.id == toolEntryId(call.id) }
        if (idx >= 0) entries[idx] = StreamEntry.ToolCapsuleEntry(toolEntryId(call.id), call)
        dirty = true
    }

    private fun toolEntryId(callId: String) = "tool-$callId"

    private fun nextId(): String = "e-${++entrySeq}"

    /** 从条目 id 提取序号（“e-42” / “cycle-1-e-43” → 42/43；其余 → 0）。 */
    private fun entrySeqOf(id: String): Long =
        ENTRY_SEQ_REGEX.find(id)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

    /** 代理对安全截断：首字符若是低代理项则让位，防 emoji 被劈成豆腐块。 */
    private fun safeTakeLast(s: String, n: Int): String = when {
        s.length <= n -> s
        n <= 0 -> ""
        Character.isLowSurrogate(s[s.length - n]) -> s.substring(s.length - n + 1)
        else -> s.substring(s.length - n)
    }

    private fun buildSnapshot(): CodeStreamSnapshot {
        val activeBuf = activeTerminalCallId?.let { terminalBuffers[it] }
        return CodeStreamSnapshot(
            entries = entries.toList(),
            toolCallCount = toolCallCount,
            failedToolCallCount = failedToolCallCount,
            lastError = lastError,
            activeTerminalCallId = activeTerminalCallId,
            terminalContent = activeBuf?.content() ?: "",
            affectedFiles = affectedFiles.toList(),
            terminalBusy = terminalBusy,
            terminalCommandSummary = terminalCommandSummary,
            terminalExitCode = terminalExitCode
        )
    }

    // ═══ 内部：参数解析 / 展示文案 ═══

    private fun parseArgs(raw: String): Map<String, kotlinx.serialization.json.JsonElement> {
        if (raw.isBlank()) return emptyMap()
        return runCatching {
            Json.parseToJsonElement(raw).let { el ->
                (el as? kotlinx.serialization.json.JsonObject)?.toMap() ?: emptyMap()
            }
        }.getOrDefault(emptyMap())
    }

    private fun targetOf(kind: ToolKind, args: Map<String, kotlinx.serialization.json.JsonElement>, toolName: String): String {
        val str = { key: String -> args[key]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNullSafe() } }
        return when (kind) {
            ToolKind.READ_FILE, ToolKind.WRITE_FILE, ToolKind.EDIT_FILE ->
                str("path") ?: str("file") ?: str("file_path") ?: UNKNOWN_TARGET
            ToolKind.GREP_SEARCH -> str("pattern") ?: str("query") ?: str("keyword") ?: toolName
            ToolKind.BASH -> str("command")?.lineSequence()?.firstOrNull()?.take(40) ?: toolName
            ToolKind.GIT -> str("command") ?: str("subcommand") ?: toolName
            ToolKind.LINT, ToolKind.TEST -> str("path") ?: str("target") ?: toolName
            else -> str("name") ?: str("server") ?: toolName
        }
    }

    private fun displayNameOf(kind: ToolKind, toolName: String): String = when (kind) {
        ToolKind.READ_FILE -> "读取"
        ToolKind.WRITE_FILE -> "写入"
        ToolKind.EDIT_FILE -> "编辑"
        ToolKind.GREP_SEARCH -> "搜索"
        ToolKind.BASH -> "命令"
        ToolKind.GIT -> "Git"
        ToolKind.LINT -> "Lint"
        ToolKind.TEST -> "测试"
        ToolKind.PLAN -> "计划"
        ToolKind.MCP_CUSTOM -> toolName.take(16)
    }

    /** 命令退出码：尾部若干行内逐行匹配（TerminalExec 的 `exit N` 尾缀；
     *  尾部优先避免正文日志里 “exit code 0 desired” 之类文本被误命中）。 */
    private fun exitCodeOf(output: String, kind: ToolKind): Int? {
        if (kind != ToolKind.BASH && kind != ToolKind.GIT) return null
        val tail = output.lineSequence().toList().takeLast(EXIT_CODE_TAIL_LINES)
        for (line in tail.asReversed()) {
            val m = EXIT_CODE_REGEX.find(line) ?: continue
            return m.groupValues[1].toIntOrNull()
        }
        return null
    }

    private fun summaryOf(
        status: ToolCallStatus,
        diff: UnifiedDiffParser.ParsedDiff?,
        exitCode: Int?,
        output: String
    ): String {
        if (status == ToolCallStatus.FAILED) {
            val line = output.lineSequence().firstOrNull { it.isNotBlank() } ?: "失败"
            return line.take(SUMMARY_MAX)
        }
        diff?.let { d ->
            if (d.hunks.isNotEmpty()) return "+${d.totalAdded} −${d.totalRemoved} · ${d.hunks.size} hunk"
        }
        exitCode?.let { return "exit $it" }
        val line = output.lineSequence().firstOrNull { it.isNotBlank() }
        return line?.take(SUMMARY_MAX) ?: "完成"
    }

    /** JsonPrimitive content 的 null 安全读取（非 primitive 返回 null）。 */
    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
        runCatching { content }.getOrNull()

    private companion object {
        const val UNKNOWN_TARGET = "…"
        const val ARGS_SUMMARY_MAX = 80
        const val SUMMARY_MAX = 24
        const val NON_BASH_LOG_TAIL_CHARS = 2000
        const val EXIT_CODE_TAIL_LINES = 8

        /** 中止文案：停止卡与中断胶囊的副标题共用。 */
        const val ABORTED_TEXT = "已中止"
        val EXIT_CODE_REGEX = Regex("""(?:exit(?:\s+code)?|Exit:)[:\s]+(\d+)\b""", RegexOption.IGNORE_CASE)
        val ENTRY_SEQ_REGEX = Regex("""(?:^|-)e-(\d+)$""")
    }
}
