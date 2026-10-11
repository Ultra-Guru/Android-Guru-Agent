package com.apex.agent.ui.screen.terminal

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apex.agent.R
import com.apex.agent.platform.terminal.ubuntu.lifecycle.UbuntuLifecycleCoordinator
import com.apex.agent.ui.screen.terminal.history.TerminalHistorySheet
import com.apex.agent.ui.screen.terminal.scheme.TerminalSchemePickerSheet
import com.apex.agent.ui.screen.terminal.settings.TerminalSettingsSheet
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * 终端主屏（T89 大修 —— 用户反馈「页面一坨 / Ubuntu 用不了 / 没法输入命令」
 * 的系统性重构）。
 *
 * ## T89 结构决策（旧 → 新）
 *
 *  - **砍层叠条带**：旧版竖屏顶部堆 4~7 层（状态 inset + OfflineBanner +
 *    ContextMeterBar + ConsoleTopBar + SessionStrip + StatusStrip 21dp 调试行），
 *    键区再叠 80dp（45 键长滚 + 8 宏键）。新版：单一紧凑顶栏（副标题承载
 *    后端/环境状态）+ 会话条（多会话才出现）+ 终端 + 键区（见 KeyToolbar 重做）。
 *    状态栏调试噪音（#id STATE rows×cols）删除；
 *  - **notice 全局可见**：旧版 notice 只渲染在 StatusStrip 里且仅在有会话时
 *    出现 —— 而会话创建失败/Ubuntu 不可用恰恰发生在**无会话**时，用户全程
 *    盲飞。新版 notice 恒渲染为顶栏下横幅（错误红/信息 mint，可点击关闭）；
 *  - **单弹层范式**：旧版「设置=侧抽屉（套在 App 全局 drawer 里的双层侧滑）、
 *    环境/配色/历史=bottom sheet、新建=AlertDialog」三种范式混学。新版配置
 *    类统一 bottom sheet（[TerminalConsoleTheme] 深色控制台主题），新建会话
 *    保留 AlertDialog（快速二选一的标准容器）；
 *  - **环境进度不遮内容**：旧版 UbuntuBusyStrip 浮在终端 BottomCenter 盖住
 *    最后几行输出/提示符。新版并入顶栏下方 2dp 细进度线 + 副标题百分比；
 *  - **终端永可用**：配合 VM 的 LOCAL 降级兜底（Ubuntu 失败自动拉 Android
 *    shell），主区不再有「无会话 + 键盘无响应」死区。
 */
