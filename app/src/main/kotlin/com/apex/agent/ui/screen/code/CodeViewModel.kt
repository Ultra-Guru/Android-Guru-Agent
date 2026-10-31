package com.apex.agent.ui.screen.code

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apex.agent.R
import com.apex.agent.core.code.CodeAgentEngine
import com.apex.agent.core.code.CodeEngineFacade
import com.apex.agent.core.code.longtask.LongTaskStatus
import com.apex.agent.core.code.longtask.LongTaskStore
import com.apex.agent.core.code.experience.RoleExperienceStore
import com.apex.agent.core.code.experience.RoleExperienceTracker
import com.apex.agent.core.code.longtask.LongTaskTracker
import com.apex.agent.core.code.longtask.TaskCopyEngine
import com.apex.agent.core.code.standard.DualLogicCodeEngine
import com.apex.agent.core.code.CodeContextSnapshot
import com.apex.agent.core.code.standard.StandardLogicMode
import com.apex.agent.core.code.stream.CodeStreamCheckpoint
import com.apex.agent.core.code.stream.CodeStreamSession
import com.apex.agent.core.code.stream.StreamToolCall
import com.apex.agent.core.code.thinking.CodeAdaptiveThinkingSelector
import com.apex.agent.core.code.thinking.CodeThinkingLevel
import com.apex.agent.core.codetools.CodeWorkspaceRoots
import com.apex.agent.core.codetools.tools.CodeTodoTool
import com.apex.agent.core.engine.AgentAnswer
import com.apex.agent.core.engine.AgentEngine
import com.apex.agent.core.engine.AgentEvent
import com.apex.agent.core.engine.AgentMode
import com.apex.agent.core.engine.AgentQuestion
import com.apex.agent.core.engine.UserInput
import com.apex.agent.core.engine.UserQuestionBridge
import com.apex.agent.core.engine.goal.GoalModeCoordinator
import com.apex.agent.core.llm.ReasoningEffort
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.github.GithubTokenManager
import com.apex.agent.platform.code.ws.CodeWorkspace
import com.apex.agent.platform.code.ws.CodeWorkspaceManager
import com.apex.agent.slash.SlashCommand
import com.apex.agent.slash.SlashCommandParser
import com.apex.agent.slash.SlashCommandRouter
import com.apex.agent.slash.SlashRouteContext
import com.apex.agent.ui.chatkit.CODING_SCREEN_MODES
import com.apex.agent.ui.chatkit.PendingPipelineCommand
import com.apex.agent.ui.chatkit.llmConfiguredFlow
import com.apex.agent.ui.screen.code.editor.AtRefParser
import com.apex.agent.ui.screen.code.editor.EditorFileException
import com.apex.agent.ui.screen.code.editor.EditorFileLoader
import com.apex.agent.ui.screen.code.editor.EditorLoadError
import com.apex.agent.ui.screen.code.session.CodeSessionStore
import com.apex.agent.ui.screen.code.history.CodeHistoryStore
import com.apex.agent.ui.screen.code.history.clearConversationWithArchive
import com.apex.agent.ui.chatkit.ChatToolkitStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import javax.inject.Named
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * # Code ViewModel — Coding 模式屏的状态与事件归约
 *
 * 与 AgentChatViewModel 的关系：**平行实现而非复用**（两模式交互面差异大
 * —— Code 屏以工作区为中心、工具卡以 diff/验证为核心），但引擎侧契约一致：
 * AgentEvent 流 → UI 状态归约 → ConfirmationSink/submitUserInput 回传。
 *
 * 精简的事件归约（16ms 级流式节流不做 —— 编码回复长度可控，直接增量
 * append），实现已按 God-file 预算拆至同包扩展 [CodeEventReducer.kt]
 * （模式同 AgentChatEventApplier）：ResponseChunk → 助手消息追加；
 * ToolCallStart/Complete → 工具卡（CodeEdit/write 渲染 diff 摘要）；
 * UserInputRequired → 挂起等输入（AskUser）；Complete/Aborted/Error →
 * 收尾 + todo 快照刷新。
 *
 * ## v1.0 会话恢复（#152）
 *
 * 引擎侧上下文由 CodeConversationMemory 按 workspaceId 持久化（API 级
 * 完整历史，setActiveWorkspace 时自动恢复）；本层补齐 **UI 快照**——
 * 消息列表 / todos / 当前文件存 `code_sessions/ws_<id>.json`：bindWorkspace
 * 「冲刷旧快照 → 恢复新快照」串行（Mutex 防快速切换交错）；消息变更后
 * 800ms 防抖落盘（对齐 Agent 屏惯例）；onCleared 经独立 IO scope 最后
 * 冲刷（viewModelScope 届时已取消，防抖尾巴不能丢）。
 *
 * ## v1.0 编辑器面板与 @ 引用（#154）
 *
 * sendMessage 前经 AtRefParser 提取 `@file:line` 选区引用：引用块拼进
 * 引擎输入（用户消息 UI 展示保持原文），首个引用设为当前文件并打开面板；
 * CodeEdit / CodeWrite 成功后自动跟随被改文件；面板数据直读工作区文件
 * （EditorFileLoader，绕开工具输出 8000 字符截断），行点击回填 `@file:line`。
 *
 * ## 文件拆分（God-file 预算纪律）
 *
 * VM 行数守 1200 门禁（quality-gate check_file_size）：**只减不增**。
 * 已拆出的本包内 internal 扩展文件（模式同 Agent 屏 AgentChat* 拆分，
 * 调用点无感知，依赖成员开放 internal）：`CodeLongTaskCenterOps.kt`（v1.2
 * 长任务中心用户操作流；run 生命周期钩子仍在 runEngine 内）；
 * `CodeGoalController.kt`（v3 GOAL 目标模式控制全链；模式附属动作经
 * setMode 尾部 onEnterGoalMode 单点接入）；`CodeSessionSnapshotOps.kt`
 * （#152 会话快照 restore/build/persist 三动作面）。
 */
