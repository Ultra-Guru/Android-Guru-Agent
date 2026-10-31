package com.apex.agent.ui.screen.hubcenter

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apex.agent.R
import com.apex.agent.core.tools.fusion.FusionBundleDigest
import com.apex.agent.core.tools.fusion.FusionBundleManifest
import com.apex.agent.fusion.FusionBundleController
import com.apex.agent.ui.component.FusionBundleComposerDialog
import com.apex.agent.ui.component.LocalFeedbackController
import com.apex.agent.ui.component.FeedbackSeverity
import com.apex.agent.ui.component.rememberClaudeImportLauncher
import com.apex.agent.ui.component.rememberFusionBundleController
import com.apex.agent.ui.icons.CrownGold
import com.apex.agent.ui.icons.FusionCrownCanvas
import com.apex.agent.ui.screen.market.dismissMcpStartup
import com.apex.agent.ui.screen.market.saveSkillConfig
import com.apex.agent.ui.screen.market.BrowseConnectorsTab
import com.apex.agent.ui.screen.market.BrowseIntegrationsTab
import com.apex.agent.ui.screen.market.BrowseMcpTab
import com.apex.agent.ui.screen.market.BrowsePluginsTab
import com.apex.agent.ui.screen.market.BrowseSkillsTab
import com.apex.agent.ui.screen.market.GithubCandidatesPickDialog
import com.apex.agent.ui.screen.market.InstalledConnectorsTab
import com.apex.agent.ui.screen.market.InstalledIntegrationsTab
import com.apex.agent.ui.screen.market.InstalledMcpTab
import com.apex.agent.ui.screen.market.InstalledPluginsTab
import com.apex.agent.ui.screen.market.InstalledSkillsTab
import com.apex.agent.ui.screen.market.ManifestDryRunDialog
import com.apex.agent.ui.screen.market.MarketScope
import com.apex.agent.ui.screen.market.MarketSkillDetailDialog
import com.apex.agent.ui.screen.market.MarketTab
import com.apex.agent.ui.screen.market.MarketTier
import com.apex.agent.ui.screen.market.MarketUiState
import com.apex.agent.ui.screen.market.MarketViewModel
import com.apex.agent.ui.screen.market.McpStartupProgressDialog
import kotlinx.coroutines.launch

/**
 * # 工具聚合中心（取代旧「市场」顶层的聚合优先体验）
 *
 * 旧市场（市场/已安装管理 双视图 × 5 页签）整体退役；本中心以**聚合**为
 * 第一公民重组全部能力，三层结构：
 *
 * ```
 * ┌───────────────────────────────────────────────┐
 * │ 👑 融合面板（hero）：把技能·MCP·工具·插件融合成聚合 │
 * │    [＋ 新建聚合]      [📥 导入 Claude 插件包]      │
 * ├───────────────────────────────────────────────┤
 * │ 我的聚合（横滑卡片）：REA / Superpowers / 自建包…  │
 * │    [激活|停用] [安装成员] [编辑]                    │
 * ├───────────────────────────────────────────────┤
 * │ 成员体系：[Agent|Coding 分级] [插件|技能|MCP|连接器|集成] │
 * │          [添加成员 | 我的成员] ← 旧市场双视图收编为段控 │
 * │ <对应 Browse 与 Installed 五对页签内容（安装机器全复用）>   │
 * └───────────────────────────────────────────────┘
 * ```
 *
 * 设计要点（为什么这样组织）：
 * - **聚合优先**：用户的核心诉求是「把一堆东西当一个整体用」——融合面板
 *   与我的聚合置顶，成员管理沉底；旧市场的「发现/管理」双视图收编为
 *   每类成员内的两档段控（添加成员 / 我的成员），层级少一层；
 * - **聚合卡横滑**：聚合数量随生态增长（内置 7 + 自建 + 导入），横滑卡片
 *   不与下方页签的纵向列表抢滚动空间（嵌套滚动冲突的根治方案）；
 * - **机器全复用**：[MarketViewModel] / Hub 控制器 / 安装管理器 / 五对
 *   Browse·Installed 页签原样挂载（market 包降级为中心的实现细节包，
 *   「市场」作为用户概念已删除）；旧 MarketScreen 上的四个全局对话框
 *   （技能详情 / MCP 启动进度 / 安装干运行 / GitHub 候选）随状态流在
 *   本中心统一渲染。
 */
