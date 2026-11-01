package com.apex.agent.core.code

import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [KitRequestSanitizer] 行为契约（kit 三线请求出站消毒）。
 *
 * 复盘链路（2026-11-01 实机诊断包截图，标准线）：工具执行中用户点停止 →
 * 硬取消绕过库内「中止补齐占位结果」收尾 → 库引擎内存态 session 留下无配对
 * Assistant.toolCalls → 再次发消息/点「继续」时直接装配请求 → 400
 * "tool_calls must be followed by tool messages" → 库容错降级无工具重发
 * （消息列表未动）→ 二次 400 → 致命错误，会话级报废。
 *
 * 本消毒口挂在三条 kit 线模型客户端的出站路径上——无论库引擎内存态处于
 * 何种中断残局，出站请求的配对协议恒成立。
 */
class KitRequestSanitizerTest {

    private fun call(id: String) = ToolCall(id, "tool_x", "{}")

    private fun assistantWithCalls(vararg ids: String) =
        LlmMessage.Assistant("working", ids.map { call(it) })

    /**
     * OpenAI 兼容端点的配对协议校验（与 400 报文同口径）：
     * 1. 带 toolCalls 的 Assistant 之后必须**紧随** ToolResult 块；
     * 2. 块内每个 tool_call_id 恰好应答一次；
     * 3. 块外不得出现 ToolResult（孤儿应答同样拒绝）。
     */
    private fun assertPairingProtocolValid(messages: List<LlmMessage>) {
        var i = 0
        while (i < messages.size) {
            val msg = messages[i]
            if (msg is LlmMessage.Assistant && msg.toolCalls.isNotEmpty()) {
                val expected = msg.toolCalls.map { it.id }.toMutableSet()
                var j = i + 1
                while (j < messages.size && messages[j] is LlmMessage.ToolResult) {
                    val tr = messages[j] as LlmMessage.ToolResult
                    assertTrue(
                        "tool_call_id ${tr.toolCallId} 未在声明集内或重复应答",
                        expected.remove(tr.toolCallId)
                    )
                    j++
                }
                assertTrue("悬空 tool_calls 未被应答: $expected", expected.isEmpty())
                i = j
            } else {
                assertTrue(
                    "块外孤儿 ToolResult: ${messages[i]}",
                    msg !is LlmMessage.ToolResult
                )
                i++
            }
        }
    }

    @Test
    fun `clean history passes through untouched`() {
        val clean = listOf(
            LlmMessage.System("sys"),
            LlmMessage.User("run tests"),
            LlmMessage.Assistant("plan", listOf(call("c1"))),
            LlmMessage.ToolResult("c1", "ok"),
            LlmMessage.Assistant("done")
        )
        // 无悬空 → 原列表原样返回（零拷贝零改写）
        assertSame(clean, KitRequestSanitizer.sanitize(clean, "test"))
        assertPairingProtocolValid(clean)
    }

    @Test
    fun `dangling tail toolCalls get synthesized ToolResult`() {
        // 「死于工具执行中」：末尾 Assistant 带 c1/c2 无应答
        val history = listOf(
            LlmMessage.System("sys"),
            LlmMessage.User("run"),
            LlmMessage.Assistant("start", listOf(call("c0"))),
            LlmMessage.ToolResult("c0", "ok"),
            assistantWithCalls("c1", "c2")
        )
        val sanitized = KitRequestSanitizer.sanitize(history, "test")

        assertEquals(history.size + 2, sanitized.size)
        assertPairingProtocolValid(sanitized)
        // 合成应答紧随悬空 Assistant（协议要求 tool 消息紧跟 tool_calls）
        val tail = sanitized.takeLast(2).filterIsInstance<LlmMessage.ToolResult>()
        assertEquals(listOf("c1", "c2"), tail.map { it.toolCallId })
        // 原历史不被修改（纯函数契约）
        assertEquals(5, history.size)
    }

    @Test
    fun `abort then continue - dangling pair mid-history gets repaired`() {
        // 用户场景还原：中止（A(c1) 无应答落进 session）→ 用户再发消息 →
        // 引擎追加了新消息。悬空配对在历史**中部**，不在末尾。
        val history = listOf(
            LlmMessage.System("sys"),
            LlmMessage.User("fix the build"),
            assistantWithCalls("c1"),
            LlmMessage.User("继续"),
            LlmMessage.Assistant("let me check")
        )
        val sanitized = KitRequestSanitizer.sanitize(history, "test")

        assertPairingProtocolValid(sanitized)
        // 合成 ToolResult 插在悬空 Assistant 与下一条非工具消息之间
        val idxAssistant = sanitized.indexOfFirst {
            it is LlmMessage.Assistant && it.toolCalls.isNotEmpty()
        }
        val after = sanitized[idxAssistant + 1]
        assertTrue(
            "Assistant(toolCalls) 后必须紧跟 ToolResult，实际是 $after",
            after is LlmMessage.ToolResult && after.toolCallId == "c1"
        )
    }

    @Test
    fun `orphan toolResults are dropped`() {
        // 反向残局：ToolResult 存活而声明它的 Assistant 已被压缩窗口切掉
        val history = listOf(
            LlmMessage.System("sys"),
            LlmMessage.User("hi"),
            LlmMessage.ToolResult("ghost", "orphan output"),
            LlmMessage.Assistant("reply")
        )
        val sanitized = KitRequestSanitizer.sanitize(history, "test")

        assertPairingProtocolValid(sanitized)
        assertTrue(
            "孤儿 ToolResult 应被丢弃",
            sanitized.none { it is LlmMessage.ToolResult }
        )
        assertEquals(3, sanitized.size)
    }

    @Test
    fun `sanitize is idempotent`() {
        val history = listOf(
            LlmMessage.User("run"),
            assistantWithCalls("c1"),
            LlmMessage.User("继续")
        )
        val once = KitRequestSanitizer.sanitize(history, "test")
        val twice = KitRequestSanitizer.sanitize(once, "test")
        // 第二趟零动作：既不改写也不增删
        assertEquals(once, twice)
        assertPairingProtocolValid(twice)
    }

    @Test
    fun `blank id toolCalls survive without synthesis`() {
        // 无 id 的畸形声明（流式分片丢失）：不合成应答也不剥声明——
        // 交由 provider 侧报文决定；本消毒口只保证已知 id 的配对
        val history = listOf(
            LlmMessage.User("run"),
            LlmMessage.Assistant("working", listOf(ToolCall("", "tool_x", "{}")))
        )
        val sanitized = KitRequestSanitizer.sanitize(history, "test")
        assertEquals(history, sanitized)
    }
}
