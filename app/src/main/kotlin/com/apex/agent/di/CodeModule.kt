package com.apex.agent.di

import android.content.Context
import com.apex.agent.codenative.NativeTextKernel
import com.apex.agent.core.code.CodeAgentEngine
import com.apex.agent.core.code.CodeContextProvider
import com.apex.agent.core.code.CodeConversationMemory
import com.apex.agent.core.code.RulesProvider
import com.apex.agent.core.code.StandardTextKernel
import com.apex.agent.core.code.courier.CourierKitAdapter
import com.apex.agent.core.code.loom.LoomKitAdapter
import com.apex.courierkit.dispatch.CourierCommandProbe
import com.apex.courierkit.dispatch.CourierModelInfo
import com.apex.courierkit.dispatch.DispatchPermissionSnapshot
import com.apex.courierkit.dispatch.CourierPermissionSnapshotSource
import com.apex.courierkit.dispatch.CourierSkillCatalog
import com.apex.courierkit.dispatch.CourierSkillDigest
import com.apex.courierkit.dispatch.CourierSkillPayload
import com.apex.courierkit.dispatch.DispatchPermissionRule
import com.apex.courierkit.dispatch.DispatchRuleEffect
import com.apex.loomkit.weave.LoomActivePackage
import com.apex.loomkit.weave.loop.LoomModelInfo
import com.apex.loomkit.weave.packager.LoomPackageCatalog
import com.apex.loomkit.weave.packager.LoomPackageInfo
import com.apex.loomkit.weave.permission.LoomAccessMode
import com.apex.loomkit.weave.permission.LoomPermissionRule
import com.apex.loomkit.weave.permission.LoomPermissionSnapshot
import com.apex.loomkit.weave.permission.LoomPermissionSource
import com.apex.loomkit.weave.permission.LoomRuleEffect
import com.apex.agent.core.code.standard.DualLogicCodeEngine
import com.apex.agent.core.code.standard.StandardKitAdapter
import com.apex.agent.core.tools.catalog.McpToolNaming
import com.apex.agent.core.tools.mcp.McpManager
import com.apex.standardkit.loop.KitMcpInstructionSource
import com.apex.standardkit.loop.KitMcpServerInstructions
import com.apex.standardkit.loop.KitModelInfo
import com.apex.standardkit.loop.KitPermissionEffect
import com.apex.standardkit.loop.KitPermissionMode
import com.apex.standardkit.loop.KitPermissionRule
import com.apex.standardkit.loop.KitPermissionSource
import com.apex.agent.core.llm.ModelRole
import com.apex.agent.core.llm.runtime.ModelRoleRouter
import com.apex.agent.permission.PermissionEffect
import com.apex.agent.permission.PermissionMode
import com.apex.agent.permission.PermissionRule
import com.apex.agent.core.codetools.CodeWorkspaceRoots
import com.apex.agent.core.codetools.git.GitCommandRunner
import com.apex.agent.core.codetools.tools.CodeTodoTool
import com.apex.agent.git.ProotGitCommandRunner
import com.apex.agent.github.GithubTokenManager
import com.apex.agent.platform.terminal.proot.PRootCapabilitySource
import com.apex.agent.platform.terminal.proot.PRootHostEnvironment
import com.apex.agent.core.engine.AgentConfig
import com.apex.agent.core.engine.AgentEngine
import com.apex.agent.core.engine.AgentMode
import com.apex.agent.core.engine.ApexAgentEngine
import com.apex.agent.core.code.thinking.CodeAdaptiveThinkingSelector
import com.apex.agent.core.code.thinking.CodeThinkingLevel
import com.apex.agent.core.engine.compression.ContextCompressor
import com.apex.agent.core.engine.ExecutionMemoryObserver
import com.apex.agent.core.llm.LlmClient
import com.apex.agent.core.llm.runtime.ModelRuntime
import com.apex.agent.core.tools.ToolExecutor
import com.apex.agent.core.tools.ToolRegistry
import com.apex.agent.core.tools.catalog.ToolActivationStore
import com.apex.agent.core.tools.skill.SkillRegistry
import com.apex.agent.platform.code.ws.CodeWorkspaceManager
import com.apex.agent.ui.screen.code.history.CodeHistoryStore
import com.apex.agent.ui.screen.code.session.CodeSessionStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Named
import javax.inject.Singleton

