package com.apex.agent.ui.screen.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apex.agent.R
import com.apex.memkit.core.model.ChatFact
import com.apex.memkit.core.model.EpisodeSummary
import com.apex.memkit.core.model.GraphEntity
import com.apex.memkit.graph.screen.MemoryGraphContent
import com.apex.memkit.graph.screen.rememberMemoryGraphState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 记忆页（记忆页 v3 全面完善）。
 *
 * v2（#549）建立了六轨数据面 + 图谱画布双形态；v3 把列表形态从
 * 「多个内滚 LazyColumn 挤在固定 Column 里」重构为一整支单列滚动列表：
 *
 *  - **单列列表**：分区（概览 / 健康 / 隐私 / 聊天记忆 / 知识图谱 /
 *    近期经历 / 高频宏）全部扁平化为一个 LazyColumn 的条目 ——
 *    全页自然滚动，消除嵌套滚动死角；列表内容由 [buildMemoryListPlan]
 *    纯函数产出（分区序 / 折叠 / 过滤 JVM 直测）。
 *  - **分区导航**：顶部粘性芯片行一键直达四分区（锚点由计划扫描而来），
 *    当前分区随滚动高亮。
 *  - **下拉刷新**：PullToRefreshBox 整页手势刷新（数据新鲜度此前只有
 *    重进页面一条路）；概览区补「上次刷新」时间行。
 *  - **经历分区**：状态过滤芯片（进行中 / 成功 / 失败 / 放弃，带计数）
 *    + 终态徽章 + 用时 / 应用包 / 手动整理元数据。
 *  - **宏卡可展开**：目标 / 动作序列（类型标签 + 记法）/ 溯源情景 /
 *    沉淀与最近回放时间。
 *  - **知识分区折叠**：实体超上限折叠 + 「显示全部」展开（数百实体
 *    不再一铺到底）。
 *  - **搜索高亮**：命中词加粗（区间来自 [queryMatchRanges] 纯函数）；
 *    命中摘要行 + 一键清空。
 *  - **统计卡联动**：六卡全部可点 —— 直达对应分区或切换图谱视图。
 *
 * 图谱形态（v2）不动：库侧 MemoryGraphContent 自带统计条 / 过滤 /
 * 缩放 / 详情 Sheet 与软刷新；本页只补 Scaffold 外壳与延迟漫游。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(
    viewModel: MemoryViewModel = hiltViewModel()
) {
    val episodes by viewModel.episodes.collectAsStateWithLifecycle()
    val macros by viewModel.macros.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val query by viewModel.searchQuery.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val message by viewModel.lastMessage.collectAsStateWithLifecycle()
    val quarantinedCount by viewModel.quarantinedCount.collectAsStateWithLifecycle()
    val dreamRunning by viewModel.maintenanceRunning.collectAsStateWithLifecycle()
    val chatMemory by viewModel.chatMemory.collectAsStateWithLifecycle()
    val knowledge by viewModel.knowledge.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val lastRefreshAt by viewModel.lastRefreshAt.collectAsStateWithLifecycle()

    var showSearch by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<EpisodeSummary?>(null) }
    var pendingChatRecord by remember { mutableStateOf<ChatFact?>(null) }
    var showClearChatConfirm by remember { mutableStateOf(false) }
    var showToast by remember { mutableStateOf(false) }

    // 写入口（记忆页 v2）：顶栏「添加」菜单 → 记事实 / 教经验两个对话框
    var showAddMenu by remember { mutableStateOf(false) }
    var showSaveFactDialog by remember { mutableStateOf(false) }
    var showTeachDialog by remember { mutableStateOf(false) }

    // 记忆图谱视图（apex-kit-memory:memory-graph-ui）：与列表视图同页切换。
    // 状态提升到本屏顶层 —— 图谱的缩放 / 选中 / 过滤现场跨切换存活。
    var graphMode by remember { mutableStateOf(false) }
    val graphState = rememberMemoryGraphState(viewModel.graphSource)

    // 列表 → 图谱漫游意图（"entity:名" / "fact:ID"）：切到图谱视图后待
    // 装载落定再 navigateTo（首装 / 写后重装都是异步 —— 直接跳会落空）。
    var pendingGraphJump by remember { mutableStateOf<String?>(null) }

    // ═══ v3 列表级 UI 状态（计划输入，见 buildMemoryListPlan）═══
    var episodeFilter by remember { mutableStateOf(EpisodeFilter.ALL) }
    var knowledgeExpanded by remember { mutableStateOf(false) }
    val expandedMacros = remember { mutableStateListOf<String>() }
    val expandedEntities = remember { mutableStateListOf<String>() }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // 搜索态：query 非空时命中列表整段替换分区内容（分区导航失去意义，隐藏）
    val searchActive = showSearch && query.isNotBlank()

    // 知识实体中心度排序（关联数降序 → 名称；快照数百级，重组成本可忽略）
    val knowledgeSorted = remember(knowledge) {
        val counts = knowledge.relations.flatMap { listOf(it.from, it.to) }
            .groupingBy { it }.eachCount()
        knowledge.entities.sortedWith(
            compareByDescending<GraphEntity> { counts[it.name] ?: 0 }.thenBy { it.name }
        )
    }

    // 单列列表计划（纯函数产物）+ 分区锚点 + 首可见下标（当前分区推导）
    val expandedMacroSnapshot = expandedMacros.toSet()
    val plan = remember(
        searchActive, searchResults, chatMemory, knowledgeSorted, knowledgeExpanded,
        episodes, episodeFilter, macros, expandedMacroSnapshot,
    ) {
        buildMemoryListPlan(
            MemoryListPlanInput(
                searchActive = searchActive,
                searchResults = searchResults,
                chatEntries = chatMemory,
                knowledgeEntities = knowledgeSorted,
                knowledgeExpanded = knowledgeExpanded,
                episodes = episodes,
                episodeFilter = episodeFilter,
                macros = macros,
                expandedMacros = expandedMacroSnapshot,
            )
        )
    }
    val anchors = remember(plan) { plan.sectionAnchors() }
    val firstVisibleIndex by remember { derivedStateOf { listState.firstVisibleItemIndex } }

    // 分区导航：锚点缺失（搜索态隐藏导航条，不会走到）兜底滚回顶部
    val navigateToSection: (MemorySection?) -> Unit = { section ->
        scope.launch { listState.animateScrollToItem(anchors[section] ?: 0) }
    }

    LaunchedEffect(message) {
        if (message != null) showToast = true
    }

    // P2-12（6-c）：VM 为 Activity 级单例，其他页面产生的记忆变更不会自动同步到本页 ——
    // 每次进入本屏强制刷新快照（原仅 VM init 刷新一次；照 SkillScreen 同款模式）。
    // 图谱画布的数据新鲜度不由宿主驱动：库侧 MemKitGraphSource 接 store
    // 修订号信号，状态机自动软刷新（保位 / 保选中 / 探索态相机稳定）。
    LaunchedEffect(Unit) { viewModel.refresh() }

    // 延迟漫游：等首装落定 + 目标可寻址（软刷新去抖 1.5s 窗口 —— 刚写入
    // 的节点可能晚到；不可寻址时最多等 1.44s，超时 navigateTo 自身会兑底
    // 开启被关掉的目标类型后选中，不硬跳）。
    LaunchedEffect(graphMode, pendingGraphJump) {
        val target = pendingGraphJump ?: return@LaunchedEffect
        if (!graphMode) return@LaunchedEffect
        withTimeoutOrNull(2500L) {
            while (graphState.loading) delay(80)
            var settle = 0
            while (target !in graphState.displayGraph.nodesById && settle < 18) {
                delay(80)
                settle++
            }
        }
        graphState.navigateTo(target)
        pendingGraphJump = null
    }

    // 进入图谱视图时清空搜索词（图谱有自带搜索；回列表不残留命中列表）
    LaunchedEffect(graphMode) {
        if (graphMode && showSearch) viewModel.clearSearch()
    }

    Scaffold(
        // 内层 Scaffold 置零 insets：状态栏已由根 Scaffold 顶栏承担，避免双重叠加
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                // 顶栏置零 windowInsets，避免与根 Scaffold 状态栏双重叠加
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = { Text(stringResource(R.string.memory_title)) },
                actions = {
                    // 手动沉淀入口（v2）：记事实 / 教经验 —— 与 agent 写入工具同落点
                    IconButton(onClick = { showAddMenu = true }) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.memory_write_add))
                    }
                    MemoryAddMenu(
                        expanded = showAddMenu,
                        onDismiss = { showAddMenu = false },
                        onSaveFact = { showSaveFactDialog = true },
                        onTeachExperience = { showTeachDialog = true }
                    )
                    // 图谱 / 列表视图切换（同页双形态：关系网 vs 管理列表）
                    IconButton(onClick = { graphMode = !graphMode }) {
                        Icon(
                            Icons.Default.AccountTree,
                            contentDescription = stringResource(
                                if (graphMode) R.string.memory_list_view else R.string.memory_graph_view
                            ),
                        )
                    }
                    if (!graphMode) {
                        IconButton(onClick = { showSearch = !showSearch }) {
                            Icon(Icons.Default.Search, contentDescription = stringResource(R.string.MemorySearchNodes))
                        }
                    }
                }
            )
        },
        snackbarHost = {
            val msg = message
            if (showToast && msg != null) {
                Snackbar(
                    modifier = Modifier.padding(16.dp),
                    action = {
                        TextButton(onClick = { showToast = false; viewModel.clearMessage() }) {
                            Text(stringResource(R.string.memory_got_it))
                        }
                    }
                ) { Text(msg) }
            }
        },
    ) { padding ->
        // 图谱视图：六类记忆节点 + 六类关系边的交互画布（库 MemoryGraphContent
        // 自带统计条 / 过滤搜索 / 缩放控制 / 详情 Sheet；本页只补 Scaffold 外壳）
        if (graphMode) {
            MemoryGraphContent(
                state = graphState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 分区导航条（搜索态隐藏 —— 命中列表整段替换分区内容，锚点无意义）
            if (!searchActive) {
                MemorySectionNavRow(
                    anchors = anchors,
                    listStateFirstVisible = firstVisibleIndex,
                    onNavigate = navigateToSection,
                )
            }

            // 搜索框（顶栏开关控制显隐；空词时分区内容照常 —— 搜索是覆盖不是替换空态）
            if (showSearch) {
                OutlinedTextField(
                    value = query,
                    onValueChange = viewModel::onSearch,
                    label = { Text(stringResource(R.string.memory_search_label)) },
                    singleLine = true,
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = viewModel::clearSearch) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.memory_search_clear))
                            }
                        }
                    },
                    supportingText = {
                        if (query.isBlank()) Text(stringResource(R.string.memory_search_hint))
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }

            // 单列滚动列表（v3 核心）+ 下拉刷新（整页手势 —— 数据新鲜度
            // 不再只有重进页面一条路）
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = viewModel::refresh,
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(plan, key = { it.key }) { item ->
                        when (item) {
                            MemoryListItem.Stats -> StatsBlock(
                                stats = stats,
                                lastRefreshAt = lastRefreshAt,
                                onOpenSection = { section -> navigateToSection(section) },
                                onOpenGraph = { graphMode = true },
                            )

                            MemoryListItem.Health -> MemoryHealthCard(
                                quarantinedCount = quarantinedCount,
                                dreamRunning = dreamRunning,
                                onDreamNow = viewModel::maintenanceNow,
                                onClearQuarantine = viewModel::clearQuarantine,
                            )

                            MemoryListItem.Privacy -> PrivacyNotice()

                            is MemoryListItem.SearchSummary -> Column {
                                Text(
                                    stringResource(R.string.memory_search_summary_fmt, item.total),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                                )
                                if (item.total == 0) {
                                    EmptyHint(
                                        stringResource(R.string.memory_search_no_match_all, query),
                                        Icons.Outlined.SearchOff,
                                    )
                                }
                            }

                            is MemoryListItem.SearchGroupLabel -> Text(
                                searchGroupLabel(item.type),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                            )

                            is MemoryListItem.SearchHit -> SearchHitCard(
                                hit = item.hit,
                                query = query,
                                onJumpToGraph = { nodeId ->
                                    pendingGraphJump = nodeId
                                    graphMode = true
                                },
                                modifier = Modifier
                                    .animateItem()
                                    .padding(horizontal = 12.dp),
                            )

                            MemoryListItem.ChatHeader -> {
                                MemoryChatHeader(
                                    recordCount = chatMemory.sumOf { it.records.size },
                                    onClearAll = { showClearChatConfirm = true },
                                )
                                if (chatMemory.isEmpty()) {
                                    EmptyHint(stringResource(R.string.memory_chat_empty), Icons.Outlined.Forum)
                                }
                            }

                            is MemoryListItem.ChatEntry -> MemoryChatEntryCard(
                                entry = item.entry,
                                onDeleteRecord = { record -> pendingChatRecord = record },
                                modifier = Modifier
                                    .animateItem()
                                    .padding(horizontal = 12.dp),
                            )

                            MemoryListItem.KnowledgeHeader -> {
                                MemoryKnowledgeHeader(
                                    entityCount = knowledge.entities.size,
                                    relationCount = knowledge.relations.size,
                                )
                                if (knowledge.entities.isEmpty()) {
                                    EmptyHint(stringResource(R.string.memory_knowledge_empty), Icons.Outlined.Hub)
                                }
                            }

                            is MemoryListItem.KnowledgeEntity -> MemoryEntityCard(
                                entity = item.entity,
                                relations = relationsOf(item.entity.name, knowledge.relations),
                                expanded = item.entity.name in expandedEntities,
                                onToggle = {
                                    if (item.entity.name in expandedEntities) {
                                        expandedEntities.remove(item.entity.name)
                                    } else {
                                        expandedEntities.add(item.entity.name)
                                    }
                                },
                                onOpenInGraph = { name ->
                                    pendingGraphJump = "entity:$name"
                                    graphMode = true
                                },
                                modifier = Modifier
                                    .animateItem()
                                    .padding(horizontal = 12.dp),
                            )

                            is MemoryListItem.KnowledgeExpand -> Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center,
                            ) {
                                TextButton(onClick = { knowledgeExpanded = !item.expanded }) {
                                    Text(
                                        if (item.expanded) {
                                            stringResource(R.string.memory_knowledge_show_top)
                                        } else {
                                            stringResource(R.string.memory_knowledge_show_all_fmt, item.totalCount)
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                            }

                            MemoryListItem.EpisodesHeader -> SectionHeaderWithIcon(
                                icon = Icons.Outlined.History,
                                title = stringResource(R.string.MemoryRecentEpisodes),
                            )

                            is MemoryListItem.EpisodeFilterRow -> {
                                EpisodeFilterChips(
                                    row = item,
                                    onSelect = { episodeFilter = it },
                                )
                                val visible = filterEpisodes(episodes, item.current)
                                if (visible.isEmpty()) {
                                    EmptyHint(
                                        if (episodes.isEmpty()) {
                                            stringResource(R.string.memory_empty_episodes)
                                        } else {
                                            stringResource(R.string.memory_episode_filter_empty)
                                        },
                                        Icons.Outlined.History,
                                    )
                                }
                            }

                            is MemoryListItem.EpisodeCard -> EpisodeCard(
                                episode = item.episode,
                                onDelete = { pendingDelete = item.episode },
                                modifier = Modifier
                                    .animateItem()
                                    .padding(horizontal = 12.dp),
                            )

                            MemoryListItem.MacrosHeader -> {
                                SectionHeaderWithIcon(
                                    icon = Icons.Outlined.Repeat,
                                    title = stringResource(R.string.memory_frequent_macros),
                                )
                                if (macros.isEmpty()) {
                                    EmptyHint(stringResource(R.string.memory_empty_macros), Icons.Outlined.Repeat)
                                }
                            }

                            is MemoryListItem.MacroCard -> MacroCard(
                                macro = item.macro,
                                expanded = item.expanded,
                                onToggle = {
                                    if (item.expanded) expandedMacros.remove(item.macro.skillId)
                                    else expandedMacros.add(item.macro.skillId)
                                },
                                modifier = Modifier
                                    .animateItem()
                                    .padding(horizontal = 12.dp),
                            )

                            MemoryListItem.Footer -> Spacer(Modifier.height(16.dp))
                        }
                    }
                }
            }
        }
    }

    // ═══ 对话框族（破坏性操作二次确认 + 写入口）═══

    // 删除经历确认
    pendingDelete?.let { ep ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.memory_delete_episode_title)) },
            text = { Text(stringResource(R.string.memory_delete_episode_text, ep.goal)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteEpisode(ep.episodeId)
                    pendingDelete = null
                }) { Text(stringResource(R.string.memory_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.memory_cancel)) }
            }
        )
    }

    // 删除单条聊天记忆确认（v3 从 ChatMemorySection 包装上提 —— 卡片与
    // 确认对话框分离，条目离屏不丢确认现场）
    pendingChatRecord?.let { record ->
        AlertDialog(
            onDismissRequest = { pendingChatRecord = null },
            title = { Text(stringResource(R.string.memory_chat_delete_title)) },
            text = { Text(stringResource(R.string.memory_chat_delete_text, record.content)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteChatMemoryRecord(record.id)
                    pendingChatRecord = null
                }) { Text(stringResource(R.string.memory_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingChatRecord = null }) { Text(stringResource(R.string.memory_cancel)) }
            }
        )
    }

    // 一键清空聊天记忆确认（正文带条数与不可恢复提示）
    if (showClearChatConfirm) {
        val recordCount = chatMemory.sumOf { it.records.size }
        AlertDialog(
            onDismissRequest = { showClearChatConfirm = false },
            title = { Text(stringResource(R.string.memory_chat_clear_title)) },
            text = { Text(stringResource(R.string.memory_chat_clear_text, recordCount)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearChatMemory()
                    showClearChatConfirm = false
                }) { Text(stringResource(R.string.memory_chat_clear), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearChatConfirm = false }) {
                    Text(stringResource(R.string.memory_cancel))
                }
            }
        )
    }

    // 写入口对话框（v2）
    if (showSaveFactDialog) {
        SaveFactDialog(
            onDismiss = { showSaveFactDialog = false },
            onSave = viewModel::saveFactManually
        )
    }
    if (showTeachDialog) {
        TeachExperienceDialog(
            onDismiss = { showTeachDialog = false },
            onTeach = viewModel::teachExperience
        )
    }
}

