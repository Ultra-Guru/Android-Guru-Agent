package com.apex.agent.core.tools.builtin.context

import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.ToolCall
import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.StructuredAgentTool
import com.apex.agent.core.tools.ToolErrorCode
import com.apex.agent.core.tools.ToolResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #396 上下文归档 + 取原文单测：
 * - [InMemoryContextArchive] 契约（全文不截断 / 稳定序号 / 清空）；
 * - [ContextFetchTool]（#N 活跃全文、a#N 归档全文、offset/limit 分窗、
 *   非法定位与越界错误语义）；
 * - [ContextSearchTool] 归档感知（a#N / #N 双源命中）；
 * - [ContextRecapTool] 归档概况行。
 */
class ContextArchiveToolsTest {

    // ── fake 会话 ──────────────────────────────────────────────────

    private class FakeContext(private val records: List<ContextRecord>) : SessionContextProvider {
        override fun records(): List<ContextRecord> = records
    }

    /** 超过 2000 截断护栏的活跃记录（验证 Fetch 绕过截断取全文）。 */
    private val longLiveText = "live-record ".repeat(400) // 4800 chars

    private val liveRecords = listOf(
        ContextRecord(0, "system", "system prompt", 0L),
        ContextRecord(1, "user", "user asks about api key rotation", 1000L),
        ContextRecord(2, "assistant", "[tool_call] Read", 1500L),
        ContextRecord(3, "tool", longLiveText, 2000L)
    )

    private val context = FakeContext(liveRecords)
    private val archive = InMemoryContextArchive()

    private val search = ContextSearchTool(context, archive)
    private val fetch = ContextFetchTool(
        liveReader = {
            listOf(
                LlmMessage.System("system prompt"),
                LlmMessage.User("user asks about api key rotation"),
                LlmMessage.Assistant("", toolCalls = listOf(ToolCall("c1", "Read", "{}"))),
                LlmMessage.ToolResult("c1", longLiveText)
            )
        },
        archive = archive
    )
    private val recap = ContextRecapTool(context, archive)

    private suspend fun structured(tool: AgentTool, args: String): ToolResult =
        (tool as StructuredAgentTool).executeStructured(args)

    private fun okOf(result: ToolResult): String {
        assertTrue("expected ok but was: $result", result.error == null)
        return result.output ?: ""
    }

    // ═══ InMemoryContextArchive 契约 ═════════════════════════════════

    @Test
    fun `archive keeps full content without the 2000 guard`() {
        archive.appendArchived(listOf(LlmMessage.User(longLiveText)))
        assertEquals(1, archive.size())
        val records = archive.archivedRecords()
        assertEquals(1, records.size)
        assertEquals("user", records[0].role)
        assertEquals(longLiveText, records[0].content)
    }

    @Test
    fun `archive assistant tool calls get marker lines and stable indexes`() {
        archive.appendArchived(
            listOf(
                LlmMessage.User("u1"),
                LlmMessage.Assistant("", toolCalls = listOf(ToolCall("c1", "Grep", "{}"))),
                LlmMessage.ToolResult("c1", "hit")
            )
        )
        val records = archive.archivedRecords()
        assertEquals(listOf(0, 1, 2), records.map { it.index })
        assertEquals("[tool_call] Grep", records[1].content)

        // 追加不改变既有索引（定位符稳定性）
        archive.appendArchived(listOf(LlmMessage.User("u2")))
        val grown = archive.archivedRecords()
        assertEquals(4, grown.size)
        assertEquals(listOf(0, 1, 2, 3), grown.map { it.index })
    }

    @Test
    fun `archive clear resets everything`() {
        archive.appendArchived(listOf(LlmMessage.User("u1")))
        archive.clear()
        assertEquals(0, archive.size())
        assertTrue(archive.archivedRecords().isEmpty())
    }

    // ═══ ContextFetchTool ═════════════════════════════════════════════

    @Test
    fun `fetch live record by hash locator returns untruncated full text`() = runTest {
        val out = okOf(structured(fetch, """{"ref": "#3", "limit": 24000}"""))
        assertTrue(out.contains("Record #3 [tool]"))
        assertTrue("全文必须返回（绕过 2000 截断）", out.contains(longLiveText))
    }

