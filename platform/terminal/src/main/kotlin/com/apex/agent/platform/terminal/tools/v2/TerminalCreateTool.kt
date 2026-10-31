package com.apex.agent.platform.terminal.tools.v2

import com.apex.agent.platform.terminal.policy.PrivilegeLevel
import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.tools.TerminalTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Agent tool: TerminalCreate
 *
 * Spec ref: ATR 2.0 Final Spec §34.1 + T73 backend routing
 *
 * Creates a long-lived Session (PTY + shell). A Session is a workspace, not a single command.
 * Returns sessionId for subsequent run/observe/wait/write calls.
 *
 * JSON Schema (input):
 *   { shell?: string="/system/bin/sh", cwd?: string="/sdcard", rows?: int=24, cols?: int=200,
 *     env?: object<string,string>, privilege?: "NORMAL"|"SHIZUKU"|"ROOT"=NORMAL,
 *     backend?: string="local", workspaceId?: string }   // T75: workspaceId 仅 linux-ubuntu
 * JSON Schema (output):
 *   { sessionId: int, pid: int, shell: string, cwd: string, rows: int, cols: int,
 *     privilege: string, state: "READY"|"STARTING"|"BROKEN", cursor: int,
 *     backendId: string, runtimeType: string, rootfsId?: string, guestCwd?: string,
 *     workspaceId?: string }
 * Errors: PtyUnavailable, InvalidInput, PermissionDenied, BackendNotFound,
 *         RootfsNotReady（先调 TerminalUbuntuInstall）, BackendFailed,
 *         WorkspaceError:InvalidId（workspaceId 非法）
 */
class TerminalCreateTool(
    private val runtime: TerminalRuntime
) : TerminalTool {
    override val id: String = "TerminalCreate"
    override val name: String = id
    override val description: String = """
        Create a long-lived terminal Session (PTY + shell). A Session is a workspace, not a single
        command. Returns sessionId for subsequent run/observe/wait/write calls.
        backend="linux-ubuntu" (default) runs Ubuntu 24.04 via PRoot
        (requires the rootfs to be installed — check TerminalBackends, install via TerminalUbuntuInstall).
        workspaceId selects an isolated file area bound to guest /workspace
        (persists across sessions; unknown valid ids are auto-created; default: "default" —
        manage via TerminalWorkspaces).
    """.trimIndent()

    override val parametersSchema: String = """
{"type":"object","properties":{"cwd":{"type":"string","default":"/sdcard","description":"Initial working directory (guest path; /workspace default; relative paths land under /workspace)"},"rows":{"type":"integer","default":24},"cols":{"type":"integer","default":200,"description":"Terminal width in columns. Agent sessions default to 200 — the legacy 80-col default hard-wrapped long commands, splitting identifiers across SCREEN-mode read lines (misread risk). Human-facing views re-flow independently."},"env":{"type":"object","additionalProperties":{"type":"string"},"description":"Extra environment variables (passed into the guest)"},"backend":{"type":"string","default":"linux-ubuntu","enum":["linux-ubuntu"],"description":"Execution backend: linux-ubuntu = Ubuntu 24.04 via PRoot"},"workspaceId":{"type":"string","description":"Isolated workspace id bound to guest /workspace. Pattern: lowercase alphanumeric, may contain - and _, max 64 chars. Unknown valid ids are auto-created."}},"required":[]}
    """.trimIndent()

    suspend fun execute(input: Input): Output {
        val result = runtime.create(
            cwd = input.cwd,
            rows = input.rows,
            cols = input.cols,
            env = input.env,
            privilege = input.privilege,
            backendId = input.backend,
            workspaceId = input.workspaceId
        )
        return result.fold(
            onSuccess = { r -> Output(
                sessionId = r.sessionId, pid = r.pid, shell = r.shell, cwd = r.cwd,
                rows = r.rows, cols = r.cols, privilege = r.privilege.name,
                state = r.state, cursor = r.cursor,
                backendId = r.backendId, runtimeType = r.runtimeType,
                rootfsId = r.rootfsId, guestCwd = r.guestCwd,
                workspaceId = r.workspaceId
            ) },
            onFailure = { throw it }
        )
    }

    override suspend fun invoke(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val cwd = json.stringArg("cwd") ?: "/sdcard"
        val rows = json.intArg("rows") ?: 24
        // 实录 #15：80 列硬折行把长命令/标识符拆碎（isinstance → isins/tance），
        // SCREEN 读屏极易看错。Agent 会话默认 200 列（IPC 层夹在 2..1024 内）。
        val cols = json.intArg("cols") ?: AGENT_DEFAULT_COLS
        val backend = json["backend"]?.jsonPrimitive?.content ?: "linux-ubuntu"
        val workspaceId = json["workspaceId"]?.jsonPrimitive?.contentOrNull
        // P3 fix（审计 6-b）：实现 schema 已声明的 privilege 参数解析 ——
        // 原实现硬编码 NORMAL，schema 里的 "NORMAL"|"SHIZUKU"|"ROOT" 形同虚设。
        // 非法值回退 NORMAL（与 schema 默认一致），不抛错（工具层宽容解析约定）。
        val privilege = json["privilege"]?.jsonPrimitive?.contentOrNull
            ?.let { raw -> runCatching { PrivilegeLevel.valueOf(raw) }.getOrNull() }
            ?: PrivilegeLevel.NORMAL
        val env = json["env"]?.jsonObject?.entries?.associate {
            it.key to (it.value as? JsonPrimitive ?: JsonPrimitive("")).content
        } ?: emptyMap()
        val out = execute(Input(cwd, rows, cols, env, privilege, backend, workspaceId))
        return buildJsonObject {
            put("sessionId", JsonPrimitive(out.sessionId)); put("pid", JsonPrimitive(out.pid))
            put("shell", JsonPrimitive(out.shell)); put("cwd", JsonPrimitive(out.cwd))
            put("state", JsonPrimitive(out.state)); put("cursor", JsonPrimitive(out.cursor))
            put("backendId", JsonPrimitive(out.backendId))
            put("runtimeType", JsonPrimitive(out.runtimeType))
            out.rootfsId?.let { put("rootfsId", JsonPrimitive(it)) }
            out.guestCwd?.let { put("guestCwd", JsonPrimitive(it)) }
            out.workspaceId?.let { put("workspaceId", JsonPrimitive(it)) }
        }.toString()
    }

    data class Input(
        val cwd: String = "/sdcard",
        val rows: Int = 24,
        val cols: Int = AGENT_DEFAULT_COLS,
        val env: Map<String, String> = emptyMap(),
        val privilege: PrivilegeLevel = PrivilegeLevel.NORMAL,
        val backend: String = "linux-ubuntu",
        val workspaceId: String? = null
    )

    data class Output(
        val sessionId: Long,
        val pid: Int,
        val shell: String,
        val cwd: String,
        val rows: Int,
        val cols: Int,
        val privilege: String,
        val state: String,
        val cursor: Long,
        val backendId: String = "linux-ubuntu",
        val runtimeType: String = "LINUX",
        val rootfsId: String? = null,
        val guestCwd: String? = null,
        val workspaceId: String? = null
    )

    companion object {
        /**
         * Agent 会话默认列宽（实录 #15）：80 列硬折行拆碎长命令与标识符，
         * SCREEN 读屏误读率高（isinstance 被折成 isins + tance）。
         * 200 列在 IPC 夹紧范围（2..1024）内，长命令单行可读。
         */
        const val AGENT_DEFAULT_COLS = 200
    }
}
