package com.apex.agent.core.code.standard

import com.apex.agent.core.code.CodeConversationMemory
import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.ToolCall
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [loadNormalized] 行为契约（标准线悬空 toolCall 400 自愈）：
 *
 * 复盘链路（2026-10 实机录屏）：标准线回合在工具执行中被中止 →
 * CodeConversationMemory 落盘末尾留下无配对的 Assistant.toolCalls →
 * 恢复后每个后续请求 400（"tool_calls must be followed by tool
 * messages"）→ 会话级报废、agent「看不到上面的对话」。深潜线在
 * buildMessages 逐请求修补；本入口把同款归一化接到标准线 KitMemory.load。
 */
class StandardHistoryNormalizationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun assistantWithCalls(vararg ids: String) =
        LlmMessage.Assistant("working", ids.map { ToolCall(it, "tool_x", "{}") })

    @Test
    fun `dangling tail toolCalls get synthesized ToolResult and memory is rewritten`() {
        val memory = CodeConversationMemory(tmp.newFolder())
        // 模拟「死于工具执行中」的落盘历史：末尾 Assistant 带 c1/c2 无应答
        memory.save(
            listOf(
                LlmMessage.User("run tests"),
                LlmMessage.Assistant("start", listOf(ToolCall("c0", "tool_a", "{}"))),
                LlmMessage.ToolResult("c0", "ok"),
                assistantWithCalls("c1", "c2")
            )
        )

        val loaded = memory.loadNormalized()

        // 补发两个合成 ToolResult（c1/c2），前序历史不动
        assertEquals(6, loaded.size)
        val tail = loaded.takeLast(2).filterIsInstance<LlmMessage.ToolResult>()
        assertEquals(listOf("c1", "c2"), tail.map { it.toolCallId })
        // 回写生效：再次加载（缓存直读）拿到的是同一份已修补历史
        assertEquals(loaded, memory.loadNormalized())
    }

    @Test
    fun `clean history passes through untouched`() {
        val memory = CodeConversationMemory(tmp.newFolder())
        val clean = listOf(
            LlmMessage.User("hi"),
            LlmMessage.Assistant("plan", listOf(ToolCall("c1", "tool_a", "{}"))),
            LlmMessage.ToolResult("c1", "ok"),
            LlmMessage.Assistant("done")
        )
        memory.save(clean)

        assertEquals(clean, memory.loadNormalized())
    }

    @Test
    fun `normalization rewrites disk and stays scoped per workspace`() {
        val base = tmp.newFolder()
        val memory = CodeConversationMemory(base)
        memory.bindWorkspace("wsA")
        memory.save(listOf(LlmMessage.User("a"), assistantWithCalls("cA")))
        // 触发 A 槽位归一化（缓存修补 + 异步落盘调度）
        val a = memory.loadNormalized()
        assertEquals(3, a.size) // User + Assistant + 合成 ToolResult
        assertTrue(a.last() is LlmMessage.ToolResult)

        // A 槽位仍绑定时轮询落盘到账（bind 切换会让挂起持久化改写新槽位
        // —— 落盘断言必须留在本槽位内做才是确定性的）
        val wsAFile = File(base, "ws_wsA.json")
        val flushed = pollUntil(2_000L) {
            wsAFile.exists() && wsAFile.readText().contains("\"toolCallId\":\"cA\"")
        }
        assertTrue("落盘含合成应答（异步持久化到账）", flushed)

        // B 槽位不受 A 修补影响
        memory.bindWorkspace("wsB")
        memory.save(listOf(LlmMessage.User("b")))
        assertEquals(listOf(LlmMessage.User("b")), memory.loadNormalized())

        // 回绑 A：从盘上读到的已是归一化历史（无需再修补）
        memory.bindWorkspace("wsA")
        val reloaded = memory.loadNormalized()
        assertEquals(3, reloaded.size)
        assertTrue(reloaded.last() is LlmMessage.ToolResult)
    }

    /** 截止时间内轮询条件（异步持久化的确定性等待，避免裸 sleep）。 */
    private fun pollUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }
}
