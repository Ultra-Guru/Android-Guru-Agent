package com.apex.agent.core.tools.pluginhost.claude

import com.apex.agent.core.tools.pluginhost.bridge.PluginEventBus
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * # ClaudeHookPort —— Claude 钩子命令执行端口
 *
 * 官方钩子是 **shell 命令**（`hooks.json` 声明，经 stdin 喂 JSON、读
 * stdout/exit code）。Android 上它们在 PRoot 沙箱内执行（app 层接线，
 * Phase 6）；JVM 测试用 [LocalShellHookPort]（真实 /bin/sh）。
 *
 * 安全边界：命令来自**已过安装闸门**（hash/confirm）的插件包内容，
 * 执行发生在沙箱域；本端口契约要求实现方保证 cwd 与 env 注入
 * （CLAUDE_PLUGIN_ROOT）由宿主控制，用户输入永不拼接进命令串
 * （用户数据只经 stdin JSON —— 官方协议本身如此设计）。
 */
interface ClaudeHookPort {
    suspend fun exec(
        command: String,
        cwd: File,
        timeoutMs: Long,
        env: Map<String, String>,
        /** 官方协议：输入 JSON 经 stdin 喂给钩子（用户数据不经命令行）。 */
        stdinJson: String
    ): Result<com.apex.agent.core.tools.extension.NodeInstallPort.ExecOutcome>

    /** JVM 直启实现（真实 /bin/sh —— CI E2E 用；Android app 走沙箱实现）。 */
    class LocalShellHookPort : ClaudeHookPort {
        override suspend fun exec(
            command: String,
            cwd: File,
            timeoutMs: Long,
            env: Map<String, String>,
            stdinJson: String
        ): Result<com.apex.agent.core.tools.extension.NodeInstallPort.ExecOutcome> =
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val builder = ProcessBuilder(listOf("/bin/sh", "-c", command))
                        .directory(cwd)
                        .redirectErrorStream(false)
                    builder.environment().putAll(env)
                    val process = builder.start()
                    // stdout/stderr 必须与超时等待**并发**收干：同步 read 会阻塞到
                    // 进程退出，超时语义完全失效（管道写满时还会永久卡死）
                    val stdoutFuture = java.util.concurrent.CompletableFuture.supplyAsync {
                        process.inputStream.bufferedReader().readText()
                    }
                    val stderrFuture = java.util.concurrent.CompletableFuture.supplyAsync {
                        process.errorStream.bufferedReader().readText()
                    }
                    process.outputStream.use { it.write(stdinJson.toByteArray(Charsets.UTF_8)) }
                    val finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                    if (!finished) {
                        process.destroyForcibly()
                        // 立即返回不等 stdout future：destroyForcibly 只杀直接子进程，
                        // 孙进程（脚本里的 sleep 等）继续持有管道写端 —— 等流闭合
                        // 会把「超时」拖成「挂起」。孤儿读线程随管道关闭自然终结。
                        return@runCatching com.apex.agent.core.tools.extension.NodeInstallPort.ExecOutcome(
                            exitCode = -1,
                            stdout = "",
                            stderr = "hook timeout after ${timeoutMs}ms"
                        )
                    }
                    com.apex.agent.core.tools.extension.NodeInstallPort.ExecOutcome(
                        exitCode = process.exitValue(),
                        stdout = stdoutFuture.get(5, java.util.concurrent.TimeUnit.SECONDS).orEmpty(),
                        stderr = stderrFuture.get(5, java.util.concurrent.TimeUnit.SECONDS).orEmpty()
                    )
                }
            }
    }
}

