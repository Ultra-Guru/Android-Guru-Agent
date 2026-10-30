package com.apex.agent.ui.screen.market

import androidx.annotation.StringRes
import com.apex.agent.R
import com.apex.agent.core.tools.connector.ConnectorDef
import com.apex.agent.core.tools.marketplace.ClawHubSource
import com.apex.agent.core.tools.marketplace.ModelScopeSource
import com.apex.agent.core.tools.mcp.McpTransport
import com.apex.agent.marketplace.MarketInstallManager
import com.apex.agent.plugin.host.functional.UnifiedPluginEntry

/*
 * 市场 UI 模型层（v1.4.1 自 MarketViewModel.kt 按职责缝迁出；同包零导入变更，
 * 调用点不动）。本 PR 两步演进：
 * 1. 续迁枚举 / [MarketUiState] / [McpStartupUi]（行模型本就与 VM 职责正交，
 *    界面层与 VM 共用这些类型）；
 * 2. 升级 per-scope 启停字段 —— enabledAgent / enabledCoding / needsConfig
 *    （双工位隔离 + 就绪门禁，见 docs/market-scope-isolation-v2.md）。
 */

// ═══ UI 行数据（避免界面直接依赖各注册表内部类型）═══

data class MarketSkillRow(
    val id: String,
    val name: String,
    val description: String,
    val installed: Boolean,
    /**
     * per-scope 启停（双工位隔离）：Agent 分级 / Coding 分级的开关各自独立，
     * 已安装管理页的 Switch 按当前 [MarketTier] 读写对应字段 —— 在 Coding 市场
     * 关掉不影响 Agent 市场（修「两分级同步」问题）。
     */
    val enabledAgent: Boolean = true,
    val enabledCoding: Boolean = true,
    val source: String = "",   // local / modelscope / github
    /**
     * true = App 内置模板：能力已在二进制里（工具原生注册），不存在"下载/安装"这一步。
     * UI 显示为「内置」标记而不是「安装」按钮 —— 旧实现给它一个安装按钮，
     * 点了只是往本地写一份 JSON，观感是装了，实际什么外部内容都没拉取。
     */
    val builtin: Boolean = false,
    // ── cs-mem 认知健康（v2 增强：让市场"活"起来）──
    /** 能量 [0.01, 10.0]；衰减到 0.05 以下会被梦境折叠。1.0 = 默认/新装。 */
    val energy: Float = 1.0f,
    /** 已结晶为可跳过 LLM 的确定性宏（高频成功技能）。 */
    val isCrystallized: Boolean = false,
    /** 最近一次执行时间戳（ms）；0 = 从未执行。 */
    val lastUsedAt: Long = 0,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    // ── 市场元数据（来自 manifest，用于分类过滤与详情页）──
    val category: String? = null,
    val tags: List<String> = emptyList(),
    val author: String = "",
    val version: String = "",
    val trustLevel: String = "community",
    /**
     * Issue #166：manifest.bundled——APK assets（assets/skills/）首启幂等释放的内置优质技能。
     * 市场卡 / 详情显示「内置」徽标；卸载入口降级（内置可禁用不可卸载，
     * SkillRegistry.uninstall 对 bundled 恒 false）。
     */
    val bundled: Boolean = false,
    /** #197 工位作用域（"agent"/"coding"/"all"）—— 市场分级的过滤口径。 */
    val scope: String = "all",
    /**
     * 就绪门禁：manifest 声明了必填配置（configuration.userConfig）且尚未填齐。
     * 卡片显示「需配置」徽标；配置齐前不进斜杠菜单（用户需求：
     * 「没有配置好的和没有启动的不要在输入框 / 按钮里显示」）。
     */
    val needsConfig: Boolean = false
) {
    /** 成功率 0..1。 */
    val successRate: Float get() =
        if (successCount + failureCount == 0) 0f
        else successCount.toFloat() / (successCount + failureCount)

    /** 是否低能量（需要被使用否则会衰减）。 */
    val isLowEnergy: Boolean get() = installed && energy < 0.5f && !isCrystallized

    /** 双工位至少一开（旧消费方口径；分级开关请读 enabledAgent/enabledCoding）。 */
    val enabled: Boolean get() = enabledAgent || enabledCoding

    /** 当前分级下该行是否处于开启态（已安装管理页 Switch 的读写口径）。 */
    fun enabledForTier(tier: MarketTier): Boolean =
        if (tier == MarketTier.CODING) enabledCoding else enabledAgent
}