@Composable
fun TerminalScreen(
    onBack: () -> Unit,
    viewModel: TerminalViewModel = hiltViewModel()
) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val activeId by viewModel.activeSessionId.collectAsStateWithLifecycle()
    val semantic by viewModel.semanticState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val ubuntu by viewModel.ubuntuLifecycleState.collectAsStateWithLifecycle()
    val ubuntuProgress by viewModel.ubuntuProgress.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val blacklist by viewModel.blacklist.collectAsStateWithLifecycle()
    val whitelist by viewModel.whitelist.collectAsStateWithLifecycle()

    // 弹层瞬态 rememberSaveable（#2-c P2-7）：旋转/进程重建后 dialog/sheet
    // 开关态不丢，避免用户重建流程（尤其确认类弹层）被系统事件打断后重来。
    var showNewSessionDialog by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showEnvironmentCenter by rememberSaveable { mutableStateOf(false) }
    var showSchemePicker by rememberSaveable { mutableStateOf(false) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    // E2 防误触：待确认关闭的会话 id（null = 无确认弹层）。
    var closeConfirmTargetId by rememberSaveable { mutableStateOf<Long?>(null) }

    // 保持屏幕常亮：看长任务输出（编译 / apt / 训练日志）时不被息屏打断 ——
    // Termux 默认持有 wakelock，这里用等价的 window flag，交给用户开关。
    val keepScreenOn = settings.keepScreenOn
    val view = LocalView.current
    DisposableEffect(keepScreenOn) {
        // L3：context 可能被 ContextThemeWrapper 包裹（旧版 as? Activity 直接
        // 拿不到 window → 常亮静默失效）—— 沿 baseContext 链解包找 Activity。
        val activity = generateSequence(view.context) { (it as? android.content.ContextWrapper)?.baseContext }
            .filterIsInstance<android.app.Activity>().firstOrNull()
        val window = activity?.window
        if (window != null) {
            if (keepScreenOn) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            // 离屏必须还原：否则终端页退出后整 App 一直亮屏耗电
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // 环境中心打开时刷新一次占用（安装/删除后状态驱动刷新，这里兑底）
    LaunchedEffect(showEnvironmentCenter) {
        if (showEnvironmentCenter) viewModel.refreshRootfsSize()
    }

    // 反馈横幅自动消隐（P1-4：错误/降级类稍长 —— 含操作指引；信息类短一档。
    // 旧注释声称「错误类稍长」但实现是统一 8000ms，现在真正按语义分档）
    LaunchedEffect(notice) {
        val n = notice ?: return@LaunchedEffect
        delay(if (n.kind == TerminalViewModel.TerminalNotice.Kind.INFO) 5000 else 8000)
        viewModel.consumeNotice()
    }

    // P1-3：派生状态用 derivedStateOf 缓存 —— sessions/activeId/ubuntu 每次变更
    // 不再重推导全部条件链，依赖方（chrome/主区）只在派生值变化时重组。
    val activeTab by remember { derivedStateOf { sessions.firstOrNull { it.id == activeId } } }
    val hasSession by remember { derivedStateOf { activeId != null && sessions.any { it.id == activeId } } }
    val envBusy by remember { derivedStateOf { ubuntu.phase in ENV_BUSY_PHASES } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ConsoleTheme.bg)
    ) {
        // ═══ P1-3：终端 chrome 层（顶栏 + 环境进度线 + notice 横幅 + 会话条）═══
        // 旧版 6 个条件渲染层叠在主 Column 里、hasSession/envBusy/activeTab
        // 每次重组都要重推导 —— 提取为独立 Composable，主 Screen 只关心主区
        // 与弹层编排，重组范围互不干扰。
        TerminalChrome(
            activeTab = activeTab,
            sessions = sessions,
            activeId = activeId,
            semantic = semantic,
            ubuntuPhase = ubuntu.phase,
            ubuntuPercent = ubuntuProgress?.percent ?: 0,
            envBusy = envBusy,
            notice = notice,
            onBack = onBack,
            onNewSession = { showNewSessionDialog = true },
            onOpenCenter = { showEnvironmentCenter = true },
            onOpenSettings = { showSettings = true },
            onSelectSession = viewModel::selectSession,
            onCloseSession = { id ->
                // E2 防误触：活跃会话的关闭钮先弹确认（closeSession 会杀 PTY，
                // 跑着的编译/apt/训练任务误点即丢且无法恢复）；
                // 死会话（进程已退出）无现场可丢，直接移除。
                if (sessions.firstOrNull { it.id == id }?.isAlive == true) {
                    closeConfirmTargetId = id
                } else {
                    viewModel.closeSession(id)
                }
            },
            onDismissNotice = viewModel::consumeNotice
        )

        // ═══ 主区：终端 / 环境面板 ═══
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (hasSession) {
                // T88（3）：:terminal-view Canvas 直绘（滚动/选区/IME/鼠标上报
                // 全部下放 View 层）。
                TerminalViewHost(
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxSize()
                )
                // T87：死会话覆盖层 —— 会话进程已退出时不再让用户对着
                // 死 PTY 敲字（旧体验：每次输入弹「输入失败」却无路可走）。
                // P1-3：activeTab 是 delegated（derivedStateOf）属性，smart
                // cast 不可用 —— 局部快照后判断。
                val tab = activeTab
                if (tab != null && !tab.isAlive) {
                    DeadSessionOverlay(
                        isUbuntu = tab.isUbuntu,
                        onRestart = viewModel::restartActiveSession,
                        onClose = { viewModel.closeSession(tab.id) }
                    )
                }
            } else {
                EnvironmentPanel(
                    phase = ubuntu.phase,
                    progress = ubuntuProgress,
                    lastError = ubuntu.lastError,
                    onRetry = viewModel::installUbuntu,
                    onNewUbuntu = { viewModel.createSession(TerminalViewModel.BACKEND_UBUNTU) },
                    onNewLocal = { viewModel.createSession(TerminalViewModel.BACKEND_LOCAL) },
                    onOpenCenter = { showEnvironmentCenter = true }
                )
            }
        }
    }

    if (showNewSessionDialog) {
        TerminalConsoleTheme {
            NewSessionDialog(
                onDismiss = { showNewSessionDialog = false },
                onUbuntu = {
                    showNewSessionDialog = false
                    viewModel.createSession(TerminalViewModel.BACKEND_UBUNTU)
                },
                onLocal = {
                    showNewSessionDialog = false
                    viewModel.createSession(TerminalViewModel.BACKEND_LOCAL)
                }
            )
        }
    }

    // ═══ 终端设置（T89：侧抽屉 → bottom sheet，单弹层范式）═══
    if (showSettings) {
        TerminalSettingsSheet(
            settings = settings,
            onSettings = viewModel::updateSettings,
            schemeName = viewModel.currentSchemeDisplayName(),
            onOpenSchemePicker = { showSchemePicker = true },
            boldAsBright = viewModel.boldAsBright.collectAsStateWithLifecycle().value,
            onBoldAsBright = viewModel::setBoldAsBright,
            onOpenHistory = { showHistory = true },
            extraKeys = viewModel.extraKeys.collectAsStateWithLifecycle().value,
            onAddExtraKey = viewModel::addExtraKey,
            onRemoveExtraKey = viewModel::removeExtraKey,
            onResetExtraKeys = viewModel::resetExtraKeys,
            blacklist = blacklist,
            whitelist = whitelist,
            onAddBlack = viewModel::addBlacklist,
            onRemoveBlack = viewModel::removeBlacklist,
            onAddWhite = viewModel::addWhitelist,
            onRemoveWhite = viewModel::removeWhitelist,
            onClose = { showSettings = false }
        )
    }

    // ═══ 环境中心（顶栏图层入口）═══
    if (showEnvironmentCenter) {
        TerminalConsoleTheme {
            EnvironmentCenterSheet(
                onDismiss = { showEnvironmentCenter = false },
                ubuntu = ubuntu,
                progress = ubuntuProgress,
                rootfsSize = viewModel.rootfsSize.collectAsStateWithLifecycle().value,
                onInstallUbuntu = viewModel::installUbuntu,
                onCancelUbuntuInstall = viewModel::cancelUbuntuInstall,
                onRepairUbuntu = viewModel::repairUbuntu,
                onRemoveUbuntu = viewModel::removeUbuntu,
                onCreateUbuntuSession = {
                    showEnvironmentCenter = false
                    viewModel.createSession(TerminalViewModel.BACKEND_UBUNTU)
                },
                useMirror = viewModel.useMirror.collectAsStateWithLifecycle().value,
                onToggleMirror = viewModel::setUseMirror,
                depItems = viewModel.depItems,
                install = viewModel.install.collectAsStateWithLifecycle().value,
                onInstallDep = viewModel::installDep,
                onInstallAll = viewModel::installAll,
                onInstallAndroid = viewModel::installAndroidOnly
            )
        }
    }

    // ═══ T87：配色方案选择器 ═══
    if (showSchemePicker) {
        TerminalConsoleTheme {
            TerminalSchemePickerSheet(
                currentSchemeId = viewModel.colorSchemeId.collectAsStateWithLifecycle().value,
                onPick = { viewModel.setColorScheme(it) },
                onDismiss = { showSchemePicker = false }
            )
        }
    }

    // ═══ T87：命令历史 ═══
    if (showHistory) {
        TerminalConsoleTheme {
            TerminalHistorySheet(
                entries = viewModel.commandHistoryEntries.collectAsStateWithLifecycle().value,
                onPick = { cmd ->
                    viewModel.sendInput(cmd)
                    showHistory = false
                },
                onClear = { viewModel.clearCommandHistory() },
                onDismiss = { showHistory = false }
            )
        }
    }

    // ═══ E2：活跃会话关闭确认（防误触丢现场 —— 最后防线）═══
    closeConfirmTargetId?.let { targetId ->
        TerminalConsoleTheme {
            AlertDialog(
                onDismissRequest = { closeConfirmTargetId = null },
                title = { Text(stringResource(R.string.term_close_confirm_title)) },
                text = { Text(stringResource(R.string.term_close_confirm_text)) },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.closeSession(targetId)
                        closeConfirmTargetId = null
                    }) {
                        Text(
                            stringResource(R.string.term_close_confirm_ok),
                            color = ConsoleTheme.danger
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { closeConfirmTargetId = null }) {
                        Text(stringResource(R.string.term_cancel))
                    }
                }
            )
        }
    }
}

