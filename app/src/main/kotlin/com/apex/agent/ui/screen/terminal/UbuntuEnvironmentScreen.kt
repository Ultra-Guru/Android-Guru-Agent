@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.apex.agent.ui.screen.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apex.agent.R
import com.apex.agent.environment.DepGroup
import com.apex.agent.environment.DepItem
import com.apex.agent.platform.terminal.ubuntu.lifecycle.UbuntuLifecycleCoordinator
import kotlinx.coroutines.delay

/**
 * T97（Ubuntu 大重构）—— Ubuntu 开发环境安装页（唯一保留的 Ubuntu UI）。
 *
 * 决策背景：终端控制台（命令输入/多会话/历史）整体退役 —— Ubuntu 由 Agent
 * 全权掌控（Agent 侧经 TerminalUbuntuEnsure / TerminalExec / TerminalLinuxPackages
 * 等工具操作）。用户侧只剩本页：
 *
 *  1. Ubuntu rootfs 卡 —— 阶段徽章 / 磁盘占用 / 安装进度 / 能力清单 /
 *     失败根因；按钮按阶段切换（解包/取消/修复/删除，删除带确认对话框）；
 *  2. 开发环境安装卡 —— TUNA 镜像开关、一键全装/Android 组、**版本自选**
 *     （JDK 17/21/11/8 · Build-Tools · NDK · Gradle · Node · Go · Rust）、
 *     已装状态徽章（批量探测）、等宽安装日志。
 *
 * 视觉契约沿用旧终端域（GitHub Dark 恒暗令牌 + 等宽排版 —— ConsoleTokens）。
 */
