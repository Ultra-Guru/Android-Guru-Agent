package com.apex.agent.platform.terminal.chroot

import com.apex.agent.platform.terminal.environment.LinuxEnvironmentManager
import com.apex.agent.platform.terminal.linux.RootfsDescriptor
import com.apex.agent.platform.terminal.linux.RootfsProvider
import com.apex.agent.platform.terminal.proot.PRootEnvTrampoline
import com.apex.agent.platform.terminal.proot.SharedStorageBridge
import com.apex.agent.platform.terminal.runtime.BackendAvailability
import com.apex.agent.platform.terminal.runtime.BackendRuntimeType
import com.apex.agent.platform.terminal.runtime.BackendSessionMetadata
import com.apex.agent.platform.terminal.runtime.ExecutionBackend
import com.apex.agent.platform.terminal.runtime.SessionSpawnRequest
import com.apex.agent.platform.terminal.runtime.SpawnSpec
import com.apex.agent.platform.terminal.workspace.AbsolutePath
import com.apex.agent.platform.terminal.workspace.GuestCwdMapper
import com.apex.agent.platform.terminal.workspace.GuestUserHome
import com.apex.agent.platform.terminal.workspace.LinuxWorkspaceManager
import java.io.File

/**
 * T98: LinuxChrootBackend —— 真实 Root 的 chroot 执行后端（PRoot 的孪生兄弟）。
 *
 * ## 为什么需要它（PRoot 的结构性短板）
 *
 * PRoot 是 ptrace 用户态翻译层：每次 syscall 都要 tracer 拦截 + 路径重写 +
 * 伪 root 伪装 —— 性能差（文件密集型任务 3~10 倍损耗）、边界条件多。更致命
 * 的是现代 Android（HyperOS / ColorOS / 鸿蒙等）收紧 `kernel.yama.ptrace_scope`
 * 与 SELinux 策略后，PRoot 在部分设备上**直接假死**（tracer 无法 attach →
 * 僵尸进程挂死，见 rootfs E2E 测试的「ptrace 受限环境」注记）。chroot 是
 * 内核原语，不受 ptrace 限制，代价是**必须真实 Root**。
 *
 * ## 双模式定位（与本仓库三级权限链的关系）
 *
 * ```
 * 无 Root     → linux-ubuntu（PRoot，默认后端，零门槛）
 * 有 Root     → linux-ubuntu-chroot（本后端，Agent 经 TerminalCreate 显式选择）
 * ```
 *
 * PRoot 保持注册表首位（默认后端不变）；本后端由 Agent 通过
 * TerminalBackends 发现、TerminalCreate(backend="linux-ubuntu-chroot") 显式
 * 创建 —— 不做隐式自动降级/升级（可用性探测无法廉价区分「ptrace 受限」
 * 与「proot 版本差异」，隐式切换会让排查变玄学；诚实的显式选择优于聪明
 * 的自动切换）。权限链（Root / Shizuku / 无障碍，platform/privilege）的
 * 联动降级是后续工作（见 docs/terminal/TERMINAL_CHROOT_BACKEND_T98.md 路线图）。
 *
 * ## argv 契约（forkpty → execv(su, ["-c", script])）
 *
 * ```
 * <su> -c '
 *   R=<rootfs>
 *   mountpoint -q "$R/proc"      || mount -t proc  proc     "$R/proc"      2>/dev/null
 *   mountpoint -q "$R/sys"       || mount -t sysfs sys      "$R/sys"       2>/dev/null
 *   mountpoint -q "$R/dev"       || mount --bind /dev       "$R/dev"       2>/dev/null
 *   mountpoint -q "$R/root"      || mount --bind <homeDir>  "$R/root"      2>/dev/null
 *   mkdir -p "$R/workspace" 2>/dev/null
 *   mountpoint -q "$R/workspace" || mount --bind <wsDir>    "$R/workspace" 2>/dev/null
 *   [共享存储可用时] mkdir -p "$R/sdcard"; mount --bind /storage/emulated/0 "$R/sdcard"
 *   chroot "$R" /usr/bin/env -i 'K=V' … /bin/sh -c "cd <guestCwd> && exec /bin/bash -i"
 *   umount …（LIFO 最佳努力收尾）
 * '
 * ```
 *
 *  - **env trampoline 复用 [PRootEnvTrampoline]**：`/usr/bin/env -i K=V` 与
 *    PRoot 后端同一防注入构造（key 形态校验 / value 禁 \n、NUL / PATH 兜底），
 *    双后端 guest env 逐字节同源（LinuxEnvironmentManager.interactiveGuestEnv
 *    —— T81 D-6 的单一事实源纪律）。trampoline 名字带 PRoot 是历史原因，
 *    语义是通用的 env 清洗器；
 *  - **cwd**：chroot(1) 落 guest "/"，要进 /workspace 必须经 sh -c cd（无 -w
 *    等价物）；guestCwd 走 [GuestCwdMapper]（与 PRoot 同映射）；
 *  - **挂载幂等**：mountpoint -q 守卫 —— 上次会话异常退出留下的挂载直接复用，
 *    不叠加堆叠挂载；多个 chroot 会话并发时共享挂载（先退出的 umount 因
 *    EBUSY 静默失败，最后退出的清场 —— 最佳努力，无泄漏累积）；
 *  - **PTY 语义**：forkpty 使 su 成为会话/进程组长（PGID==su PID）→
 *    kill(-PGID)/TIOCSWINSZ/tcgetpgrp 与 LOCAL/PRoot 会话同一套语义；
 *    bash 拿到的是真 PTY slave（su/chroot 只传递 fd，不翻译）。
 *
 * ## 与 PRoot 后端的语义对齐表
 *
 * | 维度 | linux-ubuntu（PRoot） | linux-ubuntu-chroot（本类） |
 * |---|---|---|
 * | 门槛 | 无 Root | 真实 Root（su 授权） |
 * | ptrace 依赖 | 强（受限环境假死） | 无 |
 * | 性能 | syscall 翻译损耗 | 原生（内核 namespace 内直跑） |
 * | guest uid | fake root（-0 伪装） | 真实 uid 0 |
 * | rootfs | 同一 Provisioner 产物（共享） | 同一 Provisioner 产物（共享） |
 * | workspace/home | bind → /workspace、/root | mount --bind → 同路径 |
 * | guest env | env trampoline（同源） | env trampoline（同源） |
 */
