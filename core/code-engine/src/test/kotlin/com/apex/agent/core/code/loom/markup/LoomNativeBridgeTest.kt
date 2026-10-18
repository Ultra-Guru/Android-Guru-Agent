package com.apex.agent.core.code.loom.markup

import com.apex.agent.core.llm.LlmStreamChunk
import com.apex.agent.core.llm.ToolCall
import com.apex.agent.core.llm.Usage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LoomNativeBridge 翻译测试：
 *
 * - content / reasoning / usage / finish 直通与包裹；
 * - 原生 toolCalls 增量装配（index 归并 / 名字分段拼接 / 参数 JSON 保真）；
 * - 装配刷出时机（内容帧后随 / 流收尾）与交错次序；
 * - 非法参数 JSON 降级 / 腰斩名字丢弃 / finish 幂等。
 */
class LoomNativeBridgeTest {

    private fun textFrames(frames: List<LoomNativeBridge.LoomWireFrame>): String =
        frames.filterIsInstance<LoomNativeBridge.LoomWireFrame.Text>()
            .joinToString("") { it.delta }

    // ── 直通与包裹 ─────────────────────────────────────────────────

    @Test
    fun `content frames pass through verbatim`() {
        val b = LoomNativeBridge()
        val out = b.feed(LlmStreamChunk(content = "你好")) +
            b.feed(LlmStreamChunk(content = "，世界"))
        assertEquals("你好，世界", textFrames(out))
    }

    @Test
    fun `reasoning frames are wrapped into one deliberate block`() {
        val b = LoomNativeBridge()
        val out = b.feed(LlmStreamChunk(reasoningContent = "先想 ")) +
            b.feed(LlmStreamChunk(reasoningContent = "再想")) +
            b.feed(LlmStreamChunk(content = "结论"))
        assertEquals(
            "<deliberate>先想 再想</deliberate>结论",
            textFrames(out)
        )
    }

    @Test
    fun `reasoning at stream end closes block at finish`() {
        val b = LoomNativeBridge()
        b.feed(LlmStreamChunk(reasoningContent = "思考"))
        val out = b.finish()
        assertEquals("<deliberate>思考</deliberate>", textFrames(out))
    }

    @Test
    fun `usage and finish pass through as frames`() {
        val b = LoomNativeBridge()
        val out = b.feed(
            LlmStreamChunk(usage = Usage(promptTokens = 10, completionTokens = 5, totalTokens = 15))
        ) + b.feed(LlmStreamChunk(isFinish = true, finishReason = "stop"))
        val usage = out.filterIsInstance<LoomNativeBridge.LoomWireFrame.Usage>().single()
        assertEquals(15, usage.totalTokens)
        val finish = out.filterIsInstance<LoomNativeBridge.LoomWireFrame.Finish>().single()
        assertEquals("stop", finish.reason)
    }

    // ── 原生调用装配 ───────────────────────────────────────────────

    @Test
    fun `tool call deltas assemble into use block at finish`() {
        val b = LoomNativeBridge()
        b.feed(
            LlmStreamChunk(
                toolCalls = listOf(ToolCall(id = "c1", name = "Code", arguments = "", index = 0))
            )
        )
        b.feed(
            LlmStreamChunk(
                toolCalls = listOf(ToolCall(id = "", name = "Read", arguments = "", index = 0))
            )
        )
        b.feed(
            LlmStreamChunk(
                toolCalls = listOf(
                    ToolCall(id = "", name = "", arguments = "{\"path\":", index = 0)
                )
            )
        )
        b.feed(
            LlmStreamChunk(
                toolCalls = listOf(
                    ToolCall(id = "", name = "", arguments = "\"a.kt\"}", index = 0)
                )
            )
        )
        val out = b.finish()
        val text = textFrames(out)
        assertTrue(text.contains("<use name=\"CodeRead\">"))
        assertTrue(text.contains("<arg name=\"path\">a.kt</arg>"))
        assertTrue(text.contains("</use>"))
    }

    @Test
    fun `parallel tool calls keyed by index stay separate`() {
        val b = LoomNativeBridge()
        b.feed(
            LoomStreamChunk(
                toolCalls = listOf(
                    ToolCall(id = "a", name = "T1", arguments = "{\"x\":\"1\"}", index = 0),
                    ToolCall(id = "b", name = "T2", arguments = "{\"y\":\"2\"}", index = 1)
                )
            )
        )
        val out = b.finish()
        val text = textFrames(out)
        assertTrue(text.contains("<use name=\"T1\">"))
        assertTrue(text.contains("<use name=\"T2\">"))
        assertTrue(text.indexOf("T1") < text.indexOf("T2"))
    }

    @Test
    fun `content after tool frames flushes calls first`() {
        val b = LoomNativeBridge()
        b.feed(
            LlmStreamChunk(
                toolCalls = listOf(ToolCall(id = "a", name = "Echo", arguments = "{}", index = 0))
            )
        )
        val out = b.feed(LlmStreamChunk(content = "补充说明"))
        val text = textFrames(out)
        // 调用块先于内容帧（保交错次序）
        assertTrue(text.indexOf("<use name=\"Echo\">") < text.indexOf("补充说明"))
    }

    @Test
    fun `invalid arguments json degrades to raw arg passthrough`() {
        val b = LoomNativeBridge()
        b.feed(
            LlmStreamChunk(
                toolCalls = listOf(ToolCall(id = "a", name = "T", arguments = "not json", index = 0))
            )
        )
        val out = b.finish()
        val text = textFrames(out)
        assertTrue(text.contains("<arg name=\"raw\">not json</arg>"))
    }

    @Test
    fun `nameless call delta is dropped`() {
        val b = LoomNativeBridge()
        b.feed(
            LoomStreamChunk(
                toolCalls = listOf(ToolCall(id = "a", name = "", arguments = "{}", index = 0))
            )
        )
        val out = b.finish()
        assertEquals("", textFrames(out))
    }

    @Test
    fun `index-less deltas get distinct slots by arrival order`() {
        val b = LoomNativeBridge()
        b.feed(
            LlmStreamChunk(
                toolCalls = listOf(ToolCall(id = "a", name = "A", arguments = "{}", index = -1))
            )
        )
        b.feed(
            LlmStreamChunk(
                toolCalls = listOf(ToolCall(id = "b", name = "B", arguments = "{}", index = -1))
            )
        )
        val out = b.finish()
        val text = textFrames(out)
        assertTrue(text.contains("<use name=\"A\">"))
        assertTrue(text.contains("<use name=\"B\">"))
    }

    // ── 幂等与复位 ─────────────────────────────────────────────────

    @Test
    fun `finish is idempotent`() {
        val b = LoomNativeBridge()
        b.feed(LlmStreamChunk(content = "x"))
        b.finish()
        assertEquals(emptyList<LoomNativeBridge.LoomWireFrame>(), b.finish())
    }

    @Test
    fun `reset clears assembly state`() {
        val b = LoomNativeBridge()
        b.feed(
            LoomStreamChunk(
                toolCalls = listOf(ToolCall(id = "a", name = "T", arguments = "{}", index = 0))
            )
        )
        b.reset()
        assertEquals(emptyList<LoomNativeBridge.LoomWireFrame>(), b.finish())
        // 复位后正常翻译
        val out = b.feed(LlmStreamChunk(content = "ok"))
        assertEquals("ok", textFrames(out))
    }
}
