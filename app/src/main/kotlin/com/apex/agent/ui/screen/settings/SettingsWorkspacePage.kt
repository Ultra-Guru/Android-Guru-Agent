package com.apex.agent.ui.screen.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.apex.agent.R
import com.apex.agent.ui.screen.onboarding.WorkspaceScopes
import com.apex.agent.ui.screen.onboarding.treeUriDisplayName

/**
 * 「Agent 配置 → 工作区」叶子：引导页选定的工作区范围此后无任何查看/修改
 * 入口（Onboarding 是唯一写入方）—— 本页补齐事后复查与改选：
 *  - 范围档位（全部公共存储 / 指定文件夹）下拉；
 *  - 指定文件夹时展示文件夹名 +「更换文件夹」（系统 SAF 目录选择器，
 *    takePersistableUriPermission 持久化授权，与引导页同款流程）；
 *  - 「恢复为全部公共存储」一键回退。
 *
 * 持久化走 AgentSettings 三字段（workspaceScope / workspaceFolderUri /
 * workspaceFolderName），与引导页共用同一写入通道（SettingsRepository
 * 单一可信源），无新存储面。
 */
@Composable
internal fun WorkspaceSection(agent: AgentSettings, onAgent: (AgentSettings) -> Unit) {
    val context = LocalContext.current

    val treeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            // SAF 持久化授权：读 + 写都要（与引导页同款；个别 provider 不带
            // persistable 标志时 runCatching 吞掉，URI 字符串仍落盘）。
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            val name = treeUriDisplayName(uri)
            onAgent(
                agent.copy(
                    workspaceScope = WorkspaceScopes.FOLDER,
                    workspaceFolderUri = uri.toString(),
                    workspaceFolderName = name
                )
            )
        }
    }

    val scopeOptions = listOf(
        WorkspaceScopes.ALL to stringResource(R.string.settings_workspace_scope_all),
        WorkspaceScopes.FOLDER to stringResource(R.string.settings_workspace_scope_folder),
    )

    SettingsLeafColumn {
        SectionCard(
            title = stringResource(R.string.settings_workspace_section),
            icon = Icons.Outlined.Folder
        ) {
            DropdownRow(
                label = stringResource(R.string.settings_workspace_scope),
                options = scopeOptions,
                selected = agent.workspaceScope,
                description = stringResource(R.string.settings_workspace_scope_desc)
            ) { scope ->
                if (scope == WorkspaceScopes.ALL) {
                    // 回退全部公共存储：清空文件夹指向（三字段同步，避免悬空半态）
                    onAgent(
                        agent.copy(
                            workspaceScope = WorkspaceScopes.ALL,
                            workspaceFolderUri = "",
                            workspaceFolderName = ""
                        )
                    )
                } else if (agent.workspaceFolderUri.isBlank()) {
                    // 切到「指定文件夹」但尚未选过：直接拉起 SAF 选择器
                    treeLauncher.launch(null)
                } else {
                    onAgent(agent.copy(workspaceScope = WorkspaceScopes.FOLDER))
                }
            }
            if (agent.workspaceScope == WorkspaceScopes.FOLDER) {
                Text(
                    text = if (agent.workspaceFolderName.isNotBlank())
                        stringResource(R.string.settings_workspace_current_folder, agent.workspaceFolderName)
                    else stringResource(R.string.settings_workspace_no_folder),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Button(
                    onClick = { treeLauncher.launch(null) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.settings_workspace_pick_folder)) }
                if (agent.workspaceFolderUri.isNotBlank()) {
                    OutlinedButton(
                        onClick = {
                            onAgent(
                                agent.copy(
                                    workspaceScope = WorkspaceScopes.ALL,
                                    workspaceFolderUri = "",
                                    workspaceFolderName = ""
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.settings_workspace_reset_all)) }
                }
            }
        }
    }
}
