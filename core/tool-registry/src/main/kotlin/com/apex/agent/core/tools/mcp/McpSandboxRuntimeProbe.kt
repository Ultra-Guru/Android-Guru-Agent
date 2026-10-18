package com.apex.agent.core.tools.mcp

/**
 * ═══ 沙箱运行时探针（MCP 市场诚实化）═══
 *
 * `runInSandbox=true` 的 STDIO 服务器连接前的**真实环境预检**：
 * rootfs 装了吗？沙箱里有 node/npx/uvx/python 吗？
 *
 * ## 为什么需要它
 * 既有 [McpManager] 的 `sandboxReadinessProbe` 只探 rootfs `current` 标记
 * —— rootfs 装好但**没装 nodejs** 是常态（Ubuntu rootfs 默认不带），于是
 * `npx -y ...` 经 proot 启动后 execve 立即失败，进程秒退，用户看到的是
 * 抽象的「MCP 进程在响应前退出」（真因在 stderr 里，得上日志翻）。
 * 本探针把这一步提前到 **fork 之前**：缺什么、怎么补，一次说清。
 *
 * ## 契约（实现方 = app 层 marketplace 包的 SandboxRuntimeProbe）
 * - 探测必须是**纯文件系统存在性检查**（rootfs 是 host 侧普通目录），
 *   不得 spawn 进程（那会把预检变成一次失败的启动，违背初衷）；
 * - 未知命令形态（java/docker/自定义二进制）返回 [Verdict.READY] ——
 *   不确定时不拦截，留给真实启动路径报真因（诚实但不越权）；
 * - verdict 仅用于**快速失败与提示**，READY 不代表启动必然成功（网络、
 *   包本身的问题仍由启动管线如实报告）。
 *
 * 纯 JVM 接口（core:tool-registry 保持零 Android 依赖），单测注入 lambda。
 */
fun interface McpSandboxRuntimeProbe {

    /**
     * 探测沙箱内能否解析执行 [command]（如 "npx" / "uvx" / "python"）。
     *
     * @param command 配置里的可执行名（裸名，不是绝对路径；PATH 解析语义）
     */
    fun probe(command: String): Verdict

    /** 探测裁决三态。 */
    enum class Verdict {
        /** 沙箱与运行时均在，可以进入真实启动管线。 */
        READY,
        /** Ubuntu rootfs 未安装（或已损坏）—— 需先到终端页装 Ubuntu。 */
        ROOTFS_MISSING,
        /** rootfs 就绪但命令对应的运行时缺失（如没装 nodejs/npm/uv）。 */
        RUNTIME_MISSING
    }
}