/**
 * # Code Module — Coding 模式 DI 装配
 *
 * Coding 模式与 Agent 模式**同级别**：独立的引擎实例（@Named("code")）、独立的
 * 会话记忆（per-workspace 隔离），但**共享**全部能力基础设施：
 *
 * | 共享单例 | 互用效果 |
 * |---|---|
 * | ToolRegistry / ToolExecutor | 110+ 工具 + v3 门控/熔断/重试 + v4 目录 —— 两模式同一套规则 |
 * | SkillRegistry | prompt 型技能注入两模式系统提示词（安装即生效） |
 * | McpManager | mcp__search__web_search / mcp__github__* 一等工具两模式可用 |
 * | ModelRuntime | 多模型角色路由（PRIMARY/VISION/REASONING/SUMMARY） |
 * | CS-Mem observer | 编码动作同样进入认知记忆 |
 *
 * 引擎构造不重写 Agent Loop：CodeAgentEngine 是 ApexAgentEngine 的薄包装，
 * 编码行为经 additionalSystemContext 通道注入（见 [com.apex.agent.core.code.CodePrompts]）。
 */
@Module
@InstallIn(SingletonComponent::class)
object CodeModule {

    /** 共享 todo 工具单例（注册表与 CodeViewModel 看同一份状态）。 */
    @Provides
    @Singleton
    fun provideCodeTodoTool(): CodeTodoTool = CodeTodoTool()

    /** AUTO 档自治选档器（纯函数无状态；VM 发送前预检 + 运行中深水区升级观察）。 */
    @Provides
    @Singleton
    fun provideCodeAdaptiveThinkingSelector(): CodeAdaptiveThinkingSelector =
        CodeAdaptiveThinkingSelector()

    /**
     * 编码会话 UI 快照仓库（#152）：按 workspaceId 存消息/todos/lastActiveFile。
     *
     * ⚠️ baseDir 必须与引擎记忆目录（code_memory）隔离——两者都以 ws_<id>.json
     * 命名，同目录会静默互相覆盖（引擎侧存 API 级完整历史，本仓库存 UI 展示态）。
     */
    @Provides
    @Singleton
    fun provideCodeSessionStore(@ApplicationContext context: Context): CodeSessionStore {
        return CodeSessionStore(File(context.filesDir, "code_sessions"))
    }

    /**
     * Coding 历史对话归档仓库（v1.4.10）：新会话先归档（UI 快照 + 双线引擎记忆
     * 原文件），历史面板可恢复/删除。目录约定：code_history 与引擎记忆目录
     * code_memory / code_memory_standard 同层隔离。
     */
    @Provides
    @Singleton
    fun provideCodeHistoryStore(@ApplicationContext context: Context): CodeHistoryStore {
        return CodeHistoryStore(
            baseDir = File(context.filesDir, "code_history"),
            deepMemoryDir = File(context.filesDir, "code_memory"),
            standardMemoryDir = File(context.filesDir, "code_memory_standard")
        )
    }

    /**
     * git 命令执行通道（#153）：宿主无 git 二进制，git 预装在 PRoot Ubuntu
     * rootfs 内；工作区恒定 bind 为 guest /workspace（与终端会话同源）。
     * rootfsBaseDir / hostEnvironment / persistentHome 与 McpModule 的
     * ProotMcpProcessLauncher 接线同款约定（TerminalModule 提供）。
     * v3 S3（G2）：注入 GitHub PAT 供应器——已连接时 git clone/push 等经
     * 凭据桥真实可用（见 GithubTerminalEnvInjector）。
     */
    @Provides
    @Singleton
    fun provideProotGitCommandRunner(
        @ApplicationContext context: Context,
        hostEnvironment: PRootHostEnvironment,
        rootfsBaseDir: File,
        workspaceRoots: CodeWorkspaceRoots,
        capabilitySource: PRootCapabilitySource,
        githubTokenManager: GithubTokenManager
    ): GitCommandRunner {
        return ProotGitCommandRunner(
            hostEnv = hostEnvironment.hostEnv(),
            libprootPath = hostEnvironment.prootBinary.absolutePath,
            rootfsDir = rootfsBaseDir,
            isRootfsReady = { File(rootfsBaseDir, "current").exists() },
            workspaceRoots = workspaceRoots,
            persistentHomeDir = File(context.filesDir, "linux/home"),
            // T92：argv 能力门（与终端会话/apt 同款版本自适应）
            capabilities = capabilitySource::invoke,
            // v3 S3（G2）：GitHub 凭据供应（未连接返回 null = 不注入）
            gitHubTokenProvider = { githubTokenManager.getToken() }
        )
    }

