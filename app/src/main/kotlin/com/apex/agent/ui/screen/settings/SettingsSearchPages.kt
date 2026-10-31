package com.apex.agent.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.apex.agent.R
import com.apex.agent.platform.terminal.searxng.SEARXNG_ENGINE_CATALOG
import com.apex.agent.platform.terminal.searxng.SearxngInstallStage
import com.apex.agent.platform.terminal.searxng.SearxngState
import com.apex.agent.searxng.SearxngSettingsStore
import com.apex.agent.service.ApexCoreService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 设置中心「搜索」大分类（SearXNG Local Search Spec v1.0 §11 + 开放问题 2
 * 拍板「先增加网络搜索相关所有设置」）。
 *
 *  - search/local：本地搜索（SearXNG）开关卡（状态机可视：Installing 阶段进度
 *    / Starting 不确定进度 / Ready 绿点 / Failed 重试按钮）+ 引擎多选（v1 固定
 *    6 个目录）+ 清除安装入口（设置页二级，确认对话框门禁）+ 地址展示（F12）。
 *
 * 开关接线纪律（Spec §8「UI 只发 action，不直接调 Coordinator」）：开/关 =
 * 先写意图（SearxngSettingsStore）再 startForegroundService(ACTION_START/STOP)；
 * 重试/引擎变更/清除 = ViewModel 直调 Coordinator 单例（进程内幂等 API，无
 * action 语义）。
 */
@HiltViewModel
class SearxngViewModel @Inject constructor(
    val coordinator: com.apex.agent.platform.terminal.searxng.SearxngLifecycleCoordinator,
    private val installer: com.apex.agent.platform.terminal.searxng.SearxngInstaller,
    private val settingsStore: SearxngSettingsStore,
    @ApplicationContext private val appContext: android.content.Context
) : ViewModel() {

    /** Coordinator 状态流直通（INV4：UI 由状态流驱动）。 */
    val state: StateFlow<SearxngState> = coordinator.state

    /** 引擎选择（意图侧事实源；变更即落盘 + 通知 Coordinator）。 */
    private val _engines = MutableStateFlow(settingsStore.engines())
    val engines: StateFlow<Set<String>> = _engines.asStateFlow()

    /** 地址展示（F12 排查用）。 */
    val address: String get() = coordinator.address()

    /** 开关切换：先写意图再发 action（Spec §8 事务语义；失败由 reconcile 补偿）。 */
    fun setEnabled(enabled: Boolean) {
        settingsStore.setEnabled(enabled)
        val action = if (enabled) ApexCoreService.ACTION_START_SEARXNG else ApexCoreService.ACTION_STOP_SEARXNG
        runCatching {
            val intent = android.content.Intent(appContext, ApexCoreService::class.java).setAction(action)
            ContextCompat.startForegroundService(appContext, intent)
        }
    }

    /** 失败重试（Spec §6 retry —— Coordinator 直调，仅 Failed 有效）。 */
    fun retry() {
        viewModelScope.launch { runCatching { coordinator.retry() } }
    }

    /** 引擎多选切换（Spec §6 setEngines —— Ready 时 Coordinator 自动重启生效）。 */
    fun toggleEngine(id: String) {
        val next = _engines.value.toMutableSet().apply {
            if (!add(id)) remove(id)
        }
        // 空名单回落默认引擎（与 Writer 归一规则一致 —— UI 侧同步展示归一结果）
        val normalized = next.ifEmpty { com.apex.agent.platform.terminal.searxng.SEARXNG_DEFAULT_ENGINES }
        _engines.value = normalized
        viewModelScope.launch { runCatching { coordinator.setEngines(normalized) } }
    }

    /** 清除安装（Spec §8「清除搜索组件」入口 —— Off/Failed 态可用）。 */
    fun clearInstall(onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val stopped = runCatching { coordinator.disable().isSuccess }.getOrDefault(false)
            val cleared = stopped && runCatching { installer.uninstall().isSuccess }.getOrDefault(false)
            onDone(cleared)
        }
    }
}

// ═══════════════ search/local：本地搜索（SearXNG）═══════════════

