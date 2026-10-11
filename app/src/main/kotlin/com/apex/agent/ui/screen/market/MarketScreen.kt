package com.apex.agent.ui.screen.market

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apex.agent.R

/**
 * 市场（v3 顶栏双视图重构）
 *
 * 结构（对应产品反馈：「点击市场后顶部有市场 / 已安装管理两个导航，
 * 两个导航下面都保留原有子导航 skill / mcp / 插件等」）：
 *
 * ```
 * ┌─────────────────────────────────────────────┐
 * │  PrimaryTabRow：  市场  │  已安装管理            │  ← 顶栏视图切换
 * ├─────────────────────────────────────────────┤
 * │  SecondaryTabRow： 插件 │ Skills │ MCP │ 连接器 │ 集成  │  ← 原有五个子导航（两视图共享）
 * ├─────────────────────────────────────────────┤
 * │  内容区（发现/安装 ↔ 管理已装）                  │
 * └─────────────────────────────────────────────┘
 * ```
 *
 * - 「市场」发现视图：插件发现 / Skills 模板与导入 / MCP 添加 / 连接器添加 /
 *   魔搭 + GitHub 集成（见 [MarketBrowseTabs.kt]）；
 * - 「已安装管理」视图：上述五类的已装内容启停 / 卸载 / 连接管理
 *   （见 [MarketInstalledTabs.kt]）；
 * - 切换视图不重置子页签；已安装视图空态提供「去市场安装」一键跳回。
 *
 * 数据层见 [MarketViewModel]（全部 IO 线程化）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketScreen(viewModel: MarketViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    // 修复跨屏 stale 开关：VM 为 Activity 级单例，在 Skill 页切换的开关不会自动同步到市场页 ——
    // 每次进入本屏时强制刷新快照（原仅 init 刷新一次）
    LaunchedEffect(Unit) { viewModel.refresh() }

    LaunchedEffect(state.lastMessage) {
        state.lastMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    // 已安装计数徽标：已加载插件 + 已装技能 + MCP 工具源 + 连接器
    val installedCount = state.plugins.count { it.loaded } +
        state.skills.size +
        state.mcps.size +
        state.connectors.size

    // ═══ #197 分级过滤态：Skills/MCP 列表按当前市场分级（agent/coding）过滤 ═══
    // scope=all 的条目两边都保留；分类页/已安装页统一消费这份数据，
    // 与斜杠菜单的 SlashMenuData.forScope 同一过滤口径。
    val tierState = remember(state.tier, state.skills, state.mcps) {
        state.copy(
            skills = viewModel.visibleSkills(state),
            mcps = viewModel.visibleMcps(state)
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ═══ #197 市场分级：Agent 市场 / Coding 市场（双工位分流）═══
            MarketTierSelector(
                tier = state.tier,
                onSelect = viewModel::selectTier
            )

            // ═══ 顶栏视图导航：市场 / 已安装管理 ═══
            MarketScopeTabs(
                scope = state.scope,
                installedCount = installedCount,
                onSelect = viewModel::selectScope
            )

            // ═══ 子导航：五个分类页签（两视图共享，切换视图不重置）═══
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

            // ═══ 内容区：按视图 × 分类分发（#197：传分级过滤态 tierState）═══
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
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }

    // ═══ v2 认知市场：技能详情对话框（cs-mem 能量 + 调用统计 + 熔断 + 轨迹）═══
    val detailSkillId = state.detailSkillId
    if (detailSkillId != null) {
        val skillRow = state.skills.firstOrNull { it.id == detailSkillId }
        val skillName = skillRow?.name ?: detailSkillId
        MarketSkillDetailDialog(
            skillId = detailSkillId,
            skillName = skillName,
            // Issue #166：内置技能在详情标题行展示「内置」徽标（manifest.bundled → 行数据）
            bundled = skillRow?.bundled == true,
            detailState = state.detailState,
            loading = state.detailLoading,
            onDismiss = viewModel::closeSkillDetail
        )
    }

    // ═══ #197 MCP 真实启动进度弹窗（连接中服务器的实时阶段）═══
    state.mcpStartup?.let { startup ->
        McpStartupProgressDialog(
            startup = startup,
            onDismiss = viewModel::dismissMcpStartup
        )
    }

    // ═══ P1（市场审计）：安装确认门禁 —— 干运行预览（manifest 摘要/依赖/权限/
    // promptInjection 警示）非空时弹窗，用户确认后才落盘+激活。唤醒此前零接线的
    // ManifestDryRunDialog（点对即装 → 不可信内容先目检）。═══
    state.installPreview?.let { preview ->
        ManifestDryRunDialog(
            preview = preview,
            onConfirm = viewModel::confirmPendingInstall,
            onDismiss = viewModel::dismissPendingInstall
        )
    }

    // ═══ GitHub 智能安装：多候选选择弹窗（技能集合 / MCP monorepo 仓库）═══
    // 探测发现多个可装项（如 anthropics/skills 的 21 个技能、
    // modelcontextprotocol/servers 的 7 台 server）时置入；单候选不弹直接装。
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
 * #197 市场分级选择器：Agent 市场 / Coding 市场 双胶囊（横条）。
 *
 * 分级决定列表的可见范围（scope 过滤）与添加 MCP 时的默认作用域：
 * Agent 市场服务 Agent 屏（聊天技能/联网搜索/记忆），Coding 市场服务
 * Coding 屏（开发技能/GitHub/文件系统）。两个市场互不可见对方专属条目。
 */
