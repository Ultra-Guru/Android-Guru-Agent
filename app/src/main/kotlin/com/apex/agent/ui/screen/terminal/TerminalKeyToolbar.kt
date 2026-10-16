package com.apex.agent.ui.screen.terminal

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.apex.agent.R
import com.apex.agent.platform.terminal.io.TerminalKey
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 触屏辅助键行（T89 重做 —— 「一坨按钮」治理）。
 *
 * 旧版是单行 ~45 键的横向长滚（11 主簇 + 12 符号 + 5 控制码 + 4 导航 +
 * 12 F 键 + 粘贴），加上扩展宏行默认 8 键 —— 键区泛滥成两行滚动条，被用户
 * 直接吐槽。新版结构（Termux extra-keys 心智模型）：
 *
 *  - **主行（恒显，可横滚 + 渐隐提示）**：拉键盘 / 退格 / ESC / TAB /
 *    CTRL·SHIFT·ALT 锁存 / 方向键 / 粘贴 / FN 展开钮 —— 覆盖 99% 的触屏
 *    终端操作（窄屏溢出侧渐隐明示可滚，见 [FadeScrollableKeyRow]）；
 *  - **FN 行（点 FN 展开，可横滚）**：控制码（^C ^D ^Z ^L ^U）+ 导航
 *    （HOME/END/PGUP/PGDN）+ F1-F12 —— 低频键折叠，不再常驻吃屏；
 *  - 符号键删除：软键盘自带完整符号面板，与 IME 重复的 12 个符号键纯属噪音。
 *
 * 触控目标 36dp 高（Material 无障碍阈值）；锁存键高亮为 mint 实底深字，
 * SHIFT/ALT 一次性（随下一个特殊键发出即释放）。
 *
 * P2-2：函数与文件统一命名 TerminalKeyToolbar（旧版文件叫
 * TerminalKeyToolbar.kt、函数却叫 KeyToolbar —— 前缀不一致重构时容易看漏）。
 */
