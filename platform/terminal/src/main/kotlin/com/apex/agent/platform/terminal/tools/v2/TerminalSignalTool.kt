package com.apex.agent.platform.terminal.tools.v2

import com.apex.agent.platform.terminal.io.InputOwner
import com.apex.agent.platform.terminal.io.UnixSignal
import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.tools.TerminalTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray

/**
 * Agent tool: TerminalSignal
 *
 * Spec ref: ATR 2.0 Final Spec §34.6
 *
 * Send a Unix signal. Default scope=SESSION targets the session's whole process group
 * (kill(-PGID), shell included — cancel/teardown semantics). T82 adds scope=JOB: the
 * signal goes ONLY to the terminal's foreground process group (tcgetpgrp) — the shell
 * survives (Ctrl-C semantics: interrupt the current command, session stays alive).
 * SIGINT (Ctrl+C), SIGTERM, SIGKILL. USER-initiated SIGINT produces a UserInterrupt event
 * so the Agent can distinguish user cancellation from command failure.
 *
 * JSON Schema (input):
 *   { sessionId: int, signal: "SIGINT"|"SIGTERM"|"SIGKILL"|"SIGHUP"|"SIGQUIT",
 *     jobId?: int, scope?: "SESSION"|"JOB"="SESSION" }
 * JSON Schema (output):
 *   { sent: bool, signal: string, targetJobId: int|null, scope: string,
 *     foregroundDelivered: bool|null }
 *   scope=JOB 时 sent/foregroundDelivered=false = 无前台作业（空闲 prompt，未发送）。
 * Errors: SessionNotFound, SessionClosed, PermissionDenied, ProcessExited, InvalidInput
 */
class TerminalSignalTool(
    private val runtime: TerminalRuntime
) : TerminalTool {
    override val id: String = "TerminalSignal"
    override val name: String = id
    override val description: String = """
        Send a Unix signal. scope=SESSION (default): the whole process group (shell included —
        teardown semantics). scope=JOB (T82): only the foreground command's process group —
        the shell survives (Ctrl-C semantics: interrupt the current command, session stays
        alive; returns foregroundDelivered=false when no foreground job is running).
        SIGINT (Ctrl+C), SIGTERM, SIGKILL, SIGTSTP (Ctrl+Z: suspend the foreground job —
        the job stops but the shell regains the prompt and the session stays interactive;
        resume later with SIGCONT). USER-initiated SIGINT produces a UserInterrupt event.
    """.trimIndent()

    override val parametersSchema: String = """
{"type":"object","properties":{"sessionId":{"type":"integer"},"signal":{"type":"string"},"jobId":{"type":"integer"},"scope":{"type":"string","enum":["SESSION","JOB"],"default":"SESSION"}},"required":["sessionId","signal"]}
""".trimIndent()

    suspend fun execute(input: Input): Output {
        val signal: UnixSignal = input.signal
        val result = if (input.scope == SignalScope.JOB) {
            runtime.signalForeground(
                sessionId = input.sessionId,
                signal = signal,
                owner = InputOwner.AGENT,   // auto-injected
                jobId = input.jobId
            )
        } else {
            runtime.signal(
                sessionId = input.sessionId,
                signal = signal,
                owner = InputOwner.AGENT,   // auto-injected
                jobId = input.jobId
            )
        }
        return result.fold(
            onSuccess = { r -> Output(sent = r.sent, signal = r.signal.name, targetJobId = r.targetJobId, scope = input.scope.name, foregroundDelivered = r.foregroundDelivered) },
            onFailure = { throw it }
        )
    }

    override suspend fun invoke(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        // 别名宽容（实录 #8）：schema 层已互认，工具层同步读取
        val sessionId = json.longArg("sessionId", "session_id")
            ?: throw IllegalArgumentException("sessionId required (accepted: sessionId, session_id)")
        val signal = runCatching { UnixSignal.valueOf(json.stringArg("signal") ?: "SIGINT") }.getOrNull() ?: throw IllegalArgumentException("invalid signal")
        val jobId = json.longArg("jobId", "job_id")
        val scope = runCatching { SignalScope.valueOf(json.stringArg("scope") ?: "SESSION") }.getOrDefault(SignalScope.SESSION)
        val out = execute(Input(sessionId, signal, jobId, scope))
        return buildJsonObject {
            put("sent", JsonPrimitive(out.sent))
            put("signal", JsonPrimitive(out.signal))
            put("targetJobId", if (out.targetJobId != null) JsonPrimitive(out.targetJobId) else JsonPrimitive("null"))
            put("scope", JsonPrimitive(out.scope))
            out.foregroundDelivered?.let { put("foregroundDelivered", JsonPrimitive(it)) }
            // 实录 #7：JOB 范围信号在 shell 本体处于前台（heredoc PS2 等待）时
            // 设计上不投递 —— 模型连续两次 Ctrl+C“无效”后只能 SIGKILL 整会话。
            // 未送达时附带正确自救路径（TerminalWrite KEY=CTRL_C 走 PTY 线路规程）。
            if (out.scope == SignalScope.JOB.name && !(out.sent && out.foregroundDelivered == true)) {
                put("hint", JsonPrimitive(AgentGuidance.SIGNAL_UNDELIVERED_HINT))
            }
        }.toString()
    }

    enum class SignalScope { SESSION, JOB }

    data class Input(
        val sessionId: Long,
        val signal: UnixSignal,
        val jobId: Long? = null,
        val scope: SignalScope = SignalScope.SESSION
    )

    data class Output(
        val sent: Boolean,
        val signal: String,
        val targetJobId: Long?,
        val scope: String,
        val foregroundDelivered: Boolean? = null
    )
}
