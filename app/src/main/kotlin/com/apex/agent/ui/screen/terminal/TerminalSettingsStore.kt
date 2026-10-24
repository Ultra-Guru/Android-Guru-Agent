package com.apex.agent.ui.screen.terminal

import android.content.SharedPreferences
import com.apex.agent.ui.screen.terminal.extrakeys.ExtraKeysConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 终端设置仓储（P0-1 拆分）—— `TerminalSettings` / 扩展键的 prefs 读写从
 * `TerminalViewModel` 收口到独立存储类。VM 只做声明与委托，不再知道任何
 * SharedPreferences 键名。
 *
 * 持久化键（SharedPreferences "apex_terminal"）：
 *  - `term_font_size` / `term_monochrome` / `term_show_keybar` /
 *    `term_vibrate_bell` / `term_keep_screen_on`：TerminalSettings；
 *  - `term_extra_keys`：单行布局序列（[ExtraKeysConfig.serialize]）。
 */
class TerminalSettingsStore(private val prefs: SharedPreferences) {

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<TerminalViewModel.TerminalSettings> = _settings.asStateFlow()

    private val _extraKeys = MutableStateFlow(loadExtraKeys(prefs))

    /** 扩展键（用户宏；空 = 不渲染扩展行）。 */
    val extraKeys: StateFlow<List<ExtraKeysConfig.ExtraKey>> = _extraKeys.asStateFlow()

    /**
     * 更新设置（block 返回新值）。字号在持久化前钳制到合法区间（双指捏合
     * 与设置抽屉共用同一钳制 —— 防旧版本已落盘的越界值继续生效）。
     */
    fun update(block: TerminalViewModel.TerminalSettings.() -> TerminalViewModel.TerminalSettings) {
        val next = _settings.value.block().let {
            it.copy(
                fontSize = it.fontSize.coerceIn(
                    TerminalViewModel.TerminalSettings.MIN_FONT_SIZE,
                    TerminalViewModel.TerminalSettings.MAX_FONT_SIZE
                )
            )
        }
        prefs.edit()
            .putInt("term_font_size", next.fontSize)
            .putBoolean("term_monochrome", next.monochrome)
            .putBoolean("term_show_keybar", next.showKeybar)
            .putBoolean("term_vibrate_bell", next.vibrateOnBell)
            .putBoolean("term_keep_screen_on", next.keepScreenOn)
            .apply()
        _settings.value = next
    }

    private fun loadSettings() = TerminalViewModel.TerminalSettings(
        fontSize = prefs.getInt("term_font_size", 13),
        monochrome = prefs.getBoolean("term_monochrome", false),
        showKeybar = prefs.getBoolean("term_show_keybar", true),
        vibrateOnBell = prefs.getBoolean("term_vibrate_bell", true),
        keepScreenOn = prefs.getBoolean("term_keep_screen_on", false)
    )

    /** 追加一个扩展键（spec 形如 `标签=cmd:apt-get update`；非法 spec 静默拒绝）。 */
    fun addExtraKey(spec: String) {
        val key = ExtraKeysConfig.parseKey(spec.trim()) ?: return
        val next = ExtraKeysConfig.appendKey(listOf(_extraKeys.value), key).flatten()
        _extraKeys.value = next
        persistExtraKeys(prefs, next)
    }

    /** 移除指定标签的扩展键。 */
    fun removeExtraKey(label: String) {
        val next = _extraKeys.value.filterNot { it.label == label }
        _extraKeys.value = next
        persistExtraKeys(prefs, next)
    }

    /** 重置为默认布局（设置抽屉「恢复默认」）。 */
    fun resetExtraKeys() {
        val next = ExtraKeysConfig.DEFAULT_LAYOUT.flatten()
        _extraKeys.value = next
        persistExtraKeys(prefs, next)
    }
}
