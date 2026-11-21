package com.apex.agent.core.tools.fusion.rea

import java.io.File

/**
 * # REA 运行时端口
 *
 * REA 引擎（纯 Kotlin，core:tool-registry）对宿主环境的全部依赖收敛为两个
 * 小接口 —— app 层 DI 适配（core 不反向依赖平台模块，镜像 SkillHotReloadLogSink
 * 的注入哲学）：
 *
 * - [ReaCommandRunner]：DEVICE_SHELL / SANDBOX_BRIDGE 两层的命令执行面。
 *   app 层桥接到既有终端执行链（PRoot 优先 → 宿主 shell 回落）；
 * - [ReaPageFetcher]：WEB_FETCH 层的页面/脚本抓取面（app 层桥接 OkHttp）。
 *
 * 未注入（null / 缺省实现）时对应分层工具结构化降级 —— 引擎本身永不因
 * 端口缺失而崩溃。
 */

/** 命令执行结果（stdout + stderr + 退出码）。 */
data class ReaCommandOutput(
    val command: List<String>,
    val stdout: String,
    val stderr: String = "",
    val exitCode: Int,
    val durationMs: Long = 0,
    /** 实际执行通道（"sandbox" = PRoot Ubuntu；"host" = 宿主 shell；"unavailable"）。 */
    val channel: String = "unavailable"
) {
    fun isOk(): Boolean = exitCode == 0
}

/**
 * 命令执行端口。
 *
 * 语义：接收**参数化命令**（argv 形态，防注入），在最佳可用通道执行：
 * PRoot Ubuntu rootfs 就绪 → 沙箱内执行（objdump/rizin/jadx 等 apt 装
 * 的引擎都在沙箱 PATH）；否则宿主 shell 回落（设备本地 am/pm/dumpsys
 * 这类 DEVICE_SHELL 工具天然在宿主可用）。
 */
fun interface ReaCommandRunner {
    suspend fun run(command: List<String>, timeoutMs: Long): Result<ReaCommandOutput>
}

/** 抓取结果。 */
data class ReaPageContent(
    val url: String,
    val status: Int,
    val contentType: String,
    val body: String,
    val bodySha256: String
)

/**
 * 页面抓取端口（WEB_FETCH 层）：静态 GET + 返回原文（脚本提取与 import
 * 分析在引擎内做 —— 端口只负责传输）。
 */
fun interface ReaPageFetcher {
    suspend fun fetch(url: String, timeoutMs: Long): Result<ReaPageContent>
}

/** 引擎工作目录（证据包导出 / 提取落盘的根）。 */
data class ReaWorkspace(val root: File) {
    fun ensure(): File {
        root.mkdirs()
        return root
    }
}