@Composable
private fun MarketTierSelector(
    tier: MarketTier,
    onSelect: (MarketTier) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        MarketTier.entries.forEach { t ->
            FilterChip(
                selected = tier == t,
                onClick = { onSelect(t) },
                label = { Text(stringResource(t.labelRes)) },
                modifier = Modifier.padding(end = 8.dp)
            )
        }
    }
}

/**
 * #197 MCP 真实启动进度弹窗：逐阶段渲染 [com.apex.agent.core.tools.mcp.McpStartupEvent]。
 *
 * 每一行都是真实事件（环境检查 / 子进程 fork（pid+argv）/ initialize 握手 /
 * initialized / tools 发现），时间戳为事件到达时刻；进行中的阶段尾部脉冲，
 * 失败行红色展开真实错误。**没有任何模拟延时** —— 阶段间的等待就是真实的
 * npx 冷启动/网络握手耗时。
 */
@Composable
private fun McpStartupProgressDialog(
    startup: McpStartupUi,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.market_mcp_startup_title, startup.serverName),
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .width(320.dp)
            ) {
                // #205：key 加 index —— stderr 事件与阶段事件同毫秒到达时
                // "timestamp+stage" 也会撞键（两条 STDERR 同 ms 尤甚）导致崩溃。
                itemsIndexed(
                    startup.events,
                    key = { i, e -> "$i-${e.timestampMs}-${e.stage.name}" }
                ) { _, event ->
                    val failed = event.stage == com.apex.agent.core.tools.mcp.McpStartupStage.FAILED
                    val stderr = event.stage == com.apex.agent.core.tools.mcp.McpStartupStage.STDERR
                    Row(
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier.padding(vertical = if (stderr) 2.dp else 5.dp)
                    ) {
                        Icon(
                            imageVector = if (failed) Icons.Default.ErrorOutline
                            else if (stderr) Icons.Default.Info
                            else Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = if (failed) MaterialTheme.colorScheme.error
                            else if (stderr) MaterialTheme.colorScheme.outline
                            else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(17.dp)
                        )
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text(
                                text = stageLabel(event.stage),
                                style = if (stderr) MaterialTheme.typography.labelSmall
                                else MaterialTheme.typography.labelLarge
                            )
                            Text(
                                text = event.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = formatEventTime(event.timestampMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
                if (startup.running) {
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 6.dp)
                        ) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Text(
                                text = stringResource(R.string.market_mcp_startup_running),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    if (startup.running) stringResource(R.string.market_mcp_startup_hide)
                    else stringResource(R.string.market_mcp_startup_done)
                )
            }
        }
    )
}

/** 启动阶段的本地化标签（真实阶段名 → 用户可读文案）。 */
@Composable
private fun stageLabel(stage: com.apex.agent.core.tools.mcp.McpStartupStage): String =
    stringResource(
        when (stage) {
            com.apex.agent.core.tools.mcp.McpStartupStage.ENV_CHECK -> R.string.market_mcp_stage_env
            com.apex.agent.core.tools.mcp.McpStartupStage.SPAWN -> R.string.market_mcp_stage_spawn
            com.apex.agent.core.tools.mcp.McpStartupStage.INITIALIZE_SENT -> R.string.market_mcp_stage_init_sent
            com.apex.agent.core.tools.mcp.McpStartupStage.INITIALIZE_RESULT -> R.string.market_mcp_stage_init_result
            com.apex.agent.core.tools.mcp.McpStartupStage.INITIALIZED -> R.string.market_mcp_stage_initialized
            com.apex.agent.core.tools.mcp.McpStartupStage.TOOLS_DISCOVERED -> R.string.market_mcp_stage_tools
            com.apex.agent.core.tools.mcp.McpStartupStage.STDERR -> R.string.market_mcp_stage_stderr
            com.apex.agent.core.tools.mcp.McpStartupStage.FAILED -> R.string.market_mcp_stage_failed
        }
    )

// v1.4.4 UX 审查：SimpleDateFormat 提升为复用实例（旧实现位于 items 循环体内，
// 每行每次重组都 new 一个；对齐 LogViewerScreen 的既有模式）
private val marketEventTimeFormat = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)

private fun formatEventTime(ts: Long): String {
    synchronized(marketEventTimeFormat) {
        return marketEventTimeFormat.format(java.util.Date(ts))
    }
}
