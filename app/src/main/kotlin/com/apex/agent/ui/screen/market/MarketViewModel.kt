package com.apex.agent.ui.screen.market

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apex.agent.core.tools.ToolCircuitBreaker
import com.apex.agent.core.tools.ToolTraceRecorder
import com.apex.agent.core.tools.ToolUsageTracker
import com.apex.agent.core.tools.connector.ConnectorDef
import com.apex.agent.core.tools.connector.ConnectorRegistry
import com.apex.agent.core.tools.marketplace.ClawHubSource
import com.apex.agent.core.tools.marketplace.ModelScopeSource
import com.apex.agent.core.tools.mcp.McpConfigImport
import com.apex.agent.core.tools.mcp.McpConfigValidator
import com.apex.agent.core.tools.mcp.McpManager
import com.apex.agent.core.tools.mcp.McpServerCatalog
import com.apex.agent.core.tools.mcp.McpServerConfig
import com.apex.agent.core.tools.mcp.McpStartupStage
import com.apex.agent.core.tools.mcp.McpStartupTracker
import com.apex.agent.core.tools.mcp.McpTransport
import com.apex.agent.core.tools.skill.SkillMenuProvider
import com.apex.agent.core.tools.skill.SkillRegistry
import com.apex.agent.marketplace.GithubFeaturedCatalog
import com.apex.agent.marketplace.MarketInstallManager
import com.apex.agent.platform.csmem.store.MemoryGraphStore
import com.apex.agent.plugin.host.PluginManager
import com.apex.agent.plugin.host.functional.FunctionalPluginStore
import com.apex.agent.plugin.host.functional.UnifiedPluginEntry
import com.apex.agent.plugin.host.functional.UnifiedPluginRegistry
import com.apex.agent.ui.language.LanguageManager
import com.apex.agent.R
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 市场页 ViewModel（v2 全面重构）
 *
 * 相比旧 MarketViewModel：
 * - **所有注册表操作移出主线程**：旧实现在 Main 线程直接调 install/setEnabled/
 *   discoverPlugins（扫盘/写文件/跨进程 IPC），点一下开关就掉帧。现在全部
 *   `Dispatchers.IO`。
 * - **功能补全**：MCP 添加/删除/启用开关（HTTP/SSE/STDIO）、连接器增删开关
 *   （ConnectorRegistry 持久化）、插件加载/卸载、Skill JSON 导入、
 *   魔搭/GitHub 两个集成源 + URL 导入——对齐并超越 PR45 的市场设计。
 * - **变更推送**：斜杠菜单由各注册表的 changes 流自动刷新（见 SlashMenuProvider v2），
 *   市场操作完成后无需手动通知菜单。
 */
