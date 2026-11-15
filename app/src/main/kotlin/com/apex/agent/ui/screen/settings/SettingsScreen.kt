@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.apex.agent.ui.screen.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apex.agent.R
import com.apex.agent.core.llm.ModelProfile
import com.apex.agent.ui.screen.code.discardPendingSkinChange
import com.apex.agent.permission.PermissionSettingsSection
import kotlinx.coroutines.launch

/**
 * 设置中心 v2 —— 抽屉两级分层导航（设置页面完全重构第一阶段）。
 *
 * 结构（对齐浏览器式设置中心惯例）：
 * - 总抽屉点「设置」进入本页时，总抽屉收回（根层 navigateToTopLevel 语义），
 *   **设置抽屉自动打开**（独立 ModalNavigationDrawer，与总抽屉互不干扰）；
 * - 设置抽屉第一列 = 一级大分类（模型配置 / 工作台 / 集成与扩展 / 终端 /
 *   界面外观 / 数据与备份）；
 * - 点击分类展开二级叶子（手风琴，同一时刻仅一个分类展开）；
 * - 点击叶子（如模型配置 → 配置 / 参数）→ 抽屉收回 → 内容区渲染对应页面；
 * - 顶栏汉堡钮可随时重开设置抽屉切换页面（页面状态保留）。
 *
 * 第二阶段（本 PR）：浏览器式多标签 —— 打开的页面以标签形式排在
 * 最上方，点叉号关闭并回退到相邻标签 / 设置抽屉（全部关闭时）。
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onBack: () -> Unit = {}
) {
    // ═══ v1.2/v1.3 部件外观（PartSkin spec）：会话皮肤状态机（active/pending）
    // 与「立即新建对话并应用」动作来自 CodeViewModel —— 单 Activity 架构下
    // hiltViewModel() 解析同一 ViewModelStore，与 CodeScreen 共享同一实例。═══
    // BUG-12(Spec02 · 待 SkinActivationRepository 抽取):hiltViewModel() 默认
    // 作用域是 NavBackStackEntry,跨目的地不保证与 Code 屏共享 —— clearConversation/
    // discardPendingSkinChange 可能作用于孤儿实例。完整修复 = 抽皮肤激活单例仓 +
    // 仪器测试验证「设置改皮肤 → Code 屏生效」,工程独立于本批(风险见 Spec §6)。
    val codeViewModel: com.apex.agent.ui.screen.code.CodeViewModel = hiltViewModel()
    val codeState by codeViewModel.uiState.collectAsStateWithLifecycle()
    // B0-BUG-01:配置损坏横幅(持久展示;损坏域写回已禁用,原始数据保留在 corrupt_backup_*)
    val corruptedDomains by viewModel.dataCorruption.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val providers by viewModel.providers.collectAsStateWithLifecycle()
    val roles by viewModel.roles.collectAsStateWithLifecycle()
    val agent by viewModel.agentSettings.collectAsStateWithLifecycle()

    // B1-BUG-10:选中档案/弹窗开关 saveable(旋转/进程重建/切标签往返不丢)
    var selectedId by rememberSaveable { mutableStateOf(viewModel.defaultProfile.id) }
    val selected = profiles.firstOrNull { it.id == selectedId } ?: profiles.firstOrNull()

    // ═══ 导航状态（rememberSaveable：旋转/进程重建不丢）═══
    // openTabs：浏览器式标签列表（叶子 route，按打开顺序；Saver 转 NUL 分隔串落盘）
    // activeTab：当前激活标签 route（空串 = 无标签，设置抽屉主导）
    // expandedCategory：抽屉展开中的一级分类（默认模型配置）
    var openTabs by rememberSaveable(stateSaver = OpenTabsSaver) {
        mutableStateOf(emptyList<String>())
    }
    var activeTab by rememberSaveable { mutableStateOf("") }
    var expandedCategory by rememberSaveable { mutableStateOf<String?>(SettingsNavTree.first().route) }

    // 各标签页面状态隔离（切标签往返：滚动位置/展开态保留 —— 与根层
    // ApexRoot 的 destinationStateHolder 同款模式）
            // BUG-11 兑现(PR1):activeTab 已升级为稳定 page.id —— 分类重排
            // 不再改变该 key,页内状态跨重排存活。
    val pageStateHolder = rememberSaveableStateHolder()

    // 进入设置页即打开设置抽屉（总抽屉已由根层收回）
    val settingsDrawerState = rememberDrawerState(initialValue = DrawerValue.Open)
    val scope = rememberCoroutineScope()

    var showProviders by rememberSaveable { mutableStateOf(false) }
    var showRoles by rememberSaveable { mutableStateOf(false) }

    // Agent 设置统一更新入口
    // B1-BUG-05:字段级变换管道(读最新值再变换)—— 旧实现整体覆盖
    // { next } 快速连点/后台 AgentSettingSet 并发写会互相覆盖。
    val onAgent: (AgentSettings.() -> AgentSettings) -> Unit =
        { transform -> viewModel.updateAgentSettings(transform) }

    // ═══ 抽屉交互 ═══
    val openSettingsDrawer: () -> Unit = {
        scope.launch { settingsDrawerState.open() }
    }
    val onToggleCategory: (String) -> Unit = { route ->
        expandedCategory = if (expandedCategory == route) null else route
    }
    // 点击叶子：追加浏览器式标签（已开则仅激活）+ 收回设置抽屉
    val onSelectLeaf: (String) -> Unit = { route ->
        // PR1:抽屉仍列旧 SettingsNavTree(分类语境),标签统一存稳定 page.id
        val pageId = SettingsNavAliases.resolvePageId(route) ?: return@onSelectLeaf
        if (!openTabs.contains(pageId)) openTabs = openTabs + pageId
        activeTab = pageId
        expandedCategory = categoryOfLeaf(route)?.route
        scope.launch { settingsDrawerState.close() }
    }
    // B1-BUG-13:全部标签关闭 → 显示设置首页(引导态),不再自动重开抽屉
    // (旧实现重复清空状态后又弹抽屉,用户被迫再面对一次遮罩)。
    val onAllTabsClosed: () -> Unit = {
        openTabs = emptyList()
        activeTab = ""
    }
    // 关闭单个标签：关闭激活标签 → 回退相邻标签（左优先，规则见 closeSettingsTab）
    val onCloseTab: (String) -> Unit = { route ->
        val (next, nextActive) = closeSettingsTab(openTabs, activeTab, route)
        openTabs = next
        activeTab = nextActive ?: ""
        if (next.isEmpty()) onAllTabsClosed()
    }

    // ═══ 返回键链：抽屉开 → 关抽屉；有标签 → 关当前标签（同点叉号）；否则 → 出设置页 ═══
    BackHandler(enabled = settingsDrawerState.isOpen) {
        scope.launch { settingsDrawerState.close() }
    }
    // B1-BUG-09:返回链第三级 —— 无标签且抽屉关 → 真正退出设置
    // (旧实现 onBack 参数声明后从未调用,返回键在三步内走不完退出链)。
    BackHandler(enabled = !settingsDrawerState.isOpen && activeTab.isEmpty()) {
        onBack()
    }
    BackHandler(enabled = !settingsDrawerState.isOpen && activeTab.isNotEmpty()) {
        onCloseTab(activeTab)
    }

    ModalNavigationDrawer(
        drawerState = settingsDrawerState,
        // 与总抽屉同款手势纪律：禁边缘滑动误触（返回键/汉堡钮/scrim 关闭不受影响）
        gesturesEnabled = false,
        drawerContent = {
            SettingsDrawerSheet(
                expandedCategory = expandedCategory,
                // PR1:activeTab 已是 page.id —— 抽屉叶子高亮反查主旧 route
                activeLeafRoute = legacyRouteOfCompat(activeTab.ifEmpty { null }),
                onToggleCategory = onToggleCategory,
                onSelectLeaf = onSelectLeaf
            )
        }
    ) {
        Scaffold(
            // 内层 Scaffold 置零 insets：状态栏已由根 Scaffold 顶栏承担，避免双重叠加
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TopAppBar(
                    // 顶栏置零 windowInsets，避免与根 Scaffold 状态栏双重叠加
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    title = {
                        Text(
                            if (activeTab.isNotEmpty()) {
                                // PR1:page.id 标题优先,旧 leaf 兜底
                                SettingsPages.byId[activeTab]?.let { stringResource(it.titleRes) }
                                    ?: leafOf(activeTab)?.let { stringResource(it.titleRes) }
                                    ?: stringResource(R.string.settings_title)
                            } else stringResource(R.string.settings_title)
                        )
                    },
                    navigationIcon = {
                        // 页面态：汉堡钮重开设置抽屉（切换分类/叶子）
                        IconButton(onClick = openSettingsDrawer) {
                            Icon(Icons.Default.Menu, stringResource(R.string.settings_open_drawer))
                        }
                    },
                    actions = {
                    }
                )
            }
        ) { padding ->
            Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
            ) {
                if (corruptedDomains.isNotEmpty()) {
                    // BUG-01 验收:损坏横幅持久可见,不提供一键忽略
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            stringResource(R.string.settings_corrupt_banner),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
                if (activeTab.isNotEmpty()) {
                    // ═══ 浏览器式标签条：页面排在最上方（顶栏下、内容区上）═══
                    SettingsTabBar(
                        tabs = openTabs,
                        activeTab = activeTab,
                        onTabClick = { route ->
                            activeTab = route
                            expandedCategory = categoryOfLeaf(route)?.route
                        },
                        onTabClose = onCloseTab
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    )
                }
                Box(modifier = Modifier.fillMaxSize()) {
                    if (activeTab.isEmpty()) {
                        // 未选叶子：引导态（抽屉已打开覆盖其上，关闭后可见）
                        SettingsLandingHint(onOpenDrawer = openSettingsDrawer)
                    } else {
                        // 以标签 route 隔离页面状态：切标签往返不丢滚动位置/展开态
                        pageStateHolder.SaveableStateProvider(activeTab) {
                            SettingsPageRouter(
                                route = activeTab,
                                profiles = profiles,
                                selected = selected,
                                agent = agent,
                                viewModel = viewModel,
                                onAgent = onAgent,
                                onSelect = { selectedId = it },
                                onManageProviders = { showProviders = true },
                                onManageRoles = { showRoles = true },
                                onReturnToDrawer = onAllTabsClosed,
                                codeSkinActivation = codeState.conversationSkin,
                                codeRunning = codeState.isRunning,
                                onApplySkinViaNewChat = codeViewModel::clearConversation,
                                onDiscardPendingSkin = codeViewModel::discardPendingSkinChange
                            )
                        }
                    }
                }
            }
        }
    }

    if (showProviders) {
        ProvidersDialog(providers, viewModel) { showProviders = false }
    }
    if (showRoles) {
        RolesDialog(roles, profiles, viewModel) { showRoles = false }
    }
}

// ───────────────────────────── 页面路由 ─────────────────────────────

/** page.id → 主旧 route(抽屉叶子高亮兼容);null = 非法/未知。 */
private fun legacyRouteOfCompat(pageId: String?): String? {
    if (pageId.isNullOrEmpty()) return null
    return SettingsNavAliases.legacyRouteOf(pageId)
        ?: pageId.takeIf { it in SettingsNavTree.flatMap { c -> c.children }.map { l -> l.route } }
}

