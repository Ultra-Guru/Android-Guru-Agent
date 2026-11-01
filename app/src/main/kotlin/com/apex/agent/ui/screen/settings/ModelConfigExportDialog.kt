package com.apex.agent.ui.screen.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.core.llm.ModelProfile
import com.apex.agent.core.llm.ProviderConfig
import com.apex.agent.core.llm.share.ModelConfigExporter

/**
 * 模型配置导出弹窗（导入弹窗的对称闭环）。
 *
 * 把当前服务商 + 其全部模型档案序列化为可读 JSON（[ModelConfigExporter]，
 * 与导入识别同一套键名约定 —— 本应用导出的文本可直接粘回导入弹窗恢复）。
 *
 * 密钥纪律：API Key **永不导出**（省略 api_key 键，对齐整包备份的脱敏
 * 口径），弹窗内显式提示；导出文本可安全粘贴到任何地方。
 *
 * 交互流：打开即生成预览（随档案编辑实时刷新）→「复制」进剪贴板 /
 * 手动选择文本。纯展示 + 复制，无落盘动作。
 */
@Composable
internal fun ModelConfigExportDialog(
    provider: ProviderConfig,
    profiles: List<ModelProfile>,
    onDismiss: () -> Unit
) {
    // 随编辑实时刷新（纯函数、开销可忽略）；空串 = 无可导出内容
    val json = remember(provider, profiles) {
        ModelConfigExporter.export(provider, profiles)
    }
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val scroll = rememberScrollState()
    val hScroll = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.model_export_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.model_export_redacted),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                if (json.isBlank()) {
                    Text(
                        stringResource(R.string.model_export_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    Text(
                        json,
                        style = TextStyle(fontFamily = FontFamily.Monospace),
                        modifier = Modifier
                            .heightIn(max = 320.dp)
                            .verticalScroll(scroll)
                            .horizontalScroll(hScroll)
                    )
                }
            }
        },
        confirmButton = {
            Row {
                if (copied) {
                    Text(
                        stringResource(R.string.model_export_copied),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.heightIn(min = 32.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                }
                TextButton(
                    enabled = json.isNotBlank(),
                    onClick = {
                        clipboard.setText(AnnotatedString(json))
                        copied = true
                    }
                ) {
                    Icon(
                        Icons.Outlined.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.model_export_copy))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.model_close)) }
        }
    )
}
