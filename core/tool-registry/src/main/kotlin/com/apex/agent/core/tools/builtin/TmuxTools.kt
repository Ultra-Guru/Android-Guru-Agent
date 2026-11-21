package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.StructuredAgentTool
import com.apex.agent.core.tools.ToolErrorCode
import com.apex.agent.core.tools.ToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Tmux 多会话 Agent 工具（Operit 对标：docs/terminal/operit-rikkahub-comparison.md §4.7
 * 明确列出 Operit 有 tmux 多会话而 apex 缺失 —— 本文件补齐该能力面）。
 *
 * 三个核心工具（任务定义）：
 *  - [TmuxNewSessionTool]    → `tmux_new_session`（创建/幂等复用命名会话）
 *  - [TmuxSwitchSessionTool] → `tmux_switch_session`（切换活动会话）
 *  - [TmuxListSessionsTool]  → `tmux_list_sessions`（列出存活会话）
 *
 * 附带增补（只创建/切换而不 send/capture 的多会话没有实际价值 —— Operit §4.7 的
 * 价值就在并行交互）：[TmuxSendKeysTool] / [TmuxCapturePaneTool] / [TmuxKillSessionTool]。
 *
 * 模块边界（docs/terminal/TERMINAL_SDK_BOUNDARY.md 的毕业前提）：core/tool-registry
 * 对 platform:terminal **零 Kotlin import**。本文件因此不 import 任何 terminal 类型 ——
 * 它依赖同文件内声明的 [TmuxSessionGateway]（纯 JVM 接缝，对齐
 * TerminalLinuxPackagesTool ← LinuxPackageManager 的同款解耦）。真实实现是
 * platform:terminal 的 [com.apex.agent.platform.terminal.tmux.TmuxSessionManager]
 * （方法签名一一对应，app 侧 di/ToolModule.kt 直接 `::createSession` 委托注册）。
 *
 * 错误传播契约：业务失败以 `ok=false` + 结构化 errorCode 返回（实现侧抛
 * TmuxException 时在工具层兜底），**绝不抛异常穿透工具边界** —— Agent 侧靠
 * 结构化结果决策 retry/abort。
 */
/**
 * Tmux 会话操作的纯 JVM 接缝。core 不反向依赖 platform:terminal；由
 * [com.apex.agent.platform.terminal.tmux.TmuxSessionManager] 实现同名方法。
 */
interface TmuxSessionGateway {
    suspend fun createSession(name: String, workingDirectory: String?): String
    suspend fun switchSession(name: String): String
    suspend fun listSessions(): String
    suspend fun killSession(name: String): String
    suspend fun sendKeys(name: String, command: String): String
    suspend fun capturePane(name: String, lines: Int): String
}

/** 三工具共享的参数解析/兜底（宽松：字符串容忍缺失，数字按需取整）。 */
internal object TmuxToolArgs {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun parse(arguments: String): JsonObject? =
        runCatching { Json.parseToJsonElement(arguments).jsonObject }.getOrNull()

