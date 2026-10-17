package com.apex.agent.platform.terminal.tools.legacy

import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.tools.TerminalTool
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Legacy compat tool: TerminalListLegacy
 *
 * Spec ref: ATR 2.0 Final Spec §35
 *
 * Preserves the OLD contract: list active terminal sessions.
 *
 * Maps to: TerminalSnapshot(mode=SESSIONS). Returns just the session ids (old shape).
 */
@Deprecated("ATR 2.0 compat alias — use the new TerminalObserve/write/signal/snapshot/close API instead. Scheduled for removal in a future version.")
class LegacyListTool(
    private val runtime: TerminalRuntime
) : TerminalTool {
    override val id: String = "TerminalListLegacy"
    override val name: String = id
    override val parametersSchema: String = """
        {
          "type": "object",
          "properties": {
            "dummy": { "type": "integer", "description": "ignored — kept for symmetry" }
          }
        }
    """.trimIndent()
    override val description: String = """
        [COMPAT] List active terminal sessions (returns session ids). For full state use
        TerminalSnapshot(mode=FULL). Kept for backward compat.
    """.trimIndent()

    suspend fun execute(input: Input = Input()): Output {
        val result = runtime.snapshot(mode = TerminalRuntime.SnapshotMode.SESSIONS)
        return result.fold(
            onSuccess = { r ->
                Output(
                    sessions = r.sessions.map { s ->
                        SessionInfo(
                            sessionId = s.session.id,
                            pid = s.session.pid,
                            shell = s.session.shell,
                            cwd = s.session.cwd,
                            state = s.session.state.name,
                            cursor = s.session.cursor
                        )
                    }
                )
            },
            onFailure = { throw it }
        )
    }

    override suspend fun invoke(arguments: String): String {
        val out = execute(Input())
        return buildJsonObject {
            put("sessions", buildJsonArray {
                for (s in out.sessions) {
                    add(buildJsonObject {
                        put("sessionId", JsonPrimitive(s.sessionId))
                        put("pid", JsonPrimitive(s.pid))
                        put("shell", JsonPrimitive(s.shell))
                        put("cwd", JsonPrimitive(s.cwd))
                        put("state", JsonPrimitive(s.state))
                        put("cursor", JsonPrimitive(s.cursor))
                    })
                }
            })
        }.toString()
    }

    data class Input(val dummy: Int = 0)   // old TerminalListLegacy took no args; keep Input for symmetry

    data class Output(val sessions: List<SessionInfo>)

    data class SessionInfo(
        val sessionId: Long,
        val pid: Int,
        val shell: String,
        val cwd: String,
        val state: String,
        val cursor: Long
    )
}
