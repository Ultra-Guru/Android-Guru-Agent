@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.apex.agent.ui.screen.terminal

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apex.agent.R
import com.apex.agent.platform.terminal.ubuntu.lifecycle.UbuntuLifecycleCoordinator
import kotlinx.coroutines.delay

/**
 * M3 控制台（T96 —— 终端大重构：唯一终端入口）。
 *
 * 视觉契约（Material 3）：TopAppBar 等宽标题 → 会话标签行（多对话）→
 * 通知条 → 24dp 圆角终端卡片（LazyColumn 真实行流）→ 28dp 圆角
 * OutlinedTextField（"$ " 前缀 + Send/Stop）。
 *
 * 单源输入同步【输入同步】：OutlinedTextField 的 value 与卡片内当前输入行
 * 共用 [TerminalViewModel.input] —— 一处状态两处渲染，零漂移。
 */
@Composable
fun TerminalScreen(
    viewModel: TerminalViewModel,
    onBack: () -> Unit
) {
    TerminalConsoleTheme {
        val sessions by viewModel.sessions.collectAsStateWithLifecycle()
        val activeId by viewModel.activeSessionId.collectAsStateWithLifecycle()
        val notice by viewModel.notice.collectAsStateWithLifecycle()
        val ubuntuState by viewModel.ubuntuLifecycleState.collectAsStateWithLifecycle()
        val settings by viewModel.settings.collectAsStateWithLifecycle()

        var showEnvCenter by rememberSaveable { mutableStateOf(false) }
        var showHistory by rememberSaveable { mutableStateOf(false) }
        var showSettings by rememberSaveable { mutableStateOf(false) }

        // 亮屏（设置项）
        val view = LocalView.current
        LaunchedEffect(settings.keepScreenOn) {
            view.keepScreenOn = settings.keepScreenOn
        }
        // 通知自动消隐（INFO 5s / 其余 8s）—— 委托属性不可智能转换，取局部快照
        LaunchedEffect(notice) {
            val n = notice
            if (n != null) {
                delay(if (n.kind == TerminalViewModel.TerminalNotice.Kind.INFO) 5_000 else 8_000)
                viewModel.consumeNotice()
            }
        }

        Scaffold(
            containerColor = ConsoleTokens.background,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            stringResource(R.string.term_title),
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
                        IconButton(onClick = { showHistory = true }) {
                            Icon(Icons.Outlined.History, stringResource(R.string.term_history_title))
                        }
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Outlined.Settings, stringResource(R.string.term_settings_title))
                        }
                        IconButton(onClick = { showEnvCenter = true }) {
                            Icon(Icons.Outlined.Layers, stringResource(R.string.term_env_center))
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
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 12.dp)
            ) {
                SessionTabsRow(
                    viewModel = viewModel,
                    sessions = sessions,
                    activeId = activeId,
                    ubuntuReady = ubuntuState.phase == UbuntuLifecycleCoordinator.Phase.READY
                )
                Spacer(Modifier.height(8.dp))
                NoticeBar(notice)
                Spacer(Modifier.height(8.dp))
                ConsoleBody(viewModel, activeId, settings.fontSize, Modifier.weight(1f))
                Spacer(Modifier.height(8.dp))
                InputBar(viewModel)
            }
        }

        if (showEnvCenter) {
            EnvironmentCenterSheet(viewModel) { showEnvCenter = false }
        }
        if (showHistory) {
            TerminalHistorySheet(viewModel) { showHistory = false }
        }
        if (showSettings) {
            TerminalSettingsSheet(viewModel) { showSettings = false }
        }
    }
}

/** 兼容入口（与旧屏同签名 —— ApexRoot 路由零改动）。 */
@Composable
fun TerminalScreen(onBack: () -> Unit) {
    val viewModel: TerminalViewModel = hiltViewModel()
    TerminalScreen(viewModel, onBack)
}

// ═══════════════ 会话标签行（多对话）═══════════════

@Composable
private fun SessionTabsRow(
    viewModel: TerminalViewModel,
    sessions: List<SessionTab>,
    activeId: Long?,
    ubuntuReady: Boolean
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        sessions.forEach { tab ->
            val selected = tab.id == activeId
            val label = tab.title?.take(24)
                ?: stringResource(R.string.term_tab_ubuntu)
            val dead = !tab.isAlive
            AssistChip(
                onClick = { viewModel.selectSession(tab.id) },
                label = {
                    Text(
                        if (dead) "$label · ${stringResource(R.string.term_state_dead)}" else label,
                        color = if (selected) ConsoleTokens.onSurface else ConsoleTokens.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelMedium
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Outlined.Terminal,
                        contentDescription = null,
                        Modifier.size(16.dp),
                        tint = when {
                            dead -> ConsoleTokens.lineExitBad
                            tab.isUbuntu -> ConsoleTokens.amber
                            selected -> ConsoleTokens.primary
                            else -> ConsoleTokens.onSurfaceVariant
                        }
                    )
                },
                trailingIcon = {
                    IconButton(
                        onClick = { viewModel.closeSession(tab.id) },
                        modifier = Modifier.size(20.dp)
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            stringResource(R.string.term_close_session),
                            Modifier.size(14.dp),
                            tint = ConsoleTokens.onSurfaceVariant
                        )
                    }
                },
                shape = RoundedCornerShape(50)
            )
        }
        AssistChip(
            onClick = { viewModel.createSession(SessionRegistry.BACKEND_UBUNTU) },
            label = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.term_new_session_title), style = MaterialTheme.typography.labelMedium)
                }
            },
            shape = RoundedCornerShape(50)
        )
    }
}

