package com.apex.agent.ui.screen.code.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.ui.screen.code.CodeViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ─────────────────────────────────────────────────────────────────────────────
// Coding 历史对话面板（ModalBottomSheet）+ 工作区条入口按钮。
//
// 自管态（entries 加载 / 删除确认 / 恢复中）——CodeScreen 只出一个
// showCodeHistory 状态位与一次挂载调用，行数预算零压力。
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 历史入口按钮（工作区条尾部）：对话历史（区别于长任务中心的 History 图标）。
 * UI-012：48dp 触区红线。
 */
@Composable
fun CodeHistoryIconButton(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
        Icon(
            Icons.AutoMirrored.Outlined.Chat,
            contentDescription = stringResource(R.string.code_history_open),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * 历史对话面板：列表（点按恢复）+ 导出 Markdown（系统分享）+ 删除
 * （二次确认）+ 空态。导出按钮与 Agent 屏会话历史「导出为 Markdown」
 * 同款能力（此前 Coding 侧缺失 —— 用户实测反馈补齐）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeHistorySheet(
    viewModel: CodeViewModel,
    onDismiss: () -> Unit
) {
    // 导出需要 applicationContext（FileProvider + 分享拉起；VM 非
    // AndroidViewModel，取 Compose 侧上下文后解包防泄漏）
    val context = androidx.compose.ui.platform.LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var entries by remember { mutableStateOf<List<CodeHistoryEntry>?>(null) }
    var restoring by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<CodeHistoryEntry?>(null) }
    val scope = rememberCoroutineScope()

    // 打开即载入（恢复/删除后重载同一路径）
    LaunchedEffect(Unit) {
        entries = viewModel.loadCodeHistoryEntries()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.Chat,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = stringResource(R.string.code_history_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = (entries?.size ?: 0).toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(12.dp))

            when {
                entries == null -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                }
                entries.orEmpty().isEmpty() -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.code_history_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 长列表内滚（8 条 ≈ 一屏；Sheet 自身不滚动防嵌套手势冲突）
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(entries.orEmpty(), key = { it.id }) { entry ->
                        CodeHistoryRow(
                            entry = entry,
                            enabled = !restoring,
                            onClick = {
                                restoring = true
                                viewModel.restoreCodeHistoryEntry(entry)
                                onDismiss()
                            },
                            onExport = {
                                // 导出只读归档（readSnapshot 零副作用），面板保持
                                // 打开 —— 连续导出多条不被分享面板打断后丢现场。
                                viewModel.exportCodeHistoryEntryMarkdown(context, entry)
                            },
                            onDelete = { confirmDelete = entry }
                        )
                    }
                    item { Spacer(modifier = Modifier.height(12.dp)) }
                }
            }
        }
    }

    // 删除二次确认（破坏性操作，与长任务中心同款口径）
    confirmDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.code_history_delete_confirm)) },
            text = { Text(stringResource(R.string.code_history_delete_msg_fmt, entry.title.ifBlank { entry.id })) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    // 乐观删除：先从本地列表移除，协程落盘；失败仅留痕（重开自然校正）
                    entries = entries.orEmpty().filterNot { it.id == entry.id }
                    scope.launch { viewModel.deleteCodeHistoryEntry(entry) }
                }) {
                    Text(stringResource(R.string.code_history_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) {
                    Text(stringResource(R.string.code_history_cancel))
                }
            }
        )
    }
}

/** 单条历史：标题 + 时间/条数 + 导出钮 + 删除钮；整行点按恢复。 */
@Composable
private fun CodeHistoryRow(
    entry: CodeHistoryEntry,
    enabled: Boolean,
    onClick: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    val timeFmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                shape = CircleShape,
                modifier = Modifier.size(26.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = entry.messageCount.coerceAtMost(999).toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.title.ifBlank { stringResource(R.string.code_history_no_user_msg) },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = timeFmt.format(Date(entry.updatedAt)),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 导出 Markdown（只读通道，恢复进行中也不禁用 —— 互不抢活位）
            IconButton(
                onClick = onExport,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            ) {
                Icon(
                    Icons.Outlined.FileDownload,
                    contentDescription = stringResource(R.string.code_history_export),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
            IconButton(
                onClick = onDelete,
                enabled = enabled,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            ) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.code_history_delete),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
