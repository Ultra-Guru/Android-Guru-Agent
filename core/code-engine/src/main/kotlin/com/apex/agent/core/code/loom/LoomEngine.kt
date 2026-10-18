package com.apex.agent.core.code.loom

import com.apex.agent.core.code.CodeAgentEngine
import com.apex.agent.core.code.CodeContextProvider
import com.apex.agent.core.code.CodeConversationMemory
import com.apex.agent.core.code.CodeEngineFacade
import com.apex.agent.core.code.RulesProvider
import com.apex.agent.core.code.StandardTextKernel
import com.apex.agent.core.code.loom.loop.LoomAbortSignal
import com.apex.agent.core.code.loom.loop.LoomEnvironmentInfo
import com.apex.agent.core.code.loom.loop.LoomEnvironmentSource
import com.apex.agent.core.code.loom.loop.LoomFailureClassifier
import com.apex.agent.core.code.loom.loop.LoomFailureKind
import com.apex.agent.core.code.loom.loop.LoomHeuristicEstimator
import com.apex.agent.core.code.loom.loop.LoomLogger
import com.apex.agent.core.code.loom.loop.LoomMemory
import com.apex.agent.core.code.loom.loop.LoomModelClient
import com.apex.agent.core.code.loom.loop.LoomModelInfo
import com.apex.agent.core.code.loom.loop.LoomModelInfoSource
import com.apex.agent.core.code.loom.loop.LoomPlanGate
import com.apex.agent.core.code.loom.loop.LoomRoundRunner
import com.apex.agent.core.code.loom.loop.LoomSession
import com.apex.agent.core.code.loom.loop.LoomTokenEstimator
import com.apex.agent.core.code.loom.loop.LoomToolRunner
import com.apex.agent.core.code.loom.loop.LoomToolSource
import com.apex.agent.core.code.loom.loop.LoomWorkspaceContextSource
import com.apex.agent.core.code.loom.markup.LoomOutcomeFormatter
import com.apex.agent.core.code.loom.memory.LoomMemoryWeaver
import com.apex.agent.core.code.loom.packager.LoomPackageCatalog
import com.apex.agent.core.code.loom.packager.LoomPackager
import com.apex.agent.core.code.loom.permission.LoomApprovalBox
import com.apex.agent.core.code.loom.permission.LoomPermissionGovernor
import com.apex.agent.core.code.loom.permission.LoomPermissionSource
import com.apex.agent.core.code.loom.steer.LoomSteering
import com.apex.agent.core.code.loom.swarm.LoomLocalTool
import com.apex.agent.core.code.loom.swarm.LoomSwarmHub
import com.apex.agent.core.code.loom.swarm.LoomSwarmTools
import com.apex.agent.core.code.loom.thinking.LoomThinkingPolicy
import com.apex.agent.core.engine.AgentEngine
import com.apex.agent.core.engine.AgentEvent
import com.apex.agent.core.engine.AgentMode
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
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * # Loom Engine — 织造线引擎门面（Coding 第三思考逻辑线）
 *
 * 与深潜（CodeAgentEngine）/ 标准（StandardKitAdapter）并列的第三枚
 * 引擎：**端口适配 + 组装**——循环语义全部在织造线自有组件
 * （LoomRoundRunner / LoomSession / 权限治理 / 子线程群 / 织造包），
 * 本类只做三件事：
 *
 * 1. **端口适配**：宿主类型（ModelRuntime / ToolRegistry / ToolExecutor /
 *    CodeConversationMemory / AppLogger / StandardTextKernel）↔ 织造端口；
 * 2. **工具面组装**：宿主注册表 + 引擎本地工具（loom_thread_* 子线程四件 +
 *    loom_package_* 织造包三件）经 forcedTools 过滤；
 * 3. **门面契约**：[CodeEngineFacade] 17 方法逐一直通。
 *
 * ```
 * CodeViewModel ─► DualLogicCodeEngine ─► LoomEngine（本类）
 *                                            ├─ LoomSession（会话织物 + 提示词 JIT）
 *                                            ├─ LoomRoundRunner（梭回合机）
 *                                            ├─ LoomPermissionGovernor（权限三态门）
 *                                            ├─ LoomSwarmHub（子线程群）
 *                                            └─ LoomPackager（织造包）
 * ```
 *
 * ## 执行器纪律（#230 同款）
 *
 * 织造线自带权限门 → 注入的 [toolExecutor] 应为**仅环境门**执行器
 * （宿主 @Named("loomEngineTools")）——共用主执行器的组合门会双弹窗。
 */