@HiltViewModel
class MarketViewModel @Inject constructor(
    @ApplicationContext internal val appContext: Context,
    private val skillRegistry: SkillRegistry,
    private val skillMenuProvider: SkillMenuProvider,
    internal val mcpManager: McpManager,
    private val connectorRegistry: ConnectorRegistry,
    private val pluginManager: PluginManager,
    // Plugin System v2：统一插件门面（双形态目录 / 功能插件启停装卸）
    private val unifiedPluginRegistry: UnifiedPluginRegistry,
    private val installManager: MarketInstallManager,
    private val modelScopeSource: ModelScopeSource,
    private val clawHubSource: ClawHubSource,
    // 官方 Hub 仓库（技能 + MCP 双目录）——独立状态域，God-file 预算拆分
    val hub: MarketHubController,
    // mcp.so 社区目录（MCP 页签长尾源）——同构独立状态域
    val mcpSo: MarketMcpSoController,
    // MCP Registry 目录（官方 Registry + PulseMCP 源切换）——同构独立状态域
    val registry: MarketRegistryController,
    // Operit 社区插件（GitHub 聚合，MCP 形态探测安装）——同构独立状态域
    val operit: MarketOperitController,
    // 语言切换：VM 侧消息（snackbar）按当前语言取词
    internal val languageManager: LanguageManager,
    // ── v2 认知市场：注入 cs-mem + 工具分析四件套（均为 @Singleton）──
    private val memoryGraphStore: MemoryGraphStore,
    private val usageTracker: ToolUsageTracker,
    private val circuitBreaker: ToolCircuitBreaker,
    private val traceRecorder: ToolTraceRecorder
) : ViewModel() {

    /** 技能分析投影器（聚合 cs-mem + 工具统计 + 熔断 + 轨迹 → 详情 UI 状态）。 */
    private val skillAnalytics = SkillAnalytics(
        memoryGraphStore, usageTracker, circuitBreaker, traceRecorder
    )

    internal val _uiState = MutableStateFlow(MarketUiState())
    val uiState: StateFlow<MarketUiState> = _uiState.asStateFlow()

    /** 魔搭全量列表（过滤基于全量，避免在已过滤结果上二次过滤后无法还原）。 */
    private var allModelScopeSkills: List<ModelScopeSource.ModelScopeSkill> = emptyList()

    /** ClawHub 当前页偏移（加载更多时 offset += 一页）。 */
    private var clawHubOffset = 0

    /** ClawHub 当前是否处于搜索结果模式（重试时区分走搜索还是热门）。 */
    private var clawHubSearchMode = false

    /** 当前搜索结果集对应的查询词（翻页/重试用，避免用户改了输入框但未点搜索时错页）。 */
    private var clawHubSearchQuery = ""

    /**
     * #205 启动事件追踪器：弹窗关掉后事件仍留底 —— 「已安装管理」里的
     * 时间线按钮可回放每台服务器的真实启动史（pid/argv/serverInfo/stderr）。
     */
    internal val startupTracker = McpStartupTracker()

    /** 安装确认的挂起等待桥（awaitInstallConfirmation ↔ confirm/dismiss）。 */
    private var pendingInstallDecision: kotlinx.coroutines.CompletableDeferred<Boolean>? = null

    init {
        refresh()
        loadMcpCatalog()
        // Hub / mcp.so 安装完成 → 刷新市场快照（已装徽标 / MCP 列表联动）。
        // P1 修复：mcpSo.refreshMarket 此前三处 tryEmit 全仓零收集者 —— mcp.so
        // 源安装后徽标/已配置列表不刷新，与 Hub 源行为不对称。
        hub.refreshMarket
            .onEach { refresh() }
            .launchIn(viewModelScope)
        mcpSo.refreshMarket
            .onEach { refresh() }
            .launchIn(viewModelScope)
        // Registry 目录（官方 Registry / PulseMCP）与 Operit 社区插件安装完成
        // → 同口径刷新市场快照（已装徽标 / 已配置列表联动）。
        registry.refreshMarket
            .onEach { refresh() }
            .launchIn(viewModelScope)
        operit.refreshMarket
            .onEach { refresh() }
            .launchIn(viewModelScope)
        // P1 修复：接入安装确认门禁（市场内容不可信 → 干运行预览 → 用户目检
        // → 才落盘+激活）。唤醒已建成但零接线的 ManifestDryRunDialog 三件套。
        installManager.installConfirmGate = { preview ->
            withContext(Dispatchers.Main) { awaitInstallConfirmation(preview) }
        }
    }

    /** 挂起等待用户对安装预览的确认（主线程弹窗， CompletableDeferred 桥接）。 */
    private suspend fun awaitInstallConfirmation(
        preview: com.apex.agent.marketplace.MarketInstallManager.ManifestPreview
    ): Boolean {
        // P2 修复（并发覆盖）：两个不同源的安装并发到达 gate 时，第二次赋值
        // 会覆盖第一个 deferred（永不完成 → 协程永久挂起 + 泄漏）。先按
        // 「取消」结算旧的（对话框互斥，同时只应有一个预览）。
        pendingInstallDecision?.let { old ->
            runCatching { old.complete(false) }
        }
        val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
        pendingInstallDecision = deferred
        _uiState.update { it.copy(installPreview = preview) }
        // P1 修复（gate 永久挂起）：无超时克星 —— 预览对话框被任何路径抹掉
        //（见 refresh 的 installPreview 保留修复）后，没有任何代码会 complete
        // 这个 deferred，安装协程永久挂起、安装按钮永久禁用。60s 兜底：
        // 超时视为用户未确认，走取消分支收尾（对话框若仍在，用户仍可点
        // 确认 —— 那时 pending 已清理，confirm/dismiss 是幂等 no-op）。
        val confirmed = kotlinx.coroutines.withTimeoutOrNull(INSTALL_CONFIRM_TIMEOUT_MS) {
            deferred.await()
        } ?: false
        if (pendingInstallDecision === deferred) {
            pendingInstallDecision = null
            _uiState.update { it.copy(installPreview = null) }
        }
        return confirmed
    }

    /** 用户在安装预览对话框点「确认安装」。 */
    fun confirmPendingInstall() {
        pendingInstallDecision?.complete(true)
        pendingInstallDecision = null
        _uiState.update { it.copy(installPreview = null) }
    }

    /** 用户在安装预览对话框点「取消」（或点外部关闭）。 */
    fun dismissPendingInstall() {
        pendingInstallDecision?.complete(false)
        pendingInstallDecision = null
        _uiState.update { it.copy(installPreview = null) }
    }

    /**
     * P1/P2 修复（VM 销毁时的门禁收尾）：挂起在 gate 上的安装协程随
     * viewModelScope 取消而终结，但单例控制器（Hub 等）自持 scope 的
     * 安装协程不会 —— 不清理 gate 会让它们的 busy 标志永不复位（安装
     * 按钮永久禁用，MarketHubController.installSkill 守卫直接 return）。
     */
    override fun onCleared() {
        pendingInstallDecision?.let { runCatching { it.complete(false) } }
        pendingInstallDecision = null
        installManager.installConfirmGate = null
        super.onCleared()
    }

    /** 全量刷新（IO 线程）：技能/MCP/连接器/插件快照 + cs-mem 健康数据。保留集成源列表避免安装后列表闪失。 */
    fun refresh() {
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) { snapshotState() } ?: return@launch
            _uiState.update { state -> snapshot.copy(
                // 视图与瞬时态保留：scope 不保留 →「已安装管理」里任何开关/卸载/连接
                // （全部以 refresh() 收尾）都会把顶栏弹回「市场」视图；mcpConnecting
                // 不保留 → 长连接期间重进屏幕丢失防双击并发保护。
                // #206 修复：tier 不保留 → 任何操作后 Coding 市场选择被弹回 Agent 市场。
                scope = state.scope,
                mcpConnecting = state.mcpConnecting,
                tier = state.tier,
                // #205：进度弹窗与目录状态跨 refresh 保留 —— 连接完成后
                // refresh() 不再把弹窗抹掉（完整时间线由用户手动关闭）；
                // 目录资产只加载一次，不能被快照重置回空表。
                mcpStartup = state.mcpStartup,
                mcpCatalog = state.mcpCatalog,
                mcpCatalogLoaded = state.mcpCatalogLoaded,
                mcpCatalogError = state.mcpCatalogError,
                mcpCatalogCategory = state.mcpCatalogCategory,
                catalogEnvEntry = state.catalogEnvEntry,
                selectedTab = state.selectedTab,
                modelScopeQuery = state.modelScopeQuery,
                modelScopeSkills = state.modelScopeSkills,
                modelScopeLoading = state.modelScopeLoading,
                modelScopeError = state.modelScopeError,
                githubQuery = state.githubQuery,
                githubHits = state.githubHits,
                githubSearching = state.githubSearching,
                githubError = state.githubError,
                // GitHub 智能安装候选弹窗跨 refresh 保留（与 installPreview 同类
                // 修复）：PickNeeded 置入候选后本方法会以 refresh() 收尾 ——
                // 不保留的话快照新建的 UiState 把候选抹回空表，多候选弹窗
                // 刚弹即被清除（用户视角「点了没反应」）；逐个挑选流程同样
                // 依赖剩余候选跨 refresh 存活。
                githubCandidates = state.githubCandidates,
                githubCandidatesRepo = state.githubCandidatesRepo,
                skillSource = state.skillSource,
                clawHubSkills = state.clawHubSkills,
                clawHubLoading = state.clawHubLoading,
                clawHubError = state.clawHubError,
                clawHubQuery = state.clawHubQuery,
                clawHubQueryLoading = state.clawHubQueryLoading,
                clawHubInstallingSlug = state.clawHubInstallingSlug,
                clawHubHasMore = state.clawHubHasMore,
                clawHubSearchActive = state.clawHubSearchActive,
                categoryFilter = state.categoryFilter,
                skillQuery = state.skillQuery,
                skillSuggestions = state.skillSuggestions,
                detailSkillId = state.detailSkillId,
                detailState = state.detailState,
                detailConfigForm = state.detailConfigForm,
                detailLoading = state.detailLoading,
                busy = state.busy,
                lastMessage = state.lastMessage,
                // P1 修复（gate 死锁）：installPreview 也必须跨 refresh 保留 ——
                // 任何后台安装完成/连接完成触发的 refresh 会把预览对话框抹掉，
                // 但 gate 的 deferred 没人 complete → 安装协程永久挂起、
                // busy/clawHubInstallingSlug/Hub 的 installingSkillId 永不复位
                // → 全部安装按钮永久禁用（无恢复手段，只能重启 App）。
                installPreview = state.installPreview
            ) }
        }
    }

    private suspend fun snapshotState(): MarketUiState? {
        return runCatching {
            val installed = skillRegistry.getInstalled()
            val installedIds = installed.map { it.manifest.id }.toSet()

            // cs-mem 健康批量投影：为每个已安装技能取 energy/crystallized/lastUsedAt/success/failure
            val healthMap = skillAnalytics.batchProjectSkillHealth(installedIds.toList())

            val skills = installed.map {
                val h = healthMap[it.manifest.id]
                MarketSkillRow(
                    id = it.manifest.id,
                    name = it.manifest.name,
                    description = it.manifest.description,
                    installed = true,
                    // per-scope 启停（双工位隔离）：分级开关各自独立读写
                    enabledAgent = it.enabledAgent,
                    enabledCoding = it.enabledCoding,
                    // 就绪门禁：必填配置未填齐 → 「需配置」徽标 + 菜单隐藏
                    needsConfig = !skillRegistry.isConfigured(it.manifest.id),
                    energy = h?.energy ?: 1.0f,
                    isCrystallized = h?.isCrystallized ?: false,
                    lastUsedAt = h?.lastUsedAt ?: 0,
                    successCount = h?.successCount ?: 0,
                    failureCount = h?.failureCount ?: 0,
                    category = it.manifest.category,
                    tags = it.manifest.tags,
                    author = it.manifest.author,
                    version = it.manifest.version,
                    trustLevel = it.manifest.trustLevel,
                    bundled = it.manifest.bundled,
                    scope = it.manifest.scope
                )
            }
            val templates = skillMenuProvider.getBuiltinTemplates().map { t ->
                val tpl = SkillMenuProvider.BUILTIN_TEMPLATES.firstOrNull { it.id == t.id }
                MarketSkillRow(
                    id = t.id, name = t.label, description = t.description,
                    installed = false, enabledAgent = false, enabledCoding = false,
                    source = "builtin", builtin = true,
                    category = tpl?.category,
                    tags = tpl?.tags.orEmpty()
                )
            }
            val connected = mcpManager.getConnectedServers().toSet()
            val mcps = mcpManager.getConfigs().map {
                MarketMcpRow(
                    name = it.name,
                    endpoint = it.endpointSummary(),
                    transport = it.transport,
                    enabled = it.enabled,
                    connected = it.name in connected,
                    builtin = it.transport == McpTransport.BUILTIN,
                    scope = it.scope
                )
            }
            val connectors = connectorRegistry.getAll()
            val loaded = pluginManager.loadedPlugins.value.keys
            val plugins = pluginManager.discoverPlugins().map {
                MarketPluginRow(it.packageName, it.label, it.packageName in loaded)
            }
            // Plugin System v2：统一目录对账（功能性插件磁盘扫描 + APK
            // 已加载快照合并——同一 IO 调度器上扫盘，与既有插件发现同轮次）。
            unifiedPluginRegistry.refresh()
            val installedPlugins = unifiedPluginRegistry.catalog.value
            MarketUiState(
                skills = skills,
                skillTemplates = templates,
                mcps = mcps,
                connectors = connectors,
                plugins = plugins,
                installedPlugins = installedPlugins,
                installedModelScopeIds = installedIds
            )
        }.getOrNull()
    }

    fun selectTab(tab: MarketTab) = _uiState.update { it.copy(selectedTab = tab) }

    /** 切换顶栏视图（市场 ⇄ 已安装管理），保留当前子页签。 */
    fun selectScope(scope: MarketScope) = _uiState.update { it.copy(scope = scope) }

    // #206：selectTier 迁至 MCP 目录区（切换分级时同步校正目录分类选择），见下方。

    /** #197 当前分级下可见的技能列表（scope=all 两级都保留）。 */
    fun visibleSkills(state: MarketUiState): List<MarketSkillRow> {
        val scope = state.tier.name.lowercase()
        return state.skills.filter { it.scope == "all" || it.scope == scope }
    }

    /** #197 当前分级下可见的 MCP 列表。 */
    fun visibleMcps(state: MarketUiState): List<MarketMcpRow> {
        val scope = state.tier.name.lowercase()
        return state.mcps.filter { it.scope == "all" || it.scope == scope }
    }

    fun clearMessage() = _uiState.update { it.copy(lastMessage = null) }

    internal fun message(msg: String) = _uiState.update { it.copy(lastMessage = msg) }

    // ═══ Skills ═══

    /** 当前市场分级的 scope 值（安装默认启用工位 / 分级开关写入口径）。 */
    private fun tierScope(): String = _uiState.value.tier.name.lowercase()

    /**
     * 分级独立启停（per-scope）：开关只写当前 [MarketTier] 对应的工位 ——
     * Coding 市场里关掉不影响 Agent 市场（修「两分级同步」问题；另一侧
     * 的开关状态由 [MarketSkillRow.enabledAgent]/[enabledCoding] 各自展示）。
     */
    fun toggleSkill(skillId: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            skillRegistry.setEnabledForScope(skillId, tierScope(), enabled)
            refresh()
        }
    }

    /**
     * 保存技能配置值（详情对话框表单）：写入后 SkillRegistry 发 changes ——
     * 斜杠菜单自动重建（配置齐了条目即刻出现），市场行「需配置」徽标即时消隐。
     */
    fun saveSkillConfig(skillId: String, values: Map<String, String>) {
        viewModelScope.launch(Dispatchers.IO) {
            skillRegistry.setConfigValues(skillId, values)
            message(languageManager.getString(R.string.market_skill_config_saved))
            refresh()
        }
    }

    /**
     * 卸载技能。内置技能（bundled）不可卸载——提前拦下并给出正确提示。
     */
    fun uninstallSkill(skillId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            // Issue #166：内置技能不可卸载（SkillRegistry.uninstall 对 bundled 恒 false），
            // 提前拦下并给出正确提示——否则会落进「未找到技能」的误导文案。
            if (_uiState.value.skills.any { it.id == skillId && it.bundled }) {
                message(languageManager.getString(R.string.market_skill_bundled_uninstall_blocked_hint))
                return@launch
            }
            val ok = skillRegistry.uninstall(skillId)
            message(
                if (ok) {
                    languageManager.getString(R.string.market_skill_uninstalled).format(skillId)
                } else {
                    languageManager.getString(R.string.market_skill_not_found).format(skillId)
                }
            )
            refresh()
        }
    }

    fun installSkillTemplate(templateId: String) {
        viewModelScope.launch {
            installManager.installSkillTemplate(templateId).fold(
                onSuccess = { message(it) },
                onFailure = {
                    message(languageManager.getString(R.string.market_install_failed).format(it.message ?: ""))
                }
            )
            refresh()
        }
    }

    /** 导入自定义 Skill JSON（粘贴内容）。安装默认启用工位 = 当前市场分级。 */
    fun importSkillJson(content: String) {
        viewModelScope.launch {
            installManager.installSkillFromJson(content, defaultScope = tierScope()).fold(
                onSuccess = { message(it) },
                onFailure = {
                    message(languageManager.getString(R.string.market_import_failed).format(it.message ?: ""))
                }
            )
            refresh()
        }
    }

    /** 从 URL 导入 Skill manifest。安装默认启用工位 = 当前市场分级。 */
    fun importSkillFromUrl(url: String) {
        viewModelScope.launch {
            installManager.installSkillFromUrl(url, defaultScope = tierScope()).fold(
                onSuccess = { message(it) },
                onFailure = {
                    message(languageManager.getString(R.string.market_import_failed).format(it.message ?: ""))
                }
            )
            refresh()
        }
    }

    /**
     * 从本地文件导入 Skill（.zip / .json 自动识别）。
     *
     * 用户反馈"Skills 不能自己导入"：本地 skill 包此前没有任何入口。
     * zip 走 SafeZipExtractor（路径穿越 / zip bomb 防御），json 直接装 manifest。
     */
    fun importSkillFromFile(uri: android.net.Uri) {
        viewModelScope.launch {
            installManager.installSkillFromFile(uri).fold(
                onSuccess = { message(it) },
                onFailure = {
                    message(it.message ?: languageManager.getString(R.string.market_local_import_failed))
                }
            )
            refresh()
        }
    }

    /**
     * 从本地文件导入 MCP 配置（`{"mcpServers": {...}}` 形态的 .json）。
     * 复用 [importMcpConfig] 的解析与逐条报错管道。
     */
    fun importMcpConfigFromFile(uri: android.net.Uri) {
        viewModelScope.launch {
            installManager.readTextFile(uri).fold(
                onSuccess = { text -> importMcpConfig(text) },
                onFailure = {
                    message(
                        languageManager.getString(R.string.market_read_file_failed)
                            .format(it.message ?: "")
                    )
                }
            )
        }
    }

    // ═══ MCP ═══

    // MCP 工位服务器操作 / 精选目录 / 启动时间线已按 God-file 预算拆至
    // MarketViewModelMcpOps.kt 与 MarketViewModelMcpCatalog.kt（internal 扩展，
    // 模式同 CodeLongTaskCenterOps.kt —— 调用点与方法引用语义不变）。

    // ═══ MCP · 配置编辑（已安装管理「编辑」入口）═══

    /** 正在编辑的 MCP 配置快照（null = 编辑器关闭）。UI 据此渲染 [EditMcpDialog]。 */
    internal val _editingMcp = MutableStateFlow<McpServerConfig?>(null)
    val editingMcp: StateFlow<McpServerConfig?> = _editingMcp.asStateFlow()

    /**
     * #197/#206 切换市场分级：同时校正目录分类选择 —— coding 专属分类在
     * Agent 分级下无条目，旧选择留着只会得到空列表。
     */
    fun selectTier(tier: MarketTier) {
        _uiState.update { state ->
            val catStillVisible = state.mcpCatalogCategory?.let { cat ->
                state.mcpCatalog.any { it.category == cat && it.visibleToTier(tier.name.lowercase()) }
            } ?: true
            state.copy(
                tier = tier,
                mcpCatalogCategory = if (catStillVisible) state.mcpCatalogCategory else null
            )
        }
    }

    // ═══ 连接器 ═══

    fun addConnector(id: String, name: String, type: String, endpoint: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val trimmedId = id.trim()
            connectorRegistry.add(
                ConnectorDef(id = trimmedId, name = name.trim(), type = type, endpoint = endpoint.trim())
            ).fold(
                onSuccess = {
                    message(languageManager.getString(R.string.market_connector_added).format(trimmedId))
                },
                onFailure = {
                    message(languageManager.getString(R.string.market_add_failed).format(it.message ?: ""))
                }
            )
            refresh()
        }
    }

    fun toggleConnector(id: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            connectorRegistry.setEnabled(id, enabled)
            refresh()
        }
    }

    fun removeConnector(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            connectorRegistry.remove(id)
            message(languageManager.getString(R.string.market_connector_removed).format(id))
            refresh()
        }
    }

    // ═══ 插件 ═══

    fun loadPlugin(row: MarketPluginRow) {
        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) {
                pluginManager.discoverPlugins().firstOrNull { it.packageName == row.packageName }
            } ?: return@launch
            // bindService 需在 Looper 线程调用（Context 契约），回调本身回主线程
            withContext(Dispatchers.Main) { pluginManager.loadPlugin(info) }
            message(languageManager.getString(R.string.market_plugin_load_requested).format(info.label))
            refresh()
        }
    }

    fun unloadPlugin(packageName: String) {
        viewModelScope.launch {
            // unbindService 同样需在主线程执行
            withContext(Dispatchers.Main) { pluginManager.unloadPlugin(packageName) }
            message(languageManager.getString(R.string.market_plugin_unloaded).format(packageName))
            refresh()
        }
    }

    // ═══ Plugin System v2：功能性插件启停 / 卸载 / 导入（统一门面路由） ═══

    /**
     * 功能性插件启停：标记文件 + 工具宏注册/回收 + 目录刷新（一体化，
     * 见 UnifiedPluginRegistry.setEnabledFunctional）。磁盘标记写入与
     * 注册表操作在 IO 调度器上执行（对齐 toggleSkill 的线程纪律）。
     */
    fun toggleFunctionalPlugin(id: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = unifiedPluginRegistry.setFunctionalEnabled(id, enabled)
            if (!ok) {
                message(languageManager.getString(R.string.market_plugin_not_found).format(id))
            }
            refresh()
        }
    }

    /**
     * 功能性插件卸载：工具回收 + 目录删除 + 刷新。结果如实反馈
     * （market_plugin_uninstalled / market_plugin_not_found）。
     */
    fun uninstallFunctionalPlugin(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = unifiedPluginRegistry.uninstallFunctionalPlugin(id)
            message(
                if (ok) {
                    languageManager.getString(R.string.market_plugin_functional_uninstalled).format(id)
                } else {
                    languageManager.getString(R.string.market_plugin_not_found).format(id)
                }
            )
            refresh()
        }
    }

    /**
     * 功能性插件导入（plugin.json 文本）：校验 → 落盘 → 默认启用态激活。
     * 安装结果分档反馈（成功 / 升级 / 幂等 / 拒绝理由），全部人话。
     */
    fun importFunctionalPlugin(manifestJson: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = unifiedPluginRegistry.installFunctionalPlugin(manifestJson)
            message(
                when (result) {
                    is FunctionalPluginStore.InstallResult.Installed ->
                        languageManager.getString(R.string.market_plugin_functional_installed)
                            .format(result.manifest.displayName)
                    is FunctionalPluginStore.InstallResult.Upgraded ->
                        languageManager.getString(R.string.market_plugin_functional_upgraded)
                            .format(result.manifest.displayName)
                    is FunctionalPluginStore.InstallResult.Unchanged ->
                        languageManager.getString(R.string.market_plugin_functional_unchanged)
                            .format(result.manifest.displayName)
                    is FunctionalPluginStore.InstallResult.Rejected ->
                        languageManager.getString(R.string.market_plugin_functional_rejected)
                            .format(result.reason)
                }
            )
            refresh()
        }
    }

    // ═══ 集成：魔搭 ═══

    fun updateModelScopeQuery(query: String) =
        _uiState.update { it.copy(modelScopeQuery = query) }

    fun loadModelScopeSkills() {
        viewModelScope.launch {
            _uiState.update { it.copy(modelScopeLoading = true, modelScopeError = null) }
            modelScopeList()
            _uiState.update { it.copy(modelScopeLoading = false) }
        }
    }

    private suspend fun modelScopeList() {
        modelScopeSource.listSkills().fold(
            onSuccess = { skills ->
                allModelScopeSkills = skills
                val filtered = modelScopeSource.filterSkills(skills, _uiState.value.modelScopeQuery)
                _uiState.update { it.copy(modelScopeSkills = filtered) }
            },
            onFailure = { e ->
                allModelScopeSkills = emptyList()
                _uiState.update { it.copy(modelScopeSkills = emptyList(), modelScopeError = e.message) }
            }
        )
    }

    fun filterModelScope(query: String) {
        _uiState.update { it.copy(modelScopeQuery = query) }
        val filtered = modelScopeSource.filterSkills(allModelScopeSkills, query)
        _uiState.update { it.copy(modelScopeSkills = filtered) }
    }

    fun installModelScopeSkill(skill: ModelScopeSource.ModelScopeSkill) {
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            installManager.installModelScopeSkill(skill, defaultScope = tierScope()).fold(
                onSuccess = { message(it) },
                onFailure = {
                    message(
                        languageManager.getString(R.string.market_modelscope_install_failed)
                            .format(it.message ?: "")
                    )
                }
            )
            _uiState.update { it.copy(busy = false) }
            refresh()
        }
    }

    // ═══ 集成：GitHub ═══

    fun updateGithubQuery(query: String) = _uiState.update { it.copy(githubQuery = query) }

    fun searchGithub() {
        viewModelScope.launch {
            val query = _uiState.value.githubQuery.trim()
            if (query.isBlank()) return@launch
            _uiState.update { it.copy(githubSearching = true, githubError = null) }
            installManager.searchGitHubSkills(query).fold(
                onSuccess = { hits -> _uiState.update { it.copy(githubHits = hits) } },
                onFailure = { e -> _uiState.update { it.copy(githubHits = emptyList(), githubError = e.message) } }
            )
            _uiState.update { it.copy(githubSearching = false) }
        }
    }

    fun installGithubRepo(fullName: String) = installFromRepoInput(fullName)

    /**
     * 真实联网智能安装：接受 `owner/repo`、GitHub 链接、git@ 链接任一种写法。
     * 按内容类型自动路由（技能 / MCP server / 技能集合）：
     * - 根技能形态 → 原路径直装（零行为回归）；
     * - MCP 形态（server.json / .mcp.json / src monorepo，如
     *   modelcontextprotocol/servers）→ RegistryMcpInstaller 决策树；
     * - 技能集合（子目录 SKILL.md，如 anthropics/skills）→ 候选列表；
     * 多候选时置入 githubCandidates 弹选择框，单候选直接装完。
     */
    fun installFromRepoInput(input: String) {
        if (_uiState.value.busy) return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            installManager.installSmartFromGithub(input).fold(
                onSuccess = { outcome ->
                    when (outcome) {
                        is MarketInstallManager.GithubInstallOutcome.Done -> message(outcome.message)
                        is MarketInstallManager.GithubInstallOutcome.PickNeeded -> _uiState.update {
                            it.copy(
                                githubCandidates = outcome.candidates,
                                githubCandidatesRepo = outcome.repoTitle
                            )
                        }
                    }
                },
                onFailure = {
                    message(languageManager.getString(R.string.market_install_failed).format(it.message ?: ""))
                }
            )
            _uiState.update { it.copy(busy = false) }
            refresh()
        }
    }

    /** 安装单个 GitHub 候选（用户在候选列表点选）。 */
    fun installGithubCandidate(candidate: MarketInstallManager.GithubRepoCandidate) {
        if (_uiState.value.busy) return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            installManager.installGithubCandidate(candidate).fold(
                onSuccess = { msg ->
                    // 装完该项后从候选列表移除（支持逐个挑选）；列表清空
                    // 时一并关弹窗
                    _uiState.update { state ->
                        val rest = state.githubCandidates.filterNot { it.key == candidate.key }
                        if (rest.isEmpty()) {
                            state.copy(githubCandidates = emptyList(), githubCandidatesRepo = null)
                        } else {
                            state.copy(githubCandidates = rest)
                        }
                    }
                    message(msg)
                },
                onFailure = {
                    message(languageManager.getString(R.string.market_install_failed).format(it.message ?: ""))
                }
            )
            _uiState.update { it.copy(busy = false) }
            refresh()
        }
    }

    /** 一键安装全部 GitHub 候选（逐个顺序装，失败项点名不中断）。 */
    fun installAllGithubCandidates() {
        val candidates = _uiState.value.githubCandidates
        if (candidates.isEmpty() || _uiState.value.busy) return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            installManager.installAllGithubCandidates(candidates).fold(
                onSuccess = { msg ->
                    _uiState.update { it.copy(githubCandidates = emptyList(), githubCandidatesRepo = null) }
                    message(msg)
                },
                onFailure = {
                    message(languageManager.getString(R.string.market_install_failed).format(it.message ?: ""))
                }
            )
            _uiState.update { it.copy(busy = false) }
            refresh()
        }
    }

    /** 关闭 GitHub 候选选择弹窗（不安装任何内容）。 */
    fun dismissGithubCandidates() =
        _uiState.update { it.copy(githubCandidates = emptyList(), githubCandidatesRepo = null) }

    // ═══ Skills 页签：GitHub 精选（顶级开源仓库 coding 技能专栏）═══

    /**
     * 安装一条 GitHub 精选技能（anthropics/skills、obra/superpowers 等已核实
     * 仓库的 SKILL.md → prompt 型技能，复用既有 GitHub 候选安装管线）。
     *
     * 安装默认启用工位 = 当前市场分级（精选条目 manifest scope 固定 coding ——
     * 只出现在 Coding 市场与 Coding 屏 / 菜单，与 Agent 分级完全隔离）。
     */
    fun installGithubFeatured(entry: GithubFeaturedCatalog.FeaturedSkill) {
        if (_uiState.value.githubFeaturedInstallingId != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(githubFeaturedInstallingId = entry.installId) }
            try {
                installManager.installGithubCandidate(
                    GithubFeaturedCatalog.toCandidate(entry),
                    defaultScope = tierScope()
                ).fold(
                    onSuccess = { message(it) },
                    onFailure = {
                        message(
                            languageManager.getString(R.string.market_install_failed)
                                .format(it.message ?: "")
                        )
                    }
                )
            } finally {
                _uiState.update { it.copy(githubFeaturedInstallingId = null) }
                refresh()
            }
        }
    }

    // ═══ Skills 页签：ClawHub 技能仓库 ═══

    /** 切换 Skills 页签的仓库源（本地 ⇄ ClawHub）。 */
    fun selectSkillSource(source: SkillRepoSource) =
        _uiState.update { it.copy(skillSource = source) }

    fun updateClawHubQuery(query: String) =
        _uiState.update { it.copy(clawHubQuery = query) }

    /** 加载 ClawHub 热门列表（第一页；也用作「返回热门」）。 */
    fun loadClawHubTrending() {
        viewModelScope.launch {
            _uiState.update { it.copy(clawHubLoading = true, clawHubError = null) }
            clawHubSearchMode = false
            clawHubOffset = 0
            clawHubSource.listTrending(limit = ClawHubSource.TRENDING_PAGE_SIZE, offset = 0).fold(
                onSuccess = { page ->
                    clawHubOffset = ClawHubSource.TRENDING_PAGE_SIZE
                    _uiState.update {
                        it.copy(
                            clawHubSkills = page.entries,
                            clawHubHasMore = page.hasMore,
                            clawHubSearchActive = false
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(clawHubSkills = emptyList(), clawHubError = e.message) }
                }
            )
            _uiState.update { it.copy(clawHubLoading = false) }
        }
    }

    /** 关键词搜索（空关键词退回热门列表，与搜索端点「必须有关键词」的行为对齐）。 */
    fun searchClawHub() {
        val query = _uiState.value.clawHubQuery.trim()
        if (query.isEmpty()) {
            loadClawHubTrending()
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(clawHubQueryLoading = true, clawHubError = null) }
            clawHubSearchMode = true
            clawHubSearchQuery = query
            clawHubOffset = 0
            clawHubSource.search(query, limit = ClawHubSource.DEFAULT_PAGE_SIZE, offset = 0).fold(
                onSuccess = { page ->
                    clawHubOffset = ClawHubSource.DEFAULT_PAGE_SIZE
                    _uiState.update {
                        it.copy(
                            clawHubSkills = page.entries,
                            clawHubHasMore = page.hasMore,
                            clawHubSearchActive = true
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(clawHubSkills = emptyList(), clawHubError = e.message) }
                }
            )
            _uiState.update { it.copy(clawHubQueryLoading = false) }
        }
    }

    /**
     * 加载下一页（追加并按 owner/slug 去重；热门与搜索模式共用）。
     *
     * 服务端 offset 翻页实测暂不生效（返回重复页）：去重后 0 新条目时
     * 自动收起「加载更多」，避免无限空点；若服务端将来修复 offset，
     * 本逻辑无需改动即自动恢复真分页。
     */
    fun loadMoreClawHub() {
        val current = _uiState.value
        if (current.clawHubLoading || current.clawHubQueryLoading || !current.clawHubHasMore) return
        viewModelScope.launch {
            _uiState.update { it.copy(clawHubLoading = true) }
            val pageSize = if (clawHubSearchMode) {
                ClawHubSource.DEFAULT_PAGE_SIZE
            } else {
                ClawHubSource.TRENDING_PAGE_SIZE
            }
            val result = if (clawHubSearchMode) {
                clawHubSource.search(
                    clawHubSearchQuery,
                    limit = pageSize,
                    offset = clawHubOffset
                )
            } else {
                clawHubSource.listTrending(
                    limit = pageSize,
                    offset = clawHubOffset
                )
            }
            result.fold(
                onSuccess = { page ->
                    clawHubOffset += pageSize
                    _uiState.update { state ->
                        val seen = state.clawHubSkills.map { it.key }.toHashSet()
                        val fresh = page.entries.filter { it.key !in seen }
                        state.copy(
                            clawHubSkills = state.clawHubSkills + fresh,
                            // 全重复页（服务端 offset 未生效）→ 收起加载更多
                            clawHubHasMore = page.hasMore && fresh.isNotEmpty()
                        )
                    }
                },
                onFailure = { e ->
                    // 追加失败：保留已有列表，仅提示错误，不动页码
                    _uiState.update { it.copy(clawHubError = e.message) }
                }
            )
            _uiState.update { it.copy(clawHubLoading = false) }
        }
    }

    /** 失败重试（按当前模式走热门或搜索）。 */
    fun retryClawHub() {
        if (clawHubSearchMode) searchClawHub() else loadClawHubTrending()
    }

    /** 从 ClawHub 安装（下载 ZIP → 转 prompt 型技能 → SkillRegistry）。安装默认启用工位 = 当前市场分级。 */
    fun installClawHub(entry: ClawHubSource.ClawHubSkillEntry) {
        viewModelScope.launch {
            _uiState.update { it.copy(clawHubInstallingSlug = entry.slug) }
            try {
                installManager.installClawHubSkill(entry, defaultScope = tierScope()).fold(
                    onSuccess = { message(it) },
                    onFailure = {
                        message(
                            languageManager.getString(R.string.market_clawhub_install_failed)
                                .format(it.message ?: "")
                        )
                    }
                )
            } finally {
                // 无论成败都复位行级 busy 并刷新已安装列表（「已安装」Chip 依据 skills 快照）
                _uiState.update { it.copy(clawHubInstallingSlug = null) }
                refresh()
            }
        }
    }

    // ═══ 认知市场：详情 + 分类过滤 + 模糊搜索 ═══

    /** 详情对话框的配置表单数据（manifest 声明 + 当前已填值 + 就绪态）。 */
    private suspend fun loadConfigForm(skillId: String): SkillConfigFormData? =
        withContext(Dispatchers.IO) {
            val skill = skillRegistry.get(skillId) ?: return@withContext null
            if (skill.manifest.configuration.userConfig.isEmpty()) return@withContext null
            SkillConfigFormData(
                fields = skill.manifest.configuration.userConfig,
                values = skillRegistry.getConfigValues(skillId),
                configured = skillRegistry.isConfigured(skillId)
            )
        }

    /**
     * 打开技能详情对话框：异步从 cs-mem + ToolUsageTracker + CircuitBreaker + TraceRecorder
     * 投影出 [SkillDetailUiState]（能源/结晶/调用统计/熔断/轨迹），并同步加载
     * 配置表单数据（就绪门禁：声明了 userConfig 的技能提供配置入口）。
     */
    fun loadSkillDetail(skillId: String) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(detailSkillId = skillId, detailLoading = true, detailState = null, detailConfigForm = null)
            }
            val manifest = withContext(Dispatchers.IO) {
                skillRegistry.getInstalled().firstOrNull { it.manifest.id == skillId }?.manifest
            }
            val detail = skillAnalytics.projectSkillAnalytics(skillId, manifest)
            val configForm = loadConfigForm(skillId)
            _uiState.update { it.copy(detailState = detail, detailConfigForm = configForm, detailLoading = false) }
        }
    }

    /** 关闭技能详情对话框。 */
    fun closeSkillDetail() {
        _uiState.update {
            it.copy(detailSkillId = null, detailState = null, detailConfigForm = null, detailLoading = false)
        }
    }

    /** 设置分类过滤（null = 全部分类）。 */
    fun setCategoryFilter(category: String?) {
        _uiState.update { it.copy(categoryFilter = category) }
    }

    /**
     * 本地技能搜索：对已安装 + 内置模板做 ToolSuggester 模糊匹配。
     * 输入空时清空建议并显示全部。
     */
    fun setSkillQuery(query: String) {
        val trimmed = query.trim()
        _uiState.update { it.copy(skillQuery = trimmed) }
        if (trimmed.isBlank()) {
            _uiState.update { it.copy(skillSuggestions = emptyList()) }
            return
        }
        viewModelScope.launch {
            // 候选 id = 已安装 + 内置模板
            val candidates = withContext(Dispatchers.IO) {
                val installed = skillRegistry.getInstalled().map { it.manifest.id }
                val templates = SkillMenuProvider.BUILTIN_TEMPLATES.map { it.id }
                (installed + templates).distinct()
            }
            val suggestions = com.apex.agent.core.tools.ToolSuggester.suggest(
                trimmed, candidates, maxSuggestions = 5
            )
            _uiState.update { it.copy(skillSuggestions = suggestions) }
        }
    }

    /**
     * 对当前已安装技能 + 内置模板应用分类过滤 + 搜索查询。
     * 返回过滤后的技能列表（UI 渲染用）。
     */
    fun filteredSkills(): List<MarketSkillRow> {
        val state = _uiState.value
        val all = state.skills + state.skillTemplates
        return all.filter { row ->
            // #206 分类过滤：键为 SkillCategory.key（categoryFilter 与 chip 同域）；
            // 「未分类」哨兵（UNCATEGORIZED_FILTER）匹配旧值残留行。
            (state.categoryFilter == null ||
                (if (state.categoryFilter == UNCATEGORIZED_FILTER) {
                    com.apex.agent.core.tools.skill.SkillCategory.of(row.category) == null
                } else {
                    row.category == state.categoryFilter
                })) &&
            // 搜索查询：精确匹配 id/name/tags/description
            (state.skillQuery.isBlank() ||
                row.id.contains(state.skillQuery, ignoreCase = true) ||
                row.name.contains(state.skillQuery, ignoreCase = true) ||
                row.description.contains(state.skillQuery, ignoreCase = true) ||
                row.tags.any { it.contains(state.skillQuery, ignoreCase = true) })
        }
    }

    companion object {
        /** #206「未分类」过滤哨兵（与真实域 key 不撞车的哨兵值）。 */
        const val UNCATEGORIZED_FILTER = "__uncategorized__"

        /**
         * P1 修复（gate 永久挂起）：安装确认等待的兜底超时 —— 对话框被
         * 任何路径抹掉时，安装协程最多挂起这么久后按「未确认」收尾。
         */
        const val INSTALL_CONFIRM_TIMEOUT_MS = 60_000L
    }
}
