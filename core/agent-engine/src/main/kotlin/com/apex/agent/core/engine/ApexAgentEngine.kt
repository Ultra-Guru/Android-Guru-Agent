package com.apex.agent.core.engine

import com.apex.agent.core.engine.assist.HumanAssistFlow
import com.apex.agent.core.engine.assist.ReflectionFlow
import com.apex.agent.core.engine.compression.ContextCompressor
import com.apex.agent.core.engine.compression.CompressionReport
import com.apex.agent.core.engine.compression.TokenEstimator
import com.apex.agent.core.engine.compression.ToolOutputTruncator
import com.apex.agent.core.engine.goal.GoalDecision
import com.apex.agent.core.engine.goal.GoalModeCoordinator
import com.apex.agent.core.engine.orchestrator.LoopDetector
import com.apex.agent.core.engine.orchestrator.RecoveryPlanner
import com.apex.agent.core.engine.task.DanglingToolCallRepair
import com.apex.agent.core.engine.plan.PLAN_CONFIRMATION_TIMEOUT_MS
import com.apex.agent.core.engine.plan.PlanDecision
import com.apex.agent.core.engine.plan.PlanGraph
import com.apex.agent.core.engine.plan.awaitPlanConfirmationDecision
import com.apex.agent.core.engine.terminal.TerminalProactivityAdvisor
import com.apex.agent.core.engine.thinking.ThinkingModeController
import com.apex.agent.core.llm.*
import com.apex.agent.core.llm.runtime.LlmRequestContext
import com.apex.agent.core.llm.runtime.ModelRuntime
import com.apex.agent.core.llm.runtime.ModelRuntimeException
import com.apex.agent.core.llm.runtime.SingleClientModelRuntime
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.core.logging.LogLevel
import com.apex.agent.core.tools.ToolExecutor
import com.apex.agent.core.tools.ToolRegistry
import com.apex.agent.core.tools.ToolStreamEvent
import com.apex.agent.core.tools.builtin.context.SessionContextArchive
import com.apex.agent.core.tools.catalog.ToolActivationStore
import com.apex.agent.core.tools.catalog.ToolRequestBudget
import com.apex.agent.core.tools.skill.SkillActivationStore
import com.apex.agent.core.tools.skill.SkillRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Apex Agent Engine — production implementation.
 *
 * Execution modes:
 * - [AgentMode.BUILD]: streaming ReAct loop (Think → Act → Observe → repeat → Done).
 * - [AgentMode.PLAN]: Think → Plan → stream plan to UI → await user confirmation
 *   → execute each [PlanStep] in sequence → emit a final reflection.
 * - [AgentMode.SPEC]: Think → Spec → stream [ExecutionSpec] to UI → await user
 *   confirmation → execute each deliverable in sequence → emit a final summary.
 * - [AgentMode.REFLECTION]: Build loop + "generate → review → revise" cycle on the
 *   final text turn ([AgentConfig.reflectionRounds] rounds), emitting
 *   [AgentEvent.ReflectionReview] between passes.
 * - [AgentMode.HUMAN_ASSIST]: Build loop with a system prompt that mandates
 *   AskUserChoice whenever multiple options exist (human-in-the-loop).
 * - [AgentMode.CUSTOM]: Build loop with a user-supplied custom instruction
 *   appended to the system prompt.
 *
 * All modes:
 * - Stream every LLM response token-by-token via [AgentEvent.ResponseChunk] / [AgentEvent.ThinkingChunk].
 * - Honor [AgentConfig.thinkingLevel] (#168 six levels incl. AUTO) via [ThinkingModeController] profiles.
 * - Accumulate streamed tool-call argument fragments via [StreamingToolCallAccumulator].
 */
class ApexAgentEngine(
    private val llmClient: LlmClient,
    // #168：internal —— EnginePromptDelegates.kt 同包扩展需要工具清单桥接。
    internal val toolRegistry: ToolRegistry,
    // internal —— EngineToolExecution.kt 同包扩展直调（工具执行流）。
    internal val toolExecutor: ToolExecutor,
    // internal —— EngineCompressionGate.kt 读取 maxContextTokens/preserveRecentTurns。
    // @Volatile：updateConfig/patchConfig 由 UI 线程调用，引擎循环在 IO 协程读
    // —— 引用替换的可见性与 isRunning 同一顾虑（P2-7）。AgentConfig 本身是
    // 不可变 data class，@Volatile 保证的是「换成哪份快照」跨线程一致可见。
    @Volatile
    internal var config: AgentConfig = AgentConfig.STANDARD,
    // internal —— EngineCompressionGate.kt 同包扩展直调（历史压缩触发）。
    internal val memory: ConversationMemory? = null,
    internal val contextCompressor: ContextCompressor? = null,
    // #396 上下文归档：会话边界（新会话/恢复）清空；写入方为压缩装饰器。
    internal val contextArchive: SessionContextArchive? = null,
    internal val skillRegistry: SkillRegistry? = null,

    private val privilegeInfoProvider: PrivilegeInfoProvider? = null,
    private val environmentInfoProvider: EnvironmentInfoProvider? = null,
    // internal —— EngineToolExecution.kt 同包扩展直调（隐式记忆采集）。
    internal val memoryObserver: ExecutionMemoryObserver? = null,
    /**
     * 已连接服务提供者（GitHub/连接器等）：非空时系统提示词注入
     * "## Connected Services" 段，让模型知道这些服务的工具已就绪。
     *
     * 根因修复：旧版模型不知道 GitHub 已连接，106 个工具里 7 个
     * github_* 从不被选中；连接器同理。为空则省略该段（测试兼容）。
     */
    private val connectedServicesProvider: ConnectedServicesProvider? = null,
    /**
     * T72 — 多模型运行时。非空时所有 LLM 调用按 [LlmRequestContext.role] 路由到
     * 对应 Profile / Client，并做能力校验、降级、诊断。
     *
     * 为空则回退到 [SingleClientModelRuntime]（行为与 T72 之前完全等价：单一
     * [llmClient] 处理所有请求）。这样：
     *  1. 现有基于 [FakeLlmClient] 的单元测试无需改动即可继续通过；
     *  2. 生产环境由 DI 注入 [com.apex.agent.core.llm.runtime.DefaultModelRuntime]，
     *     获得完整多模型能力。
     */
    modelRuntime: ModelRuntime? = null,
    /**
     * Tool System v4 — 会话工具激活存储（ToolOpen 激活的工具进入下一轮请求）。
     *
     * 为空时引擎自建实例（单引擎场景等价）；DI 注入与 McpToolRegistrar /
     * 编排器共享同一实例。任务开始时 [execute] 会 reset（激活不跨会话泄漏）。
     */
    private val toolActivation: ToolActivationStore = ToolActivationStore(),
    /**
     * Issue #165 — 生命周期钩子派发口（SessionStart/UserPromptSubmit/Stop/
     * PreCompact/SessionEnd）。装配层传 [HookRegistryHookRunner]；本类内部
     * 经 [SessionHookCoordinator] 携带状态与派发（文件预算红线，插桩逻辑
     * 内聚抽出）。null 时所有插桩点零开销，事件流语义与接入前完全一致。
     */
    private val hookRunner: HookRunner? = null,
    /** #168 六档思考：AUTO 选档/迭代倍率/工具自检/自评清单全在此，引擎仅三个钩子。 */
    // internal —— EngineCompressionGate.kt 压缩阈值解析直调。
    internal val thinkingController: ThinkingModeController = ThinkingModeController(), // 默认值兼容旧测试
    /**
     * 技能会话激活存储（技能渐进披露）。非空时：系统提示词携带
     * 「Skill Catalog」目录段，且 skillPrompts 只注入已激活技能的方法论
     * 全文（SkillRegistry.getActivePromptInjections）；模型经 SkillActivate
     * 工具装载。为空时回退 legacy 行为（全部启用技能全量注入），
     * 既有单测与子代理零改动兼容。
     */
    internal val skillActivation: SkillActivationStore? = null,
    /**
     * 长任务韧性守卫：LLM 瞬时错误退避重试 / 空响应重试 / 工具瞬时重试 /
     * 连续失败换路提示。默认启用（长任务需求）；测试可注入
     * EngineResilienceGuard(EngineResiliencePolicy.DISABLED) 回退旧「遇错即停」行为。
     */
    internal val resilience: EngineResilienceGuard = EngineResilienceGuard(),
    /**
     * v3 GOAL 模式协调器：每轮纯文本收尾前调 [GoalModeCoordinator.onAgentTurn]
     * 快速验收——未达标注入差距续跑，达标/轮次耗尽照常收尾。null = 无目标。
     */
    private val goalCoordinator: GoalModeCoordinator? = null,
    // #395 abort 语义收口：桥接层挂起提问的收口口（AskUserChoice/权限门 gateway.ask；abort 路径
    // 经 abortConfirmationChannels 统一收口 —— 弹窗取消卡死根因）；internal 供同包扩展直调；null 兼容旧测试。
    internal val userQuestionGateway: UserQuestionGateway? = null
) : AgentEngine, ConfirmationSink {

    /** #165 插桩句柄（null 安全派生；开号时快照当前模式名）。 */
    // internal —— EngineCompressionGate.kt PreCompact 插桩直调。
    internal val sessionHooks: SessionHookCoordinator? =
        hookRunner?.let { SessionHookCoordinator(it) { config.mode.name } }

    /**
     * 实际执行 LLM 调用的运行时。null [modelRuntime] 时回退到单 client，
     * 保留旧行为；非空时使用多模型路由。
     */
    internal val runtime: ModelRuntime = modelRuntime ?: SingleClientModelRuntime(llmClient)

    /**
     * T76 — 当前执行的诊断标签（taskId/stepId → LlmRequestContext 四元 ID）。
     *
     * 由 TaskRuntime 在执行边界设置（N-12：观测性贯通——字段在 T72 已预留，
     * 此前恒传 null）。引擎内部所有 LlmRequestContext 构造点经 [tagged]
     * 包裹后携带任务/步骤关联，诊断日志可按任务聚合。
     */
    @Volatile
    internal var executionTags: Pair<String?, String?>? = null

    /**
     * 本轮为「会话首条用户消息且纯问候」（SmallTalkDetector 判定）。
     * execute() 入口置位 / finally 复位；系统提示词据此注入
     * First-Turn Greeting 硬约束段（见 EnginePrompts）。
     */
    @Volatile
    private var firstGreetingTurn = false

    /**
     * 本轮召回的聊天长期记忆（已格式化文本；null = 无可召回内容）。
     * execute() 入口经 memoryObserver.recallChatMemory 填充，finally 复位；
     * 系统提示词据此注入 "## Remembered About You" 段。
     */
    @Volatile
    private var chatMemoryNote: String? = null

    /** 本轮聊天信号（共情/澄清，ChatSignalDetector）。execute() 入口填充，finally 复位。 */
    @Volatile
    private var chatSignal: ChatSignal? = null

    /** T76 — 压缩后重注入的任务状态 system 消息（N-9，TaskRuntime 调用）。 */
    fun injectSystemContext(content: String) {
        conversationHistory.add(LlmMessage.System(content))
    }

    /**
     * diag-20261011：工具执行期 System 提示的延迟队列（[deferSystemNote] /
     * [flushPendingSystemNotes]，见 EngineToolExecution.kt 文件头）——
     * 保证任何 System 提示永远落在完整 tool 块之后，不破坏配对。
     */
    internal val pendingSystemNotes: ArrayDeque<String> = ArrayDeque()

    /** diag-20261011：配对类 400 自愈重试已用次数（每任务至多 1 次）。 */
    private var pairingRepairAttempts = 0

    /** T76 — TaskRuntime 设置当前任务/步骤诊断标签（null = 清理）。 */
    fun setLlmExecutionTags(taskId: String?, stepId: String?) {
        executionTags = taskId?.let { it to stepId }
    }

    /** 工具输出截断器（始终生效，不依赖 contextCompressor 是否注入） */
    // internal —— EngineToolExecution.kt 同包扩展直调（输出截断）。
    internal val toolTruncator = ToolOutputTruncator(
        maxChars = config.maxToolOutputLength
    )

    /** #170 终端主动性顾问：一次性 shell 连击 / 工具链任务 / 失败连击 → 轮次级 System 建议（纯状态机，引擎仅两钩子）。 */
    // internal —— EngineToolExecution.kt 同包扩展直调（终端主动性滑窗）。
    internal val terminalAdvisor = TerminalProactivityAdvisor()

    /**
     * 循环检测（编排器路径同款下沉，3-C/C8）：引擎路径此前只有失败连击
     * 统计（[resilience].onToolCallOutcome），模型反复调用同一「成功」工具
     * 的空转检测不到。每次逻辑工具调用（内部重试收敛后）record 一次，
     * 检出重复/振荡即注入换路提示 —— 两个类都是纯内存状态机，零外部依赖。
     */
    // internal —— EngineToolExecution.kt 同包扩展直调（循环检测/恢复提示）。
    internal val loopDetector = LoopDetector()
    internal val loopRecovery = RecoveryPlanner()

    // ═══ Tool System v4 — 请求工具计划 / 名称映射 / 降级状态 ═══

    /**
     * 当前迭代的请求工具计划（provider 安全名 + 回映射）。
     * 每轮迭代 chatStream 前重建；[executeToolCallStreaming] 用它的
     * providerNameToId 把模型回显的工具名映射回注册表 id。
     */
    @Volatile
    // internal —— EngineToolExecution.kt / EngineToolPlanner 同包扩展直调。
    internal var currentToolPlan: ToolRequestBudget.RequestToolPlan? = null

    /**
     * 工具请求降级等级（0=正常 / 1=纯 CORE 无强制 / 2=无工具）。
     * Provider 以 4xx 拒绝带 tools 的请求时逐级降级重试同一轮，
     * 保证“直接发送对话”永远有响应而非直接报错。
     */
    private var toolDegradationLevel = 0

    // internal —— EngineCompressionGate.kt 传入压缩器/持久化。
    internal val conversationHistory: MutableList<LlmMessage> = mutableListOf<LlmMessage>().apply {
        memory?.load()?.let { addAll(it) }
    }
    // P2-10：isRunning 跨线程读写需 volatile（abort 在引擎循环外调用）。
    // TODO(结构化改造): conversationHistory 的跨协程写入（contextInjector）应
    // 收敛为显式队列（diag-20261011 已消主要并发源：compressNow 运行期拒绝）。
    @Volatile
    internal var isRunning = false

    /**
     * 任务内是否有任何工具动作失败（跨 [executeToolCallStreaming] 调用累计；
     * execute 入口重置）。@Volatile：跨挂起点与诊断线程读写可见性。
     */
    @Volatile
    internal var anyActionFailed = false

    /**
     * Channel for the UI to deliver plan-confirmation decisions back to the engine
     * while [executePlanMode] is suspended on `awaitPlanConfirmationDecision`.
     *
     * #169：Boolean → [PlanDecision]（可携带步骤勾选/重排）。internal 供
     * plan/PlanExecutionSupport.kt 扩展注册与清理（同模块拆分模式）。
     * Reset to a fresh [CompletableDeferred] every time a new plan is awaiting confirmation.
     * @Volatile（#214 同型）：引擎协程写、UI/测试线程读，跨挂起点可见性依赖此标记。
     */
    @Volatile
    internal var planConfirmationDeferred: CompletableDeferred<PlanDecision>? = null

    /** UI 回投 SPEC 确认（[executeSpecMode] 挂在 [awaitSpecConfirmation] 时）。@Volatile 同 plan 通道。 */
    @Volatile
    internal var specConfirmationDeferred: CompletableDeferred<Boolean>? = null

    /**
     * Channel for the UI to deliver user-input answers back to the engine
     * while [executeBuildLoop] is suspended on awaitUserInput
     * （ConfirmationChannels.kt 扩展）。
     *
     * #214：@Volatile —— [submitUserInputIfAwaiting] 在 VM 主线程读、
     * 引擎协程写，投递回执（超时后迟到提交必须拿到 false）依赖可见性。
     * #340：internal——同包 ConfirmationChannels.kt 扩展的注册/清理同锁。
     */
    @Volatile
    internal var userInputDeferred: CompletableDeferred<String>? = null

    /**
     * #340：确认通道三件套（userInput/plan/spec）的注册/中止互斥锁。
     *
     * 旧 abort() 的“complete-then-null”两步与注册方（await* 的 deferred 赋值）
     * 之间无 happens-before——abort 恰在两步之间执行时，引擎新注册的
     * deferred 会被 null 覆盖，UI 后续提交全部落空，引擎挂满 5 分钟超时
     * （用户看到的就是「chat 卡死」）。注册与 abort 全部收进同一把锁，
     * 窗口结构性消除；锁内无挂起点（complete/赋值均同步），不阻塞调度。
     * internal：plan/EngineSpecFlow 扩展的注册/清理与 abort 同锁。
     */
    internal val confirmationLock = Any()

    fun updateConfig(newConfig: AgentConfig) {
        val old = config
        config = newConfig
        syncCompressorWindow(old, newConfig)
    }

    /**
     * Issue #164 —— 全局规则（设置页编辑；Agent 模式经 EnginePrompts 的
     * "## Global Rules" 段注入。coding 实例不设值——它走 RulesProvider 通道，
     * 两通道互斥防双注）。
     */
    @Volatile
    private var globalRulesText: String = ""

    /** 更新全局规则（VM 监听设置流调用；下轮 buildSystemPrompt 生效）。 */
    fun updateGlobalRules(rules: String) { globalRulesText = rules }

    /** 当前生效的 [AgentConfig]（供 UI 层做 read-modify-write）。 */
    fun currentConfig(): AgentConfig = config

    /** #168 — 最近一次 AUTO 档选档决策（"LEVEL: 理由"；VM 于 IterationStart 后拉取展示）。 */
    fun currentThinkingDecision(): String? = thinkingController.lastDecision?.let { "${it.level.name}: ${it.reason}" }

    /**
     * 读-改-写式更新配置：保留未触及字段，避免 [updateConfig] 全量替换时
     * 把 maxIterations / maxContextTokens / temperature 等字段重置回默认值。
     */
    fun patchConfig(transform: (AgentConfig) -> AgentConfig) {
        val old = config
        config = transform(config)
        syncCompressorWindow(old, config)
    }

    /**
     * Issue #222 — 上下文窗口变更同步到压缩器：HybridCompressor 分层收敛的
     * 停止阈值必须与引擎压缩门（EngineCompressionGate 读 config.maxContextTokens）
     * 同源，否则模型切换后小窗口压缩不到底、大窗口过早丢上下文。
     */
    private fun syncCompressorWindow(old: AgentConfig, new: AgentConfig) {
        if (old.maxContextTokens != new.maxContextTokens) {
            contextCompressor?.updateContextWindow(new.maxContextTokens)
        }
    }

    /** 清空历史与持久化记忆（开新会话）。#165：SessionEnd 经派发；归档一并清空。 */
    fun clearHistory() {
        sessionHooks?.onSessionEnd()
        conversationHistory.clear()
        memory?.clear()
        contextArchive?.clear()
    }

    /** 恢复会话：内存历史与持久化记忆同步替换；归档随会话边界重置。 */
    fun restoreHistory(messages: List<LlmMessage>) {
        conversationHistory.clear()
        conversationHistory.addAll(messages)
        memory?.save(messages)
        contextArchive?.clear()
    }

    /** 当前持久化的消息条数（UI 显示历史深度）。 */
    fun historyCount(): Int = memory?.count() ?: conversationHistory.size

    // ═══ 真实用量统计（用户反馈「已用 token 像假的、用完还是 0」）═══
    //
    // 根因修复的完整背景与状态逻辑内聚于 [EngineUsageTracker]
    // （EngineUsageTracking.kt，God-file 预算拆分）：请求体带
    // stream_options.include_usage（客户端层），流尾统计帧解析进
    // LlmStreamChunk.usage，每轮结束发射 [AgentEvent.UsageUpdated]，
    // 仪表盘显示服务端返回的真实 token 数。
    private val usageTracker = EngineUsageTracker { conversationHistory }

    /** 当前上下文 token 数（UI 仪表盘）：优先真实 usage，回退启发式估算。 */
    fun currentTokenCount(): Int = usageTracker.currentContextTokens()

    /** 会话累计消耗的真实 token（多轮累加；0 = 尚无统计）。 */
    fun sessionTotalTokens(): Long = usageTracker.sessionTotalTokens()

    /** 上下文 token 上限（占用百分比的分母）。 */
    fun maxContextTokens(): Int = config.maxContextTokens

    /** 累加一轮真实 usage 并发射仪表盘事件（无统计时零开销；spec 流复用）。 */
    internal suspend fun recordAndEmitUsage(u: Usage?, emit: suspend (AgentEvent) -> Unit) {
        usageTracker.accumulate(u)?.let { emit(it) }
    }

    /**
     * 主动压缩上下文（UI 仪表盘按钮触发）：与自动压缩共用 [ContextCompressor]，
     * 不依赖 execute 流的 emit，直接返回 [CompressionReport] 供 ViewModel 呈现。
     * 手动压缩不受 [AgentConfig.compressionThreshold] 限制。compressor 未注入 → null。
     */
    suspend fun compressNow(): CompressionReport? {
        val compressor = contextCompressor ?: return null
        // diag-20261011：任务运行中禁止手动压缩 —— 压缩器原地改写共享
        // conversationHistory，与引擎循环的 append 并发交错会撕裂
        // tool_calls 配对（同样的 400 根因，chaos-audit D-05）。改为拒绝。
        if (isRunning) {
            AppLogger.instance.warn(
                LogCategory.ENGINE, "ApexAgentEngine",
                "compressNow skipped: task running (concurrent history rewrite breaks tool pairing)"
            )
            return null
        }
        // #165：PreCompact——手动压缩（压缩器动历史前）。
        sessionHooks?.onPreCompact()
        val report = runCatching {
            compressor.compress(
                history = conversationHistory,
                preserveRecent = config.preserveRecentTurns
            )
        }.getOrNull() ?: return null

        // 同步到持久化记忆（如果存在）
        memory?.save(conversationHistory)
        return report
    }

    /** 把消息加入内存历史，同时持久化到记忆（如果存在）。 */
    internal fun addMessage(message: LlmMessage) {
        conversationHistory.add(message)
        memory?.append(message)
    }

    /**
     * Called from the UI (e.g. `ChatViewModel.confirmPlan`) to resume the suspended
     * plan-mode execution. No-op if no plan is currently awaiting confirmation.
     * Exposed to the orchestrator via the [ConfirmationSink] interface.
     *
     * #340：Boolean 回执 —— true = 已送达挂起等待；false = 无挂起等待或已被
     * abort/超时收场，调用方（VM）据此给出「未送达」提示（#214 同型）。
     * complete 与读取同收 [confirmationLock]，与注册/abort 互斥（窗口②同源）。
     */
    override fun submitPlanConfirmation(confirmed: Boolean): Boolean =
        synchronized(confirmationLock) {
            planConfirmationDeferred?.complete(PlanDecision.legacy(confirmed)) ?: false
        }

    /** #169：带步骤勾选/重排的确认重载（UI 人控透传，语义见 [PlanDecision]）。 */
    fun submitPlanConfirmation(confirmed: Boolean, enabledSteps: List<Int>?, order: List<Int>?): Boolean =
        synchronized(confirmationLock) {
            planConfirmationDeferred?.complete(PlanDecision(confirmed, enabledSteps, order)) ?: false
        }

    /**
     * Called from the UI (e.g. `ChatViewModel.submitSpecConfirmation`) to resume the
     * suspended spec-mode execution. No-op if no spec is currently awaiting confirmation.
     * Exposed to the orchestrator via the [ConfirmationSink] interface.
     * #340：同 plan 通道的 Boolean 回执（语义见 [submitPlanConfirmation]）。
     */
    override fun submitSpecConfirmation(confirmed: Boolean): Boolean =
        synchronized(confirmationLock) {
            specConfirmationDeferred?.complete(confirmed) ?: false
        }

    /**
     * 兼容旧接口：纯文本输入委托给多模态入口。
     */
    override fun execute(input: String): Flow<AgentEvent> = execute(UserInput.text(input))

    /** 多模态入口：images 注入 User 消息（Vision 真看图）；内存历史保留
     * base64、持久化只存文本副本；files 拼路径上下文（工具可读取）。 */
    override fun execute(input: UserInput): Flow<AgentEvent> = flow {
        isRunning = true
        anyActionFailed = false
        // v4：新任务开始 —— 会话激活的工具不跨任务泄漏；降级状态复位。
        toolActivation.reset()
        toolDegradationLevel = 0
        pairingRepairAttempts = 0
        pendingSystemNotes.clear()
        // 长任务韧性：新任务重置重试/换路/熔断预算（冷却期不跨任务）。
        resilience.resetForTask()
        runCatching { toolExecutor.resetBreakers() }
        // 循环检测：新任务清空检测窗口与恢复预算。
        loopDetector.reset()
        loopRecovery.reset()
        // #165：SessionStart 开号 + UserPromptSubmit。
        sessionHooks?.onSessionBeginIfNeeded()
        sessionHooks?.onUserPrompt(input.text)
        val startTime = System.currentTimeMillis()
        var totalToolCalls = 0
        var totalIterations = 0
        var taskHadFailure = false
        // 本轮历史起点（记忆沉淀用）：finally 里从该下标之后找最终助手回复；
        // 异常路径下未进入 try 体则为 -1（跳过沉淀）。
        var turnHistoryStart = -1

        try {
            // 隐式记忆采集：观察者异常包防线，防 isRunning 卡死。
            try {
                memoryObserver?.onTaskStart(input.text, null)
            } catch (e: Throwable) {
                AppLogger.instance.warn(
                    LogCategory.ENGINE, "ApexAgentEngine",
                    "memoryObserver.onTaskStart threw: ${e.message}"
                )
            }

            // ═══ 首轮问候检测 + 聊天记忆召回（发送前、历史追加前）═══
            // 首轮 = 历史尚无任何 User 消息；纯问候 → 系统提示词注入硬约束段。
            firstGreetingTurn =
                conversationHistory.none { it is LlmMessage.User } &&
                    SmallTalkDetector.isGreetingOnly(input.text)
            chatSignal = ChatSignalDetector.detect(input.text)
            // 聊天长期记忆召回：失败折叠为 null（记忆子系统异常绝不阻断主对话）。
            chatMemoryNote = try {
                memoryObserver?.recallChatMemory(input.text)?.takeIf { it.isNotBlank() }
            } catch (e: Throwable) {
                AppLogger.instance.warn(
                    LogCategory.ENGINE, "ApexAgentEngine",
                    "recallChatMemory threw (ignored): ${e.message}"
                )
                null
            }
            // 本轮起点（记忆沉淀用）：finally 里从该下标之后找最终助手回复。
            turnHistoryStart = conversationHistory.size

            val userText = buildUserText(input)
            val userMessage = LlmMessage.User(content = userText, images = input.images)

            // 内存历史保留完整图片；持久化只存文本副本（防存储爆炸）。
            conversationHistory.add(userMessage)
            memory?.append(
                userMessage.copy(
                    images = emptyList(),
                    content = if (input.images.isEmpty()) userMessage.content
                    else userMessage.content + "\n[图片已附加，持久化记忆中不保存 base64]"
                )
            )

            when (config.mode) {
                AgentMode.BUILD -> {
                    val iter = executeBuildLoop { event ->
                        if (event is AgentEvent.ToolCallComplete) totalToolCalls++
                        if (event is AgentEvent.IterationStart) totalIterations =
                            maxOf(totalIterations, event.iteration)
                        AppLogger.instance.logEvent(event)
                        emit(event)
                    }
                    totalIterations = maxOf(totalIterations, iter)
                }
                // #197：CHAT（零工具）/ AGENT（非编程全能）/ LOOP 共享 ReAct 主循环，
                // 差异在工具计划与提示词模式段；调度在 VM 层，引擎只跑单次。
                AgentMode.CHAT, AgentMode.AGENT, AgentMode.LOOP -> {
                    val iter = executeBuildLoop { event ->
                        if (event is AgentEvent.ToolCallComplete) totalToolCalls++
                        if (event is AgentEvent.IterationStart) totalIterations =
                            maxOf(totalIterations, event.iteration)
                        AppLogger.instance.logEvent(event)
                        emit(event)
                    }
                    totalIterations = maxOf(totalIterations, iter)
                }
                AgentMode.PLAN -> {
                    val planIterations = executePlanMode(userText) { event ->
                        if (event is AgentEvent.ToolCallComplete) totalToolCalls++
                        if (event is AgentEvent.IterationStart) totalIterations =
                            maxOf(totalIterations, event.iteration)
                        AppLogger.instance.logEvent(event)
                        emit(event)
                    }
                    totalIterations = maxOf(totalIterations, planIterations)
                }
                AgentMode.SPEC -> {
                    val specIterations = executeSpecMode(userText) { event ->
                        if (event is AgentEvent.ToolCallComplete) totalToolCalls++
                        if (event is AgentEvent.IterationStart) totalIterations =
                            maxOf(totalIterations, event.iteration)
                        AppLogger.instance.logEvent(event)
                        emit(event)
                    }
                    totalIterations = maxOf(totalIterations, specIterations)
                }
                // REFLECTION / HUMAN_ASSIST / CUSTOM / GOAL 共享 ReAct 主循环：差异由
                // buildSystemPrompt 的 Mode 段驱动；GOAL 走 executeBuildLoop 钩子。
                AgentMode.REFLECTION, AgentMode.HUMAN_ASSIST, AgentMode.CUSTOM, AgentMode.GOAL -> {
                    val iter = executeBuildLoop { event ->
                        if (event is AgentEvent.ToolCallComplete) totalToolCalls++
                        if (event is AgentEvent.IterationStart) totalIterations =
                            maxOf(totalIterations, event.iteration)
                        AppLogger.instance.logEvent(event)
                        emit(event)
                    }
                    totalIterations = maxOf(totalIterations, iter)
                }
            }

            // #165：Stop——本回合正常完成（错误/中止不触发）。
            sessionHooks?.onTurnCompleted()
        } catch (e: TimeoutCancellationException) {
            // P2-4/A-3：TCE 先于父类 catch；协程不活跃（外层超时穿透）必须重抛，
            // 仅自身活跃才折叠为 Error（避免吞外层取消）。
            if (!currentCoroutineContext().isActive) {
                AppLogger.instance.warn(LogCategory.ENGINE, "ApexAgentEngine", "外层超时取消穿透引擎，重抛 TCE")
                throw e
            }
            AppLogger.instance.error(LogCategory.ENGINE, "ApexAgentEngine", "计划/规格确认超时: ${PLAN_CONFIRMATION_TIMEOUT_MS / 1000}s")
            // #216：引擎自身产生的用户可见错误文案统一中文（技术细节留在 AppLogger）。
            emit(AgentEvent.Error("计划/规格确认超时（${PLAN_CONFIRMATION_TIMEOUT_MS / 1000}s 无响应），任务已中止", recoverable = false))
        } catch (e: CancellationException) {
            AppLogger.instance.warn(LogCategory.ENGINE, "ApexAgentEngine", "任务被中止 (CancellationException)")
            emit(AgentEvent.Aborted)
        } catch (e: ModelRuntimeException) {
            // T72 §十四：可降级类（限流/超时/不可用/鉴权）标记 recoverable，
            // 配置/能力类不可恢复；#213 细节只进日志，用户文案中文映射。
            val fatal = !e.isFallbackEligible && e !is ModelRuntimeException.ModelFallbackExhausted
            AppLogger.instance.error(
                LogCategory.LLM, "ApexAgentEngine",
                "模型运行时错误 [${e::class.simpleName}]: ${e.message}"
            )
            taskHadFailure = true
            emit(AgentEvent.Error(LlmErrorText.userMessage(e), recoverable = !fatal))
        } catch (e: Exception) {
            // #213：原始 message 落日志，用户气泡中文指引；recoverable=true
            //（未识别异常往往是一次性抖动，可手动重试；自动重试交给韧性守卫）。
            AppLogger.instance.error(LogCategory.ENGINE, "ApexAgentEngine", "运行异常: ${e.message}", e)
            taskHadFailure = true
            emit(AgentEvent.Error(LlmErrorText.userMessage(e), recoverable = true))
        } finally {
            isRunning = false
            // 隐式记忆采集：finally 保证无论成败都关闭会话；观察者异常兜底不阻断 Complete。
            try {
                memoryObserver?.onTaskFinish(success = !taskHadFailure && !anyActionFailed)
            } catch (e: Throwable) {
                AppLogger.instance.warn(
                    LogCategory.ENGINE, "ApexAgentEngine",
                    "memoryObserver.onTaskFinish threw: ${e.message}"
                )
            }
            // 聊天记忆自动沉淀：取本轮起点后最后一条有正文的 Assistant（异常兜底）。
            try {
                val finalAssistantText = if (turnHistoryStart >= 0) {
                    conversationHistory.drop(turnHistoryStart)
                        .filterIsInstance<LlmMessage.Assistant>()
                        .lastOrNull { it.content.isNotBlank() }?.content
                } else null
                if (finalAssistantText != null) {
                    memoryObserver?.onConversationTurn(input.text, finalAssistantText)
                }
            } catch (e: Throwable) {
                AppLogger.instance.warn(
                    LogCategory.ENGINE, "ApexAgentEngine",
                    "memoryObserver.onConversationTurn threw: ${e.message}"
                )
            }
            // 复位轮次级注入态（防止下一轮携带上一轮的问候约束/记忆快照）。
            firstGreetingTurn = false
            chatMemoryNote = null
            chatSignal = null
            // Cancel any dangling plan-confirmation deferred so it doesn't leak.
            planConfirmationDeferred?.complete(PlanDecision.legacy(false))
            planConfirmationDeferred = null
            specConfirmationDeferred?.complete(false)
            specConfirmationDeferred = null
            emit(
                AgentEvent.Complete(
                    summary = "",
                    totalIterations = totalIterations,
                    totalToolCalls = totalToolCalls,
                    totalDurationMs = System.currentTimeMillis() - startTime
                )
            )
        }
    }

    // ═══════════════════════════════════════════════════════
    // PLAN mode
    // ═══════════════════════════════════════════════════════

    private suspend fun executePlanMode(
        input: String,
        emit: suspend (AgentEvent) -> Unit
    ): Int {
        // Phase 1: think + generate plan (streamed as ThinkingChunk).
        // 规划期只读显式化（#169）：planningPhase=true 注入「仅产出计划 JSON、
        // 不执行任何工具/写操作」约束段；本阶段本就不携带 tools。
        emit(AgentEvent.ThinkingStart(0, config.thinkingLevel))

        val planResponseBuilder = StringBuilder()

        // B1：不再显式传 temperature —— 哨兵（-1）回退到 Profile 值，
        // 设置页/小大脑菜单改参数对下一次请求真实生效。
        runtime.chatStream(
            context = tagged(LlmRequestContext.reasoning("plan_generation")),
            messages = listOf(LlmMessage.System(buildSystemPrompt(planningPhase = true))) +
                LlmMessage.User(EnginePrompts.buildPlanPrompt(input, toolRegistry.getAllTools()))
        ).collect { chunk ->
            chunk.content?.let {
                planResponseBuilder.append(it)
                emit(AgentEvent.ThinkingChunk(it))
            }
            // 真实用量：规划期请求同样计入会话统计与仪表盘
            recordAndEmitUsage(chunk.usage, emit)
        }

        val planResponse = planResponseBuilder.toString()
        emit(AgentEvent.ThinkingComplete(planResponse))

        // Phase 2: parse plan
        val plan = EngineResponseParsers.parseExecutionPlan(planResponse, input)
        emit(AgentEvent.PlanGenerated(plan))

        // Phase 3: await user confirmation（#169：Boolean → PlanDecision，
        // 可携带步骤勾选 enabledSteps 与重排 order，见 plan/PlanConfirmationRequest）。
        // #340：注册先行——emit 必须发生在 deferred 注册之后，否则 submit 在
        // 注册前到达（tap 快速消费使 emit 立即返回）→ complete 落空 → 引擎挂满
        // 5 分钟。改走 beforeWait 回调（注册 → emit → 等待）。
        val decision = awaitPlanConfirmationDecision(
            beforeWait = { emit(AgentEvent.PlanAwaitingConfirmation(plan)) }
        )
        if (!decision.confirmed) {
            emit(AgentEvent.Aborted)
            return 0
        }

        // Phase 3.5 (#169)：应用用户勾选/重排 + dependsOn 拓扑排序 → 锁定计划。
        // locked 为局部 val，执行期间不可变（“计划锁定”语义）；锁定播报经
        // addMessage 写入历史（自动持久化 ConversationMemory），后续每轮 LLM
        // 请求都能看到这份不可变契约。
        val locked = PlanGraph.lock(plan, decision.enabledSteps, decision.order)
        addMessage(LlmMessage.System(locked.lockMessage))
        emit(AgentEvent.PlanConfirmed(locked.plan))

        // Phase 4: execute each step in locked order.
        // Each step is a single iteration of the Build loop with a step-scoped user message.
        var iterations = 0
        for ((index, step) in locked.plan.steps.withIndex()) {
            if (!isRunning) break
            emit(AgentEvent.StepStart(index, step.description))

            addMessage(LlmMessage.User(EnginePrompts.buildStepExecutionPrompt(locked.plan, step, index)))

            val stepIters = executeBuildLoop { event -> emit(event) }
            iterations += stepIters
        }

        // Phase 5: reflection（只读总结，同样生效 planningPhase 约束）
        val reflectionBuilder = StringBuilder()
        runtime.chatStream(
            context = tagged(LlmRequestContext.primary("plan_reflection")),
            messages = listOf(LlmMessage.System(buildSystemPrompt(planningPhase = true))) +
                LlmMessage.User(EnginePrompts.buildReflectionPrompt(locked.plan))
        ).collect { chunk ->
            chunk.content?.let {
                reflectionBuilder.append(it)
                emit(AgentEvent.ResponseChunk(it))
            }
            recordAndEmitUsage(chunk.usage, emit)
        }
        emit(AgentEvent.ResponseComplete(reflectionBuilder.toString()))

        return iterations
    }

    // awaitPlanConfirmation 已迁至 plan/PlanExecutionSupport.kt（#169：Boolean → PlanDecision）。
    // ═══════════════════════════════════════════════════════
    // SPEC mode —— executeSpecMode / awaitSpecConfirmation 已迁至
    // EngineSpecFlow.kt（同包扩展 + internal 成员直调，调用点零改动）。
    // ═══════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════
    // BUILD mode (ReAct loop)
    // ═══════════════════════════════════════════════════════

    internal suspend fun executeBuildLoop(emit: suspend (AgentEvent) -> Unit): Int {
        var iteration = 0
        // P0 修复（承诺未执行催促）：每任务至多催促一次（防无限循环）
        var promiseNudged = false

        while (isRunning && iteration < thinkingController.effectiveMaxIterations(config.maxIterations)) {
            iteration++
            // #168：解析本轮生效思考档位（AUTO → 复杂度选档；工具计数由控制器自持）。
            thinkingController.onIterationStart(config, iteration, userText = lastUserPromptText(conversationHistory))
            // diag-20261011：冲刷上一批延迟 System 提示（位置安全点：完整 tool 块之后）。
            flushPendingSystemNotes()
            // #170：终端主动性 —— 轮次级建议注入（会话流切换/Ubuntu 预备/失败恢复）。
            terminalAdvisor.onIterationStart(lastUserPromptText(conversationHistory) ?: "")
                ?.let { addMessage(LlmMessage.System(it.systemNote)) }
            // 每轮迭代重新触发 ThinkingStart（第 2..N 轮 UI 指示器刷新）。
            var thinkingEmittedForIteration = false
            emit(AgentEvent.IterationStart(iteration))

            // P7: 每轮迭代前检查是否需要压缩
            maybeCompressContext(emit)

            if (thinkingController.effectiveLevel(config) != ThinkingLevel.NONE && !thinkingEmittedForIteration) {
                emit(AgentEvent.ThinkingStart(iteration, thinkingController.effectiveLevel(config)))
                thinkingEmittedForIteration = true
            }

            // 隐式记忆旁路：LLM 推理前尝试"肌肉记忆"FSM 宏执行，命中则跳过
            // 本轮 LLM；不匹配/失败照常走 LLM。
            when (val bypass = memoryObserver?.tryBypass()) {
                is BypassOutcome.Executed -> {
                    emit(
                        AgentEvent.ResponseChunk(
                            "⚡ 肌肉记忆旁路执行完成（${bypass.actionCount} 步，已跳过 LLM 推理）。"
                        )
                    )
                    continue
                }
                is BypassOutcome.Failed -> {
                    // 旁路执行偏离/异常，回退到 LLM 接管（日志已由 BypassEngine 记录）。
                    AppLogger.instance.warn(
                        LogCategory.ENGINE, "ApexAgentEngine",
                        "Bypass failed, falling back to LLM: ${bypass.reason}"
                    )
                }
                else -> { /* NotAttempted / NotMatched → 照常走 LLM */ }
            }

            // ═══ Tool System v4：先建工具计划，再建消息 ═══
            // 计划决定请求 tools 数组与 system prompt 工具清单（同一份
            // plan.visibleRegistryIds）——两侧永远一致；已连接服务的工具 id
            // 并入计划（P0：否则宣称可用而 tools 数组里没有，provider 拒绝）。
            val plan = EngineToolPlanner.buildToolPlan(
                config, toolRegistry, toolActivation, toolDegradationLevel,
                serviceToolIds = connectedServicesProvider?.connectedToolIds() ?: emptySet()
            )
            currentToolPlan = plan

            val messages = buildMessages()

            val contentBuilder = StringBuilder()
            val reasoningBuilder = StringBuilder()
            // 累加器键策略：并行工具调用首片段携带 id+index，后续只带 index ——
            // 以 index 优先的复合键防同调用撕裂（详见循环内注释）。
            val toolCallsAccumulator = mutableMapOf<String, StreamingToolCallAccumulator>()

            // T72：含图片时路由到 VISION 角色（不满足降级 PRIMARY；全链无视觉
            // 抛 ModelCapabilityMismatch 而非静默丢图）。
            val reactContext = if (messagesContainImages(messages)) {
                tagged(LlmRequestContext.vision("react_loop"))
            } else {
                tagged(LlmRequestContext.primary("react_loop"))
            }

            // v4：强制函数调用 → tool_choice=required / 具体函数；降级后不再强制。
            val forcedChoice = EngineToolPlanner.forcedToolChoiceSpec(
                config.forcedToolIds, plan, toolDegradationLevel
            )

            try {
                runtime.chatStream(
                    context = reactContext,
                    messages = messages,
                    tools = plan.tools,
                    temperature = -1f,
                    maxTokens = -1,
                    toolChoice = forcedChoice
                ).collect { chunk ->
                chunk.content?.let {
                    contentBuilder.append(it)
                    emit(AgentEvent.ResponseChunk(it))
                }
                // 真实用量统计帧（流尾帧）：记录 + 发射 UsageUpdated。
                recordAndEmitUsage(chunk.usage, emit)
                // 多模态输出：模型生成的图片/视频转 markdown 注入回复流
                //（复用 ResponseChunk 管线直达 UI）。
                MediaMarkdown.from(chunk.images, chunk.videos)?.let { mediaMd ->
                    contentBuilder.append(mediaMd)
                    emit(AgentEvent.ResponseChunk(mediaMd))
                }
                // 原生思考内容（DeepSeek-R1 / Qwen3-thinking / OpenAI o-series 等）：
                // 透传为 ThinkingChunk，让 UI 显示思维链。
                // 空串帧过滤（2026-10 用户反馈）：网关对不思考模型也发 reasoning_content:""，
                // 空串进管道会建出永不收尾的空思考卡 —— 不进。
                chunk.reasoningContent?.takeIf { it.isNotEmpty() }?.let {
                    reasoningBuilder.append(it)
                    emit(AgentEvent.ThinkingChunk(it))
                }
                for (tc in chunk.toolCalls) {
                    // 并行工具调用分片键：index 优先（全分片稳定；id 只在首片），
                    // 防同调用撕裂；index<0 回退 id。
                    val key = if (tc.index >= 0) "_idx_${tc.index}" else tc.id
                    if (key.isBlank()) continue  // 既无 id 又无 index 的畸形片段，跳过
                    val acc = toolCallsAccumulator.getOrPut(key) {
                        StreamingToolCallAccumulator(tc.id.ifBlank { key }, tc.name)
                    }
                    appendFragmentAndEmitDelta(acc, tc, emit)
                }
            }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // ═══ diag-20261011：配对类 400 自愈（每任务至多一次）═══
                // 历史里存在修复器来不及归位的配对破坏（遗留会话/极端 provider
                // 分片）时，就地归一化后重试本轮，而不是直接报废任务。
                if (pairingRepairAttempts < 1 && EngineToolPlanner.isDanglingPairRejection(e)) {
                    pairingRepairAttempts++
                    if (repairDanglingToolCalls()) {
                        AppLogger.instance.warn(
                            LogCategory.ENGINE, "ApexAgentEngine",
                            "pairing-400 self-healed by history normalization; retrying iteration $iteration"
                        )
                        iteration--
                        continue
                    }
                }
                // ═══ Tool System v4：工具请求降级重试 ═══
                // Provider 拒带 tools 请求时逐级降级（1=纯 CORE；2=无工具）重试
                // 同一轮（仅本轮零输出时防重复拼接）；#242 同时发可见提示。
                if (contentBuilder.isEmpty() && reasoningBuilder.isEmpty() &&
                    toolCallsAccumulator.isEmpty() && toolDegradationLevel < 2 &&
                    plan.tools.isNotEmpty() && EngineToolPlanner.isToolsRelatedRejection(e)
                ) {
                    toolDegradationLevel++
                    AppLogger.instance.warn(
                        LogCategory.ENGINE, "ApexAgentEngine",
                        "Tools rejected by provider (level ${toolDegradationLevel}): " +
                            "${e.message ?: e::class.simpleName} — degrading tool payload and retrying"
                    )
                    EngineDegradationNotice.noticeFor(toolDegradationLevel)?.let { emit(AgentEvent.ThinkingChunk(it)) } // #242 降级可见提示
                    continue
                }
                // ═══ 长任务韧性：LLM 瞬时错误退避重试（不消耗迭代配额；P2 前置
                // 「本轮零输出」防重放拼接半截回答）。═══
                val hasPartialOutput = contentBuilder.isNotEmpty() ||
                    reasoningBuilder.isNotEmpty() || toolCallsAccumulator.isNotEmpty()
                when (val retryDecision = resilience.onLlmFailure(e)) {
                    is EngineResilienceGuard.LlmRetryDecision.Retry -> {
                        if (hasPartialOutput) throw e
                        AppLogger.instance.warn(
                            LogCategory.LLM, "ApexAgentEngine",
                            "LLM 瞬时失败（${e::class.simpleName}），退避 ${retryDecision.delayMs}ms " +
                                "后自动重试（${retryDecision.attempt}/${resilience.policy.maxLlmRetries}）: ${e.message}"
                        )
                        emit(
                            AgentEvent.ThinkingChunk(
                                "[engine] 模型暂时不可用（${e::class.simpleName ?: "error"}）— " +
                                    "${retryDecision.delayMs / 1000.0}s 后自动重试 " +
                                    "（${retryDecision.attempt}/${resilience.policy.maxLlmRetries}）\n"
                            )
                        )
                        delay(retryDecision.delayMs)
                        iteration--
                        continue
                    }
                    is EngineResilienceGuard.LlmRetryDecision.Stop -> throw e
                }
            }

            // ═══ P1 修复（降级自动恢复）：本轮成功 → 降级等级归零（孤立失败
            // 无跨轮记忆，旧状态机一次误判就永久降级）。═══
            if (toolDegradationLevel > 0) {
                AppLogger.instance.debug(
                    LogCategory.ENGINE, "ApexAgentEngine",
                    "Iteration succeeded — resetting tool degradation level to 0"
                )
                toolDegradationLevel = 0
            }

            // 若本轮收到了原生思考内容，发射 ThinkingComplete 让 UI 收尾。
            if (reasoningBuilder.isNotEmpty()) {
                emit(AgentEvent.ThinkingComplete(reasoningBuilder.toString()))
            }

            val toolCalls = toolCallsAccumulator.values.map { it.build() }

            when {
                toolCalls.isNotEmpty() ->
                    // 工具循环 v2：单轮调度核心（AskUser 保序 / 并行批 / 单轮上限）
                    // 迁至 EngineToolExecution.kt（God-file 预算拆分同款模式）。
                    executeToolCallTurn(contentBuilder.toString(), toolCalls, emit)

                contentBuilder.isNotEmpty() -> {
                    // ═══ #168 HUMAN_ASSIST 模式：决策点后置检测 ═══
                    // 模型常不调 AskUserChoice 直接写对比文本 —— 检出 → 挂起等选择
                    // → 回填 continue；无/超时 → null 照常收尾（不丢回复）。
                    // 规则见 assist/DecisionPointDetector.kt；流程见 assist/HumanAssistFlow.kt。
                    if (config.mode == AgentMode.HUMAN_ASSIST) {
                        // #214：超时发 UserInputExpired 关闭挂起对话框；空答复折叠为空串照常收尾。
                        val followUp = HumanAssistFlow(emit) {
                            awaitUserInput { emit(AgentEvent.UserInputExpired) } ?: ""
                        }.interceptResponse(contentBuilder.toString())
                        if (followUp != null) {
                            addMessage(LlmMessage.Assistant(contentBuilder.toString()))
                            addMessage(LlmMessage.User(followUp))
                            continue
                        }
                    }

                    // ═══ Reflection 模式：生成 → 评审 → 修正 ═══
                    // 循环体迁至 assist/ReflectionFlow.kt（依赖全注入，纯 Kotlin
                    // 可单测）；此处只保留接线与收尾。
                    if (config.mode == AgentMode.REFLECTION && config.reflectionRounds > 0) {
                        val final = ReflectionFlow(
                            runtime = runtime,
                            systemPrompt = { buildSystemPrompt() },
                            tagged = { tagged(it) },
                            reviewPromptOf = { buildReviewPrompt(it) },
                            revisePromptOf = { d, r, n -> buildRevisePrompt(d, r, n) },
                            addAssistantMessage = { addMessage(LlmMessage.Assistant(it)) }
                        ).run(contentBuilder.toString(), config.reflectionRounds, emit)

                        emit(AgentEvent.ResponseComplete(final))
                        return iteration
                    }

                    // ═══ P0 修复（承诺未执行催促，BUILD 模式）═══
                    // 纯文本轮是 BUILD 循环的终止条件 —— 弱模型常只叙述计划不调
                    // tool_calls。后置检测（assist/PromiseDetector，保守）：检出强
                    // 承诺且未催促过 → System 催促并 continue（每任务至多一次，
                    // 催促后仍不行动 = 合法收尾）；否则照常 ResponseComplete。
                    if (config.mode == AgentMode.BUILD && !promiseNudged &&
                        iteration < thinkingController.effectiveMaxIterations(config.maxIterations) - 1 &&
                        com.apex.agent.core.engine.assist.PromiseDetector
                            .isPromiseWithoutAction(contentBuilder.toString())
                    ) {
                        promiseNudged = true
                        addMessage(LlmMessage.Assistant(contentBuilder.toString()))
                        addMessage(
                            LlmMessage.System(com.apex.agent.core.engine.assist.PromiseDetector.NUDGE_MESSAGE)
                        )
                        continue
                    }

                    // ═══ v3 GOAL 模式：每轮自然收尾 → 快速模型验收；未达标注入差距
                    // 说明继续跑，达标/轮次耗尽/无目标 → 照常收尾。═══
                    if (config.mode == AgentMode.GOAL && goalCoordinator != null) {
                        val decision = goalCoordinator.onAgentTurn(contentBuilder.toString())
                        when (decision) {
                            is GoalDecision.Continue -> {
                                addMessage(LlmMessage.Assistant(contentBuilder.toString()))
                                addMessage(LlmMessage.System(decision.continuationNote))
                                emit(AgentEvent.ThinkingChunk(decision.userNotice))
                                continue
                            }
                            is GoalDecision.Stop ->
                                emit(AgentEvent.ThinkingChunk(decision.userNotice))
                            null -> { /* 无活动目标：照常收尾 */ }
                        }
                    }

                    addMessage(LlmMessage.Assistant(contentBuilder.toString()))
                    emit(AgentEvent.ResponseComplete(contentBuilder.toString()))
                    return iteration
                }

                else -> {
                    // ═══ P0 修复（reasoning-only 兜底）+ 空响应退避重试 ═══
                    // 正文恒空但思考通道有完整回答（弱蒸馏 R1 / 网关把正文挂
                    // 末帧 message）→ 以 reasoning 兜底为回答收尾，不再盲目
                    // 重试。逻辑迁至 EngineReasoningFallback（God-file 预算
                    // 拆分，同包扩展直调 —— 与 EngineAskUserFlow 同一模式）。
                    when (finishEmptyTurn(reasoningBuilder.toString(), emit)) {
                        EmptyTurnOutcome.FINISHED -> return iteration
                        EmptyTurnOutcome.RETRY -> { iteration--; continue }
                        EmptyTurnOutcome.FAILED ->
                            // #213：空响应用户文案统一中文映射。
                            emit(AgentEvent.Error(LlmErrorText.RESPONSE_INVALID))
                    }
                    return iteration
                }
            }
        }

        // #216：最大迭代超限不是死局 —— 上下文仍在，可点击重试继续推进
        //（recoverable=true 让 UI 渲染重试入口）。
        if (iteration >= thinkingController.effectiveMaxIterations(config.maxIterations)) {
            val maxIterations = thinkingController.effectiveMaxIterations(config.maxIterations)
            emit(
                AgentEvent.Error(
                    "已达到最大迭代轮次（$maxIterations），任务可能未完成——可点击重试继续推进",
                    recoverable = true
                )
            )
        }
        return iteration
    }

    // ═══════════════════════════════════════════════════════
    // Prompt builders
    // ═══════════════════════════════════════════════════════

    private fun buildMessages(): List<LlmMessage> {
        repairDanglingToolCalls()
        val messages = mutableListOf<LlmMessage>()
        messages.add(LlmMessage.System(buildSystemPrompt()))
        messages.addAll(conversationHistory)
        return messages
    }

    /**
     * P0 修复（会话级报废根因）：发送前修补悬空 tool_call 历史。
     *
     * 引擎在 `addMessage(Assistant(toolCalls))` 后、ToolResult 补齐前被中断
     * （cancel/进程被杀/AskUser 等待中退出）→ 历史末尾留下无配对 tool_calls
     * → OpenAI 兼容端点对后续每次请求都 400，会话级报废。现每次构建请求前
     * 幂等修补（[DanglingToolCallRepair]）：无悬空零开销，有则改写内存历史
     * 并持久化。diag-20261011：修复器升级为全面归一化（分离应答回迁/孤儿
     * 丢弃/重复 id 剥离），返回是否有修补供自愈重试判定。
     *
     * @return true = 历史被归一化改写（调用方可安全重试请求）
     */
    private fun repairDanglingToolCalls(): Boolean {
        val report = DanglingToolCallRepair.repair(conversationHistory)
        if (!report.hasRepairs) return false
        conversationHistory.clear()
        conversationHistory.addAll(report.repairedHistory)
        memory?.save(conversationHistory)
        AppLogger.instance.warn(
            LogCategory.ENGINE, "AgentEngine",
            "History normalized: ${report.repairedCallIds.size} synthesized, " +
                "${report.movedCallIds.size} moved back, " +
                "${report.droppedToolResultIds.size} orphan toolResults dropped, " +
                "${report.strippedCallIds.size} duplicate declarations stripped",
            tags = arrayOf("dangling-repair")
        )
        return true
    }

    internal fun buildSystemPrompt(planningPhase: Boolean = false): String = EnginePrompts.buildSystemPrompt(
        config = config,
        currentProfile = thinkingController.profileFor(config),
        planningPhase = planningPhase,
        privilegeLevel = privilegeInfoProvider?.currentLevel() ?: "NORMAL_SHELL",
        visibleTools = EngineToolPlanner.visibleToolsFor(currentToolPlan, toolRegistry),
        catalogTools = EngineToolPlanner.catalogToolsFor(currentToolPlan, toolRegistry),
        toolNameMap = EngineToolPlanner.idToProviderName(currentToolPlan),
        toolsUnavailable = currentToolPlan?.tools?.isEmpty() == true &&
            toolDegradationLevel >= EngineToolPlanner.DEGRADATION_NO_TOOLS,
        skillPrompts = skillPromptInjections, // 解析在 EngineSkillPromptResolver.kt
        skillCatalog = skillCatalogDigests,
        environmentSummary = environmentInfoProvider?.environmentSummary(),
        connectedServices = connectedServicesProvider?.connectedServicesSummary(),
        // Issue #164：全局规则（Agent 模式通道；coding 实例不设值，见 updateGlobalRules KDoc）
        globalRules = globalRulesText,
        // 首轮纯问候硬约束 + 聊天记忆召回（execute() 置位，finally 复位）。
        firstTurnGreeting = firstGreetingTurn,
        memoryContext = chatMemoryNote,
        chatSignal = chatSignal
    )

    // SPEC / Reflection 模式 prompt 包装器已迁至 EnginePromptDelegates.kt（#168 零净增腾挪，调用点零改动）。
    // ═══════════════════════════════════════════════════════
    // Plan / Spec parsing — delegated to [EngineResponseParsers]
    // ═══════════════════════════════════════════════════════

    override suspend fun abort() {
        isRunning = false
        // #340：三对 complete-then-null 原子收进 confirmationLock（含 #395 桥接层收口，详见 ConfirmationChannels.kt）。
        abortConfirmationChannels()
    }

    override fun submitUserInput(answer: String) {
        userInputDeferred?.complete(answer)
    }

    /**
     * #214 带投递回执的用户输入提交：存在挂起等待且投递成功 → true；
     * 等待已超时收场（或无 pending 请求）→ false —— 调用方（VM）据此
     * 给出「回答未送达」的显式提示，迟到的输入不再被静默丢弃。
     */
    fun submitUserInputIfAwaiting(answer: String): Boolean {
        val deferred = userInputDeferred ?: return false
        return deferred.complete(answer)
    }

    override fun cancelUserInput() {
        userInputDeferred?.complete("")
    }

    // maybeCompressContext 迁至 EngineCompressionGate.kt（同包顶层扩展，
    // 调用点零改动 —— 模式与 EnginePromptDelegates / EngineAskUserFlow 一致）。
    // awaitUserInput / abortConfirmationChannels 迁至 ConfirmationChannels.kt
    //（同包顶层扩展，#340 确认通道治理 + God-file 预算）；
    // awaitPlanConfirmationDecision / awaitSpecConfirmation 分别留在
    // plan/PlanExecutionSupport.kt 与 EngineSpecFlow.kt（同步治理）。
}
