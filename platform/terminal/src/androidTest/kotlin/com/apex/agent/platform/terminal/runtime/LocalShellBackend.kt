package com.apex.agent.platform.terminal.runtime

/**
 * 测试夹具 —— 朴素 shell 后端（原生产 LocalShellBackend 随 Ubuntu-only 收敛
 * 移入测试源集；行为保持逐字节一致，作为契约/IPC/端到端测试的默认后端）。
 *
 * - argv：["/system/bin/sh", "-i"]（P70 前硬编码 spawn 的 golden 形态）
 * - env：显式传全量（与 pty_session.cpp 的安全默认值同组键值），行为恒定
 * - runtimeType：LINUX（ANDROID_LOCAL 枚举值已移除；测试只关心"一个可用后端"）
 */
class LocalShellBackend : ExecutionBackend {

    override val id: String = ID
    override val runtimeType: BackendRuntimeType = BackendRuntimeType.LINUX

    override suspend fun availability(): BackendAvailability = BackendAvailability.Ready

    override suspend fun prepare(request: SessionSpawnRequest): Result<SpawnSpec> {
        // 显式 request.env 叠加在默认值之上（调用方意图优先）。
        val env = defaultEnv() + request.env
        return Result.success(
            SpawnSpec(
                argv = listOf(DEFAULT_SHELL, "-i"),
                env = env,
                cwd = request.cwd.ifBlank { DEFAULT_CWD },
                cwdIsGuestPath = false,
                shellDisplay = null,           // argv[0] 即 shell 路径
                cwdDisplay = null,             // host cwd 即语义 cwd
                metadata = BackendSessionMetadata(backendId = id, guestCwd = null)
            )
        )
    }

    private fun defaultEnv(): Map<String, String> = mapOf(
        "TERM" to "xterm-256color",
        "HOME" to "/data/local/tmp",
        "USER" to "shell",
        "SHELL" to DEFAULT_SHELL,
        "LANG" to "en_US.UTF-8",
        "LC_ALL" to "en_US.UTF-8",
        "PATH" to "/system/bin:/system/xbin:/vendor/bin:/data/local/tmp/bin:/product/bin"
    )

    companion object {
        const val ID = "local"
        const val DEFAULT_SHELL = "/system/bin/sh"
        const val DEFAULT_CWD = "/data/local/tmp"
    }
}
