package com.apex.agent.ui.screen.terminal

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import java.util.Locale

/**
 * 终端控制台视觉基座（T96 —— 终端大重构）。
 *
 * 本文件是整个终端包的共享契约层：
 *  - [ConsoleTokens] GitHub Dark 令牌（恒暗 —— 终端语义要求白天模式也是深色
 *    面板，与旧 TerminalGlass 的恒暗材质同一决策；禁用靛蓝系）；
 *  - [TerminalConsoleTheme] M3 暗色 MaterialTheme 包装 + 等宽排版；
 *  - [ConsoleLine] 控制台行模型 —— 引擎层与 UI 层的唯一行契约；
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

    // ── 行类型色（ConsoleLine.kind → 前景色）──
    val lineCmd = Color(0xFF7EE787)       // 提交行：命令主体
    val lineCmdPrompt = Color(0xFF8B949E) // 提交行：提示符部分
    val lineOut = Color(0xFFE6EDF3)       // 真实输出
    val lineExitOk = Color(0xFF7EE787)    // exit 0
    val lineExitBad = Color(0xFFFF7B72)   // exit 非 0 / 会话中断
    val lineInfo = Color(0xFF8B949E)      // 系统提示（降级/恢复等）
    val lineError = Color(0xFFFF7B72)     // 执行失败

    // ── 语法高亮色板（TerminalSyntax 消费）──
    val syntaxKeyword = Color(0xFFFF7B72)
    val syntaxString = Color(0xFFA5D6FF)
    val syntaxNumber = Color(0xFF79C0FF)
    val syntaxPath = Color(0xFF7EE787)
    val syntaxOperator = Color(0xFFFF7B72)
    val syntaxFlag = Color(0xFFFFB454)
    val syntaxPlain = Color(0xFFE6EDF3)
    val syntaxDim = Color(0xFF8B949E)
}

/**
 * 控制台行模型 —— 引擎（RealCommandExecutor / VM 提交管线）与 UI
 * （LazyColumn 渲染）之间的唯一行契约。
 *
 * [kind] 决定前景色与渲染方式；[text] 为已清洗的纯文本（无 ANSI/OSC 控制
 * 序列 —— 剥离发生在 RawStreamSanitizer）。
 */
data class ConsoleLine(
    val kind: LineKind,
    val text: String
) {
    enum class LineKind { CMD, OUT, EXIT_OK, EXIT_BAD, INFO, ERROR }
}

/**
 * M3 恒暗主题包装 —— 终端域内所有 Composable（屏体 + 三个 Sheet + 对话框）
 * 必须包裹在本主题内，保证暗色令牌不随 App 亮色主题泄漏。
 *
 * 排版：等宽 Monospace 13sp / 行高 20sp（控制台正文与代码高亮的统一基线）。
 */
@Composable
internal fun TerminalConsoleTheme(content: @Composable () -> Unit) {
    // 恒暗是有意决策（不读 isSystemInDarkTheme）：白天模式下终端也是深色面板，
    // 与旧 TerminalGlass 恒暗材质、TerminalView 深色配色同一语义。
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
 * 磁盘用量格式化（T96 转移自旧 EnvironmentCenterSheet，原实现语义不变）。
 *
 * StorageScreen 以 `com.apex.agent.ui.screen.terminal.formatBytes` 路径导入 ——
 * 本函数保持包级可见性，StorageScreen 零改动。
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
