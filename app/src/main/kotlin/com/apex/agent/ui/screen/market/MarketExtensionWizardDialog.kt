package com.apex.agent.ui.screen.market

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.apex.agent.R

/**
 * # 统一安装向导（市场完全重构 PR-C）
 *
 * 粘贴任意 spec（npm / claude: / dsh: / mcp: / skill: / github: / apk: / URL）
 * → resolve → inspect 预览（装什么/哪个域/权限/脚本）→ 确认安装 —— 与 agent
 * 侧 ExtensionResolve/Inspect/Install 三工具**同一闸门实例**（hash 门/确认门
 * 如实传递）。五页签旧安装流保留双轨，本向导是增量入口。
 */
@Composable
internal fun ExtensionWizardDialog(
    controller: com.apex.agent.marketplace.ExtensionWizardController
) {
    val state by controller.state.collectAsState()
    if (!state.visible) return

    var confirmChecked by remember(state.manifestHash) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { controller.hide() },
        title = { Text(stringResource(R.string.market_extension_wizard_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.spec,
                    onValueChange = { controller.updateSpec(it) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.market_extension_wizard_hint), style = MaterialTheme.typography.bodySmall) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
                Text(
                    stringResource(R.string.market_extension_wizard_grammar),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                state.preview?.let { preview ->
                    Text(
                        preview,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(8.dp)
                    )
                }
                if (state.requiresConfirm && state.manifestHash != null) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(
                            checked = confirmChecked,
                            onCheckedChange = { confirmChecked = it }
                        )
                        Text(
                            stringResource(R.string.market_extension_wizard_confirm),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                state.result?.let { result ->
                    Text(
                        result,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (result.startsWith("✅")) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 第一步：认路 + 看清（spec 变更后旧预览作废，重按有效）
                if (state.manifestHash == null) {
                    Button(
                        onClick = { controller.resolveAndInspect() },
                        enabled = state.spec.isNotBlank() && !state.busy
                    ) {
                        Text(stringResource(R.string.market_extension_wizard_preview))
                    }
                } else {
                    // 第二步：装（敏感扩展需勾选确认）
                    Button(
                        onClick = { controller.install(confirmed = confirmChecked || !state.requiresConfirm) },
                        enabled = !state.busy && !(state.requiresConfirm && !confirmChecked)
                    ) {
                        Text(stringResource(R.string.market_action_install))
                    }
                }
            }
        },
        dismissButton = {
            OutlinedButton(onClick = { controller.hide() }) {
                Text(stringResource(R.string.market_action_close))
            }
        }
    )
}
