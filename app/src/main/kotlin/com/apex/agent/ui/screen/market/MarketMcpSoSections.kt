package com.apex.agent.ui.screen.market

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.core.tools.marketplace.McpSoSource

/**
 * ═══ mcp.so 社区目录 · 市场 UI 区块 ═══
 *
 * MCP 页签的社区长尾目录（官方 Hub 仓库之外的 1.5 万+ 服务器）：
 * - [McpSoCard]：目录行卡（名称 + 作者 + 描述 + 分类/热度 + 一键安装）；
 * - 已配置同名服务器 → 显示「已安装」徽标（安装 ≠ 启动，启动在
 *   「当前工位已配置服务器」区块统一操作，与官方 Hub 同口径）。
 *
 * 安装链路见 [MarketMcpSoController.installServer]：详情页 mcpServers
 * 配置 → 统一解析（STDIO 自动路由 PRoot 沙箱）→ 注册表。
 */

/** mcp.so 目录行卡。 */
@Composable
internal fun McpSoCard(
    entry: McpSoSource.McpSoEntry,
    installed: Boolean,
    installing: Boolean,
    installBusy: Boolean,
    onInstall: () -> Unit
) {
    Column {
        MarketCard(
            title = entry.name + if (entry.verified) " ✓" else "",
            subtitle = buildString {
                if (entry.author.isNotBlank()) append(entry.author)
                if (entry.category.isNotBlank()) {
                    if (isNotEmpty()) append(" · ")
                    append(entry.category)
                }
                if (entry.popularity.isNotBlank()) {
                    if (isNotEmpty()) append(" · ")
                    append(entry.popularity)
                }
            },
            description = entry.description,
            descriptionMaxLines = 2,
            trailing = {
                if (installed) {
                    MarketStatusChip(
                        text = stringResource(R.string.market_status_installed),
                        positive = true
                    )
                } else {
                    TextButton(
                        onClick = onInstall,
                        enabled = !installBusy
                    ) {
                        Text(
                            if (installing) stringResource(R.string.market_installing)
                            else stringResource(R.string.market_action_install)
                        )
                    }
                }
            }
        )
        if (installing) {
            Text(
                text = stringResource(R.string.market_mcpso_fetching_config),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp)
            )
        }
    }
}

/** mcp.so 区块的「加载更多」行（末页隐藏）。 */
@Composable
internal fun McpSoLoadMoreRow(
    loadingMore: Boolean,
    hasMore: Boolean,
    enabled: Boolean,
    onLoadMore: () -> Unit
) {
    if (!hasMore) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onLoadMore, enabled = enabled && !loadingMore) {
            Text(
                if (loadingMore) stringResource(R.string.market_mcpso_loading_more)
                else stringResource(R.string.market_mcpso_load_more),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
