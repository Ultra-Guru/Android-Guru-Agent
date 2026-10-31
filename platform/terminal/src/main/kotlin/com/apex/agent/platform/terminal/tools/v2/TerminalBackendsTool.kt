package com.apex.agent.platform.terminal.tools.v2

import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.tools.TerminalTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Agent tool: TerminalBackends — T73（后端能力发现）
 *
 * ExecutionBackend.kt 注释指定的 P73 交付物：availability() 的 Agent 出口。
 *
 * 列出所有已注册执行后端及其真实可用性：
 *   - linux-ubuntu   LINUX          READY / NEEDS_ROOTFS / FAILED
 *   - linux-ubuntu   LINUX          READY（rootfs 已装）/ NEEDS_ROOTFS（先用
 *                                   TerminalUbuntuEnsure 引导）/ FAILED（proot 缺失等）
 *
 * Agent 决策链：backends() → 需要 Ubuntu 但 NEEDS_ROOTFS → TerminalUbuntuEnsure
 * → READY 后 TerminalCreate(backend="linux-ubuntu") → run/observe/write …
 *
 * JSON Schema (input):  { }（无参数）
 * JSON Schema (output): { backends: [ { id, runtimeType, available, state, detail? } ] }
 */
class TerminalBackendsTool(
    private val runtime: TerminalRuntime
) : TerminalTool {
    override val id: String = "TerminalBackends"
    override val name: String = id
    override val description: String = """
        Discover available terminal execution backends and their real availability.
        Returns each backend's id (use as TerminalCreate's "backend" parameter), runtime type,
        and state: READY (usable now) / NEEDS_ROOTFS (bundled archive not yet extracted — call TerminalUbuntuEnsure, offline, minutes for the full ~1GB environment) /
        FAILED (with reason). Check this before creating a linux-ubuntu session.
        linux-ubuntu (PRoot, works on any device) is the default; linux-ubuntu-chroot (real-root
        chroot, near-native performance, no ptrace dependency) is READY only on rooted devices —
        prefer it there when PRoot is slow or blocked.
    """.trimIndent()

    override val parametersSchema: String = """
{"type":"object","properties":{},"required":[]}
    """.trimIndent()

    override suspend fun invoke(arguments: String): String {
        // 无参数；容忍调用方传 {} 或多余键
        if (arguments.isNotBlank()) {
            runCatching { Json.parseToJsonElement(arguments).jsonObject }
        }
        val list = runtime.backends()
        return buildJsonObject {
            put("backends", buildJsonArray {
                for (b in list) {
                    add(buildJsonObject {
                        put("id", JsonPrimitive(b.id))
                        put("runtimeType", JsonPrimitive(b.runtimeType))
                        put("available", JsonPrimitive(b.available))
                        put("state", JsonPrimitive(b.state))
                        b.detail?.let { put("detail", JsonPrimitive(it)) }
                    })
                }
            })
        }.toString()
    }
}
