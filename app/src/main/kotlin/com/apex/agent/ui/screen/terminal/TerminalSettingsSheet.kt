@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.apex.agent.ui.screen.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apex.agent.R

/**
 * 终端设置 Sheet（T96 转移 + M3 化；网格时代字段随交互视图退役，
 * 保留字号 / 亮屏 / 黑白名单）。
 */
@Composable
internal fun TerminalSettingsSheet(
    viewModel: TerminalViewModel,
    onDismiss: () -> Unit
) {
    TerminalConsoleTheme {
        val settings by viewModel.settings.collectAsStateWithLifecycle()
        val blacklist by viewModel.blacklist.collectAsStateWithLifecycle()
        val whitelist by viewModel.whitelist.collectAsStateWithLifecycle()

        ModalBottomSheet(onDismissRequest = onDismiss, containerColor = ConsoleTokens.background) {
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .height(560.dp)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        stringResource(R.string.term_settings_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = ConsoleTokens.onSurface,
                        fontFamily = FontFamily.Monospace
                    )
                }
                item {
                    SectionCard(title = stringResource(R.string.term_appearance)) {
                        FontSizeRow(
                            value = settings.fontSize,
                            onMinus = { viewModel.updateSettings { copy(fontSize = fontSize - 1) } },
                            onPlus = { viewModel.updateSettings { copy(fontSize = fontSize + 1) } }
                        )
                    }
                }
                item {
                    SectionCard(title = stringResource(R.string.term_feedback)) {
                        ToggleRow(
                            title = stringResource(R.string.term_keep_screen_on),
                            desc = stringResource(R.string.term_keep_screen_on_desc),
                            checked = settings.keepScreenOn,
                            onToggle = { on -> viewModel.updateSettings { copy(keepScreenOn = on) } }
                        )
                    }
                }
                item {
                    SectionCard(title = stringResource(R.string.term_blacklist_title)) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                stringResource(R.string.term_blacklist_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = ConsoleTokens.onSurfaceVariant
                            )
                            PolicyListEditor(
                                placeholder = stringResource(R.string.term_cmd_hint),
                                addLabel = stringResource(R.string.term_add),
                                entries = blacklist,
                                onAdd = { viewModel.addBlacklist(it) },
                                onRemove = { viewModel.removeBlacklist(it) }
                            )
                        }
                    }
                }
                item {
                    SectionCard(title = stringResource(R.string.term_whitelist)) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            PolicyListEditor(
                                placeholder = stringResource(R.string.term_cmd_hint),
                                addLabel = stringResource(R.string.term_add),
                                entries = whitelist,
                                onAdd = { viewModel.addWhitelist(it) },
                                onRemove = { viewModel.removeWhitelist(it) }
                            )
                        }
                    }
                }
                item {
                    Text(
                        stringResource(R.string.term_env_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = ConsoleTokens.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                }
            }
        }
    }
}

/** 字号行：− 值 +（8..24 钳制在 Store.update 内完成）。 */
@Composable
private fun FontSizeRow(value: Int, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            stringResource(R.string.term_font_size),
            style = MaterialTheme.typography.bodyMedium,
            color = ConsoleTokens.onSurface,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onMinus) { Text("−", color = ConsoleTokens.onSurfaceVariant) }
        Text(
            "$value",
            style = MaterialTheme.typography.bodyMedium,
            color = ConsoleTokens.onSurface,
            fontFamily = FontFamily.Monospace
        )
        IconButton(onClick = onPlus) { Text("＋", color = ConsoleTokens.onSurfaceVariant) }
    }
}

/** 名单编辑器：输入 + 添加 + 已有条目移除。 */
@Composable
private fun PolicyListEditor(
    placeholder: String,
    addLabel: String,
    entries: Set<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit
) {
    var draft by rememberSaveable { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            placeholder = { Text(placeholder, fontFamily = FontFamily.Monospace) },
            trailingIcon = {
                TextButton(
                    onClick = {
                        if (draft.isNotBlank()) {
                            onAdd(draft)
                            draft = ""
                        }
                    }
                ) { Text(addLabel) }
            },
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace)
        )
        if (entries.isNotEmpty()) {
            entries.sorted().forEach { entry ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        entry,
                        Modifier.weight(1f),
                        color = ConsoleTokens.onSurface,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                    TextButton(onClick = { onRemove(entry) }) {
                        Text(stringResource(R.string.term_remove), color = ConsoleTokens.lineError)
                    }
                }
            }
        } else {
            Text(
                stringResource(R.string.term_policy_empty),
                style = MaterialTheme.typography.bodySmall,
                color = ConsoleTokens.onSurfaceVariant
            )
        }
    }
}
