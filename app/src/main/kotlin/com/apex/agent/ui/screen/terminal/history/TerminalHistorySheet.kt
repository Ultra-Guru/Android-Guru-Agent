package com.apex.agent.ui.screen.terminal.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apex.agent.R
import com.apex.agent.ui.screen.terminal.ConsoleTheme

/**
 * T87：命令历史抽屉（Termux `history` 的触屏等价物）。
 *
 * 交互：
 *  - 点条目 → 立即发送到当前会话（免重打）；
 *  - 复制按钮 → 进系统剪贴板（编辑后再发）；
 *  - 清空 → 全删（确认后由宿主执行）。
 *
 * 空态给引导文案（引导用户开始敲命令）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalHistorySheet(
    entries: List<String>,
    onPick: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(Icons.Default.History, contentDescription = null, tint = ConsoleTheme.accent)
                Text(
                    stringResource(R.string.term_history_title),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                    modifier = Modifier.weight(1f)
                )
                if (entries.isNotEmpty()) {
                    TextButton(onClick = onClear) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.term_history_clear),
                            tint = ConsoleTheme.danger,
                            modifier = Modifier.height(16.dp)
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(stringResource(R.string.term_history_clear), color = ConsoleTheme.danger, fontSize = 13.sp)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.term_history_hint),
                fontSize = 12.sp,
                color = ConsoleTheme.dim
            )
            Spacer(Modifier.height(12.dp))

            if (entries.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .background(ConsoleTheme.chip, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.term_history_empty),
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        color = ConsoleTheme.dim
                    )
                }
            } else {
                // 长列表：max-h + 滚动（UI 规范：长列表必须有界滚动区）
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(420.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(entries, key = { it }) { cmd ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(ConsoleTheme.chip, RoundedCornerShape(8.dp))
                                .clickable { onPick(cmd) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                cmd,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                color = ConsoleTheme.text,
                                maxLines = 1,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { clipboard.setText(AnnotatedString(cmd)) },
                                modifier = Modifier.padding(start = 4.dp)
                            ) {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = stringResource(R.string.term_copy),
                                    tint = ConsoleTheme.dim,
                                    modifier = Modifier.height(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
