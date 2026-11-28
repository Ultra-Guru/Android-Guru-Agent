package com.apex.agent.core.engine.compression

import com.apex.agent.core.llm.ImageContent
import com.apex.agent.core.llm.LlmMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #827 — [HistoryImageEvictor] keep-last-K 退场策略测试（纯 JVM，无 mock）。
 *
 * 断言：
 * 1. 最近 K 个携图轮的图片原样保留；
 * 2. 更早的携图轮以占位文本替换（**原文保留**、图片剥离）；
 * 3. 输入历史绝不被修改（元素不可变 copy，列表元素引用不变）；
 * 4. 无退场（无图 / 携图轮 ≤ K）返回同一实例（调用方短路依据）。
 */
class HistoryImageEvictorTest {

    private fun img(id: Int) = ImageContent(base64Data = "", url = "https://example.com/$id.png")

    private fun user(text: String, imageCount: Int) =
        if (imageCount == 0) LlmMessage.User(text)
        else LlmMessage.User(text, List(imageCount) { img(it) })

    /** 3 个携图轮夹 Assistant 回复的典型多图会话历史。 */
    private fun agedHistory(): MutableList<LlmMessage> = mutableListOf(
        LlmMessage.System("sys"),
        user("带图任务一", 2),
        LlmMessage.Assistant("回复一"),
        user("带图任务二", 1),
        LlmMessage.Assistant("回复二"),
        user("带图任务三", 1),
        LlmMessage.Assistant("回复三"),
    )

    @Test
    fun `images within last K image-bearing turns are preserved`() {
        val history = agedHistory()
        val evicted = HistoryImageEvictor.evict(history, keepLastK = 2)
        // 最近 2 个携图轮（索引 3 / 5）原样保留（图片与原文都不动）。
        val kept1 = evicted[3] as LlmMessage.User
        val kept2 = evicted[5] as LlmMessage.User
        assertEquals(1, kept1.images.size)
        assertEquals("带图任务二", kept1.content)
        assertEquals(1, kept2.images.size)
        assertEquals("带图任务三", kept2.content)
        assertFalse(kept1.content.contains(HistoryImageEvictor.PLACEHOLDER))
        // 非携图消息不经手（同一元素引用直通）。
        assertTrue("non-image messages must pass by reference", evicted[0] === history[0])
        assertTrue(evicted[2] === history[2])
    }

    @Test
    fun `images older than last K turns are evicted with placeholder`() {
        val evicted = HistoryImageEvictor.evict(agedHistory(), keepLastK = 2)
        val evictedTurn = evicted[1] as LlmMessage.User
        assertTrue("images must be stripped", evictedTurn.images.isEmpty())
        assertTrue(
            "original text must survive, got '${evictedTurn.content}'",
            evictedTurn.content.contains("带图任务一")
        )
        assertTrue(
            "placeholder must mark the omission",
            evictedTurn.content.contains(HistoryImageEvictor.PLACEHOLDER)
        )
        // keepLastK=0：全部携图轮退场。
        val evictAll = HistoryImageEvictor.evict(agedHistory(), keepLastK = 0)
        assertTrue((evictAll[1] as LlmMessage.User).images.isEmpty())
        assertTrue((evictAll[3] as LlmMessage.User).images.isEmpty())
        assertTrue((evictAll[5] as LlmMessage.User).images.isEmpty())
    }

    @Test
    fun `input history is never mutated`() {
        val history = agedHistory()
        val snapshotContents = history.map { (it as? LlmMessage.User)?.content ?: "" }
        val evicted = HistoryImageEvictor.evict(history, keepLastK = 2)
        // 输入列表本体：条数不变、元素未被改写（老元素仍带图、原文未动）。
        assertEquals(snapshotContents.size, history.size)
        val oldTurn = history[1] as LlmMessage.User
        assertEquals(2, oldTurn.images.size)
        assertEquals("带图任务一", oldTurn.content)
        assertFalse(oldTurn.content.contains(HistoryImageEvictor.PLACEHOLDER))
        // 退场产出是新列表（copy 替换，不与输入共享可变结构）。
        assertFalse(evicted === history)
        assertTrue(evicted[1] !== history[1])
    }

    @Test
    fun `no eviction returns the same instance`() {
        val noImages = listOf<LlmMessage.User>(user("纯文本", 0), user("也无图", 0))
        assertSame(noImages, HistoryImageEvictor.evict(noImages))
        val withinK = listOf(user("图一", 1), user("图二", 1))
        assertSame(withinK, HistoryImageEvictor.evict(withinK))
        assertSame(emptyList<LlmMessage>(), HistoryImageEvictor.evict(emptyList()))
    }

    @Test
    fun `blank content gets placeholder without leading newline`() {
        val history = listOf(user("", 1), user("新图", 1))
        val evicted = HistoryImageEvictor.evict(history, keepLastK = 1)
        assertEquals(
            HistoryImageEvictor.PLACEHOLDER,
            (evicted[0] as LlmMessage.User).content
        )
    }
}
