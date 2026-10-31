package com.apex.agent.platform.terminal.searxng

import com.apex.agent.platform.terminal.proot.LinuxExecutionContextFactory
import com.apex.agent.platform.terminal.proot.PRootCommand
import com.apex.agent.platform.terminal.proot.PRootCommandBuilderImpl
import com.apex.agent.platform.terminal.proot.ProotExecutor
import com.apex.agent.platform.terminal.proot.PRootLaunchRequest
import com.apex.agent.platform.terminal.proot.SystemBindProfile
import com.apex.agent.platform.terminal.workspace.AbsolutePath
import com.apex.agent.platform.terminal.workspace.WorkspacePath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [SearxngCommandPort] 的生产适配 —— 与 UbuntuAptPackageManager 同一条
 * ProotExecutor 非交互执行路径（PR #75 计划 §3.3 的定位：rootfs 探针/apt/
 * 批处理命令专用）。
 *
 * argv 组装纪律（与 apt 的 buildProotCommand 单源同款）：
 *  - LinuxExecutionContextFactory.resolve() 为唯一上下文构造点（同一 rootfs/
 *    workspace/home/proot 二进制/能力集）；
 *  - 系统级 bind 对齐 SystemBindProfile.STANDARD（/proc /dev /sys —— pip 编译
 *    组件、/dev/urandom 熵源依赖）；
 *  - 取消感知：调用方协程的活跃状态注入 isCancelled（分片轮询，取消即强杀 —
 *    disable 安装中的长 apt/pip 不占满超时）；
 *  - guest env 追加经 env trampoline（apt 基线 + 调用方显式 env）。
 */
class SearxngProotCommandPort(
    private val contextFactory: LinuxExecutionContextFactory,
    private val executor: ProotExecutor
) : SearxngCommandPort {

    override suspend fun exec(
        command: String,
        timeoutMs: Long,
        env: Map<String, String>
    ): Result<SearxngExecResult> = withContext(Dispatchers.IO) {
        val context = contextFactory.resolve().getOrElse { e ->
            return@withContext Result.failure(RuntimeException("ubuntu not ready — ${e.message}"))
        }
        val guestEnv = context.aptGuestEnv + env
        // 单命令 shell 形态（/bin/bash -c）：安装命令是复合命令（&& 链），
        // 与 ProotCommandSpawner 的 bash 方言纪律一致（dash 无 pipefail/进程替换）。
        if (command.isBlank()) {
            return@withContext Result.failure(RuntimeException("empty command"))
        }
        val launch = PRootLaunchRequest(
            rootfs = context.rootfs,
            executable = "/bin/bash",
            arguments = listOf("-c", command),
            workingDirectory = WorkspacePath(GUEST_WORKSPACE_CWD),
            environment = guestEnv,
            binds = buildList {
                add(context.homeBind)
                addAll(SystemBindProfile.STANDARD.toBinds())
                add(context.workspaceBind)
            },
            terminalMode = com.apex.agent.platform.terminal.api.TerminalMode.AUTO,
            fakeRoot = true,
            killOnExit = true
        )
        val cmd: PRootCommand = PRootCommandBuilderImpl().build(
            launch,
            AbsolutePath(context.prootBinary.absolutePath),
            AbsolutePath(context.rootfsDir.absolutePath),
            AbsolutePath(context.workspaceDir.absolutePath),
            capabilities = context.capabilities
        )
        val callerContext = currentCoroutineContext()
        val exec = executor.executeBounded(
            cmd, timeoutMs = timeoutMs, maxOutputBytes = OUTPUT_BUDGET_BYTES,
            isCancelled = { !callerContext.isActive }
        )
        Result.success(
            SearxngExecResult(
                exitCode = exec.exitCode,
                stdout = exec.stdout,
                stderr = exec.stderr,
                timedOut = exec.timedOut
            )
        )
    }

    override suspend fun rootfsDir(): File? =
        contextFactory.resolve().getOrNull()?.rootfsDir

    companion object {
        /** 安装日志输出预算（apt/pip 输出可达数 MB —— 有界采集防 OOM，T76 §35 同款）。 */
        private const val OUTPUT_BUDGET_BYTES: Long = 1 shl 20

        /** guest 工作目录（/workspace —— 与 apt 执行路径一致）。 */
        private const val GUEST_WORKSPACE_CWD = "/workspace"
    }
}