class LinuxChrootBackend(
    /** su 定位器（T98：文件系统探测，绝不 exec —— 见 [SuBinaryLocator]）。 */
    private val suLocator: SuBinaryLocator,
    private val rootfsProvider: RootfsProvider,
    /** T75: workspace 管理（与 PRoot 后端共享同一 manager —— 同一目录池）。 */
    private val workspaces: LinuxWorkspaceManager,
    /** T75: 持久化用户 home（与 PRoot 后端共享 —— 双模式数据互通）。 */
    private val userHome: GuestUserHome,
    /** guest env 权威来源（T81 D-6：与 PRoot 后端同源）。 */
    private val environment: LinuxEnvironmentManager = LinuxEnvironmentManager(),
    /** 共享存储桥（与 PRoot 后端同款约定；不可用 → 不 bind）。 */
    private val sharedStorage: SharedStorageBridge? = null,
    override val id: String = ID
) : ExecutionBackend {

    override val runtimeType: BackendRuntimeType = BackendRuntimeType.LINUX

    // 注意：availability/prepare 不包 withContext(Dispatchers.IO) —— 与
    // LinuxPRootBackend 同款纪律（内部全是瞬时操作；线程归属由调用方
    // TerminalRuntime 的 IO 协程决定）。
    override suspend fun availability(): BackendAvailability {
        val su = suLocator.locate()
            ?: return BackendAvailability.Failed(
                "ChrootError:SU_NOT_FOUND — 设备无 su 二进制（主流 Root 方案落点：" +
                    SuBinaryLocator.DEFAULT_CANDIDATES.joinToString(", ") + "）。" +
                    "chroot 后端需要真实 Root；未 Root 设备请使用默认 linux-ubuntu（PRoot）后端。"
            )
        if (!su.canExecute()) {
            // locate() 已过滤，此处防御并发删除窗口（下一次 availability 重新探测）。
            return BackendAvailability.Failed("ChrootError:SU_NOT_EXECUTABLE — ${su.absolutePath}")
        }
        val rootfs = rootfsProvider.current()
            ?: return BackendAvailability.NeedsRootfs()
        return if (rootfs.location == null) {
            BackendAvailability.NeedsRootfs("no_location")
        } else {
            BackendAvailability.Ready
        }
    }

    override suspend fun prepare(request: SessionSpawnRequest): Result<SpawnSpec> {
        // 1. su（availability 已探过；prepare 再探一次防并发变化 —— 与 PRoot 的
        //    binaryProvider.locate+verify 同款诚实重探）。
        val su = suLocator.locate()
            ?: return Result.failure(
                IllegalStateException("ChrootError:SU_NOT_FOUND — su 在 prepare 时消失（Root 方案被卸载？）")
            )

        // 2. rootfs
        val rootfs: RootfsDescriptor = rootfsProvider.current()
            ?: return Result.failure(
                RuntimeException("TerminalError:RootfsNotReady — 无已安装 rootfs（先用 TerminalUbuntuInstall 引导）")
            )
        val rootfsPath = rootfs.location
            ?: return Result.failure(
                RuntimeException("TerminalError:RootfsNotReady — rootfs 无 location")
            )

        // 3. workspace + 持久化 home（与 PRoot 后端同款懒解析/播种 —— 双模式共享）。
        val workspaceId = request.workspaceId?.trim()?.takeIf { it.isNotEmpty() }
            ?: LinuxWorkspaceManager.DEFAULT_ID
        val workspaceDir = workspaces.resolve(workspaceId).getOrElse { e ->
            return Result.failure(e)
        }
        val userHomeDir = userHome.ensureReady(File(rootfsPath.value)).getOrElse { e ->
            return Result.failure(e)
        }

        // 4. guest cwd（GuestCwdMapper 单一事实源 —— 与 PRoot 同映射）。
        val guestCwd = GuestCwdMapper.map(request.cwd)
        if (!GUEST_CWD_SAFE.matches(guestCwd)) {
            return Result.failure(
                IllegalArgumentException(
                    "ChrootError:InvalidCwd — guest cwd 必须匹配 ${GUEST_CWD_SAFE.pattern}" +
                        "（shell 脚本内嵌安全边界；got \"$guestCwd\"）"
                )
            )
        }

        // 5. guest env（env trampoline 复用 —— 与 PRoot 同源同构造）。
        val guestEnv = environment.interactiveGuestEnv(request.env)

        // 6. 组装 spawn 脚本 + SpawnSpec。
        val script = buildScript(
            rootfsDir = rootfsPath.value,
            homeDir = userHomeDir.absolutePath,
            workspaceDir = workspaceDir.absolutePath,
            guestCwd = guestCwd,
            guestEnv = guestEnv,
            sharedStorageHostDir = sharedStorage?.hostDir()?.absolutePath
        )
        return Result.success(
            SpawnSpec(
                argv = listOf(su.absolutePath, "-c", script),
                env = HOST_ENV,
                // host cwd 只需一个必然存在的目录（su/chroot 不读它；-c 内自管路径）。
                cwd = rootfsPath.value,
                cwdIsGuestPath = true,
                shellDisplay = GUEST_SHELL,
                cwdDisplay = guestCwd,
                metadata = BackendSessionMetadata(
                    backendId = id,
                    rootfsId = rootfs.id,
                    workspaceId = workspaceId,
                    workspaceDir = workspaceDir.absolutePath,
                    binds = mountDescriptions(
                        homeDir = userHomeDir.absolutePath,
                        workspaceDir = workspaceDir.absolutePath,
                        sharedStorageHostDir = sharedStorage?.hostDir()?.absolutePath
                    ),
                    guestCwd = guestCwd
                )
            )
        )
    }

    /**
     * 组装 su -c 的完整脚本（internal —— 测试直接断言脚本形状契约）。
     *
     * 结构（见类 KDoc argv 契约）：
     *  1. 系统挂载 /proc /sys /dev（mountpoint 幂等守卫）；
     *  2. home → /root、workspace → /workspace、共享存储 → /sdcard（bind）；
     *  3. chroot + env trampoline + sh -c cd && exec bash -i；
     *  4. LIFO umount 最佳努力收尾（EBUSY 静默 —— 并发会话共享挂载）。
     */
    internal fun buildScript(
        rootfsDir: String,
        homeDir: String,
        workspaceDir: String,
        guestCwd: String,
        guestEnv: Map<String, String>,
        sharedStorageHostDir: String?
    ): String {
        val r = shellQuote(rootfsDir)
        val lines = mutableListOf<String>()

        // ── 1. 系统级挂载（与 SystemBindProfile.STANDARD 同集 —— /proc /dev /sys）──
        lines += "R=$r"
        lines += "mountpoint -q \"\$R/proc\" || mount -t proc proc \"\$R/proc\" 2>/dev/null"
        lines += "mountpoint -q \"\$R/sys\" || mount -t sysfs sys \"\$R/sys\" 2>/dev/null"
        lines += "mountpoint -q \"\$R/dev\" || mount --bind /dev \"\$R/dev\" 2>/dev/null"

        // ── 2. 持久化 home / workspace / 共享存储（bind）──
        lines += "mountpoint -q \"\$R/root\" || mount --bind ${shellQuote(homeDir)} \"\$R/root\" 2>/dev/null"
        lines += "mkdir -p \"\$R/workspace\" 2>/dev/null"
        lines += "mountpoint -q \"\$R/workspace\" || mount --bind ${shellQuote(workspaceDir)} \"\$R/workspace\" 2>/dev/null"
        if (sharedStorageHostDir != null) {
            lines += "mkdir -p \"\$R/sdcard\" 2>/dev/null"
            lines += "mountpoint -q \"\$R/sdcard\" || mount --bind ${shellQuote(sharedStorageHostDir)} \"\$R/sdcard\" 2>/dev/null"
        }

        // ── 3. chroot + env trampoline（/usr/bin/env -i K=V … 与 PRoot 后端同源）──
        // guestCwd 已过 GUEST_CWD_SAFE 白名单（无 shell 元字符），内嵌无需引号；
        // K=V token 经 shellQuote（value 可含空格等元字符 —— argv 数组在 proot
        // 后端天然安全，脚本字符串这里必须显式包裹）。
        val envTokens = PRootEnvTrampoline.guestPrefix(guestEnv)
            .drop(ENV_EXECUTABLE_PREFIX_SIZE)   // 剥掉 /usr/bin/env -i（chroot 命令自带）
            .joinToString(" ") { shellQuote(it) }
        lines += "chroot \"\$R\" ${shellQuote(ENV_EXECUTABLE)} -i $envTokens /bin/sh -c 'cd $guestCwd && exec /bin/bash -i'"

        // ── 4. LIFO 最佳努力收尾（bash 退出后执行；EBUSY=并发会话共享，静默）──
        val umounts = mutableListOf("\$R/workspace", "\$R/root")
        if (sharedStorageHostDir != null) umounts.add(0, "\$R/sdcard")
        umounts += listOf("\$R/dev", "\$R/sys", "\$R/proc")
        lines += umounts.joinToString(" ; ") { "umount \"$it\" 2>/dev/null" }
        return lines.joinToString("\n")
    }

    /** metadata.binds 的挂载描述（host:guest 形态 —— 与 PRoot 的 binds 同口径）。 */
    private fun mountDescriptions(
        homeDir: String,
        workspaceDir: String,
        sharedStorageHostDir: String?
    ): List<String> {
        val list = mutableListOf(
            "proc:$GUEST_PROC",
            "sysfs:$GUEST_SYS",
            "/dev:$GUEST_DEV",
            "$homeDir:${GuestUserHome.GUEST_PATH}",
            "$workspaceDir:${GuestCwdMapper.GUEST_WORKSPACE_ROOT}",
        )
        sharedStorageHostDir?.let { list.add("$it:/sdcard") }
        return list
    }

    companion object {
        const val ID = "linux-ubuntu-chroot"
        const val GUEST_SHELL = "/bin/bash"

        /** guest 内系统挂载点（与 SystemBindProfile.STANDARD 同集）。 */
        const val GUEST_PROC = "/proc"
        const val GUEST_SYS = "/sys"
        const val GUEST_DEV = "/dev"

        /** env trampoline 前缀长度（/usr/bin/env + -i 两 token）。 */
        private const val ENV_EXECUTABLE_PREFIX_SIZE = 2

        /**
         * guest cwd 安全白名单：字母数字与路径安全标点 —— 脚本内嵌无引号
         * 边界（GuestCwdMapper 产物恒满足；防御 request.cwd 直通绝对路径
         * 的异常输入）。
         */
        private val GUEST_CWD_SAFE = Regex("^[A-Za-z0-9._\\/-]+$")

        /** trampoline 的 env 解释器（与 PRootEnvTrampoline.ENV_EXECUTABLE 同值）。 */
        private const val ENV_EXECUTABLE = "/usr/bin/env"

        /**
         * su 的宿主 env：最小 PATH（mount/chroot/sh 都在 /system/bin；toybox
         * 与 Magisk busybox 亦然）。G4 纪律同 PRoot 后端 —— guest 变量绝不
         * 混入（guest env 全部经 trampoline）。
         */
        private val HOST_ENV: Map<String, String> = mapOf(
            "PATH" to "/system/bin:/system/xbin:/sbin:/vendor/bin",
            "ANDROID_ROOT" to "/system",
            "ANDROID_DATA" to "/data",
        )
    }
}

/**
 * POSIX 单引号包裹（shellQuote）：`'` → `'\''` 经典转义 —— 任何值（含空格、
 * 元字符、Unicode）在单引号内都是字面量。chroot 脚本内嵌路径/K=V 的统一
 * 防注入边界（对应 proot 后端的 argv 数组天然安全性 —— 脚本字符串必须
 * 显式包裹）。
 */
internal fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
