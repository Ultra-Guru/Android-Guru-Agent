package com.apex.agent.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apex.agent.R
import com.apex.agent.ui.screen.terminal.CommandPolicyStore
import com.apex.agent.ui.screen.terminal.TerminalSettingsStore
import com.apex.agent.ui.screen.terminal.TerminalViewModel
import com.apex.agent.ui.screen.terminal.extrakeys.ExtraKeysConfig
import com.apex.agent.ui.screen.terminal.scheme.TerminalColorSchemeRegistry
import com.apex.agent.ui.screen.terminal.scheme.TerminalColorSchemeSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * 设置中心「终端」大分类（扩展阶段）。
 *
 * 终端的 12 项持久化设置此前只活在终端页 bottom sheet（TerminalSettingsSheet），
 * 入口深、无全局归纳 —— 本文件把它们接入设置中心两级抽屉：
 *  - terminal/appearance：字号 / 单色 / 键栏 / 响铃振动 / 屏幕常亮；
 *  - terminal/scheme：配色方案 + bold-as-bright；
 *  - terminal/keys：扩展键宏（增删 + 恢复默认）；
 *  - terminal/policy：命令黑白名单。
 *
 * 三个 store 为 Hilt 单例（TerminalModule 提供）—— 与终端页共享同一内存态：
 * 在设置中心改字号/配色，终端页（若在后栈）经同一 StateFlow 即时刷新；
 * 反向亦然。终端页 bottom sheet 保留（场景内快捷调整），两入口等价。
 *
 * UI 控件对齐 SettingsWidgets 惯例（SwitchRow / IntFieldRow / DropdownRow），
 * 文案复用终端域既有 term_* i18n 键（en+zh 已对称），无新增存储面。
 */
@HiltViewModel
class SettingsTerminalViewModel @Inject constructor(
    val settingsStore: TerminalSettingsStore,
    val policyStore: CommandPolicyStore,
    val schemeSettings: TerminalColorSchemeSettings,
    // 方案显示名跟随应用语言（与终端页选择器同口径）
    private val lang: com.apex.agent.ui.language.LanguageManager,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
) : ViewModel() {

    /** 中文判定（zh 显式 → 中文；system → 设备 Locale；en → 英文）—— 与 TerminalViewModel.isZhLanguage 同口径。 */
    fun isZh(): Boolean = when (lang.language.value) {
        "zh" -> true
        "en" -> false
        else -> runCatching {
            val locales = appContext.resources.configuration.locales
            locales.size() > 0 && locales[0].language == "zh"
        }.getOrDefault(false)
    }
}

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
                min = TerminalViewModel.TerminalSettings.MIN_FONT_SIZE,
                max = TerminalViewModel.TerminalSettings.MAX_FONT_SIZE
            ) { n -> viewModel.settingsStore.update { copy(fontSize = n) } }
            SwitchRow(stringResource(R.string.term_monochrome), settings.monochrome) { on ->
                viewModel.settingsStore.update { copy(monochrome = on) }
            }
            SwitchRow(stringResource(R.string.term_keybar), settings.showKeybar) { on ->
                viewModel.settingsStore.update { copy(showKeybar = on) }
            }
        }
        SectionCard(
            title = stringResource(R.string.term_feedback),
            icon = Icons.Default.Settings
        ) {
            SwitchRow(
                stringResource(R.string.term_vibrate_on_bell), settings.vibrateOnBell,
                description = stringResource(R.string.term_vibrate_desc)
            ) { on -> viewModel.settingsStore.update { copy(vibrateOnBell = on) } }
            SwitchRow(
                stringResource(R.string.term_keep_screen_on), settings.keepScreenOn,
                description = stringResource(R.string.term_keep_screen_on_desc)
            ) { on -> viewModel.settingsStore.update { copy(keepScreenOn = on) } }
        }
    }
}

// ═══════════════ terminal/scheme：配色方案 ═══════════════

