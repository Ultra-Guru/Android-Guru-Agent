package com.apex.agent.platform.terminal.tools.v2

import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.tools.TerminalTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray

/**
 * Agent tool: TerminalResize
 *
 * Spec ref: ATR 2.0 Final Spec §34.7
 *
 * Resize the Session's PTY (sends SIGWINCH). Updates VirtualTerminal dimensions. Useful before
 * running TUI programs (vim/top) to fit screen.
 *
 * JSON Schema (input):
 *   { sessionId: int, rows: int, cols: int }
 * JSON Schema (output):
 *   { resized: bool, rows: int, cols: int }
 * Errors: SessionNotFound, SessionClosed, InvalidInput
 */
class TerminalResizeTool(
    private val runtime: TerminalRuntime
) : TerminalTool {
    override val id: String = "TerminalResize"
    override val name: String = id
    override val description: String = """
        Resize the Session's PTY (sends SIGWINCH). Updates VirtualTerminal dimensions. Useful
        before running TUI programs (vim/top) to fit screen.
    """.trimIndent()

    override val parametersSchema: String = """
{"type":"object","properties":{"sessionId":{"type":"integer"},"rows":{"type":"integer"},"cols":{"type":"integer"}},"required":["sessionId","rows","cols"]}
""".trimIndent()

    suspend fun execute(input: Input): Output {
        require(input.rows >= 1 && input.cols >= 1) {
            "TerminalError:InvalidInput — rows and cols must be ≥ 1"
        }
        val result = runtime.resize(input.sessionId, input.rows, input.cols)
        return result.fold(
            onSuccess = { r -> Output(resized = r.resized, rows = r.rows, cols = r.cols) },
            onFailure = { throw it }
        )
    }

    override suspend fun invoke(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        // 别名宽容（实录 #8）：schema 层已互认，工具层同步读取
        val sessionId = json.longArg("sessionId", "session_id")
            ?: throw IllegalArgumentException("sessionId required (accepted: sessionId, session_id)")
        val rows = json.intArg("rows") ?: throw IllegalArgumentException("rows required")
        val cols = json.intArg("cols") ?: throw IllegalArgumentException("cols required")
        val out = execute(Input(sessionId, rows, cols))
        return buildJsonObject { put("resized", JsonPrimitive(out.resized)); put("rows", JsonPrimitive(out.rows)); put("cols", JsonPrimitive(out.cols)) }.toString()
    }

    data class Input(val sessionId: Long, val rows: Int, val cols: Int)
    data class Output(val resized: Boolean, val rows: Int, val cols: Int)
}
