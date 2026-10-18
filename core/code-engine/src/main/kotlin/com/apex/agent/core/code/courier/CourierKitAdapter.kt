package com.apex.agent.core.code.courier

import com.apex.agent.core.code.CodeAgentEngine
import com.apex.agent.core.code.CodeContextProvider
import com.apex.agent.core.code.CodeConversationMemory
import com.apex.agent.core.code.CodeEngineFacade
import com.apex.agent.core.code.CodePrompts
import com.apex.agent.core.code.RulesProvider
import com.apex.agent.core.code.StandardTextKernel
import com.apex.agent.core.code.thinking.CodeThinkingLevel
import com.apex.agent.core.engine.AgentEngine
import com.apex.agent.core.engine.AgentEvent
import com.apex.agent.core.engine.AgentMode
import com.apex.agent.core.engine.ExecutionPlan
import com.apex.agent.core.engine.InputType
import com.apex.agent.core.engine.PlanStep
import com.apex.agent.core.engine.RiskLevel
import com.apex.agent.core.engine.ThinkingLevel
import com.apex.agent.core.engine.UserInput
import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.LlmResponse
import com.apex.agent.core.llm.LlmStreamChunk
import com.apex.agent.core.llm.ToolChoiceSpec
import com.apex.agent.core.llm.ToolDefinition
import com.apex.agent.core.llm.runtime.LlmRequestContext
import com.apex.agent.core.llm.runtime.ModelRuntime
import com.apex.agent.core.llm.runtime.ModelRuntimeException
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.core.tools.ToolExecutor
import com.apex.agent.core.tools.ToolRegistry
import com.apex.agent.core.tools.ToolStreamEvent
import com.apex.courierkit.dispatch.CourierCommandProbe
import com.apex.courierkit.dispatch.CourierConfig
import com.apex.courierkit.dispatch.CourierEngine
import com.apex.courierkit.dispatch.CourierEnvironmentInfo
import com.apex.courierkit.dispatch.CourierEnvironmentSource
import com.apex.courierkit.dispatch.CourierFailureClassifier
import com.apex.courierkit.dispatch.CourierFailureKind
import com.apex.courierkit.dispatch.CourierHeuristicEstimator
import com.apex.courierkit.dispatch.CourierLogger
import com.apex.courierkit.dispatch.CourierMemory
import com.apex.courierkit.dispatch.CourierModelClient
import com.apex.courierkit.dispatch.CourierModelInfo
import com.apex.courierkit.dispatch.CourierModelInfoSource
import com.apex.courierkit.dispatch.CourierPermissionSnapshotSource
import com.apex.courierkit.dispatch.CourierSkillCatalog
import com.apex.courierkit.dispatch.CourierTokenEstimator
import com.apex.courierkit.dispatch.CourierToolRunner
import com.apex.courierkit.dispatch.CourierToolSource
import com.apex.courierkit.dispatch.CourierWorkspaceContextSource
import com.apex.courierkit.dispatch.DispatchEvent
import com.apex.courierkit.dispatch.DispatchExecutionPlan
import com.apex.courierkit.dispatch.DispatchFileRef
import com.apex.courierkit.dispatch.DispatchImageContent
import com.apex.courierkit.dispatch.DispatchInputType
import com.apex.courierkit.dispatch.DispatchMessage
import com.apex.courierkit.dispatch.DispatchModelResponse
import com.apex.courierkit.dispatch.DispatchMode
import com.apex.courierkit.dispatch.DispatchRiskLevel
import com.apex.courierkit.dispatch.DispatchStreamChunk
import com.apex.courierkit.dispatch.DispatchThinkingLevel
import com.apex.courierkit.dispatch.DispatchToolCall
import com.apex.courierkit.dispatch.DispatchToolChoice
import com.apex.courierkit.dispatch.DispatchToolDefinition
import com.apex.courierkit.dispatch.DispatchToolStream
import com.apex.courierkit.dispatch.DispatchUserInput
import com.apex.courierkit.dispatch.DispatchUsage
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * # Courier Kit Adapter — 信使思考逻辑线的库接线适配器
 *
 * 信使循环已落地为独立库模块（`com.apex.courierkit:courier-dispatch`，
 * 仓库 Ultra-Guru/apex-courier-kit）——本类是宿主侧的**薄适配层**（与
 * [com.apex.agent.core.code.standard.StandardKitAdapter] /
 * [com.apex.agent.core.code.loom.LoomKitAdapter] 同构）：
 *
 * ```
 * CodeViewModel ─► DualLogicCodeEngine ─► CourierKitAdapter（本类）
 *                                          │ 端口适配（模型客户端/工具面/日志/记忆…）
 *                                          │ 协议映射（AgentEvent ↔ DispatchEvent 等）
 *                                          ▼
 *                                     CourierEngine（库内核：收发匣/趟次步进机/勘合门/档房/下站前哨）
 * ```
 *
 * ## 职责边界
 *
 * - 库内核：收发匣投递语义（steer/queue/控制件）/ 趟次步进机（逻辑步
 *   vs 物理尝试 + 并行工具纤程 + 续令出口分类）/ 勘合门（通配则例三态
 *   法则 + always 记忆）/ 档房压缩（结构化卷宗 + 收缩阶梯）/ 示廊公告
 *   （常设指令价值增量）/ 下站前哨（子驿会话）——全部循环语义在库中演进；
 * - 本适配器：**双向映射**——宿主类型（AgentEvent / LlmMessage /
 *   ToolDefinition / ModelRuntime …）↔ 库协议类型（DispatchEvent /
 *   DispatchMessage / DispatchToolDefinition / CourierModelClient …）；
 *   外加宿主域知识段渲染（工作区上下文 / 全局与项目规则）与设置层
 *   权限规则 → 勘合则例的通道映射。
 *
 * ## 执行器纪律（#230 同款）
 *
 * 信使线库内自带勘合门 → 注入的 [toolExecutor] 应为**仅环境门**执行器
 * （宿主 @Named("courierEngineTools")）——共用主执行器的组合门会双弹窗。
 */
