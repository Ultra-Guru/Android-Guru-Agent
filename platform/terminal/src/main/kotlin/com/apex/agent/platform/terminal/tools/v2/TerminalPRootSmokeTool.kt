package com.apex.agent.platform.terminal.tools.v2

import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.platform.terminal.proot.LinuxPRootBackend
import com.apex.agent.platform.terminal.proot.PRootSmokeProbe
import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.tools.TerminalTool
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Agent tool: TerminalPRootSmoke — 显式 PRoot 运行时健康探测。
 *
 * 与 [TerminalBackendsTool] 的分工（T99 评审 B1 修复）：
 *  - `TerminalBackends` 保持**纯发现**（readOnlyHint=true）：只读请求永不
 *    exec `<proot> -r <rootfs> /bin/true`。否则 PLAN 模式放行只读工具时，
 *    一次普通查询就会执行可能被修改过的 rootfs 里的 guest 二进制。
 *  - 本工具显式执行 guest 探针；它不是只读工具，`readOnlyHint=false`
 *    （继承 Terminal 家族 MEDIUM 推断）→ PLAN 拒绝、DEFAULT/RiskAwareToolGate
 *    按既有副作用面处理。默认后端与路由不变，PRoot 仍为默认。
 *
 * JSON Schema (input):  { }（无参数）
 * JSON Schema (output): { id, proot, runtimeType, smoke: ok|timed_out|
 *                          ptrace_signal|binary_error|not_ready,
 *                          exitCode?, suggestion? }
 */
class TerminalPRootSmokeTool(
    private val runtime: TerminalRuntime,
    private val smokeProbe: suspend () -> PRootSmokeProbe.SmokeResult
) : TerminalTool {
    override val id: String = "TerminalPRootSmoke"
    override val name: String = id
    override val description: String = """
        Run an explicit PRoot runtime health probe: execute `/bin/true` inside the installed
        Ubuntu rootfs under ptrace and report the observed outcome. Use this when
        TerminalBackends reports linux-ubuntu READY but you want to know whether ptrace
        restrictions on this device are *likely* (a timeout or SIGTRAP/SIGSYS signal), which
        is not proof of any specific kernel policy. This tool executes guest code; it is not
        a read-only query, and it never switches the default backend or routing.
    """.trimIndent()

    override val parametersSchema: String = """
{"type":"object","properties":{},"required":[]}
    """.trimIndent()

    override suspend fun invoke(arguments: String): String {
        if (arguments.isNotBlank()) {
            runCatching { Json.parseToJsonElement(arguments).jsonObject }
        }
        val backends = runtime.backends()
        val ready = backends.any { it.id == LinuxPRootBackend.ID && it.state == "READY" }
        val smoke = if (ready) {
            try {
                smokeProbe.invoke()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                PRootSmokeProbe.SmokeResult(PRootSmokeProbe.Status.BINARY_ERROR)
            }
        } else {
            PRootSmokeProbe.SmokeResult(PRootSmokeProbe.Status.NOT_READY)
        }
        val suggestion = when (smoke.status) {
            PRootSmokeProbe.Status.TIMED_OUT, PRootSmokeProbe.Status.PTRACE_SIGNAL ->
                "PRoot smoke probe ${smoke.status.name.lowercase()}: ptrace restrictions on this device are likely, " +
                    "but not proven. Sessions may hang. Consider backend 'linux-ubuntu-chroot' " +
                    "if superuser is granted, or report the issue. No backend was switched."
            else -> null
        }
        if (suggestion != null) {
            runCatching { AppLogger.instance.warn(LogCategory.TOOL, id, suggestion) }
        }
        return buildJsonObject {
            put("id", JsonPrimitive(id))
            put("proot", JsonPrimitive(ready))
            put("runtimeType", JsonPrimitive("LINUX"))
            put("smoke", JsonPrimitive(smoke.status.name.lowercase()))
            smoke.exitCode?.let { put("exitCode", JsonPrimitive(it)) }
            suggestion?.let { put("suggestion", JsonPrimitive(it)) }
        }.toString()
    }
}
