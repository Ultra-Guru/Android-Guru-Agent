package com.apex.agent.ui.screen.terminal

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apex.agent.R
import com.apex.agent.platform.terminal.ubuntu.lifecycle.UbuntuLifecycleCoordinator

/**
 * 环境面板（无会话时的终端主区）—— T95 从 TerminalScreen.kt 拆出：
 * TerminalScreen 因文件行数门禁（main ≤1200）触顶，而本面板是职责完整的
 * 独立体（三形态状态机：准备中 / 失败 / 就绪空态），沿既有 TerminalKeyToolbar /
 * SessionRegistry 的拆分惯例独立成文件。
 */
// ═══════════════════════ 环境面板（无会话时的主区）═══════════════════════

/**
 * 无会话时的主区面板 —— 三个形态：
 *  - **准备中**（NOT_INSTALLED/INSTALLING/ROOTFS_READY/BOOTSTRAPPING/RECOVERING）：
 *    三步进度（解压内置环境 → 配置系统 → 初始化工具链）+ 百分比/字节 + 当前阶段消息；
 *    T85 自动预备语义：App 启动即后台进行，用户无需任何操作，完成后自动进终端；
 *  - **失败**（FAILED）：错误详情 + 重试 + 环境中心；
 *  - **就绪空态**（READY，会话被用户全部关闭）：快捷新建入口。
 */
@Composable
internal fun EnvironmentPanel(
    phase: UbuntuLifecycleCoordinator.Phase,
    progress: UbuntuLifecycleCoordinator.LifecycleProgress?,
    lastError: String?,
    onRetry: () -> Unit,
    onNewUbuntu: () -> Unit,
    onNewLocal: () -> Unit,
    onOpenCenter: () -> Unit
) {
    // M3：verticalScroll + Arrangement.Center 是 Compose 已知坑组合 —— 内容
    // 超视口时 Arrangement 对齐失效/顶部被裁且滚不回去（横屏、小屏、大字号
    // 下本面板内容 ≈450dp+ 必现）。改为外层 Box 居中 + 内层滚动列：
    // 内容矮于视口 → 整体垂直居中；高于视口 → 从顶部开始完整滚动、绝不裁头。
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (phase) {
                UbuntuLifecycleCoordinator.Phase.FAILED -> EnvironmentFailureContent(
                    lastError = lastError,
                    onRetry = onRetry,
                    onOpenCenter = onOpenCenter
                )
                UbuntuLifecycleCoordinator.Phase.READY -> EnvironmentReadyEmptyContent(
                    onNewUbuntu = onNewUbuntu,
                    onNewLocal = onNewLocal
                )
                else -> EnvironmentPreparingContent(
                    phase = phase,
                    progress = progress,
                    onNewLocal = onNewLocal,
                    onOpenCenter = onOpenCenter
                )
            }
        }
    }
}

/** 环境准备步骤序号：0 解压内置档案 → 1 配置系统 → 2 初始化工具链。 */
private fun setupStepIndex(stage: String?): Int = when {
    stage == null -> 0
    stage.startsWith("install:CONFIGURING") ||
        stage.startsWith("install:ACTIVATING") ||
        stage.startsWith("install:VALIDATING") -> 1
    stage.startsWith("bootstrap:") -> 2
    else -> 0 // install:RESOLVING/DOWNLOADING/VERIFYING/EXTRACTING
}

