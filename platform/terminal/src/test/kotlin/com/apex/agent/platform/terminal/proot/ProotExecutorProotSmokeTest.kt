package com.apex.agent.platform.terminal.proot

import com.apex.agent.platform.terminal.linux.CpuArchitecture
import com.apex.agent.platform.terminal.linux.LinuxDistribution
import com.apex.agent.platform.terminal.linux.RootfsDescriptor
import com.apex.agent.platform.terminal.linux.RootfsProvider
import com.apex.agent.platform.terminal.linux.RootfsVerification
import com.apex.agent.platform.terminal.workspace.AbsolutePath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * P71: ProotExecutor 真实 proot 冒烟测试（host rootfs = "/"，GH runner 直跑 VM）。
 *
 * 验证链路（无 JNI，JVM ProcessBuilder）：
 *   PRootCommandBuilder（含 P71 的 -w 修正）
 *     → ProotExecutor.execute（G1 真实 pid + G4 env 白名单）
 *     → 真实 proot -r / -- /bin/sh -c …
 *
 * 自跳过（assumeTrue）：proot 未安装或 ptrace 受限的 runner。
 * CI 的 app-compile job 安装了 proot —— GH ubuntu-24.04 实测可跑
 *（PRootRuntimeIntegrationTest 同款 prootCanRun 预检）。
 *
 * 真机 forkpty→execv(proot) 全链路见 androidTest NativePtyArgvInstrumentationTest。
 */
class ProotExecutorProotSmokeTest {

    private class HostRootfsProvider : RootfsProvider {
        override suspend fun current(): RootfsDescriptor? = RootfsDescriptor(
            id = "host-root",
            distribution = LinuxDistribution.UNKNOWN,
            version = null,
            architecture = CpuArchitecture.X86_64,
            location = AbsolutePath("/"),
            sizeBytes = null,
            checksum = null,
            readOnly = false
        )

        override suspend fun verify(rootfs: RootfsDescriptor): Result<RootfsVerification> =
            Result.failure(RuntimeException("unused"))
    }

    private fun prootBinary(): File? =
        listOf("/usr/bin/proot", "/usr/local/bin/proot", "/bin/proot")
            .map { File(it) }
            .firstOrNull { it.exists() && it.canExecute() }