@Composable
internal fun TerminalSchemePage(viewModel: SettingsTerminalViewModel = hiltViewModel()) {
    val schemeId by viewModel.schemeSettings.schemeId.collectAsStateWithLifecycle()
    val boldAsBright by viewModel.schemeSettings.boldAsBright.collectAsStateWithLifecycle()

    // 方案显示名跟随应用语言（zh → nameZh；与终端页选择器同口径）
    val zh = viewModel.isZh()
    val options = TerminalColorSchemeRegistry.all().map { it to it.displayName(zh) }

    SettingsLeafColumn {
        SectionCard(
            title = stringResource(R.string.term_scheme_section),
            icon = Icons.Default.Palette
        ) {
            DropdownRow(
                label = stringResource(R.string.term_scheme_current),
                options = options,
                selected = TerminalColorSchemeRegistry.byId(schemeId),
                description = stringResource(R.string.settings_terminal_scheme_desc)
            ) { scheme -> viewModel.schemeSettings.setSchemeId(scheme.id) }
            SwitchRow(
                stringResource(R.string.term_bold_as_bright), boldAsBright,
                description = stringResource(R.string.term_bold_as_bright_desc)
            ) { on -> viewModel.schemeSettings.setBoldAsBright(on) }
        }
    }
}

// ═══════════════ terminal/keys：扩展键宏 ═══════════════

@Composable
internal fun TerminalExtraKeysPage(viewModel: SettingsTerminalViewModel = hiltViewModel()) {
    val extraKeys by viewModel.settingsStore.extraKeys.collectAsStateWithLifecycle()
    SettingsLeafColumn {
        SectionCard(
            title = stringResource(R.string.term_extra_keys_section),
            icon = Icons.Default.Apps
        ) {
            Text(
                stringResource(R.string.term_extra_keys_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            if (extraKeys.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height((extraKeys.size.coerceAtMost(6) * 36).dp)
                ) {
                    items(extraKeys, key = { it.label + it.kind + it.payload }) { k ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "• ${k.label}  (${kindLabel(k.kind)})",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                            TextButton(onClick = { viewModel.settingsStore.removeExtraKey(k.label) }) {
                                Text(
                                    stringResource(R.string.term_remove),
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
            StringAdderRow(
                hint = stringResource(R.string.term_extra_keys_hint),
                addLabel = stringResource(R.string.term_add)
            ) { spec -> viewModel.settingsStore.addExtraKey(spec) }
            TextButton(onClick = { viewModel.settingsStore.resetExtraKeys() }) {
                Text(
                    stringResource(R.string.term_extra_keys_reset),
                    color = MaterialTheme.colorScheme.primary
                )
            }
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
                title = stringResource(R.string.term_blacklist),
                items = blacklist.toList().sorted(),
                onAdd = { viewModel.policyStore.addBlacklist(it) },
                onRemove = { viewModel.policyStore.removeBlacklist(it) },
                danger = true
            )
            CommandSetEditor(
                title = stringResource(R.string.term_whitelist),
                items = whitelist.toList().sorted(),
                onAdd = { viewModel.policyStore.addWhitelist(it) },
                onRemove = { viewModel.policyStore.removeWhitelist(it) },
                danger = false
            )
        }
    }
}

// ───────────────────────────── 私有小组件 ─────────────────────────────

/** 宏类型 → 短标签（与终端设置弹层同款）。 */
private fun kindLabel(kind: ExtraKeysConfig.MacroKind): String = when (kind) {
    ExtraKeysConfig.MacroKind.TEXT -> stringResourceFor("文本", "Text")
    ExtraKeysConfig.MacroKind.CMD -> stringResourceFor("命令", "Command")
    ExtraKeysConfig.MacroKind.KEY -> stringResourceFor("按键", "Key")
    ExtraKeysConfig.MacroKind.CTRL -> stringResourceFor("控制", "Ctrl")
    ExtraKeysConfig.MacroKind.PASTE -> stringResourceFor("粘贴", "Paste")
}

/** 非 Compose 场景的轻量双语取词（跟随 Locale；kindLabel 列表项用）。 */
private fun stringResourceFor(zh: String, en: String): String =
    if (java.util.Locale.getDefault().language == "zh") zh else en

/** 输入框 + 添加按钮（黑白名单条目 / 扩展键 spec 共用形态）。 */
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

/** 名单编辑器（标题 + 添加行 + 条目列表；黑名单红标题 / 白名单主色标题）。 */
@Composable
private fun CommandSetEditor(
    title: String,
    items: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    danger: Boolean
) {
    Text(
        title,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
        color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    )
    StringAdderRow(
        hint = stringResource(R.string.term_cmd_hint),
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
