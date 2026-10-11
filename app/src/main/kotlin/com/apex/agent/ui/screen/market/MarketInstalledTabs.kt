package com.apex.agent.ui.screen.market

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apex.agent.R
import com.apex.agent.core.tools.connector.ConnectorDef
import com.apex.agent.core.tools.mcp.McpTransport
import com.apex.agent.plugin.api.PluginKind
import com.apex.agent.plugin.host.functional.UnifiedPluginEntry
import com.apex.agent.plugin.host.functional.UnifiedPluginState

/**
 * ═══ 市场 · 已安装管理视图（INSTALLED）═══
 *
 * 顶栏「已安装管理」视图下的五个子页签，只放「管理」语义的内容：
 * - 插件：Plugin System v2 双形态统一目录（功能性 plugin.json + APK 插件）
 *   的启停 / 卸载 / 导入；
 * - Skills：已安装技能的启停 / 卸载；
 * - MCP：已配置工具源的连接 / 断开 / 启停 / 删除；
 * - 连接器：已有连接器的启停 / 删除；
 * - 集成：从魔搭（ms-*）/ GitHub（gh-*）/ ClawHub（ch-*）安装的技能管理。
 *
 * 所有空态提供「去市场安装」一键跳回发现视图 —— 两个视图互为闭环。
 */

/** 空态统一动作 —— 跳回「市场」发现视图（保留当前子页签）。 */
private val goToBrowse: (MarketViewModel) -> Unit = { it.selectScope(MarketScope.BROWSE) }

/**
 * 沙箱就绪态判定：与 ProotMcpProcessLauncher 门禁同源（rootfs current 链接存在）。
 * 与 BrowseMcpTab 添加对话框的判定保持一致 —— 判定口径分叉会造成「添加能开沙箱、
 * 编辑却提示不可用」的矛盾体验。
 *
 * #206 修复：以 editingConfig 为键重查 —— 旧实现 remember 无键，rootfs 装好后
 * 编辑弹窗仍报「不可用」直到重组。
 */
@Composable
private fun rememberSandboxAvailable(key: Any? = Unit): Boolean {
    val context = androidx.compose.ui.platform.LocalContext.current
    return androidx.compose.runtime.remember(key) {
        java.io.File(context.filesDir, "rootfs/ubuntu/current").exists()
    }
}

// ═══ 已安装管理 · 插件（Plugin System v2：双形态统一目录） ═══

/**
 * Plugin System v2：统一插件管理视图（功能性 + APK 双形态合并，数据源
 * MarketUiState.installedPlugins ← UnifiedPluginRegistry.catalog）。
 *
 * - **类型徽章**：「功能」（进程内 plugin.json 数据）与「APK」（独立 APK
 *   + AIDL 跨进程）并列展示——用户在同一页理解两种插件生态；
 * - **功能性插件**：启停 Switch（工具宏实时注册/回收）+ 卸载（目录删除）；
 *   BROKEN 态如实展示原因并引导重装；
 * - **APK 插件**：保持既有语义——卸载 = 解除绑定（APK 留在系统，可重载）；
 * - **导入**：头部「导入插件 JSON」按钮（粘贴 plugin.json 文本）——功能性
 *   插件的本地分发入口（市场仓库分发是后续路线，见设计文档）。
 */
