package com.apex.agent.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apex.agent.R
import com.apex.agent.core.tools.mcp.McpManager
import com.apex.agent.github.GithubTokenManager
import com.apex.agent.github.mcp.BuiltinGithubMcpServer
import com.apex.agent.github.mcp.GithubMcpToolsets
import com.apex.agent.github.mcp.OfficialGithubMcp
import kotlinx.coroutines.launch

/**
 * # GitHub MCP 配置对话框（v1.9 输入框 GitHub 图标入口）
 *
 * 用户规格：「github mcp 要放在输入框的那个 github 图标，点击后可以在
 * 那里配置；这个 GitHub 官方 MCP 不是 git 角色专属」—— 本对话框是
 * 两台 GitHub MCP 服务器的统一配置面（与角色无关，任何工位/角色可用）：
 *
 * - **内置 GitHub MCP**（`github`，进程内 REST 直连，7 工具）：启用开关
 *   + 连接态；禁用即断开（[McpManager.setEnabled] 语义），启用后自动
 *   连接；
 * - **官方远程 GitHub MCP**（`github-official`，api.githubcopilot.com，
 *   全量工具面）：启用开关（连接走 `${GITHUB_TOKEN}` 令牌桥 —— **需先
 *   连接 GitHub 账号**，未连接时给引导而非硬失败）+ **工具集订阅**
 *   （官方 `?toolsets=` 参数：全选 = 服务器默认集；勾选子集 = 显式订阅）。
 *
 * 配置变更直接落 mcp_servers.json（McpManager 单一数据源，市场页/斜杠
 * 菜单/本对话框三处同源），`changes` 流驱动本对话框自动刷新。
 *
 * @param mcpManager     MCP 管理器（配置与连接的单一数据源）。
 * @param tokenManager   GitHub PAT 连接（官方远程服务器的令牌桥前提）。
 */
