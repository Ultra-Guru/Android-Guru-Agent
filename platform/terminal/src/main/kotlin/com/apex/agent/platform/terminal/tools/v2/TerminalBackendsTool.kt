package com.apex.agent.platform.terminal.tools.v2

import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.platform.terminal.proot.LinuxPRootBackend
import com.apex.agent.platform.terminal.proot.PRootSmokeProbe
import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.tools.TerminalTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.CancellationException

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
    private val runtime: TerminalRuntime,
    /** Explicit tool invocation only; never called from backend availability or app startup. */
    private val smokeProbe: (suspend () -> PRootSmokeProbe.SmokeResult)? = null
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
        val smoke = if (smokeProbe != null && list.any { it.id == LinuxPRootBackend.ID && it.state == "READY" }) {
            try {
                smokeProbe.invoke()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                PRootSmokeProbe.SmokeResult(PRootSmokeProbe.Status.BINARY_ERROR)
            }
        } else null
        val suggestion = when (smoke?.status) {
            PRootSmokeProbe.Status.TIMED_OUT, PRootSmokeProbe.Status.PTRACE_SIGNAL ->
                "PRoot smoke probe ${smoke.status.name.lowercase()}: ptrace restrictions on this device are likely, " +
                    "but not proven. Sessions may hang. Consider backend 'linux-ubuntu-chroot' " +
                    "if superuser is granted, or report the issue. No backend was switched."
            else -> null
        }
        if (suggestion != null) {
            runCatching { AppLogger.instance.warn(LogCategory.TOOL, "TerminalBackends", suggestion) }
        }
        return buildJsonObject {
            put("backends", buildJsonArray {
                for (b in list) {
                    add(buildJsonObject {
                        put("id", JsonPrimitive(b.id))
                        put("runtimeType", JsonPrimitive(b.runtimeType))
                        put("available", JsonPrimitive(b.available))
                        put("state", JsonPrimitive(b.state))
                        if (b.id == LinuxPRootBackend.ID && b.state == "READY" && smoke != null &&
                            smoke.status != PRootSmokeProbe.Status.NOT_READY
                        ) put("smoke", JsonPrimitive(smoke.status.name.lowercase()))
                        b.detail?.let { put("detail", JsonPrimitive(it)) }
                    })
                }
            })
            suggestion?.let { put("suggestion", JsonPrimitive(it)) }
        }.toString()
    }
}