data class MarketMcpRow(
    val name: String,
    /** 端点摘要：远端显示 URL，STDIO 显示完整命令行。 */
    val endpoint: String,
    val transport: McpTransport,
    val enabled: Boolean,
    val connected: Boolean,
    /** 内置服务器（BUILTIN 进程内 transport，如内置 GitHub）：不可删除。 */
    val builtin: Boolean = false,
    /** #197 工位作用域（"agent"/"coding"/"all"）。 */
    val scope: String = "all"
)

data class MarketPluginRow(
    val packageName: String,
    val label: String,
    val loaded: Boolean
)

/** 市场五个页签（两个顶栏视图共享同一套子导航）—— label 为资源 id，composable 侧 stringResource 取词。 */
enum class MarketTab(@StringRes val labelRes: Int) {
    PLUGINS(R.string.market_tab_plugins),
    SKILLS(R.string.market_tab_skills),
    MCP(R.string.market_tab_mcp),
    CONNECTORS(R.string.market_tab_connectors),
    INTEGRATIONS(R.string.market_tab_integrations)
}

/**
 * 市场顶栏视图：
 * - [BROWSE] 市场 —— 发现与安装入口（模板 / GitHub / URL / 魔搭 / 添加表单）；
 * - [INSTALLED] 已安装管理 —— 管理已装/已加载内容（启停 / 卸载 / 连接）。
 *
 * 两个视图下均保留同一套 [MarketTab] 子导航（插件 / Skills / MCP / 连接器 / 集成），
 * 切换视图不重置子页签 —— 用户在「Skills · 市场」看完安装源，切到
 * 「已安装管理」还在 Skills 分类下，上下文不断裂。
 */
enum class MarketScope(@StringRes val labelRes: Int) {
    BROWSE(R.string.market_scope_browse),
    INSTALLED(R.string.market_scope_installed)
}

/**
 * #197 市场分级：双工位市场（与技能/MCP 的 scope 字段同源）。
 * - [AGENT] Agent 市场 —— 服务于 Agent 屏（聊天技能 / 联网搜索 / 记忆 / 思维链…）；
 * - [CODING] Coding 市场 —— 服务于 Coding 屏（开发技能 / GitHub / 文件系统 / 沙箱…）。
 */
enum class MarketTier(@StringRes val labelRes: Int) {
    AGENT(R.string.market_tier_agent),
    CODING(R.string.market_tier_coding)
}

/**
 * 市场 · Skills 页签的仓库源切换：
 * - [LOCAL] —— 本地技能（已安装 + 内置模板 + 四个本地安装入口）；
 * - [HUB] —— 官方技能仓库（apex-skill-hub：内置瘦身后迁出的 62 个生活/通用
 *   技能，目录浏览 + 一键直装）；
 * - [CLAWHUB] —— ClawHub 技能仓库（clawhub.ai：浏览热门 / 搜索 / 真实下载安装）；
 * - [GITHUB] —— GitHub 精选（anthropics/skills、obra/superpowers 等顶级开源
 *   仓库的 coding 技能，一键直装 SKILL.md → prompt 型技能）。
 */
enum class SkillRepoSource(@StringRes val labelRes: Int) {
    LOCAL(R.string.market_skill_source_local),
    HUB(R.string.market_skill_source_hub),
    CLAWHUB(R.string.market_skill_source_clawhub),
    GITHUB(R.string.market_skill_source_github_featured)
}

