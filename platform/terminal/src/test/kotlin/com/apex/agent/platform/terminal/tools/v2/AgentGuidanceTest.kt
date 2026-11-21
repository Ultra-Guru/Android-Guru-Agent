package com.apex.agent.platform.terminal.tools.v2

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对话实录 #7/#8/#9 回归锁 —— 终端工具层引导与宽容能力（纯函数，无运行时依赖）。
 *
 * 实录事故：
 * - #7 多行 heredoc 直灌 PTY → 会话挂死在 PS2 '>'；JOB 范围 Ctrl+C 送达不了
 *   前台 shell 本体（两次无效），最终 SIGKILL 整会话才脱身；
 * - #8 session_id 被 'sessionId' required 拒收，错误信息无修正指引；
 * - #9 EVENT 模式只回 eventCount=92，模型当作“已读过输出”。
 *
 * 修复承诺（本文件锁死纯函数层；JSON 输出层的接线由工具代码保证）：
 * 1. heredocAdvisory：<< / <<- 命中 → 警告含“先落文件”与 CTRL_C 自救；
 *    <<<（here-string）不误报；无 heredoc 零噪音；
 * 2. 别名宽容读取：session_id / job_id / cursor 等别名优先级正确；
 * 3. EVENT 提示与信号未送达提示的关键内容常量在位。
 */
class AgentGuidanceTest {

    // ═══════════════════════ heredocAdvisory（实录 #7）═══════════════════════

    @Test
    fun `heredoc start triggers advisory with file-first and ctrl-c escape`() {
        val advisory = AgentGuidance.heredocAdvisory("python3 - <<'PYEOF'\nprint(1)\nPYEOF")
        assertTrue("heredoc 必须触发警告", advisory != null)
        assertTrue("必须建议先落文件：$advisory", advisory!!.contains("file"))
        assertTrue("必须给出 CTRL_C 自救：$advisory", advisory.contains("CTRL_C"))
        assertTrue("必须说明 PS2 '>' 挂死形态：$advisory", advisory.contains("'>"))
    }

    @Test
    fun `strip-tab heredoc form is detected`() {
        assertTrue(AgentGuidance.heredocAdvisory("cat <<-EOF\nhi") != null)
    }

    @Test
    fun `here-string triple redirect is not flagged`() {
        // <<< 单行自洽，不会挂死 —— 不误报（“$var” 用转义避免 Kotlin 插值）
        assertNull(AgentGuidance.heredocAdvisory("grep foo <<< \"\$var\""))
    }

    @Test
    fun `plain command and terminator rescue line are silent`() {
        assertNull("普通命令零噪音", AgentGuidance.heredocAdvisory("ls -la /workspace"))
        // 挂死后补送终结符的自救写入（单行 EOF 文本）不含 << —— 不警告
        assertNull("补终结符不得误报", AgentGuidance.heredocAdvisory("PYEOF"))
    }

    // ═══════════════════════ 别名宽容读取（实录 #8/#9）═══════════════════════

    @Test
    fun `longArg reads primary first then aliases`() {
        val both = Json.parseToJsonElement(
            """{"sessionId": 2, "session_id": 9}"""
        ).let { it as kotlinx.serialization.json.JsonObject }
        assertEquals("主名优先", 2L, both.longArg("sessionId", "session_id"))

        val aliasOnly = Json.parseToJsonElement(
            """{"session_id": 7}"""
        ).let { it as kotlinx.serialization.json.JsonObject }
        assertEquals("别名兜底（实录 #8 形态）", 7L, aliasOnly.longArg("sessionId", "session_id"))
        assertEquals("cursor 别名兜底（实录 #9 形态）", 0L, buildJsonObject {
            put("cursor", 0)
        }.longArg("afterCursor", "after_cursor", "cursor"))
    }

    @Test
    fun `longArg tolerates numeric json literals`() {
        val obj = buildJsonObject { put("sessionId", 42) } // 数字字面量而非字符串
        assertEquals(42L, obj.longArg("sessionId", "session_id"))
    }

    @Test
    fun `stringArg boolArg intArg read strictly by alias order`() {
        val obj = buildJsonObject {
            put("kind", "KEY")
            put("background", true)
            put("job_id", 5)
        }
        assertEquals("KEY", obj.stringArg("kind", "kind_name"))
        assertEquals(true, obj.boolArg("background"))
        assertEquals(5, obj.intArg("jobId", "job_id"))
        assertNull(obj.longArg("sessionId", "session_id"))
        assertFalse(obj.boolArg("force") ?: false)
    }

    // ═══════════════════════ 提示常量（实录 #9 / #7）═══════════════════════

    @Test
    fun `event mode hint tells the model how to actually read output`() {
        assertTrue(AgentGuidance.EVENT_MODE_HINT.contains("NO output text"))
        assertTrue("必须指向可读模式", AgentGuidance.EVENT_MODE_HINT.contains("SCREEN"))
        assertTrue(AgentGuidance.EVENT_MODE_HINT.contains("RAW"))
    }

    @Test
    fun `signal undelivered hint explains why and gives the escape`() {
        assertTrue("必须解释 shell 在前台的原因", AgentGuidance.SIGNAL_UNDELIVERED_HINT.contains("foreground"))
        assertTrue("必须给出 CTRL_C 路径", AgentGuidance.SIGNAL_UNDELIVERED_HINT.contains("CTRL_C"))
    }
}
