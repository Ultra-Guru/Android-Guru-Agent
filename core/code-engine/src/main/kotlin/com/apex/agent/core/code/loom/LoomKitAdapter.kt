package com.apex.agent.core.code.loom

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
import com.apex.agent.core.llm.ToolDefinition
import com.apex.agent.core.llm.runtime.LlmRequestContext
import com.apex.agent.core.llm.runtime.ModelRuntime
import com.apex.agent.core.llm.runtime.ModelRuntimeException
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.core.tools.ToolExecutor
import com.apex.agent.core.tools.ToolRegistry
import com.apex.agent.core.tools.ToolStreamEvent
import com.apex.loomkit.weave.LoomEngine
import com.apex.loomkit.weave.LoomEngineConfig
import com.apex.loomkit.weave.WeaveEvent
import com.apex.loomkit.weave.WeaveExecutionPlan
import com.apex.loomkit.weave.WeaveFileRef
import com.apex.loomkit.weave.WeaveImageContent
import com.apex.loomkit.weave.WeaveInputType
import com.apex.loomkit.weave.WeaveMessage
import com.apex.loomkit.weave.WeaveMode
import com.apex.loomkit.weave.WeaveModelResponse
import com.apex.loomkit.weave.WeaveStreamChunk
import com.apex.loomkit.weave.WeaveThinkingLevel
import com.apex.loomkit.weave.WeaveToolCall
import com.apex.loomkit.weave.WeaveToolDefinition
import com.apex.loomkit.weave.WeaveToolStream
import com.apex.loomkit.weave.WeaveUserInput
import com.apex.loomkit.weave.loop.LoomFailureClassifier
import com.apex.loomkit.weave.loop.LoomFailureKind
import com.apex.loomkit.weave.loop.LoomHeuristicEstimator
import com.apex.loomkit.weave.loop.LoomLogger
import com.apex.loomkit.weave.loop.LoomMemory
import com.apex.loomkit.weave.loop.LoomModelClient
import com.apex.loomkit.weave.loop.LoomModelInfo
import com.apex.loomkit.weave.loop.LoomModelInfoSource
import com.apex.loomkit.weave.loop.LoomTokenEstimator
import com.apex.loomkit.weave.loop.LoomToolRunner
import com.apex.loomkit.weave.loop.LoomToolSource
import com.apex.loomkit.weave.loop.LoomWorkspaceContextSource
import com.apex.loomkit.weave.packager.LoomPackageCatalog
import com.apex.loomkit.weave.permission.LoomPermissionSource
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * # Loom Kit Adapter — 织造思考逻辑线的库接线适配器
 *
 * 织造循环已提取为独立库模块
 * （`com.apex.loomkit:loom-weave`，仓库 Ultra-Guru/apex-loom-kit）——
 * 本类是宿主侧的**薄适配层**（与 [com.apex.agent.core.code.standard.StandardKitAdapter]
 * 同构）：
 *
 * ```
 * CodeViewModel ─► DualLogicCodeEngine ─► LoomKitAdapter（本类）
 *                                          │ 端口适配（模型客户端/工具面/日志/记忆…）
 *                                          │ 协议映射（AgentEvent ↔ WeaveEvent 等）
 *                                          ▼
 *                                     LoomEngine（库内核：梭回合机/权限门/子线程群/织造包）
 * ```
 *
 * ## 职责边界
 *
 * - 库内核（[LoomEngine]）：in-band 标记协议 / 梭回合循环 / 权限三态门 /
 *   子线程群 / 织造包 / digest 压缩——全部循环语义在库中演进；
 * - 本适配器：**双向映射**——宿主类型（AgentEvent / LlmMessage /
 *   ToolDefinition / ModelRuntime …）↔ 库协议类型（WeaveEvent / WeaveMessage /
 *   WeaveToolDefinition / LoomModelClient …）；外加宿主域知识段渲染
 *   （工作区上下文 / 全局与项目规则）。
 *
 * ## 执行器纪律（#230 同款）
 *
 * 织造线库内自带权限门 → 注入的 [toolExecutor] 应为**仅环境门**执行器
 * （宿主 @Named("loomEngineTools")）——共用主执行器的组合门会双弹窗。
 */