    /** 工作区管理器（创建/列出/删除/激活 + 环境探测）。 */
    @Provides
    @Singleton
    fun provideCodeWorkspaceManager(
        @ApplicationContext context: Context
    ): CodeWorkspaceManager {
        // 工作区物理目录与 LinuxWorkspaceManager 同源父目录
        // （<filesDir>/linux/workspaces/）—— code_* 工具、terminal 会话、
        // Agent 文件工具三方看同一份文件；default 工作区即 Agent 沙箱根。
        val base = File(context.filesDir, "linux/workspaces")
        val index = File(context.filesDir, "code_workspaces")
        return CodeWorkspaceManager(workspacesBaseDir = base, indexDir = index)
    }

    /** CodeWorkspaceRoots 契约绑定（code_* 工具的动态根解析）。 */
    @Provides
    @Singleton
    fun provideCodeWorkspaceRoots(manager: CodeWorkspaceManager): CodeWorkspaceRoots = manager

    /** JIT 上下文提供者（环境探测 + 项目统计 → 系统提示词）。 */
    @Provides
    @Singleton
    fun provideCodeContextProvider(manager: CodeWorkspaceManager): CodeContextProvider {
        return object : CodeContextProvider {
            override fun provide(workspaceRoot: File, activeFile: String?): String? {
                // system 工作区（根 = /，2026-10 默认整个系统）：全系统根不做
                // 环境嗅探/项目统计 —— 深度遍历宿主根既慢又无项目语义。
                if (workspaceRoot.absolutePath == "/") return null
                val env = manager.detectEnvironment(workspaceRoot)
                val stats = manager.projectStats(workspaceRoot)
                return listOfNotNull(env.summary, stats).joinToString(" · ").ifBlank { null }
            }
        }
    }

    /** per-workspace 对话记忆（文件式，按 workspaceId 分片）。 */
    @Provides
    @Singleton
    fun provideCodeConversationMemory(@ApplicationContext context: Context): CodeConversationMemory {
        return CodeConversationMemory(File(context.filesDir, "code_memory"))
    }

    /**
     * 标准线 per-workspace 对话记忆（v1.5 双思考逻辑）：与深潜线的
     * code_memory 目录隔离——两条思考逻辑各自独立会话现场，切换互不污染，
     * 切回时现场完整恢复。
     */
    @Provides
    @Singleton
    @Named("codeStandard")
    fun provideStandardCodeConversationMemory(
        @ApplicationContext context: Context
    ): CodeConversationMemory {
        return CodeConversationMemory(File(context.filesDir, "code_memory_standard"))
    }

    /**
     * 织造线 per-workspace 对话记忆（第三思考逻辑线）：独立通道
     * code_memory_loom——与深潜/标准互不污染，切回时现场完整恢复。
     */
    @Provides
    @Singleton
    @Named("codeLoom")
    fun provideLoomCodeConversationMemory(
        @ApplicationContext context: Context
    ): CodeConversationMemory {
        return CodeConversationMemory(File(context.filesDir, "code_memory_loom"))
    }

    /**
     * 信使线 per-workspace 对话记忆（第四思考逻辑线）：独立通道
     * code_memory_courier——与另三线互不污染，切回时现场完整恢复。
     */
    @Provides
    @Singleton
    @Named("codeCourier")
    fun provideCourierCodeConversationMemory(
        @ApplicationContext context: Context
    ): CodeConversationMemory {
        return CodeConversationMemory(File(context.filesDir, "code_memory_courier"))
    }

    /**
     * 文本加速核（C++17 JNI，token 估算 / 行级 diff / 模糊定位）：
     * .so 缺失时 NativeTextKernel 内部自动回退纯 Kotlin 实现——这里恒可注入。
     */
    @Provides
    @Singleton
    fun provideStandardTextKernel(): StandardTextKernel = NativeTextKernel()

