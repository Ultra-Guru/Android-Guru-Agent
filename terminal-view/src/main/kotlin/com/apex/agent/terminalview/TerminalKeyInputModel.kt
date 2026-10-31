package com.apex.agent.terminalview

import com.apex.agent.terminalemulator.KeyModifiers
import com.apex.agent.terminalemulator.TerminalKey

/**
 * T88（2-a）：硬件键盘映射层（纯 JVM —— View 把 Android KeyEvent 归一成
 * [HardwareKey] 再喂进来，映射表可在 CI 无设备锁定）。
 *
 * ## 映射语义（与平台层 `KeyEventMapping`/Termux 对齐）
 *
 *  - **Ctrl+字母** → 控制字节（A..Z → 0x01..0x1A；Space→0x00、[→0x1B、\→0x1C、
 *    ]→0x1D、^→0x1E、_→0x1F、?→0x7F —— xterm 控制区全集）；
 *  - **Alt+键** → 文本 + ALT 修饰位（View 发送 `ESC` 前缀 —— bash `Alt+B/F` 词跳；
 *    最终编码归宿主，本层只标记）；
 *  - **方向/F1-12/Home/End/PgUp/PgDn/Enter/Tab/Backspace/Esc/Delete** →
 *    [TerminalKey] + 修饰位（DECCKM/modifyOtherKeys 由宿主 `encodeKey` 处理）；
 *  - 无修饰的**字符键** → 文本（走 IME/文本路径）；
 *  - **KEYCODE_DEL 是 Backspace**（Android 命名陷阱）、FORWARD_DEL 才是 Delete；
 *  - 小键盘：DECKPAM 数字 SS3 编码由宿主做 —— 本层映射成 `NUMPAD_*` 键身份。
 *
 * 标签规一：`KeyEvent.keyCodeToString()` 的 `KEYCODE_XXX` 或裸 `XXX` 都接受
 * （前缀剥除、大小写不敏感）。
 */
object TerminalKeyInputModel {

    /** 修饰键（JVM 可测的小枚举 —— 与 emulator `KeyModifiers` 位一一对应）。 */
    enum class KeyModifier(val bit: Int) {
        SHIFT(KeyModifiers.SHIFT),
        ALT(KeyModifiers.ALT),
        CTRL(KeyModifiers.CTRL),
        META(KeyModifiers.META);

        companion object {
            /** 修饰集 → xterm 位掩码（emulator `KeyModifiers` 语义）。 */
            fun maskOf(mods: Set<KeyModifier>): Int {
                var m = 0
                if (SHIFT in mods) m = m or SHIFT.bit
                if (ALT in mods) m = m or ALT.bit
                if (CTRL in mods) m = m or CTRL.bit
                if (META in mods) m = m or META.bit
                return m
            }
        }
    }

    /**
     * 去安卓化的硬件键事件：View 由 `KeyEvent` 构造（label =
     * `KeyEvent.keyCodeToString(keyCode)` 去掉 `KEYCODE_` 前缀，如 `"DPAD_UP"`）。
     */
    data class HardwareKey(
        val keyCodeLabel: String,
        val mods: Set<KeyModifier> = emptySet(),
        /** 键位 Unicode（生成字符；0 = 无 —— 组合键下常见，此时用标签表兜底）。 */
        val unicodeChar: Int = 0
    )

    /** 映射结果 —— View 按类型路由到 client 回调。 */
    sealed class MappedInput {
        /** 特殊键（含修饰位）→ `onTerminalKey`。 */
        data class TerminalKeyInput(val key: TerminalKey, val mods: Int) : MappedInput()

        /** 控制字节 → `onTerminalControlChar`。 */
        data class ControlChar(val code: Int) : MappedInput()

        /** 字符输入（mods 携带 ALT/META 时 View 加 ESC 前缀）→ `onTerminalWrite`。 */
        data class CharInput(val text: String, val mods: Int) : MappedInput()

        /** 无映射（音量/相机等）—— View 放行给系统。 */
        object Unmapped : MappedInput()
    }

