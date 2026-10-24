package com.apex.agent.core.code.stream

import com.apex.agent.core.engine.AgentEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CodeStreamSession] 参数增量（[AgentEvent.ToolCallArgsDelta]）归约测试。
 *
 * 覆盖：等待态胶囊建立与实时预览刷新 / Start 到达后的升级（幂等不重复
 * 计数）/ Complete 清空预览（代码窗自动收回）/ 迟到分片忽略 / 中止收口 /
 * MCP 族不建等待态胶囊 / 名字晚到 provider 的缓冲回放 / BASH 终端三信号。
 */
class CodeStreamArgsDeltaTest {

    private fun delta(callId: String, toolName: String, args: String) =
        AgentEvent.ToolCallArgsDelta(callId = callId, toolName = toolName, delta = args)

    private fun capsules(s: CodeStreamSession): List<StreamToolCall> =
        s.snapshot().entries.filterIsInstance<StreamEntry.ToolCapsuleEntry>().map { it.call }

    @Test
    fun `write deltas create waiting capsule with live preview then upgrade`() {
        val s = CodeStreamSession()
        s.beginRun("写个文件")
        s.onEvent(delta("w1", "CodeWrite", "{\"path\":\"src/A.kt\",\"content\":\"fun a()"))
        s.onEvent(delta("w1", "CodeWrite", " {}\"}"))
        val waiting = capsules(s).single()
        assertEquals(ToolCallStatus.WAITING, waiting.status)
        assertEquals(ToolKind.WRITE_FILE, waiting.kind)
        assertEquals("src/A.kt", waiting.target)
        assertEquals("fun a() {}", waiting.streamingPreview?.writeContent)
        assertEquals(1, s.snapshot().toolCallCount)

        // Start 到达：等待态升级为运行态，预览保留（执行期代码窗持续）
        s.onEvent(
            AgentEvent.ToolCallStart(
                "w1", "CodeWrite",
                """{"path":"src/A.kt","content":"fun a() {}"}"""
            )
        )
        val running = capsules(s).single()
        assertEquals(ToolCallStatus.RUNNING, running.status)
        assertEquals("src/A.kt", running.target)
        assertEquals("fun a() {}", running.streamingPreview?.writeContent)
        // 升级不重复计数
        assertEquals(1, s.snapshot().toolCallCount)

        // Complete：终态清空预览（代码窗收回，折叠为单行胶囊）
        s.onEvent(
            AgentEvent.ToolCallComplete(
                callId = "w1", toolName = "CodeWrite",
                arguments = """{"path":"src/A.kt"}""",
                output = "(written)", fullOutput = "",
                success = true, durationMs = 50
            )
        )
        val done = capsules(s).single()
        assertEquals(ToolCallStatus.SUCCESS, done.status)
        assertNull(done.streamingPreview)
        assertEquals(1, s.snapshot().toolCallCount)
    }