@Composable
private fun EnvironmentPreparingContent(
    phase: UbuntuLifecycleCoordinator.Phase,
    progress: UbuntuLifecycleCoordinator.LifecycleProgress?,
    onNewLocal: () -> Unit,
    onOpenCenter: () -> Unit
) {
    // ── 顶部图标：呼吸光晕的终端符号 ──
    val transition = rememberInfiniteTransition(label = "env-glow")
    val glow by transition.animateFloat(
        initialValue = 0.55f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        label = "env-glow-alpha"
    )
    Box(contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(84.dp)
                .background(ConsoleTheme.accent.copy(alpha = 0.14f * glow), CircleShape)
        )
        Surface(
            shape = CircleShape,
            color = ConsoleTheme.accentSoft,
            border = androidx.compose.foundation.BorderStroke(1.dp, ConsoleTheme.accent.copy(alpha = 0.35f))
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(64.dp)) {
                Icon(
                    Icons.Default.Terminal, contentDescription = null,
                    tint = ConsoleTheme.accent,
                    modifier = Modifier.size(30.dp)
                )
            }
        }
    }
    Spacer(Modifier.height(22.dp))
    Text(
        stringResource(R.string.term_env_prep_title),
        fontSize = 19.sp,
        fontWeight = FontWeight.Bold,
        color = ConsoleTheme.text
    )
    Spacer(Modifier.height(6.dp))
    Text(
        stringResource(R.string.term_env_prep_desc),
        fontSize = 12.sp,
        color = ConsoleTheme.dim,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(28.dp))

    // ── 三步进度 ──
    val currentStep = setupStepIndex(progress?.stage)
    val steps = listOf(
        stringResource(R.string.term_step_unpack),
        stringResource(R.string.term_step_configure),
        stringResource(R.string.term_step_toolchain)
    )
    steps.forEachIndexed { index, label ->
        SetupStepRow(
            label = label,
            state = when {
                index < currentStep -> SetupStepState.DONE
                index == currentStep -> SetupStepState.ACTIVE
                else -> SetupStepState.PENDING
            }
        )
    }
    Spacer(Modifier.height(26.dp))

    // ── 进度条 + 百分比 / 字节 ──
    val percent = progress?.percent ?: 0
    val bytes = progress?.bytesTransferred ?: 0L
    val bytesTotal = progress?.bytesTotal
    val determinate = percent > 0 || (bytesTotal != null && bytesTotal > 0L)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(ConsoleTheme.bar)
            .border(androidx.compose.foundation.BorderStroke(1.dp, ConsoleTheme.stroke), RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    progress == null -> stringResource(R.string.term_starting)
                    else -> progress.message.ifBlank { progress.stage }
                },
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = ConsoleTheme.dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (determinate) {
                Text(
                    "$percent%",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    color = ConsoleTheme.accent
                )
            }
        }
        if (determinate) {
            LinearProgressIndicator(
                progress = { (percent.coerceIn(0, 100)) / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = ConsoleTheme.accent,
                trackColor = ConsoleTheme.accentSoft
            )
            if (bytesTotal != null && bytesTotal > 0L) {
                Text(
                    "${formatMb(bytes)} / ${formatMb(bytesTotal)}",
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = ConsoleTheme.dim
                )
            }
        } else {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = ConsoleTheme.accent,
                trackColor = ConsoleTheme.accentSoft
            )
        }
        Text(
            stringResource(R.string.term_prep_footnote),
            fontSize = 10.5.sp,
            color = ConsoleTheme.dim
        )
    }
    Spacer(Modifier.height(18.dp))

    // ── 次级入口：不等环境，先用 Android Shell / 环境中心（纵向堆叠，
    //    旧版两个横排 TextButton 在窄屏溢出）──
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = onNewLocal) {
            Icon(Icons.Default.Android, null, Modifier.size(15.dp), tint = ConsoleTheme.dim)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.term_use_android_first), color = ConsoleTheme.dim, fontSize = 12.sp)
        }
        TextButton(onClick = onOpenCenter) {
            Icon(Icons.Default.Layers, null, Modifier.size(15.dp), tint = ConsoleTheme.dim)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.term_env_center), color = ConsoleTheme.dim, fontSize = 12.sp)
        }
    }
}

private enum class SetupStepState { DONE, ACTIVE, PENDING }