    /**
     * Coding 引擎（@Named("code")）—— v1.5 起为**双思考逻辑路由门面**：
     *
     * ```
     * DualLogicCodeEngine ──┬── CodeAgentEngine（深潜线：七档思考，v1.2 既有行为）
     *                       └── StandardKitAdapter → standard-loop 库（标准线：标准任务循环）
     * ```
     *
     * 深潜线配置取向（与 Agent 默认差异）：BUILD 循环 + 更高迭代上限
     * （编码任务链路长）+ 更大工具输出配额（CodeRead 输出被 2000 字符
     * 默认值截断会毁掉编码循环）。标准线由 [StandardKitAdapter] 接线
     * apex-kit-standard 库内核（画像回合预算 + 权限门 + 会话压缩 + 子代理
     * 派发 + 并行工具执行 + 续跑语义 + MCP 指引——循环语义全部在库中）。
     *
     * CodeViewModel 只面向 [CodeEngineFacade] / AgentEngine 编程，
     * 右上角「思考逻辑」切换经 DualLogicCodeEngine.switchLogic 路由。
     */
    @Provides
    @Singleton
    @Named("code")
    fun provideCodeAgentEngine(
        llmClient: LlmClient,
        toolRegistry: ToolRegistry,
        toolExecutor: ToolExecutor,
        contextCompressor: ContextCompressor,
        skillRegistry: SkillRegistry,
        privilegeInfoProvider: com.apex.agent.core.engine.PrivilegeInfoProvider,
        environmentInfoProvider: com.apex.agent.core.engine.EnvironmentInfoProvider,
        memoryObserver: ExecutionMemoryObserver,
        connectedServicesProvider: AndroidConnectedServicesProvider,
        modelRuntime: ModelRuntime,
        codeMemory: CodeConversationMemory,
        codeContextProvider: CodeContextProvider,
        // Issue #165：生命周期钩子派发口（coding 引擎实例同样接入）
        hookRunner: com.apex.agent.core.engine.HookRunner,
        // 技能渐进披露：与 Agent 模式共享同一激活存储（SkillActivate 工具
        // 写入的是全局单例；coding 引擎读同一份才能让装备即时生效——
        // 也避免 46 技能全量注入撞爆 coding 请求体积）。
        skillActivation: com.apex.agent.core.tools.skill.SkillActivationStore,
        // v1.5 标准线：独立记忆通道 + C++ 文本加速核
        @Named("codeStandard") standardMemory: CodeConversationMemory,
        standardTextKernel: StandardTextKernel,
        // #230 收尾：标准线专用执行器（仅环境门 —— 标准线引擎自带
        // 业界标准式权限门，共用主执行器的组合门会双弹窗）
        @javax.inject.Named("standardEngineTools") standardToolExecutor: ToolExecutor,
        // 织造线（第三线）：独立记忆通道 + 仅环境门执行器（同 #230 纪律）
        @Named("codeLoom") loomMemory: CodeConversationMemory,
        @javax.inject.Named("loomEngineTools") loomToolExecutor: ToolExecutor,
        // 信使线（第四线）：独立记忆通道 + 仅环境门执行器（同 #230 纪律）
        @Named("codeCourier") courierMemory: CodeConversationMemory,
        @javax.inject.Named("courierEngineTools") courierToolExecutor: ToolExecutor,
        // MCP 服务器指引源（initialize 握手的 instructions 字段——标准线
        // 注入系统提示词，模型拿到服务器官方用法指引）
        mcpManager: McpManager,
        // 权限设置源（设置层 → 标准线权限门的实时快照通道）
        settingsRepository: com.apex.agent.ui.screen.settings.SettingsRepository,
        // v3 GOAL 模式：目标验收协调器（与 Agent 屏共享单例；深潜线引擎挂
        // 每轮验收钩子，见 ApexAgentEngine.goalCoordinator）
        goalCoordinator: com.apex.agent.core.engine.goal.GoalModeCoordinator,
        // #395 abort 语义收口：弹窗提问（AskUserChoice/权限门）随 abort 即时收场，
        // 引擎流不再吃满 5 分钟超时（取消卡死根因）
        userQuestionBridge: com.apex.agent.core.engine.UserQuestionBridge
    ): AgentEngine {
        // 工具循环 v2 + 限速友好：设置层快照拍平进编码引擎（与 Agent 屏
        // AgentModule 同源字段；maxIterations 0 = 无上限哨兵）。
        val agentSettings = settingsRepository.agentSettings.value
        val richPayload = agentSettings.rateLimitFriendly && agentSettings.rateLimitRichPayload
        val codeConfig = AgentConfig(
            mode = AgentMode.BUILD,
            thinkingLevel = CodeThinkingLevel.STANDARD.toAgentLevel(),
            maxIterations = 40,
            toolCallParallelism = agentSettings.toolCallParallelism,
            maxContextTokens = 128_000,
            maxToolOutputLength = if (richPayload) 16_000 else 8_000,
            compressionThreshold = if (richPayload) 0.85f else 0.8f,
            preserveRecentTurns = if (richPayload) 9 else 6,
            // #197 工位作用域：coding 引擎只注入 coding/all 编码技能
            // （与 Agent 屏的聊天技能提示词层完全独立）
            skillScope = "coding"
        )
        val deepDive = CodeAgentEngine(
            delegate = ApexAgentEngine(
                llmClient = llmClient,
                toolRegistry = toolRegistry,
                toolExecutor = toolExecutor,
                config = codeConfig,
                memory = codeMemory,
                contextCompressor = contextCompressor,
                skillRegistry = skillRegistry,
                privilegeInfoProvider = privilegeInfoProvider,
                environmentInfoProvider = environmentInfoProvider,
                memoryObserver = memoryObserver,
                connectedServicesProvider = connectedServicesProvider,
                modelRuntime = modelRuntime,
                // 独立激活存储：ToolOpen 的会话激活不与 Agent 模式互相污染
                toolActivation = ToolActivationStore(),
                // Issue #165：coding 引擎同样接入生命周期钩子（会话/回合/压缩事件）
                hookRunner = hookRunner,
                // 技能激活共享（见参数 KDoc）：目录 + 已装备方法论注入。
                skillActivation = skillActivation,
                // v3 GOAL 模式：每轮收尾快速模型验收（无目标时零行为差异）
                goalCoordinator = goalCoordinator,
                // #395 abort 语义收口（与 Agent 屏同源）：取消任务时同步
                // 收口桥接层挂起的提问/权限弹窗
                userQuestionGateway = userQuestionBridge
            ),
            codeMemory = codeMemory,
            contextProvider = codeContextProvider,
            // Issue #164：规则提供者（无状态可直 new）——refreshContext 时
            // 组装 Global/Project Rules 追加进 additionalSystemContext
            rulesProvider = RulesProvider()
        )
        // 设置层权限快照源：模式 + 规则三元组实时读取（与 PermissionModeGate
        // 的 settingsProvider 同款模式——改设置即时生效）；含空格的模式串
        // 视为 shell 命令模式（如 "git push*"），其余为工具 id 模式。
        val standardPermissionSource = object : KitPermissionSource {
            override fun snapshot(): KitPermissionSource.Snapshot {
                val settings = settingsRepository.agentSettings.value
                return KitPermissionSource.Snapshot(
                    mode = when (settings.permissionMode) {
                        PermissionMode.BYPASS -> KitPermissionMode.BYPASS
                        PermissionMode.DEFAULT -> KitPermissionMode.DEFAULT
                        PermissionMode.ACCEPT_EDITS -> KitPermissionMode.ACCEPT_EDITS
                        PermissionMode.PLAN -> KitPermissionMode.PLAN
                    },
                    rules = settings.permissionRules
                        .filter { !it.pattern.contains(' ') }
                        .map { it.toStandard() },
                    commandRules = settings.permissionRules
                        .filter { it.pattern.contains(' ') }
                        .map { it.toStandard() }
                )
            }
        }
        // 模型信息源：PRIMARY 角色解析链首选项（模型 id + 真实上下文窗口）
        // ——标准线 env 块注入与压缩预算消费（修复恒 128K 的窗口盲区）。
        val standardModelInfoProvider: () -> KitModelInfo? = {
            try {
                (modelRuntime.resolve(ModelRole.PRIMARY) as? ModelRoleRouter.ResolutionResult.Success)
                    ?.primary?.profile
                    ?.let { KitModelInfo(modelId = it.name, contextWindow = it.contextWindow) }
            } catch (t: Throwable) {
                null // 解析失败 → 静态 128K 兜底，env 块省略模型行
            }
        }
        // MCP 服务器指引源：已连接服务器的 initialize instructions →
        // （服务器名 → 指引 + 注册表 id 前缀）。引擎按当前工具面过滤——
        // 只有该服务器工具真的在面上，指引才注入（防幻觉指引）。
        val standardMcpInstructionSource = KitMcpInstructionSource {
            mcpManager.getServerInstructions().map { (server, instructions) ->
                KitMcpServerInstructions(
                    serverName = server,
                    instructions = instructions,
                    toolIdPrefix = "mcp__" + McpToolNaming.slug(server) + "__"
                )
            }
        }
        val standard = StandardKitAdapter(
            runtime = modelRuntime,
            toolRegistry = toolRegistry,
            // #230：标准线走引擎级权限门 + 仅环境门的执行器（防双弹窗）；
            // 深潜线（delegate = ApexAgentEngine，无引擎级门）继续用主执行器。
            toolExecutor = standardToolExecutor,
            memory = standardMemory,
            contextProvider = codeContextProvider,
            rulesProvider = RulesProvider(),
            textKernel = standardTextKernel,
            // 设置层权限接线（权限模式 + 规则三元组，每轮任务前拉取）
            permissionSource = standardPermissionSource,
            // 模型感知上下文窗口（env 块 + 压缩预算）
            modelInfoProvider = standardModelInfoProvider,
            // MCP 服务器使用指引（initialize 握手透传）
            mcpInstructionSource = standardMcpInstructionSource,
            // v3 子代理预算：标准线派发器与深潜线 SubAgentRunner 同源消费
            // （设置 → 子代理 的快照，改设置即时生效）
            subAgentSettingsProvider = {
                settingsRepository.agentSettings.value.subagent.sanitized()
            }
        )
        // 织造线（第三线，独立库 com.apex.loomkit:loom-weave）：端口适配 +
        // 库内自有循环（梭回合机 / 权限三态门 / 子线程群 / 织造包——全部在
        // apex-kit-loom 库演进，宿主仅保留薄适配层）。设置层权限模式 →
        // 织造模态映射：BYPASS → UNBOUND（无界）、DEFAULT/ACCEPT_EDITS →
        // FABRIC（布面内自由织）、PLAN → OBSERVE（只读巡视）。
        val loomPermissionSource = LoomPermissionSource {
            val settings = settingsRepository.agentSettings.value
            LoomPermissionSnapshot(
                mode = when (settings.permissionMode) {
                    PermissionMode.BYPASS -> LoomAccessMode.UNBOUND
                    PermissionMode.DEFAULT, PermissionMode.ACCEPT_EDITS -> LoomAccessMode.FABRIC
                    PermissionMode.PLAN -> LoomAccessMode.OBSERVE
                },
                rules = settings.permissionRules
                    .filter { !it.pattern.contains(' ') }
                    .map { it.toLoom() },
                commandRules = settings.permissionRules
                    .filter { it.pattern.contains(' ') }
                    .map { it.toLoom() }
            )
        }
        // 织造包目录：共享技能仓库的 coding 域技能（get → 全文指引；
        // digest → 目录一行摘要）
        val loomPackageCatalog = object : LoomPackageCatalog {
            override fun list(): List<LoomPackageInfo> =
                skillRegistry.getSkillDigests("coding").map {
                    LoomPackageInfo(id = it.id, title = it.name, summary = it.summary)
                }

            override fun load(id: String): LoomActivePackage? {
                val skill = skillRegistry.get(id) ?: return null
                val scopes = skillRegistry.effectiveScopesOf(id)
                if ("coding" !in scopes) return null
                val manifest = skill.manifest
                return LoomActivePackage(
                    id = manifest.id,
                    title = manifest.name,
                    // 提示词型技能注入正文；其余技能以描述兑底（目录语义仍成立）
                    instructions = manifest.promptInjection?.takeIf { it.isNotBlank() }
                        ?: manifest.description
                )
            }
        }
        val loom = LoomKitAdapter(
            runtime = modelRuntime,
            toolRegistry = toolRegistry,
            // #230 同款：织造线引擎级权限门 + 仅环境门执行器（防双弹窗）
            toolExecutor = loomToolExecutor,
            memory = loomMemory,
            contextProvider = codeContextProvider,
            rulesProvider = RulesProvider(),
            textKernel = standardTextKernel,
            permissionSource = loomPermissionSource,
            modelInfoProvider = {
                try {
                    (modelRuntime.resolve(ModelRole.PRIMARY) as? ModelRoleRouter.ResolutionResult.Success)
                        ?.primary?.profile
                        ?.let { LoomModelInfo(modelId = it.name, contextWindow = it.contextWindow) }
                } catch (t: Throwable) {
                    null // 解析失败 → 静态 128K 兑底
                }
            },
            packageCatalog = loomPackageCatalog
        )
        // 信使线（第四线，独立库 com.apex.courierkit:courier-dispatch）：端口适配
        // + 库内自有循环（收发匣 / 趟次步进机 / 勘合门 / 档房 / 示廊 / 下站前哨
        // ——全部在 apex-kit-courier 库演进，宿主仅保留薄适配层）。设置层权限
        // 规则 → 勘合则例映射：无空格模式 → 工具 id 伪动作（tool）；含空格模式
        // → 终端命令模式（bash）。权限模式 → 基线则例注入快照头部（后例覆盖
        // 语义下用户规则仍可收窄/放宽；未注入时库侧 BUILD 角色缺省全放行，
        // PLAN 只读等模式语义会被整体旁路）。
        val courierPermissionSource = CourierPermissionSnapshotSource {
            val settings = settingsRepository.agentSettings.value
            DispatchPermissionSnapshot(
                rules = courierModeBaseline(settings.permissionMode) +
                    settings.permissionRules
                        .filter { !it.pattern.contains(' ') }
                        .map { it.toCourierToolRule() },
                commandRules = settings.permissionRules
                    .filter { it.pattern.contains(' ') }
                    .map { it.toCourierToolRule() }
            )
        }
        // 技能目录（示廊清单 + 委派装载）：共享技能仓库的 coding 域技能
        val courierSkillCatalog = object : CourierSkillCatalog {
            override fun list(): List<CourierSkillDigest> =
                skillRegistry.getSkillDigests("coding").map {
                    CourierSkillDigest(id = it.id, title = it.name)
                }

            override fun load(id: String): CourierSkillPayload? {
                val skill = skillRegistry.get(id) ?: return null
                val scopes = skillRegistry.effectiveScopesOf(id)
                if ("coding" !in scopes) return null
                val manifest = skill.manifest
                return CourierSkillPayload(
                    id = manifest.id,
                    title = manifest.name,
                    instructions = manifest.promptInjection?.takeIf { it.isNotBlank() }
                        ?: manifest.description
                )
            }
        }
        // 终端命令探针：从终端类工具参数提取命令串（勘合复合命令逐段核验）
        val courierCommandProbe = CourierCommandProbe { toolId, arguments ->
            if (toolId == SHELL_TOOL_ID || toolId.contains("terminal", ignoreCase = true)) {
                extractJsonStringField(arguments, "command")
            } else {
                null
            }
        }
        val courier = CourierKitAdapter(
            runtime = modelRuntime,
            toolRegistry = toolRegistry,
            // #230 同款：信使线勘合门 + 仅环境门执行器（防双弹窗）
            toolExecutor = courierToolExecutor,
            memory = courierMemory,
            contextProvider = codeContextProvider,
            rulesProvider = RulesProvider(),
            textKernel = standardTextKernel,
            permissionSource = courierPermissionSource,
            modelInfoProvider = {
                try {
                    (modelRuntime.resolve(ModelRole.PRIMARY) as? ModelRoleRouter.ResolutionResult.Success)
                        ?.primary?.profile
                        ?.let {
                            CourierModelInfo(
                                modelId = it.name,
                                contextWindow = it.contextWindow,
                                outputLimit = it.maxOutputTokens
                            )
                        }
                } catch (t: Throwable) {
                    null
                }
            },
            skillCatalog = courierSkillCatalog,
            commandProbe = courierCommandProbe
        )
        return DualLogicCodeEngine(
            deepDive = deepDive,
            standard = standard,
            loom = loom,
            courier = courier
        )
    }

