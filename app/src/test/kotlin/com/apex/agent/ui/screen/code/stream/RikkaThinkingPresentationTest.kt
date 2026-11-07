package com.apex.agent.ui.screen.code.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RikkaThinkingPresentationTest {
    @Test fun `burst input is paced rather than revealed in one frame`() {
        val reveal = RikkaThinkingReveal()
        val full = "第一句正在思考。第二句仍在推理。"
        val first = reveal.advance(full, isStreaming = true)
        assertTrue(first.isNotEmpty())
        assertTrue(first.length < full.length)
        val second = reveal.advance(full, isStreaming = true)
        assertTrue(second.length > first.length)
        assertTrue(second.length < full.length)
    }

    @Test fun `append does not replay previous text and preserves surrogate pairs`() {
        val reveal = RikkaThinkingReveal()
        assertEquals("😀", reveal.advance("😀你好", isStreaming = true))
        assertEquals("😀你", reveal.advance("😀你好", isStreaming = true))
        assertEquals("😀你好", reveal.advance("😀你好！", isStreaming = true))
        assertEquals("😀你好！", reveal.advance("😀你好！", isStreaming = true))
    }

    @Test fun `completion flushes authoritative full text without dropping tail`() {
        val reveal = RikkaThinkingReveal()
        reveal.advance("未完成", isStreaming = true)
        assertEquals("完整内容。尾句", reveal.advance("完整内容。尾句", isStreaming = false))
    }

    @Test fun `skip after body starts reveals full thought without changing source`() {
        val reveal = RikkaThinkingReveal()
        reveal.advance("思考一二三四", isStreaming = true)
        assertEquals("思考一二三四", reveal.advance("思考一二三四", isStreaming = true, skip = true))
        assertEquals("思考一二三四", reveal.advance("思考一二三四", isStreaming = true))
        assertFalse(reveal.advance("思考一二三四", isStreaming = true).isEmpty())
    }

    @Test fun `collapsed preview is labelled first sentence not fabricated summary`() {
        assertEquals("先核对输入。", rikkaThinkingFirstSentence("\n先核对输入。随后写测试。"))
        assertEquals("没有句号的完整首行", rikkaThinkingFirstSentence("没有句号的完整首行\n第二行"))
        assertEquals("", rikkaThinkingFirstSentence("  \n  "))
    }
}
