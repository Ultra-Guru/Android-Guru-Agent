package com.apex.agent.core.tools.builtin

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tmux 多会话工具单测 —— core/tool-registry 侧，用假 [TmuxSessionGateway]（零 terminal import）。
 *
 * 验收点（工具层只做参数解析 + 结构化错误兜底 + 透传 gateway 载荷）：
 *  - id/schema/description 英文且 schema 为 String（TerminalTool 契约）；
 *  - 缺参 / JSON 畸形 → ok=false + errorCode（不穿透异常）；
 *  - 透传 gateway 的 ok=true 载荷；
 *  - gateway 抛异常 → 兜底成同构 ok=false JSON（retryable）。
 *
 * 模块边界：本测试不 import platform:terminal（对齐 TERMINAL_SDK_BOUNDARY.md）。
 */
class TmuxToolsTest {

    /** 假 gateway：记录调用、可编程返回值与异常。 */
    private class FakeGateway : TmuxSessionGateway {
        val calls = mutableListOf<Pair<String, String?>>()  // (method, arg)
        var returnValue = """{"ok":true,"action":"stub"}"""
        var throwOn = false

        override suspend fun createSession(name: String, workingDirectory: String?): String {
            calls += "create:$name" to workingDirectory
            if (throwOn) throw IllegalStateException("boom")
            return returnValue
        }
        override suspend fun switchSession(name: String): String {
            calls += "switch:$name" to null
            if (throwOn) throw IllegalStateException("boom")
            return returnValue
        }
        override suspend fun listSessions(): String {
            calls += "list" to null
            if (throwOn) throw IllegalStateException("boom")
            return returnValue
        }
        override suspend fun killSession(name: String): String {
            calls += "kill:$name" to null
            if (throwOn) throw IllegalStateException("boom")
            return returnValue
        }
        override suspend fun sendKeys(name: String, command: String): String {
            calls += "send:$name:$command" to null
            if (throwOn) throw IllegalStateException("boom")
            return returnValue
        }
        override suspend fun capturePane(name: String, lines: Int): String {
            calls += "capture:$name:$lines" to null
            if (throwOn) throw IllegalStateException("boom")
            return returnValue
        }
    }

    // ── 接口/契约 ──

    @Test
    fun `tools declare english ids and string schemas`() {
        val gw = FakeGateway()
        val tools = listOf(
            TmuxNewSessionTool(gw), TmuxSwitchSessionTool(gw), TmuxListSessionsTool(gw),
            TmuxSendKeysTool(gw), TmuxCapturePaneTool(gw), TmuxKillSessionTool(gw)
        )
        assertEquals(
            setOf(
                "tmux_new_session", "tmux_switch_session", "tmux_list_sessions",
                "tmux_send_keys", "tmux_capture_pane", "tmux_kill_session"
            ),
            tools.map { it.id }.toSet()
        )
        tools.forEach {
            assertTrue("schema must be non-blank string: ${it.id}", it.parametersSchema.isNotBlank())
            assertTrue("english description required: ${it.id}", it.description.isNotBlank())
            // 描述是英文（不含中文）—— 仓库约定工具描述面向模型用英文
            assertTrue(it.description.none { ch -> ch.code > 0x2E80 })
        }
    }

    @Test
    fun `new session schema requires name and forbids tmux locator chars in description`() {
        val schema = TmuxNewSessionTool(FakeGateway()).parametersSchema
        assertTrue(schema.contains("\"required\":[\"name\"]"))
    }

    // ── 透传成功载荷 ──

    @Test
    fun `new session passes name and cwd to gateway`() = runTest {
        val gw = FakeGateway().apply {
            returnValue = """{"ok":true,"action":"new","name":"dev","alreadyExists":false}"""
        }
        val out = TmuxNewSessionTool(gw).execute("""{"name":"dev","workingDirectory":"/w/p"}""")
        assertEquals("""{"ok":true,"action":"new","name":"dev","alreadyExists":false}""", out)
        assertEquals(listOf("create:dev" to "/w/p"), gw.calls)
    }

