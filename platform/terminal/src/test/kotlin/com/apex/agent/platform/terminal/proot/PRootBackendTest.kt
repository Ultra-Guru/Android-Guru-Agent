package com.apex.agent.platform.terminal.proot

import com.apex.agent.platform.terminal.linux.CpuArchitecture
import com.apex.agent.platform.terminal.linux.LinuxDistribution
import com.apex.agent.platform.terminal.linux.RootfsDescriptor
import com.apex.agent.platform.terminal.linux.RootfsState
import com.apex.agent.platform.terminal.workspace.AbsolutePath
import com.apex.agent.platform.terminal.workspace.WorkspacePath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * P68 共享组件测试（T73 收编后仅覆盖 P71 LinuxPRootBackend 仍在使用的活代码：
 * PRootCommandBuilder / RootfsValidation 类型。旧 PRootRuntime / MountPlanner /
 * EnvironmentBuilder / 错误模型已随 P68 运行时栈删除 —— 其职责由 P71
 * LinuxPRootBackendTest + ProotExecutorTest 承接）。
 */
class PRootCommandBuilderTest {
    private fun rootfs() = RootfsDescriptor(
        id = "ubuntu-24.04-arm64", distribution = LinuxDistribution.UBUNTU,
        version = "24.04", architecture = CpuArchitecture.ARM64,
        location = AbsolutePath("/data/rootfs/ubuntu/versions/v1"),
        sizeBytes = 123L, checksum = "abc", readOnly = false
    )

    @Test fun `builds PRoot command with rootfs and executable`() {
        val cmd = PRootCommandBuilderImpl().build(
            PRootLaunchRequest(rootfs(), "/bin/bash", listOf("-i")),
            prootBinary = AbsolutePath("/lib/libproot.so"),
            rootfsPath = AbsolutePath("/data/rootfs/v1"),
            workspacePath = AbsolutePath("/data/ws")
        )
        assertEquals("/lib/libproot.so", cmd.executable.value)
        assertEquals("-r", cmd.arguments[0])
        assertEquals("/data/rootfs/v1", cmd.arguments[1])
        // T88: guest 命令在 "--" 之后，且以 env trampoline 开头（proot 5.1.107 兼容）
        val dd = cmd.arguments.indexOf("--")
        assertTrue(dd > 0)
        assertEquals(PRootEnvTrampoline.ENV_EXECUTABLE, cmd.arguments[dd + 1])
        assertEquals(PRootEnvTrampoline.CLEAN_ENV_FLAG, cmd.arguments[dd + 2])
        assertEquals("/bin/bash", cmd.arguments[cmd.arguments.size - 2])
        assertEquals("-i", cmd.arguments.last())
    }

    @Test fun `builds with fakeRoot flag`() {
        val cmd = PRootCommandBuilderImpl().build(
            PRootLaunchRequest(rootfs(), "/bin/sh", fakeRoot = true),
            AbsolutePath("/p"), AbsolutePath("/r"), AbsolutePath("/w")
        )
        assertTrue(cmd.arguments.contains("-0"))
    }

    @Test fun `builds with bind mounts`() {
        val req = PRootLaunchRequest(
            rootfs(), "/bin/sh",
            binds = listOf(PRootBind(AbsolutePath("/host/cache"), "/root/.cache"))
        )
        val cmd = PRootCommandBuilderImpl().build(req, AbsolutePath("/p"), AbsolutePath("/r"), AbsolutePath("/w"))
        assertTrue(cmd.arguments.contains("/host/cache:/root/.cache"))
    }

    @Test fun `builds with environment passthrough`() {
        val req = PRootLaunchRequest(
            rootfs(), "/bin/sh",
            // 显式带 PATH —— 免触发兜底注入，断言可以精确到逐元素
            environment = linkedMapOf("HOME" to "/root", "PATH" to "/usr/bin:/bin")
        )
        val cmd = PRootCommandBuilderImpl().build(req, AbsolutePath("/p"), AbsolutePath("/r"), AbsolutePath("/w"))
        // T88：env trampoline —— guest 命令变为 env -i HOME=/root PATH=… /bin/sh
        val dd = cmd.arguments.indexOf("--")
        assertTrue(dd > 0)
        assertEquals(
            listOf(
                PRootEnvTrampoline.ENV_EXECUTABLE, PRootEnvTrampoline.CLEAN_ENV_FLAG,
                "HOME=/root", "PATH=/usr/bin:/bin", "/bin/sh"
            ),
            cmd.arguments.subList(dd + 1, cmd.arguments.size)
        )
    }