// ═══════════════════════ 终端 chrome 层（P1-3 提取）═══════════════════════

/** 环境「忙碌」相位（安装中 / 引导中 —— 顶栏细进度线的显示条件）。 */
private val ENV_BUSY_PHASES = setOf(
    UbuntuLifecycleCoordinator.Phase.INSTALLING,
    UbuntuLifecycleCoordinator.Phase.BOOTSTRAPPING
)

/**
 * 终端 chrome 层（P1-3 提取）：顶栏 + 环境收敛细进度线 + notice 横幅 +
 * 会话条。旧版这 4 块 + 各自的条件渲染层叠在主 Screen 的 Column 里，
 * hasSession/envBusy/activeTab 每次重组都要重推导 —— 收拢为独立 Composable
 * 后主 Screen 只关心主区与弹层编排，chrome 内部变更不影响主区重组范围。
 */
@Composable
private fun TerminalChrome(
    activeTab: SessionTab?,
    sessions: List<SessionTab>,
    activeId: Long?,
    semantic: com.apex.agent.platform.terminal.state.TerminalSemanticState?,
    ubuntuPhase: UbuntuLifecycleCoordinator.Phase,
    ubuntuPercent: Int,
    envBusy: Boolean,
    notice: TerminalViewModel.TerminalNotice?,
    onBack: () -> Unit,
    onNewSession: () -> Unit,
    onOpenCenter: () -> Unit,
    onOpenSettings: () -> Unit,
    onSelectSession: (Long) -> Unit,
    onCloseSession: (Long) -> Unit,
    onDismissNotice: () -> Unit
) {
    // ═══ 顶栏（单一紧凑层：导航 + 状态 + 动作）═══
    ConsoleTopBar(
        activeTab = activeTab,
        ubuntuPhase = ubuntuPhase,
        ubuntuPercent = ubuntuPercent,
        semantic = semantic,
        onBack = onBack,
        onNewSession = onNewSession,
        onOpenCenter = onOpenCenter,
        onOpenSettings = onOpenSettings
    )

    // ═══ 环境收敛细进度线（不遮内容 —— 替代旧浮条）═══
    if (envBusy) {
        val percent = ubuntuPercent
        val determinate = percent > 0
        if (determinate) {
            LinearProgressIndicator(
                progress = { percent.coerceIn(0, 100) / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = ConsoleTheme.accent,
                trackColor = ConsoleTheme.accentSoft
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = ConsoleTheme.accent,
                trackColor = ConsoleTheme.accentSoft
            )
        }
    }

    // ═══ notice 横幅（恒可见 —— 无会话时也反馈；点击关闭）═══
    notice?.let { NoticeBanner(notice = it, onDismiss = onDismissNotice) }

    // ═══ 会话条（≥2 个会话才显示 —— 单会话零干扰）═══
    if (sessions.size >= 2) {
        SessionStrip(
            sessions = sessions,
            activeId = activeId,
            onSelect = onSelectSession,
            onClose = onCloseSession
        )
    }
}

// ═══════════════════════ notice 横幅 ═══════════════════════

/**
 * 终端操作反馈横幅（T89：恒可见 —— 替代旧版「埋在状态条里且无会话时消失」的
 * 反馈通道）。
 *
 * P1-4：颜色由 [TerminalViewModel.TerminalNotice.Kind] 决定（VM 发出时刻已定
 * 语义）—— 替代旧版关键词字符串嗅探（`contains("失败")/contains("error")`：
 * 文案一改错误样式就静默丢失，且中英混合判断不可靠）。
 *  - ERROR → 红系 + Warning 图标；
 *  - FALLBACK（降级：Ubuntu 失败自动切 Android Shell）→ amber 警示 + Warning；
 *  - INFO → mint 信息色 + CheckCircle。
 *
 * T95：点击横幅展开全文（错误类常被两行截断 —— 用户反馈里
 * 「backend prepare failed: /data/user/0/com…」被截尾的路径就是根因线索，
 * 看不到完整错误就无法自查）；× 钮独立关闭，不再承担双重职责。
 */
@Composable
private fun NoticeBanner(
    notice: TerminalViewModel.TerminalNotice,
    onDismiss: () -> Unit
) {
    // 新 notice 到达重置展开态（remember 键含 notice 身份）
    var expanded by remember(notice) { mutableStateOf(false) }
    val bg = when (notice.kind) {
        TerminalViewModel.TerminalNotice.Kind.ERROR -> ConsoleTheme.dangerSoft
        TerminalViewModel.TerminalNotice.Kind.FALLBACK -> ConsoleTheme.warnSoft
        TerminalViewModel.TerminalNotice.Kind.INFO -> ConsoleTheme.accentSoft
    }
    val fg = when (notice.kind) {
        TerminalViewModel.TerminalNotice.Kind.ERROR -> ConsoleTheme.danger
        TerminalViewModel.TerminalNotice.Kind.FALLBACK -> ConsoleTheme.amber
        TerminalViewModel.TerminalNotice.Kind.INFO -> ConsoleTheme.accent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable(onClick = { expanded = !expanded })
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            if (notice.kind == TerminalViewModel.TerminalNotice.Kind.INFO) {
                Icons.Default.CheckCircle
            } else {
                Icons.Default.Warning
            },
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(15.dp)
        )
        Text(
            notice.text,
            modifier = Modifier.weight(1f),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = fg,
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis
        )
        // × 关闭钮独立触控（36dp 触控区 + 16dp 图标）；旧版整行点击关闭会把
        // 「想看全文」的点击误当关闭，与 T95 展开语义冲突
        Icon(
            Icons.Default.Close,
            contentDescription = stringResource(R.string.term_close),
            tint = ConsoleTheme.dim,
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .clickable(onClick = onDismiss)
                .padding(10.dp)
        )
    }
}

