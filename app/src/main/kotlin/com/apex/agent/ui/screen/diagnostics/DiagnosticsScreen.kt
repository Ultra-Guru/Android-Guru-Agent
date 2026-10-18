package com.apex.agent.ui.screen.diagnostics

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FilePresent
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.apex.agent.R
import com.apex.agent.core.tools.mcp.McpManager
import com.apex.agent.core.tools.mcp.McpTransport
import com.apex.agent.core.tools.skill.SkillRegistry
import com.apex.agent.diagnostics.DiagRow
import com.apex.agent.diagnostics.DiagnosticsCollector
import com.apex.agent.diagnostics.LogFileSink
import com.apex.agent.diagnostics.formatBytes
import com.apex.agent.tools.ToolAuditLogger
import com.apex.agent.ui.glass.GlassButton
import com.apex.agent.ui.glass.GlassCard
import com.apex.agent.ui.language.LanguageManager
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 诊断中心页（诊断中心三件套之三）—— 日志落盘查看 + 崩溃记录应用内查看 + 一键诊断报告。
 *
 * 数据链路：[DiagnosticsCollector]（应用/设备信息、崩溃文件、zip 报告）与
 * [LogFileSink]（日志文件读取/清理）。LogFileSink 的启动接线（ApexApp 内
 * start()）由主控完成，本页只消费其产物。
 *
 * 结构：
 *  - 应用信息 GlassCard（默认展开）+ 设备信息 GlassCard（默认收起），行模型 [DiagRow]；
 *  - 崩溃记录区（filesDir/crash/ 下全局 crash handler 的落盘，首个应用内查看入口），
 *    点击行打开等宽字体内容弹窗，弹窗内可分享/删除，区头可清空；
 *  - 日志文件区（filesDir/logs/ 下 LogFileSink 落盘产物），每行查看/分享，底部清空；
 *  - 生成诊断报告按钮 → busy 态 → 成功弹窗（路径+大小+分享 zip）/ 失败弹窗。
 */

/** 诊断中心 UI 状态快照。 */
data class DiagnosticsUiState(
    val appInfo: List<DiagRow> = emptyList(),
    val deviceInfo: List<DiagRow> = emptyList(),
    val crashes: List<File> = emptyList(),
    val logFiles: List<File> = emptyList(),
    val logBytes: Long = 0,
    val busy: Boolean = false,
    val reportFile: File? = null,
    /** 报告生成失败信号（spec UiState 之外的补充字段：失败也需要一次性反馈，见 worklog）。 */
    val reportFailed: Boolean = false,
    // ═══ 诊断增强（用户诉求：工具 / mcp / skills / 终端调用日志 / 失败详细记录）═══
    /** MCP 服务器快照（配置 + 连接状态）。 */
    val mcpServers: List<McpServerRow> = emptyList(),
    /** 已安装技能快照（双工位启用位）。 */
    val skills: List<SkillRow> = emptyList(),
    /** 最近工具/终端调用审计（tool-audit.jsonl）。 */
    val toolCalls: List<ToolAuditLogger.AuditEntry> = emptyList(),
    /** 失败子集（success=false / 拒绝类决策，含命令与详情现场）。 */
    val failures: List<ToolAuditLogger.AuditEntry> = emptyList()
)

/** MCP 服务器展示行（名称 / 传输形态 / 端点 / 启用 / 连接）。 */
data class McpServerRow(
    val name: String,
    val transport: String,
    val endpoint: String,
    val enabled: Boolean,
    val connected: Boolean
)