@Composable
fun AggregationCenterScreen(viewModel: MarketViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val fusion = rememberFusionBundleController()
    val bundles by fusion.bundles.collectAsStateWithLifecycle()
    val activeBundle by fusion.activeBundle.collectAsStateWithLifecycle()
    val feedback = LocalFeedbackController.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // 进屏刷新（VM 为 Activity 级单例，跨屏开关状态以本屏进入时快照为准）
    LaunchedEffect(Unit) { viewModel.refresh() }

    LaunchedEffect(state.lastMessage) {
        state.lastMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    // ── 融合面板状态：新建/编辑组合器 + Claude zip 导入 ──
    var composerEditing by remember { mutableStateOf<FusionBundleManifest?>(null) }
    var showComposer by remember { mutableStateOf(false) }
    val importLauncher = rememberClaudeImportLauncher(fusion)

    // 异步回调文案预解析（仓库惯例：组合期取词）
    val activatedPattern = stringResource(R.string.fusion_result_activated)
    val skippedPattern = stringResource(R.string.fusion_result_skipped)
    val deactivatedPattern = stringResource(R.string.fusion_result_deactivated)
    val installedPattern = stringResource(R.string.fusion_result_installed)
    val failedText = stringResource(R.string.fusion_result_failed)

    // #197 分级过滤态：Skills/MCP 按当前工位分级过滤（与斜杠菜单同口径）
    val tierState = remember(state.tier, state.skills, state.mcps) {
        state.copy(
            skills = viewModel.visibleSkills(state),
            mcps = viewModel.visibleMcps(state)
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ═══ 第一层：融合面板 hero ═══
            FusionHeroCard(
                onCompose = {
                    composerEditing = null
                    showComposer = true
                },
                onImport = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) }
            )

            // ═══ 第二层：我的聚合（横滑卡片）═══
            Text(
                text = stringResource(R.string.hub_my_aggregations),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 4.dp)
            )
            if (bundles.isEmpty()) {
                Text(
                    text = stringResource(R.string.hub_aggregations_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            } else {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.height(150.dp)
                ) {
                    items(bundles, key = { it.id }) { digest ->
                        AggregationCard(
                            digest = digest,
                            isActive = activeBundle?.id == digest.id,
                            onActivate = {
                                scope.launch {
                                    fusion.activate(digest.id).fold(
                                        onSuccess = { act ->
                                            val note = if (act.skipped.isEmpty()) ""
                                            else String.format(skippedPattern, act.skipped.size)
                                            feedback.show(
                                                String.format(activatedPattern, act.bundleName) + note,
                                                FeedbackSeverity.INFO
                                            )
                                        },
                                        onFailure = {
                                            feedback.show(it.message ?: failedText, FeedbackSeverity.WARNING)
                                        }
                                    )
                                }
                            },
                            onDeactivate = {
                                fusion.deactivate().onSuccess {
                                    feedback.show(
                                        String.format(deactivatedPattern, digest.name),
                                        FeedbackSeverity.INFO
                                    )
                                }
                            },
                            onInstallMembers = {
                                scope.launch {
                                    fusion.installMembers(digest.id).fold(
                                        onSuccess = { report ->
                                            val note = if (report.skipped.isEmpty()) ""
                                            else String.format(skippedPattern, report.skipped.size)
                                            feedback.show(
                                                String.format(
                                                    installedPattern, report.bundleName,
                                                    report.installedSkills.size,
                                                    report.configuredServers.size +
                                                        report.connectedServers.size,
                                                    report.installedPlugins.size
                                                ) + note,
                                                FeedbackSeverity.INFO
                                            )
                                        },
                                        onFailure = {
                                            feedback.show(it.message ?: failedText, FeedbackSeverity.WARNING)
                                        }
                                    )
                                }
                            },
                            onEdit = {
                                composerEditing = fusion.registryForTools.get(digest.id)
                                showComposer = true
                            }
                        )
                    }
                }
            }

            // ═══ 第三层：成员体系 ═══
            // 分级（Agent/Coding 工位分流 —— scope 语义核心，保留）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                MarketTier.entries.forEach { t ->
                    FilterChip(
                        selected = state.tier == t,
                        onClick = { viewModel.selectTier(t) },
                        label = { Text(stringResource(t.labelRes)) },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                // 添加成员 / 我的成员（旧市场双视图收编为段控）
                FilterChip(
                    selected = state.scope == MarketScope.BROWSE,
                    onClick = { viewModel.selectScope(MarketScope.BROWSE) },
                    label = { Text(stringResource(R.string.hub_mode_add)) },
                    modifier = Modifier.padding(end = 8.dp)
                )
                FilterChip(
                    selected = state.scope == MarketScope.INSTALLED,
                    onClick = { viewModel.selectScope(MarketScope.INSTALLED) },
                    label = { Text(stringResource(R.string.hub_mode_mine)) }
                )
            }

            SecondaryTabRow(selectedTabIndex = state.selectedTab.ordinal) {
                MarketTab.entries.forEach { tab ->
                    Tab(
                        selected = state.selectedTab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        text = {
                            Text(
                                stringResource(tab.labelRes),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                }
            }

            // 内容区：分级 × 分类 × 添加/我的 三维分发
            Box(modifier = Modifier.weight(1f)) {
                when (state.scope) {
                    MarketScope.BROWSE -> when (state.selectedTab) {
                        MarketTab.PLUGINS -> BrowsePluginsTab(tierState, viewModel)
                        MarketTab.SKILLS -> BrowseSkillsTab(tierState, viewModel)
                        MarketTab.MCP -> BrowseMcpTab(tierState, viewModel)
                        MarketTab.CONNECTORS -> BrowseConnectorsTab(tierState, viewModel)
                        MarketTab.INTEGRATIONS -> BrowseIntegrationsTab(tierState, viewModel)
                    }
                    MarketScope.INSTALLED -> when (state.selectedTab) {
                        MarketTab.PLUGINS -> InstalledPluginsTab(tierState, viewModel)
                        MarketTab.SKILLS -> InstalledSkillsTab(tierState, viewModel)
                        MarketTab.MCP -> InstalledMcpTab(tierState, viewModel)
                        MarketTab.CONNECTORS -> InstalledConnectorsTab(tierState, viewModel)
                        MarketTab.INTEGRATIONS -> InstalledIntegrationsTab(tierState, viewModel)
                    }
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }

    // ═══ 全局对话框（随状态流渲染 —— 旧 MarketScreen 迁移）═══
    if (showComposer) {
        FusionBundleComposerDialog(
            controller = fusion,
            editing = composerEditing,
            onDismiss = { showComposer = false }
        )
    }

    // v2 认知市场：技能详情对话框（cs-mem 能量 + 调用统计 + 熔断 + 轨迹）
    val detailSkillId = state.detailSkillId
    if (detailSkillId != null) {
        val skillRow = state.skills.firstOrNull { it.id == detailSkillId }
        MarketSkillDetailDialog(
            skillId = detailSkillId,
            skillName = skillRow?.name ?: detailSkillId,
            bundled = skillRow?.bundled == true,
            detailState = state.detailState,
            loading = state.detailLoading,
            onDismiss = viewModel::closeSkillDetail,
            configForm = state.detailConfigForm,
            onSaveConfig = { values -> viewModel.saveSkillConfig(detailSkillId, values) }
        )
    }

    // #197 MCP 真实启动进度弹窗
    state.mcpStartup?.let { startup ->
        McpStartupProgressDialog(
            startup = startup,
            onDismiss = viewModel::dismissMcpStartup
        )
    }

    // P1 安装确认门禁：干运行预览（manifest 摘要/依赖/权限/promptInjection 警示）
    state.installPreview?.let { preview ->
        ManifestDryRunDialog(
            preview = preview,
            onConfirm = viewModel::confirmPendingInstall,
            onDismiss = viewModel::dismissPendingInstall
        )
    }

    // GitHub 智能安装：多候选选择弹窗（技能集合 / MCP monorepo 仓库）
    val githubCandidates = state.githubCandidates
    if (githubCandidates.isNotEmpty()) {
        GithubCandidatesPickDialog(
            repoTitle = state.githubCandidatesRepo ?: "",
            candidates = githubCandidates,
            busy = state.busy,
            onInstallOne = viewModel::installGithubCandidate,
            onInstallAll = viewModel::installAllGithubCandidates,
            onDismiss = viewModel::dismissGithubCandidates
        )
    }
}

/**
 * 融合面板 hero 卡：聚合中心的身份与主行动位。
 * 王冠 + 主张一句话（首行）+ 双主行动（新建聚合 / 导入 Claude 插件包，次行）。
 */
@Composable
private fun FusionHeroCard(
    onCompose: () -> Unit,
    onImport: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = CrownGold.copy(alpha = 0.10f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FusionCrownCanvas(active = true, modifier = Modifier.size(34.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.hub_hero_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.hub_hero_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 10.dp)
            ) {
                Button(onClick = onCompose) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.fusion_composer_entry))
                }
                OutlinedButton(onClick = onImport) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.fusion_import_claude_entry))
                }
            }
        }
    }
}

