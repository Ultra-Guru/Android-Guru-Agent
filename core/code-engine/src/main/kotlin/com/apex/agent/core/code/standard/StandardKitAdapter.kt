package com.apex.agent.core.code.standard

import com.apex.agent.core.code.CodeAgentEngine
import com.apex.agent.core.code.CodeContextProvider
import com.apex.agent.core.code.CodeConversationMemory
import com.apex.agent.core.code.CodeEngineFacade
import com.apex.agent.core.code.CodePrompts
import com.apex.agent.core.code.RulesProvider
import com.apex.agent.core.code.StandardTextKernel
import com.apex.agent.core.code.subagent.SubAgentSettings
import com.apex.agent.core.code.thinking.CodeThinkingLevel
import com.apex.agent.core.engine.AgentEngine
import com.apex.agent.core.engine.AgentEvent
import com.apex.agent.core.engine.AgentMode
import com.apex.agent.core.engine.ExecutionPlan
import com.apex.agent.core.engine.PlanStep
import com.apex.agent.core.engine.RiskLevel
import com.apex.agent.core.engine.UserInput
import com.apex.agent.core.engine.InputType
import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.ToolChoiceSpec
import com.apex.agent.core.llm.runtime.LlmRequestContext
import com.apex.agent.core.llm.runtime.ModelRuntime
import com.apex.agent.core.llm.runtime.ModelRuntimeException
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.core.tools.ToolExecutor
import com.apex.agent.core.tools.ToolRegistry
import com.apex.agent.core.tools.ToolStreamEvent
import com.apex.standardkit.loop.AgentLoopEngine
import com.apex.standardkit.loop.KitAttachment
import com.apex.standardkit.loop.KitChatResponse
import com.apex.standardkit.loop.KitEngineConfig
import com.apex.standardkit.loop.KitEnvironmentInfo
import com.apex.standardkit.loop.KitEvent
import com.apex.standardkit.loop.KitImageContent
import com.apex.standardkit.loop.KitLlmErrorClassifier
import com.apex.standardkit.loop.KitLlmErrorKind
import com.apex.standardkit.loop.KitMcpInstructionSource
import com.apex.standardkit.loop.KitMessage
import com.apex.standardkit.loop.KitModelClient
import com.apex.standardkit.loop.KitModelInfo
import com.apex.standardkit.loop.KitModelInfoSource
import com.apex.standardkit.loop.KitPermissionSource
import com.apex.standardkit.loop.KitPlan
import com.apex.standardkit.loop.KitRequestOptions
import com.apex.standardkit.loop.KitRiskLevel
import com.apex.standardkit.loop.KitStreamChunk
import com.apex.standardkit.loop.KitSubAgentBudget
import com.apex.standardkit.loop.KitThinkingLevel
import com.apex.standardkit.loop.KitToolChoice
import com.apex.standardkit.loop.KitToolDefinition
import com.apex.standardkit.loop.KitToolSource
import com.apex.standardkit.loop.KitToolStreamEvent
import com.apex.standardkit.loop.KitUserInput
import com.apex.standardkit.loop.KitWorkspaceContextSource
import com.apex.standardkit.loop.KitWorkspaceInfo
import java.io.File
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * # Standard Kit Adapter — 标准思考逻辑线的库接线适配器
 *
 * 标准任务循环已提取为独立库模块
 * （`com.apex.standardkit:standard-loop`，仓库 Ultra-Guru/apex-standard-kit）——
 * 本类是宿主侧的**薄适配层**：
 *
 * ```
 * CodeViewModel ─► DualLogicCodeEngine ─► StandardKitAdapter（本类）
 *                                          │ 端口适配（模型客户端/工具面/日志/记忆…）
 *                                          ▼
 *                                    AgentLoopEngine（库内核）
 * ```
 *
 * ## 职责边界
 *
 * - 库内核（[AgentLoopEngine]）：回合循环 / 画像 / 权限门 / 压缩 / 子代理 /
 *   并行工具执行 / 续跑语义 / MCP 指引渲染——全部循环语义在库中演进；
 * - 本适配器：**双向映射**——宿主类型（AgentEvent / LlmMessage /
 *   ToolDefinition / ModelRuntime …）↔ 库协议类型（KitEvent / KitMessage /
 *   KitToolDefinition / KitModelClient …）；外加宿主域知识段渲染
 *   （工作区上下文 / 全局与项目规则——[KitWorkspaceContextSource] 端口实现）。
 *
 * ## 与旧 StandardModeEngine 的行为对应
 *
 * 构造参数面保持同构（runtime / toolRegistry / toolExecutor / memory /
 * contextProvider / rulesProvider / textKernel / 权限源 / 模型信息源 /
 * 子代理预算源）——DI（CodeModule）接线方式不变；引擎行为由库版本
 * 全面接棒（并行工具执行 / 输出截断续跑 / 溢出压缩恢复 / MCP 指引等
 * 增强语义见库 README）。
 */
