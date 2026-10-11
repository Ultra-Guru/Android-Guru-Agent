@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.apex.agent.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.core.llm.share.ImportedModelConfig
import com.apex.agent.core.llm.share.ModelConfigFormat
import com.apex.agent.core.llm.share.ModelConfigImportException
import com.apex.agent.core.llm.share.ModelConfigImporter
import com.apex.agent.core.llm.share.ModelConfigText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 模型配置导入弹窗（用户需求：模型配置支持直接粘贴 Python / JSON 等语言导入）。
 *
 * 交互流：粘贴文本 → 防抖 300ms 实时解析（格式识别 + 摘要预览 / 错误即时反馈）
 * → 「导入」落盘 → 完成态展示导入结果（服务商 + 模型数），关闭后由调用方
 * 自动选中新导入的首个档案。
 *
 * UI 与解析分层：本弹窗只负责采集与反馈，[ModelConfigImporter]（纯 JVM 可单测）
 * 负责识别，[SettingsViewModel.importModelConfig] 负责落盘 —— 弹窗里的实时
 * 预览与最终导入走同一份识别代码，预览所见即导入所得。
 *
 * 对话框样式对齐 ModelSetupCard.AvailableModelsDialog（AlertDialog + 文本域 +
 * 错误色提示），全部文案走 stringResource 双语资源。
 */
@Composable
internal fun ModelConfigImportDialog(
    viewModel: SettingsViewModel,
    onDismiss: () -> Unit,
    onImported: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    var selectedFormat by remember { mutableStateOf(ModelConfigFormat.AUTO) }

    // 实时解析结果（预览用，与导入走同一识别器）
    var preview by remember { mutableStateOf<ImportedModelConfig?>(null) }
    var detectedFormat by remember { mutableStateOf(ModelConfigFormat.AUTO) }
    var parseError by remember { mutableStateOf<String?>(null) }
    var nothingRecognized by remember { mutableStateOf(false) }

    var importing by remember { mutableStateOf(false) }
    var applyError by remember { mutableStateOf<String?>(null) }
    // (模型数, 服务商名) —— 非空即完成态
    var done by remember { mutableStateOf<Pair<Int, String>?>(null) }

    val scope = rememberCoroutineScope()

    // 防抖 300ms 实时解析：停顿后才解析，避免逐键全量 parse 大文本。
    // 完成态 / 清空文本时不解析（重置预览即可）。
    LaunchedEffect(text, selectedFormat) {
        preview = null
        parseError = null
        nothingRecognized = false
        if (text.isBlank()) return@LaunchedEffect
        delay(300)
        val parsed = withContext(Dispatchers.Default) {
            runCatching { ModelConfigImporter.import(text, selectedFormat) }
        }
        parsed.fold(
            onSuccess = { cfg ->
                preview = cfg
                // AUTO 档展示识别出的格式；显式选档时就是所选档
                detectedFormat = if (selectedFormat == ModelConfigFormat.AUTO) {
                    ModelConfigText.detect(text)
                } else {
                    selectedFormat
                }
            },
            onFailure = { e ->
                when (e) {
                    is ModelConfigImportException.NothingRecognized -> nothingRecognized = true
                    else -> parseError = e.message
                }
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.model_import_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val finished = done
                if (finished != null) {
                    Text(
                        stringResource(R.string.model_import_done, finished.first, finished.second),
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    Text(
                        stringResource(R.string.model_import_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )

                    // 格式选择：Auto（默认，自动识别）/ JSON / Python / YAML / TOML
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        ModelConfigFormat.values().forEach { f ->
                            FilterChip(
                                selected = selectedFormat == f,
                                onClick = { selectedFormat = f },
                                label = { Text(formatLabel(f)) }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        placeholder = { Text(stringResource(R.string.model_import_paste_hint)) },
                        minLines = 6,
                        maxLines = 12,
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // ── 实时预览：识别格式 + 摘要（服务商 · 端点 · 模型数）──
                    val pv = preview
                    if (pv != null) {
                        Text(
                            stringResource(R.string.model_import_detected, formatLabel(detectedFormat)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        val summaryTarget = buildString {
                            append(pv.providerName)
                            if (pv.baseUrl.isNotBlank()) append(" · ").append(pv.baseUrl)
                        }
                        Text(
                            stringResource(R.string.model_import_summary, pv.models.size, summaryTarget),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    parseError?.let {
                        Text(
                            stringResource(R.string.model_import_parse_error, it),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (nothingRecognized) {
                        Text(
                            stringResource(R.string.model_import_nothing),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    applyError?.let {
                        Text(
                            stringResource(R.string.model_import_apply_error, it),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (done == null) {
                TextButton(
                    enabled = preview != null && !importing,
                    onClick = {
                        importing = true
                        applyError = null
                        scope.launch {
                            viewModel.importModelConfig(text, selectedFormat).fold(
                                onSuccess = { summary ->
                                    done = summary.count to summary.providerName
                                    // 导入成功即切换到新档案（弹窗停在完成态供确认）
                                    onImported(summary.firstProfileId)
                                },
                                onFailure = { applyError = it.message }
                            )
                            importing = false
                        }
                    }
                ) {
                    if (importing) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(stringResource(R.string.model_import_button))
                }
            }
        },
        dismissButton = {
            // 完成态换用「关闭」；导入中途取消走 Cancel
            TextButton(onClick = onDismiss) {
                Text(
                    stringResource(
                        if (done != null) R.string.model_close else R.string.model_import_cancel
                    )
                )
            }
        }
    )
}

/** 格式标签（Auto / JSON / Python / YAML / TOML —— 双语同形，无需资源键）。 */
private fun formatLabel(format: ModelConfigFormat): String = when (format) {
    ModelConfigFormat.AUTO -> "Auto"
    ModelConfigFormat.JSON -> "JSON"
    ModelConfigFormat.PYTHON -> "Python"
    ModelConfigFormat.YAML -> "YAML"
    ModelConfigFormat.TOML -> "TOML"
}
