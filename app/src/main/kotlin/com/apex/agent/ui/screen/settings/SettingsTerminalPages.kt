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
import androidx.compose.material.icons.filled.Settings
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
import com.apex.agent.ui.screen.terminal.TerminalSettings
import com.apex.agent.ui.screen.terminal.TerminalSettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * 设置中心「终端」大分类（扩展阶段）。
 *
 * 终端持久化设置此前只活在终端页 bottom sheet（TerminalSettingsSheet），
 * 入口深、无全局归纳 —— 本文件把它们接入设置中心两级抽屉：
 *  - terminal/appearance：字号 / 屏幕常亮；
 *  - terminal/policy：命令黑白名单；
 *  - terminal/service：Ubuntu 终端运行时宿主（ApexCoreService）保活开关
 *    （原 Agent 设置分区的 Keep Alive 开关迁入——服务本体是终端生命周期宿主）。
 *
 * T96 终端大重构后叶子随控制台收窄：配色方案与扩展键宏已随旧交互终端
 * 退役（scheme/extrakeys 子包删除），terminal/scheme 与 terminal/keys
 * 两叶不再存在；网格时代字段（monochrome/showKeybar/vibrateBell）一并
 * 退役，appearance 叶只剩控制台仍消费的两项。
 *
 * 两个 store 为 Hilt 单例（TerminalModule 提供）—— 与终端控制台共享
 * 同一内存态：在设置中心改字号，终端页（若在后栈）经同一 StateFlow
 * 即时刷新；反向亦然。终端页设置 Sheet 保留（场景内快捷调整），两入口
 * 等价。
 *
 * UI 控件对齐 SettingsWidgets 惯例（SwitchRow / IntFieldRow），
 * 文案复用终端域既有 term_* i18n 键（en+zh 已对称），无新增存储面。
 */
@HiltViewModel
class SettingsTerminalViewModel @Inject constructor(
    val settingsStore: TerminalSettingsStore,
    val policyStore: CommandPolicyStore,
) : ViewModel()

// ═══════════════ terminal/appearance：外观与反馈 ═══════════════

@Composable
internal fun TerminalAppearancePage(viewModel: SettingsTerminalViewModel = hiltViewModel()) {
    val settings by viewModel.settingsStore.settings.collectAsStateWithLifecycle()
    SettingsLeafColumn {
        SectionCard(
            title = stringResource(R.string.term_appearance),
            icon = Icons.Default.Settings
        ) {
            IntFieldRow(
                label = stringResource(R.string.term_font_size),
                value = settings.fontSize,
                min = TerminalSettings.MIN_FONT_SIZE,
                max = TerminalSettings.MAX_FONT_SIZE
            ) { n -> viewModel.settingsStore.update { copy(fontSize = n) } }
            SwitchRow(
                stringResource(R.string.term_keep_screen_on), settings.keepScreenOn,
                description = stringResource(R.string.term_keep_screen_on_desc)
            ) { on -> viewModel.settingsStore.update { copy(keepScreenOn = on) } }
        }
    }
}

// ═══════════════ terminal/policy：命令黑白名单 ═══════════════

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
 * 开关（自原 Agent 设置分区迁入，接线不变：CoreServiceGate.apply 即时启停）。
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