// ═══════════════════════ 死会话覆盖层 ═══════════════════════

/**
 * 会话已退出覆盖层（半透明盖在终端 grid 上）。
 *
 * - 「重启会话」→ 同 backend 重建（[TerminalViewModel.restartActiveSession]）；
 * - 「关闭」→ 移除 tab；
 * - 下方如实展示发生了什么（进程结束 = 退出/被杀；不再伪装成输入故障）。
 */
@Composable
private fun DeadSessionOverlay(
    isUbuntu: Boolean,
    onRestart: () -> Unit,
    onClose: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ConsoleTheme.bg.copy(alpha = 0.70f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = ConsoleTheme.amber,
                modifier = Modifier.size(40.dp)
            )
            Text(
                stringResource(R.string.term_session_dead_title),
                color = ConsoleTheme.text,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                stringResource(
                    if (isUbuntu) R.string.term_session_dead_ubuntu else R.string.term_session_dead_local
                ),
                color = ConsoleTheme.dim,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(
                    onClick = onRestart,
                    modifier = Modifier
                        .background(ConsoleTheme.accentSoft, RoundedCornerShape(10.dp))
                ) {
                    Text(stringResource(R.string.term_session_restart), color = ConsoleTheme.accent, fontSize = 13.sp)
                }
                TextButton(onClick = onClose) {
                    Text(stringResource(R.string.term_close), color = ConsoleTheme.dim, fontSize = 13.sp)
                }
            }
        }
    }
}

