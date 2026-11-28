package com.apex.agent.tools

/**
 * ═══ DCP 探针输出解析器（S04）═══
 *
 * 把 preflight / verify 的只读 argv 探针输出解析成 diff 字段与守卫 flag。
 * 解析纪律（S04 任务清单）：
 * - `dumpsys package` 输出格式跨 Android 版本会变 —— **允许字段缺失**：
 *   缺失时字段为 null、`parseIncomplete=true` 进 diff.before，绝不把
 *   「没解析出来」当成「不是系统应用」；
 * - 解析器零 Android 依赖（纯字符串），JVM 单测用脚本化输出穷举。
 */
internal object DiffParsers {

    /** 单条探针的解析产出。 */
    data class Reading(
        val fields: Map<String, String?>,
        val adminPackages: Set<String> = emptySet(),
        val launcherPackage: String? = null,
        val parseIncomplete: Boolean = false
    )

    /** 按探针 argv 形状分发（argv[0..2] 联合判定，不猜不兜底）。 */
    fun parse(argv: List<String>, stdout: String, exitCode: Int): Reading {
        val target = argv.lastOrNull { !it.startsWith("-") && !it.startsWith("%") } ?: ""
        return when {
            argv.take(3) == listOf("pm", "list", "packages") -> pmList(target, stdout)
            argv.take(2) == listOf("dumpsys", "package") -> dumpsysPackage(stdout)
            argv == listOf("cmd", "shortcut", "get-default-launcher") -> launcher(stdout)
            argv == listOf("dumpsys", "device_policy") -> devicePolicy(stdout)
            argv.take(3) == listOf("cmd", "package", "dump") -> cmdPackageDump(stdout)
            argv.firstOrNull() == "stat" -> stat(argv, stdout, exitCode)
            argv.take(2) == listOf("settings", "get") -> simpleValue("value", stdout)
            argv.take(4) == listOf("cmd", "appops", "get") -> appops(stdout)
            argv.firstOrNull() == "getprop" && argv.size == 2 -> simpleValue("value", stdout)
            argv.firstOrNull() == "pidof" -> pidof(stdout, exitCode)
            argv.firstOrNull() == "ls" -> rawListing("listing", stdout, exitCode)
            else -> Reading(emptyMap(), parseIncomplete = true)
        }
    }

    /** `pm list packages [filter]` —— 输出含 package:<目标> 即已安装。 */
    private fun pmList(target: String, stdout: String): Reading {
        if (target.isBlank()) {
            return Reading(mapOf("installed" to null), parseIncomplete = true)
        }
        val installed = stdout.lineSequence()
            .map { it.removePrefix("package:").trim() }
            .any { it == target }
        return Reading(mapOf("installed" to installed.toString()))
    }

    /**
     * `dumpsys package <pkg>` —— 版本 / 安装时间 / uid / 系统应用标记。
     * 字段缺失（跨版本输出差异）如实给 null + parseIncomplete。
     */
    private fun dumpsysPackage(stdout: String): Reading {
        if (stdout.isBlank()) {
            return Reading(
                mapOf(
                    "versionName" to null, "firstInstallTime" to null,
                    "userId" to null, "systemApp" to null, "updatedSystemApp" to null
                ),
                parseIncomplete = true
            )
        }
        val lines = stdout.lines()
        val fields = mutableMapOf<String, String?>()
        var incomplete = false
        for (line in lines) {
            val t = line.trim()
            when {
                t.startsWith("versionName=") ->
                    fields["versionName"] = t.substringAfter('=').takeIf { it.isNotBlank() }
                t.startsWith("versionCode=") ->
                    fields["versionCode"] = t.substringAfter('=').takeIf { it.isNotBlank() }
                t.startsWith("firstInstallTime=") ->
                    fields["firstInstallTime"] = t.substringAfter('=').takeIf { it.isNotBlank() }
                t.startsWith("userId=") ->
                    fields["userId"] = t.substringAfter('=').takeIf { it.isNotBlank() }
                t.startsWith("pkgFlags=[") || t.startsWith("flags=[") -> {
                    // SYSTEM / UPDATED_SYSTEM 出现在 flags 列表（跨版本两种段名）。
                    val isSystem = t.contains("SYSTEM")
                    val isUpdated = t.contains("UPDATED_SYSTEM")
                    if (fields["systemApp"] == null) {
                        fields["systemApp"] = isSystem.toString()
                        fields["updatedSystemApp"] = isUpdated.toString()
                    }
                }
            }
        }
        for (key in listOf("versionName", "firstInstallTime", "userId", "systemApp", "updatedSystemApp")) {
            if (key !in fields) {
                fields[key] = null
                incomplete = true
            }
        }
        return Reading(fields, parseIncomplete = incomplete)
    }

