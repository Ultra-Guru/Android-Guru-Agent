package com.apex.agent.ui.screen.code

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.platform.code.ws.CodeWorkspace
import com.apex.agent.platform.code.ws.CodeWorkspaceManager

/**
 * # Code Workspace Selector — 输入栏内工作区选择器（2026-10 用户需求）
 *
 * 三条需求一次落地：
 * 1. **归位输入栏**：选择器从顶部 WorkspaceBar 左侧迁入输入栏工具行（顶栏
 *    左上角选择器移除，顶栏只留逻辑切换/文件浏览/长任务/新会话）；
 * 2. **真实文件夹**：「Select Folder…」走 SAF `OpenDocumentTree`，用户实际
 *    选一个设备目录，经 [treeUriToHostPath] 解码成宿主路径 →
 *    [CodeWorkspaceManager.importFrom]（不复制文件、同目录幂等复用）；
 * 3. **默认整个系统**：`system` 工作区（根 = /）恒列首项 —— 不选文件夹时
 *    的默认工作区即全系统。
 *
 * 触发胶囊与其他工具行按钮同规格（28dp 无边框圆角胶囊，13dp 图标）；
 * 下拉为系统感知宽度（窄屏自动收缩，永不溢出屏幕）。
 */
@Composable
internal fun CodeWorkspaceSelectorChip(
    active: CodeWorkspace?,
    workspaces: List<CodeWorkspace>,
    onSelect: (String) -> Unit,
    onCreate: (String) -> Unit,
    onImportFolder: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var menuOpen by remember { mutableStateOf(false) }
    var showNewDialog by rememberSaveable { mutableStateOf(false) }

    // SAF 文件夹选择（真实工作目录）：解码成宿主路径后走 importFrom；
    // 解码失败（非 externalstorage provider）→ 空路径信号 → VM 错误条
    val context = LocalContext.current
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            val path = treeUriToHostPath(context, uri)
            onImportFolder(path ?: "")
        }
    }

    Box(modifier = modifier) {
        // ── 触发胶囊（28dp 无边框；对齐工具行其他按钮规格）──
        Surface(
            onClick = { menuOpen = true },
            shape = RoundedCornerShape(50),
            color = if (active == null || active.workspaceId == CodeWorkspaceManager.SYSTEM_ID) {
                MaterialTheme.colorScheme.surfaceContainerHigh
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
            contentColor = if (active == null || active.workspaceId == CodeWorkspaceManager.SYSTEM_ID) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSecondaryContainer
            },
            modifier = Modifier.heightIn(min = 28.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Folder,
                    contentDescription = stringResource(R.string.code_switch_workspace),
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = active?.let { workspaceDisplayName(it) }
                        ?: stringResource(R.string.code_no_workspace),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp)
                )
            }
        }

        // ── 下拉（系统感知宽度：窄屏收缩，永不溢出）──
        val maxMenuWidth = LocalConfiguration.current.screenWidthDp.dp - 24.dp
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            modifier = Modifier.widthIn(min = 260.dp, max = maxMenuWidth)
        ) {
            // 1. 工作区清单（system 恒首项 = 默认整个系统）
            workspaces.sortedByDescending { it.workspaceId == CodeWorkspaceManager.SYSTEM_ID }
                .forEach { ws ->
                    val deletable = ws.workspaceId != CodeWorkspaceManager.SYSTEM_ID &&
                        ws.workspaceId != "default" &&
                        ws.workspaceId != active?.workspaceId
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    workspaceDisplayName(ws),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (ws.workspaceId == active?.workspaceId) FontWeight.Bold else FontWeight.Normal
                                )
                                ws.detectedEnvironment?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        },
                        leadingIcon = {
                            if (ws.workspaceId == CodeWorkspaceManager.SYSTEM_ID) {
                                Icon(
                                    Icons.Outlined.Storage,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            } else if (ws.workspaceId == active?.workspaceId) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            } else {
                                Spacer(modifier = Modifier.size(16.dp))
                            }
                        },
                        trailingIcon = if (deletable) {
                            {
                                IconButton(onClick = { onDelete(ws.workspaceId) }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = stringResource(R.string.code_delete_workspace),
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        } else null,
                        onClick = {
                            menuOpen = false
                            onSelect(ws.workspaceId)
                        }
                    )
                }

            // 2. 真实文件夹选择（SAF）—— 本选择器的主入口
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(R.string.code_workspace_pick_folder),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Outlined.FolderOpen,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                },
                onClick = {
                    menuOpen = false
                    folderPicker.launch(null)
                }
            )

            // 3. 空白命名工作区（应用内沙箱目录；旧行为保留）
            DropdownMenuItem(
                text = { Text(stringResource(R.string.code_new_workspace)) },
                leadingIcon = {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                },
                onClick = {
                    menuOpen = false
                    showNewDialog = true
                }
            )
        }
    }

    if (showNewDialog) {
        NewWorkspaceDialog(
            onConfirm = { name ->
                onCreate(name)
                showNewDialog = false
            },
            onDismiss = { showNewDialog = false }
        )
    }
}

/**
 * SAF 树 URI → 宿主文件路径（尽力解码）。
 *
 * 仅支持 externalstorage provider（`primary:Path` → /storage/emulated/0/Path、
 * `XXXX-YYYY:Path` → /storage/XXXX-YYYY/Path —— 卷根与 [Environment] 对齐）；
 * 其余 provider（云盘/下载集合等）无法映射为 File 路径 → null（调用方出
 * 「无法导入」错误条）。app 持有 MANAGE_EXTERNAL_STORAGE，File 通道直通。
 */
internal fun treeUriToHostPath(context: Context, uri: Uri): String? {
    if (uri.scheme != "content") return null
    val authority = uri.authority ?: return null
    if (authority != "com.android.externalstorage.documents") return null
    val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
    val sep = docId.indexOf(':')
    if (sep <= 0 || sep == docId.lastIndex) return null
    val volume = docId.substring(0, sep)
    val path = docId.substring(sep + 1)
    val volumeRoot = when (volume) {
        "primary" -> runCatching {
            android.os.Environment.getExternalStorageDirectory().canonicalPath
        }.getOrDefault("/storage/emulated/0")
        else -> "/storage/$volume"
    }
    return if (path.isBlank()) volumeRoot else "$volumeRoot/$path"
}

/** 展示名：system 工作区本地化（「整个系统」/「Whole System」），其余用存储名。 */
@Composable
private fun workspaceDisplayName(ws: CodeWorkspace): String =
    if (ws.workspaceId == CodeWorkspaceManager.SYSTEM_ID) {
        stringResource(R.string.code_workspace_whole_system)
    } else {
        ws.name
    }

/** 空白命名工作区对话框（从 CodeScreen 迁入；逻辑不变）。 */
@Composable
private fun NewWorkspaceDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    // rememberSaveable：旋转/重建后已输入的工作区名不丢（String 可入 Bundle）。
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.code_new_workspace_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text(stringResource(R.string.code_workspace_name_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name.trim()) },
                enabled = name.isNotBlank()
            ) { Text(stringResource(R.string.code_create)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.code_cancel)) }
        }
    )
}