@Composable
fun UbuntuEnvironmentScreen(
    viewModel: UbuntuEnvironmentViewModel,
    onBack: () -> Unit
) {
    TerminalConsoleTheme {
        val ubuntuState by viewModel.ubuntuLifecycleState.collectAsStateWithLifecycle()
        val notice by viewModel.notice.collectAsStateWithLifecycle()
        val installState by viewModel.depCenter.install.collectAsStateWithLifecycle()
        val statusState by viewModel.depCenter.status.collectAsStateWithLifecycle()

        // 安装进行中亮屏（替代旧字号/亮屏设置项 —— 长装机的实际需要）
        val view = LocalView.current
        val busy = ubuntuState.phase in BUSY_PHASES || installState.runningId != null
        LaunchedEffect(busy) { view.keepScreenOn = busy }

        // 通知自动消隐（INFO 5s / ERROR 8s）
        LaunchedEffect(notice) {
            val n = notice
            if (n != null) {
                delay(if (n.kind == UbuntuEnvironmentViewModel.TerminalNotice.Kind.INFO) 5_000 else 8_000)
                viewModel.consumeNotice()
            }
        }

        Scaffold(
            containerColor = ConsoleTokens.background,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            stringResource(R.string.term_ubuntu_env_title),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.titleLarge
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.term_back))
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.refreshDepStatuses() }) {
                            Icon(Icons.Filled.Refresh, stringResource(R.string.term_status_refresh))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = ConsoleTokens.background,
                        titleContentColor = ConsoleTokens.onSurface,
                        navigationIconContentColor = ConsoleTokens.onSurfaceVariant,
                        actionIconContentColor = ConsoleTokens.onSurfaceVariant
                    )
                )
            }
        ) { padding ->
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        stringResource(R.string.term_ubuntu_env_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = ConsoleTokens.onSurfaceVariant
                    )
                }
                item { NoticeBar(notice) }
                item { UbuntuCard(viewModel, ubuntuState) }
                item {
                    DepsCard(
                        viewModel = viewModel,
                        installState = installState,
                        statusState = statusState,
                        ubuntuReady = ubuntuState.phase == UbuntuLifecycleCoordinator.Phase.READY
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

/** 兼容入口（ApexRoot 路由零改动签名）。 */
@Composable
fun UbuntuEnvironmentScreen(onBack: () -> Unit) {
    val viewModel: UbuntuEnvironmentViewModel = hiltViewModel()
    UbuntuEnvironmentScreen(viewModel, onBack)
}

private val BUSY_PHASES = setOf(
    UbuntuLifecycleCoordinator.Phase.INSTALLING,
    UbuntuLifecycleCoordinator.Phase.BOOTSTRAPPING,
    UbuntuLifecycleCoordinator.Phase.RECOVERING
)

// ═══════════════ 通知条 ═══════════════

@Composable
private fun NoticeBar(notice: UbuntuEnvironmentViewModel.TerminalNotice?) {
    if (notice == null) return
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = ConsoleTokens.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            notice.text,
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            color = when (notice.kind) {
                UbuntuEnvironmentViewModel.TerminalNotice.Kind.ERROR -> ConsoleTokens.lineError
                UbuntuEnvironmentViewModel.TerminalNotice.Kind.INFO -> ConsoleTokens.onSurfaceVariant
            },
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ═══════════════ Ubuntu rootfs 卡 ═══════════════

@Composable
private fun UbuntuCard(
    viewModel: UbuntuEnvironmentViewModel,
    lifecycle: UbuntuLifecycleCoordinator.LifecycleState
) {
    val phase = lifecycle.phase
    val progress by viewModel.ubuntuProgress.collectAsStateWithLifecycle(initialValue = null)
    val rootfsSize = viewModel.rootfsSize
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
                UbuntuLifecycleCoordinator.Phase.BOOTSTRAPPING,
                UbuntuLifecycleCoordinator.Phase.RECOVERING -> {
                    LinearProgressIndicator(
                        progress = { (progress?.percent ?: 0) / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        stringResource(R.string.term_bootstrapping),
                        style = MaterialTheme.typography.bodySmall,
                        color = ConsoleTokens.amber,
                        fontFamily = FontFamily.Monospace
                    )
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
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                when (phase) {
                    UbuntuLifecycleCoordinator.Phase.NOT_INSTALLED ->
                        TextButton(onClick = { viewModel.installUbuntu() }) { Text(stringResource(R.string.term_unpack_built_in)) }
                    UbuntuLifecycleCoordinator.Phase.ROOTFS_READY ->
                        TextButton(onClick = { viewModel.installUbuntu() }) { Text(stringResource(R.string.term_finish_bootstrap)) }
                    UbuntuLifecycleCoordinator.Phase.BOOTSTRAPPING,
                    UbuntuLifecycleCoordinator.Phase.RECOVERING ->
                        Text(stringResource(R.string.term_bootstrapping), color = ConsoleTokens.amber)
                    UbuntuLifecycleCoordinator.Phase.READY -> {
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

// ═══════════════ 开发环境安装卡（版本自选）═══════════════

@Composable
private fun DepsCard(
    viewModel: UbuntuEnvironmentViewModel,
    installState: TerminalDepCenter.InstallState,
    statusState: TerminalDepCenter.StatusState,
    ubuntuReady: Boolean
) {
    val depItems = viewModel.depCenter.depItems
    val useMirror by viewModel.depCenter.useMirror.collectAsStateWithLifecycle()

    SectionCard(title = stringResource(R.string.term_deps_title)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.term_mirror_note),
                style = MaterialTheme.typography.bodySmall,
                color = ConsoleTokens.onSurfaceVariant
            )
            ToggleRow(
                title = stringResource(R.string.term_use_mirror),
                desc = stringResource(R.string.term_mirror_desc),
                checked = useMirror,
                onToggle = { viewModel.depCenter.setUseMirror(it) }
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                TextButton(
                    onClick = { viewModel.depCenter.installAll() },
                    enabled = installState.runningId == null
                ) { Text(stringResource(R.string.term_install_all)) }
                TextButton(
                    onClick = { viewModel.depCenter.installAndroidOnly() },
                    enabled = installState.runningId == null
                ) { Text(stringResource(R.string.term_android_deps)) }
            }

            DepGroup.entries.forEach { group ->
                val items = depItems.filter { it.group == group }
                if (items.isEmpty()) return@forEach
                Text(
                    stringResource(groupLabel(group)),
                    style = MaterialTheme.typography.titleSmall,
                    color = ConsoleTokens.primary,
                    fontFamily = FontFamily.Monospace
                )
                items.forEach { item ->
                    DepRow(
                        item = item,
                        installing = installState.runningId == item.id,
                        installed = statusState.installed[item.id],
                        checking = statusState.checking,
                        ubuntuReady = ubuntuReady,
                        onInstall = { version -> viewModel.depCenter.installDep(item, version) }
                    )
                }
            }

            if (installState.log.isNotBlank()) {
                Text(
                    stringResource(R.string.term_dep_log),
                    style = MaterialTheme.typography.titleSmall,
                    color = ConsoleTokens.onSurface,
                    fontFamily = FontFamily.Monospace
                )
                LogPane(installState.log)
            }
        }
    }
}

private fun groupLabel(group: DepGroup): Int = when (group) {
    DepGroup.BASIC -> R.string.term_group_basic
    DepGroup.JAVA -> R.string.term_group_java
    DepGroup.ANDROID -> R.string.term_group_android
    DepGroup.RUNTIME -> R.string.term_group_runtime
}

/** 单个依赖行：名称 + 状态徽章 / 描述 / 版本下拉 + 安装按钮。 */
@Composable
private fun DepRow(
    item: DepItem,
    installing: Boolean,
    installed: Boolean?,
    checking: Boolean,
    ubuntuReady: Boolean,
    onInstall: (String?) -> Unit
) {
    var selectedVersion by rememberSaveable(item.id) {
        mutableStateOf(item.preferredVersion ?: "")
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                item.name,
                style = MaterialTheme.typography.bodyMedium,
                color = ConsoleTokens.onSurface,
                modifier = Modifier.weight(1f)
            )
            StatusChip(installed = installed, checking = checking)
        }
        Text(
            item.description,
            style = MaterialTheme.typography.bodySmall,
            color = ConsoleTokens.onSurfaceVariant
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (item.versioned) {
                VersionSelector(
                    versions = item.versions,
                    selected = selectedVersion,
                    onSelect = { selectedVersion = it }
                )
            }
            Spacer(Modifier.weight(1f))
            if (installing) {
                Text(
                    stringResource(R.string.term_installing),
                    style = MaterialTheme.typography.labelMedium,
                    color = ConsoleTokens.amber
                )
            } else {
                TextButton(
                    onClick = { onInstall(selectedVersion.takeIf { it.isNotBlank() }) },
                    enabled = ubuntuReady
                ) { Text(stringResource(R.string.term_install)) }
            }
        }
    }
}

/** 已装状态徽章（null = 未知/未探测 → 不渲染）。 */
@Composable
private fun StatusChip(installed: Boolean?, checking: Boolean) {
    when {
        checking && installed == null -> Text(
            stringResource(R.string.term_dep_checking),
            style = MaterialTheme.typography.labelMedium,
            color = ConsoleTokens.onSurfaceVariant
        )
        installed == null -> Unit
        installed -> Icon(
            Icons.Filled.CheckCircle,
            contentDescription = stringResource(R.string.term_dep_installed),
            tint = ConsoleTokens.lineExitOk,
            modifier = Modifier.size(16.dp)
        )
        else -> Text(
            stringResource(R.string.term_dep_missing),
            style = MaterialTheme.typography.labelMedium,
            color = ConsoleTokens.onSurfaceVariant
        )
    }
}

/** 版本自选下拉（等宽字体，紧凑形态）。 */
@Composable
private fun VersionSelector(
    versions: List<String>,
    selected: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Text(
                "v$selected",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = ConsoleTokens.primary,
                maxLines = 1
            )
            Spacer(Modifier.width(4.dp))
            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
        }
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            versions.forEach { v ->
                DropdownMenuItem(
                    text = { Text(v, fontFamily = FontFamily.Monospace) },
                    onClick = { onSelect(v); expanded = false }
                )
            }
        }
    }
}

/** 等宽安装日志窗（限高滚动 —— 长安装输出的可读容器）。 */
@Composable
private fun LogPane(log: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = ConsoleTokens.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            log.takeLast(6000),
            Modifier
                .height(220.dp)
                .verticalScroll(rememberScrollState())
                .padding(10.dp),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = ConsoleTokens.onSurfaceVariant
        )
    }
}

// ═══════════════ 共享小部件（自 EnvironmentCenterSheet 迁入）═══════════════

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