/**
 * # ClaudeHookAdapter —— 宿主事件 → Claude 官方钩子调度（spec §3.6）
 *
 * 官方协议落地：
 * 1. 事件映射表（[toClaudeEvent]）—— 无映射的宿主事件如实不分发；
 * 2. matcher 匹配（工具事件按官方 regex 语义）；
 * 3. 输入构造：官方 stdin JSON（session_id / hook_event_name / tool_name /
 *    tool_input / cwd）+ CLAUDE_PLUGIN_ROOT 环境变量；
 * 4. 退出码语义：0 = 成功；2 = 阻断（stderr 反馈）；其它 = 非阻塞错误；
 * 5. stdout JSON 的 hookSpecificOutput 解析（additionalContext /
 *    permissionDecision）—— 部分字段宿主暂不消费时保留原样（前向兼容）。
 *
 * 类型边界：`type != "command"`（prompt/agent/http 钩子）按官方文档存在
 * 但宿主未支持 —— 结果里如实标注 skippedNotSupported，不冒充执行。
 */
class ClaudeHookAdapter(
    private val port: ClaudeHookPort
) {
    /** 单个钩子的执行结果。 */
    data class HookResult(
        val command: String,
        val exitCode: Int?,
        /** 官方语义：exit 2 = 阻断。 */
        val blocking: Boolean,
        val stderr: String,
        /** stdout JSON 的 additionalContext（无则空）。 */
        val additionalContext: String,
        /** stdout JSON 的 permissionDecision（allow/deny；无则 null）。 */
        val permissionDecision: String?,
        /** 超时（exitCode = -1）。 */
        val timedOut: Boolean,
        /** 类型未支持被跳过（prompt/agent/http）。 */
        val skippedNotSupported: Boolean
    )

    /** 一次事件的调度产物（宿主/引擎消费）。 */
    data class DispatchOutcome(
        val event: PluginEventBus.PluginHostEvent,
        val results: List<HookResult>
    ) {
        /** 聚合的 additionalContext（顺序保留，去空）。 */
        val contexts: List<String>
            get() = results.mapNotNull { it.additionalContext.takeIf { c -> c.isNotBlank() } }

        /** 是否有阻断性失败（exit 2）—— 调用方（引擎）决定后续行为。 */
        val blocked: Boolean get() = results.any { it.blocking }

        /** PreToolUse 的 deny 决策（工具门）。 */
        val denied: Boolean
            get() = results.any { it.permissionDecision.equals("deny", ignoreCase = true) }
    }

    /**
     * 调度一个宿主事件到插件钩子。
     *
     * @param pluginRoot 插件包根（CLAUDE_PLUGIN_ROOT 注入值）
     * @param toolName 工具事件携带的工具名（matcher 匹配用）
     * @param toolInputJson 工具入参（官方 tool_input）
     * @param sessionId 会话 id（官方 session_id）
     */
    suspend fun dispatch(
        manifest: ClaudeHooksManifest.Manifest,
        event: PluginEventBus.PluginHostEvent,
        pluginRoot: File,
        toolName: String? = null,
        toolInputJson: String = "{}",
        sessionId: String = "apex-session"
    ): DispatchOutcome {
        val claudeEvent = toClaudeEvent(event)
            ?: return DispatchOutcome(event, emptyList())
        val groups = manifest.groups[claudeEvent].orEmpty()
        val results = mutableListOf<HookResult>()
        for (group in groups) {
            if (!ClaudeHooksManifest.matchesTool(group, toolName)) continue
            for (hook in group.hooks) {
                if (hook.type != "command" || hook.command.isBlank()) {
                    results += HookResult(
                        command = hook.command, exitCode = null, blocking = false,
                        stderr = "", additionalContext = "", permissionDecision = null,
                        timedOut = false, skippedNotSupported = true
                    )
                    continue
                }
                results += runHook(hook, claudeEvent, pluginRoot, toolName, toolInputJson, sessionId)
            }
        }
        return DispatchOutcome(event, results)
    }

    private suspend fun runHook(
        hook: ClaudeHooksManifest.HookEntry,
        claudeEvent: String,
        pluginRoot: File,
        toolName: String?,
        toolInputJson: String,
        sessionId: String
    ): HookResult {
        val cwd = pluginRoot.canonicalFile
        val command = hook.command
            .replace("\${CLAUDE_PLUGIN_ROOT}", cwd.path)
            .replace("\${CLAUDE_PROJECT_DIR}", cwd.path)
        val timeoutMs = ((hook.timeout ?: DEFAULT_TIMEOUT_SECONDS) * 1_000L).coerceAtLeast(1_000L)
        val inputJson = buildInputJson(claudeEvent, toolName, toolInputJson, sessionId, cwd)
        val outcome = port.exec(
            command = command,
            cwd = cwd,
            timeoutMs = timeoutMs,
            env = mapOf("CLAUDE_PLUGIN_ROOT" to cwd.path),
            stdinJson = inputJson
        ).getOrElse {
            return HookResult(
                command = command, exitCode = null, blocking = false,
                stderr = it.message ?: it::class.simpleName ?: "exec failed",
                additionalContext = "", permissionDecision = null,
                timedOut = false, skippedNotSupported = false
            )
        }
        val parsed = parseSpecificOutput(outcome.stdout)
        return HookResult(
            command = command,
            exitCode = outcome.exitCode,
            blocking = outcome.exitCode == 2,
            stderr = outcome.stderr,
            additionalContext = parsed?.get("additionalContext")?.jsonPrimitive?.content ?: "",
            permissionDecision = parsed?.get("permissionDecision")?.jsonPrimitive?.content,
            timedOut = outcome.exitCode == -1,
            skippedNotSupported = false
        )
    }

    /** 官方 stdin JSON 形状（Common input fields）。 */
    private fun buildInputJson(
        event: String,
        toolName: String?,
        toolInputJson: String,
        sessionId: String,
        cwd: File
    ): String {
        val toolInput = runCatching { json.parseToJsonElement(toolInputJson) }
            .getOrNull() ?: kotlinx.serialization.json.JsonPrimitive(toolInputJson)
        return json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            buildJsonObject {
                put("session_id", sessionId)
                put("transcript_path", "")
                put("cwd", cwd.path)
                put("hook_event_name", event)
                if (toolName != null) put("tool_name", toolName)
                put("tool_input", toolInput)
            }
        )
    }

    private fun parseSpecificOutput(stdout: String): JsonObject? {
        val trimmed = stdout.trim().takeIf { it.startsWith("{") } ?: return null
        val root = runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull() ?: return null
        return root["hookSpecificOutput"] as? JsonObject
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        private const val DEFAULT_TIMEOUT_SECONDS = 60

        /**
         * 宿主事件 → 官方事件映射（spec §3.6 要求明确映射表；无映射不伪造）。
         */
        fun toClaudeEvent(event: PluginEventBus.PluginHostEvent): String? = when (event) {
            PluginEventBus.PluginHostEvent.SESSION_START -> ClaudeHooksManifest.Events.SESSION_START
            PluginEventBus.PluginHostEvent.SESSION_END -> ClaudeHooksManifest.Events.SESSION_END
            PluginEventBus.PluginHostEvent.USER_PROMPT_SUBMIT -> ClaudeHooksManifest.Events.USER_PROMPT_SUBMIT
            PluginEventBus.PluginHostEvent.BEFORE_TOOL_CALL -> ClaudeHooksManifest.Events.PRE_TOOL_USE
            PluginEventBus.PluginHostEvent.AFTER_TOOL_CALL -> ClaudeHooksManifest.Events.POST_TOOL_USE
            PluginEventBus.PluginHostEvent.AFTER_TOOL_CALL_FAILURE ->
                ClaudeHooksManifest.Events.POST_TOOL_USE_FAILURE
            PluginEventBus.PluginHostEvent.NOTIFICATION -> ClaudeHooksManifest.Events.NOTIFICATION
            // plugin.* 是宿主内部事件，Claude 官方面无对应 —— 如实不映射
            else -> null
        }
    }
}
