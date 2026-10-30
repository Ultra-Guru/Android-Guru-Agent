package com.apex.agent.core.jiguan.tools

import com.apex.agent.core.jiguan.FridaScriptLibrary
import com.apex.agent.core.jiguan.HookKind
import com.apex.agent.core.jiguan.HookSpec
import com.apex.agent.core.jiguan.JiguanRuntime
import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolRisk
import javax.inject.Inject
import javax.inject.Singleton

/**
 * # Java / ART 域工具（Java Runtime）
 *
 * - [JiguanJavaClassesTool]：list_java_classes —— 已加载类枚举（过滤器内联进脚本）；
 * - [JiguanHookJavaMethodTool]：hook_java_method —— 声明式 Hook（LOG / REPLACE）；
 * - [JiguanCallJavaMethodTool]：call_java_method —— App 内部方法主动调用。
 *
 * 三者共享 Frida 桥（[JiguanRuntime.java]）—— 通道缺席时 Omni-Hook 的结构化
 * 降级指引直达 Agent。
 */
// ═══════════════════════════════════════════════════════════════════

@Singleton
class JiguanJavaClassesTool @Inject constructor(
    private val runtime: JiguanRuntime,
) : AgentTool {

    override val id = "JiguanJavaClasses"
    override val name = "Jiguan: Java Classes"
    override val description = """
        Enumerate loaded Java classes in a target process (list_java_classes) via the
        Frida bridge. ALWAYS pass a filter — apps load 10k+ classes; unfiltered output
        is capped at 4000 lines by the script.

        Example: {"pid": 12345, "filter": "Encrypt"}
    """.trimIndent()
    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "pid": {"type": "integer", "description": "Target process id"},
                "filter": {"type": "string", "description": "Substring filter (strongly recommended, e.g. Encrypt/Crypto/Ad)"}
            },
            "required": ["pid", "filter"]
        }
    """.trimIndent()
    override val metadata: ToolMetadata = ToolMetadata.Builder(id)
        .category(ToolCategory.SYSTEM)
        .risk(ToolRisk.MEDIUM)
        .tag("jiguan", "java", "frida")
        .build()

    override suspend fun execute(arguments: String): String {
        val args = JiguanArgs.parse(arguments) ?: return "Error: invalid JSON arguments"
        val pid = JiguanArgs.int(args, "pid", -1)
        val filter = JiguanArgs.string(args, "filter")
        if (pid <= 0) return "Error: missing required parameter 'pid'"
        if (filter.isBlank()) return "Error: missing required parameter 'filter' (10k+ classes loaded — filter is mandatory)"
        val script = FridaScriptLibrary.javaEnumerateClasses(filter)
        val output = runtime.java.runScript(pid, script, 30_000).fold(
            onSuccess = { it },
            onFailure = {
                return "Error: frida bridge unavailable (${it.message ?: "channel offline"}) — " +
                    "install frida-server (/data/local/tmp + root) and frida-tools in PRoot; see JiguanCapabilityReport."
            },
        )
        val classes = output.lineSequence()
            .filter { it.startsWith("[class] ") }
            .map { it.removePrefix("[class] ") }
            .toList()
        if (classes.isEmpty()) {
            return "No loaded classes matched \"$filter\". ${output.lineSequence().firstOrNull { it.startsWith("[done]") } ?: ""}"
        }
        return buildString {
            appendLine("Loaded classes matching \"$filter\" (${classes.size}):")
            classes.take(300).forEach { appendLine("- $it") }
            if (classes.size > 300) appendLine("… ${classes.size - 300} more — narrow the filter")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════

@Singleton
class JiguanHookJavaMethodTool @Inject constructor(
    private val runtime: JiguanRuntime,
) : AgentTool {

    override val id = "JiguanHookJavaMethod"
    override val name = "Jiguan: Hook Java Method"
    override val description = """
        Hook a Java method in a target process (hook_java_method) via the Frida bridge.
        Actions: "log" (record args/return, default) or "replace" (force a return value).
        The hook script is sandbox-validated before injection. Captured events are
        returned as console output.

        Example log: {"pid": 12345, "class": "com.x.EncryptUtil", "method": "aesEncrypt", "action": "log"}
        Example replace: {"pid": 12345, "class": "com.x.SplashAd", "method": "show", "action": "replace", "return": "true"}
    """.trimIndent()
    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "pid": {"type": "integer", "description": "Target process id"},
                "class": {"type": "string", "description": "Fully qualified class name"},
                "method": {"type": "string", "description": "Method name (all overloads hooked)"},
                "action": {"type": "string", "enum": ["log", "replace"], "description": "Hook behavior (default log)"},
                "return": {"type": "string", "description": "Return value literal for action=replace (null/true/false/number/string)"},
                "wait_seconds": {"type": "integer", "description": "Capture window in seconds (default 15)"}
            },
            "required": ["pid", "class", "method"]
        }
    """.trimIndent()
    override val metadata: ToolMetadata = ToolMetadata.Builder(id)
        .category(ToolCategory.SYSTEM)
        .risk(ToolRisk.HIGH)
        .tag("jiguan", "java", "hook", "frida")
        .build()

    override suspend fun execute(arguments: String): String {
        val args = JiguanArgs.parse(arguments) ?: return "Error: invalid JSON arguments"
        val pid = JiguanArgs.int(args, "pid", -1)
        val className = JiguanArgs.string(args, "class")
        val method = JiguanArgs.string(args, "method")
        if (pid <= 0 || className.isBlank() || method.isBlank()) {
            return "Error: required parameters pid/class/method"
        }
        val spec = HookSpec(
            kind = HookKind.JAVA_METHOD,
            pid = pid,
            className = className,
            methodName = method,
            action = JiguanArgs.string(args, "action", "log"),
            actionPayload = JiguanArgs.string(args, "return"),
        )
        val waitSec = JiguanArgs.int(args, "wait_seconds", 15).coerceIn(3, 60)
        val handle = runtime.hooks.install(spec, timeoutMs = waitSec * 1000L)
        return renderHookResult(handle.id, handle.channel.name, handle.events)
    }
}