@Composable
internal fun TerminalKeyToolbar(
    ctrlActive: Boolean,
    onCtrlToggle: () -> Unit,
    shiftActive: Boolean,
    onShiftToggle: () -> Unit,
    altActive: Boolean,
    onAltToggle: () -> Unit,
    onText: (String) -> Unit,
    onKey: (TerminalKey) -> Unit,
    onControl: (Char) -> Unit,
    onShowKeyboard: () -> Unit,
    onPaste: () -> Unit
) {
    var fnExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(KeybarChrome.bg)
            .animateContentSize()
    ) {
        // ── 主行（T90 可横滚；T95 双修正：①键帽收紧 —— padding 11→8dp /
        //    12sp / 单字符键 32dp 最小触控宽，ESC→ALT 主簇在 360dp 窄屏全量
        //    可见；②两端渐隐遮罩明示「还有键可滚」，取代旧版裸裁切 ——
        //    用户看到「AL…」被屏幕边缘切一半，第一观感是键区坏了）──
        FadeScrollableKeyRow {
            // 显式拉起输入法：触屏上"点一下没反应"的兜底入口
            ToolbarKey("⌨", emphasized = true) { onShowKeyboard() }
            // 退格：IME 桥的组合区差分已覆盖多数场景，但触屏仍需确定可用的删除键。
            // T92：长按连发 —— 按住删整段、按住方向键连续导航，不用狂点。
            ToolbarKey("⌫", emphasized = true, repeatOnHold = true) { onKey(TerminalKey.BACKSPACE) }
            ToolbarKey("ESC") { onKey(TerminalKey.ESC) }
            ToolbarKey("TAB") { onKey(TerminalKey.TAB) }
            ToolbarKey("CTRL", highlighted = ctrlActive, onClick = onCtrlToggle)
            // SHIFT/ALT 锁存（一次性 —— 与下一个方向/导航/F 键组合后自动释放）。
            // SHIFT+方向 = vim 可视选择 / readline 选区；ALT+B/F = 词跳。
            ToolbarKey("SHIFT", highlighted = shiftActive, onClick = onShiftToggle)
            ToolbarKey("ALT", highlighted = altActive, onClick = onAltToggle)
            ToolbarKey("←", repeatOnHold = true) { onKey(TerminalKey.ARROW_LEFT) }
            ToolbarKey("↑", repeatOnHold = true) { onKey(TerminalKey.ARROW_UP) }
            ToolbarKey("↓", repeatOnHold = true) { onKey(TerminalKey.ARROW_DOWN) }
            ToolbarKey("→", repeatOnHold = true) { onKey(TerminalKey.ARROW_RIGHT) }
            ToolbarKey(stringResource(R.string.term_paste), emphasized = true) { onPaste() }
            ToolbarKey(
                label = "FN",
                highlighted = fnExpanded,
                onClick = { fnExpanded = !fnExpanded }
            )
        }

        // ── FN 行（低频键折叠；展开后可横滚）──
        if (fnExpanded) {
            FadeScrollableKeyRow(verticalPadding = 0.dp) {
                // 控制码：^C 中断 / ^D EOF / ^Z 挂起 / ^L 清屏 / ^U 清行
                ToolbarKey("^C") { onControl('c') }
                ToolbarKey("^D") { onControl('d') }
                ToolbarKey("^Z") { onControl('z') }
                ToolbarKey("^L") { onControl('l') }
                ToolbarKey("^U") { onControl('u') }
                // 导航：htop 帮助、vim 命令模式、mc 菜单
                ToolbarKey("HOME", repeatOnHold = true) { onKey(TerminalKey.HOME) }
                ToolbarKey("END", repeatOnHold = true) { onKey(TerminalKey.END) }
                ToolbarKey("PGUP", repeatOnHold = true) { onKey(TerminalKey.PAGE_UP) }
                ToolbarKey("PGDN", repeatOnHold = true) { onKey(TerminalKey.PAGE_DOWN) }
                // F 键
                ToolbarKey("F1") { onKey(TerminalKey.F1) }
                ToolbarKey("F2") { onKey(TerminalKey.F2) }
                ToolbarKey("F3") { onKey(TerminalKey.F3) }
                ToolbarKey("F4") { onKey(TerminalKey.F4) }
                ToolbarKey("F5") { onKey(TerminalKey.F5) }
                ToolbarKey("F6") { onKey(TerminalKey.F6) }
                ToolbarKey("F7") { onKey(TerminalKey.F7) }
                ToolbarKey("F8") { onKey(TerminalKey.F8) }
                ToolbarKey("F9") { onKey(TerminalKey.F9) }
                ToolbarKey("F10") { onKey(TerminalKey.F10) }
                ToolbarKey("F11") { onKey(TerminalKey.F11) }
                ToolbarKey("F12") { onKey(TerminalKey.F12) }
            }
        }
    }
}

@Composable
private fun ToolbarKey(
    label: String,
    highlighted: Boolean = false,
    emphasized: Boolean = false,
    /** T92：长按连发（初始 400ms 延迟 + 60ms 周期，Termux extra-keys auto-repeat
     *  同款节奏）。仅退格/方向/导航类幂等键启用 —— 锁存键/粘贴/FN 不适用。 */
    repeatOnHold: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .height(36.dp)
            // T95：单字符键（⌨/⌫/方向）收 32dp 最小触控宽（旧版纯内容宽
            // ≈ 23dp，低于可用触控下限）；长标签键不受影响（按内容自适应）
            .widthIn(min = 32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    highlighted -> KeybarChrome.keyHi
                    emphasized -> KeybarChrome.keyEmph
                    else -> KeybarChrome.key
                }
            )
            .let { base ->
                if (repeatOnHold) base.keyRepeatModifier(label, onClick)
                else base.clickable(onClick = onClick)
            }
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = if (highlighted) KeybarChrome.keyTextHi else KeybarChrome.keyText
        )
    }
}

