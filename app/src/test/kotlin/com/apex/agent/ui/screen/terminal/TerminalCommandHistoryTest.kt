package com.apex.agent.ui.screen.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TerminalCommandHistory] 语义测试（T96）—— 旧版行为的等价断言
 * （去重置顶 / 上限裁剪 / 序号前缀编码 / 竖线转义 / ↑ 检索）。
 */
class TerminalCommandHistoryTest {

    private class MemStore : TerminalCommandHistory.Store {
        var saved: Set<String>? = null
        override fun load(): Set<String>? = saved
        override fun save(entries: Set<String>) { saved = entries }
    }

    @Test
    fun `record dedups to top and keeps order`() {
        val h = TerminalCommandHistory(MemStore(), maxEntries = 500)
        h.record("ls")
        h.record("cd /sdcard")
        h.record("ls")
        assertEquals(listOf("ls", "cd /sdcard"), h.entries.value)
    }

    @Test
    fun `blank commands ignored`() {
        val h = TerminalCommandHistory(MemStore(), maxEntries = 500)
        h.record("   ")
        h.record("")
        assertTrue(h.entries.value.isEmpty())
    }

    @Test
    fun `cap trims oldest beyond max`() {
        val h = TerminalCommandHistory(MemStore(), maxEntries = 3)
        h.record("a"); h.record("b"); h.record("c"); h.record("d")
        assertEquals(listOf("d", "c", "b"), h.entries.value)
    }

    @Test
    fun `pipe in command survives round trip via fullwidth escape`() {
        val mem = MemStore()
        val h = TerminalCommandHistory(mem, maxEntries = 500)
        h.record("grep 'a|b' file")
        // 编码侧：竖线转义为全角，序号前缀保序
        assertEquals(setOf("00000|grep 'a｜b' file"), mem.saved)
        // 恢复侧：新实例从同一存储读出 —— 转义还原
        val restored = TerminalCommandHistory(mem, maxEntries = 500)
        assertEquals(listOf("grep 'a|b' file"), restored.entries.value)
    }

    @Test
    fun `restore from persisted encoding keeps order and unescapes`() {
        val mem = MemStore()
        mem.saved = setOf(
            "00000|first",
            "00001|grep 'a｜b' f",
            "00002|has|pipe"
        )
        val h = TerminalCommandHistory(mem, maxEntries = 500)
        assertEquals(
            listOf("has|pipe", "grep 'a|b' f", "first"),
            h.entries.value
        )
    }

    @Test
    fun `entry containing newline is dropped honestly`() {
        val mem = MemStore()
        mem.saved = setOf("00000|good", "00001|bad\u000Acmd")
        val h = TerminalCommandHistory(mem, maxEntries = 500)
        // 载入侧过滤；写入侧（record）不可能收到换行（VM 以单行输入提交）
        assertEquals(listOf("good"), h.entries.value)
    }

    @Test
    fun `clear empties list and store`() {
        val mem = MemStore()
        val h = TerminalCommandHistory(mem, maxEntries = 500)
        h.record("x")
        h.clear()
        assertTrue(h.entries.value.isEmpty())
        assertEquals(emptySet<String>(), mem.saved)
    }

    @Test
    fun `search matches prefix and excludes`() {
        val h = TerminalCommandHistory(MemStore(), maxEntries = 500)
        h.record("git status"); h.record("git log"); h.record("ls")
        assertEquals(
            listOf("git status", "git log"),
            h.search("git")
        )
        assertEquals(
            listOf("git log"),
            h.search("git", exclude = setOf("git status"))
        )
    }
}