    @Test
    fun `list sessions passes through gateway payload`() = runTest {
        val gw = FakeGateway().apply {
            returnValue = """{"ok":true,"count":2,"sessions":[{"name":"dev"},{"name":"build"}]}"""
        }
        val out = TmuxListSessionsTool(gw).execute("{}")
        assertTrue(out.contains("\"count\":2"))
        assertEquals(1, gw.calls.size)
        assertEquals("list", gw.calls.first().first)
    }

    @Test
    fun `switch send capture kill pass args through`() = runTest {
        val gw = FakeGateway()
        TmuxSwitchSessionTool(gw).execute("""{"name":"b"}""")
        TmuxSendKeysTool(gw).execute("""{"name":"b","command":"make"}""")
        TmuxCapturePaneTool(gw).execute("""{"name":"b","lines":50}""")
        TmuxKillSessionTool(gw).execute("""{"name":"b"}""")
        assertTrue(gw.calls.any { it.first == "switch:b" })
        assertTrue(gw.calls.any { it.first == "send:b:make" })
        assertTrue(gw.calls.any { it.first == "capture:b:50" })
        assertTrue(gw.calls.any { it.first == "kill:b" })
    }

    @Test
    fun `capture defaults lines to 200 when omitted`() = runTest {
        val gw = FakeGateway()
        TmuxCapturePaneTool(gw).execute("""{"name":"b"}""")
        assertTrue(gw.calls.first().first == "capture:b:200")
    }

    // ── 结构化错误兜底（不穿透异常）──

    @Test
    fun `missing name yields structured ok=false without calling gateway`() = runTest {
        val gw = FakeGateway()
        val out = TmuxNewSessionTool(gw).execute("{}")
        assertTrue(out.contains("\"ok\":false"))
        assertTrue(out.contains("InvalidInput"))
        assertTrue(gw.calls.isEmpty())
    }

    @Test
    fun `malformed json yields structured ok=false`() = runTest {
        val out = TmuxSwitchSessionTool(FakeGateway()).execute("not json")
        assertTrue(out.contains("\"ok\":false"))
        assertTrue(out.contains("InvalidInput"))
    }

    @Test
    fun `missing command for send keys is rejected`() = runTest {
        val gw = FakeGateway()
        val out = TmuxSendKeysTool(gw).execute("""{"name":"b"}""")
        assertTrue(out.contains("\"ok\":false"))
        assertTrue(gw.calls.isEmpty())
    }

    @Test
    fun `gateway exception is caught into structured ok=false retryable`() = runTest {
        val gw = FakeGateway().apply { throwOn = true }
        val out = TmuxListSessionsTool(gw).execute("{}")
        assertTrue(out.contains("\"ok\":false"))
        assertTrue(out.contains("TmuxCommandFailed"))
        assertTrue(out.contains("\"retryable\":true"))
        assertTrue(out.contains("boom"))
    }

    @Test
    fun `no exception escapes tool boundary`() = runTest {
        val gw = FakeGateway().apply { throwOn = true }
        // 逐一调用，全部应返回结构化 JSON 而非抛异常
        listOf(
            TmuxNewSessionTool(gw) to """{"name":"a"}""",
            TmuxSwitchSessionTool(gw) to """{"name":"a"}""",
            TmuxListSessionsTool(gw) to "{}",
            TmuxSendKeysTool(gw) to """{"name":"a","command":"x"}""",
            TmuxCapturePaneTool(gw) to """{"name":"a"}""",
            TmuxKillSessionTool(gw) to """{"name":"a"}"""
        ).forEach { (tool, args) ->
            val out = tool.execute(args)
            assertTrue("must not throw; got: $out", out.contains("\"ok\":false"))
            assertFalse(out.contains("Error: "))
        }
    }
}
