package com.apex.agent.terminalview

import android.view.KeyCharacterMap
import android.view.KeyEvent

/**
 * T92：硬件键盘事件「预路由」—— 纯决策逻辑（从 TerminalView 抽出，SRP 行预算）。
 *
 * dispatchHardwareKeyEvent 的无副作用部分：
 *  - Shift+PgUp/PgDn 本地翻页判定（Termux 拦截同款 —— 不透传 PTY）；
 *  - 死键组合重音状态机（欧式布局 acute/grave/tilde… → é/à/ñ）；
 *  - AltGr（纯右 Alt）修饰豁免（德/法/北欧布局字符第三层不构成 xterm ALT）。
 *
 * View 层保留副作用胶水（滚动/写 PTY/映射派发）。
 */
internal object TerminalKeyEventRouter {

    /** 预路由结果 —— View 据此执行副作用。 */
    sealed interface PreMap {

        /** Shift+PgUp/PgDn：本地 scrollback 翻页一屏（不透传 PTY）。 */
        data class PageScroll(val up: Boolean) : PreMap

        /** 死键按下（unicodeChar 高位 COMBINING_ACCENT 标记）—— 记住重音。 */
        data class DeadAccent(val accent: Int) : PreMap

        /** 重音 + 本击字符合成成功（[KeyCharacterMap.getDeadChar]）→ 直接写入。 */
        data class ComposedAccent(val text: String) : PreMap

        /**
         * 常规硬件键（修饰集已做 AltGr 豁免）。
         *
         * @param clearAccent 上一击重音是否已被消费（合成失败 = 丢弃；非打印键
         *   （unicode=0，如方向键）不消费 —— 旧行为保持）
         */
        data class Hardware(
            val mods: Set<TerminalKeyInputModel.KeyModifier>,
            val unicodeChar: Int,
            val keyCodeLabel: String,
            val clearAccent: Boolean
        ) : PreMap
    }

    /**
     * @param pendingAccent 上一击死键重音（0 = 无）。纯函数：新重音状态由返回值
     *   表达（DeadAccent = 记住/覆盖；ComposedAccent/Hardware.clearAccent = 消费）。
     * @param hasScrollback 本地 scrollback 是否有可翻量（false = Shift+PgUp 放行
     *   常规映射，交 TUI 自行翻页）
     */
    fun preMap(event: KeyEvent, pendingAccent: Int, hasScrollback: Boolean): PreMap {
        // Shift+PgUp/PgDn = 本地翻页（Termux 拦截同款）—— 旧行为 vim/less 把它当
        // 普通 PgUp 发（TUI 内部翻页），用户在主屏想翻 scrollback 时无从下手。
        if (hasScrollback && event.action == KeyEvent.ACTION_DOWN &&
            event.isShiftPressed && !event.isCtrlPressed && !event.isAltPressed
        ) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_PAGE_UP -> return PreMap.PageScroll(up = true)
                KeyEvent.KEYCODE_PAGE_DOWN -> return PreMap.PageScroll(up = false)
            }
        }
        // 死键组合重音 —— KeyEvent.unicodeChar 的高位携带 COMBINING_ACCENT 标记；
        // 下一击合成（é/à/ñ…）。旧行为重音被吞（printableCharOf 区间判断落空）。
        val unicode = event.unicodeChar
        if (unicode != 0 && (unicode and KeyCharacterMap.COMBINING_ACCENT) != 0) {
            return PreMap.DeadAccent(unicode and KeyCharacterMap.COMBINING_ACCENT_MASK)
        }
        if (pendingAccent != 0 && unicode != 0 && event.action == KeyEvent.ACTION_DOWN) {
            val combined = KeyCharacterMap.getDeadChar(pendingAccent, unicode)
            if (combined != 0) return PreMap.ComposedAccent(String(Character.toChars(combined)))
            // 合成失败：重音丢弃，继续走常规路径
            return hardware(event, unicode, clearAccent = true)
        }
        return hardware(event, unicode, clearAccent = false)
    }

    /** 常规映射（AltGr 豁免见类 KDoc）。 */
    private fun hardware(event: KeyEvent, unicode: Int, clearAccent: Boolean): PreMap.Hardware {
        val altGrOnly = event.isAltPressed &&
            (event.metaState and KeyEvent.META_ALT_RIGHT_ON) != 0 &&
            (event.metaState and KeyEvent.META_ALT_LEFT_ON) == 0
        val mods = mutableSetOf<TerminalKeyInputModel.KeyModifier>()
        if (event.isCtrlPressed) mods.add(TerminalKeyInputModel.KeyModifier.CTRL)
        if (event.isShiftPressed) mods.add(TerminalKeyInputModel.KeyModifier.SHIFT)
        if (event.isAltPressed && !altGrOnly) mods.add(TerminalKeyInputModel.KeyModifier.ALT)
        if (event.isMetaPressed) mods.add(TerminalKeyInputModel.KeyModifier.META)
        return PreMap.Hardware(
            mods = mods,
            unicodeChar = unicode,
            keyCodeLabel = KeyEvent.keyCodeToString(event.keyCode),
            clearAccent = clearAccent
        )
    }
}
