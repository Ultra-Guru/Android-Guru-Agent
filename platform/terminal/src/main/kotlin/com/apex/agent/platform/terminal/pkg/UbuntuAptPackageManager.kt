package com.apex.agent.platform.terminal.pkg

import com.apex.agent.platform.terminal.environment.LinuxEnvironmentManager
import com.apex.agent.platform.terminal.linux.RootfsDescriptor
import com.apex.agent.platform.terminal.linux.RootfsProvider
import com.apex.agent.platform.terminal.proot.PRootBind
import com.apex.agent.platform.terminal.proot.PRootBinaryProvider
import com.apex.agent.platform.terminal.proot.PRootCommand
import com.apex.agent.platform.terminal.proot.PRootCommandBuilder
import com.apex.agent.platform.terminal.proot.PRootCommandBuilderImpl
import com.apex.agent.platform.terminal.proot.PRootHostEnvironment
import com.apex.agent.platform.terminal.proot.PRootLaunchRequest
import com.apex.agent.platform.terminal.proot.ProotExecutor
import com.apex.agent.platform.terminal.proot.SystemBindProfile
import com.apex.agent.platform.terminal.workspace.AbsolutePath
import com.apex.agent.platform.terminal.workspace.GuestUserHome
import com.apex.agent.platform.terminal.workspace.LinuxWorkspaceManager
import com.apex.agent.platform.terminal.workspace.WorkspacePath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

/**
 * T76: UbuntuAptPackageManager —— 生产级 apt/dpkg 包管理器。
 *
 * 把 [LinuxPackageManager] 契约接到真实 Ubuntu rootfs 上的 apt-get/apt-cache/dpkg。
 * **所有 apt 执行经 [ProotExecutor]（PRoot + ProcessBuilder），绝不直接 Runtime.exec**
 * （T76 §3）—— 与交互式 TerminalCreate(backendId="linux-ubuntu") 共享同一 rootfs /
 * workspace / persistent home / package state，不存在两个互不知道状态的 Ubuntu 环境。
 *
 * 架构边界（T76 §3 / §47）：
 * ```
 * LinuxPackageManager.update/install/...
 *         ↓
 * UbuntuAptPackageManager
 *         ↓ ProotExecutor.executeBounded (有界输出)
 *         ↓ PRootCommandBuilder (argv 构造 + 注入防护)
 *         ↓ proot -r <rootfs> -b <workspace>:/workspace -b <home>:/root
 *         ↓   -E DEBIAN_FRONTEND=noninteractive ...
 *         ↓   -- apt-get install -y git
 *         ↓
 * Ubuntu RootFS (apt/dpkg database) ← 与 terminal session 共享
 * ```
 *
 * 并发控制（T76 §5）：
 *  - 写操作（update/install/remove/upgrade/repair）经 [PackageOperationLock] 串行化
 *    （进程内 Mutex + 跨实例 OS 文件锁）。
 *  - 读操作（search/info/isInstalled/installedVersion）无锁并发（apt-cache/dpkg-query
 *    只读，不触碰 dpkg database 写锁）。
 *
 * 取消语义（T76 §36）：
 *  - 协程取消 → [ensureActive] 抛 CancellationException → finally 释放锁 → 状态置
 *    CANCELLED。ProotExecutor 的 destroyForcibly 由其内部 timeout 处理；协程取消时
 *    已启动的 proot 进程会因 --kill-on-exit 在 proot 退出时被回收。
 *
 * 超时语义（T76 §37）：
 *  - 超时 → ProotExecutor destroyForcibly + timedOut=true → 状态置 TIMED_OUT（区别
 *    于 FAILED —— Agent 据此重试而非修复环境）。
 *
 * 有界输出（T76 §35）：apt 输出首-N + 尾-M 截断，默认 1 MB。
 */