    @Test fun `environment never uses proot -E flag (bundled 5_1_107 compatibility)`() {
        // T88 根因回归：捆绑 proot 5.1.107.92 的选项表没有 -E（proot-me/proot
        // 上游 master 也没有 —— 那是本仓库自造的 argv 形状）。一旦 -E 回归，
        // 设备上 bootstrap 立刻报 `proot error: unknown option '-E'`。
        val req = PRootLaunchRequest(
            rootfs(), "/bin/bash", listOf("-c", "echo hi"),
            environment = mapOf("TERM" to "xterm-256color", "HOME" to "/root")
        )
        val cmd = PRootCommandBuilderImpl().build(req, AbsolutePath("/p"), AbsolutePath("/r"), AbsolutePath("/w"))
        val argv = listOf(cmd.executable.value) + cmd.arguments
        assertTrue(
            "argv must not contain -E: $argv",
            PRootArgvContract.legacyIncompatibleFlags(argv).isEmpty()
        )
        assertTrue("env trampoline present", PRootArgvContract.hasEnvTrampoline(argv))
    }

    @Test fun `empty environment still injects PATH for env lookup`() {
        // env -i 后 guest 需要解析可执行名（如 apt-get）；无 PATH 的 guest
        // 连内建查找都失灵 —— 空 env 时 trampoline 兜底注入权威 PATH。
        val cmd = PRootCommandBuilderImpl().build(
            PRootLaunchRequest(rootfs(), "/bin/sh"),
            AbsolutePath("/p"), AbsolutePath("/r"), AbsolutePath("/w")
        )
        val dd = cmd.arguments.indexOf("--")
        assertTrue(
            "PATH fallback present",
            cmd.arguments.subList(dd + 1, cmd.arguments.size).any {
                it.startsWith("PATH=") && it.contains("/usr/bin")
            }
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid env key rejected by trampoline`() {
        // TM6 平移：key 含 '=' 或 '-' 前缀会被 /usr/bin/env 误解析，必须在构造层拒绝
        PRootEnvTrampoline.guestPrefix(mapOf("-BAD-KEY" to "x"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `env value with newline rejected by trampoline`() {
        PRootEnvTrampoline.guestPrefix(mapOf("TERM" to "xterm\nRESET"))
    }

    @Test fun `command separates executable and arguments`() {
        val cmd = PRootCommandBuilderImpl().build(
            PRootLaunchRequest(rootfs(), "/bin/bash", listOf("-c", "echo hi")),
            AbsolutePath("/p"), AbsolutePath("/r"), AbsolutePath("/w")
        )
        // proot is the executable; guest command lives after "--"
        val dd = cmd.arguments.indexOf("--")
        assertTrue(dd > 0)
        // T88: trampoline 前缀（env -i + PATH 兜底）在 executable 之前
        assertEquals(
            listOf("/bin/bash", "-c", "echo hi"),
            cmd.arguments.takeLast(3)
        )
        assertEquals(PRootEnvTrampoline.ENV_EXECUTABLE, cmd.arguments[dd + 1])
    }

    @Test fun `workspace always bound to slash workspace`() {
        val cmd = PRootCommandBuilderImpl().build(
            PRootLaunchRequest(rootfs(), "/bin/sh"),
            AbsolutePath("/p"), AbsolutePath("/r"), AbsolutePath("/data/ws")
        )
        assertTrue(cmd.arguments.contains("-b"))
        assertTrue(cmd.arguments.contains("/data/ws:/workspace"))
    }

    @Test fun `guest cwd maps workspace prefix to slash workspace`() {
        val builder = PRootCommandBuilderImpl()
        assertEquals("/workspace", builder.toGuestPath("workspace:/"))
        assertEquals("/workspace/foo", builder.toGuestPath("workspace:/foo"))
        assertEquals("/workspace/foo", builder.toGuestPath("workspace:foo"))
        assertEquals("/root", builder.toGuestPath("/root"))  // no prefix = guest absolute path
    }
}

class RootfsValidationErrorTest {
    @Test fun `all error types exist`() {
        // P68 contract surface still used by ProvisionedRootfsProvider fallback checks.
        assertEquals(10, RootfsValidationError.values().size)
    }

    @Test fun `RootfsValidation is immutable data class`() {
        val v = RootfsValidation(
            valid = true, architectureCompatible = true,
            hasRootDirectory = true, hasBin = true, hasEtc = true,
            hasUsr = true, hasHome = true, errors = emptyList()
        )
        assertTrue(v.valid)
        assertTrue(v.errors.isEmpty())
    }

    @Test fun `RootfsState covers lifecycle`() {
        // sanity: the descriptor state enum used by providers still exists
        assertTrue(RootfsState.values().contains(RootfsState.AVAILABLE))
    }
}