@Composable
private fun SetupStepRow(label: String, state: SetupStepState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when (state) {
            SetupStepState.DONE -> Icon(
                Icons.Default.CheckCircle, contentDescription = null,
                tint = ConsoleTheme.accent, modifier = Modifier.size(18.dp)
            )
            SetupStepState.ACTIVE -> CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 1.8.dp,
                color = ConsoleTheme.accent
            )
            SetupStepState.PENDING -> Box(
                Modifier
                    .size(16.dp)
                    .background(ConsoleTheme.stroke, CircleShape)
            )
        }
        Text(
            label,
            fontSize = 13.sp,
            color = when (state) {
                SetupStepState.DONE -> ConsoleTheme.text
                SetupStepState.ACTIVE -> ConsoleTheme.accent
                SetupStepState.PENDING -> ConsoleTheme.dim
            },
            fontWeight = if (state == SetupStepState.ACTIVE) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun EnvironmentFailureContent(
    lastError: String?,
    onRetry: () -> Unit,
    onOpenCenter: () -> Unit
) {
    Icon(
        Icons.Default.Warning, contentDescription = null,
        tint = ConsoleTheme.danger, modifier = Modifier.size(56.dp)
    )
    Spacer(Modifier.height(18.dp))
    Text(stringResource(R.string.term_env_failed_title), fontSize = 19.sp, fontWeight = FontWeight.Bold, color = ConsoleTheme.text)
    Spacer(Modifier.height(8.dp))
    if (!lastError.isNullOrBlank()) {
        Text(
            lastError.take(300),
            fontSize = 10.5.sp,
            fontFamily = FontFamily.Monospace,
            color = ConsoleTheme.dim,
            textAlign = TextAlign.Center,
            maxLines = 6,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(10.dp))
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = ConsoleTheme.dangerSoft,
        modifier = Modifier.clip(RoundedCornerShape(12.dp))
    ) {
        Row(
            Modifier
                .clickable(onClick = onRetry)
                .padding(horizontal = 26.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null, tint = ConsoleTheme.danger, modifier = Modifier.size(17.dp))
            Text(stringResource(R.string.term_retry), color = ConsoleTheme.danger, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
    }
    Spacer(Modifier.height(14.dp))
    TextButton(onClick = onOpenCenter) {
        Text(stringResource(R.string.term_open_env_center_diag), color = ConsoleTheme.dim, fontSize = 12.sp)
    }
}

@Composable
private fun EnvironmentReadyEmptyContent(
    onNewUbuntu: () -> Unit,
    onNewLocal: () -> Unit
) {
    Icon(
        Icons.Default.Terminal, contentDescription = null,
        tint = ConsoleTheme.accent, modifier = Modifier.size(52.dp)
    )
    Spacer(Modifier.height(18.dp))
    Text(stringResource(R.string.term_no_terminal_session), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ConsoleTheme.text)
    Spacer(Modifier.height(6.dp))
    Text(
        stringResource(R.string.term_env_ready_hint),
        fontSize = 12.sp,
        color = ConsoleTheme.dim,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(24.dp))
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = ConsoleTheme.accentSoft,
        border = androidx.compose.foundation.BorderStroke(1.dp, ConsoleTheme.accent.copy(alpha = 0.4f)),
        modifier = Modifier.clip(RoundedCornerShape(12.dp))
    ) {
        Row(
            Modifier
                .clickable(onClick = onNewUbuntu)
                .padding(horizontal = 30.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Icon(Icons.Default.Terminal, contentDescription = null, tint = ConsoleTheme.accent, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.term_new_ubuntu_session), color = ConsoleTheme.accent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
    }
    Spacer(Modifier.height(12.dp))
    TextButton(onClick = onNewLocal) {
        Icon(Icons.Default.Android, null, Modifier.size(15.dp), tint = ConsoleTheme.dim)
        Spacer(Modifier.width(6.dp))
        Text("Android Shell", color = ConsoleTheme.dim, fontSize = 12.sp)
    }
}

/** 字节 → MB 文本（1 位小数）。 */
private fun formatMb(bytes: Long): String =
    String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
