package com.apex.agent.feature.terminalm3

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * ═══════════════════════════════════════════════════════════════
 *  Material 3 Terminal —— 主题（GitHub Dark 恒暗配色 + 等宽字体族）
 * ═══════════════════════════════════════════════════════════════
 *
 * 设计口径：
 *  - **恒暗色**：终端语义天然暗色（代码/CLI 场景无需亮色变体）—— 不接
 *    isSystemInDarkTheme，直接给固定 darkColorScheme，杜绝「系统亮色把
 *    终端卡片洗白导致对比度崩塌」；
 *  - **配色板**：GitHub Dark 官方口径 —— 背景 #0D1117、卡片面 #161B22、
 *    主前景 #E6EDF3、弱化文本 #8B949E、描边 #30363D、成功/主色 #7EE787、
 *    错误 #FF7B72；
 *  - **代码高亮板**：命令行 token 级着色（prompt 绿 / 命令名亮白加粗 /
 *    旗标与引号串橙 / 普通参数弱灰），见 [TerminalSyntax]；
 *  - **字体**：全站 FontFamily.Monospace，终端正文 13sp / 行高 20sp
 *    （GitHub 代码视图同款密度）。
 */
private val TerminalBackground = Color(0xFF0D1117)
private val TerminalSurfaceCard = Color(0xFF161B22)
private val TerminalSurfaceRaised = Color(0xFF21262D)
private val TerminalOnSurface = Color(0xFFE6EDF3)
private val TerminalOnSurfaceVariant = Color(0xFF8B949E)
private val TerminalOutline = Color(0xFF30363D)
private val TerminalGreen = Color(0xFF7EE787)
private val TerminalError = Color(0xFFFF7B72)
private val TerminalAmber = Color(0xFFFFA657)

/**
 * 代码高亮用色板（[TerminalScreen] 的 buildAnnotatedString 消费）。
 * 与 M3 colorScheme 分离声明：语法高亮是「文本语义」而非「组件语义」，
 * 不随组件颜色 API 演进耦合。
 */
object TerminalSyntax {
    /** 提示符 "$" 与成功 ✓ —— GitHub Dark 绿。 */
    val prompt: Color = TerminalGreen

    /** 命令名（首 token）—— 亮白加粗。 */
    val command: Color = TerminalOnSurface

    /** 旗标（-x / --long）与引号字符串 —— GitHub Dark 橙。 */
    val flag: Color = TerminalAmber

    /** 普通参数 / 弱化提示行 —— GitHub Dark 灰。 */
    val muted: Color = TerminalOnSurfaceVariant

    /** 常规输出 —— 亮白。 */
    val output: Color = TerminalOnSurface

    /** 错误 / 未知命令 —— GitHub Dark 红。 */
    val error: Color = TerminalError
}

/**
 * 终端专属 M3 暗色方案。surfaceContainer 族给足三级（卡片 161B22 /
 * 按压态 21262D），让 FilledIconButton 等 M3 组件在恒暗背景下层级分明。
 */
private val M3TerminalColorScheme = darkColorScheme(
    primary = TerminalGreen,
    onPrimary = TerminalBackground,
    primaryContainer = TerminalSurfaceCard,
    onPrimaryContainer = TerminalGreen,
    secondary = TerminalOnSurfaceVariant,
    onSecondary = TerminalBackground,
    secondaryContainer = TerminalSurfaceCard,
    onSecondaryContainer = TerminalOnSurface,
    tertiary = TerminalAmber,
    onTertiary = TerminalBackground,
    tertiaryContainer = TerminalSurfaceCard,
    onTertiaryContainer = TerminalAmber,
    background = TerminalBackground,
    onBackground = TerminalOnSurface,
    surface = TerminalBackground,
    onSurface = TerminalOnSurface,
    surfaceVariant = TerminalSurfaceCard,
    onSurfaceVariant = TerminalOnSurfaceVariant,
    surfaceContainer = TerminalBackground,
    surfaceContainerHigh = TerminalSurfaceCard,
    surfaceContainerHighest = TerminalSurfaceRaised,
    surfaceContainerLow = TerminalBackground,
    surfaceContainerLowest = TerminalBackground,
    outline = TerminalOutline,
    outlineVariant = TerminalSurfaceRaised,
    error = TerminalError,
    onError = TerminalBackground,
    errorContainer = TerminalSurfaceCard,
    onErrorContainer = TerminalError,
)

/**
 * 等宽字体族（终端正文 13sp / 20sp；标题与辅助层级同族放大缩小，
 * 保持整页代码感一致）。
 */
private val TerminalTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 20.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 16.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 20.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
    ),
)

/**
 * M³ 终端主题入口 —— 页内所有组件（TopAppBar / OutlinedTextField /
 * Card / LazyColumn 文本）统一取该 colorScheme + typography。
 */
@Composable
fun M3TerminalTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = M3TerminalColorScheme,
        typography = TerminalTypography,
        content = content,
    )
}