data class MarketUiState(
    val scope: MarketScope = MarketScope.BROWSE,
    val selectedTab: MarketTab = MarketTab.PLUGINS,
    // #197 市场分级（Agent 市场 / Coding 市场）
    val tier: MarketTier = MarketTier.AGENT,
    // Skills
    val skills: List<MarketSkillRow> = emptyList(),
    val skillTemplates: List<MarketSkillRow> = emptyList(),
    // MCP
    val mcps: List<MarketMcpRow> = emptyList(),
    // 连接器
    val connectors: List<ConnectorDef> = emptyList(),
    // 插件（BROWSE 视图：APK 发现态）
    val plugins: List<MarketPluginRow> = emptyList(),
    // 插件（INSTALLED 视图：统一目录 —— 功能性 + APK 双形态合并）
    val installedPlugins: List<UnifiedPluginEntry> = emptyList(),
    // 集成：魔搭
    val modelScopeSkills: List<ModelScopeSource.ModelScopeSkill> = emptyList(),
    val modelScopeLoading: Boolean = false,
    val modelScopeQuery: String = "",
    val modelScopeError: String? = null,
    val installedModelScopeIds: Set<String> = emptySet(),
    // 集成：GitHub
    val githubQuery: String = "",
    val githubHits: List<MarketInstallManager.GitHubRepoHit> = emptyList(),
    val githubSearching: Boolean = false,
    val githubError: String? = null,
    // GitHub 智能安装：多候选选择弹窗（技能集合 / MCP monorepo 仓库发现
    // 多个可装项时非空；单候选不弹直接装）。关闭 = null。
    val githubCandidates: List<MarketInstallManager.GithubRepoCandidate> = emptyList(),
    val githubCandidatesRepo: String? = null,
    // Skills：ClawHub 技能仓库（源切换 + 列表/搜索/安装状态）
    val skillSource: SkillRepoSource = SkillRepoSource.LOCAL,
    val clawHubSkills: List<ClawHubSource.ClawHubSkillEntry> = emptyList(),
    /** 首次/刷新加载中（trending 或翻页）。 */
    val clawHubLoading: Boolean = false,
    val clawHubError: String? = null,
    val clawHubQuery: String = "",
    /** 关键词搜索请求中（与列表加载区分，避免双重 loading）。 */
    val clawHubQueryLoading: Boolean = false,
    /** 正在安装的技能 slug（行级 busy；null = 空闲，非 null 时禁用其它安装按钮防并发）。 */
    val clawHubInstallingSlug: String? = null,
    /** P1（市场审计）：待确认安装的干运行预览 —— 非空时 AggregationCenterScreen 弹
     * ManifestDryRunDialog（manifest 摘要/依赖/权限/promptInjection 警示），
     * 用户确认后才真正落盘。此前三件套预览组件全为死码，所有源一点即装。 */
    val installPreview: com.apex.agent.marketplace.MarketInstallManager.ManifestPreview? = null,
    /** 是否还有下一页（「加载更多」按钮可见性）。 */
    val clawHubHasMore: Boolean = false,
    /** 当前列表是否为搜索结果（控制「返回热门」提示）。 */
    val clawHubSearchActive: Boolean = false,
    // ── Skills：GitHub 精选（顶级开源仓库 coding 技能专栏）──
    /** 正在安装的精选条目 id（行级 busy；null = 空闲）。 */
    val githubFeaturedInstallingId: String? = null,
    // ── v2 认知市场增强 ──
    /** 当前选中分类过滤（null = 全部）。镜像 ToolCategory 枚举名。 */
    val categoryFilter: String? = null,
    /** 本地搜索查询（已安装技能 + 内置模板的模糊匹配，ToolSuggester 驱动）。 */
    val skillQuery: String = "",
    /** 模糊搜索建议（"你是不是要找…"）。 */
    val skillSuggestions: List<String> = emptyList(),
    /** 当前打开的技能详情对话框对应的 skillId。null = 关闭。 */
    val detailSkillId: String? = null,
    /** 详情对话框加载的分析数据。null = 未加载/加载中。 */
    val detailState: SkillDetailUiState? = null,
    /** 就绪门禁表单段数据（详情对话框；manifest 声明 userConfig 时非空）。 */
    val detailConfigForm: SkillConfigFormData? = null,
    /** 详情加载中。 */
    val detailLoading: Boolean = false,
    // 全局
    val busy: Boolean = false,
    /** 正在连接的 MCP 服务器名（null = 无）：连接中禁用对应行按钮，防双击并发重连 */
    val mcpConnecting: String? = null,
    /** #197 真实启动进度（连接中服务器的实时阶段事件；null = 关闭弹窗）。 */
    val mcpStartup: McpStartupUi? = null,
    // ── #205 MCP 精选目录（assets/mcp_catalog 离线随包）──
    /** 目录全量条目（未分级过滤）。 */
    val mcpCatalog: List<com.apex.agent.core.tools.mcp.McpServerCatalog.McpCatalogEntry> = emptyList(),
    /** 目录加载完成（空目录且 false = 加载中/失败）。 */
    val mcpCatalogLoaded: Boolean = false,
    /** 目录加载失败原因（本地资产损坏时非空）。 */
    val mcpCatalogError: String? = null,
    /** 目录分类过滤（null = 全部）。 */
    val mcpCatalogCategory: String? = null,
    /** 待配置环境变量的目录条目（null = 环境变量弹窗关闭）。 */
    val catalogEnvEntry: com.apex.agent.core.tools.mcp.McpServerCatalog.McpCatalogEntry? = null,
    val lastMessage: String? = null
)

/**
 * #197 MCP 真实启动进度弹窗状态：事件列表由 [com.apex.agent.core.tools.mcp.McpStartupListener] 的真实回调
 * 逐条追加（无模拟延时，阶段间的等待就是真实的进程启动/网络握手耗时）。
 */
data class McpStartupUi(
    val serverName: String,
    val events: List<com.apex.agent.core.tools.mcp.McpStartupEvent> = emptyList(),
    val running: Boolean = true
)