class StandardKitAdapter(
    private val runtime: ModelRuntime,
    private val toolRegistry: ToolRegistry,
    private val toolExecutor: ToolExecutor,
    /** 会话记忆（null = 子代理/测试：纯内存会话，不落盘）。 */
    private val memory: CodeConversationMemory? = null,
    private val contextProvider: CodeContextProvider? = null,
    private val rulesProvider: RulesProvider? = null,
    /** 文本加速核（可选；native JNI 实现由 App 层注入，失败自动回退）。 */
    private val textKernel: StandardTextKernel? = null,
    /** 权限配置源（设置层快照通道；NONE = 纯模式兜底）。 */
    private val permissionSource: KitPermissionSource = KitPermissionSource.NONE,
    /** 模型信息提供者（modelId + 真实上下文窗口）。 */
    private val modelInfoProvider: (() -> KitModelInfo?)? = null,
    /** MCP 服务器指引源（App 层从 McpManager 接线）。 */
    private val mcpInstructionSource: KitMcpInstructionSource? = null,
    /** v3 子代理预算快照源（设置 → 子代理）。 */
    private val subAgentSettingsProvider: (() -> SubAgentSettings)? = null,
    /** LLM 容错阶梯延迟执行器（测试注入）。 */
    private val retrySleeper: suspend (Long) -> Unit = { delay(it) },
    /** 引擎预算配置（宿主默认值与深潜线口径对齐）。 */
    private val engineConfig: KitEngineConfig = KitEngineConfig(
        toolOutputBudget = 8_000,
        maxContextTokens = 128_000,
        compressionThreshold = 0.8f,
        preserveRecentMessages = 8,
        contextTag = "standard_loop",
        subAgentContextTag = "standard_subagent_loop",
        compactionContextTag = "standard_compaction"
    )
) : AgentEngine, CodeEngineFacade {

    // ═══════════════════════ 宿主域状态（提示词段渲染源）═══════════════════════

    /** 全局规则文本（JIT 注入——设置层同步）。 */
    @Volatile
    private var globalRules: String = ""

    /** 工作区状态（root 为宿主 File；库只拿路径字符串）。 */
    @Volatile
    private var workspaceId: String? = null

    @Volatile
    private var workspaceName: String? = null

    @Volatile
    private var workspaceRoot: File? = null

    @Volatile
    private var activeFile: String? = null

    // ═══════════════════════ 库引擎装配 ═══════════════════════

    /** 库引擎（标准任务循环内核——本适配器的全部循环语义来源）。 */
    private val loopEngine: AgentLoopEngine = AgentLoopEngine(
        modelClient = RuntimeModelClient(),
        toolSource = RegistryToolSource(),
        toolRunner = ExecutorToolRunner(),
        config = engineConfig,
        logger = AppLoggerAdapter,
        memory = memory?.let { MemoryAdapter(it) },
        textKernel = textKernel?.let { kernel ->
            com.apex.standardkit.loop.KitTextKernel { text -> kernel.estimateTokens(text) }
        },
        permissionSource = permissionSource,
        // SAM 构造器接 lambda 字面量（fun interface）——模型信息源端口接入
        modelInfoSource = modelInfoProvider?.let { provider ->
            KitModelInfoSource { provider() }
        },
        environmentInfoSource = { environmentInfo() },
        workspaceContextSource = HostPromptSections(),
        mcpInstructionSource = mcpInstructionSource,
        subAgentBudgetSource = subAgentSettingsProvider?.let { provider ->
            com.apex.standardkit.loop.KitSubAgentBudgetSource { provider().toKitBudget() }
        },
        errorClassifier = RuntimeErrorClassifier,
        retrySleeper = retrySleeper
    )

    // ═══════════════════════ 端口适配 ═══════════════════════

    /** 请求上下文：压缩摘要走 SUMMARY 角色（与旧引擎口径一致），其余 PRIMARY。 */
    private fun requestContext(contextTag: String): LlmRequestContext =
        if (contextTag == engineConfig.compactionContextTag) {
            LlmRequestContext.summary(contextTag)
        } else {
            LlmRequestContext.primary(contextTag)
        }

    /** 模型客户端端口：ModelRuntime ↔ KitModelClient（含 toolChoice 映射）。 */
    private inner class RuntimeModelClient : KitModelClient {
        override fun chatStream(
            contextTag: String,
            messages: List<KitMessage>,
            tools: List<KitToolDefinition>,
            options: KitRequestOptions
        ): Flow<KitStreamChunk> =
            runtime.chatStream(
                context = requestContext(contextTag),
                messages = messages.map { it.toLlmMessage() },
                tools = tools.map { it.toToolDefinition() },
                temperature = options.temperature,
                maxTokens = options.maxTokens,
                toolChoice = when (options.toolChoice) {
                    KitToolChoice.AUTO -> null
                    KitToolChoice.NONE -> ToolChoiceSpec.None
                }
            ).map { chunk ->
                KitStreamChunk(
                    content = chunk.content,
                    toolCalls = chunk.toolCalls.map { it.toKitToolCall() },
                    reasoningContent = chunk.reasoningContent,
                    usage = chunk.usage?.let {
                        com.apex.standardkit.loop.KitUsage(
                            it.promptTokens, it.completionTokens, it.totalTokens
                        )
                    },
                    isFinish = chunk.isFinish,
                    finishReason = chunk.finishReason
                )
            }

        override suspend fun chat(
            contextTag: String,
            messages: List<KitMessage>,
            temperature: Float,
            maxTokens: Int
        ): KitChatResponse =
            runtime.chat(
                context = requestContext(contextTag),
                messages = messages.map { it.toLlmMessage() },
                temperature = temperature,
                maxTokens = maxTokens
            ).let { response ->
                KitChatResponse(
                    content = response.content,
                    usage = response.usage?.let {
                        com.apex.standardkit.loop.KitUsage(
                            it.promptTokens, it.completionTokens, it.totalTokens
                        )
                    }
                )
            }
    }

    /** 工具定义源端口：ToolRegistry → KitToolDefinition 快照。 */
    private inner class RegistryToolSource : KitToolSource {
        override fun definitions(): List<KitToolDefinition> =
            toolRegistry.getToolDefinitions().map { it.toKitToolDefinition() }
    }

    /** 工具执行端口：ToolExecutor 流事件 → KitToolStreamEvent。 */
    private inner class ExecutorToolRunner : com.apex.standardkit.loop.KitToolRunner {
        override fun executeStream(name: String, arguments: String): Flow<KitToolStreamEvent> =
            toolExecutor.executeStream(name, arguments).map { event ->
                when (event) {
                    is ToolStreamEvent.Output -> KitToolStreamEvent.Output(event.chunk)
                    is ToolStreamEvent.Progress -> KitToolStreamEvent.Progress(event.percent, event.message)
                    is ToolStreamEvent.Complete -> KitToolStreamEvent.Complete(event.output)
                    is ToolStreamEvent.Error -> KitToolStreamEvent.Error(event.message)
                }
            }
    }

    /** 日志端口：AppLogger ↔ KitLogger。 */
    private object AppLoggerAdapter : com.apex.standardkit.loop.KitLogger {
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

    /**
     * 失败分类器：宿主 ModelRuntimeException 体系 → KitLlmErrorKind
     * （瞬时 / 请求级拒绝 / 上下文溢出 / 未分类），消息嗅探兜底。
     */
    private object RuntimeErrorClassifier : KitLlmErrorClassifier {
        override fun classify(t: Throwable): KitLlmErrorKind = when (t) {
            is ModelRuntimeException.ModelUnavailable,
            is ModelRuntimeException.ModelRateLimited,
            is ModelRuntimeException.ModelTimeout,
            is ModelRuntimeException.ModelResponseInvalid,
            is IOException -> KitLlmErrorKind.TRANSIENT
            is ModelRuntimeException.ModelRequestRejected,
            is ModelRuntimeException.ModelConfigurationError,
            is ModelRuntimeException.ProviderConfigurationError,
            is ModelRuntimeException.ModelAuthenticationFailed -> {
                // 请求级拒绝里再嗅一层上下文溢出（部分网关以 400 报超窗）
                if (isContextOverflowText(t.message)) KitLlmErrorKind.CONTEXT_OVERFLOW
                else KitLlmErrorKind.REQUEST_REJECTION
            }
            else -> if (isContextOverflowText(t.message)) {
                KitLlmErrorKind.CONTEXT_OVERFLOW
            } else KitLlmErrorClassifier.Default.classify(t)
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

    /** 会话记忆端口：CodeConversationMemory ↔ KitMemory（消息双向映射）。 */
    private class MemoryAdapter(private val memory: CodeConversationMemory) :
        com.apex.standardkit.loop.KitMemory {
        override fun append(message: KitMessage) {
            memory.append(message.toLlmMessage())
        }

        override fun save(messages: List<KitMessage>) {
            memory.save(messages.map { it.toLlmMessage() })
        }

        override fun load(): List<KitMessage> =
            memory.load().map { it.toKitMessage() }

        override fun clear() = memory.clear()
        override fun count(): Int = memory.count()
        override fun bindWorkspace(workspaceId: String) =
            memory.bindWorkspace(workspaceId)
    }

    /** 环境信息（env 块的平台/目录/git 行）。 */
    private fun environmentInfo(): KitEnvironmentInfo = KitEnvironmentInfo(
        platformText = PLATFORM_TEXT,
        workdirLabel = workspaceRoot?.path,
        isGitRepo = workspaceRoot?.let { File(it, ".git").exists() }
    )

    /**
     * 宿主提示词段提供者：工作区上下文 + 全局规则 + 项目规则
     * （AGENTS.md 等仓库级规则发现与格式化——宿主域知识）。
     */
    private inner class HostPromptSections :
        KitWorkspaceContextSource {

        override fun workspaceSection(): String? {
            val root = workspaceRoot ?: return null
            return CodePrompts.workspaceContext(
                workspaceName = workspaceName ?: root.name,
                rootLabel = root.path,
                guestPath = GUEST_PATH,
                environmentSummary = contextProvider?.provide(root, activeFile),
                projectStats = null,
                activeFile = activeFile
            )
        }

        override fun globalRulesSection(): String? =
            rulesProvider?.formatGlobalRules(globalRules)?.takeIf { it.isNotBlank() }

        override fun projectRulesSection(): String? {
            val rp = rulesProvider ?: return null
            val rules = rp.loadProjectRules(workspaceRoot, activeFile) ?: return null
            return rp.formatProjectRules(rules)?.takeIf { it.isNotBlank() }
        }
    }

    // ═══════════════════════ AgentEngine ═══════════════════════

    override fun execute(input: String): Flow<AgentEvent> =
        execute(UserInput.text(input))

    override fun execute(input: UserInput): Flow<AgentEvent> =
        loopEngine.execute(input.toKitUserInput()).map { it.toAgentEvent() }

    override suspend fun abort() = loopEngine.abort()

    override fun submitUserInput(answer: String) = loopEngine.submitUserInput(answer)

    override fun cancelUserInput() = loopEngine.cancelUserInput()

    // ═══════════════════════ CodeEngineFacade ═══════════════════════

    override fun setActiveWorkspace(
        workspaceId: String,
        name: String,
        root: File,
        activeFile: String?
    ) {
        this.workspaceId = workspaceId
        this.workspaceName = name
        this.workspaceRoot = root
        this.activeFile = activeFile
        loopEngine.setActiveWorkspace(
            KitWorkspaceInfo(
                workspaceId = workspaceId,
                name = name,
                rootPath = root.absolutePath,
                activeFilePath = activeFile
            )
        )
    }

    override fun setActiveFile(activeFile: String?) {
        this.activeFile = activeFile
        loopEngine.setActiveFile(activeFile)
    }

    override fun prepareForTask() = loopEngine.prepareForTask()

    override fun updateGlobalRules(rules: String) {
        globalRules = rules
        // 系统提示词每回合 JIT 重建——下次请求生效
    }

    override fun updateMode(mode: AgentMode) {
        loopEngine.updateMode(mode.name.lowercase())
    }

    override fun submitPlanConfirmation(
        confirmed: Boolean,
        enabledSteps: List<Int>?,
        order: List<Int>?
    ): Boolean = loopEngine.submitPlanConfirmationIfAwaiting(confirmed, enabledSteps, order)

    override fun updateSessionExtras(extras: String?) =
        loopEngine.updateSessionExtras(extras)

    override fun updateRolePersona(roleDefinition: String, rolePrompt: String?) =
        loopEngine.updateRolePersona(roleDefinition, rolePrompt)

    override fun updateForcedTools(forcedToolIds: Set<String>, exposeAll: Boolean) =
        loopEngine.updateForcedTools(forcedToolIds, exposeAll)

    override fun updateThinkingLevel(level: CodeThinkingLevel) =
        loopEngine.updateThinkingLevel(level.toKit())

    override fun thinkingLevel(): CodeThinkingLevel = loopEngine.thinkingLevel().toHost()

    override fun currentThinkingDecision(): String? = null

    override fun clearConversation() = loopEngine.clearConversation()

    override fun historyCount(): Int = loopEngine.historyCount()

    override fun currentWorkspace(): CodeAgentEngine.WorkspaceInfo? {
        val id = workspaceId ?: return null
        return CodeAgentEngine.WorkspaceInfo(id, workspaceName ?: id, workspaceRoot)
    }

    override fun currentTokenCount(): Int = loopEngine.currentTokenCount()

    override fun maxContextTokens(): Int = loopEngine.maxContextTokens()

    /**
     * 运行中转向（steering）——库引擎能力在宿主侧的直通口。
     * [CodeViewModel] 可在运行中把用户补充指令入队（下个回合边界注入），
     * 未运行时返回 false（调用方改走常规发送）。
     */
    fun pushSteer(text: String): Boolean = loopEngine.pushSteer(text)

    /** 库引擎直通（诊断/扩展用——正常路径全部经本适配器门面）。 */
    fun loopEngineHandle(): AgentLoopEngine = loopEngine

    companion object {
        /** PRoot Ubuntu 会话中工作区统一挂载点（与 CodeAgentEngine 一致）。 */
        private const val GUEST_PATH = "/workspace"

        /** env 块的平台行（与 PRoot Ubuntu 沙箱宿主一致）。 */
        private const val PLATFORM_TEXT = "Android with a PRoot Ubuntu sandbox"
    }
}

// ═══════════════════════ 类型映射（协议 ↔ 宿主）═══════════════════════
//
// 纯函数映射（零外部状态捕获）——文件级私有扩展：
// 1. 嵌套适配类（含非 inner 的 MemoryAdapter）均可直接调用，
//    无需外部实例接收者；
// 2. 协议 ↔ 宿主的双向转换语义一目了然，新增嵌套端口实现时零改动。

private fun UserInput.toKitUserInput(): KitUserInput = KitUserInput(
    text = text,
    images = images.map {
        KitImageContent(
            base64Data = it.base64Data,
            mimeType = it.mimeType,
            url = it.url
        )
    },
    files = files.map { KitAttachment(it.name, it.mimeType, it.sizeBytes) }
)

private fun KitMessage.toLlmMessage(): LlmMessage = when (this) {
    is KitMessage.System -> LlmMessage.System(content)
    is KitMessage.User -> LlmMessage.User(content, images.map { it.toImageContent() })
    is KitMessage.Assistant -> LlmMessage.Assistant(
        content,
        toolCalls.map { it.toHostToolCall() }
    )
    is KitMessage.ToolResult -> LlmMessage.ToolResult(toolCallId, content)
}

private fun LlmMessage.toKitMessage(): KitMessage = when (this) {
    is LlmMessage.System -> KitMessage.System(content)
    is LlmMessage.User -> KitMessage.User(content, images.map { it.toKitImage() })
    is LlmMessage.Assistant -> KitMessage.Assistant(
        content,
        toolCalls.map { it.toKitToolCall() }
    )
    is LlmMessage.ToolResult -> KitMessage.ToolResult(toolCallId, content)
}

private fun KitImageContent.toImageContent() =
    com.apex.agent.core.llm.ImageContent(
        base64Data = base64Data,
        mimeType = mimeType,
        url = url
    )

private fun com.apex.agent.core.llm.ImageContent.toKitImage() =
    KitImageContent(base64Data = base64Data, mimeType = mimeType, url = url)

private fun com.apex.agent.core.llm.ToolCall.toKitToolCall() =
    com.apex.standardkit.loop.KitToolCall(
        id = id,
        name = name,
        arguments = arguments,
        index = index
    )

private fun com.apex.standardkit.loop.KitToolCall.toHostToolCall() =
    com.apex.agent.core.llm.ToolCall(
        id = id,
        name = name,
        arguments = arguments,
        index = index
    )

private fun com.apex.agent.core.llm.ToolDefinition.toKitToolDefinition() =
    KitToolDefinition(name = name, description = description, parameters = parameters)

private fun KitToolDefinition.toToolDefinition() =
    com.apex.agent.core.llm.ToolDefinition(
        name = name,
        description = description,
        parameters = parameters
    )

private fun SubAgentSettings.toKitBudget() = KitSubAgentBudget(
    enabled = enabled,
    maxConcurrent = maxConcurrent,
    timeoutMs = timeoutMs,
    outputLimit = outputLimit,
    toolOutputLimit = toolOutputLimit
)

private fun CodeThinkingLevel.toKit(): KitThinkingLevel =
    runCatching { KitThinkingLevel.valueOf(name) }.getOrDefault(KitThinkingLevel.STANDARD)

private fun KitThinkingLevel.toHost(): CodeThinkingLevel =
    runCatching { CodeThinkingLevel.valueOf(name) }.getOrDefault(CodeThinkingLevel.STANDARD)

private fun KitPlan.toExecutionPlan() = ExecutionPlan(
    goal = goal,
    steps = steps.map {
        PlanStep(
            index = it.index,
            description = it.description,
            toolName = it.toolName,
            estimatedArgs = it.estimatedArgs,
            dependsOn = it.dependsOn
        )
    },
    estimatedToolCalls = estimatedToolCalls,
    riskLevel = when (riskLevel) {
        KitRiskLevel.LOW -> RiskLevel.LOW
        KitRiskLevel.MEDIUM -> RiskLevel.MEDIUM
        KitRiskLevel.HIGH -> RiskLevel.HIGH
        KitRiskLevel.CRITICAL -> RiskLevel.CRITICAL
    },
    reasoning = reasoning
)

/** 库事件 → 宿主 AgentEvent（1:1——胶囊时间轴零改动消费）。 */
private fun KitEvent.toAgentEvent(): AgentEvent = when (this) {
    is KitEvent.IterationStart -> AgentEvent.IterationStart(iteration)
    is KitEvent.ThinkingChunk -> AgentEvent.ThinkingChunk(text)
    is KitEvent.ResponseChunk -> AgentEvent.ResponseChunk(text)
    is KitEvent.ResponseComplete -> AgentEvent.ResponseComplete(fullText)
    is KitEvent.UsageUpdated -> AgentEvent.UsageUpdated(
        promptTokens, completionTokens, totalTokens
    )
    is KitEvent.ToolCallStart -> AgentEvent.ToolCallStart(callId, toolName, arguments)
    is KitEvent.ToolOutputChunk -> AgentEvent.ToolOutputChunk(callId, chunk)
    is KitEvent.ToolProgress -> AgentEvent.ToolProgress(callId, percent, message)
    is KitEvent.ToolCallComplete -> AgentEvent.ToolCallComplete(
        callId = callId,
        toolName = toolName,
        arguments = arguments,
        output = output,
        fullOutput = fullOutput,
        success = success,
        durationMs = durationMs
    )
    is KitEvent.UserInputRequired -> AgentEvent.UserInputRequired(prompt, type.toHost())
    KitEvent.UserInputExpired -> AgentEvent.UserInputExpired
    is KitEvent.ContextCompressed -> AgentEvent.ContextCompressed(
        beforeTokens = beforeTokens,
        afterTokens = afterTokens,
        strategy = strategy,
        summary = summary,
        messagesRemoved = messagesRemoved,
        messagesTruncated = messagesTruncated
    )
    is KitEvent.PlanGenerated -> AgentEvent.PlanGenerated(plan.toExecutionPlan())
    is KitEvent.PlanAwaitingConfirmation ->
        AgentEvent.PlanAwaitingConfirmation(plan.toExecutionPlan())
    is KitEvent.PlanConfirmed -> AgentEvent.PlanConfirmed(plan.toExecutionPlan())
    is KitEvent.SteeringAccepted -> AgentEvent.ResponseChunk(
        // 转向受理回执：以增量文本呈现（UI 侧无需新增事件类型即可见）
        "[steering] 已入队补充指令：$text\n"
    )
    is KitEvent.LlmRetryScheduled -> AgentEvent.LlmRetryScheduled(
        attempt = attempt,
        delayMs = delayMs,
        reason = reason
    )
    is KitEvent.Error -> AgentEvent.Error(message, recoverable)
    is KitEvent.Complete -> AgentEvent.Complete(
        summary = summary,
        totalIterations = totalIterations,
        totalToolCalls = totalToolCalls,
        totalDurationMs = totalDurationMs
    )
    KitEvent.Aborted -> AgentEvent.Aborted
}

private fun com.apex.standardkit.loop.KitInputType.toHost(): InputType = when (this) {
    com.apex.standardkit.loop.KitInputType.CONFIRMATION -> InputType.CONFIRMATION
    com.apex.standardkit.loop.KitInputType.TEXT -> InputType.TEXT
    com.apex.standardkit.loop.KitInputType.CHOICE -> InputType.CHOICE
}
