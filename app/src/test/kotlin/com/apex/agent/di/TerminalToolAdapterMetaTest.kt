package com.apex.agent.di

import com.apex.agent.core.tools.ToolResultMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3（Agent-Shell UI 契约修复）—— TerminalToolAdapter 元数据提取回归锁。
 *
 * parseMeta 是「终端命令失败不再显绿」修复的数据源：从终端工具的 JSON 输出
 * 提取 exit_code / session_id / channel / cwd / error / status →
 * [ToolResultMeta]，engine 据此以真实退出码判定成败（127/126/-1 不再被
 * `"Error"` 前缀猜测误判为成功）。这里锁三类契约：
 *
 * 1. TerminalExec 形态：全字段提取（含门禁拒绝的 error=permission_denied）；
 * 2. TerminalRun/TerminalWait 形态：session_id / status=INPUT_REQUIRED →
 *    waitingInput（挂起可见）；
 * 3. 容错：非 JSON 输出 / 字段类型异常 → 空 meta 静默降级，永不打断执行。
 */
class TerminalToolAdapterMetaTest {

    private fun metaOf(json: String): ToolResultMeta =
        TerminalToolAdapter(FakeTerminalTool(json)).parseMeta(json)

    @Test
    fun `TerminalExec json yields full meta`() {
        val m = metaOf(
            """{"stdout":"","stderr":"sh: foo: not found","exit_code":127,""" +
                """"duration_ms":12,"truncated":false,"timed_out":false,"killed":false,""" +
                """"channel":"proot-ubuntu","cwd":"/workspace/proj"}"""
        )
        assertEquals(127, m.exitCode)
        assertEquals("proot-ubuntu", m.channel)
        assertEquals("/workspace/proj", m.cwd)
        assertNull(m.errorKind)
        assertFalse(m.waitingInput)
    }

    @Test
    fun `gate denied output carries error kind`() {
        val m = metaOf(
            """{"stdout":"","stderr":"用户拒绝","exit_code":126,""" +
                """"channel":"local-sh","error":"permission_denied"}"""
        )
        assertEquals(126, m.exitCode)
        assertEquals("permission_denied", m.errorKind)
    }

    @Test
    fun `success exit zero extracts cleanly`() {
        val m = metaOf("""{"stdout":"ok","exit_code":0,"channel":"root-su"}""")
        assertEquals(0, m.exitCode)
        assertEquals("root-su", m.channel)
    }

    @Test
    fun `session id and waiting input from run and wait tools`() {
        val run = metaOf("""{"sessionId":"5","jobId":9,"started":true}""")
        assertEquals("5", run.sessionId)
        assertFalse(run.waitingInput)

        val wait = metaOf(
            """{"mode":"WAIT","sessionId":"5","status":"INPUT_REQUIRED"}"""
        )
        assertEquals("5", wait.sessionId)
        assertTrue(wait.waitingInput)
    }

    @Test
    fun `camelCase sessionId alias also accepted`() {
        val m = metaOf("""{"sessionId":7,"started":true}""")
        assertEquals("7", m.sessionId)
    }

    @Test
    fun `non json output degrades to empty meta`() {
        val m = metaOf("plain text output, not json")
        assertNull(m.exitCode)
        assertNull(m.sessionId)
        assertNull(m.channel)
        assertNull(m.cwd)
        assertNull(m.errorKind)
        assertFalse(m.waitingInput)
    }

    @Test
    fun `wrong field types degrade silently`() {
        // exit_code 非数字 → 字段级降级（intOrNull → null）；channel 数字字面量
        // → kotlinx 宽容读为字符串形式（真实终端工具输出里 channel 恒为字符串，
        // 此处锁定实际行为防意外回归）。
        val m = metaOf("""{"exit_code":"not-a-number","channel":123}""")
        assertNull(m.exitCode)
        assertEquals("123", m.channel)
    }

    @Test
    fun `object fields degrade whole meta silently`() {
        // 字段为 JSON 对象 → jsonPrimitive 扩展抛异常 → runCatching 整体降级
        // 为空 meta（永不因解析打断执行 —— 防御契约）。
        val m = metaOf("""{"exit_code":{"nested":1},"channel":"local-sh"}""")
        assertNull(m.exitCode)
        assertNull(m.channel)
    }

    /** 只承载 JSON 输出的最小假终端工具（parseMeta 只读 invoke 结果）。 */
    private class FakeTerminalTool(private val json: String) :
        com.apex.agent.platform.terminal.tools.TerminalTool {
        override val id: String = "Fake"
        override val name: String = id
        override val description: String = "test"
        override val parametersSchema: String = "{}"
        override suspend fun invoke(arguments: String): String = json
    }
}
