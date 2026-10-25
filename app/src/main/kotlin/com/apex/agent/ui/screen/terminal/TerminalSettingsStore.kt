package com.apex.agent.ui.screen.terminal

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 控制台设置模型（T96 —— 自旧 TerminalViewModel.TerminalSettings 提升；
 * 网格时代字段（monochrome/showKeybar/vibrateBell）随交互视图一并退役，
 * 保留字号与亮屏两个控制台仍消费的项）。
 */
data class TerminalSettings(
    val fontSize: Int = 13,
    val keepScreenOn: Boolean = false
) {
    companion object {
        const val MIN_FONT_SIZE = 8
        const val MAX_FONT_SIZE = 24
    }
}

/**
 * 终端设置仓储（T96 自旧版转移并裁剪）—— 键名与历史版本一致
 * （apex_terminal: term_font_size / term_keep_screen_on）。
 *
 * 字号在持久化前钳制到 [TerminalSettings.MIN_FONT_SIZE]..[TerminalSettings.MAX_FONT_SIZE]
 * （防止旧版本已落盘的越界值继续生效）。
 *
 * Hilt 单例（TerminalModule 提供）：设置中心「终端」分类与本控制台
 * 共享同一内存态（构造注入 Context，prefs 由本类自建）。
 */
class TerminalSettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("apex_terminal", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<TerminalSettings> = _settings.asStateFlow()

    fun update(block: TerminalSettings.() -> TerminalSettings) {
        val next = _settings.value.block().let {
            it.copy(fontSize = it.fontSize.coerceIn(TerminalSettings.MIN_FONT_SIZE, TerminalSettings.MAX_FONT_SIZE))
        }
        prefs.edit()
            .putInt("term_font_size", next.fontSize)
            .putBoolean("term_keep_screen_on", next.keepScreenOn)
            .apply()
        _settings.value = next
    }

    private fun load() = TerminalSettings(
        fontSize = prefs.getInt("term_font_size", 13),
        keepScreenOn = prefs.getBoolean("term_keep_screen_on", false)
    )
}
