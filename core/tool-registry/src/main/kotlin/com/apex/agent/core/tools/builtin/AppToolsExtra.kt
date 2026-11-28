package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.device.DeviceOpResult
import com.apex.agent.core.tools.device.DeviceOpsPort
import kotlinx.serialization.json.*

/**
 * 应用安装工具
 *
 * Installs an APK file already on device storage.
 * Use DownloadFile first if the APK is from a URL.
 */
class AppInstallTool(
    private val shellExecutor: suspend (String) -> String
) : AgentTool {

    override val id = "AppInstall"
    override val name = "Install APK"
    override val description = """
        Install an APK file on the device.
        The APK must already be on device storage.
        Use DownloadFile first if the APK is from a URL.

        Examples:
        - {"path": "/sdcard/Download/app.apk"}
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "Path to APK file"}
            },
            "required": ["path"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val path = json["path"]?.jsonPrimitive?.content ?: return "Error: 'path' required"
        // Shell-escape the APK path — unescaped `;` / `$(...)` would inject into `pm install -r $path`.
        return shellExecutor("pm install -r ${ShellQuote.shellQuote(path)}")
    }
}

/**
 * 应用卸载工具
 *
 * Uninstalls an app by package name.
 * WARNING: This removes the app and its data permanently (unless keep_data=true).
 *
 * S05：DCP 启用时内部委托 [DeviceOpsPort.executeLegacy]（package.uninstall）
 * —— preflight（系统应用/设备管理器/桌面/自身四重守卫）+ 语义分级确认 +
 * 执行后复探；返回文本形态保持向后兼容（Success / Error: 前缀）。
 * `dcp.enabled=false` 或未注入 port 时走原 shellExec 链，行为与旧版一致。
 */
class AppUninstallTool(
    private val shellExecutor: suspend (String) -> String,
    private val deviceOps: DeviceOpsPort? = null
) : AgentTool {

    override val id = "AppUninstall"
    override val name = "Uninstall App"
    override val description = """
        Uninstall an app by package name.
        WARNING: This removes the app and its data permanently.

        Examples:
        - {"package": "com.example.app"}
        - {"package": "com.example.app", "keep_data": true}
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "package": {"type": "string", "description": "Package name to uninstall"},
                "keep_data": {"type": "boolean", "description": "Keep app data (default: false)"}
            },
            "required": ["package"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val pkg = json["package"]?.jsonPrimitive?.content ?: return "Error: 'package' required"
        if (!ShellQuote.isValidPackageName(pkg)) {
            return "Error: invalid package name '$pkg' (must match ^[A-Za-z0-9._]+\$)"
        }
        val keepData = json["keep_data"]?.jsonPrimitive?.booleanOrNull ?: false

        // S05：DCP 委托优先 —— 结构化 argv（不经 sh -c）+ preflight 守卫 +
        // 执行后复探；DCP 未启用时 executeLegacy 返回 DCP_DISABLED，走旧链。
        if (deviceOps != null) {
            val params = buildMap {
                put("package", pkg)
                if (keepData) put("keep_data", "-k")
            }
            val result = deviceOps.executeLegacy("package.uninstall", params)
            if (result.error != "DCP_DISABLED") {
                return result.legacyText()
            }
        }

        val cmd = if (keepData) "pm uninstall -k $pkg" else "pm uninstall $pkg"
        return shellExecutor(cmd)
    }
}

/**
 * 强制停止应用
 *
 * S05：DCP 启用时内部委托 [DeviceOpsPort.executeLegacy]（package.force_stop）
 * —— argv 直调 + 语义分级（MEDIUM 可逆）；返回文本形态向后兼容。
 */
class AppForceStopTool(
    private val shellExecutor: suspend (String) -> String,
    private val deviceOps: DeviceOpsPort? = null
) : AgentTool {

    override val id = "AppForceStop"
    override val name = "Force Stop App"
    override val description = "Force stop a running app by package name."

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "package": {"type": "string", "description": "Package name to force stop"}
            },
            "required": ["package"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val pkg = json["package"]?.jsonPrimitive?.content ?: return "Error: 'package' required"
        if (!ShellQuote.isValidPackageName(pkg)) {
            return "Error: invalid package name '$pkg' (must match ^[A-Za-z0-9._]+\$)"
        }

        if (deviceOps != null) {
            val result = deviceOps.executeLegacy("package.force_stop", mapOf("package" to pkg))
            if (result.error != "DCP_DISABLED") {
                return result.legacyText()
            }
        }

        return shellExecutor("am force-stop $pkg")
    }
}

/**
 * S05 旧工具委托的返回文本兼容层：保持旧版 Success / Error 前缀形态，
 * 旧提示词与既有消费者零改动。
 */
internal fun DeviceOpResult.legacyText(): String = when {
    ok -> rawOutput?.takeIf { it.isNotBlank() } ?: "Success"
    error != null -> buildString {
        append("Error: ").append(error)
        hint?.let { append(" — ").append(it) }
    }
    else -> "Error: 命令执行失败"
}

/**
 * 应用详情工具
 *
 * Get detailed info about an installed app: version, permissions, storage, activities.
 */
class AppInfoTool(
    private val shellExecutor: suspend (String) -> String
) : AgentTool {

    override val id = "AppInfo"
    override val name = "App Info"
    override val description = """
        Get detailed info about an installed app: version, permissions, storage, activities.

        Examples:
        - {"package": "com.android.chrome"}
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "package": {"type": "string", "description": "Package name"},
                "section": {"type": "string", "enum": ["all", "version", "permissions", "storage", "activities"], "description": "Info section (default: all)"}
            },
            "required": ["package"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val pkg = json["package"]?.jsonPrimitive?.content ?: return "Error: 'package' required"
        if (!ShellQuote.isValidPackageName(pkg)) {
            return "Error: invalid package name '$pkg' (must match ^[A-Za-z0-9._]+\$)"
        }
        val section = json["section"]?.jsonPrimitive?.content ?: "all"

        return when (section) {
            "version" -> shellExecutor("dumpsys package $pkg | grep -E 'versionName|versionCode|firstInstallTime|lastUpdateTime'")
            "permissions" -> shellExecutor("dumpsys package $pkg | grep -A 100 'requested permissions:' | head -30")
            "storage" -> shellExecutor("du -sh /data/data/$pkg 2>/dev/null || echo 'No access to app data'")
            "activities" -> shellExecutor("dumpsys package $pkg | grep -A 50 'Activity Resolver Table' | head -30")
            else -> buildString {
                appendLine("=== App: $pkg ===")
                appendLine(shellExecutor("dumpsys package $pkg | grep -E 'versionName|versionCode|firstInstallTime|lastUpdateTime|dataDir'"))
                appendLine("\n=== Permissions (first 15) ===")
                appendLine(shellExecutor("dumpsys package $pkg | grep -A 20 'requested permissions:' | head -15"))
            }
        }
    }
}
