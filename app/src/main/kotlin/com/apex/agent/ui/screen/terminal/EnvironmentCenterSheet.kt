@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.apex.agent.ui.screen.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apex.agent.R
import com.apex.agent.platform.terminal.ubuntu.lifecycle.UbuntuLifecycleCoordinator

/**
 * 环境中心 Sheet（T96 自旧版全量转移 + M3 化重写）。
 *
 * 三卡结构（语义与旧版一致）：
 *  1. Ubuntu rootfs 卡 —— 阶段徽章 / 磁盘占用 / 安装进度 / 能力清单 /
 *     失败根因 / 降级引导；按钮按阶段切换（解包/取消/收尾引导/修复/删除，
 *     删除带确认对话框）；
 *  2. Android 本地 Shell 卡（内置徽章）；
 *  3. 依赖安装卡 —— 镜像开关（TUNA，持久化 dep_use_mirror）、一键全装/
 *     仅 Android 组、逐项安装 + 等宽安装日志。
 */
@Composable
internal fun EnvironmentCenterSheet(
    viewModel: TerminalViewModel,
    onDismiss: () -> Unit
) {
    TerminalConsoleTheme {
        val lifecycle by viewModel.ubuntuLifecycleState.collectAsStateWithLifecycle()
        val progress by viewModel.ubuntuProgress.collectAsStateWithLifecycle(initialValue = null)
        val rootfsSize = viewModel.rootfsSize
        val installState by viewModel.depCenter.install.collectAsStateWithLifecycle()
        val useMirror by viewModel.depCenter.useMirror.collectAsStateWithLifecycle()

        ModalBottomSheet(onDismissRequest = onDismiss, containerColor = ConsoleTokens.background) {
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .height(560.dp)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        stringResource(R.string.term_env_center_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = ConsoleTokens.onSurface,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        stringResource(R.string.term_env_center_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = ConsoleTokens.onSurfaceVariant
                    )
                }
                item {
                    UbuntuCard(viewModel, lifecycle, progress, rootfsSize)
                }
                item {
                    AndroidShellCard()
                }
                item {
                    DepsCard(
                        viewModel = viewModel,
                        installState = installState,
                        useMirror = useMirror,
                        onUseMirror = { viewModel.depCenter.setUseMirror(it) }
                    )
                }
            }
        }
    }
}

// ═══════════════ Ubuntu rootfs 卡 ═══════════════

@Composable
private fun UbuntuCard(
    viewModel: TerminalViewModel,
    lifecycle: UbuntuLifecycleCoordinator.LifecycleState,
    progress: UbuntuLifecycleCoordinator.LifecycleProgress?,
    rootfsSize: Long?
) {
    val phase = lifecycle.phase
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(phase) {
        if (phase == UbuntuLifecycleCoordinator.Phase.READY) viewModel.refreshRootfsSize()
    }

    SectionCard(title = stringResource(R.string.term_ubuntu_card_title)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PhaseBadge(phase)
                Spacer(Modifier.size(8.dp))
                Text(
                    stringResource(R.string.term_ubuntu_card_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = ConsoleTokens.onSurfaceVariant
                )
            }
            rootfsSize?.let {
                Text(
                    stringResource(R.string.term_disk_usage, formatBytes(it)),
                    style = MaterialTheme.typography.bodySmall,
                    color = ConsoleTokens.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace
                )
            }
            when (phase) {
                UbuntuLifecycleCoordinator.Phase.INSTALLING -> {
                    LinearProgressIndicator(
                        progress = { (progress?.percent ?: 0) / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    progress?.let {
                        Text(
                            stringResource(
                                R.string.term_progress_bytes,
                                formatBytes(it.bytesTransferred),
                                formatBytes(it.bytesTotal ?: 0L)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = ConsoleTokens.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.term_unpacking),
                            style = MaterialTheme.typography.bodySmall,
                            color = ConsoleTokens.amber
                        )
                        TextButton(onClick = { viewModel.cancelInstallUbuntu() }) {
                            Text(stringResource(R.string.term_cancel))
                        }
                    }
                }
                UbuntuLifecycleCoordinator.Phase.READY -> {
                    lifecycle.capabilities?.takeIf { it.isNotEmpty() }?.let { list ->
                        Text(
                            stringResource(R.string.term_capabilities) + ": " +
                                list.joinToString(" · ") { c -> c.name + "(" + c.status + ")" },
                            style = MaterialTheme.typography.bodySmall,
                            color = ConsoleTokens.lineInfo,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    lifecycle.bootstrapNote?.let {
                        Text(
                            stringResource(R.string.term_bootstrap_note) + " " + it,
                            style = MaterialTheme.typography.bodySmall,
                            color = ConsoleTokens.amber
                        )
                    }
                }
                UbuntuLifecycleCoordinator.Phase.FAILED -> {
                    Text(
                        lifecycle.lastError?.take(300) ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = ConsoleTokens.lineError,
                        fontFamily = FontFamily.Monospace
                    )
                }
                else -> Unit
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (phase) {
                    UbuntuLifecycleCoordinator.Phase.NOT_INSTALLED ->
                        TextButton(onClick = { viewModel.installUbuntu() }) { Text(stringResource(R.string.term_unpack_built_in)) }
                    UbuntuLifecycleCoordinator.Phase.ROOTFS_READY ->
                        TextButton(onClick = { viewModel.installUbuntu() }) { Text(stringResource(R.string.term_finish_bootstrap)) }
                    UbuntuLifecycleCoordinator.Phase.BOOTSTRAPPING,
                    UbuntuLifecycleCoordinator.Phase.RECOVERING ->
                        Text(stringResource(R.string.term_bootstrapping), color = ConsoleTokens.amber)
                    UbuntuLifecycleCoordinator.Phase.READY -> {
                        TextButton(onClick = { viewModel.createSession(SessionRegistry.BACKEND_UBUNTU) }) {
                            Text(stringResource(R.string.term_new_ubuntu_session))
                        }
                        TextButton(onClick = { viewModel.installUbuntu() }) { Text(stringResource(R.string.term_repair)) }
                        TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.term_delete)) }
                    }
                    UbuntuLifecycleCoordinator.Phase.FAILED -> {
                        TextButton(onClick = { viewModel.installUbuntu() }) { Text(stringResource(R.string.term_retry_install)) }
                        TextButton(onClick = { viewModel.installUbuntu() }) { Text(stringResource(R.string.term_repair)) }
                        TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.term_delete)) }
                    }
                    else -> Unit
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.term_remove_ubuntu_title)) },
            text = { Text(stringResource(R.string.term_remove_ubuntu_text)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; viewModel.removeUbuntu() }) {
                    Text(stringResource(R.string.term_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.term_cancel)) }
            }
        )
    }
}

