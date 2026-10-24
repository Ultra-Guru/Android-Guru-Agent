package com.apex.agent.ui.screen.terminal

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * 终端命令历史（T87 实现；T96 自旧 history/ 子包平铺转移）。
 *
 * 职责：记录用户提交的命令（回车时刻、通过门禁的才有资格入史）；去重置顶；
 * 上限裁剪（[MAX_ENTRIES]）；持久化（进程重启不丢）；StateFlow 广播（历史
 * Sheet 实时刷新）+ ↑/↓ 召回检索。
 *
 * 不做：Agent 写入的命令（AGENT owner 不进用户历史 —— bash history 语义）。
 *
 * 持久化编码：StringSet 无序 —— 存储时附带序号前缀「00001|cmd」保证恢复
 * 顺序；命令内竖线转义为全角竖线 U+FF5C；含换行的条目诚实丢弃。
 *
 * 存储抽象（T96）：历史语义单测直测（内存桩）—— 不再依赖 Android 桩。
 */
class TerminalCommandHistory(
    private val store: Store,
    private val maxEntries: Int = MAX_ENTRIES
) {
    /** 存储抽象。 */
    interface Store {
        fun load(): Set<String>?
        fun save(entries: Set<String>)
    }

    /** 生产入口（apex_terminal SharedPreferences，与历史版本同键同格式）。 */
    constructor(context: Context, maxEntries: Int = MAX_ENTRIES) :
        this(PrefsStore(context), maxEntries)

    private class PrefsStore(context: Context) : Store {
        private val prefs = context.getSharedPreferences("apex_terminal", Context.MODE_PRIVATE)
        override fun load(): Set<String>? = prefs.getStringSet(KEY, null)
        override fun save(entries: Set<String>) {
            prefs.edit().putStringSet(KEY, entries).apply()
        }
    }

    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<String>> = _entries.asStateFlow()

    /** 记录一条已提交命令（空/空白忽略；去重置顶）。 */
    fun record(command: String) {
        val cmd = command.trim()
        if (cmd.isEmpty()) return
        val next = buildList(capacity = maxEntries.coerceAtMost(_entries.value.size + 1)) {
            add(cmd)
            for (e in _entries.value) {
                if (size >= maxEntries) break
                if (e != cmd) add(e)
            }
        }
        _entries.value = next
        store.save(encode(next))
    }

    /** 清空（历史 Sheet 危险按钮确认后调用）。 */
    fun clear() {
        _entries.value = emptyList()
        store.save(emptySet())
    }

    /** ↑/↓ 召回检索：前缀匹配（exclude = 本次输入已召回过的候选）。 */
    fun search(prefix: String, exclude: Set<String> = emptySet()): List<String> =
        _entries.value.filter { it.startsWith(prefix) && it !in exclude }

    private fun load(): List<String> =
        store.load()?.filter { it.isNotBlank() }?.sortedByDescending {
            it.substringBefore('|').toIntOrNull() ?: 0
        }?.map { decodeEntry(it) }
            // 载入侧防御：含换行的条目诚实丢弃（编码侧本就不产出；手写存储/旧数据兜底）
            ?.filterNot { it.contains('\n') } ?: emptyList()

    private fun encode(entries: List<String>): Set<String> =
        entries.mapIndexedNotNull { i, e ->
            if (e.contains('\n')) return@mapIndexedNotNull null
            val prefix = String.format(Locale.US, "%05d", i)
            "$prefix|${e.replace('|', '｜')}"
        }.toSet()

    private fun decodeEntry(raw: String): String {
        val body = raw.substringAfter('|')
        return body.replace('｜', '|')
    }

    companion object {
        private const val KEY = "term_command_history"
        const val MAX_ENTRIES = 500
    }
}
