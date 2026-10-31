package com.apex.agent.platform.terminal.tools.legacy

import com.apex.agent.platform.terminal.io.InputOwner
import com.apex.agent.platform.terminal.io.UnixSignal
import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.tools.TerminalTool
import com.apex.agent.platform.TerminalWait.WaitCondition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Legacy compat tool: TerminalExecLegacy
 *
 * Spec ref: ATR 2.0 Final Spec §35 (compatibility layer) / §46 (TerminalExecLegacy id collision fix)
 *
 * Preserves the OLD synchronous contract: execute(command) → output + exitCode (blocks until done).
 *
 * CRITICAL CHANGE vs old TerminalManager.execute():
 *   - OLD: used SETTLE_TIME_MS=300 / MAX_SETTLE_WAIT_MS=2000 (output-silence → complete). DELETED.
 *   - NEW: uses runtime.run() + runtime.wait(PROCESS_EXITED, timeoutMs) + runtime.observe(RAW).
 *         Completion is waitpid-confirmed, NOT settle-time-inferred.
 *
 * id collision fix (Spec §46): this tool is the SINGLE source of `TerminalExecLegacy`.
 * The old `StreamingTerminalExecTool` (duplicate id) is removed in Phase 4; streaming
 * capability is now `TerminalRun` + `TerminalObserve` (Agent calls run, then observes
 * incrementally — no separate streaming tool needed).
 */
@Deprecated(
    "ATR 2.0 compat alias — use TerminalRun + TerminalWait + TerminalObserve instead. Scheduled for removal in a future version.",
    ReplaceWith("TerminalRunTool(runtime)", "com.apex.agent.platform.terminal.tools.v2.TerminalRunTool")
)
class LegacyExecTool(
    private val runtime: TerminalRuntime
) : TerminalTool {
    override val id: String = "TerminalExecLegacy"
    override val name: String = id
    override val parametersSchema: String = """
        {
          "type": "object",
          "properties": {
            "sessionId": { "type": "integer", "description": "Target session id" },
            "command":   { "type": "string",  "description": "Shell command to execute" },
            "timeoutMs": { "type": "integer", "description": "Max wait ms for process exit", "default": 120000 },
            "maxOutputBytes": { "type": "integer", "description": "Max captured output bytes", "default": 65536 }
          },
          "required": ["sessionId", "command"]
        }
    """.trimIndent()
    override val description: String = """
        [COMPAT, DEPRECATED] Execute a command synchronously and return output + exitCode. Blocks
        until process exits (waitpid-confirmed, NOT settle-time). For new code prefer TerminalRun +
        TerminalWait + TerminalObserve (non-blocking, incremental). Kept for backward compat.
    """.trimIndent()

    suspend fun execute(input: Input): Output {
        val t0 = System.nanoTime()
        val runResult = runtime.run(
            sessionId = input.sessionId, command = input.command,
            owner = InputOwner.AGENT, background = false, timeoutMs = input.timeoutMs
        )
        val run = runResult.getOrElse { throw it }

        val waitResult = runtime.wait(
            sessionId = input.sessionId,
            condition = WaitCondition.ProcessExited(jobId = run.jobId),
            timeoutMs = input.timeoutMs
        )
        val wait = waitResult.getOrElse { throw it }
        val exitCode = when (wait) {
            is com.apex.agent.platform.TerminalWait.WaitResult.Matched -> {
                val ev = wait.event
                if (ev is com.apex.agent.platform.terminal.events.TerminalEvent.ProcessExited) ev.exitCode ?: -1
                else 0
            }
            is com.apex.agent.platform.TerminalWait.WaitResult.Timeout -> {
                runtime.signal(input.sessionId, UnixSignal.SIGKILL, InputOwner.AGENT, run.jobId)
                return Output(output = "", exitCode = -1, truncated = false, durationMs = wait.waitedMs, timedOut = true)
            }
            is com.apex.agent.platform.TerminalWait.WaitResult.SessionGone -> {
                return Output(output = "", exitCode = -1, truncated = false, durationMs = (System.nanoTime() - t0) / 1_000_000, timedOut = false)
            }
        }

        val obsResult = runtime.observe(
            sessionId = input.sessionId,
            mode = TerminalRuntime.ObserveMode.RAW,
            afterCursor = run.startCursor,
            maxBytes = input.maxOutputBytes
        )
        val obs = obsResult.getOrElse { throw it }
        // 修复：成功路径此前恒报 durationMs=0 —— 现在如实测量端到端耗时
        val durationMs = (System.nanoTime() - t0) / 1_000_000
        // overrun 诚实上报（P1 修复）：RingTerminalBuffer 溢出后 observe 返回
        // raw="" + overrun=true（afterCursor 比最老数据还老）—— 旧实现只透传
        // obs.truncated，模型看到「空输出且未截断」，把缓冲溢出误判成命令
        // 无输出。现在 overrun 也标记 truncated，并在 output 里写明发生了
        // 什么、怎么重读（从 oldestCursor 重同步）。
        val honestOutput = if (obs.overrun) {
            val notice = "[OUTPUT BUFFER OVERRUN: the session ring buffer (256KB) was " +
                "overwritten after cursor ${run.startCursor}; earliest available cursor is " +
                "${obs.oldestCursor}. Re-observe with afterCursor=${obs.oldestCursor} to read " +
                "the surviving tail, or re-run the command with narrower output.]"
            (obs.raw ?: "").ifEmpty { notice }
        } else {
            obs.raw ?: ""
        }
        return Output(
            output = honestOutput,
            exitCode = exitCode,
            truncated = obs.truncated || obs.overrun,
            durationMs = durationMs,
            timedOut = false
        )
    }

    override suspend fun invoke(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val sessionId = json["sessionId"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: throw IllegalArgumentException("TerminalError:InvalidInput — 'sessionId' (long) required")
        val command = json["command"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("TerminalError:InvalidInput — 'command' required")
        val timeoutMs = json["timeoutMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: 120_000L
        val maxOutputBytes = json["maxOutputBytes"]?.jsonPrimitive?.content?.toIntOrNull() ?: 65536
        val out = execute(Input(sessionId, command, timeoutMs, maxOutputBytes))
        return buildJsonObject {
            put("output", JsonPrimitive(out.output))
            put("exitCode", JsonPrimitive(out.exitCode))
            put("truncated", JsonPrimitive(out.truncated))
            put("durationMs", JsonPrimitive(out.durationMs))
            put("timedOut", JsonPrimitive(out.timedOut))
        }.toString()
    }

    data class Input(
        val sessionId: Long,
        val command: String,
        val timeoutMs: Long = 120_000L,
        val maxOutputBytes: Int = 65536
    )

    data class Output(
        val output: String,
        val exitCode: Int,
        val truncated: Boolean,
        val durationMs: Long,
        val timedOut: Boolean
    )
}
