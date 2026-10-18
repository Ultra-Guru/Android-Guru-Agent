package com.apex.agent.core.engine.compression

import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.tools.builtin.context.InMemoryContextArchive
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #396 归档压缩装饰器回归锁：
 *
 * - 压缩前快照 → 差集归档：被裁剪的原始消息进归档，完好保留的不进；
 * - 就地截断的工具结果（两次判不等）以**原始全文**进归档；
 * - `[CONTEXT ARCHIVE]` 检索单 upsert（重复压缩不堆叠，计数更新）；
 * - 归档为空时移除残余注记；
 * - needsCompression / updateContextWindow 纯转发。
 */
class ArchivingCompressorTest {

    /** 假压缩器：把头部 system 之后、保留窗之前的中段整体替换成一条摘要 System。 */
    private class FakeSummarizer : ContextCompressor {
        var calls = 0
        override fun needsCompression(
            history: List<LlmMessage>, maxTokens: Int, threshold: Float
        ): Boolean = history.size > 4

        override suspend fun compress(
            history: MutableList<LlmMessage>, preserveRecent: Int
        ): CompressionReport {
            calls++
            val system = history.filterIsInstance<LlmMessage.System>().take(1)
            val preserved = history.takeLast(preserveRecent)
            history.clear()
            history += system
            history += LlmMessage.System("[COMPRESSED CONTEXT - Summary of previous conversation]\nsummary\n[END COMPRESSED CONTEXT - Recent messages follow]")
            history += preserved
            return CompressionReport(
                beforeTokens = 1000, afterTokens = 100, strategy = CompressionStrategy.LLM_SUMMARY,
                summary = "summary", messagesRemoved = 10, messagesTruncated = 0
            )
        }
    }

    private fun history(): MutableList<LlmMessage> = mutableListOf(
        LlmMessage.System("system prompt"),
        LlmMessage.User("u1 original"),
        LlmMessage.Assistant("", toolCalls = listOf(com.apex.agent.core.llm.ToolCall("c1", "Read", "{}"))),
        LlmMessage.ToolResult("c1", "result original full text"),
        LlmMessage.User("u2 keep"),
        LlmMessage.Assistant("a2 keep")
    )

    @Test
    fun `pruned messages are archived verbatim and preserved ones are not`() = runTest {
        val delegate = FakeSummarizer()
        val archive = InMemoryContextArchive()
        val compressor = ArchivingCompressor(delegate, archive)
        val history = history()

        compressor.compress(history, preserveRecent = 2)

        assertEquals(1, delegate.calls)
        // 中段 3 条（u1 / assistant(tool_call) / toolresult 被摘要替换）进归档；
        // system 与保留窗（u2/a2）完好保留不进。
        val archived = archive.archivedRecords()
        assertEquals(3, archive.size())
        assertTrue(archived.any { it.content == "u1 original" })
        assertTrue(archived.any { it.content == "result original full text" })
        assertFalse(archived.any { it.content == "system prompt" })
        assertFalse(archived.any { it.content == "u2 keep" })
    }

    @Test
    fun `in-place truncated tool output archives its original form`() = runTest {
        val delegate = object : ContextCompressor {
            override fun needsCompression(
                history: List<LlmMessage>, maxTokens: Int, threshold: Float
            ): Boolean = true

            override suspend fun compress(
                history: MutableList<LlmMessage>, preserveRecent: Int
            ): CompressionReport {
                // 就地截断工具输出（原 100 字符 → 5 字符）：结构仍在但内容已变
                val idx = history.indexOfFirst { it is LlmMessage.ToolResult }
                history[idx] = LlmMessage.ToolResult("c1", "trunc")
                return CompressionReport(100, 90, CompressionStrategy.TOOL_TRUNCATION, "t", 0, 1)
            }
        }
        val archive = InMemoryContextArchive()
        val history = mutableListOf<LlmMessage>(
            LlmMessage.System("sys"), LlmMessage.User("u"),
            LlmMessage.ToolResult("c1", "original one hundred chars ".repeat(3))
        )
        ArchivingCompressor(delegate, archive).compress(history, 5)

        val archived = archive.archivedRecords()
        assertEquals(1, archive.size())
        assertEquals("tool", archived[0].role)
        assertTrue("归档必须保留截断前的原文", archived[0].content.startsWith("original one hundred chars"))
    }

    @Test
    fun `archive note is upserted once and never stacks`() = runTest {
        val delegate = FakeSummarizer()
        val archive = InMemoryContextArchive()
        val compressor = ArchivingCompressor(delegate, archive)
        val history = history()

        compressor.compress(history, preserveRecent = 2)
        compressor.compress(history, preserveRecent = 2)

        val notes = history.filterIsInstance<LlmMessage.System>()
            .filter { it.content.startsWith("[CONTEXT ARCHIVE] ") }
        assertEquals("检索单全局唯一（重复压缩原位更新）", 1, notes.size)
        assertTrue(notes[0].content.contains("ContextSearch"))
        assertTrue(notes[0].content.contains("ContextFetch"))
        // 注记插在头部系统块（system prompt + 压缩摘要）之后
        val noteIndex = history.indexOfFirst { it is LlmMessage.System && it.content.startsWith("[CONTEXT ARCHIVE] ") }
        assertEquals(2, noteIndex)
    }

    @Test
    fun `note is removed when archive is empty after external clear`() = runTest {
        val summarizer = FakeSummarizer()
        val archive = InMemoryContextArchive()
        val compressor = ArchivingCompressor(summarizer, archive)
        val history = history()

        compressor.compress(history, preserveRecent = 2)
        assertTrue(history.any { it is LlmMessage.System && it.content.startsWith("[CONTEXT ARCHIVE] ") })

        // 会话边界清空归档后，后续压缩无裁剪（no-op）→ 残余注记应被移除
        archive.clear()
        val noop = object : ContextCompressor {
            override fun needsCompression(
                history: List<LlmMessage>, maxTokens: Int, threshold: Float
            ): Boolean = true

            override suspend fun compress(
                history: MutableList<LlmMessage>, preserveRecent: Int
            ): CompressionReport = CompressionReport(100, 100, CompressionStrategy.NONE, "noop", 0, 0)
        }
        ArchivingCompressor(noop, archive).compress(history, preserveRecent = 2)
        assertFalse(history.any { it is LlmMessage.System && it.content.startsWith("[CONTEXT ARCHIVE] ") })
    }

    @Test
    fun `nothing archived when delegate compresses nothing`() = runTest {
        val delegate = object : ContextCompressor {
            override fun needsCompression(
                history: List<LlmMessage>, maxTokens: Int, threshold: Float
            ): Boolean = false

            override suspend fun compress(
                history: MutableList<LlmMessage>, preserveRecent: Int
            ): CompressionReport = CompressionReport(100, 100, CompressionStrategy.NONE, "noop", 0, 0)
        }
        val archive = InMemoryContextArchive()
        val history = history()
        ArchivingCompressor(delegate, archive).compress(history, 5)
        assertEquals(0, archive.size())
        assertFalse(history.any { it is LlmMessage.System && it.content.startsWith("[CONTEXT ARCHIVE] ") })
    }

    @Test
    fun `needsCompression and updateContextWindow are pure delegation`() {
        val delegate = FakeSummarizer()
        val compressor = ArchivingCompressor(delegate, InMemoryContextArchive())
        assertTrue(compressor.needsCompression(history(), 100, 0.1f))
        assertFalse(compressor.needsCompression(listOf(LlmMessage.System("s")), 100, 0.1f))
        compressor.updateContextWindow(2000)
    }
}