// ═══════════════════════════════════════════════════════════════════

@Singleton
class JiguanCallJavaMethodTool @Inject constructor(
    private val runtime: JiguanRuntime,
) : AgentTool {

    override val id = "JiguanCallJavaMethod"
    override val name = "Jiguan: Call Java Method"
    override val description = """
        Actively invoke a method inside a target process (call_java_method) via the
        Frida bridge. Static methods call on the class; instance methods instantiate
        first (no-arg constructor). Use JiguanJavaClasses to discover targets.

        Example: {"pid": 12345, "class": "com.x.Util", "method": "getSign", "args": "[\"abc\"]", "static": true}
    """.trimIndent()
    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "pid": {"type": "integer", "description": "Target process id"},
                "class": {"type": "string", "description": "Fully qualified class name"},
                "method": {"type": "string", "description": "Method name"},
                "args": {"type": "string", "description": "JSON array of arguments, e.g. [\"abc\", 1] (default [])"},
                "static": {"type": "boolean", "description": "true = static call, false = instance via no-arg ctor (default true)"}
            },
            "required": ["pid", "class", "method"]
        }
    """.trimIndent()
    override val metadata: ToolMetadata = ToolMetadata.Builder(id)
        .category(ToolCategory.SYSTEM)
        .risk(ToolRisk.HIGH)
        .tag("jiguan", "java", "frida")
        .build()

    override suspend fun execute(arguments: String): String {
        val args = JiguanArgs.parse(arguments) ?: return "Error: invalid JSON arguments"
        val pid = JiguanArgs.int(args, "pid", -1)
        val className = JiguanArgs.string(args, "class")
        val method = JiguanArgs.string(args, "method")
        if (pid <= 0 || className.isBlank() || method.isBlank()) {
            return "Error: required parameters pid/class/method"
        }
        val argsJson = JiguanArgs.string(args, "args", "[]").ifBlank { "[]" }
        if (!argsJson.trim().startsWith("[")) {
            return "Error: 'args' must be a JSON array literal like [\"abc\", 1]"
        }
        val script = FridaScriptLibrary.javaCallMethod(
            className = className,
            methodName = method,
            argsJson = argsJson,
            static = JiguanArgs.bool(args, "static", true),
        )
        val output = runtime.java.runScript(pid, script, 20_000).fold(
            onSuccess = { it },
            onFailure = {
                return "Error: frida bridge unavailable (${it.message ?: "channel offline"}) — " +
                    "see JiguanCapabilityReport for channel setup guidance."
            },
        )
        val ret = output.lineSequence().firstOrNull { it.startsWith("[ret] ") }
            ?: return "Call executed but no return captured. Output:\n$output"
        return "Call $className.$method($argsJson) → ${ret.removePrefix("[ret] ")}"
    }
}

/** Hook 结果渲染（Java/Native Hook 工具共用）。 */
internal fun renderHookResult(handleId: Int, channel: String, events: List<String>): String = buildString {
    appendLine("Hook #$handleId installed via $channel.")
    if (events.isEmpty()) {
        appendLine("(no events captured in the window — trigger the target behavior and re-check)")
    } else {
        appendLine("Captured events (${events.size}):")
        events.take(120).forEach { appendLine(it) }
        if (events.size > 120) appendLine("… ${events.size - 120} more")
    }
}