    /** `cmd package dump <pkg>` —— 补充 hidden 等 cmd 口径字段。 */
    private fun cmdPackageDump(stdout: String): Reading {
        if (stdout.isBlank()) return Reading(emptyMap(), parseIncomplete = true)
        val fields = mutableMapOf<String, String?>()
        for (line in stdout.lines()) {
            val t = line.trim()
            when {
                t.startsWith("hidden=") -> fields["hidden"] = t.substringAfter('=').takeIf { it.isNotBlank() }
                t.startsWith("stopped=") -> fields["stopped"] = t.substringAfter('=').takeIf { it.isNotBlank() }
            }
        }
        return Reading(fields)
    }

    /** `cmd shortcut get-default-launcher` → Launcher: ComponentInfo{pkg/cls}。 */
    private fun launcher(stdout: String): Reading {
        val m = LAUNCHER_LINE.find(stdout) ?: return Reading(
            mapOf("defaultLauncher" to null), parseIncomplete = true
        )
        val pkg = m.groupValues[1]
        return Reading(mapOf("defaultLauncher" to pkg), launcherPackage = pkg.takeIf { it.isNotBlank() })
    }

    /** `dumpsys device_policy` → Admins 段里的设备管理器包名集合。 */
    private fun devicePolicy(stdout: String): Reading {
        if (stdout.isBlank()) return Reading(mapOf("deviceAdmins" to null), parseIncomplete = true)
        val admins = mutableSetOf<String>()
        for (line in stdout.lines()) {
            val m = ADMIN_LINE.find(line) ?: continue
            admins += m.groupValues[1]
        }
        return Reading(mapOf("deviceAdmins" to admins.sorted().joinToString(",")), adminPackages = admins)
    }

    /**
     * `stat -c <fmt> <path>` —— exit != 0（文件不存在 / 无权）时 exists=false
     * （file.* 的 verify 语义依赖它：软删后 stat 应失败）。
     */
    private fun stat(argv: List<String>, stdout: String, exitCode: Int): Reading {
        val path = argv.lastOrNull() ?: return Reading(fields = emptyMap(), parseIncomplete = true)
        if (exitCode != 0) {
            val fields = mutableMapOf<String, String?>("exists" to "false")
            if (argv.contains("%n") || argv.any { it.contains("%n") }) fields["name"] = null
            return Reading(fields)
        }
        val fmt = argv.getOrNull(1) ?: ""
        val out = stdout.trim()
        return when {
            fmt == "%s" -> Reading(
                mapOf("exists" to "true", "size" to out.takeIf { it.isNotBlank() })
            )
            fmt == "%n" -> Reading(
                mapOf("exists" to "true", "name" to out.takeIf { it.isNotBlank() })
            )
            out.contains('|') -> {
                val parts = out.split('|')
                Reading(
                    mapOf(
                        "exists" to "true",
                        "name" to parts.getOrNull(0),
                        "size" to parts.getOrNull(1),
                        "mode" to parts.getOrNull(2),
                        "owner" to parts.getOrNull(3),
                        "group" to parts.getOrNull(4),
                        "mtime" to parts.getOrNull(5)
                    )
                )
            }
            else -> Reading(mapOf("exists" to "true", "raw" to out.take(256)), parseIncomplete = true)
        }
    }

    /** settings get / getprop —— 单值输出（"null" 视为未设置 → null）。 */
    private fun simpleValue(field: String, stdout: String): Reading {
        val v = stdout.trim()
        return Reading(
            mapOf(field to v.takeIf { it.isNotBlank() && it != "null" })
        )
    }

    /** `cmd appops get <pkg> <op>` → "Mode: X" 行。 */
    private fun appops(stdout: String): Reading {
        val m = APPOPS_LINE.find(stdout) ?: return Reading(mapOf("mode" to null), parseIncomplete = true)
        return Reading(mapOf("mode" to m.groupValues[1]))
    }

    /** `pidof <pkg>` —— 有输出 = 在跑；无输出 / exit!=0 = 未跑。 */
    private fun pidof(stdout: String, exitCode: Int): Reading {
        val running = stdout.isNotBlank() && exitCode == 0
        return Reading(mapOf("running" to running.toString(), "pid" to stdout.trim().takeIf { running }))
    }

    /** ls 原样（有界，verify 里给模型看回收区内容）。 */
    private fun rawListing(field: String, stdout: String, exitCode: Int): Reading =
        if (exitCode != 0) Reading(mapOf(field to null), parseIncomplete = true)
        else Reading(mapOf(field to stdout.take(2048)))

    private val LAUNCHER_LINE = Regex("""Launcher:\s*ComponentInfo\{([^/}]+)/""")
    private val ADMIN_LINE = Regex("""ComponentInfo\{([^/}]+)/""")
    private val APPOPS_LINE = Regex("""Mode:\s*(\S+)""")
}
