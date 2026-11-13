package com.apex.agent.platform.TerminalExec

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 本地 shell 通道（pipe 模式）—— Termux Run Command / OperIt bridge 的执行形态：
 * `ProcessBuilder(shell, "-c", command)`，**stdout/stderr 分离采集**、真实退出码。
 *
 * 与交互式 PTY 会话（tty 合并两流）刻意分离：一次性命令走 pipe 才能给出结构化的
 * stdout/stderr。
 *
 * 纯 JVM（java.lang.ProcessBuilder）—— 无 android.* import；Android 上由 app 层
 * 传入 `/system/bin/sh`，JVM 测试默认 `/bin/sh`。
 *
 * stdin 由 [ExecEngine] 统一收尾（写载荷→close / 无载荷立即 close）：无输入命令
 * （如 `cat`）收到 EOF 自行退出，而不是等到超时；带载荷时载荷完整写入后才 EOF。
 */
class ProcessBuilderSpawner(
    override val channel: String = "local-sh",
    private val shell: String = "/bin/sh"
) : CommandSpawner {

    override val supportsEnv: Boolean = true

    override fun spawn(request: SpawnRequest): SpawnedCommand {
        val pb = ProcessBuilder(shell, "-c", request.command)
        request.cwd?.let { pb.directory(File(it)) }
        request.env.forEach { (k, v) -> pb.environment()[k] = v }
        val proc = pb.start()
        // stdin 保持打开，由 ExecEngine 统一收尾（写载荷→close / 无载荷直接 close
        // = 原有「立即 EOF」语义不变；spawner 不再自行关闭，否则 ExecRequest.stdin
        // 永远写不进去 —— 2026-11 DSH Native Plugin Host 修正）
        return ProcessSpawnedCommand(proc)
    }

    internal class ProcessSpawnedCommand(private val proc: Process) : SpawnedCommand {
        override val stdout: java.io.InputStream get() = proc.inputStream
        override val stderr: java.io.InputStream get() = proc.errorStream
        override val stdin: java.io.OutputStream get() = proc.outputStream
        override fun waitFor(timeoutMs: Long): Boolean =
            proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        override fun exitValue(): Int = proc.exitValue()
        override fun destroy() {
            proc.destroyForcibly()
        }
    }
}
