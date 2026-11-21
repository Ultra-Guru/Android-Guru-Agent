package com.apex.agent.ui.screen.onboarding

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.apex.agent.R

/**
 * 新手引导第 4 页 —— 配置工作区（引导序号 8，可跳过）。
 *
 * 两个互斥选项：
 *  - 操控所有文件（默认）：Agent 的文件操作范围 = 整个公共存储，
 *    依赖上一页的「所有文件访问」权限；
 *  - 指定工作区文件夹：系统 SAF 目录选择器（ACTION_OPEN_DOCUMENT_TREE）
 *    选定一个文件夹作为 Agent 的默认工作区，选定即经
 *    takePersistableUriPermission 持久化授权（chaos-crash-audit C-09 的
 *    教训：不持久化的 content URI 重启即失效）。
 *
 * 选择即时经 [onWorkspaceSelected] 回调持久化（即使引导中途被杀进程，
 * 选择也不丢）；跳过 = 维持默认「操控所有」。
 */
@Composable
internal fun OnboardingWorkspacePage(
    selectedScope: String,
    selectedFolderName: String,
    onWorkspaceSelected: (scope: String, folderUri: String, folderName: String) -> Unit
) {
    val context = LocalContext.current
    var mode by remember {
        mutableStateOf(
            if (selectedScope == WorkspaceScopes.FOLDER && selectedFolderName.isNotBlank()) {
                WorkspaceScopes.FOLDER
            } else WorkspaceScopes.ALL
        )
    }
    var folderName by remember { mutableStateOf(selectedFolderName) }

    val treeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            // SAF 持久化授权：读 + 写都要，缺写权限的工作区是只读摆设。
            // 极少数 provider 不带 persistable 标志 —— runCatching 吞掉，
            // URI 字符串仍记录（主存储卷的目录选择正常都带持久化授权）。
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            val name = treeUriDisplayName(uri)
            folderName = name
            mode = WorkspaceScopes.FOLDER
            onWorkspaceSelected(WorkspaceScopes.FOLDER, uri.toString(), name)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(12.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
            ) {
                Text(
                    "8",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
            Text(
                stringResource(R.string.onboarding_ws_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.onboarding_ws_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))

        // ── 选项 A：操控所有文件（默认）──
        WorkspaceOptionCard(
            icon = Icons.Default.Public,
            title = stringResource(R.string.onboarding_ws_all_title),
            description = stringResource(R.string.onboarding_ws_all_desc),
            selected = mode == WorkspaceScopes.ALL,
            onClick = {
                mode = WorkspaceScopes.ALL
                onWorkspaceSelected(WorkspaceScopes.ALL, "", "")
            }
        )
        Spacer(Modifier.height(12.dp))

        // ── 选项 B：指定工作区文件夹 ──
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (mode == WorkspaceScopes.FOLDER) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
            border = if (mode == WorkspaceScopes.FOLDER) {
                BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
            } else null,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.FolderOpen, null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.onboarding_ws_folder_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            stringResource(R.string.onboarding_ws_folder_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (mode == WorkspaceScopes.FOLDER) {
                        Icon(
                            Icons.Default.CheckCircle, null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                // 已选定文件夹：显示当前工作区 + 「重新选择」
                if (mode == WorkspaceScopes.FOLDER && folderName.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                    ) {
                        Text(
                            stringResource(R.string.onboarding_ws_current_folder, folderName),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { treeLauncher.launch(null) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (mode == WorkspaceScopes.FOLDER && folderName.isNotBlank()) {
                            stringResource(R.string.onboarding_ws_folder_repick)
                        } else {
                            stringResource(R.string.onboarding_ws_folder_pick)
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(R.string.onboarding_ws_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))
    }
}

/** 工作区范围取值（持久化在 AgentSettings.workspaceScope）。 */
object WorkspaceScopes {
    /** 操控所有公共存储（默认）。 */
    const val ALL = "all"

    /** 指定 SAF 文件夹为工作区。 */
    const val FOLDER = "folder"
}

/** 选项 A 卡片：单行形态（无内嵌按钮），整卡可点选。 */
@Composable
private fun WorkspaceOptionCard(
    icon: ImageVector,
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        border = if (selected) {
            BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
        } else null,
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (selected) {
                Icon(
                    Icons.Default.CheckCircle, null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

/**
 * SAF tree URI → 人类可读文件夹名。
 *
 * tree URI 的 documentId 形如 primary:Download/MyFolder（卷:路径），
 * 取路径最后一段做显示名；无路径段的退回卷名。DocumentsContract 是
 * framework API（API 21+，minSdk 26 直接可用），不必引入 documentfile 依赖。
 */
private fun treeUriDisplayName(uri: Uri): String {
    return runCatching {
        val docId = DocumentsContract.getTreeDocumentId(uri)
        val lastSegment = docId.substringAfterLast('/')
        if (lastSegment.isNotBlank() && lastSegment != docId) {
            lastSegment
        } else {
            docId.substringAfterLast(':').removeSuffix(":").ifBlank { docId }
        }
    }.getOrNull()
        ?: uri.lastPathSegment
        ?: uri.toString()
}
