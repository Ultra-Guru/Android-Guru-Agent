@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.apex.agent.ui.screen.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.apex.agent.R
import com.apex.agent.backup.BackupManager
import com.apex.agent.backup.ExportOptions
import com.apex.agent.backup.ImportPreview
import com.apex.agent.backup.ImportResult
import com.apex.agent.ui.glass.GlassButton
import com.apex.agent.ui.glass.GlassCard
import com.apex.agent.ui.glass.GlassIconButton
import com.apex.agent.ui.language.LanguageManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

// ─────────────────────────────────────────────────────────────────────────────
// 备份 / 恢复区（Task 5-c）—— 主控接线：设置页调用 BackupSection()（内部
// hiltViewModel 自持状态，无需传参）。
//
// 流程：导出（范围开关 → Vault 口令×2 → CreateDocument SAF → 写文件）；
// 导入（OpenDocument SAF → 只读预览 → 确认对话框[范围开关+Vault 口令] → 执行）。
// 结果以 inline 反馈行展示（成功绿 / 失败红），message 一次性、可关闭。
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 备份区 ViewModel —— 全部 IO 经 Dispatchers.IO；[BackupUiState.message] 为
 * 一次性提示，消费（[consumeMessage]）后清空。
 */
@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backupManager: BackupManager,
    private val languageManager: LanguageManager,
) : ViewModel() {

    data class BackupUiState(
        val exporting: Boolean = false,
        val importing: Boolean = false,
        /** 一次性提示（导出 / 导入结果），消费后清空。 */
        val message: String? = null,
        val isError: Boolean = false,
        /** 待确认导入的备份全文（已过 parsePreview，未触碰任何本机数据）。 */
        val pendingImportJson: String? = null,
        val pendingPreview: ImportPreview? = null,
    )

    private val _uiState = MutableStateFlow(BackupUiState())
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()

    /** 消费一次性提示（反馈行关闭按钮）。 */
    fun consumeMessage() = _uiState.update { it.copy(message = null, isError = false) }

    /** 放弃待确认的导入（对话框取消 / 关闭）。 */
    fun cancelImport() = _uiState.update { it.copy(pendingImportJson = null, pendingPreview = null) }

    /** SAF CreateDocument 回选后执行导出。口令用毕即清零（ExportOptions 契约）。 */
    fun exportTo(uri: Uri, includeVault: Boolean, passphrase: String) {
        if (_uiState.value.exporting) return
        _uiState.update { it.copy(exporting = true, message = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val pass = passphrase.takeIf { it.isNotEmpty() }?.toCharArray()
            try {
                val result = backupManager.writeBundleToUri(uri, ExportOptions(includeVault, pass))
                _uiState.update { state ->
                    val ex = result.exceptionOrNull()
                    state.copy(
                        exporting = false,
                        isError = result.isFailure,
                        message = when (ex) {
                            null -> languageManager.getString(R.string.backup_export_success)
                            is IllegalArgumentException ->
                                languageManager.getString(R.string.backup_passphrase_empty)
                            else -> languageManager.getString(
                                R.string.backup_export_failed_fmt, ex.message ?: ex.javaClass.simpleName
                            )
                        },
                    )
                }
            } finally {
                pass?.fill('\u0000')
            }
        }
    }

    /** SAF OpenDocument 回选后：读文件 → 只读预览 → 置待确认态（不执行恢复）。 */
    fun pickFileResult(uri: Uri) {
        if (_uiState.value.importing) return
        _uiState.update { it.copy(importing = true, message = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val text = backupManager.readBundleFromUri(uri)
            val preview = text?.let { backupManager.parsePreview(it) }
            _uiState.update { state ->
                when {
                    text == null -> state.copy(
                        importing = false, isError = true,
                        message = languageManager.getString(R.string.backup_import_read_failed),
                    )
                    preview == null -> state.copy(
                        importing = false, isError = true,
                        message = languageManager.getString(R.string.backup_import_invalid_file),
                    )
                    else -> state.copy(importing = false, pendingImportJson = text, pendingPreview = preview)
                }
            }
        }
    }

    /** 确认对话框「恢复」：按勾选范围执行导入并回填结果文案（含错误码映射）。 */
    fun confirmImport(restoreSettings: Boolean, restoreVault: Boolean, passphrase: String) {
        val jsonText = _uiState.value.pendingImportJson ?: return
        if (_uiState.value.importing) return
        _uiState.update { it.copy(importing = true, message = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val pass = passphrase.takeIf { it.isNotEmpty() }?.toCharArray()
            try {
                val result = backupManager.import(jsonText, restoreSettings, restoreVault, pass)
                _uiState.update {
                    it.copy(
                        importing = false,
                        message = buildImportMessage(result),
                        isError = result.errors.isNotEmpty(),
                        pendingImportJson = null,
                        pendingPreview = null,
                    )
                }
            } finally {
                pass?.fill('\u0000')
            }
        }
    }

    /** 导入结果 → 多行本地化文案；设置恢复成功附加「重启后完全生效」提示。 */
    private fun buildImportMessage(result: ImportResult): String {
        val lines = mutableListOf<String>()
        if (result.settingsImported) {
            lines += languageManager.getString(R.string.backup_result_settings_ok)
            lines += languageManager.getString(R.string.backup_result_settings_restart)
        }
        if (result.vaultImported) lines += languageManager.getString(R.string.backup_result_vault_ok)
        if (result.errors.isNotEmpty()) {
            lines += languageManager.getString(R.string.backup_result_errors_prefix)
            result.errors.forEach { code -> lines += "• " + localizeError(code) }
        }
        return lines.joinToString("\n")
    }

    /** 错误码（BackupManager.ERR_*）→ 本地化文案；未知码原样展示（可诊断）。 */
    private fun localizeError(code: String): String {
        val name = code.substringBefore(':')
        val context = code.substringAfter(':', "")
        return when (name) {
            BackupManager.ERR_VERSION_TOO_NEW -> languageManager.getString(
                R.string.backup_result_unsupported_version_fmt, _uiState.value.pendingPreview?.formatVersion ?: 1
            )
            BackupManager.ERR_BUNDLE_UNREADABLE -> languageManager.getString(R.string.backup_import_invalid_file)
            BackupManager.ERR_VAULT_PASSPHRASE_MISSING -> languageManager.getString(R.string.backup_result_vault_passphrase_missing)
            BackupManager.ERR_VAULT_DECRYPT_FAILED -> languageManager.getString(R.string.backup_result_vault_decrypt_failed)
            BackupManager.ERR_VAULT_WRITE_FAILED -> languageManager.getString(R.string.backup_result_vault_write_failed)
            BackupManager.ERR_PROVIDERS_REDACT_FAILED -> languageManager.getString(R.string.backup_result_providers_redact_failed)
            BackupManager.ERR_SETTINGS_WRITE_FAILED -> languageManager.getString(R.string.backup_result_settings_write_failed_fmt, context)
            else -> code
        }
    }
}

/**
 * 备份 / 恢复区 UI —— 说明卡 + 导出卡（范围开关 / 口令输入）+ 导入卡（选文件）
 * + inline 反馈行 + 导入确认对话框。自持 [BackupViewModel]。
 */
@Composable
fun BackupSection(viewModel: BackupViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // 导出侧本地态：开关 rememberSaveable 留存；口令用 remember（绝不落盘）
    var includeVault by rememberSaveable { mutableStateOf(false) }
    var passphrase by remember { mutableStateOf("") }
    var passphraseConfirm by remember { mutableStateOf("") }
    // 导入确认对话框本地态（对话框重建即重置，默认全开）
    var restoreSettings by remember { mutableStateOf(true) }
    var restoreVault by remember { mutableStateOf(true) }
    var importPassphrase by remember { mutableStateOf("") }

    val busy = state.exporting || state.importing
    // Vault 开启时：口令非空且两次一致才允许导出（不一致即时校验）
    val exportEnabled = !busy && (!includeVault || (passphrase.isNotBlank() && passphrase == passphraseConfirm))

    // 建议文件名（进入本区生成一次，分钟级精度足够）
    val suggestedName = remember {
        "apex-backup-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".json"
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? -> uri?.let { viewModel.exportTo(it, includeVault, passphrase) } }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { viewModel.pickFileResult(it) } }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // ═══ 说明卡：包含什么 / 永不包含什么（密钥安全声明）═══
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.SettingsBackupRestore, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.backup_section_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.backup_section_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.backup_security_included), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.backup_security_never), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.backup_vault_envelope_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // ═══ 导出区：范围开关 +（Vault 时）口令输入 + 导出按钮 ═══
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.backup_export_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            BackupSwitchRow(
                title = stringResource(R.string.backup_include_vault),
                description = stringResource(R.string.backup_include_vault_desc),
                checked = includeVault, onCheckedChange = { includeVault = it },
            )
            if (includeVault) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = passphrase, onValueChange = { passphrase = it },
                    label = { Text(stringResource(R.string.backup_passphrase_label)) },
                    placeholder = { Text(stringResource(R.string.backup_passphrase_hint)) },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                val mismatch = passphraseConfirm.isNotEmpty() && passphraseConfirm != passphrase
                OutlinedTextField(
                    value = passphraseConfirm, onValueChange = { passphraseConfirm = it },
                    label = { Text(stringResource(R.string.backup_passphrase_confirm_label)) },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true, isError = mismatch,
                    supportingText = { if (mismatch) Text(stringResource(R.string.backup_passphrase_mismatch)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.backup_passphrase_strength_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassButton(
                    text = stringResource(if (state.exporting) R.string.backup_exporting else R.string.backup_export_button),
                    onClick = { exportLauncher.launch(suggestedName) },
                    leadingIcon = Icons.Outlined.SettingsBackupRestore, enabled = exportEnabled,
                )
                if (state.exporting) {
                    Spacer(Modifier.width(10.dp))
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
        }

        // ═══ 导入区：选文件 → 预览确认对话框 ═══
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.backup_import_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.backup_import_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassButton(
                    text = stringResource(if (state.importing) R.string.backup_importing else R.string.backup_import_button),
                    onClick = { importLauncher.launch(arrayOf("application/json")) },
                    leadingIcon = Icons.Outlined.Restore, enabled = !busy,
                )
                if (state.importing) {
                    Spacer(Modifier.width(10.dp))
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
        }

        // ═══ inline 反馈行（成功绿 / 失败红；一次性，可关闭）═══
        state.message?.let { msg ->
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        msg, style = MaterialTheme.typography.bodySmall,
                        color = if (state.isError) MaterialTheme.colorScheme.error else BackupSuccessGreen,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    GlassIconButton(
                        icon = Icons.Default.Close,
                        contentDescription = stringResource(R.string.backup_message_dismiss),
                        onClick = viewModel::consumeMessage, size = 32.dp, iconSize = 16.dp,
                    )
                }
            }
        }
    }

    // ═══ 导入确认对话框（预览 + 恢复范围 + Vault 口令）═══
    val preview = state.pendingPreview
    if (preview != null && state.pendingImportJson != null) {
        BackupImportConfirmDialog(
            preview = preview,
            importing = state.importing,
            restoreSettings = restoreSettings, onRestoreSettings = { restoreSettings = it },
            restoreVault = restoreVault, onRestoreVault = { restoreVault = it },
            passphrase = importPassphrase, onPassphraseChange = { importPassphrase = it },
            onConfirm = {
                // 文件不含 Vault 时强制不传 vault 恢复（restoreVault 勾选态对空文件无意义）
                viewModel.confirmImport(restoreSettings, restoreVault && preview.vaultIncluded, importPassphrase)
                importPassphrase = ""
            },
            onDismiss = viewModel::cancelImport,
        )
    }
}