    /**
     * T72 修正：预检不再使用 `--` 分隔符 —— upstream proot 5.4（CI apt 版）
     * 不支持 Termux proot 5.1.107 的 `--` 扩展，P71 时代的预检因此恒 false，
     * CI 上的 SKIPPED 被误读为“ptrace 受限”（实际 ptrace 可用，语法不兼容）。
     * 现在探测真实能力：无 `--` 语法 + PROOT_NO_SECCOMP（glibc≥2.39 guest 与
     * seccomp 加速冲突；Termux proot 忽略此变量，无条件设置是安全的）。
     */
    private fun prootCanRun(): Boolean {
        val bin = prootBinary() ?: return false
        return try {
            // 最小公共语法：Ubuntu 24.04 archive 的 proot 5.1.0 连
            // --kill-on-exit 都不认 —— 预检不带任何 Termux/5.2+ 扩展。
            val pb = ProcessBuilder(bin.absolutePath, "-r", "/", "/bin/true")
                .redirectErrorStream(true)
            pb.environment()["PROOT_NO_SECCOMP"] = "1"
            val proc = pb.start()
            // 有界等待（30s）：与 UbuntuRootfsEndToEndIntegrationTest.prootWorks 同一防御 ——
            // 无限期 waitFor 在 ptrace 受限环境下挂住整个测试任务（CI 症状：无任何
            // 测试事件直至任务超时）。超时即判 proot 不可用，冒烟组诚实跳过。
            val exited = proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
            if (!exited) {
                runCatching { proc.destroyForcibly() }
                return false
            }
            proc.exitValue() == 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * T91（D5）：host proot 方言实测 —— 生产 provider 的能力探针（exec
     * `--kill-on-exit --version`，不 ptrace）直接判定，argv 构造端按方言自适应。
     * T88 时代的 adaptForHostProot 手工过滤层已删除 —— 与「把 -E 偷搬进宿主 env」
     * 同构风险：适配层改写生产 argv，CI 绿不等于设备绿。host proot 为
     * Termux 补丁版时探针返回 TERMUX_COMPAT，argv 含 --kill-on-exit/--，
     * 同样可执行（Termux 方言是上游超集）。
     */
    private fun hostCapabilities(bin: File): com.apex.agent.platform.terminal.proot.PRootArgvCapabilities {
        val env = com.apex.agent.platform.terminal.proot.PRootHostEnvironment(
            nativeLibraryDir = bin.parentFile.absolutePath,
            baseDir = File(System.getProperty("java.io.tmpdir"), "t91-smoke-dialect-base"),
            cacheDir = File(System.getProperty("java.io.tmpdir"), "t91-smoke-dialect-cache")
        )
        return com.apex.agent.platform.terminal.proot.NativeLibraryPRootBinaryProvider(env)
            .capabilitiesFor(bin)
    }

    /** 以真实探针方言执行 builder 命令（T91：argv 原样，无适配层）。 */
    private fun execAdapted(cmd: PRootCommand, bin: File): ProotExecutor.Execution {
        val argv = listOf(cmd.executable.value) + cmd.arguments
        val hostEnv = mutableMapOf<String, String>("PROOT_NO_SECCOMP" to "1")
        // 用户目录安装的 proot（非 ldconfig 注册）需要 LD_LIBRARY_PATH 解析 libtalloc ——
        // CI 的 dpkg 安装无此变量时为 no-op（与 T72 E2E 的 executorWith 一致）。
        System.getenv("LD_LIBRARY_PATH")?.let { hostEnv["LD_LIBRARY_PATH"] = it }
        val withEnv = ProotExecutor(hostEnv = { hostEnv })
        return withEnv.execute(PRootCommand(AbsolutePath(argv[0]), argv.drop(1)))
    }

    private fun buildCommand(bin: File, guestCwd: String, command: List<String>): PRootCommand {
        val builder = PRootCommandBuilderImpl()
        val launch = PRootLaunchRequest(
            rootfs = runBlocking { HostRootfsProvider().current()!! },
            executable = command.first(),
            arguments = command.drop(1),
            workingDirectory = com.apex.agent.platform.terminal.workspace.WorkspacePath(guestCwd),
            environment = mapOf("P71_SMOKE" to "ok"),
            binds = emptyList(),
            fakeRoot = true,
            killOnExit = true
        )
        // T91（D5）：按实测方言构造 argv —— 上游 proot 自动省略 --kill-on-exit/--，
        // 测试真正执行生产 argv 形状（与设备同一 builder 路径，零手工改写）。
        return builder.build(
            launch, AbsolutePath(bin.absolutePath), AbsolutePath("/"), AbsolutePath("/tmp"),
            capabilities = hostCapabilities(bin)
        )
    }

    @Test
    fun `proot exec true exits zero with real pid`() {
        val bin = prootBinary()
        assumeTrue("proot must be installed", bin != null)
        assumeTrue("proot must be runnable (ptrace)", prootCanRun())

        val exec = ProotExecutor()
        val result = execAdapted(buildCommand(bin!!, "/root", listOf("/bin/true")), bin)

        assertEquals("stderr: ${result.stderr}", 0, result.exitCode)
        assertTrue(result.pid > 0)
        assertFalse(result.timedOut)
    }

    @Test
    fun `proot echoes through rootfs with env trampoline visible in guest`() {
        val bin = prootBinary()
        assumeTrue("proot must be installed", bin != null)
        assumeTrue("proot must be runnable (ptrace)", prootCanRun())

        val exec = ProotExecutor()
        val result = execAdapted(
            buildCommand(bin!!, "/root", listOf("/bin/sh", "-c", "echo P71=\$P71_SMOKE cwd=\$(pwd)")),
            bin
        )

        assertEquals("stderr: ${result.stderr}", 0, result.exitCode)
        assertEquals("P71=ok cwd=/root", result.stdout.trim())
    }

    @Test
    fun `proot passes nonzero exit code through`() {
        val bin = prootBinary()
        assumeTrue("proot must be installed", bin != null)
        assumeTrue("proot must be runnable (ptrace)", prootCanRun())

        val exec = ProotExecutor()
        val result = execAdapted(buildCommand(bin!!, "/root", listOf("/bin/sh", "-c", "exit 42")), bin)

        assertEquals(42, result.exitCode)
    }

    @Test
    fun `proot -w fix lands in guest workspace mapping`() {
        val bin = prootBinary()
        assumeTrue("proot must be installed", bin != null)

        // 不执行 —— 仅验证 builder 输出的 -w 是 guest 路径（旧 bug：/tmp）
        val cmd = buildCommand(bin!!, "workspace:/sub", listOf("/bin/true"))
        val wIdx = cmd.arguments.indexOf("-w")
        assertTrue(wIdx >= 0)
        assertEquals("/workspace/sub", cmd.arguments[wIdx + 1])
    }

    /**
     * 启动延迟基准（PR #75 计划 §20："每次启动 PRoot 的成本"量化 —— P71 产出数据）。
     *
     * 输出 proot -r / /bin/true 的 5 次冷启动耗时 —— JVM 侧 fork+exec+ptrace
     * attach 的下界（真机数值 = 此值 + rootfs I/O + bash profile，见 androidTest 基准）。
     */
    @Test
    fun `startup latency benchmark produces data`() {
        val bin = prootBinary()
        assumeTrue("proot must be installed", bin != null)
        assumeTrue("proot must be runnable (ptrace)", prootCanRun())

        val exec = ProotExecutor()
        val samples = mutableListOf<Long>()
        repeat(5) {
            val r = execAdapted(buildCommand(bin!!, "/root", listOf("/bin/true")), bin)
            assertEquals(0, r.exitCode)
            samples.add(r.durationMs)
        }
        println("═══ P71 proot startup benchmark (host /, /bin/true, ${samples.size} runs) ═══")
        println("samples(ms): $samples")
        println("min=${samples.min()}ms avg=${samples.average().toLong()}ms max=${samples.max()}ms")
        // 断言只做 sanity（不锁具体数值 —— 硬件相关），数据进 CI 日志供汇总
        assertTrue("single cold start must complete under 10s", samples.max()!! < 10_000)
    }
}