    /** 设置层规则 → 织造线规则（效应枚举一一对应）。 */
    private fun PermissionRule.toLoom(): LoomPermissionRule =
        LoomPermissionRule(
            pattern = pattern,
            effect = when (effect) {
                PermissionEffect.ALLOW -> LoomRuleEffect.ALLOW
                PermissionEffect.ASK -> LoomRuleEffect.ASK
                PermissionEffect.DENY -> LoomRuleEffect.DENY
            }
        )

    /** 设置层规则 → 标准线规则（效应枚举一一对应）。 */
    private fun PermissionRule.toStandard(): KitPermissionRule =
        KitPermissionRule(
            pattern = pattern,
            effect = when (effect) {
                PermissionEffect.ALLOW -> KitPermissionEffect.ALLOW
                PermissionEffect.ASK -> KitPermissionEffect.ASK
                PermissionEffect.DENY -> KitPermissionEffect.DENY
            }
        )

    /** 设置层规则 → 信使线工具 id 伪动作则例（无空格=工具 id / 含空格=命令模式→bash）。 */
    private fun PermissionRule.toCourierToolRule(): DispatchPermissionRule =
        DispatchPermissionRule(
            action = if (pattern.contains(' ')) "bash" else "tool",
            resource = pattern,
            effect = when (effect) {
                PermissionEffect.ALLOW -> DispatchRuleEffect.ALLOW
                PermissionEffect.ASK -> DispatchRuleEffect.ASK
                PermissionEffect.DENY -> DispatchRuleEffect.DENY
            }
        )

