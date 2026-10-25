package com.apex.agent.ui.screen.terminal

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 命令黑白名单仓储（T96 自旧版转移；存储抽象化使语义可单测）。
 *
 * 门禁语义（与 TerminalModule DI 的 TerminalPolicyImpl 同源 prefs —— 两侧
 * 消费同一份数据，交互路径 fast-fail（本类），运行时写入侧兜底）：
 *  - 黑名单命中命令头 → 拒绝；
 *  - 白名单非空 → 仅白名单放行；
 *  - 两者皆空 → 放行（Termux 哲学：自己敲的命令不过滤）。
 *
 * 匹配 = 命令头 token 精确等值（消除旧 startsWith 前缀误拦：「rm」不再
 * 误拦「rmdir」的头 token）。
 *
 * Hilt 单例（TerminalModule 提供）：设置中心「终端」分类与本控制台
 * 共享同一内存态（生产经 Context 构造，测试可注入 [Store] 假实现）。
 */
class CommandPolicyStore(private val store: Store) {

    /** 存储抽象（读写两个 StringSet）。 */
    interface Store {
        fun load(key: String): Set<String>?
        fun save(key: String, entries: Set<String>)
    }

    /** 生产入口（apex_terminal SharedPreferences，键名与历史版本逐字一致）。 */
    constructor(context: Context) : this(PrefsStore(context))

    private class PrefsStore(context: Context) : Store {
        private val prefs = context.getSharedPreferences("apex_terminal", Context.MODE_PRIVATE)
        override fun load(key: String): Set<String>? = prefs.getStringSet(key, null)
        override fun save(key: String, entries: Set<String>) {
            prefs.edit().putStringSet(key, entries).apply()
        }
    }

    private val _blacklist = MutableStateFlow(store.load(KEY_BLACKLIST) ?: emptySet())
    val blacklist: StateFlow<Set<String>> = _blacklist.asStateFlow()

    private val _whitelist = MutableStateFlow(store.load(KEY_WHITELIST) ?: emptySet())
    val whitelist: StateFlow<Set<String>> = _whitelist.asStateFlow()

    fun addBlacklist(cmd: String) = edit(KEY_BLACKLIST, _blacklist) { add(normalize(cmd)) }

    fun removeBlacklist(cmd: String) = edit(KEY_BLACKLIST, _blacklist) { remove(normalize(cmd)) }

    fun addWhitelist(cmd: String) = edit(KEY_WHITELIST, _whitelist) { add(normalize(cmd)) }

    fun removeWhitelist(cmd: String) = edit(KEY_WHITELIST, _whitelist) { remove(normalize(cmd)) }

    /** 交互输入的命令头检查（提交管线第一步）。 */
    fun isCommandAllowed(command: String): Boolean {
        val head = command.trim().substringBefore(' ').lowercase()
        if (head.isEmpty()) return true
        if (_blacklist.value.any { head == it }) return false
        val wl = _whitelist.value
        if (wl.isNotEmpty()) return head in wl
        return true
    }

    private fun normalize(cmd: String) = cmd.trim().lowercase().substringBefore(' ')

    private fun edit(
        key: String,
        flow: MutableStateFlow<Set<String>>,
        mutate: MutableSet<String>.() -> Unit
    ) {
        val next = flow.value.toMutableSet().apply(mutate)
        store.save(key, next)
        flow.value = next
    }

    companion object {
        /** 与 DI TerminalPolicyImpl 共键（app/di/TerminalModule 动态策略读取同一数据）。 */
        internal const val KEY_BLACKLIST = "cmd_blacklist"
        internal const val KEY_WHITELIST = "cmd_whitelist"
    }
}
