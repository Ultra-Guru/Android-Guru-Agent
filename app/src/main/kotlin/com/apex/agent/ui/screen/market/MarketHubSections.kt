package com.apex.agent.ui.screen.market

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apex.agent.R
import com.apex.agent.core.tools.mcp.McpConfigExport
import com.apex.agent.core.tools.marketplace.HubSource
import com.apex.agent.core.tools.marketplace.MarketNames
import com.apex.agent.core.tools.marketplace.NativeMcpEquivalents
import com.apex.agent.ui.component.GithubTokenDialog

/**
 * ═══ 官方 Hub 仓库 · 市场 UI 区块 ═══
 *
 * - [HubSkillSection]：Skills 页签「官方仓库」源（apex-skill-hub 目录浏览 + 一键直装）；
 * - [HubMcpCatalogCard]：MCP 页签的官方 MCP 仓库行卡（apex-mcp-hub：安装 → 配置 → 启动）；
 * - [ConfiguredMcpCard]：MCP 页签的当前工位服务器行卡（配置 / 启动 / 停止 ——
 *   「市场内完成配置启动」的产品要求，已安装管理页保留全量管理）；
 * - [BuiltinMcpConfigDialog]：内置（BUILTIN 进程内）服务器的配置对话框 ——
 *   作用域 / 启停 / 连接 / GitHub Token，补齐「所有 MCP 都有配置按钮」的缺口。
 */

// ═══ Skills · 官方技能仓库 ═══

/**
 * 官方技能仓库区块（apex-skill-hub）：
 * 首次切入自动加载目录；按当前市场分级（tier）过滤可见条目；
 * 已安装显示徽标，未安装显示「安装」（行级 busy 防并发下载）。
 */
@Composable
internal fun HubSkillSection(state: MarketUiState, viewModel: MarketViewModel) {
    val hubState by viewModel.hub.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.hub.loadSkills() }

    val tierScope = state.tier.name.lowercase()
    val visible = hubState.skills.filter { it.visibleToScope(tierScope) }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.market_hub_header),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
        )

        // 错误横幅（可重试）
        hubState.skillsError?.let { error ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.market_load_failed_with_reason, error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                TextButton(
                    onClick = { viewModel.hub.loadSkills(force = true) },
                    enabled = !hubState.skillsLoading
                ) { Text(stringResource(R.string.market_action_retry)) }
            }
        }

        when {
            hubState.skillsLoading && visible.isEmpty() -> {
                MarketEmptyState(hint = stringResource(R.string.market_hub_loading))
            }
            visible.isEmpty() && hubState.skillsError == null -> {
                MarketEmptyState(hint = stringResource(R.string.market_hub_empty))
            }
            visible.isNotEmpty() -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(visible, key = { it.key }) { entry ->
                        HubSkillCard(entry, state, hubState, viewModel)
                    }
                }
            }
        }
    }
}

/** Hub 技能行卡：名称 + 版本 + 描述 + 已装徽标 / 安装按钮。 */
@Composable
private fun HubSkillCard(
    entry: HubSource.HubSkillEntry,
    state: MarketUiState,
    hubState: MarketHubController.HubUiState,
    viewModel: MarketViewModel
) {
    val installed = state.skills.any { it.id == entry.id }
    val installing = hubState.installingSkillId == entry.id
    MarketCard(
        title = entry.name,
        subtitle = "v${entry.version} · ${entry.id}",
        description = entry.description,
        descriptionMaxLines = 2,
        trailing = {
            if (installed) {
                MarketStatusChip(text = stringResource(R.string.market_status_installed), positive = true)
            } else {
                TextButton(
                    onClick = { viewModel.hub.installSkill(entry) },
                    // 安装期间禁用所有行的安装按钮，防并发下载
                    enabled = hubState.installingSkillId == null
                ) {
                    Text(
                        if (installing) stringResource(R.string.market_installing)
                        else stringResource(R.string.market_action_install)
                    )
                }
            }
        }
    )
}

// ═══ MCP · 官方 MCP 仓库行卡 ═══

/**
 * 官方 MCP 仓库行卡（apex-mcp-hub 目录条目）：
 * - **内置等价**（SequentialThinking → thinking 等）：卡片直接渲染
 *   「已内置」徽标 + 「启动内置」/「停止」—— 真内置不卖安装
 *   （NativeMcpEquivalents 的卡片级落地，安装拦截之外的第一道诚实门）；
 * - 未安装 → 「安装」（enabled=false 落盘，安装 ≠ 启动）；
 * - 已安装未运行 → 「启动」（真实连接管线，进度弹窗见 MarketScreen）；
 * - 运行中 → 「停止」；
 * - 沙箱条目带「需 Ubuntu」标签（rootfs 未装时启动会得到引导性报错）；
 * - STDIO 条目提供「桌面配置」复制 —— 本机没有运行时（沙箱未装
 *   nodejs）时，把官方 mcpServers JSON 带去电脑端用，而不是卡在
 *   必败的启动上（McpConfigExport 的首个 UI 接线点）。
 */
