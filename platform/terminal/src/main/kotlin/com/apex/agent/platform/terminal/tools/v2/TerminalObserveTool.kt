package com.apex.agent.platform.terminal.tools.v2

import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.screen.TerminalScreenState
import com.apex.agent.platform.terminal.state.TerminalSemanticState
import com.apex.agent.platform.terminal.tools.TerminalTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Agent tool: TerminalObserve
 *
 * Spec ref: ATR 2.0 Final Spec §34.3 — the core perception API.
 *
 * Default mode=SEMANTIC returns machine-readable state (session/job/input/cursor) with NO raw
 * output — token-cheap.
 *   mode=EVENT   → incremental events since afterCursor
 *   mode=SCREEN  → parsed screen (for TUI like vim/top)
 *   mode=RAW     → raw bytes since afterCursor
 *
 * JSON Schema (input):
 *   { sessionId: int, mode?: "SEMANTIC"|"EVENT"|"SCREEN"|"RAW"=SEMANTIC,
 *     afterCursor?: int=0, maxBytes?: int=12000, maxEvents?: int=200 }
 * JSON Schema (output):
 *   { mode, sessionId, cursor, startCursor?, endCursor?, truncated, overrun, oldestCursor?,
 *     semantic?, events?, screen?, raw? }
 * Errors: SessionNotFound, SessionClosed, BufferOverrun, ReadFailed
 */