@Composable
fun GithubMcpConfigDialog(
    mcpManager: McpManager,
    tokenManager: GithubTokenManager,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val connectionState by tokenManager.connectionState.collectAsStateWithLifecycle()
    // 协程内取词需要 Context（stringResource 是 @Composable，不能进 launch）。
    val context = androidx.compose.ui.platform.LocalContext.current

    // 快照态：changes 流驱动重取（市场页同款刷新纪律）。
    var refreshTick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        mcpManager.changes.collect { refreshTick++ }
    }

    val builtinEnabled = remember(refreshTick) {
        mcpManager.getConfigs().firstOrNull { it.name == BuiltinGithubMcpServer.ID }?.enabled ?: false
    }
    val builtinConnected = remember(refreshTick) {
        mcpManager.getConnectedServers().contains(BuiltinGithubMcpServer.ID)
    }
    val officialConfig = remember(refreshTick) {
        mcpManager.getConfigs().firstOrNull { it.name == OfficialGithubMcp.SERVER_NAME }
    }
    val officialEnabled = officialConfig?.enabled ?: false
    val officialConnected = remember(refreshTick) {
        mcpManager.getConnectedServers().contains(OfficialGithubMcp.SERVER_NAME)
    }
    var officialSelected by remember(officialConfig?.url) {
        mutableStateOf(
            GithubMcpToolsets.parseSelected(officialConfig?.url ?: OfficialGithubMcp.REMOTE_URL)
        )
    }

    // 操作反馈（连接失败的可行动指引）。
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_github_mark),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp)
                )
                Text(stringResource(R.string.github_mcp_dialog_title))
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // ═══ 内置 GitHub MCP（进程内，7 工具）═══
                McpServerBlock(
                    title = stringResource(R.string.github_mcp_builtin_title),
                    subtitle = stringResource(R.string.github_mcp_builtin_subtitle),
                    enabled = builtinEnabled,
                    connected = builtinConnected,
                    onToggle = { enabled ->
                        busy = true
                        scope.launch {
                            mcpManager.setEnabled(BuiltinGithubMcpServer.ID, enabled)
                            if (enabled) {
                                mcpManager.connect(BuiltinGithubMcpServer.ID).fold(
                                    onSuccess = { feedback = null },
                                    onFailure = {
                                        feedback = context.getString(
                                            R.string.github_mcp_connect_failed, it.message ?: ""
                                        )
                                    }
                                )
                            }
                            busy = false
                        }
                    }
                )

                HorizontalDivider()

                // ═══ 官方远程 GitHub MCP（全量工具面）═══
                McpServerBlock(
                    title = stringResource(R.string.github_mcp_official_title),
                    subtitle = stringResource(R.string.github_mcp_official_subtitle),
                    enabled = officialEnabled,
                    connected = officialConnected,
                    onToggle = { enabled ->
                        busy = true
                        scope.launch {
                            mcpManager.setEnabled(OfficialGithubMcp.SERVER_NAME, enabled)
                            if (enabled) {
                                mcpManager.connect(OfficialGithubMcp.SERVER_NAME).fold(
                                    onSuccess = { feedback = null },
                                    onFailure = {
                                        feedback = context.getString(
                                            R.string.github_mcp_connect_failed, it.message ?: ""
                                        )
                                    }
                                )
                            }
                            busy = false
                        }
                    }
                )

                // 令牌前提：官方远程服务器要求 PAT（令牌桥占位符解析）。
                if (!connectionState.isConnected) {
                    Text(
                        text = stringResource(R.string.github_mcp_official_need_pat),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }

                // ═══ 工具集订阅（官方 ?toolsets= 参数；启用态才可编辑）═══
                Text(
                    text = stringResource(R.string.github_mcp_toolsets_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.github_mcp_toolsets_subtitle),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                GithubMcpToolsets.ALL.forEach { toolset ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(
                            checked = toolset.key in officialSelected,
                            enabled = officialEnabled && !busy,
                            onCheckedChange = { checked ->
                                val next = if (checked) {
                                    officialSelected + toolset.key
                                } else {
                                    officialSelected - toolset.key
                                }
                                officialSelected = next
                                // 立即落配置：URL 重建（保留 enabled 态与
                                // Authorization 占位符头）；已连接则重连生效。
                                busy = true
                                scope.launch {
                                    val wasConnected =
                                        mcpManager.getConnectedServers().contains(OfficialGithubMcp.SERVER_NAME)
                                    if (wasConnected) {
                                        mcpManager.disconnect(OfficialGithubMcp.SERVER_NAME)
                                    }
                                    mcpManager.addServer(
                                        OfficialGithubMcp.config().copy(
                                            url = GithubMcpToolsets.urlWithToolsets(next),
                                            enabled = officialEnabled
                                        )
                                    )
                                    if (wasConnected && officialEnabled) {
                                        mcpManager.connect(OfficialGithubMcp.SERVER_NAME).fold(
                                            onSuccess = { feedback = null },
                                            onFailure = {
                                                feedback = context.getString(
                                                    R.string.github_mcp_connect_failed, it.message ?: ""
                                                )
                                            }
                                        )
                                    }
                                    busy = false
                                }
                            }
                        )
                        Column {
                            Text(
                                text = "${toolset.zhLabel} · ${toolset.key}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = toolset.summary,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                feedback?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                }
                TextButton(onClick = onDismiss, enabled = !busy) {
                    Text(stringResource(R.string.github_mcp_dialog_done))
                }
            }
        }
    )
}

/** 服务器块：标题/副标题 + 连接态徽标 + 启用开关（两台服务器同构）。 */
@Composable
private fun McpServerBlock(
    title: String,
    subtitle: String,
    enabled: Boolean,
    connected: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
        Text(
            text = when {
                connected -> stringResource(R.string.github_mcp_status_connected)
                enabled -> stringResource(R.string.github_mcp_status_enabled)
                else -> stringResource(R.string.github_mcp_status_disabled)
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (connected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 2.dp)
        )
    }
}