    @Test
    fun `deltas arriving after start are ignored`() {
        val s = CodeStreamSession()
        s.beginRun("task")
        s.onEvent(
            AgentEvent.ToolCallStart("b1", "TerminalExec", """{"command":"ls"}""")
        )
        // Start 已达（RUNNING，全量参数在胶囊里）：迟到/重放分片无信息量
        s.onEvent(delta("b1", "TerminalExec", """{"command":"rm -rf""""))
        val call = capsules(s).single()
        assertEquals(ToolCallStatus.RUNNING, call.status)
        assertNull(call.streamingPreview)
        assertEquals("ls", call.target)
    }

    @Test
    fun `abort during args generation finalizes waiting capsule`() {
        val s = CodeStreamSession()
        s.beginRun("写一半")
        s.onEvent(delta("w1", "CodeWrite", "{\"path\":\"x.kt\",\"content\":\"half"))
        assertEquals(ToolCallStatus.WAITING, capsules(s).single().status)
        s.abortLocally()
        val call = capsules(s).single()
        assertEquals(ToolCallStatus.PARTIAL, call.status)
        assertNull(call.streamingPreview)
    }

    @Test
    fun `mcp kind deltas do not create waiting capsule`() {
        val s = CodeStreamSession()
        s.beginRun("查天气")
        // MCP 族无追踪器（参数小、无「写入过程」可展示）→ 不建等待态胶囊
        s.onEvent(delta("m1", "mcp_weather", """{"city":"SF","unit":"c"}"""))
        assertTrue(capsules(s).isEmpty())
        // Start 到达按原路径建卡（旧管线行为零回退）
        s.onEvent(
            AgentEvent.ToolCallStart("m1", "mcp_weather", """{"city":"SF"}""")
        )
        val call = capsules(s).single()
        assertEquals(ToolCallStatus.RUNNING, call.status)
        assertEquals(ToolKind.MCP_CUSTOM, call.kind)
    }

    @Test
    fun `late tool name buffers fragments and replays on arrival`() {
        val s = CodeStreamSession()
        s.beginRun("名字晚到的 provider")
        // 首片名空：无族可建槽 → 缓冲，不建胶囊
        s.onEvent(delta("w2", "", "{\"content\":\"early bytes"))
        assertTrue(capsules(s).isEmpty())
        // 名字到达：建槽回放全量缓冲 → 等待态胶囊带完整预览出现
        s.onEvent(delta("w2", "CodeWrite", "\",\"path\":\"c.kt\"}"))
        val call = capsules(s).single()
        assertEquals(ToolKind.WRITE_FILE, call.kind)
        assertEquals("c.kt", call.target)
        assertEquals("early bytes", call.streamingPreview?.writeContent)
    }

    @Test
    fun `bash deltas preview command and upgrade drives terminal signals`() {
        val s = CodeStreamSession()
        s.beginRun("跑构建")
        s.onEvent(delta("b1", "TerminalExec", "{\"command\":\"./gradlew build"))
        s.onEvent(delta("b1", "TerminalExec", "\"}"))
        val waiting = capsules(s).single()
        assertEquals(ToolKind.BASH, waiting.kind)
        assertEquals("./gradlew build", waiting.target)

        // 升级为运行态：终端面板三信号照常接管（busy 展开 + 头行命令）
        s.onEvent(
            AgentEvent.ToolCallStart("b1", "TerminalExec", """{"command":"./gradlew build"}""")
        )
        val snap = s.snapshot()
        assertTrue(snap.terminalBusy)
        assertEquals("./gradlew build", snap.terminalCommandSummary)
        assertEquals("b1", snap.activeTerminalCallId)
    }

    @Test
    fun `edit deltas expose old and new preview then complete clears`() {
        val s = CodeStreamSession()
        s.beginRun("改一处")
        s.onEvent(delta("e1", "CodeEdit", "{\"old_string\":\"foo\",\"new_string\":\"bar"))
        s.onEvent(delta("e1", "CodeEdit", "\"}"))
        val waiting = capsules(s).single()
        assertEquals(ToolKind.EDIT_FILE, waiting.kind)
        assertEquals("foo", waiting.streamingPreview?.editOld)
        assertEquals("bar", waiting.streamingPreview?.editNew)
        s.onEvent(
            AgentEvent.ToolCallStart(
                "e1", "CodeEdit",
                """{"path":"a.kt","old_string":"foo","new_string":"bar"}"""
            )
        )
        s.onEvent(
            AgentEvent.ToolCallComplete(
                callId = "e1", toolName = "CodeEdit",
                arguments = """{"path":"a.kt"}""",
                output = "(no textual change)",
                fullOutput = "--- a/a.kt\n+++ b/a.kt\n@@ -1 +1 @@\n-foo\n+bar\n(1 added, 1 removed)",
                success = true, durationMs = 30
            )
        )
        val done = capsules(s).single()
        assertEquals(ToolCallStatus.APPLIED, done.status)
        assertNull(done.streamingPreview)
    }

    @Test
    fun `late path completion refreshes waiting capsule target`() {
        val s = CodeStreamSession()
        s.beginRun("路径后到")
        // content 先到、path 后到：胶囊先以…建卡，path 到达后目标刷新
        s.onEvent(delta("w3", "CodeWrite", "{\"content\":\"code first"))
        s.onEvent(delta("w3", "CodeWrite", "\",\"path\":\"late.kt\"}"))
        val call = capsules(s).single()
        assertEquals("late.kt", call.target)
        assertEquals("code first", call.streamingPreview?.writeContent)
    }
}
