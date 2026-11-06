package com.apex.agent.core.tools.extension

import java.io.File

/**
 * # NodeInstallPort —— Node 系安装器基座的执行端口（core 无 Android 依赖）
 *
 * OpenCode / DSH 适配器的安装动作（npm install / git clone）在 **ubuntu 域**
 * 执行（PRoot 沙箱有真 node/npm/git，宿主没有）。core 侧只定义端口，app 层
 * 经 `ExecEngine(ProotCommandSpawner(...))` 实现（与 FusionModule 的
 * ReaCommandRunner 同款接线）；单测手写 fake（不真跑进程）。
 *
 * 安全契约：调用方（NodeFamilyInstaller）只传**白名单构造**的 argv ——
 * npm 包名过 isNpmName、仓库只认 owner/repo 解析结果，用户输入永不进 shell 串。
 */
interface NodeInstallPort {

    /**
     * 在 [cwd]（host 侧绝对路径目录，必须已存在）执行 [command]。
     * 实现方经 PRoot 通道执行（cwd 同路径 bind 语义，见 ProotCommandSpawner
     * 的「其它真实存在 host 目录」分支），永不继承 app 进程环境变量。
     */
    suspend fun exec(command: List<String>, cwd: File, timeoutMs: Long): Result<ExecOutcome>

    /** 探针：node/npm 是否在沙箱内可解析（doctor/inspect 依赖报告用）。 */
    suspend fun probeRuntime(): RuntimeProbe

    data class ExecOutcome(
        val exitCode: Int,
        val stdout: String,
        val stderr: String
    ) {
        val success: Boolean get() = exitCode == 0
    }

    data class RuntimeProbe(
        val nodeVersion: String?,
        val npmVersion: String?
    ) {
        val ready: Boolean get() = nodeVersion != null && npmVersion != null
    }
}