class UbuntuAptPackageManager(
    private val executor: ProotExecutor,
    private val binaryProvider: PRootBinaryProvider,
    private val rootfsProvider: RootfsProvider,
    private val userHome: GuestUserHome,
    private val hostEnv: PRootHostEnvironment,
    private val workspaces: LinuxWorkspaceManager,
    private val environment: LinuxEnvironmentManager,
    private val lock: PackageOperationLock,
    private val commandBuilder: PRootCommandBuilder = PRootCommandBuilderImpl(),
    private val coordinator: PackageOperationCoordinator = PackageOperationCoordinator(),
    /** apt 操作默认超时（apt update/install 可能较慢）。 */
    private val defaultTimeoutMs: Long = DEFAULT_APT_TIMEOUT_MS,
    /** 输出上限（首 512KB + 尾 512KB）。 */
    private val maxOutputBytes: Long = ProotExecutor.DEFAULT_MAX_OUTPUT_BYTES,
    /**
     * T81 (D-6 / §34)：统一执行上下文工厂 —— rootfs/workspace/home/proot/env
     * 的单一构造点。null → 按上述构造参数内部自建（兼容旧构造；生产 DI 注入
     * 与 LinuxPRootBackend 共享的实例）。
     */
    private val contextFactory: com.apex.agent.platform.terminal.proot.LinuxExecutionContextFactory? = null,
    /**
     * ★ apt 执行的系统级 bind 集（/proc /dev /sys）—— 与交互会话
     * （LinuxPRootBackend）对齐；默认 [SystemBindProfile.STANDARD]。
     * JVM 测试注入 [SystemBindProfile.NONE] 可回到旧行为（裸 -r）。
     */
    private val systemBinds: SystemBindProfile = SystemBindProfile.STANDARD
) : LinuxPackageManager {

    /** T81 (D-6)：惰性内部工厂（旧构造兼容路径）。 */
    private val effectiveContextFactory: com.apex.agent.platform.terminal.proot.LinuxExecutionContextFactory
        get() = contextFactory ?: com.apex.agent.platform.terminal.proot.LinuxExecutionContextFactory(
            binaryProvider, rootfsProvider, workspaces, userHome, hostEnv, environment
        )

    private val _events = MutableSharedFlow<PackageOperationEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    override fun operations(): Flow<PackageOperationEvent> = _events.asSharedFlow()

    // ──────────────────────────────────────────────────────────────────
    // status
    // ──────────────────────────────────────────────────────────────────

    override suspend fun status(): PackageManagerStatus {
        val rootfs = rootfsProvider.current()
            ?: return unavailable("no active rootfs")
        // 探测 apt-get + dpkg 存在性
        val aptProbe = runAptRead(rootfs, listOf("apt-get", "--version"), timeoutMs = 10_000)
        val dpkgProbe = runAptRead(rootfs, listOf("dpkg", "--version"), timeoutMs = 10_000)
        val aptOk = aptProbe != null && aptProbe.exitCode == 0
        val dpkgOk = dpkgProbe != null && dpkgProbe.exitCode == 0
        val available = aptOk && dpkgOk
        val version = aptProbe?.stdout?.lineSequence()?.firstOrNull()?.trim()
        // ★ 半装状态检测（用户实测缺陷）：dpkg --version 只证明二进制能跑，
        // 对「apt-get install 被强杀后留下的 unpacked-not-configured / updates
        // 残留」完全无感 —— 环境实际已坏（每次 apt install 立即报
        // "dpkg was interrupted"），health check 却判 HEALTHY →
        // EnvironmentRepairService 不会触发 dpkg --configure -a。
        // dpkg --audit 列出所有处于非一致状态的包（只读 dpkg 数据库，零网络，
        // 秒级）—— 输出非空即 NEEDS_CONFIGURATION，包名解析进 brokenPackages。
        val audit = if (dpkgOk) {
            runAptRead(rootfs, listOf("dpkg", "--audit"), timeoutMs = 15_000)
        } else {
            null
        }
        val brokenFromAudit = audit?.let { parseAuditPackages(it.stdout) } ?: emptyList()
        val databaseState = when {
            !dpkgOk -> PackageDatabaseState.BROKEN
            brokenFromAudit.isNotEmpty() -> PackageDatabaseState.NEEDS_CONFIGURATION
            else -> PackageDatabaseState.HEALTHY
        }
        return PackageManagerStatus(
            available = available,
            manager = if (aptOk) "apt-get" else "none",
            version = version,
            databaseState = databaseState,
            lockState = if (lock.isLocked()) PackageLockState.LOCKED else PackageLockState.FREE,
            metadataState = PackageMetadataState.UNKNOWN,
            brokenPackages = brokenFromAudit
        )
    }

    private fun unavailable(reason: String) = PackageManagerStatus(
        available = false, manager = "none", version = null,
        databaseState = PackageDatabaseState.UNKNOWN,
        lockState = PackageLockState.UNKNOWN,
        metadataState = PackageMetadataState.UNKNOWN,
        brokenPackages = emptyList()
    )

    // ──────────────────────────────────────────────────────────────────
    // update
    // ──────────────────────────────────────────────────────────────────

    override suspend fun update(options: PackageUpdateOptions): PackageOperation {
        val opId = newOpId()
        return runWriteOp(
            opId, PackageOperationType.UPDATE, emptyList(),
            AptCommandBuilder().buildUpdate(), timeoutMs = defaultTimeoutMs
        ) { exec, _ ->
            val installed = emptyList<String>()
            val failed = parseFailedPackages(exec.stderr)
            PackageOperationResult(
                installed = installed,
                durationMs = exec.durationMs,
                exitCode = exec.exitCode,
                operationId = opId,
                state = if (exec.ok) PackageOperationState.SUCCEEDED else PackageOperationState.FAILED,
                stdout = exec.stdout,
                stderr = exec.stderr,
                stdoutTruncated = exec.stdoutTruncated,
                stderrTruncated = exec.stderrTruncated,
                maxOutputBytes = maxOutputBytes,
                failedPackages = failed
            )
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // install
    // ──────────────────────────────────────────────────────────────────

    override suspend fun install(
        packages: List<PackageSpec>,
        options: PackageInstallOptions
    ): PackageOperation {
        require(packages.isNotEmpty()) { "PackageError:EmptyInstall — 至少指定一个包" }
        val first = installInternal(packages, options)
        // ★ dpkg 中断自愈（用户实测致命缺陷）：apt-get install 被强杀（超时
        // destroyForcibly / App 进程死亡 / 系统杀 proot）→ dpkg 数据库留在
        // 「半装状态」（unpacked-not-configured / updates 残留）。此后**每一次**
        // 重试 apt-get install 都在 dpkg 前置检查即失败：
        //   "E: dpkg was interrupted, you must manually run 'dpkg --configure -a'"
        // —— bootstrap BASE_PACKAGES 因此永久 FAILED → 降级 READY 注记永挂，
        // 修复按钮（ensureReady 重跑 bootstrap）陷入同错死循环；且 T97 控制台
        // 已退役，用户没有手动执行 dpkg --configure -a 的通道。
        // 修复：识别 DPKG_INTERRUPTED → 自动 repair()（dpkg --configure -a）→
        // 成功则重试一次 install。锁安全：installInternal 与 repair 各自完整的
        // runWriteOp 生命周期（先获取后释放），顺序调用无重入。
        return autoHealDpkgInterrupted(first) { installInternal(packages, options) }
    }

    private suspend fun installInternal(
        packages: List<PackageSpec>,
        options: PackageInstallOptions
    ): PackageOperation {
        val opId = newOpId()
        val argv = AptCommandBuilder().buildInstall(packages, options)
        return runWriteOp(opId, PackageOperationType.INSTALL, packages, argv, defaultTimeoutMs) { exec, _ ->
            val installed = parseInstalledPackages(exec.stdout)
            val failed = parseFailedPackages(exec.stderr)
            val notFound = parseNotFoundPackages(exec.stderr)
            val alreadySatisfied = packages.map { it.name }.filter { name ->
                name !in installed && name !in failed && name !in notFound
            }
            PackageOperationResult(
                installed = installed,
                alreadySatisfied = alreadySatisfied,
                durationMs = exec.durationMs,
                exitCode = exec.exitCode,
                operationId = opId,
                state = if (exec.ok) PackageOperationState.SUCCEEDED else PackageOperationState.FAILED,
                stdout = exec.stdout,
                stderr = exec.stderr,
                stdoutTruncated = exec.stdoutTruncated,
                stderrTruncated = exec.stderrTruncated,
                maxOutputBytes = maxOutputBytes,
                failedPackages = failed + notFound
            )
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // remove
    // ──────────────────────────────────────────────────────────────────

    override suspend fun remove(
        packages: List<PackageSpec>,
        options: PackageRemoveOptions
    ): PackageOperation {
        require(packages.isNotEmpty()) { "PackageError:EmptyRemove — 至少指定一个包" }
        val opId = newOpId()
        val argv = AptCommandBuilder().buildRemove(packages, options)
        return runWriteOp(opId, PackageOperationType.REMOVE, packages, argv, defaultTimeoutMs) { exec, _ ->
            PackageOperationResult(
                removed = packages.map { it.name },
                durationMs = exec.durationMs,
                exitCode = exec.exitCode,
                operationId = opId,
                state = if (exec.ok) PackageOperationState.SUCCEEDED else PackageOperationState.FAILED,
                stdout = exec.stdout,
                stderr = exec.stderr,
                stdoutTruncated = exec.stdoutTruncated,
                stderrTruncated = exec.stderrTruncated,
                maxOutputBytes = maxOutputBytes,
                failedPackages = if (exec.ok) emptyList() else packages.map { it.name }
            )
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // upgrade
    // ──────────────────────────────────────────────────────────────────

    override suspend fun upgrade(
        packages: List<PackageSpec>,
        options: PackageUpgradeOptions
    ): PackageOperation {
        val first = upgradeInternal(packages)
        // 同 install：dpkg 中断自愈（见 install KDoc —— upgrade 同样会碰
        // "dpkg was interrupted" 前置检查失败）。
        return autoHealDpkgInterrupted(first) { upgradeInternal(packages) }
    }

    private suspend fun upgradeInternal(packages: List<PackageSpec>): PackageOperation {
        val opId = newOpId()
        val argv = AptCommandBuilder().buildUpgrade(packages)
        return runWriteOp(opId, PackageOperationType.UPGRADE, packages, argv, defaultTimeoutMs) { exec, _ ->
            val upgraded = parseInstalledPackages(exec.stdout)  // "Setting up" 行同样标记升级
            PackageOperationResult(
                upgraded = upgraded,
                durationMs = exec.durationMs,
                exitCode = exec.exitCode,
                operationId = opId,
                state = if (exec.ok) PackageOperationState.SUCCEEDED else PackageOperationState.FAILED,
                stdout = exec.stdout,
                stderr = exec.stderr,
                stdoutTruncated = exec.stdoutTruncated,
                stderrTruncated = exec.stderrTruncated,
                maxOutputBytes = maxOutputBytes,
                failedPackages = parseFailedPackages(exec.stderr)
            )
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // search (read-only, no lock)
    // ──────────────────────────────────────────────────────────────────

    override suspend fun search(query: String): PackageSearchResult {
        val rootfs = rootfsProvider.current()
            ?: return PackageSearchResult(query, emptyList())
        val argv = AptCommandBuilder().buildSearch(query)
        val exec = runAptRead(rootfs, argv, timeoutMs = 30_000)
            ?: return PackageSearchResult(query, emptyList())
        val results = exec.stdout.lineSequence()
            .filter { it.isNotBlank() }
            .map { line ->
                val parts = line.split(" - ", limit = 2)
                PackageInfo(
                    name = parts[0].trim(),
                    version = null,
                    architecture = null,
                    installed = false,
                    candidateVersion = null,
                    description = parts.getOrNull(1)?.trim(),
                    sizeBytes = null
                )
            }.toList()
        return PackageSearchResult(query, results)
    }

    // ──────────────────────────────────────────────────────────────────
    // info (read-only)
    // ──────────────────────────────────────────────────────────────────

    override suspend fun info(packageName: String): PackageInfo {
        val rootfs = rootfsProvider.current()
            ?: return emptyInfo(packageName)
        val argv = AptCommandBuilder().buildInfo(packageName)
        val exec = runAptRead(rootfs, argv, timeoutMs = 30_000)
            ?: return emptyInfo(packageName)
        if (exec.exitCode != 0) return emptyInfo(packageName)
        val text = exec.stdout
        val version = regexFind(text, "Version: (.+)")
        val arch = regexFind(text, "Architecture: (.+)")
        val desc = regexFind(text, "Description: (.+)")
        val size = regexFind(text, "Size: (\\d+)")?.toLongOrNull()
        val installed = isInstalled(packageName)
        return PackageInfo(
            name = packageName,
            version = version,
            architecture = arch,
            installed = installed,
            candidateVersion = version,
            description = desc,
            sizeBytes = size
        )
    }

    // ──────────────────────────────────────────────────────────────────
    // isInstalled / installedVersion (read-only)
    // ──────────────────────────────────────────────────────────────────

    override suspend fun isInstalled(packageName: String): Boolean {
        val rootfs = rootfsProvider.current() ?: return false
        val argv = AptCommandBuilder().buildIsInstalled(packageName)
        val exec = runAptRead(rootfs, argv, timeoutMs = 10_000) ?: return false
        return exec.exitCode == 0 && exec.stdout.contains("install ok installed")
    }

    /**
     * T84：批量已装探测 —— 单次 `dpkg-query -W -f='${binary:Package}\t${Provides}\t${db:Status-Abbrev}'`
     * 多包（契约见 [LinuxPackageManager.batchInstalledStatus]）。
     *
     * 两个实测要点：
     *  1. **虚包**：essential 清单含 `awk`（虚包，由 gawk 提供）—— dpkg-query
     *     按包名查库查不到它（no packages found matching awk → stderr + 退出码 1）。
     *     故格式带 ${Provides} 列，虚包随已装实体（gawk）一并算已装；
     *  2. **退出码 1 容忍**：未知/虚包名只触发 warning，其余包的记录仍完整落在
     *     stdout —— 解析部分输出，缺失包按「未装」处理（安全回退）；null 仅留给
     *     环境不可用（rootfs 未就绪 / exec 异常）。
     */
    override suspend fun batchInstalledStatus(packages: List<String>): Map<String, Boolean>? {
        if (packages.isEmpty()) return emptyMap()
        val rootfs = rootfsProvider.current() ?: return null
        // dpkg-query 模板含 ${...}（dpkg 占位符语法）—— Kotlin 字符串拼接避开模板转义。
        val format = "-f=" + "$" + "{binary:Package}" + "\t" + "$" + "{Provides}" + "\t" + "$" + "{db:Status-Abbrev}" + "\n"
        val argv = listOf("dpkg-query", "-W", format) + packages
        val exec = runAptRead(rootfs, argv, timeoutMs = 30_000) ?: return null
        return parseBatchInstalledStatus(exec.stdout, packages)
    }

    override suspend fun installedVersion(packageName: String): String? {
        val rootfs = rootfsProvider.current() ?: return null
        // dpkg-query -W -f=${Version} <pkg>
        val argv = listOf("dpkg-query", "-W", "-f=\${Version}", packageName)
        val exec = runAptRead(rootfs, argv, timeoutMs = 10_000) ?: return null
        return if (exec.exitCode == 0) exec.stdout.trim().takeIf { it.isNotEmpty() } else null
    }

    // ──────────────────────────────────────────────────────────────────
    // repair (dpkg --configure -a)
    // ──────────────────────────────────────────────────────────────────

    /**
     * T82（Termux 基线 §5.2）：真实已安装包列表。
     *
     * 旧 `TerminalLinuxPackages installed` action 是诚实记录的 stub（只回
     * brokenPackages + 让 Agent 自己 shell `dpkg -l`）。本实现经
     * `dpkg-query -W -f=${binary:Package}\t${db:Status-Abbrev}\t${Version}` 解析
     * （只读、无锁、有界），status 以 `ii` 开头 = 正确安装。
     */
    override suspend fun installed(limit: Int): List<com.apex.agent.platform.terminal.pkg.InstalledPackage> {
        val rootfs = rootfsProvider.current() ?: return emptyList()
        // dpkg -W 模板含 ${...}（dpkg 占位符语法）—— Kotlin 模板转义为 ${'$'}{…}。
        val format = "-f=" + "$" + "{binary:Package}" + "\t" + "$" + "{db:Status-Abbrev}" + "\t" + "$" + "{Version}" + "\n"
        val argv = listOf("dpkg-query", "-W", format)
        val exec = runAptRead(rootfs, argv, timeoutMs = 30_000) ?: return emptyList()
        if (exec.exitCode != 0) return emptyList()
        return exec.stdout.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 3) return@mapNotNull null
                val pkg = parts[0].trim()
                if (pkg.isEmpty()) return@mapNotNull null
                com.apex.agent.platform.terminal.pkg.InstalledPackage(
                    name = pkg,
                    status = parts[1].trim(),
                    version = parts.drop(2).joinToString("\t").trim()
                )
            }
            .filter { it.properlyInstalled }
            .take(limit.coerceAtMost(2000))
            .toList()
    }

    /** T82（基线 §5.5）：apt autoremove —— 统一写操作框架（锁 + 事件 + 磁盘 preflight）。 */
    override suspend fun autoremove(): PackageOperation {
        val opId = newOpId()
        val argv = listOf("apt-get", "-y", "autoremove")
        return runWriteOp(opId, PackageOperationType.REMOVE, emptyList(), argv, defaultTimeoutMs) { exec, _ ->
            PackageOperationResult(
                durationMs = exec.durationMs,
                exitCode = exec.exitCode,
                operationId = opId,
                state = if (exec.ok) PackageOperationState.SUCCEEDED else PackageOperationState.FAILED,
                stdout = exec.stdout,
                stderr = exec.stderr,
                stdoutTruncated = exec.stdoutTruncated,
                stderrTruncated = exec.stderrTruncated,
                maxOutputBytes = maxOutputBytes
            )
        }
    }

    /** T82（基线 §5.5）：apt clean —— 下载缓存清理。 */
    override suspend fun clean(): PackageOperation {
        val opId = newOpId()
        val argv = listOf("apt-get", "clean")
        return runWriteOp(opId, PackageOperationType.REPAIR, emptyList(), argv, defaultTimeoutMs) { exec, _ ->
            PackageOperationResult(
                durationMs = exec.durationMs,
                exitCode = exec.exitCode,
                operationId = opId,
                state = if (exec.ok) PackageOperationState.SUCCEEDED else PackageOperationState.FAILED,
                stdout = exec.stdout,
                stderr = exec.stderr,
                stdoutTruncated = exec.stdoutTruncated,
                stderrTruncated = exec.stderrTruncated,
                maxOutputBytes = maxOutputBytes
            )
        }
    }

    override suspend fun repair(): PackageOperation {
        val opId = newOpId()
        val argv = listOf("dpkg", "--configure", "-a")
        return runWriteOp(opId, PackageOperationType.REPAIR, emptyList(), argv, defaultTimeoutMs) { exec, _ ->
            PackageOperationResult(
                durationMs = exec.durationMs,
                exitCode = exec.exitCode,
                operationId = opId,
                state = if (exec.ok) PackageOperationState.SUCCEEDED else PackageOperationState.FAILED,
                stdout = exec.stdout,
                stderr = exec.stderr,
                stdoutTruncated = exec.stdoutTruncated,
                stderrTruncated = exec.stderrTruncated,
                maxOutputBytes = maxOutputBytes
            )
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // dpkg 中断自愈（install / upgrade 共用）
    // ──────────────────────────────────────────────────────────────────

    /**
     * ★ dpkg 中断自动愈合：首次操作失败且错误码为 [PackageErrorCode.DPKG_INTERRUPTED]
     * 时，自动执行 [repair]（`dpkg --configure -a`）完成半装包的配置，成功后重试
     * 一次原操作。
     *
     * 为什么必须在这里做（而非 bootstrap / 工具层各做一遍）：
     *  - 所有 apt 写路径（bootstrap BASE_PACKAGES / TerminalLinuxPackages 工具 /
     *    DepCenter 环境安装）都经过本类 —— 单点修复全覆盖；
     *  - 错误映射 [mapExecToError] 已产出结构化 DPKG_INTERRUPTED（recoverable=true），
     *    但此前**无人消费**该信号 —— 用户实测：bootstrap 降级注记
     *    "E: dpkg was interrupted…" 永挂，修复按钮重跑 bootstrap 同错死循环
     *    （T97 控制台退役后用户也没有手动跑 dpkg --configure -a 的通道）。
     *
     * 语义：
     *  - 首次成功 / 非中断错误 → 原样返回（零行为变化）；
     *  - 中断 → repair → 成功 → 重试一次，重试结果即最终结果（重试成功时
     *    stderr 首行附注自愈轨迹，Agent/用户可见「发生了什么」）；
     *  - 中断 → repair → 失败 → 返回原始失败，但 error.message 附带 repair 失败
     *    原因（诊断保真 —— 两个失败都可见，不再只有第一条）；
     *  - CancellationException 自然透传（installInternal/repair 各自重抛）。
     *
     * 事件流：repair 与重试各自完整发事件（Failed/Completed/Progress）——
     * 订阅者看到真实自愈轨迹，无隐藏副作用。
     */
    private suspend fun autoHealDpkgInterrupted(
        first: PackageOperation,
        retry: suspend () -> PackageOperation
    ): PackageOperation {
        if (first.state == PackageOperationState.SUCCEEDED) return first
        // TIMED_OUT 伴随的中断不自动重试：首要问题是慢/挂死而非 dpkg 状态，
        // 立即重跑大概率再超时一次（多花一整轮 apt 超时预算）—— 交还调用方决策。
        if (first.state == PackageOperationState.TIMED_OUT) return first
        val originalError = first.error ?: return first
        if (originalError.code != PackageErrorCode.DPKG_INTERRUPTED) return first
        val opId = first.id
        emit(
            PackageOperationEvent.Progress(
                opId,
                "AUTO_HEAL",
                "dpkg was interrupted — running dpkg --configure -a then retrying once"
            )
        )
        val repairOp = repair()
        if (repairOp.state != PackageOperationState.SUCCEEDED) {
            val repairDetail = repairOp.error?.message
                ?: repairOp.result?.stderr?.lineSequence()?.filter { it.startsWith("E:") }
                    ?.take(3)?.joinToString("; ")?.ifBlank { null }
                ?: repairOp.result?.stderr?.take(200)
                ?: "dpkg --configure -a failed (exit ${repairOp.exitCode})"
            val healedError = originalError.copy(
                message = "${originalError.message.take(300)} | 自动修复失败: $repairDetail — " +
                    "dpkg 状态未收敛，请重试或删除重装 Ubuntu 环境"
            )
            // 不重发 Failed（首 op 已如实发过）—— 修复失败的合并诊断随返回值
            // 交给调用方（bootstrap note / 工具层 error.message 均可见）。
            return first.copy(error = healedError)
        }
        emit(
            PackageOperationEvent.Progress(
                opId, "AUTO_HEAL",
                "dpkg --configure -a succeeded — retrying original operation once"
            )
        )
        val second = retry()
        // 重试成功：结果 stderr 首行附注自愈轨迹（有界输出内的诚实标注，
        // 不吞不造 —— 原始 apt 输出原样保留在后）。事件流不重发 Completed：
        // 首 op 已如实发过 Failed，重试 op（新 opId）有自己的完整生命周期，
        // 两个 AUTO_HEAL Progress 已标注自愈轨迹。
        if (second.state == PackageOperationState.SUCCEEDED && second.result != null) {
            val annotated = second.result.copy(
                stderr = "[auto-healed: dpkg was interrupted → dpkg --configure -a → retried]\n" +
                    second.result.stderr
            )
            return second.copy(result = annotated)
        }
        return second
    }

    // ──────────────────────────────────────────────────────────────────
    // 核心：写操作执行框架（lock + env + execute + error mapping + cancel/timeout）
    // ──────────────────────────────────────────────────────────────────

    /**
     * 写操作统一框架：
     *  1. 状态置 QUEUED → RUNNING，emit 事件
     *  2. 获取 [PackageOperationLock]（串行化 apt 写）
     *  3. 构造 proot argv（apt env + 既有 rootfs/workspace/home bind）
     *  4. [ProotExecutor.executeBounded]（有界输出 + 超时强杀）
     *  5. 协程取消 → CancellationException 重抛 → finally 释放锁 + 置 CANCELLED
     *  6. 超时 → 置 TIMED_OUT
     *  7. 失败 → 按 exit code / stderr 映射 [PackageErrorCode]
     */
    private suspend fun runWriteOp(
        opId: String,
        type: PackageOperationType,
        packages: List<PackageSpec>,
        aptArgv: List<String>,
        timeoutMs: Long,
        buildResult: (exec: com.apex.agent.platform.terminal.proot.BoundedExecution, rootfs: RootfsDescriptor) -> PackageOperationResult
    ): PackageOperation {
        val startedAt = System.currentTimeMillis()
        emit(PackageOperationEvent.StateChanged(opId, PackageOperationState.QUEUED, PackageOperationState.RUNNING))
        val rootfs = rootfsProvider.current()
        if (rootfs == null || rootfs.location == null) {
            val err = PackageOperationError(PackageErrorCode.ROOTFS_NOT_READY, "no active rootfs", false)
            emit(PackageOperationEvent.Failed(opId, err))
            return failedOp(opId, type, packages, startedAt, err)
        }
        if (!coordinator.tryAcquireWrite(opId)) {
            val err = PackageOperationError(PackageErrorCode.APT_LOCKED, "another apt operation in progress in-process", true)
            emit(PackageOperationEvent.Failed(opId, err))
            return failedOp(opId, type, packages, startedAt, err)
        }
        try {
            return lock.withLock(rootfs) {
                currentCoroutineContext().ensureActive()
                // T81 (U-4) + T84：写操作磁盘 preflight。
                // T84 修正：INSTALL 的空间需求按**真实缺失包数**估算 —— 旧公式
                // packages.size × 250MB 对全预装场景（完整 rootfs 的 bootstrap 校验）
                // 虚报 ~6GB 把正常设备拒之门外。批量探测不可用（null）时回退旧估算。
                val requiredBytes = when (type) {
                    PackageOperationType.INSTALL -> {
                        val statuses = batchInstalledStatus(packages.map { it.name })
                        val chargeable = if (statuses != null) {
                            statuses.values.count { !it }
                        } else {
                            packages.size
                        }
                        100L * 1024 * 1024 + chargeable * 250L * 1024 * 1024
                    }
                    PackageOperationType.UPGRADE ->
                        100L * 1024 * 1024 + packages.size * 250L * 1024 * 1024
                    else -> 100L * 1024 * 1024
                }
                runCatching { diskPreflight(java.io.File(rootfs.location!!.value), requiredBytes, type.name) }
                    .onFailure { fe ->
                        val err = PackageOperationError(
                            PackageErrorCode.DISK_FULL,
                            fe.message ?: "insufficient disk space",
                            recoverable = false
                        )
                        emit(PackageOperationEvent.Failed(opId, err))
                        throw fe
                    }
                emit(PackageOperationEvent.Progress(opId, "EXECUTE", aptArgv.joinToString(" ")))
                val exec = runAptBounded(rootfs, aptArgv, timeoutMs)
                currentCoroutineContext().ensureActive()
                val result = buildResult(exec, rootfs)
                val finalState = when {
                    exec.timedOut -> PackageOperationState.TIMED_OUT
                    exec.ok -> PackageOperationState.SUCCEEDED
                    else -> PackageOperationState.FAILED
                }
                val finalResult = result.copy(state = finalState)
                // P3 fix（审计 6-b）：mapExecToError 只调一次 —— 原实现事件发射与 op
                // 构造各调一次（重复构造 + 双份日志噪音）。
                val err = if (finalState == PackageOperationState.SUCCEEDED) null
                    else mapExecToError(exec, aptArgv)
                if (err == null) {
                    emit(PackageOperationEvent.Completed(opId, finalResult))
                } else {
                    emit(PackageOperationEvent.Failed(opId, err))
                }
                opOf(opId, type, packages, startedAt, finalState, finalResult, err)
            }
        } catch (ce: CancellationException) {
            // 状态发射：订阅者看到真实取消（RUNNING → CANCELLED），而非 FAILED
            emit(PackageOperationEvent.StateChanged(opId, PackageOperationState.RUNNING, PackageOperationState.CANCELLED))
            // P2 fix（审计 6-b，与类文档第 5 条契约对齐）：CancellationException 必须
            // 重抛（保留 finally 释放锁）—— 原实现吞掉后返回 cancelledOp，调用方
            // Job 已被取消却看到写操作“正常返回”，结构化取消传播失效。
            throw ce
        } catch (e: Exception) {
            val err = mapExceptionToError(e, aptArgv)
            emit(PackageOperationEvent.Failed(opId, err))
            return failedOp(opId, type, packages, startedAt, err)
        } finally {
            coordinator.releaseWrite(opId)
        }
    }

    /**
     * T81 (U-2)：读操作最近一次环境错误（proot 崩溃/rootfs 缺失/超时…）。
     * 原实现 catch(Exception)→null 全吞：isInstalled()=false 与真实「未安装」
     * 不可区分（静默失败掩盖环境损坏）。返回值保持保守语义（false/空），
     * 但环境错误通过此 StateFlow 可观察 —— 工具层据此区分并引导 repair。
     */
    private val _lastReadError = kotlinx.coroutines.flow.MutableStateFlow<PackageOperationError?>(null)
    val lastReadError: kotlinx.coroutines.flow.StateFlow<PackageOperationError?> = _lastReadError.asStateFlow()

    /**
     * T81 (U-4)：写操作磁盘 preflight —— rootfs 所在卷剩余空间不足时提前结构化失败
     * （DISK_FULL），不再等 apt 中途失败留下半装状态。估算：每包 250MB 保守预算
     * + 100MB 基线（dpkg 数据库/日志）。可通过 [diskPreflight] 注入替换（测试）。
     */
    internal var diskPreflight: suspend (rootfsDir: java.io.File, requiredBytes: Long, stage: String) -> Unit =
        { dir, required, stage ->
            val usable = dir.usableSpace
            // P3 fix（审计 6-b）：usable==0（卷满/不可读）也会触发 DISK_FULL ——
            // 原 `usable in 1 until required` 把 0 排除在外，满盘反而漏判放行。
            if (usable < required) {
                throw RuntimeException(
                    "PackageError:DISK_FULL — ${'$'}stage 前磁盘不足（需 ${'$'}{required / (1024 * 1024)}MB，" +
                        "可用 ${'$'}{usable / (1024 * 1024)}MB）"
                )
            }
        }

    /** 经 ProotExecutor 执行一个 apt 子命令（写操作路径，有界输出）。 */
    private suspend fun runAptBounded(
        rootfs: RootfsDescriptor,
        aptArgv: List<String>,
        timeoutMs: Long
    ): com.apex.agent.platform.terminal.proot.BoundedExecution {
        val command = buildProotCommand(rootfs, aptArgv, environment.aptGuestEnv())
        // ProotExecutor.executeBounded 是同步阻塞（ProcessBuilder.waitFor）——
        // 包在 withContext(Dispatchers.IO) 内以免阻塞协程调度器。但本类无 Dispatchers
        // 依赖（保持纯 JVM 可测试）；调用方（TerminalRuntime IO 协程）已处于 IO 上下文。
        //
        // 取消感知（P0 修复）：旧实现的整段 waitFor 阻塞 —— 协程取消后仍占满
        // 整个 apt 超时（600s）；现在把调用方协程的活跃状态注入为取消探测，
        // 分片轮询（100ms 粒度）在取消后立即强杀返回。
        val callerContext = currentCoroutineContext()
        return executor.executeBounded(
            command, timeoutMs = timeoutMs, maxOutputBytes = maxOutputBytes,
            isCancelled = { !callerContext.isActive }
        )
    }

    /**
     * 读操作路径（无锁，短超时）。返回 null = 环境不可用。
     * T81 (U-2)：环境错误不再静默吞掉 —— 记录到 [lastReadError]（含结构化
     * 错误码），返回值仍为 null（调用方保守语义不变）。
     */
    private suspend fun runAptRead(
        rootfs: RootfsDescriptor,
        aptArgv: List<String>,
        timeoutMs: Long
    ): com.apex.agent.platform.terminal.proot.BoundedExecution? {
        return try {
            val command = buildProotCommand(rootfs, aptArgv, environment.aptGuestEnv())
            val callerContext = currentCoroutineContext()
            executor.executeBounded(
                command, timeoutMs = timeoutMs, maxOutputBytes = maxOutputBytes,
                isCancelled = { !callerContext.isActive }
            )
        } catch (e: Exception) {
            _lastReadError.value = mapExceptionToError(e, aptArgv)
            null
        }
    }

    /**
     * 构造 proot PRootCommand：apt 子命令作为 guest executable + argv。
     * 复用 [PRootCommandBuilder] 的注入防护（TM6：guestPath/env value 校验）。
     */
    private suspend fun buildProotCommand(
        rootfs: RootfsDescriptor,
        aptArgv: List<String>,
        guestEnv: Map<String, String>
    ): PRootCommand {
        val binaryPath = binaryProvider.locate().getOrElse { e ->
            throw RuntimeException("AptError:PROOT_UNAVAILABLE — ${e.message}", e)
        }
        // T92（D5 完成度）：verify 产物不再丢弃 —— capabilities 参与 argv 构造
        //（与 LinuxPRootBackend 同款能力门；此前 apt 路径默认 Termux 基线，
        // 二进制升级/替换后 apt 拒启而终端会话正常，静默分叉）。
        val binaryInfo = binaryProvider.verify(binaryPath).getOrElse { e ->
            throw RuntimeException("AptError:PROOT_VERIFY_FAILED — ${e.message}", e)
        }
        val rootfsPath = rootfs.location
            ?: throw RuntimeException("AptError:ROOTFS_NOT_READY — rootfs 无 location")
        // workspace bind：用 default workspace（与 terminal session 共享状态 —— T76 §3）
        val workspaceDir = workspaces.resolve(LinuxWorkspaceManager.DEFAULT_ID).getOrElse { e ->
            throw RuntimeException("AptError:WORKSPACE_UNAVAILABLE — ${e.message}", e)
        }
        // persistent home bind（与 terminal session 共享 /root）
        val homeDir = userHome.ensureReady(File(rootfsPath.value)).getOrElse { e ->
            throw RuntimeException("AptError:HOME_UNAVAILABLE — ${e.message}", e)
        }
        // host env 准备（proot 自身需要）
        hostEnv.prepare().getOrElse { e ->
            throw RuntimeException("AptError:PROOT_HOST_ENV — ${e.message}", e)
        }
        val executable = aptArgv.first()
        val arguments = aptArgv.drop(1)
        val launch = PRootLaunchRequest(
            rootfs = rootfs,
            executable = executable,
            arguments = arguments,
            workingDirectory = WorkspacePath(GUEST_APT_CWD),
            environment = guestEnv,
            // ★ 系统级 bind 对齐交互会话（LinuxPRootBackend.prepare 用
            // SystemBindProfile.STANDARD）：/proc /dev /sys 缺失时 dpkg
            // maintainer script、procps 依赖工具（dpkg-query 探测路径）、
            // /dev/urandom 熵源（python-ssl/curl）都会出现只在 apt 侧复现的
            // 诡异失败 —— 两条执行路径 bind 集不一致是环境漂移源。host 路径
            // 不存在的条目由 toBinds() 诚实过滤。
            binds = buildList {
                add(PRootBind(AbsolutePath(homeDir.absolutePath), GuestUserHome.GUEST_PATH))
                addAll(systemBinds.toBinds())
            },
            terminalMode = com.apex.agent.platform.terminal.api.TerminalMode.AUTO,
            fakeRoot = true,
            killOnExit = true
        )
        return commandBuilder.build(
            launch,
            binaryPath,
            rootfsPath,
            AbsolutePath(workspaceDir.absolutePath),
            // T92：探针实测能力门（upstream 5.1.0 等不带 --kill-on-exit 的
            // 二进制不再拒启；Termux 捆绑基线 argv 逐字节不变）
            capabilities = binaryInfo.capabilities
        )
    }

    // ──────────────────────────────────────────────────────────────────
    // 错误映射 + 输出解析
    // ──────────────────────────────────────────────────────────────────

    private fun mapExecToError(
        exec: com.apex.agent.platform.terminal.proot.BoundedExecution,
        aptArgv: List<String>
    ): PackageOperationError {
        if (exec.timedOut) {
            return PackageOperationError(PackageErrorCode.TIMEOUT, "apt timed out after ${exec.durationMs}ms", recoverable = true)
        }
        val stderr = exec.stderr
        return when {
            stderr.contains("Unable to locate package") ->
                PackageOperationError(PackageErrorCode.PACKAGE_NOT_FOUND, stderr.trim(), recoverable = false)
            stderr.contains("Could not get lock") || stderr.contains("lock") ->
                PackageOperationError(PackageErrorCode.APT_LOCKED, stderr.trim(), recoverable = true)
            stderr.contains("dpkg was interrupted") || stderr.contains("dpkg returned an error") ->
                PackageOperationError(PackageErrorCode.DPKG_INTERRUPTED, stderr.trim(), recoverable = true)
            stderr.contains("broken packages") || stderr.contains("Unmet dependencies") ->
                PackageOperationError(PackageErrorCode.DEPENDENCY_CONFLICT, stderr.trim(), recoverable = false)
            stderr.contains("No space left") ->
                PackageOperationError(PackageErrorCode.DISK_FULL, stderr.trim(), recoverable = false)
            stderr.contains("Connection failed") || stderr.contains("Temporary failure") ->
                PackageOperationError(PackageErrorCode.NETWORK_DNS_FAILED, stderr.trim(), recoverable = true)
            stderr.contains("Certificate verification failed") || stderr.contains("TLS") ->
                PackageOperationError(PackageErrorCode.NETWORK_TLS_FAILED, stderr.trim(), recoverable = false)
            exec.exitCode == 100 ->
                PackageOperationError(PackageErrorCode.APT_FAILED, "apt-get exit 100: ${stderr.trim()}", recoverable = true)
            else ->
                PackageOperationError(PackageErrorCode.UNKNOWN, "exit=${exec.exitCode}: ${stderr.trim().take(500)}", recoverable = false)
        }
    }

    private fun mapExceptionToError(e: Throwable, aptArgv: List<String>): PackageOperationError {
        val msg = e.message ?: ""
        return when {
            // T81 (U-6)：跨实例 OS 锁竞争（PackageOperationLock 抛 PackageLockError:OsLockHeld）
            // 原映射无此标记 → 错标 UNKNOWN/不可恢复；应为 APT_LOCKED/可重试。
            msg.contains("PackageLockError:OsLockHeld") || msg.contains("OsLockHeld") ->
                PackageOperationError(PackageErrorCode.APT_LOCKED, msg, recoverable = true)
            msg.contains("PROOT_UNAVAILABLE") -> PackageOperationError(PackageErrorCode.PROOT_UNAVAILABLE, msg, false)
            msg.contains("ROOTFS_NOT_READY") -> PackageOperationError(PackageErrorCode.ROOTFS_NOT_READY, msg, false)
            msg.contains("WORKSPACE_UNAVAILABLE") -> PackageOperationError(PackageErrorCode.WORKSPACE_UNAVAILABLE, msg, true)
            msg.contains("HOME_UNAVAILABLE") -> PackageOperationError(PackageErrorCode.HOME_UNAVAILABLE, msg, true)
            else -> PackageOperationError(PackageErrorCode.UNKNOWN, "${e.javaClass.simpleName}: $msg", false)
        }
    }

    /** 解析 apt-get install 输出的 "Setting up <pkg>" / "Unpacking <pkg>" 行。 */
    private fun parseInstalledPackages(stdout: String): List<String> {
        val installed = mutableListOf<String>()
        val settingUp = Regex("Setting up (\\S+)").findAll(stdout)
        for (m in settingUp) {
            installed.add(m.groupValues[1])
        }
        return installed.distinct()
    }

    /** 解析 stderr 中失败的包名（E: / dpkg error processing package X）。 */
    private fun parseFailedPackages(stderr: String): List<String> {
        val failed = mutableListOf<String>()
        // "error processing package X (--configure)"
        Regex("error processing package (\\S+)").findAll(stderr).forEach { failed.add(it.groupValues[1]) }
        // "E: Sub-process returned an error code" 无包名 —— 忽略
        return failed.distinct()
    }

    /** 解析 "E: Unable to locate package X"。 */
    private fun parseNotFoundPackages(stderr: String): List<String> {
        return Regex("Unable to locate package (\\S+)").findAll(stderr)
            .map { it.groupValues[1] }
            .distinct()
            .toList()
    }

    private fun regexFind(text: String, pattern: String): String? {
        val m = Regex(pattern).find(text)
        return m?.groupValues?.getOrNull(1)?.trim()
    }

    private fun emptyInfo(name: String) = PackageInfo(
        name = name, version = null, architecture = null,
        installed = false, candidateVersion = null, description = null, sizeBytes = null
    )

    // ──────────────────────────────────────────────────────────────────
    // op 构造 + 事件
    // ──────────────────────────────────────────────────────────────────

    private fun newOpId(): String = "apt-${UUID.randomUUID().toString().take(8)}"

    private fun emit(event: PackageOperationEvent) {
        _events.tryEmit(event)
    }

    private fun opOf(
        opId: String, type: PackageOperationType, packages: List<PackageSpec>,
        startedAt: Long, state: PackageOperationState,
        result: PackageOperationResult?, error: PackageOperationError?
    ): PackageOperation {
        return PackageOperation(
            id = opId, type = type, state = state,
            requestedPackages = packages,
            startedAt = startedAt, finishedAt = System.currentTimeMillis(),
            exitCode = result?.exitCode, result = result, error = error
        )
    }

    private fun failedOp(
        opId: String, type: PackageOperationType, packages: List<PackageSpec>,
        startedAt: Long, error: PackageOperationError
    ): PackageOperation = opOf(
        opId, type, packages, startedAt, PackageOperationState.FAILED,
        result = null, error = error
    )

    companion object {
        /**
         * apt 操作默认超时。180s 在慢网络（尤其中国大陆 → ports.ubuntu.com 官方源）
         * 下普遍不够：apt update 需拉取 InRelease + 索引 20-40MB，bootstrap 的
         * base packages 安装更是 200MB+ 级下载 —— 180s 必超时（TIMEOUT → bootstrap
         * FAILED@APT_UPDATE/BASE_PACKAGES）。提升到 10 分钟与 ensureReady 的
         * 30 分钟总预算（UbuntuLifecycleCoordinator.DEFAULT_ENSURE_TIMEOUT_MS，T84）
         * 对齐；超时只惩罚挂死，正常慢速下载不会误杀。
         */
        const val DEFAULT_APT_TIMEOUT_MS: Long = 600_000L
        /** apt 操作的 guest cwd（/root —— 持久 home，可写）。 */
        const val GUEST_APT_CWD = "/root"

        /**
         * T84：`dpkg-query -W -f='${binary:Package}\t${Provides}\t${db:Status-Abbrev}'`
         * 输出解析（纯函数，供 [batchInstalledStatus] 与单测直接消费）。
         *
         * - status 前缀 `ii` = 已正确安装（T82 基线同判据）；
         * - **虚包解析**：已装实体 Provides 列里的名字（如 gawk → awk）一并算已装；
         * - 未出现在输出中的包 = 未装（调用方安全回退，绝不误报已装）。
         */
        internal fun parseBatchInstalledStatus(
            stdout: String,
            packages: List<String>
        ): Map<String, Boolean> {
            // (name, provides, installed)
            val rows = stdout.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull { line ->
                    val cols = line.split('\t')
                    if (cols.size < 3) return@mapNotNull null
                    Triple(
                        cols[0].trim(),
                        cols[1].trim().split(' ', ',').map { it.trim() }.filter { it.isNotEmpty() },
                        cols.last().trim().startsWith("ii")
                    )
                }
                .toList()
            val pkgNameShape = Regex("[a-z0-9][a-z0-9.+\\-]*")
            val installedNames = buildSet {
                rows.filter { it.third }.forEach { (name, provides, _) ->
                    add(name)
                    // 版本化 provides 形如 "awk (= 1:5.2...)" —— 按包名形状过滤碎片
                    provides.filter { it.matches(pkgNameShape) }.forEach { add(it) }
                }
            }
            return packages.associateWith { it in installedNames }
        }

        /**
         * ★ `dpkg --audit` 输出解析（纯函数）—— 半装/未配置包名提取。
         *
         * 输出形态（各段落说明文字 + 空行后的包名行）：
         * ```text
         * The following packages have been unpacked but not yet configured.
         *  They must be configured using dpkg --configure or the configure
         *  menu option in dselect for them to work:
         *
         *  clickhouse-common-static libc6
         *
         * The following packages are only half configured, ...
         * ```
         * 判定规则（保守，不误收说明文字）：一行内**所有**空白分隔 token 都是
         * 包名形状（`[a-z0-9][a-z0-9.+-]*(:arch)?`，尾部逗号/句号剥掉）才算包名行
         * —— 说明句含大写开头/逗号/冒号等标点，天然被排除。多行包名各自解析。
         */
        internal fun parseAuditPackages(auditStdout: String): List<String> {
            if (auditStdout.isBlank()) return emptyList()
            val tokenShape = Regex("[a-z0-9][a-z0-9.+\\-]*(?::[a-z0-9]+)?")
            return auditStdout.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .filter { line ->
                    // 至少一个 token 且全部为包名形状（说明文字必含大写/标点 → 排除）
                    val tokens = line.split(Regex("\\s+"))
                        .map { it.trimEnd(',', '.', ';') }
                        .filter { it.isNotEmpty() }
                    tokens.isNotEmpty() && tokens.all { tokenShape.matches(it) }
                }
                .flatMap { line ->
                    line.split(Regex("\\s+"))
                        .map { it.trimEnd(',', '.', ';') }
                        .filter { it.isNotEmpty() }
                }
                .distinct()
                .take(50)   // 有界：极端损坏时防日志/事件爆炸
                .toList()
        }
    }
}