@Composable
internal fun InstalledPluginsTab(state: MarketUiState, viewModel: MarketViewModel) {
    var pendingApkUnload by remember { mutableStateOf<UnifiedPluginEntry?>(null) }
    var pendingFunctionalUninstall by remember { mutableStateOf<UnifiedPluginEntry?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    val installed = state.installedPlugins

    if (installed.isEmpty()) {
        Column {
            MarketEmptyState(
                hint = stringResource(R.string.market_installed_plugins_empty),
                actionLabel = stringResource(R.string.market_installed_plugins_go),
                onAction = { goToBrowse(viewModel) }
            )
            // 空态同样提供导入入口——首个功能性插件的冷启动路径不能藏在非空态里。
            OutlinedButton(
                onClick = { showImportDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Icon(
                    Icons.Default.FileDownload,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    stringResource(R.string.market_plugin_import_json),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    } else {
        MarketList(
            items = installed,
            key = { "${it.kind.name}:${it.id}" },
            header = {
                item {
                    Column {
                        MarketHeader(
                            stringResource(
                                R.string.market_installed_plugins_header_v2,
                                installed.count { it.kind == PluginKind.FUNCTIONAL },
                                installed.count { it.kind == PluginKind.APK }
                            )
                        )
                        // 功能性插件本地导入入口（plugin.json 粘贴导入）。
                        OutlinedButton(
                            onClick = { showImportDialog = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                Icons.Default.FileDownload,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                stringResource(R.string.market_plugin_import_json),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }
        ) { plugin ->
            MarketCard(
                title = plugin.name,
                subtitle = plugin.id,
                description = buildString {
                    append(plugin.description)
                    if (plugin.description.isNotBlank()) append('\n')
                    append("v").append(plugin.version)
                },
                trailing = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 类型徽章：功能（进程内数据）/ APK（独立应用）。
                        MarketStatusChip(
                            text = if (plugin.kind == PluginKind.FUNCTIONAL) {
                                stringResource(R.string.market_plugin_kind_functional)
                            } else {
                                stringResource(R.string.market_plugin_kind_apk)
                            },
                            positive = false
                        )
                        when (plugin.state) {
                            UnifiedPluginState.ACTIVE -> MarketStatusChip(
                                text = stringResource(
                                    if (plugin.kind == PluginKind.FUNCTIONAL) {
                                        R.string.market_plugin_state_active
                                    } else {
                                        R.string.market_status_loaded
                                    }
                                ),
                                positive = true
                            )
                            UnifiedPluginState.INSTALLED -> MarketStatusChip(
                                text = stringResource(R.string.market_plugin_state_installed),
                                positive = false
                            )
                            UnifiedPluginState.BROKEN -> MarketStatusChip(
                                text = stringResource(R.string.market_plugin_state_broken),
                                positive = false
                            )
                        }
                        if (plugin.kind == PluginKind.FUNCTIONAL) {
                            // 功能性插件：启停开关（BROKEN 态禁用——坏数据
                            // 不允许激活，先修好或卸载）。
                            Switch(
                                checked = plugin.enabled && plugin.state == UnifiedPluginState.ACTIVE,
                                enabled = plugin.state != UnifiedPluginState.BROKEN,
                                onCheckedChange = { viewModel.toggleFunctionalPlugin(plugin.id, it) }
                            )
                            TextButton(onClick = { pendingFunctionalUninstall = plugin }) {
                                Text(stringResource(R.string.market_action_uninstall))
                            }
                        } else {
                            // APK 插件：既有语义（卸载 = 解绑，APK 留存可重载）。
                            TextButton(onClick = { pendingApkUnload = plugin }) {
                                Text(stringResource(R.string.market_action_uninstall))
                            }
                        }
                    }
                }
            )
            // 状态补充说明（BROKEN 原因 / 工具 id 冲突拒绝记录）——如实呈现，
            // 引导用户重装或检查插件数据。
            plugin.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }
    }

    // APK 插件解绑确认（既有文案与行为不变）。
    pendingApkUnload?.let { plugin ->
        AlertDialog(
            onDismissRequest = { pendingApkUnload = null },
            title = { Text(stringResource(R.string.market_plugins_uninstall_title)) },
            text = {
                Text(stringResource(R.string.market_plugins_uninstall_text, plugin.name))
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.unloadPlugin(plugin.id)
                    pendingApkUnload = null
                }) { Text(stringResource(R.string.market_action_uninstall), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingApkUnload = null }) {
                    Text(stringResource(R.string.market_action_cancel))
                }
            }
        )
    }

    // 功能性插件卸载确认（真删除：目录 + 工具注册一并回收）。
    pendingFunctionalUninstall?.let { plugin ->
        AlertDialog(
            onDismissRequest = { pendingFunctionalUninstall = null },
            title = { Text(stringResource(R.string.market_plugin_functional_uninstall_title)) },
            text = {
                Text(stringResource(R.string.market_plugin_functional_uninstall_text, plugin.name))
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.uninstallFunctionalPlugin(plugin.id)
                    pendingFunctionalUninstall = null
                }) { Text(stringResource(R.string.market_action_uninstall), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingFunctionalUninstall = null }) {
                    Text(stringResource(R.string.market_action_cancel))
                }
            }
        )
    }

    if (showImportDialog) {
        ImportFunctionalPluginDialog(
            onDismiss = { showImportDialog = false },
            onImport = { json ->
                viewModel.importFunctionalPlugin(json)
                showImportDialog = false
            }
        )
    }
}

// ═══ 已安装管理 · Skills ═══

@Composable
internal fun InstalledSkillsTab(state: MarketUiState, viewModel: MarketViewModel) {
    var pendingUninstall by remember { mutableStateOf<MarketSkillRow?>(null) }

    if (state.skills.isEmpty()) {
        MarketEmptyState(
            hint = stringResource(R.string.market_installed_skills_empty),
            actionLabel = stringResource(R.string.market_installed_skills_go),
            onAction = { goToBrowse(viewModel) }
        )
        return
    }

    // ── v2 认知排序：按最近使用时间倒序（从未使用的沉底）──
    val sortedSkills = state.skills.sortedByDescending { it.lastUsedAt }

    MarketList(
        items = sortedSkills,
        emptyHint = stringResource(R.string.market_installed_skills_empty_short),
        key = { it.id },
        header = {
            item {
                MarketHeader(
                    stringResource(R.string.market_installed_skills_header, state.skills.size)
                )
            }
        }
    ) { skill ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { viewModel.loadSkillDetail(skill.id) }
        ) {
            MarketCard(
                title = skill.name,
                subtitle = if (skill.lastUsedAt > 0) {
                    "${skill.id} · " + stringResource(
                        R.string.market_skill_last_used,
                        formatRelativeTime(skill.lastUsedAt)
                    )
                } else {
                    "${skill.id} · " + stringResource(R.string.market_skill_never_used)
                },
                description = skill.description,
                trailing = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (skill.bundled) {
                            // Issue #166：assets 释放的内置技能——「内置」徽标（复用状态 chip 样式）
                            MarketStatusChip(
                                text = stringResource(R.string.market_skill_bundled_badge),
                                positive = true
                            )
                        } else if (skill.isCrystallized) {
                            MarketCrystallizedBadge()
                        } else if (skill.isLowEnergy) {
                            MarketLowEnergyBadge()
                        }
                        Switch(
                            checked = skill.enabled,
                            onCheckedChange = { viewModel.toggleSkill(skill.id, it) }
                        )
                        // Issue #166：内置技能不可卸载（可禁用）——不渲染删除按钮，
                        // 卡片下方给替代提示；SkillRegistry.uninstall 侧同样拦一道。
                        if (!skill.bundled) {
                            IconButton(onClick = { pendingUninstall = skill }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.market_cd_uninstall_skill),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            )
            // 能量条
            Spacer(modifier = Modifier.height(4.dp))
            MarketEnergyBar(energy = skill.energy)
            // Issue #166：内置技能的卸载替代提示（删除按钮已隐藏）
            if (skill.bundled) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    stringResource(R.string.market_skill_bundled_uninstall_blocked_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 成功率 + 调用计数
            if (skill.successCount + skill.failureCount > 0) {
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${skill.successCount}✓ / ${skill.failureCount}✗",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    MarketSuccessRateChip(successRate = skill.successRate)
                }
            }
        }
    }

    pendingUninstall?.let { skill ->
        AlertDialog(
            onDismissRequest = { pendingUninstall = null },
            title = { Text(stringResource(R.string.market_uninstall_skill_title)) },
            text = { Text(stringResource(R.string.market_uninstall_skill_text, skill.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.uninstallSkill(skill.id)
                    pendingUninstall = null
                }) { Text(stringResource(R.string.market_action_uninstall), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingUninstall = null }) {
                    Text(stringResource(R.string.market_action_cancel))
                }
            }
        )
    }
}

// ═══ 已安装管理 · MCP ═══

@Composable
internal fun InstalledMcpTab(state: MarketUiState, viewModel: MarketViewModel) {
    var pendingDelete by remember { mutableStateOf<MarketMcpRow?>(null) }
    // 编辑器：VM 持有编辑快照（跨刷新存活，避免列表刷新把填一半的表单冲掉）
    val editingConfig by viewModel.editingMcp.collectAsStateWithLifecycle()
    // BUILTIN 配置对话框（作用域/启停/连接/GitHub 账号）
    var builtinConfigFor by remember { mutableStateOf<MarketMcpRow?>(null) }

    if (state.mcps.isEmpty()) {
        MarketEmptyState(
            hint = stringResource(R.string.market_installed_mcp_empty),
            actionLabel = stringResource(R.string.market_installed_go_add),
            onAction = { goToBrowse(viewModel) }
        )
        return
    }

    MarketList(
        items = state.mcps,
        emptyHint = stringResource(R.string.market_installed_mcp_empty_short),
        key = { it.name },
        header = {
            item {
                MarketHeader(
                    stringResource(R.string.market_installed_mcp_header, state.mcps.size)
                )
            }
        }
    ) { server ->
        MarketCard(
            // 内置服务器（进程内 transport，App 预置）加「内置」标记与用户自建区分。
            title = if (server.builtin) {
                stringResource(R.string.market_builtin_name, server.name)
            } else {
                server.name
            },
            subtitle = server.endpoint,
            description = when (server.transport) {
                McpTransport.STDIO -> stringResource(R.string.market_transport_stdio)
                McpTransport.SSE -> stringResource(R.string.market_transport_sse)
                McpTransport.HTTP -> stringResource(R.string.market_transport_http)
                McpTransport.BUILTIN -> stringResource(R.string.market_transport_builtin)
            },
            trailing = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    MarketStatusChip(
                        text = when {
                            server.connected -> stringResource(R.string.market_status_connected)
                            server.enabled -> stringResource(R.string.market_status_offline)
                            else -> stringResource(R.string.market_status_disabled)
                        },
                        positive = server.connected
                    )
                    Switch(
                        checked = server.enabled,
                        onCheckedChange = { viewModel.toggleMcp(server.name, it) }
                    )
                    // #205 启动时间线：tracker 里留过底的服务器可回放真实
                    // 启动史（pid/argv/serverInfo/stderr），排障「刚才为什么没连上」。
                    if (viewModel.hasMcpTimeline(server.name)) {
                        TextButton(
                            onClick = { viewModel.showMcpTimeline(server.name) }
                        ) {
                            Text(stringResource(R.string.market_mcp_timeline))
                        }
                    }
                    // 配置编辑：改 URL / 命令 / 参数 / 环境变量 / 沙箱开关；
                    // BUILTIN 是进程内预置 —— 也有配置入口（作用域/启停/连接/
                    // GitHub 账号，见 BuiltinMcpConfigDialog），补齐「所有 MCP
                    // 都有配置按钮」的产品要求。
                    if (server.builtin) {
                        TextButton(
                            enabled = state.mcpConnecting != server.name,
                            onClick = { builtinConfigFor = server }
                        ) {
                            Text(stringResource(R.string.market_mcp_config))
                        }
                    } else {
                        TextButton(
                            enabled = state.mcpConnecting != server.name,
                            onClick = { viewModel.openMcpEditor(server.name) }
                        ) {
                            Text(stringResource(R.string.market_mcp_edit_action))
                        }
                    }
                    TextButton(
                        // 连接中禁用该行按钮防双击并发重连（断开不受影响）
                        enabled = state.mcpConnecting != server.name,
                        onClick = {
                            if (server.connected) viewModel.disconnectMcp(server.name)
                            else viewModel.connectMcp(server.name)
                        }
                    ) {
                        Text(
                            when {
                                server.connected -> stringResource(R.string.market_mcp_stop)
                                state.mcpConnecting == server.name -> stringResource(R.string.market_connecting)
                                else -> stringResource(R.string.market_mcp_start)
                            }
                        )
                    }
                    TextButton(
                        // 内置服务器随 App 预置/自愈，不提供删除入口
                        enabled = !server.builtin,
                        onClick = { if (!server.builtin) pendingDelete = server }
                    ) {
                        Text(
                            stringResource(R.string.market_action_delete),
                            color = if (server.builtin) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            else MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        )
    }

    pendingDelete?.let { server ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.market_delete_mcp_title)) },
            text = { Text(stringResource(R.string.market_delete_mcp_text, server.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeMcp(server.name)
                    pendingDelete = null
                }) { Text(stringResource(R.string.market_action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.market_action_cancel))
                }
            }
        )
    }

    // ═══ 配置编辑对话框（预填现有配置；保存后断开→覆盖→重连）═══
    editingConfig?.let { config ->
        EditMcpDialog(
            initial = config,
            // 以当前编辑对象为键重查沙箱态（每次打开编辑器都是新鲜判定）。
            sandboxAvailable = rememberSandboxAvailable(config),
            onSave = { updated -> viewModel.updateMcpServer(updated) },
            onDismiss = { viewModel.closeMcpEditor() },
            // #206 实时预检（编辑模式自动豁免自身名的重名误报）。
            validate = viewModel::validateMcpConfig
        )
    }

    // ═══ BUILTIN 配置对话框（作用域/启停/连接/GitHub 账号）═══
    builtinConfigFor?.let { server ->
        BuiltinMcpConfigDialog(
            server = server,
            viewModel = viewModel,
            onDismiss = { builtinConfigFor = null }
        )
    }
}

// ═══ 已安装管理 · 连接器 ═══

@Composable
internal fun InstalledConnectorsTab(state: MarketUiState, viewModel: MarketViewModel) {
    var pendingDelete by remember { mutableStateOf<ConnectorDef?>(null) }

    if (state.connectors.isEmpty()) {
        MarketEmptyState(
            hint = stringResource(R.string.market_installed_connectors_empty),
            actionLabel = stringResource(R.string.market_installed_go_add),
            onAction = { goToBrowse(viewModel) }
        )
        return
    }

    MarketList(
        items = state.connectors,
        emptyHint = stringResource(R.string.market_installed_connectors_empty_short),
        key = { it.id },
        header = {
            item {
                MarketHeader(stringResource(R.string.market_installed_connectors_header))
            }
        }
    ) { connector ->
        MarketCard(
            title = connector.name,
            subtitle = connector.id,
            description = connector.type + if (connector.endpoint.isNotBlank()) " · ${connector.endpoint}" else "",
            trailing = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (connector.builtin) {
                        MarketStatusChip(
                            text = stringResource(R.string.market_builtin),
                            positive = false
                        )
                    }
                    Switch(
                        checked = connector.enabled,
                        onCheckedChange = { viewModel.toggleConnector(connector.id, it) }
                    )
                    TextButton(
                        onClick = { pendingDelete = connector }
                    ) {
                        Text(stringResource(R.string.market_action_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        )
    }

    pendingDelete?.let { connector ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.market_delete_connector_title)) },
            text = {
                Text(
                    stringResource(R.string.market_delete_connector_text, connector.name) +
                        if (connector.builtin) {
                            stringResource(R.string.market_delete_connector_builtin_suffix)
                        } else ""
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeConnector(connector.id)
                    pendingDelete = null
                }) { Text(stringResource(R.string.market_action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.market_action_cancel))
                }
            }
        )
    }
}

