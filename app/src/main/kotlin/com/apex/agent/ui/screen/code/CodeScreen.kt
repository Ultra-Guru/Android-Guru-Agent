package com.apex.agent.ui.screen.code

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apex.agent.R
import com.apex.agent.core.codetools.tools.CodeTodoTool
import com.apex.agent.ui.component.GithubIconButton
import com.apex.agent.ui.component.GithubTokenDialog
import com.apex.agent.ui.component.SlashAutoCompleteHost
import com.apex.agent.ui.component.SlashCommandButton
import com.apex.agent.ui.component.SkillChipInputField
import com.apex.agent.ui.component.SlashMenuProvider
import com.apex.agent.ui.component.FusionCodingBanner
import com.apex.agent.ui.component.FusionCodingButton
import com.apex.agent.ui.component.rememberSlashMenuProvider
import com.apex.agent.ui.screen.agent.CODING_SCREEN_MODES
import com.apex.agent.ui.screen.agent.AgentModeSelector
import com.apex.agent.ui.screen.agent.AgentRoleSelector
import com.apex.agent.ui.screen.agent.BrainMenuButton
import com.apex.agent.ui.screen.agent.PendingPipelineCommand
import com.apex.agent.ui.screen.agent.PlanConfirmationCard
import com.apex.agent.ui.screen.agent.QuestionCard
import com.apex.agent.ui.screen.agent.ToolRef
import com.apex.agent.ui.screen.agent.ToolkitChipsRow
import com.apex.agent.ui.screen.agent.ToolkitRingButton
import com.apex.agent.core.code.standard.StandardLogicMode
import com.apex.agent.core.code.stream.StreamEntry
import com.apex.agent.core.code.stream.StreamToolCall
import com.apex.agent.core.llm.EndpointResolver
import com.apex.agent.ui.screen.agent.toolkit.OutputFormat
import com.apex.agent.ui.screen.code.editor.CodeEditorPanel
import com.apex.agent.ui.screen.code.history.CodeHistoryIconButton
import com.apex.agent.ui.screen.code.history.CodeHistorySheet
import com.apex.agent.ui.screen.code.longtask.CodeLongTaskSheet
import com.apex.agent.ui.screen.code.stream.CodeStreamTimeline
import com.apex.agent.ui.screen.code.stream.TerminalPanelState
import com.apex.agent.ui.screen.code.stream.CodeToolDetailSheet
import com.apex.agent.ui.glass.GlassCard
import com.apex.agent.ui.glass.GlassModalScrim
import com.apex.agent.ui.glass.GlassStyle
import com.apex.agent.ui.glass.glassModalBlur
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/**
 * # Code Screen — Coding 模式主屏（与 Agent 聊天屏同级别）
 *
 * #197 双工位升级后的布局：工作区条（切换/新建）→ Todo 面板（可折叠）→
 * 编辑器面板 → **胶囊时间轴** → 终端面板 → 工具/权限提问卡 → 错误条 →
 * **模式+思考选择器行（Build/Plan + 七档思考）** → 输入栏（**斜杠 +
 * GitHub + 小圆环函数调用 + 输入框 + 发送**）。
 * PLAN 模式的计划确认卡插在时间轴上方（人控门）；胶囊点击进详情弹层。
 */