class CourierKitAdapter(
    private val runtime: ModelRuntime,
    private val toolRegistry: ToolRegistry,
    private val toolExecutor: ToolExecutor,
    /** 会话记忆（null = 子驿/测试：纯内存会话）。 */
    private val memory: CodeConversationMemory? = null,
    private val contextProvider: CodeContextProvider? = null,
    private val rulesProvider: RulesProvider? = null,
    /** 文本加速核（缺省启发式估算）。 */
    private val textKernel: StandardTextKernel? = null,
    /** 勘合快照源（设置层实时通道）。 */
    private val permissionSource: CourierPermissionSnapshotSource =
        CourierPermissionSnapshotSource.NONE,
    /** 模型信息源（真实上下文窗口 + 输出上限）。 */
    private val modelInfoProvider: (() -> CourierModelInfo?)? = null,
    /** 技能目录（宿主技能仓库桥——示廊清单 + 委派装载）。 */
    private val skillCatalog: CourierSkillCatalog = CourierSkillCatalog.Empty,
    /** 终端命令探针（勘合逐段核验用）。 */
    private val commandProbe: CourierCommandProbe = CourierCommandProbe.None,
    /** 引擎预算配置。 */
    private val engineConfig: CourierConfig = CourierConfig()
) : AgentEngine, CodeEngineFacade {

    private val engine = CourierEngine(
        modelClient = RuntimeModelClient(),
        hostToolSource = CourierToolSource {
            toolRegistry.getToolDefinitions().map { it.toDispatch() }
        },
        hostToolRunner = HostToolRunner(),
        memory = memory?.let { MemoryAdapter(it) },
        logger = AppLoggerAdapter,
        estimator = textKernel?.let { kernel ->
            CourierTokenEstimator { kernel.estimateTokens(it) }
        } ?: CourierHeuristicEstimator,
        failureClassifier = HostErrorClassifier,
        environmentSource = HostEnvironmentSource(),
        modelInfoSource = modelInfoProvider?.let { provider ->
            CourierModelInfoSource { provider() }
        },
        workspaceContextSource = HostPromptSections(),
        skillCatalog = skillCatalog,
        commandProbe = commandProbe,
        permissionSnapshotSource = permissionSource
    )

    /** 全局规则缓存（updateGlobalRules 写入——提示词段读）。 */
    @Volatile
    private var globalRulesCache: String = ""

    // ═══════════════════════════ AgentEngine ═══════════════════════════

    override fun execute(input: String): Flow<AgentEvent> =
        engine.execute(input).map { it.toHost() }

    override fun execute(input: UserInput): Flow<AgentEvent> =
        engine.execute(input.toDispatch()).map { it.toHost() }

    override suspend fun abort() {
        engine.abort()
    }

    override fun submitUserInput(answer: String) {
        engine.submitUserInput(answer)
    }

    override fun cancelUserInput() {
        engine.cancelUserInput()
    }

    /** 运行中转向（未运行返回 false——调用方改走常规发送）。 */
    fun pushSteer(text: String): Boolean = engine.pushSteer(text)

    // ═══════════════════════════ CodeEngineFacade ═══════════════════════════

    override fun setActiveWorkspace(
        workspaceId: String,
        name: String,
        root: File,
        activeFile: String?
    ) {
        engine.setActiveWorkspace(workspaceId, name, root, activeFile)
    }

    override fun setActiveFile(activeFile: String?) {
        engine.setActiveFile(activeFile)
    }

    override fun prepareForTask() {
        engine.prepareForTask()
    }

    override fun updateGlobalRules(rules: String) {
        globalRulesCache = rules
        engine.updateGlobalRules(rules)
    }

    override fun updateMode(mode: AgentMode) {
        engine.updateMode(mode.toDispatch())
    }

    override fun submitPlanConfirmation(
        confirmed: Boolean,
        enabledSteps: List<Int>?,
        order: List<Int>?
    ): Boolean = engine.submitPlanConfirmation(confirmed)

    override fun updateSessionExtras(extras: String?) {
        engine.updateSessionExtras(extras)
    }

    override fun updateRolePersona(roleDefinition: String, rolePrompt: String?) {
        engine.updateRolePersona(roleDefinition, rolePrompt)
    }

    override fun updateForcedTools(forcedToolIds: Set<String>, exposeAll: Boolean) {
        engine.updateForcedTools(forcedToolIds, exposeAll)
    }

    override fun updateThinkingLevel(level: CodeThinkingLevel) {
        engine.updateThinkingLevel(level.toDispatch())
    }

    override fun thinkingLevel(): CodeThinkingLevel = engine.thinkingLevel().toHost()

    override fun currentThinkingDecision(): String? = engine.currentThinkingDecision()

    override fun clearConversation() {
        engine.clearConversation()
    }

    override fun historyCount(): Int = engine.historyCount()

    override fun currentWorkspace(): CodeAgentEngine.WorkspaceInfo? {
        val ws = engine.currentWorkspace() ?: return null
        return CodeAgentEngine.WorkspaceInfo(ws.id, ws.name, ws.root)
    }

    override fun currentTokenCount(): Int = engine.currentTokenCount()

    override fun maxContextTokens(): Int = engine.maxContextTokens()

    // ═══════════════════════════ 输入映射 ═══════════════════════════

    private fun UserInput.toDispatch(): DispatchUserInput = DispatchUserInput(
        text = text,
        images = images.map { it.toDispatch() },
        files = files.map { DispatchFileRef(it.name, it.mimeType, it.localPath, it.sizeBytes) }
    )

    private fun com.apex.agent.core.llm.ImageContent.toDispatch(): DispatchImageContent =
        DispatchImageContent(
            base64Data = base64Data,
            mimeType = mimeType,
            detail = detail,
            url = url
        )

    // ═══════════════════════════ 事件映射（DispatchEvent → AgentEvent）═══════════════════════════

    private fun DispatchEvent.toHost(): AgentEvent = when (this) {
        is DispatchEvent.IterationStart -> AgentEvent.IterationStart(iteration)
        is DispatchEvent.ThinkingStart -> AgentEvent.ThinkingStart(iteration, level.foldToHost())
        is DispatchEvent.ThinkingChunk -> AgentEvent.ThinkingChunk(text)
        is DispatchEvent.ThinkingComplete -> AgentEvent.ThinkingComplete(fullThought)
        is DispatchEvent.ResponseChunk -> AgentEvent.ResponseChunk(text)
        is DispatchEvent.ResponseComplete -> AgentEvent.ResponseComplete(fullText)
        is DispatchEvent.PlanGenerated -> AgentEvent.PlanGenerated(plan.toHost())
        is DispatchEvent.PlanAwaitingConfirmation ->
            AgentEvent.PlanAwaitingConfirmation(plan.toHost())

        is DispatchEvent.PlanConfirmed -> AgentEvent.PlanConfirmed(plan.toHost())
        is DispatchEvent.ToolCallStart -> AgentEvent.ToolCallStart(callId, toolName, arguments)
        is DispatchEvent.ToolOutputChunk -> AgentEvent.ToolOutputChunk(callId, chunk)
        is DispatchEvent.ToolProgress -> AgentEvent.ToolProgress(callId, percent, message)
        is DispatchEvent.ToolCallComplete ->
            AgentEvent.ToolCallComplete(
                callId = callId,
                toolName = toolName,
                arguments = arguments,
                output = output,
                fullOutput = fullOutput,
                success = success,
                durationMs = durationMs
            )

        is DispatchEvent.UsageUpdated ->
            AgentEvent.UsageUpdated(promptTokens, completionTokens, totalTokens)

        is DispatchEvent.ContextCompressed ->
            AgentEvent.ContextCompressed(
                beforeTokens = beforeTokens,
                afterTokens = afterTokens,
                strategy = strategy,
                summary = summary,
                messagesRemoved = messagesRemoved,
                messagesTruncated = messagesTruncated
            )

        is DispatchEvent.UserInputRequired ->
            AgentEvent.UserInputRequired(prompt, type.toHost())

        DispatchEvent.UserInputExpired -> AgentEvent.UserInputExpired
        is DispatchEvent.LlmRetryScheduled ->
            AgentEvent.LlmRetryScheduled(attempt, delayMs, reason)

        is DispatchEvent.Error -> AgentEvent.Error(message, recoverable)
        is DispatchEvent.Complete ->
            AgentEvent.Complete(summary, totalIterations, totalToolCalls, totalDurationMs)

        DispatchEvent.Aborted -> AgentEvent.Aborted
    }

    private fun DispatchInputType.toHost(): InputType = when (this) {
        DispatchInputType.CONFIRMATION -> InputType.CONFIRMATION
        DispatchInputType.TEXT -> InputType.TEXT
        DispatchInputType.CHOICE -> InputType.CHOICE
    }

    /** 八档 → 宿主六档（ULTRACODE/APEXCODE 折叠 MAXIMUM、AUTO 折叠 DEEP——与另两线映射同口径）。 */
    private fun DispatchThinkingLevel.foldToHost(): ThinkingLevel = when (this) {
        DispatchThinkingLevel.NONE -> ThinkingLevel.NONE
        DispatchThinkingLevel.LIGHT -> ThinkingLevel.LIGHT
        DispatchThinkingLevel.STANDARD -> ThinkingLevel.STANDARD
        DispatchThinkingLevel.DEEP -> ThinkingLevel.DEEP
        DispatchThinkingLevel.MAXIMUM,
        DispatchThinkingLevel.ULTRACODE,
        DispatchThinkingLevel.APEXCODE -> ThinkingLevel.MAXIMUM

        DispatchThinkingLevel.AUTO -> ThinkingLevel.DEEP
    }

    private fun DispatchExecutionPlan.toHost(): ExecutionPlan = ExecutionPlan(
        goal = goal,
        steps = steps.map { s ->
            PlanStep(
                index = s.index,
                description = s.description,
                toolName = s.toolName,
                estimatedArgs = s.estimatedArgs,
                dependsOn = s.dependsOn
            )
        },
        estimatedToolCalls = estimatedToolCalls,
        riskLevel = when (riskLevel) {
            DispatchRiskLevel.LOW -> RiskLevel.LOW
            DispatchRiskLevel.MEDIUM -> RiskLevel.MEDIUM
            DispatchRiskLevel.HIGH -> RiskLevel.HIGH
            DispatchRiskLevel.CRITICAL -> RiskLevel.CRITICAL
        },
        reasoning = reasoning
    )

    // ═══════════════════════════ 枚举映射 ═══════════════════════════

    private fun AgentMode.toDispatch(): DispatchMode =
        runCatching { DispatchMode.valueOf(name) }.getOrDefault(DispatchMode.BUILD)

    private fun CodeThinkingLevel.toDispatch(): DispatchThinkingLevel =
        runCatching { DispatchThinkingLevel.valueOf(name) }
            .getOrDefault(DispatchThinkingLevel.STANDARD)

    private fun DispatchThinkingLevel.toHost(): CodeThinkingLevel =
        runCatching { CodeThinkingLevel.valueOf(name) }
            .getOrDefault(CodeThinkingLevel.STANDARD)

    // ═══════════════════════════ 消息与工具面映射 ═══════════════════════════

    private fun DispatchMessage.toHost(): LlmMessage = when (this) {
        is DispatchMessage.System -> LlmMessage.System(content)
        is DispatchMessage.User -> LlmMessage.User(
            content = content,
            images = images.map { it.toHost() }
        )

        is DispatchMessage.Assistant -> LlmMessage.Assistant(content, toolCalls.map { it.toHost() })
        is DispatchMessage.ToolResult -> LlmMessage.ToolResult(toolCallId, content)
    }

    private fun DispatchImageContent.toHost(): com.apex.agent.core.llm.ImageContent =
        com.apex.agent.core.llm.ImageContent(
            base64Data = base64Data,
            mimeType = mimeType,
            detail = detail,
            url = url
        )

    private fun DispatchToolCall.toHost(): com.apex.agent.core.llm.ToolCall =
        com.apex.agent.core.llm.ToolCall(
            id = id,
            name = name,
            arguments = arguments,
            index = index
        )

    private fun ToolDefinition.toDispatch(): DispatchToolDefinition =
        DispatchToolDefinition(name = name, description = description, parameters = parameters)

    private fun DispatchToolDefinition.toHost(): ToolDefinition =
        ToolDefinition(name = name, description = description, parameters = parameters)

    // ═══════════════════════════ 端口适配 ═══════════════════════════

    /** 模型客户端端口：ModelRuntime（summary tag → SUMMARY 角色路由；toolChoice 透传）。 */
    private inner class RuntimeModelClient : CourierModelClient {
        override fun chatStream(
            contextTag: String,
            messages: List<DispatchMessage>,
            tools: List<DispatchToolDefinition>,
            temperature: Float,
            maxTokens: Int,
            toolChoice: DispatchToolChoice?
        ): Flow<DispatchStreamChunk> = runtime.chatStream(
            context = requestContext(contextTag),
            messages = messages.map { it.toHost() },
            tools = tools.map { it.toHost() },
            temperature = temperature,
            maxTokens = maxTokens,
            toolChoice = toolChoice?.toHost()
        ).map { it.toDispatch() }

        override suspend fun chat(
            contextTag: String,
            messages: List<DispatchMessage>,
            temperature: Float,
            maxTokens: Int
        ): DispatchModelResponse = runtime.chat(
            context = requestContext(contextTag),
            messages = messages.map { it.toHost() },
            temperature = temperature,
            maxTokens = maxTokens
        ).toDispatch()

        private fun requestContext(contextTag: String): LlmRequestContext =
            if (contextTag == engineConfig.summaryContextTag) {
                LlmRequestContext.summary(contextTag)
            } else {
                LlmRequestContext.primary(contextTag)
            }
    }

    private fun DispatchToolChoice.toHost(): ToolChoiceSpec = when (this) {
        DispatchToolChoice.Auto -> ToolChoiceSpec.Auto
        DispatchToolChoice.Required -> ToolChoiceSpec.Required
        is DispatchToolChoice.Function -> ToolChoiceSpec.Function(name)
        DispatchToolChoice.None -> ToolChoiceSpec.None
    }

    private fun LlmStreamChunk.toDispatch(): DispatchStreamChunk = DispatchStreamChunk(
        content = content,
        toolCalls = toolCalls.map { it.toDispatch() },
        isFinish = isFinish,
        finishReason = finishReason,
        reasoningContent = reasoningContent,
        images = images,
        videos = videos,
        usage = usage?.let {
            DispatchUsage(
                promptTokens = it.promptTokens,
                completionTokens = it.completionTokens,
                totalTokens = it.totalTokens
            )
        }
    )

    private fun com.apex.agent.core.llm.ToolCall.toDispatch(): DispatchToolCall =
        DispatchToolCall(id = id, name = name, arguments = arguments, index = index)

    private fun LlmResponse.toDispatch(): DispatchModelResponse = DispatchModelResponse(
        content = content,
        toolCalls = toolCalls.map { it.toDispatch() },
        usage = usage?.let {
            DispatchUsage(
                promptTokens = it.promptTokens,
                completionTokens = it.completionTokens,
                totalTokens = it.totalTokens
            )
        },
        images = images,
        videos = videos
    )

    /** 工具执行端口：宿主执行器（仅环境门——权限确认归库内勘合门独占）。 */
    private inner class HostToolRunner : CourierToolRunner {
        override fun executeStream(toolId: String, arguments: String): Flow<DispatchToolStream> =
            toolExecutor.executeStream(toolId, arguments).map { it.toDispatch() }
    }

    private fun ToolStreamEvent.toDispatch(): DispatchToolStream = when (this) {
        is ToolStreamEvent.Output -> DispatchToolStream.Output(chunk)
        is ToolStreamEvent.Progress -> DispatchToolStream.Progress(percent, message)
        is ToolStreamEvent.Complete -> DispatchToolStream.Complete(output)
        is ToolStreamEvent.Error -> DispatchToolStream.Error(message)
    }

    /**
     * 记忆端口：CodeConversationMemory（独立通道 code_memory_courier）。
     * ⚠️ 非内部类（不引用外层接收者——见织造线 CI R1 教训），自带双向映射。
     */
    private class MemoryAdapter(private val memory: CodeConversationMemory) : CourierMemory {
        override fun load(): List<DispatchMessage> = memory.load().map { it.toDispatch() }
        override fun append(message: DispatchMessage) = memory.append(message.toHost())
        override fun save(messages: List<DispatchMessage>) =
            memory.save(messages.map { it.toHost() })

        override fun clear() = memory.clear()
        override fun count(): Int = memory.count()
        override fun bindWorkspace(workspaceId: String) =
            memory.bindWorkspace(workspaceId)

        private fun LlmMessage.toDispatch(): DispatchMessage = when (this) {
            is LlmMessage.System -> DispatchMessage.System(content)
            is LlmMessage.User -> DispatchMessage.User(
                content = content,
                images = images.map {
                    DispatchImageContent(
                        base64Data = it.base64Data,
                        mimeType = it.mimeType,
                        detail = it.detail,
                        url = it.url
                    )
                }
            )

            is LlmMessage.Assistant -> DispatchMessage.Assistant(content)
            is LlmMessage.ToolResult -> DispatchMessage.ToolResult(toolCallId, content)
        }

        private fun DispatchMessage.toHost(): LlmMessage = when (this) {
            is DispatchMessage.System -> LlmMessage.System(content)
            is DispatchMessage.User -> LlmMessage.User(
                content = content,
                images = images.map {
                    com.apex.agent.core.llm.ImageContent(
                        base64Data = it.base64Data,
                        mimeType = it.mimeType,
                        detail = it.detail,
                        url = it.url
                    )
                }
            )

            is DispatchMessage.Assistant -> LlmMessage.Assistant(content)
            is DispatchMessage.ToolResult -> LlmMessage.ToolResult(toolCallId, content)
        }
    }

    /** 日志端口。 */
    private object AppLoggerAdapter : CourierLogger {
        override fun info(tag: String, message: String) {
            AppLogger.instance.info(LogCategory.ENGINE, tag, message)
        }

        override fun warn(tag: String, message: String) {
            AppLogger.instance.warn(LogCategory.ENGINE, tag, message)
        }

        override fun error(tag: String, message: String, throwable: Throwable?) {
            AppLogger.instance.error(LogCategory.ENGINE, tag, message, throwable)
        }
    }

    /** 失败分类器：ModelRuntimeException 体系 → 信使失败四类。 */
    private object HostErrorClassifier : CourierFailureClassifier {
        override fun classify(t: Throwable): CourierFailureKind = when (t) {
            is ModelRuntimeException.ModelUnavailable,
            is ModelRuntimeException.ModelRateLimited,
            is ModelRuntimeException.ModelTimeout,
            is ModelRuntimeException.ModelResponseInvalid,
            is IOException -> CourierFailureKind.TRANSIENT

            is ModelRuntimeException.ModelRequestRejected,
            is ModelRuntimeException.ModelConfigurationError,
            is ModelRuntimeException.ProviderConfigurationError,
            is ModelRuntimeException.ModelAuthenticationFailed ->
                if (isContextOverflowText(t.message)) {
                    CourierFailureKind.CONTEXT_OVERFLOW
                } else {
                    CourierFailureKind.REQUEST_REJECTED
                }

            else -> if (isContextOverflowText(t.message)) {
                CourierFailureKind.CONTEXT_OVERFLOW
            } else {
                CourierFailureKind.UNCLASSIFIED
            }
        }

        private fun isContextOverflowText(message: String?): Boolean {
            val text = (message ?: "").lowercase()
            return OVERFLOW_MARKERS.any { text.contains(it) }
        }

        private val OVERFLOW_MARKERS = listOf(
            "context length", "context window", "maximum context",
            "too long", "prompt is too long", "input tokens exceed",
            "token limit", "context_length_exceeded", "maximum number of tokens"
        )
    }

    /** 宿主提示词段（工作区 / 全局规则 / 项目规则）。 */
    private inner class HostPromptSections : CourierWorkspaceContextSource {
        override fun workspaceSection(): String? {
            val ws = engine.currentWorkspace() ?: return null
            val root = ws.root ?: return null
            return CodePrompts.workspaceContext(
                workspaceName = ws.id,
                rootLabel = root.path,
                guestPath = GUEST_PATH,
                environmentSummary = contextProvider?.provide(root, null),
                projectStats = null,
                activeFile = null
            )
        }

        override fun globalRulesSection(): String? =
            rulesProvider?.formatGlobalRules(globalRulesCache)?.takeIf { it.isNotBlank() }

        override fun projectRulesSection(): String? {
            val rp = rulesProvider ?: return null
            val ws = engine.currentWorkspace() ?: return null
            val root = ws.root ?: return null
            val rules = rp.loadProjectRules(root, null) ?: return null
            return rp.formatProjectRules(rules)?.takeIf { it.isNotBlank() }
        }
    }

    /** 环境源端口：工作区根 + 平台行。 */
    private inner class HostEnvironmentSource : CourierEnvironmentSource {
        override fun current(): CourierEnvironmentInfo = CourierEnvironmentInfo(
            platformText = "Android (PRoot Ubuntu terminal)",
            workdirLabel = engine.currentWorkspace()?.root?.path,
            isGitRepo = null
        )
    }

    companion object {
        private const val TAG = "CourierKitAdapter"

        /** PRoot Ubuntu 会话中工作区统一挂载点（与另三线一致）。 */
        private const val GUEST_PATH = "/workspace"
    }
}