/** 技能展示行（id / 名称 / 版本 / 双工位启用位 / 工具数）。 */
data class SkillRow(
    val id: String,
    val name: String,
    val version: String,
    val enabledAgent: Boolean,
    val enabledCoding: Boolean,
    val toolCount: Int
)

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val collector: DiagnosticsCollector,
    private val logSink: LogFileSink,
    // i18n：#228 查看器失败态文案（非 Compose 场景，LanguageManager 按当前语言取词）
    private val lang: LanguageManager,
    // ═══ 诊断增强数据源（真实运行时状态，非静态文案）═══
    private val mcpManager: McpManager,
    private val skillRegistry: SkillRegistry,
    private val auditLogger: ToolAuditLogger
) : ViewModel() {

    private val _uiState = MutableStateFlow(DiagnosticsUiState())
    val uiState: StateFlow<DiagnosticsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    /** 聚合刷新全部信息区与文件列表（Default 调度，避免系统 API/IO 压主线程）。 */
    fun refresh() {
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.Default) {
                // ── 诊断增强：MCP / Skills / 工具审计（全部锁内快照读，零连接副作用）──
                val connected = mcpManager.getConnectedServers().toSet()
                val mcpRows = mcpManager.getConfigs().map { cfg ->
                    McpServerRow(
                        name = cfg.name,
                        transport = cfg.transport.name + if (cfg.runInSandbox) "+SANDBOX" else "",
                        endpoint = when (cfg.transport) {
                            McpTransport.HTTP, McpTransport.SSE -> cfg.url.ifBlank { "-" }
                            McpTransport.STDIO ->
                                (cfg.command ?: "-") + cfg.args.joinToString(" ", " ")
                            McpTransport.BUILTIN -> "builtin"
                        },
                        enabled = cfg.enabled,
                        connected = cfg.name in connected
                    )
                }
                val skillRows = skillRegistry.getInstalled().map { s ->
                    SkillRow(
                        id = s.manifest.id,
                        name = s.manifest.name,
                        version = s.manifest.version,
                        enabledAgent = s.enabledAgent,
                        enabledCoding = s.enabledCoding,
                        toolCount = s.manifest.tools.size
                    )
                }
                val toolCallRows = auditLogger.recentEntries(TOOL_CALL_ROWS)
                val failureRows = auditLogger.recentFailures(TOOL_CALL_ROWS)
                DiagnosticsUiState(
                    appInfo = collector.appInfoRows(),
                    deviceInfo = collector.deviceInfoRows(),
                    crashes = collector.crashFiles(),
                    logFiles = logSink.logFiles(),
                    logBytes = logSink.totalBytes(),
                    mcpServers = mcpRows,
                    skills = skillRows,
                    toolCalls = toolCallRows,
                    failures = failureRows
                )
            }
            // 保留 busy/report* 旗标：刷新绝不打断进行中的报告生成或吞掉未消费的结果
            _uiState.update { cur ->
                snapshot.copy(busy = cur.busy, reportFile = cur.reportFile, reportFailed = cur.reportFailed)
            }
        }
    }

    fun deleteCrash(file: File) {
        viewModelScope.launch {
            withContext(Dispatchers.Default) { collector.deleteCrash(file) }
            refresh()
        }
    }

    fun clearCrashes() {
        viewModelScope.launch {
            withContext(Dispatchers.Default) { collector.clearCrashes() }
            refresh()
        }
    }

    fun clearLogs() {
        viewModelScope.launch {
            withContext(Dispatchers.Default) { logSink.clearAll() }
            refresh()
        }
    }

    /** 生成诊断报告：busy 期间禁重复触发；成功置 reportFile，失败置 reportFailed。 */
    fun generateReport() {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true, reportFailed = false) }
        viewModelScope.launch {
            val report = withContext(Dispatchers.Default) { collector.buildDiagnosticsReport() }
            _uiState.update { it.copy(busy = false, reportFile = report, reportFailed = report == null) }
        }
    }

    /** UI 消费完报告结果（成功/失败弹窗关闭）后复位，保证下次结果能再次触发提示。 */
    fun consumeReportResult() {
        _uiState.update { it.copy(reportFile = null, reportFailed = false) }
    }

    /**
     * 读取崩溃文件内容（转给 UI 弹窗；调用方负责切 IO 线程）。
     * #228：失败显式分流为 [ViewerContent.Failed]（带错误摘要）——旧 getOrNull
     * 把 IO 异常吞成 null，与加载态无法区分，弹窗只能无限转圈。
     * 文件缺失（底层返回 null，如列表刷新前刚被清空）同样按失败呈现。
     */
    fun readCrash(file: File): ViewerContent = runCatching { collector.readCrash(file) }.fold(
        onSuccess = { text ->
            if (text != null) ViewerContent.Loaded(text)
            else ViewerContent.Failed(lang.getString(R.string.diag_viewer_missing))
        },
        onFailure = { ViewerContent.Failed(it.message ?: it.javaClass.simpleName) }
    )

    /**
     * 读取日志文件内容（512KB 截尾保留最新；调用方负责切 IO 线程）。
     * #228：同 readCrash —— 成功/失败三态分流，不再 getOrNull 静默。
     */
    fun readLog(file: File): ViewerContent = runCatching { logSink.readText(file) }.fold(
        onSuccess = { text ->
            if (text != null) ViewerContent.Loaded(text)
            else ViewerContent.Failed(lang.getString(R.string.diag_viewer_missing))
        },
        onFailure = { ViewerContent.Failed(it.message ?: it.javaClass.simpleName) }
    )

    /** 崩溃文件时间标签：优先解析文件名内嵌时间戳，回退 lastModified。 */
    fun formatCrashTime(file: File): String = synchronized(crashTimeFormat) {
        crashTimeFormat.format(Date(collector.crashFileTime(file)))
    }

    /** 审计事件时间标签（诊断增强区行首；同一格式器复用 + 同步保护）。 */
    fun formatAuditTime(ts: Long): String = synchronized(crashTimeFormat) {
        crashTimeFormat.format(Date(ts))
    }

    private companion object {
        /** 工具审计展示行数（最近 N 次 + 失败 N 条；与 SystemDiagnose 工具上限同量级）。 */
        const val TOOL_CALL_ROWS = 50
    }
}

