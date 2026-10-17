package com.apex.agent.ui.screen.market

import com.apex.agent.core.tools.marketplace.HubSource
import com.apex.agent.core.tools.mcp.McpManager
import com.apex.agent.core.tools.mcp.McpServerConfig
import com.apex.agent.core.tools.mcp.McpTransport
import com.apex.agent.github.GithubTokenManager
import com.apex.agent.marketplace.MarketInstallManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ═══ 官方 Hub 仓库控制器（市场 · Skills「官方仓库」源 + MCP 目录）═══
 *
 * 从 [MarketViewModel] 拆出的独立状态域（God-file 预算 + 单一职责）：
 * - **技能仓库**（apex-skill-hub）：目录浏览 + 单技能 manifest 下载安装
 *   （走 [MarketInstallManager.installSkillFromJson] 统一管道）；
 * - **MCP 仓库**（apex-mcp-hub）：目录浏览 + 一键安装
 *   （写入 `mcp_servers.json`，**enabled=false —— 安装 ≠ 启动**，启动走
 *   市场「启动」按钮的真实连接管线）。
 *
 * 安装完成后经 [refreshMarket] 通知宿主 VM 刷新快照（已安装徽标/列表联动）。
 * 自持 SupervisorJob scope（单例，与 App 进程同生命周期；安装失败不殊及宿主）。
 */
@Singleton
class MarketHubController @Inject constructor(
    private val hubSource: HubSource,
    private val installManager: MarketInstallManager,
    private val mcpManager: McpManager,
    // 内置 github MCP 配置对话框的账号连接入口（token 验证/保存复用全局单例）
    val githubTokens: GithubTokenManager
) {
    /** Hub 双目录的 UI 状态（技能 + MCP）。 */
    data class HubUiState(
        // ── 技能仓库 ──
        val skills: List<HubSource.HubSkillEntry> = emptyList(),
        val skillsLoading: Boolean = false,
        val skillsError: String? = null,
        val installingSkillId: String? = null,
        // ── MCP 仓库 ──
        val mcps: List<HubSource.HubMcpEntry> = emptyList(),
        val mcpsLoading: Boolean = false,
        val mcpsError: String? = null,
        val installingMcpName: String? = null
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _uiState = MutableStateFlow(HubUiState())
    val uiState: StateFlow<HubUiState> = _uiState.asStateFlow()

    /** 安装完成信号（宿主 VM 收集后 refresh()，刷新已装徽标与列表）。 */
    private val _refreshMarket = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val refreshMarket = _refreshMarket.asSharedFlow()

    /** 拉取技能仓库目录（已有数据或加载中则跳过；force = 下拉刷新语义）。 */
    fun loadSkills(force: Boolean = false) {
        val current = _uiState.value
        if (!force && (current.skillsLoading || current.skills.isNotEmpty())) return
        scope.launch {
            _uiState.update { it.copy(skillsLoading = true, skillsError = null) }
            hubSource.listSkills().fold(
                onSuccess = { entries -> _uiState.update { it.copy(skills = entries) } },
                onFailure = { e ->
                    _uiState.update { it.copy(skills = emptyList(), skillsError = e.message) }
                }
            )
            _uiState.update { it.copy(skillsLoading = false) }
        }
    }

    /**
     * 安装一个 Hub 技能：下载 manifest → 统一安装管道。
     * 行级 busy（installingSkillId）防并发下载。
     *
     * per-scope 默认启用工位 = 条目声明的 scope（agent/coding；all = 双开）——
     * 与市场分级过滤同源（HubSkillEntry.visibleToScope）。
     */
    fun installSkill(entry: HubSource.HubSkillEntry) {
        if (_uiState.value.installingSkillId != null) return
        scope.launch {
            _uiState.update { it.copy(installingSkillId = entry.id) }
            try {
                hubSource.downloadSkillManifest(entry).fold(
                    onSuccess = { manifestJson ->
                        val defaultScope = entry.scope.takeIf { it == "agent" || it == "coding" }
                        installManager.installSkillFromJson(manifestJson, defaultScope = defaultScope)
                    },
                    onFailure = { Result.failure(it) }
                ).fold(
                    onSuccess = { _refreshMarket.tryEmit(Unit) },
                    onFailure = { e -> _uiState.update { it.copy(skillsError = e.message) } }
                )
            } finally {
                _uiState.update { it.copy(installingSkillId = null) }
                _refreshMarket.tryEmit(Unit)
            }
        }
    }

    /** 拉取 MCP 仓库目录（配置内联，单次请求即完整目录）。 */
    fun loadMcpServers(force: Boolean = false) {
        val current = _uiState.value
        if (!force && (current.mcpsLoading || current.mcps.isNotEmpty())) return
        scope.launch {
            _uiState.update { it.copy(mcpsLoading = true, mcpsError = null) }
            hubSource.listMcpServers().fold(
                onSuccess = { entries -> _uiState.update { it.copy(mcps = entries) } },
                onFailure = { e ->
                    _uiState.update { it.copy(mcps = emptyList(), mcpsError = e.message) }
                }
            )
            _uiState.update { it.copy(mcpsLoading = false) }
        }
    }

    /**
     * 安装一台 Hub MCP 服务器：目录条目 → [McpServerConfig] 写入注册表。
     *
     * - **安装 ≠ 启动**：一律 `enabled=false` 落盘，启动（真实连接：进程
     *   fork + JSON-RPC initialize + tools/list）由用户在市场显式触发；
     * - 已有同名配置 → 不覆盖（用户可能已自定义）；
     * - 沙箱条目的 rootfs 就绪门禁在启动时由 ProotMcpProcessLauncher 把守
     *   （引导性报错），安装阶段不拦截。
     */
    fun installMcp(entry: HubSource.HubMcpEntry) {
        if (_uiState.value.installingMcpName != null) return
        scope.launch {
            _uiState.update { it.copy(installingMcpName = entry.name) }
            try {
                val exists = mcpManager.getConfigs().any { it.name == entry.name }
                if (!exists) {
                    mcpManager.addServer(toServerConfig(entry)).fold(
                        onSuccess = { _refreshMarket.tryEmit(Unit) },
                        onFailure = { e -> _uiState.update { it.copy(mcpsError = e.message) } }
                    )
                }
                _refreshMarket.tryEmit(Unit)
            } finally {
                _uiState.update { it.copy(installingMcpName = null) }
            }
        }
    }

    companion object {
        /** 目录条目 → 注册表配置（纯函数，便于单测；安装 ≠ 启动 → enabled 恒 false）。 */
        fun toServerConfig(entry: HubSource.HubMcpEntry): McpServerConfig = McpServerConfig(
            name = entry.name,
            url = entry.url,
            transport = when (entry.transport.uppercase()) {
                "STDIO" -> McpTransport.STDIO
                "SSE" -> McpTransport.SSE
                else -> McpTransport.HTTP
            },
            command = entry.command,
            args = entry.args,
            env = entry.env,
            runInSandbox = entry.runInSandbox,
            enabled = false,
            scope = entry.scope
        )
    }
}