// ═══ 已安装管理 · 集成（魔搭 / GitHub / ClawHub 来源技能）═══

@Composable
internal fun InstalledIntegrationsTab(state: MarketUiState, viewModel: MarketViewModel) {
    var pendingUninstall by remember { mutableStateOf<MarketSkillRow?>(null) }

    // 远程来源技能：魔搭安装固定 ms- 前缀，GitHub SKILL.md 转换安装固定 gh- 前缀，
    // ClawHub 仓库安装固定 ch- 前缀；携带自定义 manifest 的 GitHub 仓库 id 不定，
    // 归入「Skills」页统一管理。
    val remoteSkills = state.skills.filter {
        it.id.startsWith("ms-") || it.id.startsWith("gh-") || it.id.startsWith("ch-")
    }

    if (remoteSkills.isEmpty()) {
        MarketEmptyState(
            hint = stringResource(R.string.market_installed_integrations_empty),
            actionLabel = stringResource(R.string.market_installed_integrations_go),
            onAction = { goToBrowse(viewModel) }
        )
        return
    }

    MarketList(
        items = remoteSkills,
        emptyHint = stringResource(R.string.market_installed_integrations_empty_short),
        key = { it.id },
        header = {
            item {
                MarketHeader(
                    stringResource(R.string.market_installed_integrations_header)
                )
            }
        }
    ) { skill ->
        MarketCard(
            title = skill.name,
            subtitle = skill.id,
            description = skill.description,
            trailing = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    MarketStatusChip(
                        text = when {
                            skill.id.startsWith("ms-") -> stringResource(R.string.market_source_modelscope)
                            skill.id.startsWith("ch-") -> "ClawHub"
                            else -> "GitHub"
                        },
                        positive = false
                    )
                    Switch(
                        checked = skill.enabled,
                        onCheckedChange = { viewModel.toggleSkill(skill.id, it) }
                    )
                    IconButton(onClick = { pendingUninstall = skill }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.market_cd_uninstall_skill),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        )
    }

    pendingUninstall?.let { skill ->
        AlertDialog(
            onDismissRequest = { pendingUninstall = null },
            title = { Text(stringResource(R.string.market_uninstall_skill_title)) },
            text = { Text(stringResource(R.string.market_uninstall_skill_text, skill.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.uninstallSkill(skill.id)
                    pendingUninstall = null
                }) { Text(stringResource(R.string.market_action_uninstall), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingUninstall = null }) {
                    Text(stringResource(R.string.market_action_cancel))
                }
            }
        )
    }
}
