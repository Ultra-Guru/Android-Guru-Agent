package com.apex.agent.marketplace

import com.apex.agent.core.tools.mcp.McpSandboxRuntimeProbe
import com.apex.agent.core.tools.mcp.McpSandboxRuntimeProbe.Verdict
import java.io.File

/**
 * ═══ SandboxRuntimeProbe — 沙箱运行时真实探测（MCP 市场诚实化）═══
 *
 * [McpSandboxRuntimeProbe] 的 app 层实现：对 `runInSandbox=true` 的 STDIO
 * 命令做 **纯文件系统存在性检查**（rootfs 是 host 侧普通目录，无需 spawn
 * 进程就能判定命令能否解析），在连接 fork 之前拦下「rootfs 没装 / node
 * 没装」两类必败场景。
 *
 * ## rootfs 解析（与 ProotSandboxCommandRunner / ProotMcpProcessLauncher 同款）
 * `<rootfsBaseDir>/current` 标记 → `versions/<id>`（RootfsInstallLayout
 * 原子激活布局）；标记缺失但目录内有 `bin/` 时兼容直接传入本体；均不
 * 满足 → [Verdict.ROOTFS_MISSING]。**只探可用性，不判损坏**（标记指向
 * 目录缺失等损坏形态交给真实启动路径的 launcher 报详细文案 —— 预检
 * 不越权）。
 *
 * ## 命令解析域（与 LinuxEnvironmentManager.GUEST_PATH 对齐）
 * guest PATH 固定为 `/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin`
 * —— 探测就是在这六个目录里找同名可执行文件，外加持久化 home 的
 * `.local/bin`（pip --user 形态兜底）。按命令家族给安装指引：
 * - node 家族（npx/npm/node）：`apt install -y nodejs npm`
 * - uv 家族（uvx/uv）：`pip install uv`
 * - python 家族（python/python3）：`apt install -y python3`
 * - **未知命令**：[Verdict.READY]（不越权拦截 —— java/docker/自定义二进制
 *   留给真实启动路径报真因）。
 *
 * 无状态、线程安全（每次调用独立 File 存在性检查）；DI 单例注入
 * McpManager.sandboxRuntimeProbe。
 */
class SandboxRuntimeProbe(
    /** rootfs 安装基目录（TerminalModule 的 rootfsBaseDir：`<filesDir>/rootfs/ubuntu`）。 */
    private val rootfsBaseDir: File,
    /**
     * 持久化 home 宿主目录（`<filesDir>/linux/home`，bind 到 guest /root）。
     * null / 不存在 = 跳过 `.local/bin` 兜底路径。
     */
    private val persistentHomeDir: File?
) : McpSandboxRuntimeProbe {

    override fun probe(command: String): Verdict {
        val resolved = resolveActiveRootfs() ?: return Verdict.ROOTFS_MISSING
        val name = command.trim().substringAfterLast('/')
        return when {
            name.isEmpty() -> Verdict.READY
            name in NODE_FAMILY -> probeNodeFamily(resolved, name)
            name in UV_FAMILY -> probeUvFamily(resolved)
            name in PYTHON_FAMILY -> probePythonFamily(resolved)
            else -> Verdict.READY
        }
    }

    /** 缺什么、怎么补 —— 供 UI 的环境引导文案复用（与 core 门禁文案同口径）。 */
    fun installHint(command: String): String? {
        val name = command.trim().substringAfterLast('/')
        return when {
            name.isEmpty() -> null
            name in NODE_FAMILY -> "apt install -y nodejs npm"
            name in UV_FAMILY -> "pip install uv"
            name in PYTHON_FAMILY -> "apt install -y python3"
            else -> null
        }
    }

    // ── rootfs 解析（ProotSandboxCommandRunner.resolveActiveRootfs 的只读镜像）──

    /** 激活 rootfs 目录；未安装返回 null（损坏形态交给真实启动路径）。 */
    internal fun resolveActiveRootfs(): File? {
        val marker = File(rootfsBaseDir, CURRENT_MARKER)
        if (marker.isFile) {
            val artifactId = runCatching { marker.readText().trim() }.getOrDefault("")
            if (artifactId.isNotEmpty()) {
                val versionDir = File(rootfsBaseDir, "$VERSIONS_DIR/$artifactId")
                if (versionDir.isDirectory) return versionDir
                return null // 标记指向目录缺失：按未就绪处理，详细文案留给 launcher
            }
        }
        return rootfsBaseDir.takeIf { File(it, "bin").isDirectory }
    }

    // ── 家族探测 ──

    /** node 家族：npx/npm/node 通常是同一发行包（apt 的 nodejs/npm）。 */
    private fun probeNodeFamily(rootfs: File, name: String): Verdict {
        if (findInGuestPath(rootfs, name) != null) return Verdict.READY
        // npx 找不到时 node 在也算「装了一半」—— 仍按缺失报（npx 才是要跑的命令）
        return Verdict.RUNTIME_MISSING
    }

    /** uv 家族：uvx/uv 同发行包（pip install uv 两者都装）。 */
    private fun probeUvFamily(rootfs: File): Verdict =
        if (findInGuestPath(rootfs, "uvx") != null || findInGuestPath(rootfs, "uv") != null) {
            Verdict.READY
        } else {
            Verdict.RUNTIME_MISSING
        }

    /** python 家族：Ubuntu rootfs 自带 python3，缺失说明是极简裁剪版。 */
    private fun probePythonFamily(rootfs: File): Verdict =
        if (findInGuestPath(rootfs, "python3") != null || findInGuestPath(rootfs, "python") != null) {
            Verdict.READY
        } else {
            Verdict.RUNTIME_MISSING
        }

    /** 在 guest PATH 目录 + 持久化 home 的 .local/bin 里找可执行文件。 */
    private fun findInGuestPath(rootfs: File, binary: String): File? {
        for (dir in GUEST_BIN_DIRS) {
            val candidate = File(rootfs, "$dir/$binary")
            if (candidate.isFile) return candidate
        }
        val homeLocalBin = persistentHomeDir?.let { File(it, ".local/bin/$binary") }
        return homeLocalBin?.takeIf { it.isFile }
    }

    private companion object {
        /** guest PATH 的目录部分（LinuxEnvironmentManager.GUEST_PATH 的镜像）。 */
        val GUEST_BIN_DIRS = listOf(
            "usr/local/sbin", "usr/local/bin", "usr/sbin", "usr/bin", "sbin", "bin"
        )

        /** rootfs 安装布局常量（RootfsInstallLayout 语义）。 */
        const val CURRENT_MARKER = "current"
        const val VERSIONS_DIR = "versions"

        val NODE_FAMILY = setOf("npx", "npm", "node", "bunx")
        val UV_FAMILY = setOf("uvx", "uv")
        val PYTHON_FAMILY = setOf("python", "python3")
    }
}
