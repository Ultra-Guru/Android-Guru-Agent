package com.apex.agent.core.tools.mcp

import java.io.File

/**
 * #205 MCP 配置预检（pre-flight validation）。
 *
 * **为什么需要**：连接失败的真实报错发生在进程/网络层（「命令不存在」「401」
 * 「等待响应超时 180000ms」），用户看到的是结果而不是原因。预检在**配置
 * 阶段**就把可静态判定的问题指出来：添加弹窗 / 目录安装 / 导入前调用，
 * 缺什么、什么形态跑不了，当场说清。
 *
 * 全部检查都是**本地静态判定**（File/PATH 存在性、字符串形态），无网络、
 * 无进程拉起 —— 与「真实启动」不冲突：预检通过后连接阶段的一切事件
 * 依旧来自真实代码路径。
 */
object McpConfigValidator {

    // ═══ 结果模型 ═══

    enum class Severity { ERROR, WARN }

    /** 稳定错误码（测试 / 国际化按码取词）。 */
    enum class Code {
        /** 服务器名为空。 */
        NAME_BLANK,

        /** 与既有配置重名。 */
        NAME_DUPLICATE,

        /** BUILTIN 传输但宿主未注册该内置服务器。 */
        BUILTIN_UNREGISTERED,

        /** STDIO 传输缺 command。 */
        STDIO_NO_COMMAND,

        /** command 存在但 args 为空（如 npx 裸跑 —— npx 无参数会挂起）。 */
        EMPTY_ARGS,

        /** 绝对路径命令的文件不存在。 */
        COMMAND_NOT_FOUND,

        /** 相对命令在宿主 PATH 找不到（Android 宿主无 node —— 提示开沙箱）。 */
        HOST_RUNTIME_UNAVAILABLE,

        /** runInSandbox=true 但沙箱 rootfs 未就绪。 */
        SANDBOX_NOT_READY,

        /** runInSandbox=true 但传输不是 STDIO（远端/内置没有沙箱语义）。 */
        SANDBOX_NON_STDIO,

        /** 远端传输缺 URL。 */
        REMOTE_NO_URL,

        /** URL 协议不是 http/https。 */
        REMOTE_BAD_URL,

        /** 环境变量键含 '='、值含换行 —— 进程层注入会撕裂 argv/env。 */
        ENV_INJECTION_CHARS
    }

    data class Finding(
        val code: Code,
        val severity: Severity,
        val message: String
    ) {
        val isError: Boolean get() = severity == Severity.ERROR
    }

    // ═══ 入口 ═══

    /**
     * 预检一份配置。
     *
     * @param existingNames 既有配置名集合（重名判定）
     * @param builtinNames 宿主注册的内置服务器名（BUILTIN 判定）
     * @param sandboxReady 沙箱就绪态；null = 宿主不支持沙箱（不检查该项）
     * @param hostLookup 命令存在性探针（默认 [hostCommandExists]；测试注入假件）
     */
    fun validate(
        config: McpServerConfig,
        existingNames: Set<String> = emptySet(),
        builtinNames: Set<String> = emptySet(),
        sandboxReady: Boolean? = null,
        hostLookup: (String) -> Boolean = ::hostCommandExists
    ): List<Finding> {
        val findings = mutableListOf<Finding>()

        if (config.name.isBlank()) {
            findings += Finding(Code.NAME_BLANK, Severity.ERROR, "服务器名为空")
        } else if (config.name in existingNames) {
            findings += Finding(Code.NAME_DUPLICATE, Severity.ERROR, "服务器名 '${config.name}' 已存在")
        }

        when (config.transport) {
            McpTransport.BUILTIN -> {
                if (config.name !in builtinNames) {
                    findings += Finding(
                        Code.BUILTIN_UNREGISTERED, Severity.ERROR,
                        "内置服务器 '${config.name}' 未注册（仅 App 预置的内置服务器可连接）"
                    )
                }
            }
            McpTransport.STDIO -> {
                val command = config.command?.trim().orEmpty()
                if (command.isEmpty()) {
                    findings += Finding(Code.STDIO_NO_COMMAND, Severity.ERROR, "STDIO 传输需要填写命令")
                } else {
                    if (config.args.isEmpty()) {
                        findings += Finding(
                            Code.EMPTY_ARGS, Severity.WARN,
                            "args 为空：'$command' 裸跑通常会挂起或进交互模式（如 npx 需 -y 与包名）"
                        )
                    }
                    if (command.startsWith("/")) {
                        if (!hostLookup(command)) {
                            findings += Finding(Code.COMMAND_NOT_FOUND, Severity.ERROR, "命令不存在：$command")
                        }
                    } else if (!config.runInSandbox && sandboxReady != false) {
                        // 沙箱关闭（或宿主不支持沙箱）时才检查宿主 PATH：
                        // 沙箱模式下命令在 rootfs 内解析，宿主 PATH 不相关。
                        if (!hostLookup(command)) {
                            findings += Finding(
                                Code.HOST_RUNTIME_UNAVAILABLE, Severity.WARN,
                                "宿主 PATH 找不到 '$command'（Android 宿主无 node/python；建议开启沙箱或改用远端）"
                            )
                        }
                    }
                }
            }
            McpTransport.HTTP, McpTransport.SSE -> {
                val url = config.url.trim()
                if (url.isEmpty()) {
                    findings += Finding(Code.REMOTE_NO_URL, Severity.ERROR, "${config.transport} 传输需要填写 URL")
                } else {
                    val scheme = url.substringBefore("://", missingDelimiterValue = "")
                    if (scheme != "http" && scheme != "https") {
                        findings += Finding(Code.REMOTE_BAD_URL, Severity.ERROR, "URL 协议应为 http/https：$url")
                    }
                }
            }
        }

        if (config.runInSandbox) {
            if (config.transport != McpTransport.STDIO) {
                findings += Finding(Code.SANDBOX_NON_STDIO, Severity.ERROR, "沙箱仅适用于 STDIO 本地命令")
            } else if (sandboxReady == false) {
                findings += Finding(
                    Code.SANDBOX_NOT_READY, Severity.ERROR,
                    "沙箱 rootfs 未就绪 —— 请先在终端页完成 Ubuntu 环境安装"
                )
            }
        }

        for ((key, value) in config.env) {
            if (key.contains('=') || key.isBlank()) {
                findings += Finding(Code.ENV_INJECTION_CHARS, Severity.ERROR, "环境变量键非法：'$key'")
            }
            if (value.contains('\n') || value.contains('\r')) {
                findings += Finding(Code.ENV_INJECTION_CHARS, Severity.ERROR, "环境变量 '$key' 的值含换行")
            }
        }

        return findings
    }

    /** 只留 ERROR 的便捷视图。 */
    fun errorsOf(findings: List<Finding>): List<Finding> = findings.filter { it.isError }

    // ═══ 宿主命令探针 ═══

    /**
     * 宿主命令存在性：绝对路径查文件；裸命令查 PATH 各目录。
     *
     * 纯 File 判定（无进程拉起）—— JVM 单测里 `java`（宿主 JVM 自带）与
     * `/bin/sh`（绝对路径）零误报；Android 上 PATH 只含 /system/bin 系，
     * `npx` 自然查不到 → 正确提示开沙箱。
     */
    fun hostCommandExists(command: String): Boolean {
        val name = command.trim()
        if (name.isEmpty()) return false
        if (name.startsWith("/")) return File(name).canExecute()
        val path = System.getenv("PATH") ?: return false
        return path.split(File.pathSeparator).any { dir ->
            dir.isBlank() || File(dir, name).canExecute()
        }
    }
}