/** 导入确认对话框：只读预览（元信息）+ 恢复范围开关 + Vault 口令输入。 */
@Composable
private fun BackupImportConfirmDialog(
    preview: ImportPreview,
    importing: Boolean,
    restoreSettings: Boolean,
    restoreVault: Boolean,
    passphrase: String,
    onRestoreSettings: (Boolean) -> Unit,
    onRestoreVault: (Boolean) -> Unit,
    onPassphraseChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    // 文件不含 Vault 时：口令输入不出现，vault 范围开关不显示
    val anyScope = restoreSettings || restoreVault
    val vaultPassOk = !preview.vaultIncluded || !restoreVault || passphrase.isNotBlank()
    val createdAtText = remember(preview.createdAt) {
        SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(preview.createdAt))
    }
    AlertDialog(
        onDismissRequest = { if (!importing) onDismiss() },
        title = { Text(stringResource(R.string.backup_preview_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PreviewRow(stringResource(R.string.backup_preview_version_fmt, preview.formatVersion))
                PreviewRow(stringResource(R.string.backup_preview_created_fmt, createdAtText))
                PreviewRow(stringResource(R.string.backup_preview_from_fmt, preview.appVersionName))
                PreviewRow(stringResource(if (preview.vaultIncluded) R.string.backup_preview_vault_included else R.string.backup_preview_vault_absent))
                PreviewRow(stringResource(if (preview.settingsIncluded) R.string.backup_preview_settings_included else R.string.backup_preview_settings_absent))
                Spacer(Modifier.height(6.dp))
                BackupSwitchRow(
                    title = stringResource(R.string.backup_restore_settings),
                    description = null, checked = restoreSettings, onCheckedChange = onRestoreSettings,
                )
                if (preview.vaultIncluded) {
                    BackupSwitchRow(
                        title = stringResource(R.string.backup_restore_vault),
                        description = null, checked = restoreVault, onCheckedChange = onRestoreVault,
                    )
                    if (restoreVault) {
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = passphrase, onValueChange = onPassphraseChange,
                            label = { Text(stringResource(R.string.backup_import_passphrase_label)) },
                            placeholder = { Text(stringResource(R.string.backup_import_passphrase_hint)) },
                            visualTransformation = PasswordVisualTransformation(), singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !importing && anyScope && vaultPassOk) {
                Text(stringResource(R.string.backup_dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !importing) {
                Text(stringResource(R.string.backup_dialog_cancel))
            }
        },
    )
}

/** 预览元信息行（对话框内统一样式）。 */
@Composable
private fun PreviewRow(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall)
}

/** 标签 + 描述 + Switch 的设置行（与设置中心其它区一致的行结构）。 */
@Composable
private fun BackupSwitchRow(
    title: String,
    description: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (!description.isNullOrBlank()) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 成功反馈绿（Material Green 400：深浅色玻璃面上均可读；失败色走主题 error）。 */
private val BackupSuccessGreen = Color(0xFF66BB6A)
