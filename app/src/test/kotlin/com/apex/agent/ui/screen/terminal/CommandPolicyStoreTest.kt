package com.apex.agent.ui.screen.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CommandPolicyStore] 门禁语义测试（T96）。
 *
 * 断言值来自旧版语义（Termux 哲学 + 精确头 token 匹配）—— 存储为内存桩。
 */
class CommandPolicyStoreTest {

    private class MemStore : CommandPolicyStore.Store {
        val map = mutableMapOf<String, Set<String>>()
        override fun load(key: String): Set<String>? = map[key]
        override fun save(key: String, entries: Set<String>) { map[key] = entries }
    }

    private fun storeWith(blacklist: Set<String> = emptySet(), whitelist: Set<String> = emptySet()): CommandPolicyStore {
        val mem = MemStore()
        mem.map[CommandPolicyStore.KEY_BLACKLIST] = blacklist
        mem.map[CommandPolicyStore.KEY_WHITELIST] = whitelist
        return CommandPolicyStore(mem)
    }

    @Test
    fun `empty lists allow everything`() {
        val s = storeWith()
        assertTrue(s.isCommandAllowed("rm -rf /"))
        assertTrue(s.isCommandAllowed(""))
    }

    @Test
    fun `blacklist exact head blocks`() {
        val s = storeWith(blacklist = setOf("rm"))
        assertFalse(s.isCommandAllowed("rm -rf /"))
        assertTrue(s.isCommandAllowed("rmdir -p x"))   // rm 不误拦 rmdir（头 token 精确匹配）
        assertTrue(s.isCommandAllowed("echo rm"))      // rm 不在头位
    }

    @Test
    fun `whitelist non-empty allows only members`() {
        val s = storeWith(whitelist = setOf("ls", "cat"))
        assertTrue(s.isCommandAllowed("ls -alF"))
        assertTrue(s.isCommandAllowed("cat /proc/cpuinfo"))
        assertFalse(s.isCommandAllowed("rm -rf /"))
    }

    @Test
    fun `blacklist wins over whitelist`() {
        val s = storeWith(blacklist = setOf("ls"), whitelist = setOf("ls"))
        assertFalse(s.isCommandAllowed("ls"))
    }

    @Test
    fun `normalize trims lowercases and takes first token`() {
        val s = storeWith()
        s.addBlacklist("  RM  -rf ")
        assertTrue(s.blacklist.value.contains("rm"))
        assertFalse(s.isCommandAllowed("RM -rf /"))    // 大小写归一
    }

    @Test
    fun `add and remove update flow and store`() {
        val s = storeWith()
        s.addBlacklist("reboot")
        assertTrue(s.isCommandAllowed("ls"))
        assertFalse(s.isCommandAllowed("reboot"))
        s.removeBlacklist("reboot")
        assertTrue(s.isCommandAllowed("reboot"))
    }

    @Test
    fun `edit persists through store contract`() {
        val mem = MemStore()
        val s = CommandPolicyStore(mem)
        s.addWhitelist("git")
        assertEquals(setOf("git"), mem.map[CommandPolicyStore.KEY_WHITELIST])
    }
}
