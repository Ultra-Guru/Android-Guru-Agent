package com.apex.agent.platform.terminal.chroot

import com.apex.agent.platform.terminal.linux.CpuArchitecture
import com.apex.agent.platform.terminal.linux.LinuxDistribution
import com.apex.agent.platform.terminal.linux.RootfsDescriptor
import com.apex.agent.platform.terminal.linux.RootfsProvider
import com.apex.agent.platform.terminal.linux.RootfsVerification
import com.apex.agent.platform.terminal.proot.SharedStorageBridge
import com.apex.agent.platform.terminal.runtime.BackendAvailability
import com.apex.agent.platform.terminal.runtime.SessionSpawnRequest
import com.apex.agent.platform.terminal.workspace.AbsolutePath
import com.apex.agent.platform.terminal.workspace.GuestCwdMapper
import com.apex.agent.platform.terminal.workspace.GuestUserHome
import com.apex.agent.platform.terminal.workspace.LinuxWorkspaceManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * T98: LinuxChrootBackend 单元测试 —— argv 脚本契约、availability 三态、
 * cwd 映射（GuestCwdMapper 单一事实源）、shellQuote 防注入边界。
 *
 * 全部纯 JVM（fake su / fake rootfs provider；绝不 exec su —— 授权弹窗
 * 副作用见 SuBinaryLocator KDoc）。真实 su + mount + chroot 的端到端验证
 * 依赖真机 Root 环境，属 androidTest / 手工验收范围（见
 * docs/terminal/TERMINAL_CHROOT_BACKEND_T98.md 验收矩阵）。
 */
class LinuxChrootBackendTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ─── fakes（无 mock 框架 —— 手写 fake，仓库测试纪律） ───

    private class FakeRootfsProvider(private val rootfsPath: String? = "/fake/rootfs") : RootfsProvider {
        override suspend fun current(): RootfsDescriptor? =
            rootfsPath?.let {
                RootfsDescriptor(
                    id = "ubuntu-24.04",
                    distribution = LinuxDistribution.UBUNTU,
                    version = "24.04",
                    architecture = CpuArchitecture.ARM64,
                    location = AbsolutePath(it),
                    sizeBytes = null,
                    checksum = null,
                    readOnly = false
                )
            }

        override suspend fun verify(rootfs: RootfsDescriptor): Result<RootfsVerification> =
            Result.failure(RuntimeException("unused"))
    }

    private class NullRootfsProvider : RootfsProvider {
        override suspend fun current(): RootfsDescriptor? = null
        override suspend fun verify(rootfs: RootfsDescriptor): Result<RootfsVerification> =
            Result.failure(RuntimeException("unused"))
    }

    /**
     * tmp 下造一个可执行假 su（locate 只 stat 不 exec —— 授权语义留给真机）。
     * 幂等：懒 lambda 在每次 prepare/availability 都会执行，同名单次创建后
     * 复用（TemporaryFolder.newFile 的重名即抛，不能用它）。
     */
    private fun fakeSu(name: String = "su", executable: Boolean = true): File =
        File(tmp.root, name).apply {
            if (!exists()) writeText("")
            setExecutable(executable)
        }

    private fun backend(
        suLocator: SuBinaryLocator = SuBinaryLocator { listOf(fakeSu()) },
        rootfsProvider: RootfsProvider = FakeRootfsProvider(),
        workspaceRoot: File = File(tmp.root, "workspaces"),
        homeRoot: File = File(tmp.root, "home"),
        sharedStorage: SharedStorageBridge? = null
    ) = LinuxChrootBackend(
        suLocator = suLocator,
        rootfsProvider = rootfsProvider,
        workspaces = LinuxWorkspaceManager(workspaceRoot),
        userHome = GuestUserHome(homeRoot),
        sharedStorage = sharedStorage
    )

    // ─── SuBinaryLocator ───

    @Test
    fun `su locator picks first existing executable candidate`() {
        val a = tmp.newFile("a-su")            // 存在但不可执行
        val b = tmp.newFile("b-su").apply { setExecutable(true) }
        val c = tmp.newFile("c-su").apply { setExecutable(true) }
        val loc = SuBinaryLocator { listOf(a, b, c) }
        assertEquals(b.absolutePath, loc.locate()!!.absolutePath)
    }

    @Test
    fun `su locator returns null when nothing executable exists`() {
        val missing = File(tmp.root, "nope-su")
        val notExec = tmp.newFile("plain-su")
        val loc = SuBinaryLocator { listOf(missing, notExec) }
        assertEquals(null, loc.locate())
    }

    @Test
    fun `su locator default candidates cover mainstream root schemes`() {
        // 契约锁：默认候选必须含 Magisk/KernelSU 标准落点（顺序敏感 —— 探测序即优先级）。
        assertEquals("/system/bin/su", SuBinaryLocator.DEFAULT_CANDIDATES.first())
        assertTrue(SuBinaryLocator.DEFAULT_CANDIDATES.contains("/system/xbin/su"))
    }

    // ─── availability 三态 ───

    @Test
    fun `availability no su is Failed with SU_NOT_FOUND`() = runBlocking {
        val b = backend(suLocator = SuBinaryLocator { emptyList() })
        val av = b.availability()
        assertTrue(av is BackendAvailability.Failed)
        assertTrue((av as BackendAvailability.Failed).reason.contains("ChrootError:SU_NOT_FOUND"))
    }

    @Test
    fun `availability su present but no rootfs is NeedsRootfs`() = runBlocking {
        val b = backend(rootfsProvider = NullRootfsProvider())
        val av = b.availability()
        assertTrue("expected NeedsRootfs, got $av", av is BackendAvailability.NeedsRootfs)
    }

    @Test
    fun `availability su plus rootfs is Ready`() = runBlocking {
        val b = backend()
        assertTrue(b.availability() is BackendAvailability.Ready)
    }

    // ─── prepare → SpawnSpec argv 契约 ───

    @Test
    fun `prepare argv is su -c script with idempotent mounts and chroot trampoline`() = runBlocking {
        val wsRoot = File(tmp.root, "ws")
        val homeRoot = File(tmp.root, "home")
        val su = fakeSu("su-prod")
        val b = backend(
            suLocator = SuBinaryLocator { listOf(su) },
            workspaceRoot = wsRoot,
            homeRoot = homeRoot
        )
        val spec = b.prepare(SessionSpawnRequest(cwd = "", rows = 24, cols = 80)).getOrThrow()

        // argv 形状：su -c <script>
        assertEquals(3, spec.argv.size)
        assertEquals(su.absolutePath, spec.argv[0])
        assertEquals("-c", spec.argv[1])
        val script = spec.argv[2]

        // 脚本头部：R=<rootfs>（单引号包裹）
        val rootfsLine = script.lineSequence().first()
        assertEquals("R='/fake/rootfs'", rootfsLine)
        val lines = script.lineSequence().toList()

        // T99：挂载表精确守卫与跨会话锁；无 mountpoint 也不能堆叠。
        assertTrue(script.contains("/proc/self/mountinfo"))
        assertTrue(script.contains("flock -s 9"))
        assertTrue(script.contains("flock -x 8"))
        assertTrue(script.contains(ChrootMountScript.mount("proc", "mount", "-t", "proc", "proc")))
        assertTrue(script.contains(ChrootMountScript.mount("sys", "mount", "-t", "sysfs", "sys")))
        assertTrue(script.contains(ChrootMountScript.mount("dev", "mount", "--bind", "/dev")))

        // home → /root 与 workspace → /workspace 的 bind（mkdir 先行 —— rootfs 内无该目录）
        assertTrue(lines.any { it == ChrootMountScript.mount("root", "mount", "--bind", homeRoot.absolutePath) })
        assertTrue(lines.any { it == "mkdir -p \"\$R/workspace\" || exit 1" })
        assertTrue(lines.any { it == ChrootMountScript.mount("workspace", "mount", "--bind", File(wsRoot, "default").absolutePath) })

        // chroot 行：env trampoline（与 PRoot 后端同源）+ cd guest cwd + exec bash -i
        val chrootLine = lines.first { it.startsWith("chroot ") }
        assertTrue(chrootLine.contains("chroot \"\$R\" '/usr/bin/env' -i"))
        // guest env 基线（LinuxEnvironmentManager.interactiveGuestEnv）必含 TERM/HOME/PATH
        assertTrue(chrootLine.contains("'TERM=xterm-256color'"))
        assertTrue(chrootLine.contains("'HOME=/root'"))
        assertTrue(chrootLine.contains("'PATH="))
        assertTrue(chrootLine.contains("/bin/sh -c 'cd /workspace && exec /bin/bash -i'"))

        assertTrue(chrootLine.endsWith("8>&- 9>&-"))
        assertTrue(script.contains("cleanup_one workspace\n"))
        assertTrue(script.contains("cleanup_one root\n"))
        assertTrue(script.contains("cleanup_one dev\n"))
        assertTrue(script.contains("cleanup_one sys\n"))
        assertTrue(script.contains("cleanup_one proc\n"))
        // New descriptor: unlocking the guest's inherited fd 9 would release its lock.
        assertTrue(script.contains("exec 9>&-"))
        assertTrue(script.contains("exec 7>>\"\$STATE/users.lock\""))
        assertTrue(script.contains("flock -n -x 7"))

        // SpawnSpec 展示语义（与 PRoot 后端同口径）
        assertEquals("/bin/bash", spec.shellDisplay)
        assertEquals("/workspace", spec.cwdDisplay)
        assertTrue(spec.cwdIsGuestPath)
        assertEquals("linux-ubuntu-chroot", spec.metadata.backendId)
        assertEquals("ubuntu-24.04", spec.metadata.rootfsId)
        assertEquals("default", spec.metadata.workspaceId)
        assertTrue(spec.metadata.binds.any { it.endsWith(":${GuestUserHome.GUEST_PATH}") })
        assertTrue(spec.metadata.binds.any { it.endsWith(":${GuestCwdMapper.GUEST_WORKSPACE_ROOT}") })
    }

    @Test
    fun `prepare maps relative cwd into workspace namespace and absolute passthrough`() = runBlocking {
        val b = backend()
        val rel = b.prepare(SessionSpawnRequest(cwd = "src/main", rows = 24, cols = 80)).getOrThrow()
        assertEquals("/workspace/src/main", rel.cwdDisplay)
        assertTrue(rel.argv[2].contains("cd /workspace/src/main && exec /bin/bash -i"))

        val abs = b.prepare(SessionSpawnRequest(cwd = "/root", rows = 24, cols = 80)).getOrThrow()
        assertEquals("/root", abs.cwdDisplay)
        // "/sdcard"（旧 LOCAL 默认 cwd）与 "/"、空串同语义 → /workspace（GuestCwdMapper）
        val sd = b.prepare(SessionSpawnRequest(cwd = "/sdcard", rows = 24, cols = 80)).getOrThrow()
        assertEquals("/workspace", sd.cwdDisplay)
    }

    @Test
    fun `prepare rejects shell-metacharacter cwd honestly`() = runBlocking {
        val b = backend()
        // 直通绝对路径含引号（注入探测）—— GUEST_CWD_SAFE 白名单拒绝
        val r = b.prepare(SessionSpawnRequest(cwd = "/'; rm -rf /", rows = 24, cols = 80))
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("ChrootError:InvalidCwd"))
    }

    @Test
    fun `prepare includes sdcard bind only when shared storage available`() = runBlocking {
        val shared = tmp.newFolder("sdcard-host")
        val b = backend(
            sharedStorage = SharedStorageBridge(hostDirProvider = { shared })
        )
        val spec = b.prepare(SessionSpawnRequest(cwd = "", rows = 24, cols = 80)).getOrThrow()
        val script = spec.argv[2]
        assertTrue(script.contains(ChrootMountScript.mount("sdcard", "mount", "--bind", shared.absolutePath)))
        assertTrue(script.contains("cleanup_one sdcard"))
        assertTrue(spec.metadata.binds.any { it == "${shared.absolutePath}:/sdcard" })

        // 不可用（hostDirProvider 返回 null）→ 无 sdcard 行（诚实降级，不伪造）
        val b2 = backend(
            sharedStorage = SharedStorageBridge(hostDirProvider = { null })
        )
        val spec2 = b2.prepare(SessionSpawnRequest(cwd = "", rows = 24, cols = 80)).getOrThrow()
        assertFalse(spec2.argv[2].contains("ensure_mount 'sdcard'"))
        assertFalse(spec2.metadata.binds.any { it.endsWith(":/sdcard") })
    }

    @Test
    fun `prepare fails honestly without rootfs`() = runBlocking {
        val b = backend(rootfsProvider = NullRootfsProvider())
        val r = b.prepare(SessionSpawnRequest(cwd = "", rows = 24, cols = 80))
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("RootfsNotReady"))
    }

    @Test
    fun `prepare fails honestly when su disappears between availability and prepare`() = runBlocking {
        // 并发卸载窗口：availability Ready 之后 su 被删 —— prepare 诚实失败不伪造
        val suFile = tmp.newFile("vanishing-su").apply { setExecutable(true) }
        val b = backend(suLocator = SuBinaryLocator { listOf(suFile) })
        assertTrue(b.availability() is BackendAvailability.Ready)
        assertTrue(suFile.delete())
        val r = b.prepare(SessionSpawnRequest(cwd = "", rows = 24, cols = 80))
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("ChrootError:SU_NOT_FOUND"))
    }

    @Test
    fun `prepare seeds persistent home once and shares it with proot backend semantics`() = runBlocking {
        // T75 语义：home 目录随首次 prepare 创建（GuestUserHome.ensureReady 幂等播种）
        val homeRoot = File(tmp.root, "home")
        val b = backend(homeRoot = homeRoot)
        b.prepare(SessionSpawnRequest(cwd = "", rows = 24, cols = 80)).getOrThrow()
        b.prepare(SessionSpawnRequest(cwd = "", rows = 24, cols = 80)).getOrThrow()  // 幂等
        assertTrue("persistent home created", homeRoot.isDirectory)
        assertNotNull("home seeded with bashrc", homeRoot.listFiles()?.firstOrNull { it.name == ".bashrc" })
    }

    @Test
    fun `mounts use a shared lock and exact mount table guard instead of stacking when mountpoint is absent`() = runBlocking {
        val script = backend().prepare(SessionSpawnRequest(cwd = "", rows = 24, cols = 80))
            .getOrThrow().argv[2]
        assertTrue("mount operations must be serialized across sessions", script.contains("flock -x"))
        assertTrue("mount identity must be checked without mountpoint", script.contains("/proc/self/mountinfo"))
        assertFalse("mountpoint-only guard stacks on stripped ROMs", script.contains("mountpoint -q"))
        assertTrue("cleanup must also run after interrupted guest", script.contains("trap 'cleanup_mounts' EXIT"))
    }

    // ─── shellQuote 防注入边界 ───

    @Test
    fun `shellQuote wraps plain values and escapes embedded quotes`() {
        assertEquals("'/fake/rootfs'", shellQuote("/fake/rootfs"))
        // 内嵌单引号 → '\'' 经典转义（POSIX 字面量拼接）
        assertEquals("'/a'\\''; rm -rf /'", shellQuote("/a'; rm -rf /"))
        // 空串与空格串都是合法字面量
        assertEquals("''", shellQuote(""))
        assertEquals("'a b  c'", shellQuote("a b  c"))
    }

    @Test
    fun `env values with spaces are single-quoted tokens in chroot line`() = runBlocking {
        val b = backend()
        val spec = b.prepare(
            SessionSpawnRequest(cwd = "", rows = 24, cols = 80, env = mapOf("PROMPT_COMMAND" to "echo hi there"))
        ).getOrThrow()
        val chrootLine = spec.argv[2].lineSequence().first { it.startsWith("chroot ") }
        assertTrue("space-bearing value is quoted as one token", chrootLine.contains("'PROMPT_COMMAND=echo hi there'"))
    }

    // ─── GuestCwdMapper（单一事实源 —— 与 PRoot 后端逐字节同映射） ───

    @Test
    fun `guest cwd mapper contract matches proot backend semantics`() {
        assertEquals("/workspace", GuestCwdMapper.map(""))
        assertEquals("/workspace", GuestCwdMapper.map("  "))
        assertEquals("/workspace", GuestCwdMapper.map("/"))
        assertEquals("/workspace", GuestCwdMapper.map("/sdcard"))
        assertEquals("/root", GuestCwdMapper.map("/root"))
        assertEquals("/workspace/src/main", GuestCwdMapper.map("src/main"))
        assertEquals("/workspace/a b", GuestCwdMapper.map("a b"))
    }
}