@HiltViewModel
class CodeViewModel @Inject constructor(
    @Named("code") private val codeEngine: AgentEngine,
    internal val workspaceManager: CodeWorkspaceManager,
    internal val codeTodoTool: CodeTodoTool,
    internal val codeSessionStore: CodeSessionStore,
    // v1.4.10 Coding 历史对话：归档仓库（新会话先归档，历史面板可恢复/删除）。
    internal val codeHistoryStore: CodeHistoryStore,
    private val workspaceRoots: CodeWorkspaceRoots,
    private val userQuestionBridge: UserQuestionBridge,
    // v1.8.0 知识库：roleController 按当前专家角色注入专属知识库检索指引
    internal val knowledgeBaseService: com.apex.agent.knowledge.KnowledgeBaseService,
    // Issue #164：全局规则（设置页编辑）——发送前同步到引擎，refreshContext 时
    // 经 RulesProvider 注入（goal 扩展 CodeGoalController.kt 同包消费）
    internal val settingsRepository: com.apex.agent.ui.screen.settings.SettingsRepository,
    // v1.2 长任务中心：追踪器/复制引擎/存储。longTaskTracker 供
    // CodeEventReducer.kt；taskCopyEngine/longTaskStore 供 CodeLongTaskCenterOps.kt。
    internal val longTaskTracker: LongTaskTracker,
    // v1.9 角色经验卡：事件流聚合器（沉淀/注入/强化三通道，控制器代理
    // 见 RoleExperienceController —— God-file 预算：VM 只做暴露）。
    internal val roleExperienceTracker: RoleExperienceTracker,
    internal val roleExperienceStore: RoleExperienceStore,
    internal val taskCopyEngine: TaskCopyEngine,
    internal val longTaskStore: LongTaskStore,
    // ═══ 会话用量与通知（上下文圆环详情数据面 + 任务完成通知）═══
    // usageLedger：逐轮落账（用量仪表盘数据源，圆环详情的会话累计）；
    // apexNotifications/foregroundTracker/appContext：任务完成通知（设置开关
    // 真接线 —— 后台时跑完发系统通知，前台静音）。
    internal val usageLedger: com.apex.agent.usage.UsageLedger,
    internal val apexNotifications: com.apex.agent.notify.ApexNotifications,
    internal val foregroundTracker: com.apex.agent.notify.ForegroundTracker,
    @dagger.hilt.android.qualifiers.ApplicationContext internal val appContext: android.content.Context,
    // AUTO 档自治选档器（发送前预检 + 运行中深水区升级观察）
    private val adaptiveSelector: CodeAdaptiveThinkingSelector,
    // #197 函数调用二级菜单候选工具（注册表快照；小圆环迁移至 Coding 屏）
    private val toolRegistry: com.apex.agent.core.tools.ToolRegistry,
    // ═══ #197 Coding 工位升级（从 Agent 屏迁入）═══
    /** 「小圆环」函数调用/工具菜单（Coding 工位独占消费）。 */
    internal val toolkitStore: ChatToolkitStore,
    /** 「功能聚合」16 项能力注入（见 neon/NeonOrbFeatures.kt）。 */
    internal val neonOrbStore: com.apex.agent.ui.screen.code.neon.NeonOrbStore,
    /** GitHub PAT 连接（Coding 屏的 gh 连接入口 + v1.9 MCP 配置面对话框）。 */
    internal val githubTokenManager: GithubTokenManager,
    /** 斜杠路由需要 MCP 连接快照（/mcp:<id> 引导提示词据此生成）；
     * v1.9 起同时喂给输入框 GitHub 图标的 MCP 配置对话框（internal）。 */
    internal val mcpManager: com.apex.agent.core.tools.mcp.McpManager,
    // Plugin System v2：斜杠路由需要功能性插件指令贡献快照
    // （/plugin:<id> 命中时注入插件声明的定制 prompt）。
    private val unifiedPluginRegistry: com.apex.agent.plugin.host.functional.UnifiedPluginRegistry,
    // i18n：用户可见系统消息按当前语言取词（组合外场景）
    //（GOAL 扩展同包消费：目标设定/停止/重启回执）
    internal val languageManager: com.apex.agent.ui.language.LanguageManager,
    // v3 GOAL 目标模式：全局目标协调器（与深潜线引擎共享单例；每轮验收钩子
    // 在引擎侧，UI 侧驱动设定弹层/状态卡——扩展接线见 CodeGoalController.kt）
    internal val goalCoordinator: GoalModeCoordinator,
    // ═══ v6 Git 工作区（右上角面板直读）：PRoot 沙箱 git 执行器 ═══
    private val gitRunner: com.apex.agent.core.codetools.git.GitCommandRunner
) : ViewModel() {

    // ═══ v1.6 工作区浏览器（全屏页，apex-kit-workspace）═══
    // 适配层薄包装（git 通道/工作区根/日志/引擎事件映射）住 WorkspaceKitHolder.kt；
    // 控制器随 VM 生命周期，导航往返保持状态。
    internal val workspaceKit by lazy {
        WorkspaceKitHolder(gitRunner, workspaceRoots, viewModelScope)
    }

    internal val _uiState = MutableStateFlow(CodeUiState())
    val uiState: StateFlow<CodeUiState> = _uiState.asStateFlow()

    /** 工具/权限门的主动提问（AgentQuestion 结构化选项；与 AskUser 的纯文本通道并存）。 */
    val pendingAgentQuestion: StateFlow<AgentQuestion?> = userQuestionBridge.pendingQuestion

    internal val idGen = AtomicLong(0)
    private var runJob: Job? = null
    private var editorJob: Job? = null
    // 防抖落盘 job（扩展读写：CodeSessionSnapshotOps.kt 的 scheduleSessionPersist）
    internal var sessionPersistJob: Job? = null
    private var sessionLoadJob: Job? = null

    // ═══ 胶囊时间轴（渲染通道数据源）═══
    // 渲染半边：事件归约进 session，25ms ticker 拉快照进 uiState.stream；副作用
    // 半边（长任务追踪/深水区观察器/落盘）仍走既有 reduce——双通道彻底分流。

    /** 胶囊时间轴会话（VM 自持，跨 run 累积；恢复/清空见 bind/clear）。 */
    internal val streamSession = CodeStreamSession()

    /** 渲染 ticker：运行期每 25ms 拉一次快照（≤40Hz 攒批）。 */
    private var renderJob: Job? = null

    /** 当前绑定的会话归属工作区（null = 尚未绑定，不落盘）。 */
    internal var boundWorkspaceId: String? = null

    // ═══ AUTO 档自治状态（coding 专属，引擎零参与）═══

    /** 本轮 run 累计工具调用数（深水区升级观察器信号）。 */
    private var runToolCalls = 0

    /** 本轮 run 最近 3 次工具成败滑窗（true = 成功）。 */
    private val recentToolOutcomes = ArrayDeque<Boolean>()

    /** 本轮 run 生效的深度档（AUTO 预检解析结果；非 AUTO = 用户显式档）。 */
    private var effectiveRunLevel: CodeThinkingLevel = CodeThinkingLevel.STANDARD

    /** 上一轮 run 的工具调用总数（下轮 AUTO 预检的错误史信号）。 */
    private var lastRunToolCalls = 0

    /** 上一轮 run 的错误数（引擎 3 滑窗口径近似：run 内滑窗错误峰值）。 */
    private var lastRunErrors = 0

    // ═══ #209 错误条「重试」通道 ═══

    /** 最近一次引擎运行的完整输入（含 @引用块/斜杠提示词；重试时原样重放）。 */
    private var lastRunEngineInput: String? = null

    /** 最近一次引擎运行的展示目标（长任务追踪文案；与 runEngine 口径一致）。 */
    private var lastRunDisplayGoal: String? = null

    /** 工作区恢复/冲刷串行锁（快速连续切换时防交错；历史恢复复用同一把锁）。 */
    internal val sessionMutex = Mutex()

    /**
     * 会话落盘的独立 scope：viewModelScope 在 onCleared 之前就被取消，
     * 最后一次冲刷必须有地方落地。Job 短命（单次文件写），无泄漏之虞。
     */
    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    internal val codeEngineImpl: CodeEngineFacade?
        get() = codeEngine as? CodeEngineFacade

    /** 双思考逻辑路由门面（右上角切换入口；注入恒为 DualLogicCodeEngine）。 */
    internal val dualLogicEngine: DualLogicCodeEngine?
        get() = codeEngine as? DualLogicCodeEngine

    // ═══ v6 控制器接线（lazy：viewModelScope 构造后才可用；God-file 预算：
    //     专家模板逻辑独立成文件，VM 只做暴露）═══

    /** Coding 专家模板（模式行胶囊；设置流 → 引擎人设通道）。 */
    internal val roleController: CodeRoleController by lazy {
        CodeRoleController(settingsRepository, { codeEngineImpl }, knowledgeBaseService, viewModelScope)
    }

    /** 角色经验卡（沉淀/注入/强化三通道；经验域逻辑全在控制器内）。 */
    internal val roleExperience: RoleExperienceController by lazy {
        RoleExperienceController(
            tracker = roleExperienceTracker,
            store = roleExperienceStore,
            settingsRepository = settingsRepository,
            scope = viewModelScope
        )
    }

    init {
        // 工作区清单 + 激活恢复（manager init 已恢复 activeId）
        refreshWorkspaces()
        val active = workspaceManager.activeWorkspace()
        if (active != null) {
            bindWorkspace(active)
        }
        // v1.2 七档思考系统：恢复持久化档位（本字段独立记忆，
        // 不随任何复制/模板选项覆盖 —— 档位覆盖位已随任务模板功能移除）
        restoreThinkingLevel()
        // v1.5 双思考逻辑：恢复持久化档位（空/未知 → 深潜）。先于 setMode：
        // GOAL 恢复强制深潜线，在后恢复会反切持久化线、破坏「GOAL 恒走深潜」。
        restoreLogicMode()
        // v1.5 输出展示档恢复（流式/整段；见 CodeOutputModeOps.kt）
        restoreOutputMode()
        // v1.6 输出风格恢复（经典时间轴/Rikka；见 CodeOutputStyleOps.kt）
        // + 设置流订阅：idle 期切风格回屏即生效（消灭「切了没反应」）
        restoreOutputStyle()
        observeOutputStyle()
        // #197：Coding 屏模式同步（引擎侧恒为 BUILD/PLAN；未知值回退 BUILD）
        val startupMode = settingsRepository.agentSettings.value.let { s ->
            s.codeExecutionMode.takeIf { it.isNotBlank() }
                ?.let { runCatching { AgentMode.valueOf(it.uppercase()) }.getOrNull() }
        } ?: AgentMode.BUILD
        setMode(if (startupMode in CODING_SCREEN_MODES) startupMode else AgentMode.BUILD)
        // v3 GOAL：协调器全局状态流 → uiState.goalState（状态卡数据源）
        setupGoalController()
    }

    // ═══ v1.5 思考逻辑四线（深潜 = 自研七档 / 标准 = 任务循环 / 织造 = 经纬交织 / 信使 = 驿传调度）═══

    /**
     * 切换思考逻辑（Coding 屏右上角选择器入口）：持久化 + 四引擎门面
     * 即时路由 + 系统消息（各线现场独立保留，切回即恢复）+ 运行中拒绝
     * （防丢现场，由 UI 弹提示）。
     *
     * @return true = 切换成功；false = 正在运行（UI 提示稍后再切）
     */
    fun setLogicMode(mode: StandardLogicMode): Boolean {
        if (_uiState.value.isRunning) return false
        if (_uiState.value.logicMode == mode) return true
        val switched = dualLogicEngine?.switchLogic(mode) ?: true
        if (!switched) return false
        settingsRepository.updateAgentSettings {
            copy(codeThinkingLogic = mode.persistenceName)
        }
        _uiState.update { it.copy(logicMode = mode) }
        appendSystemMessage(
            languageManager.getString(
                when (mode) {
                    StandardLogicMode.STANDARD -> R.string.code_logic_switched_standard
                    StandardLogicMode.LOOM -> R.string.code_logic_switched_loom
                    StandardLogicMode.COURIER -> R.string.code_logic_switched_courier
                    StandardLogicMode.DEEP_DIVE -> R.string.code_logic_switched_deep_dive
                }
            )
        )
        // 引擎侧同步当前模式/档位（新激活线接收与 UI 一致的 Build/Plan 与档位）
        codeEngineImpl?.updateMode(_uiState.value.mode)
        codeEngineImpl?.updateThinkingLevel(_uiState.value.thinkingLevel)
        return true
    }

    /** 启动恢复：codeThinkingLogic 字符串 → 逻辑（空/未知 → 深潜兜底）。 */
    private fun restoreLogicMode() {
        val mode = StandardLogicMode.fromName(
            settingsRepository.agentSettings.value.codeThinkingLogic
        ) ?: StandardLogicMode.DEEP_DIVE
        dualLogicEngine?.switchLogic(mode)
        _uiState.update { it.copy(logicMode = mode) }
        // 新激活线同步当前 Build/Plan 与档位（与 setLogicMode 运行时切换同口径）
        codeEngineImpl?.updateMode(_uiState.value.mode)
        codeEngineImpl?.updateThinkingLevel(_uiState.value.thinkingLevel)
    }

    /** 上下文查看（路由到当前激活逻辑线；深潜线全量，kit 线统计降级）。 */
    fun contextSnapshot(): CodeContextSnapshot? = codeEngineImpl?.contextSnapshot()

    // ═══ #197 执行模式（Build/Plan）═══

    /**
     * 切换执行模式：持久化（AgentSettings.codeExecutionMode）+ 引擎
     * patchConfig 即时生效。PLAN = 先出完整计划、确认后执行；BUILD = 边想边做；
     * GOAL（v3）= 目标驱动验收循环——切入时强制深潜线（验收只在深潜线引擎
     * 接线）+ 无活动目标弹设定 Sheet（逻辑单点在 [onEnterGoalMode]，选择器
     * onSelect 与 init 恢复共用本路径）。
     */
    fun setMode(mode: AgentMode) {
        if (mode !in CODING_SCREEN_MODES) return
        settingsRepository.updateAgentSettings { copy(codeExecutionMode = mode.name.lowercase()) }
        _uiState.update { it.copy(mode = mode) }
        codeEngineImpl?.updateMode(mode)
        if (mode == AgentMode.GOAL) onEnterGoalMode()
    }

    /** #197 PLAN 模式计划确认/驳回（人控门；勾选/重排同 Agent 屏口径）。 */
    fun confirmPlan(confirmed: Boolean, enabledSteps: List<Int>? = null, order: List<Int>? = null) {
        _uiState.update { it.copy(awaitingPlanConfirmation = false) }
        // #340：回执 false/null = 无挂起等待（迟到/被 abort）——显式提示「未送达」。
        if (codeEngineImpl?.submitPlanConfirmation(confirmed, enabledSteps, order) != true) {
            appendSystemMessage(languageManager.getString(R.string.chat_plan_confirm_late))
        }
    }

    // ═══ #197 斜杠指令管线（Coding 工位：coding 域技能/MCP）；v5 多选 chip ═══

    /**
     * 当前挂在输入框内的技能 chip 列表（顺序 = 追加顺序；type:id 去重）。
     * 发送时：单枚拼回 `/type:id` 走既有斜杠路由；多枚逐个路由后合并为
     * 一轮引擎执行（见 [sendMessage] / [handleMultiChipPipeline]）。
     */
    private val _pendingCommands = MutableStateFlow<List<PendingPipelineCommand>>(emptyList())
    val pendingCommands: StateFlow<List<PendingPipelineCommand>> = _pendingCommands.asStateFlow()

    /** 排队任务的 chip 恢复通道（CodeTaskQueueController 扩展访问）。 */
    internal fun restorePendingCommands(commands: List<PendingPipelineCommand>) {
        _pendingCommands.value = commands.distinctBy { it.type + ":" + it.id }
    }

    /** 追加一枚技能 chip（斜杠菜单/实时联想选中项；重复选择不重复挂载）。 */
    fun addPendingCommand(command: PendingPipelineCommand) {
        val key = command.type + ":" + command.id
        _pendingCommands.value =
            if (_pendingCommands.value.any { (it.type + ":" + it.id) == key }) _pendingCommands.value
            else _pendingCommands.value + command
    }

    /** 整体同步 chip 集合（输入框内退格/点击删除 → 回报新集合；幂等）。 */
    fun setPendingCommands(commands: List<PendingPipelineCommand>) {
        _pendingCommands.value = commands.distinctBy { it.type + ":" + it.id }
    }

    /** 清空全部 chip。 */
    fun clearPendingCommands() {
        _pendingCommands.value = emptyList()
    }

    /** /mcp:github 未连接信号（UI 收集后打开 GithubTokenDialog）。 */
    private val _requestGithubConnect = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val requestGithubConnect: SharedFlow<Unit> = _requestGithubConnect.asSharedFlow()

    /**
     * 斜杠命令分派：路由出系统消息 + 引擎提示词后执行；/mcp:github 未连接
     * 时只发信号不执行（与 Agent 屏旧实现同语义，但可见域是 coding 域）。
     */
    private fun handleSlashCommand(command: String) {
        val parsed = SlashCommandParser.parse(command) ?: run {
            // 解析失败：当普通消息走引擎（不吞用户输入）
            runEngine(command)
            return
        }
        // ═══ v1.5 /logic:<mode> — 本地路由命令：切换双思考逻辑 ═══
        // 纯 ViewModel 状态操作（持久化 + 门面路由 + 会话现场保留），必须在此
        // 截获、不进通用路由。运行中拒绝切换与选择器入口同口径（防丢现场）。
        if (parsed is SlashCommand.Logic) {
            handleLogicCommand(parsed)
            return
        }
        val context = SlashRouteContext(
            githubConnected = githubTokenManager.isConnected(),
            githubUsername = githubTokenManager.getUsername(),
            mcpConnected = mcpManager.getConnectedServers().toSet(),
            functionalCommands = unifiedPluginRegistry.functionalSlashCommands()
                .associateBy { it.commandId }
        )
        val route = SlashCommandRouter.route(parsed, context)
        _uiState.update { state ->
            state.copy(
                messages = state.messages + CodeChatMessage(
                    id = idGen.incrementAndGet(),
                    CodeChatMessage.Role.SYSTEM,
                    route.systemMessage
                )
            )
        }
        if (route.requestGithubConnect) {
            _requestGithubConnect.tryEmit(Unit)
            return
        }
        if (route.agentPrompt.isNotBlank()) {
            runEngine(route.agentPrompt)
        }
    }

    /**
     * v5 多 chip 流水线：输入框内 ≥2 枚技能 chip 时的发送执行体。
     *
     * 与 Agent 屏 [com.apex.agent.ui.screen.agent.AgentChatViewModel] 的同名
     * 方法同构：逐 chip 解析路由（系统消息逐条入轴、MCP/连接态门控拦截不
     * 阻断其余 chip），有效 agentPrompt 合并 + 用户附加文本 → **单轮**
     * runEngine。displayGoal 用 chip 标签组合（无附加文本时用户气泡可读）。
     */
    private fun handleMultiChipPipeline(chips: List<PendingPipelineCommand>, userText: String) {
        val context = SlashRouteContext(
            githubConnected = githubTokenManager.isConnected(),
            githubUsername = githubTokenManager.getUsername(),
            mcpConnected = mcpManager.getConnectedServers().toSet(),
            functionalCommands = unifiedPluginRegistry.functionalSlashCommands()
                .associateBy { it.commandId }
        )
        val prompts = mutableListOf<String>()
        chips.forEach { chip ->
            val parsed = SlashCommandParser.parse(chip.toCommandToken()) ?: return@forEach
            val route = SlashCommandRouter.route(parsed, context)
            _uiState.update { state ->
                state.copy(
                    messages = state.messages + CodeChatMessage(
                        id = idGen.incrementAndGet(),
                        CodeChatMessage.Role.SYSTEM,
                        route.systemMessage
                    )
                )
            }
            if (route.requestGithubConnect) {
                _requestGithubConnect.tryEmit(Unit)
                return@forEach
            }
            if (route.agentPrompt.isNotBlank()) prompts += route.agentPrompt
        }
        if (prompts.isEmpty()) return

        val combined = buildString {
            prompts.forEachIndexed { index, prompt ->
                if (index > 0) append("\n\n")
                append(prompt)
            }
            if (userText.isNotBlank()) {
                append("\n\n用户附加要求: ").append(userText)
            }
        }
        val displayGoal = userText.ifBlank { chips.joinToString(" + ") { it.label } }
        runEngine(combined, displayGoal = displayGoal)
    }

    /**
     * `/logic:<mode>` 执行体（v1.5 双引擎切换的命令通道，与右上角选择器
     * 共用 [setLogicMode] 同一入口：持久化 + 门面路由 + 会话现场保留 +
     * 系统消息回执）。
     *
     * - id 解析走 [StandardLogicMode.fromName] 容错别名（standard/std →
     *   标准；deep_dive/deep/apex → 深潜）；
     * - 未知 id → 引导消息（不进引擎、不改状态）；
     * - 运行中拒绝切换 → 与选择器同口径提示（[R.string.code_logic_switch_blocked]）。
     */
    private fun handleLogicCommand(command: com.apex.agent.slash.SlashCommand.Logic) {
        val mode = StandardLogicMode.fromName(command.id)
        if (mode == null) {
            appendSystemMessage(
                languageManager.getString(R.string.code_logic_unknown_mode, command.id)
            )
            return
        }
        // setLogicMode 内部已发切换回执（code_logic_switched_*）与
        // isRunning 拒绝分支（返回 false）；此处补齐命令通道的失败提示。
        if (!setLogicMode(mode)) {
            appendSystemMessage(
                languageManager.getString(R.string.code_logic_switch_blocked)
            )
        }
    }

    // ═══ #197 模型 API 配置状态（未配置时发送拦截由 UI 层处理）═══

    val llmConfigured: StateFlow<Boolean> =
        settingsRepository.llmConfiguredFlow(viewModelScope)

    /** 函数调用二级菜单候选工具（注册表快照，含类别/风险元数据）。 */
    fun availableTools(): List<com.apex.agent.ui.chatkit.ToolRef> =
        toolRegistry.getAllTools()
            .map { tool ->
                com.apex.agent.ui.chatkit.ToolRef(
                    id = tool.id,
                    name = tool.name,
                    category = tool.metadata.category,
                    highRisk = tool.metadata.isHighRisk
                )
            }
            .sortedWith(compareBy<com.apex.agent.ui.chatkit.ToolRef> { it.category?.order ?: Int.MAX_VALUE }.thenBy { it.id })

    // ═══ 消息发送 ═══

    fun sendMessage(text: String) {
        val trimmed = text.trim()
        val pendingCmds = _pendingCommands.value
        if (trimmed.isEmpty() && pendingCmds.isEmpty()) return

        // ═══ 任务排队：运行中发出的第二条任务入队（逻辑住 CodeTaskQueueController）═══
        if (_uiState.value.isRunning && enqueueWhileRunning(trimmed, pendingCmds)) return

        // ═══ v5 多 chip：≥2 枚时逐个路由合并为一轮引擎执行 ═══
        if (pendingCmds.size >= 2) {
            _pendingCommands.value = emptyList()
            _uiState.update { it.copy(inputDraft = "") }
            handleMultiChipPipeline(pendingCmds, trimmed)
            return
        }

        // ═══ #197 斜杠管线：单枚胶囊拼回命令；/ 开头文本走路由 ═══
        // 摘胶囊 + 清草稿（斜杠/普通发送共用收尾），再分派。
        val pendingCmd = pendingCmds.firstOrNull()
        val effectiveText = if (pendingCmd != null) {
            if (trimmed.isEmpty()) pendingCmd.toCommandToken()
            else pendingCmd.toCommandToken() + " " + trimmed
        } else trimmed
        _pendingCommands.value = emptyList()
        if (effectiveText.startsWith("/")) {
            _uiState.update { it.copy(inputDraft = "") }
            handleSlashCommand(effectiveText)
            return
        }

        // #154：@file:line 选区引用——引用块只进引擎输入，UI 消息保持原文；
        // 首个引用立即设为当前文件并打开编辑器面板（选区即上下文）。
        val refs = AtRefParser.parse(trimmed)
        val engineInput = AtRefParser.buildContextBlock(refs)?.let { trimmed + it } ?: trimmed
        refs.firstOrNull()?.let { openEditorFile(it.path) }

        _uiState.update {
            it.copy(
                messages = it.messages + CodeChatMessage(idGen.incrementAndGet(), CodeChatMessage.Role.USER, trimmed),
                inputDraft = ""
            )
        }

        runEngine(engineInput, displayGoal = trimmed)
    }

    /**
     * 引擎执行主路径（sendMessage 与斜杠路由共用）：同步全局规则/小圆环参数
     * （#197）→ 预检档位 → 长任务入账 → 胶囊入轴 → 事件双通道收集。
     *
     * @param engineInput 进引擎的完整输入（含 @引用块/斜杠路由提示词）
     * @param displayGoal 长任务追踪的目标文案（用户原始输入）
     */
    private fun runEngine(engineInput: String, displayGoal: String? = null) {
        val goal = displayGoal ?: engineInput

        // #209：记录本次运行输入 —— 失败后错误条「重试」按原样重放
        //（sendMessage / 斜杠路由 / 模板启动等所有入口统一覆盖）。
        lastRunEngineInput = engineInput
        lastRunDisplayGoal = displayGoal

        // Issue #164：发送前同步全局规则（设置页改动无需重启，下轮生效）。
        // 引擎侧注入详见 CodeAgentEngine.refreshContext + RulesProvider。
        codeEngineImpl?.updateGlobalRules(settingsRepository.agentSettings.value.globalRules)

        // ═══ #197 「小圆环」函数调用（Coding 工位独占）：会话上下文附加段 +
        // v4 强制工具计划。prepareForTask 的 refreshContext 会把 extras 拼进
        // additionalSystemContext（顺序：先存 extras 再刷上下文）。 ═══
        // 功能聚合注入段（16 项能力）同通道追加 —— 小圆环在前、功能聚合在后。
        // v1.9 角色经验卡：召回的相关经验段拼在末位（同类任务先读历史教训）。
        codeEngineImpl?.updateSessionExtras(
            roleExperience.enrichExtras(
                base = neonOrbStore.mergeWith(toolkitStore.buildSessionContext()),
                goal = goal
            )
        )
        codeEngineImpl?.updateForcedTools(
            forcedToolIds = toolkitStore.forcedToolIds(),
            exposeAll = toolkitStore.exposeAllToolsEnabled()
        )

        // ═══ AUTO 档自治：发送前预检选档（四线统一，引擎零参与）═══
        // 先解析、后 prepareForTask：预检结果（具体深度档）先落引擎字段，
        // refreshContext 再按该档注入编码特化指令——本轮 run 即生效
        // （旧序为「先 refresh 再预检」，深潜线 AUTO 的编码指令恒滞后一轮）。
        // 决策进系统消息 + uiState.adaptiveDecision（选择器旁回显）。
        // 非 AUTO 档直接用用户显式档（引擎档位缓存同步刷新，幂等）。
        resolveRuntimeThinkingLevel(goal)

        codeEngineImpl?.prepareForTask()

        // v1.2 长任务追踪：本次运行的开始（旧 run 未收尾会被自动 ABORTED
        // 收尾，见 LongTaskTracker.beginRun 防御）。档位记用户选择（AUTO 记
        // AUTO——诚实口径，实际生效档在 adaptiveDecision 可追溯）。
        longTaskTracker.beginRun(
            goal = goal,
            workspaceId = boundWorkspaceId ?: "",
            workspaceName = _uiState.value.activeWorkspace?.name ?: "",
            thinkingLevel = _uiState.value.thinkingLevel.name,
            agentMode = _uiState.value.mode.name
        )

        // v1.9 角色经验卡：本轮任务的聚合归零（当前激活角色领卡）。
        roleExperience.beginRun(goal, _uiState.value.activeWorkspace?.name ?: "")

        // 深水区升级观察器归零（新 run 重新计数）。
        runToolCalls = 0
        recentToolOutcomes.clear()

        // 胶囊时间轴：用户气泡入轴 + 渲染 ticker 启动（25ms ≤40Hz 攒批）。
        // 输出展示档/风格本轮捕获（运行中改设置不打断当前轮，见
        // CodeOutputModeOps.kt / CodeOutputStyleOps.kt）。
        captureRunOutputMode()
        captureRunOutputStyle()
        streamSession.beginRun(goal)
        startRenderTicker()

        runJob = viewModelScope.launch {
            _uiState.update { it.copy(isRunning = true, error = null, errorRetriable = false) }
            var aborted = false
            try {
                codeEngine.execute(UserInput(text = engineInput)).collect { event ->
                    longTaskTracker.onEvent(event)
                    roleExperience.onEvent(event)
                    maybeEscalateOnDeepWater(event)
                    if (event is AgentEvent.ThinkingChunk && event.text.contains(GOAL_NOTICE_MARKER)) {
                        // v3 GOAL：验收结论不走思考卡（默认折叠只有 80 字预览，
                        // 且下一轮 ThinkingComplete 会整体覆写文本吞掉通知）
                        // → 注入系统行，时间轴常驻可见并随会话落盘。
                        appendSystemMessage(event.text.trim())
                    } else {
                        streamSession.onEvent(event)
                    }
                    reduce(event)
                    // v1.6 工作区浏览器实时跟随：文件类工具事件 → 库控制器
                    //（callId 幂等去重在库内；浏览器未打开时仅进 feed，零开销）
                    workspaceKit.onAgentEvent(event)
                }
            } catch (e: CancellationException) {
                aborted = true
                // abort 或 VM 清理：静默
            } catch (e: Exception) {
                // #209：异常文本先净化（类名映射/首行有效信息/过滤堆栈与 null），
                // 再进错误条 —— 裸英文堆栈串不再直出；本次运行可一键重试。
                showError(sanitizeErrorText(e), retriable = true)
            } finally {
                _uiState.update {
                    it.copy(
                        isRunning = false,
                        messages = it.messages.map { m -> if (m.isStreaming) m.copy(isStreaming = false) else m },
                        contextUsedTokens = codeEngineImpl?.currentTokenCount() ?: 0,
                        contextMaxTokens = contextWindowTokens(),
                        todos = codeTodoTool.snapshot()
                    )
                }
                // 上一轮错误史归档（下轮 AUTO 预检的输入信号）。
                lastRunToolCalls = runToolCalls
                lastRunErrors = recentToolOutcomes.count { !it }
                // 渲染收尾：停 ticker 前冲最后一次快照（尾巴事件不丢）。
                stopRenderTicker()
                // v1.2 长任务收尾：状态裁决（中止/失败/完成）+ 长任务判定入库
                // （短任务返回 null 静默丢弃）；入库后刷新面板数据。
                val finalError = _uiState.value.error
                val taskStatus = when {
                    aborted -> LongTaskStatus.ABORTED
                    finalError != null -> LongTaskStatus.FAILED
                    else -> LongTaskStatus.COMPLETED
                }
                longTaskTracker.endRun(taskStatus, errorMessage = finalError)
                // v1.9 角色经验卡：确定性提炼 + 落卷（纯聊天轮内部静默丢弃）。
                roleExperience.endRun(taskStatus.name, errorMessage = finalError)
                // 任务完成通知（设置 → 通知 真接线：后台时跑完发系统通知，
                // 前台静音；中止不发 —— 用户自己按的停）。
                notifyRunCompletion(taskStatus, lastRunDisplayGoal)
                if (_uiState.value.longTaskSheetVisible) refreshLongTasks()
                scheduleSessionPersist()
                // 任务排队收尾（干净完成 → 自动接力；失败/中止 → 等确认，见 QueuedTaskPolicy）
                finalizeQueuedTaskAfterRun(taskStatus)
            }
        }
    }

    fun abort() {
        runJob?.cancel()
        // 本地防御式收尾（对齐 Agent 屏 abort）：已取消协程上 emit(Aborted) 再抛取消异常、
        // 事件到不了收集器，不收口则时间轴冻结（气泡挂光标、胶囊无限计时）。
        streamSession.abortLocally()
        viewModelScope.launch { codeEngine.abort() }
        // 收口后的时间轴立即冲最终快照，不等不会来的 tick。
        stopRenderTicker()
        _uiState.update { it.copy(isRunning = false) }
    }

    fun submitUserInput(answer: String) {
        _uiState.update { it.copy(pendingQuestion = null) }
        codeEngine.submitUserInput(answer)
    }

    /**
     * #509：用户取消挂起的提问（返回键 / 取消按钮）。
     *
     * 必须同时关界面**并**唤醒引擎等待 —— 只清 `pendingQuestion` 会让引擎
     * 继续挂在 5 分钟超时上，界面看似已取消而任务实际停摆。
     * `cancelUserInput()` 以空答案唤醒，引擎侧由 `AskUserOutcome.NoAnswer`
     * 明确告知模型「没有作答」，不再伪造 `User answered:`。
     */
    fun cancelPendingQuestion() {
        _uiState.update { it.copy(pendingQuestion = null) }
        codeEngine.cancelUserInput()
    }

    /** 用户回答了工具/权限门的结构化提问（QuestionCard 回传）。 */
    fun answerAgentQuestion(selectedIds: List<String>, customText: String?) {
        val question = userQuestionBridge.pendingQuestion.value ?: return
        userQuestionBridge.submit(
            AgentAnswer(
                questionId = question.id,
                selectedOptionId = selectedIds.firstOrNull(),
                selectedOptionIds = selectedIds,
                customText = customText?.takeIf { it.isNotBlank() }
            )
        )
    }

    /** 用户取消了工具/权限门的结构化提问（视作跳过）。 */
    fun cancelAgentQuestion() {
        val question = userQuestionBridge.pendingQuestion.value ?: return
        userQuestionBridge.submit(AgentAnswer(questionId = question.id, skipped = true))
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null, errorRetriable = false) }
    }

    /**
     * #209 错误条「重试」：原样重放最近一次引擎运行（runEngine 同路径 ——
     * 不重复追加用户气泡，对话历史语义与 Agent 屏 retryLastUser 一致）。
     * 无可重放运行或仍在运行时空操作。
     */
    fun retryLastRun() {
        val input = lastRunEngineInput ?: return
        if (_uiState.value.isRunning) return
        runEngine(input, lastRunDisplayGoal)
    }

    /**
     * 错误条统一出口（#209）：[retriable] = true 时 UI 渲染「重试」按钮 ——
     * 仅引擎运行失败类错误（引擎 Error 事件按 recoverable 透传、collect 异常
     * 恒为 true）；参数校验/状态冲突类（任务运行中、工作区冲突等）默认 false，
     * 避免把重放入口挂在语义无关的错误上。
     */
    internal fun showError(message: String, retriable: Boolean = false) {
        _uiState.update { it.copy(error = message, errorRetriable = retriable) }
    }

    /**
     * 异常 → 错误条可读文案（#209；#312 资源化）：三段策略见
     * [CodeErrorText]（God-file 预算：净化逻辑拆至独立文件，VM 只留代理）。
     */
    internal fun sanitizeErrorText(e: Throwable): String =
        CodeErrorText.sanitize(e, languageManager)

    fun clearConversation() {
        // v1.4.10：新会话 = 先归档再清空（旧实现物理删除，历史不可找回）。
        // 实现住 CodeHistoryController.kt（God-file 预算：VM 只减不增）。
        clearConversationWithArchive()
    }

    // ═══ 思考档位（coding 专属七档）═══

    /**
     * 切换思考档位：持久化（codeThinkingLevel）+ 引擎三通道（档位映射 +
     * 旋钮补偿 + 编码特化指令）+ 模型原生 reasoning 强度同步（T1 通道；
     * AUTO 档不动原生 effort——实际档位由发送前预检决定）。
     */
    fun setThinkingLevel(level: CodeThinkingLevel) {
        settingsRepository.updateAgentSettings { copy(codeThinkingLevel = level.name.lowercase()) }
        applyThinkingLevel(level)
        if (level == CodeThinkingLevel.AUTO) return
        val effort = level.toReasoningEffortName()
            ?.let { name -> runCatching { ReasoningEffort.valueOf(name) }.getOrNull() }
            ?: ReasoningEffort.NONE
        settingsRepository.profiles.value.firstOrNull { it.isDefault }
            ?.let { settingsRepository.upsertProfile(it.copy(reasoningEffort = effort)) }
    }

    /** 档位 → UI 状态 + 引擎三通道（setThinkingLevel 与启动恢复共用）。 */
    private fun applyThinkingLevel(level: CodeThinkingLevel) {
        _uiState.update { it.copy(thinkingLevel = level, adaptiveDecision = null) }
        codeEngineImpl?.updateThinkingLevel(level)
        effectiveRunLevel = if (level == CodeThinkingLevel.AUTO) effectiveRunLevel else level
    }

    /** 启动恢复：codeThinkingLevel 字符串 → 档位（空/未知 → STANDARD）。 */
    private fun restoreThinkingLevel() {
        val level = CodeThinkingLevel.fromName(settingsRepository.agentSettings.value.codeThinkingLevel)
            ?: CodeThinkingLevel.STANDARD
        applyThinkingLevel(level)
    }

    /**
     * AUTO 档发送前预检：解析出本轮生效深度档并下发引擎（**四线统一**）。
     *
     * - 预检信号：任务 goal + 上一轮 lastRun 计数/错误史；
     * - 决策进系统消息（可解释）+ uiState.adaptiveDecision（选择器旁回显）；
     * - 生效档写 [effectiveRunLevel]（深水区升级观察器的比较基准）；
     * - 非 AUTO 档：直接用用户显式档（effectiveRunLevel 同步 + 引擎档位
     *   幂等刷新——切逻辑线后新线引擎同样拿到当前档，缓存不滞留旧值）。
     *
     * 注意：引擎 patchConfig 只接受解析后的具体档（toAgentLevel 映射 +
     * 旋钮补偿在 CodeAgentEngine.updateThinkingLevel 内完成），
     * 引擎从不感知 AUTO——四条逻辑线同语义；标准/织造/信使线的适配器
     * 收到解析后的档位各自映射回合预算倍率（KitThinkingLevel 等价枚举）。
     */
    private fun resolveRuntimeThinkingLevel(goal: String) {
        val selected = _uiState.value.thinkingLevel
        if (selected != CodeThinkingLevel.AUTO) {
            effectiveRunLevel = selected
            codeEngineImpl?.updateThinkingLevel(selected)
            return
        }
        val decision = adaptiveSelector.select(
            goalText = goal,
            lastRunToolCalls = lastRunToolCalls,
            lastRunErrors = lastRunErrors
        )
        effectiveRunLevel = decision.level
        codeEngineImpl?.updateThinkingLevel(decision.level)
        _uiState.update { it.copy(adaptiveDecision = decision.reason) }
        // #312：前缀文案资源化（decision.reason 为 core 侧生成，另行跟踪）
        appendSystemMessage(languageManager.getString(R.string.code_adaptive_precheck_fmt, decision.reason))
    }

    /**
     * 运行中深水区升级观察器（AUTO 档专属，**四线统一**）：
     * ToolCallComplete 计数 + 3 次成败滑窗 →
     * [CodeAdaptiveThinkingSelector.escalateOnDeepWater]。
     *
     * 升级落地：引擎档位热切换（下轮迭代生效）+ 编码指令即时重织
     * （prepareForTask——升级档的编码特化指令从本轮后续迭代起跟进，
     * 不再等下一次发送）+ 系统消息说明。
     * 仅 AUTO 档参与（用户显式选档被尊重，不自动加码）；APEXCODE 已是
     * 顶档（观察器内部短路）；标准/织造/信使线的 updateThinkingLevel
     * 为纯字段写（下回合读新倍率），热切换同款安全。
     */
    private fun maybeEscalateOnDeepWater(event: AgentEvent) {
        if (event !is AgentEvent.ToolCallComplete) return
        runToolCalls++
        recentToolOutcomes.addLast(event.success)
        while (recentToolOutcomes.size > RECENT_OUTCOME_WINDOW) recentToolOutcomes.removeFirst()
        if (_uiState.value.thinkingLevel != CodeThinkingLevel.AUTO) return
        val decision = adaptiveSelector.escalateOnDeepWater(
            runToolCalls = runToolCalls,
            recentWindowErrors = recentToolOutcomes.count { !it },
            current = effectiveRunLevel
        ) ?: return
        effectiveRunLevel = decision.level
        codeEngineImpl?.updateThinkingLevel(decision.level)
        // 升级档的编码特化指令即时重织（深潜线 refreshContext 按新档取
        // directive；kit 线 prepareForTask 为空实现/轻刷新，同通道零风险）。
        codeEngineImpl?.prepareForTask()
        appendSystemMessage(
            languageManager.getString(R.string.code_adaptive_escalate_fmt, decision.reason)
        )
    }

    /** 追加一条系统消息（时间轴主通道 + 旧消息通道双写，仅 UI 展示）。 */
    internal fun appendSystemMessage(text: String) {
        streamSession.injectSystem(text)
        _uiState.update {
            it.copy(messages = it.messages + CodeChatMessage(idGen.incrementAndGet(), CodeChatMessage.Role.SYSTEM, text))
        }
    }

    // ═══ 输入草稿（#154：@file:line 程序化插入通道）═══

    fun updateInputDraft(text: String) {
        _uiState.update { it.copy(inputDraft = text) }
    }

    /** 把一段引用文本（如 `src/Foo.kt:12`）追加进输入草稿。 */
    fun insertAtRef(ref: String) {
        val piece = ref.trim()
        if (piece.isEmpty()) return
        _uiState.update { state ->
            val joined = when {
                state.inputDraft.isBlank() -> piece
                state.inputDraft.endsWith(" ") -> state.inputDraft + piece
                else -> state.inputDraft + " " + piece
            }
            state.copy(inputDraft = joined)
        }
    }

    // ═══ 编辑器面板（#154）═══

    /** 打开（或切换到）某个工作区相对路径的文件；引擎上下文同步跟随。 */
    fun openEditorFile(path: String) {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return
        _uiState.update { it.copy(editorFilePath = trimmed, editorFile = null, editorLoading = true, editorError = null) }
        codeEngineImpl?.setActiveFile(trimmed)
        editorJob?.cancel()
        editorJob = viewModelScope.launch {
            try {
                val root = workspaceRoots.activeRoot()
                val file = withContext(Dispatchers.IO) { EditorFileLoader.loadEditorFile(root, trimmed) }
                // 防过期回写：加载期间用户可能已关闭或切到别的文件
                if (_uiState.value.editorFilePath == trimmed) {
                    _uiState.update { it.copy(editorFile = file, editorLoading = false) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: EditorFileException) {
                // #312：数据层稳定错误码 → 本地化文案（编辑器面板错误行）。
                if (_uiState.value.editorFilePath == trimmed) {
                    _uiState.update { it.copy(editorLoading = false, editorError = editorErrorText(e)) }
                }
            } catch (e: Exception) {
                if (_uiState.value.editorFilePath == trimmed) {
                    // #209 收尾：编辑器加载失败同样走 sanitize —— 裸 message
                    // （英文堆栈/null）不直出错误行。
                    _uiState.update { it.copy(editorLoading = false, editorError = sanitizeErrorText(e)) }
                }
            }
        }
    }

    fun closeEditor() {
        editorJob?.cancel()
        _uiState.update { it.copy(editorFilePath = null, editorFile = null, editorLoading = false, editorError = null) }
    }

    /** #209 收尾：单独清掉编辑器错误行（面板保留，不整块关闭）。 */
    fun dismissEditorError() {
        _uiState.update { it.copy(editorError = null) }
    }

    /**
     * #312：EditorFileLoader 稳定错误码 → 本地化文案（纯数据层不依赖
     * Android 资源，UI 侧按码取词；上下文变量：路径 / 字节数）。
     */
    private fun editorErrorText(e: EditorFileException): String = when (e.code) {
        EditorLoadError.EMPTY_PATH -> languageManager.getString(R.string.code_editor_err_empty_path)
        EditorLoadError.PATH_ESCAPE -> languageManager.getString(R.string.code_editor_err_escape_fmt, e.path.orEmpty())
        EditorLoadError.NOT_FOUND -> languageManager.getString(R.string.code_editor_err_not_found_fmt, e.path.orEmpty())
        EditorLoadError.IS_DIRECTORY -> languageManager.getString(R.string.code_editor_err_directory_fmt, e.path.orEmpty())
        EditorLoadError.TOO_LARGE -> languageManager.getString(R.string.code_editor_err_too_large_fmt, e.byteCount)
    }

    // ═══ 工作区管理 ═══

    fun refreshWorkspaces() {
        _uiState.update {
            it.copy(
                workspaces = workspaceManager.list(),
                activeWorkspace = workspaceManager.activeWorkspace()
            )
        }
    }

    fun createWorkspace(name: String) {
        val created = workspaceManager.create(name)
        if (created == null) {
            showError(languageManager.getString(R.string.code_ws_create_failed))
            return
        }
        bindWorkspace(created)
    }

    fun switchWorkspace(workspaceId: String) {
        val activated = workspaceManager.activate(workspaceId) ?: return
        bindWorkspace(activated)
    }

    fun deleteWorkspace(workspaceId: String) {
        if (_uiState.value.isRunning) {
            showError(languageManager.getString(R.string.code_ws_delete_blocked_running))
            return
        }
        // system 根 = / 绝不可删（manager 侧恒拒，VM 前置拦截统一文案）
        if (workspaceId == "default" || workspaceId == CodeWorkspaceManager.SYSTEM_ID) {
            showError(languageManager.getString(R.string.code_ws_delete_default))
            return
        }
        workspaceManager.delete(workspaceId)
        // 会话快照 + 历史归档随工作区一并清理（引擎记忆目录由 manager 负责）
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { codeSessionStore.clear(workspaceId) }
                runCatching { codeHistoryStore.clearWorkspace(workspaceId) }
            }
        }
        refreshWorkspaces()
        val active = workspaceManager.activeWorkspace()
        if (active != null) bindWorkspace(active) else clearConversation()
    }

    internal fun bindWorkspace(ws: CodeWorkspace) {
        // 切走前捕获旧会话快照（同工作区重复 bind 不冲刷）
        val previousId = boundWorkspaceId
        val previousSnapshot = if (previousId != null && previousId != ws.workspaceId) {
            buildSessionSnapshot(previousId)
        } else null
        boundWorkspaceId = ws.workspaceId
        sessionPersistJob?.cancel()
        sessionLoadJob?.cancel()
        editorJob?.cancel()

        // 引擎绑定（同步：clearHistory + restoreHistory + refreshContext）
        codeEngineImpl?.setActiveWorkspace(
            workspaceId = ws.workspaceId,
            name = ws.name,
            root = File(ws.hostRootPath)
        )

        // UI 先切到「新工作区空态」，快照异步恢复（含旧快照冲刷）；
        // 会话累计 token 随会话边界归零（历史归档不存 token 统计）。
        _uiState.update {
            it.copy(
                activeWorkspace = ws,
                workspaces = workspaceManager.list(),
                contextUsedTokens = codeEngineImpl?.currentTokenCount() ?: 0,
                contextMaxTokens = contextWindowTokens(),
                sessionInputTokens = 0,
                sessionOutputTokens = 0,
                sessionCachedTokens = 0,
                editorFilePath = null, editorFile = null, editorError = null,
                editorLoading = false
            )
        }

        sessionLoadJob = viewModelScope.launch {
            sessionMutex.withLock {
                if (previousSnapshot != null) {
                    withContext(Dispatchers.IO) { runCatching { codeSessionStore.save(previousSnapshot) } }
                        .onFailure { AppLogger.instance.warn(LogCategory.UI, "CodeSession", "冲刷旧会话快照失败：${it.message}") }
                }
                restoreSessionSnapshot(ws)
            }
        }
    }

    // ═══ 会话快照三动作面（restore/build/persist，#152）已拆至
    //     CodeSessionSnapshotOps.kt（God-file 预算纪律；调用点零改动）═══

    override fun onCleared() {
        runJob?.cancel()
        renderJob?.cancel()
        // #152：viewModelScope 在 onCleared 前已被取消——防抖尾巴经独立 scope 冲刷
        val wsId = boundWorkspaceId
        if (wsId != null) {
            val snapshot = buildSessionSnapshot(wsId)
            persistScope.launch {
                runCatching { codeSessionStore.save(snapshot) }
                    .onFailure { AppLogger.instance.warn(LogCategory.UI, "CodeSession", "最终快照冲刷失败：${it.message}") }
            }
        }
        super.onCleared()
    }

    // ═══ 胶囊时间轴：渲染 ticker 与详情数据 ═══

    /** 启动渲染 ticker（25ms ≤40Hz；脏才推快照，无变更零重组）。 */
    private fun startRenderTicker() {
        renderJob?.cancel()
        renderJob = viewModelScope.launch {
            while (isActive) {
                delay(RENDER_TICK_MS)
                streamSession.tick()?.let { snap ->
                    _uiState.update { it.copy(stream = snap) }
                }
            }
        }
    }

    /** 停止 ticker 并冲最后一次快照（run 收尾调用）。 */
    private fun stopRenderTicker() {
        renderJob?.cancel()
        renderJob = null
        streamSession.snapshot().let { snap ->
            _uiState.value = _uiState.value.copy(stream = snap)
        }
    }

    /** 详情弹层的终端尾窗（历史 BASH 调用的输出回看）。 */
    fun terminalLogOf(callId: String): String? = streamSession.terminalContentOf(callId)

    /** 详情弹层入参便捷转换（UI 持有 StreamToolCall 时调用）。 */
    fun toolCallById(call: StreamToolCall): StreamToolCall = call

    private companion object {
        /** 深水区升级观察器的工具成败滑窗长度（与选档器口径一致：最近 3 次）。 */
        const val RECENT_OUTCOME_WINDOW = 3

        /** 渲染攒批窗口：25ms = 上限 40Hz（规格书：脉冲式输出）。 */
        const val RENDER_TICK_MS = 25L
    }
}