    /** 特殊键标签 → TerminalKey（键身份，编码在宿主）。 */
    private val specialKeys: Map<String, TerminalKey> = buildMap {
        put("DPAD_UP", TerminalKey.UP)
        put("DPAD_DOWN", TerminalKey.DOWN)
        put("DPAD_LEFT", TerminalKey.LEFT)
        put("DPAD_RIGHT", TerminalKey.RIGHT)
        put("MOVE_HOME", TerminalKey.HOME)
        put("MOVE_END", TerminalKey.END)
        put("PAGE_UP", TerminalKey.PAGE_UP)
        put("PAGE_DOWN", TerminalKey.PAGE_DOWN)
        put("ENTER", TerminalKey.ENTER)
        put("NUMPAD_ENTER", TerminalKey.NUMPAD_ENTER)
        put("TAB", TerminalKey.TAB)
        put("BACKSPACE", TerminalKey.BACKSPACE)
        put("DEL", TerminalKey.BACKSPACE)        // KEYCODE_DEL = Backspace（Android 命名陷阱）
        put("FORWARD_DEL", TerminalKey.DELETE)
        put("ESCAPE", TerminalKey.ESCAPE)
        put("INSERT", TerminalKey.INSERT)
        put("SPACE", TerminalKey.SPACE)
        for (i in 1..12) put("F$i", enumValueOfSafe("F$i"))
        for (i in 0..9) put("NUMPAD_$i", enumValueOfSafe("NUMPAD_$i"))
        put("NUMPAD_DOT", TerminalKey.NUMPAD_DECIMAL)
        put("NUMPAD_COMMA", TerminalKey.NUMPAD_SEPARATOR)
        put("NUMPAD_ADD", TerminalKey.NUMPAD_ADD)
        put("NUMPAD_SUBTRACT", TerminalKey.NUMPAD_SUBTRACT)
        put("NUMPAD_MULTIPLY", TerminalKey.NUMPAD_MULTIPLY)
        put("NUMPAD_DIVIDE", TerminalKey.NUMPAD_DIVIDE)
    }

    /** 符号键标签 → 生成字符（KEYCODE_ 命名与 ASCII 的对照）。 */
    private val symbolChars: Map<String, Char> = mapOf(
        "MINUS" to '-', "EQUALS" to '=', "LEFT_BRACKET" to '[', "RIGHT_BRACKET" to ']',
        "BACKSLASH" to '\\', "SEMICOLON" to ';', "APOSTROPHE" to '\'',
        "COMMA" to ',', "PERIOD" to '.', "SLASH" to '/', "GRAVE" to '`',
        "AT" to '@', "PLUS" to '+', "STAR" to '*', "POUND" to '#'
    )

    /** 控制区修饰对照（Ctrl+符号 → C0 字节）。 */
    private val ctrlSymbolCodes: Map<Char, Int> = mapOf(
        ' ' to 0x00, '@' to 0x00,
        '[' to 0x1B, '\\' to 0x1C, ']' to 0x1D, '^' to 0x1E, '_' to 0x1F,
        '?' to 0x7F
    )

    /**
     * 主映射入口。
     *
     * @param unicodeChar View 传入的 `KeyEvent.unicodeChar`（组合键下可能为 0，
     *        此时用标签表兜底推导）
     */
    fun map(hardwareKey: HardwareKey, unicodeChar: Int = hardwareKey.unicodeChar): MappedInput {
        val label = hardwareKey.keyCodeLabel.removePrefix("KEYCODE_").uppercase()
        val mods = KeyModifier.maskOf(hardwareKey.mods)
        val ctrl = KeyModifier.CTRL in hardwareKey.mods

        // 1) Ctrl 组合优先：字符面 → 控制字节（xterm 控制区全集）
        if (ctrl) {
            val ch = printableCharOf(label, unicodeChar) ?: spaceCharOf(label, unicodeChar)
            if (ch != null) {
                if (ch in 'a'..'z') return MappedInput.ControlChar(ch - 'a' + 1)
                if (ch in 'A'..'Z') return MappedInput.ControlChar(ch - 'A' + 1)
                ctrlSymbolCodes[ch]?.let { return MappedInput.ControlChar(it) }
            }
        }

        // 2) 特殊键（修饰位透传 —— 宿主 encodeKey 编码 xterm 修饰组合）
        specialKeys[label]?.let { return MappedInput.TerminalKeyInput(it, mods) }

        // 3) 普通字符（字母/数字/符号）：Ctrl 无映射时按字符走（剥 CTRL 位 ——
        //    Ctrl+数字等 xterm 语义就是原字符）
        printableCharOf(label, unicodeChar)?.let {
            return MappedInput.CharInput(it.toString(), mods and KeyModifiers.CTRL.inv())
        }

        // 4) 音量/相机/搜索等 —— 放行系统
        return MappedInput.Unmapped
    }

    /**
     * 标签/Unicode → 可打印字符（无 → null）。
     * 单字符标签（A、7…）与符号表是 unicodeChar 缺失时的兜底。
     */
    private fun printableCharOf(label: String, unicodeChar: Int): Char? {
        if (unicodeChar in 0x20..0x7E) return unicodeChar.toChar()
        if (unicodeChar > 0x7E && unicodeChar < 0xFFFE) return unicodeChar.toChar()
        symbolChars[label]?.let { return it }
        if (label.length == 1) {
            val c = label[0]
            if (c.isLetterOrDigit() || !c.isWhitespace()) return c
        }
        return null
    }

    /** Space 标签在 printableCharOf 之外的显式通道（Ctrl+Space → NUL）。 */
    fun spaceCharOf(label: String, unicodeChar: Int): Char? =
        if (unicodeChar == ' '.code || label == "SPACE") ' ' else null

    private fun enumValueOfSafe(name: String): TerminalKey =
        runCatching { TerminalKey.valueOf(name) }.getOrDefault(TerminalKey.ESCAPE)
}