// ═══════════════ 通知条 ═══════════════

@Composable
private fun NoticeBar(notice: TerminalViewModel.TerminalNotice?) {
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
                TerminalViewModel.TerminalNotice.Kind.ERROR -> ConsoleTokens.lineError
                TerminalViewModel.TerminalNotice.Kind.FALLBACK -> ConsoleTokens.amber
                TerminalViewModel.TerminalNotice.Kind.INFO -> ConsoleTokens.onSurfaceVariant
            },
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ═══════════════ 控制台主体 ═══════════════

@Composable
private fun ConsoleBody(
    viewModel: TerminalViewModel,
    activeId: Long?,
    fontSize: Int,
    modifier: Modifier
) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val ubuntuState by viewModel.ubuntuLifecycleState.collectAsStateWithLifecycle()
    val tab = sessions.firstOrNull { it.id == activeId }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = ConsoleTokens.surfaceContainerHigh)
    ) {
        Box(Modifier.fillMaxSize()) {
            when {
                tab == null -> EnvironmentInlinePanel(viewModel, ubuntuState)
                !tab.isAlive -> DeadSessionOverlay(viewModel, tab)
                else -> ConsoleLinesPane(viewModel, tab, fontSize)
            }
        }
    }
}

/** 输出行流 + 当前输入行（真实输出渲染核心）。 */
@Composable
private fun ConsoleLinesPane(viewModel: TerminalViewModel, tab: SessionTab, fontSize: Int) {
    val lines = viewModel.linesOf(tab.id)
    val running = viewModel.runningCommands[tab.id]
    val prompt = if (tab.isUbuntu) "#" else "$"
    val listState = rememberLazyListState()
    // 自动滚底：行数变化即滚到最后一项（当前输入/运行行）
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
    }
    val blinkAlpha = rememberBlinkAlpha()
    val mono = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = fontSize.sp,
        lineHeight = (fontSize * 1.55f).sp
    )

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp)) {
        SelectionContainer {
            LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                items(lines.size) { i -> ConsoleRow(lines[i], mono) }
                item {
                    if (running != null) {
                        // 运行指示行：琥珀色 + 省略号（真实输出流结束后被 EXIT 行取代）
                        Text("$prompt $running …", style = mono, color = ConsoleTokens.amber)
                    } else {
                        // 当前输入行 —— 与输入框共用 vm.input【输入同步】；
                        // 闪烁光标 ▌ 以同色 alpha 合成，500ms 周期。
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("$prompt ", style = mono, color = ConsoleTokens.lineCmdPrompt)
                            Text(viewModel.input, style = mono, color = ConsoleTokens.lineOut)
                            Text(
                                "▌",
                                style = mono,
                                color = ConsoleTokens.primary.copy(alpha = blinkAlpha.coerceIn(0.15f, 1f))
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 单行渲染：kind 决定色彩策略，CMD/OUT 走语法高亮。 */
@Composable
private fun ConsoleRow(line: ConsoleLine, mono: TextStyle) {
    when (line.kind) {
        ConsoleLine.LineKind.CMD -> Row(verticalAlignment = Alignment.Top) {
            Text("$ ", style = mono, color = ConsoleTokens.lineCmdPrompt)
            Text(
                TerminalSyntax.highlightCommand(line.text),
                style = mono.merge(TextStyle(color = ConsoleTokens.lineCmd))
            )
        }
        ConsoleLine.LineKind.OUT -> Text(
            TerminalSyntax.highlightOutput(line.text),
            style = mono.merge(TextStyle(color = ConsoleTokens.lineOut))
        )
        ConsoleLine.LineKind.EXIT_OK -> Text(
            line.text, style = mono.merge(TextStyle(color = ConsoleTokens.lineExitOk))
        )
        ConsoleLine.LineKind.EXIT_BAD -> Text(
            line.text, style = mono.merge(TextStyle(color = ConsoleTokens.lineExitBad))
        )
        ConsoleLine.LineKind.INFO -> Text(
            line.text, style = mono.merge(TextStyle(color = ConsoleTokens.lineInfo))
        )
        ConsoleLine.LineKind.ERROR -> Text(
            line.text, style = mono.merge(TextStyle(color = ConsoleTokens.lineError))
        )
    }
}

/** 闪烁光标 alpha（500ms 周期往返）。 */
@Composable
private fun rememberBlinkAlpha(): Float {
    val transition = rememberInfiniteTransition(label = "cursor")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.15f,
        animationSpec = infiniteRepeatable(tween(500)),
        label = "cursorAlpha"
    )
    return alpha
}

// ═══════════════ 环境内联面板（无会话时）═══════════════

@Composable
private fun EnvironmentInlinePanel(
    viewModel: TerminalViewModel,
    state: UbuntuLifecycleCoordinator.LifecycleState
) {
    val phase = state.phase
    val progress by viewModel.ubuntuProgress.collectAsStateWithLifecycle(initialValue = null)
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)
    ) {
        when (phase) {
            UbuntuLifecycleCoordinator.Phase.FAILED -> {
                Text(
                    stringResource(R.string.term_env_failed_title),
                    color = ConsoleTokens.lineError,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    state.lastError?.take(300) ?: "",
                    color = ConsoleTokens.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
                ActionRow {
                    AssistChip(onClick = { viewModel.installUbuntu() }, label = { Text(stringResource(R.string.term_retry)) })
                }
            }
            UbuntuLifecycleCoordinator.Phase.READY -> {
                Text(
                    stringResource(R.string.term_no_terminal_session),
                    color = ConsoleTokens.onSurface,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    stringResource(R.string.term_env_ready_hint),
                    color = ConsoleTokens.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                ActionRow {
                    FilledSessionButton(stringResource(R.string.term_new_ubuntu_session)) {
                        viewModel.createSession(SessionRegistry.BACKEND_UBUNTU)
                    }
                }
            }
            else -> {
                // 准备中：NOT_INSTALLED / INSTALLING / ROOTFS_READY / BOOTSTRAPPING / RECOVERING
                Text(
                    stringResource(R.string.term_env_prep_title),
                    color = ConsoleTokens.onSurface,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    stringResource(R.string.term_env_prep_desc),
                    color = ConsoleTokens.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                val step = setupStepIndex(progress?.stage)
                Text(
                    listOf(
                        stringResource(R.string.term_step_unpack),
                        stringResource(R.string.term_step_configure),
                        stringResource(R.string.term_step_toolchain)
                    ).getOrElse(step) { "" } + " · " + (progress?.message?.take(60) ?: stringResource(R.string.term_starting)),
                    color = ConsoleTokens.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
                LinearProgressIndicator(
                    progress = { (progress?.percent ?: 0) / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    stringResource(R.string.term_prep_footnote),
                    color = ConsoleTokens.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                ActionRow {
                    AssistChip(onClick = { viewModel.installUbuntu() }, label = { Text(stringResource(R.string.term_retry)) })
                }
            }
        }
    }
}

/** 进度阶段 → 步骤序号（与旧 EnvironmentPanel.setupStepIndex 同规则）。 */
private fun setupStepIndex(stage: String?): Int = when {
    stage == null -> 0
    stage.startsWith("bootstrap") -> 2
    stage.startsWith("install") && stage.substringAfter(':') in
        setOf("CONFIGURING", "ACTIVATING", "VALIDATING") -> 1
    else -> 0
}

@Composable
private fun ActionRow(content: @Composable () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) { content() }
}

@Composable
private fun FilledSessionButton(text: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = ConsoleTokens.primary,
        contentColor = ConsoleTokens.background
    ) {
        Text(
            text,
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            style = MaterialTheme.typography.labelLarge
        )
    }
}

// ═══════════════ 死会话覆盖层 ═══════════════

@Composable
private fun DeadSessionOverlay(viewModel: TerminalViewModel, tab: SessionTab) {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            stringResource(R.string.term_session_dead_title),
            color = ConsoleTokens.lineError,
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            stringResource(if (tab.isUbuntu) R.string.term_session_dead_ubuntu else R.string.term_session_dead_local),
            color = ConsoleTokens.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
        FilledSessionButton(stringResource(R.string.term_session_restart)) {
            viewModel.restartActiveSession()
        }
    }
}

// ═══════════════ 输入条 ═══════════════

@Composable
private fun InputBar(viewModel: TerminalViewModel) {
    val activeId by viewModel.activeSessionId.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val tab = sessions.firstOrNull { it.id == activeId }
    val running = activeId?.let { viewModel.runningCommands[it] }
    val canType = tab != null && tab.isAlive && running == null

    OutlinedTextField(
        value = viewModel.input,
        onValueChange = viewModel::onInputChange, // 【输入同步】唯一状态源
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp),
        enabled = canType,
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        prefix = { Text("$ ", fontFamily = FontFamily.Monospace, color = ConsoleTokens.primary) },
        placeholder = {
            Text(stringResource(R.string.term_cmd_hint), fontFamily = FontFamily.Monospace)
        },
        trailingIcon = {
            if (running != null) {
                // 停止（SIGINT —— 与真实终端 Ctrl-C 同义）
                FilledIconButton(onClick = { viewModel.stopActive() }, shape = RoundedCornerShape(50)) {
                    Text("■", color = ConsoleTokens.background, fontSize = 12.sp)
                }
            } else {
                FilledIconButton(
                    onClick = { viewModel.submit() },
                    enabled = canType && viewModel.input.isNotBlank(),
                    shape = RoundedCornerShape(50)
                ) {
                    Text("↵", color = ConsoleTokens.background, fontSize = 16.sp)
                }
            }
        },
        textStyle = TextStyle(fontFamily = FontFamily.Monospace),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { viewModel.submit() })
    )
}
