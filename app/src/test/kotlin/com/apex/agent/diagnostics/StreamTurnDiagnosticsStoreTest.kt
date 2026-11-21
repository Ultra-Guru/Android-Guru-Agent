package com.apex.agent.diagnostics

import com.apex.agent.core.llm.StreamDiagnostics
import com.apex.agent.core.llm.UsageSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [StreamTurnDiagnosticsStore] 环形存储测试（SPE-08 P1-1 / SPE-09）。
 *
 * 断言：容量上限淘汰最旧、新→旧排序快照隔离、空态。
 */
class StreamTurnDiagnosticsStoreTest {

    private fun stats(turnId: String): StreamDiagnostics = StreamDiagnostics(
        turnId = turnId,
        framesReceived = 1,
        usageSource = UsageSource.REAL
    )

    @Test
    fun `ring evicts oldest beyond capacity and returns newest first`() {
        val store = StreamTurnDiagnosticsStore()
        repeat(15) { store.record(stats("t-$it")) } // 容量 10：保留 t-5..t-14
        val recent = store.recent()
        assertEquals(10, recent.size)
        assertEquals("新→旧", "t-14", recent.first().turnId)
        assertEquals("最旧保留到 t-5", "t-5", recent.last().turnId)
        assertEquals(10, store.size())
    }

    @Test
    fun `snapshot list is isolated from subsequent writes`() {
        val store = StreamTurnDiagnosticsStore()
        store.record(stats("a"))
        val snapshot = store.recent()
        store.record(stats("b"))
        // 读取快照与后续写入隔离（不可变列表拷贝）。
        assertEquals(1, snapshot.size)
        assertEquals(2, store.recent().size)
    }

    @Test
    fun `empty state is empty list`() {
        val store = StreamTurnDiagnosticsStore()
        assertTrue(store.recent().isEmpty())
        assertEquals(0, store.size())
    }
}