    /**
     * 设置层权限模式 → 信使线基线则例（注入快照头部，后例覆盖语义下
     * 用户规则仍可收窄/放宽）。映射口径与标准线/织造线对齐：
     * - BYPASS → 全放行（含 .env 读取——与 Agent 线 BYPASS 语义一致）；
     * - DEFAULT → 编辑 / 终端 / 网络勘验（读免确认）；
     * - ACCEPT_EDITS → 编辑放行、终端仍勘验；
     * - PLAN → 编辑 / 写入 / 终端拒付（只读巡视，`edit * deny` 压轴触发
     *   工具面整枝剪除）。
     */
    private fun courierModeBaseline(mode: PermissionMode): List<DispatchPermissionRule> =
        when (mode) {
            PermissionMode.BYPASS -> listOf(
                DispatchPermissionRule("*", "*", DispatchRuleEffect.ALLOW)
            )
            PermissionMode.DEFAULT -> listOf(
                DispatchPermissionRule("edit", "*", DispatchRuleEffect.ASK),
                DispatchPermissionRule("bash", "*", DispatchRuleEffect.ASK),
                DispatchPermissionRule("net", "*", DispatchRuleEffect.ASK)
            )
            PermissionMode.ACCEPT_EDITS -> listOf(
                DispatchPermissionRule("edit", "*", DispatchRuleEffect.ALLOW),
                DispatchPermissionRule("bash", "*", DispatchRuleEffect.ASK)
            )
            PermissionMode.PLAN -> listOf(
                DispatchPermissionRule("edit", "*", DispatchRuleEffect.DENY),
                DispatchPermissionRule("write", "*", DispatchRuleEffect.DENY),
                DispatchPermissionRule("bash", "*", DispatchRuleEffect.DENY)
            )
        }

    /** 工具参数 JSON 提取字符串字段（终端命令探针用；坏 JSON 折叠 null）。 */
    private fun extractJsonStringField(json: String, field: String): String? = try {
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(json)
        (obj as? kotlinx.serialization.json.JsonObject)
            ?.get(field)?.let { el ->
                (el as? kotlinx.serialization.json.JsonPrimitive)
                    ?.takeIf { it.isString }?.content
            }
    } catch (t: Throwable) {
        null
    }

    /** 终端执行工具 id（宿主注册表口径）。 */
    private const val SHELL_TOOL_ID = "ShellExecute"
}