// ═══════════════════════ 顶栏 ═══════════════════════

@Composable
private fun ConsoleTopBar(
    activeTab: SessionTab?,
    ubuntuPhase: UbuntuLifecycleCoordinator.Phase,
    ubuntuPercent: Int,
    semantic: com.apex.agent.platform.terminal.state.TerminalSemanticState?,
    onBack: () -> Unit,
    onNewSession: () -> Unit,
    onOpenCenter: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ConsoleTheme.bar)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ★ 返回钮（用户反馈：终端页不要汉堡/左滑拉抽屉）—— 取代旧 Menu 钮。
        // 语义与系统返回键一致（弹历史栈，栈空兑底回 Agent 主页）；AutoMirrored
        // 图标在 RTL 布局下自动镜像。抽屉在返回后的页面正常可达。
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.term_back_to_drawer),
                tint = ConsoleTheme.text
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(
                stringResource(R.string.term_title),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = ConsoleTheme.text
            )
            // 副标题（一行）：会话后端 / 前台 job / 环境进度 —— 旧 StatusStrip
            // 的调试噪音（#id STATE rows×cols）删除，保留用户可读信号。
            val subtitle = when {
                activeTab != null && !activeTab.isAlive -> stringResource(R.string.term_session_dead_title)
                activeTab != null && semantic?.foregroundJob != null ->
                    "▶ ${semantic?.foregroundJob?.command?.take(24)}"
                activeTab != null && activeTab.isUbuntu -> "ubuntu 24.04 · bash"
                activeTab != null -> "android shell"
                ubuntuPhase in setOf(
                    UbuntuLifecycleCoordinator.Phase.INSTALLING,
                    UbuntuLifecycleCoordinator.Phase.BOOTSTRAPPING
                ) -> stringResource(R.string.term_env_preparing, ubuntuPercent)
                ubuntuPhase == UbuntuLifecycleCoordinator.Phase.FAILED -> stringResource(R.string.term_env_error_short)
                else -> stringResource(R.string.term_waiting_session)
            }
            Text(
                subtitle,
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace,
                color = ConsoleTheme.dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onNewSession) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.term_cd_new_session), tint = ConsoleTheme.accent)
        }
        IconButton(onClick = onOpenCenter) {
            Icon(Icons.Default.Layers, contentDescription = stringResource(R.string.term_cd_env_center), tint = ConsoleTheme.accent)
        }
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.term_cd_settings), tint = ConsoleTheme.accent)
        }
    }
}