/**
 * 我的聚合横滑卡：名称 + 版本 + 徽章（内置/自建/使用中）+ 组件计数 +
 * 三行动（激活|停用 / 安装成员 / 编辑）。信息密度高于王冠菜单行 ——
 * 中心是聚合的「主场」，这里承担管理职责（王冠菜单保持轻量选用）。
 */
@Composable
private fun AggregationCard(
    digest: FusionBundleDigest,
    isActive: Boolean,
    onActivate: () -> Unit,
    onDeactivate: () -> Unit,
    onInstallMembers: () -> Unit,
    onEdit: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
        color = if (isActive) CrownGold.copy(alpha = 0.12f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.width(264.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = digest.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "v${digest.version}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 2.dp)
            ) {
                if (digest.bundled) {
                    Text(
                        text = stringResource(R.string.fusion_badge_bundled),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else if (digest.id.startsWith("custom-")) {
                    Text(
                        text = stringResource(R.string.fusion_composer_custom_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = CrownGold
                    )
                }
                if (isActive) {
                    Text(
                        text = stringResource(R.string.fusion_menu_active_badge),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = CrownGold
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.fusion_row_components,
                        digest.skillCount, digest.mcpServerCount, digest.pluginCount,
                        digest.toolHintCount, digest.engineCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            if (digest.description.isNotBlank()) {
                Text(
                    text = digest.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 8.dp)
            ) {
                if (isActive) {
                    OutlinedButton(
                        onClick = onDeactivate,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.fusion_action_deactivate),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                } else {
                    Button(
                        onClick = onActivate,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.fusion_action_activate),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.fusion_action_install_members),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onInstallMembers)
                )
                if (!digest.bundled) {
                    Text(
                        text = stringResource(R.string.hub_edit),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable(onClick = onEdit)
                    )
                }
            }
        }
    }
}