// ═══ 概览区（六卡 + 上次刷新行）══════════════════════════════════

/**
 * 概览统计块（v3：六卡全部可点 —— 直达对应分区 / 切图谱；补「上次
 * 刷新」时间行，让快照新鲜度可感知）。
 */
@Composable
private fun StatsBlock(
    stats: MemoryViewModel.MemoryStats,
    lastRefreshAt: Long?,
    onOpenSection: (MemorySection) -> Unit,
    onOpenGraph: () -> Unit,
) {
    Column(Modifier.padding(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard(
                stringResource(R.string.memory_stats_episodes), "${stats.episodeCount}",
                Modifier.weight(1f),
                onClick = { onOpenSection(MemorySection.EPISODES) },
            )
            StatCard(
                stringResource(R.string.memory_nodes), "${stats.nodeCount}",
                Modifier.weight(1f),
                onClick = onOpenGraph,
            )
            StatCard(
                stringResource(R.string.memory_macros), "${stats.macroCount}",
                Modifier.weight(1f),
                onClick = { onOpenSection(MemorySection.MACROS) },
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard(
                stringResource(R.string.memory_stats_facts), "${stats.factCount}",
                Modifier.weight(1f),
                onClick = { onOpenSection(MemorySection.CHAT) },
            )
            StatCard(
                stringResource(R.string.memory_stats_entities), "${stats.entityCount}",
                Modifier.weight(1f),
                onClick = { onOpenSection(MemorySection.KNOWLEDGE) },
            )
            StatCard(
                stringResource(R.string.memory_stats_relations), "${stats.relationCount}",
                Modifier.weight(1f),
                onClick = { onOpenSection(MemorySection.KNOWLEDGE) },
            )
        }
        if (lastRefreshAt != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.memory_refreshed_at_fmt, formatTime(lastRefreshAt)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

// ═══ 隐私告知（#218）══════════════════════════════════════════════

/** 隐私告知条：记忆在本机处理、可随时关闭摄取/删除 —— 常驻可见。 */
@Composable
private fun PrivacyNotice() {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.PrivacyTip,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.size(8.dp))
            Text(
                stringResource(R.string.memory_capture_privacy_notice),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ═══ 通用分区头 / 经历过滤芯片行 ═════════════════════════════════

/** 带图标的分区头（经历 / 宏分区用；聊天 / 知识分区有各自专用头）。 */
@Composable
private fun SectionHeaderWithIcon(icon: ImageVector, title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp))
        Spacer(Modifier.size(6.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** 经历状态过滤芯片行（全部 + 四终态带计数；当前选中高亮）。 */
@Composable
private fun EpisodeFilterChips(
    row: MemoryListItem.EpisodeFilterRow,
    onSelect: (EpisodeFilter) -> Unit,
) {
    LazyRow(
        modifier = Modifier.padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "ef_all") {
            FilterChip(
                selected = row.current == EpisodeFilter.ALL,
                onClick = { onSelect(EpisodeFilter.ALL) },
                label = { Text(stringResource(R.string.memory_episode_filter_all)) },
            )
        }
        item(key = "ef_running") {
            FilterChip(
                selected = row.current == EpisodeFilter.RUNNING,
                onClick = { onSelect(EpisodeFilter.RUNNING) },
                label = { Text(chipLabel(R.string.memory_episode_filter_running, row.running)) },
            )
        }
        item(key = "ef_succeeded") {
            FilterChip(
                selected = row.current == EpisodeFilter.SUCCEEDED,
                onClick = { onSelect(EpisodeFilter.SUCCEEDED) },
                label = { Text(chipLabel(R.string.memory_episode_filter_succeeded, row.succeeded)) },
            )
        }
        item(key = "ef_failed") {
            FilterChip(
                selected = row.current == EpisodeFilter.FAILED,
                onClick = { onSelect(EpisodeFilter.FAILED) },
                label = { Text(chipLabel(R.string.memory_episode_filter_failed, row.failed)) },
            )
        }
        item(key = "ef_abandoned") {
            FilterChip(
                selected = row.current == EpisodeFilter.ABANDONED,
                onClick = { onSelect(EpisodeFilter.ABANDONED) },
                label = { Text(chipLabel(R.string.memory_episode_filter_abandoned, row.abandoned)) },
            )
        }
    }
}

/** 芯片标签 + 计数后缀（0 不缀 —— 零计数不加噪音）。 */
@Composable
private fun chipLabel(labelRes: Int, count: Int): String {
    val label = stringResource(labelRes)
    return if (count > 0) "$label $count" else label
}

/** 搜索分组标签（事实 / 实体 / 场景节点）。 */
@Composable
private fun searchGroupLabel(type: MemorySearchHitType): String = when (type) {
    MemorySearchHitType.FACT -> stringResource(R.string.memory_search_group_facts)
    MemorySearchHitType.ENTITY -> stringResource(R.string.memory_search_group_entities)
    MemorySearchHitType.NODE -> stringResource(R.string.memory_search_group_nodes)
}