@Composable
internal fun HubMcpCatalogCard(
    entry: HubSource.HubMcpEntry,
    state: MarketUiState,
    hubState: MarketHubController.HubUiState,
    viewModel: MarketViewModel
) {
    // #346：已安装行匹配用归一化比较键（与安装防重同口径：同名服务器从
    // 其他源安装、大小写/分隔符变体也识别为已安装）
    val installedRow = state.mcps.firstOrNull { MarketNames.isSameServer(it.name, entry.name) }
    val installing = hubState.installingMcpName == entry.name
    // 内置等价：仅 STDIO 条目查（npx 变体在本机是严格劣解）；远端同名
    // 条目不拦 —— 远端形态本机真实可用，与内置互不遮挡。
    val builtinEquivalent = if (entry.transport.equals("STDIO", ignoreCase = true)) {
        NativeMcpEquivalents.builtinFor(entry.name, entry.command, entry.args)
    } else {
        null
    }?.let { builtinId ->
        BuiltinEquivalentUi(
            builtinId = builtinId,
            connected = state.mcps.any { it.name == builtinId && it.connected },
            onConnect = { viewModel.connectMcp(builtinId) },
            onDisconnect = { viewModel.disconnectMcp(builtinId) }
        )
    }
    val clipboard = LocalClipboardManager.current
    Column {
        MarketCard(
            title = entry.name,
            subtitle = entry.endpointSummary(),
            description = entry.description,
            descriptionMaxLines = 2,
            trailing = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    when {
                        // 真内置不卖安装：优先于 installedRow（旧版可能装过
                        // npx 变体，原生等价仍是更好的承载形态）
                        builtinEquivalent != null -> BuiltinEquivalentTrailing(
                            builtin = builtinEquivalent,
                            connecting = state.mcpConnecting == builtinEquivalent.builtinId
                        )
                        installedRow == null -> TextButton(
                            onClick = { viewModel.hub.installMcp(entry) },
                            enabled = hubState.installingMcpName == null
                        ) {
                            Text(
                                if (installing) stringResource(R.string.market_installing)
                                else stringResource(R.string.market_action_install)
                            )
                        }
                        installedRow.connected -> {
                            MarketStatusChip(
                                text = stringResource(R.string.market_status_running),
                                positive = true
                            )
                            TextButton(
                                enabled = state.mcpConnecting != entry.name,
                                onClick = { viewModel.disconnectMcp(entry.name) }
                            ) { Text(stringResource(R.string.market_mcp_stop)) }
                        }
                        else -> {
                            MarketStatusChip(
                                text = stringResource(R.string.market_hub_mcp_not_running),
                                positive = false
                            )
                            TextButton(
                                enabled = state.mcpConnecting != entry.name,
                                onClick = { viewModel.connectMcp(entry.name) }
                            ) {
                                Text(
                                    if (state.mcpConnecting == entry.name) {
                                        stringResource(R.string.market_connecting)
                                    } else {
                                        stringResource(R.string.market_mcp_start)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        )
        Row(
            modifier = Modifier.padding(start = 4.dp, top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (builtinEquivalent != null) {
                Text(
                    text = stringResource(
                        R.string.market_mcp_builtin_note, builtinEquivalent.builtinId
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f, fill = false)
                )
            } else if (entry.requiresRootfs) {
                Text(
                    text = stringResource(R.string.market_hub_mcp_requires_rootfs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            // 桌面配置：把本条目装配成官方 mcpServers JSON 复制到剪贴板
            //（Claude Desktop / Cursor / Cline 通用）—— 本机沙箱缺运行时
            // 时的诚实出路，与安装失败文案的指引闭环。
            if (builtinEquivalent == null && entry.transport.equals("STDIO", ignoreCase = true)) {
                TextButton(
                    onClick = {
                        val config = MarketHubController.toServerConfig(entry)
                        McpConfigExport.exportServer(config)?.let { json ->
                            clipboard.setText(AnnotatedString(json))
                            viewModel.message(
                                viewModel.languageManager.getString(
                                    R.string.market_mcp_desktop_config_copied, entry.name
                                )
                            )
                        } ?: viewModel.message(
                            viewModel.languageManager.getString(
                                R.string.market_mcp_desktop_config_failed, entry.name
                            )
                        )
                    }
                ) {
                    Text(
                        text = stringResource(R.string.market_mcp_desktop_config),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

// ═══ MCP · 沙箱环境体检卡（npx 启动 100% 失败的根治闭环）═══

/**
 * 沙箱环境体检引导卡（MCP 页签顶部）：
 * - **rootfs 未装**：说明 + 引导去「终端」页安装 Ubuntu（无一键动作）；
 * - **运行时缺失**（nodejs 家族 / python）：说明 + **一键安装**按钮
 *   （真实在 PRoot 内 apt 安装，分钟级 busy）；
 * - **结果反馈**：安装成功/失败（附 stderr 尾部）就地在卡内呈现。
 *
 * 状态源：[MarketHubController.HubUiState.runtimeStatus /
 * runtimeInstalling / runtimeNotice]（页签切入自动体检 +
 * 安装门禁命中时同步点亮）。
 */
@Composable
internal fun SandboxRuntimeCard(
    hubState: MarketHubController.HubUiState,
    viewModel: MarketViewModel
) {
    val status = hubState.runtimeStatus ?: return
    androidx.compose.material3.ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = if (status.rootfsMissing) {
                    stringResource(R.string.market_sandbox_rootfs_missing_title)
                } else {
                    stringResource(
                        R.string.market_sandbox_runtime_missing_title,
                        status.missingCommand.orEmpty().ifBlank { "npx" }
                    )
                },
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = if (status.rootfsMissing) {
                    stringResource(R.string.market_sandbox_rootfs_missing_body)
                } else {
                    stringResource(R.string.market_sandbox_runtime_missing_body)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
            hubState.runtimeNotice?.let { notice ->
                Text(
                    text = notice,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            if (!status.rootfsMissing) {
                TextButton(
                    enabled = !hubState.runtimeInstalling,
                    onClick = { viewModel.hub.installSandboxRuntime() },
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Text(
                        if (hubState.runtimeInstalling) {
                            stringResource(R.string.market_sandbox_runtime_installing)
                        } else {
                            stringResource(R.string.market_sandbox_runtime_install_action)
                        }
                    )
                }
            }
        }
    }
}

// ═══ MCP · 当前工位服务器行卡（市场内配置 / 启动）═══

/**
 * 当前工位已配置服务器的紧凑管理卡（MCP 页签内）：
 * 状态徽标 + 启动/停止 + 配置（BUILTIN → [BuiltinMcpConfigDialog]；
 * 自建 → 复用已安装管理页的 EditMcpDialog）。
 */
@Composable
internal fun ConfiguredMcpCard(
    server: MarketMcpRow,
    state: MarketUiState,
    viewModel: MarketViewModel
) {
    var showBuiltinConfig by remember { mutableStateOf(false) }

    Column {
        MarketCard(
            title = if (server.builtin) {
                stringResource(R.string.market_builtin_name, server.name)
            } else {
                server.name
            },
            subtitle = server.endpoint,
            description = null,
            trailing = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    MarketStatusChip(
                        text = when {
                            server.connected -> stringResource(R.string.market_status_running)
                            server.enabled -> stringResource(R.string.market_status_offline)
                            else -> stringResource(R.string.market_status_disabled)
                        },
                        positive = server.connected
                    )
                    if (server.connected) {
                        TextButton(
                            enabled = state.mcpConnecting != server.name,
                            onClick = { viewModel.disconnectMcp(server.name) }
                        ) { Text(stringResource(R.string.market_mcp_stop)) }
                    } else {
                        // 启动按钮不再被 enabled 开关门禁：connectMcp 入口已
                        // 原子地「先启用再连接」（#338）—— 安装落盘的
                        // enabled=false 配置点启动即可直接走真实连接管线，
                        // 无需先去已安装管理拨开关（旧版「装完启动是灰的」）。
                        TextButton(
                            enabled = state.mcpConnecting != server.name,
                            onClick = { viewModel.connectMcp(server.name) }
                        ) {
                            Text(
                                if (state.mcpConnecting == server.name) {
                                    stringResource(R.string.market_connecting)
                                } else {
                                    stringResource(R.string.market_mcp_start)
                                }
                            )
                        }
                    }
                    // 配置按钮：所有服务器（含 BUILTIN）都有 —— 产品要求
                    TextButton(
                        onClick = {
                            if (server.builtin) showBuiltinConfig = true
                            else viewModel.openMcpEditor(server.name)
                        }
                    ) { Text(stringResource(R.string.market_mcp_config)) }
                }
            }
        )
    }

    if (showBuiltinConfig) {
        BuiltinMcpConfigDialog(
            server = server,
            viewModel = viewModel,
            onDismiss = { showBuiltinConfig = false }
        )
    }
}

// ═══ MCP · 内置服务器配置对话框 ═══

/**
 * 内置（BUILTIN 进程内 transport）MCP 服务器的配置对话框。
 *
 * 内置服务器不可删改命令/URL（能力在二进制里），可配置的是：
 * - **工位作用域**（agent / coding / all —— 市场分级与斜杠菜单的过滤口径）；
 * - **启停**（enabled —— 启用 = App 启动时自动连接）；
 * - **连接**（真实启动管线：进程内握手 + tools/list 发现，进度弹窗由
 *   MarketScreen 的 McpStartupProgressDialog 全局渲染）；
 * - **GitHub 账号**（仅内置 github：嵌 GithubTokenDialog，补 token 后
 *   get_me/list_repositories 等 7 工具立即可用）。
 *
 * 对话框内每次动作后市场快照刷新（VM refresh），行数据以 uiState 实时
 * 重查（`fresh`），配置项即时反映变更。
 */
@Composable
internal fun BuiltinMcpConfigDialog(
    server: MarketMcpRow,
    viewModel: MarketViewModel,
    onDismiss: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // 实时行数据（作用域/启停/连接态随本对话框内的动作即时变化）
    val fresh = state.mcps.firstOrNull { it.name == server.name } ?: server
    var showGithubToken by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.market_mcp_config_title, fresh.name),
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // 能力说明（内置服务器各自做什么）
                Text(
                    text = builtinMcpDescription(fresh.name),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.market_transport_builtin),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )

                // ── 工位作用域 ──
                Text(
                    text = stringResource(R.string.market_mcp_scope_label),
                    style = MaterialTheme.typography.labelMedium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = fresh.scope == "agent",
                        onClick = { viewModel.updateMcpScope(fresh.name, "agent") },
                        label = {
                            Text(
                                stringResource(R.string.market_scope_agent),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    )
                    FilterChip(
                        selected = fresh.scope == "coding",
                        onClick = { viewModel.updateMcpScope(fresh.name, "coding") },
                        label = {
                            Text(
                                stringResource(R.string.market_scope_coding),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    )
                    FilterChip(
                        selected = fresh.scope == "all",
                        onClick = { viewModel.updateMcpScope(fresh.name, "all") },
                        label = {
                            Text(
                                stringResource(R.string.market_scope_all),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    )
                }

                // ── 启停（启用 = App 启动时自动连接）──
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.market_mcp_enable_label),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = stringResource(R.string.market_mcp_enable_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = fresh.enabled,
                        onCheckedChange = { viewModel.toggleMcp(fresh.name, it) }
                    )
                }

                // ── 连接状态 / 手动启动停止 ──
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    MarketStatusChip(
                        text = when {
                            fresh.connected -> stringResource(R.string.market_status_running)
                            fresh.enabled -> stringResource(R.string.market_status_offline)
                            else -> stringResource(R.string.market_status_disabled)
                        },
                        positive = fresh.connected
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    // 同 ConfiguredMcpCard：connectMcp 自动启用，启动按钮
                    // 不受 enabled 开关门禁（内置服务器被关用时也能一键拉起）。
                    TextButton(
                        enabled = state.mcpConnecting != fresh.name,
                        onClick = { viewModel.connectMcp(fresh.name) }
                    ) { Text(stringResource(R.string.market_mcp_start)) }
                    TextButton(
                        enabled = fresh.connected,
                        onClick = { viewModel.disconnectMcp(fresh.name) }
                    ) { Text(stringResource(R.string.market_mcp_stop)) }
                }

                // ── GitHub 账号（仅内置 github 服务器）──
                if (fresh.name == "github") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(R.string.market_mcp_github_account),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { showGithubToken = true }) {
                            Text(stringResource(R.string.market_mcp_github_connect))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.market_action_done)) }
        }
    )

    if (showGithubToken) {
        val tokenManager = viewModel.hub.githubTokens
        // v3 S3：onSuccess 第三参 = 对话框归一化后的默认仓库（null = 未填/
        // 无法识别，不改动既有值）
        GithubTokenDialog(
            onDismiss = { showGithubToken = false },
            onSubmit = { token -> tokenManager.validateToken(token) },
            onSuccess = { token, username, normalizedRepo ->
                tokenManager.saveToken(token, username)
                normalizedRepo?.let { tokenManager.saveDefaultRepoCanonical(it) }
                showGithubToken = false
            }
        )
    }
}

/** 内置服务器 → 能力说明文案（未收录的名字走通用描述）。 */
@Composable
private fun builtinMcpDescription(name: String): String = stringResource(
    when (name) {
        "github" -> R.string.market_mcp_builtin_desc_github
        "search" -> R.string.market_mcp_builtin_desc_search
        "fs" -> R.string.market_mcp_builtin_desc_fs
        "memory" -> R.string.market_mcp_builtin_desc_memory
        "thinking" -> R.string.market_mcp_builtin_desc_thinking
        else -> R.string.market_mcp_builtin_desc_default
    }
)
