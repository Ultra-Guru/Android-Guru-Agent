@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.apex.agent.ui.screen.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apex.agent.R

/**
 * 命令历史 Sheet（T96 自旧 history/ 子包转移 + M3 化）。
 *
 * 行为与旧版一致：点击条目 = 直接提交执行（经过门禁管线）；逐行复制按钮
 * 走系统剪贴板并 toast（复制计数由 [TerminalViewModel.notifyInfo] 反馈）。
 */
@Composable
internal fun TerminalHistorySheet(
    viewModel: TerminalViewModel,
    onDismiss: () -> Unit
) {
    TerminalConsoleTheme {
        val entries by viewModel.history.collectAsStateWithLifecycle()
        val context = LocalContext.current
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        ModalBottomSheet(onDismissRequest = onDismiss, containerColor = ConsoleTokens.background) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.term_history_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = ConsoleTokens.onSurface,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            stringResource(R.string.term_history_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = ConsoleTokens.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = { viewModel.clearHistory() }) {
                        Text(stringResource(R.string.term_history_clear), color = ConsoleTokens.lineError)
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (entries.isEmpty()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            stringResource(R.string.term_history_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = ConsoleTokens.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        Modifier
                            .fillMaxWidth()
                            .height(420.dp)
                    ) {
                        items(entries.size) { i ->
                            val cmd = entries[i]
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    cmd,
                                    Modifier
                                        .weight(1f)
                                        .padding(vertical = 6.dp),
                                    color = ConsoleTokens.onSurface,
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                IconButton(
                                    onClick = {
                                        clipboard.setPrimaryClip(
                                            ClipData.newPlainText("terminal", cmd)
                                        )
                                        // 复制成功反馈（通知条）
                                        viewModel.onHistoryCopied(cmd.length)
                                    }
                                ) {
                                    Icon(
                                        Icons.Filled.ContentCopy,
                                        stringResource(R.string.term_copy),
                                        Modifier.size(16.dp),
                                        tint = ConsoleTokens.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