@Composable
fun CodeScreen(
    viewModel: CodeViewModel,
    slashMenuProvider: SlashMenuProvider = rememberSlashMenuProvider(),
    // 「小大脑」菜单 → 配置模型：跳转设置页模型配置区（与 Agent 屏同款
    // 压栈语义，改完返回 Coding 上下文）
    onOpenSettings: () -> Unit = {},
    // v1.6 工作区浏览器全屏页（右上角文件图标 → 压栈；页面内叉号弹栈返回）
    onOpenWorkspaceBrowser: () -> Unit = {}
) {
    // #184：长任务导出需要 applicationContext（落盘 + 分享；VM 非 AndroidViewModel）
    val context = androidx.compose.ui.platform.LocalContext.current
    // E4（#2-c P1-6）：主状态流对齐全仓 117 处先例换 lifecycle 版 —— 后台/不可见
    // 期间停收集（StateFlow 无参重载语义与 collectAsState 一致，初始值取 value）。
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // v6 Coding 专家模板（模式行胶囊数据源）
    val codingRoles by viewModel.roleController.roles.collectAsStateWithLifecycle()
    val activeCodingRole by viewModel.roleController.active.collectAsStateWithLifecycle()
    val pendingAgentQuestion by viewModel.pendingAgentQuestion.collectAsStateWithLifecycle()
    val pendingCommands by viewModel.pendingCommands.collectAsStateWithLifecycle()
    val llmConfigured by viewModel.llmConfigured.collectAsStateWithLifecycle()

    // ═══ 「小大脑」模型选择数据面（用户反馈：Coding 模式此前无模型入口）═══
    // 扩展属性/函数见 CodeModelSelection.kt（God-file 预算拆分同款模式）。
    // 当前 id 从列表派生（扩展转发的是仓库同一 StateFlow 实例，收集安全）。
    val codeProfiles by viewModel.codeProfiles.collectAsStateWithLifecycle()
    val codeProviders by viewModel.codeProviders.collectAsStateWithLifecycle()
    val codeCurrentProfileId = codeProfiles.firstOrNull { it.isDefault }?.id
        ?: codeProfiles.firstOrNull()?.id
    // 输入行为设置（sendKeyBehavior：send 回车直发 / newline 回车换行）
    val uiSettings by viewModel.codeUiSettings.collectAsStateWithLifecycle()
    // 原生 reasoning 参数生效判定（与客户端能力位门控同口径：Profile 勾了
    // reasoning 或 o-series/gpt-5 模型名才随请求发 reasoning_effort ——
    // 思考选择器/指南据此出「不生效」提示，回答用户「切换是不是真实的」）
    val nativeEffortActive = remember(codeProfiles, codeCurrentProfileId) {
        val cur = codeProfiles.firstOrNull { it.id == codeCurrentProfileId }
            ?: codeProfiles.firstOrNull()
        cur?.let {
            it.capabilities.reasoning || EndpointResolver.isStrictOpenAiReasoner(it.modelId)
        } ?: false
    }

    // ═══ #197 「小圆环」工具菜单状态（Coding 工位独占）═══
    val toolkit = viewModel.toolkitStore
    val webSearchEnabled by toolkit.webSearchEnabled.collectAsStateWithLifecycle()
    val timeEnabled by toolkit.timeEnabled.collectAsStateWithLifecycle()
    val selectedFunctionIds by toolkit.selectedFunctionIds.collectAsStateWithLifecycle()
    val outputFormat by toolkit.outputFormat.collectAsStateWithLifecycle()
    val customSchema by toolkit.customSchema.collectAsStateWithLifecycle()
    val rules by toolkit.rules.collectAsStateWithLifecycle()
    val availableTools = remember { viewModel.availableTools() }

    // ═══ #197 /mcp:github 未连接信号（斜杠引导连接闭环）═══
    var showGithubConnectDialog by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        viewModel.requestGithubConnect.collect { showGithubConnectDialog = true }
    }

    // ═══ #197 模型 API 未配置浮窗（发送拦截）═══
    var showApiMissingNotice by rememberSaveable { mutableStateOf(false) }

    var showThinkingGuide by rememberSaveable { mutableStateOf(false) }
    // v1.4.10 Coding 历史对话面板（右上角对话历史入口；新会话自动归档）
    // 注：Git 面板状态已随本 PR「Git 入口去重」退役（工作区浏览器内置
    // Git 页签即全部 Git 能力），main 侧新增的历史面板状态保留。
    var showCodeHistory by rememberSaveable { mutableStateOf(false) }
    // 会话上下文查看（底部弹层；点开时拉取一次快照）
    var showContextSheet by rememberSaveable { mutableStateOf(false) }
    // 胶囊详情弹层选中项（存 id 不存对象：StreamToolCall 是不可变快照，
    // 每 25ms 批次按 index 替换新实例——存对象会冻结在点击瞬间，
    // 状态/耗时/Diff 永不更新；存 id 每次重组从活快照重查）。
    // rememberSaveable：旋转/重建后详情弹层不丢（String? 可入 Bundle）。
    var selectedToolCallId by rememberSaveable { mutableStateOf<String?>(null) }
    // 终端面板折叠态 + 用户手动态（流水式折叠，用户需求：「终端命令等流水
    // 似输出：运行时保持展开跟随输出，完成后自动折叠成胶囊」）。
    // 语义与 ThinkingCard 的 userToggled 守卫同款：
    // - 新命令开跑（terminalBusy=true）→ 强制展开并重置手动态（新输出值得被看到）；
    // - 命令收束（terminalBusy=false）→ 未手动干预过则自动折叠（头行保留
    //   命令摘要 + exit 码供回看）；手动展开/收起过则尊重用户选择。
    var terminalCollapsed by rememberSaveable { mutableStateOf(false) }
    var terminalUserToggled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.stream.terminalBusy) {
        if (state.stream.terminalBusy) {
            terminalCollapsed = false
            terminalUserToggled = false
        } else if (!terminalUserToggled) {
            terminalCollapsed = true
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier.fillMaxSize()
        // IME insets 已由 ApexRoot 的 contentWindowInsets(systemBars ∪ ime) 统一
        // 注入——这里再叠 .imePadding() 会双重抬升（键盘弹出时输入栏被顶过高，
        // 第三轮审计对 Agent 屏的同款修复，见 ApexRoot.kt 注释）。
    ) {
        WorkspaceBar(
            onClearChat = viewModel::clearConversation,
            onOpenLongTasks = viewModel::openLongTaskCenter,
            onOpenHistory = { showCodeHistory = true },
            onOpenContext = { showContextSheet = true },
            // v1.5 右上角思考逻辑切换（深潜 = 自研 / 标准 = 标准任务循环）
            logicMode = state.logicMode,
            onSwitchLogic = viewModel::setLogicMode,
            // v1.6 工作区浏览器（文件树/预览/Git/变更产物全屏页；页面内
            // 自带 Git 能力 —— 原 CallSplit 独立 Git 入口与其重复，已移除）
            onOpenWorkspaceBrowser = onOpenWorkspaceBrowser,
            // 小大脑（LLM 模型选择）：顶栏右端空隙位 —— 与输入栏 modelMenu 插槽
            // 同一数据源与回调（providerNameOf 回调在非 @Composable 上下文触发，
            // 兜底文案预解析）。
            modelMenu = {
                val unknownProviderLabel = stringResource(R.string.chat_unknown_provider)
                BrainMenuButton(
                    profiles = codeProfiles,
                    currentProfileId = codeCurrentProfileId ?: "",
                    providerNameOf = { providerId ->
                        codeProviders.firstOrNull { it.id == providerId }?.displayName
                            ?: unknownProviderLabel
                    },
                    onSelectProfile = { viewModel.selectCodeModel(it) },
                    onParamsChanged = { t, p, m -> viewModel.updateCodeModelParams(t, p, m) },
                    onConfigure = onOpenSettings
                )
            }
        )

        if (state.todos.isNotEmpty()) {
            TodoPanel(todos = state.todos)
        }

        // v1.0 #154：编辑器面板——当前文件预览（行号/着色/行点击回填 @file:line）
        state.editorFilePath?.let { editorPath ->
            CodeEditorPanel(
                filePath = editorPath,
                file = state.editorFile,
                isLoading = state.editorLoading,
                errorText = state.editorError,
                onClose = viewModel::closeEditor,
                onLineClick = { line -> viewModel.insertAtRef("@$editorPath:$line") },
                onDismissError = viewModel::dismissEditorError
            )
        }

        // ═══ #197 PLAN 模式计划确认卡（人控门；勾选/重排后确认执行）═══
        if (state.awaitingPlanConfirmation && state.plan != null) {
            PlanConfirmationCard(
                plan = state.plan!!,
                onConfirm = { enabledSteps, order ->
                    viewModel.confirmPlan(true, enabledSteps, order)
                },
                onReject = { viewModel.confirmPlan(false) }
            )
        }

        // ═══ v5 玻璃悬浮层重构（Agent 屏同款 overlay 模式；kyant0/backdrop 引擎）═══
        // 时间轴 = 玻璃采样源（layerBackdrop）；底部栈（提问卡/错误条/
        // 模式行/输入栏）悬浮于时间轴之上 —— 输入栏 GlassCard(Floating) 从
        // 时间轴获得真实 backdrop 采样 + blur + lens 折射（此前 Coding 屏零玻璃：
        // 死色输入条 + 流式结论裸铺在背景上）。lowerStackInsetPx 动态测量悬浮栈高度，
        // 时间轴 contentPadding 补偿，最后一条不被遮挡。
        // 终端尾窗已内联进时间轴末尾（2026-10 用户反馈「跟随流水式输出执行，
        // 不要悬浮在输入框上」）—— 不再占据悬浮栈、不遮挡卡片，随输出贴尾。
        val streamBackdrop = rememberLayerBackdrop()
        var lowerStackInsetPx by remember { mutableIntStateOf(0) }
        val lowerStackInsetDp = with(LocalDensity.current) { lowerStackInsetPx.toDp() }
        // 内联终端胶囊状态：活跃 BASH 或有历史输出即渲染（时间轴末条）
        val terminalState =
            if (state.stream.activeTerminalCallId != null || state.stream.terminalContent.isNotBlank()) {
                TerminalPanelState(
                    content = state.stream.terminalContent,
                    activeCommand = state.stream.terminalCommandSummary.ifBlank { null },
                    busy = state.stream.terminalBusy,
                    exitCode = state.stream.terminalExitCode,
                    collapsed = terminalCollapsed
                )
            } else {
                null
            }
        Box(modifier = Modifier.weight(1f)) {
            CodeStreamTimeline(
                snapshot = state.stream,
                isStreaming = state.isRunning,
                bottomInset = lowerStackInsetDp,
                backdrop = streamBackdrop,
                onToolClick = { call -> selectedToolCallId = call.id },
                terminal = terminalState,
                onToggleTerminalCollapse = {
                    terminalUserToggled = true
                    terminalCollapsed = !terminalCollapsed
                },
                // ═══ 玻璃幕帘（2026-10 用户反馈）：提问卡挂起时时间轴整体模糊 ═══
                // 液态玻璃的 backdrop 采样存在盲区（动画图层/采样时序差），盲区
                // 文字会以锐利形态透过霜面与卡内内容叠字 —— 被覆盖内容自身先
                // blur（修饰序在 layerBackdrop 外层，采样源不失真），屏幕透出的
                // 只能是模糊像素。
                modifier = Modifier.glassModalBlur(pendingAgentQuestion != null)
            )

            // ═══ 模态幕帘：scrim 兜底（API < 31 无 blur）+ 点击拦截（门控必须先作答）═══
            if (pendingAgentQuestion != null) {
                GlassModalScrim(modifier = Modifier.matchParentSize())
            }

            // ═══ 底部悬浮栈：悬浮于时间轴之上（backdrop 采样前提）═══
            // （终端尾窗已内联进时间轴 —— 此栈只剩提问卡/错误条/目标卡/
            // 选择器行/输入栏，高度回落，时间轴可见区变大）
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .onSizeChanged { lowerStackInsetPx = it.height }
            ) {
                // v1.0 #155：工具/权限门的结构化提问卡（与 AskUser 的纯文本对话框并存；
                // 工具执行已挂起，用户必须作答才能继续）
                pendingAgentQuestion?.let { question ->
                    QuestionCard(
                        question = question,
                        onAnswer = { optionIds, customText ->
                            viewModel.answerAgentQuestion(optionIds, customText)
                        },
                        onCancel = viewModel::cancelAgentQuestion,
                        backdrop = streamBackdrop
                    )
                }

                state.error?.let { err ->
                    // #209：运行失败类错误（errorRetriable）提供一键重试；运行中不重复触发。
                    // v6 玻璃接线：错误条从实色 errorContainer 改 GlassCard 真采样 ——
                    // accent=error 保错误语义在玻璃材质上仍可辨。
                    ErrorBar(
                        message = err,
                        backdrop = streamBackdrop,
                        onRetry = if (state.errorRetriable && !state.isRunning) viewModel::retryLastRun else null,
                        onDismiss = viewModel::dismissError
                    )
                }

                // ═══ v3 GOAL 目标状态卡（输入栏上方；目标存在即显示——无论
                // ACTIVE/ACHIEVED/STOPPED、切走模式也显示，可随时停止/重启；
                // 实现在 GoalSetupSheet.kt 同文件，与 Loop 状态卡同款形态）═══
                state.goalState?.let { goalState ->
                    GoalStatusCard(
                        state = goalState,
                        onStop = viewModel::stopActiveGoal,
                        onResume = viewModel::resumeGoal
                    )
                }

                // ═══ 任务排队卡（输入栏上方）：运行中发出的第二条任务 ═══
                // 等待发送（完成后自动接力）/ 等待排队（上一任务失败，需点
                // 「开始排队」确认）——数据与动作全在 CodeViewModel。
                state.queuedTask?.let { queued ->
                    QueuedTaskCard(
                        task = queued,
                        onStart = viewModel::startQueuedTask,
                        onCancel = viewModel::cancelQueuedTask,
                        backdrop = streamBackdrop
                    )
                }

                // ═══ #197 模式 + 思考档位选择器行（Build/Plan 双档 + 七档思考）═══
                //（GOAL 切入的弹层/深潜线保障在 VM setMode → onEnterGoalMode 单点，
                // 此处保持纯方法引用——选择器与 init 恢复共用同一路径）
                // v6 前插专家模板胶囊（全栈置顶/Git/Android/各语言专家——
                // 选择即持久化 codeActiveRoleId，经 roleController 落引擎人设）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 2.dp)
                ) {
                    AgentRoleSelector(
                        current = activeCodingRole,
                        roles = codingRoles,
                        onSelect = viewModel.roleController::select
                    )
                    AgentModeSelector(
                        current = state.mode,
                        onSelect = viewModel::setMode,
                        modes = CODING_SCREEN_MODES
                    )
                    CodeThinkingSelector(
                        current = state.thinkingLevel,
                        adaptiveDecision = state.adaptiveDecision,
                        onSelect = viewModel::setThinkingLevel,
                        onOpenGuide = { showThinkingGuide = true },
                        nativeEffortActive = nativeEffortActive
                    )
                }

                CodeInputBar(
                    draft = state.inputDraft,
                    onDraftChange = viewModel::updateInputDraft,
                    isRunning = state.isRunning,
                    llmConfigured = llmConfigured,
                    onSendBlocked = { showApiMissingNotice = true },
                    workspaceMenu = {
                        CodeWorkspaceSelectorChip(
                            active = state.activeWorkspace,
                            workspaces = state.workspaces,
                            onSelect = viewModel::switchWorkspace,
                            onCreate = viewModel::createWorkspace,
                            onImportFolder = { path -> viewModel.importWorkspaceFromFolder(path) },
                            onDelete = viewModel::deleteWorkspace
                        )
                    },
                    onSend = { text ->
                        // v3 GOAL：GOAL 模式且尚无活动目标 → 首条消息转目标
                        // 设定弹层（草稿预填），不直接发送；返回 false（非 GOAL/
                        // 已有目标/斜杠指令）照常走发送管线。
                        if (!viewModel.maybeGoalFirstSend(text)) {
                            viewModel.sendMessage(text)
                        }
                    },
                    onAbort = viewModel::abort,
                    slashMenuProvider = slashMenuProvider,
                    onAddPendingCommand = { viewModel.addPendingCommand(it) },
                    pendingCommands = pendingCommands,
                    onChipsChange = { viewModel.setPendingCommands(it) },
                    backdrop = streamBackdrop,
                    githubTokenManager = viewModel.githubTokenManager,
                    toolkit = toolkit,
                    // 回车键行为（与 Agent 屏同源设置；此前 Coding 屏落默认值，
                    // Enter 被消费却无动作 —— 换不了行的直接根因）
                    sendKeyBehavior = uiSettings.sendKeyBehavior,
                    // 「小大脑」模型菜单插槽（Agent 屏同款组件；providerNameOf
                    // 回调在非 @Composable 上下文触发 —— 兜底文案预解析）
                    modelMenu = {
                        val unknownProviderLabel = stringResource(R.string.chat_unknown_provider)
                        BrainMenuButton(
                            profiles = codeProfiles,
                            currentProfileId = codeCurrentProfileId ?: "",
                            providerNameOf = { providerId ->
                                codeProviders.firstOrNull { it.id == providerId }?.displayName
                                    ?: unknownProviderLabel
                            },
                            onSelectProfile = { viewModel.selectCodeModel(it) },
                            onParamsChanged = { t, p, m -> viewModel.updateCodeModelParams(t, p, m) },
                            onConfigure = onOpenSettings
                        )
                    },
                    // 功能聚合（16 项真实注入；状态由 NeonOrbButton 内部
                    // 经 store 自收集 —— 这里只交挂载点，与 modelMenu 同款插槽）
                    neonOrbMenu = {
                        com.apex.agent.ui.screen.code.neon.NeonOrbButton(
                            store = viewModel.neonOrbStore
                        )
                    },
                    toolkitState = ToolkitUiState(
                        webSearchEnabled = webSearchEnabled,
                        timeEnabled = timeEnabled,
                        selectedFunctionIds = selectedFunctionIds,
                        availableTools = availableTools,
                        outputFormat = outputFormat,
                        customSchema = customSchema,
                        rules = rules,
                        exposeAllTools = toolkit.exposeAllTools.collectAsStateWithLifecycle().value
                    )
                )
            }
        }
    }

        // ═══ #197 模型 API 未配置浮窗（同 Agent 屏，发送拦截时弹出）═══
        if (showApiMissingNotice) {
            ApiMissingFloatingNotice(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .zIndex(10f),
                onOpenSettings = { showApiMissingNotice = false },
                onDismiss = { showApiMissingNotice = false }
            )
        }
    }

    // v1.0 #155：工具/权限门的结构化提问已在上方 Column 内渲染
    state.pendingQuestion?.let { question ->
        PendingQuestionDialog(
            question = question,
            onSubmit = viewModel::submitUserInput
        )
    }

    // coding 七档思考指南（ModalBottomSheet）：阶梯总表 + 逐档卡片，可直切档
    if (showThinkingGuide) {
        CodeThinkingGuideSheet(
            currentLevel = state.thinkingLevel,
            onDismiss = { showThinkingGuide = false },
            onSelect = viewModel::setThinkingLevel,
            nativeEffortActive = nativeEffortActive
        )
    }

    // ═══ v3 GOAL 目标设定弹层（切到 GOAL 无活动目标 / GOAL 模式首条消息
    // 拦截打开；默认轮次读设置页 goalMaxRounds；开始 → startGoalModeGoal
    //（关闭弹层 + 首条提示 = 打开时携带的草稿））═══
    if (state.showGoalSetup) {
        GoalSetupSheet(
            initialText = state.goalSetupDraft,
            defaultMaxRounds = viewModel.goalDefaultMaxRounds(),
            onDismiss = viewModel::dismissGoalSetup,
            onStart = { statement, criteria, maxRounds ->
                viewModel.startGoalModeGoal(statement, criteria, maxRounds, state.goalSetupDraft)
            }
        )
    }

    // ═══ #197 GitHub 连接对话框（斜杠 /mcp:github 未连接信号）═══
    // v3 S3：onSuccess 第三参 = 对话框归一化后的默认仓库（null = 未填/无法
    // 识别，不改动既有值）
    if (showGithubConnectDialog) {
        GithubTokenDialog(
            onDismiss = { showGithubConnectDialog = false },
            onSubmit = { token -> viewModel.githubTokenManager.validateToken(token) },
            onSuccess = { token, username, normalizedRepo ->
                viewModel.githubTokenManager.saveToken(token, username)
                normalizedRepo?.let {
                    viewModel.githubTokenManager.saveDefaultRepoCanonical(it)
                }
                showGithubConnectDialog = false
            }
        )
    }

    // 胶囊详情弹层（分族路由：BASH→终端全文 / EDIT→Diff / GREP→命中列表）
    // —— 从活快照按 id 重查（含折叠进轮次卡内的胶囊），状态实时翻转
    val selectedCall: StreamToolCall? = selectedToolCallId?.let { id ->
        state.stream.entries.asSequence().mapNotNull { e ->
            when (e) {
                is StreamEntry.ToolCapsuleEntry -> e.call
                is StreamEntry.VerifyCycleEntry -> e.calls.firstOrNull { it.id == id }
                else -> null
            }
        }.firstOrNull { it.id == id }
    }
    selectedCall?.let { call ->
        CodeToolDetailSheet(
            call = call,
            terminalFallback = viewModel.terminalLogOf(call.id),
            onDismiss = { selectedToolCallId = null }
        )
    }

    // v1.2 长任务中心（ModalBottomSheet）：任务记录 + 任务模板 + 档位效能三页签
    CodeLongTaskSheet(
        visible = state.longTaskSheetVisible,
        records = state.longTasks,
        loading = state.longTaskLoading,
        stats = state.thinkingStats,
        onDismiss = viewModel::closeLongTaskCenter,
        onCopy = viewModel::copyTask,
        onRelaunch = viewModel::relaunchTask,
        onResume = viewModel::resumeTask,
        onDelete = viewModel::deleteLongTask,
        onCompareWithParent = viewModel::compareWithParent,
        onStartTemplate = viewModel::startFromTemplate,
        onExport = { id -> viewModel.exportLongTask(context, id) }
    )

    // v1.4.10 历史对话（ModalBottomSheet）：新会话自动归档的会话列表（恢复/删除）
    if (showCodeHistory) {
        CodeHistorySheet(viewModel = viewModel, onDismiss = { showCodeHistory = false })
    }
    if (showContextSheet) {
        // 打开时拉取一次快照（不进状态流 —— 查看是即时动作，会话推进后重开即刷新）
        val contextSnapshot = remember(showContextSheet) { viewModel.contextSnapshot() }
        CodeContextSheet(snapshot = contextSnapshot, onDismiss = { showContextSheet = false })
    }
}

