package com.apex.agent.ui.screen.code.history

import com.apex.agent.ui.screen.code.session.CodeSessionSnapshot
import com.apex.agent.ui.screen.code.session.StorableCodeMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [CodeHistoryStore] 行为契约（纯 JVM）：归档往返、引擎记忆双线回写、
 * 列表排序与标题投影、删除/清理、坏档隔离与容错。
 */
class CodeHistoryStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 假钟（纪律：不睡墙钟，issue #276）。 */
    private var fakeNow = 1_000L

    private fun newStore(): CodeHistoryStore {
        val base = tmp.newFolder("code_history")
        val deep = tmp.newFolder("code_memory").apply { mkdirs() }
        val standard = tmp.newFolder("code_memory_standard").apply { mkdirs() }
        return CodeHistoryStore(base, deep, standard, clock = { fakeNow })
    }

    private fun snapshot(
        wsId: String,
        messages: List<StorableCodeMessage> = listOf(
            StorableCodeMessage("SYSTEM", "欢迎"),
            StorableCodeMessage("USER", "帮我修复崩溃\n顺便补个测试"),
            StorableCodeMessage("ASSISTANT", "已修复")
        ),
        updatedAt: Long = System.currentTimeMillis()
    ) = CodeSessionSnapshot(workspaceId = wsId, messages = messages, updatedAt = updatedAt)

    // ── 归档 + 列表 ─────────────────────────────────────────────────

    @Test
    fun `archive then entries lists newest first with title projection`() {
        val store = newStore()
        store.archive("alpha", snapshot("alpha", updatedAt = 1000L))
        fakeNow += 5
        store.archive("alpha", snapshot("alpha", updatedAt = 2000L))

        val entries = store.entries("alpha")
        assertEquals(2, entries.size)
        assertEquals("帮我修复崩溃", entries[0].title)
        assertTrue("新→旧排序", entries[0].id > entries[1].id)
        assertEquals(3, entries[0].messageCount)
    }

    @Test
    fun `entries derive title from first user message only`() {
        val store = newStore()
        store.archive("w", snapshot("w", messages = listOf(
            StorableCodeMessage("SYSTEM", "system only preamble"),
            StorableCodeMessage("USER", "first user line\nsecond line")
        )))

        val entries = store.entries("w")
        assertEquals(1, entries.size)
        assertEquals("first user line", entries[0].title)
    }

    @Test
    fun `entries without user message yields blank title`() {
        val store = newStore()
        store.archive("w", snapshot("w", messages = listOf(
            StorableCodeMessage("SYSTEM", "system"),
            StorableCodeMessage("ASSISTANT", "assistant only")
        )))
        assertEquals("", store.entries("w").first().title)
    }

    @Test
    fun `workspaces are isolated`() {
        val store = newStore()
        store.archive("a", snapshot("a"))
        store.archive("b", snapshot("b"))

        assertEquals(1, store.entries("a").size)
        assertEquals(1, store.entries("b").size)
        assertTrue(store.entries("a").all { it.workspaceId == "a" })
    }

    // ── 引擎记忆双线回写 ─────────────────────────────────────────────

    @Test
    fun `archive copies both engine memory files when present`() {
        val store = newStore()
        val base = tmp.root
        File(base, "code_memory").apply { mkdirs() }
            .resolve("ws_alpha.json").writeText("""[{"role":"user","content":"hi"}]""")
        File(base, "code_memory_standard").apply { mkdirs() }
            .resolve("ws_alpha.json").writeText("""[{"role":"user","content":"std"}]""")

        val id = store.archive("alpha", snapshot("alpha"))
        assertNotNull(id)

        val sessionDir = File(base, "code_history").resolve("ws_alpha").resolve(id!!)
        assertTrue(File(sessionDir, "ui.json").exists())
        assertEquals("""[{"role":"user","content":"hi"}]""", File(sessionDir, "deep.json").readText())
        assertEquals("""[{"role":"user","content":"std"}]""", File(sessionDir, "standard.json").readText())
    }

    @Test
    fun `restore writes memories back and returns snapshot`() {
        val store = newStore()
        val base = tmp.root
        File(base, "code_memory").apply { mkdirs() }
            .resolve("ws_alpha.json").writeText("""[{"role":"user","content":"deep history"}]""")
        // 标准线无记忆文件（该会话没在标准线聊过）
        File(base, "code_memory_standard").apply { mkdirs() }

        val id = store.archive("alpha", snapshot("alpha"))
        // 模拟归档后的新会话：活位记忆被清空/覆写
        File(base, "code_memory").resolve("ws_alpha.json").writeText("[]")
        File(base, "code_memory_standard").resolve("ws_alpha.json").writeText("""[{"role":"user","content":"stray"}]""")

        val restored = store.restoreInto("alpha", id!!)
        assertNotNull(restored)
        assertEquals(3, restored!!.messages.size)
        assertEquals("""[{"role":"user","content":"deep history"}]""",
            File(base, "code_memory").resolve("ws_alpha.json").readText())
        // 归档里没有 standard.json → 活位删除（引擎重放按空历史处理）
        assertFalse(File(base, "code_memory_standard").resolve("ws_alpha.json").exists())
    }

    @Test
    fun `restore returns null for missing session`() {
        val store = newStore()
        assertNull(store.restoreInto("alpha", "9999999999999"))
    }

    // ── 删除 / 清理 / 坏档 ───────────────────────────────────────────

    @Test
    fun `delete removes one entry and is idempotent`() {
        val store = newStore()
        val id = store.archive("w", snapshot("w"))
        store.delete("w", id!!)
        assertTrue(store.entries("w").isEmpty())
        store.delete("w", id) // 幂等
        assertTrue(store.entries("w").isEmpty())
    }

    @Test
    fun `clearWorkspace wipes all history of that workspace only`() {
        val store = newStore()
        store.archive("a", snapshot("a"))
        store.archive("b", snapshot("b"))

        store.clearWorkspace("a")
        assertTrue(store.entries("a").isEmpty())
        assertEquals(1, store.entries("b").size)
    }

    @Test
    fun `corrupt ui json is quarantined and skipped in entries`() {
        val store = newStore()
        val id = store.archive("w", snapshot("w"))
        val ui = tmp.root.resolve("code_history").resolve("ws_w").resolve(id!!).resolve("ui.json")
        ui.writeText("{ not json")

        assertEquals(0, store.entries("w").size)
        // 坏档隔离：目录改名 .corrupt 留排查线索
        assertTrue(File(ui.parentFile.parentFile, "$id.corrupt").exists())
    }

    @Test
    fun `workspace id is sanitized against path traversal`() {
        val store = newStore()
        val id = store.archive("../../etc", snapshot("../../etc"))
        assertNotNull(id)
        // 归档落在 baseDir 内（清洗后的目录），不逃逸：ws_ 前缀自带 1 个
        // 下划线 + "../../" 6 个特殊字符各替换 1 个 → ws_______etc（7 个）
        val wsDir = tmp.root.resolve("code_history").resolve("ws_______etc")
        assertTrue(wsDir.isDirectory)
    }
}
