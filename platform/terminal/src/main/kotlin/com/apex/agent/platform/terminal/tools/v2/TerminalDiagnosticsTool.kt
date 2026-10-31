package com.apex.agent.platform.terminal.tools.v2

import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.tools.TerminalTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Agent tool: TerminalDiagnostics —— T87（「终端到底能不能被 Agent 调用」的自证工具）。
 *
 * 一个工具同时回答三个问题：
 *  1. **会话面**：每个会话的真实状态（backend/shell/state/pid/退出码/前台 job）；
 *  2. **后端面**：local / linux-ubuntu 的可用性（READY / NEEDS_ROOTFS / FAILED+原因）；
 *  3. **自证面**：smokeTest=true 时真实执行一条命令（默认 `printf` 探针）并回报
 *     实际输出 —— Agent 拿到回显即证明「exec 链路（spawner → fork → PTY/pipe →
 *     采集）端到端可用」，无需向用户求证。
 *
 * 输出末尾附 **nextActions**（结构化建议：哪个会话死该 close、rootfs 缺该
 * ubuntu.install、DNS 断该 linux.network…），Agent 拿到即可行动。
 *
 * JSON Schema (input):
 *   { smokeTest?: boolean }   —— true = 额外跑一次真实 exec 探针
 * JSON Schema (output):
 *   { ok, sessions: [...], backends: [...], smoke?: {command, exitCode, output},
 *     nextActions: [ ... ] }
 */
class TerminalDiagnosticsTool(
    private val runtime: TerminalRuntime,
    /** 可选的 exec 探针（app DI 注入 ExecEngine 路径；null = 该能力如实缺席）。 */
    private val execProbe: (suspend (command: String) -> String?)? = null
) : TerminalTool {
    override val id: String = "TerminalDiagnostics"
    override val name: String = id
    override val description: String = """
        One-shot terminal stack diagnostics: live sessions (state/exit/backend), backend
        availability, and an optional REAL exec smoke test proving the agent→terminal chain
        end-to-end. Use this first when terminal behavior looks broken (dead sessions,
        write failures, Ubuntu won't start) — it returns structured findings plus
        nextActions you can execute immediately.
    """.trimIndent()

    override val parametersSchema: String = """
{"type":"object","properties":{"smokeTest":{"type":"boolean","description":"run a real exec probe and report its output"}},"required":[]}
    """.trimIndent()

    override suspend fun invoke(arguments: String): String {
        val wantSmoke = runCatching {
            Json.parseToJsonElement(arguments).jsonObject["smokeTest"]?.toString()?.toBoolean() ?: false
        }.getOrDefault(false)

        val snap = runtime.snapshot(TerminalRuntime.SnapshotMode.SESSIONS).getOrNull()
        val sessions = snap?.sessions ?: emptyList()
        val backends = runtime.backends()
        val nextActions = mutableListOf<String>()

        val sessionsJson = buildJsonArray {
            for (s in sessions) {
                add(buildJsonObject {
                    put("id", JsonPrimitive(s.session.id))
                    put("shell", JsonPrimitive(s.session.shell))
                    put("state", JsonPrimitive(s.session.state.name))
                    put("pid", JsonPrimitive(s.session.pid))
                    s.session.lastExitCode?.let { put("lastExitCode", JsonPrimitive(it)) }
                    // backend 语义推断（SessionSnapshot 不带 backend 字段 ——
                    // shell=proot/bash → linux-ubuntu；旧 local 会话标注 legacy-local）
                    put(
                        "backend",
                        JsonPrimitive(
                            if (s.session.shell.contains("bash", true) ||
                                s.session.shell.contains("proot", true)
                            ) "linux-ubuntu" else "legacy-local"
                        )
                    )
                    s.foregroundJob?.let { put("foregroundJob", JsonPrimitive(it.command)) }
                    if (s.session.state.name in DEAD_STATES) {
                        put("verdict", JsonPrimitive("DEAD"))
                        nextActions.add("session ${s.session.id} is ${s.session.state.name} — close it (TerminalClose) or restart")
                    }
                })
            }
        }

        val backendsJson = buildJsonArray {
            for (b in backends) {
                add(buildJsonObject {
                    put("id", JsonPrimitive(b.id))
                    put("runtimeType", JsonPrimitive(b.runtimeType))
                    put("state", JsonPrimitive(b.state))
                    b.detail?.let { put("detail", JsonPrimitive(it)) }
                })
                when {
                    b.state == "NEEDS_ROOTFS" -> nextActions.add(
                        "backend '${b.id}' needs rootfs — call TerminalUbuntuInstall first (offline, minutes)"
                    )
                    b.state == "FAILED" -> nextActions.add(
                        "backend '${b.id}' failed: ${b.detail ?: "unknown"}"
                    )
                }
            }
        }

        // ── 自证探针：真实执行一条命令并回报输出 ──
        val smokeJson = if (wantSmoke) {
            val probe = execProbe?.invoke(SMOKE_COMMAND)
            buildJsonObject {
                put("command", JsonPrimitive(SMOKE_COMMAND))
                if (probe != null) {
                    put("chainOk", JsonPrimitive(probe.contains(SMOKE_MARKER)))
                    put("output", JsonPrimitive(probe.take(200)))
                    if (!probe.contains(SMOKE_MARKER)) {
                        nextActions.add("smoke probe output mismatch — exec chain partially broken, inspect output")
                    }
                } else {
                    put("chainOk", JsonPrimitive(false))
                    put("output", JsonPrimitive("(exec probe not wired on this host)"))
                    nextActions.add("exec probe unavailable — verify TerminalExec separately")
                }
            }
        } else null

        return buildJsonObject {
            put("ok", JsonPrimitive(nextActions.isEmpty()))
            put("sessionCount", JsonPrimitive(sessions.size))
            put("sessions", sessionsJson)
            put("backends", backendsJson)
            smokeJson?.let { put("smoke", it) }
            put("nextActions", buildJsonArray {
                for (a in nextActions.distinct()) add(JsonPrimitive(a))
                if (nextActions.isEmpty()) add(JsonPrimitive("all clear — terminal stack healthy"))
            })
        }.toString()
    }

    companion object {
        private val DEAD_STATES = setOf("EXITED", "CLOSED", "LOST", "FAILED", "STOPPING")
        private const val SMOKE_COMMAND = "printf 'TERMINAL-EXEC-OK'"
        private const val SMOKE_MARKER = "TERMINAL-EXEC-OK"
    }
}
