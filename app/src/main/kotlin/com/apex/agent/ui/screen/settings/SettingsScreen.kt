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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apex.agent.R
import com.apex.agent.core.llm.ModelProfile
import com.apex.agent.permission.PermissionSettingsSection
import kotlinx.coroutines.launch

/**
 * 设置中心 v2 —— 抽屉两级分层导航（设置页面完全重构第一阶段）。
 *
 * 结构（对齐浏览器式设置中心惯例）：
 * - 总抽屉点「设置」进入本页时，总抽屉收回（根层 navigateToTopLevel 语义），
 *   **设置抽屉自动打开**（独立 ModalNavigationDrawer，与总抽屉互不干扰）；
 * - 设置抽屉第一列 = 一级大分类（模型配置 / Agent 配置 / 界面外观 / 数据与备份）；
 * - 点击分类展开二级叶子（手风琴，同一时刻仅一个分类展开）；
 * - 点击叶子（如模型配置 → 配置 / 参数）→ 抽屉收回 → 内容区渲染对应页面；
 * - 顶栏汉堡钮可随时重开设置抽屉切换页面（页面状态保留）。
 *
 * 第二阶段（PR2）将把「单一当前页」升级为浏览器式多标签：页面以标签
 * 形式排在最上方，点叉号关闭并回退到相邻标签 / 设置抽屉。
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onBack: () -> Unit = {}
) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val providers by viewModel.providers.collectAsStateWithLifecycle()
    val roles by viewModel.roles.collectAsStateWithLifecycle()
    val agent by viewModel.agentSettings.collectAsStateWithLifecycle()

    var selectedId by remember { mutableStateOf(viewModel.defaultProfile.id) }
    val selected = profiles.firstOrNull { it.id == selectedId } ?: profiles.firstOrNull()

    // ═══ 导航状态（rememberSaveable：旋转/进程重建不丢）═══
    // activePage：当前打开的叶子页面 route（null = 尚未选择，抽屉主导）
    // expandedCategory：抽屉展开中的一级分类（默认模型配置）
    var activePage by rememberSaveable { mutableStateOf<String?>(null) }
    var expandedCategory by rememberSaveable { mutableStateOf<String?>(SettingsNavTree.first().route) }

    // 进入设置页即打开设置抽屉（总抽屉已由根层收回）
    val settingsDrawerState = rememberDrawerState(initialValue = DrawerValue.Open)
    val scope = rememberCoroutineScope()

    var showProviders by remember { mutableStateOf(false) }
    var showRoles by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<TestResult?>(null) }
    var isTesting by remember { mutableStateOf(false) } // 修复：测试连接期间禁用按钮，防连点并发请求

    // Agent 设置统一更新入口
    val onAgent: (AgentSettings) -> Unit = { next -> viewModel.updateAgentSettings { next } }

    // ═══ 抽屉交互 ═══
    val openSettingsDrawer: () -> Unit = {
        scope.launch { settingsDrawerState.open() }
    }
    val onToggleCategory: (String) -> Unit = { route ->
        expandedCategory = if (expandedCategory == route) null else route
    }
    // 点击叶子：进入配置页面 + 收回设置抽屉
    val onSelectLeaf: (String) -> Unit = { route ->
        activePage = route
        expandedCategory = categoryOfLeaf(route)?.route
        scope.launch { settingsDrawerState.close() }
    }
    // 从页面回到设置抽屉（清当前页 —— 重新选分类）
    val onReturnToDrawer: () -> Unit = {
        activePage = null
        openSettingsDrawer()
    }

    // ═══ 返回键链：抽屉开 → 关抽屉；有页面 → 回抽屉；否则 → 出设置页 ═══
    BackHandler(enabled = settingsDrawerState.isOpen) {
        scope.launch { settingsDrawerState.close() }
    }
    BackHandler(enabled = !settingsDrawerState.isOpen && activePage != null) {
        onReturnToDrawer()
    }

    ModalNavigationDrawer(
        drawerState = settingsDrawerState,
        // 与总抽屉同款手势纪律：禁边缘滑动误触（返回键/汉堡钮/scrim 关闭不受影响）
        gesturesEnabled = false,
        drawerContent = {
            SettingsDrawerSheet(
                expandedCategory = expandedCategory,
                activeLeafRoute = activePage,
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
                            activePage?.let { route ->
                                leafOf(route)?.let { leaf -> stringResource(leaf.titleRes) }
                            } ?: stringResource(R.string.settings_title)
                        )
                    },
                    navigationIcon = {
                        // 页面态：汉堡钮重开设置抽屉（切换分类/叶子）
                        IconButton(onClick = openSettingsDrawer) {
                            Icon(Icons.Default.Menu, stringResource(R.string.settings_open_drawer))
                        }
                    },
                    actions = {
                        Button(
                            onClick = {
                                selected?.let {
                                    scope.launch {
                                        isTesting = true
                                        try {
                                            testResult = viewModel.testConnection(it.id)
                                        } finally {
                                            isTesting = false
                                        }
                                    }
                                }
                            },
                            enabled = selected != null && !isTesting
                        ) {
                            if (isTesting) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Outlined.Science, null, Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (isTesting) stringResource(R.string.settings_testing)
                                else stringResource(R.string.settings_test)
                            )
                        }
                    }
                )
            }
        ) { padding ->
            Box(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
            ) {
                val page = activePage
                if (page == null) {
                    // 未选叶子：引导态（抽屉已打开覆盖其上，关闭后可见）
                    SettingsLandingHint(onOpenDrawer = openSettingsDrawer)
                } else {
                    SettingsPageRouter(
                        route = page,
                        profiles = profiles,
                        selected = selected,
                        agent = agent,
                        roles = roles,
                        viewModel = viewModel,
                        onAgent = onAgent,
                        onSelect = { selectedId = it },
                        onManageProviders = { showProviders = true },
                        onManageRoles = { showRoles = true },
                        onReturnToDrawer = onReturnToDrawer
                    )
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
    testResult?.let { r ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { testResult = null },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { testResult = null }) { Text(stringResource(R.string.settings_dialog_ok)) }
            },
            title = {
                Text(
                    if (r.success) stringResource(R.string.settings_conn_success)
                    else stringResource(R.string.settings_conn_failed)
                )
            },
            text = { Text(r.message) }
        )
    }
}

// ───────────────────────────── 页面路由 ─────────────────────────────

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
    roles: com.apex.agent.core.llm.ModelRoleConfig,
    viewModel: SettingsViewModel,
    onAgent: (AgentSettings) -> Unit,
    onSelect: (String) -> Unit,
    onManageProviders: () -> Unit,
    onManageRoles: () -> Unit,
    onReturnToDrawer: () -> Unit
) {
    val onUpdate: (ModelProfile) -> Unit = { viewModel.upsertProfile(it) }
    when (route) {
        // ═══ 模型配置 ═══
        "model/setup" -> SettingsLeafColumn {
            ModelSetupCard(
                profiles = profiles,
                selected = selected,
                viewModel = viewModel,
                onSelect = onSelect,
                onManageProviders = onManageProviders,
                onManageRoles = onManageRoles,
            )
        }
        "model/generation" -> SettingsLeafColumn {
            selected?.let { GenerationSection(it, onUpdate) } ?: EmptyProfileHint()
        }
        "model/reasoning" -> SettingsLeafColumn {
            selected?.let { ReasoningSection(it, onUpdate) } ?: EmptyProfileHint()
        }
        "model/context" -> SettingsLeafColumn {
            selected?.let { ContextSection(it, onUpdate) } ?: EmptyProfileHint()
        }
        "model/tools" -> SettingsLeafColumn {
            selected?.let { ToolsSection(it, onUpdate) } ?: EmptyProfileHint()
        }
        "model/network" -> SettingsLeafColumn {
            selected?.let { NetworkSection(it, onUpdate) } ?: EmptyProfileHint()
        }
        "model/prompt" -> SettingsLeafColumn {
            selected?.let { PromptSection(it, onUpdate) } ?: EmptyProfileHint()
        }
        "model/advanced" -> SettingsLeafColumn {
            selected?.let { AdvancedSection(it, onUpdate) } ?: EmptyProfileHint()
        }
        "model/providers-roles" -> ProvidersRolesPage(
            onManageProviders = onManageProviders,
            onManageRoles = onManageRoles
        )
        // ═══ Agent 配置 ═══
        "agent/execution" -> SettingsLeafColumn { AgentSection(agent, onAgent) }
        "agent/roles" -> SettingsLeafColumn { AgentRolesSection(agent, onAgent) }
        "agent/permissions" -> SettingsLeafColumn {
            PermissionSettingsSection(
                mode = agent.permissionMode,
                rules = agent.permissionRules,
                onModeChange = { m -> onAgent(agent.copy(permissionMode = m)) },
                onRulesChange = { rs -> onAgent(agent.copy(permissionRules = rs)) }
            )
        }
        "agent/rules" -> SettingsLeafColumn {
            AgentRulesSectionBlock(agent, onAgent, viewModel)
        }
        "agent/hooks" -> SettingsLeafColumn {
            AgentHooksSectionBlock(viewModel)
        }
        "agent/presets" -> SettingsLeafColumn { ModePresetEditorSection(agent, onAgent) }
        "agent/subagent" -> SettingsLeafColumn { SubAgentSettingsSection(agent, onAgent) }
        "agent/goal-loop" -> SettingsLeafColumn {
            GoalSettingsSection(agent, onAgent)
            LoopSettingsSection(agent, onAgent)
        }
        "agent/coding" -> SettingsLeafColumn { CodingSettingsSection(agent, onAgent) }
        "agent/github" -> SettingsLeafColumn { GithubSettingsSection(viewModel) }
        "agent/compression" -> SettingsLeafColumn { CompressionSection(agent, onAgent) }
        "agent/vision" -> SettingsLeafColumn {
            VisionSection(agent, roles, profiles, viewModel)
        }
        // ═══ 界面外观 ═══
        "interface/appearance" -> SettingsLeafColumn { AppearanceSection(agent, onAgent) }
        "interface/chat" -> SettingsLeafColumn { ChatDisplaySection(agent, onAgent) }
        // ═══ 数据与备份 ═══
        "data/backup" -> SettingsLeafColumn { BackupSection() }
        // 未知 route 兜底：回设置抽屉（不渲染空白页）
        else -> LaunchedEffect(route) { onReturnToDrawer() }
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