@Composable
internal fun SearchLocalPage(viewModel: SearxngViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val engines by viewModel.engines.collectAsStateWithLifecycle()
    var showClearDialog by remember { mutableStateOf(false) }

    SettingsLeafColumn {
        SearxngSwitchCard(
            state = state,
            address = viewModel.address,
            onToggle = viewModel::setEnabled,
            onRetry = viewModel::retry
        )

        // 状态细则（Installing 的阶段进度 / Failed 的原因 —— 独立于开关卡的轻量补充）
        when (val s = state) {
            is SearxngState.Installing -> Column {
                Text(
                    stringResource(R.string.search_status_installing) + " — " +
                        stringResource(stageRes(s.stage)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                LinearProgressIndicator(
                    progress = { s.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
            }
            is SearxngState.Starting -> Column {
                Text(
                    stringResource(R.string.search_status_starting),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
            is SearxngState.Restarting -> Column {
                Text(
                    stringResource(R.string.search_status_restarting, s.attempt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
            is SearxngState.Failed -> Column {
                Text(
                    stringResource(R.string.search_status_failed_reason, s.reason),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                if (s.retryable) {
                    OutlinedButton(onClick = viewModel::retry, modifier = Modifier.padding(top = 4.dp)) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.search_retry))
                    }
                }
            }
            else -> Unit
        }

        // ── 引擎多选（F9：v1 固定 6 目录；变更即写 + Ready 时自动重启）──
        SectionCard(
            title = stringResource(R.string.search_engines_title),
            icon = Icons.Outlined.Search,
            initiallyExpanded = true
        ) {
            Text(
                stringResource(R.string.search_engines_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            EngineChips(
                selected = engines,
                onToggle = viewModel::toggleEngine
            )
        }

        // ── 排查信息（F12）+ 清除入口（开放问题 2：设置页二级）──
        SectionCard(
            title = stringResource(R.string.search_diagnostics_title),
            icon = Icons.Filled.Delete,
            subtitle = viewModel.address
        ) {
            Text(
                stringResource(R.string.search_fallback_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            val canClear = state is SearxngState.Off || state is SearxngState.Failed
            Button(
                onClick = { showClearDialog = true },
                enabled = canClear,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = Color.White
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.search_clear))
            }
            if (!canClear) {
                Text(
                    stringResource(R.string.search_clear_disabled_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    showClearDialog = false
                    viewModel.clearInstall { }
                }) { Text(stringResource(R.string.search_clear_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(R.string.search_clear_cancel))
                }
            },
            title = { Text(stringResource(R.string.search_clear_title)) },
            text = { Text(stringResource(R.string.search_clear_body)) }
        )
    }
}

/** 开关卡（Spec §11 SearxngSwitchCard 契约）。 */
@Composable
private fun SearxngSwitchCard(
    state: SearxngState,
    address: String,
    onToggle: (Boolean) -> Unit,
    onRetry: () -> Unit
) {
    SectionCard(
        title = stringResource(R.string.search_local_title),
        icon = Icons.Filled.Search,
        subtitle = address,
        initiallyExpanded = true
    ) {
        val installing = state is SearxngState.Installing
        val checked = state !is SearxngState.Off
        SwitchRow(
            label = stringResource(R.string.search_local_switch),
            checked = checked,
            description = stringResource(R.string.search_local_switch_desc),
            onCheckedChange = { on -> onToggle(on) }
        )
        // 状态行（Inv4：由状态流驱动）
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (state) {
                is SearxngState.Ready -> {
                    StatusDot(color = Color(0xFF2E7D32))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.search_status_ready),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics {
                            contentDescription = "searxng ready"
                        }
                    )
                }
                is SearxngState.Stopping -> {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.search_status_stopping), style = MaterialTheme.typography.bodyMedium)
                }
                is SearxngState.Off -> {
                    Text(
                        stringResource(R.string.search_status_off),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                else -> {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(
                            when (state) {
                                is SearxngState.Installing -> R.string.search_status_installing
                                is SearxngState.Restarting -> R.string.search_status_restarting_short
                                else -> R.string.search_status_starting
                            }
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

/** 引擎多选 chips（字符串 id 键 —— ChipMultiSelect 是 Int 键，这里内建字符串版）。 */
@Composable
private fun EngineChips(
    selected: Set<String>,
    onToggle: (String) -> Unit
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(SEARXNG_ENGINE_CATALOG, key = { it.id }) { engine ->
            FilterChip(
                selected = engine.id in selected,
                onClick = { onToggle(engine.id) },
                label = { Text(engine.label) }
            )
        }
    }
}

/** Ready 绿点。 */
@Composable
private fun StatusDot(color: Color) {
    Surface(shape = CircleShape, color = color, modifier = Modifier.size(10.dp)) {}
}

/** 安装阶段文案（SearxngInstallStage.label → i18n 资源映射）。 */
private fun stageRes(stage: String): Int = when (stage) {
    SearxngInstallStage.APT.label -> R.string.search_stage_apt
    SearxngInstallStage.CLONE.label -> R.string.search_stage_clone
    SearxngInstallStage.VENV.label -> R.string.search_stage_venv
    SearxngInstallStage.PIP.label -> R.string.search_stage_pip
    else -> R.string.search_stage_verify
}
