package com.apex.agent.platform.terminal.proot

import com.apex.agent.platform.terminal.linux.CpuArchitecture
import com.apex.agent.platform.terminal.linux.LinuxDistribution
import com.apex.agent.platform.terminal.linux.RootfsDescriptor
import com.apex.agent.platform.terminal.linux.RootfsProvider
import com.apex.agent.platform.terminal.linux.RootfsVerification
import com.apex.agent.platform.terminal.runtime.SessionSpawnRequest
import com.apex.agent.platform.terminal.workspace.AbsolutePath
import com.apex.agent.platform.terminal.workspace.GuestUserHome
import com.apex.agent.platform.terminal.workspace.LinuxWorkspaceManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** 共享夹具：静态 rootfs 描述（外层类与嵌套 provider 均可用）。 */
private fun capabilitiesRootfs() = RootfsDescriptor(
    id = "ubuntu-24.04-arm64", distribution = LinuxDistribution.UBUNTU,
    version = "24.04", architecture = CpuArchitecture.ARM64,
    location = AbsolutePath("/fake/rootfs"), sizeBytes = null, checksum = null, readOnly = false
)

/**
 * T91（D5）：PRoot argv 能力自适应契约测试 ——「双探针参与 argv 决策，根除静默差异」。
 *
 * ## 关键回归锁（本套件的诞生动机）
 *
 * 首版实现把两个 Termux 补丁选项耦合在单一方言枚举里（killOnExit ⇒ separator），
 * CI 实测 Debian proot 5.4.0 打脸：**它支持 `--kill-on-exit`（上游已采纳）但
 * 仍不支持 `--`** —— 正交能力必须独立探针、独立决策：
 *
 * | 二进制 | killOnExit | separator | argv 形状 |
 * |---|---|---|---|
 * | 捆绑 Termux 5.1.107.92 | ✅ | ✅ | 两选项全发（设备生产，逐字节不变） |
 * | 上游 5.1.0 | ❌ | ❌ | 两选项皆省 |
 * | Debian 5.4.0 | ✅ | ❌ | **只发 --kill-on-exit**（混合形态） |
 *
 * 覆盖四层：
 *  1. **双探针 → 能力集映射**（[NativeLibraryPRootBinaryProvider.capabilitiesFor]）：
 *     每项三态（true/false/null → 发/省/保守省）+ 混合组合 + 记忆化（每项探针只
 *     exec 一次）；
 *  2. **能力集 → argv 形状**（[PRootCommandBuilderImpl]）：四组合逐一 golden；
 *  3. **契约多形状**（[PRootArgvContract]）：黑名单扫描边界与 trampoline 判定
 *     在有/无 `--` 两种形状下都正确；
 *  4. **端到端**（[LinuxPRootBackend.prepare]）：provider verify 结果的能力集
 *     真实参与 argv（此前只作门禁被丢弃 —— 静默差异的直接根源）。
 *
 * 真实 proot 执行（上游/混合形状 argv 可跑）见 ProotExecutorProotSmokeTest /
 * UbuntuRootfsEndToEndIntegrationTest / UbuntuTerminalRuntimeWiringTest
 * （T91 起三者的测试内适配层已删除，生产 argv 原样执行）。
 */
class PRootArgvCapabilitiesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun launchRequest(killOnExit: Boolean = true) = PRootLaunchRequest(
        rootfs = capabilitiesRootfs(), executable = "/bin/bash", arguments = listOf("-i"),
        environment = linkedMapOf("TERM" to "xterm-256color", "PATH" to "/usr/bin:/bin"),
        fakeRoot = true, killOnExit = killOnExit
    )

    // ─── 1. 双探针 → 能力集映射（三态 × 正交组合 + 记忆化） ───

    private fun elfDir(): File = tmp.newFolder().apply {
        // 真实 Termux arm64 libproot.so 前 20 字节（ELF header，e_machine=183）
        File(this, "libproot.so").writeBytes(
            byteArrayOf(0x7f, 0x45, 0x4c, 0x46, 0x02, 0x01, 0x01, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x02, 0x00, 0xB7.toByte(), 0x00)
        )
    }

    private fun hostEnvAt(dir: File): PRootHostEnvironment =
        PRootHostEnvironment(dir.absolutePath, tmp.newFolder(), tmp.newFolder())

    private fun providerAt(
        dir: File,
        killOnExit: (File) -> Boolean?,
        separator: (File) -> Boolean?
    ) = NativeLibraryPRootBinaryProvider(
        hostEnv = hostEnvAt(dir),
        supportedAbis = { listOf("arm64-v8a") },
        versionProbe = { "proot version: 5.1.107.92" },
        killOnExitProbe = killOnExit,
        separatorProbe = separator
    )

    @Test
    fun `bundled Termux profile maps to full capabilities`() = runBlocking {
        val dir = elfDir()
        val provider = providerAt(dir, killOnExit = { true }, separator = { true })
        val info = provider.verify(provider.locate().getOrThrow()).getOrThrow()
        assertEquals(PRootArgvCapabilities.TERMUX_BUNDLED, info.capabilities)
        assertEquals(PRootVersion(5, 1, 107), info.version)
    }

    @Test
    fun `upstream 5_1_0 profile maps to both-off capabilities`() = runBlocking {
        val dir = elfDir()
        val provider = providerAt(dir, killOnExit = { false }, separator = { false })
        val info = provider.verify(provider.locate().getOrThrow()).getOrThrow()
        assertEquals(PRootArgvCapabilities.UPSTREAM_SAFE, info.capabilities)
    }

    @Test
    fun `Debian 5_4 hybrid profile keeps kill-on-exit but drops separator`() = runBlocking {
        // ★ 首版方言枚举翻车的实测形态：Debian 5.4.0 支持 --kill-on-exit
        // （上游已采纳）但不支持 `--`。混合能力必须逐项落位。
        val dir = elfDir()
        val provider = providerAt(dir, killOnExit = { true }, separator = { false })
        val info = provider.verify(provider.locate().getOrThrow()).getOrThrow()
        assertTrue("kill-on-exit supported on Debian 5.4", info.capabilities.supportsKillOnExit)
        assertFalse("separator NOT supported on Debian 5.4", info.capabilities.supportsOptionSeparator)
    }

    @Test
    fun `indeterminate probes conservatively omit both options`() = runBlocking {
        // 探针 exec 异常（fork 失败等）→ null → 保守省略（省略在任何 proot 上
        // 均合法：guest 命令恒以非选项 token /usr/bin/env 开头，天然分界）
        val dir = elfDir()
        val provider = providerAt(dir, killOnExit = { null }, separator = { null })
        val info = provider.verify(provider.locate().getOrThrow()).getOrThrow()
        assertEquals(PRootArgvCapabilities.UPSTREAM_SAFE, info.capabilities)
        assertNull(info.version.takeIf { it != PRootVersion(5, 1, 107) }) // versionProbe 正常给出
    }

    @Test
    fun `capability probes are memoized per binary (no exec storm across verify calls)`() = runBlocking {
        val dir = elfDir()
        val killCalls = AtomicInteger(0)
        val sepCalls = AtomicInteger(0)
        val provider = providerAt(
            dir,
            killOnExit = { killCalls.incrementAndGet(); true },
            separator = { sepCalls.incrementAndGet(); false }
        )
        val binary = provider.locate().getOrThrow()
        repeat(5) { provider.verify(binary).getOrThrow() }
        // provider 是 DI 单例；availability + prepare 每会话都 verify ——
        // 每项探针必须只 exec 一次（记忆化），否则每次 spawn 多两次子进程。
        assertEquals("kill-on-exit probe must be memoized", 1, killCalls.get())
        assertEquals("separator probe must be memoized", 1, sepCalls.get())
        val info = provider.verify(binary).getOrThrow()
        assertTrue(info.capabilities.supportsKillOnExit)
        assertFalse(info.capabilities.supportsOptionSeparator)
    }

    // ─── 2. 能力集 → argv 形状（builder golden，四组合逐一） ───

    private fun buildArgv(capabilities: PRootArgvCapabilities, killOnExit: Boolean = true): List<String> {
        val cmd = PRootCommandBuilderImpl().build(
            launchRequest(killOnExit), AbsolutePath("/p"), AbsolutePath("/r"), AbsolutePath("/w"),
            capabilities = capabilities
        )
        return listOf(cmd.executable.value) + cmd.arguments
    }

    @Test
    fun `full capabilities keep both kill-on-exit and option separator`() {
        val argv = buildArgv(PRootArgvCapabilities.TERMUX_BUNDLED)
        assertTrue("kill-on-exit present", argv.contains("--kill-on-exit"))
        assertEquals("separator right before env trampoline", "--", argv[argv.indexOf("/usr/bin/env") - 1])
        assertTrue(PRootArgvContract.hasEnvTrampoline(argv))
        assertTrue(PRootArgvContract.legacyIncompatibleFlags(argv).isEmpty())
    }

    @Test
    fun `upstream safe capabilities omit both but keep env trampoline`() {
        val argv = buildArgv(PRootArgvCapabilities.UPSTREAM_SAFE)
        assertFalse("no kill-on-exit (upstream 5.1.0 rejects it)", argv.contains("--kill-on-exit"))
        assertFalse("no -- separator (upstream rejects it)", argv.contains("--"))
        // env trampoline 原样保留：guest env 注入语义与能力无关（T88 trampoline
        // 在上游 proot 上原样合法 —— 这正是 E2E 无需适配 env 的原因）
        val envIdx = argv.indexOf("/usr/bin/env")
        assertTrue("env trampoline present", envIdx > 0)
        assertEquals("-i", argv[envIdx + 1])
        assertEquals("TERM=xterm-256color", argv[envIdx + 2])
        assertEquals("PATH=/usr/bin:/bin", argv[envIdx + 3])
        assertEquals(listOf("/bin/bash", "-i"), argv.takeLast(2))
        assertTrue(PRootArgvContract.hasEnvTrampoline(argv))
    }

    @Test
    fun `Debian 5_4 hybrid argv keeps kill-on-exit and omits separator`() {
        // ★ CI 实测翻车形态的 builder 锁：Debian 5.4 只认 --kill-on-exit。
        // 旧适配层会把 --kill-on-exit 也滤掉（语义损失），新构造器只省 `--`。
        val argv = buildArgv(PRootArgvCapabilities(supportsKillOnExit = true, supportsOptionSeparator = false))
        assertTrue("kill-on-exit kept on Debian 5.4", argv.contains("--kill-on-exit"))
        assertFalse("separator omitted on Debian 5.4", argv.contains("--"))
        val envIdx = argv.indexOf("/usr/bin/env")
        assertTrue("env trampoline directly after options", envIdx > 0)
        assertTrue(PRootArgvContract.hasEnvTrampoline(argv))
    }

    @Test
    fun `killOnExit=false never emits the flag on any capability profile`() {
        for (caps in listOf(
            PRootArgvCapabilities.TERMUX_BUNDLED,
            PRootArgvCapabilities.UPSTREAM_SAFE,
            PRootArgvCapabilities(true, false)
        )) {
            assertFalse(
                "caps=$caps: request.killOnExit=false must win",
                buildArgv(caps, killOnExit = false).contains("--kill-on-exit")
            )
        }
    }

    // ─── 3. 契约多形状（PRootArgvContract） ───

    @Test
    fun `contract accepts both shapes and rejects malformed argv`() {
        val withSeparator = listOf(
            "/proot", "-r", "/rootfs", "-0", "--kill-on-exit", "-b", "/ws:/workspace",
            "--", "/usr/bin/env", "-i", "TERM=xterm", "/bin/bash", "-i"
        )
        val withoutSeparator = listOf(
            "/proot", "-r", "/rootfs", "-0", "--kill-on-exit", "-b", "/ws:/workspace",
            "/usr/bin/env", "-i", "TERM=xterm", "/bin/bash", "-i"
        )
        for (argv in listOf(withSeparator, withoutSeparator)) {
            assertTrue("trampoline recognized: $argv", PRootArgvContract.hasEnvTrampoline(argv))
            assertTrue("no legacy flags: $argv", PRootArgvContract.legacyIncompatibleFlags(argv).isEmpty())
        }
        // 违规形状：无 trampoline / 黑名单 flag 命中
        assertFalse(PRootArgvContract.hasEnvTrampoline(listOf("/proot", "-r", "/rootfs", "/bin/bash")))
        assertEquals(
            listOf("-E"),
            PRootArgvContract.legacyIncompatibleFlags(
                listOf("/proot", "-r", "/rootfs", "-E", "KEY=V", "/usr/bin/env", "-i", "/bin/bash")
            )
        )
        // 无 `--` 形状的黑名单扫描边界：guest 命令段不被扫描（env trampoline 后止）
        assertEquals(
            "blacklist scan stops at env trampoline on separator-less shape",
            emptyList<String>(),
            PRootArgvContract.legacyIncompatibleFlags(withoutSeparator)
        )
    }

    // ─── 4. 端到端：backend prepare 消费 verify 能力集 ───

    private class CapabilitiesProvider(private val capabilities: PRootArgvCapabilities) : PRootBinaryProvider {
        override suspend fun locate(): Result<AbsolutePath> = Result.success(AbsolutePath("/fake/libproot.so"))
        override suspend fun verify(binary: AbsolutePath): Result<PRootBinaryInfo> = Result.success(
            PRootBinaryInfo(binary, PRootVersion(5, 1, 107), CpuArchitecture.ARM64, true, capabilities)
        )
    }

    private class StaticRootfsProvider : RootfsProvider {
        override suspend fun current(): RootfsDescriptor = capabilitiesRootfs()
        override suspend fun verify(rootfs: RootfsDescriptor): Result<RootfsVerification> =
            Result.failure(RuntimeException("unused"))
    }

    @Test
    fun `backend prepare routes verified capabilities into spawn argv`() = runBlocking {
        for (caps in listOf(
            PRootArgvCapabilities.TERMUX_BUNDLED,
            PRootArgvCapabilities.UPSTREAM_SAFE,
            PRootArgvCapabilities(supportsKillOnExit = true, supportsOptionSeparator = false)
        )) {
            val backend = LinuxPRootBackend(
                binaryProvider = CapabilitiesProvider(caps),
                rootfsProvider = StaticRootfsProvider(),
                workspaces = LinuxWorkspaceManager(File(tmp.root, "ws-${caps.supportsKillOnExit}-${caps.supportsOptionSeparator}")),
                userHome = GuestUserHome(File(tmp.root, "home-${caps.supportsKillOnExit}-${caps.supportsOptionSeparator}")),
                systemBinds = SystemBindProfile.NONE,
                hostEnv = null
            )
            val spec = backend.prepare(SessionSpawnRequest(cwd = "", rows = 24, cols = 80)).getOrThrow()
            val argv = spec.argv
            assertEquals(
                "caps=$caps: --kill-on-exit 由探针实测决定（不再盲发）",
                caps.supportsKillOnExit, argv.contains("--kill-on-exit")
            )
            assertEquals(
                "caps=$caps: -- 终结符由探针实测决定",
                caps.supportsOptionSeparator, argv.contains("--")
            )
            assertTrue("env trampoline intact", PRootArgvContract.hasEnvTrampoline(argv))
        }
    }

    // ─── 5. T92：默认探针前置 hostEnv.prepare（首启不静默降级） ───

    /**
     * 脚本化假 proot：exit 0（模拟「选项被接受」）。真实 exec 走 JVM 沙箱
     * 的 /bin/sh —— 与 ProotExecutorProotSmokeTest 同源的环境假设（无 /bin/sh
     * 的环境 Assume 跳过，诚实降级）。
     */
    private fun scriptProot(dir: File): File = File(dir, "libproot.so").apply {
        writeText("#!/bin/sh\nexit 0\n")
        setExecutable(true, false)
    }

    /** 真实占位 libtalloc（File.exists() 跟随符号链接 —— 悬空链接会误报 false）。 */
    private fun dummyTalloc(dir: File): File = File(dir, "libtalloc.so").apply {
        writeBytes(byteArrayOf(0x74, 0x61, 0x6c, 0x6c)) // "tall"
    }

    @Test
    fun `T92 default probes run hostEnv prepare before exec (first-boot no silent downgrade)`() {
        org.junit.Assume.assumeTrue("需要 /bin/sh 的 JVM 沙箱", File("/bin/sh").exists())
        val dir = tmp.newFolder().apply {
            scriptProot(this)
            dummyTalloc(this) // 悬空符号链接 exists()=false —— 需真实目标文件
        }
        val hostEnv = PRootHostEnvironment(dir.absolutePath, tmp.newFolder(), tmp.newFolder())
        // ★ 断言基线：探针执行前 staging 目录不存在（模拟首次安装/清缓存后
        // 的真实状态 —— libtalloc.so.2 symlink 与 PROOT_TMP_DIR 均未建）
        assertFalse("前置：staging 尚未建立", File(hostEnv.stagingDir, "libtalloc.so.2").exists())

        val provider = NativeLibraryPRootBinaryProvider(
            hostEnv = hostEnv,
            supportedAbis = { listOf("arm64-v8a") }
            // 三探针均用默认真实 exec 实现（不注入 fake —— 测的就是默认路径）
        )
        // capabilitiesFor 是 verify 内部的能力入口（跳过 ELF 字节级校验 ——
        // 脚本文件不是合法 ELF，但探针前置逻辑与此无关）
        val caps = provider.capabilitiesFor(File(dir, "libproot.so"))

        // 探针 exec 前 prepare 幂等执行：staging symlink 与 tmp 目录已建
        assertTrue(
            "探针必须先 prepare（否则首启 exec 因链接缺失确定性非零退出 → 能力被永久记忆化为 false）",
            File(hostEnv.stagingDir, "libtalloc.so.2").exists()
        )
        assertTrue(hostEnv.prootTmpDir.isDirectory)
        // 脚本 exit 0 → 两项能力均实测为支持
        assertTrue("exit-0 假 proot → kill-on-exit 判支持", caps.supportsKillOnExit)
        assertTrue("exit-0 假 proot → separator 判支持", caps.supportsOptionSeparator)
    }

    @Test
    fun `T92 unpreparable host env yields indeterminate probes not false`() {
        // prepare 失败（nativeDir 无 libproot.so → canExecute=false）→ 默认探针
        // 不 exec、返回 null → 保守省略（而非「不支持」）—— 与「exec 链接失败
        // 被误认 unknown option → false」的旧缺陷分界。用默认探针（注入 fake
        // 会绕过 probeEnv，测不到前置逻辑）。
        val emptyDir = tmp.newFolder() // 无 libproot.so → prepare 必失败
        val hostEnv = PRootHostEnvironment(emptyDir.absolutePath, tmp.newFolder(), tmp.newFolder())
        val provider = NativeLibraryPRootBinaryProvider(
            hostEnv = hostEnv,
            supportedAbis = { emptyList() }
            // killOnExit/separator 探针均用默认实现 —— prepare 失败时绝不 exec
        )
        // capabilitiesFor 的入参：一个「可执行且必 exit 0」的脚本 —— 若探针
        // 在 prepare 失败后仍违规 exec，它会 exit 0 → 能力判 true（≠ 保守
        // 基线）→ 断言失败（回归可检出）；Fix A 生效时探针根本不执行。
        org.junit.Assume.assumeTrue("需要 /bin/sh 的 JVM 沙箱", File("/bin/sh").exists())
        val trapScript = File(tmp.root, "trap-proot.sh").apply {
            writeText("#!/bin/sh\nexit 0\n")
            setExecutable(true, false)
        }
        val caps = provider.capabilitiesFor(trapScript)
        assertEquals("prepare 失败 → 不可判定 → 保守省略", PRootArgvCapabilities.UPSTREAM_SAFE, caps)
    }
}
