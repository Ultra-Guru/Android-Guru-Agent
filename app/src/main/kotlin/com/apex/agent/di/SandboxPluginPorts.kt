package com.apex.agent.di

import android.content.Context
import com.apex.agent.core.tools.extension.NodeInstallPort
import com.apex.agent.core.tools.pluginhost.claude.ClaudeHookPort
import com.apex.agent.core.tools.pluginhost.worker.WorkerProcessPort
import com.apex.agent.platform.terminal.proot.PRootArgvCapabilities
import com.apex.agent.platform.terminal.proot.PRootHostEnvironment
import com.apex.agent.tools.ProotCommandSpawner
import com.apex.agent.tools.PrivilegedCommandSpawner
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * # SandboxWorkerProcessPort —— Plugin Worker 的 Android 沙箱进程端口（Phase 6）
 *
 * 经 [ProotCommandSpawner.spawnLongLived] 在 Ubuntu rootfs 内启动长驻
 * node 进程（stdin 保持打开 = 换行 JSON IPC 通道）。
 *
 * ⚠️ 待设备验证（spec §16.5）：CI 验证编译 + JVM E2E（LocalProcessWorkerPort）；
 * 沙箱长驻进程的实机行为（rootfs 就绪度 / bind 映射 / ARM64 node 兼容）
 * 需真机回归 —— 文档矩阵如实标注，不冒充已验证。
 */
class SandboxWorkerProcessPort(
    context: Context
) : WorkerProcessPort {

    private val spawner: ProotCommandSpawner = buildSpawner(context)

    override fun spawn(spec: WorkerProcessPort.SpawnSpec): Result<Process> =
        spawner.spawnLongLived(
            argv = spec.argv,
            cwd = File(spec.workingDir),
            env = spec.env
        )
}

/**
 * # SandboxClaudeHookPort —— Claude 钩子的沙箱执行端口（Phase 6）
 *
 * 官方钩子协议：shell 命令 + stdin JSON 入 / stdout·exit code 出。实现在
 * 沙箱内以 `bash -c` 长驻形态起进程（复用 spawnLongLived 的全部 bind/env
 * 约定），喂 stdin、收 stdout/stderr、按官方超时语义强杀 —— 与
 * ClaudeHookPort.LocalShellHookPort 的 JVM 实现同一协议形状。
 *
 * CLAUDE_PLUGIN_ROOT 注入 guest 视角路径（插件目录同路径 bind）。
 */
class SandboxClaudeHookPort(
    context: Context
) : ClaudeHookPort {

    private val spawner: ProotCommandSpawner = buildSpawner(context)

    override suspend fun exec(
        command: String,
        cwd: File,
        timeoutMs: Long,
        env: Map<String, String>,
        stdinJson: String
    ): Result<NodeInstallPort.ExecOutcome> = withContext(Dispatchers.IO) {
        val guestRoot = cwd.canonicalFile.path
        val fullEnv = env + mapOf("CLAUDE_PLUGIN_ROOT" to guestRoot)
        spawner.spawnLongLived(
            argv = listOf("/bin/bash", "-c", command.replace("\${CLAUDE_PLUGIN_ROOT}", guestRoot)),
            cwd = cwd,
            env = fullEnv
        ).mapC { process ->
            try {
                process.outputStream.use { it.write(stdinJson.toByteArray(Charsets.UTF_8)) }
                val stdoutF = java.util.concurrent.CompletableFuture.supplyAsync {
                    process.inputStream.bufferedReader().readText()
                }
                val stderrF = java.util.concurrent.CompletableFuture.supplyAsync {
                    process.errorStream.bufferedReader().readText()
                }
                val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    NodeInstallPort.ExecOutcome(-1, "", "hook timeout after ${timeoutMs}ms")
                } else {
                    NodeInstallPort.ExecOutcome(
                        exitCode = process.exitValue(),
                        stdout = stdoutF.get(5, TimeUnit.SECONDS).orEmpty(),
                        stderr = stderrF.get(5, TimeUnit.SECONDS).orEmpty()
                    )
                }
            } catch (e: Exception) {
                runCatching { process.destroyForcibly() }
                NodeInstallPort.ExecOutcome(-1, "", e.message ?: e::class.simpleName ?: "exec failed")
            }
        }
    }

    private inline fun <T, R> Result<T>.mapC(transform: (T) -> R): Result<R> =
        fold({ Result.success(transform(it)) }, { Result.failure(it) })
}

/** ProotCommandSpawner 的 Plugin Host 接线（与 ExtensionModule 同款参数）。 */
internal fun buildSpawner(context: Context): ProotCommandSpawner = ProotCommandSpawner(
    hostEnvironment = PRootHostEnvironment(
        nativeLibraryDir = context.applicationInfo.nativeLibraryDir,
        baseDir = context.filesDir,
        cacheDir = context.cacheDir
    ),
    rootfsDir = File(context.filesDir, "linux/rootfs"),
    isRootfsReady = { File(context.filesDir, "linux/rootfs/current").exists() },
    defaultWorkspaceDir = File(context.filesDir, "linux/workspaces/default"),
    persistentHomeDir = File(context.filesDir, "linux/home"),
    fallback = PrivilegedCommandSpawner(),
    capabilities = { PRootArgvCapabilities.TERMUX_BUNDLED }
)