    fun str(args: JsonObject, key: String): String? =
        (args[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    fun int(args: JsonObject, key: String): Int? =
        (args[key] as? JsonPrimitive)?.intOrNull

    /**
     * 兜底：参数缺失/JSON 畸形/实现侧异常 → 同构 JSON。
     * 与 manager 的失败载荷同形（ok/errorCode/retryable/error），这样 Agent 拿到的
     * 始终是结构化决策依据，而非被 v1 `"Error: …"` 协议拍平丢失 errorCode 的字符串。
     */
    fun fallback(action: String, code: String, retryable: Boolean, message: String?): String =
        buildJsonObject {
            put("ok", false)
            put("action", action)
            put("errorCode", code)
            put("retryable", retryable)
            put("error", message ?: "tmux operation failed")
        }.toString()
}

/** ── tmux_new_session：创建命名会话（幂等：同名存活 → 直接复用，不报错）。 ── */
class TmuxNewSessionTool(private val gateway: TmuxSessionGateway) : StructuredAgentTool {
    override val id: String = "tmux_new_session"
    override val name: String = id
    override val description: String =
        "Create a new named tmux session inside the Ubuntu guest (idempotent: reuses an " +
            "existing live session with the same name) and set it as the active session. " +
            "Requires the guest rootfs to have tmux installed."

    // tmux 目标名禁 `.` `:` —— tmux 会把它们解析成 window/pane 定位符。
    override val parametersSchema: String =
        """{"type":"object","properties":{"name":{"type":"string","description":"Session name. Allowed pattern: ^[A-Za-z0-9][A-Za-z0-9_-]{0,63} (tmux target names forbid '.' and ':')."},"workingDirectory":{"type":"string","description":"Optional absolute path inside the guest used as the session's starting cwd."}},"required":["name"]}"""

    override suspend fun executeStructured(arguments: String): ToolResult {
        val action = "new"
        val args = TmuxToolArgs.parse(arguments)
            ?: return ToolResult.ok(TmuxToolArgs.fallback(action, "InvalidInput", false, "arguments is not valid JSON"))
        val name = TmuxToolArgs.str(args, "name")
            ?: return ToolResult.ok(TmuxToolArgs.fallback(action, "InvalidInput", false, "missing 'name'"))
        return try {
            ToolResult.ok(gateway.createSession(name, TmuxToolArgs.str(args, "workingDirectory")))
        } catch (e: Exception) {
            ToolResult.ok(TmuxToolArgs.fallback(action, "TmuxCommandFailed", true, e.message))
        }
    }
}

/** ── tmux_switch_session：切换活动会话（仅存活会话；store 落 lastActive）。 ── */
class TmuxSwitchSessionTool(private val gateway: TmuxSessionGateway) : StructuredAgentTool {
    override val id: String = "tmux_switch_session"
    override val name: String = id
    override val description: String =
        "Switch the active tmux session by name. Only live sessions can be activated; the " +
            "choice is persisted as the default target for subsequent send/capture calls."

    override val parametersSchema: String =
        """{"type":"object","properties":{"name":{"type":"string","description":"Target session name to activate."}},"required":["name"]}"""

    override suspend fun executeStructured(arguments: String): ToolResult {
        val action = "switch"
        val args = TmuxToolArgs.parse(arguments)
            ?: return ToolResult.ok(TmuxToolArgs.fallback(action, "InvalidInput", false, "arguments is not valid JSON"))
        val name = TmuxToolArgs.str(args, "name")
            ?: return ToolResult.ok(TmuxToolArgs.fallback(action, "InvalidInput", false, "missing 'name'"))
        return try {
            ToolResult.ok(gateway.switchSession(name))
        } catch (e: Exception) {
            ToolResult.ok(TmuxToolArgs.fallback(action, "TmuxCommandFailed", true, e.message))
        }
    }
}

/** ── tmux_list_sessions：列出全部存活会话（权威：`tmux ls`）。 ── */
class TmuxListSessionsTool(private val gateway: TmuxSessionGateway) : StructuredAgentTool {
    override val id: String = "tmux_list_sessions"
    override val name: String = id
    override val description: String =
        "List all live tmux sessions in the Ubuntu guest with their window counts, creation " +
            "times, attached flags, and which one is currently active. Source of truth is " +
            "`tmux ls` — a registered-but-dead session is never reported live."

    override val parametersSchema: String =
        """{"type":"object","properties":{}}"""

    override suspend fun executeStructured(arguments: String): ToolResult = try {
        ToolResult.ok(gateway.listSessions())
    } catch (e: Exception) {
        ToolResult.ok(TmuxToolArgs.fallback("list", "TmuxCommandFailed", true, e.message))
    }
}

/** ── tmux_send_keys：向目标会话发一行命令（多会话并行的实际价值点）。 ── */
class TmuxSendKeysTool(private val gateway: TmuxSessionGateway) : StructuredAgentTool {
    override val id: String = "tmux_send_keys"
    override val name: String = id
    override val description: String =
        "Send a single command line to a named tmux session (equivalent to typing it in that " +
            "session and pressing Enter). This is how the agent drives several sessions in " +
            "parallel without attaching."

    override val parametersSchema: String =
        """{"type":"object","properties":{"name":{"type":"string","description":"Target session name."},"command":{"type":"string","description":"Command line to send. Newlines are collapsed and sent with one Enter."}},"required":["name","command"]}"""

    override suspend fun executeStructured(arguments: String): ToolResult {
        val action = "sendKeys"
        val args = TmuxToolArgs.parse(arguments)
            ?: return ToolResult.ok(TmuxToolArgs.fallback(action, "InvalidInput", false, "arguments is not valid JSON"))
        val name = TmuxToolArgs.str(args, "name")
            ?: return ToolResult.ok(TmuxToolArgs.fallback(action, "InvalidInput", false, "missing 'name'"))
        val command = TmuxToolArgs.str(args, "command")
            ?: return ToolResult.ok(TmuxToolArgs.fallback(action, "InvalidInput", false, "missing 'command'"))
        return try {
            ToolResult.ok(gateway.sendKeys(name, command))
        } catch (e: Exception) {
            ToolResult.ok(TmuxToolArgs.fallback(action, "TmuxCommandFailed", true, e.message))
        }
    }
}

/** ── tmux_capture_pane：抓取目标会话当前屏面（无需 attach 即可读输出）。 ── */
class TmuxCapturePaneTool(private val gateway: TmuxSessionGateway) : StructuredAgentTool {
    override val id: String = "tmux_capture_pane"
    override val name: String = id
    override val description: String =
        "Capture the visible pane contents of a named tmux session so the agent can read its " +
            "output without attaching. 'lines' bounds the scrollback window (1..10000, default 200)."

    override val parametersSchema: String =
        """{"type":"object","properties":{"name":{"type":"string","description":"Target session name."},"lines":{"type":"integer","description":"How many lines of scrollback to capture (default 200)."}},"required":["name"]}"""

    override suspend fun executeStructured(arguments: String): ToolResult {
        val action = "capture"
        val args = TmuxToolArgs.parse(arguments)
            ?: return ToolResult.ok(TmuxToolArgs.fallback(action, "InvalidInput", false, "arguments is not valid JSON"))
        val name = TmuxToolArgs.str(args, "name")
            ?: return ToolResult.ok(TmuxToolArgs.fallback(action, "InvalidInput", false, "missing 'name'"))
        return try {
            ToolResult.ok(gateway.capturePane(name, TmuxToolArgs.int(args, "lines") ?: 200))
        } catch (e: Exception) {
            ToolResult.ok(TmuxToolArgs.fallback(action, "TmuxCommandFailed", true, e.message))
        }
    }
}

/** ── tmux_kill_session：终止会话（不存在 → SessionNotFound）。 ── */
class TmuxKillSessionTool(private val gateway: TmuxSessionGateway) : StructuredAgentTool {
    override val id: String = "tmux_kill_session"
    override val name: String = id
    override val description: String =
        "Kill a named tmux session. The session must be live; killing it also clears its " +
            "registration and demotes it from active if it was active."

    override val parametersSchema: String =
        """{"type":"object","properties":{"name":{"type":"string","description":"Target session name to kill."}},"required":["name"]}"""

    override suspend fun executeStructured(arguments: String): ToolResult {
        val action = "kill"
        val args = TmuxToolArgs.parse(arguments)
            ?: return ToolResult.ok(TmuxToolArgs.fallback(action, "InvalidInput", false, "arguments is not valid JSON"))
        val name = TmuxToolArgs.str(args, "name")
            ?: return ToolResult.ok(TmuxToolArgs.fallback(action, "InvalidInput", false, "missing 'name'"))
        return try {
            ToolResult.ok(gateway.killSession(name))
        } catch (e: Exception) {
            ToolResult.ok(TmuxToolArgs.fallback(action, "TmuxCommandFailed", true, e.message))
        }
    }
}