// ═══ 工作区条 ═══

@Composable
private fun WorkspaceBar(
    onClearChat: () -> Unit,
    onOpenLongTasks: () -> Unit,
    onOpenHistory: () -> Unit = {},
    // 会话上下文查看（当前激活逻辑线的消息序列 + 注入段 + token 统计）
    onOpenContext: () -> Unit = {},
    logicMode: StandardLogicMode = StandardLogicMode.DEEP_DIVE,
    onSwitchLogic: (StandardLogicMode) -> Boolean = { false },
    onOpenWorkspaceBrowser: () -> Unit = {},
    // 「小大脑」模型菜单插槽：顶栏右端空隙位常驻可见（用户反馈补齐 ——
    // 输入栏工具行的模型菜单位于横向滚动末位，小屏常被滚出屏外难发现）
    modelMenu: @Composable () -> Unit = {}
) {
    // v1.5 切换被拒提示（运行中拒绝；2s 自清，行内告警形态）
    var logicSwitchBlocked by remember { mutableStateOf(false) }
    LaunchedEffect(logicSwitchBlocked) {
        if (logicSwitchBlocked) {
            delay(2000)
            logicSwitchBlocked = false
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            // v1.5 思考逻辑切换（右上角：深潜 = 自研七档 / 标准 = 标准任务循环）
            CodeLogicModeSelector(
                current = logicMode,
                onSwitchBlocked = logicSwitchBlocked,
                onSelect = { mode ->
                    val accepted = onSwitchLogic(mode)
                    if (!accepted) logicSwitchBlocked = true
                    accepted
                }
            )

            Spacer(Modifier.width(4.dp))

            // ═══ v1.6 工作区浏览器（右上角：文件树/预览/变更产物全屏页）═══
            // FolderOpen 图标即「浏览工作区」；页面内右上角叉号返回 Coding
            IconButton(onClick = onOpenWorkspaceBrowser, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                Icon(
                    Icons.Outlined.FolderOpen,
                    contentDescription = stringResource(R.string.code_workspace_browser_cd),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }

            // 会话上下文查看（当前激活线的消息序列 + 注入段 + token 统计；
            // UI-012：48dp 触区红线）
            IconButton(onClick = onOpenContext, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                Icon(
                    Icons.Outlined.Visibility,
                    contentDescription = stringResource(R.string.code_context_open),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }

            // v1.2 长任务中心入口（记录/模板两页签的 ModalBottomSheet）
            // UI-012：48dp 触区红线（原 28dp）
            IconButton(onClick = onOpenLongTasks, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                Icon(
                    Icons.Outlined.History,
                    contentDescription = stringResource(R.string.code_longtask_open),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }

            // v1.4.10 历史对话入口（新会话自动归档；与长任务中心相邻语义区分：
            // History=任务记录，Chat=对话历史）
            CodeHistoryIconButton(onClick = onOpenHistory)

            // 新会话：图标按钮（对齐 Agent 屏 History 邻钮；UI-012：48dp 触区红线，原 28dp）
            IconButton(onClick = onClearChat, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = stringResource(R.string.code_clear_chat),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }

            // ═══ 小大脑（LLM 模型选择）：右端空隙位（用户反馈补齐）═══
            // Spacer 占位把模型按钮推到行尾 —— 与输入栏工具行的 modelMenu 同一数据源
            //（选择即 selectCodeModel 热生效）；顶栏位常驻可见，不需横向滚动寻找。
            Spacer(Modifier.weight(1f))
            modelMenu()
        }
    }
}

// ═══ Todo 面板 ═══

@Composable
private fun TodoPanel(todos: List<CodeTodoTool.Todo>) {
    var expanded by remember { mutableStateOf(true) }
    val done = todos.count { it.status == "completed" }

    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
            ) {
                Icon(
                    Icons.Default.Checklist,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.code_todo_progress, done, todos.size),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                todos.forEach { todo ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 1.dp)
                    ) {
                        val mark = when (todo.status) {
                            "completed" -> "✔"
                            "in_progress" -> "▶"
                            "cancelled" -> "✖"
                            else -> "○"
                        }
                        val color = when (todo.status) {
                            "completed" -> MaterialTheme.colorScheme.primary
                            "in_progress" -> MaterialTheme.colorScheme.tertiary
                            "cancelled" -> MaterialTheme.colorScheme.outline
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Text(mark, color = color, style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (todo.priority == "high") "${todo.content} (!)" else todo.content,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (todo.status == "cancelled") MaterialTheme.colorScheme.outline
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

// ═══ 消息流 ═══
// v 胶囊流式：旧 CodeMessageList/CodeMessageItem/ToolCard/DiffOutput 渲染族
// 已由 stream 包（CodeStreamTimeline + 卡片族）整体取代——Diff 着色/
// 等宽回放/流式态全部升级为胶囊 + 详情弹层分族路由形态。

// ═══ 输入栏（#197 升级：斜杠 + GitHub + 小圆环函数调用 + 输入框 + 发送）═══

/** 小圆环状态包（避免 CodeInputBar 参数爆炸）。 */
private data class ToolkitUiState(
    val webSearchEnabled: Boolean,
    val timeEnabled: Boolean,
    val selectedFunctionIds: Set<String>,
    val availableTools: List<ToolRef>,
    val outputFormat: OutputFormat,
    val customSchema: String,
    val rules: List<com.apex.agent.ui.screen.agent.toolkit.ChatRule>,
    val exposeAllTools: Boolean
)

@Composable
private fun CodeInputBar(
    draft: String,
    onDraftChange: (String) -> Unit,
    isRunning: Boolean,
    llmConfigured: Boolean,
    onSendBlocked: () -> Unit,
    onSend: (String) -> Unit,
    onAbort: () -> Unit,
    // 工作区选择（2026-10 用户需求：迁入输入栏工具行首位；顶栏左上角
    // 选择器已移除）—— 数据/回调由 CodeScreen 组合层闭合
    workspaceMenu: @Composable () -> Unit = {},
    slashMenuProvider: SlashMenuProvider,
    onAddPendingCommand: (PendingPipelineCommand) -> Unit,
    pendingCommands: List<PendingPipelineCommand>,
    onChipsChange: (List<PendingPipelineCommand>) -> Unit,
    backdrop: Backdrop,
    githubTokenManager: com.apex.agent.github.GithubTokenManager,
    toolkit: com.apex.agent.ui.screen.agent.toolkit.ChatToolkitStore,
    // 回车键行为（send | newline，设置页可配；Agent 屏同款消费）
    sendKeyBehavior: String = "send",
    // 「小大脑」模型菜单插槽（Agent 屏同款 BrainMenuButton；插槽化避免
    // 参数爆炸 —— 数据与回调由 CodeScreen 组合层闭合）
    modelMenu: @Composable () -> Unit = {},
    // 「功能聚合」插槽（16 项能力面板；数据由 store 自闭合 ——
    // 与 modelMenu 同款插槽化，CodeScreen 只传 store 挂载点）
    neonOrbMenu: @Composable () -> Unit = {},
    toolkitState: ToolkitUiState
) {
    // ═══ v5 玻璃输入栏（Agent 屏同款）：Floating 档 + 顶部双角圆角，悬浮栈
    // 布局使本卡悬浮于时间轴之上 → backdrop 采样获得真实玻璃材质。═══
    GlassCard(
        backdrop = backdrop,
        style = GlassStyle.Floating,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            // ═══ v5：技能 chip 已内联进输入框（SkillChipInputField），独立的
            // 胶囊行移除 —— 不再出现「胶囊行叠在输入框上方」的重叠观感。═══

            // ═══ 小圆环状态标签行（搜索/时间/函数/格式/规则芯片，可单独关闭）═══
            ToolkitChipsRow(
                webSearchEnabled = toolkitState.webSearchEnabled,
                timeEnabled = toolkitState.timeEnabled,
                selectedFunctionIds = toolkitState.selectedFunctionIds,
                toolNameOf = { id -> toolkitState.availableTools.firstOrNull { it.id == id }?.name ?: id },
                outputFormat = toolkitState.outputFormat,
                enabledRulesCount = toolkitState.rules.count { it.enabled },
                onCloseWebSearch = { toolkit.setWebSearchEnabled(false) },
                onCloseTime = { toolkit.setTimeEnabled(false) },
                onRemoveFunction = { toolkit.toggleFunction(it) },
                onCloseFormat = { toolkit.setOutputFormat(OutputFormat.NONE) },
                onDisableAllRules = { toolkitState.rules.filter { it.enabled }.forEach { r -> toolkit.setRuleEnabled(r.id, false) } }
            )

            // ═══ FUSION 王冠激活态横幅（Coding 工位钦定位，未激活不占位）═══
            FusionCodingBanner()

            // ═══ 工具栏行：工作区 + 斜杠 + 王冠 + GitHub + 小圆环（横向滚动）═══
            // 工作区选择器占首位（2026-10 用户需求：输入栏内未被占用的位置；
            // 真实文件夹选择 + 默认整个系统，见 CodeWorkspaceSelector.kt）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            ) {
                workspaceMenu()

                // ═══ / 斜杠指令按钮（coding 域：开发技能 + coding 工位 MCP）═══
                // v5 多选：流水线条目选中保持展开（继续多选）+ ✓ 勾选态；
                // 再点一次已选条目 = 摘除。返回 false（普通指令）才收起。
                SlashCommandButton(
                    slashMenuProvider = slashMenuProvider,
                    scope = "coding",
                    isSelected = { item ->
                        PendingPipelineCommand.fromCommand(item.command, item.label)
                            ?.let { c -> pendingCommands.any { it.type == c.type && it.id == c.id } }
                            ?: false
                    },
                    onItemSelected = { item ->
                        val capsule = PendingPipelineCommand.fromCommand(item.command, item.label)
                        if (capsule != null) {
                            val exists = pendingCommands.any { it.type == capsule.type && it.id == capsule.id }
                            if (exists) {
                                onChipsChange(pendingCommands.filterNot { it.type == capsule.type && it.id == capsule.id })
                            } else {
                                onAddPendingCommand(capsule)
                            }
                            true // 流水线条目：保持展开，继续多选
                        } else {
                            val merged = if (draft.isBlank()) item.command
                            else draft.trimEnd() + " " + item.command
                            onDraftChange(merged)
                            false
                        }
                    }
                )

                // ═══ 王冠：FUSION 工具聚合体系入口（Coding 工位钦定位，
                // 选包激活 + chip 回灌输入框 + /fusion 强制使用）═══
                FusionCodingButton(onBundleChip = onAddPendingCommand)

                // ═══ GitHub 连接按钮（#197 从 Agent 屏迁入）═══
                GithubIconButton(tokenManager = githubTokenManager)

                // ═══ 小圆环：函数调用/搜索/时间/结构化输出/规则（#197 迁入）═══
                ToolkitRingButton(
                    webSearchEnabled = toolkitState.webSearchEnabled,
                    timeEnabled = toolkitState.timeEnabled,
                    selectedFunctionIds = toolkitState.selectedFunctionIds,
                    availableTools = toolkitState.availableTools,
                    outputFormat = toolkitState.outputFormat,
                    customSchema = toolkitState.customSchema,
                    rules = toolkitState.rules,
                    exposeAllTools = toolkitState.exposeAllTools,
                    onToggleWebSearch = { toolkit.setWebSearchEnabled(it) },
                    onToggleTime = { toolkit.setTimeEnabled(it) },
                    onToggleFunction = { toolkit.toggleFunction(it) },
                    onToggleExposeAllTools = { toolkit.setExposeAllTools(it) },
                    onSelectFormat = { toolkit.setOutputFormat(it) },
                    onSetCustomSchema = { toolkit.setCustomSchema(it) },
                    onUpsertRule = { toolkit.upsertRule(it) },
                    onDeleteRule = { toolkit.deleteRule(it) },
                    onToggleRule = { id, enabled -> toolkit.setRuleEnabled(id, enabled) }
                )

                // ═══ 功能聚合：能力面板入口（16 项真实注入；neon/NeonOrbButton.kt）═══
                // 与小圆环同排但独立体系：时间注入/结构化输出/网络搜索等
                // 三组能力聚合，面板开关 → NeonOrbStore → 每轮发送注入。
                neonOrbMenu()

                // ═══ 小大脑：模型切换 + 参数调节 + 配置跳转（用户反馈补齐）═══
                // Coding 工位此前无模型入口 —— 共享 LlmClient 恒用默认 Profile；
                // 现与 Agent 屏同款（AgentChatScreen 输入栏同位置）。
                modelMenu()
            }
            Spacer(Modifier.height(4.dp))

            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    // ═══ v5 技能 chip 输入框（与 Agent 屏同款组件）═══
                    // 回车行为接线（修复：此前未传 sendKeyBehavior/onSend ——
                    // Enter 被 IME_ACTION_SEND 消费后回调为空，既不发送也插不进
                    // 换行，「只能看到一行输入」的直接根因）。逻辑与发送按钮
                    // 同口径：未配置拦截 → 空内容忽略 → GOAL 首条转设定弹层。
                    SkillChipInputField(
                        value = draft,
                        onValueChange = onDraftChange,
                        chips = pendingCommands,
                        onChipsChange = onChipsChange,
                        placeholder = stringResource(
                            if (llmConfigured) R.string.code_input_hint
                            else R.string.code_input_hint_no_api
                        ),
                        sendKeyBehavior = sendKeyBehavior,
                        onSend = {
                            if (!llmConfigured) {
                                onSendBlocked()
                                return@SkillChipInputField
                            }
                            if (draft.isNotBlank() || pendingCommands.isNotEmpty()) {
                                onSend(draft)
                            }
                        }
                    )
                    // / 实时联想（coding 域）
                    SlashAutoCompleteHost(
                        inputText = draft,
                        slashMenuProvider = slashMenuProvider,
                        scope = "coding",
                        onItemSelected = { item ->
                            val capsule = PendingPipelineCommand.fromCommand(item.command, item.label)
                            if (capsule != null) {
                                onAddPendingCommand(capsule)
                            } else {
                                onDraftChange(item.command)
                            }
                        }
                    )
                }
                if (isRunning) {
                    // ═══ 任务排队入口：运行中输入了第二条任务时并列「发送（入队）═══
                    // + 停止」——发送走 sendMessage 的排队分支（文本 + chip 快照
                    // 入队，输入框上方排队卡接管展示），不再需要等停止后重输。
                    if (draft.isNotBlank() || pendingCommands.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                if (!llmConfigured) {
                                    onSendBlocked()
                                    return@IconButton
                                }
                                onSend(draft)
                            }
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = stringResource(R.string.code_send),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    IconButton(onClick = onAbort) {
                        Icon(
                            Icons.Default.Stop,
                            contentDescription = stringResource(R.string.code_stop),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                } else {
                    IconButton(
                        onClick = {
                            // #197 模型 API 未配置：拦截发送并弹浮窗
                            if (!llmConfigured) {
                                onSendBlocked()
                                return@IconButton
                            }
                            if (draft.isNotBlank() || pendingCommands.isNotEmpty()) {
                                onSend(draft)
                            }
                        },
                        enabled = draft.isNotBlank() || pendingCommands.isNotEmpty()
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.code_send),
                            tint = if (draft.isNotBlank() || pendingCommands.isNotEmpty()) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * #197 模型 API 未配置浮窗（Coding 屏版本；同 Agent 屏语义）。
 * 4.5s 自动消散；Coding 屏无直达设置页的路由参数——提示用户去抽屉设置。
 */
@Composable
private fun ApiMissingFloatingNotice(
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(4_500)
        onDismiss()
    }
    androidx.compose.animation.AnimatedVisibility(
        visible = true,
        enter = androidx.compose.animation.slideInVertically(initialOffsetY = { -it }) +
            androidx.compose.animation.fadeIn(),
        modifier = modifier.padding(top = 8.dp)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shape = RoundedCornerShape(14.dp),
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(horizontal = 12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Icon(Icons.Default.ErrorOutline, contentDescription = null, modifier = Modifier.size(20.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.chat_api_missing_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.chat_api_missing_body),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                TextButton(onClick = { onOpenSettings(); onDismiss() }) {
                    Text(stringResource(R.string.code_api_missing_got_it))
                }
            }
        }
    }
}

// ═══ 对话框 ═══

@Composable
private fun ErrorBar(
    message: String,
    backdrop: Backdrop?,
    onRetry: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    GlassCard(
        backdrop = backdrop,
        style = GlassStyle.Floating,
        shape = RoundedCornerShape(12.dp),
        accent = MaterialTheme.colorScheme.error,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Icon(
                Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            // #209：可恢复错误提供「重试」（复用错误条既有 action 按钮模式；
            // 文案复用 chat_retry，与 Agent 屏 RetryChip 同词）。
            if (onRetry != null) {
                TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.chat_retry))
                }
            }
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.code_dismiss))
            }
        }
    }
}

@Composable
private fun PendingQuestionDialog(
    question: String,
    onSubmit: (String) -> Unit
) {
    // rememberSaveable：旋转/重建后已输入的回答不丢（String 可入 Bundle）。
    var answer by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { },
        title = { Text(stringResource(R.string.code_question_title)) },
        text = {
            Column {
                Text(question, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = answer,
                    onValueChange = { answer = it },
                    placeholder = { Text(stringResource(R.string.code_question_hint)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            val defaultYes = stringResource(R.string.code_question_default_yes)
            TextButton(
                onClick = { onSubmit(answer.ifBlank { defaultYes }) }
            ) { Text(stringResource(R.string.code_question_submit)) }
        }
    )
}