// ═══════════════════════ 会话条 ═══════════════════════

/** 会话 tab 标题的最大字符数 —— shell 标题（tmux/ssh）可能很长，UI 只取前若干字符 + 省略号。 */
private const val MAX_TAB_TITLE = 24

@Composable
private fun SessionStrip(
    sessions: List<SessionTab>,
    activeId: Long?,
    onSelect: (Long) -> Unit,
    onClose: (Long) -> Unit
) {
    val listState = rememberLazyListState()
    // T95：活跃会话 chip 始终滚入视野 —— 横向 LazyRow 里 chip 会被屏幕边缘
    // 裁半截（用户反馈「#4 A…」），新建/切换会话后自动滚到该 chip 完整可见。
    val activeIndex = sessions.indexOfFirst { it.id == activeId }
    LaunchedEffect(activeId, sessions.size) {
        if (activeIndex >= 0) listState.animateScrollToItem(activeIndex)
    }
    LazyRow(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .background(ConsoleTheme.bg)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(sessions, key = { it.id }) { tab ->
            SessionChip(
                tab = tab,
                active = tab.id == activeId,
                onSelect = { onSelect(tab.id) },
                onClose = { onClose(tab.id) }
            )
        }
    }
}

@Composable
private fun SessionChip(
    tab: SessionTab,
    active: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit
) {
    // 标题优先显示 shell 自己设的窗口名（OSC 0/1/2 —— vim/tmux/ssh 都会设），
    // 没有才退回 "#id 后端"。对齐 Termux / JuiceSSH：多会话靠标题分辨在跑什么。
    // #170：Agent 创建的会话（backendId=="agent"，见 TerminalViewModel 的推断）
    // 加「Agent·」徽标 —— 会话列表本就不按 owner 过滤，Agent 的 PTY 会话与
    // 用户会话同列展示，徽标让来源一目了然（用户接管输入仍走 USER owner）。
    val agentBadge = if (tab.backendId == "agent") "Agent·" else ""
    val title = tab.title?.take(MAX_TAB_TITLE)
        ?: "#${tab.id} $agentBadge${if (tab.isUbuntu) "Ubuntu" else "Android"}"
    val chipBg by animateColorAsState(
        targetValue = if (active) ConsoleTheme.accentSoft else ConsoleTheme.chip,
        label = "chip-bg"
    )
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(chipBg)
            .clickable(onClick = onSelect)
            .padding(start = 10.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        // 存活状态点
        Box(
            Modifier
                .size(6.dp)
                .background(
                    if (tab.isAlive) ConsoleTheme.accent else ConsoleTheme.danger,
                    CircleShape
                )
        )
        Column {
            Text(
                title,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                color = ConsoleTheme.text
            )
            Text(
                if (tab.title != null) "#${tab.id} $agentBadge${if (tab.isUbuntu) "Ubuntu" else "Android"}" else sessionStateLabel(tab.state),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                color = ConsoleTheme.dim,
                maxLines = 1
            )
        }
        // 关闭钮：32dp 触控区 + 图标 16dp（视觉同旧版）。M4：padding 在
        // clickable **之后**只缩小图标绘制区、不扩大触控 —— 旧版 24dp 触控区
        // 配「≥32dp」注释是错的，低于 48dp 无障碍建议线。
        Icon(
            Icons.Default.Close, stringResource(R.string.term_cd_close_session),
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .clickable(onClick = onClose)
                .padding(8.dp),
            tint = ConsoleTheme.dim
        )
    }
}