/** 崩溃时间标签格式化器（SimpleDateFormat 非线程安全，synchronized 复用）。 */
private val crashTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

/** 弹窗查看目标：崩溃文件或日志文件（决定读取通道与是否提供删除操作）。 */
private data class ViewerTarget(val file: File, val isCrash: Boolean)

/**
 * 崩溃/日志查看弹窗的内容状态（#228）。
 *
 * 旧实现用 String? 表达内容：null 同时承担「加载中」与「读取失败」两种语义，
 * IO 一失败弹窗就永远停在转圈（无错误文案、无重试、无关闭引导）。sealed
 * 三态让 UI 能区分加载/成功/失败，失败可重试（retryKey 驱动重读）。
 */
sealed interface ViewerContent {
    data object Loading : ViewerContent
    data class Loaded(val text: String) : ViewerContent
    data class Failed(val error: String) : ViewerContent
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(viewModel: DiagnosticsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var appExpanded by remember { mutableStateOf(true) }
    var deviceExpanded by remember { mutableStateOf(false) }
    var viewer by remember { mutableStateOf<ViewerTarget?>(null) }
    var viewerContent by remember { mutableStateOf<ViewerContent?>(null) }
    // #228：查看器重试驱动 —— 变更 key 触发下方 LaunchedEffect 重新读取
    var viewerRetryKey by remember { mutableStateOf(0) }
    var pendingDeleteCrash by remember { mutableStateOf<File?>(null) }
    var showClearCrashes by remember { mutableStateOf(false) }
    var showClearLogs by remember { mutableStateOf(false) }
    var showReportDialog by remember { mutableStateOf(false) }
    var showReportFailed by remember { mutableStateOf(false) }
    // 诊断增强：失败详情弹窗选中项（点击失败行打开，看命令/详情/退出码全现场）
    var failureDetail by remember { mutableStateOf<ToolAuditLogger.AuditEntry?>(null) }

    // i18n：onClick / 非组合上下文不可调 stringResource —— 上提词
    val shareChooserTitle = stringResource(R.string.diag_share_chooser)

    // 进入页面刷新（VM 为 Activity 级单例，重进需重取文件列表快照）
    LaunchedEffect(Unit) { viewModel.refresh() }
    // 报告结果一次性弹窗：由 reportFile / reportFailed 变化触发，关闭时 consume 复位
    LaunchedEffect(state.reportFile) { if (state.reportFile != null) showReportDialog = true }
    LaunchedEffect(state.reportFailed) { if (state.reportFailed) showReportFailed = true }
    // 弹窗内容加载：IO 线程读取，主线程仅渲染
    // #228：读取以 (viewer, retryKey) 驱动 —— 重试改 key 即重读；先置 Loading
    // 再读，失败显式置 Failed（旧实现 null 混流是无限转圈的根因）。
    LaunchedEffect(viewer, viewerRetryKey) {
        val target = viewer
        if (target == null) {
            viewerContent = null
        } else {
            viewerContent = ViewerContent.Loading
            viewerContent = withContext(Dispatchers.IO) {
                if (target.isCrash) viewModel.readCrash(target.file) else viewModel.readLog(target.file)
            }
        }
    }

    Scaffold(
        // 内层 Scaffold 置零 insets：状态栏已由根 Scaffold 顶栏承担，避免双重叠加
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = { Text(stringResource(R.string.diag_title)) },
                actions = {
                    if (state.busy) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = viewModel::refresh) {
                            Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.diag_refresh), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── 顶部副标题：报告内容说明 ──
            item {
                Text(
                    stringResource(R.string.diag_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ── 应用信息（默认展开）──
            item {
                InfoSectionCard(
                    title = stringResource(R.string.diag_app_section),
                    icon = Icons.Outlined.Info,
                    expanded = appExpanded,
                    onToggle = { appExpanded = !appExpanded },
                    rows = state.appInfo
                )
            }

            // ── 设备信息（默认收起）──
            item {
                InfoSectionCard(
                    title = stringResource(R.string.diag_device_section),
                    icon = Icons.Outlined.Smartphone,
                    expanded = deviceExpanded,
                    onToggle = { deviceExpanded = !deviceExpanded },
                    rows = state.deviceInfo
                )
            }

            // ── 崩溃记录 ──
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Outlined.BugReport, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            Text(
                                stringResource(R.string.diag_crash_section),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            if (state.crashes.isNotEmpty()) {
                                Text(
                                    stringResource(R.string.diag_crash_count, state.crashes.size),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                TextButton(onClick = { showClearCrashes = true }) {
                                    Text(stringResource(R.string.diag_crash_clear), color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                        if (state.crashes.isEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(stringResource(R.string.diag_crash_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Spacer(Modifier.height(4.dp))
                            state.crashes.forEach { file ->
                                val timeLabel = remember(file) { viewModel.formatCrashTime(file) }
                                CrashFileRow(file = file, timeLabel = timeLabel, onOpen = { viewer = ViewerTarget(file, isCrash = true) })
                            }
                        }
                    }
                }
            }

            // ── MCP 服务器（诊断增强：配置/连接快照，用户诉求「便于我寻找问题」）──
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Outlined.Hub, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Text(
                                stringResource(R.string.diag_mcp_section),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            if (state.mcpServers.isNotEmpty()) {
                                Text(
                                    stringResource(
                                        R.string.diag_mcp_count,
                                        state.mcpServers.size,
                                        state.mcpServers.count { it.connected }
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (state.mcpServers.isEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(stringResource(R.string.diag_mcp_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Spacer(Modifier.height(4.dp))
                            state.mcpServers.forEach { row -> McpServerListRow(row) }
                        }
                    }
                }
            }

            // ── Skills（诊断增强：安装/双工位启用快照）──
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Outlined.Extension, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Text(
                                stringResource(R.string.diag_skill_section),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            if (state.skills.isNotEmpty()) {
                                Text(
                                    stringResource(R.string.diag_skill_count, state.skills.size),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (state.skills.isEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(stringResource(R.string.diag_skill_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Spacer(Modifier.height(4.dp))
                            state.skills.forEach { row -> SkillListRow(row) }
                        }
                    }
                }
            }

            // ── 工具与终端调用日志（诊断增强：tool-audit.jsonl 最近轨迹）──
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Outlined.Terminal, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Text(
                                stringResource(R.string.diag_audit_section),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            if (state.toolCalls.isNotEmpty()) {
                                Text(
                                    stringResource(R.string.diag_audit_count, state.toolCalls.size),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (state.toolCalls.isEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(stringResource(R.string.diag_audit_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Spacer(Modifier.height(4.dp))
                            state.toolCalls.forEach { row ->
                                ToolAuditListRow(
                                    entry = row,
                                    timeLabel = viewModel.formatAuditTime(row.ts)
                                )
                            }
                        }
                    }
                }
            }

            // ── 失败详细记录（诊断增强：失败子集 + 点击看命令/详情现场）──
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Outlined.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            Text(
                                stringResource(R.string.diag_failure_section),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            if (state.failures.isNotEmpty()) {
                                Text(
                                    stringResource(R.string.diag_failure_count, state.failures.size),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        if (state.failures.isEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(stringResource(R.string.diag_failure_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Spacer(Modifier.height(4.dp))
                            state.failures.forEach { row ->
                                ToolAuditListRow(
                                    entry = row,
                                    timeLabel = viewModel.formatAuditTime(row.ts),
                                    isFailure = true,
                                    onOpenDetail = { failureDetail = row }
                                )
                            }
                        }
                    }
                }
            }

            // ── 日志文件 ──
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Outlined.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Text(
                                stringResource(R.string.diag_log_section),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            if (state.logFiles.isNotEmpty()) {
                                Text(
                                    stringResource(R.string.diag_log_count, state.logFiles.size, formatBytes(state.logBytes)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (state.logFiles.isEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(stringResource(R.string.diag_log_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Spacer(Modifier.height(4.dp))
                            state.logFiles.forEach { file ->
                                LogFileRow(
                                    file = file,
                                    onOpen = { viewer = ViewerTarget(file, isCrash = false) },
                                    onShare = { shareFile(context, file, "text/plain", shareChooserTitle) }
                                )
                            }
                            Spacer(Modifier.height(2.dp))
                            TextButton(onClick = { showClearLogs = true }) {
                                Text(stringResource(R.string.diag_log_clear), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }

            // ── 生成诊断报告 ──
            item {
                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.diag_report_section), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.diag_report_content_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(12.dp))
                    if (state.busy) {
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(10.dp))
                                Text(stringResource(R.string.diag_report_generating), style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    } else {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            GlassButton(
                                text = stringResource(R.string.diag_report_generate),
                                onClick = viewModel::generateReport,
                                leadingIcon = Icons.Outlined.Archive
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }

    // ══════════ 崩溃/日志内容查看弹窗 ══════════
    viewer?.let { target ->
        Dialog(onDismissRequest = { viewer = null }) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        target.file.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(8.dp))
                    when (val content = viewerContent) {
                        is ViewerContent.Loaded -> {
                            val lines = remember(content) { content.text.lines() }
                            // 等宽 12sp 逐行渲染：512KB 上限内 LazyColumn 按需组合，滚动流畅
                            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                                items(lines.size, key = { it }) { idx ->
                                    Text(
                                        lines[idx],
                                        style = MaterialTheme.typography.bodySmall,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                        // #228：IO 失败 —— 错误图标 + 「读取失败」+ 错误摘要 + 重试/关闭引导
                        is ViewerContent.Failed -> {
                            Column(
                                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    Icons.Outlined.ErrorOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(32.dp)
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    stringResource(R.string.diag_viewer_failed),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    content.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = { viewerRetryKey++ }) {
                                        Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(stringResource(R.string.diag_viewer_retry))
                                    }
                                    TextButton(onClick = { viewer = null }) {
                                        Text(stringResource(R.string.diag_viewer_close))
                                    }
                                }
                            }
                        }
                        // null（弹窗刚开、effect 尚未回填）与 Loading 同渲染：居中转圈
                        else -> {
                            Box(modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { shareFile(context, target.file, "text/plain", shareChooserTitle) }) {
                            Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.diag_share))
                        }
                        if (target.isCrash) {
                            TextButton(onClick = {
                                pendingDeleteCrash = target.file
                                viewer = null
                            }) {
                                Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.diag_crash_delete), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }

    // ══════════ 失败详情弹窗（诊断增强：命令/详情/退出码/耗时/通道全现场）══════════
    failureDetail?.let { entry ->
        AlertDialog(
            onDismissRequest = { failureDetail = null },
            title = { Text(stringResource(R.string.diag_failure_detail_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "${viewModel.formatAuditTime(entry.ts)} · ${entry.tool}",
                        style = MaterialTheme.typography.titleSmall,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        entry.decision,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error
                    )
                    entry.command?.let { cmd ->
                        Text(stringResource(R.string.diag_failure_field_command), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow
                        ) {
                            Text(
                                cmd,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                    entry.detail?.let { d ->
                        Text(stringResource(R.string.diag_failure_field_detail), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(d, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                    val meta = buildList {
                        entry.exitCode?.let { add(stringResource(R.string.diag_failure_field_exit) + ": $it") }
                        entry.durationMs?.let { add(stringResource(R.string.diag_failure_field_duration) + ": ${it}ms") }
                        entry.tier?.let { add(stringResource(R.string.diag_failure_field_tier) + ": $it") }
                    }
                    if (meta.isNotEmpty()) {
                        Text(
                            meta.joinToString("  ·  "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { failureDetail = null }) {
                    Text(stringResource(R.string.diag_report_close))
                }
            }
        )
    }

    // ══════════ 确认/结果对话框 ══════════
    pendingDeleteCrash?.let { file ->
        ConfirmDialog(
            title = stringResource(R.string.diag_crash_delete_confirm_title),
            text = stringResource(R.string.diag_crash_delete_confirm_text, file.name),
            confirmLabel = stringResource(R.string.diag_crash_delete),
            onConfirm = { viewModel.deleteCrash(file); pendingDeleteCrash = null },
            onDismiss = { pendingDeleteCrash = null }
        )
    }

    if (showClearCrashes) {
        ConfirmDialog(
            title = stringResource(R.string.diag_crash_clear_confirm_title),
            text = stringResource(R.string.diag_crash_clear_confirm_text, state.crashes.size),
            confirmLabel = stringResource(R.string.diag_crash_clear),
            onConfirm = { viewModel.clearCrashes(); showClearCrashes = false },
            onDismiss = { showClearCrashes = false }
        )
    }

    if (showClearLogs) {
        ConfirmDialog(
            title = stringResource(R.string.diag_log_clear_confirm_title),
            text = stringResource(
                R.string.diag_log_clear_confirm_text,
                state.logFiles.size,
                formatBytes(state.logBytes)
            ),
            confirmLabel = stringResource(R.string.diag_log_clear),
            onConfirm = { viewModel.clearLogs(); showClearLogs = false },
            onDismiss = { showClearLogs = false }
        )
    }

    if (showReportDialog) {
        val report = state.reportFile
        if (report != null) {
            AlertDialog(
                onDismissRequest = { showReportDialog = false; viewModel.consumeReportResult() },
                title = { Text(stringResource(R.string.diag_report_success_title)) },
                text = {
                    Text(stringResource(R.string.diag_report_file_info, report.absolutePath, formatBytes(report.length())))
                },
                confirmButton = {
                    TextButton(onClick = {
                        shareFile(context, report, "application/zip", shareChooserTitle)
                        showReportDialog = false
                        viewModel.consumeReportResult()
                    }) {
                        Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.diag_report_share))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showReportDialog = false; viewModel.consumeReportResult() }) {
                        Text(stringResource(R.string.diag_report_close))
                    }
                }
            )
        }
    }

    if (showReportFailed) {
        AlertDialog(
            onDismissRequest = { showReportFailed = false; viewModel.consumeReportResult() },
            title = { Text(stringResource(R.string.diag_report_failed_title)) },
            text = { Text(stringResource(R.string.diag_report_failed_text)) },
            confirmButton = {
                TextButton(onClick = { showReportFailed = false; viewModel.consumeReportResult() }) {
                    Text(stringResource(R.string.diag_report_close))
                }
            }
        )
    }
}

// ══════════════════════════════ 子组件 ══════════════════════════════

/** 破坏性操作确认对话框（删除/清空共用骨架；确认按钮着错误色）。 */
@Composable
private fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.diag_cancel)) }
        }
    )
}

/** 信息区玻璃卡：图标 + 标题 + 展开/收起，展开时渲染 [DiagRow] 行列表。 */
@Composable
private fun InfoSectionCard(
    title: String,
    icon: ImageVector,
    expanded: Boolean,
    onToggle: () -> Unit,
    rows: List<DiagRow>
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onToggle).padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(icon, contentDescription = title, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) stringResource(R.string.diag_collapse) else stringResource(R.string.diag_expand),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                rows.forEach { row -> DiagRowItem(row) }
            }
        }
    }
}

/** 单条信息行：key 左（辅助色小字），value 右（尾对齐，可换行）。 */
@Composable
private fun DiagRowItem(row: DiagRow) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(row.key, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.38f))
        Text(row.value, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End, modifier = Modifier.weight(0.62f))
    }
}

/** 崩溃文件行：错误色图标 + 文件名 + 时间/大小，点击打开内容弹窗。 */
@Composable
private fun CrashFileRow(file: File, timeLabel: String, onOpen: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onOpen)
    ) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.BugReport, contentDescription = stringResource(R.string.diag_view), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(file.name, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "$timeLabel · ${formatBytes(file.length())}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 日志文件行：文件名 + 大小，点击查看，尾部分享按钮。 */
@Composable
private fun LogFileRow(file: File, onOpen: () -> Unit, onShare: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onOpen)
    ) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.FilePresent, contentDescription = stringResource(R.string.diag_view), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(file.name, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(formatBytes(file.length()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onShare) {
                Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.diag_share), modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * FileProvider 分享文件（照抄 LogViewerScreen.exportAndShare 范式）：
 * appContext 跨回调边界、EXTRA_STREAM + FLAG_GRANT_READ_URI_PERMISSION、
 * chooser 加 NEW_TASK（从非 Activity 上下文启动的兜底）。
 */
// ══════════ 诊断增强行组件（MCP / Skills / 工具审计）══════════

/**
 * MCP 服务器行：名称 + 传输形态徽标 + 连接状态点（绿 = 已连接 / 灰 = 未连接）+
 * 端点（url 或命令行，等宽小字）+ 禁用态整行降透明度。
 */
@Composable
private fun McpServerListRow(row: McpServerRow) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            // 连接状态点：主色/表面变色，与文案互补（色盲可读）
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (row.connected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant
                    )
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(
                    row.name,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (row.enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                Text(
                    buildString {
                        append(row.transport)
                        append("  ·  ")
                        append(if (row.connected) stringResource(R.string.diag_mcp_connected)
                        else stringResource(R.string.diag_mcp_disconnected))
                        if (!row.enabled) append("  ·  ").append(stringResource(R.string.diag_mcp_disabled))
                        append('\n').append(row.endpoint)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * 技能行：名称 + 版本 + 双工位启用徽标（Agent/Coding 各一枚，禁用工位划暗）+ 工具数。
 */
@Composable
private fun SkillListRow(row: SkillRow) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Extension, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(
                    "${row.name}  v${row.version}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    buildString {
                        append(
                            if (row.enabledAgent) stringResource(R.string.diag_skill_scope_agent) + " ✓"
                            else stringResource(R.string.diag_skill_scope_agent) + " ✕"
                        )
                        append("  ·  ")
                        append(
                            if (row.enabledCoding) stringResource(R.string.diag_skill_scope_coding) + " ✓"
                            else stringResource(R.string.diag_skill_scope_coding) + " ✕"
                        )
                        append("  ·  ")
                        append(stringResource(R.string.diag_skill_tools_unit, row.toolCount))
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * 工具审计行：时间 + 工具名（等宽）+ 决策徽标 + 耗时；失败行错误色强调且可点击
 * 打开详情弹窗（[onOpenDetail]）。
 */
@Composable
private fun ToolAuditListRow(
    entry: ToolAuditLogger.AuditEntry,
    timeLabel: String,
    isFailure: Boolean = false,
    onOpenDetail: () -> Unit = {}
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isFailure) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
        else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .let { if (isFailure) it.clickable(onClick = onOpenDetail) else it }
    ) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (isFailure) Icons.Outlined.Warning else Icons.Outlined.Terminal,
                contentDescription = stringResource(R.string.diag_view),
                tint = if (isFailure) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(
                    buildString {
                        append(entry.tool)
                        entry.tier?.let { append("  [").append(it).append(']') }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isFailure) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    buildString {
                        append(timeLabel)
                        append("  ·  ").append(entry.decision)
                        entry.durationMs?.let { append("  ·  ").append(it).append("ms") }
                        entry.command?.let { append('\n').append(it) }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun shareFile(context: Context, file: File, mime: String, chooserTitle: String) {
    val appContext = context.applicationContext
    runCatching {
        val uri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        appContext.startActivity(
            Intent.createChooser(intent, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
