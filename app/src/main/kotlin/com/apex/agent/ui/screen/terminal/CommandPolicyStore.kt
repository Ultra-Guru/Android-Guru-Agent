package com.apex.agent.ui.screen.terminal

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 命令黑白名单仓储（P0-1 拆分）—— 用户名单的 prefs 读写 + 提交时刻的门禁
 * 检查，从 `TerminalViewModel` 收口。
 *
 * 门禁语义（与 TerminalModule 动态策略同源的 prefs 数据，但仅消费用户名单；
 * 交互路径的内置默认危险命令拦截由用户自行把条目加入黑名单完成 —— 自己敲
 * 的命令接 Termux 哲学：不过滤）。拦截判定的输入侧镜像由 [LineMirror] 维护。
 *
 * 匹配 = 命令头 token 精确等值（与 CommandPolicy 的 token 语义一致，消除旧
 * startsWith 前缀误拦：“rm” 不再误拦 “rmdir...” 的头 token）。
 */
class CommandPolicyStore(private val prefs: SharedPreferences) {

    private val _blacklist = MutableStateFlow(loadSet("cmd_blacklist"))
    val blacklist: StateFlow<Set<String>> = _blacklist.asStateFlow()

    private val _whitelist = MutableStateFlow(loadSet("cmd_whitelist"))
    val whitelist: StateFlow<Set<String>> = _whitelist.asStateFlow()

    fun addBlacklist(cmd: String) = editSet("cmd_blacklist", _blacklist) { add(normalize(cmd)) }

    fun removeBlacklist(cmd: String) = editSet("cmd_blacklist", _blacklist) { remove(normalize(cmd)) }

    fun addWhitelist(cmd: String) = editSet("cmd_whitelist", _whitelist) { add(normalize(cmd)) }

    fun removeWhitelist(cmd: String) = editSet("cmd_whitelist", _whitelist) { remove(normalize(cmd)) }

    /**
     * 交互输入的命令头检查。
     *  - 黑名单命中 → 拒绝；
     *  - 白名单非空 → 仅白名单放行；
     *  - 两者皆空 → 放行（Termux 哲学）。
     */
    fun isCommandAllowed(command: String): Boolean {
        val head = command.trim().substringBefore(' ').lowercase()
        if (head.isEmpty()) return true
        if (_blacklist.value.any { head == it }) return false
        val wl = _whitelist.value
        if (wl.isNotEmpty()) {
            return head in wl
        }
        return true
    }

    private fun normalize(cmd: String) = cmd.trim().lowercase().substringBefore(' ')

    private fun loadSet(key: String): Set<String> =
        prefs.getStringSet(key, emptySet()) ?: emptySet()

    private fun editSet(
        key: String,
        flow: MutableStateFlow<Set<String>>,
        mutate: MutableSet<String>.() -> Unit
    ) {
        val next = flow.value.toMutableSet().apply(mutate)
        prefs.edit().putStringSet(key, next).apply()
        flow.value = next
    }
}
