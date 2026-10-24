package com.apex.agent.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * ═══════════════════════════════════════════════════════════════
 *  AccentColors —— 强调色单一事实源（#278 四套颜色体系统一）
 * ═══════════════════════════════════════════════════════════════
 *
 * 背景：强调色设置（设置中心「预设配色」/ Dynamic Color）此前只覆盖
 * MaterialTheme —— 终端 ConsoleTheme 硬编码 mint、浏览器 ChromePalette
 * 自成一套、玻璃 GlassPalette 虽已派生但无统一契约，四处取色并存：
 * 用户改强调色后 Material 组件变了，终端控制台 / 浏览器 chrome 仍是旧色。
 *
 * 统一原则：**MaterialTheme 的强调色族是唯一事实源**。[ApexTheme] 组装
 * colorScheme 的同时，把同一来源的强调色族经 [LocalAccentColors] 下发；
 * 各专项体系从它派生，不再自持 hex：
 *  - MaterialTheme：预设配色 / Dynamic Color / 极简灰阶三路照旧；
 *  - ConsoleTheme（终端恒深控制台）：取 [AccentColors.dark]（近黑底
 *    发光变体）—— 控制台不随 App 深浅翻转，但强调色族跟随设置；
 *    警示 / 危险是状态语义色，不随强调色漂移（「极简不等于色盲」纪律）；
 *  - GlassPalette：组合期直接读 MaterialTheme.colorScheme 派生（既有
 *    行为，天然跟随，无需经本 Local）；
 *  - 浏览器 ChromePalette（apex-kit-browser 外部库）：库侧尚无注入口，
 *    见 #278 遗留项 —— 需要库暴露 host accent 钩子后接线。
 *
 * 双变体说明：同一方案在深浅两种基底上各有调色（深色 = 霓虹提亮、
 * 浅色 = 可读深色，见 ThemePalettes 设计原则 2）。恒深表面（终端）与
 * 随主题表面需要的变体不同，故 [AccentColors] 同时携带 [dark] / [light]
 * 两组，消费端按自己的表面语义取用，禁止各查一遍深浅再猜。
 */

/**
 * 一套配色方案的强调色组（primary / secondary / tertiary 全量 12 色）。
 * 不含中性色 —— 中性基底由 ThemePalettes.kt 的 Dark / LightNeutralBase 提供。
 *
 * #278：本类型即强调色单一事实源的**单变体**（深 / 浅各一），同时是
 * [accentColorScheme]（MaterialTheme）与 [accentColors]（各专项体系经
 * [LocalAccentColors] 消费）的共同来源 —— 两路输出同源，不齐即 bug
 * （契约测试 AccentColorsContractTest 锁死）。
 */
@Immutable
data class AccentTone(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiary: Color,
    val onTertiary: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color
)

/**
 * 当前强调色族的深浅双态。由 [ApexTheme] 按实际生效来源（预设配色 /
 * Dynamic Color / 极简灰阶）合成 —— 语义与同时刻的 MaterialTheme
 * colorScheme 强调槽位一一对应（预设路径二者同出自 accentSetFor，
 * 契约由 AccentColorsContractTest 锁死）。
 */
@Immutable
data class AccentColors(
    /** 恒深色语境（近黑底发光）—— 终端控制台等不随 App 深浅翻转的表面。 */
    val dark: AccentTone,
    /** 浅色语境（白底可读）—— 随 App 浅色主题的表面。 */
    val light: AccentTone
)

/**
 * 当前强调色族（由 ApexTheme 注入；未注入时兜底 MINT —— 与旧终端
 * chrome 硬编码观感一致，预览 / 未包主题的测试不漂移）。
 */
val LocalAccentColors = staticCompositionLocalOf { accentColors(AccentPalette.MINT) }

/** 从既有 ColorScheme 抽取强调色族 —— Dynamic Color（壁纸取色）路径的接入口。 */
fun ColorScheme.toAccentTone(): AccentTone = AccentTone(
    primary = primary,
    onPrimary = onPrimary,
    primaryContainer = primaryContainer,
    onPrimaryContainer = onPrimaryContainer,
    secondary = secondary,
    onSecondary = onSecondary,
    secondaryContainer = secondaryContainer,
    onSecondaryContainer = onSecondaryContainer,
    tertiary = tertiary,
    onTertiary = onTertiary,
    tertiaryContainer = tertiaryContainer,
    onTertiaryContainer = onTertiaryContainer
)