    @Test
    fun `fetch archived record by a-hash locator`() = runTest {
        archive.appendArchived(listOf(LlmMessage.User("archived original wording")))
        val out = okOf(structured(fetch, """{"ref": "a#0"}"""))
        assertTrue(out.contains("Record a#0 [user]"))
        assertTrue(out.contains("archived original wording"))
    }

    @Test
    fun `fetch pages long records with offset and limit`() = runTest {
        // limit 下限 200（防误传 1 一次性取空），用 600 字符验证分窗
        archive.appendArchived(listOf(LlmMessage.User("0123456789".repeat(60)))) // 600 chars
        val first = okOf(structured(fetch, """{"ref": "a#0", "offset": 0, "limit": 200}"""))
        assertTrue(first.contains("600 chars total"))
        assertTrue(first.contains("showing 0..200"))
        assertTrue("续读提示应出现（还有 400 字符）", first.contains("400 more chars"))
        assertTrue("续读提示应给出下一窗 offset", first.contains("offset=200"))
        // 尾窗：offset 500 + limit 200 收敛到 600，无续读提示
        val tail = okOf(structured(fetch, """{"ref": "a#0", "offset": 500, "limit": 200}"""))
        assertTrue(tail.contains("showing 500..600"))
        assertFalse("到末尾不应出现续读提示", tail.contains("more chars"))
    }

    @Test
    fun `fetch unknown locator shape is invalid_argument`() = runTest {
        val result = structured(fetch, """{"ref": "banana"}""")
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, result.error?.code)
    }

    @Test
    fun `fetch nonexistent record is not_found with a hint`() = runTest {
        val live = structured(fetch, """{"ref": "#99"}""")
        assertEquals(ToolErrorCode.NOT_FOUND, live.error?.code)
        assertTrue(live.error?.message.orEmpty().contains("ContextSearch"))

        val archived = structured(fetch, """{"ref": "a#99"}""")
        assertEquals(ToolErrorCode.NOT_FOUND, archived.error?.code)
    }

    @Test
    fun `fetch offset beyond end is invalid_argument`() = runTest {
        val result = structured(fetch, """{"ref": "#1", "offset": 9999}""")
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, result.error?.code)
    }

    // ═══ ContextSearchTool 归档感知 ═══════════════════════════════════

    @Test
    fun `search hits archived records with a-locator`() = runTest {
        archive.appendArchived(listOf(LlmMessage.User("the api key is sk-secret-42")))
        val out = okOf(structured(search, """{"query": "sk-secret-42"}"""))
        assertTrue(out.contains("a#0"))
        assertTrue(out.contains("api key"))
    }

    @Test
    fun `search hits live records with hash locator and both sources coexist`() = runTest {
        archive.appendArchived(listOf(LlmMessage.User("archived needle")))
        val out = okOf(structured(search, """{"query": "needle"}"""))
        // 归档命中（a#0）与活跃命中（#1: "user asks about api key rotation" 无 needle…）
        assertTrue(out.contains("a#0"))
        // 活跃源命中的定位符保持 #N 形态（无 a 前缀）
        val live = okOf(structured(search, """{"query": "api key"}"""))
        assertTrue(live.contains("#1"))
        assertFalse(live.contains("a#1"))
    }

    @Test
    fun `search without archive wiring keeps legacy behavior`() = runTest {
        val legacy = ContextSearchTool(context)
        val out = okOf(structured(legacy, """{"query": "api key"}"""))
        assertTrue(out.contains("#1"))
    }

    // ═══ ContextRecapTool 归档概况 ═══════════════════════════════════

    @Test
    fun `recap overview mentions archived record count when archive is non-empty`() = runTest {
        archive.appendArchived(
            listOf(LlmMessage.User("u1"), LlmMessage.ToolResult("c9", "r1"))
        )
        val out = okOf(structured(recap, """{"scope": "full"}"""))
        assertTrue(out.contains("archived (compressed-away, retrievable): 2 records"))
        assertTrue(out.contains("ContextFetch"))
    }

    @Test
    fun `recap omits archived line when archive is empty`() = runTest {
        val out = okOf(structured(recap, """{"scope": "full"}"""))
        assertFalse(out.contains("archived (compressed-away"))
    }
}