@Composable
private fun PhaseBadge(phase: UbuntuLifecycleCoordinator.Phase) {
    val (labelRes, color) = when (phase) {
        UbuntuLifecycleCoordinator.Phase.NOT_INSTALLED -> R.string.term_phase_not_installed to ConsoleTokens.onSurfaceVariant
        UbuntuLifecycleCoordinator.Phase.INSTALLING -> R.string.term_phase_installing to ConsoleTokens.amber
        UbuntuLifecycleCoordinator.Phase.ROOTFS_READY -> R.string.term_phase_rootfs_ready to ConsoleTokens.amber
        UbuntuLifecycleCoordinator.Phase.BOOTSTRAPPING -> R.string.term_phase_bootstrapping to ConsoleTokens.amber
        UbuntuLifecycleCoordinator.Phase.READY -> R.string.term_phase_ready to ConsoleTokens.lineExitOk
        UbuntuLifecycleCoordinator.Phase.RECOVERING -> R.string.term_phase_recovering to ConsoleTokens.amber
        UbuntuLifecycleCoordinator.Phase.FAILED -> R.string.term_phase_failed to ConsoleTokens.lineError
    }
    Text(
        stringResource(labelRes),
        style = MaterialTheme.typography.labelMedium,
        color = color,
        fontFamily = FontFamily.Monospace
    )
}

// ═══════════════ Android Shell 卡 ═══════════════

@Composable
private fun AndroidShellCard() {
    SectionCard(title = stringResource(R.string.term_android_shell_title)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.term_android_shell_desc),
                style = MaterialTheme.typography.bodySmall,
                color = ConsoleTokens.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                stringResource(R.string.term_builtin),
                style = MaterialTheme.typography.labelMedium,
                color = ConsoleTokens.amber,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

// ═══════════════ 依赖安装卡 ═══════════════

@Composable
private fun DepsCard(
    viewModel: TerminalViewModel,
    installState: TerminalDepCenter.InstallState,
    useMirror: Boolean,
    onUseMirror: (Boolean) -> Unit
) {
    val depItems = viewModel.depCenter.depItems
    SectionCard(title = stringResource(R.string.term_deps_title)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ToggleRow(
                title = stringResource(R.string.term_use_mirror),
                desc = stringResource(R.string.term_mirror_desc),
                checked = useMirror,
                onToggle = onUseMirror
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { viewModel.depCenter.installAll() }) {
                    Text(stringResource(R.string.term_install_all))
                }
                TextButton(onClick = { viewModel.depCenter.installAndroidOnly() }) {
                    Text(stringResource(R.string.term_android_deps))
                }
            }
            depItems.forEach { item ->
                val installing = installState.runningId == item.id
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        item.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = ConsoleTokens.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    if (installing) {
                        Text(
                            stringResource(R.string.term_installing),
                            style = MaterialTheme.typography.labelMedium,
                            color = ConsoleTokens.amber
                        )
                    } else {
                        TextButton(onClick = { viewModel.depCenter.installDep(item) }) {
                            Text(stringResource(R.string.term_install))
                        }
                    }
                }
            }
            if (installState.log.isNotBlank()) {
                Text(
                    installState.log.takeLast(4000),
                    style = MaterialTheme.typography.bodySmall,
                    color = ConsoleTokens.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

// ═══════════════ 共享小部件（Settings/History Sheet 复用）═══════════════

@Composable
internal fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = ConsoleTokens.surfaceContainer)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = ConsoleTokens.onSurface,
                fontFamily = FontFamily.Monospace
            )
            content()
        }
    }
}

@Composable
internal fun ToggleRow(
    title: String,
    desc: String?,
    checked: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = ConsoleTokens.onSurface)
            desc?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = ConsoleTokens.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onToggle)
    }
}