/**
 * 恢复串 → 标签列表（PR1 · FR-105）：逐项经别名表解析到稳定 page.id ——
 * 旧 route 静默升级；未知/失效项**只丢弃自身**，不清空全部标签（BUG-08 契约）。
 */
internal fun sanitizeRestoredTabs(raw: String): List<String> =
    raw.split("\u0000").mapNotNull { SettingsNavAliases.resolvePageId(it.trim()) }

/** 标签列表持久化 Saver：List<String> ↔ NUL 分隔串（route 不含控制字符，安全）。 */
private val OpenTabsSaver = Saver<List<String>, String>(
    save = { it.joinToString("\u0000") },
    restore = { s -> sanitizeRestoredTabs(s) }
)

/**
 * 叶子 route → 页面内容分发。每个页面 = 旧三页签对应分区的原样搬移，
 * 功能零增删；新增页面时在此登记（SettingsNavTree 同步加叶子）。
 */
@Composable
private fun SettingsPageRouter(
    route: String,
    profiles: List<ModelProfile>,
    selected: ModelProfile?,
    agent: AgentSettings,
    viewModel: SettingsViewModel,
    onAgent: (AgentSettings.() -> AgentSettings) -> Unit,
    onSelect: (String) -> Unit,
    onManageProviders: () -> Unit,
    onManageRoles: () -> Unit,
    onReturnToDrawer: () -> Unit,
    codeSkinActivation: com.apex.agent.ui.screen.code.SkinActivation,
    codeRunning: Boolean,
    onApplySkinViaNewChat: () -> Unit,
    onDiscardPendingSkin: () -> Unit
) {
    val onUpdate: (ModelProfile) -> Unit = { viewModel.upsertProfile(it) }
    // PR1:page.id 分发 —— 旧 route 在入口经别名表解析(合并页内容暂按
    // §4.3 语义叠放,物理拆合归 PR4)。未知 pageId → 迁移占位(不清空标签)。
    val pageId = SettingsNavAliases.resolvePageId(route)
    when (pageId) {
        // ═══ model:模型与连接 ═══
        "model.connection" -> SettingsLeafColumn {
            ModelSetupCard(
                profiles = profiles,
                selected = selected,
                viewModel = viewModel,
                onSelect = onSelect,
                onManageProviders = onManageProviders,
                onManageRoles = onManageRoles,
            )
            // 合并页(§4.3):providersRoles 锚点 —— 服务商/角色管理入口
            ProvidersRolesPage(
                onManageProviders = onManageProviders,
                onManageRoles = onManageRoles
            )
        }
        "model.generation" -> SettingsLeafColumn {
            selected?.let { GenerationSection(it, onUpdate) } ?: EmptyProfileHint()
            // 归并页：高级选项（结构化输出/自定义头）是请求参数而非提示词，
            // 自提示词页迁入 —— 与生成参数同为「发请求时生效」的模型档位
            selected?.let { AdvancedSection(it, onUpdate) } ?: EmptyProfileHint()
        }
        "model.reasoning" -> SettingsLeafColumn {
            selected?.let { ReasoningSection(it, onUpdate) } ?: EmptyProfileHint()
        }
        "model.context" -> SettingsLeafColumn {
            selected?.let { ContextSection(it, onUpdate) } ?: EmptyProfileHint()
            // 归并页：上下文压缩（全局域）—— §4.3 起 PR4 迁 system.diagnostics,
            // 迁出前保留原地渲染(相关设置跳转在 diagnostics 页同样可见)
            CompressionSection(agent, onAgent)
        }
        "model.tools" -> SettingsLeafColumn {
            selected?.let { ToolsSection(it, onUpdate) } ?: EmptyProfileHint()
        }
        "model.network" -> SettingsLeafColumn {
            selected?.let { NetworkSection(it, onUpdate) } ?: EmptyProfileHint()
            // 归并页：限速友好（全局域）—— PR4 迁 system.diagnostics
            RateLimitSection(agent, onAgent)
        }
        "model.prompt" -> SettingsLeafColumn {
            selected?.let { PromptSection(it, onUpdate) } ?: EmptyProfileHint()
        }
        // ═══ agent:代理与安全 ═══
        // v1.7 部件外观迁 appearance.skins(§4.3)—— 本页只保留行为类开关
        "agent.coding" -> SettingsLeafColumn {
            CodingSettingsSection(agent, onAgent)
        }
        "agent.subagent" -> SettingsLeafColumn { SubAgentSettingsSection(agent, onAgent) }
        "agent.permissions" -> SettingsLeafColumn {
            PermissionSettingsSection(
                mode = agent.permissionMode,
                rules = agent.permissionRules,
                onModeChange = { m -> onAgent { copy(permissionMode = m) } },
                onRulesChange = { rs -> onAgent { copy(permissionRules = rs) } }
            )
        }
        "agent.policy" -> TerminalCommandPolicyPage()
        "agent.rules" -> SettingsLeafColumn {
            AgentRulesSectionBlock(agent, onAgent, viewModel)
        }
        "agent.hooks" -> SettingsLeafColumn {
            AgentHooksSectionBlock(viewModel)
        }
        "agent.goal" -> SettingsLeafColumn { GoalSettingsSection(agent, onAgent) }
        // ═══ appearance:外观与交互(interface/appearance 一拆四)═══
        "appearance.theme" -> SettingsLeafColumn {
            AppearanceSection(agent, onAgent)
        }
        "appearance.chat" -> SettingsLeafColumn {
            ChatDisplaySection(agent, onAgent)
        }
        "appearance.skins" -> SettingsLeafColumn {
            PartSkinSection(
                agent = agent,
                onUpdate = onAgent,
                activation = codeSkinActivation,
                isRunning = codeRunning,
                onApplyNowViaNewChat = onApplySkinViaNewChat,
                onDiscardPending = onDiscardPendingSkin
            )
        }
        "appearance.background" -> SettingsLeafColumn {
            BackgroundSection(agent, onAgent)
        }
        "appearance.notifications" -> SettingsLeafColumn {
            NotificationsPage(agent, onAgent)
        }
        // ═══ services:扩展与服务 ═══
        "services.github" -> SettingsLeafColumn { GithubSettingsSection(viewModel) }
        "services.search" -> SearchLocalPage()
        "services.memory" -> SettingsLeafColumn { MemoryCaptureSection(agent, onAgent) }
        // 新增页(§4.6):暴露既有 extensionAutoSync 字段(marketLinks 入口 PR4)
        "services.extensions" -> SettingsLeafColumn {
            SectionCard(
                title = stringResource(R.string.settings_page_extensions),
                icon = Icons.Outlined.Extension,
                initiallyExpanded = true
            ) {
                SwitchRow(
                    label = stringResource(R.string.settings_ext_autosync_label),
                    checked = agent.extensionAutoSync,
                    description = stringResource(R.string.settings_ext_autosync_desc),
                    onCheckedChange = { on -> onAgent { copy(extensionAutoSync = on) } }
                )
                DescriptionText(stringResource(R.string.settings_ext_marketlinks_hint))
            }
        }
        "services.keepAlive" -> TerminalServicePage(agent, onAgent)
        // ═══ system:数据与系统 ═══
        "system.backup" -> SettingsLeafColumn { BackupSection() }
        "system.update" -> SettingsLeafColumn { UpdatePage(agent, onAgent) }
        // 新增页(§4.3):压缩/限速等全局引擎旋钮的统一入口
        "system.diagnostics" -> SettingsLeafColumn {
            CompressionSection(agent, onAgent)
            RateLimitSection(agent, onAgent)
        }
        // BUG-08 兜底:未知/已迁移 route 不再自动清空全部标签 —— 渲染迁移
        // 占位页,由用户手动决定去向(OpenTabsSaver 恢复已逐项过滤,此分支
        // 仅防御运行期动态构造的未知 route)。
        else -> SettingsLeafColumn {
            Text(
                stringResource(R.string.settings_page_migrated, route),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onReturnToDrawer) {
                Text(stringResource(R.string.settings_open_drawer))
            }
        }
    }
}

