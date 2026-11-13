package com.apex.agent.platform.terminal.chroot

import com.apex.agent.platform.terminal.runtime.SessionSpawnRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * T99 审计回归：chroot 后端显式 TerminalCreate 不经过 ensureReady，存量 rootfs 缺失
 * CA 必须在 prepare 阶段修复，否则 guest 会话内 https 仍然不可用。
 */
class ChrootCaRepairTest {

    @get:Rule val tmp = TemporaryFolder()

    private class RepairingProvider(private val path: String) : com.apex.agent.platform.terminal.linux.RootfsProvider {
        override suspend fun current() = com.apex.agent.platform.terminal.linux.RootfsDescriptor(
            id = "ubuntu-24.04",
            distribution = com.apex.agent.platform.terminal.linux.LinuxDistribution.UBUNTU,
            version = "24.04",
            architecture = com.apex.agent.platform.terminal.linux.CpuArchitecture.ARM64,
            location = com.apex.agent.platform.terminal.workspace.AbsolutePath(path),
            sizeBytes = null, checksum = null, readOnly = false
        )
        override suspend fun verify(rootfs: com.apex.agent.platform.terminal.linux.RootfsDescriptor) =
            Result.failure(com.apex.agent.platform.terminal.linux.RootfsVerification(rootfs, emptyList()))
    }

    @Test
    fun `prepare repairs missing CA on the live rootfs before returning a spawn spec`() = runBlocking {
        val rootfs = tmp.newFolder("rootfs")
        val provider = RepairingProvider(rootfs.absolutePath)
        var repairs = 0
        val su = File(tmp.root, "su").apply { writeText(""); setExecutable(true) }
        val backend = LinuxChrootBackend(
            suLocator = SuBinaryLocator { listOf(su) },
            rootfsProvider = provider,
            workspaces = com.apex.agent.platform.terminal.workspace.LinuxWorkspaceManager(
                File(tmp.root, "workspaces")
            ),
            userHome = com.apex.agent.platform.terminal.workspace.GuestUserHome(
                File(tmp.root, "home")
            ),
            caRepair = { root ->
                assertEquals(rootfs.absolutePath, root.absolutePath)
                repairs++
            }
        )
        backend.prepare(SessionSpawnRequest(cwd = "", rows = 24, cols = 80)).getOrThrow()
        assertEquals("explicit chroot create must trigger CA repair", 1, repairs)
    }

    @Test
    fun `prepare without rootfs never calls CA repair`() = runBlocking {
        var repairs = 0
        val su = File(tmp.root, "su").apply { writeText(""); setExecutable(true) }
        val backend = LinuxChrootBackend(
            suLocator = SuBinaryLocator { listOf(su) },
            rootfsProvider = object : com.apex.agent.platform.terminal.linux.RootfsProvider {
                override suspend fun current() = null
                override suspend fun verify(rootfs: com.apex.agent.platform.terminal.linux.RootfsDescriptor) =
                    Result.success(
                        com.apex.agent.platform.terminal.linux.RootfsVerification(
                            valid = false,
                            state = com.apex.agent.platform.terminal.linux.RootfsState.INVALID,
                            issues = emptyList()
                        )
                    )
            },
            workspaces = com.apex.agent.platform.terminal.workspace.LinuxWorkspaceManager(
                File(tmp.root, "workspaces")
            ),
            userHome = com.apex.agent.platform.terminal.workspace.GuestUserHome(
                File(tmp.root, "home")
            ),
            caRepair = { repairs++ }
        )
        backend.prepare(SessionSpawnRequest(cwd = "", rows = 24, cols = 80)).isFailure
        assertEquals(0, repairs)
    }
}