/** 会话状态 → 本地化短标签（P2-3：旧版直接显示硬编码英文缩写 —— 项目既有
 *  LanguageManager/双语 strings 体系，这里同样走 stringResource）。 */
@Composable
private fun sessionStateLabel(state: String): String = when (state.uppercase(Locale.US)) {
    "CREATED", "STARTING" -> stringResource(R.string.term_state_starting)
    "READY", "RUNNING" -> stringResource(R.string.term_state_run)
    "WAITING_INPUT" -> stringResource(R.string.term_state_input)
    "INTERRUPTED" -> stringResource(R.string.term_state_interrupted)
    "EXITED", "CLOSED" -> stringResource(R.string.term_state_exited)
    "BROKEN" -> stringResource(R.string.term_state_broken)
    else -> state.take(6)
}

// ═══════════════════════ 新建会话对话框 ═══════════════════════

@Composable
private fun NewSessionDialog(
    onDismiss: () -> Unit,
    onUbuntu: () -> Unit,
    onLocal: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.term_new_session_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SessionTypeCard(
                    icon = { Icon(Icons.Default.Terminal, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp)) },
                    title = stringResource(R.string.term_ubuntu_session),
                    desc = stringResource(R.string.term_session_ubuntu_desc),
                    onClick = onUbuntu
                )
                SessionTypeCard(
                    icon = { Icon(Icons.Default.Android, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(22.dp)) },
                    title = "Android Shell",
                    desc = stringResource(R.string.term_session_android_desc),
                    onClick = onLocal
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.term_cancel)) }
        }
    )
}

@Composable
private fun SessionTypeCard(
    icon: @Composable () -> Unit,
    title: String,
    desc: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        icon()
        Column {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = ConsoleTheme.text)
            Text(
                desc,
                fontSize = 11.sp,
                color = ConsoleTheme.dim,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