class LoomEngine(
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

    private val config = engineConfig.sanitized()

    // ═══════════════════════════ 子系统组装 ═══════════════════════════

    private val modelClient = RuntimeModelClient()
    private val logger = AppLoggerAdapter
    private val estimator: LoomTokenEstimator = textKernel?.let { kernel ->
        LoomTokenEstimator { kernel.estimateTokens(it) }
    } ?: LoomHeuristicEstimator

    private val packager = LoomPackager(packageCatalog)

    /** 环境源（声明先于 swarmHub——构造期即引用；lambda 内的 session 引用为惰性求值）。 */
    private val environmentSource = LoomEnvironmentSource {
        LoomEnvironmentInfo(
            platformText = PLATFORM_TEXT,
            workdirLabel = session.currentWorkspaceRoot()?.path,
            isGitRepo = session.currentWorkspaceRoot()
                ?.let { File(it, ".git").exists() }
        )
    }

    private val swarmHub = LoomSwarmHub(
        config = config,
        modelClient = modelClient,
        parentToolSource = LoomToolSource { toolRegistry.getToolDefinitions() },
        toolRunner = LocalRoutingRunner(),
        permissionSource = permissionSource,
        failureClassifier = HostErrorClassifier,
        logger = logger,
        environmentSource = environmentSource,
        workspaceRootProvider = { session.currentWorkspaceRoot() }
    )

    /** 引擎本地工具（子线程四件 + 织造包三件）。 */
    private val localTools: List<LoomLocalTool> =
        LoomSwarmTools(swarmHub).all() + packager.tools()

    private val localToolById: Map<String, LoomLocalTool> =
        localTools.associateBy { it.id }

    /** 工具面（宿主注册表 + 本地工具；forcedTools 过滤）。 */
    private val compositeToolSource = LoomToolSource { visibleToolSurface() }

    private val session = LoomSession(
        config = config,
        memory = memory?.let { MemoryAdapter(it) },
        estimator = estimator,
        environmentSource = environmentSource,
        workspaceContextSource = HostPromptSections(),
        modelInfoSource = modelInfoProvider?.let { provider ->
            LoomModelInfoSource { provider() }
        },
        logger = logger
    )

    private val governor = LoomPermissionGovernor(permissionSource)
    private val approvalBox = LoomApprovalBox()
    private val steering = LoomSteering(config)
    private val planGate = LoomPlanGate()
    private val weaver = LoomMemoryWeaver(config, modelClient, estimator, logger)
    private val abortSignal = LoomAbortSignal()

    private val runner = LoomRoundRunner(
        config = config,
        session = session,
        modelClient = modelClient,
        toolSource = compositeToolSource,
        toolRunner = LocalRoutingRunner(),
        governor = governor,
        weaver = weaver,
        steering = steering,
        approvalBox = approvalBox,
        planGate = planGate,
        formatter = LoomOutcomeFormatter(config),
        thinkingPolicy = LoomThinkingPolicy(),
        failureClassifier = HostErrorClassifier,
        logger = logger,
        abortSignal = abortSignal,
        modelInfoSource = modelInfoProvider?.let { provider ->
            LoomModelInfoSource { provider() }
        }
    )

    /** forcedTools 面（v4 渐进披露）。 */
    @Volatile
    private var forcedToolIds: Set<String> = emptySet()

    @Volatile
    private var exposeAllTools: Boolean = true

    // ═══════════════════════════ AgentEngine ═══════════════════════════

    override fun execute(input: String): Flow<AgentEvent> =
        execute(UserInput.text(input))

    override fun execute(input: UserInput): Flow<AgentEvent> = runner.run(input)

    override suspend fun abort() {
        runner.requestAbort()
    }

    override fun submitUserInput(answer: String) {
        runner.submitApprovalAnswer(answer)
    }

    override fun cancelUserInput() {
        approvalBox.cancelPending()
    }

    /** 运行中转向（未运行返回 false——调用方改走常规发送）。 */
    fun pushSteer(text: String): Boolean = runner.offerSteering(text)

    // ═══════════════════════════ CodeEngineFacade ═══════════════════════════

    override fun setActiveWorkspace(
        workspaceId: String,
        name: String,
        root: File,
        activeFile: String?
    ) {
        session.bindWorkspace(workspaceId, name, root, activeFile)
    }

    override fun setActiveFile(activeFile: String?) {
        session.updateActiveFile(activeFile)
    }

    override fun prepareForTask() {
        // 全 JIT 架构：无预热可做（提示词每回合重织 / 权限快照每调用拉取）
    }

    override fun updateGlobalRules(rules: String) {
        globalRulesCache = rules
        session.updateGlobalRules(rules)
    }

    override fun updateMode(mode: AgentMode) {
        session.updateMode(mode)
    }

    override fun submitPlanConfirmation(
        confirmed: Boolean,
        enabledSteps: List<Int>?,
        order: List<Int>?
    ): Boolean = runner.submitPlanConfirmation(confirmed)

    override fun updateSessionExtras(extras: String?) {
        session.updateSessionExtras(extras)
    }

    override fun updateRolePersona(roleDefinition: String, rolePrompt: String?) {
        session.updateRolePersona(roleDefinition, rolePrompt)
    }

    override fun updateForcedTools(forcedToolIds: Set<String>, exposeAll: Boolean) {
        this.forcedToolIds = forcedToolIds
        this.exposeAllTools = exposeAll
    }

    override fun updateThinkingLevel(level: com.apex.agent.core.code.thinking.CodeThinkingLevel) {
        session.updateThinkingLevel(level)
    }

    override fun thinkingLevel(): com.apex.agent.core.code.thinking.CodeThinkingLevel =
        session.thinkingLevel()

    override fun currentThinkingDecision(): String? = null

    override fun clearConversation() {
        session.clear()
        governor.clearApprovals()
        packager.clear()
        session.updateActivePackages(emptyList())
        swarmHub.shutdownAll()
        steering.clear()
    }

    override fun historyCount(): Int = session.historyCount()

    override fun currentWorkspace(): CodeAgentEngine.WorkspaceInfo? {
        val root = session.currentWorkspaceRoot() ?: return null
        return CodeAgentEngine.WorkspaceInfo(
            session.currentWorkspaceId(),
            session.currentWorkspaceName(),
            root
        )
    }

    override fun currentTokenCount(): Int =
        session.estimateContextTokens(visibleToolSurface())

    override fun maxContextTokens(): Int = config.maxContextTokens

    // ═══════════════════════════ 工具面组装 ═══════════════════════════

    /** 可见工具面（forcedTools 非空 = 只暴露选中）。 */
    private fun visibleToolSurface(): List<ToolDefinition> {
        val registryDefs = toolRegistry.getToolDefinitions()
        val localDefs = localTools.map {
            ToolDefinition(name = it.id, description = it.description, parameters = it.parametersSchema)
        }
        val combined = registryDefs + localDefs
        return if (forcedToolIds.isEmpty() || exposeAllTools) {
            combined
        } else {
            combined.filter { forcedToolIds.contains(it.name) }
        }
    }

    // ═══════════════════════════ 端口适配 ═══════════════════════════

    /** 模型客户端端口：ModelRuntime（digest tag → SUMMARY 角色）。 */
    private inner class RuntimeModelClient : LoomModelClient {
        override fun chatStream(
            contextTag: String,
            messages: List<LlmMessage>,
            tools: List<ToolDefinition>,
            temperature: Float,
            maxTokens: Int
        ): Flow<LlmStreamChunk> = runtime.chatStream(
            context = requestContext(contextTag),
            messages = messages,
            tools = tools,
            temperature = temperature,
            maxTokens = maxTokens
        )

        override suspend fun chat(
            contextTag: String,
            messages: List<LlmMessage>,
            temperature: Float,
            maxTokens: Int
        ): LlmResponse = runtime.chat(
            context = requestContext(contextTag),
            messages = messages,
            temperature = temperature,
            maxTokens = maxTokens
        )

        private fun requestContext(contextTag: String): LlmRequestContext =
            if (contextTag == config.digestContextTag) {
                LlmRequestContext.summary(contextTag)
            } else {
                LlmRequestContext.primary(contextTag)
            }
    }

    /** 本地路由执行器：loom_* 引擎本地直执，其余透传宿主执行器。 */
    private inner class LocalRoutingRunner : LoomToolRunner {
        override fun executeStream(toolId: String, arguments: String): Flow<ToolStreamEvent> {
            val local = localToolById[toolId] ?: return toolExecutor.executeStream(toolId, arguments)
            return kotlinx.coroutines.flow.flow {
                val output = runCatching { local.execute(arguments) }
                    .getOrElse { t ->
                        logger.warn(TAG, "local tool $toolId faulted: ${t.message}")
                        "本地工具执行失败：${t.message ?: t::class.simpleName}"
                    }
                emit(ToolStreamEvent.Output(output))
                emit(ToolStreamEvent.Complete(output))
            }
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
                else LoomFailureKind.REQUEST_REJECTION
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

    /** 记忆端口。 */
    private class MemoryAdapter(private val memory: CodeConversationMemory) : LoomMemory {
        override fun load(): List<LlmMessage> = memory.load()
        override fun append(message: LlmMessage) = memory.append(message)
        override fun save(messages: List<LlmMessage>) = memory.save(messages)
        override fun clear() = memory.clear()
        override fun count(): Int = memory.count()
        override fun bindWorkspace(workspaceId: String) =
            memory.bindWorkspace(workspaceId)
    }

    /** 环境源见前部声明（构造次序约束）——本段不再重复。 */

    /** 宿主提示词段（工作区 / 全局规则 / 项目规则）。 */
    private inner class HostPromptSections : LoomWorkspaceContextSource {
        override fun workspaceSection(): String? {
            val root = session.currentWorkspaceRoot() ?: return null
            return com.apex.agent.core.code.CodePrompts.workspaceContext(
                workspaceName = session.currentWorkspaceId(),
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
            val rules = rp.loadProjectRules(session.currentWorkspaceRoot(), null) ?: return null
            return rp.formatProjectRules(rules)?.takeIf { it.isNotBlank() }
        }
    }

    /** 全局规则缓存（updateGlobalRules 写入——提示词段读）。 */
    @Volatile
    private var globalRulesCache: String = ""

    companion object {
        private const val TAG = "LoomEngine"

        /** PRoot Ubuntu 会话中工作区统一挂载点（与深潜/标准线一致）。 */
        private const val GUEST_PATH = "/workspace"

        /** env 块的平台行。 */
        private const val PLATFORM_TEXT = "Android with a PRoot Ubuntu sandbox"
    }
}
