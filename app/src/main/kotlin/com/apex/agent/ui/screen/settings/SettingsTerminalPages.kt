package com.apex.agent.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apex.agent.R
import com.apex.agent.ui.screen.terminal.CommandPolicyStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * 设置中心「终端」大分类（T97 裁剪版）。
 *
 * T97（Ubuntu 大重构）：终端控制台退役后，terminal/appearance 叶
 * （字号 / 屏幕常亮 —— 控制台专属消费项）随之删除；环境页在安装进行中
 * 自主亮屏，无需全局开关。保留两叶：
 *  - terminal/policy：命令黑白名单 —— **Agent 侧执行门禁**（TerminalPolicyImpl
 *    dynamicPolicy 实时消费 prefs，Agent 工具执行与本名单共享同一份策略）；
 *  - terminal/service：Ubuntu 终端运行时宿主（ApexCoreService）保活开关。
 */
@HiltViewModel
class SettingsTerminalViewModel @Inject constructor(
    val policyStore: CommandPolicyStore
) : ViewModel()

// ═══════════════ terminal/policy：命令黑白名单（Agent 执行门禁）═══════════════

@Composable
internal fun TerminalCommandPolicyPage(viewModel: SettingsTerminalViewModel = hiltViewModel()) {
    val blacklist by viewModel.policyStore.blacklist.collectAsStateWithLifecycle()
    val whitelist by viewModel.policyStore.whitelist.collectAsStateWithLifecycle()

    SettingsLeafColumn {
        SectionCard(
            title = stringResource(R.string.term_blacklist_title),
            icon = Icons.Default.Block
        ) {
            Text(
                stringResource(R.string.term_blacklist_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            CommandSetEditor(
                items = blacklist.toList().sorted(),
                onAdd = { viewModel.policyStore.addBlacklist(it) },
                onRemove = { viewModel.policyStore.removeBlacklist(it) }
            )
        }
        SectionCard(
            title = stringResource(R.string.term_whitelist),
            icon = Icons.Default.Block
        ) {
            CommandSetEditor(
                items = whitelist.toList().sorted(),
                onAdd = { viewModel.policyStore.addWhitelist(it) },
                onRemove = { viewModel.policyStore.removeWhitelist(it) }
            )
        }
    }
}

// ═══════════════ terminal/service：Ubuntu 终端运行时宿主保活 ═══════════════

/**
 * 后台服务页：ApexCoreService（TerminalRuntime 生命周期宿主）的 Keep Alive
 * 开关（接线不变：CoreServiceGate.apply 即时启停）。
 */
@Composable
internal fun TerminalServicePage(agent: AgentSettings, onAgent: (AgentSettings) -> Unit) {
    // #236：Keep Alive 开关的运行时接线需要 Context（start/stopService）。
    val ctx = androidx.compose.ui.platform.LocalContext.current
    SettingsLeafColumn {
        SectionCard(
            title = stringResource(R.string.settings_terminal_service_section),
            icon = Icons.Outlined.Settings
        ) {
            SwitchRow(
                stringResource(R.string.settings_keep_alive_service), agent.keepAlive,
                description = stringResource(R.string.settings_terminal_service_desc)
            ) { on ->
                com.apex.agent.service.CoreServiceGate.apply(ctx, on)
                onAgent(agent.copy(keepAlive = on))
            }
        }
    }
}

// ───────────────────────────── 私有小组件 ─────────────────────────────

/** 输入框 + 添加按钮（黑白名单条目录入形态）。 */
@Composable
private fun StringAdderRow(hint: String, addLabel: String, onAdd: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text(hint, style = MaterialTheme.typography.bodySmall) },
            textStyle = MaterialTheme.typography.bodySmall
        )
        TextButton(onClick = {
            if (input.isNotBlank()) {
                onAdd(input.trim())
                input = ""
            }
        }) { Text(addLabel) }
    }
}

/** 名单编辑器（添加行 + 条目列表；卡片标题由外层 SectionCard 提供）。 */
@Composable
private fun CommandSetEditor(
    items: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit
) {
    StringAdderRow(
        hint = stringResource(R.string.term_policy_hint),
        addLabel = stringResource(R.string.term_add),
        onAdd = onAdd
    )
    if (items.isNotEmpty()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .height((items.size.coerceAtMost(6) * 36).dp)
        ) {
            // #245：列表源是 Set<String>（去重保证），内容即稳定 key
            items(items, key = { it }) { cmd ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "• $cmd",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    TextButton(onClick = { onRemove(cmd) }) {
                        Text(
                            stringResource(R.string.term_remove),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}