/**
 * 横滚键行 + 两端渐隐遮罩（T95：可滚行没有视觉提示 = 「按键被截断坏了」）。
 *
 * 键行本身 [horizontalScroll]（宽屏全部可见无滚动），窄屏溢出时在溢出方向
 * 绘一条 → 键栏底色的 18% 宽渐隐带，明示「这行能滚」。遮罩 Box 不装任何
 * pointerInput，不拦截下方键区的触摸/滚动。
 */
@Composable
private fun FadeScrollableKeyRow(
    /** 上下内边距（主行 5dp；FN 行紧贴主行 → 0 仅留底部 5dp）。 */
    verticalPadding: Dp = 5.dp,
    content: @Composable RowScope.() -> Unit
) {
    val scroll = rememberScrollState()
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scroll)
                .padding(horizontal = 5.dp)
                .let { m ->
                    if (verticalPadding > 0.dp) m.padding(vertical = verticalPadding)
                    else m.padding(bottom = 5.dp)
                },
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
        if (scroll.value > 0) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.horizontalGradient(
                            0f to KeybarChrome.bg,
                            0.18f to Color.Transparent
                        )
                    )
            )
        }
        if (scroll.canScrollForward) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.horizontalGradient(
                            0.82f to Color.Transparent,
                            1f to KeybarChrome.bg
                        )
                    )
            )
        }
    }
}

/**
 * T92：长按连发手势（纯 pointerInput 实现，无自定义协程作用域）。
 *
 * 语义：按下立即触发一次；按住 400ms 后以 60ms 周期连发；抬指/取消即停。
 * 快速点按 = 恰好一次（按下即触发，与 clickable 的 tap 语义一致）。
 *
 * 连发调度用**绝对时间戳**（nanoTime）而非逐事件超时重置 —— 按住期间的手指
 * 抖动事件（~16ms 间隔 < 周期）不会饿死连发计时。
 */
private fun Modifier.keyRepeatModifier(label: String, onClick: () -> Unit): Modifier =
    this.then(
        Modifier.pointerInput(label) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                onClick()
                val initialDelayNs = REPEAT_INITIAL_DELAY_MS * 1_000_000L
                val periodNs = REPEAT_PERIOD_MS * 1_000_000L
                var nextFireAt = System.nanoTime() + initialDelayNs
                while (true) {
                    val now = System.nanoTime()
                    val waitMs = ((nextFireAt - now).coerceAtLeast(0L)) / 1_000_000L
                    val event = withTimeoutOrNull(waitMs) { awaitPointerEvent() }
                    if (event != null && event.changes.all { !it.pressed }) {
                        break  // 抬指/取消
                    }
                    if (System.nanoTime() >= nextFireAt) {
                        onClick()
                        nextFireAt = System.nanoTime() + periodNs
                    }
                }
            }
        }
    )

private const val REPEAT_INITIAL_DELAY_MS = 400L
private const val REPEAT_PERIOD_MS = 60L

/** 键栏 chrome 调色（终端内容色由 TerminalColorScheme 提供；键栏自身恒深色）。
 *
 * P2-1：与 ConsoleTheme 同值的色锄到单一来源（bg=bar、keyHi=accent、
 * keyTextHi=TerminalConsoleTheme onPrimary）—— 旧版三处色源各自硬编码，
 * 改主题要记得同步；键帽/键面文字等键栏特有色保留在此（单一职责归宿）。
 *
 * #278：keyHi 走 ConsoleTheme.accent（强调色单一事实源，随设置改色
 * 即时跟随）；其余键栏色是恒深 chrome 中性色，保持静态。 */
internal object KeybarChrome {
    val bg = ConsoleTheme.bar
    val key = Color(0xFF1A2420)
    val keyHi: Color
        @Composable get() = ConsoleTheme.accent
    val keyEmph = Color(0xFF223729)
    val keyText = Color(0xFFE8F2ED)
    val keyTextHi = Color(0xFF06120D)
}