/** 无档案可用时的占位（模型类页面选中档案被删空时出现）。 */
@Composable
private fun EmptyProfileHint() {
    Text(
        stringResource(R.string.settings_no_profile_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.outline
    )
}

/** 服务商与角色页：两个入口卡（对话框触发器集中在 setup 卡之外的独立叶子）。 */
@Composable
private fun ProvidersRolesPage(
    onManageProviders: () -> Unit,
    onManageRoles: () -> Unit
) {
    SettingsLeafColumn {
        SectionCard(
            title = stringResource(R.string.settings_nav_model_providers),
            icon = Icons.Outlined.Tune
        ) {
            Text(
                stringResource(R.string.settings_providers_roles_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
        Button(onClick = onManageProviders, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.settings_manage_providers))
        }
        Button(onClick = onManageRoles, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.settings_manage_roles))
        }
    }
}

/** 未选叶子时的引导态：提示从左侧抽屉选择分类（抽屉打开时被遮罩覆盖）。 */
@Composable
private fun SettingsLandingHint(onOpenDrawer: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Outlined.Tune,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(40.dp)
        )
        Text(
            stringResource(R.string.settings_landing_title),
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            stringResource(R.string.settings_landing_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
        Button(onClick = onOpenDrawer) {
            Text(stringResource(R.string.settings_open_drawer))
        }
    }
}