class TerminalObserveTool(
    private val runtime: TerminalRuntime
) : TerminalTool {
    override val id: String = "TerminalObserve"
    override val name: String = id
    override val description: String = """
        Observe terminal state. Default mode=SEMANTIC returns machine-readable state (session/job/input/cursor)
        with NO raw output — token-cheap. mode=EVENT returns incremental events since afterCursor.
        mode=SCREEN returns parsed screen (for TUI like vim/top); pass scrollbackLines>0 to also
        receive the last N scrollback lines (T82, main screen history up to 1000 lines).
        mode=RAW returns raw bytes since afterCursor.
    """.trimIndent()

    override val parametersSchema: String = """
{"type":"object","properties":{"sessionId":{"type":"integer"},"mode":{"type":"string","default":"SEMANTIC"},"afterCursor":{"type":"integer","default":0},"maxBytes":{"type":"integer","default":12000},"maxEvents":{"type":"integer","default":200},"scrollbackLines":{"type":"integer","default":0}},"required":["sessionId"]}
""".trimIndent()

    suspend fun execute(input: Input): Output {
        val result = runtime.observe(
            sessionId = input.sessionId,
            mode = input.mode,
            afterCursor = input.afterCursor,
            maxBytes = input.maxBytes,
            maxEvents = input.maxEvents,
            scrollbackLines = input.scrollbackLines
        )
        return result.fold(
            onSuccess = { r ->
                Output(
                    mode = r.mode.name,
                    sessionId = r.sessionId,
                    cursor = r.cursor,
                    startCursor = r.startCursor,
                    endCursor = r.endCursor,
                    truncated = r.truncated,
                    overrun = r.overrun,
                    oldestCursor = r.oldestCursor,
                    semantic = r.semantic,           // serialized by tool layer (kotlinx.serialization)
                    events = r.events,
                    screen = r.screen,
                    raw = r.raw,
                    scrollbackTail = r.scrollbackTail
                )
            },
            onFailure = { throw it }
        )
    }

    override suspend fun invoke(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        // 别名宽容（实录 #8/#9）：session_id / cursor 简写均可
        val sessionId = json.longArg("sessionId", "session_id")
            ?: throw IllegalArgumentException("sessionId required (accepted: sessionId, session_id)")
        val mode = runCatching { TerminalRuntime.ObserveMode.valueOf(json.stringArg("mode") ?: "SEMANTIC") }.getOrDefault(TerminalRuntime.ObserveMode.SEMANTIC)
        // 实录 #9：模型把 "cursor" 当入参名传入时被静默忽略 → afterCursor=0
        // 从头重读。accept cursor / after_cursor 别名。
        val afterCursor = json.longArg("afterCursor", "after_cursor", "cursor") ?: 0L
        val maxBytes = json.intArg("maxBytes", "max_bytes") ?: 12000
        val maxEvents = json.intArg("maxEvents", "max_events") ?: 200
        // T82：SCREEN 模式 scrollback 尾部行数（默认 0 = 不带）
        val scrollbackLines = json.intArg("scrollbackLines", "scrollback_lines") ?: 0
        val out = execute(Input(sessionId, mode, afterCursor, maxBytes, maxEvents, scrollbackLines))
        return buildJsonObject {
            put("mode", JsonPrimitive(out.mode))
            put("sessionId", JsonPrimitive(out.sessionId))
            put("cursor", JsonPrimitive(out.cursor))
            out.startCursor?.let { put("startCursor", JsonPrimitive(it)) }
            out.endCursor?.let { put("endCursor", JsonPrimitive(it)) }
            put("truncated", JsonPrimitive(out.truncated))
            put("overrun", JsonPrimitive(out.overrun))
            // mode-specific payload
            when (mode) {
                TerminalRuntime.ObserveMode.RAW -> put("raw", JsonPrimitive(out.raw ?: ""))
                TerminalRuntime.ObserveMode.SCREEN -> {
                    out.screen?.let { scr ->
                        put("renderedText", JsonPrimitive(scr.renderedText ?: ""))
                        put("rows", JsonPrimitive(scr.rows))
                        put("cols", JsonPrimitive(scr.cols))
                        put("cursorRow", JsonPrimitive(scr.cursorRow))
                        put("cursorCol", JsonPrimitive(scr.cursorCol))
                        put("alternateScreen", JsonPrimitive(scr.alternateScreen))
                    }
                    // T82：scrollback 尾部（oldest→newest）
                    if (!out.scrollbackTail.isNullOrEmpty()) {
                        put("scrollback", kotlinx.serialization.json.buildJsonArray {
                            out.scrollbackTail.forEach { add(JsonPrimitive(it)) }
                        })
                        out.screen?.scrollbackLineCount?.let { put("scrollbackLineCount", JsonPrimitive(it)) }
                    }
                }
                TerminalRuntime.ObserveMode.SEMANTIC -> {
                    out.semantic?.let { sem ->
                        put("sessionState", JsonPrimitive(sem.session.state.name))
                        put("cursor", JsonPrimitive(sem.session.cursor))
                        put("shell", JsonPrimitive(sem.session.shell))
                        put("cwd", JsonPrimitive(sem.session.cwd))
                        sem.foregroundJob?.let { j ->
                            put("fgJobId", JsonPrimitive(j.id))
                            put("fgJobState", JsonPrimitive(j.state.name))
                            put("fgJobCommand", JsonPrimitive(j.command))
                            j.exitCode?.let { put("fgJobExitCode", JsonPrimitive(it)) }
                        }
                        put("inputState", JsonPrimitive(sem.input.state.name))
                    }
                }
                TerminalRuntime.ObserveMode.EVENT -> {
                    // 实录 #9：EVENT 只返回计数，字节本体在 RingBuffer —— 曾被
                    // 模型当作“已读到输出”。附带指引把读文本的正确模式说清。
                    put("eventCount", JsonPrimitive(out.events?.size ?: 0))
                    put("hint", JsonPrimitive(AgentGuidance.EVENT_MODE_HINT))
                }
            }
        }.toString()
    }

    data class Input(
        val sessionId: Long,
        val mode: TerminalRuntime.ObserveMode = TerminalRuntime.ObserveMode.SEMANTIC,
        val afterCursor: Long = 0,
        val maxBytes: Int = 12000,
        val maxEvents: Int = 200,
        /** T82：SCREEN 模式附带 scrollback 尾部行数。 */
        val scrollbackLines: Int = 0
    )

    data class Output(
        val mode: String,
        val sessionId: Long,
        val cursor: Long,
        val startCursor: Long?,
        val endCursor: Long?,
        val truncated: Boolean,
        val overrun: Boolean,
        val oldestCursor: Long?,
        val semantic: TerminalSemanticState?,  // serialized to JSON by tool layer
        val events: List<Any>?,          // List<TerminalEvent>
        val screen: TerminalScreenState?,     // TerminalScreenState
        val raw: String?,
        /** T82：SCREEN + scrollbackLines>0 —— VT 主屏 scrollback 尾部（oldest→newest）。 */
        val scrollbackTail: List<String>? = null
    )
}