class LoomKitAdapter(
    private val runtime: ModelRuntime,
    private val toolRegistry: ToolRegistry,
    private val toolExecutor: ToolExecutor,
    /** 会话记忆（null = 子线程/测试：纯内存会话）。 */
    private val memory: CodeConversationMemory? = null,
    private val contextProvider: CodeContextProvider? = null,
    private val rulesProvider: RulesProvider? = null,
    /** 文本加速核（缺省启发式估算）。 */
    private val textKernel: StandardTextKernel? = null,
    /** 权限快照源（设置层实时通道）。 */
    private val permissionSource: LoomPermissionSource = LoomPermissionSource.NONE,
    /** 模型信息源（真实上下文窗口）。 */
    private val modelInfoProvider: (() -> LoomModelInfo?)? = null,
    /** 织造包目录（宿主技能仓库接线）。 */
    private val packageCatalog: LoomPackageCatalog = LoomPackageCatalog.empty(),
    /** 引擎预算配置。 */
    private val engineConfig: LoomEngineConfig = LoomEngineConfig()
) : AgentEngine, CodeEngineFacade {

    private val engine = LoomEngine(
        modelClient = RuntimeModelClient(),
        hostToolSource = LoomToolSource {
            toolRegistry.getToolDefinitions().map { it.toWeave() }
        },
        hostToolRunner = HostToolRunner(),
        memory = memory?.let { MemoryAdapter(it) },
        permissionSource = permissionSource,
        modelInfoSource = modelInfoProvider?.let { provider ->
            LoomModelInfoSource { provider() }
        },
        workspaceContextSource = HostPromptSections(),
        logger = AppLoggerAdapter,
        estimator = textKernel?.let { kernel ->
            LoomTokenEstimator { kernel.estimateTokens(it) }
        } ?: LoomHeuristicEstimator,
        failureClassifier = HostErrorClassifier,
        packageCatalog = packageCatalog,
        engineConfig = engineConfig
    )

    /** 全局规则缓存（updateGlobalRules 写入——提示词段读）。 */
    @Volatile
    private var globalRulesCache: String = ""

    /** 当前打开文件缓存（提示词工作区段的 "user is viewing" 行）。 */
    @Volatile
    private var activeFile: String? = null

    // ═══════════════════════════ AgentEngine ═══════════════════════════

    override fun execute(input: String): Flow<AgentEvent> =
        engine.execute(input).map { it.toHost() }

    override fun execute(input: UserInput): Flow<AgentEvent> =
        engine.execute(input.toWeave()).map { it.toHost() }

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
        this.activeFile = activeFile
        engine.setActiveWorkspace(workspaceId, name, root, activeFile)
    }

    override fun setActiveFile(activeFile: String?) {
        this.activeFile = activeFile
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
        engine.updateMode(mode.toWeave())
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
        engine.updateThinkingLevel(level.toWeave())
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

    private fun UserInput.toWeave(): WeaveUserInput = WeaveUserInput(
        text = text,
        images = images.map { it.toWeave() },
        files = files.map { WeaveFileRef(it.name, it.mimeType, it.localPath, it.sizeBytes) }
    )

    private fun com.apex.agent.core.llm.ImageContent.toWeave(): WeaveImageContent =
        WeaveImageContent(
            base64Data = base64Data,
            mimeType = mimeType,
            detail = detail,
            url = url
        )

    // ═══════════════════════════ 事件映射（WeaveEvent → AgentEvent）═══════════════════════════

    private fun WeaveEvent.toHost(): AgentEvent = when (this) {
        is WeaveEvent.IterationStart -> AgentEvent.IterationStart(iteration)
        is WeaveEvent.ThinkingStart -> AgentEvent.ThinkingStart(iteration, thinkingLevel.foldToHost())
        is WeaveEvent.ThinkingChunk -> AgentEvent.ThinkingChunk(text)
        is WeaveEvent.ThinkingComplete -> AgentEvent.ThinkingComplete(fullThought)
        is WeaveEvent.ResponseChunk -> AgentEvent.ResponseChunk(text)
        is WeaveEvent.ResponseComplete -> AgentEvent.ResponseComplete(fullText)
        is WeaveEvent.PlanGenerated -> AgentEvent.PlanGenerated(plan.toHost())
        is WeaveEvent.PlanAwaitingConfirmation ->
            AgentEvent.PlanAwaitingConfirmation(plan.toHost())
        is WeaveEvent.PlanConfirmed -> AgentEvent.PlanConfirmed(plan.toHost())
        is WeaveEvent.ToolCallStart -> AgentEvent.ToolCallStart(callId, toolName, arguments)
        is WeaveEvent.ToolOutputChunk -> AgentEvent.ToolOutputChunk(callId, chunk)
        is WeaveEvent.ToolProgress -> AgentEvent.ToolProgress(callId, percent, message)
        is WeaveEvent.ToolCallComplete ->
            AgentEvent.ToolCallComplete(
                callId = callId,
                toolName = toolName,
                arguments = arguments,
                output = output,
                fullOutput = fullOutput,
                success = success,
                durationMs = durationMs
            )
        is WeaveEvent.UsageUpdated ->
            AgentEvent.UsageUpdated(promptTokens, completionTokens, totalTokens)
        is WeaveEvent.ContextCompressed ->
            AgentEvent.ContextCompressed(
                beforeTokens = beforeTokens,
                afterTokens = afterTokens,
                strategy = strategy,
                summary = summary,
                messagesRemoved = messagesRemoved,
                messagesTruncated = messagesTruncated
            )
        is WeaveEvent.UserInputRequired ->
            AgentEvent.UserInputRequired(prompt, type.toHost())
        WeaveEvent.UserInputExpired -> AgentEvent.UserInputExpired
        is WeaveEvent.LlmRetryScheduled ->
            AgentEvent.LlmRetryScheduled(attempt, delayMs, reason)
        is WeaveEvent.Error -> AgentEvent.Error(message, recoverable)
        is WeaveEvent.Complete ->
            AgentEvent.Complete(summary, totalIterations, totalToolCalls, totalDurationMs)
        WeaveEvent.Aborted -> AgentEvent.Aborted
    }

    private fun WeaveInputType.toHost(): InputType = when (this) {
        WeaveInputType.CONFIRMATION -> InputType.CONFIRMATION
        WeaveInputType.TEXT -> InputType.TEXT
        WeaveInputType.CHOICE -> InputType.CHOICE
    }

    /** 八档 → 宿主六档（ULTRACODE/APEXCODE 折叠 MAXIMUM、AUTO 折叠 DEEP——与深潜线映射同口径）。 */
    private fun WeaveThinkingLevel.foldToHost(): ThinkingLevel = when (this) {
        WeaveThinkingLevel.NONE -> ThinkingLevel.NONE
        WeaveThinkingLevel.LIGHT -> ThinkingLevel.LIGHT
        WeaveThinkingLevel.STANDARD -> ThinkingLevel.STANDARD
        WeaveThinkingLevel.DEEP -> ThinkingLevel.DEEP
        WeaveThinkingLevel.MAXIMUM,
        WeaveThinkingLevel.ULTRACODE,
        WeaveThinkingLevel.APEXCODE -> ThinkingLevel.MAXIMUM
        WeaveThinkingLevel.AUTO -> ThinkingLevel.DEEP
    }

    private fun WeaveExecutionPlan.toHost(): ExecutionPlan = ExecutionPlan(
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
            com.apex.loomkit.weave.WeaveRiskLevel.LOW -> RiskLevel.LOW
            com.apex.loomkit.weave.WeaveRiskLevel.MEDIUM -> RiskLevel.MEDIUM
            com.apex.loomkit.weave.WeaveRiskLevel.HIGH -> RiskLevel.HIGH
            com.apex.loomkit.weave.WeaveRiskLevel.CRITICAL -> RiskLevel.CRITICAL
        },
        reasoning = reasoning
    )

    // ═══════════════════════════ 枚举映射 ═══════════════════════════

    private fun AgentMode.toWeave(): WeaveMode =
        runCatching { WeaveMode.valueOf(name) }.getOrDefault(WeaveMode.BUILD)

    private fun WeaveMode.toHost(): AgentMode =
        runCatching { AgentMode.valueOf(name) }.getOrDefault(AgentMode.BUILD)

    private fun CodeThinkingLevel.toWeave(): WeaveThinkingLevel =
        runCatching { WeaveThinkingLevel.valueOf(name) }
            .getOrDefault(WeaveThinkingLevel.STANDARD)
            // AUTO 折叠 DEEP：与事件方向（AUTO → DEEP）同口径——用户选 AUTO
            // 时存储与回显一致，思考卡显示档不与生效档错位。
            .let { if (it == WeaveThinkingLevel.AUTO) WeaveThinkingLevel.DEEP else it }

    private fun WeaveThinkingLevel.toHost(): CodeThinkingLevel =
        runCatching { CodeThinkingLevel.valueOf(name) }
            .getOrDefault(CodeThinkingLevel.STANDARD)

    // ═══════════════════════════ 消息映射（LlmMessage ↔ WeaveMessage）═══════════════════════════

    private fun WeaveMessage.toHost(): LlmMessage = when (this) {
        is WeaveMessage.System -> LlmMessage.System(content)
        is WeaveMessage.User -> LlmMessage.User(
            content = content,
            images = images.map { it.toHost() }
        )

        is WeaveMessage.Assistant -> LlmMessage.Assistant(content, toolCalls.map { it.toHost() })
        is WeaveMessage.ToolResult -> LlmMessage.ToolResult(toolCallId, content)
    }

    private fun WeaveImageContent.toHost(): com.apex.agent.core.llm.ImageContent =
        com.apex.agent.core.llm.ImageContent(
            base64Data = base64Data,
            mimeType = mimeType,
            detail = detail,
            url = url
        )

    private fun WeaveToolCall.toHost(): com.apex.agent.core.llm.ToolCall =
        com.apex.agent.core.llm.ToolCall(
            id = id,
            name = name,
            arguments = arguments,
            index = index
        )

    private fun ToolDefinition.toWeave(): WeaveToolDefinition =
        WeaveToolDefinition(name = name, description = description, parameters = parameters)

    private fun WeaveToolDefinition.toHost(): ToolDefinition =
        ToolDefinition(name = name, description = description, parameters = parameters)

    // ═══════════════════════════ 端口适配 ═══════════════════════════

    /** 模型客户端端口：ModelRuntime（digest tag → SUMMARY 角色路由）。 */
    private inner class RuntimeModelClient : LoomModelClient {
        override fun chatStream(
            contextTag: String,
            messages: List<WeaveMessage>,
            tools: List<WeaveToolDefinition>,
            temperature: Float,
            maxTokens: Int
        ): Flow<WeaveStreamChunk> = runtime.chatStream(
            context = requestContext(contextTag),
            messages = messages.map { it.toHost() },
            tools = tools.map { it.toHost() },
            temperature = temperature,
            maxTokens = maxTokens
        ).map { it.toWeave() }

        override suspend fun chat(
            contextTag: String,
            messages: List<WeaveMessage>,
            temperature: Float,
            maxTokens: Int
        ): WeaveModelResponse = runtime.chat(
            context = requestContext(contextTag),
            messages = messages.map { it.toHost() },
            temperature = temperature,
            maxTokens = maxTokens
        ).toWeave()

        private fun requestContext(contextTag: String): LlmRequestContext =
            if (contextTag == engineConfig.digestContextTag) {
                LlmRequestContext.summary(contextTag)
            } else {
                LlmRequestContext.primary(contextTag)
            }
    }

    private fun LlmStreamChunk.toWeave(): WeaveStreamChunk = WeaveStreamChunk(
        content = content,
        toolCalls = toolCalls.map { it.toWeave() },
        isFinish = isFinish,
        finishReason = finishReason,
        reasoningContent = reasoningContent,
        images = images,
        videos = videos,
        usage = usage?.let {
            com.apex.loomkit.weave.WeaveUsage(
                promptTokens = it.promptTokens,
                completionTokens = it.completionTokens,
                totalTokens = it.totalTokens
            )
        }
    )

    private fun com.apex.agent.core.llm.ToolCall.toWeave(): WeaveToolCall =
        WeaveToolCall(id = id, name = name, arguments = arguments, index = index)

    private fun LlmResponse.toWeave(): WeaveModelResponse = WeaveModelResponse(
        content = content,
        toolCalls = toolCalls.map { it.toWeave() },
        usage = usage?.let {
            com.apex.loomkit.weave.WeaveUsage(
                promptTokens = it.promptTokens,
                completionTokens = it.completionTokens,
                totalTokens = it.totalTokens
            )
        },
        images = images,
        videos = videos
    )

    /** 工具执行端口：宿主执行器（仅环境门——权限确认归库内权限门独占）。 */
    private inner class HostToolRunner : LoomToolRunner {
        override fun executeStream(toolId: String, arguments: String): Flow<WeaveToolStream> =
            toolExecutor.executeStream(toolId, arguments).map { it.toWeave() }
    }

    private fun ToolStreamEvent.toWeave(): WeaveToolStream = when (this) {
        is ToolStreamEvent.Output -> WeaveToolStream.Output(chunk)
        is ToolStreamEvent.Progress -> WeaveToolStream.Progress(percent, message)
        is ToolStreamEvent.Complete -> WeaveToolStream.Complete(output)
        is ToolStreamEvent.Error -> WeaveToolStream.Error(message)
    }

    /** 记忆端口：CodeConversationMemory（独立通道 code_memory_loom）。 */
    private class MemoryAdapter(private val memory: CodeConversationMemory) : LoomMemory {
        override fun load(): List<WeaveMessage> = memory.load().map { it.toWeave() }
        override fun append(message: WeaveMessage) = memory.append(message.toHost())
        override fun save(messages: List<WeaveMessage>) =
            memory.save(messages.map { it.toHost() })

        override fun clear() = memory.clear()
        override fun count(): Int = memory.count()
        override fun bindWorkspace(workspaceId: String) =
            memory.bindWorkspace(workspaceId)

        private fun LlmMessage.toWeave(): WeaveMessage = when (this) {
            is LlmMessage.System -> WeaveMessage.System(content)
            is LlmMessage.User -> WeaveMessage.User(
                content = content,
                images = images.map {
                    WeaveImageContent(
                        base64Data = it.base64Data,
                        mimeType = it.mimeType,
                        detail = it.detail,
                        url = it.url
                    )
                }
            )

            is LlmMessage.Assistant -> WeaveMessage.Assistant(
                content = content,
                toolCalls = toolCalls.map {
                    WeaveToolCall(id = it.id, name = it.name, arguments = it.arguments)
                }
            )
            is LlmMessage.ToolResult -> WeaveMessage.ToolResult(toolCallId, content)
        }

        private fun WeaveMessage.toHost(): LlmMessage = when (this) {
            is WeaveMessage.System -> LlmMessage.System(content)
            is WeaveMessage.User -> LlmMessage.User(
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

            is WeaveMessage.Assistant -> LlmMessage.Assistant(
                content = content,
                toolCalls = toolCalls.map {
                    com.apex.agent.core.llm.ToolCall(it.id, it.name, it.arguments)
                }
            )
            is WeaveMessage.ToolResult -> LlmMessage.ToolResult(toolCallId, content)
        }
    }

    /** 日志端口。 */
    private object AppLoggerAdapter : LoomLogger {
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

    /** 失败分类器：ModelRuntimeException 体系 → 织造失败四类。 */
    private object HostErrorClassifier : LoomFailureClassifier {
        override fun classify(t: Throwable): LoomFailureKind = when (t) {
            is ModelRuntimeException.ModelUnavailable,
            is ModelRuntimeException.ModelRateLimited,
            is ModelRuntimeException.ModelTimeout,
            is ModelRuntimeException.ModelResponseInvalid,
            is IOException -> LoomFailureKind.TRANSIENT
            is ModelRuntimeException.ModelRequestRejected,
            is ModelRuntimeException.ModelConfigurationError,
            is ModelRuntimeException.ProviderConfigurationError,
            is ModelRuntimeException.ModelAuthenticationFailed ->
                if (isContextOverflowText(t.message)) LoomFailureKind.CONTEXT_OVERFLOW
                else LoomFailureKind.REQUEST_REJECTED
            else -> if (isContextOverflowText(t.message)) {
                LoomFailureKind.CONTEXT_OVERFLOW
            } else {
                LoomFailureKind.UNCLASSIFIED
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
    private inner class HostPromptSections : LoomWorkspaceContextSource {
        override fun workspaceSection(): String? {
            val ws = engine.currentWorkspace() ?: return null
            return CodePrompts.workspaceContext(
                workspaceName = ws.name,
                rootLabel = ws.root.path,
                guestPath = GUEST_PATH,
                environmentSummary = contextProvider?.provide(ws.root, activeFile),
                projectStats = null,
                activeFile = activeFile
            )
        }

        override fun globalRulesSection(): String? =
            rulesProvider?.formatGlobalRules(globalRulesCache)?.takeIf { it.isNotBlank() }

        override fun projectRulesSection(): String? {
            val rp = rulesProvider ?: return null
            val ws = engine.currentWorkspace() ?: return null
            val rules = rp.loadProjectRules(ws.root, activeFile) ?: return null
            return rp.formatProjectRules(rules)?.takeIf { it.isNotBlank() }
        }
    }

    companion object {
        private const val TAG = "LoomKitAdapter"

        /** PRoot Ubuntu 会话中工作区统一挂载点（与深潜/标准线一致）。 */
        private const val GUEST_PATH = "/workspace"
    }
}
