package com.apex.agent.ui.screen.terminal

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import java.util.Locale

/**
 * Ubuntu 环境页视觉基座（T97 —— 自 T96 终端控制台主题裁剪）。
 *
 * 控制台行模型（ConsoleLine）与语法高亮色板已随终端 UI 退役（Ubuntu 归
 * Agent 全权掌控）；保留：
 *  - [ConsoleTokens] GitHub Dark 令牌（恒暗 —— 环境语义要求白天模式也是深色
 *    面板，与旧终端恒暗材质同一决策；禁用靛蓝系）；
 *  - [TerminalConsoleTheme] M3 暗色 MaterialTheme 包装；
 *  - [formatBytes] 磁盘用量格式化（StorageScreen 跨屏复用，保持历史导入
 *    路径 `com.apex.agent.ui.screen.terminal.formatBytes` 不变）。
 */
internal object ConsoleTokens {

    // ── GitHub Dark 表面系 ──
    val background = Color(0xFF0D1117)
    val surface = Color(0xFF0D1117)
    val surfaceContainerHigh = Color(0xFF161B22)
    val surfaceContainer = Color(0xFF12181F)
    val outline = Color(0xFF30363D)
    val outlineVariant = Color(0xFF21262D)

    // ── 文字系 ──
    val onSurface = Color(0xFFE6EDF3)
    val onSurfaceVariant = Color(0xFF8B949E)
    val primary = Color(0xFF7EE787)
    val onErrorContainer = Color(0xFFFF7B72)
    val amber = Color(0xFFFFB454)

    // ── 语义色（环境卡/状态徽章/通知条消费）──
    val lineExitOk = Color(0xFF7EE787)    // 已装徽章 / READY 徽章
    val lineInfo = Color(0xFF8B949E)      // 能力清单
    val lineError = Color(0xFFFF7B72)     // 失败根因 / 错误通知
}

/**
 * M3 恒暗主题包装 —— Ubuntu 环境域内所有 Composable 必须包裹在本主题内，
 * 保证暗色令牌不随 App 亮色主题泄漏。
 *
 * 恒暗是有意决策（不读 isSystemInDarkTheme）：白天模式下环境页也是深色面板。
 */
@Composable
internal fun TerminalConsoleTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = ConsoleTokens.primary,
            onPrimary = Color(0xFF0D1117),
            primaryContainer = ConsoleTokens.surfaceContainerHigh,
            onPrimaryContainer = ConsoleTokens.onSurface,
            secondary = ConsoleTokens.onSurfaceVariant,
            onSecondary = Color(0xFF0D1117),
            secondaryContainer = ConsoleTokens.surfaceContainer,
            onSecondaryContainer = ConsoleTokens.onSurface,
            tertiary = ConsoleTokens.amber,
            onTertiary = Color(0xFF0D1117),
            tertiaryContainer = ConsoleTokens.surfaceContainer,
            onTertiaryContainer = ConsoleTokens.amber,
            background = ConsoleTokens.background,
            onBackground = ConsoleTokens.onSurface,
            surface = ConsoleTokens.surface,
            onSurface = ConsoleTokens.onSurface,
            surfaceVariant = ConsoleTokens.surfaceContainerHigh,
            onSurfaceVariant = ConsoleTokens.onSurfaceVariant,
            error = ConsoleTokens.onErrorContainer,
            onError = Color(0xFF0D1117),
            errorContainer = Color(0xFF2D1B1E),
            onErrorContainer = ConsoleTokens.onErrorContainer,
            outline = ConsoleTokens.outline,
            outlineVariant = ConsoleTokens.outlineVariant
        ),
        content = content
    )
}

/**
 * 磁盘用量格式化（StorageScreen 以 `com.apex.agent.ui.screen.terminal.formatBytes`
 * 路径导入 —— 本函数保持包级可见性，StorageScreen 零改动）。
 */
internal fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.2f GB", gb)
}
